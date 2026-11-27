package com.blog.relay;

import com.blog.dto.ApiResponse;
import com.blog.mail.MailGateway;
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

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final MailGateway mailGateway;

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

    @PostMapping("/auth/forgot-password")
    public ResponseEntity<ApiResponse<?>> forgotPassword(
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        if (!mailGateway.enabled()) {
            return ResponseEntity.status(503)
                    .body(ApiResponse.error(503, "邮件服务未配置，请联系管理员"));
        }
        RelayService.PasswordResetTicket ticket = relayService.createPasswordReset(
                textField(body, "email"), ClientIp.resolve(request));
        if (ticket != null) {
            String link = mailGateway.siteBaseUrl()
                    + "/relay/reset-password#token=" + ticket.token();
            String text = """
                    你好，

                    我们收到了重置 Wynne Relay 登录密码的请求。请打开下面的链接设置新密码：

                    %s

                    链接 30 分钟内有效，只能使用一次。如果这不是你发起的，可以忽略这封邮件，原密码不会被修改。
                    """.formatted(link);
            mailGateway.send(ticket.email(), "重置 Wynne Relay 登录密码", text);
        }
        // Always the same answer: unknown and known addresses are indistinguishable.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/auth/reset-password")
    public ResponseEntity<ApiResponse<?>> resetPassword(
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(relayService.resetPassword(
                textField(body, "token"),
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
