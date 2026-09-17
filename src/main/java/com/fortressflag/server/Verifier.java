package com.fortressflag.server;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
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

    /** A raw Ed25519 public key is exactly this long; anything else in the trust store is
     * treated as an unknown key, so one bad entry cannot disable a rotation set. */
    private static final int ED25519_PUBLIC_KEY_LENGTH = 32;

    /** The SPKI (X.509 SubjectPublicKeyInfo) prefix for an Ed25519 key: {@code KeyFactory}
     * takes DER, the contract publishes raw 32 bytes, and this is the difference.
     * {@code EdECPublicKeySpec} would need our own point decoding instead. */
    private static final byte[] SPKI_ED25519_PREFIX = TrustedKeys.decodeHex("302a300506032b6570032100");

    /** Runs every check in the contract's order and reports the outcome. */
    static Result verify(byte[] raw, SignaturePolicy policy, Expectations expect) {
        Envelope.Parsed parsed = Envelope.parseEnvelope(raw);
        if (parsed == null) {
            return Result.rejected(RejectionCode.MALFORMED_ENVELOPE);
        }

        if (policy.isRequired()) {
            RejectionCode code = checkSignature(parsed.envelope(), parsed.payloadBytes(), policy);
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
     * Pure Ed25519 (RFC 8032) over the payload's exact bytes as transmitted — never a
     * re-serialisation, never a prehashed variant (backend ADR-0025; contract-v1
     * §Signing keys). Order: signature before parse, so no field is read before it is
     * trusted. Package-private so the published vectors can drive it directly.
     */
    static RejectionCode checkSignature(Envelope.Wire envelope, byte[] payloadBytes, SignaturePolicy policy) {
        String sig = envelope.sig();
        if (!envelope.sigPresent() || sig == null || sig.isEmpty()) {
            return RejectionCode.MISSING_SIGNATURE;
        }
        // Split at the first two colons: the SIGNATURE may carry extra colons, the key ID
        // never can (the backend refuses a colon in a key ID for this reason).
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
        byte[] signatureBytes = signature.isEmpty() ? null : Envelope.decodeBase64Url(signature);
        if (signatureBytes == null) {
            return RejectionCode.MALFORMED_SIGNATURE;
        }
        byte[] rawKey = policy.trustedKey(keyId);
        if (rawKey == null || rawKey.length != ED25519_PUBLIC_KEY_LENGTH) {
            return RejectionCode.UNKNOWN_KEY_ID;
        }
        PublicKey publicKey;
        try {
            byte[] spki = new byte[SPKI_ED25519_PREFIX.length + rawKey.length];
            System.arraycopy(SPKI_ED25519_PREFIX, 0, spki, 0, SPKI_ED25519_PREFIX.length);
            System.arraycopy(rawKey, 0, spki, SPKI_ED25519_PREFIX.length, rawKey.length);
            publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (InvalidKeySpecException exception) {
            // A malformed key in our own trust store: "cannot verify with this key", not a
            // hard failure — the iOS rule, ported.
            return RejectionCode.UNKNOWN_KEY_ID;
        } catch (GeneralSecurityException exception) {
            // No Ed25519 provider (impossible on 17+, JEP 339) — nothing can be verified.
            return RejectionCode.BAD_SIGNATURE;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payloadBytes);
            if (!verifier.verify(signatureBytes)) {
                return RejectionCode.BAD_SIGNATURE;
            }
        } catch (GeneralSecurityException exception) {
            // Wrong-length or otherwise undecodable signature bytes.
            return RejectionCode.BAD_SIGNATURE;
        }
        return null;
    }
}
