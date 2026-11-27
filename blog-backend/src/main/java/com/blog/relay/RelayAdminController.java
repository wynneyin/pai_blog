package com.blog.relay;

import com.blog.dto.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relay administration under /api/admin/relay. Uses the same X-Admin-Token /
 * BLOG_ADMIN_TOKEN convention as the shop admin endpoints.
 */
@RestController
@RequestMapping("/api/admin/relay")
@RequiredArgsConstructor
public class RelayAdminController {

    private final RelayService relayService;

    @Value("${blog.admin-token:}")
    private String adminToken;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<?>> listUsers(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String limit) {
        verifyAdminToken(token);
        return ResponseEntity.ok(ApiResponse.success(relayService.adminListUsers(limit)));
    }

    @GetMapping("/topups")
    public ResponseEntity<ApiResponse<?>> listTopups(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit) {
        verifyAdminToken(token);
        return ResponseEntity.ok(ApiResponse.success(
                relayService.adminListTopups(status, limit)));
    }

    @PostMapping("/topups/{orderNo}/status")
    public ResponseEntity<ApiResponse<?>> updateTopupStatus(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String orderNo,
            @RequestBody(required = false) JsonNode body) {
        verifyAdminToken(token);
        String status = null;
        if (body != null && body.isObject()) {
            JsonNode value = body.get("status");
            if (value != null && value.isTextual()) {
                status = value.asText();
            }
        }
        return ResponseEntity.ok(ApiResponse.success(
                relayService.adminUpdateTopupStatus(orderNo, status)));
    }

    private void verifyAdminToken(String token) {
        if (adminToken != null && !adminToken.isBlank() && !adminToken.equals(token)) {
            throw new RelayException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
    }
}
