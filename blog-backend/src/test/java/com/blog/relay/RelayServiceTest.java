package com.blog.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "unit-test-password";
    private static final String NEW_PASSWORD = PASSWORD + "-v2";
    private static final String IP = "203.0.113.9";

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path catalogPath;
    private Path storePath;
    private RelayService service;

    @BeforeEach
    void setUp() throws IOException {
        catalogPath = tempDir.resolve("shop").resolve("relay.json");
        storePath = tempDir.resolve("data").resolve("relay-store.json");
        Files.createDirectories(catalogPath.getParent());
        Files.writeString(catalogPath, catalogJson(), StandardCharsets.UTF_8);
        service = new RelayService(objectMapper, catalogPath.toString(), storePath.toString());
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    @Test
    void catalogIsRereadOnEveryCall() throws IOException {
        JsonNode catalog = service.getCatalog();
        assertEquals(2, catalog.path("models").size());
        assertEquals(2, catalog.path("plans").size());
        assertEquals("gpt-4o-mini", catalog.path("models").get(0).path("name").asText());

        Files.writeString(catalogPath,
                catalogJson().replace("\"input\": 1.08", "\"input\": 2.5"),
                StandardCharsets.UTF_8);
        assertEquals(2.5, service.getCatalog().path("models").get(0).path("input").asDouble(), 0.0);
    }

    @Test
    void invalidCatalogReturnsServerErrorWithoutFallback() throws IOException {
        assertCatalogError("{\"plans\": []}");
        assertCatalogError("{\"models\": []}");
        assertCatalogError("{\"models\": {}, \"plans\": []}");
        assertCatalogError("{\"models\": [], \"plans\": {}}");
        assertCatalogError("[]");
        assertCatalogError("{ broken json");

        assertCatalogError("""
                {"models":[
                  {"name":"dup","input":1,"output":1},
                  {"name":"dup","input":1,"output":1}
                ],"plans":[]}
                """);
        assertCatalogError("""
                {"models":[{"name":"bad","input":-1,"output":1}],"plans":[]}
                """);
        assertCatalogError("""
                {"models":[{"name":"bad","input":"1","output":1}],"plans":[]}
                """);
        assertCatalogError("""
                {"models":[{"name":"","input":1,"output":1}],"plans":[]}
                """);
        assertCatalogError("""
                {"models":[],"plans":[
                  {"id":"p","price":1,"credit":1},
                  {"id":"p","price":1,"credit":1}
                ]}
                """);
        assertCatalogError("""
                {"models":[],"plans":[{"id":"p","price":50,"credit":40}]}
                """);
        assertCatalogError("""
                {"models":[],"plans":[{"id":"p","price":-1,"credit":1}]}
                """);
        assertCatalogError("""
                {"models":[],"plans":[{"id":"p","price":1,"credit":"2"}]}
                """);

        Files.delete(catalogPath);
        RelayException missing = assertThrows(RelayException.class, service::getCatalog);
        assertEquals(500, missing.getStatus().value());
        assertTrue(missing.getMessage().startsWith("中转目录加载失败"));
    }

    private void assertCatalogError(String json) throws IOException {
        Files.writeString(catalogPath, json, StandardCharsets.UTF_8);
        RelayException exception = assertThrows(RelayException.class, service::getCatalog);
        assertEquals(500, exception.getStatus().value());
        assertTrue(exception.getMessage().startsWith("中转目录加载失败: "), exception.getMessage());
    }

    // ------------------------------------------------------------------
    // Register / login / sessions
    // ------------------------------------------------------------------

    @Test
    void registerCreatesHashedAccountAndSession() throws IOException {
        Map<String, Object> result = service.register("  User@Example.com ", PASSWORD, IP);

        String token = (String) result.get("token");
        assertTrue(token.matches("rs_[0-9a-f]{48}"), token);
        Map<String, Object> user = mapOf(result.get("user"));
        assertEquals("user@example.com", user.get("email"));
        assertEquals(0, ((Number) user.get("balanceCents")).intValue());
        assertEquals(4, user.size());
        assertTrue(((String) user.get("id")).matches("u_[0-9a-f]{12}"));
        assertFalse(user.containsKey("passwordHash"));
        assertFalse(user.containsKey("passwordSalt"));

        JsonNode store = objectMapper.readTree(storePath.toFile());
        assertEquals(1, store.path("users").size());
        JsonNode storedUser = store.path("users").get(0);
        String salt = storedUser.path("passwordSalt").asText();
        String hash = storedUser.path("passwordHash").asText();
        assertTrue(salt.matches("[0-9a-f]{32}"), salt);
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
        assertNotEquals(PASSWORD, hash);
        assertEquals(0, storedUser.path("balanceCents").asInt());
        assertFalse(storedUser.path("disabled").asBoolean());

        assertEquals(1, store.path("sessions").size());
        JsonNode session = store.path("sessions").get(0);
        assertEquals(sha256(token), session.path("tokenHash").asText());
        Duration lifetime = Duration.between(
                Instant.parse(session.path("createdAt").asText()),
                Instant.parse(session.path("expiresAt").asText()));
        assertEquals(Duration.ofDays(7), lifetime);

        String persisted = Files.readString(storePath, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(PASSWORD));
        assertFalse(persisted.contains(token));

        Map<String, Object> me = service.me("Bearer " + token);
        assertEquals(user.get("id"), mapOf(me.get("user")).get("id"));
    }

    @Test
    void registerValidatesEmailAndPassword() {
        assertRelayError(400, "邮箱格式不正确",
                () -> service.register("not-an-email", PASSWORD, IP));
        assertRelayError(400, "邮箱格式不正确",
                () -> service.register("a@b", PASSWORD, IP));
        assertRelayError(400, "邮箱格式不正确",
                () -> service.register("a b@c.com", PASSWORD, IP));
        assertRelayError(400, "邮箱格式不正确",
                () -> service.register("a".repeat(116) + "@b.co", PASSWORD, IP));
        assertRelayError(400, "邮箱格式不正确",
                () -> service.register(null, PASSWORD, IP));

        assertRelayError(400, "密码长度需为 8-72 位",
                () -> service.register("short@example.com", "1234567", IP));
        assertRelayError(400, "密码长度需为 8-72 位",
                () -> service.register("long@example.com", "x".repeat(73), IP));
        assertRelayError(400, "密码长度需为 8-72 位",
                () -> service.register("null@example.com", null, IP));

        assertNotNull(service.register("eight@example.com", "12345678", IP).get("token"));
        assertNotNull(service.register("seventy@example.com", "x".repeat(72), IP).get("token"));
    }

    @Test
    void duplicateEmailAndWrongPasswordUseContractMessages() {
        service.register("User@Example.com", PASSWORD, IP);

        assertRelayError(400, "该邮箱已注册",
                () -> service.register(" user@example.COM ", "another-pass", IP));

        assertRelayError(401, "邮箱或密码错误",
                () -> service.login(EMAIL, "wrong-password", IP));
        assertRelayError(401, "邮箱或密码错误",
                () -> service.login("missing@example.com", PASSWORD, IP));
        assertRelayError(401, "邮箱或密码错误",
                () -> service.login(EMAIL, null, IP));
        assertRelayError(401, "邮箱或密码错误",
                () -> service.login(null, PASSWORD, IP));

        Map<String, Object> login = service.login(" USER@EXAMPLE.COM ", PASSWORD, IP);
        String token = (String) login.get("token");
        assertTrue(token.matches("rs_[0-9a-f]{48}"));
        assertEquals(EMAIL, mapOf(login.get("user")).get("email"));
        assertNotNull(service.me("Bearer " + token));
    }

    @Test
    void sessionExpiryAndBadTokensAreRejected() throws IOException {
        String token = register(EMAIL);

        Map<String, Object> me = service.me("Bearer " + token);
        assertEquals(EMAIL, mapOf(me.get("user")).get("email"));

        assertRelayError(401, "登录已失效，请重新登录", () -> service.me(null));
        assertRelayError(401, "登录已失效，请重新登录",
                () -> service.me("Bearer rs_0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertRelayError(401, "登录已失效，请重新登录",
                () -> service.me("Basic " + token));

        expireAllSessions();

        assertRelayError(401, "登录已失效，请重新登录",
                () -> service.me("Bearer " + token));
    }

    @Test
    void authEndpointsAreRateLimitedPerIp() {
        String ip = "192.0.2.77";
        for (int i = 0; i < 30; i++) {
            assertNotNull(service.register("rate" + i + "@example.com", PASSWORD, ip).get("token"));
        }
        assertRelayError(429, "请求过于频繁，请稍后再试",
                () -> service.register("rate-over@example.com", PASSWORD, ip));
        assertRelayError(429, "请求过于频繁，请稍后再试",
                () -> service.login("rate0@example.com", "wrong-password", ip));

        assertNotNull(service.register("rate-other@example.com", PASSWORD, "192.0.2.78").get("token"));
    }

    @Test
    void logoutIsIdempotentAndRemovesTheSession() throws IOException {
        String token = register(EMAIL);

        Map<String, Object> logout = service.logout("Bearer " + token);
        assertEquals(Boolean.TRUE, logout.get("ok"));
        assertRelayError(401, "登录已失效，请重新登录",
                () -> service.me("Bearer " + token));

        assertEquals(Boolean.TRUE, service.logout("Bearer " + token).get("ok"));
        assertEquals(Boolean.TRUE, service.logout(null).get("ok"));
        assertEquals(0, objectMapper.readTree(storePath.toFile()).path("sessions").size());
    }

    // ------------------------------------------------------------------
    // Password reset
    // ------------------------------------------------------------------

    @Test
    void passwordResetIsSingleUseAndRevokesSessions() throws IOException {
        String token = register(EMAIL);
        String rawKey = (String) service.createKey("Bearer " + token, "client", false).get("key");
        assertNotNull(service.findActiveApiKey("Bearer " + rawKey));
        RelayService.PasswordResetTicket ticket = service.createPasswordReset(EMAIL, IP);

        assertNotNull(ticket);
        assertEquals(EMAIL, ticket.email());
        assertTrue(ticket.token().matches("pr_[0-9a-f]{64}"), ticket.token());
        String persisted = Files.readString(storePath, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(ticket.token()));
        assertTrue(persisted.contains(sha256(ticket.token())));

        assertEquals(Boolean.TRUE, service.resetPassword(ticket.token(), NEW_PASSWORD, IP).get("reset"));
        assertRelayError(401, "登录已失效，请重新登录", () -> service.me("Bearer " + token));
        assertNull(service.findActiveApiKey("Bearer " + rawKey));
        assertRelayError(401, "邮箱或密码错误", () -> service.login(EMAIL, PASSWORD, IP));
        assertNotNull(service.login(EMAIL, NEW_PASSWORD, IP).get("token"));

        assertRelayError(400, "重置链接无效或已过期",
                () -> service.resetPassword(ticket.token(), NEW_PASSWORD, IP));
    }

    @Test
    void passwordResetHidesUnknownAccountsAndThrottlesRepeats() throws IOException {
        assertNull(service.createPasswordReset("nobody@example.com", IP));
        assertRelayError(400, "邮箱格式不正确",
                () -> service.createPasswordReset("not-an-email", IP));

        register(EMAIL);
        RelayService.PasswordResetTicket ticket = service.createPasswordReset(EMAIL, IP);
        assertNotNull(ticket);
        // A second request inside the cooldown stays silent instead of email bombing.
        assertNull(service.createPasswordReset(EMAIL, "203.0.113.10"));
        expireResetTokens();

        assertRelayError(400, "重置链接无效或已过期",
                () -> service.resetPassword(ticket.token(), NEW_PASSWORD, IP));
        assertRelayError(400, "重置链接无效或已过期",
                () -> service.resetPassword(null, NEW_PASSWORD, IP));
        assertRelayError(400, "密码长度需为 8-72 位",
                () -> service.resetPassword(ticket.token(), "short", IP));
    }

    // ------------------------------------------------------------------
    // API keys
    // ------------------------------------------------------------------

    @Test
    void createKeyReturnsPlaintextOnceAndStoresOnlyHashes() throws IOException {
        String token = register(EMAIL);
        Map<String, Object> created = service.createKey("Bearer " + token, "  主力令牌  ", false);

        String rawKey = (String) created.get("key");
        assertTrue(rawKey.matches("sk-relay-[0-9a-f]{48}"), rawKey);
        Map<String, Object> record = mapOf(created.get("record"));
        assertTrue(((String) record.get("id")).matches("k_[0-9a-f]{12}"), String.valueOf(record.get("id")));
        assertEquals(rawKey.substring(0, 16), record.get("prefix"));
        assertEquals("主力令牌", record.get("name"));
        assertNotNull(record.get("createdAt"));
        assertTrue(record.containsKey("lastUsedAt"));
        assertNull(record.get("lastUsedAt"));
        assertTrue(record.containsKey("revokedAt"));
        assertNull(record.get("revokedAt"));

        JsonNode store = objectMapper.readTree(storePath.toFile());
        assertEquals(1, store.path("keys").size());
        assertEquals(sha256(rawKey), store.path("keys").get(0).path("keyHash").asText());
        assertFalse(Files.readString(storePath, StandardCharsets.UTF_8).contains(rawKey));

        Map<String, Object> listed = service.listKeys("Bearer " + token);
        List<Map<String, Object>> records = listOf(listed.get("list"));
        assertEquals(1, records.size());
        assertEquals(record.get("id"), records.get(0).get("id"));
        assertFalse(records.get(0).containsKey("keyHash"));

        RelayKey active = service.findActiveApiKey("Bearer " + rawKey);
        assertNotNull(active);
        assertEquals(record.get("id"), active.getId());
        assertNull(service.findActiveApiKey("Bearer sk-relay-" + "0".repeat(48)));
        assertNull(service.findActiveApiKey("Bearer not-a-key"));
    }

    @Test
    void keyNameDefaultsTruncatesAndRejectsNonStrings() {
        String token = register(EMAIL);
        String header = "Bearer " + token;

        Map<String, Object> emptyName = service.createKey(header, "   ", false);
        assertEquals("默认令牌", mapOf(emptyName.get("record")).get("name"));

        Map<String, Object> missingName = service.createKey(header, null, false);
        assertEquals("默认令牌", mapOf(missingName.get("record")).get("name"));

        Map<String, Object> longName = service.createKey(header, "长".repeat(50), false);
        assertEquals(40, ((String) mapOf(longName.get("record")).get("name")).length());

        assertRelayError(400, "备注不合法",
                () -> service.createKey(header, null, true));
    }

    @Test
    void keyLimitCountsActiveKeysAndRevokeReleasesOne() {
        String token = register(EMAIL);
        String header = "Bearer " + token;

        List<Map<String, Object>> created = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            created.add(mapOf(service.createKey(header, "key-" + i, false).get("record")));
        }
        assertRelayError(400, "令牌数量已达上限",
                () -> service.createKey(header, "overflow", false));

        String firstId = (String) created.get(0).get("id");
        assertEquals(Boolean.TRUE, service.revokeKey(header, firstId).get("ok"));

        List<Map<String, Object>> records = listOf(service.listKeys(header).get("list"));
        assertEquals(10, records.size());
        Map<String, Object> revoked = records.stream()
                .filter(item -> firstId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertNotNull(revoked.get("revokedAt"));

        Map<String, Object> eleventh = service.createKey(header, "eleven", false);
        assertNotNull(eleventh.get("key"));
        assertEquals(11, listOf(service.listKeys(header).get("list")).size());

        assertEquals(Boolean.TRUE, service.revokeKey(header, firstId).get("ok"));
    }

    @Test
    void revokeRequiresOwnershipAndUnknownKeysAreNotFound() {
        String tokenA = register("a@example.com");
        String tokenB = register("b@example.com");
        String keyId = (String) mapOf(service.createKey("Bearer " + tokenA, null, false)
                .get("record")).get("id");

        assertRelayError(404, "令牌不存在",
                () -> service.revokeKey("Bearer " + tokenB, keyId));
        assertRelayError(404, "令牌不存在",
                () -> service.revokeKey("Bearer " + tokenA, "k_ffffffffffff"));
        assertRelayError(404, "令牌不存在",
                () -> service.revokeKey("Bearer " + tokenA, "  "));

        assertEquals(Boolean.TRUE, service.revokeKey("Bearer " + tokenA, keyId).get("ok"));
        assertEquals(Boolean.TRUE, service.revokeKey("Bearer " + tokenA, keyId).get("ok"));
    }

    // ------------------------------------------------------------------
    // Top-ups
    // ------------------------------------------------------------------

    @Test
    void createTopupConvertsCatalogPricesToCents() {
        String token = register(EMAIL);
        RelayTopup topup = service.createTopup("Bearer " + token, "p50");

        assertTrue(topup.getOrderNo().matches("RT\\d{14}[A-Z0-9]{4}"), topup.getOrderNo());
        assertEquals("p50", topup.getPlanId());
        assertEquals("标准充值", topup.getPlanName());
        assertEquals(5000, topup.getAmountCents());
        assertEquals(5500, topup.getCreditCents());
        assertEquals(500, topup.getBonusCents());
        assertEquals("pending", topup.getStatus());
        assertNull(topup.getMethod());
        assertNotNull(topup.getCreatedAt());
        assertNull(topup.getPaidAt());
        assertNull(topup.getCancelledAt());
        assertEquals(mapOf(service.me("Bearer " + token).get("user")).get("id"), topup.getUserId());

        RelayTopup entry = service.createTopup("Bearer " + token, "p10");
        assertEquals(1000, entry.getAmountCents());
        assertEquals(1000, entry.getCreditCents());
        assertEquals(0, entry.getBonusCents());

        assertRelayError(400, "充值档位不存在",
                () -> service.createTopup("Bearer " + token, "missing"));
        assertRelayError(400, "充值档位不存在",
                () -> service.createTopup("Bearer " + token, null));
        assertRelayError(400, "充值档位不存在",
                () -> service.createTopup("Bearer " + token, "   "));
        assertRelayError(401, "登录已失效，请重新登录",
                () -> service.createTopup("Bearer invalid", "p50"));
    }

    @Test
    void payTopupCreditsOnceAndIsIdempotent() {
        String token = register(EMAIL);
        String header = "Bearer " + token;
        RelayTopup topup = service.createTopup(header, "p50");

        RelayTopup paid = service.payTopup(header, topup.getOrderNo(), " ALIPAY ");
        assertEquals("paid", paid.getStatus());
        assertEquals("alipay", paid.getMethod());
        assertNotNull(paid.getPaidAt());
        assertTrue(paid.getPaidAt().endsWith("Z"));
        assertEquals(5500, balanceOf(token));

        RelayTopup again = service.payTopup(header, topup.getOrderNo(), "wechat");
        assertEquals("paid", again.getStatus());
        assertEquals("alipay", again.getMethod());
        assertEquals(paid.getPaidAt(), again.getPaidAt());
        assertEquals(5500, balanceOf(token));

        RelayTopup other = service.createTopup(header, "p10");
        RelayTopup paidOther = service.payTopup(header, other.getOrderNo(), "paypal");
        assertEquals("paid", paidOther.getStatus());
        assertNull(paidOther.getMethod());
        assertEquals(6500, balanceOf(token));
    }

    @Test
    void payTopupChecksOwnershipAndState() {
        String tokenA = register("a@example.com");
        String tokenB = register("b@example.com");
        RelayTopup topup = service.createTopup("Bearer " + tokenA, "p50");

        assertRelayError(403, "无权操作该订单",
                () -> service.payTopup("Bearer " + tokenB, topup.getOrderNo(), "alipay"));
        assertRelayError(404, "充值订单不存在",
                () -> service.payTopup("Bearer " + tokenA, "RT20000101000000ZZZZ", "alipay"));

        service.adminUpdateTopupStatus(topup.getOrderNo(), "cancelled");
        assertRelayError(409, "订单已取消",
                () -> service.payTopup("Bearer " + tokenA, topup.getOrderNo(), "alipay"));
        assertEquals(0, balanceOf(tokenA));
    }

    @Test
    void userTopupListIsScopedSortedAndLimited() {
        String tokenA = register("a@example.com");
        String tokenB = register("b@example.com");
        service.createTopup("Bearer " + tokenA, "p10");
        ThreadSupport.pause();
        service.createTopup("Bearer " + tokenA, "p50");
        service.createTopup("Bearer " + tokenB, "p10");

        Map<String, Object> listA = service.listTopups("Bearer " + tokenA);
        assertEquals(2, listA.get("total"));
        List<RelayTopup> topupsA = topupsOf(listA.get("list"));
        assertEquals(2, topupsA.size());
        assertTrue(topupsA.get(0).getCreatedAt().compareTo(topupsA.get(1).getCreatedAt()) >= 0);

        Map<String, Object> listB = service.listTopups("Bearer " + tokenB);
        assertEquals(1, listB.get("total"));
        assertEquals("b@example.com", emailOfUser((String) topupsOf(listB.get("list")).get(0).getUserId()));

        assertRelayError(401, "登录已失效，请重新登录", () -> service.listTopups("Bearer nope"));
    }

    // ------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------

    @Test
    void adminStatusTransitionsCreditExactlyOnce() {
        String token = register(EMAIL);
        RelayTopup topup = service.createTopup("Bearer " + token, "p50");

        RelayTopup paid = service.adminUpdateTopupStatus(topup.getOrderNo(), "paid");
        assertEquals("paid", paid.getStatus());
        assertNotNull(paid.getPaidAt());
        assertEquals(5500, balanceOf(token));

        assertRelayError(409, "充值订单状态不可变更",
                () -> service.adminUpdateTopupStatus(topup.getOrderNo(), "paid"));
        assertEquals(5500, balanceOf(token));
        RelayTopup storedPaid = topupsOf(service.listTopups("Bearer " + token).get("list")).stream()
                .filter(item -> topup.getOrderNo().equals(item.getOrderNo()))
                .findFirst()
                .orElseThrow();
        assertEquals(paid.getPaidAt(), storedPaid.getPaidAt());

        RelayTopup cancelled = service.createTopup("Bearer " + token, "p10");
        service.adminUpdateTopupStatus(cancelled.getOrderNo(), "cancelled");
        RelayTopup storedCancelled = topupsOf(service.listTopups("Bearer " + token).get("list")).stream()
                .filter(item -> cancelled.getOrderNo().equals(item.getOrderNo()))
                .findFirst()
                .orElseThrow();
        assertEquals("cancelled", storedCancelled.getStatus());
        assertNotNull(storedCancelled.getCancelledAt());
        assertEquals(5500, balanceOf(token));

        assertRelayError(409, "充值订单状态不可变更",
                () -> service.adminUpdateTopupStatus(cancelled.getOrderNo(), "paid"));
        assertRelayError(400, "充值订单状态不合法",
                () -> service.adminUpdateTopupStatus(topup.getOrderNo(), "refunded"));
        assertRelayError(404, "充值订单不存在",
                () -> service.adminUpdateTopupStatus("RT20000101000000ZZZZ", "paid"));

        RelayTopup userPaid = service.createTopup("Bearer " + token, "p10");
        service.payTopup("Bearer " + token, userPaid.getOrderNo(), "alipay");
        assertRelayError(409, "充值订单状态不可变更",
                () -> service.adminUpdateTopupStatus(userPaid.getOrderNo(), "paid"));
        assertEquals(6500, balanceOf(token));
    }

    @Test
    void adminPaidTopupWithMissingUserFailsWithoutChangingTheOrder() throws IOException {
        String token = register(EMAIL);
        RelayTopup topup = service.createTopup("Bearer " + token, "p50");

        // Point the pending order at a user id that does not exist.
        JsonNode root = objectMapper.readTree(storePath.toFile());
        for (JsonNode record : root.path("topups")) {
            if (topup.getOrderNo().equals(record.path("orderNo").asText())) {
                ((ObjectNode) record).put("userId", "u_deadbeef0000");
            }
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), root);

        assertRelayError(500, "账户数据读取失败",
                () -> service.adminUpdateTopupStatus(topup.getOrderNo(), "paid"));

        JsonNode stored = null;
        for (JsonNode record : objectMapper.readTree(storePath.toFile()).path("topups")) {
            if (topup.getOrderNo().equals(record.path("orderNo").asText())) {
                stored = record;
            }
        }
        assertNotNull(stored);
        assertEquals("pending", stored.path("status").asText());
        assertTrue(stored.path("paidAt").isNull() || stored.path("paidAt").isMissingNode());
        assertEquals(0, balanceOf(token));
    }

    @Test
    void adminUserListAggregatesKeysAndPaidTopups() {
        String tokenA = register("a@example.com");
        ThreadSupport.pause();
        String tokenB = register("b@example.com");
        String idA = (String) mapOf(service.me("Bearer " + tokenA).get("user")).get("id");

        service.createKey("Bearer " + tokenA, "one", false);
        service.createKey("Bearer " + tokenA, "two", false);

        RelayTopup paid = service.createTopup("Bearer " + tokenA, "p50");
        service.payTopup("Bearer " + tokenA, paid.getOrderNo(), "alipay");
        RelayTopup pending = service.createTopup("Bearer " + tokenA, "p10");
        service.adminUpdateTopupStatus(pending.getOrderNo(), "cancelled");

        Map<String, Object> users = service.adminListUsers(null);
        assertEquals(2, users.get("total"));
        List<Map<String, Object>> list = listOf(users.get("list"));
        assertEquals(2, list.size());

        Map<String, Object> rowA = list.stream()
                .filter(row -> idA.equals(row.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("a@example.com", rowA.get("email"));
        assertEquals(5500, ((Number) rowA.get("balanceCents")).intValue());
        assertEquals(false, rowA.get("disabled"));
        assertEquals(2, ((Number) rowA.get("keyCount")).intValue());
        assertEquals(5000L, ((Number) rowA.get("paidTopupCents")).longValue());
        assertNotNull(rowA.get("createdAt"));

        Map<String, Object> limited = service.adminListUsers("1");
        assertEquals(2, limited.get("total"));
        assertEquals(1, listOf(limited.get("list")).size());

        Map<String, Object> invalidLimit = service.adminListUsers("abc");
        assertEquals(2, invalidLimit.get("total"));
        assertEquals(2, listOf(invalidLimit.get("list")).size());

        Map<String, Object> capped = service.adminListUsers("9999");
        assertEquals(2, listOf(capped.get("list")).size());

        assertNotNull(tokenB);
    }

    @Test
    void adminTopupListFiltersAndAttachesEmail() {
        String tokenA = register("a@example.com");
        String tokenB = register("b@example.com");

        RelayTopup paid = service.createTopup("Bearer " + tokenA, "p50");
        service.payTopup("Bearer " + tokenA, paid.getOrderNo(), "alipay");
        service.createTopup("Bearer " + tokenA, "p10");
        service.createTopup("Bearer " + tokenB, "p50");

        Map<String, Object> all = service.adminListTopups(null, null);
        assertEquals(3, all.get("total"));
        List<Map<String, Object>> allRows = listOf(all.get("list"));
        assertEquals(3, allRows.size());
        for (Map<String, Object> row : allRows) {
            assertNotNull(row.get("email"));
            assertNotNull(row.get("orderNo"));
            assertNotNull(row.get("amountCents"));
        }

        Map<String, Object> paidOnly = service.adminListTopups("paid", null);
        assertEquals(1, paidOnly.get("total"));
        assertEquals("a@example.com", listOf(paidOnly.get("list")).get(0).get("email"));
        assertEquals(5000, ((Number) listOf(paidOnly.get("list")).get(0).get("amountCents")).intValue());

        Map<String, Object> pendingOnly = service.adminListTopups("pending", null);
        assertEquals(2, pendingOnly.get("total"));

        Map<String, Object> none = service.adminListTopups("cancelled", null);
        assertEquals(0, none.get("total"));
        assertTrue(listOf(none.get("list")).isEmpty());

        Map<String, Object> limited = service.adminListTopups(null, "2");
        assertEquals(3, limited.get("total"));
        assertEquals(2, listOf(limited.get("list")).size());
    }

    // ------------------------------------------------------------------
    // Usage and billing
    // ------------------------------------------------------------------

    @Test
    void usageIsChargedScopedAndLimited() {
        String token = register(EMAIL);
        String header = "Bearer " + token;
        Map<String, Object> created = service.createKey(header, null, false);
        String rawKey = (String) created.get("key");
        String userId = (String) mapOf(service.me(header).get("user")).get("id");
        RelayKey key = service.findActiveApiKey("Bearer " + rawKey);

        RelayTopup topup = service.createTopup(header, "p10");
        service.payTopup(header, topup.getOrderNo(), "alipay");
        assertEquals(1000, balanceOf(token));

        service.recordUsage(userId, key.getId(), "gpt-4o-mini", 100, 50, 3);
        ThreadSupport.pause();
        service.recordUsage(userId, key.getId(), "gpt-4o-mini", 200, 100, 5);
        assertEquals(992, balanceOf(token));

        Map<String, Object> usage = service.listUsage(header, null);
        assertEquals(2, usage.get("total"));
        List<RelayUsage> records = usageOf(usage.get("list"));
        assertEquals(2, records.size());
        assertEquals("gpt-4o-mini", records.get(0).getModel());
        assertTrue(records.get(0).getCreatedAt().compareTo(records.get(1).getCreatedAt()) >= 0);
        assertTrue(records.get(0).getId().matches("g_[0-9a-f]{12}"));
        assertEquals(200, records.get(0).getPromptTokens());
        assertEquals(100, records.get(0).getCompletionTokens());
        assertEquals(5, records.get(0).getCostCents());

        Map<String, Object> firstOnly = service.listUsage(header, "1");
        assertEquals(2, firstOnly.get("total"));
        assertEquals(1, usageOf(firstOnly.get("list")).size());

        assertEquals(2, usageOf(service.listUsage(header, "abc").get("list")).size());
        assertEquals(2, usageOf(service.listUsage(header, "0").get("list")).size());
        assertEquals(2, usageOf(service.listUsage(header, "9999").get("list")).size());

        RelayKey reloaded = service.findActiveApiKey("Bearer " + rawKey);
        assertNotNull(reloaded.getLastUsedAt());

        service.recordUsage(userId, key.getId(), "test-model", 0, 1, 5000);
        assertEquals(0, balanceOf(token));

        String otherToken = register("other@example.com");
        assertEquals(0, service.listUsage("Bearer " + otherToken, null).get("total"));
    }

    @Test
    void costFormulaMatchesIntegerCentContract() {
        assertEquals(1, RelayService.calculateCostCents(1.08, 4.32, 10, 20));
        assertEquals(0, RelayService.calculateCostCents(1.08, 4.32, 0, 0));
        assertEquals(1, RelayService.calculateCostCents(1.08, 4.32, 0, 1));
        assertEquals(108, RelayService.calculateCostCents(1.08, 4.32, 1_000_000, 0));
        assertEquals(1800, RelayService.calculateCostCents(18, 72, 1_000_000, 0));
        assertEquals(7200, RelayService.calculateCostCents(18, 72, 0, 1_000_000));
        assertEquals(0, RelayService.calculateCostCents(0, 0, 1000, 1000));
        assertEquals(1, RelayService.calculateCostCents(1, 0, 10_000, 0));
        assertEquals(101, RelayService.calculateCostCents(9, 0, 111_112, 0));
        assertEquals(0, RelayService.calculateCostCents(18, 72, -5, -5));
        assertEquals(1, RelayService.calculateCostCents(0.01, 0, 10_000, 0));
    }

    // ------------------------------------------------------------------
    // Store corruption
    // ------------------------------------------------------------------

    @Test
    void corruptStoreFailsWithoutBeingOverwritten() throws IOException {
        Files.createDirectories(storePath.getParent());

        assertCorruptStore("{ this is not json");
        assertCorruptStore("[]");
        assertCorruptStore("{\"users\": {}}");
        assertCorruptStore("{\"users\": [{\"email\": \"a@example.com\"}]}");
        assertCorruptStore("{\"sessions\": [{\"userId\": \"u_000000000000\"}]}");

        Files.writeString(storePath, "{ still broken", StandardCharsets.UTF_8);
        assertRelayError(500, "账户数据读取失败",
                () -> service.register("fresh@example.com", PASSWORD, IP));
        assertRelayError(500, "账户数据读取失败",
                () -> service.listKeys("Bearer rs_000000000000000000000000000000000000000000000000"));
        assertEquals("{ still broken", Files.readString(storePath, StandardCharsets.UTF_8));
    }

    private void assertCorruptStore(String content) throws IOException {
        Files.writeString(storePath, content, StandardCharsets.UTF_8);
        assertRelayError(500, "账户数据读取失败",
                () -> service.getUserById("u_000000000000"));
        assertEquals(content, Files.readString(storePath, StandardCharsets.UTF_8));
    }

    @Test
    void missingStoreBehavesLikeAnEmptyStore() {
        assertFalse(Files.exists(storePath));
        assertNull(service.findActiveApiKey("Bearer sk-relay-" + "0".repeat(48)));
        assertNull(service.getUserById("u_000000000000"));

        Map<String, Object> users = service.adminListUsers(null);
        assertEquals(0, users.get("total"));
        assertTrue(listOf(users.get("list")).isEmpty());
        assertFalse(Files.exists(storePath));

        register(EMAIL);
        assertTrue(Files.exists(storePath));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String register(String email) {
        return (String) service.register(email, PASSWORD, IP).get("token");
    }

    private int balanceOf(String token) {
        Map<String, Object> me = service.me("Bearer " + token);
        return ((Number) mapOf(me.get("user")).get("balanceCents")).intValue();
    }

    private String emailOfUser(String userId) {
        for (Map<String, Object> row : listOf(service.adminListUsers(null).get("list"))) {
            if (userId.equals(row.get("id"))) {
                return (String) row.get("email");
            }
        }
        throw new IllegalStateException("user not found: " + userId);
    }

    private void expireAllSessions() throws IOException {
        JsonNode root = objectMapper.readTree(storePath.toFile());
        for (JsonNode session : root.path("sessions")) {
            ((ObjectNode) session).put("expiresAt", "2000-01-01T00:00:00.000Z");
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), root);
    }

    private void expireResetTokens() throws IOException {
        JsonNode root = objectMapper.readTree(storePath.toFile());
        for (JsonNode reset : root.path("passwordResets")) {
            ((ObjectNode) reset).put("expiresAt", "2000-01-01T00:00:00.000Z");
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), root);
    }

    private void assertRelayError(int status, String message, Executable executable) {
        RelayException exception = assertThrows(RelayException.class, executable);
        assertEquals(status, exception.getStatus().value());
        assertEquals(message, exception.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<RelayTopup> topupsOf(Object value) {
        return (List<RelayTopup>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<RelayUsage> usageOf(Object value) {
        return (List<RelayUsage>) value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
                    {"id": "p10", "name": "入门充值", "price": 10, "credit": 10, "bonus": "到账 10 元额度", "hot": false, "perks": []},
                    {"id": "p50", "name": "标准充值", "price": 50, "credit": 55, "bonus": "到账 55 元额度", "hot": true, "perks": []}
                  ],
                  "faqs": [],
                  "codeSamples": [],
                  "notices": []
                }
                """;
    }

    private static final class ThreadSupport {
        private static void pause() {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
