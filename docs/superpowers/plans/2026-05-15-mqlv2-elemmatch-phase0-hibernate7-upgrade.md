# Phase 0: Hibernate 7.x Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `hibernate-orm` from `6.6.34.Final` to `7.3.4.Final`, fix all breaking changes, keep the full existing test suite green, and add one diagnostic test that confirms Hibernate 7 desugars plural-attribute joins to a `FunctionTableReference` of `unnest` (the AST shape Phases 2–4 depend on).

**Architecture:** This is a dependency-upgrade-only phase. No new features, no behavior changes to either the v1 or v2 translator beyond what is mechanically required to compile against 7.x APIs. The one new test is a *diagnostic* that locks in a design assumption — it does not exercise any new feature code.

**Tech Stack:** Gradle (Kotlin DSL) with a version catalogue at `gradle/libs.versions.toml`. Hibernate ORM, JUnit 5, AssertJ. Integration tests live under `src/integrationTest/`.

**Companion spec:** `docs/superpowers/specs/2026-05-15-mqlv2-elemmatch-via-unnest-design.md` (Phase 0 section).

---

## File map

Files this plan creates or modifies:

- **Modify:** `gradle/libs.versions.toml:21` — bump `hibernate-orm` version pin.
- **Modify:** `build.gradle.kts:71` — bump the Hibernate Javadoc link from `/orm/6.6/javadocs/` to `/orm/7.3/javadocs/`.
- **Modify:** `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — `appendJoins` method only. Replace the unconditional cast to `NamedTableReference` with a check that throws a descriptive `FeatureNotSupportedException` when the joined group's primary table reference is a `FunctionTableReference`. This is a strictly better error message; it does not introduce a new feature.
- **Modify (potentially many):** any file under `src/main/`, `src/test/`, `src/integrationTest/` that fails to compile or run against 7.3.4.Final. The exact set is discovery-driven — see Task 3.
- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java` — the one new diagnostic test.

---

## Task 1: Recon and confirm target version

**Goal:** Confirm Hibernate `7.3.4.Final` (the spec-named target) is actually the latest stable in the 7.x series at execution time. If a newer 7.x is stable, use that instead; the design says "latest stable 7.x."

**Files:** none changed yet.

- [ ] **Step 1: Check the Hibernate releases page**

Open https://hibernate.org/orm/releases/ in a browser. Confirm the latest stable in the 7.x series. The spec assumes `7.3.4.Final` but the engineer running this plan may find a newer 7.x.Final stable. Choose the latest stable 7.x.Final release. Do NOT pick a `.CR` or `.Beta`.

- [ ] **Step 2: Note the chosen version**

Record the chosen version locally (e.g., on paper or in a scratch note); subsequent tasks use it. The rest of this plan uses `7.3.4.Final` as the placeholder string; replace with the chosen version in every command below.

- [ ] **Step 3: Open the Hibernate 7 migration guide**

URL: https://docs.hibernate.org/orm/7.0/migration-guide/migration-guide.html (also linked from the 7.x release pages). Skim it once — you do not need to memorize it, but knowing the major categories of breaking change (SQM tree restructuring, removed deprecated APIs, function-descriptor signature changes) makes Task 3 faster.

---

## Task 2: Bump the Hibernate version pin

**Goal:** Single-line change to the version catalogue, plus the Javadoc link in `build.gradle.kts`.

**Files:**
- Modify: `gradle/libs.versions.toml:21`
- Modify: `build.gradle.kts:71`

- [ ] **Step 1: Update the version pin**

Edit `gradle/libs.versions.toml` line 21. Change:

```toml
hibernate-orm = "6.6.34.Final" # Remember to update javadoc links
```

to:

```toml
hibernate-orm = "7.3.4.Final" # Remember to update javadoc links
```

(replace `7.3.4.Final` with the version chosen in Task 1 if different).

- [ ] **Step 2: Update the Hibernate Javadoc link**

Edit `build.gradle.kts` line 71. Change:

```kotlin
"https://docs.hibernate.org/orm/6.6/javadocs/",
```

to:

```kotlin
"https://docs.hibernate.org/orm/7.3/javadocs/",
```

(use the major.minor matching the chosen version — for `7.3.4.Final`, use `7.3`).

- [ ] **Step 3: Refresh the Gradle dependency cache**

Run:

```
./gradlew --refresh-dependencies dependencies --configuration runtimeClasspath | head -50
```

Expected: among the listed dependencies, see `org.hibernate.orm:hibernate-core:7.3.4.Final` (or the version chosen). If you instead see a resolution error, the version string is wrong — fix it before continuing.

- [ ] **Step 4: Do NOT commit yet**

The next task will compile; the commit happens after compile and tests pass.

---

## Task 3: Fix compile errors in `src/main`

**Goal:** Make `./gradlew compileJava` succeed against 7.3.4.Final. This is a discovery-driven loop: run the compile, read each error, fix, repeat.

**Files:** any under `src/main/java/com/mongodb/hibernate/` that the compiler flags. Most fixes will land in `src/main/java/com/mongodb/hibernate/internal/translate/` (especially `AbstractMqlTranslator.java`, `Mqlv2SelectTranslator.java`).

- [ ] **Step 1: Run the main compile and capture errors**

Run:

```
./gradlew compileJava 2>&1 | tee /tmp/phase0-compile.log
```

Expected: the build will likely fail with multiple `error: …` lines. Read every error; the categories are predictable (see Step 2).

- [ ] **Step 2: Fix errors by category, one category at a time**

Common categories of 7.x breaking change (the Hibernate 7 migration guide is the canonical reference):

1. **Visitor signature changes** — `SqlAstWalker` and `SqlAstTranslator` visitor method signatures may have changed name or argument types. The translator declares many `visitX(...)` methods that throw `FeatureNotSupportedException`; many of these will need their signatures realigned. Fix by matching the new interface declaration verbatim.
2. **SQM tree / SQL AST node restructuring** — class moves, package renames, `getter`/setter renames on AST nodes. Common renames: any deprecated `…Impl` references; SQM types in `org.hibernate.query.sqm.*`.
3. **Function descriptor API** — `SelfRenderingFunctionSqlAstExpression`, `SqmFunctionDescriptor`, and related interfaces. The `MongoArrayContainsFunction` and similar dialect functions in `src/main/java/com/mongodb/hibernate/internal/dialect/function/` may need signature updates.
4. **Removed deprecated APIs** — any API marked `@Deprecated(forRemoval = true)` in 6.6 that has been deleted. Find the new replacement in the migration guide.

For each error, locate the new API in the migration guide or in the Hibernate 7.3 Javadocs (`https://docs.hibernate.org/orm/7.3/javadocs/`). Apply the fix mechanically — do not add features, do not improve error wording, do not refactor.

Rule for this task: **the diff for each fix should be the smallest possible change to compile against the new API.** If you find yourself "improving" something, stop and apply the minimal change instead.

- [ ] **Step 3: Repeat the compile loop until clean**

Re-run:

```
./gradlew compileJava 2>&1 | tee /tmp/phase0-compile.log
```

If errors remain, fix the next batch. Loop until:

```
BUILD SUCCESSFUL
```

- [ ] **Step 4: Do NOT commit yet**

Test compile and tests come next. Commit at the end of Task 5.

---

## Task 4: Fix compile errors in tests

**Goal:** Make `./gradlew compileTestJava compileIntegrationTestJava` succeed.

**Files:** any under `src/test/java/` or `src/integrationTest/java/` that the compiler flags.

- [ ] **Step 1: Compile unit-test sources**

Run:

```
./gradlew compileTestJava 2>&1 | tee /tmp/phase0-compile-test.log
```

If errors, fix using the same approach as Task 3 (minimal change, follow the migration guide). Repeat until clean.

- [ ] **Step 2: Compile integration-test sources**

Run:

```
./gradlew compileIntegrationTestJava 2>&1 | tee /tmp/phase0-compile-integration.log
```

If errors, fix using the same approach. Note: `hibernate-testing` (used by integration tests) may have its own breaking changes — typically around `SessionFactoryScope`, `ServiceRegistryScope`, `@DomainModel`, `@SessionFactory` annotations. Replacements are documented in the migration guide.

- [ ] **Step 3: Verify both compile cleanly**

Run:

```
./gradlew compileJava compileTestJava compileIntegrationTestJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Do NOT commit yet**

---

## Task 5: Run the existing test suite, fix any runtime failures

**Goal:** All unit and integration tests pass on 7.3.4.Final, with no test modifications other than what is mechanically required for API changes (e.g., annotation renames).

**Files:** any test under `src/test/` or `src/integrationTest/` that fails at runtime.

- [ ] **Step 1: Run unit tests**

Run:

```
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. If any test fails, inspect the failure. The fix should be a mechanical adaptation, not a test-logic change. Common patterns: dialect bootstrap differences, default settings differences, exception class hierarchy moves.

- [ ] **Step 2: Run integration tests**

Run:

```
./gradlew integrationTest
```

Expected: `BUILD SUCCESSFUL`. Same rules as Step 1.

The MQLv2-specific test classes (`Mqlv2SelectIntegrationTests`, `Mqlv2ShowcaseVerificationTests`, `Mqlv2JoinDemoTests`) and the v1 integration tests (`ArrayAndCollectionIntegrationTests`, `FunctionForArrayIntegrationTests`, etc.) all must pass. If any test asserts on a specific MQLv2 pipeline string and the assertion fails, that signals a behavior change introduced by the upgrade — investigate and fix the upgrade-side cause (do NOT mutate the expected string).

- [ ] **Step 3: Run the full check task**

Run:

```
./gradlew check
```

Expected: `BUILD SUCCESSFUL`. This runs compile + test + integrationTest + static analysis (Spotless, ErrorProne, NullAway). Address any static-analysis warnings that became errors under the new toolchain.

- [ ] **Step 4: Commit the upgrade**

Stage and commit:

```
git add gradle/libs.versions.toml build.gradle.kts src/main src/test src/integrationTest
git commit -m "$(cat <<'EOF'
build: upgrade hibernate-orm from 6.6.34.Final to 7.3.4.Final

Mechanical adaptation for Hibernate 7 API changes. No new features,
no translator behavior changes. All existing unit and integration
tests pass on the new version.
EOF
)"
```

(Replace `7.3.4.Final` with the version chosen in Task 1 if different.)

---

## Task 6: Improve `appendJoins` error wording for `FunctionTableReference`

**Goal:** Replace the unconditional `(NamedTableReference) ref` cast in `Mqlv2SelectTranslator.appendJoins` with a typed check that throws a descriptive `FeatureNotSupportedException` when the joined group's primary table reference is a `FunctionTableReference`. This is a *strictly better* error message on what was already an error path — no new feature, no behavior change beyond the message text. It also makes Task 7's diagnostic test possible to write.

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — method `appendJoins` (currently around line 336).

- [ ] **Step 1: Read the current `appendJoins` to confirm the cast site**

Open `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`. Find the `appendJoins` method. Confirm it starts with code equivalent to:

```java
for (var tgj : root.getTableGroupJoins()) {
    var joinedGroup = tgj.getJoinedGroup();
    var joinNtr = (NamedTableReference) joinedGroup.getPrimaryTableReference();
    ...
}
```

If the line numbers or exact code have shifted due to Task 3's edits, that is fine — the change is to the cast site.

- [ ] **Step 2: Make the change**

Use the `Edit` tool to replace:

```java
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var joinNtr = (NamedTableReference) joinedGroup.getPrimaryTableReference();
```

with:

```java
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var primaryRef = joinedGroup.getPrimaryTableReference();
            if (primaryRef instanceof FunctionTableReference ftr) {
                throw new FeatureNotSupportedException(
                        "Join target is a set-returning function (not supported in this phase): " + ftr);
            }
            var joinNtr = (NamedTableReference) primaryRef;
```

The class `FunctionTableReference` is already imported at line 86. `FeatureNotSupportedException` is already imported at line 23. No new imports needed.

- [ ] **Step 3: Verify compilation**

Run:

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full integration-test suite to verify no regression**

Run:

```
./gradlew integrationTest
```

Expected: `BUILD SUCCESSFUL`. The change is a no-op for any existing test (no existing test passes a `FunctionTableReference` as a join target; everything is `NamedTableReference`). If any test fails, investigate — the failure indicates this assumption is wrong.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: improve error wording when join target is a FunctionTableReference"
```

---

## Task 7: Add the diagnostic test for unnest AST shape

**Goal:** Add ONE new integration test that confirms Hibernate 7 desugars `from O o join o.array a` into a `FunctionTableReference` whose function descriptor identifies as `unnest`. This locks the load-bearing AST-shape assumption that Phases 2–4 depend on.

**Files:**
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java`

- [ ] **Step 1: Write the test class**

Create `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java` with the following content:

```java
/*
 * Copyright 2025-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hibernate.query.mqlv2;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.cfg.AvailableSettings.DIALECT;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Diagnostic-only tests that confirm the AST-shape assumptions Phases 2–4 depend on.
 * These tests run an HQL plural-attribute-join query and assert that the failure
 * thrown by the v2 translator confirms the join target is a FunctionTableReference
 * whose toString includes "unnest" — proving Hibernate 7 desugars
 * `join o.array a` into `lateral unnest(o.array) a`.
 *
 * These tests have no value beyond locking in the design assumption. They will
 * be removed (or replaced with positive assertions) once Phase 3 implements
 * unwind translation.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestAstDiagnosticTests.Item.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(settings = @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"))
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestAstDiagnosticTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @Test
    void pluralAttributeJoinDesugarsToUnnest() {
        assertThatThrownBy(() -> sessionFactoryScope.inSession(session ->
                        session.createQuery("from Item i join i.tags t", Item.class).getResultList()))
                .hasCauseInstanceOf(FeatureNotSupportedException.class)
                .rootCause()
                .hasMessageContaining("FunctionTableReference")
                .hasMessageContaining("unnest");
    }

    @Entity(name = "Item")
    @Table(name = "items")
    static class Item {
        @Id
        int id;

        int[] tags;

        Item() {}
    }
}
```

- [ ] **Step 2: Run the new test**

Run:

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestAstDiagnosticTests"
```

**Expected:** PASS. The chain of expectations is:
1. The HQL parses cleanly under Hibernate 7 (the plural-attribute join is valid syntax).
2. Hibernate 7 desugars the join to a `lateral unnest(i.tags) t` and produces a `TableGroupJoin` whose joined group's primary table reference is a `FunctionTableReference`.
3. The v2 translator's `appendJoins` hits the `FunctionTableReference` branch added in Task 6 and throws `FeatureNotSupportedException` with a message containing both `"FunctionTableReference"` and `"unnest"` (the second comes from the `FunctionTableReference.toString()`, which renders the function name).

If the test fails because:
- The exception is not thrown at all → Hibernate 7 is processing this query in some other way; investigate the actual AST shape before continuing with the rest of this design.
- The exception is thrown but the message does NOT contain `"unnest"` → the `FunctionTableReference.toString()` does not include the function name in 7.3.4.Final; adjust the test assertion to use a different inspection (e.g., reflectively access the function descriptor) and document the finding.
- The exception is a `ClassCastException` → the change in Task 6 was not applied or has been undone.

**Critically:** if this test fails for an *unexpected* reason, do not "fix" it by weakening the assertion. The test is a diagnostic. A surprising failure is information — surface it and rethink before proceeding to Phases 1-4.

- [ ] **Step 3: Commit the diagnostic test**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java
git commit -m "$(cat <<'EOF'
test: lock in unnest AST-shape assumption for elemMatch design

Confirms Hibernate 7 desugars plural-attribute joins to a
FunctionTableReference of unnest, which Phases 2-4 of the elemMatch
design depend on. This test will be removed or rewritten as a positive
assertion once Phase 3 lands.
EOF
)"
```

---

## Task 8: Final verification

**Goal:** Confirm the entire repository is healthy on the new Hibernate version with the diagnostic test in place.

**Files:** none changed.

- [ ] **Step 1: Run the full check task one more time**

Run:

```
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`. This is the gate. If anything fails here, investigate before declaring Phase 0 complete.

- [ ] **Step 2: Verify git status is clean and commits are stacked correctly**

Run:

```
git status
git log --oneline -10
```

Expected:
- `git status` reports the working tree is clean.
- `git log` shows (most recent first) something like:
  ```
  <sha> test: lock in unnest AST-shape assumption for elemMatch design
  <sha> MQLv2: improve error wording when join target is a FunctionTableReference
  <sha> build: upgrade hibernate-orm from 6.6.34.Final to 7.3.4.Final
  ```
  plus any incremental commits from Tasks 3-5 if the engineer chose to commit those iteratively (they may have been squashed into the single upgrade commit).

- [ ] **Step 3: Push / open PR**

Out of scope for this plan. The user reviews the work in-session before any push.

---

## Definition of done

- `./gradlew check` succeeds on `7.3.4.Final` (or the chosen latest stable 7.x).
- All pre-existing tests pass with no logic changes.
- `Mqlv2UnnestAstDiagnosticTests.pluralAttributeJoinDesugarsToUnnest` passes.
- The translator codebase contains no new features. Only mechanical API adaptations + the improved error message in `appendJoins`.
- The commit history is clean — distinct commits for the upgrade, the error-message refinement, and the diagnostic test.

## What this plan deliberately does not do

- Add any unnest translation logic. Phase 3 owns that.
- Add any new HQL surface. Phases 2-4 own that.
- Refactor v1 translator behavior. Phase 0 is purely a compatibility update.
- Touch `@ElementCollection` support. Continues to throw at SessionFactory build per pre-existing behavior.

## Risks and how this plan addresses them

- **The upgrade reveals a behavior change that no test catches.** Mitigation: Task 5 runs the full `check` task, which includes static analysis. Beyond that, there's no defense — the codebase's test coverage is its own assurance level.
- **`FunctionTableReference.toString()` does not include the function name.** Mitigation: Task 7 Step 2 documents the alternative inspection path (reflective access to the function descriptor).
- **A subtler AST shape than `FunctionTableReference` is what Hibernate 7 actually produces.** Mitigation: Task 7 Step 2 explicitly says "do not weaken the assertion" — investigate and bring findings back to the design before proceeding to Phase 1.
