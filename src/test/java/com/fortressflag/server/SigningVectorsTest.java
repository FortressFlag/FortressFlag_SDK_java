package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fortressflag.server.Verifier.Expectations;
import com.fortressflag.server.Verifier.RejectionCode;
import com.fortressflag.server.Verifier.Result;
import com.fortressflag.server.json.JsonParser;
import com.fortressflag.server.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The published signature vectors (backend ADR-0025), fed to the verifier as the exact
 * envelope bytes — never re-serialised — plus keys generated at test time. A failure on a
 * vector is a wire-contract bug, never a test to fix.
 */
final class SigningVectorsTest {

    /** The vectors' issuedAt; expiresAt is 2099 so the binding checks never age them out. */
    private static final Expectations LIVE =
            new Expectations("prod", Instant.parse("2026-09-16T00:00:00Z"), true);

    private static final JsonValue.JsonObject FILE =
            (JsonValue.JsonObject) JsonParser.parse(EvaluationVectorsTest.resource("signing.json"));

    private static String string(JsonValue value) {
        return ((JsonValue.JsonString) value).value();
    }

    private static SignaturePolicy vectorPolicy() {
        return SignaturePolicy.required(Map.of(
                string(FILE.members().get("keyId")),
                Envelope.decodeBase64Url(string(FILE.members().get("publicKey")))));
    }

    private static Stream<JsonValue.JsonObject> entries(String list) {
        return ((JsonValue.JsonArray) FILE.members().get(list)).items().stream()
                .map(JsonValue.JsonObject.class::cast);
    }

    private static byte[] envelopeBytes(JsonValue.JsonObject entry) {
        return Envelope.decodeBase64Url(string(entry.members().get("envelope")));
    }

    private static RejectionCode signatureCheck(byte[] raw, SignaturePolicy policy) {
        Envelope.Parsed parsed = Envelope.parseEnvelope(raw);
        return Verifier.checkSignature(parsed.envelope(), parsed.payloadBytes(), policy);
    }

    @Test
    void theVectorFileCarriesOnlyThePublicKey() {
        assertEquals(32, Envelope.decodeBase64Url(string(FILE.members().get("publicKey"))).length);
        String text = new String(EvaluationVectorsTest.resource("signing.json"), StandardCharsets.UTF_8);
        assertTrue(!text.contains("privateKey") && !text.contains("seed\""));
    }

    @TestFactory
    Stream<DynamicTest> acceptEntriesVerify() {
        return entries("accept").map(entry -> DynamicTest.dynamicTest(
                string(entry.members().get("name")),
                () -> assertNull(signatureCheck(envelopeBytes(entry), vectorPolicy()))));
    }

    @TestFactory
    Stream<DynamicTest> rejectEntriesFailWithTheNamedCode() {
        return entries("reject").map(entry -> DynamicTest.dynamicTest(
                string(entry.members().get("name")),
                () -> assertEquals(
                        string(entry.members().get("code")),
                        signatureCheck(envelopeBytes(entry), vectorPolicy()).wire)));
    }

    @Test
    void theServerVectorPassesTheWholeVerifier() {
        JsonValue.JsonObject server = entries("accept")
                .filter(e -> string(e.members().get("name")).equals("server-sv1"))
                .findFirst()
                .orElseThrow();
        Result result = Verifier.verify(envelopeBytes(server), vectorPolicy(), LIVE);
        assertNull(result.code());
        assertEquals(3, result.envelope().payload().flags().size());
        assertEquals("default", result.envelope().payload().project());
    }

    @Test
    void theClientVectorIsSignedCorrectlyButIsNotARuleset() {
        // Signature first, then shape: a client-plane payload verifies and is then refused
        // for not being sv=1 — proving the signature check ran on the bytes, not the schema.
        JsonValue.JsonObject client = entries("accept")
                .filter(e -> string(e.members().get("name")).equals("client-v2"))
                .findFirst()
                .orElseThrow();
        assertEquals(RejectionCode.MALFORMED_PAYLOAD,
                Verifier.verify(envelopeBytes(client), vectorPolicy(), LIVE).code());
    }

    @Test
    void rejectEntriesFailTheWholeVerifierWithTheSameCode() {
        entries("reject").forEach(entry -> assertEquals(
                string(entry.members().get("code")),
                Verifier.verify(envelopeBytes(entry), vectorPolicy(), LIVE).code().wire,
                string(entry.members().get("name"))));
    }

    @Test
    void underDisabledTheUnsignedVectorIsAcceptedAtTheSignatureStage() {
        JsonValue.JsonObject unsigned = entries("reject")
                .filter(e -> string(e.members().get("name")).equals("unsigned"))
                .findFirst()
                .orElseThrow();
        // disabled() never reaches checkSignature; the only remaining rejection is shape
        // (a client payload is not a ruleset), never a signature code.
        assertEquals(RejectionCode.MALFORMED_PAYLOAD,
                Verifier.verify(envelopeBytes(unsigned), SignaturePolicy.disabled(), LIVE).code());
        assertNull(Verifier.verify(Fixtures.envelope(Fixtures.payload()), SignaturePolicy.disabled(),
                new Expectations("dev", Fixtures.NOW, true)).code());
    }

    // ---- keys generated at test time -----------------------------------------------------

    private record Signer(byte[] rawPublicKey, java.security.PrivateKey privateKey) {}

    private static Signer freshSigner() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = pair.getPublic().getEncoded();
        return new Signer(Arrays.copyOfRange(spki, spki.length - 32, spki.length), pair.getPrivate());
    }

    private static String sign(Signer signer, String keyId, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signer.privateKey());
        signature.update(payload);
        return "ed25519:" + keyId + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static byte[] payloadBytes(Map<String, Object> payload) {
        return Fixtures.payloadJson(payload).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aFreshlySignedPayloadVerifiesAndATamperedOneDoesNot() throws Exception {
        Signer signer = freshSigner();
        SignaturePolicy policy = SignaturePolicy.required(Map.of("test-k1", signer.rawPublicKey()));
        Expectations live = new Expectations("dev", Fixtures.NOW, true);
        Map<String, Object> payload = Fixtures.payload();
        String sig = sign(signer, "test-k1", payloadBytes(payload));

        Result good = Verifier.verify(Fixtures.envelope(payload, sig), policy, live);
        assertNull(good.code());
        assertEquals(2, good.envelope().payload().flags().size());

        Map<String, Object> flipped = Fixtures.payload();
        flipped.put("flags", ((String) payload.get("flags")).replace("\"default\":false", "\"default\":true"));
        assertEquals(RejectionCode.BAD_SIGNATURE,
                Verifier.verify(Fixtures.envelope(flipped, sig), policy, live).code());
    }

    @Test
    void anUnknownKeyIdAndAWrongKeyForAKnownIdReject() throws Exception {
        Signer signer = freshSigner();
        Signer other = freshSigner();
        Expectations live = new Expectations("dev", Fixtures.NOW, true);
        Map<String, Object> payload = Fixtures.payload();
        String sig = sign(signer, "test-k1", payloadBytes(payload));
        byte[] raw = Fixtures.envelope(payload, sig);

        assertEquals(RejectionCode.UNKNOWN_KEY_ID, Verifier.verify(raw,
                SignaturePolicy.required(Map.of("test-k2", signer.rawPublicKey())), live).code());
        assertEquals(RejectionCode.BAD_SIGNATURE, Verifier.verify(raw,
                SignaturePolicy.required(Map.of("test-k1", other.rawPublicKey())), live).code());
        // A trusted key that is not 32 bytes is an unknown key, not a crash.
        assertEquals(RejectionCode.UNKNOWN_KEY_ID, Verifier.verify(raw,
                SignaturePolicy.required(Map.of("test-k1", new byte[31])), live).code());
        // The production default does not know a test key.
        assertEquals(RejectionCode.UNKNOWN_KEY_ID,
                Verifier.verify(raw, SignaturePolicy.fortressFlagProduction(), live).code());
    }

    @Test
    void disabledAcceptsAnUnsignedEnvelopeAndRequiredRefusesIt() {
        byte[] unsigned = Fixtures.envelope(Fixtures.payload());
        Expectations live = new Expectations("dev", Fixtures.NOW, true);
        assertNull(Verifier.verify(unsigned, SignaturePolicy.disabled(), live).code());
        assertEquals(RejectionCode.MISSING_SIGNATURE,
                Verifier.verify(unsigned, SignaturePolicy.fortressFlagProduction(), live).code());
    }
}
