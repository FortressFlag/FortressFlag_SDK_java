package com.fortressflag.server.example;

import com.fortressflag.server.Client;
import com.fortressflag.server.Configuration;
import com.fortressflag.server.Context;
import com.fortressflag.server.Diagnostics;
import com.fortressflag.server.FortressFlag;
import com.fortressflag.server.MalformedKeyException;
import com.fortressflag.server.SignaturePolicy;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * §6's walkthrough vehicle. Run the backend stack locally ({@code make db-up migrate seed
 * dev} in FortressFlag_Backend), then, with no configuration at all:
 * {@code ./gradlew :example:run}.
 *
 * <p>The seed key is committed deliberately and is low-entropy on purpose — it
 * authenticates against a laptop database and nothing else (CLAUDE.md §4's fixture rule).
 */
public final class Run {
    private Run() {}

    private static final String SEED_KEY = "ffs_dev_seedseedseedseedseedseedseedseedseedseed000";

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static void main(String[] args) throws InterruptedException {
        Client client;
        try {
            Configuration.Builder builder = Configuration.builder(env("FF_SERVER_KEY", SEED_KEY))
                    .baseUrl(env("FF_BASE_URL", "http://localhost:8080"))
                    // The local dev backend is unsigned unless FF_SIGNING_* is exported; a
                    // production build keeps the default (required, production key).
                    .signature(SignaturePolicy.disabled());
            String cachePath = System.getenv("FF_CACHE_PATH");
            if (cachePath != null && !cachePath.isEmpty()) {
                builder.cachePath(cachePath);
            }
            client = FortressFlag.create(builder.build());
        } catch (MalformedKeyException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("start: " + client.start(Duration.ofSeconds(15)));

        List<Context> contexts = List.of(
                new Context("user-1", Map.of("cohort", "beta")),
                new Context("user-3", Map.of("cohort", "beta")));
        List<String> flags = List.of("new-checkout-flow", "dark-mode", "beta-analytics");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            client.close();
            System.out.println("diagnostics: " + client.diagnostics());
        }));

        DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss");
        while (true) {
            Diagnostics d = client.diagnostics();
            String source = d.snapshotSource().isEmpty() ? "-" : d.snapshotSource();
            System.out.printf("%s  fetch=%s failures=%d source=%s flags=%d%n",
                    LocalTime.now().format(clock), d.lastFetchStatus(),
                    d.consecutiveFailures(), source, d.flagCount());
            for (Context context : contexts) {
                StringBuilder line = new StringBuilder("  " + context.key() + ":");
                for (String flag : flags) {
                    line.append("  ").append(flag).append('=')
                            .append(client.boolValue(flag, context, false));
                }
                System.out.println(line);
            }
            Thread.sleep(10_000);
        }
    }
}
