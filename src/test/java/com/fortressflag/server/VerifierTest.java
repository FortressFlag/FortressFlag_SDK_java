package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fortressflag.server.Verifier.Expectations;
import com.fortressflag.server.Verifier.RejectionCode;
import com.fortressflag.server.Verifier.Result;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class VerifierTest {

    private static final Expectations LIVE = new Expectations("dev", Fixtures.NOW, true);
    private static final Expectations CACHE_LOAD = new Expectations("dev", Fixtures.NOW, false);

    private static Result verify(byte[] raw, Expectations expect) {
        return Verifier.verify(raw, SignaturePolicy.disabled(), expect);
    }

    @Test
    void aGoodLiveEnvelopeVerifiesAndRetainsItsRawBytes() {
        byte[] raw = Fixtures.envelope(Fixtures.payload());
        Result result = verify(raw, LIVE);
        assertNull(result.code());
        assertEquals(raw, result.envelope().raw());
        assertEquals(2, result.envelope().payload().flags().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "<html>portal</html>", "{\"payload\":\"\"}"})
    void bodiesThatAreNotEnvelopesReject(String raw) {
        Result result = verify(raw.getBytes(StandardCharsets.UTF_8), LIVE);
        assertEquals(RejectionCode.MALFORMED_ENVELOPE, result.code());
    }

    @Test
    void aPaddedBase64UrlPayloadIsRefused() {
        // The wire is UNPADDED; '=' anywhere in the payload string must reject, however
        // happily a lenient decoder would strip it.
        String unpadded = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Fixtures.payloadJson(Fixtures.payload()).getBytes(StandardCharsets.UTF_8));
        byte[] raw = ("{\"payload\":\"" + unpadded + "==\"}").getBytes(StandardCharsets.UTF_8);
        assertEquals(RejectionCode.MALFORMED_ENVELOPE, verify(raw, LIVE).code());
    }

    @Test
    void svOtherThanOneRejectsWhole() {
        Map<String, Object> payload = Fixtures.payload();
        payload.put("sv", 99);
        assertEquals(RejectionCode.UNSUPPORTED_CONTRACT_VERSION, verify(Fixtures.envelope(payload), LIVE).code());
    }

    @Test
    void environmentMismatchRejects() {
        Map<String, Object> payload = Fixtures.payload();
        payload.put("environment", "prod");
        assertEquals(RejectionCode.ENVIRONMENT_MISMATCH, verify(Fixtures.envelope(payload), LIVE).code());
    }

    @Test
    void anUnknownFlagKindRejectsTheWholePayload() {
        Map<String, Object> payload = Fixtures.payload();
        payload.put("flags", "{\"ok\":{\"kind\":\"boolean\",\"default\":false,\"rules\":[]},\"bad\":{\"kind\":\"datetime\",\"rules\":[]}}");
        assertEquals(RejectionCode.MALFORMED_PAYLOAD, verify(Fixtures.envelope(payload), LIVE).code());
    }

    @Test
    void theExpiryAsymmetry() {
        Map<String, Object> payload = Fixtures.payload();
        payload.put("issuedAt", "2026-08-21T08:00:00Z");
        payload.put("expiresAt", "2026-08-21T08:30:00Z");
        byte[] stale = Fixtures.envelope(payload);
        assertEquals(RejectionCode.EXPIRED, verify(stale, LIVE).code());
        assertNull(verify(stale, CACHE_LOAD).code()); // freshness, not validity
    }

    @Test
    void issuedInTheFutureBeyondSkewRejectsWithinSkewVerifies() {
        Map<String, Object> far = Fixtures.payload();
        far.put("issuedAt", "2026-08-21T12:00:00Z");
        assertEquals(RejectionCode.ISSUED_IN_THE_FUTURE, verify(Fixtures.envelope(far), LIVE).code());
        Map<String, Object> near = Fixtures.payload();
        near.put("issuedAt", "2026-08-21T10:18:00Z");
        assertNull(verify(Fixtures.envelope(near), LIVE).code());
    }

    @Test
    void nonRfc3339TimestampsReject() {
        Map<String, Object> payload = Fixtures.payload();
        payload.put("issuedAt", "Aug 21 2026");
        assertEquals(RejectionCode.MALFORMED_PAYLOAD, verify(Fixtures.envelope(payload), LIVE).code());
    }

    @ParameterizedTest
    @CsvSource({
        "'', MISSING_SIGNATURE",
        "garbage, MALFORMED_SIGNATURE",
        "ed25519:AAAA, MALFORMED_SIGNATURE",
        "p256:k1:AAAA, UNSUPPORTED_SIGNATURE_ALGORITHM",
        "ed25519:unknown:AAAA, UNKNOWN_KEY_ID",
        // Well-formed, known key — still rejected: no primitive exists to accept it.
        "ed25519:k1:AAAA, BAD_SIGNATURE",
    })
    void theFailClosedStub(String sig, RejectionCode expected) {
        SignaturePolicy policy = SignaturePolicy.required(Map.of("k1", new byte[32]));
        Result result = Verifier.verify(Fixtures.envelope(Fixtures.payload(), sig), policy, LIVE);
        assertEquals(expected, result.code());
    }

    @Test
    void anAbsentSignatureUnderARequiredPolicyIsMissing() {
        SignaturePolicy policy = SignaturePolicy.required(Map.of("k1", new byte[32]));
        Result result = Verifier.verify(Fixtures.envelope(Fixtures.payload()), policy, LIVE);
        assertEquals(RejectionCode.MISSING_SIGNATURE, result.code());
    }
}
