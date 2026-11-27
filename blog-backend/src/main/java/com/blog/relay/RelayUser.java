package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted relay account. Password salt/hash are only ever written to the
 * store file and are never exposed through the API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayUser {

    private String id;
    private String email;
    private String passwordSalt;
    private String passwordHash;
    private int balanceCents;
    private boolean disabled;
    private String createdAt;
}
