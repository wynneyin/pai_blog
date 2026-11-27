package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One billed upstream request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayUsage {

    private String id;
    private String userId;
    private String keyId;
    private String model;
    private long promptTokens;
    private long completionTokens;
    private int costCents;
    private String createdAt;
}
