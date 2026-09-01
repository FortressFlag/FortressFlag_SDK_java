package com.fortressflag.server;

import java.util.Map;

/**
 * One evaluation's context: the opaque identifier the bucket hashes — a user id, a session
 * id, the customer's choice; never validated, never logged — plus targeting tags. The map
 * is defensively copied.
 */
public record Context(String key, Map<String, String> tags) {
    public Context {
        tags = Map.copyOf(tags);
    }

    public Context(String key) {
        this(key, Map.of());
    }
}
