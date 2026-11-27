package com.blog.shop;

import com.blog.relay.RelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for buyer-facing order lookup and payment authorization, plus
 * the shop-specific rate limiter.
 */
class ShopHttpTest {

    private static final String EMAIL = "buyer@example.com";
    private static final String OTHER_EMAIL = "other@example.com";
    private static final String PASSWORD = "shop-http-pass";
    private static final String ADMIN_TOKEN = "shop-http-admin";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path shopCatalogPath;
    private Path shopOrdersPath;
    private Path relayCatalogPath;
    private Path relayDataPath;
    private RelayService relayService;
    private ShopService shopService;
    private MockMvc mvc;
    private String ownerToken;

    @BeforeEach
    void setUp() throws IOException {
        shopCatalogPath = tempDir.resolve("shop").resolve("catalog.json");
        shopOrdersPath = tempDir.resolve("data").resolve("shop-orders.json");
        relayCatalogPath = tempDir.resolve("shop").resolve("relay.json");
        relayDataPath = tempDir.resolve("data").resolve("relay-store.json");
        Files.createDirectories(shopCatalogPath.getParent());
        Files.writeString(shopCatalogPath, shopCatalogJson(), StandardCharsets.UTF_8);
        Files.writeString(relayCatalogPath, relayCatalogJson(), StandardCharsets.UTF_8);

        relayService = new RelayService(objectMapper, relayCatalogPath.toString(), relayDataPath.toString());
        shopService = new ShopService(objectMapper, relayService,
                shopCatalogPath.toString(), shopOrdersPath.toString());
        ShopAdminController adminController = new ShopAdminController(shopService);
        ReflectionTestUtils.setField(adminController, "adminToken", ADMIN_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ShopController(shopService),
                        adminController)
                .setControllerAdvice(new ShopExceptionHandler())
                .addFilters(new ShopRateLimitFilter(objectMapper))
                .build();
        ownerToken = register(EMAIL);
    }

    @Test
    void createReturnsOneTimeLookupTokenWithoutStoringItInTheResponse() throws Exception {
        JsonNode created = createOrder("relay-p10", 1, EMAIL);

        assertTrue(created.path("lookupToken").asText().startsWith("so_"));
        assertEquals(67, created.path("lookupToken").asText().length());
        assertFalse(created.has("lookupTokenHash"));
        assertTrue(Files.readString(shopOrdersPath, StandardCharsets.UTF_8)
                .contains("\"lookupTokenHash\""));
        assertFalse(Files.readString(shopOrdersPath, StandardCharsets.UTF_8)
                .contains(created.path("lookupToken").asText()));
    }

    @Test
    void lookupRequiresTokenOrOwningRelaySession() throws Exception {
        JsonNode created = createOrder("relay-p10", 1, EMAIL);
        String orderNo = created.path("orderNo").asText();
        String lookupToken = created.path("lookupToken").asText();

        mvc.perform(get("/api/shop/orders/" + orderNo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("订单不存在"));

        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("X-Order-Token", "so_wrong"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("X-Order-Token", lookupToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.contact").value(EMAIL))
                .andExpect(jsonPath("$.data.lookupTokenHash").doesNotExist());

        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // A stale token header must not mask the owning relay session.
        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("X-Order-Token", "so_wrong")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        String otherToken = register(OTHER_EMAIL);
        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paymentRequiresProofAndCreditsOnce() throws Exception {
        JsonNode created = createOrder("relay-p10", 1, EMAIL);
        String orderNo = created.path("orderNo").asText();
        String lookupToken = created.path("lookupToken").asText();

        mvc.perform(post("/api/shop/orders/" + orderNo + "/pay")
                        .contentType(JSON)
                        .content("{\"method\":\"alipay\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/shop/orders/" + orderNo + "/pay")
                        .header("X-Order-Token", lookupToken)
                        .contentType(JSON)
                        .content("{\"method\":\"alipay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("paid"))
                .andExpect(jsonPath("$.data.relayCreditedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lookupTokenHash").doesNotExist());

        mvc.perform(post("/api/shop/orders/" + orderNo + "/pay")
                        .header("X-Order-Token", lookupToken)
                        .contentType(JSON)
                        .content("{\"method\":\"wechat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("paid"));

        assertEquals(1000, balanceCents(ownerToken));
    }

    @Test
    void relaySessionCanQueryItsOwnNonRelayOrder() throws Exception {
        // Generic products have no relayUserId, so the lookup token is the only
        // buyer credential; a relay session must not unlock them.
        JsonNode created = createOrder("generic", 1, "13800138000");
        String orderNo = created.path("orderNo").asText();

        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/shop/orders/" + orderNo)
                        .header("X-Order-Token", created.path("lookupToken").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void disabledSelfPayReturnsForbiddenBeforeAnyLookup() throws Exception {
        shopService.setAllowSelfPay(false);

        mvc.perform(post("/api/shop/orders/PB202601010000ZZZZ/pay")
                        .contentType(JSON)
                        .content("{\"method\":\"alipay\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("请支付后联系管理员确认到账"));
    }

    @Test
    void adminEndpointsStillRequireTheAdminToken() throws Exception {
        mvc.perform(get("/api/admin/shop/orders"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/admin/shop/orders")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void adminResponsesDoNotLeakTheCredentialHash() throws Exception {
        JsonNode created = createOrder("relay-p10", 1, EMAIL);
        String orderNo = created.path("orderNo").asText();

        mvc.perform(get("/api/admin/shop/orders")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.list[0].lookupTokenHash").doesNotExist());

        mvc.perform(post("/api/admin/shop/orders/" + orderNo + "/status")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(JSON)
                        .content("{\"status\":\"paid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lookupTokenHash").doesNotExist());
    }

    @Test
    void createOrderIsRateLimitedPerClient() throws Exception {
        for (int i = 0; i < ShopRateLimitFilter.CREATE_LIMIT; i++) {
            createOrder("relay-p10", 1, EMAIL);
        }

        mvc.perform(post("/api/shop/orders")
                        .contentType(JSON)
                        .content(orderBody("relay-p10", 1, EMAIL)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后再试"));
    }

    private String register(String email) {
        java.util.Map<String, Object> result = relayService.register(email, PASSWORD, "203.0.113.9");
        return (String) result.get("token");
    }

    private JsonNode createOrder(String productId, int quantity, String contact) throws Exception {
        MvcResult result = mvc.perform(post("/api/shop/orders")
                        .contentType(JSON)
                        .content(orderBody(productId, quantity, contact)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private static String orderBody(String productId, int quantity, String contact) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + ",\"contact\":\"" + contact + "\"}";
    }

    private int balanceCents(String token) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> user =
                (java.util.Map<String, Object>) relayService.me("Bearer " + token).get("user");
        return (Integer) user.get("balanceCents");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String shopCatalogJson() {
        return """
                {
                  "categories": [{"id": "relay", "name": "中转额度"}],
                  "products": [
                    {"id": "relay-p10", "categoryId": "relay", "name": "中转额度 ¥10",
                     "price": 10, "stock": 999, "relayPlanId": "p10"},
                    {"id": "generic", "categoryId": "relay", "name": "测试商品",
                     "price": 9.9, "stock": 999}
                  ],
                  "notices": []
                }
                """;
    }

    private static String relayCatalogJson() {
        return """
                {
                  "brand": {"name": "Wynne Relay", "baseUrl": "", "consoleUrl": "/relay/console", "supportUrl": ""},
                  "stats": [],
                  "models": [
                    {"name": "test-model", "vendor": "Test", "context": "8K", "input": 1, "output": 2, "tag": ""}
                  ],
                  "plans": [
                    {"id": "p10", "name": "入门充值", "price": 10, "credit": 10, "bonus": "", "hot": false, "perks": []}
                  ],
                  "faqs": [],
                  "codeSamples": [],
                  "notices": []
                }
                """;
    }
}
