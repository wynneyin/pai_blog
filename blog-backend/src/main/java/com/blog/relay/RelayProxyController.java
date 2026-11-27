package com.blog.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI compatible proxy. Unlike the rest of the relay API these endpoints
 * answer with OpenAI-shaped errors instead of the ApiResponse envelope.
 */
@Slf4j
@RestController
public class RelayProxyController {

    private static final String INVALID_KEY_MESSAGE = "无效的 API Key";
    private static final String INSUFFICIENT_MESSAGE = "余额不足，请先充值";
    private static final String UPSTREAM_NOT_CONFIGURED_MESSAGE = "中转上游未配置";
    private static final String INVALID_JSON_MESSAGE = "请求体不是合法 JSON";
    private static final String MODEL_NOT_FOUND_MESSAGE = "模型不存在";
    private static final String UPSTREAM_ERROR_MESSAGE = "上游请求失败";
    private static final String COST_HEADER = "X-Relay-Cost-Cents";

    private final RelayService relayService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String upstreamBaseUrl;
    private final String upstreamKey;

    public RelayProxyController(
            RelayService relayService,
            ObjectMapper objectMapper,
            @Value("${blog.relay.upstream-url:}") String upstreamUrl,
            @Value("${blog.relay.upstream-key:}") String upstreamKey) {
        this.relayService = relayService;
        this.objectMapper = objectMapper;
        this.upstreamBaseUrl = normalizeBaseUrl(upstreamUrl);
        this.upstreamKey = upstreamKey == null ? "" : upstreamKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @GetMapping("/v1/models")
    public ResponseEntity<Map<String, Object>> listModels(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        RelayKey key;
        try {
            key = relayService.findActiveApiKey(authorization);
        } catch (RelayException e) {
            return openAiError(500, e.getMessage(), "server_error", "store_error");
        }
        if (key == null) {
            return openAiError(401, INVALID_KEY_MESSAGE, "invalid_request_error", "invalid_api_key");
        }

        JsonNode catalog;
        try {
            catalog = relayService.getCatalog();
        } catch (RelayException e) {
            return openAiError(500, e.getMessage(), "server_error", "catalog_error");
        }

        List<Map<String, Object>> data = new ArrayList<>();
        for (JsonNode model : catalog.path("models")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", model.path("name").asText(""));
            item.put("object", "model");
            item.put("created", 0);
            item.put("owned_by", model.path("vendor").asText(""));
            data.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", data);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) byte[] body,
            HttpServletResponse response) {

        RelayKey key;
        try {
            key = relayService.findActiveApiKey(authorization);
        } catch (RelayException e) {
            writeOpenAiError(response, 500, e.getMessage(), "server_error", "store_error");
            return;
        }
        if (key == null) {
            writeOpenAiError(response, 401, INVALID_KEY_MESSAGE, "invalid_request_error", "invalid_api_key");
            return;
        }

        RelayUser user;
        try {
            user = relayService.getUserById(key.getUserId());
        } catch (RelayException e) {
            writeOpenAiError(response, 500, e.getMessage(), "server_error", "store_error");
            return;
        }
        if (user == null) {
            writeOpenAiError(response, 401, INVALID_KEY_MESSAGE, "invalid_request_error", "invalid_api_key");
            return;
        }
        if (user.isDisabled() || user.getBalanceCents() <= 0) {
            writeOpenAiError(response, 402, INSUFFICIENT_MESSAGE, "insufficient_quota", "insufficient_quota");
            return;
        }
        if (upstreamBaseUrl.isEmpty()) {
            writeOpenAiError(response, 503, UPSTREAM_NOT_CONFIGURED_MESSAGE,
                    "server_error", "upstream_not_configured");
            return;
        }

        JsonNode payload;
        try {
            payload = body == null ? null : objectMapper.readTree(body);
        } catch (IOException e) {
            payload = null;
        }
        if (payload == null || !payload.isObject()) {
            writeOpenAiError(response, 400, INVALID_JSON_MESSAGE, "invalid_request_error", "invalid_json");
            return;
        }

        JsonNode modelNameNode = payload.get("model");
        String modelName = modelNameNode != null && modelNameNode.isTextual() ? modelNameNode.asText() : "";

        JsonNode catalog;
        try {
            catalog = relayService.getCatalog();
        } catch (RelayException e) {
            writeOpenAiError(response, 500, e.getMessage(), "server_error", "catalog_error");
            return;
        }
        JsonNode model = findModel(catalog, modelName);
        if (model == null) {
            writeOpenAiError(response, 400, MODEL_NOT_FOUND_MESSAGE, "invalid_request_error", "model_not_found");
            return;
        }

        boolean stream = payload.path("stream").isBoolean() && payload.path("stream").asBoolean(false);
        HttpRequest upstreamRequest;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(
                            URI.create(upstreamBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!upstreamKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + upstreamKey);
            }
            upstreamRequest = requestBuilder.build();
        } catch (IllegalArgumentException e) {
            log.error("Relay upstream URL is invalid", e);
            writeOpenAiError(response, 502, UPSTREAM_ERROR_MESSAGE, "server_error", "upstream_error");
            return;
        }

        if (stream) {
            proxyStreaming(upstreamRequest, response, key, model, modelName);
        } else {
            proxyBuffered(upstreamRequest, response, key, model, modelName);
        }
    }

    private void proxyBuffered(HttpRequest request, HttpServletResponse response,
                               RelayKey key, JsonNode model, String modelName) {
        HttpResponse<byte[]> upstream;
        try {
            upstream = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeOpenAiError(response, 502, UPSTREAM_ERROR_MESSAGE, "server_error", "upstream_error");
            return;
        } catch (IOException | IllegalArgumentException e) {
            log.error("Relay upstream request failed", e);
            writeOpenAiError(response, 502, UPSTREAM_ERROR_MESSAGE, "server_error", "upstream_error");
            return;
        }

        int status = upstream.statusCode();
        byte[] bytes = upstream.body() == null ? new byte[0] : upstream.body();
        Integer costCents = null;
        if (isSuccess(status)) {
            long[] usage = parseUsage(bytes);
            if (usage != null) {
                costCents = RelayService.calculateCostCents(
                        model.path("input").asDouble(0),
                        model.path("output").asDouble(0),
                        usage[0], usage[1]);
                chargeQuietly(key, modelName, usage[0], usage[1], costCents);
            }
        }

        try {
            response.setStatus(status);
            response.setContentType(contentTypeOf(upstream, "application/json"));
            if (costCents != null) {
                response.setHeader(COST_HEADER, String.valueOf(costCents));
            }
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (IOException e) {
            log.debug("Relay client went away before the response was written: {}", e.getMessage());
        }
    }

    private void proxyStreaming(HttpRequest request, HttpServletResponse response,
                                RelayKey key, JsonNode model, String modelName) {
        HttpResponse<InputStream> upstream;
        try {
            upstream = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeOpenAiError(response, 502, UPSTREAM_ERROR_MESSAGE, "server_error", "upstream_error");
            return;
        } catch (IOException | IllegalArgumentException e) {
            log.error("Relay upstream streaming request failed", e);
            writeOpenAiError(response, 502, UPSTREAM_ERROR_MESSAGE, "server_error", "upstream_error");
            return;
        }

        int status = upstream.statusCode();
        boolean success = isSuccess(status);
        long[] usage = new long[]{-1L, -1L};
        InputStream upstreamBody = upstream.body();

        try {
            response.setStatus(status);
            response.setContentType(contentTypeOf(upstream, "text/event-stream"));
            try (InputStream input = upstreamBody == null ? InputStream.nullInputStream() : upstreamBody) {
                pipeStreaming(input, response.getOutputStream(), success, usage);
            }
        } catch (IOException e) {
            log.debug("Relay streaming response ended early: {}", e.getMessage());
        } finally {
            if (success && usage[0] >= 0 && usage[1] >= 0) {
                int cost = RelayService.calculateCostCents(
                        model.path("input").asDouble(0),
                        model.path("output").asDouble(0),
                        usage[0], usage[1]);
                chargeQuietly(key, modelName, usage[0], usage[1], cost);
            }
        }
    }

    private void pipeStreaming(InputStream input, OutputStream output,
                               boolean scanUsage, long[] usage) throws IOException {
        byte[] buffer = new byte[8192];
        StringBuilder pending = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            output.flush();
            if (!scanUsage) {
                continue;
            }
            pending.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            int newline;
            while ((newline = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, newline);
                pending.delete(0, newline + 1);
                scanSseLine(line, usage);
            }
        }
        if (scanUsage && pending.length() > 0) {
            scanSseLine(pending.toString(), usage);
        }
    }

    private void scanSseLine(String rawLine, long[] usage) {
        String line = rawLine.strip();
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode usageNode = node == null ? null : node.get("usage");
            if (usageNode != null && usageNode.isObject()) {
                JsonNode prompt = usageNode.get("prompt_tokens");
                JsonNode completion = usageNode.get("completion_tokens");
                if (prompt != null && prompt.isNumber()
                        && completion != null && completion.isNumber()) {
                    usage[0] = prompt.asLong();
                    usage[1] = completion.asLong();
                }
            }
        } catch (IOException ignored) {
            // keep-alive comments and partial lines are not JSON
        }
    }

    private long[] parseUsage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(bytes);
            JsonNode usageNode = node == null ? null : node.get("usage");
            if (usageNode == null || !usageNode.isObject()) {
                return null;
            }
            JsonNode prompt = usageNode.get("prompt_tokens");
            JsonNode completion = usageNode.get("completion_tokens");
            if (prompt == null || !prompt.isNumber()
                    || completion == null || !completion.isNumber()) {
                return null;
            }
            return new long[]{prompt.asLong(), completion.asLong()};
        } catch (IOException e) {
            return null;
        }
    }

    private void chargeQuietly(RelayKey key, String model, long promptTokens,
                               long completionTokens, int costCents) {
        try {
            relayService.recordUsage(key.getUserId(), key.getId(), model,
                    promptTokens, completionTokens, costCents);
        } catch (RuntimeException e) {
            // Billing must never turn a delivered upstream response into an error.
            log.error("Failed to record relay usage for key {}", key.getId(), e);
        }
    }

    private static JsonNode findModel(JsonNode catalog, String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return null;
        }
        JsonNode models = catalog.path("models");
        if (!models.isArray()) {
            return null;
        }
        for (JsonNode model : models) {
            if (modelName.equals(model.path("name").asText(""))) {
                return model;
            }
        }
        return null;
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static String contentTypeOf(HttpResponse<?> response, String fallback) {
        return response.headers().firstValue("Content-Type")
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    private static String normalizeBaseUrl(String url) {
        String value = url == null ? "" : url.trim();
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private void writeOpenAiError(HttpServletResponse response, int status,
                                  String message, String type, String code) {
        if (response.isCommitted()) {
            return;
        }
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(openAiErrorBody(message, type, code));
            response.reset();
            response.setStatus(status);
            response.setContentType("application/json");
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.debug("Failed to write relay proxy error: {}", e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> openAiError(int status, String message,
                                                            String type, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(openAiErrorBody(message, type, code));
    }

    private static Map<String, Object> openAiErrorBody(String message, String type, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
