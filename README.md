# FortressFlag Java SDK

FortressFlag's **Java server SDK** (backend ADR-0018): zero dependencies — including JSON,
whose bounded parser lives in this repo. Polls the server data plane's ruleset export with
an `ffs_` server key and evaluates flags **locally, in-process** — no network hop per flag
check. Java 17+.

```java
import com.fortressflag.server.Configuration;
import com.fortressflag.server.Context;
import com.fortressflag.server.FortressFlag;

var client = FortressFlag.create(
    Configuration.builder(System.getenv("FF_SERVER_KEY")).build()); // the one throw site
client.start(Duration.ofSeconds(15));  // returns at the first ruleset (or the deadline); never fatal
boolean enabled = client.boolValue(
    "dark-mode", new Context("user-42", Map.of("cohort", "beta")), false);
client.close();
```

It implements
[`FortressFlag_Standards/contracts/server-contract-v1.md`](https://github.com/FortressFlag/FortressFlag_Standards/blob/development/contracts/server-contract-v1.md)
— owned by `FortressFlag_Backend`, changed only via ADRs there. After construction, getters
never throw and the poller never blocks JVM exit. See `CLAUDE.md` for the rules this repo
holds itself to.

**The `ffs_` server key is a genuine secret** — treat it like a database password. Store it
in an environment variable or a secret manager, never in code or logs.
