package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fortressflag.server.json.JsonParser;
import com.fortressflag.server.json.JsonValue;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The published bucketing vectors. The JSON field is named deviceID because the backend
 * hashes device IDs; this SDK feeds its caller's context key through the same algorithm —
 * the rename happens here, keeping the vendored file verbatim. A failure here flips real
 * users between cohorts — and a signed modulo answers negative buckets for half of all
 * contexts, which THESE vectors catch on the first run (trap #4).
 */
final class BucketVectorsTest {

    private static JsonValue.JsonArray vectors() {
        var file = (JsonValue.JsonObject) JsonParser.parse(
                EvaluationVectorsTest.resource("buckets.json"));
        return (JsonValue.JsonArray) file.members().get("vectors");
    }

    @Test
    void theVectorFileIsNotEmpty() {
        assertTrue(vectors().items().size() > 0);
    }

    @TestFactory
    Stream<DynamicTest> bucketVectors() {
        return vectors().items().stream().map(raw -> {
            var vector = (JsonValue.JsonObject) raw;
            String contextId = ((JsonValue.JsonString) vector.members().get("deviceID")).value();
            String flagKey = ((JsonValue.JsonString) vector.members().get("flagKey")).value();
            int expected = (int) ((JsonValue.JsonNumber) vector.members().get("bucket")).value();
            return DynamicTest.dynamicTest(contextId + "/" + flagKey, () ->
                    assertEquals(expected, Bucket.of(contextId, flagKey)));
        });
    }
}
