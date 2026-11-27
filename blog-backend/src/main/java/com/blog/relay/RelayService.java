package com.blog.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Relay accounts, sessions, API keys, top-ups and usage records.
 *
 * <p>The whole store lives in one JSON file that is re-read on every call and
 * replaced atomically with a temp file + rename. All balance changes happen
 * inside a single lock around read/modify/write, which also makes top-up
 * crediting idempotent and impossible to double-count.</p>
 */
@Slf4j
@Service
public class RelayService {

    private static final DateTimeFormatter ISO_UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String ORDER_NO_TIME_PATTERN = "yyyyMMddHHmmss";
    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = buildOrderNoTimeFormatter();
    private static final String ORDER_NO_PREFIX = "RT";
    private static final String ORDER_NO_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ORDER_NO_SUFFIX_LENGTH = 4;
    private static final int ORDER_NO_RETRY_LIMIT = 100;

    private static final String PASSWORD_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PASSWORD_ITERATIONS = 120000;
    private static final int PASSWORD_KEY_LENGTH_BITS = 256;
    private static final int PASSWORD_SALT_BYTES = 16;

    private static final long SESSION_TTL_DAYS = 7L;
    private static final int SESSION_TOKEN_BYTES = 24; // 48 lowercase hex chars
    private static final int API_KEY_BYTES = 24;       // 48 lowercase hex chars
    private static final int ID_BYTES = 6;             // 12 lowercase hex chars
    private static final String SESSION_TOKEN_PREFIX = "rs_";
    private static final String API_KEY_PREFIX = "sk-relay-";

    private static final String RESET_TOKEN_PREFIX = "pr_";
    private static final int RESET_TOKEN_BYTES = 32;       // 64 lowercase hex chars
    private static final long RESET_TTL_MINUTES = 30L;
    private static final long RESET_RESEND_COOLDOWN_SECONDS = 60L;
    private static final int RESET_MAX_PER_HOUR = 5;

    private static final int MAX_ACTIVE_KEYS = 10;
    private static final int MAX_KEY_NAME_LENGTH = 40;
    private static final String DEFAULT_KEY_NAME = "默认令牌";

    private static final String CATALOG_ERROR_PREFIX = "中转目录加载失败";
    private static final String STORE_READ_ERROR = "账户数据读取失败";
    private static final String STORE_WRITE_ERROR = "账户数据保存失败";

    private static final int USER_TOPUP_LIMIT = 100;
    private static final int USAGE_DEFAULT_LIMIT = 50;
    private static final int USAGE_MAX_LIMIT = 200;
    private static final int ADMIN_DEFAULT_LIMIT = 100;
    private static final int ADMIN_MAX_LIMIT = 500;

    private static final int RATE_LIMIT_MAX_REQUESTS = 30;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000L;
    private static final int RATE_LIMIT_CLEANUP_THRESHOLD = 5_000;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> PAYMENT_METHODS = Set.of("alipay", "wechat");

    private final ObjectMapper objectMapper;
    private final Path catalogPath;
    private final Path dataPath;
    private final Object storeLock = new Object();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    /**
     * Local demonstrations confirm a transfer from the browser. Production
     * sets RELAY_ALLOW_SELF_TOPUP=false and lets an administrator mark the
     * pending order paid after checking the real payment.
     */
    @Value("${blog.relay.allow-self-topup:true}")
    private boolean allowSelfTopup = true;

    public RelayService(
            ObjectMapper objectMapper,
            @Value("${blog.relay.catalog-path:${user.dir}/shop/relay.json}") String catalogPath,
            @Value("${blog.relay.data-path:${user.dir}/data/relay-store.json}") String dataPath) {
        this.objectMapper = objectMapper;
        this.catalogPath = Paths.get(catalogPath).toAbsolutePath().normalize();
        this.dataPath = Paths.get(dataPath).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    /**
     * Reads and validates shop/relay.json on every call so operators can edit
     * prices and plans without restarting the service.
     */
    public JsonNode getCatalog() {
        try {
            if (!Files.isRegularFile(catalogPath)) {
                throw new IOException("中转目录文件不存在: " + catalogPath);
            }
            String content = Files.readString(catalogPath, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(content);
            validateCatalog(root);
            return root;
        } catch (RelayException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to load relay catalog from {}", catalogPath, e);
            throw catalogError(e.getMessage() == null ? "未知错误" : e.getMessage());
        }
    }

    private void validateCatalog(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw catalogError("中转目录不是合法的 JSON 对象");
        }

        JsonNode models = root.get("models");
        if (models == null || !models.isArray()) {
            throw catalogError("目录结构缺少 models 数组");
        }
        Set<String> modelNames = new HashSet<>();
        for (JsonNode model : models) {
            if (model == null || !model.isObject()) {
                throw catalogError("模型配置不合法");
            }
            String name = textOf(model.get("name"));
            if (name.isBlank()) {
                throw catalogError("模型名称不合法");
            }
            if (!modelNames.add(name)) {
                throw catalogError("模型名称重复: " + name);
            }
            if (!isNonNegativeNumber(model.get("input")) || !isNonNegativeNumber(model.get("output"))) {
                throw catalogError("模型价格不合法: " + name);
            }
        }

        JsonNode plans = root.get("plans");
        if (plans == null || !plans.isArray()) {
            throw catalogError("目录结构缺少 plans 数组");
        }
        Set<String> planIds = new HashSet<>();
        for (JsonNode plan : plans) {
            if (plan == null || !plan.isObject()) {
                throw catalogError("充值档位配置不合法");
            }
            String id = textOf(plan.get("id")).trim();
            if (id.isEmpty()) {
                throw catalogError("充值档位 id 不合法");
            }
            if (!planIds.add(id)) {
                throw catalogError("充值档位重复: " + id);
            }
            if (!isNonNegativeNumber(plan.get("price")) || !isNonNegativeNumber(plan.get("credit"))) {
                throw catalogError("充值档位金额不合法: " + id);
            }
            BigDecimal price = plan.get("price").decimalValue();
            BigDecimal credit = plan.get("credit").decimalValue();
            if (credit.compareTo(price) < 0) {
                throw catalogError("充值档位赠送额度不足: " + id);
            }
            try {
                toCents(plan.get("price"));
                toCents(plan.get("credit"));
            } catch (ArithmeticException e) {
                throw catalogError("充值档位金额超出范围: " + id);
            }
        }
    }

    private static RelayException catalogError(String reason) {
        return RelayException.internal(CATALOG_ERROR_PREFIX + ": " + reason);
    }

    private static boolean isNonNegativeNumber(JsonNode node) {
        return node != null && node.isNumber()
                && node.decimalValue().compareTo(BigDecimal.ZERO) >= 0;
    }

    private static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static int toCents(JsonNode value) {
        BigDecimal cents = value.decimalValue()
                .multiply(BigDecimal.valueOf(100L))
                .setScale(0, RoundingMode.HALF_UP);
        return cents.intValueExact();
    }

    private JsonNode findPlan(JsonNode catalog, String planId) {
        if (planId == null) {
            return null;
        }
        String wanted = planId.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        JsonNode plans = catalog.path("plans");
        if (!plans.isArray()) {
            return null;
        }
        for (JsonNode plan : plans) {
            if (wanted.equals(textOf(plan.get("id")).trim())) {
                return plan;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    public Map<String, Object> register(String email, String password, String clientIp) {
        checkRateLimit(clientIp);
        String normalizedEmail = normalizeEmail(email);
        if (!isValidEmail(normalizedEmail)) {
            throw RelayException.badRequest("邮箱格式不正确");
        }
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw RelayException.badRequest("密码长度需为 8-72 位");
        }

        synchronized (storeLock) {
            RelayStore store = readStore();
            if (findUserByEmail(store, normalizedEmail) != null) {
                throw RelayException.badRequest("该邮箱已注册");
            }

            String salt = randomHex(PASSWORD_SALT_BYTES);
            String hash = hashPasswordHex(password, salt);
            RelayUser user = RelayUser.builder()
                    .id(generateId("u_", userIds(store)))
                    .email(normalizedEmail)
                    .passwordSalt(salt)
                    .passwordHash(hash)
                    .balanceCents(0)
                    .disabled(false)
                    .createdAt(nowIso())
                    .build();
            String token = SESSION_TOKEN_PREFIX + randomHex(SESSION_TOKEN_BYTES);
            store.getUsers().add(user);
            store.getSessions().add(newSession(token, user.getId()));
            writeStore(store);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("user", publicUser(user));
            return result;
        }
    }

    public Map<String, Object> login(String email, String password, String clientIp) {
        checkRateLimit(clientIp);
        String normalizedEmail = normalizeEmail(email);

        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = normalizedEmail.isEmpty() ? null : findUserByEmail(store, normalizedEmail);
            boolean valid = user != null
                    && password != null
                    && verifyPassword(password, user.getPasswordSalt(), user.getPasswordHash());
            if (!valid) {
                throw RelayException.unauthorized("邮箱或密码错误");
            }

            String token = SESSION_TOKEN_PREFIX + randomHex(SESSION_TOKEN_BYTES);
            store.getSessions().add(newSession(token, user.getId()));
            writeStore(store);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("user", publicUser(user));
            return result;
        }
    }

    /** Plaintext reset token handed to the controller for the outbound email. */
    public record PasswordResetTicket(String email, String token) {
    }

    /**
     * Creates a single-use password reset token. Returns null when the email is
     * unknown, the account is disabled, or a reset was requested moments ago;
     * callers must always answer with the same generic success message so the
     * endpoint cannot be used to discover registered addresses.
     */
    public PasswordResetTicket createPasswordReset(String email, String clientIp) {
        checkRateLimit(clientIp);
        String normalizedEmail = normalizeEmail(email);
        if (!isValidEmail(normalizedEmail)) {
            throw RelayException.badRequest("邮箱格式不正确");
        }

        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = findUserByEmail(store, normalizedEmail);
            if (user == null || user.isDisabled()) {
                return null;
            }

            for (RelayPasswordReset existing : store.getPasswordResets()) {
                if (existing != null && user.getId().equals(existing.getUserId())
                        && isWithinCooldown(existing.getCreatedAt(), RESET_RESEND_COOLDOWN_SECONDS)) {
                    // Throttle email bombing without telling the caller why.
                    return null;
                }
            }

            // Cap how many messages one address can receive from any number of
            // source IPs. Older links stay valid until they expire, so a third
            // party cannot invalidate the link the owner is about to open.
            long recent = store.getPasswordResets().stream()
                    .filter(reset -> reset != null && user.getId().equals(reset.getUserId()))
                    .filter(reset -> isWithinCooldown(reset.getCreatedAt(), 3_600L))
                    .count();
            if (recent >= RESET_MAX_PER_HOUR) {
                return null;
            }
            store.getPasswordResets().removeIf(reset -> reset == null
                    || isExpired(reset.getExpiresAt()));

            Instant now = Instant.now();
            String token = RESET_TOKEN_PREFIX + randomHex(RESET_TOKEN_BYTES);
            store.getPasswordResets().add(RelayPasswordReset.builder()
                    .tokenHash(sha256Hex(token))
                    .userId(user.getId())
                    .createdAt(nowIso())
                    .expiresAt(ISO_UTC_MILLIS.format(now.plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES)))
                    .build());
            writeStore(store);
            return new PasswordResetTicket(normalizedEmail, token);
        }
    }

    /**
     * Consumes a reset token, changes the password and revokes every existing
     * session and API key for that account. Tokens are single-use and expire
     * after 30 minutes.
     */
    public Map<String, Object> resetPassword(String token, String password, String clientIp) {
        checkRateLimit(clientIp);
        if (token == null || token.isBlank()) {
            throw RelayException.badRequest("重置链接无效或已过期");
        }
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw RelayException.badRequest("密码长度需为 8-72 位");
        }

        synchronized (storeLock) {
            RelayStore store = readStore();
            String tokenHash = sha256Hex(token.trim());
            RelayPasswordReset match = null;
            for (RelayPasswordReset reset : store.getPasswordResets()) {
                if (reset != null && tokenHash.equals(reset.getTokenHash())) {
                    match = reset;
                    break;
                }
            }
            RelayUser user = match == null ? null : findUserById(store, match.getUserId());
            if (match == null || user == null || user.isDisabled() || isExpired(match.getExpiresAt())) {
                throw RelayException.badRequest("重置链接无效或已过期");
            }

            String salt = randomHex(PASSWORD_SALT_BYTES);
            user.setPasswordSalt(salt);
            user.setPasswordHash(hashPasswordHex(password, salt));
            store.getPasswordResets().removeIf(reset -> reset == null
                    || user.getId().equals(reset.getUserId()));
            store.getSessions().removeIf(session -> session == null
                    || user.getId().equals(session.getUserId()));
            // Keys minted before the reset could belong to whoever the reset is
            // meant to evict, so revoke them too (history is kept for the UI).
            String revokedAt = nowIso();
            for (RelayKey key : store.getKeys()) {
                if (key != null && user.getId().equals(key.getUserId())
                        && key.getRevokedAt() == null) {
                    key.setRevokedAt(revokedAt);
                }
            }
            writeStore(store);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reset", true);
            return result;
        }
    }

    /**
     * Removes the current session when it exists. A missing or unknown token
     * still reports success so clients can always clear local state.
     */
    public Map<String, Object> logout(String authorization) {
        String token = bearerToken(authorization);
        if (token != null) {
            String hash = sha256Hex(token);
            synchronized (storeLock) {
                RelayStore store = readStore();
                boolean removed = store.getSessions().removeIf(session ->
                        session != null && hash.equals(session.getTokenHash()));
                if (removed) {
                    writeStore(store);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    public Map<String, Object> me(String authorization) {
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("user", publicUser(user));
            return result;
        }
    }

    public RelayUser getUserById(String userId) {
        if (userId == null) {
            return null;
        }
        synchronized (storeLock) {
            return findUserById(readStore(), userId);
        }
    }

    private static Map<String, Object> publicUser(RelayUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("email", user.getEmail());
        map.put("balanceCents", user.getBalanceCents());
        map.put("createdAt", user.getCreatedAt());
        return map;
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidEmail(String email) {
        return email.length() <= 120 && EMAIL_PATTERN.matcher(email).matches();
    }

    private RelaySession newSession(String token, String userId) {
        Instant now = Instant.now();
        return RelaySession.builder()
                .tokenHash(sha256Hex(token))
                .userId(userId)
                .createdAt(ISO_UTC_MILLIS.format(now))
                .expiresAt(ISO_UTC_MILLIS.format(now.plus(SESSION_TTL_DAYS, ChronoUnit.DAYS)))
                .build();
    }

    private RelayUser requireUser(RelayStore store, String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            throw RelayException.unauthorized("登录已失效，请重新登录");
        }
        String hash = sha256Hex(token);
        RelaySession session = null;
        for (RelaySession candidate : store.getSessions()) {
            if (candidate != null && hash.equals(candidate.getTokenHash())) {
                session = candidate;
                break;
            }
        }
        if (session == null || isExpired(session.getExpiresAt())) {
            throw RelayException.unauthorized("登录已失效，请重新登录");
        }
        RelayUser user = findUserById(store, session.getUserId());
        if (user == null) {
            throw RelayException.unauthorized("登录已失效，请重新登录");
        }
        return user;
    }

    private static boolean isExpired(String expiresAt) {
        if (expiresAt == null) {
            return true;
        }
        try {
            return !Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private static boolean isWithinCooldown(String createdAt, long seconds) {
        if (createdAt == null) {
            return false;
        }
        try {
            return Instant.parse(createdAt).plusSeconds(seconds).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.strip();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    // ------------------------------------------------------------------
    // API keys
    // ------------------------------------------------------------------

    public Map<String, Object> listKeys(String authorization) {
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            List<RelayKey> keys = new ArrayList<>();
            for (RelayKey key : store.getKeys()) {
                if (key != null && user.getId().equals(key.getUserId())) {
                    keys.add(key);
                }
            }
            sortByCreatedAtDesc(keys, RelayKey::getCreatedAt);

            List<Map<String, Object>> list = new ArrayList<>();
            for (RelayKey key : keys) {
                list.add(keyRecord(key));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            return result;
        }
    }

    public Map<String, Object> createKey(String authorization, String rawName, boolean nameInvalid) {
        if (nameInvalid) {
            throw RelayException.badRequest("备注不合法");
        }
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty()) {
            name = DEFAULT_KEY_NAME;
        }
        if (name.codePointCount(0, name.length()) > MAX_KEY_NAME_LENGTH) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_KEY_NAME_LENGTH));
        }

        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            int active = 0;
            Set<String> usedIds = new HashSet<>();
            for (RelayKey key : store.getKeys()) {
                if (key == null) {
                    continue;
                }
                if (key.getId() != null) {
                    usedIds.add(key.getId());
                }
                if (user.getId().equals(key.getUserId()) && key.getRevokedAt() == null) {
                    active++;
                }
            }
            if (active >= MAX_ACTIVE_KEYS) {
                throw RelayException.badRequest("令牌数量已达上限");
            }

            String rawKey = API_KEY_PREFIX + randomHex(API_KEY_BYTES);
            RelayKey key = RelayKey.builder()
                    .id(generateId("k_", usedIds))
                    .userId(user.getId())
                    .name(name)
                    .prefix(rawKey.substring(0, 16))
                    .keyHash(sha256Hex(rawKey))
                    .createdAt(nowIso())
                    .lastUsedAt(null)
                    .revokedAt(null)
                    .build();
            store.getKeys().add(key);
            writeStore(store);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", rawKey);
            result.put("record", keyRecord(key));
            return result;
        }
    }

    public Map<String, Object> revokeKey(String authorization, String keyId) {
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            RelayKey key = findKey(store, keyId);
            if (key == null || !user.getId().equals(key.getUserId())) {
                throw RelayException.notFound("令牌不存在");
            }
            if (key.getRevokedAt() == null) {
                key.setRevokedAt(nowIso());
                writeStore(store);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            return result;
        }
    }

    /**
     * Resolves a relay API key from an Authorization header. Returns null when
     * the key is unknown or revoked; the proxy turns that into an OpenAI error.
     */
    public RelayKey findActiveApiKey(String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            return null;
        }
        String hash = sha256Hex(token);
        synchronized (storeLock) {
            RelayStore store = readStore();
            for (RelayKey key : store.getKeys()) {
                if (key != null
                        && hash.equals(key.getKeyHash())
                        && key.getRevokedAt() == null) {
                    return key;
                }
            }
            return null;
        }
    }

    private static Map<String, Object> keyRecord(RelayKey key) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", key.getId());
        record.put("prefix", key.getPrefix());
        record.put("name", key.getName());
        record.put("createdAt", key.getCreatedAt());
        record.put("lastUsedAt", key.getLastUsedAt());
        record.put("revokedAt", key.getRevokedAt());
        return record;
    }

    private static RelayKey findKey(RelayStore store, String keyId) {
        if (keyId == null) {
            return null;
        }
        String wanted = keyId.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        for (RelayKey key : store.getKeys()) {
            if (key != null && wanted.equals(key.getId())) {
                return key;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Top-ups
    // ------------------------------------------------------------------

    public Map<String, Object> listTopups(String authorization) {
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            List<RelayTopup> matching = new ArrayList<>();
            for (RelayTopup topup : store.getTopups()) {
                if (topup != null && user.getId().equals(topup.getUserId())) {
                    matching.add(topup);
                }
            }
            sortByCreatedAtDesc(matching, RelayTopup::getCreatedAt);
            int total = matching.size();
            List<RelayTopup> list = matching.size() > USER_TOPUP_LIMIT
                    ? new ArrayList<>(matching.subList(0, USER_TOPUP_LIMIT))
                    : matching;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            result.put("total", total);
            return result;
        }
    }

    public RelayTopup createTopup(String authorization, String planId) {
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            JsonNode catalog = getCatalog();
            JsonNode plan = findPlan(catalog, planId);
            if (plan == null) {
                throw RelayException.badRequest("充值档位不存在");
            }

            int amountCents;
            int creditCents;
            try {
                amountCents = toCents(plan.get("price"));
                creditCents = toCents(plan.get("credit"));
            } catch (ArithmeticException e) {
                throw catalogError("充值档位金额超出范围: " + textOf(plan.get("id")).trim());
            }
            if (creditCents < amountCents) {
                throw catalogError("充值档位赠送额度不足: " + textOf(plan.get("id")).trim());
            }

            RelayTopup topup = RelayTopup.builder()
                    .orderNo(generateOrderNo(store.getTopups()))
                    .userId(user.getId())
                    .planId(textOf(plan.get("id")).trim())
                    .planName(textOf(plan.get("name")))
                    .amountCents(amountCents)
                    .bonusCents(creditCents - amountCents)
                    .creditCents(creditCents)
                    .status("pending")
                    .method(null)
                    .createdAt(nowIso())
                    .paidAt(null)
                    .cancelledAt(null)
                    .build();
            store.getTopups().add(topup);
            writeStore(store);
            return topup;
        }
    }

    /**
     * True when the email belongs to an existing relay account. Used by the
     * shop to reject credit orders before payment when the buyer has not
     * registered yet.
     */
    public boolean relayUserExists(String email) {
        return relayUserId(email) != null;
    }

    /** Relay user id for an email, or null when the account does not exist. */
    public String relayUserId(String email) {
        String normalized = normalizeEmail(email);
        if (!isValidEmail(normalized)) {
            return null;
        }
        synchronized (storeLock) {
            RelayUser user = findUserByEmail(readStore(), normalized);
            return user == null ? null : user.getId();
        }
    }

    /** Credit in cents for a catalog plan multiplied by a shop quantity. */
    public int shopCreditCents(String planId, int quantity) {
        if (quantity < 1 || quantity > 99) {
            throw RelayException.badRequest("购买数量不合法");
        }
        JsonNode plan = findPlan(getCatalog(), planId);
        if (plan == null) {
            throw RelayException.badRequest("充值档位不存在");
        }
        try {
            return Math.multiplyExact(toCents(plan.get("credit")), quantity);
        } catch (ArithmeticException e) {
            throw catalogError("充值档位金额超出范围: " + textOf(plan.get("id")).trim());
        }
    }

    /**
     * Credits a relay account for a paid shop order and records a matching
     * top-up. The deterministic SHOP-&lt;orderNo&gt; top-up makes retries
     * idempotent, so a crash between the relay write and the shop write cannot
     * credit the same order twice.
     */
    public RelayTopup creditShopOrder(String email, String planId, int quantity,
                                      int amountCents, String shopOrderNo, String method) {
        String normalizedEmail = normalizeEmail(email);
        if (!isValidEmail(normalizedEmail)) {
            throw RelayException.badRequest("中转站注册邮箱不正确");
        }
        if (quantity < 1 || quantity > 99) {
            throw RelayException.badRequest("购买数量不合法");
        }
        String rawOrderNo = shopOrderNo == null ? "" : shopOrderNo.trim();
        if (rawOrderNo.isEmpty()) {
            throw RelayException.badRequest("商城订单号不正确");
        }
        if (amountCents < 0) {
            throw RelayException.badRequest("商城订单金额不正确");
        }
        String sourceOrderNo = "SHOP-" + rawOrderNo;
        String normalizedMethod = normalizePaymentMethod(method);

        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayTopup existing = findTopup(store, sourceOrderNo);
            if (existing != null) {
                return existing;
            }

            RelayUser user = findUserByEmail(store, normalizedEmail);
            if (user == null) {
                throw RelayException.badRequest("该邮箱尚未注册中转站，请先注册");
            }

            JsonNode plan = findPlan(getCatalog(), planId);
            if (plan == null) {
                throw RelayException.badRequest("充值档位不存在");
            }
            int planAmount;
            int planCredit;
            try {
                planAmount = toCents(plan.get("price"));
                planCredit = toCents(plan.get("credit"));
            } catch (ArithmeticException e) {
                throw catalogError("充值档位金额超出范围: " + textOf(plan.get("id")).trim());
            }
            int expectedAmount;
            int creditCents;
            try {
                expectedAmount = Math.multiplyExact(planAmount, quantity);
                creditCents = Math.multiplyExact(planCredit, quantity);
            } catch (ArithmeticException e) {
                throw RelayException.badRequest("购买数量不合法");
            }
            if (amountCents != expectedAmount) {
                throw RelayException.badRequest("商城订单金额与充值档位不一致");
            }

            RelayTopup topup = RelayTopup.builder()
                    .orderNo(sourceOrderNo)
                    .userId(user.getId())
                    .planId(textOf(plan.get("id")).trim())
                    .planName(textOf(plan.get("name")))
                    .amountCents(expectedAmount)
                    .bonusCents(Math.max(0, creditCents - expectedAmount))
                    .creditCents(creditCents)
                    .status("paid")
                    .method(normalizedMethod)
                    .createdAt(nowIso())
                    .paidAt(nowIso())
                    .cancelledAt(null)
                    .build();
            store.getTopups().add(topup);
            addCredit(user, creditCents);
            writeStore(store);
            return topup;
        }
    }

    public RelayTopup payTopup(String authorization, String orderNo, String method) {
        if (!allowSelfTopup) {
            throw RelayException.forbidden("请支付后联系管理员确认到账");
        }
        String normalizedMethod = normalizePaymentMethod(method);
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            RelayTopup topup = findTopup(store, orderNo);
            if (topup == null) {
                throw RelayException.notFound("充值订单不存在");
            }
            if (!user.getId().equals(topup.getUserId())) {
                throw RelayException.forbidden("无权操作该订单");
            }

            String status = topup.getStatus();
            if ("paid".equals(status)) {
                return topup;
            }
            if ("cancelled".equals(status)) {
                throw RelayException.conflict("订单已取消");
            }
            if (!"pending".equals(status)) {
                throw RelayException.conflict("充值订单状态不可变更");
            }

            topup.setStatus("paid");
            topup.setPaidAt(nowIso());
            if (normalizedMethod != null) {
                topup.setMethod(normalizedMethod);
            }
            addCredit(user, topup.getCreditCents());
            writeStore(store);
            return topup;
        }
    }

    public Map<String, Object> listUsage(String authorization, String rawLimit) {
        int limit = parseLimit(rawLimit, USAGE_DEFAULT_LIMIT, USAGE_MAX_LIMIT);
        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayUser user = requireUser(store, authorization);

            List<RelayUsage> matching = new ArrayList<>();
            for (RelayUsage usage : store.getUsage()) {
                if (usage != null && user.getId().equals(usage.getUserId())) {
                    matching.add(usage);
                }
            }
            sortByCreatedAtDesc(matching, RelayUsage::getCreatedAt);
            int total = matching.size();
            List<RelayUsage> list = matching.size() > limit
                    ? new ArrayList<>(matching.subList(0, limit))
                    : matching;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            result.put("total", total);
            return result;
        }
    }

    private static String normalizePaymentMethod(String method) {
        if (method == null) {
            return null;
        }
        String normalized = method.strip().toLowerCase(Locale.ROOT);
        return PAYMENT_METHODS.contains(normalized) ? normalized : null;
    }

    private static RelayTopup findTopup(RelayStore store, String orderNo) {
        if (orderNo == null) {
            return null;
        }
        String wanted = orderNo.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        for (RelayTopup topup : store.getTopups()) {
            if (topup != null && wanted.equals(topup.getOrderNo())) {
                return topup;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------------

    public Map<String, Object> adminListUsers(String rawLimit) {
        int limit = parseLimit(rawLimit, ADMIN_DEFAULT_LIMIT, ADMIN_MAX_LIMIT);
        synchronized (storeLock) {
            RelayStore store = readStore();

            List<RelayUser> users = new ArrayList<>();
            for (RelayUser user : store.getUsers()) {
                if (user != null) {
                    users.add(user);
                }
            }
            sortByCreatedAtDesc(users, RelayUser::getCreatedAt);
            int total = users.size();
            List<RelayUser> page = users.size() > limit
                    ? new ArrayList<>(users.subList(0, limit))
                    : users;

            List<Map<String, Object>> list = new ArrayList<>();
            for (RelayUser user : page) {
                int keyCount = 0;
                for (RelayKey key : store.getKeys()) {
                    if (key != null && user.getId().equals(key.getUserId())) {
                        keyCount++;
                    }
                }
                long paidTopupCents = 0L;
                for (RelayTopup topup : store.getTopups()) {
                    if (topup != null
                            && user.getId().equals(topup.getUserId())
                            && "paid".equals(topup.getStatus())) {
                        paidTopupCents += topup.getAmountCents();
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", user.getId());
                row.put("email", user.getEmail());
                row.put("balanceCents", user.getBalanceCents());
                row.put("disabled", user.isDisabled());
                row.put("createdAt", user.getCreatedAt());
                row.put("keyCount", keyCount);
                row.put("paidTopupCents", paidTopupCents);
                list.add(row);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            result.put("total", total);
            return result;
        }
    }

    public Map<String, Object> adminListTopups(String status, String rawLimit) {
        String filter = status == null ? "" : status.trim();
        int limit = parseLimit(rawLimit, ADMIN_DEFAULT_LIMIT, ADMIN_MAX_LIMIT);
        synchronized (storeLock) {
            RelayStore store = readStore();

            Map<String, String> emails = new LinkedHashMap<>();
            for (RelayUser user : store.getUsers()) {
                if (user != null) {
                    emails.put(user.getId(), user.getEmail());
                }
            }

            List<RelayTopup> matching = new ArrayList<>();
            for (RelayTopup topup : store.getTopups()) {
                if (topup != null && (filter.isEmpty() || filter.equals(topup.getStatus()))) {
                    matching.add(topup);
                }
            }
            sortByCreatedAtDesc(matching, RelayTopup::getCreatedAt);
            int total = matching.size();
            List<RelayTopup> page = matching.size() > limit
                    ? new ArrayList<>(matching.subList(0, limit))
                    : matching;

            List<Map<String, Object>> list = new ArrayList<>();
            for (RelayTopup topup : page) {
                Map<String, Object> row = objectMapper.convertValue(
                        topup, new TypeReference<LinkedHashMap<String, Object>>() {
                        });
                row.put("email", emails.get(topup.getUserId()));
                list.add(row);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("list", list);
            result.put("total", total);
            return result;
        }
    }

    public RelayTopup adminUpdateTopupStatus(String orderNo, String status) {
        String nextStatus = status == null ? "" : status.trim();
        if (!"paid".equals(nextStatus) && !"cancelled".equals(nextStatus)) {
            throw RelayException.badRequest("充值订单状态不合法");
        }

        synchronized (storeLock) {
            RelayStore store = readStore();
            RelayTopup topup = findTopup(store, orderNo);
            if (topup == null) {
                throw RelayException.notFound("充值订单不存在");
            }
            if (!"pending".equals(topup.getStatus())) {
                throw RelayException.conflict("充值订单状态不可变更");
            }

            if ("paid".equals(nextStatus)) {
                RelayUser user = findUserById(store, topup.getUserId());
                if (user == null) {
                    // Match the Node backend: an order that references a missing
                    // account must fail and stay pending instead of silently
                    // becoming paid without crediting anyone.
                    log.error("Paid top-up {} references missing user {}",
                            topup.getOrderNo(), topup.getUserId());
                    throw RelayException.internal(STORE_READ_ERROR);
                }
                topup.setStatus("paid");
                topup.setPaidAt(nowIso());
                addCredit(user, topup.getCreditCents());
            } else {
                topup.setStatus("cancelled");
                topup.setCancelledAt(nowIso());
            }
            writeStore(store);
            return topup;
        }
    }

    private static void addCredit(RelayUser user, int creditCents) {
        long next = (long) user.getBalanceCents() + creditCents;
        if (next > Integer.MAX_VALUE) {
            next = Integer.MAX_VALUE;
        }
        user.setBalanceCents((int) Math.max(0L, next));
    }

    // ------------------------------------------------------------------
    // Usage billing
    // ------------------------------------------------------------------

    /**
     * Charges one upstream call: deducts the balance (never below zero),
     * appends the usage record and updates the key's lastUsedAt in a single
     * atomic write.
     */
    public void recordUsage(String userId, String keyId, String model,
                            long promptTokens, long completionTokens, int costCents) {
        synchronized (storeLock) {
            RelayStore store = readStore();

            RelayUser user = findUserById(store, userId);
            if (user != null) {
                long next = (long) user.getBalanceCents() - Math.max(0, costCents);
                user.setBalanceCents((int) Math.max(0L, Math.min(next, Integer.MAX_VALUE)));
            } else {
                log.warn("Usage record references missing user {}", userId);
            }

            String now = nowIso();
            if (keyId != null) {
                for (RelayKey key : store.getKeys()) {
                    if (key != null && keyId.equals(key.getId())) {
                        key.setLastUsedAt(now);
                        break;
                    }
                }
            }

            Set<String> usedIds = new HashSet<>();
            for (RelayUsage usage : store.getUsage()) {
                if (usage != null && usage.getId() != null) {
                    usedIds.add(usage.getId());
                }
            }
            RelayUsage record = RelayUsage.builder()
                    .id(generateId("g_", usedIds))
                    .userId(userId)
                    .keyId(keyId)
                    .model(model)
                    .promptTokens(Math.max(0L, promptTokens))
                    .completionTokens(Math.max(0L, completionTokens))
                    .costCents(Math.max(0, costCents))
                    .createdAt(now)
                    .build();
            store.getUsage().add(record);
            writeStore(store);
        }
    }

    /**
     * Integer-cent cost from 元/百万 token prices.
     *
     * <pre>
     * inputCentsPerMillion = round(input * 100)
     * numerator = prompt * inputCentsPerMillion + completion * outputCentsPerMillion
     * cost = numerator &gt; 0 ? max(1, ceil(numerator / 1_000_000)) : 0
     * </pre>
     */
    public static int calculateCostCents(double inputPrice, double outputPrice,
                                         long promptTokens, long completionTokens) {
        long inputCentsPerMillion = Math.round(Math.max(0.0, inputPrice) * 100.0);
        long outputCentsPerMillion = Math.round(Math.max(0.0, outputPrice) * 100.0);
        long prompt = Math.max(0L, promptTokens);
        long completion = Math.max(0L, completionTokens);
        long numerator = prompt * inputCentsPerMillion + completion * outputCentsPerMillion;
        if (numerator <= 0) {
            return 0;
        }
        long cost = (numerator + 999_999L) / 1_000_000L;
        if (cost < 1L) {
            cost = 1L;
        }
        return cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost;
    }

    // ------------------------------------------------------------------
    // Limit parsing
    // ------------------------------------------------------------------

    private static int parseLimit(String rawLimit, int defaultLimit, int maxLimit) {
        if (rawLimit == null) {
            return defaultLimit;
        }
        String value = rawLimit.trim();
        int end = 0;
        if (end < value.length() && (value.charAt(end) == '+' || value.charAt(end) == '-')) {
            end++;
        }
        int digitsStart = end;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == digitsStart) {
            return defaultLimit;
        }
        try {
            double parsed = Double.parseDouble(value.substring(0, end));
            return parsed > 0 ? (int) Math.min(parsed, maxLimit) : defaultLimit;
        } catch (NumberFormatException e) {
            return defaultLimit;
        }
    }

    // ------------------------------------------------------------------
    // Password hashing
    // ------------------------------------------------------------------

    private static String hashPasswordHex(String password, String saltHex) {
        byte[] salt = HexFormat.of().parseHex(saltHex);
        byte[] hash = hashPasswordBytes(password, salt);
        return HexFormat.of().formatHex(hash);
    }

    private static byte[] hashPasswordBytes(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PASSWORD_ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码哈希失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean verifyPassword(String password, String saltHex, String hashHex) {
        if (password == null || saltHex == null || hashHex == null) {
            return false;
        }
        byte[] expected;
        byte[] salt;
        try {
            expected = HexFormat.of().parseHex(hashHex);
            salt = HexFormat.of().parseHex(saltHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = hashPasswordBytes(password, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    // ------------------------------------------------------------------
    // IDs, tokens and order numbers
    // ------------------------------------------------------------------

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private String generateId(String prefix, Collection<String> used) {
        for (int attempt = 0; attempt < ORDER_NO_RETRY_LIMIT; attempt++) {
            String candidate = prefix + randomHex(ID_BYTES);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw RelayException.internal("ID 生成失败，请重试");
    }

    private static Set<String> userIds(RelayStore store) {
        Set<String> ids = new HashSet<>();
        for (RelayUser user : store.getUsers()) {
            if (user != null && user.getId() != null) {
                ids.add(user.getId());
            }
        }
        return ids;
    }

    private String generateOrderNo(List<RelayTopup> topups) {
        Set<String> used = new HashSet<>();
        for (RelayTopup topup : topups) {
            if (topup != null && topup.getOrderNo() != null) {
                used.add(topup.getOrderNo());
            }
        }
        for (int attempt = 0; attempt < ORDER_NO_RETRY_LIMIT; attempt++) {
            String candidate = ORDER_NO_PREFIX
                    + LocalDateTime.now().format(ORDER_NO_TIME_FORMATTER)
                    + randomOrderSuffix();
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw RelayException.internal("充值单号生成失败，请重试");
    }

    private String randomOrderSuffix() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder suffix = new StringBuilder(ORDER_NO_SUFFIX_LENGTH);
        for (int i = 0; i < ORDER_NO_SUFFIX_LENGTH; i++) {
            suffix.append(ORDER_NO_ALPHABET.charAt(random.nextInt(ORDER_NO_ALPHABET.length())));
        }
        return suffix.toString();
    }

    private static DateTimeFormatter buildOrderNoTimeFormatter() {
        try {
            return DateTimeFormatter.ofPattern(ORDER_NO_TIME_PATTERN);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid relay order-number time pattern '{}', using default", ORDER_NO_TIME_PATTERN);
            return DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ------------------------------------------------------------------
    // Rate limiting
    // ------------------------------------------------------------------

    private void checkRateLimit(String clientIp) {
        long now = System.currentTimeMillis();
        if (rateWindows.size() > RATE_LIMIT_CLEANUP_THRESHOLD) {
            rateWindows.entrySet().removeIf(entry ->
                    now - entry.getValue().windowStart >= RATE_LIMIT_WINDOW_MS);
        }
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        RateWindow window = rateWindows.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStart >= RATE_LIMIT_WINDOW_MS) {
                return new RateWindow(now, 1);
            }
            current.count += 1;
            return current;
        });
        if (window.count > RATE_LIMIT_MAX_REQUESTS) {
            throw RelayException.tooManyRequests("请求过于频繁，请稍后再试");
        }
    }

    private static final class RateWindow {
        private final long windowStart;
        private int count;

        private RateWindow(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }

    // ------------------------------------------------------------------
    // Store persistence
    // ------------------------------------------------------------------

    private RelayStore readStore() {
        if (!Files.exists(dataPath)) {
            return new RelayStore();
        }
        try {
            if (!Files.isRegularFile(dataPath)) {
                throw new IOException("账户数据路径不是普通文件: " + dataPath);
            }
            String content = Files.readString(dataPath, StandardCharsets.UTF_8);
            RelayStore store = objectMapper.readValue(content, RelayStore.class);
            if (store == null) {
                throw new IOException("账户数据不是合法的 JSON 对象");
            }
            normalizeStore(store);
            return store;
        } catch (RelayException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read relay store from {}", dataPath, e);
            throw RelayException.internal(STORE_READ_ERROR);
        }
    }

    private static void normalizeStore(RelayStore store) throws IOException {
        if (store.getUsers() == null) {
            store.setUsers(new ArrayList<>());
        }
        if (store.getSessions() == null) {
            store.setSessions(new ArrayList<>());
        }
        if (store.getKeys() == null) {
            store.setKeys(new ArrayList<>());
        }
        if (store.getTopups() == null) {
            store.setTopups(new ArrayList<>());
        }
        if (store.getUsage() == null) {
            store.setUsage(new ArrayList<>());
        }
        if (store.getPasswordResets() == null) {
            store.setPasswordResets(new ArrayList<>());
        }

        for (RelayUser user : store.getUsers()) {
            if (user == null || isBlank(user.getId())) {
                throw new IOException("用户记录缺少 id");
            }
        }
        for (RelaySession session : store.getSessions()) {
            if (session == null || isBlank(session.getTokenHash()) || isBlank(session.getUserId())) {
                throw new IOException("会话记录不完整");
            }
        }
        for (RelayKey key : store.getKeys()) {
            if (key == null || isBlank(key.getId()) || isBlank(key.getUserId())
                    || isBlank(key.getKeyHash())) {
                throw new IOException("令牌记录不完整");
            }
        }
        for (RelayTopup topup : store.getTopups()) {
            if (topup == null || isBlank(topup.getOrderNo()) || isBlank(topup.getUserId())) {
                throw new IOException("充值记录不完整");
            }
        }
        for (RelayUsage usage : store.getUsage()) {
            if (usage == null || isBlank(usage.getId()) || isBlank(usage.getUserId())) {
                throw new IOException("用量记录不完整");
            }
        }
        for (RelayPasswordReset reset : store.getPasswordResets()) {
            if (reset == null || isBlank(reset.getTokenHash()) || isBlank(reset.getUserId())) {
                throw new IOException("密码重置记录不完整");
            }
        }
    }

    private void writeStore(RelayStore store) {
        Path parent = dataPath.getParent();
        Path tempFile = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tempFile = Files.createTempFile(parent, "relay-store-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), store);
            try {
                Files.move(tempFile, dataPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, dataPath, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;
        } catch (IOException e) {
            log.error("Failed to write relay store to {}", dataPath, e);
            throw RelayException.internal(STORE_WRITE_ERROR);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best effort cleanup of the temp file
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static RelayUser findUserById(RelayStore store, String userId) {
        if (userId == null) {
            return null;
        }
        for (RelayUser user : store.getUsers()) {
            if (user != null && userId.equals(user.getId())) {
                return user;
            }
        }
        return null;
    }

    private static RelayUser findUserByEmail(RelayStore store, String email) {
        if (email == null || email.isEmpty()) {
            return null;
        }
        for (RelayUser user : store.getUsers()) {
            if (user != null && email.equals(user.getEmail())) {
                return user;
            }
        }
        return null;
    }

    private static <T> void sortByCreatedAtDesc(List<T> items, Function<T, String> createdAt) {
        items.sort(Comparator.comparing(createdAt,
                Comparator.nullsLast(Comparator.<String>reverseOrder())));
    }

    private static String nowIso() {
        return ISO_UTC_MILLIS.format(Instant.now());
    }
}
