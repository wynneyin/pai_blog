package com.blog.shop;

import com.blog.dto.ApiResponse;
import com.blog.web.ClientIp;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-client fixed-window limits for the shop. Order lookup and payment are
 * deliberately stricter than the catalog: they are the endpoints that can be
 * used to guess order numbers or drain a balance if a credential leaks.
 *
 * <p>The window table is bounded (LRU) so rotating client addresses cannot grow
 * it without limit. Paths are normalized to drop matrix parameters
 * ({@code /api/shop;x=1/orders}), which Spring's PathPattern ignores but a raw
 * URI comparison would not. HEAD is treated like GET and OPTIONS is never
 * limited, matching the Node backend's route-level middleware.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ShopRateLimitFilter extends OncePerRequestFilter {

    static final long WINDOW_MS = 60_000L;
    static final int CREATE_LIMIT = 10;
    static final int LOOKUP_LIMIT = 30;
    static final int PAY_LIMIT = 10;
    static final int CATALOG_LIMIT = 120;
    static final int ADMIN_LIMIT = 60;
    static final int MAX_WINDOWS = 20_000;
    static final String TOO_MANY_MESSAGE = "请求过于频繁，请稍后再试";

    private static final Rule CREATE = new Rule("create", CREATE_LIMIT);
    private static final Rule LOOKUP = new Rule("lookup", LOOKUP_LIMIT);
    private static final Rule PAY = new Rule("pay", PAY_LIMIT);
    private static final Rule CATALOG = new Rule("catalog", CATALOG_LIMIT);
    private static final Rule ADMIN = new Rule("admin", ADMIN_LIMIT);

    private final ObjectMapper objectMapper;
    private final Map<String, Window> windows = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > MAX_WINDOWS;
        }
    };

    public ShopRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return ruleFor(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Rule rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        String key = rule.name() + "|" + ClientIp.resolve(request);
        Window window;
        synchronized (windows) {
            if (windows.size() >= MAX_WINDOWS) {
                sweep(now);
            }
            Window current = windows.get(key);
            if (current == null || now - current.start >= WINDOW_MS) {
                current = new Window(now);
                windows.put(key, current);
            } else {
                current.count += 1;
            }
            window = current;
        }

        if (window.count > rule.limit()) {
            long retryAfter = Math.max(1L, (window.start + WINDOW_MS - now + 999L) / 1000L);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isEmpty()) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
                response.setHeader("Access-Control-Expose-Headers", "Retry-After");
            }
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error(429, TOO_MANY_MESSAGE));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static Rule ruleFor(HttpServletRequest request) {
        String path = normalizePath(request.getRequestURI());
        String method = request.getMethod();
        if (path == null || "OPTIONS".equals(method)) {
            return null;
        }
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if ("POST".equals(method) && "/api/shop/orders".equals(path)) {
            return CREATE;
        }
        if ("POST".equals(method) && path.matches("^/api/shop/orders/[^/]+/pay/?$")) {
            return PAY;
        }
        if (read && path.matches("^/api/shop/orders/[^/]+/?$")) {
            return LOOKUP;
        }
        if (read && "/api/shop/catalog".equals(path)) {
            return CATALOG;
        }
        if (path.equals("/api/admin/shop") || path.startsWith("/api/admin/shop/")) {
            return ADMIN;
        }
        return null;
    }

    /** Drops Servlet matrix parameters, which Spring's path matching ignores. */
    static String normalizePath(String uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.replace("%3B", ";").replace("%3b", ";");
        return path.indexOf(';') < 0 ? path : path.replaceAll(";[^/]*", "");
    }

    private void sweep(long now) {
        windows.entrySet().removeIf(entry -> now - entry.getValue().start >= WINDOW_MS);
    }

    private record Rule(String name, int limit) {
    }

    private static final class Window {
        private final long start;
        private int count = 1;

        private Window(long start) {
            this.start = start;
        }
    }
}
