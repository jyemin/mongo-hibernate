# Phase 2: EXISTS-over-unnest → `any(...)` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate `WHERE EXISTS (SELECT 1 FROM o.array a WHERE …)` HQL into MQLv2 `array any (<rewritten body>)`. The implicit-collection-path form is the canonical `$elemMatch` HQL surface confirmed by Phase 0; the `LATERAL unnest(...)` form does not parse inside EXISTS subqueries.

**Architecture:** Add a recognition hook in `Mqlv2SelectTranslator.appendPredicateText`'s `ExistsPredicate` branch. When the inner subquery's FROM root is a `FunctionTableReference("unnest")`, dispatch to a new helper that emits `(<arrayPath> any (<rewritten-body>))` instead of the existing generic correlated-EXISTS pipeline. Inner predicates are rewritten by a new walker variant (`appendPredicateTextInsideAny`) that resolves the unnest-alias to `$.<field>` (struct) or `$` (scalar) while leaving outer-correlated references to flow through the existing `$__vN` `let`-binding machinery.

**Tech Stack:** Java, Hibernate ORM 7.3.4, MongoDB MQLv2. Tests use the `MqlCapture` `StatementInspector` pattern from `Mqlv2ShowcaseVerificationTests` for pipeline-text assertions, plus execution assertions against a real MongoDB.

**Companion spec:** `docs/superpowers/specs/2026-05-15-mqlv2-elemmatch-via-unnest-design.md` (Phase 2 sections — Components, Data flow). Phase 0 findings near the top of the spec are the authoritative HQL-surface reference.

---

## File map

Files this plan creates or modifies:

- **Modify:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java` — Promote the inner `MqlCapture` class to a top-level test utility (Task 1).
- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/MqlCapture.java` — Shared test utility with the `StatementInspector` implementation and the `LAST` thread-local (Task 1).
- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java` — Phase 2 test class with combined pipeline-text + execution assertions (Tasks 3-9).
- **Modify:** `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — Add AST helpers, the new predicate walker variant, and the `ExistsPredicate` dispatch (Tasks 2, 4-8).

The diagnostic test class `Mqlv2UnnestAstDiagnosticTests` and its companion `CapturingMqlv2TranslatorFactory` / `CapturingMqlv2Dialect` from Phase 0 stay in place until Phase 3 lands — they document AST shapes and aren't superseded yet.

---

## Task 1: Promote `MqlCapture` to a shared test utility

**Goal:** Extract `MqlCapture` from its current home (an inner class of `Mqlv2ShowcaseVerificationTests`) into a standalone top-level class in the same package, so Phase 2's new test class can reference it without depending on the showcase test's internals.

**Files:**
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/MqlCapture.java`
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java`

- [ ] **Step 1: Create the new MqlCapture class**

Create `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/MqlCapture.java`:

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

import java.io.Serial;
import java.io.Serializable;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate {@link StatementInspector} that captures every rendered JDBC SQL (which for the v2
 * dialect is a JSON-encoded MQLv2 command) into a thread-local. Used by integration tests that
 * need to assert on the emitted pipeline text in addition to the executed result rows.
 *
 * <p>Wired via {@code @Setting(name = STATEMENT_INSPECTOR, value =
 * "com.mongodb.hibernate.query.mqlv2.MqlCapture")} on the test class's {@code @ServiceRegistry}.
 */
public final class MqlCapture implements StatementInspector, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final ThreadLocal<String> LAST = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        LAST.set(sql);
        return sql;
    }
}
```

- [ ] **Step 2: Update the showcase test to use the promoted class**

Open `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java`. Find the line `value = "com.mongodb.hibernate.query.mqlv2.Mqlv2ShowcaseVerificationTests$MqlCapture"` (around line 55) and change it to:

```java
value = "com.mongodb.hibernate.query.mqlv2.MqlCapture"
```

Then delete the inner `MqlCapture` class definition (around lines 71-82, ending with the closing brace of the inner class). Verify the file still imports `Serializable` only if other code references it — likely not; remove unused `Serial` / `Serializable` / `StatementInspector` imports if they're no longer used.

Find references to `MqlCapture.LAST` inside the showcase test (around lines 410, 419). They remain valid since the new top-level class has the same `LAST` field.

- [ ] **Step 3: Compile and verify**

Run:

```
./gradlew compileIntegrationTestJava
```

Expected: `BUILD SUCCESSFUL`. If imports complain, remove unused ones in the showcase file.

- [ ] **Step 4: Run the showcase test to confirm no behavior regression**

Run:

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2ShowcaseVerificationTests"
```

Expected: the showcase test passes (the capture mechanism still works after the refactor).

- [ ] **Step 5: Commit**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/MqlCapture.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java
git commit -m "test: promote MqlCapture to shared test utility"
```

---

## Task 2: Add AST helpers in `Mqlv2SelectTranslator`

**Goal:** Add three private static helper methods that recognize and decompose `FunctionTableReference("unnest")` nodes. These are used by Phases 2-4. No behavior change yet — just pure helpers and their unit-level shape.

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Read the imports section of Mqlv2SelectTranslator.java**

Confirm `org.hibernate.sql.ast.tree.from.FunctionTableReference` is already imported (it is — line 86 per the spec). If `org.hibernate.sql.ast.tree.from.TableReference` is not imported, you may need to add it; for the helpers below it's the parameter type of `isUnnestFunctionTable`.

- [ ] **Step 2: Add the three helpers**

Near the end of the class (before the visitor stub methods), add:

```java
    // ---- Phase 2/3/4 unnest helpers ----

    /**
     * @return true iff the table reference is a {@link FunctionTableReference} whose function
     *     descriptor identifies as "unnest".
     */
    private static boolean isUnnestFunctionTable(TableReference ref) {
        return ref instanceof FunctionTableReference ftr
                && "unnest".equals(ftr.getFunctionExpression().getFunctionName());
    }

    /**
     * Returns the single argument expression to {@code unnest(<arg>)}. Throws
     * {@link FeatureNotSupportedException} if the argument is not a simple
     * {@link ColumnReference} or {@link BasicValuedPathInterpretation} (which would mean the user
     * passed a literal array, a function call, or some other non-path expression).
     */
    private static Expression extractUnnestArrayPath(FunctionTableReference ftr) {
        var args = ftr.getFunctionExpression().getArguments();
        if (args.size() != 1) {
            throw new FeatureNotSupportedException(
                    "unnest() requires exactly one argument; got " + args.size());
        }
        var arg = args.get(0);
        if (arg instanceof Expression expr
                && (expr instanceof ColumnReference || expr instanceof BasicValuedPathInterpretation<?>)) {
            return expr;
        }
        throw new FeatureNotSupportedException(
                "unnest() argument must be a path expression on an outer entity; got: "
                        + arg.getClass().getSimpleName());
    }

    /**
     * Returns the identification variable that aliases the rows of the unnest output (the "a" in
     * {@code FROM o.array a}).
     */
    private static String extractUnnestAlias(TableGroup group) {
        return ((FunctionTableReference) group.getPrimaryTableReference()).getIdentificationVariable();
    }
```

The imports `TableReference`, `Expression`, `ColumnReference`, and `BasicValuedPathInterpretation` are already in the file. If `TableReference` is not yet imported, add `import org.hibernate.sql.ast.tree.from.TableReference;` near the existing `FunctionTableReference` import.

- [ ] **Step 3: Compile**

Run:

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. The helpers are not yet called from anywhere, so no behavior change.

- [ ] **Step 4: Commit**

```
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: add AST helpers for unnest function-table recognition"
```

---

## Task 3: Create the Phase 2 test class with one failing test

**Goal:** Write the first end-to-end test (struct array, single-condition EXISTS predicate). The test asserts both the emitted MQLv2 pipeline text and the returned rows. The test is expected to FAIL at translation time because the translator doesn't yet handle the unnest-in-EXISTS shape.

**Files:**
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`

- [ ] **Step 1: Create the test class**

Create `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`:

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
import static org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Objects;
import org.bson.BsonDocument;
import org.hibernate.annotations.Struct;
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
 * Phase 2 of the elemMatch design: HQL {@code WHERE EXISTS (SELECT 1 FROM o.array a WHERE …)}
 * translates to MQLv2 {@code array any (<rewritten body>)}. Each test asserts both the emitted
 * pipeline text (via {@link MqlCapture}) and the returned rows.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestExistsIntegrationTests.Order.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestExistsIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from Order").executeUpdate();
            session.persist(new Order(1, new LineItem[] {new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 1)}));
            session.persist(new Order(2, new LineItem[] {new LineItem("GADGET-1", 10)}));
            session.persist(new Order(3, new LineItem[] {new LineItem("WIDGET-1", 0)}));
        });
        MqlCapture.LAST.remove();
    }

    @Test
    void existsOverStructArray_singlePredicate() {
        var hql = "from Order o where exists (select 1 from o.lineItems li where li.sku = 'WIDGET-1')";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        // Translation assertion: implicit collection-path EXISTS becomes `lineItems any (…)`.
        var captured = MqlCapture.LAST.get();
        assertThat(captured).isNotNull();
        assertThat(BsonDocument.parse(captured).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any ($.sku == \"WIDGET-1\"))"
                        + " | format {_id: _id, lineItems: lineItems}");

        // Execution assertion: orders 1 and 3 contain a WIDGET-1 line item; order 2 does not.
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 3);
    }

    // ---- Test entity / embeddable ----

    @Entity(name = "Order")
    @Table(name = "orders")
    static class Order {
        @Id
        int id;

        LineItem[] lineItems;

        Order() {}

        Order(int id, LineItem[] lineItems) {
            this.id = id;
            this.lineItems = lineItems;
        }
    }

    @Embeddable
    @Struct(name = "LineItem")
    static class LineItem {
        String sku;
        int qty;

        LineItem() {}

        LineItem(String sku, int qty) {
            this.sku = sku;
            this.qty = qty;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LineItem li)) return false;
            return qty == li.qty && Objects.equals(sku, li.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku, qty);
        }
    }
}
```

- [ ] **Step 2: Run the test and verify it fails for the expected reason**

Run:

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests"
```

Expected: FAIL. The translator's `appendPredicateText` ExistsPredicate branch (around line 967) treats the inner subquery as a regular `from $collection` pipeline, calls `appendQuerySpecPipeline` which downcasts the primary table reference to `NamedTableReference` (around line 432) — and that cast throws `ClassCastException` for `FunctionTableReference`.

The exact thrown exception is whatever Hibernate wraps the cast failure as; what matters is that the test fails before the assertion checks. If the test fails with a *different* root cause than `ClassCastException` on `NamedTableReference`, investigate before continuing — the AST shape may differ from what Phase 0 confirmed.

- [ ] **Step 3: Do NOT commit yet**

Implementation comes in Tasks 4-5.

---

## Task 4: Add `appendPredicateTextInsideAny` walker

**Goal:** New predicate walker that translates predicates inside an `any` body, rewriting column references to `$.<col>` (when qualifier matches an unnest alias on the stack), `$__vN` (when qualifier matches an outer-query alias), or throwing for unrecognized qualifiers. Refactor: consolidate `appendPredicateTextCorrelated` and `appendPredicateTextInsideAny` around a column-reference resolver. The existing tests (which exercise `appendPredicateTextCorrelated` via EXISTS/IN/ANY/ALL on regular subqueries) are the regression net.

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Read the existing `appendPredicateTextCorrelated` and `appendExprTextCorrelated` (around lines 442-527)**

These two methods walk a predicate / expression and rewrite outer-correlated column references as `$__vN`. The new walker variant needs the same predicate traversal but a different column-reference rule.

- [ ] **Step 2: Introduce a column-reference resolver type and consolidate the walkers**

Refactor `appendPredicateTextCorrelated` and `appendExprTextCorrelated` to take a `ColumnReferenceResolver` parameter. Define `ColumnReferenceResolver` as a private functional interface:

```java
    /** Decides how a {@link ColumnReference} encountered inside a predicate/expression walker
     *  is rendered. Implementations: outer-correlated (used by existing EXISTS/IN/ANY/ALL paths)
     *  and inside-any (used by Phase 2). */
    @FunctionalInterface
    private interface ColumnReferenceResolver {
        void render(StringBuilder sb, ColumnReference cr);
    }
```

Change `appendPredicateTextCorrelated` and `appendExprTextCorrelated` to accept a `ColumnReferenceResolver` instead of `Set<String> outerQualifiers, Map<String, String> correlatedBindings`. Inside these methods, when they encounter a `ColumnReference`, they call `resolver.render(sb, cr)` instead of the inline outer/inner-qualifier logic.

The existing outer-correlated resolver is then captured as a private helper method `outerCorrelatedResolver(outerQualifiers, correlatedBindings)` that returns a `ColumnReferenceResolver` closing over those two state values.

- [ ] **Step 3: Add the inside-any resolver and walker**

Add:

```java
    /**
     * Builds a resolver for column references encountered inside an {@code any} body. Rule:
     * <ul>
     *   <li>Qualifier matches an unnest alias on the stack → {@code $.<column>}
     *       (resolved against the innermost matching alias).
     *   <li>Qualifier matches an outer-query alias → {@code $__vN} via the outer-correlated path.
     *   <li>Otherwise → {@code FeatureNotSupportedException}.
     * </ul>
     */
    private ColumnReferenceResolver insideAnyResolver(
            List<String> unnestAliasStack,
            Set<String> outerQualifiers,
            Map<String, String> correlatedBindings) {
        var outerResolver = outerCorrelatedResolver(outerQualifiers, correlatedBindings);
        return (sb, cr) -> {
            var qualifier = cr.getQualifier();
            if (qualifier != null && unnestAliasStack.contains(qualifier)) {
                sb.append("$.").append(cr.getColumnExpression());
            } else if (qualifier != null && outerQualifiers.contains(qualifier)) {
                outerResolver.render(sb, cr);
            } else if (qualifier == null || unnestAliasStack.contains(qualifier)) {
                // Unqualified ref inside any body — treat as the current element.
                sb.append("$.").append(cr.getColumnExpression());
            } else {
                throw new FeatureNotSupportedException(
                        "Reference to alias '" + qualifier + "' inside unnest body is not in scope");
            }
        };
    }
```

- [ ] **Step 4: Compile to verify the refactor is sound**

Run:

```
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. The refactor changes signatures; call sites in the same file must be updated.

- [ ] **Step 5: Run the full integration suite to confirm no regression in existing correlated paths**

Run:

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2ShowcaseVerificationTests" \
                           --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2SelectIntegrationTests"
```

Expected: both pass. Existing EXISTS/IN/ANY/ALL paths still work because the refactor preserves behavior.

- [ ] **Step 6: Do NOT commit yet**

Continue to Task 5.

---

## Task 5: Hook `ExistsPredicate` to dispatch to `appendUnnestExistsPredicate`

**Goal:** Add the recognition + dispatch in `appendPredicateText`'s `ExistsPredicate` branch, plus the new helper. After this task, the Task 3 test passes.

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Add the new helper `appendUnnestExistsPredicate`**

Add (near the existing `appendAnyAllPredicate`, around line 847):

```java
    /**
     * Translates {@code exists (select 1 from <outerArrayPath> alias where <body>)} into
     * MQLv2 {@code (<arrayPath> any (<body-rewritten>))}, with outer-correlated references
     * captured into a {@code let} wrapper around the {@code any} expression. Negation wraps
     * the whole thing in {@code (not …)}.
     */
    private void appendUnnestExistsPredicate(StringBuilder sb, ExistsPredicate ep) {
        var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();
        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = extractUnnestAlias(innerRoot);

        var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var outerQualifiers = collectOuterQualifiers(outerSpec);
        var correlatedBindings = new LinkedHashMap<String, String>();

        var arrayPathSb = new StringBuilder();
        appendExprText(arrayPathSb, arrayPath);

        var bodySb = new StringBuilder();
        appendPredicateTextWithResolver(
                bodySb,
                innerSpec.getWhereClauseRestrictions(),
                insideAnyResolver(List.of(unnestAlias), outerQualifiers, correlatedBindings));

        var anyExpr = "(" + arrayPathSb + " any " + bodySb + ")";
        var wrapped = wrapWithLet(anyExpr, correlatedBindings);
        if (ep.isNegated()) {
            sb.append("(not ").append(wrapped).append(")");
        } else {
            sb.append(wrapped);
        }
    }
```

Note: `appendPredicateTextWithResolver` is the renamed/refactored version of `appendPredicateTextCorrelated` from Task 4. If you kept the existing name, use that name here.

- [ ] **Step 2: Hook the recognition in `appendPredicateText`'s `ExistsPredicate` branch**

In `appendPredicateText` (around line 967), find the `else if (predicate instanceof ExistsPredicate ep)` branch. Before its existing body (which calls `appendQuerySpecPipeline`), add:

```java
        } else if (predicate instanceof ExistsPredicate ep) {
            var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
            var innerRoot = innerSpec.getFromClause().getRoots().get(0);
            if (isUnnestFunctionTable(innerRoot.getPrimaryTableReference())) {
                appendUnnestExistsPredicate(sb, ep);
            } else {
                // ... existing generic correlated-EXISTS body ...
            }
```

Move the existing body into the `else` branch.

- [ ] **Step 3: Verify the Task 3 test passes**

Run:

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests.existsOverStructArray_singlePredicate"
```

Expected: PASS. Both the translation assertion (pipeline text matches expected) and the execution assertion (orders 1 and 3 returned) succeed.

If the translation text differs slightly (e.g., different parenthesization, different `format` field ordering), update the expected string to match what the translator actually emits — but ONLY after you've verified the MQLv2 is semantically equivalent. The execution assertion is the authoritative correctness check.

- [ ] **Step 4: Run the full integration suite to confirm no regression**

Run:

```
./gradlew integrationTest
```

Expected: all existing tests still pass; the new test passes.

- [ ] **Step 5: Commit Tasks 2-5 together**

```
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java
git commit -m "MQLv2: translate EXISTS over collection-valued path into any()"
```

---

## Task 6: Add multi-condition body tests (AND / OR / NOT inside `any`)

**Goal:** Confirm the predicate walker handles AND/OR/NOT junctions correctly inside the unnest body.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`

- [ ] **Step 1: Add AND-conjunction test**

Append:

```java
    @Test
    void existsOverStructArray_andConjunctionInBody() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any (($.sku == \"WIDGET-1\") and ($.qty > 0)))"
                        + " | format {_id: _id, lineItems: lineItems}");
        // Order 1 has WIDGET-1 with qty=5 (matches); order 3 has WIDGET-1 with qty=0 (doesn't).
        assertThat(results).extracting(o -> o.id).containsExactly(1);
    }
```

- [ ] **Step 2: Run and verify it passes**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests.existsOverStructArray_andConjunctionInBody"
```

Expected: PASS. If the emitted pipeline differs in parenthesization, update the expected string and re-run.

- [ ] **Step 3: Add OR-disjunction test**

Append:

```java
    @Test
    void existsOverStructArray_orDisjunctionInBody() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where li.sku = 'WIDGET-1' or li.qty > 5)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any (($.sku == \"WIDGET-1\") or ($.qty > 5)))"
                        + " | format {_id: _id, lineItems: lineItems}");
        // Order 1 matches (WIDGET-1), order 2 matches (qty=10), order 3 matches (WIDGET-1).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 2, 3);
    }
```

- [ ] **Step 4: Add NOT-inside-body test**

Append:

```java
    @Test
    void existsOverStructArray_notInsideBody() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where not (li.sku = 'WIDGET-1'))";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any ((not ($.sku == \"WIDGET-1\"))))"
                        + " | format {_id: _id, lineItems: lineItems}");
        // Order 1 has WIDGET-2 (matches NOT WIDGET-1), order 2 has GADGET-1 (matches), order 3 has only WIDGET-1 (doesn't match).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 2);
    }
```

- [ ] **Step 5: Run all three**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests"
```

Expected: all four tests in the class pass.

- [ ] **Step 6: Commit**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java
git commit -m "MQLv2: cover AND/OR/NOT inside EXISTS-over-unnest body"
```

---

## Task 7: Add NOT EXISTS test

**Goal:** Verify `NOT EXISTS (SELECT 1 FROM o.array a WHERE …)` wraps with `(not …)`.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`

- [ ] **Step 1: Add the test**

```java
    @Test
    void notExistsOverStructArray() {
        var hql = "from Order o where not exists (select 1 from o.lineItems li where li.sku = 'WIDGET-1')";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match ((not (lineItems any ($.sku == \"WIDGET-1\"))))"
                        + " | format {_id: _id, lineItems: lineItems}");
        // Only order 2 lacks WIDGET-1.
        assertThat(results).extracting(o -> o.id).containsExactly(2);
    }
```

- [ ] **Step 2: Run and verify**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests.notExistsOverStructArray"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java
git commit -m "MQLv2: cover NOT EXISTS over unnest"
```

---

## Task 8: Add correlated-outer-reference test

**Goal:** Verify a predicate in the unnest body that references an outer-query column flows through the existing `$__vN` correlated-binding machinery.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`

- [ ] **Step 1: Extend the Order entity with a `minQty` field**

Change the `Order` class to include `int minQty`. Update the constructor and the seed data:

```java
    @Entity(name = "Order")
    @Table(name = "orders")
    static class Order {
        @Id
        int id;
        int minQty;
        LineItem[] lineItems;

        Order() {}

        Order(int id, int minQty, LineItem[] lineItems) {
            this.id = id;
            this.minQty = minQty;
            this.lineItems = lineItems;
        }
    }
```

Update `seed()`:

```java
        session.persist(new Order(1, 3, new LineItem[] {new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 1)}));
        session.persist(new Order(2, 5, new LineItem[] {new LineItem("GADGET-1", 10)}));
        session.persist(new Order(3, 1, new LineItem[] {new LineItem("WIDGET-1", 0)}));
```

- [ ] **Step 2: Update the earlier tests' expected MQLv2 to include `minQty` in the `format`**

The `format` stage now includes the new field. Update each existing test's expected pipeline-text string to include `minQty: minQty` between `_id: _id` and `lineItems: lineItems`. Run the existing tests once to confirm they still pass with the updated expectations.

- [ ] **Step 3: Add the correlated-outer-reference test**

```java
    @Test
    void existsOverStructArray_correlatedOuterRef() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where li.qty > o.minQty)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (let $__v0 = minQty in (lineItems any ($.qty > $__v0)))"
                        + " | format {_id: _id, minQty: minQty, lineItems: lineItems}");
        // Order 1: minQty=3, has qty=5 (matches). Order 2: minQty=5, has qty=10 (matches).
        // Order 3: minQty=1, has qty=0 (doesn't match).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 2);
    }
```

- [ ] **Step 4: Run all tests in the class**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests"
```

Expected: every test passes.

- [ ] **Step 5: Commit**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java
git commit -m "MQLv2: cover correlated outer reference in EXISTS-over-unnest body"
```

---

## Task 9: Add scalar-array EXISTS test (discovery)

**Goal:** Confirm `WHERE EXISTS (SELECT 1 FROM o.scalarArray a WHERE …)` works for scalar arrays via the same translator path. Phase 0 confirmed the AST shape for struct arrays through the implicit collection-path; the scalar variant has NOT been confirmed for EXISTS. This task discovers whether it works as the design assumes, and adjusts scope if not.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`

- [ ] **Step 1: Add a scalar array field to the entity**

Add `int[] scores;` to `Order`. Update the constructor and seed.

```java
    Order(int id, int minQty, LineItem[] lineItems, int[] scores) { ... }
```

Seed:

```java
        session.persist(new Order(1, 3, new LineItem[] {...}, new int[] {10, 20, 30}));
        session.persist(new Order(2, 5, new LineItem[] {...}, new int[] {1, 2, 3}));
        session.persist(new Order(3, 1, new LineItem[] {...}, new int[] {}));
```

Update prior tests' expected `format` to include `scores: scores`.

- [ ] **Step 2: Add the scalar EXISTS test**

```java
    @Test
    void existsOverScalarArray_singlePredicate() {
        var hql = "from Order o where exists (select 1 from o.scores s where s > 15)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (scores any ($ > 15))"
                        + " | format {_id: _id, minQty: minQty, lineItems: lineItems, scores: scores}");
        // Order 1: scores include 20, 30 (matches). Orders 2 and 3 don't.
        assertThat(results).extracting(o -> o.id).containsExactly(1);
    }
```

- [ ] **Step 3: Run the scalar test**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests.existsOverScalarArray_singlePredicate"
```

**Three possible outcomes:**

1. **PASS:** the implicit-collection-path EXISTS form works for scalar arrays too. Phase 2's scope holds; move on.

2. **FAIL with Hibernate AssertionError in `SqmMappingModelHelper.resolveSqmPath`** (the same error Phase 0 saw on the JOIN sugar form): scalar arrays can't be used with this HQL idiom either. **Halt and report back to the human** — the design needs revisiting (likely: scalar EXISTS via explicit `lateral unnest` in the outer FROM, similar to Phase 3's scalar fallback).

3. **FAIL with a translator-side `FeatureNotSupportedException`** or other recognizable translator failure: the AST shape differs from struct arrays. Inspect the captured AST via the same pattern as Phase 0's diagnostic tests and adjust the translator.

The translator's body resolver in `insideAnyResolver` already handles the unqualified-reference case (`s > 15` where `s` is the alias and has no `.field`) by emitting `$.<col>`. For scalar arrays the column expression might be the synthesized "value" name registered with `UnnestFunction` in Phase 0; if so, the emission would be `$.value > 15` instead of `$ > 15`. Confirm what's actually emitted and decide whether to rewrite `$.value` → `$` in a final step.

- [ ] **Step 4: Commit (depending on outcome)**

If the test passes:

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java
git commit -m "MQLv2: cover EXISTS over scalar array"
```

If the test required translator adjustments, commit those alongside the test in one commit.

If outcome (2) — halt and surface to the human.

---

## Task 10: Add nested EXISTS test (array of docs with array of subdocs)

**Goal:** Verify nested EXISTS translates to nested `any(...)`. The unnest-alias stack tracks both levels.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java`

- [ ] **Step 1: Add a nested struct array on the embeddable**

Extend `LineItem` to include `Tax[] taxes` and define `Tax`:

```java
    @Embeddable
    @Struct(name = "LineItem")
    static class LineItem {
        String sku;
        int qty;
        Tax[] taxes;
        // ... constructor / equals / hashCode updates ...
    }

    @Embeddable
    @Struct(name = "Tax")
    static class Tax {
        String code;
        double rate;
        // ... constructor / equals / hashCode ...
    }
```

- [ ] **Step 2: Update seed data with nested taxes**

```java
        session.persist(new Order(1, 3, new LineItem[] {
                new LineItem("WIDGET-1", 5, new Tax[] {new Tax("VAT", 0.10), new Tax("LOCAL", 0.05)}),
                new LineItem("WIDGET-2", 1, new Tax[] {new Tax("VAT", 0.10)})
        }, new int[] {10, 20, 30}));
        // ... orders 2 and 3 similarly ...
```

- [ ] **Step 3: Add the nested EXISTS test**

```java
    @Test
    void nestedExistsOverStructArray() {
        var hql = "from Order o where exists ("
                + "  select 1 from o.lineItems li where exists ("
                + "    select 1 from li.taxes t where t.code = 'VAT'))";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .contains("lineItems any ($.taxes any ($.code == \"VAT\"))");
        // Any order that has any line item with a VAT tax.
        assertThat(results).isNotEmpty();
    }
```

The exact `format` portion of the expected text grows with each entity field; pin only the substring that proves the nested-any structure to keep the test resilient.

- [ ] **Step 4: Run**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestExistsIntegrationTests.nestedExistsOverStructArray"
```

Expected: PASS. If the inner-any rewrite fails (e.g., the inner alias `t` isn't found on the stack), the recursion into `appendUnnestExistsPredicate` from inside the outer-any body isn't pushing/popping the stack correctly. Re-examine the `unnestAliasStack` plumbing in Task 4-5 — when `appendUnnestExistsPredicate` is called from inside `insideAnyResolver`-driven walking, it must build a new stack that extends the parent's.

- [ ] **Step 5: Commit**

```
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestExistsIntegrationTests.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: cover nested EXISTS over array of docs containing inner array"
```

(Include `Mqlv2SelectTranslator.java` if you had to adjust the alias-stack plumbing.)

---

## Task 11: Final verification

**Goal:** Full `./gradlew check` is green; commit history is clean; the spec's Phase 2 scope is fully realized.

**Files:** none changed.

- [ ] **Step 1: Run full check**

```
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Inspect commit log**

```
git log --oneline -15
```

Expected (most recent first):

```
<sha> MQLv2: cover nested EXISTS over array of docs containing inner array
<sha> MQLv2: cover EXISTS over scalar array
<sha> MQLv2: cover correlated outer reference in EXISTS-over-unnest body
<sha> MQLv2: cover NOT EXISTS over unnest
<sha> MQLv2: cover AND/OR/NOT inside EXISTS-over-unnest body
<sha> MQLv2: translate EXISTS over collection-valued path into any()
<sha> MQLv2: add AST helpers for unnest function-table recognition
<sha> test: promote MqlCapture to shared test utility
```

(Plus earlier branch commits.)

- [ ] **Step 3: Spot-check the diagnostic test still passes**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestAstDiagnosticTests"
```

Expected: still PASS. The Phase 0 diagnostic stays in place until Phase 3 lands.

---

## Definition of done

- `Mqlv2UnnestExistsIntegrationTests` ships with 7-9 test methods covering: single predicate, AND/OR/NOT inside body, NOT EXISTS, correlated outer reference, scalar array variant (if Task 9 confirms it works), nested EXISTS.
- Translator changes are minimal: three new AST helpers, the consolidated `appendPredicateText*` walkers around a `ColumnReferenceResolver`, and one new `appendUnnestExistsPredicate` helper called from the recognition hook.
- Existing test suite stays green (showcase, select integration, diagnostic, array-hydration).
- Commit history is clean; each commit corresponds to one logical step.

## What this plan deliberately does not do

- Translate the plural-attribute JOIN form (`FROM O o JOIN o.array a`) — that's Phase 3.
- Translate scalar subqueries / IN subqueries over arrays — that's Phase 4.
- Translate explicit `LATERAL unnest(...)` in EXISTS subqueries — that doesn't parse in HQL (per Phase 0 findings) and isn't a user-facing surface.
- Support projection of join-alias fields inside EXISTS — out of scope.
- Support nested unnest at the outer FROM level — explicitly out of scope per the spec.

## Risks and open questions

- **Scalar-array EXISTS via implicit collection-path:** Phase 0 didn't confirm this. Task 9 is the discovery step. If it fails the same way as the JOIN sugar form did (Hibernate AssertionError), Phase 2 may need to scope back to struct arrays only.
- **Body parenthesization:** the existing translator wraps every binary predicate in `(...)`. The exact output for `(li.sku == 'WIDGET-1' AND li.qty > 0)` may have extra parens compared to the expected strings written here. Adjust expected strings to match what's emitted.
- **Alias-stack lifecycle for nested EXISTS:** Task 10's correctness depends on the unnest-alias stack being passed/extended correctly when nesting. If a flat per-call stack proves wrong, switch to a translator-instance field that callers push/pop.
- **MqlCapture promotion:** Task 1 is mechanical but touches the showcase test. If the showcase test breaks after the refactor, fix it before proceeding to Task 2.
