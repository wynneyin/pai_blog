const test = require('node:test');
const assert = require('node:assert/strict');
const { clientIp, isTrustedProxy } = require('../src/client-ip');

function request(remoteAddress, forwardedFor) {
  const headers = {};
  if (forwardedFor !== undefined) headers['x-forwarded-for'] = forwardedFor;
  return { socket: { remoteAddress }, headers };
}

test('uses the socket address without a trusted proxy', () => {
  assert.equal(clientIp(request('203.0.113.9')), '203.0.113.9');
  assert.equal(clientIp(request('203.0.113.9', '198.51.100.7')), '203.0.113.9');
});

test('walks X-Forwarded-For right to left behind a proxy', () => {
  // nginx appends the address it saw, so the leftmost entry is attacker text.
  assert.equal(clientIp(request('172.18.0.4', '1.1.1.1, 198.51.100.23')), '198.51.100.23');
});

test('uses the rightmost hop even when the real client is private', () => {
  // A LAN client behind the host's nginx is still the appended rightmost hop.
  assert.equal(clientIp(request('172.18.0.4', '1.1.1.1, 192.168.1.50')), '192.168.1.50');
});

test('invalid forwarded entries fall back to the peer', () => {
  assert.equal(clientIp(request('172.18.0.4', 'not-an-ip')), '172.18.0.4');
  assert.equal(clientIp({}), 'unknown');
});

test('flattens IPv4-mapped IPv6 and treats loopback as trusted', () => {
  assert.equal(clientIp(request('::ffff:127.0.0.1', '198.51.100.26')), '198.51.100.26');
  assert.equal(clientIp(request('::ffff:198.51.100.27')), '198.51.100.27');
});

test('treats unique-local and link-local IPv6 as proxies', () => {
  assert.equal(isTrustedProxy('fd00::2'), true);
  assert.equal(isTrustedProxy('fe80::1'), true);
  assert.equal(isTrustedProxy('2001:db8::1'), false);
});
