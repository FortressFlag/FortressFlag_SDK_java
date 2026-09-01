package com.fortressflag.server.json;

import java.util.List;
import java.util.Map;

/**
 * A parsed JSON value — the minimal tree the SDK needs, nothing more (CLAUDE.md §3).
 *
 * <p>Exactly one of the sealed permits per JSON type. {@code JsonNull} is a real value,
 * distinct from a MISSING map key — the multivariate vectors distinguish absent, null and
 * {@code false}, and collapsing any two of them mis-serves flags.
 */
public sealed interface JsonValue
        permits JsonValue.JsonObject,
                JsonValue.JsonArray,
                JsonValue.JsonString,
                JsonValue.JsonNumber,
                JsonValue.JsonBool,
                JsonValue.JsonNull {

    /** Object member order is irrelevant to the contract; duplicate keys took the LAST occurrence. */
    record JsonObject(Map<String, JsonValue> members) implements JsonValue {
        public JsonObject {
            members = Map.copyOf(members);
        }
    }

    record JsonArray(List<JsonValue> items) implements JsonValue {
        public JsonArray {
            items = List.copyOf(items);
        }
    }

    record JsonString(String value) implements JsonValue {}

    /** Always finite: the parser's number grammar cannot produce NaN or an infinity. */
    record JsonNumber(double value) implements JsonValue {}

    record JsonBool(boolean value) implements JsonValue {}

    record JsonNull() implements JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();
    }
}
