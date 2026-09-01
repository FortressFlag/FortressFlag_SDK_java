package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fortressflag.server.json.JsonValue;
import org.junit.jupiter.api.Test;

/** The decode edges the vectors cannot reach — hostile shapes that must fail closed. */
final class RulesetTest {

    @Test
    void absentAndNullAreBothNoValueAndNeitherCollapsesToFalse() {
        assertFalse(Ruleset.decodeValue(null, "boolean").ok());
        assertFalse(Ruleset.decodeValue(JsonValue.JsonNull.INSTANCE, "boolean").ok());
    }

    @Test
    void anExplicitFalseIsAValue() {
        var decoded = Ruleset.decodeValue(new JsonValue.JsonBool(false), "boolean");
        assertEquals(false, decoded.value());
        assertEquals(true, decoded.ok());
    }

    @Test
    void kindMismatchIsNoValueNotACoercion() {
        assertFalse(Ruleset.decodeValue(new JsonValue.JsonString("true"), "boolean").ok());
        assertFalse(Ruleset.decodeValue(new JsonValue.JsonNumber(1), "boolean").ok());
        assertFalse(Ruleset.decodeValue(new JsonValue.JsonBool(true), "string").ok());
        assertFalse(Ruleset.decodeValue(new JsonValue.JsonString("5"), "number").ok());
    }

    @Test
    void anUnknownKindIsNoValue() {
        assertFalse(Ruleset.decodeValue(new JsonValue.JsonBool(true), "datetime").ok());
    }
}
