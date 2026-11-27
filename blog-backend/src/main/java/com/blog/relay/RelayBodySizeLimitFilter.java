package com.blog.relay;

import com.blog.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Caps relay request bodies before Spring's message converters copy them into
 * memory. Authentication, admin-token checks and rate limiting all run inside
 * controller methods, which is too late: without this guard an unauthenticated
 * client could post a multi-hundred-megabyte body to /api/relay/**,
 * /api/admin/relay/** or /v1/** and exhaust the heap.
 *
 * <p>The limit is 100 KiB, matching the Node backend's express.json default so
 * both implementations accept and reject the same payloads. Requests with a
 * declared Content-Length over the limit are rejected without reading the
 * body; chunked requests are read through a bounded buffer and rejected as
 * soon as they exceed it.</p>
 */
@Component
public class RelayBodySizeLimitFilter extends OncePerRequestFilter {

    /** Maximum accepted request body size in bytes (100 KiB, like express.json). */
    public static final int MAX_BODY_BYTES = 100 * 1024;

    public static final String TOO_LARGE_MESSAGE = "请求体过大";

    private static final int READ_BUFFER_BYTES = 8192;

    private final ObjectMapper objectMapper;

    public RelayBodySizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isRelayRequest(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            writeTooLarge(request, response);
            return;
        }
        if (declaredLength < 0 && !isChunked(request)) {
            // No request body at all; nothing to buffer.
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = readBodyWithinLimit(request);
        if (body == null) {
            writeTooLarge(request, response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static byte[] readBodyWithinLimit(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        int initialCapacity = declaredLength >= 0 && declaredLength < MAX_BODY_BYTES
                ? (int) declaredLength
                : MAX_BODY_BYTES;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(initialCapacity);
        byte[] chunk = new byte[READ_BUFFER_BYTES];
        ServletInputStream input = request.getInputStream();
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        byte[] payload;
        if (isOpenAiRequest(request.getRequestURI())) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", TOO_LARGE_MESSAGE);
            error.put("type", "invalid_request_error");
            error.put("code", "request_too_large");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", error);
            payload = objectMapper.writeValueAsBytes(body);
        } else {
            payload = objectMapper.writeValueAsBytes(
                    ApiResponse.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), TOO_LARGE_MESSAGE));
        }
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        response.getOutputStream().flush();
    }

    private static boolean isChunked(HttpServletRequest request) {
        String transferEncoding = request.getHeader("Transfer-Encoding");
        return transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    private static boolean isRelayRequest(String uri) {
        return isOpenAiRequest(uri)
                || matchesPrefix(uri, "/api/relay")
                || matchesPrefix(uri, "/api/admin/relay");
    }

    private static boolean isOpenAiRequest(String uri) {
        return matchesPrefix(uri, "/v1");
    }

    private static boolean matchesPrefix(String uri, String prefix) {
        return uri != null && (uri.equals(prefix) || uri.startsWith(prefix + "/"));
    }

    /**
     * Serves the buffered bytes to Spring's message converters. The original
     * stream has already been consumed by the filter, so every later read must
     * come from this copy.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (RuntimeException ignored) {
                    // Unknown client charset: fall back to UTF-8.
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedBodyInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("ReadListener is not supported");
        }
    }
}
