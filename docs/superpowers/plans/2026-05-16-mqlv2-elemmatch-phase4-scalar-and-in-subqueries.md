# Phase 4: Scalar `count(*)` subquery + IN-subquery over arrays — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate two Phase 4 HQL surfaces (struct arrays only):

1. **`count(*)` scalar subquery:** `SELECT o.id, (SELECT count(*) FROM o.array a WHERE …) FROM O o` → `from $orders | format {_id: _id, big: count((from <arrayPath> | match (<body>)))}`
2. **IN subquery:** `WHERE x IN (SELECT a.col FROM o.array a [WHERE …])` → outer `match (count(let $__v0 = x in ((from <arrayPath> | match ($.col == $__v0[ and <body>])))) > 0)`

Both emissions use MQLv2's **subpipeline expression** form `(from <arrayPath> | match (...))`. This was confirmed server-side via mongosh; the alternative form `<arrayPath> | match (...)` is invalid because `|` is a pipeline stage operator, not a sequence-expression operator.

**Architecture:** Extend two existing translator branches in `Mqlv2SelectTranslator`:
- The scalar-subquery handler in `appendExprText`'s `SelectStatement` branch (around line 1034). Currently restricts to `count()` over a regular `from $collection` subquery; Phase 4 adds the unnest variant.
- The `InSubQueryPredicate` handler (around line 937). Currently emits `count(let $__v0 = x in (from $collection | match (col == $__v0)))`; Phase 4 adds the unnest variant.

Both reuse the existing `appendPredicateTextWithResolver` + `insideAnyResolver` machinery from Phase 2 to rewrite body predicates (column refs `a.x` → `$.x` inside `from <arrayPath> | match (...)`).

**Tech Stack:** Java, Hibernate ORM 7.3.4, MongoDB MQLv2. Tests use `MqlCapture` for pipeline-text assertions plus execution assertions.

**Companion spec:** `docs/superpowers/specs/2026-05-15-mqlv2-elemmatch-via-unnest-design.md` (Phase 4 sections). Server-side syntax confirmed via mongosh before plan write.

---

## File map

- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestSubqueryIntegrationTests.java` — Phase 4 test class.
- **Modify:** `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — extensions to `appendExprText`'s `SelectStatement` branch and `appendPredicateText`'s `InSubQueryPredicate` branch.

---

## Task 1: First failing test — scalar `count(*)` subquery

**Goal:** Write the first end-to-end test for `SELECT o.id, (SELECT count(*) FROM o.lineItems a WHERE a.qty > :q) FROM Order o`. Assert both the emitted pipeline text and the executed rows. Expected to fail because the translator's existing scalar-subquery branch doesn't recognize unnest function tables.

- [ ] **Step 1: Create the test class**

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.cfg.AvailableSettings.DIALECT;
import static org.hibernate.cfg.JdbcSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * Phase 4 of the elemMatch design: scalar {@code count(*)} subqueries and IN subqueries over
 * struct arrays translate via a subpipeline expression {@code (from <arrayPath> | match (...))}
 * inside MQLv2 {@code count(...)}.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestSubqueryIntegrationTests.Order.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestSubqueryIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from Order").executeUpdate();
            // Order 1: WIDGET-1 qty=5, WIDGET-2 qty=8, WIDGET-3 qty=2 → 2 items with qty > 4
            session.persist(new Order(1, new LineItem[] {
                new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 8), new LineItem("WIDGET-3", 2)
            }));
            // Order 2: GADGET-1 qty=10 → 1 item with qty > 4
            session.persist(new Order(2, new LineItem[] {new LineItem("GADGET-1", 10)}));
            // Order 3: WIDGET-1 qty=0 → 0 items with qty > 4
            session.persist(new Order(3, new LineItem[] {new LineItem("WIDGET-1", 0)}));
        });
        MqlCapture.LAST.remove();
    }

    @Test
    void scalarSubqueryCount_overStructArray_simplePredicate() {
        var hql = "select o.id, (select count(*) from o.lineItems a where a.qty > 4) from Order o order by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        var captured = BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        assertThat(captured)
                .contains("count((from lineItems | match ($.qty > 4)))");

        // Expected: (1, 2), (2, 1), (3, 0)
        assertThat(results).hasSize(3);
        assertThat(((Number) ((Object[]) results.get(0))[1]).longValue()).isEqualTo(2L);
        assertThat(((Number) ((Object[]) results.get(1))[1]).longValue()).isEqualTo(1L);
        assertThat(((Number) ((Object[]) results.get(2))[1]).longValue()).isEqualTo(0L);
    }

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

- [ ] **Step 2: Run; expect failure**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestSubqueryIntegrationTests.scalarSubqueryCount_overStructArray_simplePredicate"
```

Expected: FAIL. The existing scalar-subquery branch in `appendExprText`'s `SelectStatement` handler (around line 1034) tries to emit `count(<inner-pipeline>)` where the inner pipeline starts with `from $<collection>`. For our case the FROM root is a `FunctionTableReference("unnest")`, not a `NamedTableReference`, so the existing `appendQuerySpecPipeline` helper will ClassCastException or fail similarly.

- [ ] **Step 3: Don't commit yet**

---

## Task 2: Extend `appendExprText`'s `SelectStatement` branch

**Goal:** When the inner subquery's FROM root is an unnest function table, emit `count((from <arrayPath> | match (<rewritten-body>)))` — the subpipeline expression form. Reuses Phase 2's `insideAnyResolver` + `appendPredicateTextWithResolver` for body translation.

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Inspect the existing `SelectStatement` branch**

Find the `else if (expression instanceof SelectStatement ss)` branch in `appendExprText` (around line 1034 per the spec; offset by your prior changes). Today it:
1. Extracts `innerSpec = ss.getQueryPart().getFirstQuerySpec()`.
2. Validates exactly one selection that's a `count()` aggregate.
3. Builds a correlated inner pipeline against a `NamedTableReference` FROM root.
4. Emits `count(<wrapped pipeline>)`.

- [ ] **Step 2: Add the unnest branch**

Inside the `SelectStatement ss` branch, BEFORE the existing logic, check whether the inner FROM root is an unnest function table:

```java
        } else if (expression instanceof SelectStatement ss) {
            var innerSpec = ss.getQueryPart().getFirstQuerySpec();
            var innerRoot = innerSpec.getFromClause().getRoots().get(0);
            if (isUnnestFunctionTable(innerRoot.getPrimaryTableReference())) {
                appendUnnestScalarSubquery(sb, ss);
            } else {
                // ... existing collection-subquery logic ...
            }
        }
```

(If you've kept the same name from the existing handler, restructure accordingly. The point is the new helper handles the unnest case, and the existing logic stays intact for non-unnest scalar subqueries.)

- [ ] **Step 3: Implement `appendUnnestScalarSubquery`**

Add the helper near the existing scalar-subquery handling. It must:
1. Extract the unnest's array path and alias.
2. Validate the selection is `count(*)` / `count()` / `count(entity)` / `count(<column>)`. For other aggregates (`max`, `sum`, `avg`, `min`), throw `FeatureNotSupportedException` with the documented reason: `"Scalar subquery over unnest() must use count(); other aggregates have no pipeline-argument form in MQLv2 yet"`.
3. Build the body translation: outer correlated bindings via `insideAnyResolver(List.of(unnestAlias), outerQualifiers, correlatedBindings)`; if the inner WHERE is null/empty, the body is just `(from <arrayPath>)` without a match.
4. Emit `count((from <arrayPath> | match (<body>)))`. With outer correlations, the `count(...)` is wrapped in `let $__vN = field in ...`.

Sketch:

```java
    /**
     * Translates {@code (select count(*) from o.array a [where <body>])} into MQLv2
     * {@code count((from <arrayPath> | match (<body>)))}, using the subpipeline-expression
     * form. Outer-correlated body references flow through the existing $__vN machinery.
     */
    private void appendUnnestScalarSubquery(StringBuilder sb, SelectStatement ss) {
        var innerSpec = ss.getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();

        var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        if (selections.size() != 1) {
            throw new FeatureNotSupportedException(
                    "Scalar subquery over unnest() must project exactly one column");
        }
        var selExpr = selections.get(0).getExpression();
        if (!(selExpr instanceof SelfRenderingFunctionSqlAstExpression fn)
                || !"count".equals(fn.getFunctionName())) {
            throw new FeatureNotSupportedException(
                    "Scalar subquery over unnest() must use count(); other aggregates have no "
                            + "pipeline-argument form in MQLv2 yet");
        }

        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = extractUnnestAlias(innerRoot);

        var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var outerQualifiers = collectOuterQualifiers(outerSpec);
        var correlatedBindings = new LinkedHashMap<String, String>();

        var arrayPathSb = new StringBuilder();
        appendExprText(arrayPathSb, arrayPath);

        var innerPipelineSb = new StringBuilder("from ").append(arrayPathSb);
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            var bodySb = new StringBuilder();
            appendPredicateTextWithResolver(
                    bodySb, where, insideAnyResolver(List.of(unnestAlias), outerQualifiers, correlatedBindings));
            innerPipelineSb.append(" | match ").append(bodySb);
        }

        var subpipelineExpr = "(" + innerPipelineSb + ")";
        var countExpr = "count(" + subpipelineExpr + ")";
        sb.append(wrapWithLet(countExpr, correlatedBindings));
    }
```

The `appendExprText(arrayPathSb, arrayPath)` call emits the outer-side reference to the array column. Since the unnest array is a column on the outer entity (e.g., `o.lineItems`), this should render as `lineItems` (no qualifier in the simple single-root case).

Note the `wrapWithLet` use: when there are outer-correlated refs, the emission is `let $__v0 = outer_field in (count((from lineItems | match ($.qty > $__v0))))`. The existing `wrapWithLet` helper does this correctly.

- [ ] **Step 4: Run the failing test; it should pass**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestSubqueryIntegrationTests.scalarSubqueryCount_overStructArray_simplePredicate"
```

Expected: PASS. If the pipeline-text assertion fails because the emitted text differs in parenthesization or field naming, adjust the `.contains(...)` expected substring to match.

- [ ] **Step 5: Run the full integration suite — no regressions**

```
./gradlew integrationTest
```

Expected: all existing tests still pass.

- [ ] **Step 6: Commit**

```
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestSubqueryIntegrationTests.java
git commit -m "MQLv2: translate scalar count(*) subquery over array into count((from … | match …))"
```

---

## Task 3: Scalar subquery with correlated outer reference

**Goal:** Verify a body predicate referencing an outer column flows through the `$__vN` `let`-binding mechanism.

- [ ] **Step 1: Add `int minQty` to `Order`**

Update the entity, constructor, and seed:

```java
    Order(int id, int minQty, LineItem[] lineItems) { … }
```

Seed: Order 1 minQty=4, Order 2 minQty=5, Order 3 minQty=1.

Update the prior test (`scalarSubqueryCount_overStructArray_simplePredicate`) — the `format` stage now includes `minQty`. The `.contains("count((from lineItems | match ($.qty > 4)))")` assertion isn't pinned to the full pipeline text so it should keep passing.

- [ ] **Step 2: Add the correlated test**

```java
    @Test
    void scalarSubqueryCount_correlatedOuterRef() {
        var hql = "select o.id, (select count(*) from o.lineItems a where a.qty > o.minQty) "
                + "from Order o order by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        var captured = BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        // Inner emission wraps in a let for the outer ref. Pin the recognizable substring.
        assertThat(captured).contains("let $__v0 = minQty in (count((from lineItems | match ($.qty > $__v0))))");

        // Order 1: minQty=4. Items with qty > 4: WIDGET-1(5), WIDGET-2(8) → 2.
        // Order 2: minQty=5. Items with qty > 5: GADGET-1(10) → 1.
        // Order 3: minQty=1. Items with qty > 1: none (only qty=0) → 0.
        assertThat(((Number) ((Object[]) results.get(0))[1]).longValue()).isEqualTo(2L);
        assertThat(((Number) ((Object[]) results.get(1))[1]).longValue()).isEqualTo(1L);
        assertThat(((Number) ((Object[]) results.get(2))[1]).longValue()).isEqualTo(0L);
    }
```

Adjust the `.contains(...)` substring if the actual emission differs.

- [ ] **Step 3: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestSubqueryIntegrationTests"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestSubqueryIntegrationTests.java
git commit -m "MQLv2: cover correlated outer ref in scalar count(*) subquery over array"
```

---

## Task 4: Scalar subquery without inner predicate (count of all elements)

**Goal:** Confirm `(SELECT count(*) FROM o.lineItems a)` — no WHERE — emits `count((from lineItems))` and returns the total array length.

- [ ] **Step 1: Add the test**

```java
    @Test
    void scalarSubqueryCount_noInnerPredicate() {
        var hql = "select o.id, (select count(*) from o.lineItems a) from Order o order by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        var captured = BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        assertThat(captured).contains("count((from lineItems))");

        // Order 1 has 3 items, Order 2 has 1, Order 3 has 1.
        assertThat(((Number) ((Object[]) results.get(0))[1]).longValue()).isEqualTo(3L);
        assertThat(((Number) ((Object[]) results.get(1))[1]).longValue()).isEqualTo(1L);
        assertThat(((Number) ((Object[]) results.get(2))[1]).longValue()).isEqualTo(1L);
    }
```

- [ ] **Step 2: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestSubqueryIntegrationTests.scalarSubqueryCount_noInnerPredicate"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestSubqueryIntegrationTests.java
git commit -m "MQLv2: cover count(*) of all elements (no inner predicate)"
```

---

## Task 5: Non-count scalar aggregate over unnest — throws

**Goal:** Confirm `(SELECT max(a.qty) FROM o.lineItems a)` throws `FeatureNotSupportedException` with the documented message.

- [ ] **Step 1: Add the test**

```java
    @Test
    void scalarSubquery_nonCountAggregate_throws() {
        var hql = "select o.id, (select max(a.qty) from o.lineItems a) from Order o";
        assertThatThrownBy(() -> sessionFactoryScope.inSession(
                        session -> session.createSelectionQuery(hql, Object[].class).getResultList()))
                .rootCause()
                .isInstanceOf(com.mongodb.hibernate.internal.FeatureNotSupportedException.class)
                .hasMessageContaining("count()")
                .hasMessageContaining("pipeline-argument form");
    }
```

If `.rootCause()` is too brittle, adapt to `.hasMessageContaining(...)` on the top-level exception or `.hasCauseInstanceOf(...)`.

- [ ] **Step 2: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestSubqueryIntegrationTests.scalarSubquery_nonCountAggregate_throws"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestSubqueryIntegrationTests.java
git commit -m "MQLv2: scalar subquery over array rejects non-count aggregates"
```

---

## Task 6: First IN-subquery test

**Goal:** Translate `WHERE x IN (SELECT a.col FROM o.array a)` into the count-of-subpipeline form. Implementation extends the `InSubQueryPredicate` handler.

- [ ] **Step 1: Add the test**

```java
    @Test
    void inSubquery_overStructArray() {
        var hql = "select o from Order o where 'WIDGET-1' in (select a.sku from o.lineItems a)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        var captured = BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        assertThat(captured).contains(
                "count(let $__v0 = \"WIDGET-1\" in ((from lineItems | match ($.sku == $__v0)))) > 0");

        // Orders 1 and 3 contain WIDGET-1.
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 3);
    }
```

- [ ] **Step 2: Implement the `InSubQueryPredicate` extension**

In `Mqlv2SelectTranslator.appendPredicateText`'s `InSubQueryPredicate isp` branch (around the existing `count(let ... in (from $collection | match ...))` logic), check if the inner subquery's FROM root is an unnest function table. If yes, emit the subpipeline-expression form.

Sketch:

```java
        } else if (predicate instanceof InSubQueryPredicate isp) {
            var innerSpec = isp.getSubQuery().getQueryPart().getFirstQuerySpec();
            var innerRoot = innerSpec.getFromClause().getRoots().get(0);
            if (isUnnestFunctionTable(innerRoot.getPrimaryTableReference())) {
                appendUnnestInSubQueryPredicate(sb, isp);
            } else {
                // ... existing collection-subquery logic ...
            }
        }
```

And the new helper:

```java
    /**
     * Translates {@code x [NOT] IN (SELECT a.col FROM o.array a [WHERE …])} into MQLv2
     * {@code count(let $__v0 = x in ((from <arrayPath> | match ($.col == $__v0[ AND <body>])))) [== 0 | > 0]}.
     */
    private void appendUnnestInSubQueryPredicate(StringBuilder sb, InSubQueryPredicate isp) {
        var innerSpec = isp.getSubQuery().getQueryPart().getFirstQuerySpec();
        var innerRoot = innerSpec.getFromClause().getRoots().get(0);
        var ftr = (FunctionTableReference) innerRoot.getPrimaryTableReference();

        var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                .filter(s -> !s.isVirtual())
                .toList();
        if (selections.size() != 1) {
            throw new FeatureNotSupportedException(
                    "IN-subquery over unnest() must project exactly one column");
        }
        var projectedExpr = selections.get(0).getExpression();
        var projectedColName = simpleColumnName(projectedExpr);

        var arrayPath = extractUnnestArrayPath(ftr);
        var unnestAlias = extractUnnestAlias(innerRoot);

        var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
        var outerQualifiers = collectOuterQualifiers(outerSpec);
        var correlatedBindings = new LinkedHashMap<String, String>();

        var testVarName = "$__v" + correlatedVarCounter++;

        var arrayPathSb = new StringBuilder();
        appendExprText(arrayPathSb, arrayPath);

        // Build the inner pipeline: from <arrayPath> | match ($.<col> == $__vN [and <body>])
        var bodySb = new StringBuilder("($.").append(projectedColName).append(" == ").append(testVarName).append(")");
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            var extraBody = new StringBuilder();
            appendPredicateTextWithResolver(
                    extraBody, where, insideAnyResolver(List.of(unnestAlias), outerQualifiers, correlatedBindings));
            // Combine: (existing match) AND (extra body)
            bodySb = new StringBuilder("(").append(bodySb).append(" and ").append(extraBody).append(")");
        }
        var innerPipeline = "(from " + arrayPathSb + " | match " + bodySb + ")";

        // Wrap with let bindings: $__vN for the test value, plus any outer-correlated bindings.
        var headSb = new StringBuilder();
        appendExprText(headSb, isp.getTestExpression());
        var letExpr = wrapWithLet(innerPipeline, testVarName, headSb.toString(), correlatedBindings);
        var countCmp = isp.isNegated() ? " == 0)" : " > 0)";
        sb.append("(count(").append(letExpr).append(")").append(countCmp);
    }
```

- [ ] **Step 3: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestSubqueryIntegrationTests"
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestSubqueryIntegrationTests.java
git commit -m "MQLv2: translate IN-subquery over array into count((from … | match …)) > 0"
```

---

## Task 7: NOT IN subquery

```java
    @Test
    void notInSubquery_overStructArray() {
        var hql = "select o from Order o where 'WIDGET-1' not in (select a.sku from o.lineItems a)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        var captured = BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        assertThat(captured).contains(
                "count(let $__v0 = \"WIDGET-1\" in ((from lineItems | match ($.sku == $__v0)))) == 0");

        // Only Order 2 lacks WIDGET-1.
        assertThat(results).extracting(o -> o.id).containsExactly(2);
    }
```

Run; commit.

```
git commit -m "MQLv2: cover NOT IN subquery over array"
```

---

## Task 8: Final verification

```
./gradlew clean check
git log --oneline -10
```

Expected: `BUILD SUCCESSFUL`. Six new commits since the start of Phase 4 (Task 1's failing test commits with Task 2's translator change; then Tasks 3-7).

---

## Definition of done

- `Mqlv2UnnestSubqueryIntegrationTests` with 6+ test methods green: scalar count with simple predicate, scalar count with correlated outer ref, scalar count without inner predicate, non-count aggregate throws, IN-subquery, NOT IN-subquery.
- `Mqlv2SelectTranslator` changes localized to two new helpers (`appendUnnestScalarSubquery`, `appendUnnestInSubQueryPredicate`) and two recognition forks in existing handler branches.
- All existing tests still pass.

## What this plan deliberately does not do

- Scalar-array variants — already documented as Hibernate-blocked.
- `count(distinct ...)` over an array — out of scope (and likely needs a different MQLv2 form).
- Non-count scalar aggregates — explicitly throw.

## Risks

- **`wrapWithLet` two-arity vs. three-arity overload.** The translator has two `wrapWithLet` methods:
  - `wrapWithLet(String innerPipeline, Map correlatedBindings)` — EXISTS pattern.
  - `wrapWithLet(String innerPipeline, String headVarName, String headValue, Map correlatedBindings)` — IN/ANY/ALL pattern with the test-value binding first.
  Phase 4's scalar-subquery emission uses the two-arity form (no test-value head binding); the IN-subquery emission uses the three-arity form. Match the call sites carefully.
- **`appendExprText(arrayPathSb, arrayPath)` for the outer-side array reference.** With Phase 3 in play, the translator's `hasJoins` state matters. For Phase 4 scalar/IN subqueries there are no outer unnest joins, so `hasJoins=false` and the array path emits as a bare column name (`lineItems`). If a future query combines Phase 3 JOIN with Phase 4 scalar subquery in the same statement, the qualifier behavior may need refinement.
- **Format-stage emission of the scalar subquery's column name.** Hibernate may emit the scalar subquery as a synthetic field (e.g., `_f1`). The pipeline-text assertions pin only the `count((from ...))` substring, which should be resilient to the field-name choice.
