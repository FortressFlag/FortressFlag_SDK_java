package com.fortressflag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ConfigurationTest {

    @Test
    void aSecretContainingUnderscoresParsesTheSplitTrap() {
        // The secret is base64url; its alphabet includes '_'. indexOf parsing keeps it.
        assertEquals("dev", Configuration.parseKey("ffs_dev_abc_def_ghi"));
    }

    @Test
    void wellFormedKeysParse() {
        assertEquals("prod", Configuration.parseKey("ffs_prod_k12345"));
        assertEquals("my-env", Configuration.parseKey("ffs_my-env_secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ffs", "ffs_dev", "ffs_dev_", "ffc_dev_k", "ffs_D_k", "ffs_a_k", "ffs_-ab_k", "ffs_ab-_k"})
    void malformedKeysAreRefused(String raw) {
        assertNull(Configuration.parseKey(raw));
    }

    @Test
    void keyPrefixIsTheOnlyLoggableForm() {
        assertEquals("ffs_dev_abcdef", Configuration.keyPrefix("ffs_dev_abcdefghij"));
        assertEquals("", Configuration.keyPrefix("nonsense"));
        assertEquals("", Configuration.keyPrefix("ffs_dev_abc"));
    }

    @Test
    void theOneThrowPathNeverEchoesTheKey() {
        MalformedKeyException exception = assertThrows(
                MalformedKeyException.class,
                () -> Configuration.builder("hunter2-the-actual-secret").build());
        assertFalse(exception.getMessage().contains("hunter2"));
    }

    @Test
    void defaultsAndThePollFloor() {
        Configuration configuration = Configuration.builder("ffs_dev_k12345")
                .pollInterval(Duration.ofSeconds(5))
                .build();
        assertEquals("https://edge.fortressflag.com", configuration.baseUrl());
        assertEquals(Duration.ofSeconds(30), configuration.pollInterval()); // the floor
        assertEquals(Duration.ofSeconds(10), configuration.httpTimeout());
        assertEquals("dev", configuration.environment());
        // Fail closed by default (ADR-0025): required, trusting the production key only.
        assertTrue(configuration.signature().isRequired());
        assertNotNull(configuration.signature().trustedKey(TrustedKeys.PRODUCTION_KEY_ID));
        assertNull(configuration.signature().trustedKey("staging-2026-09-k1"));
    }

    @Test
    void theProductionKeyIsThirtyTwoRawBytes() {
        Map<String, byte[]> keys = TrustedKeys.fortressFlagProduction();
        assertEquals(1, keys.size());
        assertEquals(32, keys.get(TrustedKeys.PRODUCTION_KEY_ID).length);
        assertTrue(TrustedKeys.PRODUCTION_KEY_ID.matches("[a-z0-9-]+"));
        byte[] mine = keys.get(TrustedKeys.PRODUCTION_KEY_ID);
        mine[0] ^= 1;
        byte[] fresh = TrustedKeys.fortressFlagProduction().get(TrustedKeys.PRODUCTION_KEY_ID);
        assertTrue(mine[0] != fresh[0], "each call hands out a copy; a caller cannot mutate the SDK's key");
    }

    @Test
    void aTrailingSlashOnBaseUrlIsTrimmed() {
        assertEquals("http://x", Configuration.builder("ffs_dev_k12345").baseUrl("http://x/").build().baseUrl());
    }
}
