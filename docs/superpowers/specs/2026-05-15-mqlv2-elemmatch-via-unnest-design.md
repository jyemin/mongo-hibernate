# MQLv2 `$elemMatch` via Hibernate collection-valued paths — Design

**Status:** Phase 0 complete; phases 1-4 ready to plan
**Date:** 2026-05-15 (revised after Phase 0 findings)
**Author:** Jeff Yemin (with Claude)

## Summary

Expose full MongoDB `$elemMatch` semantics — including arrays of nested documents with multi-field predicates — through HQL in the MQLv2 Hibernate translator. The user writes idiomatic Hibernate collection-valued path expressions; the translator recognizes the resulting SQL AST shape and emits MQLv2 `any(...)`.

The user-facing HQL surface contains **no `unnest` keyword**:

| Shape | User HQL | MQLv2 emission |
|---|---|---|
| EXISTS over array (canonical `$elemMatch`) | `WHERE EXISTS (SELECT 1 FROM o.array a WHERE …)` | `array any (<rewritten body>)` |
| JOIN form, row-multiplying (struct arrays only) | `FROM O o JOIN o.array a WHERE …` | `\| unwind array \| match (…)` |
| Scalar `count(*)` subquery over array | `(SELECT count(*) FROM o.array a WHERE …)` | `count(array \| match (…))` |
| Element values in IN subquery | `WHERE x IN (SELECT a.col FROM o.array a)` | embedded `count(let … in (array \| match …)) > 0` |

**How this works under the hood.** Hibernate 7 introduced `UnnestFunction` as a set-returning function descriptor. When the dialect registers `unnest()` (which `MongoDialect` now does — see Phase 0), Hibernate's SQM resolver desugars collection-valued path references like `o.array a` into a `FunctionTableReference("unnest", o.array)` in the SQL AST. The translator pattern-matches that node and emits MQLv2 `any(...)` (for EXISTS / scalar subquery / IN subquery) or `| unwind` (for outer-FROM joins). The `unnest` keyword is an internal AST detail, never something users type.

Storage shape: an embeddable array uses `@Embeddable @Struct(name = "…")` on the element class and a plain `T[]` field on the parent — no `@JdbcTypeCode` annotation. Scalar arrays use the Hibernate-default array mapping. No `@ElementCollection` involvement — that mapping continues to throw at SessionFactory build.

This design lands as a five-phase project. Phase 0 (Hibernate 7.x upgrade + AST-shape diagnostics) is complete on the `mqlv2` branch. Phases 1-4 remain.

## Phase 0 findings (load-bearing for the plan)

Phase 0 wrote 13 diagnostic tests that exercise the AST machinery for every HQL shape used in Phases 2-4. The results govern every concrete design decision below.

**HQL forms that produce `FunctionTableReference("unnest")` and are usable design targets:**

| HQL | AST | Used by |
|---|---|---|
| `WHERE EXISTS (SELECT 1 FROM i.structTags t WHERE t.x = ?)` | EXISTS predicate; inner FROM root = `FunctionTableReference("unnest")` | Phase 2 |
| `WHERE EXISTS (SELECT 1 FROM Item i2 JOIN i2.structTags t WHERE i2 = i AND t.x = ?)` | EXISTS predicate; inner FROM root = `NamedTableReference` (Item) + TableGroupJoin to `FunctionTableReference("unnest")` | Phase 2 (alternative) |
| `FROM Item i JOIN i.structTags t` | Outer FROM root has TableGroupJoin to `FunctionTableReference("unnest")` | Phase 3 (struct sugar) |
| `FROM Item i JOIN LATERAL unnest(i.tags) t` | Same shape | Phase 3 (scalar fallback) |
| `SELECT i.id, (SELECT count(*) FROM i.structTags t WHERE t.y > ?) FROM Item i` | Scalar SelectStatement expression whose FROM root = `FunctionTableReference("unnest")` | Phase 4 |

**HQL forms confirmed NOT to work:**

| HQL | Failure | Implication |
|---|---|---|
| `WHERE EXISTS (SELECT 1 FROM LATERAL unnest(…) t WHERE …)` | `SyntaxException` at `(` after `unnest` | Hibernate's HQL grammar does not accept `LATERAL unnest(…)` inside subqueries. **The original spec's main idiom is unbuildable**; use implicit collection-path form instead. |
| `(SELECT count(*) FROM LATERAL unnest(…) t WHERE …)` in scalar SELECT | `SyntaxException` | Same reason. Phase 4 must use implicit collection-path. |
| `FROM Item i JOIN i.tags t WHERE t > 0` (int[] scalar sugar) | Hibernate `SqmMappingModelHelper.resolveSqmPath` AssertionError | Scalar sugar form unusable; scalar arrays must use the explicit `lateral unnest(...)` form in Phase 3. |
| `WHERE EXISTS (SELECT 1 FROM o.tags t WHERE t > 5)` (int[] scalar EXISTS) | Same Hibernate-internal AssertionError | **Scalar EXISTS form unusable.** Phase 2's elemMatch idiom works for STRUCT ARRAYS ONLY. For scalars, `array_contains(o.tags, value)` is the only available mechanism (value equality only — richer predicates aren't expressible in HQL on a scalar array). |

**Prerequisites Phase 0 put in place that Phases 2-4 build on:**

- `unnest` registered in `MongoDialect.initializeFunctionRegistry` (otherwise Hibernate's semantic analyzer rejects the function).
- Test-only `CapturingMqlv2TranslatorFactory` + `CapturingMqlv2Dialect` for AST inspection at the boundary between Hibernate and the translator.
- `Mqlv2UnnestAstDiagnosticTests` documents the AST shapes Phases 2-4 expect and locks them in against regression.

## Motivation

MQLv1 has `$elemMatch` for matching arrays of documents with multi-field predicates per element. In MQLv2 the equivalent is the `any` operator: `field any (<cond on $>)`. Without HQL access to this construct, users targeting MongoDB through Hibernate cannot express the most common Mongo-native query shape — filtering on inner fields of embedded array elements.

The natural Hibernate route — `@ElementCollection` of `@Embeddable` — does not work for MongoDB: through JDBC, Hibernate plans the parent insert and child-table inserts as separate statements with no synchronous hook to coalesce them into an embedded array on the parent document. The v1 mapper rejects this mapping at SessionFactory build time, and that decision is preserved in this design.

The viable alternative is `@Embeddable @Struct(name = "…")` on the element class with a plain `T[]` field on the parent. Users write idiomatic HQL that references the collection-valued path directly (e.g., `o.lineItems a` inside a subquery FROM, or `o.lineItems a` as a join target). Hibernate 7 — once we register `unnest()` in the dialect — internally desugars those collection-path references into a `FunctionTableReference("unnest", o.array)` in the SQL AST. The translator pattern-matches this AST shape and emits MQLv2's `any` or `| unwind`. The user never types `unnest` themselves; it's how Hibernate represents the desugaring internally.

## Scope

### In scope

- HQL `WHERE EXISTS (SELECT 1 FROM o.array a WHERE <predicate>)` → MQLv2 `array any (<predicate>)` — the canonical `$elemMatch` analog with parent-cardinality preserved. **Struct arrays only.** Scalar arrays (`int[]`, `Collection<Integer>`, etc.) trigger a Hibernate-internal SQM-resolution AssertionError under this idiom and every adjacent one (JOIN body predicate, scalar subquery body, IN-subquery body). Users targeting scalar arrays have only `array_contains(o.array, value)` (value equality) — richer scalar-array predicates aren't expressible in HQL.
- HQL plural-attribute JOIN `FROM O o JOIN o.array a WHERE <predicate>` → MQLv2 `| unwind array | match (...)` — row-multiplying join semantics. **Struct arrays only.** Scalar arrays (`int[]`) trigger the same `SqmMappingModelHelper.resolveSqmPath` AssertionError as scalar EXISTS the moment Hibernate has to resolve the unnest alias in a predicate or projection (i.e., any useful form of the query). The structural JOIN parses but is useless without alias references. For scalar arrays, users have `array_contains(o.array, value)` for value equality; richer predicates aren't expressible.
- Projection of join-alias columns: `SELECT o.id, a.sku FROM O o JOIN o.array a`.
- Aggregates over join-alias columns: `SELECT o.id, sum(a.qty) FROM O o JOIN o.array a GROUP BY o.id`.
- `count(*)` scalar subqueries over a collection-valued path in SELECT lists: `SELECT o.id, (SELECT count(*) FROM o.array a WHERE …) FROM O o`.
- Element values in IN/NOT-IN subqueries: `WHERE x IN (SELECT a.col FROM o.array a)`.
- **Struct-array shape only** for EXISTS, scalar subqueries, and IN subqueries. Phase 0/2 diagnostics confirmed scalar arrays (`int[]`, etc.) trigger the same `SqmMappingModelHelper.resolveSqmPath` AssertionError under all three implicit-collection-path idioms because Hibernate can't resolve body predicates on a basic-array unnest alias. Scalar users have `array_contains(o.array, value)` for value equality.
- ~~Nested EXISTS over array of docs each containing an array~~ — Phase 2 execution found this is **also blocked by a Hibernate SQM limitation**: correlating an unnest alias (a `SqmFunctionJoin`) into a deeper subquery throws `ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin`. The MQLv2 server handles nested `any` correctly; Hibernate just can't generate the HQL that would compile to it. Documented as a negative test (`nestedExistsOverStructArray_unsupported`).
- Correlation: inner predicates may reference outer-query columns through the existing `$__vN` `let`-binding mechanism. No new infrastructure.
- `NOT EXISTS`, `NOT IN` — flow through existing negation handling with `(not ...)`.

### Out of scope

- `@ElementCollection` of `@Embeddable`. Continues to throw at SessionFactory build, unchanged.
- `@JdbcTypeCode(SqlTypes.JSON)` array storage. Storage uses `@Embeddable @Struct(name=…)` only.
- `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY)` on the field — Phase 0 found this fails with *"No JdbcTypeConstructor registered for SqlTypes.STRUCT_ARRAY"* under MongoDialect. The struct nature must come from `@Struct` on the embeddable class, not from `@JdbcTypeCode` on the field.
- The HQL `LATERAL unnest(…)` form inside EXISTS subqueries or scalar SELECT subqueries. Hibernate 7's HQL grammar rejects this; use the implicit collection-path form instead.
- **All scalar-array shapes that reference the unnest alias** — Phase 0/2 diagnostics confirmed this applies uniformly across:
  - Scalar EXISTS: `WHERE EXISTS (SELECT 1 FROM o.scalarArray a WHERE a > ?)`
  - Scalar JOIN with body predicate or projection (sugar form OR explicit `LATERAL unnest`): `FROM O o JOIN [LATERAL unnest(]o.scalarArray a[)] WHERE a > ?` or `SELECT a FROM …`
  - Scalar `count(*)` subquery: `(SELECT count(*) FROM o.scalarArray a WHERE a > ?)`
  - Scalar IN-subquery: `WHERE x IN (SELECT a FROM o.scalarArray a)`

  All trigger Hibernate's `SqmMappingModelHelper.resolveSqmPath` AssertionError because Hibernate's SQM cannot resolve `a` when it's a basic-array unnest result. The MQLv2 server-side `scalarArray any ($ > ?)` form is correct; Hibernate just won't generate HQL that compiles to it. Users targeting scalar arrays use `array_contains(o.array, value)` for value equality.
- Nested EXISTS (`WHERE EXISTS (SELECT 1 FROM o.array a WHERE EXISTS (SELECT 1 FROM a.subarray b …))`). Phase 2 confirmed this also fails inside Hibernate: an unnest alias cannot be correlated into a deeper EXISTS subquery — Hibernate throws `ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin`. The MQLv2 `any($.subarray any (...))` form is server-side-correct but HQL-unbuildable. Documented as a negative test in Phase 2.
- Projecting join-alias fields from inside an EXISTS subquery. Throws with a message pointing to the JOIN form.
- Non-count scalar aggregates over unnest (`(SELECT max(a.price) FROM o.array a)`). MQLv2 has no `max(pipeline)` / `sum(pipeline)` / `avg(pipeline)` / `min(pipeline)` form; only `count(pipeline)`. Throws with a documented reason; the feature lights up automatically if MQLv2 grows the missing forms upstream.
- HQL `index()` over arrays. Orthogonal to elemMatch; defer.
- Nested arrays in the outer FROM (chained `| unwind` in the top-level pipeline). EXISTS-bodies-with-nested-unnest are in scope; outer-FROM nesting is not, in this design.
- Behavior changes to the v1 translator. Phase 0 mechanically adapted call sites for Hibernate 7 API changes and registered `unnest` for HQL parsing, but preserved all observable v1 query behavior. No new v1 features.
- Comprehensive embeddable-array storage coverage across all BSON field types. Tracked as a separate effort. Phase 1 ships only the smoke test that validates v2 SELECT hydration of array fields, not exhaustive type coverage.

## Architecture

The work breaks into five independent phases, each landed as a separate PR. Phase 0 is a prerequisite for Phases 1-4; Phases 1-4 are otherwise independent and can be reordered if priorities shift.

```
Phase 0  Hibernate 7.x upgrade            (no new features)
   │
   ├──> Phase 1  v2 SELECT hydration smoke test for array fields.
   │              Reads back struct-array and scalar-array fields via session.find()
   │              and trivial HQL SELECTs under the v2 dialect.
   │
   ├──> Phase 2  Translator: EXISTS-over-unnest  →  any(...)
   │              The canonical $elemMatch shape. Parent cardinality preserved.
   │
   ├──> Phase 3  Translator: plural-attribute-join sugar  →  | unwind
   │              Row-multiplying join semantics with projection + aggregates.
   │
   ├──> Phase 4  Translator: count-of-unnest scalar subqueries,
   │              element values in IN subqueries.
   │
   └──> Phase 5  Documentation: add elemMatch examples to the MQLv2 showcase
                 (docs/mqlv2-showcase.md + Mqlv2ShowcaseVerificationTests) and
                 the project README.
```

The phasing is chosen because:
- The Hibernate 7 upgrade has independent breaking changes that should soak in CI before any feature depends on it.
- Phase 1 surfaces the v2 SELECT-side risk. Storage round-trip is *not* the open question — INSERT/UPDATE go through `MongoTranslatorFactory` (shared with v1) and v1 tests already cover struct-array and scalar-array storage. The open question is whether the v2 SELECT translator correctly hydrates array fields back into entity state when reading documents; `Mqlv2SelectIntegrationTests` uses no array fields, so this is currently untested under v2.
- Phases 2-4 are largely independent: Phase 2 owns the EXISTS-over-unnest hook in `appendPredicateText`; Phase 3 owns the FROM-clause hook in `appendJoins`; Phase 4 extends the scalar-subquery and IN-subquery branches. None block one another in the codebase.
- Phase 5 (showcase + README polish) lands after the feature phases are complete. It's user-facing documentation; the diagnostic test class is removed at this point too since the showcase test now covers the same AST shapes via positive feature assertions.

## Components

### Phase 0 — Hibernate 7.x upgrade and AST-shape diagnostics ✅ Complete

Landed commits on the `mqlv2` branch:

- `4811f79` — `test: align MongoResultSetTests with widened cross-int-type coercions` (pre-existing test staleness, surfaced by the test run)
- `795ca48` — `build: upgrade hibernate-orm from 6.6.34.Final to 7.3.4.Final` — Gradle version bump, MongoDialect / TestMongoDialect / TestMqlv2Dialect gain functional no-arg constructors for Hibernate 7's defaults bootstrap path, MongoDialect adds `supportsUserDefinedTypes()`, MongoDatabaseMetaData implements the methods Hibernate 7's metadata snapshot queries, MongoStructJdbcType migrated to `StructuredJdbcType`. Obsolete `NativeBootstrappingIntegrationTests` deleted. Temporal-test `assertNotSupported` broadened to accept `AnnotationException` (Hibernate 7's PropertyContainer rejects unbound generic types earlier than the v1 type contributor). MongoDialectTests.noArgConstructorFails → noArgConstructorSucceeds.
- `99b193c` — `test: lock in unnest AST-shape assumption for elemMatch design` — `unnest` registered in `MongoDialect.initializeFunctionRegistry`; test-only `CapturingMqlv2TranslatorFactory` + `CapturingMqlv2Dialect` introduced; `Mqlv2UnnestAstDiagnosticTests` confirms `lateral unnest(...)` HQL syntax produces a `FunctionTableReference("unnest")` in the SQL AST, for both scalar and struct arrays.
- `4833790` — `test: expand unnest AST-shape diagnostic to validate all design shapes` — 11 more diagnostic cases covering the full HQL surface area Phases 2-4 rely on. Documented findings (the **Phase 0 findings** section above is the authoritative reference for HQL idioms that work vs. do not).

All existing tests pass on 7.3.4.Final.

### Phase 1 — v2 SELECT hydration smoke test for array fields

A small integration test class verifies the design's load-bearing v2-SELECT assumption: that the v2 translator correctly hydrates array-valued fields back into entity state when reading documents.

**Background — what's already proven and what isn't.** Storage (INSERT/UPDATE) for both struct-array and scalar-array fields is handled by `MongoTranslatorFactory`, which the v2 factory delegates to (`Mqlv2TranslatorFactory.buildMutationTranslator(...)`). v1 integration tests already cover round-trip of both shapes — `ArrayAndCollectionIntegrationTests` for scalar arrays and `StructAggregateEmbeddable[]` for struct arrays. The v2 SELECT translator, however, is independent of v1 and is currently exercised only by `Mqlv2SelectIntegrationTests`, which uses no array fields at all. Phase 1 closes that gap.

- Entity: `Order` with `@Id int id`, plus both shapes on equal footing:
  - `LineItem[] lineItems` (struct array; the struct nature comes from `@Embeddable @Struct(name = "LineItem")` on `LineItem`, **not** from any annotation on the field — Phase 0 confirmed `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY)` on the field fails with "No JdbcTypeConstructor registered").
  - `int[] scores` and `Collection<String> tags` — scalar array and scalar collection, no annotations.
- Embeddable: `@Embeddable @Struct(name = "LineItem") LineItem(String sku, int qty, double price)`.
- Tests: `session.find(Order.class, id)`, trivial HQL `from Order o where o.id = :id`, and `from Order o` (full scan). Verify the returned entity's array fields contain the expected values, in the right order, with null/empty cases included.
- Dialect under test: the v2 dialect (`TestMqlv2Dialect`).

If v2 SELECT does not hydrate array fields correctly, Phase 1 expands to investigate and fix — the elemMatch design cannot proceed without this guarantee, because every test in Phases 2-4 asserts on the returned entity state.

Comprehensive embeddable-array coverage across all BSON-mappable field types remains out of scope and tracked separately.

### Phase 2 — EXISTS-over-unnest → `any(...)`

**HQL user surface:** `WHERE EXISTS (SELECT 1 FROM o.array a WHERE …)` — the implicit collection-path form. Phase 0 confirmed Hibernate produces an `ExistsPredicate` whose inner subquery's FROM root is `FunctionTableReference("unnest")`. (The `WHERE EXISTS (SELECT 1 FROM LATERAL unnest(…) …)` form does **not** parse — Hibernate's HQL grammar rejects `lateral unnest` inside subqueries. The implicit form is the only Phase 2 surface.)

Translator additions in `Mqlv2SelectTranslator`:

- Three new AST helpers (used by Phases 2-4):
  - `isUnnestFunctionTable(TableReference)` — true iff the reference is a `FunctionTableReference` whose `getFunctionExpression().getFunctionName()` is `"unnest"`.
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

### Phase 3 — Plural-attribute JOIN → `| unwind`

**HQL user surface:**
- Struct arrays: `FROM O o JOIN o.array a WHERE a.x = …` — Hibernate desugars this to a `TableGroupJoin` whose joined group's primary table reference is `FunctionTableReference("unnest")`. Phase 0 confirmed.
- Scalar arrays: `FROM O o JOIN LATERAL unnest(o.array) a WHERE a > …` — same AST shape, different HQL form. The scalar sugar form (`FROM O o JOIN o.array a`) fails with a Hibernate-internal AssertionError in `SqmMappingModelHelper.resolveSqmPath`; the explicit `LATERAL unnest` is the supported workaround. (`LATERAL unnest` *is* legal in the outer FROM, only forbidden inside subqueries.)

The translator does not need to distinguish the two HQL forms — both land at the same AST node.

Translator additions in `Mqlv2SelectTranslator`:

- A new branch in `appendJoins` (Mqlv2SelectTranslator.java:336): for each `TableGroupJoin`, check `isUnnestFunctionTable(joinedGroup.getPrimaryTableReference())`. If true, emit `| unwind <arrayPath>` instead of `| join <alias>=$<collection>`. Don't recurse into the joined group's own joins — unwound elements don't carry further joins in this design.

- A translator-scoped map `unnestAliasToFieldPath` populated as unnest joins are recognized. Used by `appendExprText` for column-reference resolution.

- One new rule in `appendExprText` (Mqlv2SelectTranslator.java:988): when a `ColumnReference` has a qualifier that's a registered unnest alias, emit `<fieldPath>.<column>` instead of `<qualifier>.<column>`. Applies uniformly to WHERE, SELECT, GROUP BY, ORDER BY, and aggregate field references.

- `appendAggFieldRef` (Mqlv2SelectTranslator.java:768) gets the same rule for aggregates over unnest-alias columns.

- **`hasJoins` state for unnest joins.** The existing translator sets `hasJoins=true` whenever a `TableGroupJoin` is present, which causes column references to be emitted with their qualifier prefix (e.g., `o.id`). An unnest join is conceptually a document transformation, not a separate-table join, so it does not need outer aliasing in MQLv2 — `from $orders | unwind lineItems` keeps outer columns unqualified. Phase 3 introduces a finer-grained signal that distinguishes "entity joins are present" (which require aliasing) from "only unnest joins are present" (which do not). Mixed cases — an entity join *and* an unnest join in the same query — fall back to the aliased form `from o=$orders | unwind o.lineItems | …`, so both regular outer joins and unnest-joins can coexist.

### Phase 4 — Scalar subqueries and IN subqueries over arrays

**HQL user surfaces — struct arrays only:**
- `count(*)` scalar subquery: `SELECT o.id, (SELECT count(*) FROM o.array a WHERE …) FROM O o`. Implicit collection-path form; Phase 0 confirmed the SQL AST contains a `SelectStatement` expression whose FROM root is `FunctionTableReference("unnest")`.
- IN subquery: `WHERE x IN (SELECT a.col FROM o.array a WHERE …)`. Same shape inside an `InSubQueryPredicate`.

The `LATERAL unnest` form inside scalar SELECT subqueries does **not** parse in HQL; use the implicit collection-path form throughout Phase 4. **Scalar-array variants of both surfaces are unsupported** — Phase 2's expanded diagnostic showed scalar `(SELECT count(*) FROM o.tags a WHERE a > ?)` and `WHERE x IN (SELECT a FROM o.tags a)` both trigger the same Hibernate-internal `SqmMappingModelHelper.resolveSqmPath` AssertionError that scalar EXISTS hits. Phase 4 is struct-only by the same root cause.

Translator additions in `Mqlv2SelectTranslator`:

- The existing scalar-subquery handler in `appendExprText` (Mqlv2SelectTranslator.java:1034) currently restricts to `count()` over a regular `from $collection` subquery. Extend it: if the inner subquery's FROM is an unnest function table, emit `count(<arrayPath> | match (<rewritten-body>))`, reusing `appendPredicateTextInsideAny`. For non-count scalar aggregates over unnest, throw with a clear documented-reason message.

- The existing `InSubQueryPredicate` handler (Mqlv2SelectTranslator.java:937) similarly extends: if the subquery's FROM is an unnest function table, the projected column is rewritten as `$.<col>` and the inner pipeline becomes `<arrayPath> | match (...)`. The outer test value flows through the existing `$__vN` head-binding machinery.

### Phase 5 — Showcase and README polish

Once Phases 2-4 land, add a curated set of elemMatch example queries to:

- **`docs/mqlv2-showcase.md`** — the canonical user-facing tour of MQLv2 features through Hibernate. Each example pairs an HQL snippet with the emitted MQLv2 pipeline. Phase 5 adds a new section covering:
  - EXISTS over struct-array (single and multi-condition body)
  - Correlated outer reference inside the EXISTS body
  - NOT EXISTS
  - Plural-attribute JOIN with projection and aggregation (Phase 3)
  - `count(*)` scalar subquery over an array (Phase 4)
  - Element values in IN-subquery (Phase 4)

- **`src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java`** — the test that parses `docs/mqlv2-showcase.md` and verifies each emitted pipeline matches what the translator produces. Phase 5 adds matching `check(...)` calls for each new showcase example so the doc and code never drift.

- **`README.md`** — the project's top-level README. Phase 5 adds a one-paragraph entry under the existing feature/limitation lists noting that `$elemMatch`-style queries over arrays of embedded documents are supported via standard HQL EXISTS / JOIN forms on `@Struct`-annotated embeddable arrays. Includes one short HQL example and a pointer to the showcase doc.

Also: **remove the diagnostic test class `Mqlv2UnnestAstDiagnosticTests`** (and its `CapturingMqlv2TranslatorFactory` / `CapturingMqlv2Dialect` helpers) once Phase 3 lands, since the showcase test now covers the AST shapes positively. The diagnostic was scaffolding for the design and is no longer load-bearing.

## Data flow

### Phase 2 — EXISTS-over-unnest, simple correlated case

Input HQL:
```hql
from Order o
where exists (select 1 from o.lineItems li
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
  select 1 from o.lineItems li
  where exists (select 1 from li.taxes t
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

### Phase 4 — `count(*)` scalar subquery over array

Input HQL:
```hql
select o.id, (select count(*) from o.lineItems li where li.qty > 5) as big
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
from Order o where 'WIDGET-1' in (select li.sku from o.lineItems li)
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

**Empty unnest body.** `exists (select 1 from o.lineItems li)` with no WHERE clause is semantically "has at least one element." Translates to `lineItems any (true)`, which MQLv2 evaluates as "non-empty array."

**Null array handling.** When `o.lineItems` is missing or null on a document, MQLv2's `any` on a non-sequence returns false (per spec section on `any`). This matches SQL `EXISTS` semantics on a NULL-valued plural attribute. No special-case logic needed; covered by integration tests.

## Testing strategy

### Phase 0 — Hibernate 7.x upgrade ✅ Complete

- All existing tests pass on 7.3.4.Final.
- `Mqlv2UnnestAstDiagnosticTests` — 13 diagnostic methods exercising every HQL shape Phases 2-4 rely on. Each method either asserts the expected `FunctionTableReference("unnest")` AST shape (positive cases) or documents the parse / Hibernate-internal failure (negative cases, e.g., `lateral unnest` in subqueries).
- The diagnostic tests stay in place until Phase 3 lands, at which point they're replaced by positive feature assertions in the Phase 3 test class.

### Phase 1 — v2 SELECT hydration smoke

- One new test class. ~5-8 test methods covering `session.find()` and trivial HQL SELECTs against an entity with both a struct-array field (`LineItem[]` with `@Embeddable @Struct(name = "LineItem")` on the element class, no annotation on the field) and a scalar-array field (`int[]` / `Collection<String>`). Verify hydrated entity state matches seeded values, including null and empty cases.
- Dialect under test: v2 only. Storage (INSERT/UPDATE) is exercised incidentally to seed the test data but is not the assertion target — v1 tests already cover storage round-trip for both shapes.

### Phases 2-4 — Translator feature work

Each phase ships a dedicated integration test class:
- Phase 2: `Mqlv2UnnestExistsIntegrationTests`
- Phase 3: `Mqlv2UnnestJoinIntegrationTests`
- Phase 4: `Mqlv2UnnestSubqueryIntegrationTests`

Each test method asserts **both** the emitted MQLv2 pipeline text *and* the executed result rows, using the existing `MqlCapture` `StatementInspector` pattern from `Mqlv2ShowcaseVerificationTests`. Phase 2's PR includes a small prerequisite refactor that promotes `MqlCapture` from a nested inner class of the showcase test to a shared test-support utility.

**Struct- and scalar-array coverage across all three phases.** Each phase exercises both array shapes on equal footing. Test entities provide both:
- `Order.lineItems` — `LineItem[]` for the struct-array shape (continues from Phase 1's smoke test).
- `Order.tags` — `String[]` or `int[]` for the scalar-array shape.

Tests are parameterized or duplicated across the two shapes for the core supported HQL constructs (EXISTS, JOIN, projection, aggregation, IN-subquery). Edge cases — nested unnest, NOT EXISTS, cardinality verification — are tested once on the shape most representative of the case (typically struct, since nested unnest is most natural there), unless the scalar version would exercise meaningfully different translator codepaths.

**Phase 2 coverage:** single-condition body, multi-condition (AND/OR/NOT inside `any`), correlated and uncorrelated forms, NOT EXISTS, combined with non-unnest WHERE predicates, nested EXISTS (array-of-docs-with-array-of-docs — struct only), parameter-binding variants. The core EXISTS form is tested in both struct and scalar variants.

**Phase 3 coverage:** predicates over join-alias, projection of join-alias columns, aggregates over join-alias columns, GROUP BY combinations — each tested in both struct and scalar variants. One explicit cardinality test — seed one Order with three matching elements, assert exactly three rows; covered once per shape since the unwind path differs trivially.

**Phase 4 coverage:** `count(*)` of unnest in scalar SELECT subqueries — both shapes; element values in IN/NOT-IN subqueries — both shapes (scalar element in scalar IN-subquery; struct field projected as scalar in IN-subquery); documented-throw tests for non-count scalar aggregates and projection inside EXISTS — once per shape where the error path is distinct.

`Mqlv2SelectIntegrationTests` is execution-only today; it remains so. The new unnest test classes own both pipeline-text and execution assertions for their respective HQL shapes.

**Cross-phase regression net.** Every phase keeps the entire existing test suite green. CI is the gatekeeper.

**Transaction pattern (Hibernate test utilities).** MongoDB rejects the `mqlv2` command inside a multi-document transaction (`OperationNotSupportedInTransaction`). Tests must therefore use `sessionFactoryScope.inTransaction(...)` / `fromTransaction(...)` **only for writes** (e.g., `session.persist`, `session.createMutationQuery("delete from …").executeUpdate()`) and `sessionFactoryScope.inSession(...)` / `fromSession(...)` **for reads** (e.g., `session.find`, `session.createSelectionQuery(...).getResultList()`). Mixing them — for example wrapping a read in `fromTransaction` — fails at runtime with the same `mqlv2 cannot run in transaction` error. The existing `Mqlv2SelectIntegrationTests` follows this convention; new test classes in Phases 1-4 must follow it too.

## Open items resolved at design time (or by Phase 0)

- **Storage shape:** `@Embeddable @Struct(name=…)` on the element class, plain `T[]` field on the parent. No `@JdbcTypeCode` on the field. Phase 0 confirmed `@JdbcTypeCode(SqlTypes.STRUCT_ARRAY)` on the field fails with "No JdbcTypeConstructor registered". JSON storage out of scope.
- **HQL surface for elemMatch:** the **implicit collection-path form** (`WHERE EXISTS (SELECT 1 FROM o.array a WHERE …)`, `(SELECT count(*) FROM o.array a)`, `WHERE x IN (SELECT a.col FROM o.array a)`). Phase 0 confirmed `LATERAL unnest(...)` syntax inside subqueries does not parse in HQL.
- **HQL surface for JOIN:** the sugar form (`FROM O o JOIN o.array a`) for struct arrays; the explicit `FROM O o JOIN LATERAL unnest(o.array) a` for scalar arrays (the sugar form for `int[]` triggers a Hibernate-internal AssertionError).
- **Correlation mechanism:** reuse the existing `$__vN` `let`-binding infrastructure. No new mechanism.
- **Nesting:** allow nested EXISTS (translates to nested `any`). Outer-FROM nesting (chained `| unwind`) is out of scope.
- **Scalar vs. struct arrays:** equal-footing support for EXISTS / scalar subquery / IN subquery (identical HQL form). The JOIN form differs by storage shape per the row above. MQLv2 emissions differ only in the inner column-reference rewrite (`$` for scalars, `$.field` for structs).
- **Projection scope:** projection works via the JOIN form; EXISTS bodies remain predicate-only. Plus `count(*)` scalar subqueries and element values in IN subqueries. Non-count scalar aggregates over unnest are out of scope due to upstream MQLv2 expression-repertoire limits.
- **Hibernate 7 upgrade ownership:** done in Phase 0.
- **v1 translator behavior:** unchanged in observable contract. Phase 0 touched v1 code mechanically for API compatibility and registered `unnest()` for HQL parsing, but added no new query-translation features.
- **AST shapes for every supported HQL form:** confirmed by `Mqlv2UnnestAstDiagnosticTests` in Phase 0. The diagnostic tests are the authoritative reference and will be removed once Phase 3 lands.

## Risks

- **v2 SELECT-side hydration of array fields.** The design's remaining load-bearing assumption is that the v2 SELECT translator correctly reads array-valued fields back into entity state. Storage (write) is shared with v1 and already proven; read-side hydration under v2 is currently unverified for any array shape and is what Phase 1 closes.
- **`appendPredicateTextCorrelated` / `appendPredicateTextInsideAny` consolidation.** Phase 2 plans to refactor the existing correlated-predicate walker around a column-reference resolver. If the refactor is more invasive than expected, it could break existing correlated-EXISTS / IN / ANY-ALL tests. Mitigated by keeping the existing tests as the regression net.
- **Sugar form for struct arrays going forward.** Phase 0 confirmed the JOIN sugar form works today on Hibernate 7.3.4 with `@Embeddable @Struct` on the element. A future Hibernate upgrade could change the SQM-to-SQL transformation; the diagnostic test guards against regressions but cannot prevent upstream changes.

## Future work (explicitly out of this design)

- Comprehensive embeddable-array storage coverage across all BSON field types — separate effort.
- `@JdbcTypeCode(SqlTypes.JSON)` array storage support.
- `index()` inside unnest bodies.
- Non-count scalar aggregates over unnest — gated on upstream MQLv2 work.
- Selecting unnest-alias fields from inside EXISTS subqueries (requires designing how EXISTS-as-projection differs from EXISTS-as-predicate).
- Nested unnest in the outer FROM clause.
- `@ElementCollection` of `@Embeddable` mapping — gated on a much larger architectural change to coalesce parent + element-collection inserts at the JDBC layer.
