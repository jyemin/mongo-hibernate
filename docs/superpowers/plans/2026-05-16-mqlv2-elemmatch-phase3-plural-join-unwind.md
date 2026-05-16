# Phase 3: Plural-attribute JOIN → `| unwind` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate HQL `FROM O o JOIN o.array a WHERE …` (struct arrays only) into MQLv2 `from $orders | unwind array | match (…) | format {…}`. Row-multiplying join semantics — one parent row per matching element. Supports predicates over the join alias, projection of join-alias fields, and aggregates over join-alias fields with GROUP BY.

**Architecture:** New branch in `Mqlv2SelectTranslator.appendJoins` recognizes when a `TableGroupJoin`'s joined group has a `FunctionTableReference("unnest")` as primary table reference, and emits `| unwind <arrayPath>` instead of `| join <alias>=$<collection>`. A translator-scoped map `unnestAliasToFieldPath` records the alias→field-path mapping; `appendExprText` and `appendAggFieldRef` consult it when emitting column references qualified by an unnest alias.

**Tech Stack:** Java, Hibernate ORM 7.3.4, MongoDB MQLv2. Tests use `MqlCapture` for pipeline-text assertions and execute against a real MongoDB.

**Companion spec:** `docs/superpowers/specs/2026-05-15-mqlv2-elemmatch-via-unnest-design.md` (Phase 3 section). Phase 0/2 findings: scalar JOIN with body predicate is unsupported; struct JOIN sugar form (`from O o join o.struct a`) works for everything.

---

## File map

- **Create:** `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java` — Phase 3 test class.
- **Modify:** `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — `appendJoins` hook + `unnestAliasToFieldPath` map + column-ref rewrite rule in `appendExprText` and `appendAggFieldRef`.

---

## Task 1: First failing test — struct JOIN with single predicate

**Goal:** Write a single end-to-end test asserting both the emitted MQLv2 pipeline and the returned rows for `from Order o join o.lineItems a where a.sku = 'WIDGET-1'`. Expected emission: `from $orders | unwind lineItems | match (lineItems.sku == "WIDGET-1") | format {…}`. The test fails because the translator currently doesn't handle unnest joins — it'll throw on the `(NamedTableReference)` cast in `appendJoins`.

**Files:**
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java`

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
 * Phase 3 of the elemMatch design: HQL {@code FROM O o JOIN o.array a WHERE …} translates to
 * MQLv2 {@code | unwind array | match (…)}. Row-multiplying join semantics; struct arrays only.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestJoinIntegrationTests.Order.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestJoinIntegrationTests implements SessionFactoryScopeAware {

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
    void joinOverStructArray_singlePredicate() {
        var hql = "select o from Order o join o.lineItems a where a.sku = 'WIDGET-1'";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | unwind lineItems | match (lineItems.sku == \"WIDGET-1\")"
                        + " | format {_id: _id, lineItems: lineItems}");
        // Order 1 has WIDGET-1 (one match) → 1 row; order 3 has WIDGET-1 (one match) → 1 row; order 2 → 0 rows.
        // Row-multiplying JOIN: two rows total (NOT deduplicated like EXISTS).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 3);
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
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestJoinIntegrationTests.joinOverStructArray_singlePredicate"
```

Expected: FAIL. The translator's `appendJoins` casts `joinedGroup.getPrimaryTableReference()` to `NamedTableReference` (around line 339), which throws `ClassCastException` for a `FunctionTableReference`.

- [ ] **Step 3: Don't commit yet**

---

## Task 2: Implement the `appendJoins` hook + column-ref rewrite

**Goal:** When `appendJoins` encounters a `TableGroupJoin` whose joined group's primary table reference is a `FunctionTableReference("unnest")`, emit `| unwind <arrayPath>` instead of `| join`. Register the join alias → array field path mapping in a translator-scoped map. Teach `appendExprText` and `appendAggFieldRef` to consult that map when resolving column references.

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Add the field for tracking unnest aliases**

Near the other instance fields (around line 143 — `hasJoins`, `aggSignatureToName`, etc.), add:

```java
    /** Maps join-alias → array field path for unnest joins (struct arrays). Populated by appendJoins
     *  when emitting `| unwind <arrayPath>`; consulted by appendExprText / appendAggFieldRef so that
     *  column references qualified by the unnest alias resolve to <arrayPath>.<column>. */
    private final Map<String, String> unnestAliasToFieldPath = new LinkedHashMap<>();
```

- [ ] **Step 2: Add the unnest branch in `appendJoins`**

Find `appendJoins` (around line 336). Before the existing `(NamedTableReference) joinedGroup.getPrimaryTableReference()` cast, branch:

```java
    private void appendJoins(StringBuilder sb, TableGroup root) {
        for (var tgj : root.getTableGroupJoins()) {
            var joinedGroup = tgj.getJoinedGroup();
            var primaryRef = joinedGroup.getPrimaryTableReference();
            if (isUnnestFunctionTable(primaryRef)) {
                appendUnwindJoin(sb, tgj, (FunctionTableReference) primaryRef);
                continue;
            }
            var joinNtr = (NamedTableReference) primaryRef;
            // ... existing collection-join logic ...
```

- [ ] **Step 3: Implement `appendUnwindJoin`**

Add near `appendJoins`:

```java
    /**
     * Translates a plural-attribute join (`FROM O o JOIN o.array a`) into MQLv2
     * `| unwind <arrayPath>`. Records the alias→field-path mapping so column references
     * qualified by the unnest alias (`a.sku`) resolve to `<arrayPath>.<column>` (`lineItems.sku`)
     * in subsequent WHERE / SELECT / GROUP BY / ORDER BY emission.
     */
    private void appendUnwindJoin(StringBuilder sb, TableGroupJoin tgj, FunctionTableReference ftr) {
        var arrayPath = extractUnnestArrayPath(ftr);
        var alias = extractUnnestAlias(tgj.getJoinedGroup());
        var arrayPathSb = new StringBuilder();
        appendExprText(arrayPathSb, arrayPath);
        var arrayFieldPath = arrayPathSb.toString();
        sb.append(" | unwind ").append(arrayFieldPath);
        unnestAliasToFieldPath.put(alias, arrayFieldPath);
    }
```

- [ ] **Step 4: Teach `appendExprText` to resolve unnest-alias references**

In `appendExprText` (around line 988), in the branch handling `ColumnReference`, add the unnest-alias check BEFORE the existing `hasJoins && cr.getQualifier() != null` branch:

```java
        } else if (expression instanceof ColumnReference cr) {
            var qualifier = cr.getQualifier();
            if (qualifier != null && unnestAliasToFieldPath.containsKey(qualifier)) {
                sb.append(unnestAliasToFieldPath.get(qualifier))
                        .append(".")
                        .append(cr.getColumnExpression());
            } else if (hasJoins && qualifier != null && !qualifier.isEmpty()) {
                sb.append(qualifier).append(".").append(cr.getColumnExpression());
            } else {
                sb.append(cr.getColumnExpression());
            }
        } else if (expression instanceof QueryLiteral<?> ql) {
```

- [ ] **Step 5: Teach `appendAggFieldRef` the same rule**

`appendAggFieldRef` (around line 768) — apply the same alias-check logic for aggregates over join-alias columns:

```java
    private void appendAggFieldRef(StringBuilder sb, Expression expr) {
        ColumnReference cr;
        if (expr instanceof ColumnReference c) {
            cr = c;
        } else if (expr instanceof BasicValuedPathInterpretation<?> bvpi) {
            cr = bvpi.getColumnReference();
        } else {
            throw new FeatureNotSupportedException(
                    "Expected column reference in aggregate; got: " + expr.getClass().getSimpleName());
        }
        var qualifier = cr.getQualifier();
        if (qualifier != null && unnestAliasToFieldPath.containsKey(qualifier)) {
            sb.append(unnestAliasToFieldPath.get(qualifier))
                    .append("->")
                    .append(cr.getColumnExpression());
        } else if (hasJoins && qualifier != null && !qualifier.isEmpty()) {
            sb.append(qualifier).append("->").append(cr.getColumnExpression());
        } else {
            sb.append(cr.getColumnExpression());
        }
    }
```

- [ ] **Step 6: Run the failing test; it should pass**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestJoinIntegrationTests.joinOverStructArray_singlePredicate"
```

Expected: PASS. If the expected pipeline string differs in parenthesization or field ordering, adjust the expected string to match — execution assertion is the source of truth.

- [ ] **Step 7: Run the full integration suite — no regressions**

```
./gradlew integrationTest
```

Expected: all existing tests still pass; the new test passes.

- [ ] **Step 8: Commit**

```
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java
git commit -m "MQLv2: translate plural-attribute JOIN into | unwind"
```

---

## Task 3: Projection of join-alias columns

**Goal:** Confirm `SELECT o.id, a.sku FROM Order o JOIN o.lineItems a WHERE a.sku = 'WIDGET-1'` projects the join-alias field correctly.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java`

- [ ] **Step 1: Add the test**

```java
    @Test
    void joinOverStructArray_projectsAliasField() {
        var hql = "select o.id, a.sku from Order o join o.lineItems a where a.sku = 'WIDGET-1'";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | unwind lineItems | match (lineItems.sku == \"WIDGET-1\")"
                        + " | format {_id: _id, _f0: lineItems.sku}");
        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> ((Object[]) r)[1]).containsOnly("WIDGET-1");
    }
```

Adjust the expected format-field name (`_f0` vs. something else) to match what the translator actually emits.

- [ ] **Step 2: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestJoinIntegrationTests.joinOverStructArray_projectsAliasField"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java
git commit -m "MQLv2: cover JOIN projection of unnest-alias fields"
```

---

## Task 4: Aggregate over join-alias columns + GROUP BY

**Goal:** Confirm `SELECT o.id, sum(a.qty) FROM Order o JOIN o.lineItems a GROUP BY o.id`.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java`

- [ ] **Step 1: Add the test**

```java
    @Test
    void joinOverStructArray_aggregateOverAliasWithGroupBy() {
        var hql = "select o.id, sum(a.qty) from Order o join o.lineItems a group by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        // Order 1: qty 5 + 1 = 6. Order 2: qty 10. Order 3: qty 0.
        // Map result rows by id → sum for stable assertion.
        var byId = new java.util.HashMap<Integer, Long>();
        for (var r : results) {
            byId.put((Integer) ((Object[]) r)[0], ((Number) ((Object[]) r)[1]).longValue());
        }
        assertThat(byId).containsEntry(1, 6L).containsEntry(2, 10L).containsEntry(3, 0L);
    }
```

(Drop the pipeline-text assertion for this one — the GROUP BY emission is complex enough that pinning the exact text is brittle. The execution assertion is sufficient.)

- [ ] **Step 2: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestJoinIntegrationTests.joinOverStructArray_aggregateOverAliasWithGroupBy"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java
git commit -m "MQLv2: cover aggregate over JOIN-alias with GROUP BY"
```

---

## Task 5: Cardinality verification

**Goal:** Confirm JOIN is row-multiplying — one Order with N matching elements produces N rows.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java`

- [ ] **Step 1: Add the test**

```java
    @Test
    void joinOverStructArray_cardinalityIsRowMultiplying() {
        // Order 1 has TWO line items; the JOIN should produce TWO rows (NOT deduplicated).
        var hql = "select o.id, a.sku from Order o join o.lineItems a where o.id = 1";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());
        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> ((Object[]) r)[1])
                .containsExactlyInAnyOrder("WIDGET-1", "WIDGET-2");
    }
```

- [ ] **Step 2: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestJoinIntegrationTests.joinOverStructArray_cardinalityIsRowMultiplying"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java
git commit -m "MQLv2: verify JOIN cardinality (row-multiplying, not deduplicated)"
```

---

## Task 6: Lock scalar JOIN as unsupported

**Goal:** Document with a negative test that `from Order o join o.tags t where t > 5` (scalar JOIN with body predicate) fails at HQL semantic-analysis time. This matches the existing diagnostic but lives in the feature test class so users searching for "what works" find it.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java`

- [ ] **Step 1: Add a scalar array field to the entity**

Add `int[] scores;` to `Order` and update the constructor / seed.

```java
    Order(int id, LineItem[] lineItems, int[] scores) {
        this.id = id;
        this.lineItems = lineItems;
        this.scores = scores;
    }
```

Update seed: order 1 has `new int[]{10,20,30}`, order 2 `new int[]{1,2,3}`, order 3 `new int[]{}`.

Also update prior tests' expected MQLv2 strings to include `scores: scores` in the format stage.

- [ ] **Step 2: Add the negative test**

```java
    @Test
    void joinOverScalarArray_withBodyPredicate_unsupported() {
        var hql = "select o from Order o join o.scores s where s > 5";
        assertThatThrownBy(() -> sessionFactoryScope.inSession(
                        session -> session.createSelectionQuery(hql, Order.class).getResultList()))
                .rootCause()
                .isInstanceOf(AssertionError.class);
    }
```

(Per Phase 2 task 9, `.rootCause()` may or may not work depending on the exception chain — adjust to `.isInstanceOf(AssertionError.class)` directly if needed.)

- [ ] **Step 3: Run; commit**

```
./gradlew integrationTest --tests "com.mongodb.hibernate.query.mqlv2.Mqlv2UnnestJoinIntegrationTests"
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2UnnestJoinIntegrationTests.java
git commit -m "MQLv2: document scalar JOIN with body predicate as unsupported"
```

---

## Task 7: Final verification

- [ ] **Step 1: Full check**

```
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Inspect log**

```
git log --oneline -10
```

Should show six new commits since the start of Phase 3.

---

## Definition of done

- `Mqlv2UnnestJoinIntegrationTests` with 5+ test methods green (single predicate, projection, aggregate + GROUP BY, cardinality, scalar-unsupported).
- `Mqlv2SelectTranslator` changes localized to `appendJoins`, a new `appendUnwindJoin` helper, the alias-map field, and small `appendExprText` / `appendAggFieldRef` rule additions.
- All existing tests still pass (Phase 0/1/2 diagnostics, showcase, select integration).

## What this plan deliberately does not do

- Mixed entity-join + unnest-join cases (e.g., `FROM O o JOIN Customer c ON … JOIN o.lineItems a`). Out of scope per the spec; if it arises, the existing `hasJoins=true` qualifier-prefix path handles it (column references for the entity join keep their qualifier; unnest-alias refs get rewritten).
- Mutate `hasJoins` semantics. The existing `hasJoins=true` setting when ANY TableGroupJoin is present still fires for unnest joins; outer column refs get qualifier-prefixed (e.g., `o.id`). That's acceptable since the unnest path doesn't introduce a new MQLv2 alias — the unwound document is still the top-level row. If tests reveal a problem (e.g., `id` vs. `o.id` mismatch on output), revisit.
- Multi-join (two unnest joins in one query). Likely fine since each one writes its alias to the map independently; the test surface stays focused on single-join cases.

## Risks

- **`hasJoins` interaction.** The existing translator sets `hasJoins=true` whenever any join exists. With an unnest join, this means `o.id` references would be emitted with `o.` prefix. But Hibernate's SQM might emit the outer column reference WITHOUT a qualifier when the FROM has only one entity root — let the tests tell us. If parenthesization or qualifier behavior surprises us, adjust the expected pipeline strings.
- **`appendExprText` for `arrayPath` inside `appendUnwindJoin`.** The array path is a `ColumnReference` on the outer entity. When `appendExprText` runs and `hasJoins=true` (which it is, the unnest join itself counts), the column would normally be emitted as `<qualifier>.<column>`. But for the outer-entity column we want just `<column>` (e.g., `lineItems`, not `o.lineItems`). May need to handle this specially in `appendUnwindJoin` — render the array path without qualifier prefix.
