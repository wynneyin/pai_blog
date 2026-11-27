package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Relay API key. The full key is returned exactly once on creation; the store
 * keeps only its SHA-256 hash and the 16-character display prefix.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayKey {

    private String id;
    private String userId;
    private String name;
    private String prefix;
    private String keyHash;
    private String createdAt;
    private String lastUsedAt;
    private String revokedAt;
}
