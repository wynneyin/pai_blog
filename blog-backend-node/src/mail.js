const nodemailer = require('nodemailer');

// Transactional email mirrors the Java backend: disabled unless MAIL_ENABLED
// is true and both MAIL_HOST and MAIL_FROM are configured. Sends are queued and
// never block the triggering request; a delivery failure is logged but does not
// fail order creation or password reset.

const DEFAULT_SITE_BASE_URL = 'https://wynneyin.site';
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

let cachedTransport = null;
let cachedKey = '';
let testTransport = null;

function config() {
  const enabled = process.env.MAIL_ENABLED === 'true';
  const host = (process.env.MAIL_HOST || '').trim();
  const from = (process.env.MAIL_FROM || '').trim();
  const port = Number.parseInt(process.env.MAIL_PORT, 10);
  const secure = process.env.MAIL_SECURE === 'true' || process.env.MAIL_SSL === 'true';
  return {
    enabled: enabled && host !== '' && from !== '',
    host,
    from,
    port: Number.isFinite(port) && port > 0 ? port : 587,
    secure,
    starttls: process.env.MAIL_STARTTLS !== 'false',
    auth: process.env.MAIL_SMTP_AUTH !== 'false',
    username: (process.env.MAIL_USERNAME || '').trim(),
    password: process.env.MAIL_PASSWORD || ''
  };
}

function enabled() {
  return config().enabled;
}

function siteBaseUrl() {
  return (process.env.SITE_BASE_URL || DEFAULT_SITE_BASE_URL).trim().replace(/\/+$/, '');
}

function buildTransport() {
  const current = config();
  const key = [
    current.host,
    current.port,
    current.secure,
    current.starttls,
    current.auth,
    current.username,
    current.password
  ].join('|');
  if (cachedTransport && cachedKey === key) return cachedTransport;
  cachedTransport = nodemailer.createTransport({
    host: current.host,
    port: current.port,
    secure: current.secure,
    requireTLS: !current.secure && current.starttls,
    ...(current.username && current.auth
      ? { auth: { user: current.username, pass: current.password } }
      : {}),
    connectionTimeout: 5000,
    greetingTimeout: 5000,
    socketTimeout: 5000
  });
  cachedKey = key;
  return cachedTransport;
}

function transport() {
  if (testTransport) return testTransport;
  if (config().enabled) return buildTransport();
  return null;
}

// Queues one plain-text message. Returns true when the message was handed to
// the transport, false when mail is disabled or the address is unusable.
function send(to, subject, text) {
  const current = config();
  if (!current.enabled) return false;
  if (typeof to !== 'string') return false;
  const recipient = to.trim();
  // Reject header-ish input: only a plain address may become the To field.
  if (!EMAIL_RE.test(recipient)) return false;

  let sender;
  try {
    sender = transport();
  } catch (e) {
    console.error('[mail] transport setup failed:', e && e.message);
    return false;
  }
  if (!sender) return false;

  try {
    const pending = sender.sendMail({
      from: current.from,
      to: recipient,
      subject,
      text
    });
    if (pending && typeof pending.catch === 'function') {
      pending.catch((e) => console.error('[mail] send failed:', subject, e && e.message));
    }
    return true;
  } catch (e) {
    console.error('[mail] send failed:', subject, e && e.message);
    return false;
  }
}

function sendPasswordReset(email, token) {
  const link = `${siteBaseUrl()}/relay/reset-password#token=${token}`;
  const text = `你好，

我们收到了重置 Wynne Relay 登录密码的请求。请打开下面的链接设置新密码：

${link}

链接 30 分钟内有效，只能使用一次。如果这不是你发起的，可以忽略这封邮件，原密码不会被修改。
`;
  return send(email, '重置 Wynne Relay 登录密码', text);
}

function sendOrderCreated(order, lookupToken) {
  if (!order || typeof order !== 'object') return false;
  const text = `你好，

你的订单已创建。

订单号：${order.orderNo}
商品：${order.productName || ''} × ${order.quantity}
金额：¥${order.total}
查询凭证：${lookupToken}

请妥善保存查询凭证。也可以登录下单邮箱对应的中转站账号，在 ${siteBaseUrl()}/shop/orders 查询订单。
`;
  return send(order.contact, `订单已创建 · ${order.orderNo}`, text);
}

function sendOrderPaid(order) {
  if (!order || typeof order !== 'object') return false;
  if (!Number.isFinite(order.relayCreditCents)) return false;
  const credit = (order.relayCreditCents / 100).toFixed(2);
  const text = `你好，

订单 ${order.orderNo} 已确认收款，¥${credit} 额度已充入 ${order.contact} 的中转站账户。

可登录 ${siteBaseUrl()}/relay/console 查看余额和充值记录。
`;
  return send(order.contact, `额度已到账 · ${order.orderNo}`, text);
}

// Test hook: route messages to an in-memory transport instead of SMTP.
function setTransportForTests(value) {
  testTransport = value || null;
  cachedTransport = null;
  cachedKey = '';
}

if (process.env.MAIL_ENABLED === 'true' && !config().enabled) {
  console.warn('[mail] MAIL_ENABLED is true but MAIL_HOST/MAIL_FROM are incomplete; email is disabled.');
}

module.exports = {
  enabled,
  siteBaseUrl,
  send,
  sendPasswordReset,
  sendOrderCreated,
  sendOrderPaid,
  setTransportForTests
};
