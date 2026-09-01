package com.fortressflag.server;

import java.time.Duration;
import java.time.Instant;

/**
 * Envelope verification: is this payload from FortressFlag (signature policy), about us
 * (environment), and now (issuedAt/expiresAt)? A rejection is never fatal: it means "keep
 * serving the last verified snapshot" (Founding §8.4). Rejections are enumerated in this
 * much detail because "flags stopped updating" is otherwise one of the hardest things to
 * debug in a customer's service, and the answer should be one diagnostics() read.
 */
final class Verifier {
    private Verifier() {}

    enum RejectionCode {
        MALFORMED_ENVELOPE("malformedEnvelope"),
        MISSING_SIGNATURE("missingSignature"),
        MALFORMED_SIGNATURE("malformedSignature"),
        UNSUPPORTED_SIGNATURE_ALGORITHM("unsupportedSignatureAlgorithm"),
        UNKNOWN_KEY_ID("unknownKeyId"),
        BAD_SIGNATURE("badSignature"),
        MALFORMED_PAYLOAD("malformedPayload"),
        UNSUPPORTED_CONTRACT_VERSION("unsupportedContractVersion"),
        ENVIRONMENT_MISMATCH("environmentMismatch"),
        EXPIRED("expired"),
        ISSUED_IN_THE_FUTURE("issuedInTheFuture");

        final String wire;

        RejectionCode(String wire) {
            this.wire = wire;
        }
    }

    /**
     * An envelope that passed every check, kept alongside the exact bytes it arrived as.
     * {@code raw} is retained so the cache stores what was (or will one day be) signed
     * rather than a re-serialisation — re-serialising would silently strip any field a
     * future server adds and break the signature on reload (the web verifier's rule,
     * ported).
     */
    record VerifiedEnvelope(byte[] raw, Envelope.Payload payload) {}

    /**
     * What the payload must claim to be, for it to be about us.
     *
     * <p>{@code enforceExpiry}: TRUE for a live response, FALSE when loading the cachePath
     * file — and that asymmetry is the single most load-bearing rule in this SDK. On a
     * live response, expiry is the replay window: without it, anyone who captured a valid
     * response could serve it back forever, pinning a fleet to old flag values. On a cache
     * load it must NOT apply: the last verified snapshot is the primary fallback, and a
     * service that restarts after a long outage keeps evaluating with what it last saw.
     * Enforcing expiry there would silently revert every flag to caller defaults after any
     * 30-minute outage plus one restart — turning an outage into a feature regression,
     * which is precisely the failure the cascade exists to prevent. Expiry governs
     * freshness, not validity.
     */
    record Expectations(String environment, Instant now, boolean enforceExpiry) {}

    record Result(VerifiedEnvelope envelope, RejectionCode code) {
        static Result rejected(RejectionCode code) {
            return new Result(null, code);
        }
    }

    /** Forgives a wrong server-or-host clock; 300 s is the ceiling. */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(300);

    /** Runs every check in the contract's order and reports the outcome. */
    static Result verify(byte[] raw, SignaturePolicy policy, Expectations expect) {
        Envelope.Parsed parsed = Envelope.parseEnvelope(raw);
        if (parsed == null) {
            return Result.rejected(RejectionCode.MALFORMED_ENVELOPE);
        }

        if (policy.isRequired()) {
            RejectionCode code = checkSignature(parsed.envelope(), policy);
            if (code != null) {
                return Result.rejected(code);
            }
        }

        Envelope.Payload payload = Envelope.parsePayload(parsed.payloadBytes());
        if (payload == null) {
            return Result.rejected(RejectionCode.MALFORMED_PAYLOAD);
        }

        if (payload.sv() != Envelope.SUPPORTED_SERVER_CONTRACT_VERSION) {
            // A version this build does not speak might mean anything; refusing to guess
            // is the contract's own instruction.
            return Result.rejected(RejectionCode.UNSUPPORTED_CONTRACT_VERSION);
        }
        if (!payload.environment().equals(expect.environment())) {
            // A production payload replayed at a dev process (or vice versa) is refused
            // even though the key, not the payload, chose the scope: the claims must agree.
            return Result.rejected(RejectionCode.ENVIRONMENT_MISMATCH);
        }

        Instant issuedAt = Envelope.parseWireTime(payload.issuedAt());
        if (issuedAt == null) {
            return Result.rejected(RejectionCode.MALFORMED_PAYLOAD);
        }
        Instant expiresAt = Envelope.parseWireTime(payload.expiresAt());
        if (expiresAt == null) {
            return Result.rejected(RejectionCode.MALFORMED_PAYLOAD);
        }
        if (Duration.between(expect.now(), issuedAt).compareTo(CLOCK_SKEW_TOLERANCE) > 0) {
            return Result.rejected(RejectionCode.ISSUED_IN_THE_FUTURE);
        }
        if (expect.enforceExpiry()
                && Duration.between(expiresAt, expect.now()).compareTo(CLOCK_SKEW_TOLERANCE) > 0) {
            return Result.rejected(RejectionCode.EXPIRED);
        }

        return new Result(new VerifiedEnvelope(raw, payload), null);
    }

    /**
     * The signature PLUMBING with the crypto primitive deliberately absent
     * (ADR-0015/0016): backend M4's algorithm ADR has not shipped. A missing signature
     * under a required policy is rejected (fail closed, the shipped client-SDK posture
     * byte for byte); the {@code algorithm:keyID:signature} splitting and trust-store
     * lookup are real; and a signature that survives those checks is still rejected as
     * BAD_SIGNATURE, because no primitive exists to accept it. When M4 lands, its ADR
     * decides the primitive and this is where it goes — with a real trust store, this stub
     * can reject valid payloads but can never accept a forged one.
     */
    private static RejectionCode checkSignature(Envelope.Wire envelope, SignaturePolicy policy) {
        String sig = envelope.sig();
        if (!envelope.sigPresent() || sig == null || sig.isEmpty()) {
            return RejectionCode.MISSING_SIGNATURE;
        }
        // Split at the first two colons so a key ID may contain a colon later without a
        // breaking parse change.
        int first = sig.indexOf(':');
        int second = first >= 0 ? sig.indexOf(':', first + 1) : -1;
        if (first < 0 || second < 0) {
            return RejectionCode.MALFORMED_SIGNATURE;
        }
        String algorithm = sig.substring(0, first);
        String keyId = sig.substring(first + 1, second);
        String signature = sig.substring(second + 1);
        if (!algorithm.equals("ed25519")) {
            return RejectionCode.UNSUPPORTED_SIGNATURE_ALGORITHM;
        }
        if (signature.isEmpty() || Envelope.decodeBase64Url(signature) == null) {
            return RejectionCode.MALFORMED_SIGNATURE;
        }
        if (!policy.knowsKeyId(keyId)) {
            return RejectionCode.UNKNOWN_KEY_ID;
        }
        // The primitive gap, made explicit: the payload bytes are deliberately unused
        // beyond this point until M4 supplies the algorithm.
        return RejectionCode.BAD_SIGNATURE;
    }
}
