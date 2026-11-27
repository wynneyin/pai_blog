package com.blog.relay;

import com.blog.mail.MailGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP level checks for the /api/relay, /api/admin/relay and /v1 contracts:
 * envelopes, status codes, auth headers and the OpenAI shaped proxy errors.
 */
class RelayHttpTest {

    private static final String PASSWORD = "unit-test-password";
    private static final String NEW_PASSWORD = PASSWORD + "-v2";
    private static final String ADMIN_TOKEN = "unit-test-admin-token";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path catalogPath;
    private Path storePath;
    private RelayService service;
    private MockMvc mvc;
    private HttpServer upstream;
    private final TestMailGateway mail = new TestMailGateway();

    @BeforeEach
    void setUp() throws IOException {
        catalogPath = tempDir.resolve("shop").resolve("relay.json");
        storePath = tempDir.resolve("data").resolve("relay-store.json");
        Files.createDirectories(catalogPath.getParent());
        Files.writeString(catalogPath, catalogJson(), StandardCharsets.UTF_8);
        service = new RelayService(objectMapper, catalogPath.toString(), storePath.toString());
        mvc = buildMvc("", "");
    }

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
    }

    @Test
    void catalogAndAuthEndpointsUseTheJavaEnvelope() throws Exception {
        mvc.perform(get("/api/relay/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.brand.name").value("Wynne Relay"))
                .andExpect(jsonPath("$.data.models[0].name").value("gpt-4o-mini"));

        MvcResult registered = mvc.perform(post("/api/relay/auth/register")
                        .contentType(JSON)
                        .content("{\"email\":\"Http@Example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = body(registered).path("data");
        String token = data.path("token").asText();
        assertTrue(token.matches("rs_[0-9a-f]{48}"));
        assertEquals("http@example.com", data.path("user").path("email").asText());
        assertEquals(0, data.path("user").path("balanceCents").asInt());

        MvcResult malformed = mvc.perform(post("/api/relay/auth/register")
                        .contentType(JSON)
                        .content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals(400, body(malformed).path("code").asInt());
        assertEquals("请求参数不合法", body(malformed).path("message").asText());

        mvc.perform(get("/api/relay/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mvc.perform(get("/api/relay/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value("http@example.com"));

        mvc.perform(post("/api/relay/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        mvc.perform(get("/api/relay/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keysTopupsAndAdminEndpointsFollowTheContract() throws Exception {
        String token = registerHttp("admin-flow@example.com");
        String key = fundWithPlanAndCreateKey(token, "p50");

        mvc.perform(get("/api/relay/keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].prefix").value(key.substring(0, 16)));

        MvcResult badName = mvc.perform(post("/api/relay/keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"name\":123}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals("备注不合法", body(badName).path("message").asText());

        MvcResult topup = mvc.perform(post("/api/relay/topups")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"planId\":\"p10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amountCents").value(1000))
                .andReturn();
        String orderNo = body(topup).path("data").path("orderNo").asText();
        assertTrue(orderNo.matches("RT\\d{14}[A-Z0-9]{4}"));

        mvc.perform(get("/api/relay/topups").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mvc.perform(get("/api/relay/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mvc.perform(get("/api/admin/relay/users"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/relay/users").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].keyCount").value(1))
                .andExpect(jsonPath("$.data.list[0].paidTopupCents").value(5000));

        mvc.perform(get("/api/admin/relay/topups")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .param("status", "paid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].email").value("admin-flow@example.com"));

        mvc.perform(post("/api/admin/relay/topups/" + orderNo + "/status")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(JSON)
                        .content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled"));

        mvc.perform(post("/api/relay/topups/" + orderNo + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"method\":\"alipay\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        MvcResult cancelled = mvc.perform(post("/api/relay/topups/" + orderNo + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"method\":\"alipay\"}"))
                .andReturn();
        assertEquals("订单已取消", body(cancelled).path("message").asText());
    }

    @Test
    void oversizedBodiesAreRejectedBeforeAuthenticationAndParsing() throws Exception {
        String huge = "{\"email\":\"" + "a".repeat(RelayBodySizeLimitFilter.MAX_BODY_BYTES) + "\"}";

        MvcResult relay = mvc.perform(post("/api/relay/auth/register")
                        .contentType(JSON)
                        .content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andReturn();
        assertEquals(413, body(relay).path("code").asInt());
        assertEquals("请求体过大", body(relay).path("message").asText());
        // The oversized request never reached registration, so no account exists.
        mvc.perform(get("/api/admin/relay/users").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // Unauthenticated proxy requests are capped too, with the OpenAI shape.
        mvc.perform(post("/v1/chat/completions")
                        .contentType(JSON)
                        .content(huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.message").value("请求体过大"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("request_too_large"));
    }

    @Test
    void proxyRejectsBadKeysAndReportsConfigurationErrors() throws Exception {
        mvc.perform(get("/v1/models"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));

        mvc.perform(post("/v1/chat/completions")
                        .contentType(JSON)
                        .content("{\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("无效的 API Key"));

        String token = registerHttp("proxy@example.com");
        String key = fundWithPlanAndCreateKey(token, "p50");
        String auth = "Bearer " + key;

        mvc.perform(get("/v1/models").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.data[0].object").value("model"))
                .andExpect(jsonPath("$.data[0].created").value(0))
                .andExpect(jsonPath("$.data[0].owned_by").value("OpenAI"));

        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", auth)
                        .contentType(JSON)
                        .content("{\"model\":\"gpt-4o-mini\",\"messages\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("upstream_not_configured"));

        String poorToken = registerHttp("poor@example.com");
        String poorKey = (String) body(mvc.perform(post("/api/relay/keys")
                        .header("Authorization", "Bearer " + poorToken)
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("key").asText();
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + poorKey)
                        .contentType(JSON)
                        .content("{\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.code").value("insufficient_quota"));
    }

    @Test
    void proxyValidatesBodyAndModelBeforeForwarding() throws Exception {
        String token = registerHttp("validate@example.com");
        String key = fundWithPlanAndCreateKey(token, "p50");
        MockMvc proxyMvc = buildMvc("http://127.0.0.1:1/v1", "");

        MvcResult broken = proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals("invalid_json", body(broken).path("error").path("code").asText());

        MvcResult arrayBody = proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals("invalid_json", body(arrayBody).path("error").path("code").asText());

        proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("{\"model\":\"missing-model\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));

        proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("{\"model\":123}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("model_not_found"));

        proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("{\"model\":\"gpt-4o-mini\",\"messages\":[]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("upstream_error"));

        assertEquals(5500, balanceOf(token));
    }

    @Test
    void proxyForwardsBufferedRequestsAndChargesTheModelPrice() throws Exception {
        AtomicReference<String> forwardedBody = new AtomicReference<>();
        AtomicReference<String> forwardedAuth = new AtomicReference<>();
        AtomicReference<String> forwardedContentType = new AtomicReference<>();
        String responseJson = "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}";

        String baseUrl = startUpstream(exchange -> {
            forwardedBody.set(readAll(exchange));
            forwardedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            forwardedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            writeResponse(exchange, 200, "application/json", responseJson.getBytes(StandardCharsets.UTF_8));
        });

        String token = registerHttp("forward@example.com");
        String key = fundWithPlanAndCreateKey(token, "p50");
        MockMvc proxyMvc = buildMvc(baseUrl, "upstream-secret");
        String requestJson = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        MvcResult result = proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Relay-Cost-Cents", "1"))
                .andExpect(content().json(responseJson))
                .andReturn();

        assertEquals(requestJson, forwardedBody.get());
        assertEquals("Bearer upstream-secret", forwardedAuth.get());
        assertTrue(forwardedContentType.get().startsWith("application/json"));
        assertEquals(5499, balanceOf(token));

        JsonNode usage = body(mvc.perform(get("/api/relay/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertEquals(1, usage.path("total").asInt());
        assertEquals(1, usage.path("list").get(0).path("costCents").asInt());
        assertEquals(10, usage.path("list").get(0).path("promptTokens").asInt());
        assertEquals(20, usage.path("list").get(0).path("completionTokens").asInt());
        assertFalse(result.getResponse().getHeader("X-Relay-Cost-Cents").isEmpty());
    }

    @Test
    void proxyPassesThroughUpstreamErrorsWithoutCharging() throws Exception {
        String errorJson = "{\"error\":{\"message\":\"rate limited\"}}";
        String baseUrl = startUpstream(exchange ->
                writeResponse(exchange, 429, "application/json", errorJson.getBytes(StandardCharsets.UTF_8)));

        String token = registerHttp("upstream-error@example.com");
        String key = fundWithPlanAndCreateKey(token, "p50");
        MockMvc proxyMvc = buildMvc(baseUrl, "");

        MvcResult result = proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("{\"model\":\"gpt-4o-mini\",\"messages\":[]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json(errorJson))
                .andReturn();

        assertNull(result.getResponse().getHeader("X-Relay-Cost-Cents"));
        assertEquals(5500, balanceOf(token));
        assertEquals(0, body(mvc.perform(get("/api/relay/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("total").asInt());
    }

    @Test
    void proxyStreamsSseAndChargesTheLastUsage() throws Exception {
        String baseUrl = startUpstream(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.write(("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                        + "\"completion_tokens\":50}}\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });

        String token = registerHttp("stream@example.com");
        String key = fundWithPlanAndCreateKey(token, "p50");
        MockMvc proxyMvc = buildMvc(baseUrl, "");

        MvcResult result = proxyMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(JSON)
                        .content("{\"model\":\"gpt-4o-mini\",\"stream\":true,\"messages\":[]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream"))
                .andReturn();

        String streamed = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(streamed.contains("data: [DONE]"), streamed);
        assertNull(result.getResponse().getHeader("X-Relay-Cost-Cents"));
        assertEquals(5499, balanceOf(token));

        JsonNode usage = body(mvc.perform(get("/api/relay/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertEquals(1, usage.path("total").asInt());
        assertEquals(100, usage.path("list").get(0).path("promptTokens").asInt());
        assertEquals(50, usage.path("list").get(0).path("completionTokens").asInt());
        assertEquals(1, usage.path("list").get(0).path("costCents").asInt());
    }

    @Test
    void forgotPasswordRequiresConfiguredMail() throws Exception {
        mvc.perform(post("/api/relay/auth/forgot-password")
                        .contentType(JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("邮件服务未配置，请联系管理员"));
    }

    @Test
    void passwordResetEmailIsSingleUseAndRevokesOldSessions() throws Exception {
        String oldToken = registerHttp("reset@example.com");
        mail.enabled = true;

        mvc.perform(post("/api/relay/auth/forgot-password")
                        .contentType(JSON)
                        .content("{\"email\":\"reset@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true));

        assertEquals(1, mail.sent.size());
        Matcher matcher = Pattern.compile("#token=(pr_[0-9a-f]{64})")
                .matcher(mail.sent.get(0)[2]);
        assertTrue(matcher.find(), "reset link must carry a token");
        String resetToken = matcher.group(1);

        mvc.perform(post("/api/relay/auth/reset-password")
                        .contentType(JSON)
                        .content("{\"token\":\"" + resetToken
                                + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reset").value(true));

        mvc.perform(get("/api/relay/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/relay/auth/login")
                        .contentType(JSON)
                        .content("{\"email\":\"reset@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/relay/auth/login")
                        .contentType(JSON)
                        .content("{\"email\":\"reset@example.com\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/relay/auth/reset-password")
                        .contentType(JSON)
                        .content("{\"token\":\"" + resetToken
                                + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("重置链接无效或已过期"));
    }

    @Test
    void forgotPasswordDoesNotRevealUnknownAddresses() throws Exception {
        mail.enabled = true;

        mvc.perform(post("/api/relay/auth/forgot-password")
                        .contentType(JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sent").value(true));

        assertTrue(mail.sent.isEmpty());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MockMvc buildMvc(String upstreamUrl, String upstreamKey) {
        RelayAdminController adminController = new RelayAdminController(service);
        ReflectionTestUtils.setField(adminController, "adminToken", ADMIN_TOKEN);
        return MockMvcBuilders.standaloneSetup(
                        new RelayController(service, mail),
                        adminController,
                        new RelayProxyController(service, objectMapper, upstreamUrl, upstreamKey))
                .setControllerAdvice(new RelayExceptionHandler())
                .addFilters(new RelayBodySizeLimitFilter(objectMapper))
                .build();
    }

    static final class TestMailGateway implements MailGateway {

        boolean enabled;
        String siteBaseUrl = "https://example.test";
        final List<String[]> sent = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String siteBaseUrl() {
            return siteBaseUrl;
        }

        @Override
        public boolean send(String to, String subject, String text) {
            sent.add(new String[]{to, subject, text});
            return true;
        }
    }

    private String registerHttp(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/relay/auth/register")
                        .contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("token").asText();
    }

    private String fundWithPlanAndCreateKey(String token, String planId) throws Exception {
        MvcResult topup = mvc.perform(post("/api/relay/topups")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"planId\":\"" + planId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String orderNo = body(topup).path("data").path("orderNo").asText();
        mvc.perform(post("/api/relay/topups/" + orderNo + "/pay")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{\"method\":\"alipay\"}"))
                .andExpect(status().isOk());
        MvcResult created = mvc.perform(post("/api/relay/keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return body(created).path("data").path("key").asText();
    }

    private int balanceOf(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/relay/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("user").path("balanceCents").asInt();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String startUpstream(HttpHandler handler) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", handler);
        upstream.setExecutor(null);
        upstream.start();
        return "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1";
    }

    private static String readAll(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeResponse(HttpExchange exchange, int status,
                                      String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String catalogJson() {
        return """
                {
                  "brand": {"name": "Wynne Relay", "baseUrl": "", "consoleUrl": "/relay/console", "supportUrl": ""},
                  "stats": [],
                  "models": [
                    {"name": "gpt-4o-mini", "vendor": "OpenAI", "context": "128K", "input": 1.08, "output": 4.32, "tag": "性价比"},
                    {"name": "test-model", "vendor": "Test", "context": "8K", "input": 18, "output": 72, "tag": ""}
                  ],
                  "plans": [
                    {"id": "p10", "name": "入门充值", "price": 10, "credit": 10, "bonus": "", "hot": false, "perks": []},
                    {"id": "p50", "name": "标准充值", "price": 50, "credit": 55, "bonus": "", "hot": true, "perks": []}
                  ],
                  "faqs": [],
                  "codeSamples": [],
                  "notices": []
                }
                """;
    }
}
