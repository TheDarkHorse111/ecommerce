# config-server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `config-server`, a Spring Cloud Config server on port 8888 that registers with Eureka and serves a `local` and a `prod` profile out of a git backing repository, together with the backing repository itself.

**Architecture:** One Maven module, `config-server/`, parented by the root POM and listed in its `<modules>`. Two dependencies — `spring-cloud-config-server` and `spring-cloud-starter-netflix-eureka-client` — one Java class carrying `@SpringBootApplication` and `@EnableConfigServer`, and one `application.yaml` holding the port, the application name, and the git backend's URI and label. The configuration it serves lives outside the module, in a git repository laid out as one shared triplet plus one triplet per application. There is no controller, service, repository or JPA layer, so the layering rules have nothing to bite on here.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Cloud 2025.1.2, `spring-cloud-config-server` 5.0.4, `spring-cloud-starter-netflix-eureka-client` 5.0.2, JGit 7.4.0, Error Prone 2.50.0, maven-compiler-plugin 3.16.0.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` (section 3 service table and "Configuration", section 6 dependency pins, section 7 step 2). Issue #11 is the specification for this increment. Issue #10, already merged, is its dependency and is planned in `docs/superpowers/plans/2026-09-09-discovery-server.md`.

---

## Global Constraints

Copied from `CLAUDE.md`, issue #11 and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. There is no separate lint step. `.github/workflows/build.yml` runs exactly that on Corretto 25.
- **Port:** 8888. **Package root:** `com.thedarkhorse.config`. **Module directory:** `config-server/`, flat at the repository root.
- **Backing repository layout** — exactly these six files at the repository root, no others:
  `application.yaml`, `application-local.yaml`, `application-prod.yaml`, `catalog-service.yaml`, `catalog-service-local.yaml`, `catalog-service-prod.yaml`.
- **Profile-varying keys are limited to** datasource URL and credentials, Eureka `defaultZone`, Keycloak issuer URI, Redis, Meilisearch and Redpanda hosts, log level, and SQL echo. Everything else is shared.
- **`prod` carries the same values as `local`, except log level**: `local` is `DEBUG` with SQL echo on, `prod` is `INFO` with it off.
- **Real `prod` values are not committed.** The `prod` datasource values in this plan are the same placeholders as `local`, exactly as the issue instructs.
- **Annotations.** `@Component`, `@Service` and `@Repository` are forbidden. `@RestController` and `@RestControllerAdvice` are the only stereotypes and are controller-layer only. This module has neither. `@SpringBootApplication` and `@EnableConfigServer` are not stereotypes and are not covered by that ban.
- **TDD.** No class is written before a failing test for it has been seen to fail. TDD applies to code with behaviour. This module contains no branch, loop, validation, orchestration, arithmetic or HTTP contract of our own writing, so per `CLAUDE.md` it gets **no JUnit test**, and `src/test/` is not created. Every red step below is a command whose observed failure is recorded verbatim. **Never test the framework** — do not write a `@SpringBootTest` asserting that Spring Cloud Config merges files, and note the spec forbids a Spring context in tests outright.
- **Comments.** None. No Javadoc, no comment blocks, and none in `pom.xml`, `application.yaml`, or in any file of the backing repository. Rationale lives in the spec and in this plan.
- **Scope.** Build only what issue #11 asks for. In particular do **not** add an actuator dependency, `/monitor`, Spring Cloud Bus, a Kafka binder, `spring.cloud.config.server.git.search-paths`, `clone-on-start`, `refresh-rate`, basic auth, a Dockerfile, a Compose file, or a `README`. Bus and `/monitor` are spec section 3 "Configuration" work and belong to a later issue; nothing in issue #11's Acceptance section needs them. See "Deliberate omissions" for the three the logs and the design paragraph will tempt you into.
- **Every plugin carries an explicit version** — but this module declares only `spring-boot-maven-plugin`, with no version and no configuration, because the root `pluginManagement` supplies both. That is the pattern the spec fixes for every runnable module.
- **Conventional Commits** with `config` as the scope, one commit per completed red-green cycle, every commit green, each carrying a `Refs #11` footer. No `Co-Authored-By` trailer, no generated-with footer.
- **Pull request** title ends `(#11)`; body ends `Closes #11`. Merge with rebase, never squash.

---

## Verified Findings

Every claim issue #11 makes about tool behaviour was executed in this repository before planning on top of it, using a throwaway module at `.scratch/config-probe/` parented to the real root `pom.xml` by `<relativePath>` and never listed in `<modules>`, plus a throwaway bare git repository at `.scratch/backing.git`. Both were deleted afterwards.

**One claim turned out to be false, and it is the one the whole issue rests on.** It is finding 1. Findings 2 to 8 are confirmations; findings A to E are things the issue does not say that the plan depends on.

### 1. FALSE: "Creating the backing repository is part of this issue"

The workflow that implements this issue cannot create a GitHub repository. `.github/workflows/claude-advance.yml` authenticates as the `claude[bot]` GitHub App installation, and that installation is scoped to exactly one repository:

```
$ gh api /installation/repositories --jq '.repositories[].full_name'
TheDarkHorse111/ecommerce
```

It has no user context at all:

```
$ gh api user
{"message":"Resource not accessible by integration",
 "documentation_url":"https://docs.github.com/rest/users/users#get-the-authenticated-user","status":"403"}
```

Both repository-creation paths were probed with inputs that cannot create anything even on success — the REST call omits the required `name`, so a permitted token would answer `422 Validation Failed`, and the GraphQL mutation passes a blank name, so a permitted token would answer `Name can't be blank`. Both returned a permission error instead:

```
$ gh api -X POST /user/repos -f description=probe
{"message":"Resource not accessible by integration",
 "documentation_url":"https://docs.github.com/rest/repos/repos#create-a-repository-for-the-authenticated-user","status":"403"}

$ gh api graphql -f query='mutation { createRepository(input: {name: "", visibility: PUBLIC}) { repository { name } } }'
{"data":{"createRepository":null},"errors":[{"type":"FORBIDDEN","path":["createRepository"],
 "message":"Resource not accessible by integration"}]}
```

`TheDarkHorse111` is a `User`, not an organisation, so `POST /orgs/{org}/repos` — the one creation endpoint that does work with an installation token — does not apply:

```
$ gh api /users/TheDarkHorse111 --jq '{login,type}'
{"login":"TheDarkHorse111","type":"User"}
```

There is no pre-made repository to fall back on either. `TheDarkHorse111/ecommerce-config` is a 404, and the two similarly named repositories that do exist are from unrelated 2024 tutorial projects — `ecommerce-configs` holds `account.yml`, `item.yml`, `order.yml` and `eurekaserver.yml`, and `service-config` holds `accounts.yml`, `cards.yml` and `loans.yml`.

**Planned against what was observed.** The backing repository is created as an **orphan branch named `config` inside `TheDarkHorse111/ecommerce`**, which the installation token can push to (`contents: write`, and `actions/checkout` leaves a usable credential in the repository's git config). Being orphaned, it shares no history with `master` and contains only the six configuration files, so it behaves as a separate repository in every way that matters here: a config change is one push to `config` and touches no code, and the config server reads it over HTTPS from `https://github.com/TheDarkHorse111/ecommerce.git` with `default-label: config`.

This is a deviation from the issue's Design block and from spec section 3 "Configuration", both of which say a separate repository. It is deliberate, it is the only option the automation has, and it is **one line to undo**: once a human creates `TheDarkHorse111/ecommerce-config` and pushes the six files there, change `uri` to that repository, delete the `default-label` line, and delete the `config` branch. The pull request must say all of this.

If `gh repo create` unexpectedly succeeds when Task 1 runs it, take that instead: use the new repository as the `uri`, drop `default-label` if its default branch is `main`, and skip the orphan-branch steps. Task 1 Step 2 tells you how to tell the two outcomes apart.

### Confirmed

2. **The pinned train resolves Config Server 5.0.4**, matching the spec's "Config Monitor 5.0.4" note. No version is written in the module POM.

   ```
   $ mvn -B -f .scratch/config-probe/pom.xml dependency:tree -Dincludes=org.springframework.cloud:*,org.eclipse.jgit:*
   +- org.springframework.cloud:spring-cloud-config-server:jar:5.0.4:compile
   |  +- org.springframework.cloud:spring-cloud-config-client:jar:5.0.4:compile
   |  +- org.eclipse.jgit:org.eclipse.jgit:jar:7.4.0.202509020913-r:compile
   |  +- org.eclipse.jgit:org.eclipse.jgit.http.apache:jar:7.4.0.202509020913-r:compile
   |  \- org.eclipse.jgit:org.eclipse.jgit.ssh.apache:jar:7.4.0.202509020913-r:compile
   \- org.springframework.cloud:spring-cloud-starter-netflix-eureka-client:jar:5.0.2:compile
   ```

   The artifact is `spring-cloud-config-server`; there is no `spring-cloud-starter-config-server`. The web server arrives transitively — nothing extra is needed to serve HTTP. `spring-cloud-config-client` arrives transitively too and stays inert, because it only activates when `spring.config.import=configserver:` is set, which this module does not set.

3. **The module builds under the root parent, Error Prone runs on it, and `repackage` fires from the inherited `pluginManagement`** when the module declares the plugin with no version and no configuration.

   ```
   [INFO] --- compiler:3.16.0:compile (default-compile) @ config-probe ---
   [INFO] Compiling 1 source file with javac [debug parameters release 25] to target/classes
   [INFO] --- spring-boot:4.1.0:repackage (default) @ config-probe ---
   [INFO] Replacing main artifact .../config-probe-0.0.1-SNAPSHOT.jar with repackaged archive,
          adding nested dependencies in BOOT-INF/.
   ```

   No `forked`, which is what the spec requires.

4. **The config server clones a public GitHub repository over anonymous HTTPS from this runner.** Pointed at `https://github.com/TheDarkHorse111/ecommerce-configs.git`, `GET /account/prod` returned `200` with both `account-prod.yml` and `account.yml` merged. Network access from the Actions runner to github.com is not a problem, and a public repository needs no credentials.

5. **`.yaml` is served, not only `.yml`, and the merge order is the one the issue describes.** Against the six-file layout this plan commits, `GET /catalog-service/local` returned `200` with exactly four property sources, in this order:

   ```
   catalog-service-local.yaml   {"spring.datasource.url": "...", "spring.datasource.username": "catalog",
                                 "spring.datasource.password": "catalog", "spring.jpa.show-sql": true}
   application-local.yaml       {"logging.level.root": "DEBUG",
                                 "eureka.client.service-url.defaultZone": "http://localhost:8761/eureka/"}
   catalog-service.yaml         {"server.port": 8081}
   application.yaml             {"spring.jpa.hibernate.ddl-auto": "validate", "spring.jpa.show-sql": false}
   ```

   Earlier entries win. Note that `application-local.yaml` outranks `catalog-service.yaml`: the shared profile file beats the service's profile-less file. **A key in `catalog-service.yaml` therefore cannot override one in `application-local.yaml`,** which is why the two keys this plan uses for acceptance criteria 3 and 4 live where they do.

6. **`GET /gateway/local` returns `200` from the shared files alone**, with only `application-local.yaml` and `application.yaml` in `propertySources` — confirming the issue's statement that an application with no triplet reads the shared files. Not an acceptance criterion, checked because Task 1's file list depends on it being true.

7. **A push to the backing repository is served on the next request with no restart.** With the server running continuously, `spring.jpa.hibernate.ddl-auto` was changed from `validate` to `none` in the backing repository and pushed. The very next `GET /catalog-service/local`, against the same process, returned the new value and a new `version`:

   ```
   before   "version":"11d316c3d10b3f6ef15e3c684f523cc0af5edb97"  "spring.jpa.hibernate.ddl-auto":"validate"
   after    "version":"f7ee5bb46f504b9a84e5d82550e26eb2f5614e05"  "spring.jpa.hibernate.ddl-auto":"none"
   ```

   No configuration was needed for this. The git backend's default `refresh-rate` of `0` fetches on every request, so do not add the property.

8. **`spring.cloud.config.server.git.default-label` selects a non-default branch.** With the backing repository's `main` and `config` branches deliberately holding different values and `default-label: config` set, `GET /catalog-service/local` served the `config` branch's commit. This is the property the orphan-branch layout depends on.

9. **The config server registers with Eureka on 8888 as `CONFIG-SERVER`.** With `discovery-server` from issue #10 running and the probe carrying `spring-cloud-starter-netflix-eureka-client` and `spring.application.name: config-server`, `GET http://localhost:8761/eureka/apps` returned:

   ```json
   {"applications":{"versions__delta":"1","apps__hashcode":"UP_1_","application":[{"name":"CONFIG-SERVER",
     "instance":[{"instanceId":"...:config-server:8888","app":"CONFIG-SERVER","status":"UP",
     "port":{"$":8888,"@enabled":"true"},"vipAddress":"config-server", ...}]}]}}
   ```

### Five things the issue does not say

**A. `@EnableConfigServer` is mandatory, and omitting it gives a running server with no endpoints rather than a startup failure.**

The issue's Design block never mentions the annotation. With `@SpringBootApplication` alone the application starts perfectly happily —

```
INFO ... o.s.boot.tomcat.TomcatWebServer : Tomcat started on port 8888 (http) with context path '/'
INFO ... probe.ConfigProbeApplication    : Started ConfigProbeApplication in 3.116 seconds
```

— and then every config endpoint is a 404:

```
$ java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json
HTTP 404
{"timestamp":"...","status":404,"error":"Not Found","path":"/catalog-service/local"}
```

This is a quieter failure than `discovery-server`'s, which refused to start at all. Task 2 uses exactly this 404 as its second red.

**B. The default label is `main`, and a label that does not exist silently falls back to `master` with a 200.**

Pointed at `ecommerce-configs`, whose only branch is `master`, every label-less request logged a stack trace and then answered 200 anyway:

```
Caused by: org.eclipse.jgit.api.errors.RefNotFoundException: Ref main cannot be resolved
	at org.springframework.cloud.config.server.environment.JGitEnvironmentRepository.checkout(JGitEnvironmentRepository.java:493)
INFO ... .c.s.e.MultipleJGitEnvironmentRepository : Will try to refresh master label instead.
```

An explicit `GET /account/prod/main` did the same and returned `"label":"main"` with `master`'s content. Two consequences the plan depends on. First, `default-label: config` is not optional decoration — without it the server would look for `main`, fail, and serve `master`, which on `TheDarkHorse111/ecommerce` is the code branch with no configuration files at its root, giving an empty `propertySources` and a 200. Second, **a 200 does not prove the label was found**, so Task 2 pairs every response check with `grep -c` for `RefNotFoundException` over the server log, which must print `0`.

**C. `file:` URIs are not cloned, so they cannot be pushed to.**

The spec says a filesystem path under `local` "makes a push invisible to the running server". The mechanism is blunter than that: a `file:` URI is treated as an existing working copy, not as a remote to clone. Pointed at a bare repository it does not work at all —

```
Caused by: java.lang.IllegalStateException: No .git at file:///home/runner/work/ecommerce/ecommerce/.scratch/backing.git
HTTP 500
```

— and pointed at a non-bare clone it reads that clone's working tree directly, which is a branch you cannot push to. A schemeless path such as `/home/.../backing.git` is a different code path: that one *is* cloned, and that is the path finding 7 was measured on. None of this changes the plan, which uses an `https://` remote throughout; it is recorded so that nobody "simplifies" the URI to a local path when a check is inconvenient.

**D. `eureka.client.service-url.defaultZone` does not need to be set on this module.** The Eureka client's built-in default is already `http://localhost:8761/eureka/`. Finding 9 was measured with the property absent from the probe's `application.yaml` and the registration still succeeded. `CLAUDE.md` forbids configuration knobs nobody requested, so it is not set. The backing repository *does* carry it, in `application-local.yaml` and `application-prod.yaml`, because the issue names Eureka `defaultZone` as one of the profile-varying keys that config clients read.

**E. `spring.application.name` is set on this module, unlike on `discovery-server`.** It is what makes the registry entry read `CONFIG-SERVER` and the `vipAddress` read `config-server` rather than `UNKNOWN`. Acceptance criterion 1 is that the server "appears in the Eureka registry", and an entry named `UNKNOWN` would collide with every other unnamed application the moment a second service registers. `discovery-server` had no such problem because it registers with nobody.

### Tooling constraint: there is no `curl` and no `wget`

The implementing job's allowlist (`.github/workflows/claude-advance.yml`, the `implement` job) is:

```
Edit,Write,Bash(mvn:*),Bash(git:*),Bash(gh:*),Bash(ls:*),Bash(cat:*),Bash(find:*),Bash(mkdir:*),
Bash(mv:*),Bash(cp:*),Bash(rm:*),Bash(cd:*),Bash(grep:*),Bash(java:*),Bash(javap:*),Bash(printf:*),
Bash(test:*),Bash(jar:*),Bash(unzip:*),Bash(head:*),Bash(tail:*),Bash(wc:*),Bash(sort:*),
Bash(diff:*),Bash(echo:*),Bash(docker:*)
```

No HTTP client is on it. `Bash(java:*)` is, and Java's single-file source launcher runs a `.java` file directly, so **Task 2 Step 1 creates `.scratch/Probe.java` and every HTTP check in this plan runs through it.** That exact file was used for all the findings above. `Bash(gh:*)` is on the list and is the only way to inspect the backing branch on GitHub.

Several checks need a server running while the check executes. Start each jar as a **background** Bash task and stop it with the harness's background-task stop when done; `kill` and `pkill` are not on the allowlist. Only one process may hold a port at a time, so stop the previous one before starting the next. Task 3 and Task 4 need `discovery-server` on 8761 and `config-server` on 8888 at the same time — that is two background tasks, which is fine.

When a background Bash task starts, the harness reports the file its output is being written to, for example `/tmp/.../tasks/bx062i93h.output`. Several steps below grep the `config-server` task's file. They are written with `CONFIG_LOG` standing in for it — **substitute the real path the harness printed**, and note that it is assigned per task, so it changes every time you restart the server.

### Deliberate omissions

- **No `/monitor`, no Spring Cloud Bus, no Kafka binder.** Spec section 3 describes them, issue #11 does not ask for them, and no acceptance criterion touches them. Criterion 6 is satisfied by the git backend's own per-request fetch (finding 7), which is a different mechanism from Bus refresh and needs no broker.
- **No actuator dependency.** Not requested. The Eureka instance metadata advertises `/actuator/health` and `/actuator/info` regardless; those URLs 404 and nothing in this issue reads them.
- **No client wiring.** The issue's Design block ends "Clients use `spring.config.import: configserver:` with `spring.profiles.active` defaulting to `local`." No acceptance criterion covers it, and there is no client to wire: `catalog-service` and `gateway` do not exist yet, and `discovery-server` was built by issue #10 without one. `discovery-server` is not modified. That sentence is a constraint on the issues that build those services, not work for this one.
- **No validation starter.** Startup logs `Failed to set up a Bean Validation provider: jakarta.validation.NoProviderFoundException`. It is a benign `INFO` from `OptionalValidatorFactoryBean` and the spec scopes `spring-boot-starter-validation` to the API layer. Do not add it to make the line go away.

---

## File Structure

Committed to `TheDarkHorse111/ecommerce`, branch `issue-11`:

| Path | Responsibility | Action |
| --- | --- | --- |
| `pom.xml` | Root parent. Gains one `<module>config-server</module>` entry after `discovery-server`. Nothing else in it changes. | Modify |
| `config-server/pom.xml` | Module coordinates, two dependencies, one plugin declaration with no version and no configuration. | Create |
| `config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java` | The whole application: `@SpringBootApplication`, `@EnableConfigServer`, `main`. | Create |
| `config-server/src/main/resources/application.yaml` | Port 8888, application name, git backend URI and label. | Create |

Committed to `TheDarkHorse111/ecommerce`, orphan branch `config` — the backing repository, sharing no history with `master`:

| Path | Responsibility |
| --- | --- |
| `application.yaml` | Every service, every profile. Holds the schema-management default and the SQL-echo default. |
| `application-local.yaml` | Every service, `local`. Log level `DEBUG`, Eureka `defaultZone`. |
| `application-prod.yaml` | Every service, `prod`. Log level `INFO`, Eureka `defaultZone`. |
| `catalog-service.yaml` | `catalog-service`, every profile. Its port. |
| `catalog-service-local.yaml` | `catalog-service`, `local`. Datasource, SQL echo on. |
| `catalog-service-prod.yaml` | `catalog-service`, `prod`. Datasource. |

Not created: `config-server/src/test/`. There is no code with behaviour to test, and the spec forbids a Spring context in tests.

Not touched: `discovery-server/`, `lombok.config`, `.mvn/jvm.config`, `.github/`, `CLAUDE.md`, `README.md`, the spec, and any other plan.

Temporary, never committed to `master` or `issue-11`:

| Path | Responsibility |
| --- | --- |
| `.scratch/config-repo/` | A git worktree on the orphan `config` branch. Created in Task 1, removed in Task 5. |
| `.scratch/Probe.java` | The HTTP client, because the allowlist has none. Created in Task 2, deleted in Task 5. |

---

## Task 1: The backing repository

Delivers the git repository the config server reads. Nothing in issue #11's Acceptance section can be observed without it. Covers no criterion on its own; every later task consumes it.

Read finding 1 before starting. The issue says to create a new GitHub repository and the token cannot; the orphan branch is the planned alternative, and Step 1 is where you confirm for yourself that the blocker is real.

**Files:**
- Create on branch `config`: `application.yaml`, `application-local.yaml`, `application-prod.yaml`, `catalog-service.yaml`, `catalog-service-local.yaml`, `catalog-service-prod.yaml`
- Create: `.scratch/config-repo/` (a worktree, removed in Task 5)

**Interfaces:**
- Consumes: nothing from this repository. It needs `origin` to be `https://github.com/TheDarkHorse111/ecommerce.git` with a usable push credential, which `actions/checkout` leaves in the repository's git config and which a worktree shares.
- Produces: the branch `config` on `https://github.com/TheDarkHorse111/ecommerce.git`, holding exactly those six files at its root. Task 2 consumes it as `spring.cloud.config.server.git.uri` plus `default-label: config`. Task 4 pushes a further commit to it. The exact keys later tasks assert on are `spring.jpa.hibernate.ddl-auto` (only in `application.yaml`), `spring.jpa.show-sql` (in `application.yaml` and `catalog-service-local.yaml`) and `logging.level.root` (in `application-local.yaml` and `application-prod.yaml`).

- [ ] **Step 1: Write the failing test**

There is no behaviour here, so per `CLAUDE.md` there is no JUnit test. The failing test is the issue's own instruction, run as the command it describes:

```bash
gh repo create TheDarkHorse111/ecommerce-config --public
```

- [ ] **Step 2: Run it to verify it fails**

Run: `gh repo create TheDarkHorse111/ecommerce-config --public`

Expected: a permission error naming the integration, non-zero exit, no repository created. The wording comes from GraphQL's `createRepository`, which is what `gh repo create` uses:

```
GraphQL: Resource not accessible by integration (createRepository)
```

Confirm the cause rather than guessing at it:

```bash
gh api /installation/repositories --jq '.repositories[].full_name'
```

Expected exactly one line, `TheDarkHorse111/ecommerce`. That is the red: the token this job runs under is installed on one repository and can create none.

**If `gh repo create` unexpectedly succeeds,** the finding in this plan has gone stale in your favour. Take the repository: push the six files below to it on its default branch, use `https://github.com/TheDarkHorse111/ecommerce-config.git` as the `uri` in Task 2, set `default-label` only if its default branch is not `main` (finding B), and skip Steps 3 and 9 of this task. Say so in the pull request. Everything else in this plan is unchanged.

Otherwise continue.

- [ ] **Step 3: Create the orphan branch as a worktree**

An orphan branch shares no commit with `master`, so the `config` branch holds the six files and nothing else. Building it in a worktree keeps the `issue-11` working tree untouched, and a worktree shares the main repository's config, so `origin` and its push credential come for free.

```bash
git worktree add --orphan -b config .scratch/config-repo
```

Expected: `Preparing worktree (new branch 'config')`.

Confirm it is empty and unborn:

```bash
git -C .scratch/config-repo status --short --branch
```

Expected exactly: `## No commits yet on config`.

- [ ] **Step 4: Write the shared triplet**

Create `.scratch/config-repo/application.yaml`. `ddl-auto: validate` is the key acceptance criterion 4 reads: it is set here and nowhere else, so it must appear in both profile responses. Flyway owns the schema per spec section 4, so `validate` is what every service with a database wants. `show-sql: false` is the key acceptance criterion 3 reads: it is the shared default that `catalog-service-local.yaml` overrides. No comments.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

Create `.scratch/config-repo/application-local.yaml`:

```yaml
logging:
  level:
    root: DEBUG

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

Create `.scratch/config-repo/application-prod.yaml`. Identical but for the log level, which is the one key the issue insists must differ so that a filename typo or a broken merge is distinguishable from success:

```yaml
logging:
  level:
    root: INFO

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

- [ ] **Step 5: Write the catalog-service triplet**

`catalog-service` gets its own triplet because it has a key the shared files cannot carry: its datasource. Create `.scratch/config-repo/catalog-service.yaml` — port 8081 comes from the spec's service table, and spec section 3 "Configuration" puts port and datasource in the config repository, taking effect on the next restart:

```yaml
server:
  port: 8081
```

Create `.scratch/config-repo/catalog-service-local.yaml`. `show-sql: true` is the override criterion 3 reads, and it is also the issue's "`local` is `DEBUG` with SQL echo on":

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog_db
    username: catalog
    password: catalog
  jpa:
    show-sql: true
```

Create `.scratch/config-repo/catalog-service-prod.yaml`. Same datasource values as `local`, because the issue says `prod` carries the same values and that real production values are not committed. No `show-sql` key, so `prod` inherits `false` from `application.yaml` — that is the issue's "`prod` is `INFO` with it off":

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog_db
    username: catalog
    password: catalog
```

- [ ] **Step 6: Verify the branch holds six files and nothing else**

```bash
git -C .scratch/config-repo status --short
```

Expected exactly six lines, all `??`, and no directory entries:

```
?? application-local.yaml
?? application-prod.yaml
?? application.yaml
?? catalog-service-local.yaml
?? catalog-service-prod.yaml
?? catalog-service.yaml
```

If anything else appears — a `target/`, a stray file, a nested directory — remove it before committing. A seventh file at the root would be served to every application.

- [ ] **Step 7: Commit on the config branch**

```bash
git -C .scratch/config-repo add -A
git -C .scratch/config-repo commit -m "$(cat <<'EOF'
feat(config): add the backing repository for the config server

Refs #11
EOF
)"
```

`user.name` and `user.email` are already set on the repository and are shared with the worktree, so no `-c` overrides are needed.

- [ ] **Step 8: Push it**

```bash
git -C .scratch/config-repo push -u origin config
```

Expected:

```
 * [new branch]      config -> config
branch 'config' set up to track 'origin/config'.
```

- [ ] **Step 9: Confirm GitHub has it**

The config server clones over HTTPS from GitHub, not from the local worktree, so what matters is what GitHub holds.

```bash
gh api /repos/TheDarkHorse111/ecommerce/git/trees/config --jq '.tree[].path'
```

Expected exactly, in some order:

```
application-local.yaml
application-prod.yaml
application.yaml
catalog-service-local.yaml
catalog-service-prod.yaml
catalog-service.yaml
```

Confirm the branch is orphaned — no shared history with `master`, so a config change never drags code with it:

```bash
gh api /repos/TheDarkHorse111/ecommerce/commits/config --jq '.parents | length'
```

Expected: `0`.

Confirm `master` is untouched:

```bash
git status --short
```

Expected: only `.scratch/` as untracked. No modified file. There is no commit on `issue-11` in this task — the deliverable lives on the `config` branch — which is consistent with "every commit is green", because there is nothing on `issue-11` to record yet.

---

## Task 2: The module serves both profiles on 8888

Covers acceptance criterion 2 (`GET /catalog-service/local` and `GET /catalog-service/prod` both return 200), criterion 3 (the service-specific override wins), criterion 4 (a shared-only key appears in both), criterion 5 (log level differs), and the "starts on 8888" half of criterion 1. The Eureka half is Task 3.

Two red-green cycles, so two commits.

**Files:**
- Modify: `pom.xml:12-14` — add one `<module>` entry to the existing `<modules>` element
- Create: `config-server/pom.xml`
- Create: `config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java`
- Create: `config-server/src/main/resources/application.yaml`
- Create: `.scratch/Probe.java` (temporary, deleted in Task 5)

**Interfaces:**
- Consumes: from issue #1, the parent coordinates `com.thedarkhorse:ecommerce:0.0.1-SNAPSHOT` with `pom` packaging, the `pluginManagement` entry for `spring-boot-maven-plugin` carrying the `repackage` execution, the `spring-cloud-dependencies` 2025.1.2 BOM import that supplies both dependency versions, and `.mvn/jvm.config`, without which Error Prone cannot start. From Task 1, the branch `config` on `https://github.com/TheDarkHorse111/ecommerce.git`.
- Produces: the module artifact `com.thedarkhorse:config-server:0.0.1-SNAPSHOT`, whose repackaged jar is at `config-server/target/config-server-0.0.1-SNAPSHOT.jar`, and the class `com.thedarkhorse.config.ConfigServerApplication` with `public static void main(String[] args)`. Task 3 adds one dependency to `config-server/pom.xml` and one key to `config-server/src/main/resources/application.yaml`, and changes no Java. `.scratch/Probe.java` exposes `Probe.main(String[])`, invoked as `java .scratch/Probe.java <url> [accept] [settleSeconds]`; Tasks 3 and 4 use it and Task 5 deletes it.

- [ ] **Step 1: Write the failing test**

Two pieces. First the HTTP client, because the allowlist has none. Create `.scratch/Probe.java` exactly as below. It retries the connection for up to two minutes so it can be launched before the server is up, then sleeps `settleSeconds` before the request it actually reports — that sleep is what Task 3 needs to get past Eureka's response cache. It carries no comments, consistent with the standing rule, and it is a throwaway harness rather than production code, so it needs no test of its own.

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
                .timeout(Duration.ofSeconds(60))
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

Second, the check, run against the repository as it stands:

```bash
mvn -B -pl config-server clean package
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -B -pl config-server clean package`

Expected: a non-zero exit, because the module does not exist and is not in the reactor. Maven fails during project selection, before the reactor runs, so there is **no** `BUILD FAILURE` line — the whole output is:

```
[ERROR] [ERROR] Could not find the selected project in the reactor: config-server @
[ERROR] Could not find the selected project in the reactor: config-server -> [Help 1]
```

Confirm what the reactor holds today:

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary of exactly two lines, `ecommerce` and `discovery-server`. No `config-server`. That is the red.

- [ ] **Step 3: Write the minimal implementation**

In the root `pom.xml`, replace lines 12-14:

```xml
    <modules>
        <module>discovery-server</module>
    </modules>
```

with:

```xml
    <modules>
        <module>discovery-server</module>
        <module>config-server</module>
    </modules>
```

Change nothing else in the root POM.

Create `config-server/pom.xml`. No `<relativePath>` — a module in the reactor finds `../pom.xml` by default. No version on the plugin and no `<configuration>`; both come from the root `pluginManagement`. No version on the dependency; it comes from the Spring Cloud BOM. The Eureka client is not here yet — that is Task 3's green. No comments.

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

    <artifactId>config-server</artifactId>
    <name>config-server</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-server</artifactId>
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

Create `config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java`. Write it **without** `@EnableConfigServer` for now — Step 6 is the red that earns that annotation.

```java
package com.thedarkhorse.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

Create `config-server/src/main/resources/application.yaml` with exactly this and nothing else. `default-label` is not optional: without it the server looks for a `main` branch, does not find one, and silently serves `master` — which is the code branch, whose root holds no configuration files (finding B). No `eureka` block yet, no `defaultZone` ever (finding D). No comments.

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
```

- [ ] **Step 4: Run it to verify the module packages and starts on 8888**

```bash
mvn -B -pl config-server clean package
```

Expected: `BUILD SUCCESS`, containing both of these lines. The second is the inherited `repackage` execution firing without the module naming it:

```
[INFO] --- compiler:3.16.0:compile (default-compile) @ config-server ---
[INFO] Compiling 1 source file with javac [debug parameters release 25] to target/classes
...
[INFO] --- spring-boot:4.1.0:repackage (default) @ config-server ---
[INFO] Replacing main artifact .../config-server-0.0.1-SNAPSHOT.jar with repackaged archive,
       adding nested dependencies in BOOT-INF/.
```

If the compile line says `forked`, someone added `<fork>true</fork>`; the spec forbids it, so remove it.

Start the jar as a **background** Bash task, because it does not exit. Note the output path the harness reports; that is `CONFIG_LOG`.

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Then:

```bash
grep -E 'Tomcat started|Started ConfigServerApplication' CONFIG_LOG
```

Expected:

```
INFO ... o.s.boot.tomcat.TomcatWebServer      : Tomcat started on port 8888 (http) with context path '/'
INFO ... c.t.c.ConfigServerApplication        : Started ConfigServerApplication in N seconds
```

Expect one benign line in the same output, explained under "Deliberate omissions". Do not act on it:

```
INFO ... o.s.v.b.OptionalValidatorFactoryBean : Failed to set up a Bean Validation provider: ...
```

- [ ] **Step 5: Confirm the gate and commit the first cycle**

**Stop the background task first** — `mvn clean` will delete the jar underneath it.

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing `ecommerce`, `discovery-server` and `config-server`.

```bash
git status --short
```

Expected exactly: `M pom.xml`, `?? config-server/`, plus `.scratch/` untracked. Nothing under `.github/`, nothing under `discovery-server/`.

```bash
git add pom.xml config-server/pom.xml config-server/src
git commit -m "$(cat <<'EOF'
feat(config): add config server module on 8888

Refs #11
EOF
)"
```

Confirm `config-server/target/` was not staged — the root `.gitignore` already ignores `target/`.

- [ ] **Step 6: Write the second failing test**

The server is up on the right port and reads the right branch, and it serves nothing. Rebuild and restart it as a **background** Bash task:

```bash
mvn -B -pl config-server clean package
```

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Then:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json 5
```

- [ ] **Step 7: Run it to verify it fails**

Expected — the port is right, so `Probe` connects immediately rather than retrying, and Spring MVC has no handler for the path:

```
HTTP 404
Content-Type: application/json
{"timestamp":"...","status":404,"error":"Not Found","path":"/catalog-service/local"}
```

That is the red for `@EnableConfigServer`. Note that the application is running perfectly happily; nothing in the log says anything is wrong. **Stop the background task.**

- [ ] **Step 8: Add `@EnableConfigServer`**

Edit `config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java` so it reads in full:

```java
package com.thedarkhorse.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

- [ ] **Step 9: Run it to verify criterion 2 — both profiles return 200**

```bash
mvn -B -pl config-server clean package
```

Start as a **background** Bash task and note the new `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Then:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json 5
```

Expected: `HTTP 200`, `Content-Type: application/json`, and a body whose `propertySources` array holds exactly four entries in this order — `catalog-service-local.yaml`, `application-local.yaml`, `catalog-service.yaml`, `application.yaml`, each named with the `https://github.com/TheDarkHorse111/ecommerce.git/` prefix:

```json
{"name":"catalog-service","profiles":["local"],"label":null,"version":"<sha>","state":"","propertySources":[
 {"name":".../catalog-service-local.yaml","source":{"spring.datasource.url":"jdbc:postgresql://localhost:5432/catalog_db","spring.datasource.username":"catalog","spring.datasource.password":"catalog","spring.jpa.show-sql":true}},
 {"name":".../application-local.yaml","source":{"logging.level.root":"DEBUG","eureka.client.service-url.defaultZone":"http://localhost:8761/eureka/"}},
 {"name":".../catalog-service.yaml","source":{"server.port":8081}},
 {"name":".../application.yaml","source":{"spring.jpa.hibernate.ddl-auto":"validate","spring.jpa.show-sql":false}}]}
```

Then the other profile:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/prod application/json
```

Expected: `HTTP 200`, four entries again, with `catalog-service-prod.yaml` and `application-prod.yaml` in place of the `local` pair, `logging.level.root` reading `INFO`, and no `spring.jpa.show-sql` key in the `catalog-service-prod.yaml` source.

Now prove the label was actually found, because a 200 alone does not (finding B):

```bash
grep -cE 'RefNotFoundException|Will try to refresh' CONFIG_LOG
```

Expected: it prints `0`. `grep -c` exits 1 when the count is zero, so a non-zero exit code here **is** the pass; read the printed number, not the exit status. If it prints anything above `0`, the `config` branch was not found and both 200s above came from `master` — go back to Task 1 Step 9.

- [ ] **Step 10: Verify criteria 3, 4 and 5 from those two responses**

No new code and no new server. These three criteria are three readings of the two bodies from Step 9.

Criterion 3 — a key set in `application.yaml` and overridden in `catalog-service-local.yaml` returns the service-specific value. `spring.jpa.show-sql` is `false` in the `application.yaml` source and `true` in the `catalog-service-local.yaml` source, and `catalog-service-local.yaml` is first in the array, so `true` is what a client binds. Confirm both halves are present and in that order:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | grep -o '"spring.jpa.show-sql":[a-z]*'
```

Expected exactly two lines, in this order:

```
"spring.jpa.show-sql":true
"spring.jpa.show-sql":false
```

The first is `catalog-service-local.yaml`, the second `application.yaml`. If only one line appears, one of the two files is missing a key.

Criterion 4 — a key present only in `application.yaml` is present in both profile responses. `spring.jpa.hibernate.ddl-auto` is set in `application.yaml` and in no other file:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | grep -c '"spring.jpa.hibernate.ddl-auto":"validate"'
```

Expected: `1`.

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/prod application/json | grep -c '"spring.jpa.hibernate.ddl-auto":"validate"'
```

Expected: `1`. One occurrence in each, from the single shared file.

Criterion 5 — the log level differs between the two profile responses:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | grep -o '"logging.level.root":"[A-Z]*"'
```

Expected: `"logging.level.root":"DEBUG"`.

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/prod application/json | grep -o '"logging.level.root":"[A-Z]*"'
```

Expected: `"logging.level.root":"INFO"`.

**Stop the background task.** Task 3 rebuilds the jar.

- [ ] **Step 11: Confirm the gate and commit the second cycle**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`.

```bash
git status --short
```

Expected exactly: `M config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java`, plus `.scratch/` untracked.

```bash
git add config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java
git commit -m "$(cat <<'EOF'
feat(config): serve the backing repository over the config endpoints

Refs #11
EOF
)"
```

---

## Task 3: Registration with Eureka

Covers the second half of acceptance criterion 1 — the config server appears in the Eureka registry.

**Files:**
- Modify: `config-server/pom.xml` — add one dependency
- Modify: none other. `spring.application.name` is already in `application.yaml` from Task 2, because a module cannot be built twice for one key.

**Interfaces:**
- Consumes: from Task 2, the module `com.thedarkhorse:config-server:0.0.1-SNAPSHOT`, its jar at `config-server/target/config-server-0.0.1-SNAPSHOT.jar`, `spring.application.name: config-server` in its `application.yaml`, and `.scratch/Probe.java`. From issue #10, `discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar`, a Eureka server on 8761 with its own client disabled.
- Produces: a `CONFIG-SERVER` entry in the Eureka registry with `vipAddress` `config-server` on port 8888. Task 4 reuses the same two running processes.

- [ ] **Step 1: Write the failing test**

There is no behaviour here either, so the failing test is the acceptance criterion run as a command. Two servers are needed. First build both jars:

```bash
mvn -B clean package
```

Start `discovery-server` as a **background** Bash task:

```bash
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
```

Start `config-server` as a second **background** Bash task:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Then, and the `45` is load-bearing — Eureka serves `/eureka/apps` from a read-only cache that refreshes every 30 seconds, so a check taken straight after startup reads empty on a working server too:

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps application/json 45
```

- [ ] **Step 2: Run it to verify it fails**

Expected: `HTTP 200` and an empty application list, because `config-server` has no Eureka client on its classpath and does not know 8761 exists:

```
HTTP 200
Content-Type: application/json
{"applications":{"versions__delta":"1","apps__hashcode":"","application":[]}}
```

Confirm the config server is genuinely up while that check reads empty, so that the red is "not registered" rather than "not running":

```bash
grep -E 'Tomcat started|Started ConfigServerApplication' CONFIG_LOG
```

Expected: `Tomcat started on port 8888` and `Started ConfigServerApplication`. That is the red.

**Stop the `config-server` background task.** Leave `discovery-server` running — Step 4 needs it and restarting it would empty the registry.

- [ ] **Step 3: Add the Eureka client**

Edit `config-server/pom.xml` so the `<dependencies>` element reads in full. No version; the Spring Cloud BOM supplies 5.0.2.

```xml
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
    </dependencies>
```

Change nothing in `application.yaml`. `eureka.client.service-url.defaultZone` is **not** added: the client's built-in default is already `http://localhost:8761/eureka/` (finding D), and `CLAUDE.md` forbids unrequested configuration.

- [ ] **Step 4: Run it to verify criterion 1 — the server appears in the registry**

Rebuild only the module whose POM changed, so that the running `discovery-server` jar is not deleted underneath it:

```bash
mvn -B -pl config-server clean package
```

Start `config-server` as a **background** Bash task again, noting the new `CONFIG_LOG`:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Then:

```bash
java .scratch/Probe.java http://localhost:8761/eureka/apps application/json 45
```

Expected: one application, named `CONFIG-SERVER`, `UP`, on port 8888:

```json
{"applications":{"versions__delta":"1","apps__hashcode":"UP_1_","application":[{"name":"CONFIG-SERVER",
  "instance":[{"instanceId":"...:config-server:8888","app":"CONFIG-SERVER","status":"UP",
  "port":{"$":8888,"@enabled":"true"},"vipAddress":"config-server","secureVipAddress":"config-server", ...}]}]}}
```

Corroborate from the registry side, which reads the registry directly rather than through the response cache:

```bash
java .scratch/Probe.java http://localhost:8761/ | grep -E 'CONFIG-SERVER|No instances available'
```

Expected: a table row naming `CONFIG-SERVER`, and **no** `No instances available` line.

And from the client side:

```bash
grep -E 'registering service|DiscoveryClient_CONFIG-SERVER.* - registration status: 204' CONFIG_LOG
```

Expected: a `registering service...` line and a `registration status: 204` line, both naming `DiscoveryClient_CONFIG-SERVER`. 204 is what Eureka answers a successful registration.

Confirm the config endpoints still work now that a second starter is on the classpath:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | head -1
```

Expected: `HTTP 200`.

- [ ] **Step 5: Confirm the gate and commit**

**Stop both background tasks first** — `mvn clean` deletes both running jars. Task 4 starts the config server again from scratch; it needs one uninterrupted process across its own steps, not this one.

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing `ecommerce`, `discovery-server` and `config-server`.

```bash
git status --short
```

Expected exactly: `M config-server/pom.xml`, plus `.scratch/` untracked.

```bash
git add config-server/pom.xml
git commit -m "$(cat <<'EOF'
feat(config): register the config server with eureka

Refs #11
EOF
)"
```

---

## Task 4: A push to the backing repository is served without a restart

Covers acceptance criterion 6. This task changes no file that ends up on `issue-11`. Its deliverable is the observed red and green, plus a `config` branch left exactly as Task 1 committed it. It ends with **no commit on `issue-11`**, which is consistent with "every commit is green" — there is nothing green to record.

The criterion is about one uninterrupted process, so **once Step 1 starts `config-server`, do not restart it before Step 4 is done**. If it stops for any reason, start again from Step 1 with a fresh baseline reading.

**Files:**
- Modify then revert on branch `config`: `application.yaml` (in the worktree at `.scratch/config-repo/`)

**Interfaces:**
- Consumes: from Task 1, the worktree at `.scratch/config-repo/` tracking `origin/config`. From Tasks 2 and 3, the jar at `config-server/target/config-server-0.0.1-SNAPSHOT.jar`. `discovery-server` is not needed here; criterion 6 says nothing about the registry.
- Produces: nothing. No later task depends on this one. The `config` branch is left with two extra commits whose net effect on the tree is zero.

- [ ] **Step 1: Write the failing test**

Start `config-server` as a **background** Bash task and note the new `CONFIG_LOG`. This process must survive to the end of Step 4:

```bash
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
```

Record the baseline it is serving right now:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | grep -oE '"version":"[a-f0-9]+"|"spring.jpa.hibernate.ddl-auto":"[a-z]+"'
```

Expected two lines. Write both down — the second is the value criterion 6 is about, and the first is the commit the server currently has checked out:

```
"version":"<sha-A>"
"spring.jpa.hibernate.ddl-auto":"validate"
```

The failing test is that this same command must report a different value after a push, with no restart in between.

- [ ] **Step 2: Change the backing repository and push it**

Edit `.scratch/config-repo/application.yaml` so it reads in full:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
test(config): flip ddl-auto to prove refresh without restart

Refs #11
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

Expected: `<sha-A>..<sha-B>  config -> config`.

- [ ] **Step 3: Run it to verify the new value is served**

Same process, no restart, no `/actuator/refresh`, no `/monitor`:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | grep -oE '"version":"[a-f0-9]+"|"spring.jpa.hibernate.ddl-auto":"[a-z]+"'
```

Expected — a different `version`, matching `<sha-B>` from Step 2, and the new value:

```
"version":"<sha-B>"
"spring.jpa.hibernate.ddl-auto":"none"
```

Both halves matter. A changed value with an unchanged `version` would mean something other than the git fetch produced it. If the value is still `validate`, do not add `refresh-rate` or `clone-on-start` — the default already fetches per request (finding 7); check instead that Step 2's push reached GitHub with `gh api /repos/TheDarkHorse111/ecommerce/commits/config --jq .sha`.

Confirm the server really was never restarted:

```bash
grep -c 'Started ConfigServerApplication' CONFIG_LOG
```

Expected: `1`.

- [ ] **Step 4: Revert the backing repository and verify the revert is served too**

The `config` branch must end this issue holding the values Task 1 committed. Reverting also proves the mechanism twice rather than once, which rules out a one-off.

Edit `.scratch/config-repo/application.yaml` back to:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

```bash
git -C .scratch/config-repo commit -am "$(cat <<'EOF'
test(config): restore ddl-auto after the refresh check

Refs #11
EOF
)"
```

```bash
git -C .scratch/config-repo push origin config
```

Then, same running process:

```bash
java .scratch/Probe.java http://localhost:8888/catalog-service/local application/json | grep -oE '"version":"[a-f0-9]+"|"spring.jpa.hibernate.ddl-auto":"[a-z]+"'
```

Expected: a third `version`, and `"spring.jpa.hibernate.ddl-auto":"validate"` again.

Confirm the branch's tree is byte-identical to what Task 1 pushed:

```bash
git -C .scratch/config-repo diff <sha-A> HEAD --stat
```

Expected: **no output at all**. The two commits cancel out.

**Stop the background task now.** Task 5 runs `mvn -B clean verify`, which deletes the jar.

---

## Task 5: Clean up and confirm the tree

No acceptance criterion. Its deliverable is a clean working tree and a green gate, and it ends with no commit.

**Files:**
- Remove: the `.scratch/config-repo/` worktree and the local `config` branch
- Delete: `.scratch/`

**Interfaces:**
- Consumes: everything above. Produces: nothing.

- [ ] **Step 1: Remove the worktree**

The worktree is committed and pushed, so it is clean and `remove` will not complain. Removing it deletes the directory but leaves the local branch, so delete that too. Neither touches `origin/config`, which is the deliverable.

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

- [ ] **Step 2: Confirm the remote branch survived**

```bash
gh api /repos/TheDarkHorse111/ecommerce/git/trees/config --jq '.tree[].path'
```

Expected: the same six file names as Task 1 Step 9. If this is empty or 404, the branch was deleted by mistake and Task 1 must be redone before the pull request is opened.

- [ ] **Step 3: Delete the scratch directory and confirm the tree is clean**

`.scratch/` is not covered by `.gitignore`, so it must be removed rather than left untracked.

```bash
rm -rf .scratch
```

```bash
git status --short
```

Expected: **no output at all**. No modified file, no untracked file. If `config-server/target/` appears, `.gitignore`'s `target/` rule is not matching and something changed it — investigate rather than adding a new ignore rule.

- [ ] **Step 4: Confirm the gate and the shape of the branch**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS` with a reactor summary listing `ecommerce`, `discovery-server` and `config-server`.

```bash
git diff --stat master...HEAD
```

Expected exactly five entries — the four files this issue adds or modifies on `issue-11`, plus this plan, which was committed onto `issue-11` before implementation began:

```
 docs/superpowers/plans/2026-09-09-config-server.md
 config-server/pom.xml
 config-server/src/main/java/com/thedarkhorse/config/ConfigServerApplication.java
 config-server/src/main/resources/application.yaml
 pom.xml
```

Nothing under `.github/`, nothing under `discovery-server/`, and no other module. No commit — nothing changed in this task.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #11 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1a | Starts on 8888 | 2 | 2.2, 2.4 | `Could not find the selected project in the reactor: config-server` | `Tomcat started on port 8888`, `Started ConfigServerApplication` |
| 1b | Appears in the Eureka registry | 3 | 3.2, 3.4 | `"application":[]` after a 45 s settle while 8888 is confirmed up | `"name":"CONFIG-SERVER"` on port 8888, `registration status: 204`, dashboard row |
| 2 | `GET /catalog-service/local` and `GET /catalog-service/prod` both return 200 | 2 | 2.7, 2.9 | `HTTP 404` on `/catalog-service/local` with no `@EnableConfigServer` | `HTTP 200` on both, four `propertySources` each, `RefNotFoundException` count `0` |
| 3 | A key in `application.yaml` overridden in `catalog-service-local.yaml` returns the service-specific value | 2 | 2.7, 2.10 | `HTTP 404` — no response to read a key out of | `"spring.jpa.show-sql":true` then `"spring.jpa.show-sql":false`, in that order |
| 4 | A key present only in `application.yaml` is present in both profile responses | 2 | 2.7, 2.10 | `HTTP 404` | `"spring.jpa.hibernate.ddl-auto":"validate"` counts `1` in each profile |
| 5 | Log level differs between the two profile responses | 2 | 2.7, 2.10 | `HTTP 404` | `"logging.level.root":"DEBUG"` for `local`, `"INFO"` for `prod` |
| 6 | Pushing a change to the backing repository and re-requesting returns the new value with no restart | 4 | 4.1, 4.3 | `"version":"<sha-A>"`, `"ddl-auto":"validate"` | `"version":"<sha-B>"`, `"ddl-auto":"none"`, with `Started ConfigServerApplication` count still `1` |

Criteria 2 to 5 all land in Task 2 because they are four readings of one pair of HTTP responses, and a reviewer cannot sensibly accept one and reject another. Criterion 2 is deliberately not checked alone: finding B shows a 200 is also what a wrong-branch fallback returns, so Step 2.9 pairs it with a log assertion.

The issue's Design block is covered too, and two of its sentences deserve naming because no criterion tests them: `GET /gateway/local` returns the shared files alone, which is the "the gateway has none, so it reads the shared files alone" case (finding 6, verified during planning); and "clients use `spring.config.import: configserver:`" is not implemented, because there is no client — see "Deliberate omissions".

---

## Pull request

Title: `feat(config): add spring cloud config server on 8888 (#11)`

Body lists each of the six acceptance criteria with the command and the output that verified it, and ends with `Closes #11`. Merge with rebase, never squash, so the four per-cycle commits survive.

The body must lead with the deviation, because a reviewer reading only the diff will not see it:

- **The backing repository is an orphan branch, not a separate repository.** Issue #11 says "Creating the backing repository is part of this issue" and the workflow's token cannot create one: it is a `claude[bot]` GitHub App installation scoped to `TheDarkHorse111/ecommerce` alone, `gh api user` answers `403 Resource not accessible by integration`, and both `POST /user/repos` and the GraphQL `createRepository` mutation answer the same — probed with inputs that could not have created anything even on success. `TheDarkHorse111` is a user account, so the one creation endpoint that does work with an installation token, `POST /orgs/{org}/repos`, does not apply. The six files therefore live on an orphan branch `config` of this repository, sharing no history with `master`, and `spring.cloud.config.server.git.uri` points here with `default-label: config`. **To move to a real separate repository:** create `TheDarkHorse111/ecommerce-config`, push the six files from `origin/config` to its default branch, change `uri` in `config-server/src/main/resources/application.yaml`, delete the `default-label` line if that branch is `main`, and delete the `config` branch. That is the whole migration.

And the three findings a reviewer will otherwise trip over, none of which is in the issue:

- **`@EnableConfigServer` is required, and omitting it does not fail loudly.** Without it the application starts normally on 8888 and every config endpoint returns 404. There is no error in the log.
- **`default-label: config` is load-bearing, and a missing label fails as a 200.** The git backend's default label is `main`; when a label does not exist it logs `RefNotFoundException: Ref main cannot be resolved`, then `Will try to refresh master label instead`, and answers 200 from `master`. On this repository `master` is the code branch with no configuration at its root, so the fallback would return an empty `propertySources` with a 200. Every response check in this work was therefore paired with a `grep -c` for `RefNotFoundException` over the server log, which printed `0`.
- **Criterion 6 needed no configuration.** The git backend's default `refresh-rate` of `0` fetches on every request, so the pushed value was served by the next request against the same process, with a changed `version` hash and `Started ConfigServerApplication` still appearing exactly once in the log. No Spring Cloud Bus, no `/monitor` and no actuator were added; those are spec section 3 work for a later issue.

And the one omission a reviewer will ask about: `spring.config.import: configserver:` on clients is named in the issue's Design block and is not implemented here, because `catalog-service` and `gateway` do not exist yet and `discovery-server` is out of scope for this issue. No acceptance criterion covers it.
