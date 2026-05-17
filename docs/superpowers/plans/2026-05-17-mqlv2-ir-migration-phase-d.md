# MQLv2 IR Migration — Phase D (Stages + Phase E Cleanup) Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Port the remaining pipeline-level emission (stages, joins, group/having, inner subquery pipelines, deferred subquery-referencing predicates) to driver-mqlv2 IR. After Phase D, `Mqlv2SelectTranslator` builds a `Stage` for the whole query and serializes once at the top level. `appendExprText`, `appendPredicateText`, and the various `appendX` helpers are gone — replaced by IR translation functions.

This plan bundles Phases D and E from the design doc since the cleanup is so closely tied to the stage migration that a final cleanup phase as a separate step would be tiny.

## Strategy

**Incremental.** Each task migrates one stage shape or one predicate shape, replacing the corresponding hand-rolled `appendX` method. Top-level `buildQuerySpecTranslation` still drives the show, but its body migrates piece-by-piece. The very last task collapses it into `translateQuerySpec(...) -> Stage` + single-serialize.

**Invariants preserved through migration:**
- `parameterBinders` ordering — IR translation must allocate parameters in the same DFS order as hand-rolled emission. The `Mqlv2TranslationContext` `allocateParameter` already provides this.
- `field names` ordering for the JdbcOperationQuerySelect result-set mapping — currently driven by `appendFormat`'s return value. Must be preserved as we migrate the format stage.

## Files

**Modify:**
- `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java` — add per-stage and per-predicate translation methods.
- `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — replace per-stage hand-rolled emission with IR builds; eventually collapse to a top-level `translateQuerySpec` flow.

## Tasks

### D1: Foundation — simple stage helpers

Add `translateMatchStage(Stage prev, Predicate, ctx) -> Stage`, `translateLimitStage(Stage prev, Expr count, ctx) -> Stage` (or take a QuerySpec and derive), and helpers for sort. Internal scaffold; not yet wired in.

Skip if the existing structure makes it easier to introduce these inline in D2.

### D2: Migrate `appendMatch`, `appendSort`, `appendLimit`, `appendFormat` to IR

For each: replace the StringBuilder mutation with a build-Stage step. The translator's top-level still chains them, but instead of `appendX(sb, ...)`, the new shape is `s = translateX(s, ...)` returning an updated `Stage`.

Top-level `buildQuerySpecTranslation` shifts to:

```java
Stage s = new Stage.FromStageSimple(new Expr.VarRef(collName));
s = translateJoins(s, root, querySpec, ctx);  // still hand-rolled until D5
s = translateMatchToStage(s, querySpec, ctx);
s = translateGroup(s, querySpec, aggNames, havingOnlyAggs, ctx);  // hand-rolled until D4
s = translateHaving(s, querySpec, ctx);
s = translateSort(s, querySpec, ctx);
s = translateLimit(s, querySpec, queryOptions, ctx);
fieldNames = computeFieldNames(querySpec.getSelectClause(), aggNames);
s = translateFormat(s, querySpec.getSelectClause(), aggNames, ctx);
if (querySpec.getSelectClause().isDistinct()) {
    s = new Stage.DistinctStage(s);
}
return new SpecTranslation(new Serializer().serialize(s), fieldNames);
```

For stages whose translation is straightforward (Match wraps a predicate Expr, Sort wraps SortSpec list, Limit wraps an Expr, Distinct is a no-op wrap), implement directly. For Format, the projection is a `DocumentConstructor` over the SELECT clause's selections.

### D3: Migrate `appendJoins` and `appendUnnestJoin` to IR

Joins are the trickiest stage shape because of the unnest-join machinery (collection-valued path joins emit `| unwind` stages with binding context). Build `Stage.UnwindComplexStage` for unnest joins and `Stage.JoinStage` for regular joins (we may not have any HQL queries that produce a non-unnest join today; verify).

### D4: Migrate group/having to IR

Build `Stage.GroupStage` with the aggregation expressions. Having becomes a `MatchStage` after the group.

### D5: Migrate inner-pipeline subqueries (`appendQuerySpecPipeline`)

Currently produces a text fragment. Migrate to `translateInnerQuerySpec(QuerySpec, ctx) -> Stage`. The `wrapWithLet` helper produces a `SubPipelineExpr` wrapped in a `LetExpr` when correlated bindings exist.

### D6: Migrate deferred predicates (ExistsPredicate, InSubQueryPredicate, Comparison-Any/Every)

With D5's `translateInnerQuerySpec` available, these predicates can now build proper IR:
- `ExistsPredicate` → `BinaryOp(GT, FunctionCall("count", SubPipelineExpr(stage)), lit(0))` (or `== 0` when negated)
- `InSubQueryPredicate` → similar shape, with `match` over the test variable
- `Comparison(... Any/Every subquery)` → similar

### D7: Migrate scalar SelectStatement (`count(...)` subquery in expression position)

Currently in `appendExprText`'s SelectStatement arm. After D5, this becomes a single IR construction.

### D8: Migrate `translateQueryGroupToMqlv2` (UNION/INTERSECT/EXCEPT)

Currently builds pipeline text directly. Migrate to IR: nested SubPipelineExpr over BagConstructor, plus a final unwind. INTERSECT/EXCEPT translate to the existing `match (count(let ...) > 0)` / `== 0` patterns — now natively IR-shaped.

### D9: Top-level collapse + final cleanup

After D8 every appendX-style method is gone. `translate` (top-level entry point) becomes:

```java
public JdbcOperationQuerySelect translate(...) {
    var ctx = newContext();
    Stage s = translateTopLevel(queryPart, ctx);  // returns Stage
    String mqlv2Text = new Serializer().serialize(s);
    // …commandDoc, mapping producer, return…
}
```

Delete:
- `appendExprText` (replaced by `Mqlv2IrEmitters.translateExpression`).
- `appendPredicateText` (replaced by `Mqlv2IrEmitters.translatePredicate`).
- `appendFrom`, `appendJoins`, `appendUnnestJoin`, `appendMatch`, `appendSort`, `appendLimit`, `appendLimitToBuilder`, `appendGroup`, `appendHaving`, `appendScalarAgg`, `appendFormat`, `appendQuerySpecPipeline`, `appendIrExprFunction`, etc.
- All dead helpers (`comparisonOpSurface`, `anyMatchOp`, `allMatchOp`, `wrapWithLet`, etc.) — survey before deleting.

Update the migration design doc with a Phase D/E completion summary.

## Risks

- **Field-name ordering.** `appendFormat` returns the field names in projection order. The migration must preserve this exactly because `JdbcOperationQuerySelect` uses the order for result-set mapping. Build the IR's `DocumentConstructor` from the SELECT clause's selections in their existing iteration order.
- **Distinct ordering.** Currently `| distinct` appears after `| format`. Stage chain: FormatStage → DistinctStage. Verify the IR `DistinctStage(FormatStage(...))` serializes to the same text.
- **The `correlatedVarCounter` shared state.** Used in UNION/INTERSECT/EXCEPT and in subquery handling. Must continue to be globally unique across the translation. Keep as a translator field (not in context).
- **`limitJdbcParameter` field.** Used in JdbcOperationQuerySelect construction. The migration must continue to populate it when `setMaxResults` is bound.

## Self-review checklist

- [ ] `translate` top-level returns Stage and serializes once.
- [ ] All `appendX` methods deleted.
- [ ] `Mqlv2IrEmitters` has translation methods for every stage and predicate shape.
- [ ] Field names preserve insertion order from the SELECT clause.
- [ ] `parameterBinders` order is preserved.
- [ ] `correlatedVarCounter` is preserved as translator state.
- [ ] Showcase verification tests pass with absorbed paren-drift updates only — no semantic changes.

## Rollback

Each task is its own commit. Reverting a single task restores its hand-rolled path. The D9 final cleanup is the only commit where rollback is large (since it deletes many helpers); make it the final commit and ensure all prior tasks are independently committed.
