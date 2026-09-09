# Repository Restructure to Parent POM and Flat Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the repository root from a single Spring Boot application into a pure Maven parent POM that every future flat module inherits its dependency management, compiler settings and static analysis from.

**Architecture:** The root POM drops its `spring-boot-starter-parent` parent and imports Spring Boot and Spring Cloud as BOMs instead, which keeps the parent slot free and inherits no plugin configuration. Because nothing is inherited, the root declares its own `pluginManagement` for `maven-compiler-plugin` and `spring-boot-maven-plugin`, and every compiler setting the Boot parent used to supply is written out by hand. Error Prone runs as a javac plugin on the same `annotationProcessorPaths` as Lombok and MapStruct, so a finding fails `mvn verify` rather than producing a report.

**Tech Stack:** Maven 3.9.16, Java 25, Spring Boot 4.1.0 (BOM), Spring Cloud 2025.1.2 (BOM), MapStruct 1.6.3, Lombok (version supplied by the Boot BOM), Error Prone 2.50.0, `maven-compiler-plugin` 3.16.0.

**Spec:** `docs/superpowers/specs/2026-09-05-ecommerce-architecture-design.md` (sections 3, 6 and 7). Issue #1 is the specification for this increment.

## Global Constraints

- Root POM has no `<parent>`. Spring Boot and Spring Cloud are imported as BOMs.
- `<groupId>com.thedarkhorse</groupId>`, `<artifactId>ecommerce</artifactId>`, `<version>0.0.1-SNAPSHOT</version>`, `<packaging>pom</packaging>`.
- Properties, exactly these values: `maven.compiler.release` = `25`, `maven.compiler.parameters` = `true`, `project.build.sourceEncoding` = `UTF-8`, `spring-boot.version` = `4.1.0`, `spring-cloud.version` = `2025.1.2`, `mapstruct.version` = `1.6.3`, `error-prone.version` = `2.50.0`.
- Lombok's version is not pinned; it is supplied by the Spring Boot BOM.
- Every plugin carries an explicit version, because a BOM import supplies `dependencyManagement` only and no `pluginManagement` is inherited.
- `maven-compiler-plugin` is pinned at 3.16.0. Maven 3.9.16 otherwise binds 3.13.0, which cannot compile against JDK 25.
- Annotation processor path order is Lombok, `lombok-mapstruct-binding`, `mapstruct-processor`, `error_prone_core`. Setting that path disables processor discovery on the classpath, so anything absent from it stops running silently.
- Error Prone compiler arguments are exactly `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW`, `-Xplugin:ErrorProne`. No `--add-exports` flags: they apply only to a forked compiler or a toolchain, and neither is used here.
- Root `lombok.config` sets `lombok.addLombokGeneratedAnnotation = true`.
- No comments anywhere. Not in Java, not in XML, not in YAML, SQL, properties files or shell scripts. The existing `<!-- lookup parent from repository -->` in `pom.xml` goes away with the `<parent>` block and is not replaced.
- Build only what the issue asks for. No module, no helper file, no configuration knob that issue #1 did not request.
- `mvn -B clean verify` from the repository root is the only gate.
- Commits are Conventional Commits with a `Refs #1` footer. One commit per completed red-green cycle, so every commit is green. No `Co-Authored-By` trailer, no "Generated with Claude Code" footer.

## Scope Decision: No Module Is Committed

The issue's Scope says **"No service module is added here"**, and issue #10 (`discovery-server`), #11 (`config-server`), #12 (`gateway`) and #14 (`catalog-service`) each own the creation of their own module and each declares `Depends on: #1`. So this increment commits zero modules and the root `<modules>` list ships empty; the next issue in the build order adds the first entry.

Four of the seven acceptance criteria — the effective-pom check, the `java -jar` check, the Error Prone violation and the Lombok exemption — can only be observed from inside a module. Two of those four are explicitly transient ("a deliberate Error Prone violation … reverting it makes the build pass"). All four are therefore treated the same way: they are **demonstrations run against a temporary scaffold module that is never committed**, not durable artifacts.

The scaffold lives at `scratch-module/` and declares the root as its parent via `<relativePath>../pom.xml</relativePath>`, but it is deliberately **not** listed in the root `<modules>`. Maven is happy with that: a child POM may name a parent it is not a module of, and it still inherits the root's `pluginManagement`. Consequences:

- Root `mvn -B clean verify` never sees the scaffold, so the committed build stays exactly what the issue asked for.
- Scaffold builds are invoked with `mvn -f scratch-module/pom.xml …`. The acceptance criterion "a deliberate Error Prone violation fails `mvn verify`" is satisfied by that invocation, because the thing being proven is that the root's inherited `pluginManagement` wires Error Prone into a child's compilation.
- Every `git add` in this plan names explicit paths. `scratch-module/` is never staged, and Task 3 deletes it and asserts `git status --porcelain` is empty.

If the issue author intended a durable module instead, the correction is cheap: keep `scratch-module/` under its real name and add the `<modules>` entry. Raise it on the issue rather than guessing.

## Flagged, Not In Scope

`maven-surefire-plugin` is not pinned by this plan, because issue #1 does not mention it and the standing scope rule forbids building what nobody asked for. After Task 1 the reactor contains no Java and no tests, so surefire never runs and its default version cannot bite. The first module that ships a test — issue #10 — will need surefire pinned explicitly in root `pluginManagement`, for the same reason `maven-compiler-plugin` is pinned: no `pluginManagement` is inherited from a BOM. Note it there.

## File Structure

**Modified**

- `pom.xml` — the whole deliverable. Becomes a pure parent: coordinates, `packaging`, an empty `<modules>` list, properties, `dependencyManagement` with two BOM imports plus MapStruct, and `pluginManagement` for two plugins. No `<parent>`, no `<dependencies>`, no `<build><plugins>`.

**Created**

- `lombok.config` — one line, at the repository root, so it bubbles up from every module's sources.

**Deleted**

- `src/main/java/com/thedarkhorse/ecommerce/EcommerceApplication.java`
- `src/test/java/com/thedarkhorse/ecommerce/EcommerceApplicationTests.java`
- `src/main/resources/application.yaml`
- the now-empty `src/` tree

**Temporary, never committed**

- `scratch-module/pom.xml`
- `scratch-module/src/main/java/com/thedarkhorse/scratch/Scratch.java`
- `scratch-module/src/main/java/com/thedarkhorse/scratch/Suppressed.java`
- `scratch-module/src/main/java/com/thedarkhorse/scratch/Violation.java`
- `scratch-module/src/main/java/com/thedarkhorse/scratch/ScratchApplication.java`

## Testing Approach

Nothing in this increment is Java code with behaviour. There is no branch, no loop, no validation, no arithmetic and no HTTP contract, so the standing TDD rule produces **no JUnit tests** here — writing one would be testing Maven, which is testing the framework.

The red-green cycle still applies. The failing test for a build change is a **command with a stated expected failure**: run it, read the output, confirm it says the thing the change is supposed to fix, then make the change and confirm the output flips. Every task below opens with such a command. Do not skip the red run; a green-from-the-start check proves nothing about the change that follows it.

The `mvn help:*` and `mvn dependency:*` invocations are command-line diagnostics only. Never add those plugins to a POM.

## Acceptance Criteria Coverage

| # | Acceptance criterion from issue #1 | Task |
| --- | --- | --- |
| 1 | `mvn -q clean verify` succeeds from the repository root | Task 1 step 6, re-asserted Task 2 step 12 and Task 3 step 10 |
| 2 | No `@SpringBootApplication` class exists at the root | Task 1 steps 2 and 5 |
| 3 | Root `pom.xml` is `<packaging>pom</packaging>` with no compile-scope dependencies and no `<parent>` | Task 1 steps 1, 3 and 5 |
| 4 | `mvn help:effective-pom` on a module shows `<parameters>true</parameters>` and the processor path in the order Lombok, binding, MapStruct, Error Prone | Task 2 steps 3 and 7 |
| 5 | A module's `mvn package` produces a jar that runs with `java -jar` | Task 3 steps 2, 3, 6 and 7 |
| 6 | A deliberate Error Prone violation fails `mvn verify`, and reverting it makes the build pass | Task 2 steps 8 and 9 |
| 7 | A Lombok-generated method produces no Error Prone finding | Task 2 steps 10 and 11 |

---

### Task 1: Root POM becomes a pure parent

Strip the root of its Spring Boot identity. After this task the repository builds and contains no Java at all.

**Files:**
- Modify: `pom.xml` (replaced wholesale)
- Delete: `src/main/java/com/thedarkhorse/ecommerce/EcommerceApplication.java`
- Delete: `src/test/java/com/thedarkhorse/ecommerce/EcommerceApplicationTests.java`
- Delete: `src/main/resources/application.yaml`
- Test: none — build commands only, see Testing Approach

**Interfaces:**
- Consumes: nothing. This is the first task.
- Produces: a root POM at `com.thedarkhorse:ecommerce:0.0.1-SNAPSHOT`, `packaging` `pom`, no `<parent>`, an empty `<modules/>` element, the seven properties named in Global Constraints, and `dependencyManagement` importing `spring-boot-dependencies` 4.1.0 and `spring-cloud-dependencies` 2025.1.2 plus managing `org.mapstruct:mapstruct` at 1.6.3. Task 2 adds `<build><pluginManagement>` to this same file. Task 2 and Task 3 rely on `${spring-boot.version}`, `${mapstruct.version}`, `${error-prone.version}` and `${maven.compiler.parameters}` being defined here.

- [ ] **Step 1: Run the failing check for packaging and parent**

```bash
mvn -B -q help:evaluate -Dexpression=project.packaging -DforceStdout
mvn -B -q help:evaluate -Dexpression=project.parent -DforceStdout
```

Expected now, both wrong:
- first prints `jar`, must become `pom`
- second prints `org.springframework.boot:spring-boot-starter-parent:pom:4.1.0`, must become `null object or invalid expression`

- [ ] **Step 2: Run the failing check for the root application class**

```bash
grep -rn "@SpringBootApplication" --include=*.java .
```

Expected: prints `./src/main/java/com/thedarkhorse/ecommerce/EcommerceApplication.java:6:@SpringBootApplication` and exits 0. It must exit 1 with no output.

- [ ] **Step 3: Run the failing check for compile-scope dependencies**

```bash
mvn -B dependency:list -DincludeScope=compile
```

Expected: the resolved list names `spring-boot-starter-data-jpa`, `spring-boot-starter-webmvc`, `spring-boot-starter-security`, `spring-boot-starter-validation`, `spring-boot-starter-flyway` and their transitives. It must end up empty (`none`).

- [ ] **Step 4: Delete the root application**

```bash
git rm -r src
```

`src/` holds only the three files listed above, so the whole tree goes.

- [ ] **Step 5: Replace `pom.xml` wholesale**

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

</project>
```

`<modules/>` is deliberately empty: see Scope Decision. There is no `<dependencies>` block, no Lombok entry in `dependencyManagement` (the Boot BOM supplies its version), and no `<build>` section yet — Task 2 adds that.

- [ ] **Step 6: Re-run every check from steps 1 to 3, plus the gate**

```bash
mvn -B -q help:evaluate -Dexpression=project.packaging -DforceStdout
mvn -B -q help:evaluate -Dexpression=project.parent -DforceStdout
grep -rn "@SpringBootApplication" --include=*.java . ; echo "grep exit=$?"
mvn -B dependency:list -DincludeScope=compile
mvn -B clean verify
```

Expected:
- `pom`
- `null object or invalid expression`
- no output, `grep exit=1`
- resolved list is empty (`none`)
- `BUILD SUCCESS`

The last command is acceptance criterion 1. The issue writes it `mvn -q clean verify`; `-B` is the standing gate and differs only in log formatting.

- [ ] **Step 7: Commit**

`git rm` in step 4 already staged the deletions, so only `pom.xml` needs adding.

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
build(root): make the root pom a pure parent

Drop spring-boot-starter-parent and import Spring Boot and Spring Cloud
as BOMs, so the parent slot stays free and no plugin configuration is
inherited. Delete the root Spring Boot application, which was never a
service.

Refs #1
EOF
)"
```

---

### Task 2: Compiler plugin management, Error Prone and the Lombok exemption

Give the root the `maven-compiler-plugin` configuration that `spring-boot-starter-parent` used to supply, add Error Prone to the same processor path, and prove all of it from inside a child module.

**Files:**
- Modify: `pom.xml` — add a `<build><pluginManagement>` block containing `maven-compiler-plugin`
- Create: `lombok.config`
- Create, never committed: `scratch-module/pom.xml`
- Create, never committed: `scratch-module/src/main/java/com/thedarkhorse/scratch/Scratch.java`
- Create, never committed: `scratch-module/src/main/java/com/thedarkhorse/scratch/Suppressed.java`
- Create, never committed: `scratch-module/src/main/java/com/thedarkhorse/scratch/Violation.java`
- Test: none — build commands only, see Testing Approach

**Interfaces:**
- Consumes: the root POM produced by Task 1, specifically the properties `${maven.compiler.parameters}`, `${mapstruct.version}` and `${error-prone.version}`, and the `spring-boot-dependencies` import that supplies Lombok's version.
- Produces: root `pluginManagement` for `org.apache.maven.plugins:maven-compiler-plugin:3.16.0`, configured with `<parameters>${maven.compiler.parameters}</parameters>`, a four-entry `annotationProcessorPaths`, and three `compilerArgs`. Because `maven-compiler-plugin` is bound to the default lifecycle, a child module inherits this without declaring the plugin at all. Task 3 appends a second `<plugin>` to the same `<pluginManagement><plugins>` list and reuses `scratch-module/` as left by this task.

- [ ] **Step 1: Create the scaffold module**

`scratch-module/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.thedarkhorse</groupId>
        <artifactId>ecommerce</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>scratch-module</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

</project>
```

`scratch-module/src/main/java/com/thedarkhorse/scratch/Scratch.java`:

```java
package com.thedarkhorse.scratch;

import lombok.Data;

@Data
public class Scratch {

    private String name;

    private int quantity;

}
```

Do not add `scratch-module` to the root `<modules>`, and do not stage it.

- [ ] **Step 2: Run the failing check for `<parameters>` and the processor path**

```bash
mvn -B -f scratch-module/pom.xml help:effective-pom -Doutput=/tmp/effective-pom.xml
grep -n "<parameters>true</parameters>" /tmp/effective-pom.xml
sed -n '/<annotationProcessorPaths>/,/<\/annotationProcessorPaths>/p' /tmp/effective-pom.xml
```

Expected: both the grep and the sed print nothing. The root has no `pluginManagement` yet, `<parameters>` exists only as the property `maven.compiler.parameters` (help:effective-pom prints POM content, not plugin parameter defaults sourced from the plugin descriptor), and there is no processor path at all. This is acceptance criterion 4 failing.

Do not grep the whole file for `<artifactId>lombok</artifactId>`: the scaffold declares Lombok as a dependency, so that string appears in `<dependencies>` as well and the match count stops meaning anything. The `sed` range isolates the processor path, which is the thing the criterion is about.

- [ ] **Step 3: Add `pluginManagement` for `maven-compiler-plugin`**

Insert this `<build>` block into `pom.xml` immediately after the closing `</dependencyManagement>` tag:

```xml
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.16.0</version>
                    <configuration>
                        <parameters>${maven.compiler.parameters}</parameters>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
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
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
```

Four things here are deliberate and must not be "tidied":

- `<parameters>` is written out even though the property alone would work, because acceptance criterion 4 reads the effective POM, and a plugin parameter default sourced from the plugin descriptor never appears there.
- The Lombok path carries no `<version>`. `maven-compiler-plugin` 3.12.0 and later resolve a processor path version from `dependencyManagement`, and the Boot BOM manages Lombok. This is what "Lombok's version is not pinned" means.
- The MapStruct path is `mapstruct-processor`, not `mapstruct`. `dependencyManagement` manages `mapstruct`, the API artifact, so the processor needs `${mapstruct.version}` explicitly.
- `lombok-mapstruct-binding` 0.2.0 is inline because no BOM manages it and the issue's property list is fixed. If a property is preferred later, it is a one-line change.

- [ ] **Step 4: Create the root `lombok.config`**

`lombok.config`:

```
lombok.addLombokGeneratedAnnotation = true
```

One line, no comment. It sits at the repository root so that Lombok's upward search from any module's sources finds it.

- [ ] **Step 5: Run the check from step 2 again**

```bash
mvn -B -f scratch-module/pom.xml help:effective-pom -Doutput=/tmp/effective-pom.xml
grep -n "<parameters>true</parameters>" /tmp/effective-pom.xml
sed -n '/<annotationProcessorPaths>/,/<\/annotationProcessorPaths>/p' /tmp/effective-pom.xml
```

Expected: the grep prints one line, `<parameters>true</parameters>`, interpolated from the property. The sed prints the processor path block containing exactly four `<path>` elements, in this order:

1. `org.projectlombok:lombok`, with no `<version>`
2. `org.projectlombok:lombok-mapstruct-binding`, version `0.2.0`
3. `org.mapstruct:mapstruct-processor`, version `1.6.3`
4. `com.google.errorprone:error_prone_core`, version `2.50.0`

Read the printed order rather than assuming it. That order is the acceptance criterion.

- [ ] **Step 6: Confirm the scaffold compiles and Lombok ran**

```bash
mvn -B -f scratch-module/pom.xml clean compile
javap -p -v -cp scratch-module/target/classes com.thedarkhorse.scratch.Scratch | grep -c "Llombok/Generated;"
```

Expected: `BUILD SUCCESS`, then a count greater than zero. `lombok.Generated` has CLASS retention, so it lands in `RuntimeInvisibleAnnotations` and `javap -v` prints it. A count of zero means `lombok.config` was not picked up — fix that before continuing, because acceptance criterion 7 is meaningless without it.

- [ ] **Step 7: Record acceptance criterion 4 as met**

Steps 2 and 5 are the red and green halves of criterion 4. Nothing further to run.

- [ ] **Step 8: Prove the Error Prone gate — the red half of criterion 6**

Create `scratch-module/src/main/java/com/thedarkhorse/scratch/Violation.java`:

```java
package com.thedarkhorse.scratch;

public class Violation {

    private int value;

    public void reset() {
        value = value;
    }

}
```

Run:

```bash
mvn -B -f scratch-module/pom.xml clean verify
```

Expected: `BUILD FAILURE`, with a diagnostic naming `Violation.java` and the check `[SelfAssignment]`, worded like `Variable assigned to itself`. `SelfAssignment` is ERROR severity by default, which is what turns a finding into a failed build.

If the build succeeds instead, Error Prone is not running. Check `-Xplugin:ErrorProne` reached javac and that `error_prone_core` is on the processor path — do not proceed by weakening the configuration.

- [ ] **Step 9: Revert the violation — the green half of criterion 6**

```bash
rm scratch-module/src/main/java/com/thedarkhorse/scratch/Violation.java
mvn -B -f scratch-module/pom.xml clean verify
```

Expected: `BUILD SUCCESS`. Criterion 6 is now met in both directions.

- [ ] **Step 10: Prove the Lombok exemption — criterion 7**

The compile in step 6 already produced no Error Prone diagnostic for `Scratch.java`, and step 8 proved Error Prone is live in this module, so absence of a finding is meaningful rather than vacuous. Confirm the absence explicitly:

```bash
mvn -B -f scratch-module/pom.xml clean compile | tee /tmp/scratch-compile.log
grep -n "Scratch.java" /tmp/scratch-compile.log ; echo "grep exit=$?"
```

Expected: `BUILD SUCCESS` in the log, then no grep output and `grep exit=1`. Keep the build and the grep as two commands: piping `mvn` straight into `grep` hands you grep's exit status and hides a failed build.

- [ ] **Step 11: Prove the suppression mechanism, not just the absence**

Create `scratch-module/src/main/java/com/thedarkhorse/scratch/Suppressed.java`:

```java
package com.thedarkhorse.scratch;

public class Suppressed {

    private int value;

    @lombok.Generated
    public void reset() {
        value = value;
    }

}
```

Run:

```bash
mvn -B -f scratch-module/pom.xml clean compile
```

Expected: `BUILD SUCCESS`. This is the same self-assignment that failed the build in step 8; the only difference is `@lombok.Generated`, which is exactly what `lombok.addLombokGeneratedAnnotation = true` puts on every method Lombok writes. Error Prone skips members annotated `Generated`, so it never inspects code nobody wrote.

If this build fails with `[SelfAssignment]`, Error Prone is not honouring `lombok.Generated` in this version pairing. That is a real finding against the spec's line 426, not something to route around: report it on issue #1 and stop. Do not add `-Xep:SelfAssignment:OFF` or any other blanket suppression.

Then remove the file:

```bash
rm scratch-module/src/main/java/com/thedarkhorse/scratch/Suppressed.java
```

- [ ] **Step 12: Confirm the root gate is still green**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`. The root reactor still has no modules, so this proves the new `pluginManagement` did not break the parent build.

- [ ] **Step 13: Commit — root files only**

```bash
git status --porcelain
```

Expected: `M pom.xml`, `?? lombok.config`, `?? scratch-module/`. Stage the first two by name and leave the scaffold alone.

```bash
git add pom.xml lombok.config
git commit -m "$(cat <<'EOF'
build(root): manage the compiler plugin with Error Prone and Lombok

Pin maven-compiler-plugin at 3.16.0, because Maven 3.9.16 otherwise binds
3.13.0 and that cannot compile against JDK 25. Set -parameters explicitly,
since no Boot parent does it and springdoc needs record component names.
Error Prone joins the annotation processor path rather than the classpath,
because setting that path turns classpath discovery off. lombok.config
marks generated methods so Error Prone skips them.

Refs #1
EOF
)"
```

---

### Task 3: `spring-boot-maven-plugin` management and the runnable jar

Give runnable modules a `repackage` execution they inherit without configuring, prove a child produces a jar that `java -jar` starts, then remove the scaffold.

**Files:**
- Modify: `pom.xml` — add `spring-boot-maven-plugin` to the existing `<pluginManagement><plugins>` list
- Modify, never committed: `scratch-module/pom.xml`
- Create, never committed: `scratch-module/src/main/java/com/thedarkhorse/scratch/ScratchApplication.java`
- Delete: the whole `scratch-module/` tree
- Test: none — build commands only, see Testing Approach

**Interfaces:**
- Consumes: the `<build><pluginManagement><plugins>` element added in Task 2, and `${spring-boot.version}` from Task 1.
- Produces: root `pluginManagement` for `org.springframework.boot:spring-boot-maven-plugin:${spring-boot.version}` carrying a single `repackage` goal execution. From here on, a runnable module declares `spring-boot-maven-plugin` with no `<version>` and no `<configuration>`; the root itself declares it in neither `<plugins>` nor its own build, so the parent never repackages. Issues #10, #11, #12 and #14 depend on exactly this.

- [ ] **Step 1: Make the scaffold runnable**

Add to `scratch-module/pom.xml`, inside the existing `<dependencies>`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
```

Add a `<build>` block to `scratch-module/pom.xml`, after `</dependencies>`:

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

No version and no configuration on that declaration. That is the whole point of the task.

Create `scratch-module/src/main/java/com/thedarkhorse/scratch/ScratchApplication.java`:

```java
package com.thedarkhorse.scratch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ScratchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScratchApplication.class, args);
    }

}
```

- [ ] **Step 2: Run the failing check for the runnable jar**

```bash
mvn -B -f scratch-module/pom.xml clean package
```

Expected: `BUILD FAILURE`, reporting that `spring-boot-maven-plugin` has no version — `'build.plugins.plugin.version' for org.springframework.boot:spring-boot-maven-plugin is missing`. The root manages `maven-compiler-plugin` only so far.

- [ ] **Step 3: Add `spring-boot-maven-plugin` to root `pluginManagement`**

Insert this `<plugin>` into `pom.xml` as the first child of the existing `<pluginManagement><plugins>` element, before `maven-compiler-plugin`:

```xml
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
```

The execution carries no `<id>`, so it takes Maven's `default`. The root gets no `<build><plugins>` entry for this plugin: management only.

- [ ] **Step 4: Run the packaging check again**

```bash
mvn -B -f scratch-module/pom.xml clean package
```

Expected: `BUILD SUCCESS`, with a `spring-boot-maven-plugin:${spring-boot.version}:repackage` line in the log.

- [ ] **Step 5: Confirm the jar was repackaged, not left thin**

```bash
unzip -l scratch-module/target/scratch-module-0.0.1-SNAPSHOT.jar | grep -cE "BOOT-INF/(classes|lib)/"
```

Expected: a count greater than zero. A plain jar with no `BOOT-INF/` means `repackage` did not run and `java -jar` would fail on a missing main class.

- [ ] **Step 6: Run the jar**

```bash
java -jar scratch-module/target/scratch-module-0.0.1-SNAPSHOT.jar ; echo "exit=$?"
```

Expected: the Spring Boot banner, a `Started ScratchApplication` line, then `exit=0`. `spring-boot-starter` has no web server, so the context closes and the process ends on its own.

- [ ] **Step 7: Record acceptance criterion 5 as met**

Steps 2 and 4 are the red and green halves; steps 5 and 6 are the criterion itself.

- [ ] **Step 8: Delete the scaffold**

```bash
rm -rf scratch-module
```

- [ ] **Step 9: Confirm nothing of the scaffold survives**

```bash
git status --porcelain
```

Expected: `M pom.xml` and nothing else. No `?? scratch-module/`, no stray `target/`.

```bash
grep -rn "scratch" pom.xml lombok.config ; echo "grep exit=$?"
```

Expected: no output, `grep exit=1`. The scaffold must leave no trace in a committed file.

- [ ] **Step 10: Run the gate**

```bash
mvn -B clean verify
```

Expected: `BUILD SUCCESS`. This is acceptance criterion 1 in its final state.

- [ ] **Step 11: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
build(root): manage spring-boot-maven-plugin with its repackage execution

Runnable modules declare the plugin with no version and no configuration;
the root declares it in pluginManagement only, so the parent never
repackages itself.

Refs #1
EOF
)"
```

---

## Final State

`pom.xml`, in full, as it stands after Task 3. Use this to check the three commits landed what they should:

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
                        <parameters>${maven.compiler.parameters}</parameters>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
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
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>

</project>
```

`lombok.config`, in full:

```
lombok.addLombokGeneratedAnnotation = true
```

Tracked files at the end of this increment: `pom.xml`, `lombok.config`, `.gitignore`, `.gitattributes`, `CLAUDE.md`, `README.md`, `LICENSE`, `.github/**`, `docs/**`. No `src/`, no module directory.

## Pull Request

Title: `build(root): repository restructure to parent POM and flat modules (#1)`

Body ends with `Closes #1`. Merge with rebase so the three per-cycle commits survive.
