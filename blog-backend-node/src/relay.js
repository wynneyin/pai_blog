const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const http = require('http');
const https = require('https');
const { StringDecoder } = require('string_decoder');

// Relay store layout and business rules follow shop/relay-API.md. Every value
// that leaves this module goes through one of the public mappers below so
// password hashes, salts, and key hashes never escape.

const REPO_ROOT = path.resolve(__dirname, '..', '..');

const PBKDF2_ITERATIONS = 120000;
const PBKDF2_SALT_BYTES = 16;
const PBKDF2_HASH_BYTES = 32;
const SESSION_TOKEN_PREFIX = 'rs_';
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const KEY_PREFIX = 'sk-relay-';
const KEY_PREFIX_LENGTH = 16;
const MAX_ACTIVE_KEYS = 10;
// Retention caps so one account cannot grow relay-store.json without bound.
// Revoked keys and finished top-ups are pruned oldest-first; pending records
// are capped at creation time.
const MAX_KEYS_PER_USER = 50;
const MAX_SESSIONS_PER_USER = 20;
const MAX_PENDING_TOPUPS_PER_USER = 10;
const MAX_TOPUPS_PER_USER = 200;
const MAX_TOPUP_LIST = 100;
const ORDER_NO_PREFIX = 'RT';
const ORDER_NO_SUFFIX_LENGTH = 4;
const ORDER_NO_MAX_ATTEMPTS = 50;
// Uppercase alphanumerics only, whatever the configured alphabet looked like.
const ORDER_NO_ALPHABET =
  'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'.replace(/[^A-Z0-9]/g, '') || 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
const STORE_COLLECTIONS = ['users', 'sessions', 'keys', 'topups', 'usage'];
const PAYMENT_METHODS = new Set(['alipay', 'wechat']);
const AUTH_RATE_WINDOW_MS = 60 * 1000;
const AUTH_RATE_MAX = 30;
const CREATE_RATE_WINDOW_MS = 60 * 1000;
const TOPUP_CREATE_RATE_MAX = 20;
const KEY_CREATE_RATE_MAX = 10;
const UPSTREAM_TIMEOUT_MS = 120 * 1000;
// Production relies on an administrator confirming the transfer; the
// self-service "I have paid" confirmation is only for local demonstrations.
const ALLOW_SELF_TOPUP = process.env.RELAY_ALLOW_SELF_TOPUP !== 'false';
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const EMAIL_MAX_LENGTH = 120;
const KEY_NAME_MAX_LENGTH = 40;
const DEFAULT_KEY_NAME = '默认令牌';

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

function dataPath() {
  return process.env.RELAY_DATA_PATH || path.join(REPO_ROOT, 'data', 'relay-store.json');
}

function catalogPath() {
  return process.env.RELAY_CATALOG_PATH || path.join(REPO_ROOT, 'shop', 'relay.json');
}

function nowIso() {
  return new Date().toISOString();
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

// Local time as YYYYMMDDHHmmss, matching the documented top-up order format.
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

function randomHex(bytes) {
  return crypto.randomBytes(bytes).toString('hex');
}

function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function uniqueId(records, prefix) {
  for (;;) {
    const id = prefix + randomHex(6);
    if (!records.some((record) => record && record.id === id)) return id;
  }
}

function bodyOf(req) {
  return req.body && typeof req.body === 'object' && !Array.isArray(req.body) ? req.body : {};
}

function createdAtMs(record) {
  const value = record ? Date.parse(record.createdAt) : NaN;
  return Number.isFinite(value) ? value : 0;
}

function byCreatedAtDesc(a, b) {
  return createdAtMs(b) - createdAtMs(a);
}

function limitFrom(raw, fallback, max) {
  if (typeof raw !== 'string') return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed) || parsed <= 0) return fallback;
  return Math.min(parsed, max);
}

function bearerToken(req) {
  const header = req.get('authorization') || '';
  const match = /^Bearer\s+(.+)$/i.exec(String(header).trim());
  return match ? match[1].trim() : '';
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

function emptyStore() {
  return { users: [], sessions: [], keys: [], topups: [], usage: [] };
}

// A missing file means "no accounts yet". Anything unreadable or structurally
// wrong is a hard error: the corrupt file must never be replaced by a write.
function readStore() {
  let raw;
  try {
    raw = fs.readFileSync(dataPath(), 'utf8');
  } catch (e) {
    if (e && e.code === 'ENOENT') return emptyStore();
    throw httpError(500, '账户数据读取失败');
  }

  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('invalid store shape');
    }
    for (const name of STORE_COLLECTIONS) {
      if (parsed[name] === undefined) {
        parsed[name] = [];
        continue;
      }
      if (!Array.isArray(parsed[name])) throw new Error('invalid collection');
      for (const record of parsed[name]) {
        if (!record || typeof record !== 'object' || Array.isArray(record)) {
          throw new Error('invalid record');
        }
      }
    }
    return parsed;
  } catch (_) {
    throw httpError(500, '账户数据读取失败');
  }
}

// Same-directory temp file + rename, so readers never observe a partial write.
// Expired sessions are dropped on the way out so the file cannot accumulate
// dead login records forever.
function writeStore(store) {
  const file = dataPath();
  const dir = path.dirname(file);
  const now = Date.now();
  store.sessions = store.sessions.filter(
    (session) => session && Date.parse(session.expiresAt) > now
  );
  const tmp = path.join(
    dir,
    `.${path.basename(file)}.${process.pid}.${Date.now()}.${crypto.randomBytes(4).toString('hex')}.tmp`
  );

  try {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(tmp, JSON.stringify(store, null, 2), 'utf8');
    fs.renameSync(tmp, file);
  } catch (_) {
    try {
      fs.unlinkSync(tmp);
    } catch (_) {
      /* best effort cleanup */
    }
    throw httpError(500, '账户数据保存失败');
  }
}

// All balance-affecting work goes through this queue: read -> mutate -> atomic
// write happens as one unit, and async proxy charges cannot interleave with it.
let storeQueue = Promise.resolve();

function withStoreLock(task) {
  const result = storeQueue.then(task, task);
  storeQueue = result.then(
    () => undefined,
    () => undefined
  );
  return result;
}

function findUser(store, userId) {
  return store.users.find((user) => user && user.id === userId) || null;
}

function findKey(store, keyId) {
  return store.keys.find((key) => key && key.id === keyId) || null;
}

function findTopup(store, orderNo) {
  return store.topups.find((order) => order && order.orderNo === orderNo) || null;
}

// Retention: keep the newest MAX_TOPUPS_PER_USER orders per account and drop
// the oldest finished ones. Pending orders are never dropped here; their count
// is capped when a new order is created.
function pruneUserTopups(store, userId) {
  const mine = store.topups.filter((order) => order && order.userId === userId);
  const excess = mine.length - MAX_TOPUPS_PER_USER;
  if (excess <= 0) return;
  const removable = mine
    .filter((order) => order.status === 'paid' || order.status === 'cancelled')
    .sort((a, b) => createdAtMs(a) - createdAtMs(b))
    .slice(0, excess);
  if (!removable.length) return;
  const drop = new Set(removable.map((order) => order.orderNo));
  store.topups = store.topups.filter((order) => !order || !drop.has(order.orderNo));
}

// Same idea for keys: create + revoke churn would otherwise keep appending
// revoked records forever. Active keys are still limited by MAX_ACTIVE_KEYS.
function pruneUserKeys(store, userId) {
  const mine = store.keys.filter((key) => key && key.userId === userId);
  const excess = mine.length - MAX_KEYS_PER_USER;
  if (excess <= 0) return;
  const removable = mine
    .filter((key) => key.revokedAt)
    .sort((a, b) => createdAtMs(a) - createdAtMs(b))
    .slice(0, excess);
  if (!removable.length) return;
  const drop = new Set(removable.map((key) => key.id));
  store.keys = store.keys.filter((key) => !key || !drop.has(key.id));
}

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

function catalogError(reason) {
  return httpError(500, `中转目录加载失败: ${reason}`);
}

function readCatalog() {
  let raw;
  try {
    raw = fs.readFileSync(catalogPath(), 'utf8');
  } catch (e) {
    throw catalogError(e && e.message ? e.message : '目录文件读取失败');
  }

  let catalog;
  try {
    catalog = JSON.parse(raw);
  } catch (e) {
    throw catalogError(e && e.message ? e.message : '目录 JSON 解析失败');
  }

  if (!catalog || typeof catalog !== 'object' || Array.isArray(catalog)) {
    throw catalogError('目录结构不合法');
  }
  if (!Array.isArray(catalog.models)) throw catalogError('models 必须是数组');
  if (!Array.isArray(catalog.plans)) throw catalogError('plans 必须是数组');

  const modelNames = new Set();
  for (const model of catalog.models) {
    if (!model || typeof model !== 'object' || Array.isArray(model)) {
      throw catalogError('模型配置不合法');
    }
    const name = typeof model.name === 'string' ? model.name.trim() : '';
    if (!name) throw catalogError('模型名称不能为空');
    if (modelNames.has(name)) throw catalogError(`模型名称重复: ${name}`);
    modelNames.add(name);
    if (typeof model.input !== 'number' || !Number.isFinite(model.input) || model.input < 0) {
      throw catalogError(`模型输入价格不合法: ${name}`);
    }
    if (typeof model.output !== 'number' || !Number.isFinite(model.output) || model.output < 0) {
      throw catalogError(`模型输出价格不合法: ${name}`);
    }
  }

  const planIds = new Set();
  for (const plan of catalog.plans) {
    if (!plan || typeof plan !== 'object' || Array.isArray(plan)) {
      throw catalogError('充值档位配置不合法');
    }
    const id = typeof plan.id === 'string' ? plan.id.trim() : '';
    if (!id) throw catalogError('充值档位 ID 不能为空');
    if (planIds.has(id)) throw catalogError(`充值档位 ID 重复: ${id}`);
    planIds.add(id);
    if (typeof plan.price !== 'number' || !Number.isFinite(plan.price) || plan.price < 0) {
      throw catalogError(`充值档位价格不合法: ${id}`);
    }
    if (typeof plan.credit !== 'number' || !Number.isFinite(plan.credit) || plan.credit < 0) {
      throw catalogError(`充值档位额度不合法: ${id}`);
    }
    if (plan.credit < plan.price) {
      throw catalogError(`充值档位额度不能低于价格: ${id}`);
    }
  }

  return catalog;
}

// ---------------------------------------------------------------------------
// Public representations
// ---------------------------------------------------------------------------

function publicUser(user) {
  return {
    id: user.id,
    email: user.email,
    balanceCents: Number.isFinite(user.balanceCents) ? user.balanceCents : 0,
    createdAt: user.createdAt
  };
}

function publicKeyRecord(key) {
  return {
    id: key.id,
    prefix: key.prefix,
    name: key.name,
    createdAt: key.createdAt,
    lastUsedAt: key.lastUsedAt == null ? null : key.lastUsedAt,
    revokedAt: key.revokedAt == null ? null : key.revokedAt
  };
}

// ---------------------------------------------------------------------------
// Passwords and sessions
// ---------------------------------------------------------------------------

function normalizeEmail(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

function hashPassword(password, saltHex) {
  return crypto
    .pbkdf2Sync(Buffer.from(password, 'utf8'), Buffer.from(saltHex, 'hex'), PBKDF2_ITERATIONS, PBKDF2_HASH_BYTES, 'sha256')
    .toString('hex');
}

function verifyPassword(password, saltHex, expectedHex) {
  const expected = Buffer.from(String(expectedHex || ''), 'hex');
  const actual = Buffer.from(hashPassword(password, saltHex), 'hex');
  if (expected.length !== actual.length || expected.length === 0) return false;
  try {
    return crypto.timingSafeEqual(expected, actual);
  } catch (_) {
    return false;
  }
}

function createSessionRecord(store, userId) {
  const token = SESSION_TOKEN_PREFIX + randomHex(24);
  const createdAt = nowIso();
  const mine = store.sessions.filter((session) => session && session.userId === userId);
  if (mine.length >= MAX_SESSIONS_PER_USER) {
    const drop = new Set(
      mine
        .sort(byCreatedAtDesc)
        .slice(MAX_SESSIONS_PER_USER - 1)
        .map((session) => session.tokenHash)
    );
    store.sessions = store.sessions.filter(
      (session) => !session || !drop.has(session.tokenHash)
    );
  }
  store.sessions.push({
    tokenHash: sha256Hex(token),
    userId,
    createdAt,
    expiresAt: new Date(Date.now() + SESSION_TTL_MS).toISOString()
  });
  return token;
}

function activeSessionUser(store, token) {
  if (!token) return null;
  const tokenHash = sha256Hex(token);
  const now = Date.now();
  const session = store.sessions.find(
    (item) => item && item.tokenHash === tokenHash && Date.parse(item.expiresAt) > now
  );
  if (!session) return null;
  const user = findUser(store, session.userId);
  if (!user) return null;
  return { session, user };
}

// ---------------------------------------------------------------------------
// Rate limiting (login/register, per client IP)
// ---------------------------------------------------------------------------

const authRateBuckets = new Map();

function clientIp(req) {
  return req.ip || (req.socket && req.socket.remoteAddress) || 'unknown';
}

function sweepAuthBuckets(now) {
  if (authRateBuckets.size < 5000) return;
  for (const [ip, bucket] of authRateBuckets) {
    if (now - bucket.start >= AUTH_RATE_WINDOW_MS) authRateBuckets.delete(ip);
  }
}

function authRateLimit(req, res, next) {
  const now = Date.now();
  const ip = clientIp(req);
  let bucket = authRateBuckets.get(ip);
  if (!bucket || now - bucket.start >= AUTH_RATE_WINDOW_MS) {
    bucket = { start: now, count: 0 };
    authRateBuckets.set(ip, bucket);
    sweepAuthBuckets(now);
  }
  bucket.count += 1;
  if (bucket.count > AUTH_RATE_MAX) {
    return res.status(429).json(fail(429, '请求过于频繁，请稍后再试'));
  }
  return next();
}

// Per-account throttle for record-creating endpoints. Combined with the
// retention caps below it bounds how fast (and how large) one account can grow
// the store, without slowing down normal console use.
const createRateBuckets = new Map();

function createRateLimited(scope, userId, max) {
  const now = Date.now();
  const bucketKey = `${scope}:${userId}`;
  let bucket = createRateBuckets.get(bucketKey);
  if (!bucket || now - bucket.start >= CREATE_RATE_WINDOW_MS) {
    bucket = { start: now, count: 0 };
    createRateBuckets.set(bucketKey, bucket);
  }
  bucket.count += 1;
  if (createRateBuckets.size > 5000) {
    for (const [key, item] of createRateBuckets) {
      if (now - item.start >= CREATE_RATE_WINDOW_MS) createRateBuckets.delete(key);
    }
  }
  return bucket.count > max;
}

// ---------------------------------------------------------------------------
// Key/token generation
// ---------------------------------------------------------------------------

function generateApiKey() {
  return KEY_PREFIX + randomHex(24);
}

function generateTopupOrderNo(store) {
  const stamp = formatLocalStamp(new Date());
  for (let attempt = 0; attempt < ORDER_NO_MAX_ATTEMPTS; attempt += 1) {
    let suffix = '';
    for (let i = 0; i < ORDER_NO_SUFFIX_LENGTH; i += 1) {
      suffix += ORDER_NO_ALPHABET[crypto.randomInt(ORDER_NO_ALPHABET.length)];
    }
    const orderNo = `${ORDER_NO_PREFIX}${stamp}${suffix}`;
    if (!findTopup(store, orderNo)) return orderNo;
  }
  throw httpError(500, '充值单号生成失败');
}

// ---------------------------------------------------------------------------
// Billing
// ---------------------------------------------------------------------------

// Prices in the catalog are yuan per million tokens; billing is integer cents.
function computeCostCents(model, promptTokens, completionTokens) {
  const inputCentsPerMillion = Math.round(model.input * 100);
  const outputCentsPerMillion = Math.round(model.output * 100);
  const numerator = promptTokens * inputCentsPerMillion + completionTokens * outputCentsPerMillion;
  if (!(numerator > 0)) return 0;
  return Math.max(1, Math.ceil(numerator / 1000000));
}

function usageFromPayload(payload) {
  const usage = payload && typeof payload === 'object' ? payload.usage : null;
  if (!usage || typeof usage !== 'object') return null;
  const promptTokens = Number(usage.prompt_tokens);
  const completionTokens = Number(usage.completion_tokens);
  if (!Number.isFinite(promptTokens) || !Number.isFinite(completionTokens)) return null;
  if (promptTokens < 0 || completionTokens < 0) return null;
  return { promptTokens, completionTokens };
}

// Runs inside the store lock: deduct (clamped at zero), record usage, and mark
// the key as used in the same atomic write.
function recordUsage(userId, keyId, modelName, usage, costCents) {
  return withStoreLock(() => {
    const store = readStore();
    const user = findUser(store, userId);
    if (!user) return;
    const balance = Number.isFinite(user.balanceCents) ? user.balanceCents : 0;
    user.balanceCents = Math.max(0, balance - costCents);
    const key = findKey(store, keyId);
    if (key) key.lastUsedAt = nowIso();
    store.usage.push({
      id: uniqueId(store.usage, 'g_'),
      userId,
      keyId,
      model: modelName,
      promptTokens: usage.promptTokens,
      completionTokens: usage.completionTokens,
      costCents,
      createdAt: nowIso()
    });
    writeStore(store);
  });
}

// ---------------------------------------------------------------------------
// OpenAI-shaped proxy errors
// ---------------------------------------------------------------------------

function sendOpenAIError(res, status, message, type, code) {
  res.status(status).json({ error: { message, type, code } });
}

function relayKeyAuth(req) {
  const token = bearerToken(req);
  if (!token) return null;
  const store = readStore();
  const tokenHash = sha256Hex(token);
  const key = store.keys.find((item) => item && item.keyHash === tokenHash && !item.revokedAt);
  if (!key) return null;
  const user = findUser(store, key.userId);
  if (!user) return null;
  return { store, key, user };
}

function upstreamBaseUrl() {
  return (process.env.RELAY_UPSTREAM_URL || '').trim().replace(/\/+$/, '');
}

function upstreamHeaders(bodyText) {
  const headers = {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(bodyText)
  };
  const upstreamKey = process.env.RELAY_UPSTREAM_KEY || '';
  if (upstreamKey) headers.authorization = `Bearer ${upstreamKey}`;
  return headers;
}

function recordUsageSafely(userId, keyId, modelName, usage, costCents) {
  return recordUsage(userId, keyId, modelName, usage, costCents).catch(() => {
    // A failed charge must never break a response that is already on the wire.
  });
}

function handleStreamingResponse(upRes, res, context) {
  const { userId, keyId, model } = context;
  res.status(upRes.statusCode || 200);
  const contentType = upRes.headers['content-type'];
  if (contentType) res.set('content-type', contentType);
  if (typeof res.flushHeaders === 'function') res.flushHeaders();

  const decoder = new StringDecoder('utf8');
  let pending = '';
  let lastUsage = null;
  let settlePromise = null;

  const scanLine = (line) => {
    const trimmed = line.trim();
    if (!trimmed.startsWith('data:')) return;
    const payload = trimmed.slice(5).trim();
    if (!payload || payload === '[DONE]') return;
    try {
      const usage = usageFromPayload(JSON.parse(payload));
      if (usage) lastUsage = usage;
    } catch (_) {
      /* ignore non-JSON keep-alive data */
    }
  };

  // Charge the usage seen so far exactly once. Unlike a naive end-of-stream
  // hook this also runs when the client hangs up mid-stream: reading the answer
  // and then closing the socket must still cost money. Mirrors the Java proxy,
  // which bills from a finally block.
  const settle = () => {
    if (settlePromise) return settlePromise;
    try {
      pending += decoder.end();
      if (pending) scanLine(pending.replace(/\r$/, ''));
    } catch (_) {
      /* decoder already finished or the stream was torn down */
    }
    pending = '';
    if (lastUsage) {
      const usage = lastUsage;
      const costCents = computeCostCents(model, usage.promptTokens, usage.completionTokens);
      settlePromise = recordUsageSafely(userId, keyId, model.name, usage, costCents);
    } else {
      settlePromise = Promise.resolve();
    }
    return settlePromise;
  };

  upRes.on('data', (chunk) => {
    if (settlePromise) return;
    let canContinue = true;
    let clientGone = false;
    try {
      canContinue = res.write(chunk);
    } catch (_) {
      /* client went away */
      clientGone = true;
      canContinue = false;
    }
    pending += decoder.write(chunk);
    let index = pending.indexOf('\n');
    while (index !== -1) {
      const line = pending.slice(0, index).replace(/\r$/, '');
      pending = pending.slice(index + 1);
      scanLine(line);
      index = pending.indexOf('\n');
    }
    if (!canContinue) {
      if (clientGone || res.destroyed || res.writableEnded) {
        // Nobody is reading any more: charge what was scanned and stop.
        settle();
        try {
          upRes.destroy();
        } catch (_) {
          /* already closed */
        }
        return;
      }
      // Honour client backpressure instead of buffering the whole upstream
      // stream in this process.
      upRes.pause();
      res.once('drain', () => {
        if (!settlePromise) upRes.resume();
      });
    }
  });

  upRes.on('end', async () => {
    await settle();
    try {
      res.end();
    } catch (_) {
      /* client went away */
    }
  });

  upRes.on('error', () => {
    settle();
    try {
      res.destroy();
    } catch (_) {
      /* already closed */
    }
  });

  res.on('close', () => {
    if (!upRes.complete) {
      try {
        upRes.destroy();
      } catch (_) {
        /* already closed */
      }
    }
    settle();
  });
}

function handleBufferedResponse(upRes, res, context) {
  const { userId, keyId, model } = context;
  const statusCode = upRes.statusCode || 200;
  const contentType = upRes.headers['content-type'];
  const chunks = [];

  upRes.on('data', (chunk) => chunks.push(chunk));

  upRes.on('error', () => {
    if (!res.headersSent) {
      sendOpenAIError(res, 502, '上游请求失败', 'server_error', 'upstream_error');
    } else {
      try {
        res.destroy();
      } catch (_) {
        /* already closed */
      }
    }
  });

  upRes.on('end', async () => {
    const buffer = Buffer.concat(chunks);
    let usage = null;
    try {
      usage = usageFromPayload(JSON.parse(buffer.toString('utf8')));
    } catch (_) {
      /* upstream returned a 2xx with a non-JSON body; pass it through untouched */
    }

    let costCents = null;
    if (usage) {
      costCents = computeCostCents(model, usage.promptTokens, usage.completionTokens);
      await recordUsageSafely(userId, keyId, model.name, usage, costCents);
    }

    res.status(statusCode);
    if (contentType) res.set('content-type', contentType);
    if (costCents != null) res.set('X-Relay-Cost-Cents', String(costCents));
    res.end(buffer);
  });
}

function handleUpstreamResponse(upRes, res, context) {
  const statusCode = upRes.statusCode || 502;
  if (statusCode < 200 || statusCode >= 300) {
    // Non-2xx responses are passed through and never billed.
    res.status(statusCode);
    const contentType = upRes.headers['content-type'];
    if (contentType) res.set('content-type', contentType);
    upRes.on('error', () => {
      try {
        res.destroy();
      } catch (_) {
        /* already closed */
      }
    });
    res.on('close', () => {
      if (!upRes.complete) {
        try {
          upRes.destroy();
        } catch (_) {
          /* already closed */
        }
      }
    });
    upRes.pipe(res);
    return;
  }
  if (context.stream) {
    handleStreamingResponse(upRes, res, context);
  } else {
    handleBufferedResponse(upRes, res, context);
  }
}

function forwardChatCompletion({ base, bodyText, stream, userId, keyId, model, res }) {
  let url;
  try {
    url = new URL(`${base}/chat/completions`);
  } catch (_) {
    return sendOpenAIError(res, 502, '上游请求失败', 'server_error', 'upstream_error');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return sendOpenAIError(res, 502, '上游请求失败', 'server_error', 'upstream_error');
  }

  const transport = url.protocol === 'https:' ? https : http;
  const upstreamReq = transport.request(
    {
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port || undefined,
      path: `${url.pathname}${url.search}`,
      method: 'POST',
      headers: upstreamHeaders(bodyText)
    },
    (upRes) => handleUpstreamResponse(upRes, res, { stream, userId, keyId, model })
  );

  upstreamReq.on('error', () => {
    if (!res.headersSent) {
      sendOpenAIError(res, 502, '上游请求失败', 'server_error', 'upstream_error');
    } else {
      try {
        res.destroy();
      } catch (_) {
        /* already closed */
      }
    }
  });

  upstreamReq.setTimeout(UPSTREAM_TIMEOUT_MS, () => {
    upstreamReq.destroy(new Error('upstream timeout'));
  });

  upstreamReq.end(bodyText);
  return undefined;
}

// ---------------------------------------------------------------------------
// Routing helpers
// ---------------------------------------------------------------------------

function handle(fn) {
  return (req, res, next) => {
    Promise.resolve()
      .then(() => fn(req, res))
      .catch(next);
  };
}

function routerErrorHandler(err, _req, res, next) {
  if (res.headersSent) return next(err);
  const status = err && Number.isInteger(err.status) ? err.status : 500;
  const message = err && err.message ? err.message : '服务器内部错误';
  res.status(status).json(fail(status, message));
}

function adminOnly(req, res, next) {
  const token = process.env.BLOG_ADMIN_TOKEN || '';
  if (token && req.get('x-admin-token') !== token) {
    return res.status(401).json(fail(401, 'Invalid admin token'));
  }
  return next();
}

function requireUser(req, res, next) {
  const token = bearerToken(req);
  if (!token) throw httpError(401, '登录已失效，请重新登录');
  const store = readStore();
  const found = activeSessionUser(store, token);
  if (!found) throw httpError(401, '登录已失效，请重新登录');
  req.relayUserId = found.user.id;
  req.relayTokenHash = found.session.tokenHash;
  return next();
}

const relayRouter = express.Router();
const relayAdminRouter = express.Router();
const relayProxyRouter = express.Router();

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

relayRouter.get(
  '/catalog',
  handle((_req, res) => {
    res.json(ok(readCatalog()));
  })
);

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

relayRouter.post(
  '/auth/register',
  authRateLimit,
  handle(async (req, res) => {
    const body = bodyOf(req);
    const email = normalizeEmail(body.email);
    if (!email || email.length > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.test(email)) {
      throw httpError(400, '邮箱格式不正确');
    }
    const password = body.password;
    if (typeof password !== 'string' || password.length < 8 || password.length > 72) {
      throw httpError(400, '密码长度需为 8-72 位');
    }

    const salt = randomHex(PBKDF2_SALT_BYTES);
    const passwordHash = hashPassword(password, salt);

    const result = await withStoreLock(() => {
      const store = readStore();
      if (store.users.some((user) => user && normalizeEmail(user.email) === email)) {
        throw httpError(400, '该邮箱已注册');
      }
      const user = {
        id: uniqueId(store.users, 'u_'),
        email,
        passwordSalt: salt,
        passwordHash,
        balanceCents: 0,
        disabled: false,
        createdAt: nowIso()
      };
      store.users.push(user);
      const token = createSessionRecord(store, user.id);
      writeStore(store);
      return { token, user };
    });

    res.json(ok({ token: result.token, user: publicUser(result.user) }));
  })
);

relayRouter.post(
  '/auth/login',
  authRateLimit,
  handle(async (req, res) => {
    const body = bodyOf(req);
    const email = normalizeEmail(body.email);
    const password = typeof body.password === 'string' ? body.password : '';

    const result = await withStoreLock(() => {
      const store = readStore();
      const user = store.users.find((item) => item && normalizeEmail(item.email) === email);
      if (!user || !password || !verifyPassword(password, user.passwordSalt, user.passwordHash)) {
        throw httpError(401, '邮箱或密码错误');
      }
      const token = createSessionRecord(store, user.id);
      writeStore(store);
      return { token, user };
    });

    res.json(ok({ token: result.token, user: publicUser(result.user) }));
  })
);

relayRouter.post(
  '/auth/logout',
  handle(async (req, res) => {
    const token = bearerToken(req);
    if (token) {
      const tokenHash = sha256Hex(token);
      await withStoreLock(() => {
        const store = readStore();
        const index = store.sessions.findIndex((session) => session && session.tokenHash === tokenHash);
        if (index === -1) return;
        store.sessions.splice(index, 1);
        writeStore(store);
      });
    }
    res.json(ok({ ok: true }));
  })
);

relayRouter.get(
  '/me',
  handle((req, res) => {
    const found = activeSessionUser(readStore(), bearerToken(req));
    if (!found) throw httpError(401, '登录已失效，请重新登录');
    res.json(ok({ user: publicUser(found.user) }));
  })
);

// ---------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------

relayRouter.get(
  '/keys',
  requireUser,
  handle((req, res) => {
    const store = readStore();
    const list = store.keys
      .filter((key) => key && key.userId === req.relayUserId)
      .sort(byCreatedAtDesc)
      .map(publicKeyRecord);
    res.json(ok({ list }));
  })
);

relayRouter.post(
  '/keys',
  requireUser,
  handle(async (req, res) => {
    const body = bodyOf(req);
    const rawName = body.name;
    if (rawName != null && typeof rawName !== 'string') {
      throw httpError(400, '备注不合法');
    }
    let name = rawName == null ? '' : rawName.trim();
    if (!name) name = DEFAULT_KEY_NAME;
    name = name.slice(0, KEY_NAME_MAX_LENGTH);

    if (createRateLimited('keys', req.relayUserId, KEY_CREATE_RATE_MAX)) {
      throw httpError(429, '请求过于频繁，请稍后再试');
    }

    const result = await withStoreLock(() => {
      const store = readStore();
      const userId = req.relayUserId;
      if (!findUser(store, userId)) throw httpError(401, '登录已失效，请重新登录');
      const activeCount = store.keys.filter((key) => key && key.userId === userId && !key.revokedAt).length;
      if (activeCount >= MAX_ACTIVE_KEYS) {
        throw httpError(400, '令牌数量已达上限');
      }

      const key = generateApiKey();
      const record = {
        id: uniqueId(store.keys, 'k_'),
        userId,
        name,
        prefix: key.slice(0, KEY_PREFIX_LENGTH),
        keyHash: sha256Hex(key),
        createdAt: nowIso(),
        lastUsedAt: null,
        revokedAt: null
      };
      store.keys.push(record);
      pruneUserKeys(store, userId);
      writeStore(store);
      return { key, record };
    });

    res.json(ok({ key: result.key, record: publicKeyRecord(result.record) }));
  })
);

relayRouter.delete(
  '/keys/:id',
  requireUser,
  handle(async (req, res) => {
    const keyId = typeof req.params.id === 'string' ? req.params.id : '';
    await withStoreLock(() => {
      const store = readStore();
      const key = store.keys.find((item) => item && item.id === keyId && item.userId === req.relayUserId);
      if (!key) throw httpError(404, '令牌不存在');
      if (!key.revokedAt) {
        key.revokedAt = nowIso();
        writeStore(store);
      }
    });
    res.json(ok({ ok: true }));
  })
);

// ---------------------------------------------------------------------------
// Top-ups (user)
// ---------------------------------------------------------------------------

relayRouter.get(
  '/topups',
  requireUser,
  handle((req, res) => {
    const store = readStore();
    const orders = store.topups
      .filter((order) => order && order.userId === req.relayUserId)
      .sort(byCreatedAtDesc);
    res.json(ok({ list: orders.slice(0, MAX_TOPUP_LIST), total: orders.length }));
  })
);

relayRouter.post(
  '/topups',
  requireUser,
  handle(async (req, res) => {
    const body = bodyOf(req);
    const planId = typeof body.planId === 'string' ? body.planId : '';
    if (!planId) throw httpError(400, '充值档位不存在');
    const catalog = readCatalog();
    const plan = catalog.plans.find((item) => item && item.id === planId);
    if (!plan) throw httpError(400, '充值档位不存在');

    if (createRateLimited('topups', req.relayUserId, TOPUP_CREATE_RATE_MAX)) {
      throw httpError(429, '请求过于频繁，请稍后再试');
    }

    const amountCents = Math.round(plan.price * 100);
    const creditCents = Math.round(plan.credit * 100);
    const bonusCents = Math.max(0, creditCents - amountCents);

    const order = await withStoreLock(() => {
      const store = readStore();
      const userId = req.relayUserId;
      if (!findUser(store, userId)) throw httpError(401, '登录已失效，请重新登录');
      const pendingCount = store.topups.filter(
        (item) => item && item.userId === userId && item.status === 'pending'
      ).length;
      if (pendingCount >= MAX_PENDING_TOPUPS_PER_USER) {
        throw httpError(429, '请求过于频繁，请稍后再试');
      }
      const record = {
        orderNo: generateTopupOrderNo(store),
        userId,
        planId: plan.id,
        planName: typeof plan.name === 'string' ? plan.name : '',
        amountCents,
        bonusCents,
        creditCents,
        status: 'pending',
        method: null,
        createdAt: nowIso(),
        paidAt: null,
        cancelledAt: null
      };
      store.topups.push(record);
      pruneUserTopups(store, userId);
      writeStore(store);
      return record;
    });

    res.json(ok(order));
  })
);

relayRouter.post(
  '/topups/:orderNo/pay',
  requireUser,
  handle(async (req, res) => {
    if (!ALLOW_SELF_TOPUP) {
      throw httpError(403, '请支付后联系管理员确认到账');
    }
    const body = bodyOf(req);
    const rawMethod = typeof body.method === 'string' ? body.method.trim().toLowerCase() : '';
    const method = PAYMENT_METHODS.has(rawMethod) ? rawMethod : null;
    const orderNo = typeof req.params.orderNo === 'string' ? req.params.orderNo : '';

    const order = await withStoreLock(() => {
      const store = readStore();
      const record = findTopup(store, orderNo);
      if (!record) throw httpError(404, '充值订单不存在');
      if (record.userId !== req.relayUserId) throw httpError(403, '无权操作该订单');

      if (record.status === 'cancelled') throw httpError(409, '订单已取消');
      if (record.status === 'paid') return record;
      if (record.status !== 'pending') throw httpError(409, '充值订单状态不可变更');

      const user = findUser(store, record.userId);
      record.status = 'paid';
      record.paidAt = nowIso();
      if (method) record.method = method;
      if (user) {
        const balance = Number.isFinite(user.balanceCents) ? user.balanceCents : 0;
        user.balanceCents = balance + (Number.isFinite(record.creditCents) ? record.creditCents : 0);
      }
      writeStore(store);
      return record;
    });

    res.json(ok(order));
  })
);

relayRouter.get(
  '/usage',
  requireUser,
  handle((req, res) => {
    const limit = limitFrom(req.query.limit, 50, 200);
    const store = readStore();
    const records = store.usage
      .filter((item) => item && item.userId === req.relayUserId)
      .sort(byCreatedAtDesc);
    res.json(ok({ list: records.slice(0, limit), total: records.length }));
  })
);

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------

relayAdminRouter.get(
  '/users',
  adminOnly,
  handle((req, res) => {
    const limit = limitFrom(req.query.limit, 100, 500);
    const store = readStore();
    const paidByUser = new Map();
    for (const order of store.topups) {
      if (!order || order.status !== 'paid') continue;
      const amount = Number.isFinite(order.amountCents) ? order.amountCents : 0;
      paidByUser.set(order.userId, (paidByUser.get(order.userId) || 0) + amount);
    }

    const list = store.users
      .slice()
      .sort(byCreatedAtDesc)
      .map((user) => {
        const keyCount = store.keys.filter((key) => key && key.userId === user.id).length;
        return {
          id: user.id,
          email: user.email,
          balanceCents: Number.isFinite(user.balanceCents) ? user.balanceCents : 0,
          disabled: user.disabled === true,
          createdAt: user.createdAt,
          keyCount,
          paidTopupCents: paidByUser.get(user.id) || 0
        };
      });

    res.json(ok({ list: list.slice(0, limit), total: store.users.length }));
  })
);

relayAdminRouter.get(
  '/topups',
  adminOnly,
  handle((req, res) => {
    const status = typeof req.query.status === 'string' ? req.query.status.trim() : '';
    const limit = limitFrom(req.query.limit, 100, 500);
    const store = readStore();
    const emailById = new Map();
    for (const user of store.users) {
      if (user && user.id) emailById.set(user.id, user.email);
    }

    const matching = store.topups.filter((order) => order && (!status || order.status === status));
    matching.sort(byCreatedAtDesc);
    const list = matching.slice(0, limit).map((order) => ({
      ...order,
      email: emailById.get(order.userId) || ''
    }));

    res.json(ok({ list, total: matching.length }));
  })
);

relayAdminRouter.post(
  '/topups/:orderNo/status',
  adminOnly,
  handle(async (req, res) => {
    const body = bodyOf(req);
    const status = typeof body.status === 'string' ? body.status.trim() : '';
    if (status !== 'paid' && status !== 'cancelled') {
      throw httpError(400, '充值订单状态不合法');
    }
    const orderNo = typeof req.params.orderNo === 'string' ? req.params.orderNo : '';

    const order = await withStoreLock(() => {
      const store = readStore();
      const record = findTopup(store, orderNo);
      if (!record) throw httpError(404, '充值订单不存在');
      if (record.status !== 'pending') throw httpError(409, '充值订单状态不可变更');

      if (status === 'paid') {
        const user = findUser(store, record.userId);
        if (!user) throw httpError(500, '账户数据读取失败');
        const balance = Number.isFinite(user.balanceCents) ? user.balanceCents : 0;
        user.balanceCents = balance + (Number.isFinite(record.creditCents) ? record.creditCents : 0);
        record.status = 'paid';
        record.paidAt = nowIso();
      } else {
        record.status = 'cancelled';
        record.cancelledAt = nowIso();
      }
      writeStore(store);
      return record;
    });

    res.json(ok(order));
  })
);

// ---------------------------------------------------------------------------
// OpenAI-compatible proxy
// ---------------------------------------------------------------------------

relayProxyRouter.get(
  '/models',
  handle((req, res) => {
    if (!relayKeyAuth(req)) {
      return sendOpenAIError(res, 401, '无效的 API Key', 'invalid_request_error', 'invalid_api_key');
    }
    const catalog = readCatalog();
    res.json({
      object: 'list',
      data: catalog.models.map((model) => ({
        id: model.name,
        object: 'model',
        created: 0,
        owned_by: typeof model.vendor === 'string' ? model.vendor : ''
      }))
    });
    return undefined;
  })
);

relayProxyRouter.post(
  '/chat/completions',
  handle(async (req, res) => {
    const auth = relayKeyAuth(req);
    if (!auth) {
      return sendOpenAIError(res, 401, '无效的 API Key', 'invalid_request_error', 'invalid_api_key');
    }
    const { user, key } = auth;
    const balance = Number.isFinite(user.balanceCents) ? user.balanceCents : 0;
    if (user.disabled === true || balance <= 0) {
      return sendOpenAIError(res, 402, '余额不足，请先充值', 'insufficient_quota', 'insufficient_quota');
    }

    const base = upstreamBaseUrl();
    if (!base) {
      return sendOpenAIError(res, 503, '中转上游未配置', 'server_error', 'upstream_not_configured');
    }

    const body = bodyOf(req);
    const requestedModel = typeof body.model === 'string' ? body.model : '';
    const catalog = readCatalog();
    const model = catalog.models.find((item) => item && item.name === requestedModel);
    if (!model) {
      return sendOpenAIError(res, 400, '模型不存在', 'invalid_request_error', 'model_not_found');
    }

    const bodyText = Buffer.isBuffer(req.rawBody) && req.rawBody.length
      ? req.rawBody.toString('utf8')
      : JSON.stringify(req.body && typeof req.body === 'object' ? req.body : {});

    return forwardChatCompletion({
      base,
      bodyText,
      stream: body.stream === true,
      userId: user.id,
      keyId: key.id,
      model,
      res
    });
  })
);

// ---------------------------------------------------------------------------
// Error handling
// ---------------------------------------------------------------------------

relayRouter.use(routerErrorHandler);
relayAdminRouter.use(routerErrorHandler);

relayProxyRouter.use((err, _req, res, next) => {
  if (res.headersSent) return next(err);
  const status = err && Number.isInteger(err.status) ? err.status : 500;
  const message = err && err.message ? err.message : '服务器内部错误';
  sendOpenAIError(
    res,
    status,
    message,
    status === 500 ? 'server_error' : 'invalid_request_error',
    status === 500 ? 'internal_error' : 'invalid_request_error'
  );
  return undefined;
});

module.exports = { relayRouter, relayAdminRouter, relayProxyRouter };
