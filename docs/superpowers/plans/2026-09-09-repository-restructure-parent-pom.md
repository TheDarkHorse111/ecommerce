# Repository Restructure to Parent POM and Flat Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the root `pom.xml` into a pure parent that manages Spring Boot, Spring Cloud, MapStruct and an Error Prone build gate, and delete the root Spring Boot application, so that flat service modules can be added by later issues.

**Architecture:** The root POM keeps its `<parent>` slot free and imports `spring-boot-dependencies` and `spring-cloud-dependencies` as BOMs. Because no `pluginManagement` is inherited, every plugin carries an explicit version. Error Prone runs as a javac plugin on the same `annotationProcessorPaths` as Lombok, the Lombok–MapStruct binding and MapStruct, and a root `.mvn/jvm.config` opens the `jdk.compiler` internals it needs in Maven's own JVM. There is exactly one gate, `mvn -B clean verify`.

**Tech Stack:** Java 25 (Amazon Corretto 25.0.4.1), Apache Maven 3.9.16, Spring Boot 4.1.0, Spring Cloud 2025.1.2, MapStruct 1.6.3, Lombok (unpinned, resolves 1.18.46), Error Prone 2.50.0, maven-compiler-plugin 3.16.0.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` (sections 3, 6 "Root POM" and "Static analysis", 7 step 1). Issue #1 is the specification for this increment.

---

## Global Constraints

Copied from `CLAUDE.md` and the spec. Every task's requirements implicitly include this section.

- **Build gate:** `mvn -B clean verify` is the only gate. There is no separate lint step.
- **No `<parent>` on the root POM.** Spring Boot and Spring Cloud are imported as BOMs.
- **Every plugin carries an explicit version**, because a BOM import supplies `dependencyManagement` only and no `pluginManagement` is inherited.
- **Exact pins:** `spring-boot.version` = `4.1.0`, `spring-cloud.version` = `2025.1.2`, `mapstruct.version` = `1.6.3`, `error-prone.version` = `2.50.0`, `maven-compiler-plugin` = `3.16.0`, `lombok-mapstruct-binding` = `0.2.0`. Lombok's version is **not** pinned.
- **Annotation processor path order is load-bearing:** Lombok, `lombok-mapstruct-binding`, `mapstruct-processor`, `error_prone_core`. Setting `annotationProcessorPaths` disables processor discovery on the classpath, so anything absent from the path stops running silently.
- **Comments:** none. No XML comment blocks in `pom.xml`, no comments in `lombok.config`, `.mvn/jvm.config` or any YAML, SQL, properties or shell file. Rationale lives in the spec and in this plan, never in the files.
- **Scope:** build only what issue #1 asks for. No extra Maven properties, profiles, plugins or configuration knobs beyond those listed above. In particular do **not** introduce `<maven-compiler-plugin.version>` or `<lombok-mapstruct-binding.version>` properties; those two versions are written inline, because the issue's properties block does not list them.
- **Error Prone severity is per bug pattern.** Findings are not promoted wholesale with `-Werror`.
- **No forking the compiler.** `maven-compiler-plugin` must compile in Maven's own JVM. Do not add `<fork>true</fork>` or `-J`-prefixed compiler arguments to the committed POM.
- **Conventional Commits** with the module as scope, one commit per completed red-green cycle, each commit green, each carrying a `Refs #1` footer and no `Co-Authored-By` trailer beyond the configured one.

---

## Verified Findings

Every claim issue #1 makes about tool behaviour was tested in this repository before planning on top of it, using throwaway projects under `.scratch/`. Two claims did not hold.

### Confirmed

1. **Error Prone 2.50.0 on JDK 25 dies without the JVM flags.** With `error_prone_core` on the processor path and no `.mvn/jvm.config`, `mvn -B clean compile` failed with exactly the documented error:

   ```
   java.lang.IllegalAccessError: class com.google.errorprone.BaseErrorProneJavaCompiler
   (in unnamed module @0x3b4825f0) cannot access class com.sun.tools.javac.api.BasicJavacTask
   (in module jdk.compiler) because module jdk.compiler does not export
   com.sun.tools.javac.api to unnamed module @0x3b4825f0
   ```

   Maven reports this only as `An unknown compilation problem occurred`; the stack trace is above it in the log.

2. **The documented flag set fixes it.** The ten flags below were taken verbatim from <https://errorprone.info/docs/installation> and, once in effect, compilation succeeded.

3. **`SelfAssignment` is error severity and fails the build.** Output:

   ```
   [ERROR] .../Offender.java:[8,14] [SelfAssignment] Variable assigned to itself
   [ERROR]     (see https://errorprone.info/bugpattern/SelfAssignment)
   [ERROR]   Did you mean 'this.name = name;'?
   [INFO] BUILD FAILURE
   ```

   Deleting the offending file returned the build to `BUILD SUCCESS`.

4. **A Lombok-generated method produces no Error Prone finding.** Tested with `MissingOverride` promoted to `ERROR`. A hand-written `getName()` implementing an interface was flagged:

   ```
   [ERROR] .../Manual.java:[11,19] [MissingOverride] getName implements method in Named; expected @Override
   ```

   The identical getter generated by `@Data` on a class implementing the same interface was **not** flagged. See the caveat under "Claims that did not hold" for what this means for `lombok.config`.

5. **The processor path order survives into the effective POM.** `mvn help:effective-pom` on a child module listed `lombok`, `lombok-mapstruct-binding`, `mapstruct-processor`, `error_prone_core` in that order.

6. **All pins resolve.** `spring-boot-dependencies:4.1.0`, `spring-cloud-dependencies:2025.1.2`, `mapstruct:1.6.3`, `lombok-mapstruct-binding:0.2.0`, `error_prone_core:2.50.0` and `maven-compiler-plugin:3.16.0` all downloaded and ran.

7. **Error Prone analyses test sources too.** Because the configuration sits at plugin level rather than execution level, both `default-compile` and `default-testCompile` used the same processor path and compiler arguments.

8. **The full cycle works.** On the throwaway multi-module project, `mvn -B clean verify` compiled with Error Prone, ran a JUnit 5 test (`Tests run: 1, Failures: 0`) and produced a repackaged jar:

   ```
   [INFO] --- spring-boot:4.1.0:repackage (default) @ mod-a ---
   [INFO] Replacing main artifact .../mod-a-0.0.1-SNAPSHOT.jar with repackaged archive,
          adding nested dependencies in BOOT-INF/.
   ```

9. **Lombok resolves to 1.18.46**, not the 1.18.42 the spec's section 6 records. The issue is right that the version should not be pinned; the spec's recorded number is simply stale. No action — do not pin it.

### Claims that did not hold

**A. "Maven 3.9.16 otherwise binds 3.13.0, which cannot compile against JDK 25." — FALSE.**

A project with no compiler plugin declaration and `maven.compiler.release` of 25 was built. The observed output:

```
[INFO] --- compiler:3.15.0:compile (default-compile) @ defaultcp ---
[INFO] Compiling 1 source file with javac [debug release 25] to target/classes
[INFO] BUILD SUCCESS
```

Maven 3.9.16 binds **3.15.0**, not 3.13.0, and that version compiles against JDK 25 without complaint.

**This does not change the plan.** `maven-compiler-plugin` still gets pinned at 3.16.0, because the standing rule "every plugin carries an explicit version" is independent of which version Maven would otherwise pick, and an unpinned plugin makes the build depend on the Maven version on the machine. Only the issue's stated *reason* is wrong. Do not repeat that reason anywhere.

**B. "`mvn help:effective-pom` on a module shows `<parameters>true</parameters>`" — FALSE as the issue's design is written.**

The issue's design block sets only the `maven.compiler.parameters` property. With just that property, `mvn help:effective-pom` on a child module shows:

```
<maven.compiler.parameters>true</maven.compiler.parameters>
```

inside `<properties>`, and **no** `<parameters>` element anywhere in the compiler plugin configuration. The acceptance criterion as worded therefore fails.

The property alone *is* functionally sufficient — the compile line read `Compiling 3 source files with javac [forked debug parameters release 25]`, so `-parameters` was passed. But to satisfy the criterion literally, `<parameters>true</parameters>` must also be written explicitly into the compiler plugin's `<configuration>`. With both present, effective-pom showed `<parameters>true</parameters>`. Task 3 makes exactly this change, red-green.

### Not executed in this planning session

Two checks could not be run here and must be run by the implementer. Both are ordinary steps in the tasks below.

- **`.mvn/jvm.config` itself.** This sandbox refuses to create paths under `.mvn/`. The flags were proven equivalent by forking javac and passing the same ten flags `-J`-prefixed, which turned the `IllegalAccessError` into `BUILD SUCCESS`. The committed POM must **not** fork; Task 2's red-green step is what confirms the non-forked `.mvn/jvm.config` route works. Note that Write/Edit tools may refuse `.mvn/` paths — create the file with a shell redirect instead.
- **`java -jar`.** This sandbox refuses `java -jar` and `unzip`. `spring-boot:repackage` was observed replacing the main artifact with a nested-dependency archive, but the jar was never launched. Task 3 launches it.

---

## Interpretation: what `<modules>` contains

Issue #1 says both "Root `pom.xml` becomes a pure parent with a `<modules>` list" and, one sentence later, "**No service module is added here.**" It then shows a directory tree with nine module directories.

**Assumption taken:** the tree is the target layout that later issues fill in, and the explicit sentence governs. This issue creates **no** module directories, and `<modules/>` is committed empty. Spec section 7 supports this: step 1 is the restructure, step 2 stands up `discovery-server`, `config-server` and `gateway`.

That leaves two acceptance criteria — "a module's `mvn package` produces a jar that runs with `java -jar`" and "`mvn help:effective-pom` **on a module**" — with no module to run against. They are satisfied the same way the issue's own `SelfAssignment` criterion is satisfied: with a deliberate, temporary artifact. A throwaway probe module lives under `.scratch/build-probe/`, declares the real root `pom.xml` as its parent via `<relativePath>`, and is deleted before the final commit. It is never added to `<modules>` and never enters git.

This mechanism was verified: a child POM outside the parent's `<modules>` list, parented by `<relativePath>`, inherited the full compiler configuration and Error Prone fired on it (`compiler:3.16.0`, `[SelfAssignment] Variable assigned to itself`).

If the issue author actually wanted nine module skeletons, that is a one-line change to `<modules>` plus nine directories, and it belongs in issue #2 where those services are stood up.

---

## File Structure

Committed by this plan:

| Path | Responsibility | Action |
| --- | --- | --- |
| `pom.xml` | Pure parent. Coordinates, `<packaging>pom</packaging>`, empty `<modules/>`, properties, BOM imports, MapStruct in `dependencyManagement`, `pluginManagement` for `spring-boot-maven-plugin` and `maven-compiler-plugin`. | Rewrite |
| `lombok.config` | `lombok.addLombokGeneratedAnnotation = true`. | Create |
| `.mvn/jvm.config` | The ten `--add-exports` / `--add-opens` flags Error Prone needs on JDK 16+. | Create |
| `src/main/java/com/thedarkhorse/ecommerce/EcommerceApplication.java` | Root Spring Boot application. | Delete |
| `src/main/resources/application.yaml` | Its empty configuration. | Delete |
| `src/test/java/com/thedarkhorse/ecommerce/EcommerceApplicationTests.java` | Its context-load test. | Delete |
| `src/` | The whole tree, once empty. | Delete |

Temporary, never committed:

| Path | Responsibility |
| --- | --- |
| `.scratch/build-probe/` | Throwaway module parented to the real root POM, used to execute the acceptance criteria that need a module. Deleted in Task 3. |

Not touched: `.gitignore` (`.mvn/jvm.config` is not ignored — only `.mvn/wrapper/maven-wrapper.jar` is), `.github/workflows/build.yml` (already runs `mvn -B clean verify` on Corretto 25), `CLAUDE.md`, `README.md`, the spec.

---

## Task 1: Root POM becomes a pure parent, root application deleted

Covers acceptance criteria 2 (no `@SpringBootApplication` at the root) and 3 (root is `<packaging>pom</packaging>`, no compile-scope dependencies, no `<parent>`).

**Files:**
- Modify: `pom.xml` (full rewrite, current file is 136 lines)
- Delete: `src/main/java/com/thedarkhorse/ecommerce/EcommerceApplication.java`
- Delete: `src/main/resources/application.yaml`
- Delete: `src/test/java/com/thedarkhorse/ecommerce/EcommerceApplicationTests.java`
- Delete: `src/` once empty

**Interfaces:**
- Consumes: nothing.
- Produces: the parent coordinates `com.thedarkhorse:ecommerce:0.0.1-SNAPSHOT` with `pom` packaging, which every later module inherits by `<parent>`. The properties `spring-boot.version`, `spring-cloud.version`, `mapstruct.version`, `maven.compiler.release`, `maven.compiler.parameters`, `project.build.sourceEncoding`. `pluginManagement` entries for `org.springframework.boot:spring-boot-maven-plugin` (with a `repackage` execution) and `org.apache.maven.plugins:maven-compiler-plugin` (with `annotationProcessorPaths` of Lombok, binding, MapStruct). Task 2 appends `error_prone_core` and `compilerArgs` to that same plugin block; Task 3 adds `<parameters>true</parameters>` to it.

- [ ] **Step 1: Write the failing test**

There is no behaviour here, so per `CLAUDE.md` there is no JUnit test. The failing test is the acceptance criteria themselves, run as commands. Create the check script so it is repeatable:

```bash
mkdir -p .scratch
cat > .scratch/check-task1.sh <<'SH'
set -u
echo "--- packaging (want: pom) ---"
mvn -B -q help:evaluate -Dexpression=project.packaging -DforceStdout
echo
echo "--- root <parent> (want: no output) ---"
grep -n '<parent>' pom.xml || echo "none"
echo "--- @SpringBootApplication at root (want: no output) ---"
grep -rn '@SpringBootApplication' src 2>/dev/null || echo "none"
echo "--- compile-scope deps in root (want: no output) ---"
grep -n '<artifactId>spring-boot-starter' pom.xml || echo "none"
SH
bash .scratch/check-task1.sh
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash .scratch/check-task1.sh`

Expected, against the repository as it stands:

```
--- packaging (want: pom) ---
jar
--- root <parent> (want: no output) ---
5:    <parent>
--- @SpringBootApplication at root (want: no output) ---
src/main/java/com/thedarkhorse/ecommerce/EcommerceApplication.java:6:@SpringBootApplication
--- compile-scope deps in root (want: no output) ---
20:            <artifactId>spring-boot-starter-data-jpa</artifactId>
...
```

All four checks fail. That is the red.

- [ ] **Step 3: Write the minimal implementation**

Replace `pom.xml` in full with exactly this. No comments, no extra properties.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.thedarkhorse</groupId>
    <artifactId>ecommerce</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>ecommerce</name>

    <modules/>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <maven.compiler.parameters>true</maven.compiler.parameters>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-boot.version>4.1.0</spring-boot.version>
        <spring-cloud.version>2025.1.2</spring-cloud.version>
        <mapstruct.version>1.6.3</mapstruct.version>
        <error-prone.version>2.50.0</error-prone.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct</artifactId>
                <version>${mapstruct.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                    <executions>
                        <execution>
                            <goals>
                                <goal>repackage</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.16.0</version>
                    <configuration>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>${lombok.version}</version>
                            </path>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok-mapstruct-binding</artifactId>
                                <version>0.2.0</version>
                            </path>
                            <path>
                                <groupId>org.mapstruct</groupId>
                                <artifactId>mapstruct-processor</artifactId>
                                <version>${mapstruct.version}</version>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

`${lombok.version}` is supplied by the imported `spring-boot-dependencies` BOM and resolves to 1.18.46. That is what "Lombok's version is not pinned" means in practice — the BOM decides, and no property in this POM overrides it.

Then delete the root application:

```bash
git rm -r src
```

- [ ] **Step 4: Run the checks to verify they pass**

Run: `bash .scratch/check-task1.sh`

Expected:

```
--- packaging (want: pom) ---
pom
--- root <parent> (want: no output) ---
none
--- @SpringBootApplication at root (want: no output) ---
none
--- compile-scope deps in root (want: no output) ---
none
```

Then run the gate: `mvn -B clean verify`

Expected: `BUILD SUCCESS`. With no modules and no sources there is nothing to compile; this confirms the POM is well formed and both BOM imports resolve. A failure here means a BOM coordinate is wrong.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
refactor(build): make root pom a pure parent and drop the root application

Refs #1
EOF
)"
```

The `git rm -r src` from Step 3 already staged the deletions.

---

## Task 2: Error Prone as a compile-time gate

Covers acceptance criteria 6 (a deliberate `SelfAssignment` fails `mvn verify`, reverting it passes) and 7 (a Lombok-generated method produces no Error Prone finding).

**Files:**
- Modify: `pom.xml` — append the `error_prone_core` path and `compilerArgs` to the `maven-compiler-plugin` block written in Task 1
- Create: `.mvn/jvm.config`
- Create: `lombok.config`
- Create then delete: `.scratch/build-probe/`

**Interfaces:**
- Consumes: from Task 1, the `maven-compiler-plugin` `pluginManagement` block with its three-entry `annotationProcessorPaths`, and the `error-prone.version` property already set to `2.50.0`.
- Produces: a compiler configuration under which any module inheriting this parent has Error Prone run over both main and test sources. Task 3 adds `<parameters>true</parameters>` to the same `<configuration>` element.

- [ ] **Step 1: Write the failing test**

Error Prone cannot be exercised without Java to compile, and this repository now has none. Create the throwaway probe. It is parented to the real root POM by `<relativePath>` and is deliberately **not** listed in `<modules>`, so it never affects the committed build.

```bash
mkdir -p .scratch/build-probe/src/main/java/com/thedarkhorse/probe
```

`.scratch/build-probe/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.thedarkhorse</groupId>
        <artifactId>ecommerce</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>build-probe</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

`.scratch/build-probe/src/main/java/com/thedarkhorse/probe/ProbeApplication.java`:

```java
package com.thedarkhorse.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProbeApplication.class, args);
    }
}
```

Now append `error_prone_core` and the compiler arguments to the `maven-compiler-plugin` `<configuration>` in the root `pom.xml`, so that the fourth path entry follows `mapstruct-processor`:

```xml
                            <path>
                                <groupId>com.google.errorprone</groupId>
                                <artifactId>error_prone_core</artifactId>
                                <version>${error-prone.version}</version>
                            </path>
                        </annotationProcessorPaths>
                        <compilerArgs>
                            <arg>-XDcompilePolicy=simple</arg>
                            <arg>--should-stop=ifError=FLOW</arg>
                            <arg>-Xplugin:ErrorProne</arg>
                        </compilerArgs>
```

- [ ] **Step 2: Run it to verify it fails**

Run from the repository root, so that Maven's launcher looks for `.mvn/` here:

```bash
mvn -B -f .scratch/build-probe/pom.xml clean compile
```

Expected: `BUILD FAILURE`. Maven's summary line is the unhelpful `An unknown compilation problem occurred`; the real cause is higher in the log:

```
java.lang.IllegalAccessError: class com.google.errorprone.BaseErrorProneJavaCompiler
(in unnamed module @...) cannot access class com.sun.tools.javac.api.BasicJavacTask
(in module jdk.compiler) because module jdk.compiler does not export
com.sun.tools.javac.api to unnamed module @...
```

This is the red for `.mvn/jvm.config`: Error Prone is on the processor path but cannot start.

- [ ] **Step 3: Write the minimal implementation**

Create `.mvn/jvm.config` with the ten flags Error Prone documents for JDK 16 and above. **The Write and Edit tools may refuse paths under `.mvn/`**; if so, use a shell redirect. One flag per line, no comments, no blank lines:

```bash
mkdir -p .mvn
cat > .mvn/jvm.config <<'CFG'
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
CFG
```

Create `lombok.config` at the repository root:

```
lombok.addLombokGeneratedAnnotation = true
```

- [ ] **Step 4: Run it to verify it passes**

```bash
mvn -B -f .scratch/build-probe/pom.xml clean compile
```

Expected: `BUILD SUCCESS`, with a compile line reading `Compiling 1 source file with javac [debug parameters release 25] to target/classes`. Note there is no `forked` in that description — if it says `forked`, someone added `<fork>true</fork>`, which the spec forbids; remove it.

- [ ] **Step 5: Prove the Error Prone gate — `SelfAssignment` fails the build**

Add a deliberate violation at `.scratch/build-probe/src/main/java/com/thedarkhorse/probe/Offender.java`:

```java
package com.thedarkhorse.probe;

public class Offender {

    private String name;

    public void rename(String name) {
        name = name;
        this.name = name;
    }
}
```

Run: `mvn -B -f .scratch/build-probe/pom.xml clean verify`

Expected: `BUILD FAILURE` with

```
[ERROR] .../Offender.java:[8,14] [SelfAssignment] Variable assigned to itself
[ERROR]     (see https://errorprone.info/bugpattern/SelfAssignment)
[ERROR]   Did you mean 'this.name = name;'?
```

Then revert it:

```bash
rm .scratch/build-probe/src/main/java/com/thedarkhorse/probe/Offender.java
mvn -B -f .scratch/build-probe/pom.xml clean verify
```

Expected: `BUILD SUCCESS`. Both halves of acceptance criterion 6 are now observed.

- [ ] **Step 6: Prove a Lombok-generated method produces no finding**

Two files. `.scratch/build-probe/src/main/java/com/thedarkhorse/probe/Named.java`:

```java
package com.thedarkhorse.probe;

public interface Named {
    String getName();
}
```

`.scratch/build-probe/src/main/java/com/thedarkhorse/probe/Person.java`:

```java
package com.thedarkhorse.probe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Person implements Named {

    private String name;
}
```

`Person` never declares `getName()`; Lombok's `@Data` generates it, and it implements `Named.getName()` without an `@Override`. Hand-written, that is a `MissingOverride` finding.

`MissingOverride` is warning severity by default, so a clean build would prove nothing. Promote it to error for the probe only, by overriding the inherited `compilerArgs` in `.scratch/build-probe/pom.xml`. Do not touch the root POM for this — extra `-Xep` flags are not in scope for issue #1. Add:

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <compilerArgs>
                        <arg>-XDcompilePolicy=simple</arg>
                        <arg>--should-stop=ifError=FLOW</arg>
                        <arg>-Xplugin:ErrorProne -Xep:MissingOverride:ERROR</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
```

Run: `mvn -B -f .scratch/build-probe/pom.xml clean compile`

Expected: `BUILD SUCCESS` — the Lombok-generated `getName()` draws no finding.

Now confirm the check is genuinely armed, so that the pass above means something. Add `.scratch/build-probe/src/main/java/com/thedarkhorse/probe/Manual.java`:

```java
package com.thedarkhorse.probe;

public class Manual implements Named {

    private final String name;

    public Manual(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
```

Run: `mvn -B -f .scratch/build-probe/pom.xml clean compile`

Expected: `BUILD FAILURE` naming `Manual.java` and not `Person.java`:

```
[ERROR] .../Manual.java:[11,19] [MissingOverride] getName implements method in Named; expected @Override
```

That contrast is acceptance criterion 7. Remove the scaffolding used only for it:

```bash
rm .scratch/build-probe/src/main/java/com/thedarkhorse/probe/Manual.java
```

and delete the `<build>` block just added to `.scratch/build-probe/pom.xml`, so the probe is back on the inherited configuration for Task 3.

**Recorded observation, so nobody is surprised later:** in this session the Lombok-generated getter drew no finding *even before* `lombok.config` existed. Error Prone appears to skip Lombok's generated nodes because they carry no source position, not because of the `@lombok.Generated` annotation. `lombok.addLombokGeneratedAnnotation = true` is still created — the spec and the issue both require it, it is what JaCoCo and other tools key off, and it was confirmed not to break anything. Do not conclude from a green build that the file is doing the work.

- [ ] **Step 7: Confirm the committed build is still green**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`. The probe is not in `<modules>`, so the root build ignores it entirely.

- [ ] **Step 8: Commit**

Commit only the three real files. The probe stays untracked and is removed in Task 3.

```bash
git add pom.xml lombok.config .mvn/jvm.config
git status --short
```

Confirm `git status --short` lists exactly `M pom.xml`, `A lombok.config`, `A .mvn/jvm.config` as staged and shows `.scratch/` only as untracked.

```bash
git commit -m "$(cat <<'EOF'
build: run error prone as a compile-time gate

Refs #1
EOF
)"
```

---

## Task 3: Effective POM exposes `-parameters`, and a module produces a runnable jar

Covers acceptance criteria 4 (`mvn help:effective-pom` shows `<parameters>true</parameters>` and the processor path in order) and 5 (a module's `mvn package` produces a jar that runs with `java -jar`), and closes out criterion 1.

**Files:**
- Modify: `pom.xml` — add `<parameters>true</parameters>` to the `maven-compiler-plugin` `<configuration>`
- Modify then delete: `.scratch/build-probe/`

**Interfaces:**
- Consumes: from Task 2, the working Error Prone configuration and `.mvn/jvm.config`; from Task 1, the `spring-boot-maven-plugin` `pluginManagement` entry carrying the `repackage` execution.
- Produces: the final committed root POM. Every later module inherits `-parameters`, the four-entry processor path, Error Prone, and a `spring-boot-maven-plugin` it declares with no version and no configuration.

- [ ] **Step 1: Write the failing test**

Two checks, both straight from the acceptance list.

```bash
cat > .scratch/check-task3.sh <<'SH'
set -u
echo "--- <parameters> in effective pom (want: <parameters>true</parameters>) ---"
mvn -B -f .scratch/build-probe/pom.xml help:effective-pom \
  | grep -E '<parameters>' || echo "MISSING"
echo "--- processor path order (want: lombok, binding, mapstruct-processor, error_prone_core) ---"
mvn -B -f .scratch/build-probe/pom.xml help:effective-pom \
  | grep -E '<artifactId>(lombok|lombok-mapstruct-binding|mapstruct-processor|error_prone_core)</artifactId>'
SH
bash .scratch/check-task3.sh
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash .scratch/check-task3.sh`

Expected: the first check prints `MISSING`. Only the property `<maven.compiler.parameters>true</maven.compiler.parameters>` is in the effective POM; no `<parameters>` element exists in the compiler plugin's configuration. This is the falsified claim B from Verified Findings, reproduced as a red.

The second check already passes and prints the four artifact ids in order.

- [ ] **Step 3: Write the minimal implementation**

In the root `pom.xml`, add `<parameters>true</parameters>` as the first child of the `maven-compiler-plugin` `<configuration>`, immediately before `<annotationProcessorPaths>`:

```xml
                    <configuration>
                        <parameters>true</parameters>
                        <annotationProcessorPaths>
```

Leave `<maven.compiler.parameters>true</maven.compiler.parameters>` in `<properties>`; the spec requires it there and it is what actually drives javac.

- [ ] **Step 4: Run it to verify it passes**

Run: `bash .scratch/check-task3.sh`

Expected:

```
--- <parameters> in effective pom (want: <parameters>true</parameters>) ---
            <parameters>true</parameters>
--- processor path order (want: lombok, binding, mapstruct-processor, error_prone_core) ---
                <artifactId>lombok</artifactId>
                <artifactId>lombok-mapstruct-binding</artifactId>
                <artifactId>mapstruct-processor</artifactId>
                <artifactId>error_prone_core</artifactId>
```

Acceptance criterion 4 is met.

- [ ] **Step 5: Prove a module packages a runnable jar**

The probe currently has no `spring-boot-maven-plugin`. Package it as it stands:

```bash
mvn -B -f .scratch/build-probe/pom.xml clean package
java -jar .scratch/build-probe/target/build-probe-0.0.1-SNAPSHOT.jar
```

Expected: `no main manifest attribute, in .scratch/build-probe/target/build-probe-0.0.1-SNAPSHOT.jar`. That is the red — a plain jar, not a Spring Boot one.

Now add the plugin declaration to `.scratch/build-probe/pom.xml`, with no version and no configuration, exactly as every runnable module will:

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
```

```bash
mvn -B -f .scratch/build-probe/pom.xml clean package
```

Expected, confirming the inherited `repackage` execution fired without the module naming it:

```
[INFO] --- spring-boot:4.1.0:repackage (default) @ build-probe ---
[INFO] Replacing main artifact .../build-probe-0.0.1-SNAPSHOT.jar with repackaged archive,
       adding nested dependencies in BOOT-INF/.
```

```bash
java -jar .scratch/build-probe/target/build-probe-0.0.1-SNAPSHOT.jar --server.port=0
```

Expected: the Spring banner, then `Started ProbeApplication in N seconds`. Stop it with Ctrl-C. Acceptance criterion 5 is met.

This is the one step that was not executed while planning — the planning sandbox refused `java -jar`. If the jar does not start, the fault is in the root `pluginManagement` `repackage` execution, not in the probe.

- [ ] **Step 6: Delete the probe and confirm the committed build**

```bash
rm -rf .scratch
git status --short
mvn -B clean verify
```

Expected: `git status --short` shows only `M pom.xml`, with no `.scratch/` line and no untracked files. `mvn -B clean verify` prints `BUILD SUCCESS`, which is acceptance criterion 1.

Also confirm nothing but the four intended paths changed across the whole issue:

```bash
git diff --stat master...HEAD
```

Expected: `pom.xml`, `lombok.config`, `.mvn/jvm.config`, and the three deleted `src/` files. Nothing else.

- [ ] **Step 7: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
build: expose -parameters through the compiler plugin configuration

Refs #1
EOF
)"
```

---

## Acceptance criteria coverage

| # | Acceptance criterion from issue #1 | Task | Step |
| --- | --- | --- | --- |
| 1 | `mvn -q clean verify` succeeds from the repository root | 1, 3 | 1.4, 3.6 |
| 2 | No `@SpringBootApplication` class exists at the root | 1 | 1.2 red, 1.4 green |
| 3 | Root `pom.xml` is `<packaging>pom</packaging>`, no compile-scope dependencies, no `<parent>` | 1 | 1.2 red, 1.4 green |
| 4 | `mvn help:effective-pom` shows `<parameters>true</parameters>` and the processor path in order Lombok, binding, MapStruct, Error Prone | 3 | 3.2 red, 3.4 green |
| 5 | A module's `mvn package` produces a jar that runs with `java -jar` | 3 | 3.5 |
| 6 | A deliberate `SelfAssignment` fails `mvn verify`; reverting it passes | 2 | 2.5 |
| 7 | A Lombok-generated method produces no Error Prone finding | 2 | 2.6 |

Criterion 1 is written `mvn -q clean verify` in the issue; the tasks run `mvn -B clean verify`, which is the gate `CLAUDE.md` names and the one `.github/workflows/build.yml` runs. `-q` only changes log verbosity.

---

## Pull request

Title: `refactor(build): restructure to parent pom and flat modules (#1)`

Body ends with `Closes #1`. Merge with rebase, never squash, so the three per-cycle commits survive.

The body should carry the two falsified claims from Verified Findings, so that the reviewer is not left comparing the merged POM against an issue whose stated reasoning does not match what the tools do:

- Maven 3.9.16 binds `maven-compiler-plugin` 3.15.0, not 3.13.0, and 3.15.0 compiles against JDK 25. The 3.16.0 pin stays, for the standing rule that every plugin carries an explicit version.
- The `maven.compiler.parameters` property alone does not put `<parameters>true</parameters>` into the effective POM, so the element is also set explicitly in the compiler plugin configuration.
