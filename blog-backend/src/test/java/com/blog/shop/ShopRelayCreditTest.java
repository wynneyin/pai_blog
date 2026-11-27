package com.blog.shop;

import com.blog.relay.RelayException;
import com.blog.relay.RelayService;
import com.blog.relay.RelayTopup;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shop -> relay fulfillment path: orders for relay credit packs
 * require an existing relay account, and confirming payment credits that
 * account exactly once through a deterministic SHOP-&lt;orderNo&gt; top-up.
 */
class ShopRelayCreditTest {

    private static final String EMAIL = "buyer@example.com";
    private static final String PASSWORD = "relay-buyer-pass";
    private static final String IP = "203.0.113.5";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path shopCatalogPath;
    private Path shopOrdersPath;
    private Path relayCatalogPath;
    private Path relayDataPath;
    private RelayService relayService;
    private ShopService shopService;
    private String token;

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
        token = (String) relayService.register(EMAIL, PASSWORD, IP).get("token");
    }

    @Test
    void unregisteredEmailIsRejectedBeforeAnyOrderIsStored() {
        assertBadRequest(
                () -> shopService.createOrder(request("relay-p50", 1, "nobody@example.com", null)),
                "该邮箱尚未注册中转站，请先注册");
        assertFalse(Files.exists(shopOrdersPath));
    }

    @Test
    void contactMustBeAnEmailForRelayProducts() {
        assertBadRequest(
                () -> shopService.createOrder(request("relay-p50", 1, "13800138000", null)),
                "请填写中转站注册邮箱");
        assertFalse(Files.exists(shopOrdersPath));
    }

    @Test
    void relayOrderNormalizesEmailAndRecordsCredit() {
        ShopOrder order = shopService.createOrder(
                request("relay-p50", 2, "  BUYER@Example.COM  ", null));

        assertEquals(EMAIL, order.getContact());
        assertEquals("p50", order.getRelayPlanId());
        assertEquals(11000, order.getRelayCreditCents());
        assertEquals(0, new java.math.BigDecimal("100.00").compareTo(order.getTotal()));
        assertNull(order.getRelayCreditedAt());
        assertNull(order.getRelayTopupOrderNo());
        assertEquals("pending", order.getStatus());
        assertTrue(shopService.getCatalog().path("allowSelfPay").asBoolean());
    }

    @Test
    void selfPayDisabledReturnsForbiddenAndDoesNotCredit() {
        shopService.setAllowSelfPay(false);
        ShopOrder order = shopService.createOrder(request("relay-p10", 1, EMAIL, null));
        assertFalse(shopService.getCatalog().path("allowSelfPay").asBoolean());

        ShopException forbidden = assertThrows(ShopException.class,
                () -> shopService.payOrder(order.getOrderNo(), "alipay"));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());
        assertEquals("请支付后联系管理员确认到账", forbidden.getMessage());
        assertEquals(0, balanceCents());
        assertEquals("pending", shopService.getOrder(order.getOrderNo()).getStatus());
        assertEquals(0, topups().size());
    }

    @Test
    void adminConfirmationCreditsExactlyOnce() {
        ShopOrder order = shopService.createOrder(request("relay-p50", 2, EMAIL, null));

        ShopOrder paid = shopService.updateOrderStatus(order.getOrderNo(), "paid");
        assertEquals("paid", paid.getStatus());
        assertNotNull(paid.getPaidAt());
        assertTrue(paid.getPaidAt().endsWith("Z"));
        assertNotNull(paid.getRelayCreditedAt());
        assertEquals("SHOP-" + order.getOrderNo(), paid.getRelayTopupOrderNo());
        assertEquals(11000, balanceCents());

        List<RelayTopup> topups = topups();
        assertEquals(1, topups.size());
        RelayTopup topup = topups.get(0);
        assertEquals("SHOP-" + order.getOrderNo(), topup.getOrderNo());
        assertEquals("paid", topup.getStatus());
        assertEquals(10000, topup.getAmountCents());
        assertEquals(1000, topup.getBonusCents());
        assertEquals(11000, topup.getCreditCents());

        ShopOrder again = shopService.updateOrderStatus(order.getOrderNo(), "paid");
        assertEquals(paid.getPaidAt(), again.getPaidAt());
        assertEquals(paid.getRelayCreditedAt(), again.getRelayCreditedAt());
        assertEquals(11000, balanceCents());
        assertEquals(1, topups().size());

        shopService.updateOrderStatus(order.getOrderNo(), "delivered");
        assertEquals(11000, balanceCents());
        assertEquals(1, topups().size());
    }

    @Test
    void relayCreditIsIdempotentAcrossServiceInstances() {
        ShopOrder order = shopService.createOrder(request("relay-p10", 3, EMAIL, null));
        shopService.updateOrderStatus(order.getOrderNo(), "paid");

        RelayService reloaded = new RelayService(objectMapper,
                relayCatalogPath.toString(), relayDataPath.toString());
        reloaded.creditShopOrder(EMAIL, "p10", 3, 3000, order.getOrderNo(), "alipay");
        reloaded.creditShopOrder(EMAIL, "p10", 3, 3000, order.getOrderNo(), "alipay");

        assertEquals(3000, balanceCents());
        assertEquals(1, topups().size());
        assertEquals("SHOP-" + order.getOrderNo(), topups().get(0).getOrderNo());
    }

    @Test
    void cancelAfterCreditIsRejected() {
        ShopOrder order = shopService.createOrder(request("relay-p50", 1, EMAIL, null));
        shopService.updateOrderStatus(order.getOrderNo(), "paid");

        ShopException conflict = assertThrows(ShopException.class,
                () -> shopService.updateOrderStatus(order.getOrderNo(), "cancelled"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        assertEquals("已到账订单不能取消，请联系管理员处理退款", conflict.getMessage());
        assertEquals("paid", shopService.getOrder(order.getOrderNo()).getStatus());
        assertEquals(5500, balanceCents());
    }

    @Test
    void cancelBeforeConfirmationDoesNotCredit() {
        ShopOrder order = shopService.createOrder(request("relay-p10", 1, EMAIL, null));

        ShopOrder cancelled = shopService.updateOrderStatus(order.getOrderNo(), "cancelled");
        assertEquals("cancelled", cancelled.getStatus());
        assertNull(cancelled.getRelayCreditedAt());
        assertEquals(0, balanceCents());
        assertEquals(0, topups().size());
    }

    @Test
    void nonRelayOrderStillUsesFreeFormContactAndNeverCredits() {
        ShopOrder order = shopService.createOrder(request("generic", 1, "13800138000", null));
        assertNull(order.getRelayPlanId());
        assertNull(order.getRelayCreditCents());

        shopService.payOrder(order.getOrderNo(), "wechat");
        shopService.updateOrderStatus(order.getOrderNo(), "delivered");

        assertEquals(0, balanceCents());
        assertEquals(0, topups().size());
    }

    @Test
    void mismatchedAmountIsRejectedByRelayLayer() {
        RelayException mismatch = assertThrows(RelayException.class,
                () -> relayService.creditShopOrder(EMAIL, "p50", 1, 4900, "PB202601010000ABCD", "alipay"));
        assertEquals(HttpStatus.BAD_REQUEST, mismatch.getStatus());
        assertEquals("商城订单金额与充值档位不一致", mismatch.getMessage());
        assertEquals(0, balanceCents());
        assertEquals(0, topups().size());
    }

    @Test
    void relayCreditForUnknownPlanIsRejected() {
        RelayException missing = assertThrows(RelayException.class,
                () -> relayService.creditShopOrder(EMAIL, "p999", 1, 5000, "PB202601010000ABCD", "alipay"));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
        assertEquals("充值档位不存在", missing.getMessage());
        assertEquals(0, balanceCents());
    }

    @SuppressWarnings("unchecked")
    private int balanceCents() {
        Map<String, Object> me = relayService.me("Bearer " + token);
        Map<String, Object> user = (Map<String, Object>) me.get("user");
        return (Integer) user.get("balanceCents");
    }

    @SuppressWarnings("unchecked")
    private List<RelayTopup> topups() {
        Map<String, Object> result = relayService.listTopups("Bearer " + token);
        return (List<RelayTopup>) result.get("list");
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

    private static String shopCatalogJson() {
        return """
                {
                  "categories": [
                    {"id": "relay", "name": "中转额度", "blurb": "API 额度"}
                  ],
                  "products": [
                    {
                      "id": "relay-p10",
                      "categoryId": "relay",
                      "name": "中转额度 ¥10",
                      "subtitle": "",
                      "price": 10,
                      "stock": 99,
                      "relayPlanId": "p10"
                    },
                    {
                      "id": "relay-p50",
                      "categoryId": "relay",
                      "name": "中转额度 ¥50",
                      "subtitle": "",
                      "price": 50,
                      "stock": 99,
                      "relayPlanId": "p50"
                    },
                    {
                      "id": "generic",
                      "categoryId": "relay",
                      "name": "测试商品",
                      "subtitle": "",
                      "price": 9.9,
                      "stock": 99
                    }
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
