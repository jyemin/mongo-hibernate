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
- **Modify (potentially many):** any file under `src/main/`, `src/test/`, `src/integrationTest/` that fails to compile or run against 7.3.4.Final. The exact set is discovery-driven — see Task 3.
- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2TranslatorFactory.java` — test-only capture wrapper around the production `Mqlv2TranslatorFactory`. Stashes each `SelectStatement` passed to `buildSelectTranslator(...)` into a thread-local for inspection by tests.
- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2Dialect.java` — test-only dialect that returns the capturing factory. Installed via `@Setting(name = DIALECT, …)` on the diagnostic test class.
- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java` — the one new diagnostic test. No production-code changes.

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

## Task 6: Add the diagnostic test for unnest AST shape

**Goal:** Add ONE new integration test that confirms Hibernate 7 desugars `from O o join o.array a` into a `FunctionTableReference` whose function descriptor identifies as `unnest`. This locks the load-bearing AST-shape assumption that Phases 2–4 depend on. **No production-code changes.** The diagnostic uses a test-only capture wrapper around the production translator factory, so the assertion is positive (about what the AST IS) rather than dependent on a thrown error message.

**Files:**
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2TranslatorFactory.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2Dialect.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java`

- [ ] **Step 1: Write the capturing translator factory**

Create `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2TranslatorFactory.java`:

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

import com.mongodb.hibernate.internal.translate.Mqlv2TranslatorFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.exec.spi.JdbcOperationQueryMutation;
import org.hibernate.sql.exec.spi.JdbcOperationQuerySelect;
import org.hibernate.sql.model.ast.TableMutation;
import org.hibernate.sql.model.jdbc.JdbcMutationOperation;

/**
 * Test-only wrapper that captures every {@link SelectStatement} passed through
 * {@link Mqlv2TranslatorFactory#buildSelectTranslator}, exposing the most recently
 * captured statement via a thread-local. Mutation translation is delegated unchanged.
 *
 * <p>Used by diagnostic tests that need to inspect the SQL AST shape Hibernate produces
 * for a given HQL query, without modifying production code.
 */
public final class CapturingMqlv2TranslatorFactory implements SqlAstTranslatorFactory {

    private static final ThreadLocal<SelectStatement> LAST_CAPTURED = new ThreadLocal<>();

    private final Mqlv2TranslatorFactory delegate = new Mqlv2TranslatorFactory();

    /** Returns and clears the most recently captured {@link SelectStatement} on this thread. */
    public static SelectStatement takeLastCaptured() {
        var stmt = LAST_CAPTURED.get();
        LAST_CAPTURED.remove();
        return stmt;
    }

    /** Clears the thread-local without consuming the captured statement. */
    public static void reset() {
        LAST_CAPTURED.remove();
    }

    @Override
    public SqlAstTranslator<JdbcOperationQuerySelect> buildSelectTranslator(
            SessionFactoryImplementor sessionFactory, SelectStatement selectStatement) {
        LAST_CAPTURED.set(selectStatement);
        return delegate.buildSelectTranslator(sessionFactory, selectStatement);
    }

    @Override
    public SqlAstTranslator<? extends JdbcOperationQueryMutation> buildMutationTranslator(
            SessionFactoryImplementor sessionFactory, MutationStatement mutationStatement) {
        return delegate.buildMutationTranslator(sessionFactory, mutationStatement);
    }

    @Override
    public <O extends JdbcMutationOperation> SqlAstTranslator<O> buildModelMutationTranslator(
            TableMutation<O> tableMutation, SessionFactoryImplementor sessionFactory) {
        return delegate.buildModelMutationTranslator(tableMutation, sessionFactory);
    }
}
```

- [ ] **Step 2: Write the capturing dialect**

Create `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2Dialect.java`:

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

import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;

/** Test-only dialect that installs {@link CapturingMqlv2TranslatorFactory}. */
public final class CapturingMqlv2Dialect extends TestMqlv2Dialect {
    public CapturingMqlv2Dialect(DialectResolutionInfo info) {
        super(info);
    }

    @Override
    public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
        return new CapturingMqlv2TranslatorFactory();
    }
}
```

If `TestMqlv2Dialect` does not have a constructor that accepts `DialectResolutionInfo` (or has a different signature in Hibernate 7.x), match its actual constructor — confirm by reading `TestMqlv2Dialect.java` first.

- [ ] **Step 3: Write the diagnostic test**

Create `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java`:

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.cfg.AvailableSettings.DIALECT;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.sql.ast.tree.from.FunctionTableReference;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Diagnostic-only test that locks in the load-bearing AST-shape assumption Phases 2-4
 * of the elemMatch design depend on: that Hibernate 7 desugars `from O o join o.array a`
 * into a {@link FunctionTableReference} whose function descriptor identifies as "unnest".
 *
 * <p>Uses {@link CapturingMqlv2TranslatorFactory} via {@link CapturingMqlv2Dialect} so the
 * assertion is positive (about what the AST IS) without requiring production-code changes.
 * The HQL query is expected to fail at translation time (the v2 translator does not yet
 * support {@code FunctionTableReference}), but the capture happens BEFORE the failure, so
 * the failure does not block the assertion. This test will be removed once Phase 3 lands.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestAstDiagnosticTests.Item.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.CapturingMqlv2Dialect"))
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestAstDiagnosticTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void resetCapture() {
        CapturingMqlv2TranslatorFactory.reset();
    }

    @Test
    void pluralAttributeJoinDesugarsToUnnestFunctionTableReference() {
        // Trigger translation. The query will fail (the v2 translator does not yet handle
        // FunctionTableReference), but the capture in buildSelectTranslator happens first,
        // so we don't care about the failure mode here.
        try {
            sessionFactoryScope.inSession(session ->
                    session.createQuery("from Item i join i.tags t", Item.class).getResultList());
        } catch (Throwable ignored) {
            // expected: translator does not yet handle FunctionTableReference
        }

        var captured = CapturingMqlv2TranslatorFactory.takeLastCaptured();
        assertThat(captured).as("CapturingMqlv2TranslatorFactory did not capture any SelectStatement").isNotNull();

        var roots = captured.getQueryPart().getFirstQuerySpec().getFromClause().getRoots();
        assertThat(roots).hasSize(1);
        var joins = roots.get(0).getTableGroupJoins();
        assertThat(joins).as("plural-attribute join should appear as a TableGroupJoin").hasSize(1);

        var primaryRef = joins.get(0).getJoinedGroup().getPrimaryTableReference();
        assertThat(primaryRef)
                .as("Hibernate 7 should desugar 'join o.array a' into a FunctionTableReference; "
                        + "got %s instead", primaryRef.getClass().getName())
                .isInstanceOf(FunctionTableReference.class);

        var ftr = (FunctionTableReference) primaryRef;
        assertThat(ftr.toString())
                .as("FunctionTableReference toString should identify the function as unnest; "
                        + "if it does not, the test needs a different inspection path")
                .containsIgnoringCase("unnest");
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

If `FunctionTableReference.toString()` does not include the function name in 7.3.4.Final (the API does not guarantee this), the test's `containsIgnoringCase("unnest")` assertion will fail informatively. The fallback is to access the function descriptor directly — `FunctionTableReference` exposes the underlying SQM/function descriptor; consult `https://docs.hibernate.org/orm/7.3/javadocs/` for the accessor name and switch the assertion. Document the finding.

- [ ] **Step 4: Run the new test**

Run:

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestAstDiagnosticTests"
```

**Expected:** PASS. The chain of expectations is:
1. The HQL parses cleanly under Hibernate 7 (the plural-attribute join is valid syntax).
2. Hibernate 7 desugars the join to a `lateral unnest(i.tags) t` and produces a `TableGroupJoin` whose joined group's primary table reference is a `FunctionTableReference`.
3. The `CapturingMqlv2TranslatorFactory` captures the `SelectStatement` before the v2 translator runs.
4. The translation then fails (the v2 translator does not handle `FunctionTableReference`) — the test swallows this failure and inspects the captured AST.
5. The walk through `roots → joins → joinedGroup → primaryTableReference` yields a `FunctionTableReference` whose `toString()` contains `"unnest"`.

If the test fails:
- "did not capture any SelectStatement" → the capturing factory is not installed; verify `CapturingMqlv2Dialect` is the configured dialect and that `getSqlAstTranslatorFactory()` is overridden correctly.
- "should appear as a TableGroupJoin" with `hasSize(0)` → Hibernate 7 is restructuring the FROM clause in some other way; investigate the actual AST shape before continuing.
- "should desugar 'join o.array a' into a FunctionTableReference" → Hibernate 7 is NOT desugaring plural-attribute joins to unnest as the design expects; **this is a design-breaking finding** — stop and rethink the whole plan.
- "FunctionTableReference toString should identify the function as unnest" → `toString()` doesn't include the function name; switch to direct accessor as noted above.

**Critically:** if this test fails for an *unexpected* reason, do not "fix" it by weakening the assertion. The test is a diagnostic. A surprising failure is information — surface it.

- [ ] **Step 5: Commit the diagnostic test**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2TranslatorFactory.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/CapturingMqlv2Dialect.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestAstDiagnosticTests.java
git commit -m "$(cat <<'EOF'
test: lock in unnest AST-shape assumption for elemMatch design

Diagnostic-only test that confirms Hibernate 7 desugars plural-attribute
joins to a FunctionTableReference of unnest. Uses a test-only capturing
translator factory to inspect the SQL AST directly — no production-code
changes. This test will be removed or rewritten as a positive feature
assertion once Phase 3 implements unwind translation.
EOF
)"
```

---

## Task 7: Final verification

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
- The translator codebase contains no new features. Only mechanical API adaptations required by the upgrade — nothing added under `src/main`.
- The commit history is clean — distinct commits for the upgrade and the diagnostic test.

## What this plan deliberately does not do

- Add any unnest translation logic. Phase 3 owns that.
- Add any new HQL surface. Phases 2-4 own that.
- Refactor v1 translator behavior. Phase 0 is purely a compatibility update.
- Touch `@ElementCollection` support. Continues to throw at SessionFactory build per pre-existing behavior.

## Risks and how this plan addresses them

- **The upgrade reveals a behavior change that no test catches.** Mitigation: Task 5 runs the full `check` task, which includes static analysis. Beyond that, there's no defense — the codebase's test coverage is its own assurance level.
- **`FunctionTableReference.toString()` does not include the function name.** Mitigation: Task 6 Step 3 documents the alternative inspection path (direct accessor on the function descriptor).
- **A subtler AST shape than `FunctionTableReference` is what Hibernate 7 actually produces.** Mitigation: Task 6 Step 4 explicitly says "do not weaken the assertion" — investigate and bring findings back to the design before proceeding to Phase 1.
