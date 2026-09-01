package com.fortressflag.server;

import com.fortressflag.server.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * The ruleset payload types, in the server export's wire shape (server-contract-v1.md) — a
 * PORT of the backend's clientapi rule types via the Go SDK, never a share (the
 * port-don't-share doctrine). Package-private: the wire shape is not public API.
 *
 * <p>The value union: {@code serve}/{@code defaultValue} hold a {@link JsonValue} where
 * {@code null} means THE FIELD WAS ABSENT and {@link JsonValue.JsonNull} means an explicit
 * JSON null — with {@code false} a third, distinct thing ({@link JsonValue.JsonBool}). A
 * {@code Map.get == null} check alone cannot tell absent from present-null (recorded trap
 * #3); the JsonValue tree makes the three-way structural.
 */
final class Ruleset {
    private Ruleset() {}

    record FlagCondition(String tagKey, String operator, String value) {}

    /**
     * rolloutPercentage is null for no gate; 0 is a REAL gate that matches nobody
     * (rollout-zero-matches-nobody is a published vector) — presence tests are
     * {@code != null}, never a falsy shortcut.
     */
    record FlagRule(List<FlagCondition> conditions, JsonValue serve, Integer rolloutPercentage) {
        FlagRule {
            conditions = List.copyOf(conditions);
        }
    }

    record FlagConfig(String kind, JsonValue defaultValue, List<FlagRule> rules) {
        FlagConfig {
            rules = List.copyOf(rules);
        }
    }

    /** A decoded value with its presence bit — the Go (any, bool) pair. */
    record Decoded(Object value, boolean ok) {
        static final Decoded NO_VALUE = new Decoded(null, false);
    }

    /**
     * Decodes a raw wire value by the flag's kind, reporting whether a value was present.
     * Absent (null) and explicit JSON null are treated identically as no-value; a value
     * that does not follow the kind, or a kind this build does not know, is no-value too:
     * fail closed into the caller's fallback rather than serve a guess (Founding §8.3).
     * The parser's number grammar already guarantees finiteness.
     */
    static Decoded decodeValue(JsonValue raw, String kind) {
        if (raw == null || raw instanceof JsonValue.JsonNull) {
            return Decoded.NO_VALUE;
        }
        return switch (kind) {
            case "boolean" ->
                    raw instanceof JsonValue.JsonBool b ? new Decoded(b.value(), true) : Decoded.NO_VALUE;
            case "string" ->
                    raw instanceof JsonValue.JsonString s ? new Decoded(s.value(), true) : Decoded.NO_VALUE;
            case "number" ->
                    raw instanceof JsonValue.JsonNumber n ? new Decoded(n.value(), true) : Decoded.NO_VALUE;
            default -> Decoded.NO_VALUE;
        };
    }

    /**
     * Builds a FlagConfig from a parsed JsonValue, or null when the shape is not a flag
     * config. Kind validation happens in the verifier (unknown kind rejects the WHOLE
     * payload); this only refuses shapes that cannot be walked at all.
     */
    static FlagConfig parseFlagConfig(JsonValue raw) {
        if (!(raw instanceof JsonValue.JsonObject object)) {
            return null;
        }
        Map<String, JsonValue> members = object.members();
        if (!(members.get("kind") instanceof JsonValue.JsonString kind)) {
            return null;
        }
        if (!(members.get("rules") instanceof JsonValue.JsonArray rulesRaw)) {
            return null;
        }
        List<FlagRule> rules = new ArrayList<>();
        for (JsonValue ruleRaw : rulesRaw.items()) {
            if (!(ruleRaw instanceof JsonValue.JsonObject ruleObject)) {
                return null;
            }
            Map<String, JsonValue> ruleMembers = ruleObject.members();
            if (!(ruleMembers.get("conditions") instanceof JsonValue.JsonArray conditionsRaw)) {
                return null;
            }
            List<FlagCondition> conditions = new ArrayList<>();
            for (JsonValue conditionRaw : conditionsRaw.items()) {
                if (!(conditionRaw instanceof JsonValue.JsonObject conditionObject)) {
                    return null;
                }
                Map<String, JsonValue> c = conditionObject.members();
                if (!(c.get("tagKey") instanceof JsonValue.JsonString tagKey)
                        || !(c.get("operator") instanceof JsonValue.JsonString operator)
                        || !(c.get("value") instanceof JsonValue.JsonString value)) {
                    return null;
                }
                conditions.add(new FlagCondition(tagKey.value(), operator.value(), value.value()));
            }
            Integer rollout = null;
            JsonValue rolloutRaw = ruleMembers.get("rolloutPercentage");
            if (rolloutRaw != null && !(rolloutRaw instanceof JsonValue.JsonNull)) {
                if (!(rolloutRaw instanceof JsonValue.JsonNumber number)
                        || number.value() != Math.floor(number.value())) {
                    return null;
                }
                rollout = (int) number.value();
            }
            rules.add(new FlagRule(conditions, ruleMembers.get("serve"), rollout));
        }
        return new FlagConfig(kind.value(), members.get("default"), rules);
    }

    /**
     * Parses the payload's flags map, or null on an unwalkable shape. Insertion order kept
     * for deterministic diagnostics; evaluation itself never iterates the map.
     */
    static Map<String, FlagConfig> parseFlags(JsonValue flagsRaw) {
        if (!(flagsRaw instanceof JsonValue.JsonObject object)) {
            return null;
        }
        Map<String, FlagConfig> flags = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.members().entrySet()) {
            FlagConfig config = parseFlagConfig(entry.getValue());
            if (config == null) {
                return null;
            }
            flags.put(entry.getKey(), config);
        }
        return flags;
    }
}
