# Ecommerce

Single-seller ecommerce backend. Microservices in one Maven monorepo, flat modules at the root.

`docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` is authoritative. Read it before
starting work. It carries the service table, ports, DDL, and the reasoning behind every rule below.

## Standing rules

These are absolute and are not renegotiated per ticket.

**TDD.** No class is written before a failing test for it has been seen to fail. Applies to code with
behaviour: a branch, a loop, validation, orchestration, arithmetic, an HTTP contract. Code with no
behaviour gets no test — no tests for MapStruct mappers, Lombok accessors, exceptions that only
extend `RuntimeException`, entity fields, or `@Bean` methods that call a constructor. Never test the
framework.

**Annotations.** `@Component`, `@Service` and `@Repository` are forbidden. `@RestController` and
`@RestControllerAdvice` are the only stereotypes, controller layer only. Every other bean is an
`@Bean` method in an `@Configuration` class. Spring Data JPA repository interfaces are the one
exception, because Spring generates their implementations.

**Queries.** No raw query text in Java. No `@Query`, no native SQL strings, no Criteria fragments.
Only Spring Data derived query methods. Raw SQL belongs in Flyway migrations.

**Layering.** `controller → service → repository → jpa`. The controller speaks Request and Response
records, the service speaks models, the repository returns models, the jpa layer owns entities.
Service and repository each have an interface plus `CategoryServiceImpl` alongside it in the same
package. There is no `impl` subpackage. `@Transactional` and caching live on the service.

**Types.** Models are Lombok classes, not records, unless genuinely read-only. Boundary types are
records named for the entity and never the operation: `CategoryRequest`, `CategoryResponse`. Never
`CreateCategoryRequest` or `UpdateCategoryRequest`. One request type per entity.

No value-object wrappers. No `Money`, `Price`, `Sku`, `Slug`. Money is a bare `BigDecimal` on
`numeric(19,3)` columns, compared with `compareTo`. Identifiers are `uuid` holding UUIDv7; `UUID`
appears only on entities and every layer above uses `String`.

**Naming.** Lookups are prefixed `find` at every layer. Mutations use verbs.

**Validation.** Constraints live on Request records only; entities carry none. Constraints guard
shape. Integrity stays in the database, so uniqueness is a 409 and never a field error.

**Comments.** None. No Javadoc, no comment blocks, and none in YAML, SQL, properties files or shell
scripts. A comment that feels necessary means the code is badly written; fix the code. Rationale goes
in the spec.

**Scope.** Build only what the issue asks for. No helper classes, DTOs or configuration knobs that
nobody requested.

## Build

Java 25, Maven. `mvn -B clean verify` is the only gate — Error Prone runs during compilation, so a
finding fails the build.

The root POM has no `<parent>`; Spring Boot and Spring Cloud are imported as BOMs. Every plugin
therefore carries an explicit version, because no `pluginManagement` is inherited.

## Commits and pull requests

Conventional Commits with the module as the scope, for example
`feat(catalog): add category tree with materialised path`. One commit per completed red-green-refactor
cycle, so every commit is green. Each commit carries a `Refs #N` footer naming the issue it belongs
to.

The pull request title ends with the issue number, `feat(catalog): add category tree (#15)`, so the
issue is traceable from the pull request list without opening anything. The body ends with
`Closes #15`, which is what actually closes the issue on merge.

Merge with rebase, never squash. Squashing collapses the per-cycle commits and appends the pull
request number to the title a second time.

No `Co-Authored-By` trailer. No "Generated with Claude Code" footer.
