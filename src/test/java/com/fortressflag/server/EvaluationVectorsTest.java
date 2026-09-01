package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fortressflag.server.json.JsonParser;
import com.fortressflag.server.json.JsonValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The published evaluation vectors, run through THIS SDK's own parser and production
 * decoder — the whole point of publishing them in the export's wire shape. A failure here
 * is a wire-contract bug, never a test to fix (the vendored README).
 */
final class EvaluationVectorsTest {

    static byte[] resource(String name) {
        try (var stream = EvaluationVectorsTest.class.getResourceAsStream(
                "/com/fortressflag/server/vectors/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static JsonValue.JsonArray vectors(String name) {
        var file = (JsonValue.JsonObject) JsonParser.parse(resource(name));
        return (JsonValue.JsonArray) file.members().get("vectors");
    }

    private static String string(JsonValue value) {
        return ((JsonValue.JsonString) value).value();
    }

    @Test
    void theVectorFileIsNotEmpty() {
        assertTrue(vectors("evaluation.json").items().size() > 0);
    }

    @TestFactory
    Stream<DynamicTest> evaluationVectors() {
        return vectors("evaluation.json").items().stream().map(raw -> {
            var vector = (JsonValue.JsonObject) raw;
            String name = string(vector.members().get("name"));
            return DynamicTest.dynamicTest(name, () -> {
                Ruleset.FlagConfig config =
                        Ruleset.parseFlagConfig(vector.members().get("flag"));
                assertNotNull(config, "the flag config must parse through the production decoder");

                var input = (JsonValue.JsonObject) vector.members().get("input");
                Map<String, String> tags = new HashMap<>();
                var tagsRaw = (JsonValue.JsonObject) input.members().get("tags");
                for (Map.Entry<String, JsonValue> entry : tagsRaw.members().entrySet()) {
                    tags.put(entry.getKey(), string(entry.getValue()));
                }

                Ruleset.Decoded result = Evaluator.evaluate(
                        config,
                        tags,
                        string(input.members().get("contextKey")),
                        string(input.members().get("flagKey")));

                var expected = (JsonValue.JsonObject) vector.members().get("expected");
                if (expected.members().get("noValue") instanceof JsonValue.JsonBool noValue
                        && noValue.value()) {
                    assertFalse(result.ok(), "expected noValue");
                    return;
                }
                assertTrue(result.ok(), "expected a value");
                JsonValue expectedValue = expected.members().get("value");
                if (expectedValue instanceof JsonValue.JsonBool b) {
                    assertEquals(b.value(), result.value());
                } else if (expectedValue instanceof JsonValue.JsonString s) {
                    assertEquals(s.value(), result.value());
                } else if (expectedValue instanceof JsonValue.JsonNumber n) {
                    assertEquals(n.value(), result.value());
                } else {
                    throw new AssertionError("unexpected expected type");
                }
            });
        });
    }
}
