package com.blog.relay;

import com.blog.dto.ApiResponse;
import com.blog.web.ClientIp;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public relay account endpoints under /api/relay, using the standard Java
 * {code,message,data} envelope. The OpenAI compatible proxy lives in
 * {@link RelayProxyController} and uses its own error shape.
 */
@RestController
@RequestMapping("/api/relay")
@RequiredArgsConstructor
public class RelayController {

    private final RelayService relayService;

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<?>> getCatalog() {
        return ResponseEntity.ok(ApiResponse.success(relayService.getCatalog()));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<?>> register(
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(relayService.register(
                textField(body, "email"),
                textField(body, "password"),
                ClientIp.resolve(request))));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<?>> login(
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(relayService.login(
                textField(body, "email"),
                textField(body, "password"),
                ClientIp.resolve(request))));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<?>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok(ApiResponse.success(relayService.logout(authorization)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<?>> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok(ApiResponse.success(relayService.me(authorization)));
    }

    @GetMapping("/keys")
    public ResponseEntity<ApiResponse<?>> listKeys(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok(ApiResponse.success(relayService.listKeys(authorization)));
    }

    @PostMapping("/keys")
    public ResponseEntity<ApiResponse<?>> createKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) JsonNode body) {
        JsonNode nameNode = body != null && body.isObject() ? body.get("name") : null;
        boolean nameInvalid = nameNode != null && !nameNode.isNull() && !nameNode.isTextual();
        String name = nameNode != null && nameNode.isTextual() ? nameNode.asText() : null;
        return ResponseEntity.ok(ApiResponse.success(
                relayService.createKey(authorization, name, nameInvalid)));
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<ApiResponse<?>> revokeKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(relayService.revokeKey(authorization, id)));
    }

    @GetMapping("/topups")
    public ResponseEntity<ApiResponse<?>> listTopups(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok(ApiResponse.success(relayService.listTopups(authorization)));
    }

    @PostMapping("/topups")
    public ResponseEntity<ApiResponse<?>> createTopup(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.ok(ApiResponse.success(
                relayService.createTopup(authorization, textField(body, "planId"))));
    }

    @PostMapping("/topups/{orderNo}/pay")
    public ResponseEntity<ApiResponse<?>> payTopup(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String orderNo,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.ok(ApiResponse.success(
                relayService.payTopup(authorization, orderNo, textField(body, "method"))));
    }

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<?>> listUsage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String limit) {
        return ResponseEntity.ok(ApiResponse.success(
                relayService.listUsage(authorization, limit)));
    }

    private static String textField(JsonNode body, String field) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
