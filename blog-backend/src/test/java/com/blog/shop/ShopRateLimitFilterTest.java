package com.blog.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopRateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShopRateLimitFilter filter = new ShopRateLimitFilter(objectMapper);

    @Test
    void createOrderIsLimitedToTenPerMinute() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CREATE_LIMIT; i++) {
            assertEquals(200, call("POST", "/api/shop/orders", "198.51.100.5", null).getStatus());
        }

        MockHttpServletResponse blocked =
                call("POST", "/api/shop/orders", "198.51.100.5", null);
        assertEquals(429, blocked.getStatus());
        assertNotEquals(null, blocked.getHeader("Retry-After"));
        JsonNode body = objectMapper.readTree(
                blocked.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(429, body.path("code").asInt());
        assertEquals(ShopRateLimitFilter.TOO_MANY_MESSAGE, body.path("message").asText());
    }

    @Test
    void trustedProxyUsesTheForwardedClientAddress() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CREATE_LIMIT; i++) {
            assertEquals(200, call("POST", "/api/shop/orders",
                    "172.18.0.2", "198.51.100.10").getStatus());
        }
        assertEquals(429, call("POST", "/api/shop/orders",
                "172.18.0.2", "198.51.100.10").getStatus());
        assertEquals(200, call("POST", "/api/shop/orders",
                "172.18.0.2", "198.51.100.11").getStatus());
    }

    @Test
    void publicPeerCannotRotateForwardedForToBypassTheLimit() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CREATE_LIMIT; i++) {
            assertEquals(200, call("POST", "/api/shop/orders",
                    "203.0.113.9", "198.51.100." + i).getStatus());
        }
        assertEquals(429, call("POST", "/api/shop/orders",
                "203.0.113.9", "198.51.100.99").getStatus());
    }

    @Test
    void lookupAndPaymentHaveSeparateBuckets() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CREATE_LIMIT; i++) {
            call("POST", "/api/shop/orders", "198.51.100.20", null);
        }
        assertEquals(429, call("POST", "/api/shop/orders",
                "198.51.100.20", null).getStatus());

        assertEquals(200, call("GET", "/api/shop/orders/PB202601010000ABCD",
                "198.51.100.20", null).getStatus());
        assertEquals(200, call("POST", "/api/shop/orders/PB202601010000ABCD/pay",
                "198.51.100.20", null).getStatus());
    }

    @Test
    void unrelatedPathsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 200; i++) {
            assertEquals(200, call("GET", "/api/relay/catalog", "198.51.100.30", null).getStatus());
        }
    }

    @Test
    void headRequestsShareTheGetBudget() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CATALOG_LIMIT; i++) {
            assertEquals(200, call("HEAD", "/api/shop/catalog",
                    "198.51.100.50", null).getStatus());
        }
        assertEquals(429, call("HEAD", "/api/shop/catalog",
                "198.51.100.50", null).getStatus());
    }

    @Test
    void matrixParametersCannotBypassLimits() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CREATE_LIMIT; i++) {
            assertEquals(200, call("POST", "/api/shop;x=1/orders",
                    "198.51.100.51", null).getStatus());
        }
        assertEquals(429, call("POST", "/api/shop/orders;x=1",
                "198.51.100.51", null).getStatus());
        assertEquals(429, call("POST", "/api/shop/orders%3Bx=1",
                "198.51.100.51", null).getStatus());
    }

    @Test
    void optionsPreflightIsNeverLimited() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.ADMIN_LIMIT + 10; i++) {
            assertEquals(200, call("OPTIONS", "/api/admin/shop/orders",
                    "198.51.100.52", null).getStatus());
        }
    }

    @Test
    void adminEndpointsHaveTheirOwnBudget() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.ADMIN_LIMIT; i++) {
            assertEquals(200, call("GET", "/api/admin/shop/orders",
                    "198.51.100.40", null).getStatus());
        }
        assertEquals(429, call("GET", "/api/admin/shop/orders",
                "198.51.100.40", null).getStatus());
    }

    private MockHttpServletResponse call(String method, String uri,
                                         String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
        });
        assertTrue(response.getStatus() == 200 || response.getStatus() == 429);
        return response;
    }
}
