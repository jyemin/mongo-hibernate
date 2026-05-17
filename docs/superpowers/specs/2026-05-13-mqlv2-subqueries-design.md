# MQLv2 Subquery and Set Operation Support — Design Spec

**Date:** 2026-05-13
**Status:** Skunkworks
**Repo:** mongo-hibernate (jyemin fork, `mqlv2` branch)

---

## Goal

Extend `Mqlv2SelectTranslator` to translate all HQL subquery and set-operation forms into MQLv2 subpipeline expressions, removing the "Subqueries — not supported" limitation.

## Architecture

All new logic is added directly to `Mqlv2SelectTranslator` (Option A — extend in place). No new classes. The file grows from ~500 to ~800 lines but stays one coherent unit with direct access to all existing helpers.

The implementation is structured around one core mechanism plus per-form translation methods:

---

## Core Mechanism: Correlated Reference Binding

When translating an inner `QuerySpec`, some `ColumnReference` nodes belong to the **outer** query's tables. The translator detects these and binds them via MQLv2's `let` expression.

### How it works

`appendExprText` gains an overload accepting two extra parameters:
- `Set<String> outerQualifiers` — table aliases belonging to the outer query
- `LinkedHashMap<String, String> correlatedBindings` — maps `"qualifier.column"` → `"$__v0"`, populated on first encounter

When a `ColumnReference` is encountered whose qualifier is in `outerQualifiers`, the translator:
1. Computes a key: `qualifier + "." + column`
2. If not yet in `correlatedBindings`, assigns the next name: `"$__v0"`, `"$__v1"`, …
3. Emits the bound variable name instead of `qualifier.column`

After building the inner pipeline text, the result is wrapped:
```
let $__v0 = qualifier.column, $__v1 = qualifier2.col2 in (<inner pipeline>)
```

Variable names use `__` prefix to avoid collision with user field names.

The existing top-level `appendExprText(StringBuilder, Expression)` signature is unchanged — it calls the new overload with empty `outerQualifiers` and a fresh `correlatedBindings` map.

---

## Predicate Subqueries

### `InSubQueryPredicate` — `x IN (subquery)` / `NOT IN`

Converted to a correlated existence count:

```
count(let $__v0 = <testExpr>, [$__vN = outer_col, …] in (
  from $inner_table
  | match <subquery WHERE>
  | match <projected_col> == $__v0
)) > 0      ← IN
== 0        ← NOT IN
```

The test expression (`x`) is bound to `$__v0`. Additional `$__vN` bindings follow for any correlated references to outer tables. All bindings use the same `$__vN` naming scheme.

### `InListPredicate` — `x IN (v1, v2, v3)`

Translated to chained OR / AND comparisons:
- `IN`:     `(x == v1) || (x == v2) || (x == v3)`
- `NOT IN`: `(x != v1) && (x != v2) && (x != v3)`

Each `vN` goes through the existing `appendLiteralText` / `$pN` paths. This avoids reasoning about `any` operator and 3VL edge cases.

### `ExistsPredicate` — `EXISTS` / `NOT EXISTS`

```
count([let $__v0 = outer_col, …] in (
  from $inner_table
  | match <subquery WHERE>
)) > 0      ← EXISTS
== 0        ← NOT EXISTS
```

If the subquery is uncorrelated (no outer references), no `let` wrapper is needed: `count((from $inner_table | match …)) > 0`.

### `ModifiedSubQueryExpression` — `x op ALL/ANY/SOME (subquery)`

Converted via operator inversion:

| HQL form | MQLv2 |
|---|---|
| `x op ANY(sub)` | `count(let $__v0 = x in (inner \| match col same_op $__v0)) > 0` |
| `x op ALL(sub)` | `count(let $__v0 = x in (inner \| match col inverse_op $__v0)) == 0` |

`SOME` is an alias for `ANY`. `inverse_op` negates `op` (e.g. `>` → `<=`, `==` → `!=`).

---

## Scalar Subquery in SELECT

A `SelectStatement` appearing as an expression inside the outer `SelectClause` is translated to a subpipeline expression in the `format` stage:

```sql
select c.name, (select count(o) from Order o where o.customerId = c.id) from Customer c
```
```
from $customers
| format {name: name, _f0: count(let $__v0 = _id in (from $orders | match customerId == $__v0))}
```

`appendExprText` gains a branch for `SelectStatement` that delegates to `appendScalarSubpipeline`. Non-aggregate scalar subqueries (e.g. `select o.total … limit 1`) throw `FeatureNotSupportedException` in this initial scope.

---

## Subquery in FROM — `QueryPartTableGroup`

```sql
select avg(d.age) from (select c.age from Customer c where c.active = true) d
```
```
from d1_0=(from $customers | match (active == true) | format {age: age})
| format {_f0: avg($->age)}
```

Hibernate assigns an alias (`d1_0`) to the derived table. That alias becomes the source name in the outer `from` stage. Column references to the derived table use that alias as qualifier, which works naturally with the existing qualifier logic.

---

## Set Operations — `QueryGroup`

Each `QueryPart` in the group is translated to a full subpipeline `(from … | match … | format …)`.

| Operator | MQLv2 |
|---|---|
| `UNION ALL` | `from << (p1), (p2), … >> \| unwind $*` |
| `UNION` | `from << (p1), (p2), … >> \| unwind $* \| distinct` |
| `INTERSECT` | `<left pipeline> \| match count(let $__v0 = $ in (right pipeline \| match $ == $__v0)) > 0` |
| `EXCEPT` | `<left pipeline> \| match count(let $__v0 = $ in (right pipeline \| match $ == $__v0)) == 0` |
| `INTERSECT ALL` | `FeatureNotSupportedException` |
| `EXCEPT ALL` | `FeatureNotSupportedException` |

For INTERSECT/EXCEPT, `$ == $__v0` tests whole-document equality — valid because both sides of a set operation must project the same columns.

Multi-way operations (3+ parts) flatten naturally since `QueryGroup` holds N children.

---

## Error Handling

Throw `FeatureNotSupportedException` for:
- `INTERSECT ALL` / `EXCEPT ALL`
- Scalar subquery projecting a non-aggregate (the `[0]` array-index case)
- Subquery in ORDER BY or HAVING position
- Multi-column `IN`: `(a, b) IN (SELECT x, y …)`

---

## Integration Tests

New tests in `Mqlv2SelectIntegrationTests` using existing `Customer`/`Order` entities:

| Test method | HQL |
|---|---|
| `testInSubQuery` | `where c.id in (select o.customerId from Order o where o.total > 100)` |
| `testNotInSubQuery` | `where c.id not in (select o.customerId from Order o)` |
| `testInList` | `where c.age in (25, 30, 35)` |
| `testNotInList` | `where c.age not in (25, 30)` |
| `testExists` | `where exists (select 1 from Order o where o.customerId = c.id)` |
| `testNotExists` | `where not exists (select 1 from Order o where o.customerId = c.id)` |
| `testAnySubQuery` | `where c.age > any (select o.total from Order o)` |
| `testAllSubQuery` | `where c.age > all (select o.total from Order o)` |
| `testScalarSubquery` | `select c.name, (select count(o) from Order o where o.customerId = c.id) from Customer c` |
| `testSubqueryInFrom` | Criteria API (HQL does not support subqueries in FROM): `CriteriaBuilder` derived table producing `avg(age)` over active customers |
| `testUnionAll` | `from Customer c where c.age > 30 union all from Customer c where c.active = true` |
| `testUnion` | `from Customer c where c.age > 30 union from Customer c where c.active = true` |
| `testIntersect` | `from Customer c where c.age > 30 intersect from Customer c where c.active = true` |
| `testExcept` | `from Customer c where c.age > 30 except from Customer c where c.active = true` |
| `testIntersectAllThrows` | verify `FeatureNotSupportedException` |
| `testExceptAllThrows` | verify `FeatureNotSupportedException` |
