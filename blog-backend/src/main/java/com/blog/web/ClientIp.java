package com.blog.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

/**
 * Resolves the originating client address for rate limiting. In production the
 * Java backend sits behind nginx, so the socket peer is the proxy container and
 * the real address arrives in X-Forwarded-For. Only trust that header when the
 * direct peer is loopback or a private/link-local address; a public client that
 * connects to the backend directly cannot spoof its rate-limit bucket.
 *
 * <p>nginx uses {@code $proxy_add_x_forwarded_for}, which <em>appends</em> the
 * address it saw to whatever header the client sent. The header is therefore
 * walked from right to left, skipping known proxy addresses, so a client cannot
 * choose its own bucket by sending a forged leftmost entry.</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = canonicalIp(request.getRemoteAddr());
        if (remote.isEmpty()) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && isTrustedProxy(remote)) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String candidate = canonicalIp(hops[i]);
                if (candidate.isEmpty() || isTrustedProxy(candidate)) {
                    continue;
                }
                return candidate;
            }
        }
        return remote;
    }

    static boolean isTrustedProxy(String address) {
        try {
            InetAddress inet = InetAddress.getByName(address);
            if (inet.isLoopbackAddress()
                    || inet.isSiteLocalAddress()
                    || inet.isLinkLocalAddress()
                    || inet.isAnyLocalAddress()) {
                return true;
            }
            // InetAddress only treats the deprecated fec0::/10 as IPv6
            // site-local, so add the fc00::/7 unique-local block explicitly.
            byte[] bytes = inet.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Accepts only IP literals (never hostnames, which would trigger DNS) and
     * returns the canonical textual form so equivalent spellings share a bucket.
     */
    private static String canonicalIp(String value) {
        String trimmed = normalize(value);
        if (trimmed.isEmpty() || trimmed.length() > 64 || !trimmed.matches("[0-9a-fA-F:.]+")) {
            return "";
        }
        try {
            return InetAddress.getByName(trimmed).getHostAddress();
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
