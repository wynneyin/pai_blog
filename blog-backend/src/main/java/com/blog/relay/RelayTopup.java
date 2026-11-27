package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Top-up order. All amounts are integer cents; creditCents includes the bonus.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayTopup {

    private String orderNo;
    private String userId;
    private String planId;
    private String planName;
    private int amountCents;
    private int bonusCents;
    private int creditCents;
    private String status;
    private String method;
    private String createdAt;
    private String paidAt;
    private String cancelledAt;
}
