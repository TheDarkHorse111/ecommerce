# category table and tree Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `category` table, its Flyway migration, and the read and write endpoints for the category tree in `catalog-service`, with `path` as a materialised path derived from the parent so a subtree read is one `like 'keyboards/%'` instead of recursion.

**Architecture:** One Flyway migration creates `category`. Above it the four layers the spec mandates — `controller → service → repository → jpa` — arrive one at a time, each with its own failing test. `CategoryEntity` owns the `UUID` and the two Hibernate-generated timestamps; `CategoryRepositoryImpl` converts `UUID` to `String` through a MapStruct mapper and returns `Category` models; `CategoryServiceImpl` owns every rule the issue states — deriving `path` from the parent, defaulting `sort_order` and `active`, refusing to delete a node with descendants, and recomputing `path` for a moved node and every descendant under it; `CategoryController` speaks `CategoryRequest` and `CategoryResponse` and nothing else. Integrity that belongs in the database stays there: the duplicate-slug 409 comes from `unique nulls not distinct (parent_id, slug)` and the existing `@RestControllerAdvice` from #14, not from a Java check.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Framework 7.0.8, Spring Cloud 2025.1.3, Hibernate ORM 7.4.1.Final, Hibernate Validator 9.1.0.Final on Jakarta Validation 3.1.1, MapStruct 1.6.3, Flyway 12.4.0 via `spring-boot-starter-flyway` 4.1.0 plus `flyway-database-postgresql`, PostgreSQL 18.6 (`postgres:18-alpine`) with driver 42.7.11, Lombok 1.18.46, Error Prone 2.50.0, maven-compiler-plugin 3.16.0, JUnit Jupiter 6.0.3, AssertJ 3.27.7 and Mockito 5.23.0 via `spring-boot-starter-test`.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` — section 2 for the annotation ban, the query ban, the layering, the type rules, the API layer and the testing rules; section 3 for the URL shape, `Configuration` and `Package layout`; section 4 `catalog_db` for the `category` DDL; section 7 step 3 for where this increment sits. Issue #15 is the specification for this increment; its `### Scope`, `### Design` and `### Acceptance` sections are the requirement, and its comments are not. Its stated dependency #14 is merged at `fc87dfb` and is planned in `docs/superpowers/plans/2026-09-09-catalog-service.md`.

---

## Global Constraints

Copied from `CLAUDE.md` and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` from the repository root is the only gate. There is no separate lint step. Error Prone runs during compilation, so a finding at `ERROR` severity fails the build. The baseline before this plan starts is green: `BUILD SUCCESS`, five modules, `Tests run: 4` in `catalog-service` and `Tests run: 1` in `gateway`.
- **Module:** `catalog-service/`, port 8081, package root `com.thedarkhorse.catalog`, database `catalog_db` in the shared `postgres:18-alpine` container from the root `docker-compose.yaml`.
- **Annotations.** `@Component`, `@Service` and `@Repository` are forbidden. `@RestController` and `@RestControllerAdvice` are the only stereotypes and only in the controller layer. Every other bean — the mapper, `CategoryRepositoryImpl`, `CategoryServiceImpl` — is an `@Bean` method in an `@Configuration` class. `CategoryJpaRepository` is the one exception, because Spring generates its implementation. Do **not** write `@Mapper(componentModel = "spring")`: it makes MapStruct emit `@Component` on the generated class, which is the banned annotation arriving by the back door.
- **Queries.** No raw query text in Java. No `@Query`, no native SQL, no Criteria fragments. Only Spring Data derived query methods. The only SQL in this increment is `V1__create_category_table.sql`.
- **Layering.** `controller → service → repository → jpa`. The controller speaks `CategoryRequest` and `CategoryResponse`; the service speaks `Category`; the repository returns `Category`; the jpa layer owns `CategoryEntity`. `CategoryService`/`CategoryServiceImpl` and `CategoryRepository`/`CategoryRepositoryImpl` sit beside each other in the same package. There is no `impl` subpackage. `@Transactional` lives on `CategoryServiceImpl` and nowhere else. No caching annotation: the issue does not ask for one.
- **Types.** `Category` is a Lombok class (`@Data @NoArgsConstructor @AllArgsConstructor`). `CategoryRequest` and `CategoryResponse` are records, named for the entity and never for the operation — there is exactly **one** request record for `category`, used by both `POST` and `PUT`. No `CreateCategoryRequest`, no `UpdateCategoryRequest`, no `MoveCategoryRequest`, no `CategoryTreeResponse`. No value-object wrappers: no `Slug`, no `Path`, no `MaterialisedPath`. `UUID` appears only on `CategoryEntity`; every layer above it uses `String`.
- **Validation.** Constraints live on `CategoryRequest` only. `CategoryEntity` carries none. Constraints guard shape — blank, length, format, sign. Integrity stays in the database, so the duplicate slug is a 409 from `DataIntegrityViolationException` and never a field error.
- **Naming.** Lookups are prefixed `find` at every layer: `findByPath`, `findByPathStartingWithOrderByPathAsc`, `findById`, `findSubtree`. Mutations use verbs: `createCategory`, `updateCategory`, `deleteCategory`, `save`, `saveAll`, `deleteById`. Test methods are `givenContext_whenMethod_thenResult`, where the middle part is the method under test; the `find` prefix rule does not reach test names.
- **Ordering.** Private methods come last in every class, after every public, protected and package-private method.
- **TDD.** No class is written before a failing test for it has been seen to fail. Code with behaviour gets a test first: `CategoryRepositoryImpl`, `CategoryServiceImpl`, `CategoryController` and the two new `CatalogExceptionHandler` methods. Code with no behaviour gets none: `CategoryEntity`, `CategoryJpaRepository`, `Category`, `CategoryRequest`, `CategoryResponse`, `CategoryMapper`, the two exception classes, and every `@Bean` method in `CatalogConfiguration`. **Never test the framework** — no test asserting that `@Pattern` rejects Arabic, that `@Transactional` opens a transaction, or that Flyway runs. Acceptance criterion 3 is verified live against the running service instead, for exactly that reason. Unit tests only: no Spring context, no `@SpringBootTest`, no `@WebMvcTest`, no `MockMvc`, no Testcontainers. Static test data lives in named constants, never repeated inline.
- **Comments.** None. No Javadoc, no comment blocks, and none in `pom.xml`, `.sql`, `.yaml` or any properties file. Rationale lives in the spec and in this plan.
- **Scope.** Build only what issue #15 asks for. See "Deliberate omissions" for the eleven things the issue text and the surrounding spec will tempt you into.
- **Conventional Commits** with `catalog` as the scope, one commit per completed red-green-refactor cycle, every commit green, each carrying a `Refs #15` footer. No `Co-Authored-By` trailer, no generated-with footer.
- **Pull request** title ends `(#15)`; body ends `Closes #15`. Merge with rebase, never squash.

---

## Verified Findings

Every claim this plan depends on that could be settled by running something was run in this repository before the plan was written, against a real `postgres:18-alpine` container and the real dependency set resolved by `mvn -B -pl catalog-service dependency:build-classpath -Dmdep.includeScope=test`. Probes lived under `.scratch/` and were deleted afterwards.

**Two findings change the code you would otherwise write.** Finding 6 says a primitive `boolean active` on the entity silently overrides the column's `default true`, and finding 8 says `like 'keyboards%'` is the wrong subtree query and quietly returns a sibling that is not in the subtree. Read both before Task 4.

### Confirmed

1. **The baseline is green and `catalog-service` currently holds three classes and no migration.** `mvn -B clean verify` from the root: `BUILD SUCCESS`, `ecommerce`, `discovery-server`, `gateway`, `config-server`, `catalog-service` all `SUCCESS`, `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` in `CatalogExceptionHandlerTest`. `find catalog-service -type f` lists only `pom.xml`, `src/main/resources/application.yaml`, `CatalogServiceApplication.java`, `controller/ValidationError.java`, `controller/CatalogExceptionHandler.java` and `src/test/.../CatalogExceptionHandlerTest.java`. There is no `db/migration` directory, no `jpa/`, `model/`, `mapper/`, `repository/`, `service/`, `config/` or `exception/` package.

2. **`org.mapstruct:mapstruct` is not on `catalog-service`'s classpath.** The root POM manages the version (`mapstruct.version` 1.6.3) and puts `mapstruct-processor` on `annotationProcessorPaths`, but `catalog-service/pom.xml` declares no `mapstruct` dependency, and `mvn -B -pl catalog-service dependency:tree` shows none. The processor without the runtime artifact means `org.mapstruct.Mapper` does not resolve and the module will not compile. **Task 3 adds the dependency.** It carries no `<version>`; the root `dependencyManagement` supplies 1.6.3.

3. **MapStruct 1.6.3 converts `UUID` to `String` natively, so the spec's "there is no UUID mapper" holds.** `unzip -l mapstruct-processor-1.6.3.jar` lists `org/mapstruct/ap/internal/conversion/UUIDToStringConversion.class`. MapStruct's built-in conversions are bidirectional, so `String` to `UUID` comes from the same class.

4. **`@UuidGenerator` needs no `@GeneratedValue`, and `Style.VERSION_7` exists in Hibernate 7.4.1.** `javap` on `hibernate-core-7.4.1.Final.jar` shows the enum constants `AUTO, RANDOM, TIME, VERSION_6, VERSION_7`, and `javap -v` shows `UuidGenerator` is meta-annotated `org.hibernate.annotations.IdGeneratorType(value=class org.hibernate.id.uuid.UuidGenerator)`, which is what makes it a standalone id generator. A live persist produced `01a0997e-0f09-780f-b582-1f13b965d8f9`, whose `UUID.version()` printed `7`.

5. **`hbm2ddl.auto=validate` passes against the exact migration in Task 2, with `Instant` fields and with `created_by`/`updated_by` unmapped.** A real Hibernate 7.4.1 `SessionFactory` was booted against `postgres:18-alpine` with `hibernate.physical_naming_strategy=CamelCaseToUnderscoresNamingStrategy` — which is what Spring Boot installs by default — and printed `VALIDATE OK`. Three things this settles:
   - `java.time.Instant` maps to `timestamptz` under `PostgreSQLDialect`. No `@Column(columnDefinition = ...)` is needed and none is written.
   - Camel-case field names resolve to snake-case columns with no `@Column(name = ...)` anywhere. `parentId → parent_id`, `sortOrder → sort_order`, `createdAt → created_at`.
   - Columns present in the table but absent from the entity do **not** fail validation. `created_by` and `updated_by` exist in the migration and on no Java field, and validation still passed.

6. **A primitive `boolean` field defeats the column's `default true`, and this is why the service must set the defaults.** The same probe persisted an entity without touching `active`. Hibernate emitted `insert into category (active,created_at,parent_id,path,slug,sort_order,updated_at,id) values (?,?,?,?,?,?,?,?)` — `active` is in the column list — and `psql` then showed `active | f`. The `default true` in the DDL never fires, because Hibernate always sends a value. `sort_order` printed `0`, which happens to agree with the DDL and hides the same problem. **`CategoryServiceImpl` therefore applies both defaults explicitly** and Task 4 tests that it does.

7. **`@CreationTimestamp` survives an update, and `@UpdateTimestamp` advances on one.** The merge case is the one that could have forced a redesign, so it was measured directly: persist, then in a second transaction `merge` a *detached instance with `createdAt == null`*. Hibernate logged
   ```
   update category set active=?,parent_id=?,path=?,slug=?,sort_order=?,updated_at=? where id=?
   ```
   `created_at` is absent from the `set` list, because `@CreationTimestamp` marks the value insert-only. Reading the row back gave `createdAt=2026-09-13T06:42:41.218534Z updatedAt=2026-09-13T06:42:41.305482Z` — the original creation instant, and a later update instant. **`CategoryRepositoryImpl.save` can therefore be the one-liner `mapper.toModel(jpaRepository.save(mapper.toEntity(category)))`**, with the mapper ignoring both timestamps, and no `@MappingTarget` update method and no load-then-mutate branch is needed.

8. **`like 'keyboards%'` is the wrong subtree query.** Against the real table, with `keyboards`, `keyboards/accessories` and a legitimate root sibling `keyboards-2` all present:
   ```
   select path from category where path like 'keyboards%' order by path;
    keyboards
    keyboards-2
    keyboards/accessories

   select path from category where path = 'keyboards' or path like 'keyboards/%' order by path;
    keyboards
    keyboards/accessories
   ```
   `keyboards-2` is a valid root — its slug differs, so `unique nulls not distinct (parent_id, slug)` does not stop it — and the naive prefix picks it up. The spec writes `like 'keyboards/%'` for the same reason. **Every subtree read and every move cascade in this plan looks the node up by exact path or id, and matches descendants on `path + "/"` and never on `path`.** Task 5 and Task 7 each carry a test with a prefix-sharing sibling in it.

9. **The database enforces both integrity criteria on its own.** Against the migration in Task 2:
   ```
   insert ... values (..., null, 'keyboards', 'keyboards-2', ...);
   ERROR:  duplicate key value violates unique constraint "category_parent_id_slug_key"
   DETAIL:  Key (parent_id, slug)=(null, keyboards) already exists.

   delete from category where id = '...0001';
   ERROR:  update or delete on table "category" violates RESTRICT setting of foreign key
           constraint "category_parent_id_fkey" on table "category"
   ```
   `nulls not distinct` is what makes the first one fire for two roots, whose `parent_id` is null. PostgreSQL 18.6 accepts the syntax; it needs 15 or later, and the root `docker-compose.yaml` pins `postgres:18-alpine`.

10. **Every derived query name this plan uses parses.** `PartTree` from `spring-data-commons` was run directly against a `CategoryEntity` shape:
    ```
    findByPath                          -> path SIMPLE_PROPERTY (1): [Is, Equals] NEVER Order By
    findByPathStartingWithOrderByPathAsc-> path STARTING_WITH (1): [IsStartingWith, ...] Order By path: ASC
    ```
    No `@Query` is needed anywhere, which is what the query ban requires.

11. **`@GetMapping("/{*path}")` under `@RequestMapping("/api/v1/categories")` matches a multi-segment tail, and the captured value carries a leading slash.** `PathPatternParser` — the only matcher in Spring Framework 7 — parsing `/api/v1/categories/{*path}`:
    ```
    /api/v1/categories/keyboards/accessories -> matched=true  vars={path=/keyboards/accessories}
    /api/v1/categories/keyboards             ->               vars={path=/keyboards}
    /api/v1/categories                       -> matched=true  vars={path=}
    ```
    Two consequences, both of them behaviour and both tested in Task 8: **the controller must strip the leading `/`** before handing the value to the service, and `GET /api/v1/categories` matches the same mapping with an empty capture, which resolves to a path no row holds and therefore a 404. That is the honest outcome and no whole-forest listing is invented for it.

12. **The slug pattern behaves.** `[a-z0-9-]+` on a `@Pattern`, run through a real Hibernate Validator 9.1.0.Final `Validator` on a record with `@NotBlank @Size(max = 100)` alongside it:
    ```
    keyboards              -> []
    keyboards-accessories  -> []
    usb3-hubs              -> []
    Keyboards              -> [slug must match "[a-z0-9-]+"]
    لوحات                   -> [slug must match "[a-z0-9-]+"]
    keyboards/accessories  -> [slug must match "[a-z0-9-]+"]
    "keyboards\n"          -> [slug must match "[a-z0-9-]+"]
    keyboards.             -> [slug must match "[a-z0-9-]+"]
    a_b                    -> [slug must match "[a-z0-9-]+"]
    ""                     -> [slug must not be blank, slug must match "[a-z0-9-]+"]
    "   "                  -> [slug must not be blank, slug must match "[a-z0-9-]+"]
    101 * "a"              -> [slug size must be between 0 and 100]
    sortOrder = -1         -> [sortOrder must be greater than or equal to 0]
    ```
    Three things worth having measured rather than assumed. The trailing `-` inside the character class is a literal and not a malformed range, so the class is exactly lowercase Latin, digits and hyphen. `@Pattern` uses `Matcher.matches()`, so no `^`/`$` anchors are needed and a trailing newline is still rejected — with `^...$` and `find()` semantics it would not be. And `/` is rejected, which is what stops a slug from forging a second path segment.

13. **Mockito 5.23.0 works on Corretto 25.0.4.1.** Mocking an interface, stubbing and verifying all worked. It prints three warnings that are noise and not failure, and the implementer should not chase them:
    ```
    Mockito is currently self-attaching to enable the inline-mock-maker. ...
    WARNING: A Java agent has been loaded dynamically (byte-buddy-agent-1.18.10.jar)
    WARNING: Dynamic loading of agents will be disallowed by default in a future release
    ```
    Do **not** add a `-javaagent` argument to `maven-surefire-plugin` to silence them. Surefire is not configured anywhere in this repository today, and adding a plugin configuration to quiet a warning is a knob nobody requested.

14. **Flyway is wired and auto-configures itself, so no new configuration key is needed.** `spring-boot-starter-flyway:4.1.0` and `flyway-database-postgresql:12.4.0` (Flyway 12.4.0) are already dependencies of `catalog-service`, and `spring-boot-flyway-4.1.0.jar` registers `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration` in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The default location is `classpath:db/migration` and the default `spring.flyway.enabled` is `true`, so creating the file is the whole of the wiring.

15. **`HibernateJpaDialect.translateExceptionIfPossible(RuntimeException)` exists in `spring-orm-7.0.8`.** `javap` confirms the method. This is the hop that turns Hibernate's `ConstraintViolationException` into Spring's `DataIntegrityViolationException`, which is what #14's advice already maps to 409. It is the reason acceptance criterion 2 needs no Java in this increment — but it is a wiring claim rather than a measured one, so Task 9 measures the live 409 and Task 9 Step 5 names the one-line fallback if it does not appear.

### Where the issue is unverified, and one place it is short

**A. The issue's DDL omits the nullability of the audit columns, and there is no principal to put in them.** Spec section 4 requires `created_at`, `created_by`, `updated_at`, `updated_by` on every business table, and issue #15's `### Design` block lists all four. But `catalog-service/pom.xml` has no `spring-boot-starter-oauth2-resource-server`, no security is on the classpath, and nothing in issue #15 asks for authentication, so there is no Keycloak subject to record. This plan therefore writes `created_at` and `updated_at` as `not null`, generated by Hibernate and mapped on the entity, and `created_by` and `updated_by` as `varchar(64) null`, **present in the table and mapped on nothing**. Finding 5 confirms `ddl-auto: validate` accepts unmapped columns, so this costs nothing and leaves the columns waiting for the increment that brings a principal. The alternative — inventing an `AuditorAware` that returns a literal like `"system"` — writes a lie into an audit column and is not asked for.

**B. "Moving a category recomputes `path` for it and every descendant" does not say what happens when the new parent is inside the moved subtree.** Moving `keyboards` under `keyboards/accessories` is a cycle, and the recompute this plan implements would happily produce `keyboards/accessories/keyboards` and an unreachable subtree. No acceptance criterion covers it, no constraint in the issue's DDL stops it, and `CLAUDE.md` says to build only what the issue asks for. **It is left unbuilt and listed under "Deliberate omissions"**, and the pull request body says so, so the reviewer decides whether it becomes a follow-up issue rather than discovering it in production. This is the one place where following the scope rule leaves a real hole, and it is flagged rather than quietly patched.

**C. The issue says "read and write endpoints for the tree" without naming them.** The endpoints below are derived from the acceptance list and from the spec's own URL example, which is a read: `/catalog/api/v1/categories/keyboards/accessories`. Four endpoints cover all six criteria and nothing more:

| Method | Path | Body | Success | Criterion |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/categories` | `CategoryRequest` | 201 `CategoryResponse` | 1, 2, 3 |
| `GET` | `/api/v1/categories/{*path}` | — | 200 `List<CategoryResponse>` | 5 |
| `PUT` | `/api/v1/categories/{id}` | `CategoryRequest` | 200 `CategoryResponse` | 6 |
| `DELETE` | `/api/v1/categories/{id}` | — | 204 | 4 |

Reads address a category by its path, because that is what the spec's URL example does and what a subtree read needs. Writes address it by its id, because a `PUT` that moves a category changes the very path that would have addressed it. There is no ambiguity between `GET /{*path}` and the two `/{id}` mappings: they differ by HTTP method.

**D. The subtree response is a flat list, not a nested tree.** The criterion is "Fetching a subtree returns the node and every descendant", which a flat list satisfies exactly. Assembling a nested `children` structure is an algorithm, a second response record and a second set of tests that nothing in the issue asks for. The list is ordered by `path` ascending, which is a depth-first ordering of the subtree and is deterministic; `sort_order` is stored and returned but is deliberately not used to order the flat list, because sorting a mixed-depth list by a sibling-ordering column interleaves the levels and produces a sequence that is not a tree in any reading.

### Deliberate omissions

- **No cycle check on move.** Finding B. Not asked for; flagged in the pull request body.
- **No `translation` table.** Spec section 7 step 3 pairs `category` with `translation`, and #15's `### Scope` is "The `category` table, its migration, and read and write endpoints for the tree" — one table. `translation` is its own issue.
- **No `AuditorAware`, no `@EnableJpaAuditing`, no `@CreatedBy`/`@LastModifiedBy`, no `@EntityListeners`.** Finding A.
- **No `Slug`, `Path` or `MaterialisedPath` value object,** and no `PathBuilder` helper. The spec bans value-object wrappers outright, and path derivation is three lines inside `CategoryServiceImpl`.
- **No second request record.** One `CategoryRequest` serves `POST` and `PUT`. `parentId` being meaningful on both is what makes a move a `PUT` rather than a bespoke endpoint.
- **No `CategoryNotFoundException` message carrying SQL or a class name,** and no exception hierarchy. Two exceptions, each extending `RuntimeException` and nothing else, so neither gets a test.
- **No `@Query`, no `JpaSpecificationExecutor`, no `EntityGraph`, no `@ManyToOne` self-association.** `parent_id` is a plain `UUID` field on the entity. The foreign key lives in the migration where the spec puts it; an association would add lazy loading, cascade semantics and an N+1 risk that nothing here needs, and `findByParentId` would then silently resolve to a `parent.id` traversal.
- **No `spring.flyway.*`, `spring.jpa.*` or any other key in `.config-repo`.** Task 1 names every file and shows why each is already sufficient.
- **No springdoc, no `@Tag`, no `@Operation`, no `@ApiResponse`.** springdoc is not a dependency of `catalog-service` and #15 does not ask for it.
- **No pagination, no `active` filter on the read, no `GET` by id, no whole-forest listing.** Finding 11 explains what `GET /api/v1/categories` does instead: nothing special, a 404.
- **No `.gitkeep` files.** Six of the eight packages in the spec's layout arrive here because a class arrives in them. `config/`, `controller/`, `exception/`, `jpa/`, `mapper/`, `model/`, `repository/` and `service/` all end up populated by the end of this plan.

### Tooling constraint

The implementing job's allowlist (`.github/workflows/claude-advance.yml`, the `implement` job) is:

```
Edit,Write,Bash(mvn:*),Bash(git:*),Bash(gh:*),Bash(ls:*),Bash(cat:*),Bash(find:*),Bash(mkdir:*),
Bash(mv:*),Bash(cp:*),Bash(rm:*),Bash(cd:*),Bash(grep:*),Bash(java:*),Bash(javap:*),Bash(printf:*),
Bash(test:*),Bash(jar:*),Bash(unzip:*),Bash(head:*),Bash(tail:*),Bash(wc:*),Bash(sort:*),
Bash(diff:*),Bash(echo:*),Bash(docker:*)
```

No HTTP client is on it. `Bash(java:*)` is, and Java's single-file source launcher runs a `.java` file directly, so Task 9 Step 1 creates `.scratch/Probe.java` and every live HTTP check runs through it. `Bash(docker:*)` covers `docker compose` and `docker exec postgres psql`, which is how every database check is made.

Task 9 needs three servers up at once — `discovery-server` on 8761, `config-server` on 8888 and `catalog-service` on 8081 — plus the Postgres container, because `catalog-service/src/main/resources/application.yaml` carries `spring.config.import: "configserver:"` and will not start without the config server, which in turn reads this repository's `config` branch over HTTPS from GitHub. Start each as a **background** Bash task and stop it with the harness's background-task stop; `kill` and `pkill` are not on the allowlist. If outbound network is unavailable, say so and fall back to the `psql` checks in Task 2 rather than writing `server.port` or a datasource into the module to work around it.

Tasks 2 to 8 need none of that. Every one of them is `mvn -B clean verify` plus, in Task 2, one `docker` container and one `psql`.

---

## File Structure

Committed by this plan, all paths under `catalog-service/` unless stated:

| Path | Responsibility | Action |
| --- | --- | --- |
| `pom.xml` | Gains one dependency, `org.mapstruct:mapstruct`, with no `<version>`. Nothing else changes. | Modify |
| `src/main/resources/db/migration/V1__create_category_table.sql` | The `category` table, its self-referencing foreign key with `on delete restrict`, the unique `path`, and `unique nulls not distinct (parent_id, slug)`. The only SQL in the increment. | Create |
| `src/main/java/com/thedarkhorse/catalog/jpa/CategoryEntity.java` | The `UUID` id, the UUIDv7 generator, the eight mapped columns, the two Hibernate-generated timestamps. No constraint annotations, no behaviour, no test. | Create |
| `src/main/java/com/thedarkhorse/catalog/jpa/CategoryJpaRepository.java` | Two derived queries plus what `JpaRepository` gives. Spring generates the implementation, so no test. | Create |
| `src/main/java/com/thedarkhorse/catalog/model/Category.java` | The model every layer above jpa speaks. Lombok, ids as `String`. No behaviour, no test. | Create |
| `src/main/java/com/thedarkhorse/catalog/mapper/CategoryMapper.java` | Entity ↔ model, request → model, model → response. MapStruct-generated, so no test. | Create |
| `src/main/java/com/thedarkhorse/catalog/repository/CategoryRepository.java` | The plain contract the service depends on. Models in, models out, ids as `String`. | Create |
| `src/main/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImpl.java` | Delegates to `CategoryJpaRepository`, converts `String` ids to `UUID`, maps entities to models. Has behaviour, so it is tested. | Create |
| `src/main/java/com/thedarkhorse/catalog/service/CategoryService.java` | Four methods: `createCategory`, `findSubtree`, `updateCategory`, `deleteCategory`. | Create |
| `src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java` | Every rule in the issue. `@Transactional` lives here. The whole of the increment's behaviour. | Create |
| `src/main/java/com/thedarkhorse/catalog/controller/CategoryRequest.java` | One request record for the entity, carrying every constraint in the increment. | Create |
| `src/main/java/com/thedarkhorse/catalog/controller/CategoryResponse.java` | Read-only, so a record. Ids as `String`. | Create |
| `src/main/java/com/thedarkhorse/catalog/controller/CategoryController.java` | The four endpoints. Strips the leading `/` the capture-all pattern includes. | Create |
| `src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java` | Gains two `@ExceptionHandler` methods: 404 and 409. | Modify |
| `src/main/java/com/thedarkhorse/catalog/exception/CategoryNotFoundException.java` | Extends `RuntimeException` and nothing else, so no test. | Create |
| `src/main/java/com/thedarkhorse/catalog/exception/CategoryHasChildrenException.java` | Extends `RuntimeException` and nothing else, so no test. | Create |
| `src/main/java/com/thedarkhorse/catalog/config/CatalogConfiguration.java` | Three `@Bean` methods that call three constructors. No behaviour, no test. | Create |
| `src/test/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImplTest.java` | Delegation and mapping, against a mocked `CategoryJpaRepository`. | Create |
| `src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java` | Path derivation, defaults, subtree assembly, delete refusal, move cascade. The bulk of the increment's tests. | Create |
| `src/test/java/com/thedarkhorse/catalog/controller/CategoryControllerTest.java` | Leading-slash stripping and delegation. | Create |
| `src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java` | Gains two tests for the two new handler methods. | Modify |

Not touched: `.config-repo/` (Task 1 shows why), the root `pom.xml`, the root `docker-compose.yaml`, `lombok.config`, `.mvn/jvm.config`, `.github/`, `CLAUDE.md`, `README.md`, `discovery-server/`, `gateway/`, `config-server/`, the spec, and any other plan.

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/ValidateProbe.java` | Boots Hibernate against the real container and runs `hbm2ddl.auto=validate` against the committed entity. Task 2 Cycle A's red and green. |
| `.scratch/QueryProbe.java` | Runs Spring Data's own `PartTree` parser over the two derived query names against the committed entity. Task 2 Cycle B. |
| `.scratch/Probe.java` | The HTTP client, because the allowlist has none. Task 9. |
| `.scratch/` itself | Deleted in Task 9 Step 10, before the branch is pushed. |

`.scratch/` is **not** in `.gitignore`, so it shows as untracked for the whole of this plan. That is harmless because every commit below names its files explicitly and none of them uses `git add .` or `git add -A`. Do not add `.scratch/` to `.gitignore`: the instruction is to delete the directory, and a permanent ignore rule for a temporary directory is a change #15 did not ask for.

The two probes in Task 2 need the module's dependencies and its compiled classes on one classpath. The way that works here is:

```
mvn -B -pl catalog-service dependency:copy-dependencies -Dmdep.includeScope=test
java -cp "catalog-service/target/dependency/*:catalog-service/target/classes" .scratch/SomeProbe.java
```

`copy-dependencies` writes every test-scope jar into `catalog-service/target/dependency/`, which `target/` already gitignores and `mvn clean` already removes, and `java` expands the `dir/*` wildcard itself. **Do not use `dependency:build-classpath` with an `@argfile`** — `java` treats a whole `-cp` token beginning with `@` as one file name, so `-cp "@cp.txt:catalog-service/target/classes"` looks for a file called `cp.txt:catalog-service/target/classes` and fails. Any step that runs after a `mvn clean` has to re-run both commands.

---

## Task 1: Confirm the configuration repository needs no change

Covers no acceptance criterion directly. It is a task because spec section 3 `Configuration` decides which keys live in `.config-repo` rather than in a module, and an executor who skips this step will either invent a `spring.flyway.*` block or write a datasource into `catalog-service/src/main/resources/application.yaml`. The deliverable is a checked, recorded no-op.

**Files:**
- Read, and do not modify: `.config-repo/application.yaml`, `.config-repo/application-local.yaml`, `.config-repo/application-prod.yaml`, `.config-repo/catalog-service.yaml`, `.config-repo/catalog-service-local.yaml`, `.config-repo/catalog-service-prod.yaml`
- Read, and do not modify: `catalog-service/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: from #14, the six files above on the `config` branch that `config-server` serves, and the three-key module YAML.
- Produces: nothing. No file changes. Later tasks rely on `spring.jpa.hibernate.ddl-auto: validate` already being set for every service, which is what makes Task 2's live check meaningful.

- [ ] **Step 1: Read all seven files and check each key this increment could need**

Run: `cat .config-repo/application.yaml .config-repo/application-local.yaml .config-repo/application-prod.yaml .config-repo/catalog-service.yaml .config-repo/catalog-service-local.yaml .config-repo/catalog-service-prod.yaml catalog-service/src/main/resources/application.yaml`

Expected — every key this increment needs is already there, and each one is in the file spec section 3 puts it in:

| Key | File | Value | Why this increment needs it |
| --- | --- | --- | --- |
| `spring.jpa.hibernate.ddl-auto` | `.config-repo/application.yaml` | `validate` | Makes the entity and the migration disagree loudly at startup instead of silently. |
| `spring.jpa.show-sql` | `.config-repo/application.yaml` (`false`), `catalog-service-local.yaml` (`true`) | | The one key the spec keeps deliberately different between profiles. |
| `spring.datasource.url` | `.config-repo/catalog-service-local.yaml` | `jdbc:postgresql://localhost:5432/catalog_db` | Flyway and Hibernate both read it. Matches the root `docker-compose.yaml`, which sets `POSTGRES_DB: catalog_db` and `POSTGRES_USER: catalog`. |
| `spring.datasource.username` / `.password` | `.config-repo/catalog-service-local.yaml` | `catalog` / `catalog` | Same. |
| `server.port` | `.config-repo/catalog-service.yaml` | `8081` | Spec service table. |
| `eureka.client.service-url.defaultZone` | `.config-repo/application-local.yaml` | `http://localhost:8761/eureka/` | Registration, unchanged by this increment. |

- [ ] **Step 2: Confirm no Flyway key is required**

Run: `unzip -p /root/.m2/repository/org/springframework/boot/spring-boot-flyway/4.1.0/spring-boot-flyway-4.1.0.jar META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

If the local repository is elsewhere, run `mvn -B help:evaluate -Dexpression=settings.localRepository -q -DforceStdout` first and substitute.

Expected:

```
org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
org.springframework.boot.flyway.autoconfigure.FlywayEndpointAutoConfiguration
```

`spring.flyway.enabled` defaults to `true` and `spring.flyway.locations` defaults to `classpath:db/migration`. Creating `V1__create_category_table.sql` at that path is the entire wiring. **Do not add `spring.flyway.enabled: true` to `.config-repo/application.yaml`** — `CLAUDE.md` forbids setting a key to the value it already holds.

- [ ] **Step 3: Confirm the gateway route already exists**

Run: `grep -A6 "routes:" gateway/src/main/resources/application.yaml`

Expected:

```yaml
          routes:
            - id: catalog
              uri: lb://catalog-service
              predicates:
                - Path=/catalog/**
              filters:
                - StripPrefix=1
```

`/catalog/api/v1/categories/keyboards/accessories` reaches `catalog-service` as `/api/v1/categories/keyboards/accessories`. Spec section 3 keeps routes in the gateway module and out of `.config-repo`, so nothing moves.

- [ ] **Step 4: Record the outcome and commit nothing**

Run: `git -C .config-repo status --short`

Expected: empty output.

There is no commit for this task. Record in the pull request body that `.config-repo` was reviewed file by file and requires no change for #15, so a reviewer does not have to re-derive it.

---

## Task 2: The `category` table, the entity, and the JPA repository

Covers the `### Design` DDL, and puts the database half of acceptance criteria 2 and 4 in place.

The entity, the JPA repository interface and the migration have no branch, no loop and no arithmetic between them, so `CLAUDE.md` gives them no JUnit test. The red step is a command whose failure is observed instead: a probe that boots a real Hibernate `SessionFactory` against the real container with `hbm2ddl.auto=validate` and reports what is missing. Two cycles, two commits.

**Files:**
- Create: `catalog-service/src/main/resources/db/migration/V1__create_category_table.sql`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/jpa/CategoryEntity.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/jpa/CategoryJpaRepository.java`
- Create: `.scratch/ValidateProbe.java` (temporary, deleted in Task 9)

**Interfaces:**
- Consumes: from #14, `catalog-service/pom.xml` carrying `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `flyway-database-postgresql`, `org.postgresql:postgresql` and `org.projectlombok:lombok`; the root `docker-compose.yaml` `postgres` service publishing 5432 with `catalog_db`; `.config-repo` supplying `ddl-auto: validate` and the datasource.
- Produces: table `category` with columns `id, parent_id, slug, path, sort_order, active, created_at, created_by, updated_at, updated_by`; the class `com.thedarkhorse.catalog.jpa.CategoryEntity` with Lombok getters and setters for `UUID getId/setId`, `UUID getParentId/setParentId`, `String getSlug/setSlug`, `String getPath/setPath`, `int getSortOrder/setSortOrder`, `boolean isActive/setActive`, `Instant getCreatedAt/setCreatedAt`, `Instant getUpdatedAt/setUpdatedAt`; and the interface `com.thedarkhorse.catalog.jpa.CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID>` adding `Optional<CategoryEntity> findByPath(String path)` and `List<CategoryEntity> findByPathStartingWithOrderByPathAsc(String prefix)`. Task 3 consumes all three.

### Cycle A — the table exists and the entity validates against it

- [ ] **Step 1: Start Postgres and confirm `category` does not exist**

Run:

```
docker compose up -d postgres
docker exec postgres psql -U catalog -d catalog_db -c "\dt"
```

Expected: `Did not find any relations.`

- [ ] **Step 2: Write the failing check**

Create `.scratch/ValidateProbe.java`. It boots a real Hibernate 7.4.1 `SessionFactory` against the container using the module's own compiled `CategoryEntity`, with the same physical naming strategy Spring Boot installs, and runs `validate`.

```java
import java.util.Map;
import org.hibernate.cfg.Configuration;
import com.thedarkhorse.catalog.jpa.CategoryEntity;

public class ValidateProbe {

    public static void main(String[] args) {
        Map<String, String> settings = Map.of(
                "hibernate.connection.url", "jdbc:postgresql://localhost:5432/catalog_db",
                "hibernate.connection.username", "catalog",
                "hibernate.connection.password", "catalog",
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.show_sql", "true");
        Configuration configuration = new Configuration();
        settings.forEach(configuration::setProperty);
        configuration.addAnnotatedClass(CategoryEntity.class);
        try (var factory = configuration.buildSessionFactory()) {
            System.out.println("VALIDATE OK");
            java.util.UUID id = factory.fromTransaction(session -> {
                CategoryEntity entity = new CategoryEntity();
                entity.setSlug("keyboards");
                entity.setPath("keyboards");
                session.persist(entity);
                return entity.getId();
            });
            System.out.println("id = " + id + " version = " + id.version());
            factory.inSession(session -> {
                CategoryEntity found = session.find(CategoryEntity.class, id);
                System.out.println("createdAt = " + found.getCreatedAt()
                        + " updatedAt = " + found.getUpdatedAt()
                        + " sortOrder = " + found.getSortOrder()
                        + " active = " + found.isActive());
            });
        }
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run:

```
mvn -B -pl catalog-service dependency:copy-dependencies -Dmdep.includeScope=test
java -cp "catalog-service/target/dependency/*:catalog-service/target/classes" .scratch/ValidateProbe.java
```

Expected: compilation fails with

```
error: package com.thedarkhorse.catalog.jpa does not exist
```

- [ ] **Step 4: Write the entity**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/jpa/CategoryEntity.java`:

```java
package com.thedarkhorse.catalog.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "category")
@Getter
@Setter
public class CategoryEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID parentId;

    private String slug;

    private String path;

    private int sortOrder;

    private boolean active;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
```

No `@Column(name = ...)` anywhere: finding 5 measured that the default naming strategy resolves every one of these. No `@GeneratedValue`: finding 4. No validation annotations: the spec puts constraints on Request records only. No `created_by`/`updated_by` field: finding A.

- [ ] **Step 5: Compile and run the probe again to see the second failure**

Run:

```
mvn -B -q -pl catalog-service compile
java -cp "catalog-service/target/dependency/*:catalog-service/target/classes" .scratch/ValidateProbe.java
```

Expected: the module compiles, and the probe now fails at boot with

```
org.hibernate.tool.schema.spi.SchemaManagementException:
Schema-validation: missing table [category]
```

- [ ] **Step 6: Write the migration**

Create `catalog-service/src/main/resources/db/migration/V1__create_category_table.sql`:

```sql
create table category (
    id         uuid          primary key,
    parent_id  uuid          null references category (id) on delete restrict,
    slug       varchar(100)  not null,
    path       varchar(500)  not null unique,
    sort_order int           not null default 0,
    active     boolean       not null default true,
    created_at timestamptz   not null,
    created_by varchar(64)   null,
    updated_at timestamptz   not null,
    updated_by varchar(64)   null,
    unique nulls not distinct (parent_id, slug)
);

create index idx_category_parent_id on category (parent_id);
```

No comments, per `CLAUDE.md`. The index on `parent_id` is not decoration: PostgreSQL does not index the referencing side of a foreign key automatically, and `on delete restrict` makes the database check that side on every delete.

- [ ] **Step 7: Apply it and run the probe to see it pass**

Run:

```
docker cp catalog-service/src/main/resources/db/migration/V1__create_category_table.sql postgres:/tmp/V1.sql
docker exec postgres psql -U catalog -d catalog_db -v ON_ERROR_STOP=1 -f /tmp/V1.sql
java -cp "catalog-service/target/dependency/*:catalog-service/target/classes" .scratch/ValidateProbe.java
```

Expected from `psql`:

```
CREATE TABLE
CREATE INDEX
```

Expected from the probe:

```
VALIDATE OK
id = 01a0997e-...-7...-... version = 7
createdAt = 2026-...Z updatedAt = 2026-...Z sortOrder = 0 active = false
```

`version = 7` is criterion-free but confirms the spec's UUIDv7 rule. **`active = false` is finding 6 reproducing itself** — the DDL says `default true`, Hibernate sent `false` anyway. Task 4 is where that gets fixed, in the service, and it is fixed there rather than here because the DDL is the issue's and the default is not the entity's to hold.

- [ ] **Step 8: Confirm the constraints the acceptance criteria lean on**

Run:

```
docker exec postgres psql -U catalog -d catalog_db -c "insert into category (id, parent_id, slug, path, created_at, updated_at) values ('00000000-0000-0000-0000-000000000002', null, 'keyboards', 'keyboards-2', now(), now());"
```

Expected:

```
ERROR:  duplicate key value violates unique constraint "category_parent_id_slug_key"
DETAIL:  Key (parent_id, slug)=(null, keyboards) already exists.
```

That is acceptance criterion 2 at the database. Then:

```
docker exec postgres psql -U catalog -d catalog_db -c "insert into category (id, parent_id, slug, path, created_at, updated_at) select '00000000-0000-0000-0000-000000000003', id, 'accessories', 'keyboards/accessories', now(), now() from category where path = 'keyboards';"
docker exec postgres psql -U catalog -d catalog_db -c "delete from category where path = 'keyboards';"
```

Expected:

```
INSERT 0 1
ERROR:  update or delete on table "category" violates RESTRICT setting of foreign key constraint "category_parent_id_fkey" on table "category"
```

That is acceptance criterion 4 at the database.

- [ ] **Step 9: Reset the database so the committed migration is what creates the table**

Run:

```
docker compose down -v
docker compose up -d postgres
```

Step 7 applied the SQL by hand to get a green probe. Task 9 must see Flyway itself create the table from `classpath:db/migration`, and a table that already exists proves nothing.

- [ ] **Step 10: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 4` in `catalog-service` — unchanged, because nothing testable was added.

- [ ] **Step 11: Commit**

```bash
git add catalog-service/src/main/resources/db/migration/V1__create_category_table.sql \
        catalog-service/src/main/java/com/thedarkhorse/catalog/jpa/CategoryEntity.java
git commit -m "feat(catalog): add category table with materialised path

Refs #15"
```

### Cycle B — the two derived queries

- [ ] **Step 1: Write the failing check**

`CategoryJpaRepository` is a Spring Data interface with no implementation of ours, so it gets no JUnit test. What can fail is the derived-query parse, and it fails at context startup rather than at compile time, which is exactly the failure worth catching early. Create `.scratch/QueryProbe.java`, which runs `PartTree` — the parser Spring Data itself uses — against the committed entity:

```java
import com.thedarkhorse.catalog.jpa.CategoryEntity;
import org.springframework.data.repository.query.parser.PartTree;

public class QueryProbe {

    public static void main(String[] args) {
        String[] methods = {"findByPath", "findByPathStartingWithOrderByPathAsc"};
        for (String method : methods) {
            System.out.println(method + " -> " + new PartTree(method, CategoryEntity.class));
        }
    }
}
```

- [ ] **Step 2: Run it and record what it shows**

Cycle A Step 10 ran `mvn clean`, so both `target/dependency` and `target/classes` are gone. Run:

```
mvn -B -q -pl catalog-service compile
mvn -B -pl catalog-service dependency:copy-dependencies -Dmdep.includeScope=test
java -cp "catalog-service/target/dependency/*:catalog-service/target/classes" .scratch/QueryProbe.java
```

Expected: it passes already, because `PartTree` only needs the entity. **This is not a red step and must not be reported as one.** The interface below has no behaviour of ours in it, so `CLAUDE.md` gives it no test and there is no failing state to manufacture; the probe exists to prove the two names resolve against the real entity before they are committed, and Task 9 Step 3 proves the repository bean is actually created at startup. Record the output verbatim:

```
findByPath -> path SIMPLE_PROPERTY (1): [Is, Equals] NEVER Order By
findByPathStartingWithOrderByPathAsc -> path STARTING_WITH (1): [IsStartingWith, StartingWith, StartsWith] NEVER Order By path: ASC
```

- [ ] **Step 3: Write the interface**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/jpa/CategoryJpaRepository.java`:

```java
package com.thedarkhorse.catalog.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryJpaRepository extends JpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findByPath(String path);

    List<CategoryEntity> findByPathStartingWithOrderByPathAsc(String prefix);
}
```

No `@Repository`: the spec bans it, and Spring Data registers the interface without it.

- [ ] **Step 4: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 4` in `catalog-service`.

- [ ] **Step 5: Commit**

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/jpa/CategoryJpaRepository.java
git commit -m "feat(catalog): add category jpa repository with path lookups

Refs #15"
```

---

## Task 3: The model, the mapper, and the repository

Covers no acceptance criterion on its own. It is a task because the spec says "Repository implementation tests are in scope and assert that the JPA repository was called and that mapping happened", and a reviewer can accept the repository and reject the service above it.

**Files:**
- Modify: `catalog-service/pom.xml` — add `org.mapstruct:mapstruct` to `<dependencies>`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/model/Category.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/mapper/CategoryMapper.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/repository/CategoryRepository.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImpl.java`
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImplTest.java`

**Interfaces:**
- Consumes: `CategoryEntity` and `CategoryJpaRepository` from Task 2.
- Produces: `com.thedarkhorse.catalog.model.Category`, a Lombok class with `String getId/setId`, `String getParentId/setParentId`, `String getSlug/setSlug`, `String getPath/setPath`, `Integer getSortOrder/setSortOrder`, `Boolean getActive/setActive`, a no-argument constructor and a six-argument constructor in that field order. `com.thedarkhorse.catalog.mapper.CategoryMapper` with `Category toModel(CategoryEntity)`, `List<Category> toModels(List<CategoryEntity>)`, `CategoryEntity toEntity(Category)`, `Category toModel(CategoryRequest)` — added in Task 8 — and `CategoryResponse toResponse(Category)` and `List<CategoryResponse> toResponses(List<Category>)`, also Task 8. `com.thedarkhorse.catalog.repository.CategoryRepository` with `Optional<Category> findById(String id)`, `Optional<Category> findByPath(String path)`, `List<Category> findByPathStartingWith(String prefix)`, `Category save(Category category)`, `List<Category> saveAll(List<Category> categories)` and `void deleteById(String id)`. `CategoryRepositoryImpl` with the constructor `CategoryRepositoryImpl(CategoryJpaRepository jpaRepository, CategoryMapper mapper)`. Tasks 4 to 8 consume all of these.

- [ ] **Step 1: Write the failing test**

Create `catalog-service/src/test/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImplTest.java`:

```java
package com.thedarkhorse.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.jpa.CategoryEntity;
import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.model.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryRepositoryImplTest {

    private static final UUID ID = UUID.fromString("01920000-0000-7000-8000-000000000001");
    private static final UUID PARENT_ID = UUID.fromString("01920000-0000-7000-8000-000000000002");
    private static final String SLUG = "accessories";
    private static final String PATH = "keyboards/accessories";
    private static final String DESCENDANT_PREFIX = "keyboards/";
    private static final int SORT_ORDER = 3;

    private final CategoryJpaRepository jpaRepository = mock(CategoryJpaRepository.class);
    private final CategoryMapper mapper = new CategoryMapperImpl();
    private final CategoryRepositoryImpl repository = new CategoryRepositoryImpl(jpaRepository, mapper);

    @Test
    void givenAStoredEntity_whenFindByPath_thenTheModelCarriesTheIdsAsStrings() {
        when(jpaRepository.findByPath(PATH)).thenReturn(Optional.of(entity()));

        Optional<Category> found = repository.findByPath(PATH);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(ID.toString());
        assertThat(found.get().getParentId()).isEqualTo(PARENT_ID.toString());
        assertThat(found.get().getSlug()).isEqualTo(SLUG);
        assertThat(found.get().getPath()).isEqualTo(PATH);
        assertThat(found.get().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(found.get().getActive()).isTrue();
    }

    @Test
    void givenNoRow_whenFindByPath_thenEmpty() {
        when(jpaRepository.findByPath(PATH)).thenReturn(Optional.empty());

        assertThat(repository.findByPath(PATH)).isEmpty();
    }

    @Test
    void givenAStringId_whenFindById_thenTheJpaRepositoryIsCalledWithTheUuid() {
        when(jpaRepository.findById(ID)).thenReturn(Optional.of(entity()));

        assertThat(repository.findById(ID.toString())).isPresent();
        verify(jpaRepository).findById(ID);
    }

    @Test
    void givenAPrefix_whenFindByPathStartingWith_thenTheOrderedDerivedQueryIsUsed() {
        when(jpaRepository.findByPathStartingWithOrderByPathAsc(DESCENDANT_PREFIX))
                .thenReturn(List.of(entity()));

        List<Category> found = repository.findByPathStartingWith(DESCENDANT_PREFIX);

        assertThat(found).extracting(Category::getPath).containsExactly(PATH);
        verify(jpaRepository).findByPathStartingWithOrderByPathAsc(DESCENDANT_PREFIX);
    }

    @Test
    void givenAModelWithNoId_whenSave_thenTheEntityHasNoIdAndTheSavedModelIsReturned() {
        when(jpaRepository.save(any(CategoryEntity.class))).thenReturn(entity());

        Category saved = repository.save(model(null));

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getParentId()).isEqualTo(PARENT_ID);
        assertThat(captor.getValue().getPath()).isEqualTo(PATH);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(saved.getId()).isEqualTo(ID.toString());
    }

    @Test
    void givenAModelWithAnId_whenSave_thenTheEntityCarriesItAsAUuid() {
        when(jpaRepository.save(any(CategoryEntity.class))).thenReturn(entity());

        repository.save(model(ID.toString()));

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(ID);
    }

    @Test
    void givenModels_whenSaveAll_thenEveryOneIsSavedAndMappedBack() {
        when(jpaRepository.saveAll(any())).thenReturn(List.of(entity()));

        List<Category> saved = repository.saveAll(List.of(model(ID.toString())));

        assertThat(saved).extracting(Category::getId).containsExactly(ID.toString());
        verify(jpaRepository).saveAll(any());
    }

    @Test
    void givenAStringId_whenDeleteById_thenTheJpaRepositoryIsCalledWithTheUuid() {
        repository.deleteById(ID.toString());

        verify(jpaRepository).deleteById(ID);
    }

    private CategoryEntity entity() {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(ID);
        entity.setParentId(PARENT_ID);
        entity.setSlug(SLUG);
        entity.setPath(PATH);
        entity.setSortOrder(SORT_ORDER);
        entity.setActive(true);
        return entity;
    }

    private Category model(String id) {
        return new Category(id, PARENT_ID.toString(), SLUG, PATH, SORT_ORDER, true);
    }
}
```

The mapper is the real `CategoryMapperImpl`, not a mock, because "mapping happened" is one of the two things the spec asks this test to assert and a mocked mapper would assert nothing.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryRepositoryImplTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   class CategoryMapper
  location: package com.thedarkhorse.catalog.mapper
```

and the same for `CategoryMapperImpl`, `Category`, `CategoryRepositoryImpl`.

- [ ] **Step 3: Add the MapStruct dependency**

Finding 2: the processor is on the root `annotationProcessorPaths` but the runtime artifact is not a dependency of this module, so `org.mapstruct.Mapper` does not resolve. Add to `catalog-service/pom.xml`, immediately after the `org.projectlombok:lombok` entry:

```xml
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>
```

No `<version>`: the root `dependencyManagement` pins 1.6.3.

- [ ] **Step 4: Write the model**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/model/Category.java`:

```java
package com.thedarkhorse.catalog.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    private String id;
    private String parentId;
    private String slug;
    private String path;
    private Integer sortOrder;
    private Boolean active;
}
```

`Integer` and `Boolean` rather than `int` and `boolean`, because `CategoryRequest` may legitimately omit either and the service has to be able to tell "absent" from "zero" and from "false" to apply the DDL's defaults. Finding 6 is what makes that distinction load-bearing.

- [ ] **Step 5: Write the mapper**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/mapper/CategoryMapper.java`:

```java
package com.thedarkhorse.catalog.mapper;

import com.thedarkhorse.catalog.jpa.CategoryEntity;
import com.thedarkhorse.catalog.model.Category;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface CategoryMapper {

    Category toModel(CategoryEntity entity);

    List<Category> toModels(List<CategoryEntity> entities);

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    CategoryEntity toEntity(Category category);
}
```

Plain `@Mapper`, never `@Mapper(componentModel = "spring")`: the Spring component model makes MapStruct write `@Component` onto the generated class, which is the banned annotation. Task 8 exposes the mapper as an `@Bean`. `createdAt` and `updatedAt` are ignored because Hibernate owns them — finding 7 measured that the creation instant survives an update precisely because `@CreationTimestamp` keeps the column out of the `set` list, so the mapper leaving them null is correct rather than merely tolerated. The `UUID`/`String` conversions on `id` and `parentId` are built in; finding 3.

- [ ] **Step 6: Write the repository interface**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/repository/CategoryRepository.java`:

```java
package com.thedarkhorse.catalog.repository;

import com.thedarkhorse.catalog.model.Category;
import java.util.List;
import java.util.Optional;

public interface CategoryRepository {

    Optional<Category> findById(String id);

    Optional<Category> findByPath(String path);

    List<Category> findByPathStartingWith(String prefix);

    Category save(Category category);

    List<Category> saveAll(List<Category> categories);

    void deleteById(String id);
}
```

- [ ] **Step 7: Write the implementation**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImpl.java`:

```java
package com.thedarkhorse.catalog.repository;

import com.thedarkhorse.catalog.jpa.CategoryEntity;
import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.model.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;
    private final CategoryMapper mapper;

    public CategoryRepositoryImpl(CategoryJpaRepository jpaRepository, CategoryMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Category> findById(String id) {
        return jpaRepository.findById(UUID.fromString(id)).map(mapper::toModel);
    }

    @Override
    public Optional<Category> findByPath(String path) {
        return jpaRepository.findByPath(path).map(mapper::toModel);
    }

    @Override
    public List<Category> findByPathStartingWith(String prefix) {
        return mapper.toModels(jpaRepository.findByPathStartingWithOrderByPathAsc(prefix));
    }

    @Override
    public Category save(Category category) {
        return mapper.toModel(jpaRepository.save(mapper.toEntity(category)));
    }

    @Override
    public List<Category> saveAll(List<Category> categories) {
        return mapper.toModels(jpaRepository.saveAll(categories.stream().map(mapper::toEntity).toList()));
    }

    @Override
    public void deleteById(String id) {
        jpaRepository.deleteById(UUID.fromString(id));
    }
}
```

No `@Repository`, no `@Transactional`: the spec puts the first on the ban list and the second on the service.

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryRepositoryImplTest`

Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 9: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 12` in `catalog-service`.

- [ ] **Step 10: Commit**

```bash
git add catalog-service/pom.xml \
        catalog-service/src/main/java/com/thedarkhorse/catalog/model/Category.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/mapper/CategoryMapper.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/repository/CategoryRepository.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImpl.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/repository/CategoryRepositoryImplTest.java
git commit -m "feat(catalog): add category model, mapper and repository

Refs #15"
```

---

## Task 4: Creating a category derives its path from the parent

Covers acceptance criterion 1: creating a child under `keyboards` with slug `accessories` stores `path = 'keyboards/accessories'`. Also fixes finding 6 by making the service own the two DDL defaults.

**Files:**
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/exception/CategoryNotFoundException.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java`
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java`

**Interfaces:**
- Consumes: `CategoryRepository` and `Category` from Task 3.
- Produces: `com.thedarkhorse.catalog.exception.CategoryNotFoundException` with a single `CategoryNotFoundException(String message)` constructor; `com.thedarkhorse.catalog.service.CategoryService` with `Category createCategory(Category category)`, to which Tasks 5, 6 and 7 add `List<Category> findSubtree(String path)`, `void deleteCategory(String id)` and `Category updateCategory(String id, Category category)`; `CategoryServiceImpl` with the constructor `CategoryServiceImpl(CategoryRepository repository)`. Task 8 consumes the interface.

- [ ] **Step 1: Write the failing test**

Create `catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java`:

```java
package com.thedarkhorse.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryServiceImplTest {

    private static final String PARENT_ID = "01920000-0000-7000-8000-000000000001";
    private static final String CHILD_ID = "01920000-0000-7000-8000-000000000002";
    private static final String MISSING_ID = "01920000-0000-7000-8000-0000000000ff";
    private static final String PARENT_SLUG = "keyboards";
    private static final String CHILD_SLUG = "accessories";
    private static final String PARENT_PATH = "keyboards";
    private static final String CHILD_PATH = "keyboards/accessories";

    private final CategoryRepository repository = mock(CategoryRepository.class);
    private final CategoryServiceImpl service = new CategoryServiceImpl(repository);

    @Test
    void givenAParent_whenCreateCategory_thenThePathIsTheParentPathAndTheSlug() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, PARENT_ID, CHILD_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(CHILD_PATH);
    }

    @Test
    void givenNoParent_whenCreateCategory_thenThePathIsTheSlugAlone() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, null, PARENT_SLUG, null, 0, true));

        assertThat(created.getPath()).isEqualTo(PARENT_PATH);
    }

    @Test
    void givenAnUnknownParent_whenCreateCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCategory(new Category(null, MISSING_ID, CHILD_SLUG, null, 0, true)))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void givenNoSortOrderAndNoActive_whenCreateCategory_thenTheDdlDefaultsAreApplied() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category created = service.createCategory(new Category(null, null, PARENT_SLUG, null, null, null));

        assertThat(created.getSortOrder()).isZero();
        assertThat(created.getActive()).isTrue();
    }

    @Test
    void givenAnIdOnTheIncomingModel_whenCreateCategory_thenItIsNotCarriedIntoTheSavedCategory() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createCategory(new Category(CHILD_ID, null, PARENT_SLUG, null, 0, true));

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
    }

    private Category parent() {
        return new Category(PARENT_ID, null, PARENT_SLUG, PARENT_PATH, 0, true);
    }
}
```

The last test matters because `CategoryRequest` has no `id` field, so a client cannot send one — but `CategoryMapper.toModel(CategoryRequest)` in Task 8 produces a `Category` and nothing else stops a future caller from populating it. Clearing it in `createCategory` is what keeps `POST` from silently overwriting an existing row.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   class CategoryNotFoundException
  location: package com.thedarkhorse.catalog.exception
```

and the same for `CategoryServiceImpl`.

- [ ] **Step 3: Write the exception**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/exception/CategoryNotFoundException.java`:

```java
package com.thedarkhorse.catalog.exception;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(String message) {
        super(message);
    }
}
```

No test: `CLAUDE.md` exempts exceptions that only extend `RuntimeException`.

- [ ] **Step 4: Write the service interface and implementation**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java`:

```java
package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.model.Category;

public interface CategoryService {

    Category createCategory(Category category);
}
```

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java`:

```java
package com.thedarkhorse.catalog.service;

import com.thedarkhorse.catalog.exception.CategoryNotFoundException;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import org.springframework.transaction.annotation.Transactional;

public class CategoryServiceImpl implements CategoryService {

    private static final String SEPARATOR = "/";
    private static final String NOT_FOUND = "No category with id ";
    private static final int DEFAULT_SORT_ORDER = 0;

    private final CategoryRepository repository;

    public CategoryServiceImpl(CategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Category createCategory(Category category) {
        category.setId(null);
        category.setPath(findPathUnder(category.getParentId(), category.getSlug()));
        category.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        category.setActive(category.getActive() == null || category.getActive());
        return repository.save(category);
    }

    private String findPathUnder(String parentId, String slug) {
        if (parentId == null) {
            return slug;
        }
        return findParent(parentId).getPath() + SEPARATOR + slug;
    }

    private Category findParent(String parentId) {
        return repository.findById(parentId).orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND + parentId));
    }
}
```

Private methods come last, per the ordering rule. `@Transactional` is `org.springframework.transaction.annotation.Transactional` and lives here and nowhere else.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 17` in `catalog-service`.

- [ ] **Step 7: Commit**

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/exception/CategoryNotFoundException.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java
git commit -m "feat(catalog): derive category path from the parent on create

Refs #15"
```

---

## Task 5: Fetching a subtree returns the node and every descendant

Covers acceptance criterion 5, and is where finding 8 is paid for.

**Files:**
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java` — add `findSubtree`
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java` — implement it
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java` — three tests

**Interfaces:**
- Consumes: everything Task 4 produced.
- Produces: `List<Category> findSubtree(String path)` on `CategoryService`, returning the node first and its descendants after it in `path` order, and throwing `CategoryNotFoundException` when no row holds that exact path. Task 8 consumes it.

- [ ] **Step 1: Write the failing test**

Add to `CategoryServiceImplTest`. Add these constants beside the existing ones:

```java
    private static final String GRANDCHILD_PATH = "keyboards/accessories/cables";
    private static final String SIBLING_PATH = "keyboards-2";
    private static final String DESCENDANT_PREFIX = "keyboards/";
    private static final String MISSING_PATH = "mice";
```

and these tests:

```java
    @Test
    void givenANodeWithDescendants_whenFindSubtree_thenTheNodeComesFirstAndEveryDescendantFollows() {
        when(repository.findByPath(PARENT_PATH)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX))
                .thenReturn(List.of(at(CHILD_PATH), at(GRANDCHILD_PATH)));

        List<Category> subtree = service.findSubtree(PARENT_PATH);

        assertThat(subtree).extracting(Category::getPath)
                .containsExactly(PARENT_PATH, CHILD_PATH, GRANDCHILD_PATH);
    }

    @Test
    void givenALeaf_whenFindSubtree_thenOnlyTheNodeIsReturned() {
        when(repository.findByPath(GRANDCHILD_PATH)).thenReturn(Optional.of(at(GRANDCHILD_PATH)));
        when(repository.findByPathStartingWith(GRANDCHILD_PATH + "/")).thenReturn(List.of());

        assertThat(service.findSubtree(GRANDCHILD_PATH)).extracting(Category::getPath)
                .containsExactly(GRANDCHILD_PATH);
    }

    @Test
    void givenASiblingSharingThePrefix_whenFindSubtree_thenTheSiblingIsNotADescendant() {
        when(repository.findByPath(PARENT_PATH)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));

        List<Category> subtree = service.findSubtree(PARENT_PATH);

        assertThat(subtree).extracting(Category::getPath).doesNotContain(SIBLING_PATH);
        verify(repository).findByPathStartingWith(DESCENDANT_PREFIX);
        verify(repository, never()).findByPathStartingWith(PARENT_PATH);
    }

    @Test
    void givenAnUnknownPath_whenFindSubtree_thenCategoryNotFound() {
        when(repository.findByPath(MISSING_PATH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSubtree(MISSING_PATH))
                .isInstanceOf(CategoryNotFoundException.class);
    }
```

and this helper, beside `parent()` and after every `@Test` method:

```java
    private Category at(String path) {
        return new Category(CHILD_ID, PARENT_ID, CHILD_SLUG, path, 0, true);
    }
```

Add the imports `java.util.List` and `static org.mockito.Mockito.never`.

The third test is finding 8 written as an assertion. `verify(repository, never()).findByPathStartingWith(PARENT_PATH)` is the part that catches the bug, because a `findByPathStartingWith("keyboards")` implementation still passes the first two tests against a mock that was never told about `keyboards-2`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   method findSubtree(java.lang.String)
```

- [ ] **Step 3: Add the method to the interface and implement it**

In `CategoryService`, add above `createCategory` — lookups first is not a rule, but `find` before the verbs reads better and the ordering rule only governs private methods:

```java
    List<Category> findSubtree(String path);
```

with the import `java.util.List`.

In `CategoryServiceImpl`, add the public method after `createCategory` and before the private block:

```java
    @Override
    @Transactional(readOnly = true)
    public List<Category> findSubtree(String path) {
        Category node = repository.findByPath(path)
                .orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND_PATH + path));
        List<Category> subtree = new ArrayList<>();
        subtree.add(node);
        subtree.addAll(repository.findByPathStartingWith(path + SEPARATOR));
        return subtree;
    }
```

Add the constant beside the others:

```java
    private static final String NOT_FOUND_PATH = "No category at path ";
```

and the imports `java.util.ArrayList` and `java.util.List`.

`path + SEPARATOR` and never `path` — finding 8.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 21` in `catalog-service`.

- [ ] **Step 6: Commit**

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java
git commit -m "feat(catalog): read a category subtree by materialised path

Refs #15"
```

---

## Task 6: Deleting a category that has children is rejected

Covers acceptance criterion 4 above the database. Finding 9 already showed `on delete restrict` refusing the delete in Postgres; that backstop stays and is what makes the rule true even for a writer that bypasses this service. What this task adds is a rejection the API can state before the statement runs, which is the only form of it a unit test can reach given that the spec forbids a database in tests.

**Files:**
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/exception/CategoryHasChildrenException.java`
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java` — add `deleteCategory`
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java` — implement it
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java` — two new handlers
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java` — three tests
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java` — two tests

**Interfaces:**
- Consumes: everything Tasks 4 and 5 produced, plus `CatalogExceptionHandler` from #14.
- Produces: `com.thedarkhorse.catalog.exception.CategoryHasChildrenException` with a single `CategoryHasChildrenException(String message)` constructor; `void deleteCategory(String id)` on `CategoryService`; and two methods on `CatalogExceptionHandler`, `ProblemDetail handleCategoryNotFound(CategoryNotFoundException)` returning 404 and `ProblemDetail handleCategoryHasChildren(CategoryHasChildrenException)` returning 409. Task 8 consumes `deleteCategory`.

### Cycle A — the service refuses

- [ ] **Step 1: Write the failing test**

Add to `CategoryServiceImplTest`:

```java
    @Test
    void givenACategoryWithDescendants_whenDeleteCategory_thenCategoryHasChildren() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));

        assertThatThrownBy(() -> service.deleteCategory(PARENT_ID))
                .isInstanceOf(CategoryHasChildrenException.class);
        verify(repository, never()).deleteById(PARENT_ID);
    }

    @Test
    void givenALeaf_whenDeleteCategory_thenItIsDeleted() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of());

        service.deleteCategory(PARENT_ID);

        verify(repository).deleteById(PARENT_ID);
    }

    @Test
    void givenAnUnknownId_whenDeleteCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCategory(MISSING_ID))
                .isInstanceOf(CategoryNotFoundException.class);
    }
```

with the import `com.thedarkhorse.catalog.exception.CategoryHasChildrenException`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   class CategoryHasChildrenException
  location: package com.thedarkhorse.catalog.exception
```

- [ ] **Step 3: Write the exception and the method**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/exception/CategoryHasChildrenException.java`:

```java
package com.thedarkhorse.catalog.exception;

public class CategoryHasChildrenException extends RuntimeException {

    public CategoryHasChildrenException(String message) {
        super(message);
    }
}
```

Add to `CategoryService`:

```java
    void deleteCategory(String id);
```

Add to `CategoryServiceImpl`, after `findSubtree` and before the private block:

```java
    @Override
    @Transactional
    public void deleteCategory(String id) {
        Category category = findCategory(id);
        if (!repository.findByPathStartingWith(category.getPath() + SEPARATOR).isEmpty()) {
            throw new CategoryHasChildrenException(HAS_CHILDREN + category.getPath());
        }
        repository.deleteById(id);
    }
```

Add the constant:

```java
    private static final String HAS_CHILDREN = "Category has descendants at path ";
```

and rename the existing private `findParent` to `findCategory`, since it is now used for both the parent lookup and the delete lookup:

```java
    private Category findCategory(String id) {
        return repository.findById(id).orElseThrow(() -> new CategoryNotFoundException(NOT_FOUND + id));
    }
```

updating the one call inside `findPathUnder`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run the gate and commit**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 24` in `catalog-service`.

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/exception/CategoryHasChildrenException.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java
git commit -m "feat(catalog): refuse to delete a category with descendants

Refs #15"
```

### Cycle B — the two refusals become statuses

- [ ] **Step 1: Write the failing test**

Add to `CatalogExceptionHandlerTest`, beside the existing constants:

```java
    private static final String NOT_FOUND_DETAIL = "The requested resource does not exist";
    private static final String MISSING_MESSAGE = "No category at path mice";
    private static final String HAS_CHILDREN_MESSAGE = "Category has descendants at path keyboards";
```

and these tests:

```java
    @Test
    void givenAMissingCategory_whenHandleCategoryNotFound_thenNotFoundHidesTheLookup() {
        ProblemDetail body = handler.handleCategoryNotFound(new CategoryNotFoundException(MISSING_MESSAGE));

        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getDetail()).isEqualTo(NOT_FOUND_DETAIL);
        assertThat(body.getDetail()).doesNotContain(MISSING_MESSAGE);
    }

    @Test
    void givenACategoryWithChildren_whenHandleCategoryHasChildren_thenConflict() {
        ProblemDetail body =
                handler.handleCategoryHasChildren(new CategoryHasChildrenException(HAS_CHILDREN_MESSAGE));

        assertThat(body.getStatus()).isEqualTo(409);
        assertThat(body.getDetail()).isEqualTo(CONFLICT_DETAIL);
    }
```

with the imports `com.thedarkhorse.catalog.exception.CategoryHasChildrenException` and `com.thedarkhorse.catalog.exception.CategoryNotFoundException`.

The detail strings are fixed rather than the exception's message, for the same reason #14 fixed the 409 and 500 details: acceptance criterion 7 of #14 says no response body carries internals, and a path a caller has no right to see is an internal.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CatalogExceptionHandlerTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   method handleCategoryNotFound(com.thedarkhorse.catalog.exception.CategoryNotFoundException)
```

- [ ] **Step 3: Add the two handlers**

In `CatalogExceptionHandler`, add the constant beside `CONFLICT_DETAIL`:

```java
    private static final String NOT_FOUND_DETAIL = "The requested resource does not exist";
```

and the two methods after `handleDataIntegrityViolation` and before `handleUnexpected`:

```java
    @ExceptionHandler(CategoryNotFoundException.class)
    public ProblemDetail handleCategoryNotFound(CategoryNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, NOT_FOUND_DETAIL);
    }

    @ExceptionHandler(CategoryHasChildrenException.class)
    public ProblemDetail handleCategoryHasChildren(CategoryHasChildrenException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, CONFLICT_DETAIL);
    }
```

with the two imports. Neither logs: `handleDataIntegrityViolation` logs because a constraint firing is a surprise, and these two are the API stating a rule, which is not.

`@ExceptionHandler(Exception.class)` is already on this class and does not swallow these. Spring's `ExceptionHandlerMethodResolver` picks the closest match in the exception's own hierarchy, so a `CategoryNotFoundException` reaches the specific handler.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CatalogExceptionHandlerTest`

Expected: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run the gate and commit**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 26` in `catalog-service`.

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandler.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CatalogExceptionHandlerTest.java
git commit -m "feat(catalog): map category not found to 404 and children to 409

Refs #15"
```

---

## Task 7: Moving a category recomputes its path and every descendant's

Covers acceptance criterion 6. The same method also covers a rename, because in both cases the derived path changes and the cascade is identical.

**Files:**
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java` — add `updateCategory`
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java` — implement it
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java` — five tests

**Interfaces:**
- Consumes: everything Tasks 4 to 6 produced.
- Produces: `Category updateCategory(String id, Category category)` on `CategoryService`, returning the saved node with its recomputed path. Task 8 consumes it.

- [ ] **Step 1: Write the failing test**

Add to `CategoryServiceImplTest`, beside the existing constants:

```java
    private static final String NEW_PARENT_ID = "01920000-0000-7000-8000-000000000003";
    private static final String NEW_PARENT_PATH = "peripherals";
    private static final String MOVED_PATH = "peripherals/keyboards";
    private static final String MOVED_CHILD_PATH = "peripherals/keyboards/accessories";
    private static final String MOVED_GRANDCHILD_PATH = "peripherals/keyboards/accessories/cables";
    private static final String RENAMED_SLUG = "boards";
    private static final String RENAMED_PATH = "boards";
    private static final String RENAMED_CHILD_PATH = "boards/accessories";
```

and these tests:

```java
    @Test
    void givenANewParent_whenUpdateCategory_thenTheMovedNodePathIsRecomputed() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findById(NEW_PARENT_ID))
                .thenReturn(Optional.of(new Category(NEW_PARENT_ID, null, NEW_PARENT_PATH, NEW_PARENT_PATH, 0, true)));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category moved = service.updateCategory(
                PARENT_ID, new Category(null, NEW_PARENT_ID, PARENT_SLUG, null, 0, true));

        assertThat(moved.getPath()).isEqualTo(MOVED_PATH);
        assertThat(moved.getId()).isEqualTo(PARENT_ID);
        assertThat(moved.getParentId()).isEqualTo(NEW_PARENT_ID);
    }

    @Test
    void givenANewParent_whenUpdateCategory_thenEveryDescendantPathIsRecomputed() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findById(NEW_PARENT_ID))
                .thenReturn(Optional.of(new Category(NEW_PARENT_ID, null, NEW_PARENT_PATH, NEW_PARENT_PATH, 0, true)));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX))
                .thenReturn(List.of(at(CHILD_PATH), at(GRANDCHILD_PATH)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCategory(PARENT_ID, new Category(null, NEW_PARENT_ID, PARENT_SLUG, null, 0, true));

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.captor();
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::getPath)
                .containsExactly(MOVED_CHILD_PATH, MOVED_GRANDCHILD_PATH);
    }

    @Test
    void givenASiblingSharingThePrefix_whenUpdateCategory_thenOnlyRealDescendantsAreRead() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findById(NEW_PARENT_ID))
                .thenReturn(Optional.of(new Category(NEW_PARENT_ID, null, NEW_PARENT_PATH, NEW_PARENT_PATH, 0, true)));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCategory(PARENT_ID, new Category(null, NEW_PARENT_ID, PARENT_SLUG, null, 0, true));

        verify(repository).findByPathStartingWith(DESCENDANT_PREFIX);
        verify(repository, never()).findByPathStartingWith(PARENT_PATH);
    }

    @Test
    void givenANewSlug_whenUpdateCategory_thenTheNodeAndItsDescendantsAreRenamed() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.findByPathStartingWith(DESCENDANT_PREFIX)).thenReturn(List.of(at(CHILD_PATH)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Category renamed = service.updateCategory(
                PARENT_ID, new Category(null, null, RENAMED_SLUG, null, 0, true));

        assertThat(renamed.getPath()).isEqualTo(RENAMED_PATH);
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.captor();
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Category::getPath).containsExactly(RENAMED_CHILD_PATH);
    }

    @Test
    void givenAnUnchangedPath_whenUpdateCategory_thenNoDescendantIsSaved() {
        when(repository.findById(PARENT_ID)).thenReturn(Optional.of(parent()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCategory(PARENT_ID, new Category(null, null, PARENT_SLUG, null, 0, true));

        verify(repository, never()).saveAll(any());
    }

    @Test
    void givenAnUnknownId_whenUpdateCategory_thenCategoryNotFound() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCategory(
                        MISSING_ID, new Category(null, null, PARENT_SLUG, null, 0, true)))
                .isInstanceOf(CategoryNotFoundException.class);
    }
```

`ArgumentCaptor.captor()` is the generic-friendly factory added in Mockito 5.7; `forClass(List.class)` would need an unchecked cast, and Error Prone will not be pleased with one.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   method updateCategory(java.lang.String,com.thedarkhorse.catalog.model.Category)
```

- [ ] **Step 3: Implement it**

Add to `CategoryService`:

```java
    Category updateCategory(String id, Category category);
```

Add to `CategoryServiceImpl`, after `findSubtree` and before `deleteCategory`:

```java
    @Override
    @Transactional
    public Category updateCategory(String id, Category category) {
        Category existing = findCategory(id);
        String oldPath = existing.getPath();
        String newPath = findPathUnder(category.getParentId(), category.getSlug());
        if (!newPath.equals(oldPath)) {
            moveDescendants(oldPath, newPath);
        }
        existing.setParentId(category.getParentId());
        existing.setSlug(category.getSlug());
        existing.setPath(newPath);
        existing.setSortOrder(category.getSortOrder() == null ? DEFAULT_SORT_ORDER : category.getSortOrder());
        existing.setActive(category.getActive() == null || category.getActive());
        return repository.save(existing);
    }
```

and the private method, in the private block:

```java
    private void moveDescendants(String oldPath, String newPath) {
        List<Category> descendants = repository.findByPathStartingWith(oldPath + SEPARATOR);
        if (descendants.isEmpty()) {
            return;
        }
        descendants.forEach(descendant ->
                descendant.setPath(newPath + descendant.getPath().substring(oldPath.length())));
        repository.saveAll(descendants);
    }
```

`oldPath + SEPARATOR` and never `oldPath` — finding 8, again. `substring(oldPath.length())` keeps the leading `/` of the remainder, so `newPath + "/accessories"` is what gets written; that is why `newPath` carries no trailing separator.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryServiceImplTest`

Expected: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 32` in `catalog-service`.

- [ ] **Step 6: Commit**

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryService.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/service/CategoryServiceImpl.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/service/CategoryServiceImplTest.java
git commit -m "feat(catalog): recompute descendant paths when a category moves

Refs #15"
```

---

## Task 8: The endpoints

Covers acceptance criterion 3 by putting the constraints on `CategoryRequest`, and puts every other criterion behind HTTP. It also finishes the wiring, so that Task 9 has something to start.

**Files:**
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryRequest.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryResponse.java`
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryController.java`
- Modify: `catalog-service/src/main/java/com/thedarkhorse/catalog/mapper/CategoryMapper.java` — three boundary methods
- Create: `catalog-service/src/main/java/com/thedarkhorse/catalog/config/CatalogConfiguration.java`
- Test: `catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CategoryControllerTest.java`

**Interfaces:**
- Consumes: `CategoryService` and its four methods from Tasks 4 to 7; `CategoryMapper` and `CategoryRepositoryImpl` from Task 3; `CategoryJpaRepository` from Task 2.
- Produces: the four endpoints in finding C's table; `CategoryRequest(String parentId, String slug, Integer sortOrder, Boolean active)`; `CategoryResponse(String id, String parentId, String slug, String path, Integer sortOrder, Boolean active)`; `CategoryController(CategoryService service, CategoryMapper mapper)`; and the three beans `CategoryMapper categoryMapper()`, `CategoryRepository categoryRepository(CategoryJpaRepository, CategoryMapper)` and `CategoryService categoryService(CategoryRepository)`. Task 9 consumes the running application.

- [ ] **Step 1: Write the failing test**

Create `catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CategoryControllerTest.java`:

```java
package com.thedarkhorse.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.model.Category;
import com.thedarkhorse.catalog.service.CategoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CategoryControllerTest {

    private static final String ID = "01920000-0000-7000-8000-000000000001";
    private static final String PARENT_ID = "01920000-0000-7000-8000-000000000002";
    private static final String SLUG = "accessories";
    private static final String PATH = "keyboards/accessories";
    private static final String CAPTURED_PATH = "/keyboards/accessories";
    private static final String ROOT_PATH = "keyboards";
    private static final int SORT_ORDER = 3;

    private final CategoryService service = mock(CategoryService.class);
    private final CategoryMapper mapper = new CategoryMapperImpl();
    private final CategoryController controller = new CategoryController(service, mapper);

    @Test
    void givenACapturedPathWithALeadingSlash_whenFindSubtree_thenTheServiceIsCalledWithoutIt() {
        when(service.findSubtree(PATH)).thenReturn(List.of(model()));

        List<CategoryResponse> responses = controller.findSubtree(CAPTURED_PATH);

        assertThat(responses).extracting(CategoryResponse::path).containsExactly(PATH);
        verify(service).findSubtree(PATH);
    }

    @Test
    void givenASingleSegment_whenFindSubtree_thenTheServiceIsCalledWithoutTheSlash() {
        when(service.findSubtree(ROOT_PATH)).thenReturn(List.of(model()));

        controller.findSubtree("/" + ROOT_PATH);

        verify(service).findSubtree(ROOT_PATH);
    }

    @Test
    void givenARequest_whenCreateCategory_thenTheResponseCarriesTheStoredPath() {
        when(service.createCategory(any())).thenReturn(model());

        CategoryResponse response =
                controller.createCategory(new CategoryRequest(PARENT_ID, SLUG, SORT_ORDER, true));

        assertThat(response.id()).isEqualTo(ID);
        assertThat(response.path()).isEqualTo(PATH);
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(service).createCategory(captor.capture());
        assertThat(captor.getValue().getParentId()).isEqualTo(PARENT_ID);
        assertThat(captor.getValue().getSlug()).isEqualTo(SLUG);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(SORT_ORDER);
        assertThat(captor.getValue().getActive()).isTrue();
    }

    @Test
    void givenARequest_whenUpdateCategory_thenTheServiceReceivesTheIdAndTheModel() {
        when(service.updateCategory(eq(ID), any())).thenReturn(model());

        CategoryResponse response =
                controller.updateCategory(ID, new CategoryRequest(PARENT_ID, SLUG, SORT_ORDER, true));

        assertThat(response.path()).isEqualTo(PATH);
        verify(service).updateCategory(eq(ID), any());
    }

    @Test
    void givenAnId_whenDeleteCategory_thenTheServiceReceivesIt() {
        controller.deleteCategory(ID);

        verify(service).deleteCategory(ID);
    }

    private Category model() {
        return new Category(ID, PARENT_ID, SLUG, PATH, SORT_ORDER, true);
    }
}
```

There is no test asserting that `@Pattern` rejects Arabic. That would be testing Hibernate Validator, which the spec forbids outright; acceptance criterion 3 is measured live in Task 9 instead, where what is being tested is our contract and not the framework's.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryControllerTest`

Expected: `BUILD FAILURE` at `testCompile` with

```
cannot find symbol
  symbol:   class CategoryController
  location: package com.thedarkhorse.catalog.controller
```

- [ ] **Step 3: Write the two records**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryRequest.java`:

```java
package com.thedarkhorse.catalog.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        String parentId,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9-]+") String slug,
        @PositiveOrZero Integer sortOrder,
        Boolean active) {}
```

Exactly the three constraints the issue names, with the regex finding 12 measured. No anchors: `@Pattern` uses `Matcher.matches()`, so `^` and `$` add nothing and a trailing newline is rejected either way. No `@NotNull` on `sortOrder` or `active`: both are optional and the service applies the DDL's defaults. No constraint on `parentId`: a malformed one is not a shape error the issue asks about, and there is exactly one request record for this entity.

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryResponse.java`:

```java
package com.thedarkhorse.catalog.controller;

public record CategoryResponse(
        String id, String parentId, String slug, String path, Integer sortOrder, Boolean active) {}
```

- [ ] **Step 4: Add the three boundary methods to the mapper**

In `CategoryMapper`, add:

```java
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "path", ignore = true)
    Category toModel(CategoryRequest request);

    CategoryResponse toResponse(Category category);

    List<CategoryResponse> toResponses(List<Category> categories);
```

with the imports `com.thedarkhorse.catalog.controller.CategoryRequest` and `com.thedarkhorse.catalog.controller.CategoryResponse`. `id` and `path` are ignored because `CategoryRequest` has neither and MapStruct would otherwise warn on every build; the service is what fills both.

- [ ] **Step 5: Write the controller**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryController.java`:

```java
package com.thedarkhorse.catalog.controller;

import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.service.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private static final String SEPARATOR = "/";

    private final CategoryService service;
    private final CategoryMapper mapper;

    public CategoryController(CategoryService service, CategoryMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/{*path}")
    public List<CategoryResponse> findSubtree(@PathVariable String path) {
        return mapper.toResponses(service.findSubtree(withoutLeadingSeparator(path)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest request) {
        return mapper.toResponse(service.createCategory(mapper.toModel(request)));
    }

    @PutMapping("/{id}")
    public CategoryResponse updateCategory(@PathVariable String id, @Valid @RequestBody CategoryRequest request) {
        return mapper.toResponse(service.updateCategory(id, mapper.toModel(request)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable String id) {
        service.deleteCategory(id);
    }

    private String withoutLeadingSeparator(String path) {
        return path.startsWith(SEPARATOR) ? path.substring(SEPARATOR.length()) : path;
    }
}
```

`{*path}` is a capture-all and its value arrives with a leading `/` — finding 11, and the first two tests in Step 1. The private method comes last, per the ordering rule.

- [ ] **Step 6: Write the configuration**

Create `catalog-service/src/main/java/com/thedarkhorse/catalog/config/CatalogConfiguration.java`:

```java
package com.thedarkhorse.catalog.config;

import com.thedarkhorse.catalog.jpa.CategoryJpaRepository;
import com.thedarkhorse.catalog.mapper.CategoryMapper;
import com.thedarkhorse.catalog.mapper.CategoryMapperImpl;
import com.thedarkhorse.catalog.repository.CategoryRepository;
import com.thedarkhorse.catalog.repository.CategoryRepositoryImpl;
import com.thedarkhorse.catalog.service.CategoryService;
import com.thedarkhorse.catalog.service.CategoryServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CatalogConfiguration {

    @Bean
    public CategoryMapper categoryMapper() {
        return new CategoryMapperImpl();
    }

    @Bean
    public CategoryRepository categoryRepository(CategoryJpaRepository jpaRepository, CategoryMapper mapper) {
        return new CategoryRepositoryImpl(jpaRepository, mapper);
    }

    @Bean
    public CategoryService categoryService(CategoryRepository repository) {
        return new CategoryServiceImpl(repository);
    }
}
```

`CategoryMapperImpl` is generated by MapStruct in the same compilation round, which is why the module needs the runtime artifact from Task 3 as well as the processor from the root POM. Three `@Bean` methods that each call one constructor: no behaviour, so no test.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -B -pl catalog-service test -Dtest=CategoryControllerTest`

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 8: Run the gate**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 37` in `catalog-service` — 4 from #14, 2 added in Task 6, 8 from `CategoryRepositoryImplTest`, 18 from `CategoryServiceImplTest`, 5 from `CategoryControllerTest`.

- [ ] **Step 9: Commit**

```bash
git add catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryRequest.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryResponse.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/controller/CategoryController.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/mapper/CategoryMapper.java \
        catalog-service/src/main/java/com/thedarkhorse/catalog/config/CatalogConfiguration.java \
        catalog-service/src/test/java/com/thedarkhorse/catalog/controller/CategoryControllerTest.java
git commit -m "feat(catalog): add category tree read and write endpoints

Refs #15"
```

---

## Task 9: Measure all six acceptance criteria against the running service

Every criterion has a unit test or a `psql` check behind it by now, except criterion 3, which cannot have one without testing the framework, and criterion 2, whose 409 depends on a translation hop this plan has only inspected rather than measured (finding 15). This task closes both, and confirms that Flyway itself creates the table from the committed migration rather than the hand-applied SQL of Task 2.

**Files:**
- Create: `.scratch/Probe.java` (temporary)
- Delete: `.scratch/`

**Interfaces:**
- Consumes: everything above, plus `discovery-server` and `config-server` from #10 and #13.
- Produces: no committed file. Its output is the evidence the pull request body quotes.

- [ ] **Step 1: Build the HTTP client**

The allowlist has no `curl` and no `wget`. Create `.scratch/Probe.java`:

```java
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Probe {

    public static void main(String[] args) throws Exception {
        String method = args[0];
        String url = args[1];
        String body = args.length > 2 ? args[2] : null;

        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method(method, publisher)
                .build();

        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.currentTimeMillis() + 120_000L;
        while (true) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println(response.statusCode() + " " + response.body());
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

- [ ] **Step 2: Start the stack**

Run, each as a background Bash task except the first two:

```
docker compose up -d postgres
mvn -B clean package -DskipTests
java -jar discovery-server/target/discovery-server-0.0.1-SNAPSHOT.jar
java -jar config-server/target/config-server-0.0.1-SNAPSHOT.jar
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
```

Start `discovery-server` first, then `config-server`, then `catalog-service`; `catalog-service` will not boot without the config server, which reads the `config` branch from GitHub over HTTPS.

Four ids are created by the steps below and cannot be known in advance. Read each one out of the `201` body that creates it and substitute it wherever the placeholder appears: `KEYBOARDS_ID` from Step 4's first `POST`, `ACCESSORIES_ID` from Step 4's second, `CABLES_ID` and `PERIPHERALS_ID` from Steps 7 and 8. `CATALOG_OUTPUT` is the output file the harness names when the `catalog-service` background task starts.

- [ ] **Step 3: Confirm Flyway created the table from the committed migration**

Task 2 Step 9 dropped the volume, so this is Flyway's own work and not the hand-applied SQL.

Run: `grep -i flyway CATALOG_OUTPUT | head -20` — substitute the output path the harness printed for the background task.

Expected, among the lines:

```
Migrating schema "public" to version "1 - create category table"
Successfully applied 1 migration to schema "public"
```

Then:

```
docker exec postgres psql -U catalog -d catalog_db -c "\d category"
docker exec postgres psql -U catalog -d catalog_db -c "select version, description, success from flyway_schema_history;"
```

Expected: the ten columns, the `category_pkey`, `category_path_key`, `category_parent_id_slug_key` and `category_parent_id_fkey` constraints and `idx_category_parent_id`; and one history row, `1 | create category table | t`.

Hibernate booting at all is the `ddl-auto: validate` check passing, which is what proves the entity and the migration agree.

- [ ] **Step 4: Criterion 1 — a child under `keyboards` gets `path = 'keyboards/accessories'`**

Run:

```
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"slug":"keyboards"}'
```

Expected: `201 {"id":"01...","parentId":null,"slug":"keyboards","path":"keyboards","sortOrder":0,"active":true}`

`sortOrder` 0 and `active` true are the defaults from Task 4, and `active` being `true` rather than `false` is finding 6 having been dealt with.

Take the returned id as `KEYBOARDS_ID` and run:

```
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"parentId":"KEYBOARDS_ID","slug":"accessories"}'
```

Expected: `201 {...,"slug":"accessories","path":"keyboards/accessories",...}`

- [ ] **Step 5: Criterion 2 — a second root with an existing slug is a 409**

Run:

```
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"slug":"keyboards"}'
```

Expected:

```
409 {"type":"about:blank","title":"Conflict","status":409,
     "detail":"The request conflicts with the current state of the resource","instance":"/api/v1/categories"}
```

The body must carry no constraint name and no SQL.

If this returns **500** rather than 409, finding 15's translation hop did not fire, and the one-line fix is to make the violation surface inside the transactional method: change `CategoryRepositoryImpl.save` to `mapper.toModel(jpaRepository.saveAndFlush(mapper.toEntity(category)))`. `saveAndFlush` is on `JpaRepository`, so no interface changes and no test changes. Re-run this step and record which of the two it took.

- [ ] **Step 6: Criterion 3 — Arabic or uppercase in a slug is a 400**

Run:

```
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"slug":"Keyboards"}'
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"slug":"لوحات"}'
```

Expected, for both:

```
400 {"type":"about:blank","title":"Bad Request","status":400,"detail":"Invalid request content.",
     "instance":"/api/v1/categories",
     "errors":[{"field":"slug","message":"must match \"[a-z0-9-]+\""}]}
```

The `errors` array is #14's advice doing its job. Finding 12 measured the same two rejections against a real `Validator` before this plan was written, so a different message here means something is wrong with the wiring rather than with the regex.

- [ ] **Step 7: Criterion 5 — a subtree read returns the node and every descendant**

First build a subtree with a prefix-sharing sibling in it, which is finding 8's case:

```
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"parentId":"ACCESSORIES_ID","slug":"cables"}'
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"slug":"keyboards-2"}'
java .scratch/Probe.java GET http://localhost:8081/api/v1/categories/keyboards
```

Expected: `200` with exactly three elements whose `path` values are `keyboards`, `keyboards/accessories` and `keyboards/accessories/cables`, in that order. **`keyboards-2` must not appear.**

Then confirm the route through the gateway, since the spec's URL example goes through it — start `gateway` as a background task first:

```
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
java .scratch/Probe.java GET http://localhost:8080/catalog/api/v1/categories/keyboards/accessories
```

Expected: `200` with two elements, `keyboards/accessories` and `keyboards/accessories/cables`. This is the exact URL in spec section 3.

- [ ] **Step 8: Criterion 6 — a move recomputes the whole subtree**

Run:

```
java .scratch/Probe.java POST http://localhost:8081/api/v1/categories '{"slug":"peripherals"}'
java .scratch/Probe.java PUT http://localhost:8081/api/v1/categories/KEYBOARDS_ID '{"parentId":"PERIPHERALS_ID","slug":"keyboards"}'
java .scratch/Probe.java GET http://localhost:8081/api/v1/categories/peripherals
```

Expected from the `PUT`: `200 {...,"path":"peripherals/keyboards",...}`

Expected from the `GET`: `200` with four elements — `peripherals`, `peripherals/keyboards`, `peripherals/keyboards/accessories`, `peripherals/keyboards/accessories/cables`.

Then confirm the sibling did not move:

```
java .scratch/Probe.java GET http://localhost:8081/api/v1/categories/keyboards-2
```

Expected: `200` with one element, `keyboards-2`.

- [ ] **Step 9: Criterion 4 — deleting a category with children is rejected**

Run:

```
java .scratch/Probe.java DELETE http://localhost:8081/api/v1/categories/KEYBOARDS_ID
```

Expected:

```
409 {"type":"about:blank","title":"Conflict","status":409,
     "detail":"The request conflicts with the current state of the resource",...}
```

Then confirm a leaf does delete, and that the row is gone:

```
java .scratch/Probe.java DELETE http://localhost:8081/api/v1/categories/CABLES_ID
docker exec postgres psql -U catalog -d catalog_db -c "select path from category order by path;"
```

Expected: `204` with an empty body, and four rows — `keyboards-2`, `peripherals`, `peripherals/keyboards`, `peripherals/keyboards/accessories`.

Finally confirm the database would have refused it even without the service check, which is the half of criterion 4 that survives a caller who bypasses the API:

```
docker exec postgres psql -U catalog -d catalog_db -c "delete from category where path = 'peripherals';"
```

Expected: `ERROR: update or delete on table "category" violates RESTRICT setting of foreign key constraint "category_parent_id_fkey" on table "category"`

- [ ] **Step 10: Stop everything and clean up**

Stop the four background tasks with the harness's background-task stop. Then:

```
docker compose down -v
rm -rf .scratch
git status --short
```

Expected from `git status --short`: empty. Nothing in `.scratch/` may reach the branch.

- [ ] **Step 11: Run the gate one last time**

Run: `mvn -B clean verify`

Expected: `BUILD SUCCESS`, `Tests run: 37, Failures: 0, Errors: 0, Skipped: 0` in `catalog-service`, `Tests run: 1` in `gateway`, all five modules `SUCCESS`.

- [ ] **Step 12: Confirm the standing rules hold across the diff**

Run:

```
grep -rn "@Service\|@Component\|@Repository" catalog-service/src/main/java
grep -rn "@Query\|nativeQuery\|createQuery" catalog-service/src/main/java
grep -rn "//\|/\*" catalog-service/src/main/java catalog-service/src/main/resources
grep -rln "CreateCategory\|UpdateCategory\|MoveCategory" catalog-service/src
```

Expected: the first three print nothing at all, and the fourth prints nothing. `@RestController` contains neither `@Service` nor `@Component` as a substring, so a hit on the first is a real breach.

Then:

```
git log --oneline master..HEAD
```

Expected: ten commits — one `docs(plan): implementation plan for #15`, then nine `feat(catalog): ...`, one per red-green cycle: two from Task 2, one each from Tasks 3, 4 and 5, two from Task 6, one each from Tasks 7 and 8. Every one carries a `Refs #15` footer, and none carries a `Co-Authored-By` trailer or a generated-with footer.

- [ ] **Step 13: Open the pull request**

See "Pull request" below.

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #15 | Task | Step | Red | Green |
| --- | --- | --- | --- | --- | --- |
| 1 | Creating a child under `keyboards` with slug `accessories` stores `path = 'keyboards/accessories'` | 4, 9 | 4.2, 4.5, 9.4 | `cannot find symbol: class CategoryServiceImpl` at `testCompile` | `givenAParent_whenCreateCategory_thenThePathIsTheParentPathAndTheSlug` passes; live `POST` returns `201` with `"path":"keyboards/accessories"` |
| 2 | Creating a second root with an existing slug returns 409 | 2, 9 | 2.A.8, 9.5 | `psql` accepts the insert before the migration adds `unique nulls not distinct (parent_id, slug)` | `ERROR: duplicate key value violates unique constraint "category_parent_id_slug_key"` with `Key (parent_id, slug)=(null, keyboards)`; live `POST` returns `409` with no constraint name in the body. No Java: integrity stays in the database and #14's advice already maps `DataIntegrityViolationException` |
| 3 | A slug containing Arabic or uppercase returns 400 | 8, 9 | 8.2, 9.6 | Before `CategoryRequest` exists the endpoint does not exist; with the record but no `@Pattern`, `{"slug":"Keyboards"}` is accepted with `201` | live `POST` returns `400` with `errors:[{"field":"slug","message":"must match \"[a-z0-9-]+\""}]` for both `Keyboards` and `لوحات`. **No unit test**: asserting that `@Pattern` rejects Arabic is testing Hibernate Validator, which the spec forbids. Finding 12 measured the pattern itself separately |
| 4 | Deleting a category that has children is rejected | 6, 2, 9 | 6.A.2, 6.A.4, 2.A.8, 9.9 | `cannot find symbol: class CategoryHasChildrenException` at `testCompile`; and `psql` deleting a parent before the foreign key exists | `givenACategoryWithDescendants_whenDeleteCategory_thenCategoryHasChildren` and `givenALeaf_whenDeleteCategory_thenItIsDeleted` pass; live `DELETE` returns `409`, a leaf returns `204`, and `psql` still refuses with `violates RESTRICT setting of foreign key constraint "category_parent_id_fkey"` |
| 5 | Fetching a subtree returns the node and every descendant | 5, 9 | 5.2, 5.4, 9.7 | `cannot find symbol: method findSubtree(java.lang.String)` at `testCompile` | four tests pass, including `givenASiblingSharingThePrefix_whenFindSubtree_thenTheSiblingIsNotADescendant`; live `GET /api/v1/categories/keyboards` returns `keyboards`, `keyboards/accessories`, `keyboards/accessories/cables` and **not** `keyboards-2`, and the same through the gateway at `/catalog/api/v1/categories/keyboards/accessories` |
| 6 | Moving a category recomputes `path` for it and every descendant | 7, 9 | 7.2, 7.4, 9.8 | `cannot find symbol: method updateCategory(java.lang.String,...)` at `testCompile` | six tests pass, including `givenANewParent_whenUpdateCategory_thenEveryDescendantPathIsRecomputed`; live `PUT` returns `"path":"peripherals/keyboards"` and the subsequent subtree read returns all four recomputed paths while `keyboards-2` is untouched |

Criteria 2 and 4 each appear twice on purpose. Both are integrity rules the spec puts in the database, so the migration is where they are really enforced and Task 2 measures them there with `psql`. Criterion 4 additionally gets a service check, because with no database allowed in tests that is the only form of it a unit test can reach, and because an API that says 409 before the statement runs is a better contract than one that lets the driver decide. Criterion 2 gets no service check: a `findByParentIdAndSlug` guard would be a read-then-write race that the unique index has to catch anyway, and `CLAUDE.md` says uniqueness is a 409 and never a field error.

Criterion 3 is the one criterion in this issue with no unit test, and that is deliberate rather than an omission. Every way of unit-testing it — constructing a `Validator`, asserting a `ConstraintViolation` — asserts that Hibernate Validator implements `@Pattern`, which the spec bans in the same sentence as `@NotNull` and `@Transactional`. What is ours to test is that the constraint is on the record and that the advice renders it as a 400 with the field named, and only a live request shows both.

---

## Pull request

Title: `feat(catalog): add category table with materialised path tree endpoints (#15)`

Body lists each of the six acceptance criteria with the command and the output that verified it, and ends with `Closes #15`. Merge with rebase, never squash, so the nine per-cycle commits survive.

The body must also carry the four places where this increment does not match what a reviewer would predict from reading issue #15 and the spec side by side, because all four will otherwise be flagged:

- **`created_by` and `updated_by` are in the table and on no Java field, and they are nullable.** Spec section 4 requires all four audit columns and #15's `### Design` lists them, so they exist. But there is no security on `catalog-service`'s classpath and #15 does not ask for any, so there is no Keycloak subject to record and no honest value to put in them. `created_at` and `updated_at` are `not null` and Hibernate-generated. `ddl-auto: validate` accepts the two unmapped columns — measured against `postgres:18-alpine` with a real `SessionFactory` — so they cost nothing and are ready for the increment that brings a principal.
- **A move into a category's own subtree is not prevented.** `PUT` moving `keyboards` under `keyboards/accessories` will recompute paths into a cycle. No acceptance criterion covers it, nothing in the issue's DDL stops it, and `CLAUDE.md` says to build only what the issue asks for, so it is left unbuilt and named here instead. It is worth its own issue.
- **`org.mapstruct:mapstruct` was missing from `catalog-service/pom.xml`.** The root POM manages the version and puts `mapstruct-processor` on `annotationProcessorPaths`, but the runtime artifact was never a dependency of the module, so `org.mapstruct.Mapper` did not resolve. One dependency added, no `<version>`.
- **`.config-repo` needed no change, and was checked file by file rather than assumed.** `spring.jpa.hibernate.ddl-auto: validate` and `spring.jpa.show-sql` are in `application.yaml`, the datasource is in `catalog-service-local.yaml` and `catalog-service-prod.yaml`, `server.port: 8081` is in `catalog-service.yaml`, the Eureka zone is in `application-local.yaml`. Flyway needs no key at all: `spring-boot-flyway-4.1.0.jar` registers `FlywayAutoConfiguration`, `spring.flyway.enabled` already defaults to `true` and `spring.flyway.locations` already defaults to `classpath:db/migration`, and `CLAUDE.md` forbids writing a key that already holds the value you want.

And the two design readings the issue leaves open, stated so the reviewer can reject them cheaply if they disagree:

- **The subtree response is a flat `List<CategoryResponse>` ordered by `path`, not a nested tree.** "Returns the node and every descendant" is satisfied exactly by the list; a nested `children` structure is an algorithm, a second record and a second set of tests that nothing asks for. `sort_order` is stored and returned but does not order the flat list, because sorting a mixed-depth list by a sibling-ordering column interleaves the levels.
- **Reads address a category by path and writes address it by id.** `GET /api/v1/categories/{*path}` matches the spec's own URL example. `PUT` and `DELETE` take `/{id}`, because a `PUT` that moves a category changes the very path that would have addressed it. One `CategoryRequest` serves both `POST` and `PUT`; there is no `CreateCategoryRequest` and no `MoveCategoryRequest`.
