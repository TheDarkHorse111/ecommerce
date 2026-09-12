# catalog-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `catalog-service` — the Maven module, its Docker Compose Postgres, Flyway pointed at `catalog_db`, and the API-layer error contract every later endpoint inherits: Jakarta Bean Validation on Request records and a `@RestControllerAdvice` that renders every failure as RFC 7807 `ProblemDetail`.

**Architecture:** One Maven module, `catalog-service/`, parented by the root POM and listed in its `<modules>` after `config-server`. It is the first module in the repository with a database. Port, datasource and Eureka zone are **not** written in this module — they already exist on the `config` branch of this repository, which the config server serves, so the module's own `application.yaml` carries only its name, its active profile and the config import. One `@SpringBootApplication` class, one `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, and one record for a rejected field. No entity, no repository, no service, no migration, no endpoint — issue #14 explicitly excludes the business table, and issue #15 adds `category` and `translation` on top of this wiring.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Framework 7.0.8, Spring Cloud 2025.1.3, Hibernate ORM 7.4.1.Final, Hibernate Validator 9.1.0.Final on Jakarta Validation 3.1.1, Flyway 12.4.0 via `spring-boot-starter-flyway` 4.1.0 plus `flyway-database-postgresql`, PostgreSQL 18.6 (`postgres:18-alpine`) with driver 42.7.11, Lombok 1.18.46, Error Prone 2.50.0, maven-compiler-plugin 3.16.0, JUnit 5 and AssertJ via `spring-boot-starter-test`.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` (section 2 "API layer", "Types" and "Testing"; section 3 service table, "Configuration" and "Package layout"; section 4 `catalog_db`; section 6 "Infrastructure", "Root POM" and "Static analysis"; section 7 step 3). Issue #14 is the specification for this increment. Its stated dependency #2 is merged, as are #10, #12 and #13, planned in `docs/superpowers/plans/2026-09-09-discovery-server.md`, `2026-09-09-gateway.md`, `2026-09-09-config-server.md` and `2026-09-09-config-refresh-over-spring-cloud-bus.md`.

---

## Global Constraints

Copied from `CLAUDE.md` and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. There is no separate lint step. Error Prone runs during compilation, so a finding at `ERROR` severity fails the build.
- **Port:** 8081. **Package root:** `com.thedarkhorse.catalog`. **Module directory:** `catalog-service/`, flat at the repository root. **Eureka application name:** `catalog-service`. **Database:** `catalog_db`, in the shared Postgres container.
- **Annotations.** `@Component`, `@Service` and `@Repository` are forbidden. `@RestController` and `@RestControllerAdvice` are the only stereotypes, controller layer only. Every other bean is an `@Bean` method in an `@Configuration` class; this increment needs none. Spring Data JPA repository interfaces are the one exception, and this increment has none. `@SpringBootApplication` is not a stereotype and is not covered by the ban.
- **Layering.** `controller → service → repository → jpa`. This increment has a controller layer only.
- **Queries.** No raw query text in Java. No `@Query`, no native SQL strings, no Criteria fragments. Raw SQL belongs in Flyway migrations, of which this increment ships zero.
- **Types.** Models are Lombok classes unless genuinely read-only. Boundary types are records named for the entity and never the operation. No value-object wrappers. Money is a bare `BigDecimal`; identifiers are `UUID` on entities and `String` everywhere above. None of these types exist yet in this increment.
- **Validation.** Constraints live on Request records only; entities carry none. Constraints guard shape — blank, length, format, sign, range. Integrity stays in the database, so uniqueness is a 409 and never a field error. This increment ships the machinery, not the constraints: there is no Request record until #15.
- **Naming.** Lookups are prefixed `find` at every layer. Mutations use verbs. This applies to the test method names below as well — every assertion in this plan is a lookup, so every test name starts with `finds`.
- **TDD.** No class is written before a failing test for it has been seen to fail. TDD applies to code with behaviour. `CatalogExceptionHandler` has behaviour — it maps exceptions to statuses and rewrites a response body — so it gets a JUnit test written first. `CatalogServiceApplication` has none, and `ValidationError` is a record with no behaviour, so per `CLAUDE.md` neither gets a test. Every other red step below is a command whose observed failure is recorded verbatim. **Never test the framework** — no test asserting that `@NotBlank` rejects blank, that Flyway connects, or that `@RestControllerAdvice` is component-scanned. The spec forbids a Spring context in tests outright, so no `@SpringBootTest`, no `@WebMvcTest`, no `MockMvc`, and no Testcontainers.
- **Comments.** None. No Javadoc, no comment blocks, and none in `pom.xml`, `application.yaml`, `docker-compose.yaml` or any properties file. Rationale lives in the spec and in this plan.
- **Scope.** Build only what issue #14 asks for. In particular do **not** add an entity, a Spring Data repository, a service interface, a mapper, a controller, a `CategoryRequest`, a Flyway migration file, springdoc, actuator, `spring-cloud-starter-bus-kafka`, a resource-server starter, a Dockerfile, or a `README`. See "Deliberate omissions" for the five the issue text will tempt you into.
- **Every plugin carries an explicit version** — but this module declares only `spring-boot-maven-plugin`, with no version and no configuration, because the root `pluginManagement` supplies both. `gateway/pom.xml` and `config-server/pom.xml` are the precedents already in the tree.
- **Conventional Commits** with `catalog` as the scope, one commit per completed red-green cycle, every commit green, each carrying a `Refs #14` footer. No `Co-Authored-By` trailer, no generated-with footer.
- **Pull request** title ends `(#14)`; body ends `Closes #14`. Merge with rebase, never squash.

---

## Verified Findings

Every claim issue #14 makes about tool behaviour was executed in this repository before planning on top of it, using two throwaway modules under `.scratch/` and the real `discovery-server` and `config-server` running alongside them, against a real `postgres:18-alpine` container. `.scratch/` was deleted afterwards.

**One claim in the issue is incomplete in a way that silently produces a green build and a dead Flyway, and one acceptance criterion cannot be made to fail at all.** Read "Where the issue is wrong" before Task 2 and Task 4.

### Confirmed

1. **`spring.mvc.problemdetails.enabled` still exists in Boot 4.1.0 and still defaults to `false`.** From `META-INF/spring-configuration-metadata.json` inside `spring-boot-webmvc-4.1.0.jar` — note that in Boot 4 this property is no longer declared by `spring-boot-autoconfigure`:

   ```json
   {
     "name": "spring.mvc.problemdetails.enabled",
     "type": "java.lang.Boolean",
     "description": "Whether RFC 9457 Problem Details support should be enabled.",
     "sourceType": "org.springframework.boot.webmvc.autoconfigure.WebMvcProperties$Problemdetails",
     "defaultValue": false
   }
   ```

   The issue says the flag "stays `false`". It already is, so **the key is not written** — see "Deliberate omissions". Boot 4.1 calls the format RFC 9457, which obsoletes RFC 7807; the media type and the body shape are the same and `ProblemDetail` is the same class.

2. **`spring-boot-starter-validation` resolves Hibernate Validator 9.1.0.Final on Jakarta Validation 3.1.1**, exactly as the spec pins. From `mvn dependency:tree` on the probe:

   ```
   +- org.springframework.boot:spring-boot-starter-validation:jar:4.1.0:compile
   |  \- org.springframework.boot:spring-boot-validation:jar:4.1.0:compile
   |     +- org.apache.tomcat.embed:tomcat-embed-el:jar:11.0.22:compile
   |     \- org.hibernate.validator:hibernate-validator:jar:9.1.0.Final:compile
   |        +- jakarta.validation:jakarta.validation-api:jar:3.1.1:compile
   ```

3. **`ResponseEntityExceptionHandler` lives in `spring-webmvc`, not `spring-web`, and its hook is `protected`.** `javap` on `spring-webmvc-7.0.8.jar`:

   ```
   public final ResponseEntity<Object> handleException(Exception, WebRequest) throws Exception;
   protected ResponseEntity<Object> handleMethodArgumentNotValid(
       MethodArgumentNotValidException, HttpHeaders, HttpStatusCode, WebRequest);
   protected ResponseEntity<Object> handleExceptionInternal(
       Exception, Object, HttpHeaders, HttpStatusCode, WebRequest);
   ```

   `handleException` is `final`, so the only way to change what a Spring MVC exception renders as is to override one of the `protected` hooks. `handleMethodArgumentNotValid` is the one this increment overrides. Because it is `protected`, the test that calls it directly must sit in the same package as the advice, which it does.

4. **Adding `@ExceptionHandler(Exception.class)` to a class that extends `ResponseEntityExceptionHandler` does not produce an ambiguous-mapping failure.** `handleException` is annotated with an explicit list of Spring MVC exception types and `Exception` is not on it, so the resolver registers both and picks the most specific match per throwable. The probe started cleanly with all three handlers present and routed a `MethodArgumentNotValidException` to the override, a `DataIntegrityViolationException` to the 409 handler and an `IllegalStateException` to the 500 handler in the same run.

5. **The whole error contract was measured end to end.** With the advice in place, a `@Valid @RequestBody` record failing two constraints at once:

   ```
   HTTP/1.1 400
   Content-Type: application/problem+json

   {"detail":"Invalid request content.","instance":"/api/v1/probe","status":400,"title":"Bad Request",
    "errors":[{"field":"sortOrder","message":"must be greater than or equal to 0"},
              {"field":"slug","message":"must not be blank"}]}
   ```

   a thrown `DataIntegrityViolationException` carrying a real Postgres constraint message:

   ```
   HTTP/1.1 409
   Content-Type: application/problem+json

   {"detail":"The request conflicts with the current state of the resource",
    "instance":"/api/v1/probe/conflict","status":409,"title":"Conflict"}
   ```

   and a thrown `IllegalStateException` whose message was `select id from category where slug = 'keyboards'`:

   ```
   HTTP/1.1 500
   Content-Type: application/problem+json

   {"detail":"The request could not be completed","instance":"/api/v1/probe/boom",
    "status":500,"title":"Internal Server Error"}
   ```

   No body carried a stack trace, a class name or the SQL. **Note the `errors` order**: Hibernate Validator reported `sortOrder` before `slug`, which is neither declaration order nor alphabetical. No assertion anywhere may depend on that order.

6. **`Accept: text/html` on an unmapped path is what makes acceptance criterion 3 testable without shipping a controller.** With no advice, a module with zero `@RestController` classes answers:

   ```
   HTTP/1.1 404
   Content-Type: text/html;charset=UTF-8

   <html><body><h1>Whitelabel Error Page</h1><p>This application has no explicit mapping for /error,
   so you are seeing this as a fallback.</p>...</body></html>
   ```

   and with `Accept: application/json`, `{"timestamp":"...","status":404,"error":"Not Found","path":"/api/v1/categories"}` — a Boot error map, not a `ProblemDetail`. With the advice added and **still no controller**, the same request with `Accept: text/html` returns:

   ```
   HTTP/1.1 404
   Content-Type: application/problem+json

   {"detail":"No static resource api/v1/categories.","instance":"/api/v1/categories",
    "status":404,"title":"Not Found"}
   ```

   The whitelabel page is gone, the media type is `application/problem+json`, and the base class produced it, so criterion 3 has a real red and a real green in a module that ships no endpoint.

7. **The port, the datasource and the Eureka zone for `catalog-service` are already on the `config` branch of this repository.** `GET http://localhost:8888/catalog-service/local` returns four merged property sources:

   ```json
   {"name":"catalog-service","profiles":["local"],"propertySources":[
     {"name":".../catalog-service-local.yaml","source":{
       "spring.datasource.url":"jdbc:postgresql://localhost:5432/catalog_db",
       "spring.datasource.username":"catalog","spring.datasource.password":"catalog",
       "spring.jpa.show-sql":true}},
     {"name":".../application-local.yaml","source":{
       "logging.level.root":"DEBUG",
       "eureka.client.service-url.defaultZone":"http://localhost:8761/eureka/"}},
     {"name":".../catalog-service.yaml","source":{"server.port":8081}},
     {"name":".../application.yaml","source":{
       "spring.jpa.hibernate.ddl-auto":"validate","spring.jpa.show-sql":false}}]}
   ```

   So `server.port`, `spring.datasource.*` and `eureka.client.service-url.defaultZone` must **not** be written into this module — they would be a second source of truth for values the config server already owns. The Compose file's credentials are dictated by this: role `catalog`, password `catalog`, database `catalog_db`, port 5432.

8. **A module with exactly the Task 1 dependency set starts on 8081 and registers as `CATALOG-SERVICE`.** Measured with `spring-boot-starter-web`, `spring-cloud-starter-config` and `spring-cloud-starter-netflix-eureka-client` and nothing else, with `discovery-server` and `config-server` up:

   ```
   INFO ... com.netflix.discovery.DiscoveryClient : DiscoveryClient_CATALOG-SERVICE/<host>:catalog-service:8081: registering service...
   INFO ... o.s.boot.tomcat.TomcatWebServer       : Tomcat started on port 8081 (http) with context path '/'
   INFO ... com.netflix.discovery.DiscoveryClient : DiscoveryClient_CATALOG-SERVICE/<host>:catalog-service:8081 - registration status: 204
   ```

   and 50 seconds later `GET /eureka/apps` listed `"name":"CATALOG-SERVICE"` and `"name":"CONFIG-SERVER"`.

9. **`spring.jpa.hibernate.ddl-auto: validate`, which the shared `application.yaml` sets for every service, does not fail a module with zero entities.** The probe carried `spring-boot-starter-data-jpa` with no `@Entity` anywhere and started normally: `Initialized JPA EntityManagerFactory for persistence unit 'default'`, no validation error. Flyway's history table is not an entity and `validate` never looks at it.

10. **`spring.config.import: "configserver:"` is not optional and fails startup when the config server is down.** Exit code 1, before Tomcat binds:

    ```
    org.springframework.cloud.config.client.ConfigClientFailFastException:
      Could not locate PropertySource and the resource is not optional, failing
    Caused by: org.springframework.web.client.ResourceAccessException:
      I/O error on GET request for "http://localhost:8888/catalog-service/default": Connection refused
    ```

    This is worth knowing because it means the module can never silently fall back to port 8080 in its real configuration: the only way to see 8080 is to omit the import, which is exactly Task 1's red.

### Where the issue is wrong

**A. INCOMPLETE — "Flyway wiring" is not `flyway-core` in Spring Boot 4. It is `spring-boot-starter-flyway`, and getting it wrong is silent.**

In Boot 4 the autoconfiguration classes were split out of `spring-boot-autoconfigure` into per-technology modules. `FlywayAutoConfiguration` now lives in `org.springframework.boot:spring-boot-flyway`; `unzip -l` on that jar shows `org/springframework/boot/flyway/autoconfigure/FlywayAutoConfiguration.class` and its `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Nothing equivalent is in `spring-boot-autoconfigure-4.1.0.jar`.

The probe was first built with `org.flywaydb:flyway-core` on the classpath, `spring.flyway.enabled: true` and `logging.level.org.flywaydb: DEBUG`, against a running Postgres. It started perfectly:

```
INFO ... HikariPool-1 - Start completed.
INFO ... Initialized JPA EntityManagerFactory for persistence unit 'default'
INFO ... Started ProbeApplication in 2.68 seconds
```

with **not one Flyway log line**, and `\dt` on `catalog_db` returned `Did not find any tables.` A build that looks green, an application that starts, and a Flyway that never ran.

The Spring Boot 4.1 documentation is explicit: "add the appropriate Flyway module to your classpath. In-memory and file-based databases are supported by the `spring-boot-starter-flyway` starter... Other cases require also a database-specific module. For example, use `org.flywaydb:flyway-database-postgresql` with PostgreSQL." Both are needed, and the starter does not drag in `flyway-core` — `flyway-database-postgresql` is what does:

```
+- org.springframework.boot:spring-boot-starter-flyway:jar:4.1.0:compile
|  \- org.springframework.boot:spring-boot-flyway:jar:4.1.0:compile
\- org.flywaydb:flyway-database-postgresql:jar:12.4.0:compile
   \- org.flywaydb:flyway-core:jar:12.4.0:compile
```

Both versions come from the Boot BOM, so neither is written in the module POM. Task 2 Step 3 is built around this: the red is the empty `\dt`, and the green is the pair of dependencies.

**B. CONFIRMED but only with the right dependency — Flyway with zero migrations does create its history table.** Once `spring-boot-starter-flyway` was present:

```
INFO ... org.flywaydb.core.FlywayExecutor  : Database: jdbc:postgresql://localhost:5432/catalog_db (PostgreSQL 18.6)
INFO ... o.f.c.i.s.JdbcTableSchemaHistory  : Schema history table "public"."flyway_schema_history" does not exist yet
INFO ... o.f.core.internal.command.DbValidate : Successfully validated 0 migrations (execution time 00:00.006s)
WARN ... o.f.core.internal.command.DbValidate : No migrations found. Are your locations set up correctly?
INFO ... o.f.c.i.s.JdbcTableSchemaHistory  : Creating Schema History table "public"."flyway_schema_history" ...
INFO ... o.f.core.internal.command.DbMigrate : Current version of schema "public": << Empty Schema >>
INFO ... o.f.core.internal.command.DbMigrate : Schema "public" is up to date. No migration necessary.
```

```
              List of tables
 Schema |         Name          | Type  |  Owner
--------+-----------------------+-------+---------
 public | flyway_schema_history | table | catalog
```

The `No migrations found. Are your locations set up correctly?` warning is expected and correct — there are no migrations yet — and **it is not to be silenced**. `spring.flyway.fail-on-missing-locations` defaults to `false`, so the absent `classpath:db/migration` directory is not an error. Do not create an empty `db/migration` directory: git cannot track an empty directory, and the run above proves it is not needed.

**C. WRONG IMAGE CONTRACT — `postgres:18` refuses to start with the mount path every older Compose file uses.** The obvious `postgres-data:/var/lib/postgresql/data` kills the container on first boot:

```
container postgres exited (1)
...
The suggested container configuration for 18+ is to place a single mount at /var/lib/postgresql
which will then place PostgreSQL data in a subdirectory, allowing usage of "pg_upgrade --link"
without mount point boundary issues.
```

The volume must be mounted at **`/var/lib/postgresql`**. With that one change the same file comes up healthy. Task 2's Compose file is written with the correct mount; this is recorded so that a reviewer comparing it to any other project's file does not "fix" it back.

**D. NOT FALSIFIABLE — acceptance criterion 8, "a Lombok-generated method produces no Error Prone finding", cannot be made to fail, and this increment ships no Lombok-annotated class.**

Two separate measurements.

First, the root configuration already covers it, and it is already live for every module. `mvn -X` on `gateway` shows the processor path javac actually receives, and Lombok is on it even though `gateway/pom.xml` declares no Lombok dependency:

```
-processorpath .../lombok/1.18.46/lombok-1.18.46.jar:.../lombok-mapstruct-binding-0.2.0.jar:
               .../mapstruct-processor-1.6.3.jar:.../error_prone_core-2.50.0.jar:...
-g -parameters --release 25 -encoding UTF-8 -XDcompilePolicy=simple --should-stop=ifError=FLOW -Xplugin:ErrorProne
```

Two incidental facts fall out of that line. The root POM writes `<version>${lombok.version}</version>` on that path and **no such property is defined** — `mvn help:evaluate -Dexpression=lombok.version` on the root POM prints `null object or invalid expression` — yet maven-compiler-plugin 3.16.0 falls back to the managed version and resolves 1.18.46 anyway. And 1.18.46 is not the 1.18.42 the spec's pin list names; the Boot 4.1.0 BOM is what decides, and nothing in this increment changes that.

Second, the criterion has no red available. A `@Data @NoArgsConstructor @AllArgsConstructor` class with seven fields including a `BigDecimal` compiled clean under Error Prone 2.50.0 — and it compiled just as clean with a local `lombok.config` setting `lombok.addLombokGeneratedAnnotation = false` and `config.stopBubbling = true`. At Error Prone 2.50.0's default check set on Java 25 there is no finding to suppress in the first place; the root `lombok.config` is a correct belt-and-braces measure, not the thing holding the build up.

So Task 4 verifies the criterion the only honest way available: by compiling such a class inside the module as a throwaway, observing `BUILD SUCCESS` with no Error Prone output, and deleting it. That step has no red, and the plan says so rather than inventing one. The first committed Lombok class arrives with the `Category` model in #15.

### Three things the issue does not say

**E. The package layout in the issue cannot be committed as written.** The issue draws eight packages — `config/ controller/ service/ repository/ jpa/ model/ mapper/ exception/`. Git does not track empty directories, and `CLAUDE.md` forbids helper files nobody requested, which rules out eight `.gitkeep` files. This increment therefore creates the two packages that get a class — `com.thedarkhorse.catalog` and `com.thedarkhorse.catalog.controller` — and the other six materialise in #15 when their first class does. The layout is a spec rule about where classes go, not a directory manifest.

**F. `spring-boot-starter-web` is not transitive here.** `spring-boot-starter-data-jpa` does not bring Spring MVC, and neither does the config or Eureka starter. Without it there is no `DispatcherServlet`, no `ResponseEntityExceptionHandler` on the classpath, and no port to bind. It is declared explicitly.

**G. `spring-boot-starter-data-jpa` is a judgment call this plan makes, and it is worth stating.** Issue #14 ships no entity, so the strictly minimal way to give Flyway a `DataSource` is `spring-boot-starter-jdbc`. Data JPA is chosen instead for two reasons that are visible in the repository rather than speculative: the shared `application.yaml` on the `config` branch already sets `spring.jpa.hibernate.ddl-auto: validate` and `spring.jpa.show-sql` for every service, and those keys bind to nothing without it; and `DataIntegrityViolationException`, which acceptance criterion 6 names, is Spring's ORM-agnostic DAO exception that arrives with the same starter. Finding 9 shows it costs nothing with zero entities. If a reviewer prefers `spring-boot-starter-jdbc`, the swap is one line in `catalog-service/pom.xml` and changes no Java.

### Deliberate omissions

- **No `spring.mvc.problemdetails.enabled: false`.** Finding 1: it is already the default, and `CLAUDE.md` forbids configuration knobs nobody requested. Setting a key to the value it already has is a knob whose only effect is to make a reader wonder what changed it.
- **No `server.port`, no `spring.datasource.*`, no `eureka.client.service-url.defaultZone` in this module.** Finding 7: the config server already serves all three from this repository's `config` branch. Duplicating them here creates two sources of truth for the same value and the local copy wins, which is exactly the failure the config server exists to prevent.
- **No `db/migration` directory.** Finding B: Flyway creates the history table without it, `fail-on-missing-locations` defaults to `false`, and git cannot track the empty directory anyway.
- **No `@Order` on the advice.** There is exactly one `@RestControllerAdvice` in this module. Ordering matters only between competing advices, and inventing a precedence for a set of one is a knob nobody requested.
- **No `handleHandlerMethodValidationException` override.** That hook covers constraints on `@RequestParam` and `@PathVariable`, which no endpoint in this increment or in #15's issue has. The base class already renders it as a `ProblemDetail`; the `errors` array is added when a request record with constraints exists to need it.
- **No actuator, no springdoc, no `spring-cloud-starter-bus-kafka`.** Eureka advertises `/actuator/health` in the instance metadata whether or not the endpoint exists, and registration reads `"status":"UP"` regardless (finding 8 measured exactly that with no actuator on the classpath). Bus refresh is issue #13's subject and this module has no `@RefreshScope` bean or `@ConfigurationProperties` to refresh.

### Tooling constraint: there is no `curl` and no `wget`

The implementing job's allowlist (`.github/workflows/claude-advance.yml`, the `implement` job) is:

```
Edit,Write,Bash(mvn:*),Bash(git:*),Bash(gh:*),Bash(ls:*),Bash(cat:*),Bash(find:*),Bash(mkdir:*),
Bash(mv:*),Bash(cp:*),Bash(rm:*),Bash(cd:*),Bash(grep:*),Bash(java:*),Bash(javap:*),Bash(printf:*),
Bash(test:*),Bash(jar:*),Bash(unzip:*),Bash(head:*),Bash(tail:*),Bash(wc:*),Bash(sort:*),
Bash(diff:*),Bash(echo:*),Bash(docker:*)
```

No HTTP client is on it. `Bash(java:*)` is, and Java's single-file source launcher runs a `.java` file directly, so **Task 1 Step 1 re-creates `.scratch/Probe.java`** — the same file issue #12 used — and every HTTP check in this plan runs through it. `Bash(docker:*)` covers `docker compose` and `docker exec postgres psql`, which is how every database check below is made.

This increment needs **three** servers up at once — `discovery-server` on 8761, `config-server` on 8888 and `catalog-service` on 8081 — plus the Postgres container. Start each server as a **background** Bash task and stop it with the harness's background-task stop; `kill` and `pkill` are not on the allowlist. Only one process may hold a port, so stop the previous `catalog-service` before starting the next.

When a background Bash task starts, the harness reports the file its output is being written to, for example `/tmp/.../tasks/bqorhrgth.output`. Several steps below grep that file. They are written with `CATALOG_OUTPUT`, `EUREKA_OUTPUT` and `CONFIG_OUTPUT` standing in for those paths — **substitute the real paths the harness printed**, because they are assigned per task and cannot be known in advance.

`config-server` reads the `config` branch of this repository over HTTPS from GitHub, so the implementing job needs outbound network. If it is unavailable, every step that needs port 8081 is blocked; say so rather than writing `server.port` into the module to work around it.

**`GET /eureka/apps` is served from a response cache that refreshes every 30 seconds.** Measured again during this increment: `CATALOG-SERVICE` had already logged `registration status: 204` and was still absent from the response. The staleness runs one way — a registry that is filling up still reads as empty — so a check taken at t+2s passes on a correctly configured service **and** on a broken one. **Every `/eureka/apps` check in this plan waits at least 45 seconds after startup**, which is what the settle argument to `Probe.java` is for.

---

## File Structure

Committed by this plan:

| Path | Responsibility | Action |
| --- | --- | --- |
| `pom.xml` | Root parent. Gains one `<module>catalog-service</module>` entry after `config-server`. Nothing else in it changes. | Modify |
| `catalog-service/pom.xml` | Module coordinates, the runtime dependency set, `spring-boot-starter-test`, one plugin declaration with no version and no configuration. | Create |
| `catalog-service/docker-compose.yaml` | The shared Postgres container, hosting `catalog_db` with the role the config branch names. | Create |
| `catalog-service/src/main/java/com/thedarkhorse/catalog/CatalogServiceApplication.java` | `@SpringBootApplication` and `main`. No behaviour, so no test. | Create |
| `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java` | The one piece of behaviour in the module: renders every failure as `ProblemDetail`, attaches the rejected fields, maps `DataIntegrityViolationException` to 409 and everything unhandled to 500 without naming the cause. | Create |
| `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/ValidationError.java` | One rejected field and its reason — the element type of the `errors` array. A record with no behaviour, so no test. | Create |
| `catalog-service/src/main/resources/application.yaml` | Application name, active profile, config import. Nothing else: the config server owns port, datasource and Eureka zone. | Create |
| `catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java` | Plain JUnit test of the advice. No Spring context. | Create |

`ValidationError` is a record rather than a Lombok class because it is genuinely read-only, which is the spec's own carve-out. It is not named for an entity, and that is not a breach of the Request/Response naming rule: that rule governs the boundary types of an entity's endpoints, and this is the advice's own response fragment, of which there is exactly one for the whole service. A `Map<String, String>` was rejected instead because two constraints failing on the same field would silently collapse to one entry, and acceptance criterion 5 is precisely about not losing the second failure.

Not created: any `config/`, `service/`, `repository/`, `jpa/`, `model/`, `mapper/` or `exception/` package (finding E), and no `src/main/resources/db/migration` (finding B).

Not touched: `lombok.config`, `.mvn/jvm.config`, `.github/`, `CLAUDE.md`, `README.md`, `discovery-server/`, `gateway/`, `config-server/`, the `config` branch, the spec, and any other plan.

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/Probe.java` | The HTTP client, because the allowlist has none. Deleted in Task 4. |
| `catalog-service/src/main/java/com/thedarkhorse/catalog/model/LombokProbe.java` | Created and deleted inside Task 4 Step 2, to exercise acceptance criterion 8. |

---

## Task 1: The module starts on 8081 and registers as `catalog-service`

Covers acceptance criterion 1.

Two red-green cycles and two commits: the module joins the reactor, then it acquires its identity and its configuration.

**Files:**
- Modify: `pom.xml:12-16` — add a fourth `<module>` entry
- Create: `catalog-service/pom.xml`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/CatalogServiceApplication.java`
- Create: `catalog-service/src/main/resources/application.yaml`
- Create: `.scratch/Probe.java` (temporary, deleted in Task 4)

**Interfaces:**
- Consumes: from issue #1, the parent coordinates `com.thedarkhorse:ecommerce:0.0.1-SNAPSHOT` with `pom` packaging, the `pluginManagement` entry for `org.springframework.boot:spring-boot-maven-plugin` carrying the `repackage` execution, the `spring-boot-dependencies` 4.1.0 and `spring-cloud-dependencies` 2025.1.3 BOM imports, the `annotationProcessorPaths` carrying Lombok, the MapStruct binding, MapStruct and Error Prone, and `.mvn/jvm.config`, without which Error Prone cannot start. From issue #10, `com.thedarkhorse:discovery-server:0.0.1-SNAPSHOT`, jar at `discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar`, serving Eureka on 8761. From issue #13, `com.thedarkhorse:config-server:0.0.1-SNAPSHOT`, jar at `config-server/target/config-server-0.0.1-SNAPSHOT.jar`, serving 8888 from the `config` branch, which already carries `catalog-service.yaml`, `catalog-service-local.yaml` and `catalog-service-prod.yaml`.
- Produces: the module artifact `com.thedarkhorse:catalog-service:0.0.1-SNAPSHOT`, repackaged jar at `catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar`; the class `com.thedarkhorse.catalog.CatalogServiceApplication` with `public static void main(String[] args)`; `catalog-service/src/main/resources/application.yaml` carrying `spring.application.name`, `spring.profiles.active` and `spring.config.import`, which no later task in this plan modifies; and `catalog-service/pom.xml`, whose `<dependencies>` Task 2 and Task 3 each append to. `.scratch/Probe.java` exposes `Probe.main(String[])`, invoked as `java .scratch/Probe.java <url> [accept] [settleSeconds]`; Task 4 deletes it.

### Cycle A — the module joins the reactor

- [ ] **Step 1: Write the failing test**

Two pieces. First the HTTP client, because the allowlist has none. Create `.scratch/Probe.java` exactly as below. It retries the connection for up to two minutes so it can be launched before a server is up, then sleeps `settleSeconds` before the request it actually reports — that sleep is what makes an `/eureka/apps` check meaningful rather than vacuous.

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
                .timeout(Duration.ofSeconds(30))
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

Second, the check. The module does not exist yet, so run the build the criterion depends on:

```bash
mvn -B -pl catalog-service clean package
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -pl catalog-service clean package`

Expected: a non-zero exit. Maven fails during project selection, before the reactor runs, so there is **no** `BUILD FAILURE` line — the whole output is:

```
[ERROR] [ERROR] Could not find the selected project in the reactor: catalog-service @
[ERROR] Could not find the selected project in the reactor: catalog-service -> [Help 1]
```

Confirm the reactor today holds only the four existing projects:

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing `ecommerce`, `discovery-server`, `gateway` and `config-server` and nothing else. That is the red.

- [ ] **Step 3: Write the minimal implementation**

In the root `pom.xml`, replace lines 12-16:

```xml
    <modules>
        <module>discovery-server</module>
        <module>gateway</module>
        <module>config-server</module>
    </modules>
```

with:

```xml
    <modules>
        <module>discovery-server</module>
        <module>gateway</module>
        <module>config-server</module>
        <module>catalog-service</module>
    </modules>
```

Change nothing else in the root POM. In particular do **not** add a `lombok.version` property to make finding D's placeholder resolve — the build already resolves 1.18.46, that is a pre-existing root-POM matter, and issue #14 does not ask for it.

Create `catalog-service/pom.xml`. No `<relativePath>` — a module in the reactor finds `../pom.xml` by default. No version on the plugin and no `<configuration>`; both come from the root `pluginManagement`. No versions on the dependencies; the two BOM imports supply them. Only `spring-boot-starter-web` and the test starter for now — the config and Eureka starters are Cycle B's green, and the database and validation dependencies are Task 2's and Task 3's reds. No comments.

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

    <artifactId>catalog-service</artifactId>
    <name>catalog-service</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
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

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/CatalogServiceApplication.java`:

```java
package com.thedarkhorse.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
```

Create `catalog-service/src/main/resources/application.yaml` with the name only. The profile and the config import are Cycle B's green.

```yaml
spring:
  application:
    name: catalog-service
```

- [ ] **Step 4: Run it to verify the module packages**

Run: `mvn -B -pl catalog-service clean package`

Expected: `BUILD SUCCESS`, containing both of these. The second is the inherited `repackage` execution firing without the module naming it:

```
[INFO] --- compiler:3.16.0:compile (default-compile) @ catalog-service ---
[INFO] Compiling 1 source file with javac [debug parameters release 25] to target/classes
...
[INFO] --- spring-boot:4.1.0:repackage (default) @ catalog-service ---
[INFO] Replacing main artifact .../catalog-service-0.0.1-SNAPSHOT.jar with repackaged archive,
       adding nested dependencies in BOOT-INF/.
```

If the compile line says `forked`, someone added `<fork>true</fork>`; the spec forbids it, so remove it.

- [ ] **Step 5: Commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing all five projects, and `No tests to run.` for `catalog-service`.

```bash
git add pom.xml catalog-service/pom.xml catalog-service/src
git commit -m "$(cat <<'EOF'
feat(catalog): add catalog-service module

Refs #14
EOF
)"
```

### Cycle B — port 8081 and the registry entry

- [ ] **Step 6: Write the failing test**

No JUnit test: registration and port binding are the framework's behaviour, and the spec forbids asserting on them in a unit test. The failing test is acceptance criterion 1 run against the real registry.

Build everything and start `discovery-server` as a **background** Bash task. Leave it running for the rest of this plan. Do **not** start `config-server` yet — the red is about what this module does without the config import, and a running config server would add a second registry entry that muddies the reading.

```bash
mvn -B clean package
```

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

```bash
grep -E 'Tomcat started|Started Eureka Server' EUREKA_OUTPUT
```

Expected: `Tomcat started on port 8761 (http) with context path '/'` and `Started Eureka Server`.

Now start `catalog-service` as a second **background** Bash task:

```bash
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
```

- [ ] **Step 7: Run it to verify both halves fail**

```bash
grep -E 'Tomcat started|Started CatalogServiceApplication' CATALOG_OUTPUT
```

Expected — the wrong port, because nothing has told it 8081 and 8080 is Boot's default:

```
INFO ... o.s.boot.tomcat.TomcatWebServer      : Tomcat started on port 8080 (http) with context path '/'
INFO ... c.t.c.CatalogServiceApplication       : Started CatalogServiceApplication in N seconds
```

Then the registry. The `45` is load-bearing:

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps application/json 45
```

Expected, exactly — `catalog-service` is running and is **not** in the registry:

```
HTTP 200
Content-Type: application/json
{"applications":{"versions__delta":"1","apps__hashcode":"","application":[]}}
```

Both halves of criterion 1 fail: wrong port, no registration. That is the red. **Stop the `catalog-service` background task.** Leave `discovery-server` running.

- [ ] **Step 8: Write the minimal implementation**

In `catalog-service/pom.xml`, insert these two dependencies between `spring-boot-starter-web` and `spring-boot-starter-test`:

```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
```

Edit `catalog-service/src/main/resources/application.yaml` so it reads in full:

```yaml
spring:
  application:
    name: catalog-service
  profiles:
    active: local
  config:
    import: "configserver:"
```

That is the whole file, and it stays that size. Do **not** add `server.port: 8081`, `spring.datasource.*` or `eureka.client.service-url.defaultZone` — finding 7 shows the config server already serves all three, and a local copy would win over the one the config server owns.

Rebuild, then start `config-server` as a **background** task and wait for it to answer before starting `catalog-service`, because finding 10 shows a non-optional config import fails startup outright when the server is down:

```bash
mvn -B clean package
```

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json
```

Expected: `HTTP 200` and a body listing four property sources — `catalog-service-local.yaml`, `application-local.yaml`, `catalog-service.yaml` and `application.yaml` — with `"server.port":8081` among them. If the body is `{"name":"catalog-service","profiles":["local"],"propertySources":[]}`, the `config` branch is not reachable; stop and report that rather than writing the port into the module.

Then start `catalog-service` as a **background** task:

```bash
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
```

- [ ] **Step 9: Run it to verify criterion 1**

```bash
grep -E 'Tomcat started|registration status|registering service' CATALOG_OUTPUT
```

Expected — the port now comes from the config server, and the registration is logged:

```
INFO ... com.netflix.discovery.DiscoveryClient : DiscoveryClient_CATALOG-SERVICE/<host>:catalog-service:8081: registering service...
INFO ... o.s.boot.tomcat.TomcatWebServer       : Tomcat started on port 8081 (http) with context path '/'
INFO ... com.netflix.discovery.DiscoveryClient : DiscoveryClient_CATALOG-SERVICE/<host>:catalog-service:8081 - registration status: 204
```

`registration status: 204` is the server accepting it, but the registry is read through a 30-second cache, so read it with a settle:

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps application/json 45
```

Expected: two applications — `CONFIG-SERVER`, which registers itself, and `CATALOG-SERVICE`:

```json
{"applications":{"versions__delta":"1","apps__hashcode":"UP_2_","application":[
  {"name":"CATALOG-SERVICE","instance":[{"app":"CATALOG-SERVICE","status":"UP",
    "instanceId":"<host>:catalog-service:8081","vipAddress":"catalog-service",
    "port":{"$":8081,"@enabled":"true"}, ... }]},
  {"name":"CONFIG-SERVER","instance":[{"app":"CONFIG-SERVER", ... "port":{"$":8888, ...}}]}]}
```

`"name":"CATALOG-SERVICE"` with `"status":"UP"` and `"port":{"$":8081}` is criterion 1, and the registry corroborating the port is stronger than the log line alone. Eureka upper-cases application names; `spring.application.name` stays lower-case in the YAML, and `"vipAddress":"catalog-service"` is the name as written — which is also what `gateway`'s `lb://catalog-service` route resolves against.

**Stop the `catalog-service` background task.** Task 2 rebuilds and restarts it. Leave `discovery-server` and `config-server` running.

- [ ] **Step 10: Confirm the full gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with all five projects.

```bash
git status --short
```

Expected exactly: `M catalog-service/pom.xml`, `M catalog-service/src/main/resources/application.yaml`, plus `?? .scratch/`, which is untracked and Task 4 deletes. Nothing under `.github/` and nothing under the other three modules.

```bash
git add catalog-service/pom.xml catalog-service/src/main/resources/application.yaml
git commit -m "$(cat <<'EOF'
feat(catalog): register with eureka and read config from the config server

Refs #14
EOF
)"
```

---

## Task 2: Postgres in Compose, and Flyway on `catalog_db`

Covers acceptance criterion 2.

**Files:**
- Create: `catalog-service/docker-compose.yaml`
- Modify: `catalog-service/pom.xml` — append four dependencies

**Interfaces:**
- Consumes: from Task 1, `com.thedarkhorse:catalog-service:0.0.1-SNAPSHOT`, its jar, `CatalogServiceApplication`, the three-key `application.yaml`, and `.scratch/Probe.java`. From the `config` branch, `spring.datasource.url` `jdbc:postgresql://localhost:5432/catalog_db` with username and password both `catalog`, and `spring.jpa.hibernate.ddl-auto: validate` — the Compose file's credentials are dictated by these and must match them exactly.
- Produces: `catalog-service/docker-compose.yaml`, defining one service named `postgres` with container name `postgres` on 5432 and a named volume `postgres-data`; a `catalog-service/pom.xml` carrying `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql` and `org.postgresql:postgresql`, which puts `javax.sql.DataSource`, `org.springframework.dao.DataIntegrityViolationException` and `jakarta.persistence` on the compile classpath for Task 3 and for #15; and the table `public.flyway_schema_history` in `catalog_db`, which every migration in #15 appends to.

- [ ] **Step 1: Write the failing test**

No JUnit test: the spec forbids a database in tests and forbids testing the framework, and "Flyway created its table" is Flyway's behaviour, not this module's. The failing test is acceptance criterion 2 read straight out of Postgres.

Create `catalog-service/docker-compose.yaml`. The volume mounts at `/var/lib/postgresql`, **not** `/var/lib/postgresql/data` — finding C: the latter makes `postgres:18` exit 1 on first boot. The credentials are not a choice; they are what the `config` branch already tells the service to connect with. The healthcheck is what makes `--wait` mean "accepting connections" rather than "process started", and every step below depends on that. No comments.

```yaml
services:
  postgres:
    image: postgres:18-alpine
    container_name: postgres
    environment:
      POSTGRES_USER: catalog
      POSTGRES_PASSWORD: catalog
      POSTGRES_DB: catalog_db
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U catalog -d catalog_db"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  postgres-data:
```

Bring it up:

```bash
docker compose -f catalog-service/docker-compose.yaml up -d --wait
```

Expected:

```
 Container postgres  Started
 Container postgres  Waiting
 Container postgres  Healthy
```

If it prints `container postgres exited (1)` instead, read `docker logs postgres`: the volume mount is wrong (finding C).

Then the check:

```bash
docker exec postgres psql -U catalog -d catalog_db -c "\dt"
```

- [ ] **Step 2: Run it to verify it fails**

Two failures, in order.

First, `catalog_db` is empty. Run the `psql` command above.

Expected, exactly:

```
Did not find any tables.
```

Second — and this is the one worth seeing, because it is what finding A is about — confirm that a module with no database dependencies does not go looking for one. `discovery-server` and `config-server` should still be running from Task 1. Start `catalog-service` as a **background** task:

```bash
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
```

```bash
grep -ci flyway CATALOG_OUTPUT
```

Expected: it prints `0`. `grep -c` exits 1 when the count is zero, so a non-zero exit code here **is** the pass; read the printed number, not the exit status. The service is up on 8081, the database is up on 5432, and nothing connects them. That is the red. **Stop the `catalog-service` background task.**

- [ ] **Step 3: Write the minimal implementation**

In `catalog-service/pom.xml`, insert these four dependencies after `spring-cloud-starter-netflix-eureka-client` and before `spring-boot-starter-test`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

All four versions come from the Boot BOM; none is written here. `spring-boot-starter-flyway` is the artifact that carries `FlywayAutoConfiguration` in Boot 4 — **`org.flywaydb:flyway-core` alone does nothing at all and produces no error** (finding A). `flyway-database-postgresql` is required for Postgres and is what drags `flyway-core` in transitively. `spring-boot-starter-data-jpa` rather than `spring-boot-starter-jdbc` is the judgment call recorded in finding G.

Do **not** create `src/main/resources/db/migration`. Do **not** set `spring.flyway.*` anywhere: `enabled` already defaults to true, `locations` already defaults to `classpath:db/migration`, and `fail-on-missing-locations` already defaults to false.

Confirm the dependency shape before running anything:

```bash
mvn -B -pl catalog-service dependency:tree -Dincludes=org.flywaydb:*,org.springframework.boot:spring-boot-starter-flyway,org.postgresql:postgresql
```

Expected:

```
+- org.springframework.boot:spring-boot-starter-flyway:jar:4.1.0:compile
|  \- org.springframework.boot:spring-boot-flyway:jar:4.1.0:compile
+- org.flywaydb:flyway-database-postgresql:jar:12.4.0:compile
|  \- org.flywaydb:flyway-core:jar:12.4.0:compile
\- org.postgresql:postgresql:jar:42.7.11:runtime
```

- [ ] **Step 4: Run it to verify criterion 2**

```bash
mvn -B -pl catalog-service clean package
```

```bash
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
```

```bash
grep -E 'FlywayExecutor|JdbcTableSchemaHistory|DbValidate|DbMigrate|Tomcat started' CATALOG_OUTPUT
```

Expected, and the `WARN` line is correct rather than a problem — there are no migrations yet, and finding B says leave it alone:

```
INFO ... org.flywaydb.core.FlywayExecutor     : Database: jdbc:postgresql://localhost:5432/catalog_db (PostgreSQL 18.6)
INFO ... o.f.c.i.s.JdbcTableSchemaHistory     : Schema history table "public"."flyway_schema_history" does not exist yet
INFO ... o.f.core.internal.command.DbValidate : Successfully validated 0 migrations (execution time 00:00.00Ns)
WARN ... o.f.core.internal.command.DbValidate : No migrations found. Are your locations set up correctly?
INFO ... o.f.c.i.s.JdbcTableSchemaHistory     : Creating Schema History table "public"."flyway_schema_history" ...
INFO ... o.f.core.internal.command.DbMigrate  : Current version of schema "public": << Empty Schema >>
INFO ... o.f.core.internal.command.DbMigrate  : Schema "public" is up to date. No migration necessary.
INFO ... o.s.boot.tomcat.TomcatWebServer      : Tomcat started on port 8081 (http) with context path '/'
```

Then the criterion itself, read from the database rather than from the log:

```bash
docker exec postgres psql -U catalog -d catalog_db -c "\dt" -c "select count(*) from flyway_schema_history"
```

Expected — the history table exists in `catalog_db`, owned by the role the config branch names, and it is empty because there are no migrations:

```
              List of tables
 Schema |         Name          | Type  |  Owner
--------+-----------------------+-------+---------
 public | flyway_schema_history | table | catalog
(1 row)

 count
-------
     0
(1 row)
```

Confirm Hibernate did not object to `ddl-auto: validate` with no entities (finding 9):

```bash
grep -E 'Initialized JPA EntityManagerFactory|SchemaManagementException' CATALOG_OUTPUT
```

Expected: `Initialized JPA EntityManagerFactory for persistence unit 'default'` and no `SchemaManagementException`.

**Stop the `catalog-service` background task.** Leave Postgres, `discovery-server` and `config-server` running.

- [ ] **Step 5: Confirm the full gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`.

```bash
git status --short
```

Expected: `M catalog-service/pom.xml`, `?? catalog-service/docker-compose.yaml`, plus `?? .scratch/`. Nothing else.

```bash
git add catalog-service/pom.xml catalog-service/docker-compose.yaml
git commit -m "$(cat <<'EOF'
feat(catalog): add postgres compose file and flyway on catalog_db

Refs #14
EOF
)"
```

---

## Task 3: The RFC 7807 error contract

Covers acceptance criteria 3, 4, 5, 6 and 7.

One task, because the three handlers are one contract: a reviewer cannot accept "validation failures are structured" while rejecting "an unhandled exception does not leak", and criterion 7 is a property of all three bodies rather than of any one of them. One red-green cycle, one commit.

**Files:**
- Modify: `catalog-service/pom.xml` — append `spring-boot-starter-validation`
- Create: `catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/ValidationError.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java`

**Interfaces:**
- Consumes: from Task 1, `catalog-service/pom.xml` with `spring-boot-starter-web` (which supplies `spring-webmvc`, and with it `ResponseEntityExceptionHandler`) and `spring-boot-starter-test` (which supplies JUnit 5, AssertJ and `spring-test`, and with it `MockHttpServletRequest`). From Task 2, `spring-boot-starter-data-jpa`, which supplies `org.springframework.dao.DataIntegrityViolationException`.
- Produces: `com.thedarkhorse.catalog.controller.ValidationError`, a record with components `String field` and `String message`; and `com.thedarkhorse.catalog.controller.CatalogExceptionHandler`, a `@RestControllerAdvice extends ResponseEntityExceptionHandler` with `protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException, HttpHeaders, HttpStatusCode, WebRequest)`, `public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException)` and `public ProblemDetail handleUnexpected(Exception)`. Issue #15's `CategoryController` inherits all three without naming them; the `errors` array shape — a JSON array of `{"field": ..., "message": ...}` — is the contract the frontend reads.

- [ ] **Step 1: Write the failing test**

First the dependency the test's imports need. In `catalog-service/pom.xml`, insert after `spring-boot-starter-web`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

That is what puts Hibernate Validator 9.1.0.Final on the classpath (finding 2). `MethodArgumentNotValidException` itself is a Spring MVC type and would compile without it, but the advice exists to render Bean Validation failures and #15's `CategoryRequest` carries the constraints, so the starter belongs to this task rather than to that one.

Then the test. Plain JUnit, no Spring context, static data in named constants, `finds` prefix, per the spec's testing rules. It sits in the same package as the advice because `handleMethodArgumentNotValid` is `protected` (finding 3).

Create `catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java`:

```java
package com.thedarkhorse.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

class CatalogExceptionHandlerTest {

    private static final String OBJECT_NAME = "categoryRequest";
    private static final String SLUG = "slug";
    private static final String SORT_ORDER = "sortOrder";
    private static final String BLANK_MESSAGE = "must not be blank";
    private static final String NEGATIVE_MESSAGE = "must be greater than or equal to 0";
    private static final String CONFLICT_DETAIL = "The request conflicts with the current state of the resource";
    private static final String UNEXPECTED_DETAIL = "The request could not be completed";
    private static final String CONSTRAINT_NAME = "category_path_key";
    private static final String SQL_MESSAGE =
            "duplicate key value violates unique constraint \"" + CONSTRAINT_NAME + "\"";

    private final CatalogExceptionHandler handler = new CatalogExceptionHandler();

    private MethodArgumentNotValidException rejecting(FieldError... fieldErrors) throws Exception {
        MethodParameter parameter =
                new MethodParameter(ValidationError.class.getDeclaredMethod("field"), -1);
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), OBJECT_NAME);
        for (FieldError fieldError : fieldErrors) {
            bindingResult.addError(fieldError);
        }
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    @SuppressWarnings("unchecked")
    private List<ValidationError> errorsOf(ResponseEntity<Object> response) {
        ProblemDetail body = (ProblemDetail) response.getBody();
        return (List<ValidationError>) body.getProperties().get("errors");
    }

    private ResponseEntity<Object> handle(MethodArgumentNotValidException exception) {
        return handler.handleMethodArgumentNotValid(
                exception,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest()));
    }

    @Test
    void findsTheRejectedFieldAndItsReason() throws Exception {
        ResponseEntity<Object> response =
                handle(rejecting(new FieldError(OBJECT_NAME, SLUG, BLANK_MESSAGE)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ProblemDetail) response.getBody()).getStatus()).isEqualTo(400);
        assertThat(errorsOf(response)).containsExactly(new ValidationError(SLUG, BLANK_MESSAGE));
    }

    @Test
    void findsBothRejectedFieldsWhenTwoFailAtOnce() throws Exception {
        ResponseEntity<Object> response =
                handle(
                        rejecting(
                                new FieldError(OBJECT_NAME, SLUG, BLANK_MESSAGE),
                                new FieldError(OBJECT_NAME, SORT_ORDER, NEGATIVE_MESSAGE)));

        assertThat(errorsOf(response))
                .containsExactlyInAnyOrder(
                        new ValidationError(SLUG, BLANK_MESSAGE),
                        new ValidationError(SORT_ORDER, NEGATIVE_MESSAGE));
    }

    @Test
    void findsConflictForADatabaseConstraintViolation() {
        ProblemDetail body =
                handler.handleDataIntegrityViolation(new DataIntegrityViolationException(SQL_MESSAGE));

        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getDetail()).isEqualTo(CONFLICT_DETAIL);
        assertThat(body.getDetail()).doesNotContain(CONSTRAINT_NAME);
    }

    @Test
    void findsServerErrorWithoutNamingTheCause() {
        ProblemDetail body = handler.handleUnexpected(new IllegalStateException(SQL_MESSAGE));

        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getDetail()).isEqualTo(UNEXPECTED_DETAIL);
        assertThat(body.getDetail()).doesNotContain(CONSTRAINT_NAME);
        assertThat(body.getDetail()).doesNotContain(IllegalStateException.class.getSimpleName());
    }
}
```

Four details in that file are deliberate and a reviewer will ask about each.

`SQL_MESSAGE` is a real Postgres error string, so criterion 7 is asserted against the text that would actually leak rather than a placeholder. It is fed to both the 409 and the 500 handler, because both are ways SQL could reach a response body.

`rejecting` builds its `MethodParameter` from `ValidationError`'s own record accessor with index `-1`, which denotes the return type. `MethodArgumentNotValidException` only reads that parameter when building its message string, so any real executable does. A private dummy method was the obvious alternative and was measured to raise two Error Prone warnings — `[UnusedMethod] Method 'target' is never used` and `[UnusedVariable] The parameter 'slug' is never read` — which do not fail the build but do add noise no one should have to explain.

`BeanPropertyBindingResult` is constructed over `new Object()` and the errors are supplied through `addError` rather than `rejectValue`, because `rejectValue` resolves the field against the target through a bean wrapper and would need a stand-in class with `slug` and `sortOrder` properties. `addError` needs no target at all.

`containsExactlyInAnyOrder` in the two-field test is not laziness: finding 5 measured Hibernate Validator reporting `sortOrder` before `slug` for a record declaring `slug` first. Field order is not part of the contract and no assertion may pin it.

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -pl catalog-service clean verify`

Expected: `BUILD FAILURE` at `default-testCompile`, because neither class under test exists. There will be several `cannot find symbol` findings — at minimum the `CatalogExceptionHandler` field declaration and its `new` expression, and the `ValidationError` uses in `rejecting`, `errorsOf` and each assertion:

```
[ERROR] COMPILATION ERROR :
[ERROR] .../CatalogExceptionHandlerTest.java:[33,19] cannot find symbol
  symbol:   class CatalogExceptionHandler
  location: class com.thedarkhorse.catalog.controller.CatalogExceptionHandlerTest
[ERROR] .../CatalogExceptionHandlerTest.java:[33,60] cannot find symbol
  symbol:   class CatalogExceptionHandler
[ERROR] .../CatalogExceptionHandlerTest.java:[37,37] cannot find symbol
  symbol:   class ValidationError
...
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.16.0:testCompile
        (default-testCompile) on project catalog-service: Compilation failure
```

- [ ] **Step 3: Write the minimal implementation**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/ValidationError.java`. A record because it is genuinely read-only, which is the spec's own exception to the Lombok-model rule. No test: a record with no behaviour gets none.

```java
package com.thedarkhorse.catalog.controller;

public record ValidationError(String field, String message) {}
```

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java`. `@RestControllerAdvice` is one of the two stereotypes `CLAUDE.md` allows and this is the controller layer, so it is component-scanned rather than declared as an `@Bean` — the spec's API-layer paragraph calls that consistent with the stereotype rule rather than an exception to it. No comments.

```java
package com.thedarkhorse.catalog.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class CatalogExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERRORS = "errors";
    private static final String CONFLICT_DETAIL = "The request conflicts with the current state of the resource";
    private static final String UNEXPECTED_DETAIL = "The request could not be completed";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail body = exception.getBody();
        body.setProperty(
                ERRORS,
                exception.getFieldErrors().stream()
                        .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                        .toList());
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, CONFLICT_DETAIL);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_DETAIL);
    }
}
```

Three notes for the reviewer.

`exception.getBody()` is reused rather than a fresh `ProblemDetail` being built, so `status`, `title` and `detail` stay whatever Spring's own `ErrorResponse` implementation produced — `"Invalid request content."` for this one — and only the `errors` property is added. Rebuilding the body by hand would be a second source of truth for a message Spring already localises.

Both `handleDataIntegrityViolation` and `handleUnexpected` ignore their argument, and that is the point of criterion 7: the exception's message is the thing carrying the constraint name and the SQL, so it must not reach the body. Neither method logs either — logging is not what issue #14 asks for, and `ResponseEntityExceptionHandler` already logs what it handles.

`@ExceptionHandler(Exception.class)` alongside the base class's `final handleException` does not produce an ambiguous mapping (finding 4): `Exception` is not among the types that method declares, and the resolver picks the most specific match per throwable.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -B -pl catalog-service clean verify`

Expected: `BUILD SUCCESS`, containing:

```
[INFO] Running com.thedarkhorse.catalog.controller.CatalogExceptionHandlerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.thedarkhorse.catalog.controller.CatalogExceptionHandlerTest
```

and **no** `[WARNING] ... [UnusedMethod]` or `[WARNING] ... [UnusedVariable]` line against the test. Error Prone runs on test sources too, and the measured run of this exact file produced zero findings.

- [ ] **Step 5: Run it to verify criterion 3 end to end**

The unit tests cover criteria 4, 5, 6 and 7 directly. Criterion 3 — "an unhandled exception returns a `ProblemDetail` body, not a Spring whitelabel page" — is about what the running application serves, and finding 6 shows it can be checked without shipping an endpoint, because an unmapped path is handled by the same advice chain.

Postgres, `discovery-server` and `config-server` should still be running. Start `catalog-service` as a **background** task:

```bash
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
```

```bash
java .scratch/Probe.java http://localhost:8081/api/v1/categories text/html
```

Expected, exactly — the whitelabel page is gone and the media type is the RFC 7807 one:

```
HTTP 404
Content-Type: application/problem+json
{"detail":"No static resource api/v1/categories.","instance":"/api/v1/categories","status":404,"title":"Not Found"}
```

If that body is `<html><body><h1>Whitelabel Error Page</h1>...`, the advice was not component-scanned — check that it sits under `com.thedarkhorse.catalog`, the package `CatalogServiceApplication` roots the scan at.

Confirm the same for a JSON client, which before the advice returned a Boot error map rather than a `ProblemDetail`:

```bash
java .scratch/Probe.java http://localhost:8081/api/v1/categories application/json
```

Expected: the same `application/problem+json` body. Neither body carries a `trace` field, a class name or SQL, which is criterion 7 holding on a real response and not only in a unit test.

**Stop the `catalog-service` background task.**

- [ ] **Step 6: Confirm the full gate and commit**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with `catalog-service` reporting `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

```bash
git status --short
```

Expected: `M catalog-service/pom.xml`, `?? catalog-service/src/main/java/com/thedarkhorse/catalog/controller/`, `?? catalog-service/src/test/`, plus `?? .scratch/`. Nothing else.

```bash
git add catalog-service/pom.xml catalog-service/src/main/java/com/thedarkhorse/catalog/controller catalog-service/src/test
git commit -m "$(cat <<'EOF'
feat(catalog): render every failure as a problem detail

Refs #14
EOF
)"
```

---

## Task 4: Lombok under Error Prone, then the gate, the tree and the scope

Covers acceptance criterion 8, and closes out the increment.

**Files:**
- Create then delete: `catalog-service/src/main/java/com/thedarkhorse/catalog/model/LombokProbe.java`
- Delete: `.scratch/`

**Interfaces:**
- Consumes: from Tasks 1 to 3, the four commits on branch `issue-14` and the working tree they produced; from the root POM, the `annotationProcessorPaths` carrying Lombok 1.18.46 and Error Prone 2.50.0, and the root `lombok.config` setting `lombok.addLombokGeneratedAnnotation = true`.
- Produces: nothing committed. No later task depends on this one.

- [ ] **Step 1: Understand why this step has no red**

Read finding D before doing anything here. Acceptance criterion 8 was measured twice and **cannot be made to fail**: a `@Data @NoArgsConstructor @AllArgsConstructor` class compiles clean under Error Prone 2.50.0 both with and without `lombok.addLombokGeneratedAnnotation`. There is no failing state to observe first, so this step does not invent one. What follows is a verification, honestly labelled, not a red-green cycle. Do not add `-Werror`, do not add Error Prone check-severity overrides, and do not edit the root `lombok.config` to manufacture a red — none of that is in issue #14's scope.

Note also that this increment ships **no** Lombok-annotated class: the issue excludes the business table, and the first model arrives with `Category` in #15. The class below is created, compiled and deleted inside this step.

- [ ] **Step 2: Compile a Lombok class inside the module, then delete it**

Add the dependency the class needs. In `catalog-service/pom.xml`, insert before `spring-boot-starter-test`:

```xml
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
```

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/model/LombokProbe.java`. It is shaped like the `Category` model #15 will need — `@Data` generates the accessors, `equals`, `hashCode`, `toString` and `canEqual`, which is the whole surface the criterion is about — and it includes a `BigDecimal` because the spec's money rule makes that field type unavoidable later.

```java
package com.thedarkhorse.catalog.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LombokProbe {

    private String id;
    private String parentId;
    private String slug;
    private String path;
    private int sortOrder;
    private boolean active;
    private BigDecimal price;
}
```

```bash
mvn -B -pl catalog-service clean verify
```

Expected: `BUILD SUCCESS`, with the compile picking up the extra source file and **no** Error Prone finding of any severity against it:

```
[INFO] --- compiler:3.16.0:compile (default-compile) @ catalog-service ---
[INFO] Compiling 4 source files with javac [debug parameters release 25] to target/classes
[INFO] BUILD SUCCESS
```

```bash
mvn -B -pl catalog-service clean verify 2>&1 | grep -c "LombokProbe"
```

Expected: it prints `0`. `grep -c` exits 1 when the count is zero, so a non-zero exit code here **is** the pass; read the printed number. Zero mentions of the class means Error Prone said nothing about it — which is criterion 8.

A harmless JDK warning appears during any Lombok compile on Java 25 and is not an Error Prone finding:

```
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by lombok.permit.Permit
```

Now delete the class. It was scaffolding for the check, and `CLAUDE.md` forbids shipping classes nobody asked for:

```bash
rm catalog-service/src/main/java/com/thedarkhorse/catalog/model/LombokProbe.java
```

Keep the `lombok` dependency in `catalog-service/pom.xml`. It is module wiring, which is what issue #14 is for — the issue's own words are "the wiring every later endpoint inherits" — and #15 adds `Category` on top of it without reopening the POM.

- [ ] **Step 3: Commit the dependency**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with `catalog-service` reporting `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`. The reactor compiles three source files for `catalog-service` again, not four.

```bash
git status --short
```

Expected exactly: `M catalog-service/pom.xml`, plus `?? .scratch/`. If `?? catalog-service/src/main/java/com/thedarkhorse/catalog/model/` appears, the probe class was not deleted.

```bash
git add catalog-service/pom.xml
git commit -m "$(cat <<'EOF'
chore(catalog): declare lombok for the model layer

Refs #14
EOF
)"
```

- [ ] **Step 4: Stop everything and delete the scratch directory**

Stop every background Bash task — `catalog-service` if it is still running, `config-server` and `discovery-server` — and take the database down. A process left on 8081, 8888, 8761 or 5432 breaks any rerun of Tasks 1 to 3.

```bash
docker compose -f catalog-service/docker-compose.yaml down -v
```

Expected: `Container postgres  Removed`, `Volume catalog-service_postgres-data  Removed`.

`.scratch/` is not covered by `.gitignore`, so it must be removed rather than left untracked.

```bash
rm -rf .scratch
```

```bash
git status --short
```

Expected: **no output at all**. No modified file, no untracked file. If any `target/` directory appears, `.gitignore`'s `target/` rule is not matching and something changed it — investigate rather than adding a new ignore rule.

- [ ] **Step 5: Confirm the scope of the whole issue**

```bash
git diff --stat master...HEAD
```

Expected exactly nine entries — the eight files in the File Structure table plus this plan, which was committed onto `issue-14` before implementation began:

```
 catalog-service/docker-compose.yaml
 catalog-service/pom.xml
 catalog-service/src/main/java/com/thedarkhorse/catalog/CatalogServiceApplication.java
 catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java
 catalog-service/src/main/java/com/thedarkhorse/catalog/controller/ValidationError.java
 catalog-service/src/main/resources/application.yaml
 catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java
 docs/superpowers/plans/2026-09-09-catalog-service.md
 pom.xml
```

Nothing under `.github/`, nothing under `discovery-server/`, `gateway/` or `config-server/`, no `db/migration`, no entity, no migration file.

```bash
git log --oneline master..HEAD
```

Expected: six commits — the plan, then the five from Tasks 1 to 4 — each with a `Refs #14` footer and no `Co-Authored-By` trailer.

- [ ] **Step 6: Run the gate one last time**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing `ecommerce`, `discovery-server`, `gateway`, `config-server` and `catalog-service`, and `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` for `catalog-service`. No commit — nothing changed in this step.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #14 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1 | Starts on 8081 and registers with Eureka | 1 | 1.2, 1.7, 1.9 | `Could not find the selected project in the reactor: catalog-service`; then `Tomcat started on port 8080` with `"application":[]` in the registry | `Tomcat started on port 8081`, `registration status: 204`, and `"name":"CATALOG-SERVICE"` with `"port":{"$":8081}` after a 45-second settle |
| 2 | Flyway runs with zero migrations and creates its history table in `catalog_db` | 2 | 2.2, 2.4 | `Did not find any tables.` from `psql`, and `grep -ci flyway` on the running service printing `0` | `Creating Schema History table "public"."flyway_schema_history"`, and `\dt` listing that table owned by `catalog` with `count(*)` of 0 |
| 3 | An unhandled exception returns a `ProblemDetail` body, not a Spring whitelabel page | 3 | 3.2, 3.5 | `cannot find symbol: class CatalogExceptionHandler` at `testCompile`; and, before the advice, `Content-Type: text/html` carrying `<h1>Whitelabel Error Page</h1>` | `Content-Type: application/problem+json` with `{"detail":...,"instance":...,"status":404,"title":"Not Found"}` for both `Accept: text/html` and `Accept: application/json` |
| 4 | A request failing a constraint returns 400 with a `ProblemDetail` listing each rejected field and its reason | 3 | 3.2, 3.4 | `cannot find symbol` at `testCompile` | `findsTheRejectedFieldAndItsReason` passes: status 400 on both the entity and the body, and `errors` containing exactly `ValidationError[field=slug, message=must not be blank]` |
| 5 | Two fields failing at once are both reported | 3 | 3.2, 3.4 | `cannot find symbol` at `testCompile` | `findsBothRejectedFieldsWhenTwoFailAtOnce` passes: `errors` contains both entries, asserted in any order because Hibernate Validator's order is not the declaration order |
| 6 | A database constraint violation returns 409, not 500 | 3 | 3.2, 3.4 | `cannot find symbol` at `testCompile` | `findsConflictForADatabaseConstraintViolation` passes: status 409 from a real `DataIntegrityViolationException` |
| 7 | No response body carries a stack trace, class name or SQL | 3 | 3.2, 3.4, 3.5 | `cannot find symbol` at `testCompile` | `findsConflictForADatabaseConstraintViolation` and `findsServerErrorWithoutNamingTheCause` both assert the detail is the fixed string and contains neither `category_path_key` nor `IllegalStateException`; and the live 404 body carries no `trace` field |
| 8 | A Lombok-generated method produces no Error Prone finding | 4 | 4.1, 4.2 | **None available.** Finding D: measured with and without `lombok.addLombokGeneratedAnnotation`, Error Prone 2.50.0 reports nothing on a `@Data` class either way, so there is no failing state to observe. Step 4.1 says so instead of inventing one. | A `@Data @NoArgsConstructor @AllArgsConstructor` class compiled inside the module gives `BUILD SUCCESS` with `grep -c "LombokProbe"` printing `0`, then is deleted; the `lombok` dependency stays |

Criteria 3 to 7 all land in Task 3 because they are five readings of one advice class, and a reviewer cannot accept one and reject another: criterion 7 in particular is a property of every body the other four produce, which is why the same real Postgres constraint message is fed to both the 409 and the 500 handler.

Criterion 3 is deliberately checked live rather than only in a unit test. `handler.handleUnexpected(...)` returning a `ProblemDetail` proves the method, not the wiring; only a request to the running application proves the advice is component-scanned and that the whitelabel page is actually gone. Finding 6 is what makes that possible in a module that ships no endpoint.

Criterion 2 is deliberately not checked by the log alone. Flyway logging `Creating Schema History table` and Postgres actually holding that table are different claims, and Step 2.4 asserts the second with `psql` against the real container.

---

## Pull request

Title: `feat(catalog): add catalog-service module with the problem detail error contract (#14)`

Body lists each of the eight acceptance criteria with the command and the output that verified it, and ends with `Closes #14`. Merge with rebase, never squash, so the five per-cycle commits survive.

The body must also carry the four places issue #14 did not survive contact with the tool, since a reviewer comparing the module to the issue text will otherwise flag all four:

- **"Flyway wiring" in Spring Boot 4 is `spring-boot-starter-flyway` plus `flyway-database-postgresql`, not `flyway-core`.** Boot 4 moved `FlywayAutoConfiguration` out of `spring-boot-autoconfigure` into `org.springframework.boot:spring-boot-flyway`. With `flyway-core` alone the application starts, the build is green, and Flyway never runs — measured: not one Flyway log line and `Did not find any tables.` in `catalog_db`. The starter does not bring `flyway-core`; `flyway-database-postgresql` does.
- **The Compose volume mounts at `/var/lib/postgresql`, not `/var/lib/postgresql/data`.** `postgres:18` exits 1 on first boot with the older path and says so in its own logs. This is an image contract change, not a typo.
- **`server.port`, `spring.datasource.*` and `eureka.client.service-url.defaultZone` are not in this module's `application.yaml`.** They are already on this repository's `config` branch — `catalog-service.yaml` carries `server.port: 8081`, `catalog-service-local.yaml` carries the datasource, `application-local.yaml` carries the Eureka zone — and the config server serves all four files merged. The module's own YAML is three keys: name, profile, import. `spring.config.import: "configserver:"` is not optional, so a missing config server fails startup rather than silently falling back to 8080.
- **`spring.mvc.problemdetails.enabled` is not written anywhere.** Boot 4.1.0's own configuration metadata gives it `"defaultValue": false`, the issue asks only that it stay false, and `CLAUDE.md` forbids knobs nobody requested.

And the two things the issue's acceptance list implies but the increment cannot ship:

- **Acceptance criterion 8 has no failing state.** A `@Data` class compiles clean under Error Prone 2.50.0 with `lombok.addLombokGeneratedAnnotation` on *or* off, so the criterion was verified by compiling such a class inside the module, observing zero Error Prone output about it, and deleting it. The `lombok` dependency ships; the first committed Lombok class is `Category` in #15. Incidentally, the root POM's `${lombok.version}` resolves to no property at all — `mvn help:evaluate` prints `null object or invalid expression` — and maven-compiler-plugin falls back to the BOM-managed 1.18.46, which is not the 1.18.42 the spec's pin list names. Neither is issue #14's to fix.
- **Six of the eight packages in the issue's layout diagram are not created.** Git cannot track an empty directory and `CLAUDE.md` forbids placeholder files, so `config/`, `service/`, `repository/`, `jpa/`, `model/`, `mapper/` and `exception/` arrive in #15 with their first class. The layout is a rule about where classes go, not a directory manifest.
