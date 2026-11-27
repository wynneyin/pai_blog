package com.blog.shop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Persisted shop order. Times are ISO-8601 UTC strings so the JSON stored on
 * disk matches what the API returns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShopOrder {

    private String orderNo;
    private String productId;
    private String productName;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal total;
    private String contact;
    private String note;
    private String status;
    private String createdAt;
    private String paidAt;
    private String paymentMethod;
    private String deliveredAt;
    private String cancelledAt;

    /** Relay plan fulfilled by this order; null for a generic shop product. */
    private String relayPlanId;
    /** Credit added to the relay balance when the order is confirmed paid. */
    private Integer relayCreditCents;
    /** Set once the relay balance has been credited (idempotency marker). */
    private String relayCreditedAt;
    /** Relay top-up record created for this shop order. */
    private String relayTopupOrderNo;
    /** Relay account bound at checkout; lets that account query the order. */
    private String relayUserId;
    /** SHA-256 of the one-time lookup token. Never returned by the API. */
    @ToString.Exclude
    private String lookupTokenHash;
}
