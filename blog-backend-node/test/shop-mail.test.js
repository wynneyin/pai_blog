const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const express = require('express');

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'shop-mail-'));
fs.writeFileSync(
  path.join(tempDir, 'shop-catalog.json'),
  JSON.stringify({
    products: [
      {
        id: 'relay-50',
        categoryId: 'relay',
        name: '中转额度 50',
        price: 50,
        stock: 5,
        relayPlanId: 'p50'
      }
    ],
    notices: []
  })
);
fs.writeFileSync(
  path.join(tempDir, 'relay.json'),
  JSON.stringify({
    models: [{ name: 'gpt-4o-mini', input: 1.08, output: 4.32 }],
    plans: [{ id: 'p50', name: '标准充值', price: 50, credit: 55 }]
  })
);

process.env.SHOP_CATALOG_PATH = path.join(tempDir, 'shop-catalog.json');
process.env.SHOP_DATA_PATH = path.join(tempDir, 'shop-orders.json');
process.env.SHOP_ALLOW_SELF_PAY = 'true';
process.env.RELAY_CATALOG_PATH = path.join(tempDir, 'relay.json');
process.env.RELAY_DATA_PATH = path.join(tempDir, 'relay-store.json');
process.env.MAIL_ENABLED = 'true';
process.env.MAIL_HOST = 'smtp.test.invalid';
process.env.MAIL_FROM = 'no-reply@test.invalid';
process.env.SITE_BASE_URL = 'https://example.test';

const mail = require('../src/mail');
const { relayRouter } = require('../src/relay');
const { shopRouter } = require('../src/shop');

const sent = [];
mail.setTransportForTests({
  sendMail(message) {
    sent.push(message);
    return Promise.resolve({});
  }
});

const app = express();
app.use(express.json());
app.use('/api/relay', relayRouter);
app.use('/api/shop', shopRouter);

let server;
let baseUrl;

test.before(async () => {
  await new Promise((resolve) => {
    server = app.listen(0, '127.0.0.1', resolve);
  });
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

test.after(() => {
  server.close();
  fs.rmSync(tempDir, { recursive: true, force: true });
});

async function call(method, route, body, headers = {}) {
  const response = await fetch(`${baseUrl}${route}`, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: response.status, body: await response.json() };
}

test('relay orders queue created and credited emails', async () => {
  sent.length = 0;

  const registered = await call('POST', '/api/relay/auth/register', {
    email: 'buyer@example.com',
    password: 'Shop-Mail-Test-Pass'
  });
  assert.equal(registered.status, 200);

  const created = await call('POST', '/api/shop/orders', {
    productId: 'relay-50',
    quantity: 1,
    contact: 'buyer@example.com'
  });
  assert.equal(created.status, 200);
  assert.equal(created.body.data.emailQueued, true);
  const { orderNo, lookupToken } = created.body.data;
  assert.ok(lookupToken);

  assert.equal(sent.length, 1);
  assert.equal(sent[0].to, 'buyer@example.com');
  assert.match(sent[0].subject, /^订单已创建 · /);
  assert.ok(sent[0].text.includes(lookupToken));

  const paid = await call(
    'POST',
    `/api/shop/orders/${orderNo}/pay`,
    { method: 'alipay' },
    { 'x-order-token': lookupToken }
  );
  assert.equal(paid.status, 200);
  assert.equal(paid.body.data.status, 'paid');
  assert.ok(paid.body.data.relayCreditedAt);

  assert.equal(sent.length, 2);
  assert.match(sent[1].subject, /^额度已到账 · /);
  assert.ok(sent[1].text.includes('¥55.00'));

  const me = await call('GET', '/api/relay/me', undefined, {
    authorization: `Bearer ${registered.body.data.token}`
  });
  assert.equal(me.status, 200);
  assert.equal(me.body.data.user.balanceCents, 5500);
});

test('credited email reports the amount actually credited', async () => {
  sent.length = 0;

  await call('POST', '/api/relay/auth/register', {
    email: 'buyer2@example.com',
    password: 'Shop-Mail-Test-Pass'
  });
  const created = await call('POST', '/api/shop/orders', {
    productId: 'relay-50',
    quantity: 1,
    contact: 'buyer2@example.com'
  });
  assert.equal(created.status, 200);
  const { orderNo, lookupToken } = created.body.data;

  // The operator raises the bonus while the order is still pending.
  const relayCatalogPath = path.join(tempDir, 'relay.json');
  const relayCatalog = JSON.parse(fs.readFileSync(relayCatalogPath, 'utf8'));
  relayCatalog.plans[0].credit = 60;
  fs.writeFileSync(relayCatalogPath, JSON.stringify(relayCatalog));

  const paid = await call(
    'POST',
    `/api/shop/orders/${orderNo}/pay`,
    { method: 'wechat' },
    { 'x-order-token': lookupToken }
  );
  assert.equal(paid.status, 200);
  assert.equal(paid.body.data.relayCreditCents, 6000);
  assert.equal(sent.length, 2);
  assert.ok(sent[1].text.includes('¥60.00'), sent[1].text);

  const login = await call('POST', '/api/relay/auth/login', {
    email: 'buyer2@example.com',
    password: 'Shop-Mail-Test-Pass'
  });
  const me = await call('GET', '/api/relay/me', undefined, {
    authorization: `Bearer ${login.body.data.token}`
  });
  assert.equal(me.body.data.user.balanceCents, 6000);
});
