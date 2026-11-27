package com.blog.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.blog.mail.MailGateway;
import com.blog.relay.RelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path catalogPath;
    private Path ordersPath;
    private ShopService service;

    @BeforeEach
    void setUp() throws IOException {
        catalogPath = tempDir.resolve("shop").resolve("catalog.json");
        ordersPath = tempDir.resolve("data").resolve("shop-orders.json");
        Files.createDirectories(catalogPath.getParent());
        Files.writeString(catalogPath, catalogJson(), StandardCharsets.UTF_8);
        service = new ShopService(objectMapper, null, catalogPath.toString(), ordersPath.toString());
    }

    @Test
    void catalogIsRereadOnEveryRequestAndCarriesAvailability() throws IOException {
        JsonNode catalog = service.getCatalog();
        assertEquals(3, catalog.path("products").size());
        assertEquals("AI 助手 Plus · 月付", catalog.path("products").get(0).path("name").asText());
        assertEquals(2, catalog.path("notices").size());
        assertEquals(5, catalog.path("products").get(0).path("availableStock").asInt());

        Files.writeString(catalogPath, catalogJson().replace("\"stock\": 5", "\"stock\": 77"),
                StandardCharsets.UTF_8);
        assertEquals(77, service.getCatalog().path("products").get(0).path("stock").asInt());
        assertEquals(77, service.getCatalog().path("products").get(0).path("availableStock").asInt());
    }

    @Test
    void missingOrInvalidCatalogReturnsServerError() throws IOException {
        Files.writeString(catalogPath, "{ broken", StandardCharsets.UTF_8);
        ShopException invalid = assertThrows(ShopException.class, service::getCatalog);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, invalid.getStatus());
        assertTrue(invalid.getMessage().startsWith("商品目录加载失败"));

        Files.delete(catalogPath);
        ShopException missing = assertThrows(ShopException.class, service::getCatalog);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, missing.getStatus());
        assertTrue(missing.getMessage().startsWith("商品目录加载失败"));
    }

    @Test
    void createOrderPersistsAndReloads() throws IOException {
        ShopOrder order = service.createOrder(request("ai-plus-1m", 2, " 13800138000 ", " 尽快发货 "));

        assertTrue(order.getOrderNo().matches("PB\\d{14}[A-Z0-9]{4}"), order.getOrderNo());
        assertEquals("pending", order.getStatus());
        assertEquals("ai-plus-1m", order.getProductId());
        assertEquals("AI 助手 Plus · 月付", order.getProductName());
        assertEquals(0, new BigDecimal("68").compareTo(order.getUnitPrice()));
        assertEquals(2, order.getQuantity());
        assertEquals(0, new BigDecimal("136.00").compareTo(order.getTotal()));
        assertEquals("13800138000", order.getContact());
        assertEquals("尽快发货", order.getNote());
        assertNotNull(order.getCreatedAt());
        assertTrue(order.getCreatedAt().endsWith("Z"));
        assertNull(order.getPaidAt());

        assertTrue(Files.exists(ordersPath));
        JsonNode persisted = objectMapper.readTree(ordersPath.toFile());
        assertEquals(1, persisted.path("orders").size());
        JsonNode stored = persisted.path("orders").get(0);
        assertEquals(order.getOrderNo(), stored.path("orderNo").asText());
        assertTrue(Files.readString(ordersPath, StandardCharsets.UTF_8).contains("136.00"));
        try (Stream<Path> files = Files.list(ordersPath.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }

        ShopService reloaded = new ShopService(objectMapper, null, catalogPath.toString(), ordersPath.toString());
        ShopOrder loaded = reloaded.getOrder(order.getOrderNo().toLowerCase(Locale.ROOT));
        assertEquals(order.getOrderNo(), loaded.getOrderNo());
        assertEquals(0, order.getTotal().compareTo(loaded.getTotal()));
        assertEquals(2, loaded.getTotal().scale());
        assertEquals("pending", loaded.getStatus());
    }

    @Test
    void totalUsesCatalogPriceAndRoundsToTwoDecimals() {
        ShopOrder order = service.createOrder(request("rounding", 3, "13800138000", ""));

        assertEquals(0, new BigDecimal("19.99").compareTo(order.getUnitPrice()));
        assertEquals(0, new BigDecimal("59.97").compareTo(order.getTotal()));
    }

    @Test
    void orderNumbersAreUnique() {
        List<String> orderNos = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            orderNos.add(service.createOrder(request("rounding", 1, "13800138000", null)).getOrderNo());
        }
        assertEquals(8, orderNos.stream().distinct().count());
    }

    @Test
    void stockAccountingCountsNonCancelledOrdersAndReleasesOnCancel() {
        ShopOrder first = service.createOrder(request("ai-plus-1m", 3, "13800138000", null));

        ShopException conflict = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 3, "13800138000", null)));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("库存不足，剩余 2 件", conflict.getMessage());

        service.payOrder(first.getOrderNo(), "alipay");
        ShopException stillReserved = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 3, "13800138000", null)));
        assertEquals("库存不足，剩余 2 件", stillReserved.getMessage());

        service.updateOrderStatus(first.getOrderNo(), "delivered");
        ShopException afterDelivered = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 3, "13800138000", null)));
        assertEquals("库存不足，剩余 2 件", afterDelivered.getMessage());

        ShopOrder second = service.createOrder(request("ai-plus-1m", 2, "13800138000", null));
        assertEquals(2, second.getQuantity());
        assertEquals(0, service.getCatalog().path("products").get(0).path("availableStock").asInt());

        service.updateOrderStatus(second.getOrderNo(), "cancelled");
        assertEquals(2, service.getCatalog().path("products").get(0).path("availableStock").asInt());
        ShopOrder third = service.createOrder(request("ai-plus-1m", 2, "13800138000", null));
        assertEquals(2, third.getQuantity());

        ShopOrder otherProduct = service.createOrder(request("dev-ide-1y", 10, "13800138000", null));
        assertEquals(10, otherProduct.getQuantity());
    }

    @Test
    void cancelledOrderCannotBePaidAndTimestampsFollowStatus() {
        ShopOrder order = service.createOrder(request("dev-ide-1y", 1, "13800138000", null));

        ShopOrder cancelled = service.updateOrderStatus(order.getOrderNo(), "cancelled");
        assertEquals("cancelled", cancelled.getStatus());
        assertNotNull(cancelled.getCancelledAt());
        assertNull(cancelled.getDeliveredAt());

        ShopException pay = assertThrows(ShopException.class,
                () -> service.payOrder(order.getOrderNo(), "alipay"));
        assertEquals(HttpStatus.CONFLICT, pay.getStatus());
        assertEquals("订单已取消", pay.getMessage());

        ShopOrder reopened = service.updateOrderStatus(order.getOrderNo(), "paid");
        assertEquals("paid", reopened.getStatus());
        assertNull(reopened.getCancelledAt());
        assertNull(reopened.getDeliveredAt());

        ShopOrder delivered = service.updateOrderStatus(order.getOrderNo(), "delivered");
        assertNotNull(delivered.getDeliveredAt());
        assertNull(delivered.getCancelledAt());

        ShopOrder reverted = service.updateOrderStatus(order.getOrderNo(), "paid");
        assertNull(reverted.getDeliveredAt());
    }

    @Test
    void catalogPriceMustBeNonNegativeJsonNumber() throws IOException {
        Files.writeString(catalogPath, catalogJson().replace("\"price\": 68", "\"price\": \"68\""),
                StandardCharsets.UTF_8);
        ShopException textual = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 1, "13800138000", null)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, textual.getStatus());
        assertEquals("商品目录加载失败: 商品价格不合法", textual.getMessage());

        Files.writeString(catalogPath, catalogJson().replace("\"price\": 68", "\"price\": -1"),
                StandardCharsets.UTF_8);
        ShopException negative = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 1, "13800138000", null)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, negative.getStatus());
        assertEquals("商品目录加载失败: 商品价格不合法", negative.getMessage());
    }

    @Test
    void nonStringNoteIsRejected() throws IOException {
        ShopCreateOrderRequest numericNote = ShopCreateOrderRequest.fromJson(objectMapper.readTree(
                "{\"productId\":\"ai-plus-1m\",\"quantity\":1,\"contact\":\"13800138000\",\"note\":123}"));
        assertBadRequest(() -> service.createOrder(numericNote), "备注不合法");

        ShopCreateOrderRequest objectNote = ShopCreateOrderRequest.fromJson(objectMapper.readTree(
                "{\"productId\":\"ai-plus-1m\",\"quantity\":1,\"contact\":\"13800138000\",\"note\":{}}"));
        assertBadRequest(() -> service.createOrder(objectNote), "备注不合法");
    }

    @Test
    void payIsIdempotentAndKeepsFirstPaymentDetails() {
        ShopOrder order = service.createOrder(request("dev-ide-1y", 1, "13800138000", null));

        ShopOrder paid = service.payOrder(order.getOrderNo().toLowerCase(Locale.ROOT), "wechat");
        assertEquals("paid", paid.getStatus());
        assertEquals("wechat", paid.getPaymentMethod());
        assertNotNull(paid.getPaidAt());
        assertTrue(paid.getPaidAt().endsWith("Z"));

        ShopOrder again = service.payOrder(order.getOrderNo(), "alipay");
        assertEquals("paid", again.getStatus());
        assertEquals("wechat", again.getPaymentMethod());
        assertEquals(paid.getPaidAt(), again.getPaidAt());

        service.updateOrderStatus(order.getOrderNo(), "delivered");
        ShopOrder afterDelivered = service.payOrder(order.getOrderNo(), "alipay");
        assertEquals("delivered", afterDelivered.getStatus());
        assertEquals("wechat", afterDelivered.getPaymentMethod());
        assertNotNull(afterDelivered.getDeliveredAt());
    }

    @Test
    void invalidOrderInputIsRejectedWithBusinessMessages() {
        assertBadRequest(() -> service.createOrder(request(null, 1, "13800138000", null)), "商品不存在");
        assertBadRequest(() -> service.createOrder(request("   ", 1, "13800138000", null)), "商品不存在");
        assertBadRequest(() -> service.createOrder(request("missing", 1, "13800138000", null)), "商品不存在");
        assertBadRequest(() -> service.createOrder(request("ai-plus-1m", null, "13800138000", null)),
                "购买数量不合法");
        assertBadRequest(() -> service.createOrder(request("ai-plus-1m", 0, "13800138000", null)),
                "购买数量不合法");
        assertBadRequest(() -> service.createOrder(request("ai-plus-1m", 100, "13800138000", null)),
                "购买数量不合法");
        assertBadRequest(() -> service.createOrder(request("ai-plus-1m", 1, "1234", null)),
                "请填写有效的联系方式");
        assertBadRequest(() -> service.createOrder(request("ai-plus-1m", 1, "1".repeat(101), null)),
                "请填写有效的联系方式");
        assertBadRequest(() -> service.createOrder(request("ai-plus-1m", 1, "13800138000", "备".repeat(501))),
                "备注过长");

        ShopCreateOrderRequest fractional = ShopCreateOrderRequest.builder()
                .productId("ai-plus-1m")
                .quantity(objectMapper.valueToTree(1.5))
                .contact("13800138000")
                .build();
        assertBadRequest(() -> service.createOrder(fractional), "购买数量不合法");

        assertFalse(Files.exists(ordersPath));
    }

    @Test
    void adminStatusValidationRejectsUnknownStatus() {
        ShopOrder order = service.createOrder(request("dev-ide-1y", 1, "13800138000", null));

        ShopException invalid = assertThrows(ShopException.class,
                () -> service.updateOrderStatus(order.getOrderNo(), "shipped"));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatus());
        assertEquals("订单状态不合法", invalid.getMessage());
        assertEquals("pending", service.getOrder(order.getOrderNo()).getStatus());
    }

    @Test
    void unknownOrderReturnsNotFound() {
        String missing = "PB200001010000ZZZZ";

        ShopException get = assertThrows(ShopException.class, () -> service.getOrder(missing));
        assertEquals(HttpStatus.NOT_FOUND, get.getStatus());
        assertEquals("订单不存在", get.getMessage());

        ShopException pay = assertThrows(ShopException.class, () -> service.payOrder(missing, "alipay"));
        assertEquals(HttpStatus.NOT_FOUND, pay.getStatus());
        assertEquals("订单不存在", pay.getMessage());

        ShopException update = assertThrows(ShopException.class,
                () -> service.updateOrderStatus(missing, "paid"));
        assertEquals(HttpStatus.NOT_FOUND, update.getStatus());
        assertEquals("订单不存在", update.getMessage());
    }

    @Test
    void adminListFiltersSortsAndLimits() {
        ShopOrder first = service.createOrder(request("ai-plus-1m", 1, "13800138000", "first"));
        ShopOrder second = service.createOrder(request("dev-ide-1y", 2, "13800138000", "second"));
        service.payOrder(first.getOrderNo(), "alipay");
        service.updateOrderStatus(second.getOrderNo(), "delivered");

        ShopOrderListResponse all = service.listOrders(null, 100);
        assertEquals(2, all.getTotal());
        assertEquals(2, all.getList().size());

        ShopOrderListResponse paid = service.listOrders("paid", 100);
        assertEquals(1, paid.getTotal());
        assertEquals(first.getOrderNo(), paid.getList().get(0).getOrderNo());

        ShopOrderListResponse delivered = service.listOrders("delivered", 100);
        assertEquals(1, delivered.getTotal());
        assertEquals(second.getOrderNo(), delivered.getList().get(0).getOrderNo());

        ShopOrderListResponse pending = service.listOrders("pending", 100);
        assertEquals(0, pending.getTotal());
        assertTrue(pending.getList().isEmpty());

        ShopOrderListResponse limited = service.listOrders(null, 1);
        assertEquals(2, limited.getTotal());
        assertEquals(1, limited.getList().size());

        ShopOrderListResponse capped = service.listOrders(null, 9999);
        assertEquals(2, capped.getTotal());
        assertEquals(2, capped.getList().size());
    }

    @Test
    void corruptOrderFileFailsWithoutBeingOverwritten() throws IOException {
        Files.createDirectories(ordersPath.getParent());
        Files.writeString(ordersPath, "{ this is not json", StandardCharsets.UTF_8);

        ShopException create = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 1, "13800138000", null)));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, create.getStatus());
        assertEquals("订单数据读取失败", create.getMessage());
        assertEquals("{ this is not json", Files.readString(ordersPath, StandardCharsets.UTF_8));

        ShopException get = assertThrows(ShopException.class,
                () -> service.getOrder("PB200001010000ZZZZ"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, get.getStatus());
        assertEquals("订单数据读取失败", get.getMessage());
    }

    @Test
    void textualQuantityIsRejected() {
        ShopCreateOrderRequest textual = ShopCreateOrderRequest.builder()
                .productId("ai-plus-1m")
                .quantity(com.fasterxml.jackson.databind.node.TextNode.valueOf("2"))
                .contact("13800138000")
                .build();
        assertBadRequest(() -> service.createOrder(textual), "购买数量不合法");

        ShopCreateOrderRequest plusSign = ShopCreateOrderRequest.builder()
                .productId("ai-plus-1m")
                .quantity(com.fasterxml.jackson.databind.node.TextNode.valueOf("+2"))
                .contact("13800138000")
                .build();
        assertBadRequest(() -> service.createOrder(plusSign), "购买数量不合法");
    }

    @Test
    void shortageMessageUsesUnclampedAvailableStock() throws IOException {
        service.createOrder(request("ai-plus-1m", 3, "13800138000", null));
        Files.writeString(catalogPath, catalogJson().replace("\"stock\": 5", "\"stock\": 0"),
                StandardCharsets.UTF_8);

        ShopException conflict = assertThrows(ShopException.class,
                () -> service.createOrder(request("ai-plus-1m", 1, "13800138000", null)));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("库存不足，剩余 -3 件", conflict.getMessage());
    }

    @Test
    void paymentMethodOutsideWhitelistIsIgnored() throws IOException {
        ShopOrder order = service.createOrder(request("ai-plus-1m", 1, "13800138000", null));

        ShopOrder paid = service.payOrder(order.getOrderNo(), "paypal");
        assertEquals("paid", paid.getStatus());
        assertNull(paid.getPaymentMethod());

        JsonNode stored = objectMapper.readTree(ordersPath.toFile()).path("orders").get(0);
        assertFalse(stored.has("paymentMethod"));
    }

    @Test
    void noteDefaultsToEmptyStringInResponseAndFile() throws IOException {
        ShopOrder order = service.createOrder(request("ai-plus-1m", 1, "13800138000", null));

        assertEquals("", order.getNote());
        JsonNode stored = objectMapper.readTree(ordersPath.toFile()).path("orders").get(0);
        assertTrue(stored.has("note"));
        assertEquals("", stored.path("note").asText());
    }

    @Test
    void catalogWithoutProductsArrayIsRejected() throws IOException {
        Files.writeString(catalogPath, "{}", StandardCharsets.UTF_8);

        ShopException exception = assertThrows(ShopException.class, service::getCatalog);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertEquals("商品目录加载失败: 目录结构缺少 products 数组", exception.getMessage());
    }

    @Test
    void legacyOrdersWithoutNoteGetEmptyNote() throws IOException {
        Files.createDirectories(ordersPath.getParent());
        Files.writeString(ordersPath, "{\"orders\":[{\"orderNo\":\"PB202601010000ABCD\","
                + "\"productId\":\"ai-plus-1m\",\"quantity\":1,\"status\":\"pending\","
                + "\"createdAt\":\"2026-01-01T00:00:00.000Z\"}]}", StandardCharsets.UTF_8);

        ShopOrder order = service.getOrder("PB202601010000ABCD");
        assertEquals("", order.getNote());
    }

    @Test
    void rawBodyParsingMatchesReferenceValidation() throws IOException {
        ShopCreateOrderRequest numericContact = ShopCreateOrderRequest.fromJson(objectMapper.readTree(
                "{\"productId\":\"ai-plus-1m\",\"quantity\":1,\"contact\":13800138000}"));
        assertBadRequest(() -> service.createOrder(numericContact), "请填写有效的联系方式");

        ShopCreateOrderRequest arrayBody = ShopCreateOrderRequest.fromJson(objectMapper.readTree("[]"));
        assertBadRequest(() -> service.createOrder(arrayBody), "商品不存在");
    }

    @Test
    void nonNumericAdminLimitFallsBackToDefault() {
        service.createOrder(request("ai-plus-1m", 1, "13800138000", null));

        ShopOrderListResponse response = service.listOrders(null, "abc");
        assertEquals(1, response.getTotal());
        assertEquals(1, response.getList().size());
    }

    private void assertBadRequest(Executable executable, String expectedMessage) {
        ShopException exception = assertThrows(ShopException.class, executable);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals(expectedMessage, exception.getMessage());
    }

    private ShopCreateOrderRequest request(String productId, Integer quantity, String contact, String note) {
        return ShopCreateOrderRequest.builder()
                .productId(productId)
                .quantity(quantity == null ? null : IntNode.valueOf(quantity))
                .contact(contact)
                .note(note)
                .build();
    }

    @Test
    void relayOrderSendsCreatedAndCreditedEmails() throws IOException {
        Path relayCatalogPath = tempDir.resolve("shop").resolve("relay.json");
        Path relayStorePath = tempDir.resolve("data").resolve("relay-store.json");
        Files.writeString(relayCatalogPath, relayCatalogJson(), StandardCharsets.UTF_8);
        RelayService relay = new RelayService(objectMapper, relayCatalogPath.toString(),
                relayStorePath.toString());
        relay.register("buyer@example.com", "Relay-Test-Pass-1", "203.0.113.7");

        Path shopCatalogPath = tempDir.resolve("shop").resolve("shop-catalog.json");
        Files.writeString(shopCatalogPath, relayProductCatalogJson(), StandardCharsets.UTF_8);
        CapturingMailGateway mail = new CapturingMailGateway();
        ShopService shop = new ShopService(objectMapper, relay, shopCatalogPath.toString(),
                ordersPath.toString(), mail);

        Map<String, Object> created = shop.createOrderResponse(
                request("relay-50", 1, "buyer@example.com", ""));
        assertEquals(Boolean.TRUE, created.get("emailQueued"));
        assertEquals(1, mail.sent.size());
        assertEquals("buyer@example.com", mail.sent.get(0)[0]);
        assertTrue(mail.sent.get(0)[1].startsWith("订单已创建 · "), mail.sent.get(0)[1]);
        assertTrue(mail.sent.get(0)[2].contains((String) created.get("lookupToken")));

        shop.updateOrderStatus((String) created.get("orderNo"), "paid");
        assertEquals(2, mail.sent.size());
        assertTrue(mail.sent.get(1)[1].startsWith("额度已到账 · "), mail.sent.get(1)[1]);
        assertTrue(mail.sent.get(1)[2].contains("¥55.00"), mail.sent.get(1)[2]);
    }

    @Test
    void paidEmailReportsTheActuallyCreditedAmount() throws IOException {
        Path relayCatalogPath = tempDir.resolve("shop").resolve("relay.json");
        Path relayStorePath = tempDir.resolve("data").resolve("relay-store.json");
        Files.writeString(relayCatalogPath, relayCatalogJson(), StandardCharsets.UTF_8);
        RelayService relay = new RelayService(objectMapper, relayCatalogPath.toString(),
                relayStorePath.toString());
        relay.register("buyer@example.com", "Relay-Test-Pass-1", "203.0.113.7");

        Path shopCatalogPath = tempDir.resolve("shop").resolve("shop-catalog.json");
        Files.writeString(shopCatalogPath, relayProductCatalogJson(), StandardCharsets.UTF_8);
        CapturingMailGateway mail = new CapturingMailGateway();
        ShopService shop = new ShopService(objectMapper, relay, shopCatalogPath.toString(),
                ordersPath.toString(), mail);

        Map<String, Object> created = shop.createOrderResponse(
                request("relay-50", 1, "buyer@example.com", ""));

        // The operator raises the bonus while the order is still pending.
        Files.writeString(relayCatalogPath, relayCatalogJson(60), StandardCharsets.UTF_8);
        ShopOrder paid = shop.updateOrderStatus((String) created.get("orderNo"), "paid");

        assertEquals(Integer.valueOf(6000), paid.getRelayCreditCents());
        assertEquals(2, mail.sent.size());
        assertTrue(mail.sent.get(1)[2].contains("¥60.00"), mail.sent.get(1)[2]);
    }

    private static String relayCatalogJson() {
        return relayCatalogJson(55);
    }

    private static String relayCatalogJson(int credit) {
        return """
                {
                  "models": [{"name": "gpt-4o-mini", "input": 1.08, "output": 4.32}],
                  "plans": [{"id": "p50", "name": "标准充值", "price": 50, "credit": %d}]
                }
                """.formatted(credit);
    }

    private static String relayProductCatalogJson() {
        return """
                {
                  "categories": [],
                  "products": [
                    {
                      "id": "relay-50",
                      "categoryId": "relay",
                      "name": "中转额度 50",
                      "price": 50,
                      "stock": 5,
                      "relayPlanId": "p50"
                    }
                  ],
                  "notices": []
                }
                """;
    }

    private static final class CapturingMailGateway implements MailGateway {

        final List<String[]> sent = new ArrayList<>();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String siteBaseUrl() {
            return "https://example.test";
        }

        @Override
        public boolean send(String to, String subject, String text) {
            sent.add(new String[]{to, subject, text});
            return true;
        }
    }

    private static String catalogJson() {
        return """
                {
                  "categories": [
                    {"id": "ai", "name": "AI 会员", "blurb": "AI 订阅"},
                    {"id": "dev", "name": "开发工具", "blurb": "开发者订阅"}
                  ],
                  "products": [
                    {
                      "id": "ai-plus-1m",
                      "categoryId": "ai",
                      "name": "AI 助手 Plus · 月付",
                      "subtitle": "独享通道",
                      "price": 68,
                      "origPrice": 138,
                      "tag": "热销",
                      "stock": 5
                    },
                    {
                      "id": "dev-ide-1y",
                      "categoryId": "dev",
                      "name": "IDE 全家桶 · 年付",
                      "subtitle": "全系开发工具",
                      "price": 129.5,
                      "origPrice": 199,
                      "tag": "年付",
                      "stock": 10
                    },
                    {
                      "id": "rounding",
                      "categoryId": "dev",
                      "name": "测试舍入",
                      "subtitle": "",
                      "price": 19.99,
                      "origPrice": 29.99,
                      "tag": "",
                      "stock": 100
                    }
                  ],
                  "notices": ["公告一", "公告二"]
                }
                """;
    }
}
