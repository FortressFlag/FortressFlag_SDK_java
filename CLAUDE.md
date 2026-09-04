# FortressFlag_SDK_java — Agent & Contributor Guide

> **This repo inherits the FortressFlag founding principles.** The canonical document lives in
> the backend repo — read it before design decisions:
>
> - GitHub: <https://github.com/FortressFlag/FortressFlag_Backend/blob/development/CLAUDE.md>
> - Local clone: `~/Workspace/FortressFlag_Backend/CLAUDE.md`
>
> Priority order when in doubt: **Security → Compliance → Efficiency → Cost.**

---

## 1. This Repo

The **Java server SDK** (backend ADR-0020, inheriting ADR-0016's decisions): zero
dependencies, Java 17+. It embeds in a customer's backend, downloads the full evaluable
ruleset for one project + environment via an `ffs_` server key (`GET /v1/server/ruleset`),
and evaluates flags **locally, in-process**. It implements
`FortressFlag_Standards/contracts/server-contract-v1.md` and ports
`vectors/evaluation.json` + `vectors/buckets.json` as unit tests. The contract is owned by
`FortressFlag_Backend`; changes arrive only via ADRs there. The package is
`com.fortressflag.server` — distinct from Android's `com.fortressflag.sdk` on purpose.

## 2. Fail-safe evaluation (Founding §8.1, §8.4)

- **After construction, the SDK never throws to the caller and never exits or halts the
  JVM.** The host is the customer's PROCESS. CI greps library sources for `System.exit(`
  and `Runtime.getRuntime().halt(`; the never-throw promise on getters is enforced by the
  chaos suite (exceptions are legal INTERNALLY — the parser rejects hostile JSON by
  throwing to the verifier, which maps it to a rejection code; nothing propagates out).
- `FortressFlag.create()` is the ONE place the SDK may throw
  (`MalformedKeyException`, before anything serves). Getters always answer; every failure
  resolves to the caller's fallback with the reason on `diagnostics()`. There is no
  compiled-in `false` tier (ADR-0016).
- The last verified ruleset serves through any outage indefinitely. **Expiry governs
  freshness, never validity**: a live response past `expiresAt` is refused; a cache-file
  load never is.
- **The poller must never block JVM exit**: a single-thread ScheduledExecutorService with a
  DAEMON ThreadFactory, and `close()` shuts it down — the daemon flag is load-bearing, not
  tidiness; removing it turns "embed FortressFlag" into "your app never terminates".

## 3. The JSON parser is in-repo, bounded by design (ADR-0020)

Java's stdlib has no JSON parser, and the zero-dependency rule collides with reality
exactly once. The user chose owning the surface over the first runtime dependency in any
FortressFlag SDK (jackson-core was rejected by name; reopening it is a user conversation).
The rules that keep ~300 lines of parser defensible:

- Package-private, `com.fortressflag.server.json`; recursive descent over bytes the
  transport already capped at 1 MiB; **nesting depth capped at 64**; UTF-8 decoded with
  malformed input REPORTED, never replaced; strict JSON numbers (a regex gate before
  `Double.parseDouble`, which alone accepts `NaN`, `Infinity`, hex floats); duplicate keys
  take the LAST occurrence (pinned by test — matching every sibling SDK's parser);
  trailing garbage after the top-level value rejects.
- It parses the contract's payloads and the vectors, nothing more. Streaming,
  data-binding, comments, trailing commas — growth is surface; wanting any of them is a
  conversation.
- All evaluation vectors (42 as of ADR-0023) run THROUGH this parser, and the chaos corpus feeds it
  truncations of a valid payload at every byte.

## 4. The server key IS a secret (server-contract-v1, ADR-0015)

- The raw `ffs_` key lives in memory and goes out on the `Authorization` header — nowhere
  else, ever. Never a log line, an exception message, a cache file, or a toString.
- The only loggable form is the prefix: `ffs_<env>_` plus six characters.
- Test fixtures use short, low-entropy keys (`ffs_dev_k`); never commit a realistic one,
  never allowlist a scanner finding — shorten the fixture.

## 5. Context keys and tags are the customer's data (Founding §7.3)

Never logged, never persisted (the opt-in cache holds the RULESET envelope, never
contexts), never transmitted. The context key is an **opaque string** — never validated
against the client SDKs' `dev_`/`sim_` shape, never trimmed or normalised: the bucket
hashes exactly the UTF-8 bytes given, or cohorts flip between components.

## 6. Zero runtime dependencies (ADR-0020)

The `dependencies` block in `build.gradle.kts` holds **only `testImplementation` /
`testRuntimeOnly`, and CI greps the file for `implementation(`/`api(`**. Everything the SDK
needs is the JDK: `java.net.http.HttpClient`, `java.security.MessageDigest`,
`java.util.Base64`, `java.nio.file`, `java.util.concurrent` — plus §3's parser. **Ask,
don't add.**

## 7. Network surface

`GET /v1/server/ruleset?sv=1` with `Authorization`, `Accept`, and `If-None-Match` — nothing
else, ever. No SDK-version header, no telemetry: an undocumented header is an additive
contract change that goes through a backend ADR. Poll 60 s default, floored at the
contract's 30; backoff cap 1800 s with ±20% jitter ON THE SUCCESS PATH TOO; redirects
refused (`Redirect.NEVER` — a followed redirect could replay the Authorization header);
BOTH `connectTimeout` and the per-request timeout set (connect alone lets a slow body hang
the poller); bodies capped at 1 MiB.

## 8. Concurrency model (ADR-0020)

The snapshot is a `volatile` reference publishing an immutable object; getters read it ONCE
into a local and evaluate against that. Counters are `LongAdder`; mutable diagnostic state
sits behind one lock the evaluation path never takes. **Do not add a lock to the read path
"for safety" — the lock-free read IS the design** (Go's atomic.Pointer, translated).

## 9. Workflow

- Default branch `development`; all changes via PR; squash merge, linear history, `(#N)` on
  every development commit. CI is the merge gate — we cannot recall a shipped SDK.
- Commits and PRs carry FortressFlag authorship, never a personal identity: local commits
  as `FortressFlag <noreply@fortressflag.com>`; PRs opened and merged via the
  `fortressflag` GitHub App.
- **The public API (the public types of `com.fortressflag.server`) and the consumed
  contract are backward-compatibility sacred** (Founding §5, §8.3).
- Local gate, identical to CI: `./gradlew build` (compiles with `--release 17` and
  `-Werror`, runs the JUnit 5 suite), plus the two greps run by hand before pushing.
- `src/main/resources/com/fortressflag/server/vectors/*.json` are verbatim vendored
  copies; the canonical home is `FortressFlag_Standards/vectors/`. A vector change is a
  wire-contract change arriving via a backend ADR — never a test fix, never edited only
  here.
