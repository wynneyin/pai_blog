package com.blog.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Shape of data/relay-store.json. Every collection is always initialized so a
 * missing file behaves exactly like an empty store.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayStore {

    private List<RelayUser> users = new ArrayList<>();
    private List<RelaySession> sessions = new ArrayList<>();
    private List<RelayKey> keys = new ArrayList<>();
    private List<RelayTopup> topups = new ArrayList<>();
    private List<RelayUsage> usage = new ArrayList<>();
    private List<RelayPasswordReset> passwordResets = new ArrayList<>();

    public RelayStore(List<RelayUser> users, List<RelaySession> sessions, List<RelayKey> keys,
                      List<RelayTopup> topups, List<RelayUsage> usage) {
        this.users = users;
        this.sessions = sessions;
        this.keys = keys;
        this.topups = topups;
        this.usage = usage;
    }
}
