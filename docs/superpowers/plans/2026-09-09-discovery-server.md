# discovery-server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `discovery-server`, a registry-only Eureka server on port 8761, as the first flat module under the parent POM built by issue #1.

**Architecture:** One Maven module, `discovery-server/`, parented by the root POM and listed in its `<modules>`. It has one dependency, `spring-cloud-starter-netflix-eureka-server`, one Java class carrying `@SpringBootApplication` and `@EnableEurekaServer`, and one `application.yaml` that sets the port and turns the embedded Eureka *client* off so the server registers with nobody and fetches nothing. There is no controller, service, repository or JPA layer, so the layering rules have nothing to bite on here.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Cloud 2025.1.2, `spring-cloud-starter-netflix-eureka-server` 5.0.2, Error Prone 2.50.0, maven-compiler-plugin 3.16.0.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` (section 3 service table, section 6 "Root POM" and "Static analysis", section 7 step 2). Issue #10 is the specification for this increment. Issue #1, already merged, is its dependency and is planned in `docs/superpowers/plans/2026-09-09-repository-restructure-parent-pom.md`.

---

## Global Constraints

Copied from `CLAUDE.md` and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. There is no separate lint step. `.github/workflows/build.yml` runs exactly that on Corretto 25.
- **Port:** 8761. **Package root:** `com.thedarkhorse.discovery`. **Module directory:** `discovery-server/`, flat at the repository root.
- **Eureka:** `register-with-eureka: false`, `fetch-registry: false`. Registry only — this server registers with nobody and fetches nothing.
- **Annotations.** `@Component`, `@Service` and `@Repository` are forbidden. `@RestController` and `@RestControllerAdvice` are the only stereotypes and are controller-layer only. This module has neither. `@SpringBootApplication` and `@EnableEurekaServer` are not stereotypes and are not covered by that ban.
- **TDD.** No class is written before a failing test for it has been seen to fail. TDD applies to code with behaviour. This module contains no branch, loop, validation, orchestration, arithmetic or HTTP contract of our own writing, so per `CLAUDE.md` it gets **no JUnit test**, and `src/test/` is not created. Every red step below is a command whose observed failure is recorded verbatim. **Never test the framework** — do not write a `@SpringBootTest` asserting Eureka's own endpoints, and note the spec forbids a Spring context in tests outright.
- **Comments.** None. No Javadoc, no comment blocks, and none in `pom.xml`, `application.yaml` or any properties file. Rationale lives in the spec and in this plan.
- **Scope.** Build only what issue #10 asks for. In particular do **not** add `spring.application.name`, `eureka.instance.*`, `eureka.server.*`, an actuator dependency, `spring-boot-starter-validation`, a Dockerfile, a Compose file, or a `README`. None of them is requested. See "Deliberate omissions" below for the two the logs will tempt you into.
- **Every plugin carries an explicit version** — but this module declares only `spring-boot-maven-plugin`, with no version and no configuration, because the root `pluginManagement` supplies both. That is the pattern the spec fixes for every runnable module.
- **Conventional Commits** with `discovery` as the scope, one commit per completed red-green cycle, every commit green, each carrying a `Refs #10` footer. No `Co-Authored-By` trailer, no generated-with footer.
- **Pull request** title ends `(#10)`; body ends `Closes #10`. Merge with rebase, never squash.

---

## Verified Findings

Every claim issue #10 makes about tool behaviour was executed in this repository before planning on top of it, using a throwaway module at `.scratch/discovery-probe/` parented to the real root `pom.xml` by `<relativePath>` and never listed in `<modules>`. The probe was deleted afterwards.

**No claim in the issue turned out to be false.** All five acceptance criteria were reproduced. What follows are the four things the issue does not say that the plan depends on, and they are the reason several steps below look fussier than the criterion they serve.

### Confirmed

1. **Eureka server 5.0.2 is what the pinned train resolves.** `mvn dependency:tree` on the probe:

   ```
   \- org.springframework.cloud:spring-cloud-starter-netflix-eureka-server:jar:5.0.2:compile
      +- org.springframework.cloud:spring-cloud-netflix-eureka-server:jar:5.0.2:compile
      |  +- org.springframework.boot:spring-boot-starter-web:jar:4.1.0:compile
      |  +- org.springframework.boot:spring-boot-starter-freemarker:jar:4.1.0:compile
      |  +- org.springframework.cloud:spring-cloud-netflix-eureka-client:jar:5.0.2:compile
      |  +- com.netflix.eureka:eureka-core-jersey3:jar:2.0.6:compile
   ```

   No version is written in the module POM; `spring-cloud-dependencies` 2025.1.2 supplies it. The dashboard's FreeMarker templates and the web server arrive transitively — nothing extra is needed for the dashboard to render.

2. **The module builds under the root parent and Error Prone runs on it.** `compiler:3.16.0:compile ... Compiling 1 source file with javac [debug parameters release 25]`. No `forked`, which is what the spec requires.

3. **`spring-boot:repackage` fires from the inherited `pluginManagement` execution** when the module declares the plugin with no version and no configuration:

   ```
   [INFO] --- spring-boot:4.1.0:repackage (default) @ discovery-probe ---
   [INFO] Replacing main artifact .../discovery-probe-0.0.1-SNAPSHOT.jar with repackaged archive,
          adding nested dependencies in BOOT-INF/.
   ```

4. **The configured server starts on 8761 and its registry is empty.** `Tomcat started on port 8761 (http) with context path '/'`, then `GET /eureka/apps`:

   ```
   HTTP 200
   Content-Type: application/xml
   <applications>
     <versions__delta>1</versions__delta>
     <apps__hashcode></apps__hashcode>
   </applications>
   ```

   With `Accept: application/json`: `{"applications":{"versions__delta":"1","apps__hashcode":"","application":[]}}`. XML is the default representation; the JSON form is the one that literally shows `"application":[]`.

5. **The dashboard renders.** `GET /` returns `HTTP 200`, `Content-Type: text/html;charset=UTF-8`, `<title>Eureka</title>`, and the instances table body reads `<tr><td colspan="4">No instances available</td></tr>`.

6. **`SelfAssignment` fails `mvn verify` in this module, and reverting it passes.**

   ```
   [ERROR] .../Offender.java:[8,14] [SelfAssignment] Variable assigned to itself
   [ERROR]     (see https://errorprone.info/bugpattern/SelfAssignment)
   [ERROR]   Did you mean 'this.name = name;'?
   [INFO] BUILD FAILURE
   ```

   Deleting the file returned `BUILD SUCCESS`.

7. **The root build configuration survives contact with its first real module.** `mvn help:evaluate -Dexpression=lombok.version` reports `null object or invalid expression` — the property genuinely is not defined, because importing a BOM brings `dependencyManagement` and not properties. The literal `${lombok.version}` in the root `annotationProcessorPaths` nevertheless works: maven-compiler-plugin 3.16.0 resolves an unresolvable processor-path version against `dependencyManagement`, which pins Lombok at 1.18.46. A `@Data` class added to the probe compiled and its generated accessors were callable. **No action.** This is recorded only because issue #10 says this module is the first to exercise issue #1's build configuration, and a reviewer who greps the root POM will otherwise think it is broken.

### Four things the issue does not say

**A. `@EnableEurekaServer` is mandatory, and omitting it breaks startup rather than the registry.**

The issue's Design block names the starter and the two client flags but never mentions the annotation. With `@SpringBootApplication` alone the application does not start at all:

```
ERROR ... o.s.c.n.e.s.EurekaRegistration : error getting CloudEurekaClient

org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name
'scopedTarget.eurekaClient' ...: Unsatisfied dependency expressed through method 'eurekaClient'
parameter 3: No qualifying bean of type
'com.netflix.discovery.shared.transport.jersey.TransportClientFactories<?>' available

ERROR ... o.s.boot.SpringApplication : Application run failed
org.springframework.context.ApplicationContextException: Failed to start bean 'eurekaAutoServiceRegistration'
Caused by: java.lang.NullPointerException: Cannot invoke
"org.springframework.cloud.netflix.eureka.CloudEurekaClient.getApplications()" because the return value of
"...EurekaRegistration.getEurekaClient()" is null
```

The process exits with code 1. Task 1 uses exactly this as its red.

**B. `GET /eureka/apps` is served from a response cache that refreshes every 30 seconds, so checking it straight after startup proves nothing.**

Eureka's read-only response cache is on by default. An instance whose registration the server logged as

```
c.n.e.registry.AbstractInstanceRegistry : Registered instance UNKNOWN/... with status UP (replication=false)
```

was still absent from `GET /eureka/apps` on two consecutive polls afterwards and only appeared on a later one. The staleness runs one way only: a registry that is filling up still *reads* as empty. So a check taken at t+2s passes on a correctly configured server **and** on a broken one. **Every `/eureka/apps` check in this plan waits at least 40 seconds after startup**, which is why `Probe.java` below takes a settle argument.

**C. An empty `/eureka/apps` does not by itself prove `register-with-eureka: false`.**

A build with no `application.yaml` at all — default `register-with-eureka: true`, default `defaultZone` of `http://localhost:8761/eureka/` — also returns an empty list after a 40-second settle, because with nothing listening on 8761 the self-registration simply fails:

```
DiscoveryClient_UNKNOWN/... : registering service...
WARN  DiscoveryClient_UNKNOWN/... - registration failed Cannot execute request on any known server
com.netflix.discovery.shared.transport.TransportException: Cannot execute request on any known server
INFO  DiscoveryClient_UNKNOWN/... - was unable to refresh its cache! This periodic background refresh
      will be retried in 30 seconds. status = Cannot execute request on any known server
```

Task 2 therefore pairs the acceptance criterion with a log assertion: the configured server must log `Client configured to neither register nor query for data.` and must log neither `registering service...` nor `unable to refresh its cache`.

**D. One log line is a red herring.** Even correctly configured, the server logs

```
INFO  o.s.c.n.e.s.EurekaServiceRegistry : Registering application UNKNOWN with eureka with status UP
```

and on shutdown `Unregistering application UNKNOWN with eureka with status DOWN`. No registration happens — the registry stays empty and the dashboard says "No instances available". `EurekaAutoServiceRegistration` flips the local instance status while the underlying client is configured not to talk to anyone. Do not chase this line, and do not add configuration to silence it. `UNKNOWN` is the application name because `spring.application.name` is not set, which is deliberate (see "Deliberate omissions").

### Tooling constraint: there is no `curl` and no `wget`

The implementing job's allowlist (`.github/workflows/claude-advance.yml`, the `implement` job) is:

```
Edit,Write,Bash(mvn:*),Bash(git:*),Bash(gh:*),Bash(ls:*),Bash(cat:*),Bash(find:*),Bash(mkdir:*),
Bash(mv:*),Bash(cp:*),Bash(rm:*),Bash(cd:*),Bash(grep:*),Bash(java:*),Bash(javap:*),Bash(printf:*),
Bash(test:*),Bash(jar:*),Bash(unzip:*),Bash(head:*),Bash(tail:*),Bash(wc:*),Bash(sort:*),
Bash(diff:*),Bash(echo:*),Bash(docker:*)
```

No HTTP client is on it — neither `curl` nor `wget`. `Bash(java:*)` is, and Java's single-file source launcher runs a `.java` file directly, so **Task 2 Step 1 creates `.scratch/Probe.java` and every HTTP check in this plan runs through it.** That exact file was used for all the findings above.

Several checks in this plan need the server running while the check executes. Start the jar as a **background** Bash task and stop it with the harness's background-task stop when the task is done; `kill` and `pkill` are not on the allowlist. Only one server may hold 8761 at a time, so stop the previous one before starting the next.

When a background Bash task starts, the harness reports the file its output is being written to, for example `/tmp/.../tasks/bhjia4iwz.output`. Several steps below grep that file. They are written with `TASK_OUTPUT` standing in for it — **substitute the real path the harness printed**, because it is assigned per task and cannot be known in advance. `Bash(grep:*)`, `Bash(cat:*)` and `Bash(tail:*)` are all on the allowlist, so any of them reads it.

### Deliberate omissions

- **`spring.application.name` is not set.** The issue does not ask for it, and `CLAUDE.md` forbids configuration knobs nobody requested. The cost is that the log and the misleading line in finding D read `UNKNOWN`. Nothing depends on the name: this server registers with no one, and the spec routes nothing to it through the gateway.
- **No validation starter.** Startup logs `Failed to set up a Bean Validation provider: jakarta.validation.NoProviderFoundException`. It is a benign `INFO` from `OptionalValidatorFactoryBean`, the server starts and serves normally, and the spec scopes `spring-boot-starter-validation` to the API layer. Do not add it to make the line go away.

---

## File Structure

Committed by this plan:

| Path | Responsibility | Action |
| --- | --- | --- |
| `pom.xml` | Root parent. Gains one `<module>discovery-server</module>` entry inside the currently empty `<modules/>`. Nothing else in it changes. | Modify |
| `discovery-server/pom.xml` | Module coordinates, one dependency, one plugin declaration with no version and no configuration. | Create |
| `discovery-server/src/main/java/com/thedarkhorse/discovery/DiscoveryServerApplication.java` | The whole application: `@SpringBootApplication`, `@EnableEurekaServer`, `main`. | Create |
| `discovery-server/src/main/resources/application.yaml` | Port 8761, `register-with-eureka: false`, `fetch-registry: false`. | Create |

Not created: `discovery-server/src/test/`. There is no code with behaviour to test, and the spec forbids a Spring context in tests.

Not touched: `lombok.config`, `.mvn/jvm.config`, `.github/`, `CLAUDE.md`, `README.md`, the spec, and any other plan.

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/Probe.java` | The HTTP client, because the allowlist has none. Deleted in Task 3. |
| `discovery-server/src/main/java/com/thedarkhorse/discovery/Offender.java` | The deliberate Error Prone violation. Created and deleted inside Task 3, never committed. |

---

## Task 1: The module builds, and its jar starts as a Eureka server

Covers acceptance criterion 4 (`mvn package` on this module produces a jar that runs with `java -jar`) and the "starts" half of criterion 1. The port itself is Task 2.

**Files:**
- Modify: `pom.xml:12` — replace the self-closing `<modules/>` with a `<modules>` element containing one entry
- Create: `discovery-server/pom.xml`
- Create: `discovery-server/src/main/java/com/thedarkhorse/discovery/DiscoveryServerApplication.java`

**Interfaces:**
- Consumes: from issue #1, the parent coordinates `com.thedarkhorse:ecommerce:0.0.1-SNAPSHOT` with `pom` packaging; the `pluginManagement` entry for `org.springframework.boot:spring-boot-maven-plugin` carrying the `repackage` execution; the `spring-cloud-dependencies` 2025.1.2 BOM import that supplies the Eureka starter's version; `.mvn/jvm.config`, without which Error Prone cannot start.
- Produces: the module artifact `com.thedarkhorse:discovery-server:0.0.1-SNAPSHOT`, whose repackaged jar is at `discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar`, and the class `com.thedarkhorse.discovery.DiscoveryServerApplication` with `public static void main(String[] args)`. Task 2 adds `discovery-server/src/main/resources/application.yaml` beside it and changes no Java. Task 3 adds and removes a second class in the same package.

- [ ] **Step 1: Write the failing test**

There is no behaviour here, so per `CLAUDE.md` there is no JUnit test. The failing test is acceptance criterion 4 run as the command it describes. Run it against the repository as it stands:

```bash
mvn -B -pl discovery-server clean package
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -pl discovery-server clean package`

Expected: a non-zero exit, because the module does not exist and is not in the reactor. Maven fails during project selection, before the reactor runs, so there is **no** `BUILD FAILURE` line — the whole output is:

```
[ERROR] [ERROR] Could not find the selected project in the reactor: discovery-server @
[ERROR] Could not find the selected project in the reactor: discovery-server -> [Help 1]
```

Confirm the reactor really is empty today:

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a single line `Building ecommerce 0.0.1-SNAPSHOT` and `--------[ pom ]--------`. No `discovery-server` appears. That is the red.

- [ ] **Step 3: Write the minimal implementation**

In the root `pom.xml`, replace the empty element on line 12:

```xml
    <modules/>
```

with:

```xml
    <modules>
        <module>discovery-server</module>
    </modules>
```

Change nothing else in the root POM.

Create `discovery-server/pom.xml`. No `<relativePath>` — a module in the reactor finds `../pom.xml` by default. No version on the plugin and no `<configuration>`; both come from the root `pluginManagement`. No version on the dependency; it comes from the Spring Cloud BOM. No comments.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.thedarkhorse</groupId>
        <artifactId>ecommerce</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>discovery-server</artifactId>
    <name>discovery-server</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Create `discovery-server/src/main/java/com/thedarkhorse/discovery/DiscoveryServerApplication.java`. Write it **without** `@EnableEurekaServer` for now — Step 5 is the red that earns that annotation.

```java
package com.thedarkhorse.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
```

- [ ] **Step 4: Run it to verify the module now packages**

Run: `mvn -B -pl discovery-server clean package`

Expected: `BUILD SUCCESS`, containing both of these lines. The second is the inherited `repackage` execution firing without the module naming it:

```
[INFO] --- compiler:3.16.0:compile (default-compile) @ discovery-server ---
[INFO] Compiling 1 source file with javac [debug parameters release 25] to target/classes
...
[INFO] --- spring-boot:4.1.0:repackage (default) @ discovery-server ---
[INFO] Replacing main artifact .../discovery-server-0.0.1-SNAPSHOT.jar with repackaged archive,
       adding nested dependencies in BOOT-INF/.
```

If the compile line says `forked`, someone added `<fork>true</fork>`; the spec forbids it, so remove it.

- [ ] **Step 5: Run the jar to verify it fails to start**

Run in the **foreground** — it is expected to exit on its own:

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

Expected: the process fails and exits non-zero. The decisive lines:

```
ERROR ... o.s.c.n.e.s.EurekaRegistration : error getting CloudEurekaClient
org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name
'scopedTarget.eurekaClient' ...: No qualifying bean of type
'com.netflix.discovery.shared.transport.jersey.TransportClientFactories<?>' available
...
ERROR ... o.s.boot.SpringApplication : Application run failed
org.springframework.context.ApplicationContextException: Failed to start bean 'eurekaAutoServiceRegistration'
```

The jar exists and is a Boot jar, so half of criterion 4 holds; it does not run, so the criterion does not. That is the red for `@EnableEurekaServer`.

- [ ] **Step 6: Add `@EnableEurekaServer`**

Edit `discovery-server/src/main/java/com/thedarkhorse/discovery/DiscoveryServerApplication.java` so it reads in full:

```java
package com.thedarkhorse.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
```

- [ ] **Step 7: Run the jar to verify it starts**

```bash
mvn -B -pl discovery-server clean package
```

Then start it as a **background** Bash task, because it does not exit:

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

Expected in its output:

```
INFO ... e.s.EurekaServerInitializerConfiguration : Started Eureka Server
INFO ... o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8080 (http) with context path '/'
INFO ... c.t.d.DiscoveryServerApplication         : Started DiscoveryServerApplication in N seconds
```

Port **8080** is correct at this point — there is no `application.yaml` yet, and that is Task 2's red. Criterion 4 is met: the jar runs.

Expect two benign lines in the same output, both explained under "Deliberate omissions" and finding C. Do not act on either:

```
INFO ... o.s.v.b.OptionalValidatorFactoryBean : Failed to set up a Bean Validation provider: ...
WARN ... DiscoveryClient_UNKNOWN/... - registration failed Cannot execute request on any known server
```

**Stop the background task now.** Task 2 needs port 8761 and starts its own server.

- [ ] **Step 8: Confirm the full gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing `ecommerce` and `discovery-server`.

```bash
git status --short
```

Expected exactly: `M pom.xml`, `?? discovery-server/`. If `.scratch/` appears, that is fine — it is untracked and Task 3 deletes it. Nothing under `.github/` may appear.

```bash
git add pom.xml discovery-server/pom.xml discovery-server/src
git commit -m "$(cat <<'EOF'
feat(discovery): add eureka server module

Refs #10
EOF
)"
```

Confirm `discovery-server/target/` was not staged — the root `.gitignore` already ignores `target/`.

---

## Task 2: Port 8761, registry only

Covers acceptance criteria 1 (starts on 8761), 2 (`GET /eureka/apps` returns an empty application list) and 3 (the dashboard at `/` renders).

**Files:**
- Create: `discovery-server/src/main/resources/application.yaml`
- Create: `.scratch/Probe.java` (temporary, deleted in Task 3)

**Interfaces:**
- Consumes: from Task 1, the module `com.thedarkhorse:discovery-server:0.0.1-SNAPSHOT`, its jar at `discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar`, and `DiscoveryServerApplication` carrying `@SpringBootApplication` and `@EnableEurekaServer`.
- Produces: a server listening on 8761 whose embedded Eureka client is off. No Java changes, so no later task consumes a new signature. `.scratch/Probe.java` exposes `Probe.main(String[])`, invoked as `java .scratch/Probe.java <url> [accept] [settleSeconds]`, and Task 3 deletes it.

- [ ] **Step 1: Write the failing test**

Two pieces. First the HTTP client, because the allowlist has none. Create `.scratch/Probe.java` exactly as below. It retries the connection for up to two minutes so it can be launched before the server is up, then sleeps `settleSeconds` before the request it actually reports — that sleep is what makes the `/eureka/apps` check meaningful rather than vacuous (finding B).

```java
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Probe {

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String accept = args.length > 1 ? args[1] : "*/*";
        long settleSeconds = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(10))
                .build();

        long deadline = System.currentTimeMillis() + 120_000L;
        while (true) {
            try {
                client.send(request, HttpResponse.BodyHandlers.discarding());
                break;
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw e;
                }
                Thread.sleep(1000L);
            }
        }

        Thread.sleep(settleSeconds * 1000L);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("HTTP " + response.statusCode());
        System.out.println("Content-Type: " + response.headers().firstValue("Content-Type").orElse("none"));
        System.out.println(response.body());
    }
}
```

`Probe.java` lives under `.scratch/` and is never committed. It carries no comments, consistent with the standing rule, and it is a throwaway harness rather than production code, so it needs no test of its own.

Second, the check. Start the Task 1 jar as a **background** Bash task:

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

and then run:

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps application/json 40
```

- [ ] **Step 2: Run it to verify it fails**

Expected: `Probe` exhausts its two-minute retry and throws, because nothing is listening on 8761:

```
Exception in thread "main" java.net.ConnectException
	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(...)
	at Probe.main(Probe.java:...)
Caused by: java.nio.channels.ClosedChannelException
```

Confirm why, from the background task's output:

```bash
grep -E 'Tomcat started|registering service|registration failed|unable to refresh' TASK_OUTPUT
```

Expected — the port is wrong and the embedded client is trying to register itself:

```
INFO ... o.s.boot.tomcat.TomcatWebServer : Tomcat started on port 8080 (http) with context path '/'
INFO ... DiscoveryClient_UNKNOWN/... : registering service...
WARN ... DiscoveryClient_UNKNOWN/... - registration failed Cannot execute request on any known server
INFO ... DiscoveryClient_UNKNOWN/... - was unable to refresh its cache! This periodic background
     refresh will be retried in 30 seconds. status = Cannot execute request on any known server
```

That is the red for all three of port, `register-with-eureka` and `fetch-registry`. **Stop the background task.**

- [ ] **Step 3: Write the minimal implementation**

Create `discovery-server/src/main/resources/application.yaml` with exactly this and nothing else. No comments. No `spring.application.name`, no `eureka.instance`, no `eureka.server`, no actuator exposure.

```yaml
server:
  port: 8761

eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
```

- [ ] **Step 4: Run it to verify criterion 1 — the server starts on 8761**

```bash
mvn -B -pl discovery-server clean package
```

Start as a **background** Bash task:

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

Then:

```bash
grep -E 'Tomcat started|Started Eureka Server|Started DiscoveryServerApplication|Client configured' TASK_OUTPUT
```

Expected:

```
INFO ... com.netflix.discovery.DiscoveryClient    : Client configured to neither register nor query for data.
INFO ... e.s.EurekaServerInitializerConfiguration : Started Eureka Server
INFO ... o.s.boot.tomcat.TomcatWebServer          : Tomcat started on port 8761 (http) with context path '/'
INFO ... c.t.d.DiscoveryServerApplication         : Started DiscoveryServerApplication in N seconds
```

Then confirm the two red lines from Step 2 are gone, which is what proves the flags took effect rather than the registration merely failing (finding C):

```bash
grep -cE 'registering service|unable to refresh its cache' TASK_OUTPUT
```

Expected: it prints `0`. `grep -c` exits 1 when the count is zero, so a non-zero exit code here **is** the pass; read the printed number, not the exit status.

Ignore `EurekaServiceRegistry : Registering application UNKNOWN with eureka with status UP`. It is the red herring documented in finding D; Step 5 and Step 6 are what actually settle whether anything is registered.

- [ ] **Step 5: Run it to verify criterion 2 — the application list is empty**

Leave the same background server running. The `40` argument is load-bearing: it holds the request until past Eureka's 30-second response-cache refresh, so an empty result means the registry is empty rather than merely not yet refreshed.

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps application/json 40
```

Expected, exactly:

```
HTTP 200
Content-Type: application/json
{"applications":{"versions__delta":"1","apps__hashcode":"","application":[]}}
```

`"application":[]` is the empty application list. Also confirm the default representation, which is XML:

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps
```

Expected:

```
HTTP 200
Content-Type: application/xml
<applications>
  <versions__delta>1</versions__delta>
  <apps__hashcode></apps__hashcode>
</applications>
```

An empty `apps__hashcode` and no `<application>` child is the same fact in XML.

- [ ] **Step 6: Run it to verify criterion 3 — the dashboard renders**

Same background server.

```bash
java .scratch/Probe.java http://localhost:8761/
```

Expected: `HTTP 200`, `Content-Type: text/html;charset=UTF-8`, and a full HTML document. Confirm it is the Eureka dashboard and that it agrees with Step 5:

```bash
java .scratch/Probe.java http://localhost:8761/ | grep -E '<title>|Instances currently registered|No instances available'
```

Expected:

```
    <title>Eureka</title>
      <h1>Instances currently registered with Eureka</h1>
            <tr><td colspan="4">No instances available</td></tr>
```

"No instances available" is the dashboard reading the registry directly, so it corroborates criterion 2 without going through the response cache.

**Stop the background task.** Task 3 runs `mvn -B clean verify`, which does not need it, but leaving a process on 8761 will break any rerun of this task.

- [ ] **Step 7: Confirm the full gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`.

```bash
git status --short
```

Expected: `?? discovery-server/src/main/resources/` (or the file itself), plus `.scratch/` untracked. Nothing else.

```bash
git add discovery-server/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
feat(discovery): serve the registry on 8761 with the eureka client off

Refs #10
EOF
)"
```

---

## Task 3: The Error Prone gate bites on this module

Covers acceptance criterion 5 (a deliberate error-severity Error Prone violation in this module fails `mvn verify`, and reverting it makes the build pass).

This task changes no committed file. Its deliverable is the observed red and green, plus a clean tree. It ends with **no commit**, which is consistent with "every commit is green" — there is nothing green to record.

**Files:**
- Create then delete: `discovery-server/src/main/java/com/thedarkhorse/discovery/Offender.java`
- Delete: `.scratch/`

**Interfaces:**
- Consumes: from Task 1, the module in the reactor and the compiler configuration it inherits — `error_prone_core` 2.50.0 last on `annotationProcessorPaths`, the `-Xplugin:ErrorProne` compiler arguments, and `.mvn/jvm.config`, all from issue #1.
- Produces: nothing. No later task depends on this one.

- [ ] **Step 1: Write the failing test**

Create `discovery-server/src/main/java/com/thedarkhorse/discovery/Offender.java`. `SelfAssignment` is the pattern issue #10 names, and it is error severity by default, so no `-Xep` promotion is needed and the root POM is not touched.

```java
package com.thedarkhorse.discovery;

public class Offender {

    private String name;

    public void rename(String name) {
        name = name;
        this.name = name;
    }
}
```

- [ ] **Step 2: Run the gate to verify it fails**

Run: `mvn -B clean verify`

Expected: `BUILD FAILURE`, with the finding attributed to line 8 column 14 of the new file:

```
[ERROR] COMPILATION ERROR :
[ERROR] .../discovery-server/src/main/java/com/thedarkhorse/discovery/Offender.java:[8,14]
        [SelfAssignment] Variable assigned to itself
[ERROR]     (see https://errorprone.info/bugpattern/SelfAssignment)
[ERROR]   Did you mean 'this.name = name;'?
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.16.0:compile
        (default-compile) on project discovery-server: Compilation failure
```

If instead the build fails with `java.lang.IllegalAccessError: class com.google.errorprone.BaseErrorProneJavaCompiler ... cannot access class com.sun.tools.javac.api.BasicJavacTask`, the fault is `.mvn/jvm.config` from issue #1, not this module. If the build *succeeds*, Error Prone is not running at all and the criterion has not been met — do not proceed.

- [ ] **Step 3: Revert it**

```bash
rm discovery-server/src/main/java/com/thedarkhorse/discovery/Offender.java
```

- [ ] **Step 4: Run the gate to verify it passes**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`. Both halves of criterion 5 are now observed.

- [ ] **Step 5: Delete the scratch directory and confirm the tree is clean**

`.scratch/` is not covered by `.gitignore`, so it must be removed rather than left untracked.

```bash
rm -rf .scratch
git status --short
```

Expected: **no output at all**. No modified file, no untracked file. If `discovery-server/target/` appears, `.gitignore`'s `target/` rule is not matching and something changed it — investigate rather than adding a new ignore rule.

Confirm the whole issue touched only the four intended paths:

```bash
git diff --stat master...HEAD
```

Expected exactly five entries — the four files above plus this plan, which was committed onto `issue-10` before implementation began:

```
 docs/superpowers/plans/2026-09-09-discovery-server.md
 discovery-server/pom.xml
 discovery-server/src/main/java/com/thedarkhorse/discovery/DiscoveryServerApplication.java
 discovery-server/src/main/resources/application.yaml
 pom.xml
```

Nothing under `.github/`, and no other module.

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`. No commit — nothing changed in this task.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #10 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1 | Starts on 8761 | 2 | 2.2, 2.4 | Tomcat on 8080, `Probe` gets `ConnectException` on 8761 | `Tomcat started on port 8761` |
| 2 | `GET /eureka/apps` returns an empty application list | 2 | 2.2, 2.5 | Log shows `registering service...` and `unable to refresh its cache` | `"application":[]` after a 40 s settle, and `registering service` count is 0 |
| 3 | The dashboard at `/` renders | 2 | 2.2, 2.6 | Nothing listening on 8761 | `HTTP 200`, `text/html`, `<title>Eureka</title>`, `No instances available` |
| 4 | `mvn package` on this module produces a jar that runs with `java -jar` | 1 | 1.2, 1.5, 1.7 | `Could not find the selected project in the reactor`, then `Failed to start bean 'eurekaAutoServiceRegistration'` | repackaged jar, `Started DiscoveryServerApplication` |
| 5 | A deliberate error-severity Error Prone violation fails `mvn verify`, and reverting it makes the build pass | 3 | 3.2, 3.4 | `[SelfAssignment] Variable assigned to itself` → `BUILD FAILURE` | `BUILD SUCCESS` after `rm` |

Criteria 1, 2 and 3 all land in Task 2 because they are three readings of one configuration change and a reviewer cannot sensibly accept one and reject another. Criterion 2 is deliberately not checked alone: findings B and C show it passes on a broken build too, so Step 2.5 is paired with the log assertion in Step 2.4.

---

## Pull request

Title: `feat(discovery): add eureka discovery server on 8761 (#10)`

Body lists each of the five acceptance criteria with the command and the output that verified it, and ends with `Closes #10`. Merge with rebase, never squash, so the two per-cycle commits survive.

The body should also carry the two findings a reviewer will otherwise trip over, since neither is in the issue:

- `@EnableEurekaServer` is required. Without it the application does not start at all — `No qualifying bean of type TransportClientFactories` and `Failed to start bean 'eurekaAutoServiceRegistration'` — rather than starting without a registry.
- `GET /eureka/apps` reads through Eureka's 30-second response cache, so the empty-registry check was taken 40 seconds after startup and paired with a log assertion. Checked immediately after startup it would have passed on a misconfigured server too.

And the one line that looks like a defect and is not: the server logs `EurekaServiceRegistry : Registering application UNKNOWN with eureka with status UP` even though `register-with-eureka` is `false`. No registration occurs — `/eureka/apps` is empty and the dashboard reads "No instances available". The application name is `UNKNOWN` because `spring.application.name` is not set, which is deliberate: issue #10 does not ask for it and `CLAUDE.md` forbids unrequested configuration.
