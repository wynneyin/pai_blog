package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login session. Only the SHA-256 hash of the session token is stored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelaySession {

    private String tokenHash;
    private String userId;
    private String createdAt;
    private String expiresAt;
}
