package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One outstanding password reset. Only the SHA-256 of the emailed token is
 * stored; the plaintext exists solely inside the reset link.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayPasswordReset {

    @ToString.Exclude
    private String tokenHash;
    private String userId;
    private String createdAt;
    private String expiresAt;
}
