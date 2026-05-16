# MQLv2 `$elemMatch` via SQL UNNEST — Design

**Status:** Draft for review
**Date:** 2026-05-15
**Author:** Jeff Yemin (with Claude)

## Summary

Expose full MongoDB `$elemMatch` semantics — including arrays of nested documents with multi-field predicates — through HQL in the MQLv2 Hibernate translator, by leveraging SQL-standard `UNNEST` (which Hibernate ORM 7 added at MongoDB's request).

The HQL surface uses standard SQL constructs: `EXISTS (SELECT 1 FROM LATERAL unnest(o.array) li WHERE ...)` for cardinality-preserving element matching, and the plural-attribute-join sugar `FROM O o JOIN o.array li` (which Hibernate 7 desugars to `LATERAL unnest(...)`) for row-multiplying element traversal. The translator pattern-matches the `FunctionTableReference(unnest, ...)` SQL AST node and emits MQLv2 `any(...)` (for EXISTS) or `| unwind` (for outer-FROM joins).

Storage shape: `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY)` for embeddable arrays. Scalar arrays use whatever Hibernate-default mapping currently produces BSON arrays of scalars (already in the v1 mapper's supported set). No `@ElementCollection` involvement — that mapping continues to throw at SessionFactory build.

This design lands as a five-phase project, starting with a Hibernate 7.x upgrade.

## Motivation

MQLv1 has `$elemMatch` for matching arrays of documents with multi-field predicates per element. In MQLv2 the equivalent is the `any` operator: `field any (<cond on $>)`. Without HQL access to this construct, users targeting MongoDB through Hibernate cannot express the most common Mongo-native query shape — filtering on inner fields of embedded array elements.

The natural Hibernate route — `@ElementCollection` of `@Embeddable` — does not work for MongoDB: through JDBC, Hibernate plans the parent insert and child-table inserts as separate statements with no synchronous hook to coalesce them into an embedded array on the parent document. The v1 mapper rejects this mapping at SessionFactory build time, and that decision is preserved in this design.

The viable alternative is `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY)` with `LATERAL unnest()` as the HQL surface. Hibernate 7 introduces `UnnestFunction` as a set-returning function descriptor producing a `FunctionTableReference` in the SQL AST, and desugars plural-attribute joins to lateral unnest under the hood. The translator can pattern-match this AST shape unambiguously and emit MQLv2's `any` or `| unwind`.

## Scope

### In scope

- HQL `EXISTS (SELECT 1 FROM LATERAL unnest(o.array) li WHERE <predicate>)` → MQLv2 `array any (<predicate>)` — the canonical `$elemMatch` analog with parent-cardinality preserved.
- HQL plural-attribute-join sugar `FROM O o JOIN o.array li WHERE <predicate>` (Hibernate 7 desugaring) → MQLv2 `| unwind array | match (...)` — row-multiplying join semantics.
- Projection of unnest-alias columns via the join-sugar form: `SELECT o.id, li.sku FROM O o JOIN o.array li`.
- Aggregates over unnest-alias columns via the join-sugar form: `SELECT o.id, sum(li.qty) FROM O o JOIN o.array li GROUP BY o.id`.
- `count(*)` scalar subqueries over `unnest()` in SELECT lists: `SELECT o.id, (SELECT count(*) FROM LATERAL unnest(o.array) WHERE ...) FROM O o`.
- Element values in IN/NOT-IN subqueries: `WHERE x IN (SELECT li.col FROM LATERAL unnest(o.array) li)`.
- Both struct-array and scalar-array unnest. The scalar case (`unnest(o.intsCollection)`) uses `$` directly without a field qualifier inside the `any` body. The exact HQL surface for referencing the unnest column in the scalar case (Hibernate's default basic-array column name vs. unqualified alias references) is an investigation item resolved in Phase 2.
- Nested unnest inside an EXISTS body: array-of-docs-each-containing-an-array. Translates to nested `any(...)`. The unnest-alias stack tracks the innermost element rebinding.
- Correlation: inner predicates may reference outer-query columns through the existing `$__vN` `let`-binding mechanism. No new infrastructure.
- `NOT EXISTS`, `NOT IN` over unnest — flow through existing negation handling with `(not ...)`.

### Out of scope

- `@ElementCollection` of `@Embeddable`. Continues to throw at SessionFactory build, unchanged.
- `@JdbcTypeCode(SqlTypes.JSON)` array storage. Storage is `STRUCT_ARRAY` only.
- Projecting unnest-alias fields from inside an EXISTS subquery. Throws with a message pointing to the join-sugar form.
- Non-count scalar aggregates over unnest (`(SELECT max(li.price) FROM LATERAL unnest(...) li)`). MQLv2 has no `max(pipeline)` / `sum(pipeline)` / `avg(pipeline)` / `min(pipeline)` form; only `count(pipeline)`. Throws with a documented reason; the feature lights up automatically if MQLv2 grows the missing forms upstream.
- HQL `index()` inside unnest bodies. Orthogonal to elemMatch; defer.
- Nested unnest inside the outer FROM (chained `| unwind` in the top-level pipeline). EXISTS-bodies-with-nested-unnest are in scope; outer-FROM nesting is not, in this design.
- Behavior changes to the v1 translator. Phase 0 mechanically adapts call sites for Hibernate 7 API changes but preserves all observable v1 behavior. No new v1 features.
- Comprehensive embeddable-array storage coverage across all BSON field types. Tracked as a separate effort. Phase 1 ships only the smoke test that validates the design's load-bearing storage assumption.

## Architecture

The work breaks into five independent phases, each landed as a separate PR. Phase 0 is a prerequisite for Phases 1-4; Phases 1-4 are otherwise independent and can be reordered if priorities shift.

```
Phase 0  Hibernate 7.x upgrade            (no new features)
   │
   ├──> Phase 1  Storage smoke test: @JdbcTypeCode(STRUCT_ARRAY) of embeddables
   │              round-trips to embedded BSON arrays under the v2 dialect.
   │
   ├──> Phase 2  Translator: EXISTS-over-unnest  →  any(...)
   │              The canonical $elemMatch shape. Parent cardinality preserved.
   │
   ├──> Phase 3  Translator: plural-attribute-join sugar  →  | unwind
   │              Row-multiplying join semantics with projection + aggregates.
   │
   └──> Phase 4  Translator: count-of-unnest scalar subqueries,
                 element values in IN subqueries.
```

The phasing is chosen because:
- The Hibernate 7 upgrade has independent breaking changes that should soak in CI before any feature depends on it.
- Phase 1 surfaces the storage-shape risk (does `STRUCT_ARRAY` of embeddables actually produce an embedded BSON array under v2?) before Phases 2-4 commit to it.
- Phases 2-4 are largely independent: Phase 2 owns the EXISTS-over-unnest hook in `appendPredicateText`; Phase 3 owns the FROM-clause hook in `appendJoins`; Phase 4 extends the scalar-subquery and IN-subquery branches. None block one another in the codebase.

## Components

### Phase 0 — Hibernate 7.x upgrade

- Bump `hibernate-core` and related artifacts in the project's Gradle configuration to the latest 7.x.
- Adapt all compile errors in `src/main/java/com/mongodb/hibernate/` arising from Hibernate 7 API changes (visitor signatures, SQM types, function-descriptor APIs). Mechanical only — no behavior changes.
- Verify both translators (`MongoTranslator` v1, `Mqlv2SelectTranslator`) and all existing integration tests pass. CI is the gatekeeper.
- One new diagnostic-style integration test confirming that `from O o join o.array li` desugars to a `FunctionTableReference` for `unnest` in the inspected SQL AST. This locks Phase 3's assumption before Phase 3 begins.

### Phase 1 — Storage smoke test

A small integration test class verifies the design's load-bearing storage assumption:

- Entity: `Order` with `@Id int id`, `String customerId`, `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY) LineItem[] lineItems`.
- Embeddable: `LineItem(String sku, int qty, double price)`.
- Tests: insert + find-by-id round-trip; whole-array update; empty array; null array. Persisted BSON shape inspected directly to confirm `lineItems: [{sku, qty, price}, ...]`.
- Dialect under test: the v2 dialect (`TestMqlv2Dialect`). The v1 dialect is not exercised by this phase; whether it round-trips embeddable struct arrays today is out of scope.

If `STRUCT_ARRAY` of embeddables does not produce an embedded array under v2, Phase 1 expands to investigate and fix — the elemMatch design cannot proceed without this guarantee.

Comprehensive embeddable-array storage coverage across all BSON-mappable field types is explicitly out of scope and tracked separately.

### Phase 2 — EXISTS-over-unnest → `any(...)`

Translator additions in `Mqlv2SelectTranslator`:

- Three new AST helpers (used by Phases 2-4):
  - `isUnnestFunctionTable(TableReference)` — true iff the reference is a `FunctionTableReference` whose function descriptor is Hibernate's `UnnestFunction` (matched by descriptor identity or function name `unnest`).
  - `extractUnnestArrayPath(FunctionTableReference)` — returns the single argument expression to `unnest(...)`. Validates that the argument is a `ColumnReference` / `BasicValuedPathInterpretation`. Throws `FeatureNotSupportedException` otherwise.
  - `extractUnnestAlias(TableGroup)` — returns the identification variable that names the rows of the unnest output.

- A new predicate walker variant, `appendPredicateTextInsideAny`:
  - Shape mirrors the existing `appendPredicateTextCorrelated` (Mqlv2SelectTranslator.java:442).
  - Maintains an unnest-alias stack scoped to the current `any` body and any nested `any` bodies within it.
  - Column-reference rewrite rules:
    - Qualifier matches an unnest alias on the stack → `$.<column>` (resolved against the innermost matching alias).
    - Qualifier matches an outer-query alias → `$__vN` via existing correlated-binding machinery.
    - Anything else → `FeatureNotSupportedException` with a diagnostic message.
  - Refactor: `appendPredicateTextCorrelated` and `appendPredicateTextInsideAny` share ~80% of their bodies. Phase 2's PR consolidates them around a column-reference resolver passed as a parameter. Existing tests are the regression net.

- A new branch in `appendPredicateText`'s `ExistsPredicate` handler (Mqlv2SelectTranslator.java:967): before the generic correlated-EXISTS handling, check whether the inner subquery's FROM root is an unnest function table. If yes, dispatch to a new helper `appendUnnestExistsPredicate(StringBuilder, ExistsPredicate)`. If no, fall through to the existing handler.

- `appendUnnestExistsPredicate` extracts the unnest array path, the inner alias, and translates the inner WHERE through `appendPredicateTextInsideAny`. The result is wrapped as `(arrayPath any (<rewritten-body>))`, with correlated bindings wrapped in a `let` clause as in the existing EXISTS path. Negation wraps with `(not ...)`.

### Phase 3 — Plural-attribute-join sugar → `| unwind`

Translator additions in `Mqlv2SelectTranslator`:

- A new branch in `appendJoins` (Mqlv2SelectTranslator.java:336): for each `TableGroupJoin`, check `isUnnestFunctionTable(joinedGroup.getPrimaryTableReference())`. If true, emit `| unwind <arrayPath>` instead of `| join <alias>=$<collection>`. Don't recurse into the joined group's own joins — unwound elements don't carry further joins in this design.

- A translator-scoped map `unnestAliasToFieldPath` populated as unnest joins are recognized. Used by `appendExprText` for column-reference resolution.

- One new rule in `appendExprText` (Mqlv2SelectTranslator.java:988): when a `ColumnReference` has a qualifier that's a registered unnest alias, emit `<fieldPath>.<column>` instead of `<qualifier>.<column>`. Applies uniformly to WHERE, SELECT, GROUP BY, ORDER BY, and aggregate field references.

- `appendAggFieldRef` (Mqlv2SelectTranslator.java:768) gets the same rule for aggregates over unnest-alias columns.

- **`hasJoins` state for unnest joins.** The existing translator sets `hasJoins=true` whenever a `TableGroupJoin` is present, which causes column references to be emitted with their qualifier prefix (e.g., `o.id`). An unnest join is conceptually a document transformation, not a separate-table join, so it does not need outer aliasing in MQLv2 — `from $orders | unwind lineItems` keeps outer columns unqualified. Phase 3 introduces a finer-grained signal that distinguishes "entity joins are present" (which require aliasing) from "only unnest joins are present" (which do not). Mixed cases — an entity join *and* an unnest join in the same query — fall back to the aliased form `from o=$orders | unwind o.lineItems | …`, so both regular outer joins and unnest-joins can coexist.

### Phase 4 — Translatable subset of "C"

Translator additions in `Mqlv2SelectTranslator`:

- The existing scalar-subquery handler in `appendExprText` (Mqlv2SelectTranslator.java:1034) currently restricts to `count()` over a regular `from $collection` subquery. Extend it: if the inner subquery's FROM is an unnest function table, emit `count(<arrayPath> | match (<rewritten-body>))`, reusing `appendPredicateTextInsideAny`. For non-count scalar aggregates over unnest, throw with a clear documented-reason message.

- The existing `InSubQueryPredicate` handler (Mqlv2SelectTranslator.java:937) similarly extends: if the subquery's FROM is an unnest function table, the projected column is rewritten as `$.<col>` and the inner pipeline becomes `<arrayPath> | match (...)`. The outer test value flows through the existing `$__vN` head-binding machinery.

## Data flow

### Phase 2 — EXISTS-over-unnest, simple correlated case

Input HQL:
```hql
from Order o
where exists (select 1 from lateral unnest(o.lineItems) li
              where li.sku = :sku and li.qty > o.minQty)
```

SQL AST after Hibernate 7 SQM translation:
- Outer `QuerySpec`: FROM `Order o`, WHERE `ExistsPredicate(<inner>)`.
- Inner `QuerySpec`: FROM root is a `TableGroup` whose primary `TableReference` is `FunctionTableReference(unnest, o.lineItems)` aliased `li`. WHERE is `Junction(AND, [li.sku == $p0, li.qty > o.minQty])`.

Translator flow at `appendPredicateText`'s `ExistsPredicate` branch:

1. Recognize the inner FROM root via `isUnnestFunctionTable(...)` → true.
2. `arrayPath := extractUnnestArrayPath(...)` → `o.lineItems`, recognized as correlated to outer.
3. `unnestAlias := extractUnnestAlias(...)` → `"li"`.
4. Resolve `arrayPath` for the outer-side reference. With no outer joins, the surface form is `lineItems`.
5. Translate the inner WHERE via `appendPredicateTextInsideAny`, passing `unnestAliasStack=["li"]`, `outerQualifiers=collectOuterQualifiers(outerSpec)`, `correlatedBindings={}`:
   - `li.sku == $p0` → `$.sku == $p0` (alias matched on stack; parameter flows through).
   - `li.qty > o.minQty` → `$.qty > $__v0`; binding `o.minQty → $__v0` recorded.
6. Wrap with `let` for correlated bindings, then emit:

```
from $orders | match (let $__v0 = minQty in (lineItems any ($.sku == $p0 and $.qty > $__v0))) | format {...}
```

Negated EXISTS wraps with `(not ...)`.

### Phase 2 — Nested EXISTS-over-unnest

Input HQL (array of docs each containing an array of subdocs):
```hql
from Order o where exists (
  select 1 from lateral unnest(o.lineItems) li
  where exists (select 1 from lateral unnest(li.taxes) t
                where t.code = :code))
```

Translator flow:

1. Outer EXISTS triggers `appendUnnestExistsPredicate`. Unnest-alias stack: `["li"]`.
2. Inner WHERE is itself an `ExistsPredicate`. Recurse into `appendUnnestExistsPredicate` from inside `appendPredicateTextInsideAny`. Stack: `["li", "t"]`.
3. Inner unnest array path: `li.taxes`. `li` matches an alias on the stack — resolves as `$.taxes` (relative to the current outer-`any` element).
4. Inner body: `t.code == $p0` → `$.code == $p0` (innermost alias `t` matches).
5. Emit:

```
from $orders | match (lineItems any ($.taxes any ($.code == $p0))) | format {...}
```

The stack is essential: at each level, `$` rebinds to the current element, and the rewrite uses the innermost matching alias.

### Phase 3 — Plural-attribute-join sugar

Input HQL:
```hql
select o.id, li.sku from Order o join o.lineItems li where li.qty > :q
```

SQL AST after Hibernate 7 desugaring:
- Outer `QuerySpec`: FROM root is `Order o`, with a single `TableGroupJoin` whose joined group has `FunctionTableReference(unnest, o.lineItems)` aliased `li`.
- SELECT: `o.id`, `li.sku`. WHERE: `li.qty > $p0`.

Translator flow:

1. `appendJoins` sees a `TableGroupJoin` with `isUnnestFunctionTable(joinedGroup.getPrimaryTableReference())` → true.
2. Resolve the unnest's array path against the parent → `lineItems`. Emit `| unwind lineItems`. Skip the `| join` branch.
3. Register `unnestAliasToFieldPath["li"] = "lineItems"`.
4. `appendExprText` uses the new rule: `ColumnReference` qualifier `li` → emit `lineItems.<column>`. Applies to WHERE, SELECT, GROUP BY, ORDER BY.
5. Pipeline emission proceeds through the existing match / group / sort / format codepaths.

Emission:
```
from $orders | unwind lineItems | match (lineItems.qty > $p0) | format {_f0: id, _f1: lineItems.sku}
```

Cardinality is preserved by `| unwind`: one Order with three matching line items produces three result rows.

### Phase 4 — `count(*)` scalar subquery over unnest

Input HQL:
```hql
select o.id, (select count(*) from lateral unnest(o.lineItems) li where li.qty > 5) as big
from Order o
```

The scalar-subquery codepath in `appendExprText` (Mqlv2SelectTranslator.java:1034) is extended: when the inner subquery's FROM root is an unnest function table, emit `count(<arrayPath> | match (<rewritten-body>))`. Body translation reuses `appendPredicateTextInsideAny`.

Emission:
```
from $orders | format {_f0: id, _f1: count(lineItems | match ($.qty > 5))}
```

For non-count scalar aggregates over unnest, throw with: *"Scalar subquery over unnest() must use count(); other aggregates have no pipeline-argument form in MQLv2 yet"*.

### Phase 4 — Element values in IN-subqueries

Input HQL:
```hql
from Order o where 'WIDGET-1' in (select li.sku from lateral unnest(o.lineItems) li)
```

The `InSubQueryPredicate` handler is extended: if the subquery's FROM is an unnest function table, the projected column is rewritten as `$.<col>` and the inner pipeline becomes `<arrayPath> | match (...)`. The outer test value flows through the existing `$__vN` head-binding machinery.

Emission:
```
from $orders | match (count(let $__v0 = "WIDGET-1" in (lineItems | match ($.sku == $__v0))) > 0)
```

## Error handling

All unsupported cases throw `FeatureNotSupportedException` with a specific, debuggable message. The error contract is locked by assertion tests using `assertThatThrownBy(...).hasMessageContaining(...)`.

| Case | Message |
|---|---|
| `unnest(literal-array)` | `"unnest() argument must be a path expression on an outer entity; literal arrays are not supported in MQLv2"` |
| `unnest()` argument is a complex expression (function call, arithmetic) | `"unnest() argument must be a simple path expression; got: <expression class>"` |
| Column reference inside an `any` body whose qualifier matches neither an unnest alias nor an outer alias | `"Reference to alias '<x>' inside unnest body is not in scope"` |
| `index()` function inside an unnest body | `"index() inside unnest() is not yet supported in MQLv2"` |
| Projection of unnest-alias fields from inside an EXISTS subquery | `"Projecting unnested element fields is supported via plural-attribute joins, not inside EXISTS subqueries"` |
| Non-count scalar aggregate over an unnest subquery | `"Scalar subquery over unnest() must use count(); other aggregates have no pipeline-argument form in MQLv2 yet"` |
| `@ElementCollection` of `@Embeddable` | Unchanged — continues to throw at SessionFactory build per existing behavior. |

**Negation handling.** `NOT EXISTS (...)`, `NOT IN (...)` flow through the existing negation machinery — a single `(not ...)` wrap around the translated predicate. Tested explicitly.

**Empty unnest body.** `exists (select 1 from lateral unnest(o.lineItems) li)` with no WHERE clause is semantically "has at least one element." Translates to `lineItems any (true)`, which MQLv2 evaluates as "non-empty array."

**Null array handling.** When `o.lineItems` is missing or null on a document, MQLv2's `any` on a non-sequence returns false (per spec section on `any`). This matches SQL `EXISTS` semantics on a NULL-valued plural attribute. No special-case logic needed; covered by integration tests.

## Testing strategy

### Phase 0 — Hibernate 7.x upgrade

- All existing tests pass on 7.x. No new feature tests.
- One diagnostic test confirming that `from O o join o.array li` desugars to a `FunctionTableReference` named `unnest` in the inspected SQL AST. This locks Phase 3's design assumption before Phase 3 begins.

### Phase 1 — Storage smoke

- One new test class. ~5-8 test methods covering insert/find/update/empty/null for `STRUCT_ARRAY` of an embeddable, with direct BSON-shape inspection to confirm the embedded-array layout.
- Dialect under test: v2 only.

### Phases 2-4 — Translator feature work

Each phase ships a dedicated integration test class:
- Phase 2: `Mqlv2UnnestExistsIntegrationTests`
- Phase 3: `Mqlv2UnnestJoinIntegrationTests`
- Phase 4: `Mqlv2UnnestSubqueryIntegrationTests`

Each test method asserts **both** the emitted MQLv2 pipeline text *and* the executed result rows, using the existing `MqlCapture` `StatementInspector` pattern from `Mqlv2ShowcaseVerificationTests`. Phase 2's PR includes a small prerequisite refactor that promotes `MqlCapture` from a nested inner class of the showcase test to a shared test-support utility.

**Phase 2 coverage:** single-condition body, multi-condition (AND/OR/NOT inside `any`), correlated and uncorrelated forms, NOT EXISTS, combined with non-unnest WHERE predicates, nested EXISTS (array-of-docs-with-array-of-docs), scalar-array variant (`unnest(o.intsCollection)`), parameter-binding variants.

**Phase 3 coverage:** predicates over join-alias, projection of join-alias columns, aggregates over join-alias columns, GROUP BY combinations. One explicit cardinality test — seed one Order with three matching line items, assert exactly three rows.

**Phase 4 coverage:** `count(*)` of unnest in scalar SELECT subqueries; element values in IN/NOT-IN subqueries; documented-throw tests for non-count scalar aggregates and projection inside EXISTS.

`Mqlv2SelectIntegrationTests` is execution-only today; it remains so. The new unnest test classes own both pipeline-text and execution assertions for their respective HQL shapes.

**Cross-phase regression net.** Every phase keeps the entire existing test suite green. CI is the gatekeeper.

## Open items resolved at design time

- **Storage shape:** `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY)` only. JSON storage is out of scope.
- **Correlation mechanism:** reuse the existing `$__vN` `let`-binding infrastructure. No new mechanism.
- **Nesting:** allow nested `unnest` inside EXISTS bodies (translates to nested `any`). Outer-FROM nesting (chained `| unwind`) is out of scope.
- **Scalar vs. struct arrays:** both supported. The translator distinguishes by the absence vs. presence of a field qualifier on the inner column reference. The precise HQL surface for the scalar case is investigated in Phase 2.
- **Projection scope:** Option B from brainstorming — projection works via the plural-join sugar form; EXISTS bodies remain predicate-only. Plus the translatable subset of Option C — `count(*)` scalar subqueries over unnest, element values in IN subqueries. Non-count scalar aggregates over unnest are out of scope due to upstream MQLv2 expression-repertoire limits.
- **Hibernate 7 upgrade ownership:** part of this design as Phase 0; not deferred to a separate effort.
- **v1 translator behavior:** unchanged. Phase 0 may touch v1 code mechanically for API compatibility; no behavior changes.

## Risks

- **Hibernate 7 upgrade scope.** The user has flagged that 7.x has breaking changes the project has not yet absorbed. Phase 0 is the discovery phase for the full extent. If the upgrade reveals incompatibilities that can't be resolved cleanly, the entire design is blocked.
- **`STRUCT_ARRAY` of embeddables under v2.** The design's load-bearing storage assumption is that this maps to an embedded BSON array. If it doesn't, Phase 1 must fix it before Phases 2-4 can proceed. Currently unverified in v2.
- **AST shape variation.** Hibernate's exact SQM/SQL AST representation of `LATERAL unnest(...)` may differ subtly from the design's assumed `FunctionTableReference` shape (e.g., wrapped in a `LateralTable` or similar). Phase 0's diagnostic test catches this early.

## Future work (explicitly out of this design)

- Comprehensive embeddable-array storage coverage across all BSON field types — separate effort.
- `@JdbcTypeCode(SqlTypes.JSON)` array storage support.
- `index()` inside unnest bodies.
- Non-count scalar aggregates over unnest — gated on upstream MQLv2 work.
- Selecting unnest-alias fields from inside EXISTS subqueries (requires designing how EXISTS-as-projection differs from EXISTS-as-predicate).
- Nested unnest in the outer FROM clause.
- `@ElementCollection` of `@Embeddable` mapping — gated on a much larger architectural change to coalesce parent + element-collection inserts at the JDBC layer.
