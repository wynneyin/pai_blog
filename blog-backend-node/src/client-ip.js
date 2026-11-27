// Resolve the originating client for rate limiting. The Node backend normally
// sits behind nginx, so the socket peer is a private proxy address and the real
// client arrives in X-Forwarded-For. Only trust that header when the direct
// peer is loopback/private/link-local; a public client cannot spoof its bucket.
//
// nginx uses `$proxy_add_x_forwarded_for`, which appends the address it saw to
// whatever header the client sent. Walk the header right to left, skipping
// known proxies, so a forged leftmost entry cannot pick the rate-limit bucket.
const net = require('net');

function isTrustedProxy(address) {
  if (!address) return false;
  const value = String(address).replace(/^::ffff:/i, '');
  if (value === '::1' || value === '::' || value === '0.0.0.0') return true;
  if (/^(fc|fd|fe80)/i.test(value)) return true;

  const parts = value.split('.');
  if (parts.length === 4 && parts.every((part) => /^\d{1,3}$/.test(part))) {
    const octets = parts.map(Number);
    if (octets.some((part) => part > 255)) return false;
    const [a, b] = octets;
    return a === 10
      || a === 127
      || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && b === 168)
      || (a === 169 && b === 254);
  }
  return false;
}

// Accept only IP literals and flatten IPv4-mapped IPv6 so equivalent spellings
// share a bucket. Hostnames are rejected so no DNS lookup can be triggered.
function normalizeIp(value) {
  if (typeof value !== 'string') return '';
  const candidate = value.trim().replace(/^\[|\]$/g, '');
  if (!net.isIP(candidate)) return '';
  const mapped = candidate.replace(/^::ffff:/i, '');
  return net.isIP(mapped) ? mapped : candidate;
}

function clientIp(req) {
  const remote = normalizeIp(
    req && req.socket ? req.socket.remoteAddress : ''
  );
  const forwarded =
    req && req.headers && typeof req.headers['x-forwarded-for'] === 'string'
      ? req.headers['x-forwarded-for']
      : '';
  if (forwarded && isTrustedProxy(remote)) {
    const hops = forwarded.split(',');
    // nginx appends the address it actually saw, so the rightmost hop is
    // authoritative; every entry to its left is client-supplied.
    for (let i = hops.length - 1; i >= 0; i -= 1) {
      const candidate = normalizeIp(hops[i]);
      if (candidate) return candidate;
    }
  }
  return remote || 'unknown';
}

module.exports = { clientIp, isTrustedProxy };
