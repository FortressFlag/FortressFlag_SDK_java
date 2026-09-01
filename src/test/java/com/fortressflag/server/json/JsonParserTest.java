package com.fortressflag.server.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** The bounded parser's own corpus — the vectors are its primary test data (CLAUDE.md §3). */
final class JsonParserTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String name) {
        try (var stream = JsonParserTest.class.getResourceAsStream(
                "/com/fortressflag/server/vectors/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    void bothVectorFilesParseToNonEmptyDocuments() {
        for (String name : new String[] {"evaluation.json", "buckets.json"}) {
            JsonValue parsed = JsonParser.parse(resource(name));
            var object = assertInstanceOf(JsonValue.JsonObject.class, parsed);
            var vectors = assertInstanceOf(
                    JsonValue.JsonArray.class, object.members().get("vectors"));
            assertTrue(vectors.items().size() > 0, name + " has vectors");
        }
    }

    @TestFactory
    Stream<DynamicTest> aValidPayloadTruncatedAtEveryByteRejectsAndNeverCrashesTheHarness() {
        byte[] valid = bytes(
                "{\"sv\":1,\"flags\":{\"dark-mode\":{\"kind\":\"boolean\",\"default\":false,"
                        + "\"rules\":[{\"conditions\":[],\"serve\":true,\"rolloutPercentage\":50}]}}}");
        // Truncation at every byte — a loop, not hand-picked cases. Length 0 and the full
        // length are excluded: empty is its own test, and the full payload must PARSE.
        JsonParser.parse(valid);
        return java.util.stream.IntStream.range(1, valid.length)
                .mapToObj(length -> DynamicTest.dynamicTest("truncated at " + length, () -> {
                    byte[] truncated = java.util.Arrays.copyOf(valid, length);
                    assertThrows(JsonParseException.class, () -> JsonParser.parse(truncated));
                }));
    }

    @Test
    void depthSixtyFourParsesAndSixtyFiveRejects() {
        String at = "[".repeat(64) + "]".repeat(64);
        JsonParser.parse(bytes(at));
        String over = "[".repeat(65) + "]".repeat(65);
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes(over)));
    }

    @Test
    void duplicateKeysTakeTheLastOccurrence() {
        var parsed = (JsonValue.JsonObject) JsonParser.parse(bytes("{\"a\":1,\"a\":2}"));
        assertEquals(new JsonValue.JsonNumber(2), parsed.members().get("a"));
    }

    @Test
    void nonJsonNumbersAreRejectedHoweverMuchParseDoubleLikesThem() {
        for (String hostile : new String[] {
            "NaN", "Infinity", "-Infinity", "0x1p3", "+1", "1.", ".5", "01", "1d", "1f", "1e"
        }) {
            assertThrows(
                    JsonParseException.class,
                    () -> JsonParser.parse(bytes(hostile)),
                    hostile);
        }
    }

    @Test
    void overflowingExponentsAreRejectedNotInfinity() {
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes("1e999")));
    }

    @Test
    void strictJsonNumbersParse() {
        assertEquals(new JsonValue.JsonNumber(0), JsonParser.parse(bytes("0")));
        assertEquals(new JsonValue.JsonNumber(-1.5e3), JsonParser.parse(bytes("-1.5e3")));
        assertEquals(new JsonValue.JsonNumber(50), JsonParser.parse(bytes("50")));
    }

    @Test
    void unicodeEscapesIncludingSurrogatePairsDecode() {
        var pair = (JsonValue.JsonString) JsonParser.parse(bytes("\"\\ud83d\\ude00\""));
        assertEquals("\ud83d\ude00", pair.value());
        // A lone surrogate stays a lone surrogate — exactly what JSON.parse and json.loads
        // do; re-validating here would diverge from every sibling decoder.
        var lone = (JsonValue.JsonString) JsonParser.parse(bytes("\"\\ud83d\""));
        assertEquals(1, lone.value().length());
    }

    @Test
    void malformedUtf8IsReportedNeverReplaced() {
        assertThrows(
                JsonParseException.class,
                () -> JsonParser.parse(new byte[] {'"', (byte) 0xC3, '"'}));
    }

    @Test
    void trailingGarbageAndControlCharactersReject() {
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes("{} extra")));
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes("\"a\nb\"")));
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes("")));
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes("{\"a\":}")));
        assertThrows(JsonParseException.class, () -> JsonParser.parse(bytes("[1,]")));
    }

    @Test
    void theParseExceptionMessageCarriesNoInputBytes() {
        var exception = assertThrows(
                JsonParseException.class,
                () -> JsonParser.parse(bytes("{\"secret-context-key\": nope}")));
        assertTrue(!exception.getMessage().contains("secret-context-key"));
    }
}
