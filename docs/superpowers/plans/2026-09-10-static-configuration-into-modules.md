# Static configuration into the modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every key that exists today, in the three module `application.yaml` files and on the `config` branch, sits where spec section 3 puts it: static wiring in the module that owns it, and nothing left in the config repository except what varies by environment, is secret, or is worth changing while the system is running.

**Architecture:** Four moves and one audit, and no Java at all. The gateway loses the `brokers` key that the config repository already carries and has always overridden, and gains `${CONFIG_SERVER_URI:http://localhost:8888}` inside its `spring.config.import` so that the one value which cannot come from the config repository — the address of the config repository — comes from the environment instead. The `config` branch loses `spring.jpa.hibernate.ddl-auto`, which is identical everywhere and belongs in each JPA service, and loses `catalog-service.yaml`, whose only key is a port; both land in `catalog-service` in #14. Everything else on both sides is audited key by key and recorded, including two keys that a literal reading of the acceptance criteria would delete and that must not be deleted.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Cloud 2025.1.3, `spring-cloud-config-client` 5.0.5, `spring-cloud-config-server` 5.0.5, `spring-cloud-starter-bus-kafka`, `kafka-clients`, Redpanda v24.2.7 in Docker, Error Prone 2.50.0, maven-compiler-plugin 3.16.0. No dependency changes; no POM is touched.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md`, section 3 "Configuration" (the placement rule, the triplet layout, the list of profile-varying keys, and the sentence that `prod` deliberately mirrors `local` except log level). Issue #54 is the specification for this increment; its dependency #12 is planned in `docs/superpowers/plans/2026-09-09-gateway.md` and #13 in `docs/superpowers/plans/2026-09-09-config-refresh-over-spring-cloud-bus.md`, both merged. This issue reverses, for the gateway only, the deviation that plan #13 named in its own pull request body: "The Kafka broker address is in each module's YAML, not in the config repository."

---

## Global Constraints

Copied from `CLAUDE.md`, issue #54 and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. Error Prone runs during compilation. It is green today and must stay green; this issue adds and removes no Java, so the gate is a regression check, not the acceptance test. The acceptance tests are runtime observations, listed per task.
- **No comments anywhere**, including in `application.yaml` on either branch and in the throwaway `.scratch/` files. Rationale lives in the spec and in this plan.
- **TDD, applied honestly.** No class is written before a failing test for it has been seen to fail — and this issue writes no class. TDD applies to code with behaviour; YAML keys have none of ours, and the standing rules forbid a Spring context in tests and forbid testing the framework outright, so no JUnit test may be added for any of this. Every task's first step is therefore a **command whose failing output is recorded verbatim before the edit**, and every task's verification step is the same command showing the expected output after. Do not invent a `@SpringBootTest` that asserts a property resolves; that is the framework, and it is banned.
- **Scope.** Build only what issue #54 asks for. Do not add `spring.config.import` to `config-server` or `discovery-server`, do not add `optional:` to the gateway's import, do not add `spring.cloud.config.fail-fast`, do not add a Docker Compose file, do not reformat either YAML file beyond the lines named, do not "tidy" the config repository's remaining keys, and do not create `catalog-service`. Everything this issue removes from the config repository is re-added by #14 in the module that owns it.
- **Two acceptance criteria must not be read literally.** Findings T1 and T2 below name them, with the issue's own text and the spec's own text as the authority for the narrower reading. Deleting either key breaks a running service and contradicts a different criterion in the same issue.
- **Conventional Commits**, one commit per completed red-green cycle, every commit green, each carrying a `Refs #54` footer, no `Co-Authored-By` trailer and no generated-with footer. Scope `gateway` for the two module commits, scope `config` for the commit that lands on the `config` branch.
- **Pull request** title ends `(#54)`; body ends `Closes #54`. Merge with rebase, never squash. The `config` branch is not merged into `master` and has no pull request; see Task 3 for how it is published and reviewed.

---

## Verified Findings

Every claim issue #54 makes about how a tool behaves was executed on this machine before planning on top of it, using the real modules, the real `config` branch on GitHub, a Redpanda container, and a scratch copy of the gateway's `application.yaml` at `.scratch/gwconfig/` fed to the real jar with `--spring.config.location`. Nothing under `gateway/`, `config-server/`, `discovery-server/` or the `config` branch was modified to obtain any of it.

**No claim in the issue turned out to be false.** Findings 1 to 9 are confirmations. Findings T1 and T2 are two places where the *acceptance criteria*, read literally, contradict the issue's own Design block or the spec, and the plan resolves both explicitly rather than silently. Findings A to C are things the issue does not say that the plan depends on.

### 1. Confirmed: the module's copy of `brokers` "has never had an effect"

The gateway was started unmodified against a config server on 8888 serving the real `config` branch, and `/actuator/env` was asked for the one key:

```
$ java .scratch/Fetch.java "http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers"
200
{"activeProfiles":["local"],...,
 "property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml","value":"localhost:9092"},
 "propertySources":[...,
   {"name":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml",
    "property":{"origin":"Config Server https://github.com/TheDarkHorse111/ecommerce.git/application.yaml:12:32","value":"localhost:9092"}},
   {"name":"configClient"},
   {"name":"Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
    "property":{"origin":"class path resource [application.yaml] from gateway-0.0.1-SNAPSHOT.jar - 22:32","value":"localhost:9092"}},
   ...]}
```

Both sources hold the key, the config server's source is the one that wins, and the module's copy sits below `configClient` where it can never be read. Exactly what the issue says, including "the two are free to disagree silently".

### 2. Confirmed: `configserver:` with no URI defaults to `localhost:8888`

Statically, from the jar the reactor actually resolves:

```
$ mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree -Dincludes=org.springframework.cloud:spring-cloud-config-client
[INFO] Building gateway 0.0.1-SNAPSHOT
[INFO]    \- org.springframework.cloud:spring-cloud-config-client:jar:5.0.5:compile

$ jar xf spring-cloud-config-client-5.0.5.jar org/springframework/cloud/config/client/ConfigClientProperties.class
$ javap -p -c org/springframework/cloud/config/client/ConfigClientProperties.class | grep localhost
        22: ldc           #21                 // String http://localhost:8888
```

And at runtime, from the unmodified gateway whose import is the bare `"configserver:"`:

```
Fetching config from server at : http://localhost:8888
```

### 3. Confirmed: a `${...}` placeholder inside `spring.config.import` resolves

The scratch gateway configuration carried the exact line the issue proposes, `import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}"`. A second config server was started on 8889 with `--server.port=8889`.

With the variable set, the gateway reached 8889 and started:

```
$ java -DCONFIG_SERVER_URI=http://localhost:8889 -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.config.location=file:.../.scratch/gwconfig/
Fetching config from server at : http://localhost:8889
Started GatewayApplication in 9.709 seconds (process running for 10.457)
```

With it unset, the default held and the gateway started against 8888:

```
$ java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.config.location=file:.../.scratch/gwconfig/
Fetching config from server at : http://localhost:8888
Started GatewayApplication in 12.625 seconds (process running for 13.354)
```

**One caveat, stated because it is the only gap in this evidence.** The value was injected as a JVM system property (`-DCONFIG_SERVER_URI=...`) rather than an exported shell variable, because this planning session's allowlist refused a command with an environment-variable prefix. `${CONFIG_SERVER_URI:...}` resolves against the whole Spring `Environment`, and `systemProperties` and `systemEnvironment` are two sources in it that are consulted by the same resolver, so the mechanism under test is identical. Task 1 nonetheless re-runs it with a real exported variable, and that run — not this one — is the one the pull request cites.

### 4. Confirmed: the gateway with no broker address of its own starts and reaches the config repository's broker

Same scratch configuration, with the whole `environment:` block deleted:

```
$ java .scratch/Fetch.java "http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers"
200
{...,"property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml","value":"localhost:9092"},
 "propertySources":[...,
   {"name":"Config resource 'file [.../.scratch/gwconfig/application.yaml]' via location 'file:.../.scratch/gwconfig/'"},
   ...]}
```

The module-side entry is still listed as a property source, but it no longer carries the property — no `"property"` object on it. That absence is the acceptance test for criterion 1 on the gateway side.

### 5. Confirmed: a bus refresh still reaches that gateway

With the same broker-less gateway running:

```
$ java .scratch/Fetch.java "http://localhost:8889/actuator/busrefresh" POST
204
$ grep -c "Received remote refresh request" $GATEWAY_LOG
1
```

### 6. Confirmed: `prod` and `local` resolve different broker addresses, and the gateway itself resolves them

From the config server, both profiles:

```
$ java .scratch/Fetch.java http://localhost:8888/gateway/local
{"name":"gateway","profiles":["local"],...,"propertySources":[
  {"name":".../application-local.yaml","source":{"logging.level.root":"DEBUG","eureka.client.service-url.defaultZone":"http://localhost:8761/eureka/"}},
  {"name":".../application.yaml","source":{"spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"localhost:9092","spring.jpa.hibernate.ddl-auto":"validate","spring.jpa.show-sql":false}}]}

$ java .scratch/Fetch.java http://localhost:8888/gateway/prod
{"name":"gateway","profiles":["prod"],...,"propertySources":[
  {"name":".../application-prod.yaml","source":{"logging.level.root":"INFO","eureka.client.service-url.defaultZone":"http://localhost:8761/eureka/","spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"redpanda:9092"}},
  {"name":".../application.yaml","source":{...":"localhost:9092",...}}]}
```

And inside the broker-less gateway, whose Kafka client prints the address it was handed before it tries to use it:

```
$ java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.config.location=file:.../.scratch/gwconfig/ --spring.profiles.active=prod
bootstrap.servers = [redpanda:9092]

$ java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.config.location=file:.../.scratch/gwconfig/
bootstrap.servers = [localhost:9092]
```

The same file produced both. The `prod` run then hangs, because no host called `redpanda` exists on this machine — that is expected and is itself the confirmation that the address was taken seriously. Stop it after the line appears.

### 7. Confirmed: `config-server` starts with the `config` branch unreachable, keeping its own broker

```
$ java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --server.port=8890 \
    --spring.cloud.config.server.git.uri=https://github.com/TheDarkHorse111/definitely-not-a-real-repo.git
Started ConfigServerApplication in 11.585 seconds (process running for 13.044)
bootstrap.servers = [localhost:9092]
```

The clone is lazy; nothing about a missing backing repository is fatal at startup.

### 8. Confirmed: `config-server` and `discovery-server` start with no config server present

`config-server` never asks one for its own configuration, even though `spring-cloud-config-client` 5.0.5 is on its classpath transitively (finding 2's dependency tree lists it for `config-server` too). It has no `spring.config.import`, so the client never runs:

```
$ grep -c "Fetching config from server at" $CONFIG_LOG
0
```

`discovery-server` has no config client on its classpath at all, and started with nothing else running — no config server, no broker, no Eureka peer:

```
$ java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
Started DiscoveryServerApplication in 5.466 seconds (process running for 6.127)
```

### 9. Confirmed: the gate is green today, and the tree is what the plan assumes

```
$ mvn -B clean verify
[INFO] ecommerce .......................................... SUCCESS
[INFO] discovery-server ................................... SUCCESS
[INFO] gateway ............................................ SUCCESS
[INFO] config-server ...................................... SUCCESS
[INFO] BUILD SUCCESS

$ git ls-tree -r --name-only origin/config
application-local.yaml
application-prod.yaml
application.yaml
catalog-service-local.yaml
catalog-service-prod.yaml
catalog-service.yaml
```

### T1. Criterion 1 read literally would delete `config-server`'s broker address, and criterion 7 forbids that

`config-server/src/main/resources/application.yaml` carries `spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers: localhost:9092`, and so does the `config` branch's `application.yaml`. That is a key appearing in both a module `application.yaml` and a file on the `config` branch, which criterion 1 forbids in so many words.

It stays, for three reasons that all come from inside issue #54 or the spec:

- The issue's own Design block lists what stays in a module and names "**config-server's own git and bus settings**" among them.
- Criterion 7 in the same issue requires that "config-server **keeps its local broker address**".
- The hazard criterion 1 exists to prevent cannot arise here. Finding 8 shows `config-server` never fetches from a config server, so the branch's copy can never reach it. There is no precedence contest, so there is nothing to disagree silently.

Criterion 1 is therefore implemented as: **no key appears in both the config branch and the `application.yaml` of a module that imports the config server.** Today that is `gateway` alone. Do not delete `config-server`'s broker address; Task 4 checks that it is still there.

### T2. Criterion 6 read literally would delete `eureka.client.service-url.defaultZone`, and the spec forbids that

`eureka.client.service-url.defaultZone` is byte-identical in `application-local.yaml` and `application-prod.yaml` — both `http://localhost:8761/eureka/` (finding 6). "The same in every environment", read literally, catches it.

It stays. Spec section 3 lists Eureka `defaultZone` explicitly among the keys "that differ between profiles … limited to addresses and verbosity", and the next paragraph says `prod` "carries the same values as `local` except log level" because real production values are not committed and secret storage is deferred to a vault. The key is identical today because `prod` is not yet real, not because it is static. Deleting it would take Eureka registration with it under both profiles.

The same reasoning covers, and keeps, `catalog-service-prod.yaml`'s datasource URL and credentials, which are also identical to `catalog-service-local.yaml`'s. The issue says both catalog files stay, so this is a confirmation rather than a judgement.

### A. Nothing carrying the bus binder starts without a broker

Before Redpanda was started, `config-server` never finished booting. It printed no `Started ConfigServerApplication` line and instead repeated, thousands of times:

```
[AdminClient clientId=adminclient-1] Rebootstrapping with Cluster(id = null, nodes = [localhost:9092 (id: -1 rack: null isFenced: false)], partitions = [], controller = null)
```

Every check in Tasks 1 to 4 needs `docker run` Redpanda on `localhost:9092` first, except the `discovery-server` check, which has no bus.

### B. The gateway has a hard startup dependency on the config server, and this issue does not change that

With no config server on the resolved address, the gateway exits 1:

```
Could not locate PropertySource and the resource is not optional, failing
```

`spring.config.import` is not prefixed `optional:`, which is issue #11's specified form. Adding `optional:` is not in scope. It matters here only because it means every gateway check must start a config server first, and because a mistyped `CONFIG_SERVER_URI` fails loudly rather than silently.

### C. There is no `curl`, no `wget` and no `python3`

All three were refused by this session's allowlist; `java` was not. Java's single-file source launcher runs a `.java` file directly, so **Task 1 Step 1 creates `.scratch/Fetch.java`** and every HTTP check in this plan runs through it. That exact file produced every measurement above. `.scratch/` is not covered by `.gitignore` (which lists `target/` only), so Task 5 must delete it rather than leave it untracked.

Background processes are started as background Bash tasks and stopped with the harness's background-task stop; `kill` and `pkill` are not on the allowlist. The harness prints the output file for each background task; the steps below write `$GATEWAY_LOG`, `$CONFIG_LOG`, `$CONFIG_LOG_8889` and `$DISCOVERY_LOG` for those paths — **substitute the real ones, and note they change on every restart**. `grep -c` exits 1 when the count is zero; read the printed number, not the exit status.

---

## File Structure

Committed to `TheDarkHorse111/ecommerce`, branch `issue-54`:

| Path | Responsibility | Action |
| --- | --- | --- |
| `gateway/src/main/resources/application.yaml` | Loses the seven-line `environment:` block under the `bus` binder. Gains `${CONFIG_SERVER_URI:http://localhost:8888}` inside `spring.config.import`. Nothing else changes: the binder name and type, the two bus bindings, the routes, the port, the application name, the active profile and the actuator exposure all stay. | Modify |

Committed to `TheDarkHorse111/ecommerce`, branch `config` (via `config-54`):

| Path | Responsibility | Action |
| --- | --- | --- |
| `application.yaml` | Loses `spring.jpa.hibernate.ddl-auto: validate` and the now-empty `hibernate:` parent. Keeps the broker address, which `application-prod.yaml` overrides, and `spring.jpa.show-sql: false`, which `catalog-service-local.yaml` overrides. | Modify |
| `catalog-service.yaml` | Its only key is `server.port: 8081`, which is static. An application gets its own triplet only when it has a key the shared files cannot carry, and this one no longer does. | Delete |

Not touched, and each for a stated reason:

- `config-server/src/main/resources/application.yaml` — finding T1. Its broker address, git URI, label, webhook secret, port, application name and actuator exposure all answer the rule's first question, not its second.
- `discovery-server/src/main/resources/application.yaml` — its port and the `register-with-eureka`/`fetch-registry` pair are named in the issue's own list of what stays in a module.
- `application-local.yaml`, `application-prod.yaml`, `catalog-service-local.yaml`, `catalog-service-prod.yaml` on the `config` branch — log level, Eureka `defaultZone`, the prod broker address, the datasource URL and credentials, and SQL echo are all addresses, verbosity or secrets. Findings 6 and T2.
- `pom.xml`, every module POM, `.github/`, `.mvn/`, `lombok.config`, `CLAUDE.md`, `README.md`, the spec, every other plan, and every Java file in the repository. This issue adds and removes no Java and no dependency.

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/Fetch.java` | The HTTP client, because the allowlist has none. Created in Task 1, deleted in Task 5. |

---

## Task 1: The gateway reads its config server address from the environment

Covers acceptance criterion 5. It goes first because it is the only change in this issue whose red state can be produced without a second server running, and because Tasks 2 to 4 all restart the gateway anyway.

**Files:**
- Modify: `gateway/src/main/resources/application.yaml:9-10` — the `spring.config.import` line
- Create: `.scratch/Fetch.java`

**Interfaces:**
- Consumes: from #12, the `gateway` module and its jar at `gateway/target/gateway-0.0.1-SNAPSHOT.jar`, `spring.application.name: gateway`, `spring.profiles.active: local`, and `/actuator/env` exposed with `show-values: ALWAYS`. From #11, the `config-server` module, its jar, and the six-file `config` branch of `https://github.com/TheDarkHorse111/ecommerce.git`. From #13, the named `bus` Kafka binder and the two bus bindings on both modules.
- Produces: the environment variable `CONFIG_SERVER_URI` as the gateway's only outside input, defaulting to `http://localhost:8888`. `.scratch/Fetch.java` exposes `Fetch.main(String[])`, invoked as `java .scratch/Fetch.java <url> [POST]`, printing the status code on one line and the body on the next; Tasks 2 to 4 use it and Task 5 deletes it.

- [ ] **Step 1: Build the harness the checks need**

Three pieces of setup, none of which is a code change. First the HTTP client, because the allowlist has none (finding C). Create `.scratch/Fetch.java` exactly as below. No comments, consistent with the standing rule; it is a throwaway harness and gets no test of its own.

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Fetch {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(args[0]));
        if (args.length > 1 && args[1].equals("POST")) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode());
        System.out.println(response.body());
    }
}
```

Then the broker, because nothing carrying the bus binder starts without one (finding A):

```bash
docker run -d --name redpanda -p 9092:9092 -p 9644:9644 docker.redpanda.com/redpandadata/redpanda:v24.2.7 redpanda start --overprovisioned --smp 1 --memory 1G --reserve-memory 0M --node-id 0 --check=false --kafka-addr PLAINTEXT://0.0.0.0:9092 --advertise-kafka-addr PLAINTEXT://localhost:9092
```

```bash
docker ps --format "{{.Names}} {{.Status}}"
```

Expected: `redpanda Up ...`.

Then the jars:

```bash
mvn -B -DskipTests package
```

Expected: `BUILD SUCCESS`, four modules.

- [ ] **Step 2: Start two config servers, on the default address and on a second one**

Both as background tasks. The first:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

The second, which exists only so that "a non-default address" means something:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --server.port=8889
```

Wait for each, substituting the printed output paths:

```bash
until grep -q "Started ConfigServerApplication\|APPLICATION FAILED" $CONFIG_LOG; do sleep 2; done; grep -o "Started ConfigServerApplication in.*" $CONFIG_LOG
```

Expected, from each: `Started ConfigServerApplication in N seconds (process running for M)`.

```bash
java .scratch/Fetch.java http://localhost:8889/gateway/local
```

Expected: `200`, then a body naming `application-local.yaml` and `application.yaml` as property sources. Both servers serve the same branch; only their addresses differ.

- [ ] **Step 3: Run the failing check**

Start the gateway with the variable exported and the module file still unmodified:

```bash
CONFIG_SERVER_URI=http://localhost:8889 java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

If the allowlist refuses a command with an environment-variable prefix, use `java -DCONFIG_SERVER_URI=http://localhost:8889 -jar ...` instead and say so in the pull request; finding 3 explains why the two are equivalent for this mechanism and why the exported form is preferred.

```bash
until grep -q "Started GatewayApplication\|APPLICATION FAILED" $GATEWAY_LOG; do sleep 2; done; grep -o "Fetching config from server at.*" $GATEWAY_LOG
```

Expected, and this is the failure:

```
Fetching config from server at : http://localhost:8888
Fetching config from server at : http://localhost:8888
```

The gateway ignored `CONFIG_SERVER_URI` entirely and went to the hard-coded default, because `import: "configserver:"` names no URI and has nowhere to read one from. Record both lines. Stop the gateway.

- [ ] **Step 4: Make the change**

`gateway/src/main/resources/application.yaml`, lines 9 and 10 today:

```yaml
  config:
    import: "configserver:"
```

become:

```yaml
  config:
    import: "configserver:${CONFIG_SERVER_URI:http://localhost:8888}"
```

The quotes are not optional. Unquoted, `configserver:` is a YAML mapping key and the gateway cannot parse its own `application.yaml`. Nothing else on the file changes in this task.

```bash
mvn -B -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Run the check again, both ways**

With the variable set:

```bash
CONFIG_SERVER_URI=http://localhost:8889 java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

```bash
until grep -q "Started GatewayApplication\|APPLICATION FAILED" $GATEWAY_LOG; do sleep 2; done; grep -o "Fetching config from server at.*\|Started GatewayApplication in.*" $GATEWAY_LOG
```

Expected:

```
Fetching config from server at : http://localhost:8889
Fetching config from server at : http://localhost:8889
Started GatewayApplication in N seconds (process running for M)
```

Stop it. With the variable unset:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

```bash
until grep -q "Started GatewayApplication\|APPLICATION FAILED" $GATEWAY_LOG; do sleep 2; done; grep -o "Fetching config from server at.*\|Started GatewayApplication in.*" $GATEWAY_LOG
```

Expected:

```
Fetching config from server at : http://localhost:8888
Fetching config from server at : http://localhost:8888
Started GatewayApplication in N seconds (process running for M)
```

Both halves of criterion 5 are now recorded. Leave this second gateway running; Task 2 replaces it. Stop the 8889 config server; nothing else needs it until Task 3.

- [ ] **Step 6: Confirm the gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, four modules, `Tests run: 1` for `gateway`.

`mvn clean` removed the jars. Rebuild before Task 2:

```bash
mvn -B -DskipTests package
```

```bash
git add gateway/src/main/resources/application.yaml
git commit -m "chore(gateway): read the config server address from the environment

Refs #54"
```

---

## Task 2: The gateway drops the broker address the config repository already carries

Covers acceptance criterion 1 on the gateway side, and criteria 2, 3 and 4.

Criteria 2, 3 and 4 pass *before* this change as well as after, because the config server's property source already outranks the module's (finding 1) — that is precisely the issue's point, that the module's copy "has never had an effect". They are regression checks on a deletion, and the plan says so rather than pretending the deletion turns them from red to green. The one thing that is genuinely red here is the duplicate itself, and Step 1 observes it in two places: in the two files, and at runtime in the property-source list.

**Files:**
- Modify: `gateway/src/main/resources/application.yaml:16-22` — the `environment:` block under the `bus` binder

**Interfaces:**
- Consumes: Task 1's `.scratch/Fetch.java`, the Redpanda container, the config server on 8888, and the gateway jar built at the end of Task 1.
- Produces: a gateway whose only source for `spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers` is `configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml`. The binder name `bus`, the type `kafka` and the bindings `springCloudBusInput`/`springCloudBusOutput` are unchanged and every later service still names them.

- [ ] **Step 1: Run the failing check**

First on the two files:

```bash
grep -n "brokers" gateway/src/main/resources/application.yaml
```

```bash
git fetch origin config:refs/remotes/origin/config
```

```bash
git show origin/config:application.yaml
```

Expected, and this is the failure — the same key, with the same value, in both places:

```
22:                      brokers: localhost:9092
```

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

Then at runtime, against the gateway left running at the end of Task 1:

```bash
java .scratch/Fetch.java "http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers"
```

Expected: `200`, and a body in which **two** property sources carry a `"property"` object for the key — `configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml` with `"value":"localhost:9092"`, and `Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'` with `"origin":"class path resource [application.yaml] from gateway-0.0.1-SNAPSHOT.jar - 22:32"`. Record the whole body. Stop the gateway.

- [ ] **Step 2: Make the change**

`gateway/src/main/resources/application.yaml`, lines 11 to 23 today:

```yaml
  cloud:
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
```

become:

```yaml
  cloud:
    stream:
      binders:
        bus:
          type: kafka
      bindings:
```

Seven lines removed, nothing else altered. The binder keeps its name and its type, exactly as the issue's Design block requires, because those are identical everywhere and are wiring rather than an operational value.

```bash
mvn -B -DskipTests package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Check criterion 1 and criterion 2 — no duplicate, and the broker still arrives**

```bash
grep -c "brokers" gateway/src/main/resources/application.yaml
```

Expected: `0`. (`grep -c` exits 1 on zero; read the number.)

Start the gateway:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

```bash
until grep -q "Started GatewayApplication\|APPLICATION FAILED" $GATEWAY_LOG; do sleep 2; done; grep -o "Started GatewayApplication in.*" $GATEWAY_LOG
```

Expected: `Started GatewayApplication in N seconds (process running for M)`.

```bash
java .scratch/Fetch.java "http://localhost:8080/actuator/env/spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers"
```

Expected: `200`, with

```json
"property":{"source":"configserver:https://github.com/TheDarkHorse111/ecommerce.git/application.yaml","value":"localhost:9092"}
```

and — this is the part that matters — the entry named `Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'` present in the list but carrying **no** `"property"` object. Exactly one source in the whole list holds the key, and it is the config repository's.

```bash
grep -o "bootstrap.servers = .*" $GATEWAY_LOG | head -1
```

Expected: `bootstrap.servers = [localhost:9092]`. The gateway did not merely read the value, it connected with it.

- [ ] **Step 4: Check criterion 4 — a bus refresh still reaches the gateway**

```bash
grep -c "Received remote refresh request" $GATEWAY_LOG
```

Expected: `0`.

```bash
java .scratch/Fetch.java "http://localhost:8888/actuator/busrefresh" POST
```

Expected: `204`, then an empty line.

```bash
grep -c "Received remote refresh request" $GATEWAY_LOG
```

Expected: `1`.

```bash
grep -c "Started GatewayApplication" $GATEWAY_LOG
```

Expected: `1` — it refreshed, it did not restart. Stop the gateway.

- [ ] **Step 5: Check criterion 3 — `prod` and `local` differ, with no module file changing**

From the config server, both profiles:

```bash
java .scratch/Fetch.java http://localhost:8888/gateway/local
```

Expected: `200`, and `"spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"localhost:9092"` inside the `application.yaml` source, with no such key in the `application-local.yaml` source.

```bash
java .scratch/Fetch.java http://localhost:8888/gateway/prod
```

Expected: `200`, and `"spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"redpanda:9092"` inside the `application-prod.yaml` source, overriding the shared one.

Then from the gateway itself. Run it under `prod`:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

```bash
until grep -q "bootstrap.servers" $GATEWAY_LOG; do sleep 2; done; grep -o "bootstrap.servers = .*" $GATEWAY_LOG | head -1
```

Expected: `bootstrap.servers = [redpanda:9092]`.

This run will not finish starting, because no host called `redpanda` resolves on the build machine; it will loop on `Rebootstrapping with Cluster(id = null, nodes = [redpanda:9092 ...])`. That is the confirmation, not a problem. Stop it as soon as the line appears.

The two runs differ by a command-line profile and nothing else — `git status --short` at this point names no file, and `gateway/src/main/resources/application.yaml` is byte-identical between them. That is what "with no module file changing" means.

- [ ] **Step 6: Confirm the gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, four modules, `Tests run: 1` for `gateway`.

```bash
mvn -B -DskipTests package
```

```bash
git add gateway/src/main/resources/application.yaml
git commit -m "chore(gateway): drop the broker address the config repository already carries

Refs #54"
```

---

## Task 3: The config repository sheds the two static keys it is holding

Covers acceptance criterion 6 and the remainder of criterion 1. This is the only task that touches the `config` branch.

The branch is a live input to a running config server, so the work is done on `config-54`, pushed, and verified by pointing a config server at that label with `--spring.cloud.config.server.git.default-label`. Only once the verification is recorded does `config` fast-forward onto it. The `config` branch has no pull request and is never merged into `master`; publishing it is the fast-forward push in Step 6, and the diff a reviewer reads is the one quoted in the pull request body for `issue-54`.

**Files, all on branch `config`:**
- Modify: `application.yaml` — remove `spring.jpa.hibernate.ddl-auto: validate` and its now-childless `hibernate:` parent
- Delete: `catalog-service.yaml`

**Interfaces:**
- Consumes: Task 1's `.scratch/Fetch.java`, the Redpanda container, and the `config-server` jar.
- Produces: a five-file `config` branch. `spring.jpa.hibernate.ddl-auto` and `server.port: 8081` are no longer served to anyone; #14 adds both to `catalog-service/src/main/resources/application.yaml`. `spring.jpa.show-sql` and the shared broker address are still served, unchanged.

- [ ] **Step 1: Run the failing check**

The 8888 config server from Task 1 must still be running. Ask it what it serves:

```bash
java .scratch/Fetch.java http://localhost:8888/gateway/local
```

Expected, and this is half the failure — a key that is `validate` in every environment, served from the config repository to an application that has no database:

```json
{"name":".../application.yaml","source":{"spring.cloud.stream...brokers":"localhost:9092","spring.jpa.hibernate.ddl-auto":"validate","spring.jpa.show-sql":false}}
```

```bash
java .scratch/Fetch.java http://localhost:8888/catalog-service/local
```

Expected, and this is the other half — a port, served from the config repository:

```json
{"name":".../catalog-service.yaml","source":{"server.port":8081}}
```

```bash
git ls-tree -r --name-only origin/config
```

Expected: the six files of finding 9.

- [ ] **Step 2: Make the change on a branch off `config`**

Everything on `issue-54` is committed at this point, so switching branches is safe.

```bash
git switch -c config-54 origin/config
```

`application.yaml` becomes exactly this, and nothing else:

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
    show-sql: false
```

`spring.jpa.hibernate.ddl-auto` is gone and so is the `hibernate:` key that held it, because a mapping with no children is not valid here and would bind as null. The broker address stays: `application-prod.yaml` overrides it with `redpanda:9092`, so it is not the same in every environment. `spring.jpa.show-sql` stays: `catalog-service-local.yaml` overrides it with `true`, so neither is it.

```bash
git rm catalog-service.yaml
```

```bash
git add application.yaml
git commit -m "chore(config): move ddl-auto and the catalog-service port into the modules

Refs #54"
```

```bash
git push -u origin config-54
```

- [ ] **Step 3: Verify against the new label before publishing it**

Start a config server on 8889 pointed at `config-54`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --server.port=8889 --spring.cloud.config.server.git.default-label=config-54
```

```bash
until grep -q "Started ConfigServerApplication\|APPLICATION FAILED" $CONFIG_LOG_8889; do sleep 2; done; grep -o "Started ConfigServerApplication in.*" $CONFIG_LOG_8889
```

Expected: `Started ConfigServerApplication in N seconds (process running for M)`.

```bash
java .scratch/Fetch.java http://localhost:8889/gateway/local
```

Expected: `200`, with the `application.yaml` source now reading exactly

```json
"source":{"spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers":"localhost:9092","spring.jpa.show-sql":false}
```

No `ddl-auto`, and the broker address untouched.

```bash
java .scratch/Fetch.java http://localhost:8889/catalog-service/local
```

Expected: `200`, with exactly three property sources — `catalog-service-local.yaml`, `application-local.yaml`, `application.yaml` — no `catalog-service.yaml`, and no `server.port` anywhere in the body.

```bash
java .scratch/Fetch.java http://localhost:8889/catalog-service/prod
```

Expected: `200`, with exactly three property sources — `catalog-service-prod.yaml`, `application-prod.yaml`, `application.yaml` — and again no `server.port`.

- [ ] **Step 4: Check that the gateway is unaffected by the change**

Still against 8889, so the gateway reads the new branch. Task 1's change is what makes this possible:

```bash
CONFIG_SERVER_URI=http://localhost:8889 java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

```bash
until grep -q "Started GatewayApplication\|APPLICATION FAILED" $GATEWAY_LOG; do sleep 2; done; grep -o "Fetching config from server at.*\|Started GatewayApplication in.*\|bootstrap.servers = .*" $GATEWAY_LOG | head -4
```

Expected:

```
Fetching config from server at : http://localhost:8889
Fetching config from server at : http://localhost:8889
Started GatewayApplication in N seconds (process running for M)
bootstrap.servers = [localhost:9092]
```

```bash
java .scratch/Fetch.java "http://localhost:8889/actuator/busrefresh" POST
```

Expected: `204`.

```bash
grep -c "Received remote refresh request" $GATEWAY_LOG
```

Expected: `1`. Stop the gateway and the 8889 config server.

- [ ] **Step 5: Publish the branch**

```bash
git switch config
```

If the local `config` branch does not exist, `git switch -c config origin/config` first.

```bash
git merge --ff-only config-54
```

```bash
git push origin config
```

```bash
git ls-tree -r --name-only origin/config
```

Expected, after the push:

```
application-local.yaml
application-prod.yaml
application.yaml
catalog-service-local.yaml
catalog-service-prod.yaml
```

- [ ] **Step 6: Confirm the live server serves the new branch, and return to `issue-54`**

The 8888 config server has been running since Task 1 with a clone of the old branch. Stop it and start it again so it fetches:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

```bash
until grep -q "Started ConfigServerApplication\|APPLICATION FAILED" $CONFIG_LOG; do sleep 2; done; grep -o "Started ConfigServerApplication in.*" $CONFIG_LOG
```

```bash
java .scratch/Fetch.java http://localhost:8888/gateway/local
```

Expected: no `ddl-auto` in the body.

```bash
java .scratch/Fetch.java http://localhost:8888/catalog-service/local
```

Expected: no `catalog-service.yaml` source and no `server.port`.

```bash
git switch issue-54
```

No commit is made on `issue-54` in this task; the commit for this work lives on `config`.

---

## Task 4: The audit, and the two servers that change nothing

Covers acceptance criterion 6 in full, criterion 1 in full, and criteria 7 and 8. It changes no file and ends with no commit; its deliverable is the key inventory that goes into the pull request body, and the evidence that the two applications which are not config clients were not disturbed.

**Files:**
- Read: `gateway/src/main/resources/application.yaml`, `config-server/src/main/resources/application.yaml`, `discovery-server/src/main/resources/application.yaml`, and all five files on the `config` branch

**Interfaces:**
- Consumes: everything above. Produces: the table below, verified, and nothing else.

- [ ] **Step 1: Run the failing check — the key intersection**

The failing state is the one recorded at Task 2 Step 1 and Task 3 Step 1: the gateway and the branch both held `...brokers`, and the branch held `ddl-auto` and `server.port`. Re-run the intersection now that Tasks 1 to 3 are done, and confirm it has collapsed to the single sanctioned entry.

```bash
grep -rn "brokers\|ddl-auto\|server:\|port:" gateway/src/main/resources/application.yaml config-server/src/main/resources/application.yaml discovery-server/src/main/resources/application.yaml
```

```bash
git show origin/config:application.yaml
```

```bash
git show origin/config:application-local.yaml
```

```bash
git show origin/config:application-prod.yaml
```

```bash
git show origin/config:catalog-service-local.yaml
```

```bash
git show origin/config:catalog-service-prod.yaml
```

Read all eight files and fill in the intersection. Expected: exactly one key appears on both sides —

```
spring.cloud.stream.binders.bus.environment.spring.cloud.stream.kafka.binder.brokers
  config-server/src/main/resources/application.yaml
  config branch application.yaml
```

and nothing else. `gateway` and the branch now share no key. If any other pair turns up, the task is red and the extra key must be resolved before Step 2.

- [ ] **Step 2: Record the verdict on every key that stays**

This table is the audit. Confirm each row against the files read in Step 1; it goes into the pull request body verbatim.

Module side, all present because of the rule's first question — what identifies the application, what finds the config repository, what is static wiring:

| Key | Module | Why it stays |
| --- | --- | --- |
| `server.port` | gateway 8080, config-server 8888, discovery-server 8761 | A port is static. Named in the issue as the reason `catalog-service.yaml` is deleted. |
| `spring.application.name` | gateway, config-server | Named in the issue's list of what stays. It is the key the config server keys everything else by. |
| `spring.profiles.active` | gateway | Named in the issue's list of what stays. It selects which files the config server merges. |
| `spring.config.import` | gateway | Named in the issue: it cannot come from the config repository, because it is what finds the config repository. |
| `spring.cloud.stream.binders.bus.type`, `spring.cloud.stream.bindings.springCloudBus*.binder` | gateway, config-server | Named in the issue: "the binder name, type and bindings stay, because they are identical everywhere". Wiring, not an operational value. |
| `spring.cloud.stream...binder.brokers` | config-server | Finding T1. Named in the issue as "config-server's own git and bus settings"; required by criterion 7. config-server is not a config client and cannot read this from the repository it serves. |
| `spring.cloud.config.server.git.uri`, `.default-label` | config-server | Named in the issue. Same reason: this is what finds the config repository. Spec section 3 pins the URI to the GitHub remote under both profiles. |
| `spring.cloud.config.server.monitor.github.webhook-secret` | config-server | A secret, so by the rule it belongs outside the module — but config-server cannot read its own configuration from the config server, so it reads `${GITHUB_WEBHOOK_SECRET:}` from the environment instead, the same shape Task 1 gives `CONFIG_SERVER_URI`. Spec section 3 defers real secret storage to a vault. |
| `spring.cloud.gateway.server.webmvc.routes` | gateway | Spec section 3: routes are wiring, ship with the service, and are deliberately not in the config repository. |
| `management.endpoints.web.exposure.include`, `management.endpoint.env.show-values` | gateway, config-server | Static per service and different per service — `env` on the gateway, `busrefresh` on the config server. Putting them in a shared file would give each service the other's endpoints. |
| `eureka.client.register-with-eureka`, `.fetch-registry` | discovery-server | Named in the issue's list of what stays: "discovery-server's port and `register-with-eureka` pair". |

Config repository side, all present because of the rule's second question — what varies by environment, is secret, or is worth changing while the system is running:

| Key | File | Why it stays |
| --- | --- | --- |
| `spring.cloud.stream...binder.brokers` | `application.yaml`, `application-prod.yaml` | An address. `localhost:9092` shared, `redpanda:9092` in prod. Verified different in finding 6. |
| `spring.jpa.show-sql` | `application.yaml`, `catalog-service-local.yaml` | SQL echo. `false` shared, `true` for catalog under local. Spec section 3 lists it among the profile-varying keys. |
| `logging.level.root` | `application-local.yaml`, `application-prod.yaml` | Verbosity. `DEBUG` against `INFO`. The one key the spec keeps deliberately different so that a broken merge is distinguishable from success. |
| `eureka.client.service-url.defaultZone` | `application-local.yaml`, `application-prod.yaml` | Finding T2. Identical today only because prod is not real; an address by nature, and named in spec section 3's list of profile-varying keys. Deleting it would unregister every service under both profiles. |
| `spring.datasource.url`, `.username`, `.password` | `catalog-service-local.yaml`, `catalog-service-prod.yaml` | An address and credentials. Named in the issue: "a datasource URL, credentials and SQL echo all vary by environment". Identical across the two files for the same reason as `defaultZone`. |

Nothing else is on either side. Criterion 6 is satisfied: no port, no `ddl-auto`, and every remaining key is either overridden by a profile today or is an address, a credential or verbosity that will be.

- [ ] **Step 3: Check criterion 7 — config-server keeps its broker and starts with the branch unreachable**

```bash
grep -c "brokers: localhost:9092" config-server/src/main/resources/application.yaml
```

Expected: `1`. This is the key finding T1 says must survive; if it is `0`, someone applied criterion 1 literally and criterion 7 is broken.

```bash
git diff --stat master...HEAD -- config-server discovery-server
```

Expected: **no output**. Neither module was touched by this issue.

Now start it against a repository that does not exist, on a spare port so the 8888 server stays up:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar --server.port=8890 --spring.cloud.config.server.git.uri=https://github.com/TheDarkHorse111/definitely-not-a-real-repo.git
```

```bash
until grep -q "Started ConfigServerApplication\|APPLICATION FAILED" $CONFIG_LOG; do sleep 2; done; grep -o "Started ConfigServerApplication in.*\|bootstrap.servers = .*" $CONFIG_LOG | head -2
```

Expected:

```
Started ConfigServerApplication in N seconds (process running for M)
bootstrap.servers = [localhost:9092]
```

It started, and it still has its own broker address. Stop it.

- [ ] **Step 4: Check criterion 8 — both servers start with no config server present**

For `config-server`, the evidence is that it never asks:

```bash
grep -c "Fetching config from server at" $CONFIG_LOG
```

Expected: `0`, on the log of the run just made and on the 8888 run from Task 3. It has `spring-cloud-config-client` on its classpath transitively but no `spring.config.import`, so the client never runs.

For `discovery-server`, stop the 8888 config server first, so that nothing is listening on it, and stop Redpanda too — `discovery-server` carries no bus:

```bash
docker stop redpanda
```

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

```bash
until grep -q "Started DiscoveryServerApplication\|APPLICATION FAILED" $DISCOVERY_LOG; do sleep 2; done; grep -o "Started DiscoveryServerApplication in.*" $DISCOVERY_LOG
```

Expected: `Started DiscoveryServerApplication in N seconds (process running for M)`.

```bash
grep -c "Fetching config from server at" $DISCOVERY_LOG
```

Expected: `0`. Stop it.

---

## Task 5: Clean up and confirm the tree

No acceptance criterion. Its deliverable is a clean working tree, a stopped container, a green gate and a two-file diff, and it ends with no commit.

**Files:**
- Delete: `.scratch/`

**Interfaces:**
- Consumes: everything above. Produces: nothing.

- [ ] **Step 1: Stop everything**

Stop every remaining background task, then:

```bash
docker rm -f redpanda
```

Expected: `redpanda`.

```bash
docker ps --format "{{.Names}}"
```

Expected: no output.

- [ ] **Step 2: Delete the scratch directory**

`.scratch/` is not covered by `.gitignore`, which lists `target/` only, so it must be removed rather than left untracked.

```bash
rm -rf .scratch
```

```bash
git status --short
```

Expected: **no output at all**.

- [ ] **Step 3: Confirm the gate and the shape of both branches**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`, the reactor listing `ecommerce`, `discovery-server`, `gateway` and `config-server`, with `Tests run: 1` for `gateway` and no tests for the other three. No test was added or removed by this issue.

```bash
git diff --stat master...HEAD
```

Expected exactly two entries — the one file this issue modifies on `master`, plus this plan, committed onto `issue-54` before implementation began:

```
 docs/superpowers/plans/2026-09-10-static-configuration-into-modules.md
 gateway/src/main/resources/application.yaml
```

Nothing under `config-server/`, nothing under `discovery-server/`, nothing under `.github/`, and no change to any `pom.xml`.

```bash
git log --oneline origin/config -3
```

Expected: the newest entry is `chore(config): move ddl-auto and the catalog-service port into the modules`.

```bash
git branch -D config-54
```

```bash
git branch
```

Expected: `config`, `issue-54`, `master`, with `issue-54` current.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #54 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1 | No key appears in both a module `application.yaml` and a file on the `config` branch | 2, 4 | 2.1, 2.3, 4.1 | `grep -n brokers gateway/.../application.yaml` prints `22: brokers: localhost:9092` while `git show origin/config:application.yaml` prints the same key, and `/actuator/env/…brokers` shows two sources carrying the value | `grep -c` prints `0` on the gateway file; `/actuator/env/…brokers` shows the classpath source with no `"property"` object; the intersection in 4.1 is the single config-server entry that finding T1 and criterion 7 require |
| 2 | The gateway starts with no broker address of its own and reaches the broker the config repository names | 2 | 2.1, 2.3 | the module file holds `brokers: localhost:9092` | `Started GatewayApplication`, `"source":"configserver:…/application.yaml","value":"localhost:9092"`, and `bootstrap.servers = [localhost:9092]` in the log |
| 3 | `prod` and `local` resolve different broker addresses with no module file changing | 2 | 2.5 | the module file holds a third copy of the address, which would be the same under both profiles if it were ever read | `/gateway/local` → `localhost:9092`, `/gateway/prod` → `redpanda:9092`; the gateway itself logs `bootstrap.servers = [localhost:9092]` then `bootstrap.servers = [redpanda:9092]` from one unchanged file, `git status --short` empty between the runs |
| 4 | A refresh broadcast over the bus still reaches the gateway | 2 | 2.4 | `grep -c "Received remote refresh request"` prints `0` | `POST /actuator/busrefresh` → `204`, count `1`, `Started GatewayApplication` count still `1` |
| 5 | The gateway reaches a config server at a non-default address with only `CONFIG_SERVER_URI` set, and starts against `localhost:8888` with the variable unset | 1 | 1.3, 1.5 | with `CONFIG_SERVER_URI=http://localhost:8889` exported, the log says `Fetching config from server at : http://localhost:8888` — the variable is ignored | set → `http://localhost:8889` + `Started GatewayApplication`; unset → `http://localhost:8888` + `Started GatewayApplication` |
| 6 | The `config` branch carries no port, no `ddl-auto` and no other key that is the same in every environment | 3, 4 | 3.1, 3.3, 4.2 | `/gateway/local` serves `"spring.jpa.hibernate.ddl-auto":"validate"` and `/catalog-service/local` serves `{"server.port":8081}` | neither appears; `catalog-service.yaml` is gone from `git ls-tree`; the Step 4.2 table records why each of the five remaining keys varies, including finding T2 |
| 7 | config-server keeps its local broker address and still starts with the `config` branch unreachable | 4 | 4.3 | this is the criterion a literal reading of criterion 1 would break; `grep -c "brokers: localhost:9092" config-server/...` must print `1`, and `git diff --stat master...HEAD -- config-server` must be empty | started against a non-existent git repository: `Started ConfigServerApplication`, `bootstrap.servers = [localhost:9092]` |
| 8 | discovery-server and config-server still start with the config server absent | 4 | 4.4 | — | `grep -c "Fetching config from server at"` prints `0` for both; `Started DiscoveryServerApplication` with no config server and no broker running |

Criterion 1 spans Tasks 2 and 4 because the gateway half is a deletion with its own red and the whole-repository half is an audit that can only run once every other change has landed. Criteria 2, 3 and 4 are all Task 2 and all guard the same deletion; a reviewer cannot sensibly accept one and reject another, since they are the same file edit observed three ways.

Criteria 7 and 8 have no red in the ordinary sense: they were true before this issue and must still be true after. They are in the plan because the most likely way to implement criterion 1 wrongly is to delete `config-server`'s broker address, and Step 4.3 is what catches that.

The issue's Design block is covered too, and three sentences deserve naming because no criterion tests them:

- **"the binder name, type and bindings stay, because they are identical everywhere"** — Task 2 Step 2 removes seven lines and leaves `type: kafka` and both bindings in place.
- **"The default keeps `mvn -B clean verify` and a local run working with nothing exported"** — Task 1 Step 5's second half, and the gate in Tasks 1, 2 and 5. The gate never starts a Spring context, so it is indifferent to either change; the local run is the real check.
- **"catalog-service brings its own copy in #14"** — nothing in this issue creates `catalog-service`. The pull request body must say that `ddl-auto` and `server.port: 8081` are now homeless until #14 lands, and that this breaks nothing because no JPA service exists.

---

## Pull request

Title: `chore(gateway): move static configuration into the modules (#54)`

Body lists each of the eight acceptance criteria with the command and the output that verified it, and ends with `Closes #54`.

It must lead with the two readings, because a reviewer checking the criteria literally against the diff will think both are unmet:

- **`config-server` still carries `brokers: localhost:9092`, and criterion 1 says no key may be in both places.** It stays because the same issue's Design block lists "config-server's own git and bus settings" among what stays in a module, and because criterion 7 requires it in so many words. `config-server` has no `spring.config.import`; measured before planning, `grep -c "Fetching config from server at"` on its log is `0`. The branch's copy can never reach it, so there is no precedence contest and nothing to disagree silently. Criterion 1 is implemented as: no key in both the branch and a module that *imports* the config server.
- **`eureka.client.service-url.defaultZone` is identical in `application-local.yaml` and `application-prod.yaml`, and criterion 6 says nothing may be the same in every environment.** It stays because spec section 3 names Eureka `defaultZone` among the keys that differ between profiles, and says in the next paragraph that `prod` deliberately carries the same values as `local` except log level, since real production values are not committed. The same applies to the datasource URL and credentials in the two `catalog-service-*` files, which the issue says explicitly must stay.

And the findings a reviewer will otherwise trip over:

- **The module's `brokers` really had never had an effect.** `/actuator/env` before the deletion showed the config server's source winning with the module's copy sitting below `configClient`, both reading `localhost:9092`. Criteria 2, 3 and 4 therefore passed before the change as well as after; they are regression checks on a deletion, not proof that the deletion did something.
- **`CONFIG_SERVER_URI` is now the gateway's only outside input, and a typo in it is fatal.** `spring.config.import` is not prefixed `optional:`, so an unreachable address is `Could not locate PropertySource and the resource is not optional, failing` and exit 1, not a warning. That is #11's specified form and is unchanged by this issue.
- **`spring.config.import` must stay quoted.** Unquoted, `configserver:` is a YAML mapping key and the gateway cannot parse its own file. The added `${...}` does not change that.
- **The `config` branch lost two keys that nothing owns yet.** `spring.jpa.hibernate.ddl-auto` and `server.port: 8081` are homeless until #14 creates `catalog-service`. Nothing breaks in the meantime because no JPA service exists and no application is named `catalog-service`; `/catalog-service/local` still answers, now with three property sources instead of four.
- **The `config` branch is published by a fast-forward push, not a pull request.** It has no merge target — `master` never merges it — so the only place a reviewer sees that diff is this pull request body. It is quoted there in full: two lines removed from `application.yaml` and one file deleted.
