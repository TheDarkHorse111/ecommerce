# Bus broker address and config server URI in the gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The gateway stops hardcoding the two addresses it hardcodes today. The Kafka broker list moves out of `gateway/src/main/resources/application.yaml` into the config repository's shared triplet, where one entry serves every config client and `prod` and `local` can differ. The config server URI stays in the module and becomes `configserver:${CONFIG_SERVER_URI:http://localhost:8888}`.

**Architecture:** Spec section 3 asks four questions in order, and for these two addresses the first answer overrides the second. The broker list is read *after* the config client has run, so it can live in the config repository and does; only the address moves, while the binder name, the binder type and the two bindings stay in the module because they are identical everywhere and carry no secret. The config server URI is what *finds* the config repository, so it cannot live inside it; it stays in the module and reads its environment from outside through a placeholder with a `localhost:8888` default, which keeps `mvn -B clean verify` and a bare local run working with nothing exported. `config-server` is not a config client and cannot fetch its configuration from the thing it is, so it keeps its own broker address in its own module file, untouched.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Cloud 2025.1.3, `spring-cloud-starter-config`, `spring-cloud-starter-bus-kafka`, `spring-cloud-starter-gateway-server-webmvc`, `spring-boot-starter-actuator`, Redpanda v25.2.4 in Docker, Error Prone 2.50.0, maven-compiler-plugin 3.16.0.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md`, section 3 "Configuration". Issue #54 is the specification for this increment; its Scope, Design and Acceptance blocks are quoted verbatim below wherever a decision rests on them. Its dependency, issue #12, is planned in `docs/superpowers/plans/2026-09-09-gateway.md` and is merged. The two blocks this issue edits were written by issue #13, planned in `docs/superpowers/plans/2026-09-09-config-refresh-over-spring-cloud-bus.md`, which is also merged; that plan's closing section "Configuration placement, and one deviation from the spec" is the deviation this issue half-corrects, and its "Known exposure" note still stands.

---

## Global Constraints

Copied from `CLAUDE.md`, issue #54 and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. There is no separate lint step. Error Prone runs during compilation.
- **No Java is written in this issue.** Every change is YAML. Nothing gains a branch, a loop, validation, orchestration, arithmetic or an HTTP contract of ours, so per `CLAUDE.md` nothing gets a JUnit test and **nothing may be given one**. The failing test that comes first in every task below is an acceptance criterion run as a command whose failure is observed and recorded before the edit. Never test the framework.
- **Comments.** None, and that includes the two files of the config repository. Rationale lives in the spec and in this plan.
- **Scope.** Build only what issue #54 asks for. Its Scope block ends "Nothing else in either block moves." In particular do **not** move the binder name, the binder `type: kafka`, the `springCloudBusInput`/`springCloudBusOutput` bindings, the gateway routes, `spring.application.name`, `spring.profiles.active`, `server.port`, or anything under `management`. Do not touch `config-server`, `discovery-server`, either `pom.xml`, the root `pom.xml`, `.github/`, `README.md`, `CLAUDE.md` or the spec. See "Deliberate omissions".
- **Two repositories, two histories.** The module change lands on branch `issue-54` of `TheDarkHorse111/ecommerce`. The config repository change lands on branch `config` of the same remote, which is a separate history that no pull request merges. Both are part of the deliverable; only the first appears in the pull request diff, which is why the pull request body must name the second.
- **Conventional Commits**, scope `gateway` for module changes and `config` for config-repository changes, one commit per completed red-green cycle, every commit green, each carrying a `Refs #54` footer. No `Co-Authored-By` trailer, no generated-with footer.
- **Pull request** title ends `(#54)`; body ends `Closes #54`. Merge with rebase, never squash.
- **Startup order.** `spring.config.import` on the gateway is not `optional:`, so `config-server` must be up before `gateway` in every check below. That is issue #13's deliberate choice and this issue does not change it.

---

## Verified Findings

Issue #54 was written before any of this was built, so every claim it makes about how a tool behaves was executed on this machine before planning on top of it. The probes were a throwaway module at `.scratch/client-probe/` parented to the real root `pom.xml` by `<relativePath>` and never listed in `<modules>`, a throwaway git repository at `.scratch/config-repo/` holding the shared triplet, the real `config-server` jar built from `master`, and a Redpanda container. All were deleted afterwards and `git status --short` printed nothing.

**No claim in issue #54 turned out to be false.** Findings 1 to 6 are confirmations. Findings A to E are things the issue does not say that this plan depends on, and finding A is the one that decides how every assertion below is written.

### Confirmed

1. **`spring.config.import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}"` resolves the placeholder from the environment variable.** The exact line from the issue's Design block was put in a probe module and the probe was launched with `CONFIG_SERVER_URI=http://localhost:8889` and nothing else changed:

   ```
   INFO ... o.s.c.c.c.ConfigServerConfigDataLoader : Fetching config from server at : http://localhost:8889
   INFO ... o.s.c.c.c.ConfigServerConfigDataLoader : Located environment: name=probe, profiles=[default], ...
   INFO ... o.s.c.c.c.ConfigServerConfigDataLoader : Fetching config from server at : http://localhost:8889
   INFO ... o.s.c.c.c.ConfigServerConfigDataLoader : Located environment: name=probe, profiles=[local], ...
   ```

   Two fetches, not one, because Spring Boot processes config data twice — once before profiles are known and once after. Both name 8889.

2. **With the variable unset the same line defaults to `http://localhost:8888`.** With nothing listening there, the probe failed and named the address it had tried:

   ```
   org.springframework.cloud.config.client.ConfigClientFailFastException:
     Could not locate PropertySource and the resource is not optional, failing
   Caused by: org.springframework.web.client.ResourceAccessException:
     I/O error on GET request for "http://localhost:8888/probe/default": Connection refused (connect failed)
   ```

   With a config server on 8888 it started and read its configuration from it.

3. **A broker address served by the config server reaches the named binder.** The probe carried `binders.bus.type: kafka` and the two bindings but **no** `environment` subtree and therefore no broker address of its own. With `spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers: 127.0.0.1:9092` in the config repository, the probe logged:

   ```
   INFO ... o.s.c.s.binder.DefaultBinderFactory : Creating binder: bus
        bootstrap.servers = [127.0.0.1:9092]
   INFO ... o.a.k.c.c.i.ClassicKafkaConsumer    : ... Subscribed to topic(s): springCloudBus
   INFO ... c.thedarkhorse.probe.ProbeApplication : Started ProbeApplication in 4.85 seconds
   ```

   `127.0.0.1:9092` appears in no module file and is not the binder's default, so it can only have come from the config server.

4. **`prod` and `local` resolve different broker addresses from the shared triplet with no module file changing.** With `localhost:9092` in the config repository's `application.yaml` and `redpanda:9092` overriding it in `application-prod.yaml`, the same jar produced, under `local`:

   ```json
   {"activeProfiles":["local"],
    "property":{"source":"configserver:.../application.yaml","value":"localhost:9092"}}
   ```

   and under `--spring.profiles.active=prod`:

   ```json
   {"activeProfiles":["prod"],
    "property":{"source":"configserver:.../application-prod.yaml","value":"redpanda:9092"},
    "propertySources":[... {"name":"configserver:.../application-prod.yaml","property":{"value":"redpanda:9092"}},
                           {"name":"configserver:.../application.yaml","property":{"value":"localhost:9092"}} ...]}
   ```

   with `bootstrap.servers = [redpanda:9092]` on the Kafka client. Most specific wins, exactly as spec section 3 describes.

5. **A refresh broadcast over the bus still reaches a client whose broker address came from the config repository.** `POST http://localhost:8888/actuator/busrefresh` returned `HTTP 204` and the probe, untouched, logged on its Kafka listener thread:

   ```
   INFO ... [container-0-C-1] o.s.cloud.bus.event.RefreshListener : Received remote refresh request.
   INFO ... [container-0-C-1] o.s.cloud.bus.event.RefreshListener : Keys refreshed []
   ```

6. **`config-server` starts with the config repository unreachable, on its own broker address.** Started with `--spring.cloud.config.server.git.uri=https://unreachable.invalid/ecommerce.git` and otherwise as `master` leaves it:

   ```
   INFO ... o.s.c.s.binder.DefaultBinderFactory : Creating binder: bus
        bootstrap.servers = [localhost:9092]
   INFO ... o.a.k.c.c.i.ClassicKafkaConsumer    : ... Subscribed to topic(s): springCloudBus
   INFO ... o.s.boot.tomcat.TomcatWebServer     : Tomcat started on port 8888 (http) with context path '/'
   INFO ... c.t.config.ConfigServerApplication  : Started ConfigServerApplication in 5.408 seconds
   ```

   The git URI is resolved lazily, on the first request for an environment, not at startup.

### Five things the issue does not say

**A. With the broker address absent everywhere, the binder silently falls back to `localhost:9092` and the application starts anyway. `bootstrap.servers = [localhost:9092]` therefore proves nothing, and no assertion in this plan may rest on it.**

This is the trap in this issue. Measured with the key removed from both the module and the config repository:

```
$ java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
HTTP 404

     bootstrap.servers = [localhost:9092]
INFO ... c.thedarkhorse.probe.ProbeApplication : Started ProbeApplication in 4.84 seconds
```

`localhost:9092` is `KafkaBinderConfigurationProperties`' own field default, and it is also the value this issue commits to the config repository for `local`. The two are indistinguishable by value. Two consequences, both load-bearing:

- **Every green assertion about the broker address asserts on provenance, not on the value** — `/actuator/env/<key>` must return `HTTP 200` with `"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml"`. The matching red is `HTTP 404`, which is unambiguous: the key is in no property source at all.
- **The `prod` run in Task 2 is the decisive control for the whole mechanism**, because `redpanda:9092` is a value the binder would never invent and that appears in no module file.

**B. Config server property sources outrank the module's own `application.yaml`.** Read off the probe's `/actuator/env`, in precedence order:

```
"configserver:.../application-local.yaml"
"configserver:.../application.yaml"
"configClient"
"Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'"
"kafkaBinderDefaultProperties"
```

So a broker address left in both places would not conflict loudly — the config repository would quietly win and the module's copy would rot unnoticed. That is what acceptance criterion 8 exists to prevent, and it is why Task 1 removes the module's copy in the same cycle that adds the repository's.

**C. Under `prod` on a developer machine the broker address does not resolve, and the resulting ERROR is expected.** `redpanda:9092` is a container hostname. The probe logged:

```
WARN  ... o.a.k.clients.ClientUtils : Couldn't resolve server redpanda:9092 from bootstrap.servers as DNS resolution failed for redpanda
Caused by: org.apache.kafka.common.config.ConfigException: No resolvable bootstrap urls given in bootstrap.servers
INFO  ... ProbeApplication : Started ProbeApplication in 3.86 seconds
```

**The application still starts.** Task 2 asserts on the address the binder was handed, not on a working connection. Do not "fix" this by pointing `prod` at `localhost`.

**D. There is no `curl` and no `wget`, and a `VAR=value command` prefix is not permitted either.** `Bash(java:*)` is on the allowlist and Java's single-file source launcher runs a `.java` file directly, so **Task 1 Step 1 creates `.scratch/Http.java` and `.scratch/Run.java`** and every HTTP check and every environment-variable check in this plan runs through them. Both files below produced every measurement above. Measured:

```
$ CONFIG_SERVER_URI=http://localhost:8889 java -jar .../client-probe-0.0.1-SNAPSHOT.jar
This command requires approval
```

**E. `Bash(docker:*)` is on the allowlist and Redpanda runs.** Every runtime check in Tasks 1 and 2 needs a broker on `localhost:9092`; Task 3 does not. `grep -c` exits 1 when the count is zero — read the printed number, not the exit status.

### One ambiguity in the issue, and how it is resolved

Issue #54 says twice that the broker address goes to "the config repository's shared `application.yaml`", naming that one file. Its third acceptance criterion says "`prod` and `local` resolve different broker addresses without a module file changing". A key present only in `application.yaml` resolves the same under both profiles, so the two sentences cannot both be satisfied by one entry.

**Resolution: `application.yaml` carries `localhost:9092` and `application-prod.yaml` overrides it with `redpanda:9092`.** `local` inherits from `application.yaml` and nothing else, which is the first two sentences read literally; `prod` overrides, which is the third. Finding 4 measured that exact shape. `application-local.yaml` gains nothing, so the address is written in exactly two places for two profiles rather than three places for two.

Two smaller consequences worth stating rather than hiding:

- Spec section 3 says "`prod` carries the same values as `local` except log level". After this issue that is no longer true — the broker address differs too. Issue #54 is newer than the spec and its acceptance criterion is explicit, so the criterion wins. The spec's own reason for keeping one key deliberately different — "with identical files a filename typo or a broken merge is indistinguishable from success" — applies equally to this one.
- The existing profile-varying address in the repository, `eureka.client.service-url.defaultZone`, is written identically into `application-local.yaml` and `application-prod.yaml` and is absent from `application.yaml`. The broker address does not follow that shape, for the reason above. This is a visible inconsistency in the repository and the pull request body must name it.

### Acceptance criterion 8 and the copy `config-server` keeps

Criterion 8 reads "No key naming either address exists in both the module and the config repository." Taken across the whole monorepo it is unsatisfiable, because the issue's own Design block requires `config-server` to keep `brokers: localhost:9092` in `config-server/src/main/resources/application.yaml` while the config repository also holds a `brokers` key.

The criterion is about the gateway. The issue's Scope opens "The two addresses **the gateway** hardcodes", and the Design block explains why `config-server`'s copy is the exception. So the check in Task 1 Step 6 is scoped to `gateway/src/main/resources/application.yaml`, and `config-server`'s copy is asserted to be **still present and unchanged**, which is acceptance criterion 7. It is not duplication that can disagree in practice: `config-server` is not a config client, never reads the repository it serves, and finding B's precedence list never applies to it.

### Deliberate omissions

- **No `spring.cloud.config.uri` property.** `spring.config.import` already carries the URI, finding 1 measured it working, and a second key naming the same address is exactly what criterion 8 forbids.
- **No `optional:` on the import, and no `spring.cloud.config.fail-fast`.** Issue #13 chose the non-optional form deliberately and issue #54 does not revisit it.
- **No `SPRING_PROFILES_ACTIVE` support work and no removal of `spring.profiles.active: local` from the module.** The issue does not ask for it. Task 2 selects `prod` with `--spring.profiles.active=prod` on the command line, which is a run-time override and changes no module file, which is what criterion 3 requires.
- **No change to `config-server`.** Criterion 7 requires the opposite, and Task 1 Step 6 proves it.
- **No change to `discovery-server`.** It is neither a config client nor a bus participant.
- **No `application-local.yaml` entry for the broker address.** See "One ambiguity" above.
- **No Docker Compose file.** None of the three modules has one yet and this issue does not ask for one. Redpanda is started with `docker run` in Task 1 and removed in Task 4.
- **No environment variable for the broker address.** The issue routes it to the config repository precisely so that it is not a per-module knob.

---

## File Structure

Committed to `TheDarkHorse111/ecommerce`, branch `issue-54`:

| Path | Responsibility | Action |
| --- | --- | --- |
| `gateway/src/main/resources/application.yaml` | Loses the `environment` subtree under `spring.cloud.stream.binders.bus`, keeping the binder name, `type: kafka` and both bindings. Gains the `CONFIG_SERVER_URI` placeholder on `spring.config.import`. | Modify |

Committed to `TheDarkHorse111/ecommerce`, branch `config`, which no pull request merges:

| Path | Responsibility | Action |
| --- | --- | --- |
| `application.yaml` | Gains the shared broker address, `localhost:9092`, read by every config client under every profile that does not override it. | Modify |
| `application-prod.yaml` | Gains the `prod` broker address, `redpanda:9092`, overriding the shared one. | Modify |

Deliberately untouched, and asserted to be untouched in Task 4: `config-server/src/main/resources/application.yaml` including its own `brokers: localhost:9092`, `discovery-server/`, `gateway/pom.xml`, `config-server/pom.xml`, the root `pom.xml`, `lombok.config`, `.github/`, `CLAUDE.md`, `README.md`, the spec, every other plan, and the config repository's `application-local.yaml`, `catalog-service.yaml`, `catalog-service-local.yaml` and `catalog-service-prod.yaml`.

Not created: no Java, no test, no new module, no new resource file.

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/Http.java` | The HTTP client, because the allowlist has none (finding D). Created in Task 1, deleted in Task 4. |
| `.scratch/Run.java` | Launches a jar with one environment variable set, because a `VAR=value` command prefix is not permitted (finding D). Created in Task 1, used in Task 3, deleted in Task 4. |
| `.scratch/config-repo/` | A git worktree on the `config` branch. Created in Task 1, removed in Task 4. |

---

## Task 1: The broker address moves to the config repository

Covers acceptance criteria 1, 2, 4, 7 and the broker half of 8. The module's copy is removed and the repository's is added in one cycle, because finding B shows that leaving both would not fail loudly — the repository would silently win.

**Files:**
- Create: `.scratch/Http.java`, `.scratch/Run.java`
- Create: `.scratch/config-repo/` (a worktree on branch `config`)
- Modify on branch `config`: `application.yaml`
- Modify: `gateway/src/main/resources/application.yaml:11-27` — the `spring.cloud.stream` block

**Interfaces:**
- Consumes: from issue #12, the module `com.thedarkhorse:gateway:0.0.1-SNAPSHOT`, its jar at `gateway/target/gateway-0.0.1-SNAPSHOT.jar`, `spring.application.name: gateway`, `spring.profiles.active: local`, its single `catalog` route and `server.port: 8080`. From issue #13, `config-server` on 8888 with `busrefresh` exposed, the gateway's `env` actuator with `management.endpoint.env.show-values: ALWAYS`, the named `bus` binder with both bindings, and the branch `config` of `https://github.com/TheDarkHorse111/ecommerce.git` holding the six configuration files.
- Produces: the key `spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers` in the config repository's `application.yaml` with the value `localhost:9092`, and its absence from `gateway/src/main/resources/application.yaml`. Task 2 overrides that key for `prod`. `.scratch/Http.java` exposes `Http.main(String[])`, invoked as `java .scratch/Http.java <GET|POST> <url>`; `.scratch/Run.java` exposes `Run.main(String[])`, invoked as `java .scratch/Run.java <VAR> <value> <java args...>`. Tasks 2 and 3 use both; Task 4 deletes them.

- [ ] **Step 1: Create the tools and the worktree**

The allowlist has no HTTP client and no way to set an environment variable on a command (finding D). Create `.scratch/Http.java` exactly as below. It retries the connection for up to two minutes so it can be launched before a server is up. No comments, consistent with the standing rule; it is a throwaway harness and needs no test of its own.

```java
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Http {

    public static void main(String[] args) throws Exception {
        String method = args[0];
        String url = args[1];

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");

        if (method.equals("POST")) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = builder.build();

        long deadline = System.currentTimeMillis() + 120_000L;
        while (true) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("HTTP " + response.statusCode());
                System.out.println(response.body());
                return;
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(1000L);
            }
        }
    }
}
```

Create `.scratch/Run.java`:

```java
import java.util.ArrayList;
import java.util.List;

public class Run {

    public static void main(String[] args) throws Exception {
        String name = args[0];
        String value = args[1];
        List<String> command = new ArrayList<>(List.of("java"));
        for (int i = 2; i < args.length; i++) {
            command.add(args[i]);
        }
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.environment().put(name, value);
        System.exit(builder.start().waitFor());
    }
}
```

A worktree keeps the `issue-54` working tree untouched and shares `origin` and its push credential.

```bash
git fetch origin
```

```bash
git worktree add .scratch/config-repo config
```

There is no local `config` branch; `git worktree add` creates one from `origin/config` because exactly one remote has it.

Expected: `Preparing worktree (checking out 'config')` followed by the six file names.

```bash
git -C .scratch/config-repo status --short --branch
```

Expected exactly: `## config...origin/config`, and no file lines.

- [ ] **Step 2: Start Redpanda and the config server**

The bus needs a broker on `localhost:9092`. This is a `docker run`, not a committed Compose file — see "Deliberate omissions".

```bash
docker run -d --name redpanda -p 9092:9092 -p 9644:9644 docker.redpanda.com/redpandadata/redpanda:v25.2.4 redpanda start --mode dev-container --smp 1 --kafka-addr PLAINTEXT://0.0.0.0:9092 --advertise-kafka-addr PLAINTEXT://localhost:9092
```

```bash
docker exec redpanda rpk cluster info
```

Expected: a `BROKERS` table with one row, `0*  localhost  9092`. If the command errors, wait and run it again; the container takes a few seconds.

```bash
mvn -B clean package
```

Expected: `BUILD SUCCESS`, reactor listing `ecommerce`, `discovery-server`, `gateway`, `config-server`.

Start `config-server` as a **background** Bash task and note the output path the harness prints as `CONFIG_LOG`. Substitute the real path everywhere `CONFIG_LOG` appears below, and note it is reassigned on every restart.

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

```bash
grep -E 'Subscribed to topic\(s\): springCloudBus|Started ConfigServerApplication' CONFIG_LOG
```

Expected both lines. The gateway will not start without this process.

- [ ] **Step 3: Write the failing test**

No behaviour of ours is added in this issue, so per `CLAUDE.md` there is no JUnit test; the failing test is acceptance criteria 1 and 2 run as commands. Start the gateway exactly as `master` leaves it, as a second **background** Bash task, noting `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Then the two commands that must move:

```bash
grep -c 'brokers: localhost:9092' gateway/src/main/resources/application.yaml
```

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
```

- [ ] **Step 4: Run it to verify it fails**

Expected from the grep — criterion 1 fails, the address is in the module:

```
1
```

Expected from the env endpoint — `HTTP 200`, but sourced from the gateway's own jar rather than from the config server, so criterion 2 fails:

```json
{"activeProfiles":["local"],"defaultProfiles":["default"],
 "property":{"source":"Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
             "value":"localhost:9092"},
 "propertySources":[...]}
```

Read the `"source"` field, not the `"value"` field. Finding A: the value alone cannot tell the module from the config repository from the binder's own default, and every assertion in this task turns on provenance.

**Stop the gateway background task.** Leave `config-server` and Redpanda running.

- [ ] **Step 5: Write the minimal implementation**

Two files change, in two repositories, in one cycle.

Edit `.scratch/config-repo/application.yaml` so it reads in full. `spring:` appears once, with `cloud:` and `jpa:` under it; the existing `jpa` block is unchanged and must not be reordered away.

```yaml
spring:
  cloud:
    stream:
      binders:
        bus:
          environment:
            spring:
              cloud:
                stream:
                  kafka:
                    binder:
                      brokers: localhost:9092
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): hold the kafka broker address for every config client

Refs #54
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

Edit `gateway/src/main/resources/application.yaml` so it reads in full. Only the `environment` subtree under `binders.bus` is gone. The binder name `bus`, `type: kafka` and both bindings stay, because the issue's Design block says "The binder name, the bindings and the binder type are identical everywhere and carry no secret, so they stay in the module; only the address moves." `spring.config.import` is not touched in this task; that is Task 3.

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway
  profiles:
    active: local
  config:
    import: "configserver:"
  cloud:
    stream:
      binders:
        bus:
          type: kafka
      bindings:
        springCloudBusInput:
          binder: bus
        springCloudBusOutput:
          binder: bus
    gateway:
      server:
        webmvc:
          routes:
            - id: catalog
              uri: lb://catalog-service
              predicates:
                - Path=/catalog/**
              filters:
                - StripPrefix=1

management:
  endpoint:
    env:
      show-values: ALWAYS
  endpoints:
    web:
      exposure:
        include: env
```

- [ ] **Step 6: Run it to verify it passes**

```bash
mvn -B -pl gateway clean package
```

Expected: `BUILD SUCCESS`.

Start the gateway as a **background** Bash task, noting the new `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Criterion 1 — the address is no longer in the module:

```bash
grep -c 'brokers' gateway/src/main/resources/application.yaml
```

Expected: `0`. `grep -c` exits 1 on a zero count; read the printed number (finding E).

Criterion 2 — the address is read from the config repository's shared `application.yaml`:

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
```

Expected `HTTP 200`, with `"source"` naming the config server and the shared file — this is the assertion, not the value:

```json
{"activeProfiles":["local"],"defaultProfiles":["default"],
 "property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
             "value":"localhost:9092"},
 "propertySources":[... {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application-local.yaml"},
                        {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
                         "property":{"origin":"Config Server ...","value":"localhost:9092"}}, ...]}
```

If this is `HTTP 404` the key is in no property source at all and the push in Step 5 did not land or the config server has not re-fetched. If `"source"` still names `class path resource [application.yaml]`, the module edit did not land.

Confirm the binder was still built and joined the topic:

```bash
grep -E 'Creating binder: bus|Subscribed to topic\(s\): springCloudBus|Started GatewayApplication' GATEWAY_LOG
```

Expected three lines.

Criterion 4 — a refresh broadcast over the bus still reaches the gateway:

```bash
java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh
```

Expected: `HTTP 204`.

```bash
grep -E 'Received remote refresh request|Keys refreshed' GATEWAY_LOG
```

Expected two lines on the Kafka listener thread, which is what proves the message travelled over the broker rather than being handled in-process:

```
INFO ... [container-0-C-1] o.s.cloud.bus.event.RefreshListener : Received remote refresh request.
INFO ... [container-0-C-1] o.s.cloud.bus.event.RefreshListener : Keys refreshed []
```

`Keys refreshed []` with an empty list is correct — nothing in the repository changed between the gateway's start and the broadcast.

Confirm the gateway still routes, so that editing its binder block did not break issue #12's work:

```bash
java .scratch/Http.java GET http://localhost:8080/catalog/api/v1/x
```

Expected: `HTTP 503`. That is issue #12's acceptance criterion — the `catalog` route matched and no instance is registered — and it must not have become a 404.

Criterion 7, first half — `config-server` keeps its local broker address:

```bash
grep -c 'brokers: localhost:9092' config-server/src/main/resources/application.yaml
```

Expected: `1`.

Criterion 8, broker half — the key is in the config repository and not in the gateway module:

```bash
grep -rn 'brokers' gateway/src .scratch/config-repo
```

Expected exactly one line, from `.scratch/config-repo/application.yaml`, and nothing from `gateway/src`.

**Stop the gateway background task.** Leave `config-server` and Redpanda running; Task 2 uses them.

- [ ] **Step 7: Confirm the gate and commit**

```bash
git status --short
```

Expected exactly: `M gateway/src/main/resources/application.yaml`, plus `.scratch/` untracked.

```bash
git add gateway/src/main/resources/application.yaml
```

```bash
git commit -m "$(cat <<'EOF'
chore(gateway): read the kafka broker address from the config repository

Refs #54
EOF
)"
```

The gate is deferred to Task 4, which runs `mvn -B clean verify` once with nothing running; `mvn clean` here would delete the two jars out from under the processes Task 2 needs. The commit is green regardless: no Java changed and `mvn -B -pl gateway clean package` succeeded in Step 6.

---

## Task 2: `prod` and `local` resolve different broker addresses

Covers acceptance criterion 3, and is the decisive control for the whole mechanism (finding A): `redpanda:9092` appears in no module file and is not a value the Kafka binder would ever invent, so seeing it on the wire proves the address came from the config repository and nowhere else.

Requires Task 1's push to have landed and both `config-server` and Redpanda to still be running. Note finding C: under `prod` on this machine `redpanda:9092` does not resolve and the binder logs a WARN and an ERROR. **That is expected.** The assertion is about the address the binder was handed, not about a working connection.

**Files:**
- Modify on branch `config`: `application-prod.yaml`

**Interfaces:**
- Consumes: from Task 1, the broker address in the config repository's `application.yaml`, a gateway with no broker address of its own, `.scratch/config-repo/`, `.scratch/Http.java`, `config-server` on 8888 and Redpanda on 9092.
- Produces: the same key overridden for `prod` with the value `redpanda:9092`. Task 3 consumes nothing from this task but runs against the same two processes.

- [ ] **Step 1: Write the failing test**

Start the gateway under `prod` as a **background** Bash task, noting `GATEWAY_LOG`. `--spring.profiles.active=prod` is a command-line override and changes no module file, which is what criterion 3 requires.

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
```

- [ ] **Step 2: Run it to verify it fails**

Expected `HTTP 200`, with `"activeProfiles":["prod"]` and the *same* address `local` resolved in Task 1 Step 6, from the *same* file. Criterion 3 fails: the two profiles do not differ.

```json
{"activeProfiles":["prod"],"defaultProfiles":["default"],
 "property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
             "value":"localhost:9092"},
 "propertySources":[...]}
```

```bash
grep -c 'bootstrap.servers = \[localhost:9092\]' GATEWAY_LOG
```

Expected: a number greater than `0`.

**Stop the gateway background task.**

- [ ] **Step 3: Write the minimal implementation**

Edit `.scratch/config-repo/application-prod.yaml` so it reads in full. The existing `logging` and `eureka` blocks are unchanged; a `spring` block is added. `redpanda:9092` is the container hostname the spec's Redpanda runs under, not a secret and not a real production endpoint — spec section 3, "Real production values are not committed".

```yaml
logging:
  level:
    root: INFO

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/

spring:
  cloud:
    stream:
      binders:
        bus:
          environment:
            spring:
              cloud:
                stream:
                  kafka:
                    binder:
                      brokers: redpanda:9092
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): give prod its own kafka broker address

Refs #54
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

- [ ] **Step 4: Run it to verify it passes**

Confirm the config server serves the two profiles differently, before involving the gateway at all:

```bash
java .scratch/Http.java GET http://localhost:8888/gateway/prod
```

Expected `HTTP 200`, with `application-prod.yaml` listed above `application.yaml` and each carrying its own value:

```json
{"name":"gateway","profiles":["prod"],
 "propertySources":[{"name":"https://github.com/TheDarkHorse111/ecommerce.git/application-prod.yaml",
                     "source":{"logging.level.root":"INFO",
                               "eureka.client.service-url.defaultZone":"http://localhost:8761/eureka/",
                               "spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"redpanda:9092"}},
                    {"name":"https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
                     "source":{"spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"localhost:9092",
                               "spring.jpa.hibernate.ddl-auto":"validate","spring.jpa.show-sql":false}}]}
```

```bash
java .scratch/Http.java GET http://localhost:8888/gateway/local
```

Expected `HTTP 200`, with `application-local.yaml` carrying no broker key and `application.yaml` carrying `localhost:9092`.

Now the gateway itself, still the jar built in Task 1 and still with no broker address of its own. Start it as a **background** Bash task, noting the new `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
```

Expected `HTTP 200`, resolving from the profile file and showing the shared file underneath it:

```json
{"activeProfiles":["prod"],"defaultProfiles":["default"],
 "property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application-prod.yaml",
             "value":"redpanda:9092"},
 "propertySources":[... {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application-prod.yaml",
                         "property":{"value":"redpanda:9092"}},
                        {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
                         "property":{"value":"localhost:9092"}} ...]}
```

And the address the Kafka client was actually handed:

```bash
grep -c 'bootstrap.servers = \[redpanda:9092\]' GATEWAY_LOG
```

Expected: a number greater than `0`. This is the decisive measurement in the whole issue.

```bash
grep -c 'bootstrap.servers = \[localhost:9092\]' GATEWAY_LOG
```

Expected: `0`.

Confirm the ERROR that follows is the expected DNS failure and not something else, and that the application started anyway (finding C):

```bash
grep -E "Couldn't resolve server redpanda:9092|No resolvable bootstrap urls|Started GatewayApplication" GATEWAY_LOG
```

Expected all three lines, `Started GatewayApplication` among them.

Confirm no module file changed to get here:

```bash
git status --short
```

Expected: `.scratch/` untracked and nothing else.

**Stop the gateway background task.**

- [ ] **Step 5: Nothing to commit on `issue-54`**

This cycle's green lives entirely in the config repository and was pushed in Step 3. There is no module change, so there is no commit on `issue-54` and `git status --short` must still show only `.scratch/`. Do not manufacture one.

```bash
git -C .scratch/config-repo log --oneline origin/config -2
```

Expected two commits, `chore(config): give prod its own kafka broker address` above `chore(config): hold the kafka broker address for every config client`.

---

## Task 3: The config server URI reads `CONFIG_SERVER_URI`

Covers acceptance criteria 5, 6 and the URI half of 8. Redpanda and the gateway's bus are irrelevant here; what matters is which address the config client dials before anything else has started.

**Files:**
- Modify: `gateway/src/main/resources/application.yaml:10` — the `spring.config.import` line

**Interfaces:**
- Consumes: from Task 1, `.scratch/Http.java` and `.scratch/Run.java`, and the gateway with no broker address of its own. From issue #13, `spring.config.import: "configserver:"` on the gateway and the `config-server` jar.
- Produces: `spring.config.import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}"` on the gateway, and with it the run-time knob `CONFIG_SERVER_URI`. Task 4 asserts the final shape of the file.

- [ ] **Step 1: Write the failing test**

Criterion 5 is only observable when the default address is wrong, so **stop the `config-server` background task from Task 1** and start one on a non-default port instead, as a **background** Bash task, noting the new `CONFIG_LOG`. Exactly one config server runs during this task.

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --server.port=8889
```

```bash
java .scratch/Http.java GET http://localhost:8889/gateway/local
```

Expected: `HTTP 200`. Nothing is listening on 8888.

Now start the gateway, in the **foreground** so the failure is visible and the process exits, with `CONFIG_SERVER_URI` set and nothing else changed:

```bash
java .scratch/Run.java CONFIG_SERVER_URI http://localhost:8889 -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

- [ ] **Step 2: Run it to verify it fails**

Expected: the gateway ignores the variable, dials the address baked into `configserver:`, and fails. Criterion 5 fails.

```
org.springframework.cloud.config.client.ConfigClientFailFastException:
  Could not locate PropertySource and the resource is not optional, failing
Caused by: org.springframework.web.client.ResourceAccessException:
  I/O error on GET request for "http://localhost:8888/gateway/default": Connection refused (connect failed)
Caused by: java.net.ConnectException: Connection refused (connect failed)
```

The `8888` in that URL is the red. `/gateway/default` rather than `/gateway/local` is expected and is not a bug: Spring Boot processes config data twice, once before profiles are known and once after, and the first attempt is the one that fails here (finding 1).

- [ ] **Step 3: Write the minimal implementation**

Edit `gateway/src/main/resources/application.yaml`, changing one line and nothing else. The quotes are mandatory: a scalar ending in a colon is a mapping key to SnakeYAML and the application will not parse its own configuration without them.

```yaml
  config:
    import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}"
```

For the avoidance of doubt, the whole file now reads:

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway
  profiles:
    active: local
  config:
    import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}"
  cloud:
    stream:
      binders:
        bus:
          type: kafka
      bindings:
        springCloudBusInput:
          binder: bus
        springCloudBusOutput:
          binder: bus
    gateway:
      server:
        webmvc:
          routes:
            - id: catalog
              uri: lb://catalog-service
              predicates:
                - Path=/catalog/**
              filters:
                - StripPrefix=1

management:
  endpoint:
    env:
      show-values: ALWAYS
  endpoints:
    web:
      exposure:
        include: env
```

- [ ] **Step 4: Run it to verify criterion 5 passes**

```bash
mvn -B -pl gateway clean package
```

Expected: `BUILD SUCCESS`.

Start the gateway as a **background** Bash task with the variable set, noting the new `GATEWAY_LOG`:

```bash
java .scratch/Run.java CONFIG_SERVER_URI http://localhost:8889 -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

```bash
grep -E 'Fetching config from server at|Started GatewayApplication' GATEWAY_LOG
```

Expected four lines, every fetch naming 8889 and none naming 8888:

```
INFO ... o.s.c.c.c.ConfigServerConfigDataLoader : Fetching config from server at : http://localhost:8889
INFO ... o.s.c.c.c.ConfigServerConfigDataLoader : Fetching config from server at : http://localhost:8889
INFO ... c.t.gateway.GatewayApplication         : Started GatewayApplication in N seconds
```

```bash
grep -c 'localhost:8888' GATEWAY_LOG
```

Expected: `0`.

Confirm the configuration really arrived from 8889, so that "started" is not "started with nothing":

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
```

Expected `HTTP 200` with `"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml"`, unchanged from Task 1 Step 6 — the address of the config server moved, what it serves did not.

**Stop the gateway background task and the 8889 config server background task.**

- [ ] **Step 5: Run it to verify criterion 6 passes**

With the variable unset the gateway must still start against a config server on the default address. Start `config-server` as a **background** Bash task with no port override, noting the new `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Start the gateway as a second **background** Bash task, launched plainly, with `CONFIG_SERVER_URI` exported nowhere:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

```bash
grep -E 'Fetching config from server at|Started GatewayApplication' GATEWAY_LOG
```

Expected every fetch to name `http://localhost:8888`, followed by `Started GatewayApplication`.

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
```

Expected `HTTP 200` with `"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml"`.

Criterion 8, URI half — the config repository names no config server address:

```bash
grep -rnc '8888\|spring.cloud.config.uri\|CONFIG_SERVER_URI' .scratch/config-repo
```

Expected: `0` for every one of the six files. `grep -c` exits 1 on a zero count; read the printed numbers (finding E).

```bash
grep -rn 'configserver\|8888' gateway/src
```

Expected exactly one line, `gateway/src/main/resources/application.yaml` line 10, holding the placeholder and its default.

**Stop both background tasks.**

- [ ] **Step 6: Commit**

```bash
git status --short
```

Expected exactly: `M gateway/src/main/resources/application.yaml`, plus `.scratch/` untracked.

```bash
git add gateway/src/main/resources/application.yaml
```

```bash
git commit -m "$(cat <<'EOF'
chore(gateway): resolve the config server uri from CONFIG_SERVER_URI

Refs #54
EOF
)"
```

---

## Task 4: `config-server` is untouched, and the tree is clean

Covers acceptance criterion 7 in full — the half about the config repository being unreachable, which no earlier task exercises — and ends with a green gate, a stopped container and a clean working tree. It ends with no commit.

**Files:**
- Remove: the `.scratch/config-repo/` worktree and the local `config` branch
- Delete: `.scratch/`

**Interfaces:**
- Consumes: everything above. Produces: nothing.

- [ ] **Step 1: Write the failing test for criterion 7**

There is no red here in the ordinary sense, because criterion 7 asserts that something did *not* change. The check that gives it teeth is the positive control that would have caught the mistake this task guards against — moving `config-server`'s broker address along with the gateway's. Record the two facts first:

```bash
git diff master...HEAD --name-only
```

Expected exactly two paths, and `config-server/src/main/resources/application.yaml` must not be among them:

```
docs/superpowers/plans/2026-09-10-bus-broker-address-and-config-server-uri-in-the-gateway.md
gateway/src/main/resources/application.yaml
```

```bash
grep -n 'brokers' config-server/src/main/resources/application.yaml
```

Expected exactly line 26, `                      brokers: localhost:9092`, byte-identical to `master`.

- [ ] **Step 2: Run it to verify criterion 7 passes against a running server**

Redpanda must still be running from Task 1 Step 2; confirm it before measuring, since the point of this check is that the *config repository* is unreachable, not the broker:

```bash
docker ps
```

Expected: one row, `redpanda`.

Start `config-server` as a **background** Bash task with its git URI pointed at a host that does not resolve, noting `CONFIG_LOG`. This is the observable equivalent of the config repository being unreachable; the network cannot be unplugged here.

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --spring.cloud.config.server.git.uri=https://unreachable.invalid/ecommerce.git
```

```bash
grep -E 'Creating binder: bus|Subscribed to topic\(s\): springCloudBus|Tomcat started on port 8888|Started ConfigServerApplication' CONFIG_LOG
```

Expected all four lines. `config-server` starts, joins the bus and listens, with no config repository behind it:

```
INFO ... o.s.c.s.binder.DefaultBinderFactory : Creating binder: bus
INFO ... o.a.k.c.c.i.ClassicKafkaConsumer    : ... Subscribed to topic(s): springCloudBus
INFO ... o.s.boot.tomcat.TomcatWebServer     : Tomcat started on port 8888 (http) with context path '/'
INFO ... c.t.config.ConfigServerApplication  : Started ConfigServerApplication in N seconds
```

And that it used its own address, not one it could not possibly have fetched:

```bash
grep -c 'bootstrap.servers = \[localhost:9092\]' CONFIG_LOG
```

Expected: a number greater than `0`.

**Stop the background task.**

- [ ] **Step 3: Remove the container and the worktree**

```bash
docker rm -f redpanda
```

Expected: `redpanda`.

```bash
git worktree remove .scratch/config-repo
```

```bash
git branch -D config
```

```bash
git worktree list
```

Expected: one line only, the main working tree at `/home/runner/work/ecommerce/ecommerce`.

- [ ] **Step 4: Delete the scratch directory**

`.scratch/` is not covered by `.gitignore`, so it must be removed rather than left untracked.

```bash
rm -rf .scratch
```

```bash
git status --short
```

Expected: **no output at all**.

- [ ] **Step 5: Confirm the gate and the shape of both branches**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, reactor listing `ecommerce`, `discovery-server`, `gateway` and `config-server`, with `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` for `gateway` — `GatewayExceptionHandlerTest`, unchanged — and `No tests to run.` for `discovery-server` and `config-server`, which have no test sources. That is the same output `master` produces today, measured before planning. No test was added or removed by this issue.

```bash
git diff --stat master...HEAD
```

Expected exactly two entries, the plan committed before implementation began and the one module file this issue edits:

```
 docs/superpowers/plans/2026-09-10-bus-broker-address-and-config-server-uri-in-the-gateway.md
 gateway/src/main/resources/application.yaml
```

```bash
git log --oneline master..HEAD
```

Expected three commits: `chore(gateway): resolve the config server uri from CONFIG_SERVER_URI`, `chore(gateway): read the kafka broker address from the config repository`, and `docs(plan): implementation plan for #54`.

```bash
gh api /repos/TheDarkHorse111/ecommerce/git/trees/config --jq '.tree[].path'
```

Expected exactly, in some order: `application-local.yaml`, `application-prod.yaml`, `application.yaml`, `catalog-service-local.yaml`, `catalog-service-prod.yaml`, `catalog-service.yaml`. Six files, the same six as before; two of them changed content and none was added or removed.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #54 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1 | The gateway starts with no broker address in its own `application.yaml` | 1 | 1.4, 1.6 | `grep -c 'brokers: localhost:9092' gateway/src/main/resources/application.yaml` prints `1` | prints `0`, and `Started GatewayApplication` is in `GATEWAY_LOG` |
| 2 | The broker address is read from the config repository's shared `application.yaml` | 1 | 1.4, 1.6 | `/actuator/env/<key>` reports `"source":"Config resource 'class path resource [application.yaml]' …"` | `"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml"` |
| 3 | `prod` and `local` resolve different broker addresses without a module file changing | 2 | 2.2, 2.4 | under `prod`, `"source":"…/application.yaml","value":"localhost:9092"` — identical to `local` | under `prod`, `"source":"…/application-prod.yaml","value":"redpanda:9092"` and `bootstrap.servers = [redpanda:9092]`; `git status --short` shows no module change |
| 4 | A refresh broadcast over the bus still reaches the gateway | 1 | 1.4, 1.6 | with the gateway stopped in 1.4 nothing consumes the topic | `POST /actuator/busrefresh` → `HTTP 204`, then `Received remote refresh request.` and `Keys refreshed []` on thread `container-0-C-1` in `GATEWAY_LOG` |
| 5 | The gateway reaches a config server at a non-default address with only `CONFIG_SERVER_URI` set | 3 | 3.2, 3.4 | `ConfigClientFailFastException … I/O error on GET request for "http://localhost:8888/gateway/default": Connection refused` with the variable set to 8889 | every `Fetching config from server at` names `http://localhost:8889`, `grep -c 'localhost:8888'` prints `0`, `Started GatewayApplication` |
| 6 | With the variable unset, the gateway still starts against a config server on `localhost:8888` | 3 | 3.5 | 3.2's failure is the same command against an empty 8888, proving the default is dialled | with a server on 8888 and no variable: fetches name `http://localhost:8888`, `Started GatewayApplication`, `/actuator/env/<key>` sourced from the config server |
| 7 | config-server keeps its local broker address and still starts with the config repository unreachable | 1, 4 | 1.6, 4.1, 4.2 | `git diff master...HEAD --name-only` must not list `config-server/…`; `grep -n 'brokers'` must still print line 26 | with `--spring.cloud.config.server.git.uri=https://unreachable.invalid/ecommerce.git`: `Creating binder: bus`, `Subscribed to topic(s): springCloudBus`, `Tomcat started on port 8888`, `Started ConfigServerApplication`, `bootstrap.servers = [localhost:9092]` |
| 8 | No key naming either address exists in both the module and the config repository | 1, 3 | 1.6, 3.5 | before 1.6 the broker key is in the module *and* about to be in the repository | `grep -rn 'brokers' gateway/src .scratch/config-repo` prints one line, from the repository; `grep -rnc '8888\|spring.cloud.config.uri\|CONFIG_SERVER_URI' .scratch/config-repo` prints `0` for all six files; `grep -rn 'configserver\|8888' gateway/src` prints one line, the placeholder |

Criterion 8 is read as scoped to the gateway. `config-server` keeps a `brokers` key while the config repository also holds one, and the issue's own Design block requires exactly that; see "Acceptance criterion 8 and the copy `config-server` keeps" above.

The issue's Design block is covered too, and three sentences deserve naming because no criterion tests them:

- **"The binder name, the bindings and the binder type are identical everywhere and carry no secret, so they stay in the module; only the address moves."** — Task 1 Step 5 shows the gateway file in full with `bus`, `type: kafka` and both bindings intact and only the `environment` subtree gone. Task 4 Step 5's two-file diff is the check.
- **"The default keeps `mvn -B clean verify` and a local run working with nothing exported."** — Task 3 Step 5 is the local run and Task 4 Step 5 is the gate, both with `CONFIG_SERVER_URI` set nowhere.
- **"config-server keeps its own broker address locally for the same reason as the URI: it cannot fetch its configuration from the thing it is."** — Task 4 Steps 1 and 2.

---

## Pull request

Title: `chore(gateway): move the bus broker address and the config server uri out of the module (#54)`

Body lists each of the eight acceptance criteria with the command and the output that verified it, and ends with `Closes #54`.

Four things must be in the body, because a reviewer reading only the diff will not see any of them:

- **Half the change is on the `config` branch and is not in this diff.** `application.yaml` on `config` gains `spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers: localhost:9092`, and `application-prod.yaml` overrides it with `redpanda:9092`. Two commits, `chore(config): hold the kafka broker address for every config client` and `chore(config): give prod its own kafka broker address`, both already pushed. They take effect for every running config client at the next refresh or restart, independently of when this pull request merges.
- **The broker address goes in `application.yaml` with a `prod` override, not in the `application-local.yaml`/`application-prod.yaml` pair.** Issue #54 names `application.yaml` twice; its third acceptance criterion requires `prod` and `local` to differ. Only a base plus an override satisfies both. This makes the broker address the first profile-varying key in the repository that is not written twice into the profile pair the way `eureka.client.service-url.defaultZone` is, and it makes spec section 3's sentence "`prod` carries the same values as `local` except log level" out of date.
- **An absent broker address fails silently.** With the key in neither the module nor the repository, the Kafka binder falls back to its own default of `localhost:9092`, joins the bus and starts, which is byte-identical to success under `local`. Measured before the change. Every check in this issue therefore asserts on the `"source"` field of `/actuator/env`, and the `prod` run — `bootstrap.servers = [redpanda:9092]`, a value in no module file — is the control that proves the address genuinely travels from the repository to the binder.
- **`config-server` still hardcodes `brokers: localhost:9092` and still hardcodes nothing about its own URI, deliberately.** It is not a config client and cannot fetch its configuration from the thing it is. Confirmed to start, bind the `bus` binder and subscribe to `springCloudBus` with its git URI pointed at a host that does not resolve. The duplication with the config repository's key is real but inert: `config-server` never reads that file.

Two facts from issue #13 are unchanged and worth repeating so nobody reads this pull request as having fixed them: the gateway still will not start unless a config server answers, because the import is not `optional:`; and `management.endpoint.env.show-values: ALWAYS` still unmasks every value on the gateway's published port 8080, which is what makes criteria 2, 3, 5 and 6 observable at all.
