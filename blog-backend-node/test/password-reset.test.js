const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const express = require('express');

const mail = require('../src/mail');
const { relayRouter } = require('../src/relay');

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-reset-'));
process.env.RELAY_DATA_PATH = path.join(tempDir, 'relay-store.json');
delete process.env.RELAY_CATALOG_PATH;

const password = 'Reset-Test-Pass-1';
const newPassword = 'Reset-Test-Pass-2';

const app = express();
app.use(express.json());
app.use('/api/relay', relayRouter);

let server;
let baseUrl;
const sent = [];

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

function enableMail() {
  process.env.MAIL_ENABLED = 'true';
  process.env.MAIL_HOST = 'smtp.test.invalid';
  process.env.MAIL_FROM = 'no-reply@test.invalid';
  mail.setTransportForTests({
    sendMail(message) {
      sent.push(message);
      return Promise.resolve({});
    }
  });
}

async function call(method, route, body, headers = {}) {
  const response = await fetch(`${baseUrl}${route}`, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: response.status, body: await response.json() };
}

test('forgot-password is unavailable until mail is configured', async () => {
  delete process.env.MAIL_ENABLED;
  const response = await call('POST', '/api/relay/auth/forgot-password', {
    email: 'user@example.com'
  });
  assert.equal(response.status, 503);
  assert.equal(response.body.message, '邮件服务未配置，请联系管理员');
});

test('reset mail is single use and revokes old sessions', async () => {
  enableMail();
  sent.length = 0;

  const registered = await call('POST', '/api/relay/auth/register', {
    email: 'reset@example.com',
    password
  });
  assert.equal(registered.status, 200);
  const oldToken = registered.body.data.token;

  const createdKey = await call(
    'POST',
    '/api/relay/keys',
    { name: 'client' },
    { authorization: `Bearer ${oldToken}` }
  );
  assert.equal(createdKey.status, 200);
  const keyId = createdKey.body.data.record.id;

  const forgot = await call('POST', '/api/relay/auth/forgot-password', {
    email: 'reset@example.com'
  });
  assert.equal(forgot.status, 200);
  assert.equal(forgot.body.data.sent, true);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].to, 'reset@example.com');

  const match = /#token=(pr_[0-9a-f]{64})/.exec(sent[0].text);
  assert.ok(match, 'reset link must carry a token');
  const resetToken = match[1];

  const persisted = fs.readFileSync(process.env.RELAY_DATA_PATH, 'utf8');
  assert.equal(persisted.includes(resetToken), false);
  assert.equal(persisted.includes(password), false);

  const reset = await call('POST', '/api/relay/auth/reset-password', {
    token: resetToken,
    password: newPassword
  });
  assert.equal(reset.status, 200);
  assert.equal(reset.body.data.reset, true);

  const me = await call('GET', '/api/relay/me', undefined, {
    authorization: `Bearer ${oldToken}`
  });
  assert.equal(me.status, 401);

  const oldLogin = await call('POST', '/api/relay/auth/login', {
    email: 'reset@example.com',
    password
  });
  assert.equal(oldLogin.status, 401);

  const newLogin = await call('POST', '/api/relay/auth/login', {
    email: 'reset@example.com',
    password: newPassword
  });
  assert.equal(newLogin.status, 200);

  const keys = await call('GET', '/api/relay/keys', undefined, {
    authorization: `Bearer ${newLogin.body.data.token}`
  });
  assert.equal(keys.status, 200);
  const oldKey = keys.body.data.list.find((item) => item.id === keyId);
  assert.ok(oldKey, 'key record must be kept for the console');
  assert.ok(oldKey.revokedAt, 'keys minted before the reset must be revoked');

  const reused = await call('POST', '/api/relay/auth/reset-password', {
    token: resetToken,
    password: newPassword
  });
  assert.equal(reused.status, 400);
  assert.equal(reused.body.message, '重置链接无效或已过期');
});

test('forgot-password never reveals unknown addresses', async () => {
  enableMail();
  sent.length = 0;

  const response = await call('POST', '/api/relay/auth/forgot-password', {
    email: 'nobody@example.com'
  });

  assert.equal(response.status, 200);
  assert.equal(response.body.data.sent, true);
  assert.equal(sent.length, 0);
});
