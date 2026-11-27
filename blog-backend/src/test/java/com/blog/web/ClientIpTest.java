package com.blog.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpTest {

    @Test
    void usesForwardedForOnlyBehindATrustedPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shop/catalog");
        request.setRemoteAddr("172.18.0.4");
        request.addHeader("X-Forwarded-For", " 198.51.100.7 , 10.0.0.2");
        assertEquals("198.51.100.7", ClientIp.resolve(request));

        request.setRemoteAddr("203.0.113.9");
        assertEquals("203.0.113.9", ClientIp.resolve(request));
    }

    @Test
    void loopbackAndIpv6PeersAreTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shop/catalog");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.8");
        assertEquals("198.51.100.8", ClientIp.resolve(request));

        MockHttpServletRequest ipv6 = new MockHttpServletRequest("GET", "/api/shop/catalog");
        ipv6.setRemoteAddr("::1");
        ipv6.addHeader("X-Forwarded-For", "198.51.100.9");
        assertEquals("198.51.100.9", ClientIp.resolve(ipv6));
    }

    @Test
    void forgedLeftmostForwardedEntryCannotChooseTheBucket() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shop/catalog");
        request.setRemoteAddr("172.18.0.4");
        // nginx appends the address it actually saw, so the real client is
        // the rightmost entry; anything to its left is client-supplied.
        request.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.23");
        assertEquals("198.51.100.23", ClientIp.resolve(request));
    }

    @Test
    void equivalentIpv6SpellingsShareOneBucket() {
        String first = resolveForwarded("2001:0db8:0:0:0:0:0:1");
        String second = resolveForwarded("2001:db8::1");
        assertEquals(first, second);
    }

    @Test
    void uniqueLocalPeersAreTrustedLikeIpv4PrivateOnes() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shop/catalog");
        request.setRemoteAddr("fd00::2");
        request.addHeader("X-Forwarded-For", "198.51.100.25");
        assertEquals("198.51.100.25", ClientIp.resolve(request));
    }

    private static String resolveForwarded(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shop/catalog");
        request.setRemoteAddr("172.18.0.4");
        request.addHeader("X-Forwarded-For", forwardedFor);
        return ClientIp.resolve(request);
    }

    @Test
    void missingRemoteAddrFallsBackToUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shop/catalog");
        request.setRemoteAddr(null);
        assertEquals("unknown", ClientIp.resolve(request));
        assertEquals("unknown", ClientIp.resolve(null));
    }
}
