# Config refresh over Spring Cloud Bus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A push to the config repository refreshes a running service without restarting it, broadcast over Spring Cloud Bus on Redpanda, triggered either by a signed GitHub webhook at `/monitor` on the config server or by `POST /actuator/busrefresh`.

**Architecture:** `config-server` gains `spring-cloud-config-monitor`, `spring-cloud-starter-bus-kafka` and `spring-boot-starter-actuator`. The monitor contributes a `/monitor` endpoint that turns a GitHub push payload into one `RefreshRemoteApplicationEvent` per application whose files changed; the bus carries those events over a Kafka topic named `springCloudBus`, on a dedicated named binder that no future domain-event binder will share. `gateway` becomes the first config client: it imports `configserver:`, joins the same bus, and exposes `/actuator/env` so the refreshed value can be read. The only Java written is a servlet filter on `/monitor` that verifies GitHub's `X-Hub-Signature-256` HMAC, because the monitor library does not verify it — see finding 1.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Cloud 2025.1.2, `spring-cloud-config-monitor` 5.0.4, `spring-cloud-config-server` 5.0.4, `spring-cloud-starter-config` 5.0.4, `spring-cloud-bus` 5.0.2, `spring-cloud-starter-bus-kafka` 5.0.2, `spring-kafka` 4.1.0, `kafka-clients` 4.2.1, Redpanda v25.2.4 in Docker, Error Prone 2.50.0, maven-compiler-plugin 3.16.0.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` (section 3 "Configuration", section 5 "Messaging", section 6 dependency pins). Issue #13 is the specification for this increment. Issue #11 is its dependency and is planned in `docs/superpowers/plans/2026-09-09-config-server.md`; issue #12, the gateway, is planned in `docs/superpowers/plans/2026-09-09-gateway.md`. Both are merged.

---

## Global Constraints

Copied from `CLAUDE.md`, issue #13 and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. There is no separate lint step. Error Prone runs during compilation.
- **Versions come from the train.** No version appears in any module POM. `spring-cloud-dependencies` 2025.1.2 supplies monitor 5.0.4 and bus 5.0.2, exactly as the issue states (finding 2).
- **`spring-cloud-config-monitor` goes on the config server only.** `spring-cloud-starter-bus-kafka` goes on the config server and every config client. Today the only config client is `gateway`. `discovery-server` is not a config client and does not get the bus — see "Deliberate omissions".
- **Bus gets its own Kafka binder configuration**, separate from the binder that will one day carry domain events, per issue #13 and spec section 6 (`spring-cloud-bus` #267, #268). The binder is named `bus` and both bus bindings name it.
- **Annotations.** `@Component`, `@Service` and `@Repository` are forbidden. `@RestController` and `@RestControllerAdvice` are the only stereotypes and are controller-layer only. Every other bean is an `@Bean` method in an `@Configuration` class — that is how the signature filter is registered. `PropertyPathEndpoint`, the library class that carries `@RestController`, is not ours and is registered by the library's own `@Bean` method (finding 6).
- **TDD.** No class is written before a failing test for it has been seen to fail. TDD applies to code with behaviour. The one class in this issue with behaviour, `MonitorSignatureFilter`, has a branch, a comparison and an HTTP contract, so it gets a JUnit test first (Task 2). Everything else in this issue is dependency and YAML, which has no behaviour of ours, so its red steps are commands whose observed failure is recorded verbatim. **Never test the framework** — do not write a test asserting that the bus delivers a message or that `@Bean` registers a filter; the spec forbids a Spring context in tests outright.
- **Comments.** None. No Javadoc, no comment blocks, and none in `pom.xml`, `application.yaml` or any file of the backing repository. Rationale lives in the spec and in this plan.
- **Types and naming.** Lookups are prefixed `find`; mutations use verbs. The filter's private methods are `matches` and `sign`, neither a lookup nor a mutation.
- **Scope.** Build only what issue #13 asks for. In particular do **not** add Spring Security, a `management.server.port`, a Docker Compose file, a `@RefreshScope` demonstration bean, `@ConfigurationProperties` classes, a `spring.cloud.bus.id` override, `spring.cloud.bus.trace`, `spring.cloud.config.fail-fast`, `spring.cloud.config.server.monitor.*.enabled` flags, a route for `/actuator/**`, or bus dependencies on `discovery-server`. See "Deliberate omissions".
- **Every plugin carries an explicit version** — but neither module declares any plugin beyond `spring-boot-maven-plugin` with no version and no configuration, which the root `pluginManagement` supplies.
- **Conventional Commits**, scope `config` for `config-server` changes and `gateway` for `gateway` changes, one commit per completed red-green cycle, every commit green, each carrying a `Refs #13` footer. No `Co-Authored-By` trailer, no generated-with footer.
- **Pull request** title ends `(#13)`; body ends `Closes #13`. Merge with rebase, never squash.

---

## Verified Findings

Every claim issue #13 makes about tool behaviour was executed on this machine before planning on top of it, using throwaway modules at `.scratch/bus-probe/` and `.scratch/client-probe/` parented to the real root `pom.xml` by `<relativePath>` and never listed in `<modules>`, a throwaway bare git repository at `.scratch/backing.git`, and a Redpanda container. All were deleted afterwards.

**One claim turned out to be false, and it is the one the whole Security section rests on.** It is finding 1. Findings 2 to 9 are confirmations; findings A to F are things the issue does not say that the plan depends on.

### 1. FALSE: "`/monitor` … verifies GitHub's `X-Hub-Signature-256` HMAC against a shared secret, rejecting anything that fails"

`spring-cloud-config-monitor` 5.0.4 contains no HMAC code, no shared-secret property and no rejection path. The whole jar is nineteen classes:

```
$ unzip -l spring-cloud-config-monitor-5.0.4.jar
  BasePropertyPathNotificationExtractor.class      CompositePropertyPathNotificationExtractor.class
  BitbucketPropertyPathNotificationExtractor.class EnvironmentMonitorAutoConfiguration.class
  FileMonitorConfiguration.class                   GiteaPropertyPathNotificationExtractor.class
  GiteePropertyPathNotificationExtractor.class     GithubPropertyPathNotificationExtractor.class
  GitlabPropertyPathNotificationExtractor.class    GogsPropertyPathNotificationExtractor.class
  PropertyPathEndpoint.class                       PropertyPathNotification.class
  PropertyPathNotificationExtractor.class          ...
```

The endpoint reads the headers only to decide which forge sent the payload, and the GitHub extractor's entire header check is one string comparison on a *different* header:

```java
// GithubPropertyPathNotificationExtractor.java, from the 5.0.4 sources jar
protected boolean requestBelongsToGitRepoManager(HttpHeaders headers) {
    return "push".equals(headers.getFirst("X-Github-Event"));
}
```

Measured, not inferred. With the monitor on the classpath and no filter of our own, an unsigned request with no `X-Hub-Signature-256` header at all is accepted and broadcasts a refresh:

```
$ java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json - none
HTTP 200
["catalog-service-local","catalog-service","catalog"]
```

**Planned against what was observed.** The signature check is written by us, as a servlet `Filter` registered on `/monitor` by an `@Bean` method (Task 2). That is the only Java this issue adds, and it is the only part of this issue with a JUnit test. Everything else the issue claims about `/monitor` — the path, the payload shape, the per-application scoping — is true and is findings 3 and 4.

### Confirmed

2. **The pinned train resolves monitor 5.0.4 and bus 5.0.2**, exactly the versions the issue names. No version is written in any module POM.

   ```
   $ mvn -B -f .scratch/bus-probe/pom.xml dependency:tree -Dincludes=org.springframework.cloud:*,org.apache.kafka:*,org.springframework.kafka:*
   +- org.springframework.cloud:spring-cloud-config-server:jar:5.0.4:compile
   |  \- org.springframework.cloud:spring-cloud-config-client:jar:5.0.4:compile
   +- org.springframework.cloud:spring-cloud-config-monitor:jar:5.0.4:compile
   |  \- org.springframework.cloud:spring-cloud-bus:jar:5.0.2:compile
   \- org.springframework.cloud:spring-cloud-starter-bus-kafka:jar:5.0.2:compile
      \- org.springframework.cloud:spring-cloud-starter-stream-kafka:jar:5.0.2:compile
         \- org.springframework.cloud:spring-cloud-stream-binder-kafka:jar:5.0.2:compile
            +- org.springframework.cloud:spring-cloud-stream:jar:5.0.2:compile
            \- org.springframework.kafka:spring-kafka:jar:4.1.0:compile
               \- org.apache.kafka:kafka-clients:jar:4.2.1:compile
   ```

   On the client side, `spring-cloud-starter-config` resolves 5.0.4 and brings `spring-cloud-config-client` 5.0.4.

3. **`/monitor` is at `/monitor`, is `POST`-only, and needs `X-Github-Event: push`.** The mapping is `@RequestMapping(path = "${spring.cloud.config.monitor.endpoint.path:}/monitor")`, so with the prefix property unset the path is exactly `/monitor`. The payload the extractor reads is GitHub's `commits[].added|removed|modified`:

   ```json
   {"commits":[{"added":[],"removed":[],"modified":["catalog-service-local.yaml"]}]}
   ```

   A payload with no `commits` array, or without the `X-Github-Event: push` header, returns `HTTP 200 []` and publishes nothing.

4. **Scoping works exactly as the issue describes, and the destination strings are derived from the file names.** `PropertyPathEndpoint.guessServiceName` strips the extension and then strips one dash-delimited suffix at a time, mapping the stem `application` to `*`. Measured:

   ```
   $ java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json probe-secret
   HTTP 200
   ["catalog-service-local","catalog-service","catalog"]

   $ java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-application.json probe-secret
   HTTP 200
   ["*"]
   ```

   `RemoteApplicationEvent` then passes each name through `PathDestinationFactory`, which appends `:**` when the name holds at most one colon, so the events on the wire carry `catalog-service:**` and `*:**`.

5. **A bus id is `name:profiles:port:random`, and that is what the destination is matched against.** Observed on the wire, in the gateway's log:

   ```
   Received remote event from bus: [RefreshRemoteApplicationEvent
     originService = 'config-server:local:8888:a96e12eabd13b68d1292c44f9340dff8',
     destinationService = 'catalog-service:**']
   ```

   `spring.cloud.bus.id` is not set by us; `BusEnvironmentPostProcessor` derives it. It does **not** need setting for any criterion here.

6. **`PropertyPathEndpoint` is a library `@Bean`, not a component scan target.** `EnvironmentMonitorAutoConfiguration.BusPropertyPathConfiguration` declares it with `@Bean`. Its `@RestController` annotation is inside the library and is not our stereotype to police.

7. **`/actuator/busrefresh` is the endpoint id and answers 204.** `RefreshBusEndpoint` is `@Endpoint(id = "busrefresh")`. With `management.endpoints.web.exposure.include` naming it:

   ```
   $ java .scratch/Http.java GET http://localhost:8888/actuator
   HTTP 200
   {"_links":{... "busrefresh":{"href":"http://localhost:8888/actuator/busrefresh"},
    "busrefresh-destinations":{"href":"http://localhost:8888/actuator/busrefresh/{*destinations}","templated":true}}}

   $ java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh -
   HTTP 204
   ```

8. **The whole loop works, with no restart.** Config server plus one config client, both on a named `bus` Kafka binder against Redpanda. `spring.jpa.hibernate.ddl-auto` was changed from `validate` to `none` in the backing repository and pushed, then a signed `/monitor` POST naming `application.yaml` was made. The client, untouched, logged:

   ```
   INFO ... o.s.cloud.bus.event.RefreshListener : Received remote refresh request.
   INFO ... o.s.cloud.bus.event.RefreshListener : Keys refreshed [config.client.version, spring.jpa.hibernate.ddl-auto]
   ```

   and `/actuator/env` on the client went from `"value":"validate"` to `"value":"none"` against the same process.

9. **`POST /actuator/busrefresh` on one participant refreshes the other, in both directions.** POSTing on the client (8080) made the config server log `Received remote refresh request.` on its Kafka listener thread `container-0-C-1`; POSTing on the config server (8888) did the same on the client. Both then logged `Keys refreshed [...]`.

### Six things the issue does not say

**A. `/actuator/env` masks every value by default, so criterion 2 is unobservable without one more property.**

Boot 4's env endpoint defaults `management.endpoint.env.show-values` to `NEVER`. The first reading of the very key the acceptance criteria are about came back as:

```json
{"property":{"source":"configserver:.../application.yaml","value":"******"}}
```

With `management.endpoint.env.show-values: ALWAYS` the same request returns `"value":"validate"`. The property is therefore set on `gateway`, and criterion 2 is why. It also unmasks everything else the gateway's environment holds; see "Known exposure".

**B. `spring.config.import: configserver:` is not valid YAML unquoted.**

Issue #11's Design block writes it bare. A scalar ending in a colon is a mapping key to SnakeYAML, and the application does not start:

```
mapping values are not allowed here
 in 'reader', line 10, column 25:
        import: configserver:
                            ^
```

It must be written `import: "configserver:"`. That form was then measured to work and to default to `http://localhost:8888`.

**C. Once the gateway imports `configserver:` it will not start without the config server.**

The import is not `optional:`, so an unreachable config server is a startup failure, not a warning:

```
org.springframework.cloud.config.client.ConfigClientFailFastException:
  Could not locate PropertySource and the resource is not optional, failing
```

This plan keeps the non-optional form, because that is what issue #11's Design block specifies and because a gateway that silently starts with none of its configuration is worse. It is a real operational consequence and the pull request must say so: **from this issue on, `config-server` must be up before `gateway`.**

**D. A bus participant receives every message on the topic and filters locally; "not refreshed" is not "not delivered".**

`BusConsumer` logs every arrival at DEBUG and only then applies `ServiceMatcher.isForSelf`. For the catalog-only push the gateway logged three DEBUG arrivals and no refresh:

```
DEBUG ... o.springframework.cloud.bus.BusConsumer : Received remote event from bus:
  [RefreshRemoteApplicationEvent ... destinationService = 'catalog-service-local:**']
  [RefreshRemoteApplicationEvent ... destinationService = 'catalog-service:**']
  [RefreshRemoteApplicationEvent ... destinationService = 'catalog:**']
```

Criterion 4's assertion is therefore about `RefreshListener`, not about the topic. Those DEBUG lines are visible for free, because `application-local.yaml` in the config repository sets `logging.level.root: DEBUG` and the gateway now reads it.

**E. The publishing instance logs `Received remote refresh request.` even for an event addressed elsewhere.**

`RefreshListener.onApplicationEvent` logs that line before checking the destination, and then logs one of two follow-ups. On the config server, for the catalog-only push:

```
INFO ... RefreshListener : Received remote refresh request.
INFO ... RefreshListener : Refresh not performed, the event was targeting catalog:**
```

So `Received remote refresh request.` alone proves nothing; `Keys refreshed [...]` is the line that means a refresh happened, and `Refresh not performed, the event was targeting …` is the line that means it did not. Every assertion in this plan uses those two.

**F. `POST /actuator/busrefresh` on the current config server answers 405, not 404.**

There is no actuator on it yet, but `EnvironmentController` maps `/{name}/{profiles}/{label}` for GET, and `/actuator/busrefresh` matches that shape:

```
$ java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh -
HTTP 405
{"timestamp":"...","status":405,"error":"Method Not Allowed","path":"/actuator/busrefresh"}
```

Task 1's red is that 405. Do not "fix" it by expecting a 404.

### Tooling constraints

**There is no `curl` and no `wget`,** and several checks need a POST with computed headers. `Bash(java:*)` is on the implementing job's allowlist and Java's single-file source launcher runs a `.java` file directly, so **Task 1 Step 1 creates `.scratch/Http.java`** and every HTTP check in this plan runs through it. That exact file produced every measurement above.

**`Bash(docker:*)` is on the allowlist and Redpanda runs.** Every runtime check in Tasks 1 and 3 to 5 needs a broker on `localhost:9092`.

**Background tasks.** Start each server as a **background** Bash task and stop it with the harness's background-task stop; `kill` and `pkill` are not on the allowlist. Only one process may hold a port at a time. When a background task starts, the harness reports the file its output goes to, for example `/tmp/.../tasks/bx062i93h.output`. Steps below write `CONFIG_LOG` and `GATEWAY_LOG` for those files — **substitute the real paths the harness printed**, and note they are reassigned every restart. Tasks 3 to 5 need both servers up at once; that is two background tasks, which is fine.

**`grep -c` exits 1 when the count is zero.** Several steps expect it to print `0`. Read the printed number, not the exit status.

### Deliberate omissions

- **No bus on `discovery-server`.** The issue says the bus goes on "the config server and every client". `discovery-server` is not a config client — it has no `spring.config.import` and issue #10 gave it none. No acceptance criterion mentions it. Adding it would be an unrequested change to a merged module.
- **No `/actuator/busrefresh` on the gateway.** The issue's Security section says `/actuator/busrefresh` "is not routed through the gateway and is not reachable from outside the network". The gateway's own port 8080 *is* the published one, so exposing the endpoint there would contradict that sentence directly. It is exposed on `config-server` only, on 8888, which the gateway has no route to. Criterion 3's "one service" is therefore the config server, and finding 9 shows the refresh reaches the gateway from there. The gateway still consumes bus events; only the web exposure of its own endpoint is withheld.
- **No `management.server.port`.** Not requested, and unnecessary once `busrefresh` is not on the gateway.
- **No Docker Compose file.** Spec section 6 says each service owns one; none of `discovery-server`, `config-server` or `gateway` has one yet and issue #13 does not ask for one. Redpanda is started with `docker run` in the checks below and stopped in Task 6.
- **No `@RefreshScope` bean and no `@ConfigurationProperties` class.** The issue's Design block states the rule that `@RefreshScope` goes on `@Bean` methods; it does not ask for a bean to demonstrate it, and inventing one would be a helper class nobody requested. Criteria 1 and 2 are one scenario and its observation: the running service reports the changed value through `/actuator/env`, and the `Keys refreshed [...]` line names the key that moved.
- **No `spring.profiles.active` on `config-server`.** It is not a config client, so it has no profile-specific configuration to select. Its bus id becomes `config-server:8888:<random>`, which the `*:**` destination still matches (finding 5).
- **No Spring Security.** The signature check is one filter; a security starter would pull an authentication model nobody asked for.

### Known exposure, stated rather than hidden

`management.endpoint.env.show-values: ALWAYS` on the gateway unmasks every value in `/actuator/env`, and the gateway's actuator is on its published port 8080. Criterion 2 requires the change to be "visible through `/actuator/env` on the client", and there is no way to satisfy it while masking. The shared configuration files the gateway reads — `application.yaml` and `application-local.yaml` — carry no credentials today; the datasource credentials live in `catalog-service-local.yaml`, which the gateway does not read. The fix is a management port or Spring Security, and it belongs with the secret vault the spec already defers to "once there is somewhere to deploy". The pull request must name this.

### Configuration placement, and one deviation from the spec

Spec section 3 lists "Redpanda hosts" among the keys that belong in the config repository because they vary by profile. The Kafka binder configuration in this issue lives in **each module's own `application.yaml` instead**, identically in both. The reason is that `config-server` is not a config client and cannot read its own broker address from the repository it serves; putting the gateway's copy in the repository and the config server's copy in the module would mean the same address maintained in two places, which is the "two places to disagree" failure the standing rules reject everywhere else. When a domain-event binder arrives on a service that is purely a client, spec section 3 applies to it unchanged. The pull request must name this too.

---

## File Structure

Committed to `TheDarkHorse111/ecommerce`, branch `issue-13`:

| Path | Responsibility | Action |
| --- | --- | --- |
| `config-server/pom.xml` | Gains `spring-cloud-config-monitor`, `spring-cloud-starter-bus-kafka`, `spring-boot-starter-actuator` and a test-scoped `spring-boot-starter-test`. | Modify |
| `config-server/src/main/resources/application.yaml` | Gains the `bus` Kafka binder block and the `busrefresh` actuator exposure. | Modify |
| `config-server/src/main/java/com/thedarkhorse/config/config/MonitorSignatureFilter.java` | Verifies `X-Hub-Signature-256` over the raw body and re-serves the body downstream. The only class in this issue with behaviour. | Create |
| `config-server/src/main/java/com/thedarkhorse/config/config/MonitorSecurityConfiguration.java` | One `@Bean` method registering that filter on `/monitor`. | Create |
| `config-server/src/test/java/com/thedarkhorse/config/config/MonitorSignatureFilterTest.java` | Three cases: absent signature, wrong signature, matching signature. | Create |
| `gateway/pom.xml` | Gains `spring-cloud-starter-config`, `spring-cloud-starter-bus-kafka` and `spring-boot-starter-actuator`. | Modify |
| `gateway/src/main/resources/application.yaml` | Gains `spring.profiles.active`, `spring.config.import`, the `bus` binder block and the `env` actuator exposure with `show-values`. | Modify |

`com.thedarkhorse.config.config` reads awkwardly, and it is deliberate: the spec's package layout is "per service, rooted at `com.thedarkhorse.<service>`" with `config/` holding `@Configuration` classes, and this service is called `config`. Applying the layout literally is preferable to carving an exception for one module. Both classes go there — the filter is a bean-definition detail of the monitor endpoint, and this module has no controller layer of its own to put it in.

Not created: `gateway/src/test/` gains nothing. No Java with behaviour is added to the gateway.

Not touched: `pom.xml`, `discovery-server/`, `lombok.config`, `.mvn/`, `.github/`, `CLAUDE.md`, `README.md`, the spec, any other plan, and the `config` branch's final tree — Tasks 4 and 5 push to it and revert, leaving it byte-identical.

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/Http.java` | The HTTP client, because the allowlist has none. Created in Task 1, deleted in Task 6. |
| `.scratch/push-application.json` | A GitHub push payload naming `application.yaml`. |
| `.scratch/push-catalog.json` | A GitHub push payload naming `catalog-service-local.yaml`. |
| `.scratch/config-repo/` | A git worktree on the `config` branch. Created in Task 4, removed in Task 6. |

---

## Task 1: The config server gains `/monitor` and the bus

Covers no acceptance criterion on its own; criteria 1 to 5 all consume it. Ends with `/monitor` accepting an **unsigned** payload, which is precisely Task 2's red.

**Files:**
- Modify: `config-server/pom.xml:15-24` — the `<dependencies>` element
- Modify: `config-server/src/main/resources/application.yaml`
- Create: `.scratch/Http.java`, `.scratch/push-application.json`, `.scratch/push-catalog.json`

**Interfaces:**
- Consumes: from issue #11, the module `com.thedarkhorse:config-server:0.0.1-SNAPSHOT`, its jar at `config-server/target/config-server-0.0.1-SNAPSHOT.jar`, `spring.application.name: config-server`, and the `config` branch of `https://github.com/TheDarkHorse111/ecommerce.git` holding the six configuration files. From issue #1, the root `pluginManagement` and `.mvn/jvm.config`.
- Produces: `POST http://localhost:8888/monitor` accepting `{"commits":[{"added":[],"removed":[],"modified":["<file>"]}]}` with header `X-Github-Event: push` and answering a JSON array of destination names; `POST http://localhost:8888/actuator/busrefresh` answering 204; a Kafka topic `springCloudBus` on `localhost:9092`. `.scratch/Http.java` exposes `Http.main(String[])`, invoked as `java .scratch/Http.java <GET|POST> <url> [bodyFile|-] [secret|-] [signatureOverride]`; Tasks 2 to 5 use it and Task 6 deletes it.

- [ ] **Step 1: Write the failing test**

Two pieces. First the tools, because the allowlist has no HTTP client and the signature has to be computed. Create `.scratch/Http.java` exactly as below. It retries the connection for up to two minutes so it can be launched before a server is up. `-` for the body file means an empty body; `-` or an absent secret means no signature is computed; a fifth argument overrides the signature outright, and the literal `none` suppresses the header entirely. No comments, consistent with the standing rule; it is a throwaway harness and needs no test of its own.

```java
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Http {

    public static void main(String[] args) throws Exception {
        String method = args[0];
        String url = args[1];
        byte[] body = args.length > 2 && !args[2].equals("-") ? Files.readAllBytes(Path.of(args[2])) : new byte[0];
        String secret = args.length > 3 && !args[3].equals("-") ? args[3] : null;
        String override = args.length > 4 ? args[4] : null;

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");

        if (method.equals("POST")) {
            builder.header("Content-Type", "application/json");
            builder.header("X-Github-Event", "push");
            String signature = override != null ? override : (secret != null ? sign(secret, body) : null);
            if (signature != null && !signature.equals("none")) {
                builder.header("X-Hub-Signature-256", signature);
            }
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
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

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
```

Create `.scratch/push-application.json`:

```json
{"commits":[{"added":[],"removed":[],"modified":["application.yaml"]}]}
```

Create `.scratch/push-catalog.json`:

```json
{"commits":[{"added":[],"removed":[],"modified":["catalog-service-local.yaml"]}]}
```

Second, the check. Build and start the config server exactly as issue #11 left it:

```bash
mvn -B -pl config-server clean package
```

Start it as a **background** Bash task and note the output path as `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Then:

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json - none
```

- [ ] **Step 2: Run it to verify it fails**

Expected — Spring MVC has no handler for a POST to `/monitor`:

```
HTTP 404
{"timestamp":"...","status":404,"error":"Not Found","path":"/monitor"}
```

And the other half of the red:

```bash
java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh -
```

Expected — **405, not 404**, because the config server's own `/{name}/{profiles}/{label}` GET mapping matches that path shape (finding F):

```
HTTP 405
{"timestamp":"...","status":405,"error":"Method Not Allowed","path":"/actuator/busrefresh"}
```

**Stop the background task.**

- [ ] **Step 3: Start Redpanda**

The bus needs a broker before the module will start cleanly. This is a `docker run`, not a committed Compose file — see "Deliberate omissions".

```bash
docker run -d --name redpanda -p 9092:9092 -p 9644:9644 docker.redpanda.com/redpandadata/redpanda:v25.2.4 redpanda start --mode dev-container --smp 1 --kafka-addr PLAINTEXT://0.0.0.0:9092 --advertise-kafka-addr PLAINTEXT://localhost:9092
```

Confirm it is ready before going on:

```bash
docker exec redpanda rpk cluster info
```

Expected: a `BROKERS` table with one row, `0*  localhost  9092`. If the command errors, wait and run it again; the container takes a few seconds.

- [ ] **Step 4: Write the minimal implementation**

Edit `config-server/pom.xml` so `<dependencies>` reads in full. No versions; the Spring Cloud BOM supplies monitor 5.0.4 and bus 5.0.2, and the Spring Boot BOM supplies the actuator.

```xml
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-monitor</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bus-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
```

Edit `config-server/src/main/resources/application.yaml` so it reads in full. The `bus` binder is named and both bus bindings point at it, which is what keeps a future domain-event binder from inheriting the Kafka starter's byte-array serializers. Only `busrefresh` is exposed; `env` is not, because nothing here reads it.

```yaml
server:
  port: 8888

spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        git:
          uri: https://github.com/TheDarkHorse111/ecommerce.git
          default-label: config
    stream:
      binders:
        bus:
          type: kafka
          environment:
            spring:
              cloud:
                stream:
                  kafka:
                    binder:
                      brokers: localhost:9092
      bindings:
        springCloudBusInput:
          binder: bus
        springCloudBusOutput:
          binder: bus

management:
  endpoints:
    web:
      exposure:
        include: busrefresh
```

- [ ] **Step 5: Run it to verify it passes**

```bash
mvn -B -pl config-server clean package
```

Expected: `BUILD SUCCESS`, with `compiler:3.16.0:compile` and `spring-boot:4.1.0:repackage` both firing and no `forked`.

Start it as a **background** Bash task and note the new `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Confirm the named binder was built and the topic was joined:

```bash
grep -E 'Creating binder: bus|Subscribed to topic\(s\): springCloudBus|Started ConfigServerApplication' CONFIG_LOG
```

Expected three lines:

```
INFO ... o.s.c.s.binder.DefaultBinderFactory : Creating binder: bus
INFO ... o.a.k.c.c.i.ClassicKafkaConsumer    : ... Subscribed to topic(s): springCloudBus
INFO ... c.t.c.ConfigServerApplication       : Started ConfigServerApplication in N seconds
```

Then the endpoint that was a 404:

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json - none
```

Expected — and note this request carries **no signature at all**, which is the vulnerability Task 2 closes:

```
HTTP 200
["catalog-service-local","catalog-service","catalog"]
```

Then the endpoint that was a 405:

```bash
java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh -
```

Expected:

```
HTTP 204
```

Confirm the config endpoints still work with three more starters on the classpath:

```bash
java .scratch/Http.java GET http://localhost:8888/catalog-service/local | head -1
```

Expected: `HTTP 200`.

**Stop the background task.**

- [ ] **Step 6: Confirm the gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, reactor listing `ecommerce`, `discovery-server`, `gateway`, `config-server`.

```bash
git status --short
```

Expected exactly: `M config-server/pom.xml`, `M config-server/src/main/resources/application.yaml`, plus `.scratch/` untracked.

```bash
git add config-server/pom.xml config-server/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
feat(config): add the monitor endpoint and the kafka bus binder

Refs #13
EOF
)"
```

---

## Task 2: `/monitor` rejects a wrong or absent signature

Covers acceptance criterion 5. This is the only task with Java, and the only one with a JUnit test. Read finding 1 before starting: the issue says the library verifies the HMAC and it does not.

**Files:**
- Create: `config-server/src/test/java/com/thedarkhorse/config/config/MonitorSignatureFilterTest.java`
- Create: `config-server/src/main/java/com/thedarkhorse/config/config/MonitorSignatureFilter.java`
- Create: `config-server/src/main/java/com/thedarkhorse/config/config/MonitorSecurityConfiguration.java`
- Modify: `config-server/pom.xml` — add a test-scoped `spring-boot-starter-test`

**Interfaces:**
- Consumes: from Task 1, `/monitor` on 8888 and `.scratch/Http.java`. From issue #11, the package root `com.thedarkhorse.config` and its `@SpringBootApplication`, whose component scan reaches `com.thedarkhorse.config.config`.
- Produces: `com.thedarkhorse.config.config.MonitorSignatureFilter` with the public constructor `MonitorSignatureFilter(String secret)`, extending `OncePerRequestFilter`; `com.thedarkhorse.config.config.MonitorSecurityConfiguration` with `@Bean FilterRegistrationBean<MonitorSignatureFilter> monitorSignatureFilter(String secret)`; and a **mandatory** `monitor.secret` property with no default, so `config-server` from here on is started as `java -jar … --monitor.secret=<value>`. Tasks 3 to 5 all start it that way.

- [ ] **Step 1: Write the failing test**

The filter has a branch, a comparison and an HTTP contract, so it is exactly the code `CLAUDE.md` requires a test for first. Create `config-server/src/test/java/com/thedarkhorse/config/config/MonitorSignatureFilterTest.java`. `MockHttpServletRequest`, `MockHttpServletResponse` and `MockFilterChain` come from `spring-test`, inside `spring-boot-starter-test`; this is not a Spring context, so it does not run into the spec's ban. `MockFilterChain.getRequest()` is `null` until the chain is called, which is how "rejected" is asserted. Static test data is in named constants, never repeated inline.

```java
package com.thedarkhorse.config.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MonitorSignatureFilterTest {

    private static final String SECRET = "shhh";
    private static final String HEADER = "X-Hub-Signature-256";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String BODY = "{\"commits\":[{\"modified\":[\"catalog-service-local.yaml\"]}]}";
    private static final String WRONG =
            "sha256=0000000000000000000000000000000000000000000000000000000000000000";

    private final MonitorSignatureFilter filter = new MonitorSignatureFilter(SECRET);

    @Test
    void rejectsARequestWithNoSignature() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsARequestWhoseSignatureDoesNotMatchTheBody() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HEADER, WRONG);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesARequestWhoseSignatureMatchesTheBodyAndLeavesTheBodyReadable() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HEADER, "sha256=" + hmac());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(BODY);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/monitor");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String hmac() throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return HexFormat.of().formatHex(mac.doFinal(BODY.getBytes(StandardCharsets.UTF_8)));
    }
}
```

The module has no test dependency yet, so add one. Edit `config-server/pom.xml`, appending to the `<dependencies>` element from Task 1:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -pl config-server test`

Expected: `BUILD FAILURE` at `compiler:3.16.0:testCompile`, because the class under test does not exist:

```
[ERROR] .../MonitorSignatureFilterTest.java:[..] cannot find symbol
[ERROR]   symbol:   class MonitorSignatureFilter
[ERROR]   location: package com.thedarkhorse.config.config
```

The runtime half of the red is already in hand: Task 1 Step 5 accepted an unsigned `POST /monitor` with `HTTP 200 ["catalog-service-local","catalog-service","catalog"]`. That is the behaviour this task inverts.

- [ ] **Step 3: Write the minimal implementation**

Create `config-server/src/main/java/com/thedarkhorse/config/config/MonitorSignatureFilter.java`. Three things are load-bearing. The body is read once and re-served through a wrapper, because the filter consumes the stream that `PropertyPathEndpoint` needs. `MessageDigest.isEqual` is used rather than `String.equals` so the comparison does not leak the secret through timing. `read(byte[], int, int)` is overridden on the wrapper's stream because Error Prone's `InputStreamSlowMultibyteRead` fires without it.

```java
package com.thedarkhorse.config.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class MonitorSignatureFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final String secret;

    public MonitorSignatureFilter(String secret) {
        this.secret = secret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (!matches(request.getHeader(HEADER), body)) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean matches(String signature, byte[] body) {
        if (signature == null || !signature.startsWith(PREFIX)) {
            return false;
        }
        return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8));
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream delegate = new ByteArrayInputStream(body);
            return new ServletInputStream() {

                @Override
                public int read() {
                    return delegate.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return delegate.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
```

Create `config-server/src/main/java/com/thedarkhorse/config/config/MonitorSecurityConfiguration.java`. `FilterRegistrationBean` is in `org.springframework.boot.web.servlet` in Boot 4.1.0 — not `org.springframework.boot.servlet`, which does not contain it. `monitor.secret` has **no default**: a missing secret must stop the server, not silently ship a committed one.

```java
package com.thedarkhorse.config.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitorSecurityConfiguration {

    @Bean
    public FilterRegistrationBean<MonitorSignatureFilter> monitorSignatureFilter(
            @Value("${monitor.secret}") String secret) {
        FilterRegistrationBean<MonitorSignatureFilter> registration =
                new FilterRegistrationBean<>(new MonitorSignatureFilter(secret));
        registration.addUrlPatterns("/monitor");
        return registration;
    }
}
```

Change nothing in `application.yaml`. The secret is supplied at run time.

- [ ] **Step 4: Run it to verify the unit tests pass**

Run: `mvn -B -pl config-server test`

Expected:

```
[INFO] Running com.thedarkhorse.config.config.MonitorSignatureFilterTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

No Error Prone warning may name `MonitorSignatureFilter`. If `InputStreamSlowMultibyteRead` appears, the `read(byte[], int, int)` override is missing.

- [ ] **Step 5: Run it to verify criterion 5 against a running server**

```bash
mvn -B -pl config-server clean package
```

Confirm the secret is genuinely mandatory, by starting without it in the **foreground** so the failure is visible and the process exits:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Expected: startup fails with

```
Caused by: org.springframework.util.PlaceholderResolutionException:
  Could not resolve placeholder 'monitor.secret' in value "${monitor.secret}"
```

Now start it properly, as a **background** Bash task, noting the new `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --monitor.secret=probe-secret
```

Absent signature:

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json - none
```

Expected:

```
HTTP 403
{"timestamp":"...","status":403,"error":"Forbidden","path":"/monitor"}
```

Wrong signature:

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json - sha256=deadbeef
```

Expected: the same `HTTP 403`.

Correct signature, which must still reach the endpoint and still be scoped:

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json probe-secret
```

Expected:

```
HTTP 200
["catalog-service-local","catalog-service","catalog"]
```

Confirm nothing was published for the two rejected requests — the endpoint logs one line per destination it publishes, so exactly three must appear, all from the accepted request:

```bash
grep -c 'Refresh for:' CONFIG_LOG
```

Expected: `3`.

**Stop the background task.**

- [ ] **Step 6: Confirm the gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, with `Tests run: 3` for `config-server` and `Tests run: 1` for `gateway`.

```bash
git status --short
```

Expected exactly: `M config-server/pom.xml`, `?? config-server/src/main/java/com/thedarkhorse/config/config/`, `?? config-server/src/test/`, plus `.scratch/` untracked.

```bash
git add config-server/pom.xml config-server/src/main/java/com/thedarkhorse/config/config config-server/src/test
git commit -m "$(cat <<'EOF'
feat(config): verify the github webhook signature on the monitor endpoint

Refs #13
EOF
)"
```

---

## Task 3: The gateway becomes a config client with `/actuator/env`

Delivers the mechanism acceptance criterion 2 is read through. The refresh itself is Task 4.

**Files:**
- Modify: `gateway/pom.xml:15-29` — the `<dependencies>` element
- Modify: `gateway/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: from issue #12, the module `com.thedarkhorse:gateway:0.0.1-SNAPSHOT`, its jar at `gateway/target/gateway-0.0.1-SNAPSHOT.jar`, its single `catalog` route, and `spring.application.name: gateway`. From Tasks 1 and 2, a config server on 8888 started with `--monitor.secret`.
- Produces: `GET http://localhost:8080/actuator/env/<key>` answering 200 with unmasked values, and a `configserver:https://github.com/TheDarkHorse111/ecommerce.git/…` property source in the gateway's environment. Tasks 4 and 5 read both. Also produces a hard startup dependency: `gateway` no longer starts unless 8888 answers (finding C).

- [ ] **Step 1: Write the failing test**

No behaviour of ours is added here, so per `CLAUDE.md` there is no JUnit test; the failing test is the acceptance criterion run as a command. Build and start the gateway as issue #12 left it. `discovery-server` is not needed — no criterion in this issue touches the registry, and the gateway registers or fails to register without affecting anything below.

```bash
mvn -B -pl gateway clean package
```

Start it as a **background** Bash task and note the output path as `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Then:

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto
```

- [ ] **Step 2: Run it to verify it fails**

Expected — there is no actuator on the gateway and no configuration coming from anywhere but its own jar:

```
HTTP 404
{"timestamp":"...","status":404,"error":"Not Found","path":"/actuator/env/spring.jpa.hibernate.ddl-auto"}
```

**Stop the background task.**

- [ ] **Step 3: Write the minimal implementation**

Edit `gateway/pom.xml` so `<dependencies>` reads in full. `spring-cloud-starter-bus-kafka` is **not** added here; that is Task 4's green, and adding it now would leave Task 4 with no red.

```xml
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

Edit `gateway/src/main/resources/application.yaml` so it reads in full. The quotes around `"configserver:"` are mandatory — unquoted, SnakeYAML reads it as a mapping key and the application does not start (finding B). `show-values: ALWAYS` is mandatory too — without it every value reads `******` and criterion 2 cannot be observed (finding A). Only `env` is exposed; `busrefresh` is deliberately not (see "Deliberate omissions").

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

- [ ] **Step 4: Run it to verify it passes**

Start `config-server` as a **background** Bash task first — from here on the gateway will not start without it:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --monitor.secret=probe-secret
```

```bash
mvn -B -pl gateway clean package
```

Start the gateway as a second **background** Bash task, noting the new `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Then the request that was a 404:

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto
```

Expected: `HTTP 200`, with the value unmasked and its source naming the config server and the shared file. `validate` is what `application.yaml` on the `config` branch holds, and it is set in no other file:

```json
{"activeProfiles":["local"],"defaultProfiles":["default"],
 "property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
             "value":"validate"},
 "propertySources":[... {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application-local.yaml"},
                        {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
                         "property":{"origin":"Config Server ...","value":"validate"}}, ...]}
```

If `"value"` reads `******`, `show-values` is missing. If the response is a 404 with an empty `propertySources`, the `local` profile is not active.

Confirm the gateway still routes, so that adding two starters did not break issue #12's work:

```bash
java .scratch/Http.java GET http://localhost:8080/catalog/api/v1/x | head -1
```

Expected: `HTTP 503`. That is issue #12's acceptance criterion — the `catalog` route matched and no instance is registered — and it must not have become a 404.

Step 5 stops both processes to run the gate; Task 4 starts them again.

- [ ] **Step 5: Confirm the gate and commit**

**Stop both background tasks first** — `mvn clean` deletes both running jars out from under their processes. Task 4 starts them again.

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`.

```bash
git status --short
```

Expected exactly: `M gateway/pom.xml`, `M gateway/src/main/resources/application.yaml`, plus `.scratch/` untracked.

```bash
git add gateway/pom.xml gateway/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
feat(gateway): read configuration from the config server and expose the env endpoint

Refs #13
EOF
)"
```

---

## Task 4: A pushed change refreshes the running gateway

Covers acceptance criteria 1, 2 and 3. The gateway joins the bus, a signed webhook makes it pick up a pushed value with no restart, and `POST /actuator/busrefresh` on the config server does the same.

The criteria are about **one uninterrupted gateway process**, so once Step 5 starts the gateway, do not restart it before Step 8 is done. If it stops for any reason, start again from Step 5 with a fresh baseline.

**Files:**
- Modify: `gateway/pom.xml` — add one dependency
- Modify: `gateway/src/main/resources/application.yaml` — add the `bus` binder block
- Create: `.scratch/config-repo/` (a worktree, removed in Task 6)
- Modify then revert on branch `config`: `application.yaml`

**Interfaces:**
- Consumes: from Task 3, the gateway as a config client with `/actuator/env`. From Tasks 1 and 2, `/monitor` and `/actuator/busrefresh` on 8888. From issue #11, the branch `config` on `https://github.com/TheDarkHorse111/ecommerce.git`, whose `application.yaml` holds `spring.jpa.hibernate.ddl-auto: validate` and holds it nowhere else.
- Produces: a gateway that reacts to `RefreshRemoteApplicationEvent`. Task 5 consumes the same two running processes and the same worktree. The `config` branch is left with the same tree it started with.

- [ ] **Step 1: Prepare the worktree on the config branch**

A worktree keeps the `issue-13` working tree untouched and shares `origin` and its push credential.

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

- [ ] **Step 2: Write the failing test**

Task 3 Step 5 stopped both servers and `mvn clean verify` deleted both jars, so build and start them again. `config-server` first, because the gateway will not start without it.

```bash
mvn -B clean package
```

Start `config-server` as a **background** Bash task, noting `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --monitor.secret=probe-secret
```

Start the gateway as a second **background** Bash task, noting `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Record the baseline the gateway is serving right now:

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto | grep -o '"value":"[a-z]*"' | head -1
```

Expected: `"value":"validate"`.

Now push a change. Edit `.scratch/config-repo/application.yaml` so it reads in full:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): flip ddl-auto to prove bus refresh

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

Then fire the webhook the way GitHub would, signed:

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-application.json probe-secret
```

- [ ] **Step 3: Run it to verify it fails**

The `/monitor` call is expected to succeed — the config server is fully wired:

```
HTTP 200
["*"]
```

And the config server itself is expected to have published and to have seen its own event:

```bash
grep -E 'Refresh for: \*|Keys refreshed' CONFIG_LOG
```

Expected: `Refresh for: *` and `Keys refreshed []` — empty, because the config server is not a config client and has nothing of its own to rebind.

The red is on the gateway, which has no bus:

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Expected: `0`.

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto | grep -o '"value":"[a-z]*"' | head -1
```

Expected: still `"value":"validate"`, the stale value.

Confirm the config server really is serving the new one, so the red is "the gateway did not hear" and not "the push did not land":

```bash
java .scratch/Http.java GET http://localhost:8888/gateway/local | grep -o '"spring.jpa.hibernate.ddl-auto":"[a-z]*"'
```

Expected: `"spring.jpa.hibernate.ddl-auto":"none"`.

The second half of the red is criterion 3:

```bash
java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh -
```

Expected: `HTTP 204`, and then still nothing on the gateway:

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Expected: `0`.

- [ ] **Step 4: Reset the backing repository, then write the minimal implementation**

Put `application.yaml` back to `validate` and push, so that the green cycle starts from a clean baseline rather than from a value the gateway would pick up at startup anyway. Edit `.scratch/config-repo/application.yaml` so it reads in full:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): restore ddl-auto after the bus refresh red

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

Now the implementation. Edit `gateway/pom.xml`, adding one dependency to the `<dependencies>` element from Task 3, immediately after `spring-cloud-starter-config`:

```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-bus-kafka</artifactId>
        </dependency>
```

Edit `gateway/src/main/resources/application.yaml`, adding the binder block under `spring.cloud`, as a sibling of `gateway`. It is byte-identical to the config server's, for the reason given under "Configuration placement":

```yaml
    stream:
      binders:
        bus:
          type: kafka
          environment:
            spring:
              cloud:
                stream:
                  kafka:
                    binder:
                      brokers: localhost:9092
      bindings:
        springCloudBusInput:
          binder: bus
        springCloudBusOutput:
          binder: bus
```

- [ ] **Step 5: Rebuild and restart the gateway**

**Stop the gateway background task** and leave `config-server` running.

```bash
mvn -B -pl gateway clean package
```

Start the gateway as a **background** task, noting the new `GATEWAY_LOG`. This process must survive to the end of Step 8:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Confirm it joined the bus and record the restart baseline:

```bash
grep -E 'Creating binder: bus|Subscribed to topic\(s\): springCloudBus|Started GatewayApplication' GATEWAY_LOG
```

Expected three lines, the last being `Started GatewayApplication in N seconds`.

```bash
grep -c 'Started GatewayApplication' GATEWAY_LOG
```

Expected: `1`. Every check below re-runs this; it must still print `1` at the end, which is what "with no restart" means.

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto | grep -o '"value":"[a-z]*"' | head -1
```

Expected: `"value":"validate"`.

- [ ] **Step 6: Run it to verify criteria 1 and 2**

Push the change again, against the same running gateway. Edit `.scratch/config-repo/application.yaml` so it reads in full:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): flip ddl-auto to prove bus refresh

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-application.json probe-secret
```

Expected: `HTTP 200` and `["*"]`.

Now the gateway, which was silent in Step 3:

```bash
grep -E 'Received remote refresh request|Keys refreshed' GATEWAY_LOG
```

Expected exactly these two lines, and the second is the one that matters (finding E). `config.client.version` always moves with the commit; `spring.jpa.hibernate.ddl-auto` is the pushed value:

```
INFO ... o.s.cloud.bus.event.RefreshListener : Received remote refresh request.
INFO ... o.s.cloud.bus.event.RefreshListener : Keys refreshed [config.client.version, spring.jpa.hibernate.ddl-auto]
```

Criterion 2, the same reading through `/actuator/env`:

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto | grep -o '"value":"[a-z]*"' | head -1
```

Expected: `"value":"none"`.

Criterion 1's "with no restart":

```bash
grep -c 'Started GatewayApplication' GATEWAY_LOG
```

Expected: `1`.

- [ ] **Step 7: Run it to verify criterion 3**

`POST /actuator/busrefresh` on one service — the config server, per "Deliberate omissions" — must refresh the other. Record the current count first:

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Expected: `1`.

```bash
java .scratch/Http.java POST http://localhost:8888/actuator/busrefresh -
```

Expected: `HTTP 204`.

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Expected: `2`.

```bash
grep 'Keys refreshed' GATEWAY_LOG | tail -1
```

Expected: `Keys refreshed []` — nothing changed in the repository since Step 6, so the refresh ran and found no difference. That is the correct outcome; the event arriving through Kafka on the `container-0-C-1` thread is what criterion 3 asserts.

```bash
grep -c 'Started GatewayApplication' GATEWAY_LOG
```

Expected: still `1`.

- [ ] **Step 8: Restore the backing repository**

The `config` branch must end this issue with the tree issue #11 gave it. Edit `.scratch/config-repo/application.yaml` so it reads in full:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): restore ddl-auto after the bus refresh check

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-application.json probe-secret
```

```bash
java .scratch/Http.java GET http://localhost:8080/actuator/env/spring.jpa.hibernate.ddl-auto | grep -o '"value":"[a-z]*"' | head -1
```

Expected: `"value":"validate"` again — which incidentally demonstrates the loop a second time.

This is the last step that needs the uninterrupted gateway process; Step 9 stops it, and Task 5 starts a fresh one with its own baseline.

- [ ] **Step 9: Confirm the gate and commit**

**Stop both background tasks first** — `mvn clean` deletes both running jars out from under their processes. Task 5 starts them again; it needs one uninterrupted gateway process across its own steps, not this one.

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`.

```bash
git status --short
```

Expected exactly: `M gateway/pom.xml`, `M gateway/src/main/resources/application.yaml`, plus `.scratch/` untracked. Nothing under `config-server/`.

```bash
git add gateway/pom.xml gateway/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
feat(gateway): join the config refresh bus

Refs #13
EOF
)"
```

---

## Task 5: A catalog-only push does not refresh the gateway

Covers acceptance criterion 4. No file that ends up on `issue-13` changes here; the deliverable is the observation, and the task ends with no commit, which is consistent with "every commit is green" because there is nothing new to record.

The criterion is a negative, so this task runs the positive control in the same session. Without it, a broken bus, a stopped gateway or a typo in the payload would all look like a pass.

**Files:**
- Modify then revert on branch `config`: `catalog-service-local.yaml`, `application.yaml` (in the worktree at `.scratch/config-repo/`)

**Interfaces:**
- Consumes: everything from Tasks 1 to 4 — both servers running, the worktree, `.scratch/Http.java`, `.scratch/push-catalog.json` and `.scratch/push-application.json`.
- Produces: nothing. The `config` branch is left with the same tree again.

- [ ] **Step 1: Write the failing test**

Task 4 Step 9 stopped both servers and `mvn clean verify` deleted both jars, so rebuild and start them again. `config-server` first, because the gateway will not start without it.

```bash
mvn -B clean package
```

Start `config-server` as a **background** Bash task, noting `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --monitor.secret=probe-secret
```

Start the gateway as a second **background** Bash task, noting `GATEWAY_LOG`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

Confirm the gateway is up and on the bus before measuring anything:

```bash
grep -E 'Subscribed to topic\(s\): springCloudBus|Started GatewayApplication' GATEWAY_LOG
```

Expected both lines. Redpanda from Task 1 Step 3 must still be running; `docker ps` must list `redpanda`.

Record the two counts this task moves, or fails to:

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Write the number down; call it `N`. On a freshly restarted gateway it is `0`.

Now push a change that touches `catalog-service-local.yaml` and nothing else. Edit `.scratch/config-repo/catalog-service-local.yaml` so it reads in full — `show-sql` moves from `true` to `false`, and the gateway does not read this file at all:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog_db
    username: catalog
    password: catalog
  jpa:
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): flip catalog show-sql to prove scoped refresh

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-catalog.json probe-secret
```

- [ ] **Step 2: Run it to verify criterion 4**

Expected from the endpoint — three destinations, none of them the gateway:

```
HTTP 200
["catalog-service-local","catalog-service","catalog"]
```

The config server publishes all three and, being on the bus itself, sees them and declines them (finding E):

```bash
grep 'Refresh not performed' CONFIG_LOG | tail -3
```

Expected three lines naming `catalog-service-local:**`, `catalog-service:**` and `catalog:**`.

Now the assertion. First, that the messages did reach the gateway, so that "not refreshed" is not silently "not delivered" (finding D). These lines are DEBUG and are visible because `application-local.yaml` sets `logging.level.root: DEBUG`:

```bash
grep -c "Received remote event from bus.*destinationService = 'catalog" GATEWAY_LOG
```

Expected: `3`.

Second, that the gateway did not act on any of them:

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Expected: still `N`. If this is above `N`, the scoping is broken.

```bash
grep -c 'Keys refreshed' GATEWAY_LOG
```

Expected: `0` on a gateway started fresh in Step 1, and in any case unchanged from the reading taken there.

- [ ] **Step 3: Run the positive control**

Prove the same check would have caught a refresh. Edit `.scratch/config-repo/application.yaml` so it reads in full:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): control push for the scoped refresh check

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

```bash
java .scratch/Http.java POST http://localhost:8888/monitor .scratch/push-application.json probe-secret
```

Expected: `HTTP 200` and `["*"]`.

```bash
grep -c 'Received remote refresh request' GATEWAY_LOG
```

Expected: `N + 1`. The check moves when it should and did not move when it should not, which is what makes criterion 4 meaningful.

- [ ] **Step 4: Restore the backing repository**

Both files go back. Edit `.scratch/config-repo/application.yaml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

Edit `.scratch/config-repo/catalog-service-local.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog_db
    username: catalog
    password: catalog
  jpa:
    show-sql: true
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
chore(config): restore the backing files after the scoped refresh check

Refs #13
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

Confirm the branch's tree is byte-identical to where issue #11 left it — the commits accumulate, the content does not change:

```bash
git -C .scratch/config-repo diff --stat origin/config
```

Expected: no output.

```bash
gh api /repos/TheDarkHorse111/ecommerce/git/trees/config --jq '.tree[].path'
```

Expected exactly, in some order: `application-local.yaml`, `application-prod.yaml`, `application.yaml`, `catalog-service-local.yaml`, `catalog-service-prod.yaml`, `catalog-service.yaml`.

**Stop both background tasks.**

---

## Task 6: Clean up and confirm the tree

No acceptance criterion. Its deliverable is a clean working tree, a stopped container and a green gate, and it ends with no commit.

**Files:**
- Remove: the `.scratch/config-repo/` worktree and the local `config` branch
- Delete: `.scratch/`

**Interfaces:**
- Consumes: everything above. Produces: nothing.

- [ ] **Step 1: Stop and remove Redpanda**

```bash
docker rm -f redpanda
```

Expected: `redpanda`.

- [ ] **Step 2: Remove the worktree**

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

- [ ] **Step 3: Delete the scratch directory and confirm the tree is clean**

`.scratch/` is not covered by `.gitignore`, so it must be removed rather than left untracked.

```bash
rm -rf .scratch
```

```bash
git status --short
```

Expected: **no output at all**.

- [ ] **Step 4: Confirm the gate and the shape of the branch**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, reactor listing `ecommerce`, `discovery-server`, `gateway` and `config-server`, with `Tests run: 3` for `config-server` and `Tests run: 1` for `gateway`.

```bash
git diff --stat master...HEAD
```

Expected exactly eight entries — the seven files this issue adds or modifies, plus this plan, which was committed onto `issue-13` before implementation began:

```
 docs/superpowers/plans/2026-09-09-config-refresh-over-spring-cloud-bus.md
 config-server/pom.xml
 config-server/src/main/java/com/thedarkhorse/config/config/MonitorSecurityConfiguration.java
 config-server/src/main/java/com/thedarkhorse/config/config/MonitorSignatureFilter.java
 config-server/src/main/resources/application.yaml
 config-server/src/test/java/com/thedarkhorse/config/config/MonitorSignatureFilterTest.java
 gateway/pom.xml
 gateway/src/main/resources/application.yaml
```

Nothing under `.github/`, nothing under `discovery-server/`, and no change to the root `pom.xml`.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #13 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1 | Pushing a changed value to the config repository makes a running service report it with no restart | 4 | 4.3, 4.6 | `Received remote refresh request` count `0` on the gateway while the config server serves `"ddl-auto":"none"` | `Keys refreshed [config.client.version, spring.jpa.hibernate.ddl-auto]`, with `Started GatewayApplication` count still `1` |
| 2 | The change is visible through `/actuator/env` on the client | 3, 4 | 3.2, 3.4, 4.3, 4.6 | `HTTP 404` on `/actuator/env/…`; then `"value":"validate"` after the push | `"value":"validate"` unmasked from the config server source, then `"value":"none"` after the push |
| 3 | `POST /actuator/busrefresh` on one service refreshes all of them | 4 | 4.3, 4.7 | `HTTP 204` from 8888 with the gateway's refresh count still `0` | count goes `1` → `2` on the gateway, `Keys refreshed []`, `Started GatewayApplication` still `1` |
| 4 | A push touching only `catalog-service-*.yaml` does not refresh the gateway | 5 | 5.2, 5.3 | control: an `application.yaml` push moves the count to `N + 1` | `["catalog-service-local","catalog-service","catalog"]`, three DEBUG arrivals on the gateway, refresh count still `N`, `Refresh not performed, the event was targeting catalog:**` on the config server |
| 5 | A request to `/monitor` with a wrong or absent signature is rejected | 2 | 2.2, 2.4, 2.5 | `testCompile` fails on the missing class; the unsigned POST of Task 1.5 returned `HTTP 200 ["catalog-service-local",…]` | 3 unit tests green; `HTTP 403` for absent and for wrong; `HTTP 200` for correct; `Refresh for:` count `3` |

Criteria 1, 2 and 3 land together in Task 4 because they all become true at the single moment the gateway joins the bus, and a reviewer cannot sensibly accept one and reject another. Criterion 2's mechanism is split into Task 3 because the actuator and the config client are a separable deliverable with their own red.

The issue's Design and Security blocks are covered too, and four sentences deserve naming because no criterion tests them:

- **"`spring-cloud-config-monitor` goes on the config server only"** — Task 1 adds it there and nowhere else; `gateway/pom.xml` never names it.
- **"Bus gets its own Kafka binder configuration"** — the named `bus` binder in both modules' YAML, confirmed live by `Creating binder: bus` in Tasks 1.5 and 4.5.
- **"Refresh rebinds `@ConfigurationProperties` beans and re-creates `@RefreshScope` beans. `@RefreshScope` goes on `@Bean` methods."** — a standing rule, not work. No bean in this issue needs it; see "Deliberate omissions".
- **"`/actuator/busrefresh` is not routed through the gateway"** — the gateway's only route is `Path=/catalog/**`, and the endpoint is exposed on 8888 only. Both are visible in `gateway/src/main/resources/application.yaml` and `config-server/src/main/resources/application.yaml` after Tasks 3 and 1.

---

## Pull request

Title: `feat(config): refresh configuration over spring cloud bus (#13)`

Body lists each of the five acceptance criteria with the command and the output that verified it, and ends with `Closes #13`.

The body must lead with the deviation, because a reviewer reading only the diff will not see it:

- **`/monitor` does not verify the GitHub signature; we do.** Issue #13's Security block says `/monitor` "verifies GitHub's `X-Hub-Signature-256` HMAC against a shared secret, rejecting anything that fails". `spring-cloud-config-monitor` 5.0.4 contains no HMAC code and no secret property; its only header check is `"push".equals(headers.getFirst("X-Github-Event"))`. Measured before planning: with the monitor on the classpath and no filter, an unsigned `POST /monitor` returned `HTTP 200 ["catalog-service-local","catalog-service","catalog"]` and broadcast a refresh. The check is therefore `MonitorSignatureFilter`, a `OncePerRequestFilter` registered on `/monitor` by an `@Bean` method, comparing with `MessageDigest.isEqual` and re-serving the consumed body downstream. It is the only Java this issue adds and the only part of it with a unit test.

And the five findings a reviewer will otherwise trip over, none of which is in the issue:

- **The gateway now has a hard startup dependency on the config server.** `spring.config.import: "configserver:"` is not `optional:`, so an unreachable 8888 is `ConfigClientFailFastException: Could not locate PropertySource and the resource is not optional, failing`, not a warning. That is issue #11's specified form and the deliberate choice; `config-server` must be started before `gateway` from here on.
- **`spring.config.import: configserver:` must be quoted.** Unquoted it is a YAML mapping key and the application will not parse its own `application.yaml`.
- **`/actuator/env` masks every value by default in Boot 4.** `management.endpoint.env.show-values: ALWAYS` on the gateway is what makes acceptance criterion 2 observable. It also unmasks everything else, on the gateway's published port 8080. The shared files the gateway reads carry no credentials today — the datasource credentials are in `catalog-service-local.yaml`, which it does not read — but a management port or Spring Security is the real fix and belongs with the secret vault the spec already defers. Neither is in this issue.
- **`/actuator/busrefresh` is exposed on the config server only.** The issue's Security block says it is not reachable from outside the network; the gateway's port is the published one, so exposing it there would contradict that. The gateway still consumes bus events, which is what criterion 3 actually needs. `discovery-server` is not on the bus at all, because it is not a config client.
- **The Kafka broker address is in each module's YAML, not in the config repository.** Spec section 3 lists Redpanda hosts among the profile-varying keys that belong in the repository. `config-server` is not a config client and cannot read its own broker address from the repository it serves, so keeping the gateway's copy in the repository would mean the same address maintained in two places. The spec's rule stands unchanged for the domain-event binders that arrive with `catalog-service`.

And one judgement call the reviewer may want reversed with a rename: the two new classes are in `com.thedarkhorse.config.config`, applying the spec's per-service package layout literally to a service whose name happens to be `config`.
