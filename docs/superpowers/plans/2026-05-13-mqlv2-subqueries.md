# MQLv2 Subquery and Set Operation Support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `Mqlv2SelectTranslator` to translate all HQL subquery and set-operation forms into MQLv2 subpipeline expressions.

**Architecture:** All new logic added directly to `Mqlv2SelectTranslator`. A new `appendQuerySpecPipeline` helper handles inner `QuerySpec` translation; correlated outer references are detected via `Set<String> outerQualifiers` and bound via a `LinkedHashMap<String, String> correlatedBindings` that maps `"qualifier.column"` → `"$__v0"`, `"$__v1"`, etc. Set operations (`QueryGroup`) are handled by updating `translate()` to dispatch on `QueryPart` type.

**Tech Stack:** Java 21, Hibernate ORM 6.6, MQLv2, JUnit 5, AssertJ

---

## File Map

| File | Change |
|---|---|
| `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` | All subquery/set-op logic |
| `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java` | New tests for all forms |
| `docs/mqlv2-showcase.md` | Add subquery/set-op examples |

---

## Task 1: `InListPredicate` — `x IN (v1, v2, v3)`

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java:452-490`
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java`

- [ ] **Step 1: Write the failing tests**

Add to `Mqlv2SelectIntegrationTests` (after the last test at line 548, before the closing `}`):

```java
// ---- Subquery and set operation tests ----

@Test
void testInList() {
    sessionFactoryScope.inSession(session -> {
        // ages 25, 30, 35 → all three customers match
        var result = session.createSelectionQuery(
                        "from Customer c where c.age in (25, 30, 35)", Customer.class)
                .getResultList();
        assertThat(result.stream().map(c -> c.name))
                .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
    });
}

@Test
void testNotInList() {
    sessionFactoryScope.inSession(session -> {
        // not in (25, 30) → only Carol (35)
        var result = session.createSelectionQuery(
                        "from Customer c where c.age not in (25, 30)", Customer.class)
                .getResultList();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name).isEqualTo("Carol");
    });
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testInList" --tests "*.Mqlv2SelectIntegrationTests.testNotInList"
```

Expected: FAIL with `FeatureNotSupportedException`

- [ ] **Step 3: Implement `InListPredicate` in `appendPredicateText`**

In `Mqlv2SelectTranslator.java`, replace the final `else` branch of `appendPredicateText` (lines 486-489):

```java
        } else if (predicate instanceof InListPredicate ilp) {
            var exprs = ilp.getListExpressions();
            var negated = ilp.isNegated();
            var op = negated ? " != " : " == ";
            var logic = negated ? " && " : " || ";
            sb.append("(");
            for (var i = 0; i < exprs.size(); i++) {
                if (i > 0) sb.append(logic);
                sb.append("(");
                appendExprText(sb, ilp.getTestExpression());
                sb.append(op);
                appendExprText(sb, exprs.get(i));
                sb.append(")");
            }
            sb.append(")");
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate: " + predicate.getClass().getSimpleName());
        }
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testInList" --tests "*.Mqlv2SelectIntegrationTests.testNotInList"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java
git commit -m "MQLv2: translate InListPredicate (x IN / NOT IN literal list)"
```

---

## Task 2: Core correlated mechanism + `ExistsPredicate`

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java`

- [ ] **Step 1: Write the failing tests**

Add to `Mqlv2SelectIntegrationTests`:

```java
@Test
void testExists() {
    sessionFactoryScope.inSession(session -> {
        // customers that have at least one order
        var result = session.createSelectionQuery(
                        "from Customer c where exists (select 1 from Order o where o.customerId = c.id)",
                        Customer.class)
                .getResultList();
        assertThat(result.stream().map(c -> c.name))
                .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
    });
}

@Test
void testNotExists() {
    sessionFactoryScope.inSession(session -> {
        // customers with no orders having total > 500 (none qualify)
        var result = session.createSelectionQuery(
                        "from Customer c where not exists (select 1 from Order o where o.customerId = c.id and o.total > 500)",
                        Customer.class)
                .getResultList();
        assertThat(result.stream().map(c -> c.name))
                .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
    });
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testExists" --tests "*.Mqlv2SelectIntegrationTests.testNotExists"
```

Expected: FAIL with `FeatureNotSupportedException`

- [ ] **Step 3: Add correlated binding infrastructure and `appendQuerySpecPipeline`**

After the class fields (after line 136), add a new field:

```java
    private int correlatedVarCounter = 0;
```

Add two new private methods after `appendLimit` (after line 297):

```java
    /**
     * Translates an inner QuerySpec to a MQLv2 pipeline text, binding any ColumnReferences whose
     * qualifier is in outerQualifiers as correlated variables ($__v0, $__v1, ...) in the returned
     * correlatedBindings map.
     */
    private String appendQuerySpecPipeline(
            QuerySpec innerSpec,
            Set<String> outerQualifiers,
            LinkedHashMap<String, String> correlatedBindings) {
        var innerSb = new StringBuilder();
        var root = innerSpec.getFromClause().getRoots().get(0);
        var ntr = (NamedTableReference) root.getPrimaryTableReference();
        innerSb.append("from $").append(ntr.getTableExpression());
        var where = innerSpec.getWhereClauseRestrictions();
        if (where != null && !where.isEmpty()) {
            innerSb.append(" | match ");
            appendPredicateTextCorrelated(innerSb, where, outerQualifiers, correlatedBindings);
        }
        return innerSb.toString();
    }

    private void appendPredicateTextCorrelated(
            StringBuilder sb,
            Predicate predicate,
            Set<String> outerQualifiers,
            LinkedHashMap<String, String> correlatedBindings) {
        if (predicate instanceof ComparisonPredicate cp) {
            sb.append("(");
            appendExprTextCorrelated(sb, cp.getLeftHandExpression(), outerQualifiers, correlatedBindings);
            sb.append(" ").append(comparisonOpSurface(cp.getOperator())).append(" ");
            appendExprTextCorrelated(sb, cp.getRightHandExpression(), outerQualifiers, correlatedBindings);
            sb.append(")");
        } else if (predicate instanceof Junction junction) {
            var preds = junction.getPredicates();
            var op = junction.getNature() == Junction.Nature.CONJUNCTION ? "and" : "or";
            sb.append("(");
            for (var i = 0; i < preds.size(); i++) {
                if (i > 0) sb.append(" ").append(op).append(" ");
                appendPredicateTextCorrelated(sb, preds.get(i), outerQualifiers, correlatedBindings);
            }
            sb.append(")");
        } else if (predicate instanceof NegatedPredicate np) {
            sb.append("(not ");
            appendPredicateTextCorrelated(sb, np.getPredicate(), outerQualifiers, correlatedBindings);
            sb.append(")");
        } else if (predicate instanceof NullnessPredicate np) {
            if (np.isNegated()) {
                sb.append("(not isNullish(");
                appendExprTextCorrelated(sb, np.getExpression(), outerQualifiers, correlatedBindings);
                sb.append("))");
            } else {
                sb.append("isNullish(");
                appendExprTextCorrelated(sb, np.getExpression(), outerQualifiers, correlatedBindings);
                sb.append(")");
            }
        } else if (predicate instanceof BooleanExpressionPredicate bp) {
            sb.append("(");
            appendExprTextCorrelated(sb, bp.getExpression(), outerQualifiers, correlatedBindings);
            sb.append(bp.isNegated() ? " == false)" : " == true)");
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate in subquery: " + predicate.getClass().getSimpleName());
        }
    }

    private void appendExprTextCorrelated(
            StringBuilder sb,
            Expression expression,
            Set<String> outerQualifiers,
            LinkedHashMap<String, String> correlatedBindings) {
        if (expression instanceof BasicValuedPathInterpretation<?> bvpi) {
            appendExprTextCorrelated(sb, bvpi.getColumnReference(), outerQualifiers, correlatedBindings);
        } else if (expression instanceof ColumnReference cr) {
            var qualifier = cr.getQualifier();
            if (qualifier != null && outerQualifiers.contains(qualifier)) {
                var key = qualifier + "." + cr.getColumnExpression();
                var varName = correlatedBindings.computeIfAbsent(
                        key, k -> "$__v" + correlatedVarCounter++);
                sb.append(varName);
            } else if (qualifier != null && !qualifier.isEmpty()) {
                sb.append(qualifier).append(".").append(cr.getColumnExpression());
            } else {
                sb.append(cr.getColumnExpression());
            }
        } else {
            // delegate non-correlated expressions to the standard path
            appendExprText(sb, expression);
        }
    }

    /**
     * Wraps innerPipeline text with let bindings for all entries in correlatedBindings.
     * If correlatedBindings is empty, returns the pipeline unwrapped.
     * Result: "let $__v0 = qualifier.col, $__v1 = q2.col2 in (innerPipeline)"
     */
    private static String wrapWithLet(
            String innerPipeline, LinkedHashMap<String, String> correlatedBindings) {
        if (correlatedBindings.isEmpty()) {
            return "(" + innerPipeline + ")";
        }
        var sb = new StringBuilder("let ");
        var first = true;
        for (var entry : correlatedBindings.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            // entry.getValue() is "$__v0", entry.getKey() is "qualifier.column"
            // MQLv2 let syntax: $varName = expr
            sb.append(entry.getValue()).append(" = ");
            // key is "qualifier.column" → emit as qualifier.column field path
            sb.append(entry.getKey());
        }
        sb.append(" in (").append(innerPipeline).append(")");
        return sb.toString();
    }
```

- [ ] **Step 4: Add outer qualifier extraction helper**

Add after `collectAffectedTableNames` (after line 620):

```java
    private static Set<String> collectOuterQualifiers(QuerySpec outerSpec) {
        var result = new LinkedHashSet<String>();
        var roots = outerSpec.getFromClause().getRoots();
        for (var root : roots) {
            var ntr = (NamedTableReference) root.getPrimaryTableReference();
            var alias = ntr.getIdentificationVariable();
            if (alias != null) result.add(alias);
            for (var tgj : root.getTableGroupJoins()) {
                var joinNtr = (NamedTableReference) tgj.getJoinedGroup().getPrimaryTableReference();
                var joinAlias = joinNtr.getIdentificationVariable();
                if (joinAlias != null) result.add(joinAlias);
            }
        }
        return result;
    }
```

- [ ] **Step 5: Implement `ExistsPredicate` in `appendPredicateText`**

In `appendPredicateText`, replace the `else` throw at the end with:

```java
        } else if (predicate instanceof ExistsPredicate ep) {
            var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();
            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
            var wrapped = wrapWithLet(innerPipeline, correlatedBindings);
            if (ep.isNegated()) {
                sb.append("(count(").append(wrapped).append(") == 0)");
            } else {
                sb.append("(count(").append(wrapped).append(") > 0)");
            }
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported predicate: " + predicate.getClass().getSimpleName());
        }
```

Note: `ExistsPredicate.isNegated()` does not exist — Hibernate wraps NOT EXISTS as `NegatedPredicate(ExistsPredicate(...))`. The `ep.isNegated()` call above will not compile. Use this instead:

```java
        } else if (predicate instanceof ExistsPredicate ep) {
            var innerSpec = ep.getExpression().getQueryPart().getFirstQuerySpec();
            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();
            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
            var wrapped = wrapWithLet(innerPipeline, correlatedBindings);
            sb.append("(count(").append(wrapped).append(") > 0)");
        } else {
```

`NOT EXISTS` is handled by `NegatedPredicate` wrapping an `ExistsPredicate`, so no `isNegated()` needed.

- [ ] **Step 6: Run tests to verify they pass**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testExists" --tests "*.Mqlv2SelectIntegrationTests.testNotExists"
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java
git commit -m "MQLv2: correlated binding infrastructure + ExistsPredicate (EXISTS/NOT EXISTS)"
```

---

## Task 3: `InSubQueryPredicate` — `x IN (subquery)`

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java:452-490`
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java`

- [ ] **Step 1: Write the failing tests**

Add to `Mqlv2SelectIntegrationTests`:

```java
@Test
void testInSubQuery() {
    sessionFactoryScope.inSession(session -> {
        // customers whose id appears in orders with total > 100
        // orders with total > 100: id=10 (cust=1, total=150), id=12 (cust=2, total=200)
        var result = session.createSelectionQuery(
                        "from Customer c where c.id in (select o.customerId from Order o where o.total > 100)",
                        Customer.class)
                .getResultList();
        assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob");
    });
}

@Test
void testNotInSubQuery() {
    sessionFactoryScope.inSession(session -> {
        // customers whose id does NOT appear in any order
        // all 3 customers have orders, so result is empty
        var result = session.createSelectionQuery(
                        "from Customer c where c.id not in (select o.customerId from Order o)",
                        Customer.class)
                .getResultList();
        assertThat(result).isEmpty();
    });
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testInSubQuery" --tests "*.Mqlv2SelectIntegrationTests.testNotInSubQuery"
```

Expected: FAIL with `FeatureNotSupportedException`

- [ ] **Step 3: Implement `InSubQueryPredicate` in `appendPredicateText`**

Add before the `ExistsPredicate` branch in `appendPredicateText`:

```java
        } else if (predicate instanceof InSubQueryPredicate isp) {
            var innerSpec = isp.getSubQuery().getQueryPart().getFirstQuerySpec();
            // The subquery projects exactly one column; find its column name
            var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual())
                    .findFirst()
                    .orElseThrow(() -> new FeatureNotSupportedException("IN subquery must project at least one column"))
                    .getExpression();
            var projectedColName = simpleColumnName(projectedExpr);

            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();

            // Bind the test expression as $__v0
            var testVarName = "$__v" + correlatedVarCounter++;
            correlatedBindings.put("__testExpr__", testVarName);

            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
            // Add a match stage that filters the projected column == the test variable
            innerPipeline = innerPipeline + " | match (" + projectedColName + " == " + testVarName + ")";

            // Build the let prefix: first binding is the test expression
            var letSb = new StringBuilder("let ").append(testVarName).append(" = ");
            var testSb = new StringBuilder();
            appendExprText(testSb, isp.getTestExpression());
            letSb.append(testSb);

            // Append any correlated outer bindings (skip the __testExpr__ sentinel)
            for (var entry : correlatedBindings.entrySet()) {
                if (entry.getKey().equals("__testExpr__")) continue;
                letSb.append(", ").append(entry.getValue()).append(" = ").append(entry.getKey());
            }

            letSb.append(" in (").append(innerPipeline).append(")");
            var countExpr = "count(" + letSb + ")";

            if (isp.isNegated()) {
                sb.append("(").append(countExpr).append(" == 0)");
            } else {
                sb.append("(").append(countExpr).append(" > 0)");
            }
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testInSubQuery" --tests "*.Mqlv2SelectIntegrationTests.testNotInSubQuery"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java
git commit -m "MQLv2: translate InSubQueryPredicate (x IN / NOT IN subquery)"
```

---

## Task 4: `ModifiedSubQueryExpression` — `x op ALL/ANY/SOME (subquery)`

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java`

- [ ] **Step 1: Write the failing tests**

Add to `Mqlv2SelectIntegrationTests`:

```java
@Test
void testAnySubQuery() {
    sessionFactoryScope.inSession(session -> {
        // customers whose age > any order total
        // order totals: 150, 80, 200, 50 → any customer age (30, 25, 35) > 80? Yes, Alice(30)>80? No.
        // Actually age > any total: is there any total < age?
        // Alice(30): totals < 30 → none. Bob(25): none. Carol(35): none.
        // Wait — re-check: order totals are 150, 80, 200, 50
        // Alice age 30: 30 > 50? No. Bob 25: 25 > 50? No. Carol 35: 35 > 50? No.
        // Actually none qualify. Let's use a different query that does have results.
        // customers whose age > any (select o.total from Order o where o.total < 30)
        // No orders have total < 30. So use:
        // customers whose id > any (select o.customerId from Order o where o.customerId < 3)
        // customerIds in qualifying orders: 1, 1, 2 → any cust id > 1? Alice=1 No, Bob=2 Yes, Carol=3 Yes
        var result = session.createSelectionQuery(
                        "from Customer c where c.id > any (select o.customerId from Order o where o.customerId < 3)",
                        Customer.class)
                .getResultList();
        assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Bob", "Carol");
    });
}

@Test
void testAllSubQuery() {
    sessionFactoryScope.inSession(session -> {
        // customers whose id > all customerIds in orders where total > 100
        // qualifying orders: id=10(cust=1), id=12(cust=2) → customerIds {1,2}
        // c.id > all {1, 2}: Carol(3) > 2 ✓, Alice(1) > 2 ✗, Bob(2) > 2 ✗
        var result = session.createSelectionQuery(
                        "from Customer c where c.id > all (select o.customerId from Order o where o.total > 100)",
                        Customer.class)
                .getResultList();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name).isEqualTo("Carol");
    });
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testAnySubQuery" --tests "*.Mqlv2SelectIntegrationTests.testAllSubQuery"
```

Expected: FAIL with `FeatureNotSupportedException`

- [ ] **Step 3: Add operator inversion helper and implement `ModifiedSubQueryExpression`**

Add a static helper method after `comparisonOpSurface`:

```java
    private static String invertComparisonOp(ComparisonOperator op) {
        return switch (op) {
            case EQUAL -> "!=";
            case NOT_EQUAL -> "==";
            case LESS_THAN -> ">=";
            case LESS_THAN_OR_EQUAL -> ">";
            case GREATER_THAN -> "<=";
            case GREATER_THAN_OR_EQUAL -> "<";
            default -> throw new FeatureNotSupportedException("Unsupported comparison operator: " + op);
        };
    }
```

`ModifiedSubQueryExpression` is an `Expression` node, so it must be handled in `appendExprText`. However, it appears in predicate position when HQL generates `x op ANY(sub)` as a `ComparisonPredicate` whose right-hand side is a `ModifiedSubQueryExpression`. Check what Hibernate actually generates at runtime; the most common form is that Hibernate emits a `ComparisonPredicate(lhs, op, ModifiedSubQueryExpression(subquery, modifier))`.

Add a branch in `appendPredicateText` to intercept `ComparisonPredicate` whose RHS is `ModifiedSubQueryExpression`:

Replace the `ComparisonPredicate` branch in `appendPredicateText` (lines 453-459) with:

```java
        if (predicate instanceof ComparisonPredicate cp
                && cp.getRightHandExpression() instanceof ModifiedSubQueryExpression msqe) {
            var modifier = msqe.getModifier();
            var innerSpec = msqe.getSubQuery().getQueryPart().getFirstQuerySpec();
            var projectedExpr = innerSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual())
                    .findFirst()
                    .orElseThrow()
                    .getExpression();
            var projectedColName = simpleColumnName(projectedExpr);

            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();

            var testVarName = "$__v" + correlatedVarCounter++;
            correlatedBindings.put("__testExpr__", testVarName);

            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);

            // ANY: count(inner | match col op testVar) > 0
            // ALL: count(inner | match col inverse_op testVar) == 0
            String matchOp;
            String countCmp;
            if (modifier == ModifiedSubQueryExpression.Modifier.ALL) {
                matchOp = invertComparisonOp(cp.getOperator());
                countCmp = " == 0)";
            } else {
                // ANY or SOME
                matchOp = comparisonOpSurface(cp.getOperator());
                countCmp = " > 0)";
            }
            innerPipeline = innerPipeline + " | match (" + projectedColName + " " + matchOp + " " + testVarName + ")";

            var letSb = new StringBuilder("let ").append(testVarName).append(" = ");
            var testSb = new StringBuilder();
            appendExprText(testSb, cp.getLeftHandExpression());
            letSb.append(testSb);
            for (var entry : correlatedBindings.entrySet()) {
                if (entry.getKey().equals("__testExpr__")) continue;
                letSb.append(", ").append(entry.getValue()).append(" = ").append(entry.getKey());
            }
            letSb.append(" in (").append(innerPipeline).append(")");

            sb.append("(count(").append(letSb).append(")").append(countCmp);
        } else if (predicate instanceof ComparisonPredicate cp) {
            sb.append("(");
            appendExprText(sb, cp.getLeftHandExpression());
            sb.append(" ").append(comparisonOpSurface(cp.getOperator())).append(" ");
            appendExprText(sb, cp.getRightHandExpression());
            sb.append(")");
        } else if (predicate instanceof Junction junction) {
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testAnySubQuery" --tests "*.Mqlv2SelectIntegrationTests.testAllSubQuery"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java
git commit -m "MQLv2: translate ModifiedSubQueryExpression (ANY/ALL/SOME subqueries)"
```

---

## Task 5: Scalar subquery in SELECT

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java`

- [ ] **Step 1: Write the failing test**

Add to `Mqlv2SelectIntegrationTests`:

```java
@Test
void testScalarSubquery() {
    sessionFactoryScope.inSession(session -> {
        // (name, order_count) per customer
        // Alice: 2 orders, Bob: 1 order, Carol: 1 order
        var result = session.createSelectionQuery(
                        "select c.name, (select count(o) from Order o where o.customerId = c.id) from Customer c",
                        Object[].class)
                .getResultList();
        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(r -> r[0], r -> r[1])
                .containsExactlyInAnyOrder(
                        tuple("Alice", 2L),
                        tuple("Bob", 1L),
                        tuple("Carol", 1L));
    });
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testScalarSubquery"
```

Expected: FAIL with `FeatureNotSupportedException`

- [ ] **Step 3: Handle `SelectStatement` as expression in `appendExprText`**

`appendFormat` calls `appendExprText` for each SELECT-clause expression. When a scalar subquery appears in the SELECT list, Hibernate wraps it as a `SelectStatement` node. Add a branch in `appendExprText` before the final `else` throw:

```java
        } else if (expression instanceof SelectStatement ss) {
            var innerSpec = ss.getQueryPart().getFirstQuerySpec();
            // Must be a single-column aggregate subquery
            var selections = innerSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual()).toList();
            if (selections.size() != 1 || !isAggregateFunction(selections.get(0).getExpression())) {
                throw new FeatureNotSupportedException(
                        "Scalar subquery in SELECT must project a single aggregate function");
            }
            var aggFn = (SelfRenderingFunctionSqlAstExpression) selections.get(0).getExpression();
            var outerSpec = selectStatement.getQueryPart().getFirstQuerySpec();
            var outerQualifiers = collectOuterQualifiers(outerSpec);
            var correlatedBindings = new LinkedHashMap<String, String>();
            var innerPipeline = appendQuerySpecPipeline(innerSpec, outerQualifiers, correlatedBindings);
            var wrapped = wrapWithLet(innerPipeline, correlatedBindings);
            // Emit: aggFn(wrapped) — e.g. count(let $__v0 = _id in (from $orders | match ...))
            var aggName = aggFn.getFunctionName();
            sb.append(aggName).append("(").append(wrapped).append(")");
        } else {
            throw new FeatureNotSupportedException(
                    "Unsupported expression: " + expression.getClass().getSimpleName());
        }
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testScalarSubquery"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java
git commit -m "MQLv2: translate scalar aggregate subquery in SELECT"
```

---

## Task 6: Set operations — `QueryGroup` (UNION, UNION ALL, INTERSECT, EXCEPT)

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java:174-216`
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java`

- [ ] **Step 1: Write the failing tests**

Add to `Mqlv2SelectIntegrationTests`:

```java
@Test
void testUnionAll() {
    sessionFactoryScope.inSession(session -> {
        // age > 30 (Carol) UNION ALL active=true (Alice, Carol) → 3 rows (Carol appears twice)
        var result = session.createSelectionQuery(
                        "from Customer c where c.age > 30 union all from Customer c where c.active = true",
                        Customer.class)
                .getResultList();
        assertThat(result).hasSize(3);
        assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Carol", "Alice", "Carol");
    });
}

@Test
void testUnion() {
    sessionFactoryScope.inSession(session -> {
        // age > 30 (Carol) UNION active=true (Alice, Carol) → 2 distinct rows
        var result = session.createSelectionQuery(
                        "from Customer c where c.age > 30 union from Customer c where c.active = true",
                        Customer.class)
                .getResultList();
        assertThat(result).hasSize(2);
        assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Carol", "Alice");
    });
}

@Test
void testIntersect() {
    sessionFactoryScope.inSession(session -> {
        // age > 30 (Carol) INTERSECT active=true (Alice, Carol) → Carol
        var result = session.createSelectionQuery(
                        "from Customer c where c.age > 30 intersect from Customer c where c.active = true",
                        Customer.class)
                .getResultList();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name).isEqualTo("Carol");
    });
}

@Test
void testExcept() {
    sessionFactoryScope.inSession(session -> {
        // active=true (Alice, Carol) EXCEPT age > 30 (Carol) → Alice
        var result = session.createSelectionQuery(
                        "from Customer c where c.active = true except from Customer c where c.age > 30",
                        Customer.class)
                .getResultList();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name).isEqualTo("Alice");
    });
}

@Test
void testIntersectAllThrows() {
    sessionFactoryScope.inSession(session -> {
        assertThatThrownBy(() -> session.createSelectionQuery(
                        "from Customer c where c.age > 30 intersect all from Customer c where c.active = true",
                        Customer.class)
                .getResultList())
                .isInstanceOf(FeatureNotSupportedException.class);
    });
}

@Test
void testExceptAllThrows() {
    sessionFactoryScope.inSession(session -> {
        assertThatThrownBy(() -> session.createSelectionQuery(
                        "from Customer c where c.active = true except all from Customer c where c.age > 30",
                        Customer.class)
                .getResultList())
                .isInstanceOf(FeatureNotSupportedException.class);
    });
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testUnionAll" --tests "*.Mqlv2SelectIntegrationTests.testUnion" --tests "*.Mqlv2SelectIntegrationTests.testIntersect" --tests "*.Mqlv2SelectIntegrationTests.testExcept"
```

Expected: FAIL with `FeatureNotSupportedException`

- [ ] **Step 3: Add `translateQuerySpec` helper and update `translate()` to handle `QueryGroup`**

Add a new `translateQuerySpec` method that contains the existing `translate()` body for the `QuerySpec` case:

```java
    private String translateQuerySpecToMqlv2(QuerySpec querySpec) {
        var root = querySpec.getFromClause().getRoots().get(0);
        var localHasJoins = !root.getTableGroupJoins().isEmpty();
        // Temporarily override hasJoins for this spec
        var savedHasJoins = this.hasJoins;
        var savedAggMap = new LinkedHashMap<>(this.aggSignatureToName);
        this.hasJoins = localHasJoins;
        this.aggSignatureToName.clear();

        var sb = new StringBuilder();
        appendFrom(sb, root);
        appendJoins(sb, root);
        appendMatch(sb, querySpec);
        List<@Nullable String> aggNames = null;
        if (!querySpec.getGroupByClauseExpressions().isEmpty()) {
            aggNames = buildAggNames(querySpec.getSelectClause());
            appendGroup(sb, querySpec.getGroupByClauseExpressions(), querySpec.getSelectClause(), aggNames);
            appendHaving(sb, querySpec);
        }
        appendSort(sb, querySpec);
        appendLimit(sb, querySpec, null);
        appendFormat(sb, querySpec.getSelectClause(), aggNames);
        if (querySpec.getSelectClause().isDistinct()) {
            sb.append(" | distinct");
        }

        this.hasJoins = savedHasJoins;
        this.aggSignatureToName.clear();
        this.aggSignatureToName.putAll(savedAggMap);
        return sb.toString();
    }
```

Note: `appendLimit` accepts a `QueryOptions` param; add a `@Nullable QueryOptions queryOptions` param to the existing `appendLimit` signature and guard null:

```java
    private void appendLimit(StringBuilder sb, QuerySpec querySpec, @Nullable QueryOptions queryOptions) {
        if (queryOptions != null && queryOptions.getLimit() != null && queryOptions.getLimit().getMaxRows() != null) {
            throw new FeatureNotSupportedException(
                    "Use HQL LIMIT clause; setMaxResults() is not yet supported in MQLv2 mode");
        }
        // ... rest unchanged
    }
```

Update the existing `translate()` call from `appendLimit(sb, querySpec, queryOptions)` to `appendLimit(sb, querySpec, queryOptions)` (no change needed).

Now add a `translateQueryGroup` method:

```java
    private String translateQueryGroupToMqlv2(QueryGroup queryGroup) {
        import org.hibernate.query.sqm.SetOperator;
        var parts = queryGroup.getQueryParts();
        var operator = queryGroup.getSetOperator();

        if (operator == SetOperator.INTERSECT_ALL || operator == SetOperator.EXCEPT_ALL) {
            throw new FeatureNotSupportedException(
                    operator + " is not supported in MQLv2");
        }

        var pipelines = parts.stream()
                .map(p -> "(" + translateQuerySpecToMqlv2(p.getFirstQuerySpec()) + ")")
                .toList();

        return switch (operator) {
            case UNION_ALL -> {
                // from << (p1), (p2), ... >> | unwind $*
                var sb = new StringBuilder("from << ");
                for (var i = 0; i < pipelines.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(pipelines.get(i));
                }
                sb.append(" >> | unwind $*");
                yield sb.toString();
            }
            case UNION -> {
                // from << (p1), (p2), ... >> | unwind $* | distinct
                var sb = new StringBuilder("from << ");
                for (var i = 0; i < pipelines.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(pipelines.get(i));
                }
                sb.append(" >> | unwind $* | distinct");
                yield sb.toString();
            }
            case INTERSECT -> {
                // left | match count(let $__v0 = $ in (right | match $ == $__v0)) > 0
                var leftPipeline = translateQuerySpecToMqlv2(parts.get(0).getFirstQuerySpec());
                var rightPipeline = translateQuerySpecToMqlv2(parts.get(1).getFirstQuerySpec());
                var varName = "$__v" + correlatedVarCounter++;
                yield leftPipeline + " | match (count(let " + varName + " = $ in (" + rightPipeline
                        + " | match ($ == " + varName + "))) > 0)";
            }
            case EXCEPT -> {
                // left | match count(let $__v0 = $ in (right | match $ == $__v0)) == 0
                var leftPipeline = translateQuerySpecToMqlv2(parts.get(0).getFirstQuerySpec());
                var rightPipeline = translateQuerySpecToMqlv2(parts.get(1).getFirstQuerySpec());
                var varName = "$__v" + correlatedVarCounter++;
                yield leftPipeline + " | match (count(let " + varName + " = $ in (" + rightPipeline
                        + " | match ($ == " + varName + "))) == 0)";
            }
            default -> throw new FeatureNotSupportedException("Unsupported set operator: " + operator);
        };
    }
```

Update `translate()` to dispatch on `QueryPart` type (replace lines 177-216):

```java
    @Override
    public JdbcOperationQuerySelect translate(
            @Nullable JdbcParameterBindings jdbcParameterBindings, QueryOptions queryOptions) {

        var queryPart = selectStatement.getQueryPart();
        String mqlv2Text;
        List<String> fieldNames;

        if (queryPart instanceof QuerySpec querySpec) {
            var root = querySpec.getFromClause().getRoots().get(0);
            hasJoins = !root.getTableGroupJoins().isEmpty();

            var groupByExprs = querySpec.getGroupByClauseExpressions();
            var sb = new StringBuilder();
            appendFrom(sb, root);
            appendJoins(sb, root);
            appendMatch(sb, querySpec);

            List<@Nullable String> aggNames = null;
            if (!groupByExprs.isEmpty()) {
                aggNames = buildAggNames(querySpec.getSelectClause());
                appendGroup(sb, groupByExprs, querySpec.getSelectClause(), aggNames);
                appendHaving(sb, querySpec);
            }

            appendSort(sb, querySpec);
            appendLimit(sb, querySpec, queryOptions);
            fieldNames = appendFormat(sb, querySpec.getSelectClause(), aggNames);
            if (querySpec.getSelectClause().isDistinct()) {
                sb.append(" | distinct");
            }
            mqlv2Text = sb.toString();
        } else if (queryPart instanceof QueryGroup queryGroup) {
            mqlv2Text = translateQueryGroupToMqlv2(queryGroup);
            // For set operations, field names come from the first QuerySpec's SELECT clause
            var firstSpec = queryGroup.getQueryParts().get(0).getFirstQuerySpec();
            fieldNames = firstSpec.getSelectClause().getSqlSelections().stream()
                    .filter(s -> !s.isVirtual())
                    .map(s -> {
                        var expr = s.getExpression();
                        if (expr instanceof ColumnReference cr) return cr.getColumnExpression();
                        if (expr instanceof BasicValuedPathInterpretation<?> bvpi)
                            return bvpi.getColumnReference().getColumnExpression();
                        return "_f" + firstSpec.getSelectClause().getSqlSelections().indexOf(s);
                    })
                    .toList();
        } else {
            throw new FeatureNotSupportedException("Unsupported QueryPart: " + queryPart.getClass().getSimpleName());
        }

        var fieldNamesArray = new BsonArray(fieldNames.stream().map(BsonString::new).toList());
        var commandDoc = new BsonDocument("mqlv2", new BsonString(mqlv2Text))
                .append("_mqlv2FieldNames", fieldNamesArray)
                .append("_mqlv2ParamCount", new org.bson.BsonInt32(parameterBinders.size()));
        var commandJson = commandDoc.toJson();

        var root = queryPart.getFirstQuerySpec().getFromClause().getRoots().get(0);
        var affectedTableNames = collectAffectedTableNames(root);
        var mappingProducerProvider =
                sessionFactory.getServiceRegistry().requireService(JdbcValuesMappingProducerProvider.class);
        var mappingProducer = mappingProducerProvider.buildMappingProducer(selectStatement, sessionFactory);
        return new JdbcOperationQuerySelect(
                commandJson, parameterBinders, mappingProducer, affectedTableNames, 0, MAX_VALUE, emptyMap(), NONE,
                null, null);
    }
```

Note: `SetOperator` is at `org.hibernate.query.sqm.SetOperator` — no new import needed if the file already imports from `org.hibernate.query.sqm.*`, otherwise add: `import org.hibernate.query.sqm.SetOperator;`

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests.testUnionAll" --tests "*.Mqlv2SelectIntegrationTests.testUnion" --tests "*.Mqlv2SelectIntegrationTests.testIntersect" --tests "*.Mqlv2SelectIntegrationTests.testExcept" --tests "*.Mqlv2SelectIntegrationTests.testIntersectAllThrows" --tests "*.Mqlv2SelectIntegrationTests.testExceptAllThrows"
```

Expected: PASS

- [ ] **Step 5: Run all integration tests to check for regressions**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests"
```

Expected: All 42+ tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2SelectIntegrationTests.java
git commit -m "MQLv2: translate QueryGroup (UNION ALL/UNION/INTERSECT/EXCEPT set operations)"
```

---

## Task 7: Update showcase markdown

**Files:**
- Modify: `docs/mqlv2-showcase.md`

- [ ] **Step 1: Update the "Known limitations" section**

In `docs/mqlv2-showcase.md`, replace the "Subqueries — not supported" bullet in the Known Limitations section:

Old:
```
- Subqueries — not supported
```

New — remove that line entirely (subqueries are now supported).

- [ ] **Step 2: Add subquery examples section**

Append a new section after the HAVING example:

````markdown
---

### IN list predicate

```
HQL:   from Customer c where c.age in (25, 30, 35)

MQLv2: from $customers
       | match ((age == 25) || (age == 30) || (age == 35))
       | format {_id: _id, active: active, age: age, name: name}
```

```
HQL:   from Customer c where c.age not in (25, 30)

MQLv2: from $customers
       | match ((age != 25) && (age != 30))
       | format {_id: _id, active: active, age: age, name: name}
```

---

### EXISTS subquery

```
HQL:   from Customer c where exists (select 1 from Order o where o.customerId = c.id)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) > 0)
       | format {_id: _id, active: active, age: age, name: name}
```

```
HQL:   from Customer c where not exists (select 1 from Order o where o.customerId = c.id)

MQLv2: from $customers
       | match (not (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) > 0))
       | format {_id: _id, active: active, age: age, name: name}
```

---

### IN subquery

```
HQL:   from Customer c where c.id in (select o.customerId from Order o where o.total > 100)

MQLv2: from $customers
       | match (count(let $__v0 = _id, $__v1 = _id in (from $orders | match (total > 100) | match (customerId == $__v0))) > 0)
       | format {_id: _id, active: active, age: age, name: name}
```

Note: the outer `_id` is bound once as `$__v0` (the test expression) and referenced in the inner `match` stage.

---

### ANY / ALL subquery

```
HQL:   from Customer c where c.id > any (select o.customerId from Order o where o.customerId < 3)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (customerId < 3) | match (customerId > $__v0))) > 0)
       | format {_id: _id, active: active, age: age, name: name}
```

```
HQL:   from Customer c where c.id > all (select o.customerId from Order o where o.total > 100)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (total > 100) | match (customerId <= $__v0))) == 0)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### Scalar aggregate subquery in SELECT

```
HQL:   select c.name, (select count(o) from Order o where o.customerId = c.id) from Customer c

MQLv2: from $customers
       | format {name: name, _f0: count(let $__v0 = _id in (from $orders | match (customerId == $__v0)))}
```

---

### UNION ALL

```
HQL:   from Customer c where c.age > 30 union all from Customer c where c.active = true

MQLv2: from << (from $customers | match (age > 30) | format {…}),
               (from $customers | match (active == true) | format {…}) >>
       | unwind $*
```

---

### UNION (distinct)

```
HQL:   from Customer c where c.age > 30 union from Customer c where c.active = true

MQLv2: from << (from $customers | match (age > 30) | format {…}),
               (from $customers | match (active == true) | format {…}) >>
       | unwind $*
       | distinct
```

---

### INTERSECT

```
HQL:   from Customer c where c.age > 30 intersect from Customer c where c.active = true

MQLv2: from $customers | match (age > 30) | format {…}
       | match (count(let $__v0 = $ in (from $customers | match (active == true) | format {…} | match ($ == $__v0))) > 0)
```

---

### EXCEPT

```
HQL:   from Customer c where c.active = true except from Customer c where c.age > 30

MQLv2: from $customers | match (active == true) | format {…}
       | match (count(let $__v0 = $ in (from $customers | match (age > 30) | format {…} | match ($ == $__v0))) == 0)
```

---

### Known limitations (updated)

- OFFSET / skip — MQLv2 has no skip stage; throws `FeatureNotSupportedException`
- HAVING referencing aggregates not in SELECT — throws `FeatureNotSupportedException`
- Scalar aggregates (no GROUP BY) — throws `FeatureNotSupportedException`
- `INTERSECT ALL` / `EXCEPT ALL` — throws `FeatureNotSupportedException`
- Scalar subquery projecting a non-aggregate — throws `FeatureNotSupportedException`
- INSERT / UPDATE / DELETE — handled by existing MQLv1 path
````

- [ ] **Step 3: Commit**

```bash
git add docs/mqlv2-showcase.md
git commit -m "MQLv2 showcase: add subquery and set operation examples"
```

---

## Task 8: Update PR description

**Files:**
- Modify: the open PR for the `jyemin/mqlv2` branch in `mongo-hibernate`

- [ ] **Step 1: Update PR description via `gh pr edit`**

```bash
gh pr edit --body "$(cat <<'EOF'
## Summary

Adds a MQLv2-based SELECT translation path to mongo-hibernate.

### Architecture

```
HQL string
  │
  ▼  Hibernate parses to SQL AST
Mqlv2SelectTranslator
  │  walks SQL AST, appends MQLv2 text stages to StringBuilder
  │  JdbcParameter nodes become $p0, $p1, … variable references
  │  HQL literals render inline in the MQLv2 text
  ▼
{"mqlv2": "from $customers | match … | format {…}",
 "_mqlv2FieldNames": ["name","age"],
 "_mqlv2ParamCount": 1}
  │
  ▼  MongoPreparedStatement.executeQuery()
     builds let doc: {p0: <value>, p1: <value>, …}
     command.clone() with "let" added before execution
  │
  ▼  MongoStatement routes to mongoDatabase.runCommand("mqlv2", …)
     server resolves $p0/$p1/… from the let document
  │
  ▼
MongoResultSet  (reuses existing infrastructure)
```

SELECT queries go through `Mqlv2SelectTranslator`; INSERT/UPDATE/DELETE stay on the existing MQLv1 aggregation pipeline path unchanged.

### Supported HQL forms

| Category | Forms |
|---|---|
| Basic SELECT | `from Entity`, field projections |
| WHERE | comparisons, AND/OR, NOT, IS NULL/IS NOT NULL, boolean fields, arithmetic |
| Parameters | HQL named/positional params via `let` doc (`$p0`, `$p1`, …) |
| Literals | integers, strings, booleans rendered inline |
| ORDER BY | ASC (default), DESC; multi-column |
| LIMIT | HQL `limit N` clause |
| Joins | INNER, LEFT OUTER, RIGHT OUTER, FULL OUTER |
| DISTINCT | `select distinct` |
| GROUP BY | single and multiple keys; count/sum/avg/min/max |
| HAVING | aggregate predicates |
| Date functions | `year`, `month`, `day`, `hour`, `minute`, `second` |
| IN list | `x IN (v1, v2, …)` / `NOT IN` |
| EXISTS | `EXISTS (subquery)` / `NOT EXISTS` |
| IN subquery | `x IN (SELECT …)` / `NOT IN` |
| ANY/ALL | `x op ANY/ALL/SOME (SELECT …)` |
| Scalar subquery | `(SELECT count(…) …)` in SELECT clause |
| Set operations | `UNION ALL`, `UNION`, `INTERSECT`, `EXCEPT` |

### Not yet supported

- OFFSET/skip
- Scalar aggregates without GROUP BY
- `INTERSECT ALL` / `EXCEPT ALL`
- Subqueries in ORDER BY or HAVING
- Multi-column IN: `(a, b) IN (SELECT x, y …)`
- INSERT / UPDATE / DELETE (handled by existing MQLv1 path)

## Test plan

- Integration tests in `Mqlv2SelectIntegrationTests` cover all forms above
- All tests run against a live MongoDB instance via `MongoExtension`
EOF
)"
```

---

## Task 9: Final regression run

- [ ] **Step 1: Run complete integration test suite**

```
./gradlew :integrationTest --tests "*.Mqlv2SelectIntegrationTests"
```

Expected: All tests PASS (no regressions)

- [ ] **Step 2: Run unit tests**

```
./gradlew test
```

Expected: PASS
