const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const {
  relayUserId: relayAccountId,
  relayUserIdForAuthorization,
  shopCreditCents,
  creditShopOrder
} = require('./relay');
const { clientIp } = require('./client-ip');

const ORDER_STATUSES = ['pending', 'paid', 'delivered', 'cancelled'];
// Every order except a cancelled one consumes stock: pending/paid reserve it,
// delivered has shipped it. Only cancellation returns units to the shelf.
const SOLD_STATUSES = new Set(['pending', 'paid', 'delivered']);
const PAYMENT_METHODS = new Set(['alipay', 'wechat']);
const ORDER_NO_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const ORDER_NO_SUFFIX_LENGTH = 4;
const ORDER_NO_MAX_ATTEMPTS = 50;

function ok(data) {
  return { success: true, data, message: null };
}

function fail(status, message) {
  return { success: false, data: null, message };
}

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
  }
}

function httpError(status, message) {
  return new HttpError(status, message);
}

// Default paths resolve from the repository root, not the process cwd: PM2 may
// restart the backend from a different directory than the checkout root.
const REPO_ROOT = path.resolve(__dirname, '..', '..');

function catalogPath() {
  return process.env.SHOP_CATALOG_PATH || path.join(REPO_ROOT, 'shop', 'catalog.json');
}

function ordersPath() {
  return process.env.SHOP_DATA_PATH || path.join(REPO_ROOT, 'data', 'shop-orders.json');
}

// The catalog is re-read on every request so operators can edit it without a restart.
function readCatalog() {
  try {
    const raw = fs.readFileSync(catalogPath(), 'utf8');
    const catalog = JSON.parse(raw);
    if (!catalog || typeof catalog !== 'object' || !Array.isArray(catalog.products)) {
      throw new Error('目录结构缺少 products 数组');
    }
    return catalog;
  } catch (e) {
    throw httpError(500, `商品目录加载失败: ${e.message}`);
  }
}

function readOrders() {
  const file = ordersPath();
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    if (e.code === 'ENOENT') return [];
    throw httpError(500, '订单数据读取失败');
  }

  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || !Array.isArray(parsed.orders)) {
      throw new Error('invalid orders file shape');
    }
    // A structurally invalid record means the store is corrupt; match the
    // reference backend and refuse to serve or overwrite it.
    for (const order of parsed.orders) {
      if (
        !order ||
        typeof order !== 'object' ||
        Array.isArray(order) ||
        typeof order.orderNo !== 'string' ||
        !order.orderNo.trim()
      ) {
        throw new Error('invalid order record');
      }
    }
    return parsed.orders;
  } catch (_) {
    // Never overwrite a corrupt store; surface the problem to the caller instead.
    throw httpError(500, '订单数据读取失败');
  }
}

// Write to a temp file in the same directory, then rename over the target.
function writeOrders(orders) {
  const file = ordersPath();
  const dir = path.dirname(file);
  const tmp = path.join(
    dir,
    `.${path.basename(file)}.${process.pid}.${Date.now()}.${crypto.randomBytes(4).toString('hex')}.tmp`
  );

  try {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify({ orders }, null, 2), 'utf8');
    fs.renameSync(tmp, file);
  } catch (_) {
    try {
      fs.unlinkSync(tmp);
    } catch (_) {
      /* best effort cleanup */
    }
    throw httpError(500, '订单保存失败');
  }
}

function normalizeOrderNo(value) {
  return typeof value === 'string' ? value.trim().toUpperCase() : '';
}

function findOrderIndex(orders, orderNo) {
  const key = normalizeOrderNo(orderNo);
  if (!key) return -1;
  return orders.findIndex((order) => order && normalizeOrderNo(order.orderNo) === key);
}

function soldQuantityByProduct(orders) {
  const sold = new Map();
  for (const order of orders) {
    if (!order || !SOLD_STATUSES.has(order.status)) continue;
    const quantity = Number(order.quantity);
    if (!Number.isFinite(quantity) || quantity <= 0) continue;
    sold.set(order.productId, (sold.get(order.productId) || 0) + quantity);
  }
  return sold;
}

function usedQuantity(orders, productId) {
  return soldQuantityByProduct(orders).get(productId) || 0;
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const LOOKUP_TOKEN_PREFIX = 'so_';

function newLookupToken() {
  return LOOKUP_TOKEN_PREFIX + crypto.randomBytes(32).toString('hex');
}

function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function tokenHashMatches(storedHash, presentedToken) {
  if (typeof storedHash !== 'string' || !storedHash) return false;
  if (typeof presentedToken !== 'string' || !presentedToken.trim()) return false;
  const left = Buffer.from(storedHash, 'utf8');
  const right = Buffer.from(sha256Hex(presentedToken.trim()), 'utf8');
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

// A buyer may prove access with the one-time order credential or with the
// relay login that owns the fulfillment email. Missing and unauthorized orders
// intentionally share the same 404 so order numbers cannot be enumerated.
function canAccess(order, req) {
  if (!order) return false;
  if (tokenHashMatches(order.lookupTokenHash, req.get('x-order-token'))) {
    return true;
  }
  if (order.relayUserId) {
    const userId = relayUserIdForAuthorization(req.get('authorization'));
    if (userId && userId === order.relayUserId) return true;
  }
  return false;
}

function publicOrder(order) {
  if (!order || typeof order !== 'object') return order;
  const { lookupTokenHash, ...safe } = order;
  return safe;
}

// ---------------------------------------------------------------------------
// Per-client rate limiting
// ---------------------------------------------------------------------------

const RATE_WINDOW_MS = 60 * 1000;
const MAX_RATE_BUCKETS = 20000;
const RATE_LIMITS = {
  create: 10,
  lookup: 30,
  pay: 10,
  catalog: 120,
  admin: 60
};
const rateBuckets = new Map();

function sweepRateBuckets(now) {
  if (rateBuckets.size < MAX_RATE_BUCKETS) return;
  for (const [key, bucket] of rateBuckets) {
    if (now - bucket.start >= RATE_WINDOW_MS) rateBuckets.delete(key);
  }
  // Map iterates in insertion order, so drop the oldest live buckets if the
  // table is still full. The cap is a memory guard, not a limit on clients.
  for (const key of rateBuckets.keys()) {
    if (rateBuckets.size < MAX_RATE_BUCKETS) break;
    rateBuckets.delete(key);
  }
}

function rateLimit(kind) {
  const max = RATE_LIMITS[kind];
  return (req, res, next) => {
    const now = Date.now();
    const key = `${kind}|${clientIp(req)}`;
    let bucket = rateBuckets.get(key);
    if (!bucket || now - bucket.start >= RATE_WINDOW_MS) {
      bucket = { start: now, count: 0 };
      rateBuckets.set(key, bucket);
      sweepRateBuckets(now);
    }
    bucket.count += 1;
    if (bucket.count > max) {
      const retryAfter = Math.max(1, Math.ceil((bucket.start + RATE_WINDOW_MS - now) / 1000));
      res.set('Retry-After', String(retryAfter));
      res.set('Cache-Control', 'no-store');
      return res.status(429).json(fail(429, '请求过于频繁，请稍后再试'));
    }
    return next();
  };
}

// Credit the relay account behind an order exactly once. The relay side keeps
// a deterministic SHOP-<orderNo> top-up, so a retry after a partial failure
// returns the existing record instead of adding the balance twice.
function ensureRelayCredit(order) {
  if (!order || !order.relayPlanId || order.relayCreditedAt) return false;
  const amountCents = Math.round(Number(order.total) * 100);
  if (!Number.isInteger(amountCents) || amountCents < 0) {
    throw httpError(500, '订单金额不合法');
  }
  const topup = creditShopOrder(
    order.contact,
    order.relayPlanId,
    order.quantity,
    amountCents,
    order.orderNo,
    order.paymentMethod
  );
  order.relayCreditedAt = new Date().toISOString();
  order.relayTopupOrderNo = topup.orderNo;
  return true;
}

// Catalog responses carry live availability so the storefront never shows a
// product as buyable when outstanding orders have already consumed its stock.
function withAvailability(catalog, orders) {
  if (!Array.isArray(catalog.products)) return catalog;
  const sold = soldQuantityByProduct(orders);
  return {
    ...catalog,
    products: catalog.products.map((product) => {
      if (!product || typeof product !== 'object') return product;
      const stock = Number(product.stock);
      const total = Number.isFinite(stock) ? stock : 0;
      const used = sold.get(product.id) || 0;
      return { ...product, availableStock: Math.max(0, total - used) };
    })
  };
}

function roundMoney(value) {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

// Local time as YYYYMMDDHHmmss, matching the documented order-number format.
function formatLocalStamp(date) {
  return (
    String(date.getFullYear()) +
    pad2(date.getMonth() + 1) +
    pad2(date.getDate()) +
    pad2(date.getHours()) +
    pad2(date.getMinutes()) +
    pad2(date.getSeconds())
  );
}

function generateOrderNo(orders) {
  const stamp = formatLocalStamp(new Date());

  for (let attempt = 0; attempt < ORDER_NO_MAX_ATTEMPTS; attempt += 1) {
    let suffix = '';
    for (let i = 0; i < ORDER_NO_SUFFIX_LENGTH; i += 1) {
      suffix += ORDER_NO_ALPHABET[crypto.randomInt(ORDER_NO_ALPHABET.length)];
    }
    const orderNo = `PB${stamp}${suffix}`;
    if (findOrderIndex(orders, orderNo) === -1) return orderNo;
  }

  throw httpError(500, '订单号生成失败');
}

function bodyOf(req) {
  return req.body && typeof req.body === 'object' && !Array.isArray(req.body) ? req.body : {};
}

function handle(fn) {
  return (req, res, next) => {
    try {
      fn(req, res);
    } catch (e) {
      next(e);
    }
  };
}

function routerErrorHandler(err, _req, res, next) {
  if (res.headersSent) return next(err);
  if (!(err instanceof HttpError)) console.error(err && err.stack);
  const status = err && Number.isInteger(err.status) ? err.status : 500;
  const message = err && err.message ? err.message : '服务器内部错误';
  res.status(status).json(fail(status, message));
}

const shopRouter = express.Router();
const adminRouter = express.Router();

shopRouter.get(
  '/catalog',
  rateLimit('catalog'),
  handle((_req, res) => {
    const catalog = readCatalog();
    res.json(ok({
      ...withAvailability(catalog, readOrders()),
      allowSelfPay: process.env.SHOP_ALLOW_SELF_PAY !== 'false'
    }));
  })
);

shopRouter.post(
  '/orders',
  rateLimit('create'),
  handle((req, res) => {
    const body = bodyOf(req);

    const productId = typeof body.productId === 'string' ? body.productId.trim() : '';
    if (!productId) throw httpError(400, '商品不存在');

    const quantity = body.quantity;
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 99) {
      throw httpError(400, '购买数量不合法');
    }

    const contact = typeof body.contact === 'string' ? body.contact.trim() : '';
    if (contact.length < 5 || contact.length > 100) {
      throw httpError(400, '请填写有效的联系方式');
    }

    const rawNote = body.note;
    if (rawNote != null && typeof rawNote !== 'string') {
      throw httpError(400, '备注不合法');
    }
    const note = rawNote == null ? '' : rawNote.trim();
    if (note.length > 500) throw httpError(400, '备注过长');

    const catalog = readCatalog();
    const product = catalog.products.find((item) => item && item.id === productId);
    if (!product) throw httpError(400, '商品不存在');

    // Prices and stock always come from the catalog, never from the client.
    const unitPrice = typeof product.price === 'number' ? product.price : NaN;
    if (!Number.isFinite(unitPrice) || unitPrice < 0) {
      throw httpError(500, '商品目录加载失败: 商品价格不合法');
    }

    // Relay credit products are attributed to an existing relay account; the
    // email is the fulfillment target and must already be registered.
    const relayPlanId =
      typeof product.relayPlanId === 'string' ? product.relayPlanId.trim() : '';
    let normalizedContact = contact;
    let relayCreditCents = 0;
    let ownerUserId = null;
    if (relayPlanId) {
      normalizedContact = contact.toLowerCase();
      if (!EMAIL_RE.test(normalizedContact)) {
        throw httpError(400, '请填写中转站注册邮箱');
      }
      ownerUserId = relayAccountId(normalizedContact);
      if (!ownerUserId) {
        throw httpError(400, '该邮箱尚未注册中转站，请先注册');
      }
      relayCreditCents = shopCreditCents(relayPlanId, quantity);
    }

    const orders = readOrders();
    const stock = Number(product.stock);
    const available = (Number.isFinite(stock) ? stock : 0) - usedQuantity(orders, productId);
    if (quantity > available) {
      throw httpError(409, `库存不足，剩余 ${available} 件`);
    }

    const orderNo = generateOrderNo(orders);
    const lookupToken = newLookupToken();
    const order = {
      orderNo,
      productId: product.id,
      productName: product.name == null ? '' : String(product.name),
      unitPrice,
      quantity,
      total: roundMoney(unitPrice * quantity),
      contact: normalizedContact,
      note,
      status: 'pending',
      createdAt: new Date().toISOString(),
      lookupTokenHash: sha256Hex(lookupToken),
      ...(ownerUserId ? { relayUserId: ownerUserId } : {}),
      ...(relayPlanId ? { relayPlanId, relayCreditCents } : {})
    };

    orders.push(order);
    writeOrders(orders);
    res.json(ok({ ...publicOrder(order), lookupToken }));
  })
);

shopRouter.get(
  '/orders/:orderNo',
  rateLimit('lookup'),
  handle((req, res) => {
    const orders = readOrders();
    const index = findOrderIndex(orders, req.params.orderNo);
    if (index === -1 || !canAccess(orders[index], req)) {
      throw httpError(404, '订单不存在');
    }
    res.json(ok(publicOrder(orders[index])));
  })
);

shopRouter.post(
  '/orders/:orderNo/pay',
  rateLimit('pay'),
  handle((req, res) => {
    if (process.env.SHOP_ALLOW_SELF_PAY === 'false') {
      throw httpError(403, '请支付后联系管理员确认到账');
    }
    const body = bodyOf(req);
    const method = typeof body.method === 'string' ? body.method.trim() : '';

    const orders = readOrders();
    const index = findOrderIndex(orders, req.params.orderNo);
    if (index === -1 || !canAccess(orders[index], req)) {
      throw httpError(404, '订单不存在');
    }

    const order = orders[index];
    if (order.status === 'paid' || order.status === 'delivered') {
      // Paying twice is a no-op and must not rewrite paidAt/paymentMethod.
      if (ensureRelayCredit(order)) writeOrders(orders);
      return res.json(ok(publicOrder(order)));
    }
    if (order.status === 'cancelled') {
      throw httpError(409, '订单已取消');
    }

    order.status = 'paid';
    order.paidAt = new Date().toISOString();
    if (PAYMENT_METHODS.has(method)) order.paymentMethod = method;
    ensureRelayCredit(order);

    writeOrders(orders);
    res.json(ok(publicOrder(order)));
  })
);

function adminOnly(req, res, next) {
  const token = process.env.BLOG_ADMIN_TOKEN || '';
  if (token && req.get('x-admin-token') !== token) {
    return res.status(401).json(fail(401, 'Invalid admin token'));
  }
  next();
}

function createdAtMs(order) {
  const value = order ? Date.parse(order.createdAt) : NaN;
  return Number.isFinite(value) ? value : 0;
}

adminRouter.get(
  '/orders',
  rateLimit('admin'),
  adminOnly,
  handle((req, res) => {
    const status = typeof req.query.status === 'string' ? req.query.status.trim() : '';
    const parsedLimit = Number.parseInt(req.query.limit, 10);
    const limit = Number.isFinite(parsedLimit) && parsedLimit > 0 ? Math.min(parsedLimit, 500) : 100;

    const orders = readOrders();
    const matching = status ? orders.filter((order) => order && order.status === status) : orders.slice();
    matching.sort((a, b) => createdAtMs(b) - createdAtMs(a));

    res.json(ok({ list: matching.slice(0, limit).map(publicOrder), total: matching.length }));
  })
);

adminRouter.post(
  '/orders/:orderNo/status',
  rateLimit('admin'),
  adminOnly,
  handle((req, res) => {
    const body = bodyOf(req);
    const status = typeof body.status === 'string' ? body.status.trim() : '';
    if (!ORDER_STATUSES.includes(status)) {
      throw httpError(400, '订单状态不合法');
    }

    const orders = readOrders();
    const index = findOrderIndex(orders, req.params.orderNo);
    if (index === -1) throw httpError(404, '订单不存在');

    const order = orders[index];
    if (status === 'cancelled' && order.relayCreditedAt) {
      throw httpError(409, '已到账订单不能取消，请联系管理员处理退款');
    }
    order.status = status;
    if ((status === 'paid' || status === 'delivered') && !order.paidAt) {
      order.paidAt = new Date().toISOString();
    }
    if (status === 'delivered') {
      order.deliveredAt = new Date().toISOString();
    } else {
      delete order.deliveredAt;
    }
    if (status === 'cancelled') {
      order.cancelledAt = new Date().toISOString();
    } else {
      delete order.cancelledAt;
    }
    if (status === 'paid' || status === 'delivered') {
      ensureRelayCredit(order);
    }

    writeOrders(orders);
    res.json(ok(publicOrder(order)));
  })
);

shopRouter.use(routerErrorHandler);
adminRouter.use(routerErrorHandler);

module.exports = { shopRouter, adminRouter };
