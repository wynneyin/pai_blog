package com.blog.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay body cap must run before controllers (and therefore before
 * authentication, admin-token checks and rate limiting) and must never buffer
 * more than its limit, whether the client declares a length or streams chunked.
 */
class RelayBodySizeLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RelayBodySizeLimitFilter filter = new RelayBodySizeLimitFilter(objectMapper);

    @Test
    void rejectsOversizedDeclaredBodiesBeforeTheChainRuns() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/relay/auth/register");
        request.setContentType("application/json");
        request.setContent(new byte[RelayBodySizeLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(invoked, new AtomicReference<>()));

        assertFalse(invoked.get(), "oversized body must be rejected before the controller runs");
        assertEquals(413, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(413, body.path("code").asInt());
        assertEquals(RelayBodySizeLimitFilter.TOO_LARGE_MESSAGE, body.path("message").asText());
    }

    @Test
    void rejectsChunkedBodiesWithoutContentLength() throws Exception {
        MockHttpServletRequest base =
                new MockHttpServletRequest("POST", "/v1/chat/completions");
        base.setContent(new byte[RelayBodySizeLimitFilter.MAX_BODY_BYTES + 1]);
        HttpServletRequest request = new HttpServletRequestWrapper(base) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public String getHeader(String name) {
                return "Transfer-Encoding".equalsIgnoreCase(name) ? "chunked" : super.getHeader(name);
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, recordingChain(invoked, new AtomicReference<>()));

        assertFalse(invoked.get());
        assertEquals(413, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(RelayBodySizeLimitFilter.TOO_LARGE_MESSAGE,
                body.path("error").path("message").asText());
        assertEquals("invalid_request_error", body.path("error").path("type").asText());
        assertEquals("request_too_large", body.path("error").path("code").asText());
    }

    @Test
    void forwardsBodiesWithinTheLimitByteForByte() throws Exception {
        byte[] payload = new byte[RelayBodySizeLimitFilter.MAX_BODY_BYTES];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/relay/auth/register");
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        AtomicReference<byte[]> seen = new AtomicReference<>();
        AtomicInteger seenLength = new AtomicInteger(-1);

        filter.doFilter(request, response, recordingChain(invoked, seen, seenLength));

        assertTrue(invoked.get());
        assertArrayEquals(payload, seen.get());
        assertEquals(payload.length, seenLength.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void leavesNonRelayPathsUntouched() throws Exception {
        byte[] payload = new byte[RelayBodySizeLimitFilter.MAX_BODY_BYTES + 1];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/shop/orders");
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        AtomicReference<byte[]> seen = new AtomicReference<>();

        filter.doFilter(request, response, recordingChain(invoked, seen));

        assertTrue(invoked.get());
        assertEquals(payload.length, seen.get().length);
    }

    private static FilterChain recordingChain(AtomicBoolean invoked, AtomicReference<byte[]> seen) {
        return recordingChain(invoked, seen, new AtomicInteger(-1));
    }

    private static FilterChain recordingChain(AtomicBoolean invoked, AtomicReference<byte[]> seen,
                                              AtomicInteger seenLength) {
        return (request, response) -> {
            invoked.set(true);
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            seenLength.set(httpRequest.getContentLength());
            seen.set(httpRequest.getInputStream().readAllBytes());
        };
    }
}
