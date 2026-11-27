package com.blog.shop;

import com.blog.mail.MailGateway;
import com.blog.relay.RelayException;
import com.blog.relay.RelayService;
import com.blog.relay.RelayTopup;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class ShopService {

    private static final DateTimeFormatter ISO_UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * Local-time layout used in order numbers, e.g. PB20261126143022ABCD.
     * The build injects the operator-provided pattern here.
     */
    private static final String ORDER_NO_TIME_PATTERN = "yyyyMMddHHmmss";

    private static final DateTimeFormatter ORDER_NO_TIME_FORMATTER = buildOrderNoTimeFormatter();

    private static final String ORDER_NO_PREFIX = "PB";
    private static final String ORDER_NO_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ORDER_NO_SUFFIX_LENGTH = 4;
    private static final int ORDER_NO_RETRY_LIMIT = 100;
    private static final int DEFAULT_ORDER_LIMIT = 100;
    private static final int MAX_ORDER_LIMIT = 500;

    private static final String LOOKUP_TOKEN_PREFIX = "so_";
    private static final int LOOKUP_TOKEN_BYTES = 32;
    private static final SecureRandom LOOKUP_TOKEN_RANDOM = new SecureRandom();

    private static final Set<String> VALID_STATUSES = Set.of("pending", "paid", "delivered", "cancelled");
    // Every order except a cancelled one consumes stock: pending/paid reserve
    // it, delivered has shipped it. Only cancellation returns it to the shelf.
    private static final Set<String> SOLD_STATUSES = Set.of("pending", "paid", "delivered");
    private static final Set<String> VALID_PAYMENT_METHODS = Set.of("alipay", "wechat");

    private static final String CATALOG_ERROR_PREFIX = "商品目录加载失败";
    private static final String ORDERS_READ_ERROR_PREFIX = "订单数据读取失败";

    private final ObjectMapper objectMapper;
    private final RelayService relayService;
    private final Path catalogPath;
    private final Path ordersPath;
    private final MailGateway mailGateway;
    private final Object orderLock = new Object();

    /**
     * Local demos confirm a shop transfer from the browser. Production sets
     * SHOP_ALLOW_SELF_PAY=false so only an administrator can confirm payment;
     * confirmation then credits the buyer's relay balance automatically.
     */
    @Value("${blog.shop.allow-self-pay:true}")
    private boolean allowSelfPay = true;

    public ShopService(
            ObjectMapper objectMapper,
            RelayService relayService,
            @Value("${blog.shop.catalog-path:${user.dir}/shop/catalog.json}") String catalogPath,
            @Value("${blog.shop.data-path:${user.dir}/data/shop-orders.json}") String dataPath) {
        this(objectMapper, relayService, catalogPath, dataPath, null);
    }

    @Autowired
    public ShopService(
            ObjectMapper objectMapper,
            RelayService relayService,
            @Value("${blog.shop.catalog-path:${user.dir}/shop/catalog.json}") String catalogPath,
            @Value("${blog.shop.data-path:${user.dir}/data/shop-orders.json}") String dataPath,
            MailGateway mailGateway) {
        this.objectMapper = objectMapper;
        this.relayService = relayService;
        this.catalogPath = Paths.get(catalogPath).toAbsolutePath().normalize();
        this.ordersPath = Paths.get(dataPath).toAbsolutePath().normalize();
        this.mailGateway = mailGateway;
    }

    /** Test hook for the production self-pay switch. */
    void setAllowSelfPay(boolean allowSelfPay) {
        this.allowSelfPay = allowSelfPay;
    }

    // ------------------------------------------------------------------
    // Catalog
    // ------------------------------------------------------------------

    /**
     * Reads the catalog file on every call so operators can edit products
     * without restarting the service.
     */
    public JsonNode getCatalog() {
        try {
            if (!Files.isRegularFile(catalogPath)) {
                throw new IOException("商品目录文件不存在: " + catalogPath);
            }
            String content = Files.readString(catalogPath, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new IOException("商品目录不是合法的 JSON 对象");
            }
            if (!root.path("products").isArray()) {
                throw new IOException("目录结构缺少 products 数组");
            }
            JsonNode catalog = attachAvailability(root);
            if (catalog.isObject()) {
                // The storefront needs to know whether it may confirm a
                // transfer itself or must wait for an administrator.
                ((ObjectNode) catalog).put("allowSelfPay", allowSelfPay);
            }
            return catalog;
        } catch (ShopException e) {
            // A corrupt orders file is reported as such, not as a catalog error.
            throw e;
        } catch (Exception e) {
            log.error("Failed to load shop catalog from {}", catalogPath, e);
            throw ShopException.internal(CATALOG_ERROR_PREFIX + ": " + e.getMessage());
        }
    }

    /**
     * Adds live availability to each catalog product so the storefront never
     * offers a unit that outstanding orders have already consumed.
     */
    private JsonNode attachAvailability(JsonNode catalog) {
        Map<String, Integer> sold = soldQuantityByProduct(readOrders());
        JsonNode copy = catalog.deepCopy();
        for (JsonNode product : copy.path("products")) {
            if (!product.isObject()) {
                continue;
            }
            int stock = product.path("stock").asInt(0);
            int used = sold.getOrDefault(product.path("id").asText(""), 0);
            ((ObjectNode) product).put("availableStock", Math.max(0, stock - used));
        }
        return copy;
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    /**
     * Creates an order and returns the one-time lookup credential alongside the
     * persisted order fields. The credential is never stored in plaintext.
     */
    public Map<String, Object> createOrderResponse(ShopCreateOrderRequest request) {
        CreatedOrder created = createOrderInternal(request);
        boolean emailQueued = notifyOrderCreated(created);
        Map<String, Object> body = objectMapper.convertValue(
                publicView(created.order()), new TypeReference<Map<String, Object>>() {});
        body.put("lookupToken", created.lookupToken());
        body.put("emailQueued", emailQueued);
        return body;
    }

    public ShopOrder createOrder(ShopCreateOrderRequest request) {
        return createOrderInternal(request).order();
    }

    private CreatedOrder createOrderInternal(ShopCreateOrderRequest request) {
        String productId = request == null || request.getProductId() == null
                ? ""
                : request.getProductId().trim();
        if (productId.isEmpty()) {
            throw ShopException.badRequest("商品不存在");
        }

        int quantity = parseQuantity(request == null ? null : request.getQuantity());

        String contact = request == null || request.getContact() == null
                ? ""
                : request.getContact().trim();
        if (contact.length() < 5 || contact.length() > 100) {
            throw ShopException.badRequest("请填写有效的联系方式");
        }

        if (request != null && request.isNoteInvalid()) {
            throw ShopException.badRequest("备注不合法");
        }
        String note = request == null || request.getNote() == null
                ? ""
                : request.getNote().trim();
        if (note.length() > 500) {
            throw ShopException.badRequest("备注过长");
        }

        JsonNode catalog = getCatalog();
        JsonNode product = findProduct(catalog, productId);
        if (product == null) {
            throw ShopException.badRequest("商品不存在");
        }

        String productName = product.path("name").asText("");
        BigDecimal unitPrice = extractUnitPrice(product);
        int stock = product.path("stock").asInt(0);

        // Products that mirror a relay plan must be attributed to an existing
        // relay account. The email is the fulfillment target, not a free-form
        // contact field.
        String relayPlanId = text(product.get("relayPlanId")).trim();
        String relayUserId = null;
        int relayCreditCents = 0;
        if (!relayPlanId.isEmpty()) {
            if (relayService == null) {
                throw ShopException.internal("商城未配置中转站服务");
            }
            contact = contact.toLowerCase(Locale.ROOT);
            if (!isEmail(contact)) {
                throw ShopException.badRequest("请填写中转站注册邮箱");
            }
            relayUserId = relayService.relayUserId(contact);
            if (relayUserId == null) {
                throw ShopException.badRequest("该邮箱尚未注册中转站，请先注册");
            }
            try {
                relayCreditCents = relayService.shopCreditCents(relayPlanId, quantity);
            } catch (RelayException e) {
                throw new ShopException(e.getStatus(), e.getMessage());
            }
        }

        String lookupToken = LOOKUP_TOKEN_PREFIX + randomHex(LOOKUP_TOKEN_BYTES);
        String lookupTokenHash = sha256Hex(lookupToken);

        synchronized (orderLock) {
            List<ShopOrder> orders = readOrders();
            int reserved = countReservedQuantity(orders, productId);
            int available = stock - reserved;
            if (quantity > available) {
                throw ShopException.conflict("库存不足，剩余 " + available + " 件");
            }

            ShopOrder order = ShopOrder.builder()
                    .orderNo(generateOrderNo(orders))
                    .productId(productId)
                    .productName(productName)
                    .unitPrice(unitPrice)
                    .quantity(quantity)
                    .total(unitPrice.multiply(BigDecimal.valueOf(quantity))
                            .setScale(2, RoundingMode.HALF_UP))
                    .contact(contact)
                    .note(note)
                    .status("pending")
                    .createdAt(nowIso())
                    .relayPlanId(relayPlanId.isEmpty() ? null : relayPlanId)
                    .relayCreditCents(relayPlanId.isEmpty() ? null : relayCreditCents)
                    .relayUserId(relayUserId)
                    .lookupTokenHash(lookupTokenHash)
                    .build();

            orders.add(order);
            writeOrders(orders);
            return new CreatedOrder(order, lookupToken);
        }
    }

    public ShopOrder getOrder(String orderNo) {
        synchronized (orderLock) {
            ShopOrder order = findOrder(readOrders(), orderNo);
            if (order == null) {
                throw ShopException.notFound("订单不存在");
            }
            return order;
        }
    }

    /**
     * Returns an order to a buyer who proves access with the one-time lookup
     * token or with the relay account the order was created for. Missing and
     * unauthorized orders share the same 404 so the endpoint cannot be used to
     * enumerate order numbers or email addresses.
     */
    public ShopOrder getOrderForBuyer(String orderNo, String authorization, String orderToken) {
        ShopOrder order = getOrder(orderNo);
        if (!canAccess(order, authorization, orderToken)) {
            throw ShopException.notFound("订单不存在");
        }
        return publicView(order);
    }

    public ShopOrder payOrder(String orderNo, String method) {
        if (!allowSelfPay) {
            throw ShopException.forbidden("请支付后联系管理员确认到账");
        }
        synchronized (orderLock) {
            List<ShopOrder> orders = readOrders();
            ShopOrder order = findOrder(orders, orderNo);
            if (order == null) {
                throw ShopException.notFound("订单不存在");
            }
            return payOrderLocked(orders, order, method);
        }
    }

    /** Buyer-facing payment confirmation; requires the same proof as lookup. */
    public ShopOrder payOrderForBuyer(String orderNo, String method,
                                      String authorization, String orderToken) {
        if (!allowSelfPay) {
            throw ShopException.forbidden("请支付后联系管理员确认到账");
        }
        synchronized (orderLock) {
            List<ShopOrder> orders = readOrders();
            ShopOrder order = findOrder(orders, orderNo);
            if (order == null || !canAccess(order, authorization, orderToken)) {
                throw ShopException.notFound("订单不存在");
            }
            return publicView(payOrderLocked(orders, order, method));
        }
    }

    private ShopOrder payOrderLocked(List<ShopOrder> orders, ShopOrder order, String method) {
        String status = order.getStatus();
        if ("paid".equals(status) || "delivered".equals(status)) {
            if (ensureRelayCredit(order)) {
                writeOrders(orders);
                notifyOrderPaid(order);
            }
            return order;
        }
        if ("cancelled".equals(status)) {
            throw ShopException.conflict("订单已取消");
        }

        order.setStatus("paid");
        order.setPaidAt(nowIso());
        String normalizedMethod = method == null ? "" : method.trim();
        if (VALID_PAYMENT_METHODS.contains(normalizedMethod)) {
            order.setPaymentMethod(normalizedMethod);
        }
        boolean credited = ensureRelayCredit(order);
        writeOrders(orders);
        if (credited) {
            notifyOrderPaid(order);
        }
        return order;
    }

    public ShopOrderListResponse listOrders(String status, int limit) {
        String filter = status == null ? "" : status.trim();
        int safeLimit = limit <= 0 ? DEFAULT_ORDER_LIMIT : Math.min(limit, MAX_ORDER_LIMIT);

        synchronized (orderLock) {
            List<ShopOrder> orders = readOrders();
            List<ShopOrder> matching = new ArrayList<>();
            for (ShopOrder order : orders) {
                if (filter.isEmpty() || filter.equals(order.getStatus())) {
                    matching.add(order);
                }
            }
            matching.sort(Comparator.comparing(
                    ShopOrder::getCreatedAt,
                    Comparator.nullsLast(Comparator.<String>reverseOrder())));

            List<ShopOrder> list = matching.size() > safeLimit
                    ? new ArrayList<>(matching.subList(0, safeLimit))
                    : matching;

            return ShopOrderListResponse.builder()
                    .list(list.stream().map(this::publicView).toList())
                    .total(matching.size())
                    .build();
        }
    }

    /**
     * Admin list overload accepting the raw query value. Invalid values fall
     * back to the default limit, matching the reference backend's parsing.
     */
    public ShopOrderListResponse listOrders(String status, String rawLimit) {
        return listOrders(status, parseLimit(rawLimit));
    }

    private static int parseLimit(String rawLimit) {
        if (rawLimit == null) {
            return DEFAULT_ORDER_LIMIT;
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
            return DEFAULT_ORDER_LIMIT;
        }
        try {
            double parsed = Double.parseDouble(value.substring(0, end));
            return parsed > 0 ? (int) Math.min(parsed, MAX_ORDER_LIMIT) : DEFAULT_ORDER_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_ORDER_LIMIT;
        }
    }

    public ShopOrder updateOrderStatus(String orderNo, String status) {
        String nextStatus = status == null ? "" : status.trim();
        if (!VALID_STATUSES.contains(nextStatus)) {
            throw ShopException.badRequest("订单状态不合法");
        }

        synchronized (orderLock) {
            List<ShopOrder> orders = readOrders();
            ShopOrder order = findOrder(orders, orderNo);
            if (order == null) {
                throw ShopException.notFound("订单不存在");
            }

            if ("cancelled".equals(nextStatus) && order.getRelayCreditedAt() != null) {
                throw ShopException.conflict("已到账订单不能取消，请联系管理员处理退款");
            }

            order.setStatus(nextStatus);
            if (("paid".equals(nextStatus) || "delivered".equals(nextStatus))
                    && order.getPaidAt() == null) {
                order.setPaidAt(nowIso());
            }
            if ("delivered".equals(nextStatus)) {
                order.setDeliveredAt(nowIso());
            } else {
                order.setDeliveredAt(null);
            }
            if ("cancelled".equals(nextStatus)) {
                order.setCancelledAt(nowIso());
            } else {
                order.setCancelledAt(null);
            }
            boolean credited = false;
            if ("paid".equals(nextStatus) || "delivered".equals(nextStatus)) {
                credited = ensureRelayCredit(order);
            }
            writeOrders(orders);
            if (credited) {
                notifyOrderPaid(order);
            }
            return publicView(order);
        }
    }

    /**
     * Credits the relay account behind a shop order exactly once. The relay
     * side uses a deterministic SHOP-&lt;orderNo&gt; top-up, so retries after a
     * partial failure cannot double-credit.
     */
    private boolean ensureRelayCredit(ShopOrder order) {
        if (order == null || order.getRelayPlanId() == null || order.getRelayPlanId().isBlank()
                || order.getRelayCreditedAt() != null) {
            return false;
        }
        if (relayService == null) {
            throw ShopException.internal("商城未配置中转站服务");
        }
        if (order.getTotal() == null) {
            throw ShopException.internal("订单金额不合法");
        }

        int amountCents;
        try {
            amountCents = order.getTotal()
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValueExact();
        } catch (RuntimeException e) {
            throw ShopException.internal("订单金额不合法");
        }

        RelayTopup topup;
        try {
            topup = relayService.creditShopOrder(
                    order.getContact(),
                    order.getRelayPlanId(),
                    order.getQuantity(),
                    amountCents,
                    order.getOrderNo(),
                    order.getPaymentMethod());
        } catch (RelayException e) {
            throw new ShopException(e.getStatus(), e.getMessage());
        }

        order.setRelayCreditedAt(nowIso());
        order.setRelayTopupOrderNo(topup.getOrderNo());
        // A catalog edit between checkout and payment must not make the stored
        // snapshot (shown on the order page and in the email) disagree with the
        // amount that was actually credited.
        order.setRelayCreditCents(topup.getCreditCents());
        return true;
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private int parseQuantity(JsonNode node) {
        if (node == null || node.isNull()) {
            throw ShopException.badRequest("购买数量不合法");
        }

        if (node.isIntegralNumber()) {
            if (node.canConvertToInt()) {
                int value = node.intValue();
                if (value >= 1 && value <= 99) {
                    return value;
                }
            }
            throw ShopException.badRequest("购买数量不合法");
        }

        if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            if (!Double.isNaN(value) && !Double.isInfinite(value)
                    && value == Math.rint(value)
                    && value >= 1 && value <= 99) {
                return (int) value;
            }
            throw ShopException.badRequest("购买数量不合法");
        }

        throw ShopException.badRequest("购买数量不合法");
    }

    private JsonNode findProduct(JsonNode catalog, String productId) {
        JsonNode products = catalog.path("products");
        if (!products.isArray()) {
            return null;
        }
        for (JsonNode product : products) {
            if (productId.equals(product.path("id").asText(null))) {
                return product;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static boolean isEmail(String value) {
        return value != null && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    /**
     * A buyer may prove access either with the order credential issued at
     * checkout or with the relay login that owns the fulfillment email.
     */
    private boolean canAccess(ShopOrder order, String authorization, String orderToken) {
        if (order == null) {
            return false;
        }
        String storedHash = order.getLookupTokenHash();
        if (storedHash != null && !storedHash.isBlank()
                && orderToken != null && !orderToken.isBlank()
                && constantTimeEquals(storedHash, sha256Hex(orderToken.trim()))) {
            return true;
        }
        // A wrong or stale X-Order-Token must not hide a valid relay session.
        if (order.getRelayUserId() != null && !order.getRelayUserId().isBlank()
                && authorization != null && !authorization.isBlank()
                && relayService != null) {
            try {
                Map<String, Object> me = relayService.me(authorization);
                Object user = me.get("user");
                if (user instanceof Map<?, ?> map) {
                    return order.getRelayUserId().equals(map.get("id"));
                }
            } catch (RelayException ignored) {
                // Invalid relay sessions simply fail the lookup.
            }
        }
        return false;
    }

    /** Queues the order/token email; failures never fail checkout. */
    private boolean notifyOrderCreated(CreatedOrder created) {
        if (created == null || created.order() == null) {
            return false;
        }
        ShopOrder order = created.order();
        if (mailGateway == null || !mailGateway.enabled() || !isEmail(order.getContact())) {
            return false;
        }
        String total = order.getTotal() == null ? "" : order.getTotal().toPlainString();
        String text = """
                你好，

                你的订单已创建。

                订单号：%s
                商品：%s × %d
                金额：¥%s
                查询凭证：%s

                请妥善保存查询凭证。也可以登录下单邮箱对应的中转站账号，在 %s/shop/orders 查询订单。
                """.formatted(
                order.getOrderNo(),
                order.getProductName() == null ? "" : order.getProductName(),
                order.getQuantity(),
                total,
                created.lookupToken(),
                mailGateway.siteBaseUrl());
        return notifySafely(order.getContact(),
                "订单已创建 · " + order.getOrderNo(), text);
    }

    /** Queues the "credit arrived" email exactly when a credit is applied. */
    private void notifyOrderPaid(ShopOrder order) {
        if (order == null || mailGateway == null || !mailGateway.enabled()
                || !isEmail(order.getContact()) || order.getRelayCreditCents() == null) {
            return;
        }
        String credit = java.math.BigDecimal.valueOf(order.getRelayCreditCents())
                .movePointLeft(2)
                .toPlainString();
        String text = """
                你好，

                订单 %s 已确认收款，¥%s 额度已充入 %s 的中转站账户。

                可登录 %s/relay/console 查看余额和充值记录。
                """.formatted(
                order.getOrderNo(),
                credit,
                order.getContact(),
                mailGateway.siteBaseUrl());
        notifySafely(order.getContact(), "额度已到账 · " + order.getOrderNo(), text);
    }

    /** A mail failure must never turn a persisted order into an HTTP error. */
    private boolean notifySafely(String to, String subject, String text) {
        try {
            return mailGateway.send(to, subject, text);
        } catch (RuntimeException e) {
            log.warn("Mail notification '{}' failed: {}", subject, e.getMessage());
            return false;
        }
    }

    /** Strips internal fields by building an allowlist view, not a blacklist. */
    private ShopOrder publicView(ShopOrder order) {
        if (order == null) {
            return null;
        }
        return ShopOrder.builder()
                .orderNo(order.getOrderNo())
                .productId(order.getProductId())
                .productName(order.getProductName())
                .unitPrice(order.getUnitPrice())
                .quantity(order.getQuantity())
                .total(order.getTotal())
                .contact(order.getContact())
                .note(order.getNote())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .paymentMethod(order.getPaymentMethod())
                .deliveredAt(order.getDeliveredAt())
                .cancelledAt(order.getCancelledAt())
                .relayPlanId(order.getRelayPlanId())
                .relayCreditCents(order.getRelayCreditCents())
                .relayCreditedAt(order.getRelayCreditedAt())
                .relayTopupOrderNo(order.getRelayTopupOrderNo())
                .build();
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        LOOKUP_TOKEN_RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal extractUnitPrice(JsonNode product) {
        JsonNode priceNode = product.get("price");
        if (priceNode == null || !priceNode.isNumber()) {
            throw ShopException.internal(CATALOG_ERROR_PREFIX + ": 商品价格不合法");
        }
        BigDecimal price = priceNode.decimalValue();
        if (price.signum() < 0) {
            throw ShopException.internal(CATALOG_ERROR_PREFIX + ": 商品价格不合法");
        }
        return price;
    }

    private int countReservedQuantity(List<ShopOrder> orders, String productId) {
        return soldQuantityByProduct(orders).getOrDefault(productId, 0);
    }

    /** Units consumed per product across all orders that still hold stock. */
    private Map<String, Integer> soldQuantityByProduct(List<ShopOrder> orders) {
        Map<String, Integer> sold = new LinkedHashMap<>();
        for (ShopOrder order : orders) {
            if (order.getProductId() == null
                    || order.getStatus() == null
                    || !SOLD_STATUSES.contains(order.getStatus())) {
                continue;
            }
            sold.merge(order.getProductId(), order.getQuantity(), Integer::sum);
        }
        return sold;
    }

    private ShopOrder findOrder(List<ShopOrder> orders, String orderNo) {
        if (orderNo == null) {
            return null;
        }
        String key = orderNo.trim();
        if (key.isEmpty()) {
            return null;
        }
        for (ShopOrder order : orders) {
            if (order.getOrderNo() != null && order.getOrderNo().equalsIgnoreCase(key)) {
                return order;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Order number
    // ------------------------------------------------------------------

    private String generateOrderNo(List<ShopOrder> orders) {
        Set<String> used = new LinkedHashSet<>();
        for (ShopOrder order : orders) {
            if (order.getOrderNo() != null) {
                used.add(order.getOrderNo().toUpperCase(Locale.ROOT));
            }
        }

        for (int attempt = 0; attempt < ORDER_NO_RETRY_LIMIT; attempt++) {
            String candidate = ORDER_NO_PREFIX
                    + LocalDateTime.now().format(ORDER_NO_TIME_FORMATTER)
                    + randomSuffix();
            if (!used.contains(candidate.toUpperCase(Locale.ROOT))) {
                return candidate;
            }
        }
        throw ShopException.internal("订单号生成失败，请重试");
    }

    private String randomSuffix() {
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
            // Allow the service to boot when the build-time placeholder has not
            // been substituted; the layout matches the documented order number.
            log.warn("Invalid order-number time pattern '{}', using default", ORDER_NO_TIME_PATTERN);
            return DateTimeFormatter.ofPattern("yyyyMMddHHmm");
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private List<ShopOrder> readOrders() {
        if (!Files.exists(ordersPath)) {
            return new ArrayList<>();
        }

        try {
            if (!Files.isRegularFile(ordersPath)) {
                throw new IOException("订单数据路径不是普通文件: " + ordersPath);
            }
            String content = Files.readString(ordersPath, StandardCharsets.UTF_8);
            OrdersFile file = objectMapper.readValue(content, OrdersFile.class);
            if (file == null || file.getOrders() == null) {
                throw new IOException("订单数据缺少 orders 数组");
            }

            List<ShopOrder> orders = new ArrayList<>(file.getOrders());
            for (ShopOrder order : orders) {
                if (order == null || order.getOrderNo() == null || order.getOrderNo().isBlank()) {
                    throw new IOException("订单记录缺少 orderNo");
                }
                if (order.getNote() == null) {
                    order.setNote("");
                }
            }
            return orders;
        } catch (Exception e) {
            log.error("Failed to read shop orders from {}", ordersPath, e);
            throw ShopException.internal(ORDERS_READ_ERROR_PREFIX);
        }
    }

    private void writeOrders(List<ShopOrder> orders) {
        Path parent = ordersPath.getParent();
        Path tempFile = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("orders", orders);

            tempFile = Files.createTempFile(parent, "shop-orders-", ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), root);

            try {
                Files.move(tempFile, ordersPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, ordersPath, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;
        } catch (IOException e) {
            log.error("Failed to write shop orders to {}", ordersPath, e);
            throw ShopException.internal("订单数据保存失败: " + e.getMessage());
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

    private static String nowIso() {
        return ISO_UTC_MILLIS.format(Instant.now());
    }

    /**
     * File shape: {"orders": [...]}. Deserializing the whole file (instead of
     * a generic JSON tree) keeps BigDecimal prices and totals at their stored
     * scale.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OrdersFile {
        private List<ShopOrder> orders;
    }

    private record CreatedOrder(ShopOrder order, String lookupToken) {
    }
}
