# MQLv2 Translator IR Migration — Design

**Date:** 2026-05-17
**Author:** Jeff Yemin (drafted by Claude)
**Status:** Draft, awaiting review

## Goal

Refactor `Mqlv2SelectTranslator` to produce an MQLv2 AST (typed records, sealed interfaces) instead of building MQLv2 surface text directly via `StringBuilder`. The final wire output is still a `String` (consumed by `MongoPreparedStatement` over JDBC); serialization happens once at the end, in a single dedicated stage.

The IR already exists in the Java driver's `driver-mqlv2` module — `com.mongodb.mqlv2.ast.Stage`, `com.mongodb.mqlv2.ast.Expr`, supporting types, and `com.mongodb.mqlv2.Serializer`. We adopt it as a dependency rather than re-inventing.

## Motivation

`Mqlv2SelectTranslator` is currently ~2080 lines and growing roughly linearly with feature surface. The reasons aren't accidental:

- Three independent dispatch ladders coexist: by `Expression` subtype in `appendExprText`, by `Predicate` subtype in `appendPredicateText`, and by function name within both. Each new feature touches at least one ladder; cross-context features touch more.
- Each `appendX` method couples three concerns at once: what node to emit, how to format it (parens, escapes, separators), and how it interacts with surrounding context (qualifier prefixing, the let-binding stack, the unnest alias map). A new feature can't change one without reasoning about the other two.
- Tests assert byte-exact pipeline text, which means refactoring formatting decisions (e.g., paren placement) requires updating every affected test in lockstep — a tax that has, in practice, discouraged formatting cleanup.

The cumulative effect: features arrive but the design doesn't get better, and the cost of the *next* feature is monotonically increasing. Each Group-A function added 5-15 lines to a single dispatch arm, but the cognitive load to find where to add them grew faster.

Splitting node construction from formatting (the standard compiler IR pattern) addresses all three causes at once.

## The IR

`driver-mqlv2/src/main/java/com/mongodb/mqlv2/ast/` already provides:

- `Stage` — sealed interface, 16 records: `FromStageSimple`, `FromStageNested`, `MatchStage`, `FormatStage`, `AggStage`, `ProjectStage`, `LimitStage`, `SortStage`, `GroupStage`, `SetStage`, `UnsetStage`, `DistinctStage`, `CountStage`, `UnwindSimpleStage`, `UnwindComplexStage`, `JoinStage`. Each carries a `Stage prev` field for chain construction.
- `Expr` — sealed interface, 16 records: `ValueLit`, `CurrentValue`, `VarRef`, `BinaryOp`, `UnaryOp`, `FieldAccess`, `ArrowOp`, `ArrayIndex`, `UnwindExpr`, `BagConstructor`, `ArrayConstructor`, `DocumentConstructor`, `Any`, `FunctionCall`, `SubPipelineExpr`, `LetExpr`.
- `Value`, `Assignment`, `FieldPathTree`, `SortSpec`, plus enums `BinaryOpType` / `UnaryOpType` / `SortDirection` / `JoinType` / `DatePart`.
- `Serializer` — 218 lines, pure AST → MQLv2 text.
- `Pipeline.toMqlv2()` — wraps a `Stage` and serializes; implements `Mqlv2Source`. We can use either `Serializer` directly or wrap in `Pipeline`; the cleanest thing is to wrap, since `Pipeline` is what the driver also accepts.

This covers every construct `Mqlv2SelectTranslator` currently emits.

### One upstream change — landed

`Stage.LimitStage` was `record LimitStage(Stage prev, int count)`. Hibernate binds `setMaxResults` as a JDBC parameter, which we render as `$pN` — `int` can't carry a parameter reference. Changed to `record LimitStage(Stage prev, Expr count)`, with `Serializer` calling `ser(count)` instead of string-concatenating an int. Parallel to `SortSpec` which already takes `Expr`. Committed to `mongo-java-driver` `mqlv2` branch as `6e1f35e792` and published to local Maven as `org.mongodb:driver-mqlv2:5.8.0-SNAPSHOT` (skip-javadoc: a pre-existing `ExprT.java:42` doc warning is unrelated). No further upstream changes are anticipated for the migration.

## Translator shape after migration

### Top-level

```java
public String translateSelect(...) {
    Stage stage = translateQuerySpec(querySpec);
    return new Pipeline(stage).toMqlv2();
}
```

### Per-method signatures

The current `void appendX(StringBuilder sb, ...)` methods become value-returning functions over the AST:

```java
private Stage translateQuerySpec(QuerySpec qs);            // returns the full pipeline
private Stage translateJoin(Stage prev, TableGroupJoin tgj);// returns prev with one join stage appended
private Stage translateMatch(Stage prev, Predicate p);     // appends a MatchStage
private Stage translateSort(Stage prev, QuerySpec qs);     // appends a SortStage
private Stage translateLimit(Stage prev, QuerySpec qs);    // appends a LimitStage
private Stage translateFormat(Stage prev, SelectClause sc); // appends the trailing FormatStage
// …

private Expr translateExpression(Expression expr);         // replaces appendExprText
private Expr translatePredicate(Predicate p);              // replaces appendPredicateText
                                                           // (predicates are boolean Exprs in MQLv2)
```

The recursion structure mirrors what's there today; only the return types and the body shapes change. Chain-style construction (constraint #3 from the discussion) maps cleanly:

```java
private Stage translateQuerySpec(QuerySpec qs) {
    Stage s = new FromStageSimple(new VarRef(tableName(qs)));
    s = translateJoins(s, qs);
    s = translateMatch(s, qs);
    s = translateGroup(s, qs);
    s = translateHaving(s, qs);
    s = translateSort(s, qs);
    s = translateLimit(s, qs);
    s = translateFormat(s, qs);
    return s;
}
```

This is the same control flow as `buildQuerySpecTranslation` today, just with `Stage` accumulating instead of `StringBuilder`.

### What the dispatch ladders become

The current `instanceof Expression` ladder in `appendExprText` becomes:

```java
private Expr translateExpression(Expression e) {
    if (e instanceof BasicValuedPathInterpretation<?> bvpi) {
        return translateExpression(bvpi.getColumnReference());
    } else if (e instanceof ColumnReference cr) {
        return translateColumnRef(cr);
    } else if (e instanceof QueryLiteral<?> ql) {
        return new ValueLit(translateLiteral(ql.getLiteralValue()));
    } else if (e instanceof BinaryArithmeticExpression bae) {
        return new BinaryOp(arithmeticOp(bae.getOperator()),
                            translateExpression(bae.getLeftHandOperand()),
                            translateExpression(bae.getRightHandOperand()));
    } else if (e instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
        return translateFunction(fn);
    }
    // …
}
```

The function-name sub-dispatch becomes a separate small method:

```java
private Expr translateFunction(SelfRenderingFunctionSqlAstExpression<?> fn) {
    return switch (fn.getFunctionName()) {
        case "array_length", "cardinality" ->
                new FunctionCall("count", List.of(translateExpression((Expression) fn.getArguments().get(0))));
        case "array_get" -> new ArrayIndex(
                translateExpression((Expression) fn.getArguments().get(0)),
                new BinaryOp(SUBTRACT,
                        translateExpression((Expression) fn.getArguments().get(1)),
                        new ValueLit(Value.VInt.of(1))));
        case "array", "array_list" -> new ArrayConstructor(
                fn.getArguments().stream()
                        .map(a -> translateExpression((Expression) a))
                        .toList());
        case "extract" -> translateExtract(fn);
        default -> translateOtherFunction(fn);   // aggregates or throw
    };
}
```

Each function-family branch is one `case` arm; each is a pure expression that constructs an AST node. No surrounding formatting decisions. The "where do parens go" question is answered once, in the Serializer.

### Predicate-context functions

`tryAppendArrayPredicateFunction` becomes a `translateBooleanFunction` that returns `Optional<Expr>`. The `array_contains` family translates to an `Any` node; the `array_intersects` family translates to a nested `Any` + `LetExpr` + `Any`. The whole helper structure collapses to AST construction, with no string concatenation.

```java
private Optional<Expr> translateBooleanFunction(SelfRenderingFunctionSqlAstExpression<?> fn) {
    String name = fn.getFunctionName();
    List<? extends SqlAstNode> args = fn.getArguments();
    if (name.equals("array_contains") || name.equals("array_contains_nullable")) {
        var eqOp = name.endsWith("_nullable") ? IS : EQ;
        return Optional.of(new Any(
                translateExpression((Expression) args.get(0)),
                new BinaryOp(eqOp, new CurrentValue(), translateExpression((Expression) args.get(1)))));
    }
    if (name.equals("array_intersects") || name.equals("array_intersects_nullable")) {
        var eqOp = name.endsWith("_nullable") ? IS : EQ;
        Expr a = translateExpression((Expression) args.get(0));
        Expr b = translateExpression((Expression) args.get(1));
        return Optional.of(new Any(
                a,
                new LetExpr(
                        List.of(Map.entry("__x", new CurrentValue())),
                        new Any(b, new BinaryOp(eqOp, new CurrentValue(), new VarRef("__x"))))));
    }
    return Optional.empty();
}
```

## Parameter binding

`Mqlv2SelectTranslator` today maintains a `List<JdbcParameterBinder> parameterBinders` and emits `$pN` strings inline. Two natural designs in IR-world:

- **Side channel** (preferred): translator keeps `parameterBinders` exactly as today; AST nodes for parameter references are plain `VarRef("p" + index)`. The `MongoPreparedStatement` reads both the serialized text and the binder list. Driver-mqlv2's AST stays a pure language model with no Hibernate concerns.
- **Embed in AST**: a new node type or a subclass of `VarRef`. Requires extending driver-mqlv2 or wrapping with our own types. More machinery for no functional gain.

**Decision: side channel.** Identical to current behavior; isolates Hibernate-specific concerns from the driver's IR.

The existing `LimitJdbcParameter` inner class (line 2059) and the limit-parameter machinery is unchanged in scope but moves into `translateLimit`, which produces `new LimitStage(prev, new VarRef("p" + index))` after appending the binder. The upstream `LimitStage` change above (already landed) makes this expressible.

## Dependency arrangement

`driver-mqlv2` is unpublished SNAPSHOT. mongo-hibernate already depends on the SNAPSHOT for the `MongoDatabase.mqlv2(...)` entry point, so adding a transitive on `driver-mqlv2` is incremental.

- **Build dependency**: add `driver-mqlv2` to `build.gradle.kts` as a compile-time and runtime dependency. Same version as the existing `driver-sync` / `driver-core` SNAPSHOT pin.
- **Composite build**: if local iteration on driver-mqlv2 + mongo-hibernate is needed mid-migration, add `includeBuild("../mongo-java-driver")` to `settings.gradle.kts` behind a local-dev flag. CI continues to consume the published SNAPSHOT.
- **Upstream contributions**: the LimitStage change is landed; further additions are unanticipated but possible (e.g., if Serializer's paren rules differ from our test assertions in a way that's a Serializer bug rather than a test-expectation cleanup). Each would be its own small PR to mongo-java-driver.

## Migration strategy

**Per-feature, with a parallel-emission test.** For each feature being ported:

1. Write a new helper method that returns the AST for the feature.
2. Build the AST in test code that mirrors the existing test's HQL input.
3. Pass through `Serializer.serialize(...)` and assert against the existing showcase verification test's expected MQLv2 string. **Allow byte differences as long as they're semantically equivalent** — e.g., the Serializer may emit fewer parens than the current translator, and we accept that.
4. Once the parallel emission is right (or the diff is acceptable), wire the new helper in, remove the old `appendX` arm, run the integration tests.
5. Commit.

**Migration order** — leaf-first, working outward from the most isolated features:

- **Phase A (complete, 2026-05-17)** — Group-A function emissions ported to driver-mqlv2
  AST + Serializer. Foundation `Mqlv2IrEmitters.translateExpression` covers literals,
  column references, JDBC parameters, arithmetic, and recursive routing to migrated
  function helpers. Five function emissions migrated:
  - `array_length` / `cardinality`
  - `array_get` (absorbed D2 paren drift: `[(a) OP b]` → `[(a OP b)]`)
  - `array` / `array_list` constructor
  - `array_contains` / `_nullable` (absorbed D3b inner-binop double parens)
  - `array_intersects` / `_overlaps` / `_nullable` (same D3b absorption)

  D3a (outer match parens) preserved through Phase A because the existing
  `NegatedPredicate` arm in `appendPredicateText` concatenates `(not <inner>)` —
  inner parens around the IR-emitted predicate are load-bearing. Phase C will
  fold `NegatedPredicate` into IR and absorb D3a.

  JdbcParameter indexing handled via a `collectJdbcParametersDfs` pre-walk plus
  a mutable `int[]` cursor — explicit and obvious in code but awkward; Phase B's
  translation context will replace it.

  Also resolved during the probe: D1 (quoted document keys) via the upstream
  `serDocKey` tweak in driver-mqlv2 `892852d6fa`. New upstream `Serializer.serialize(Expr)`
  in driver-mqlv2 `79ce233bca` exposes the public method for standalone expression rendering.
- **Phase B (complete, 2026-05-17)** — `Mqlv2TranslationContext` introduced (holds
  parameterBinders + qualifier rules); replaces Phase A's `int[] parameterIndex` cursor.
  All leaf cases in `appendExprText` (BasicValuedPathInterpretation, ColumnReference with
  qualifier rules, QueryLiteral, UnparsedNumericLiteral, SqmParameterInterpretation,
  JdbcParameter, BinaryArithmeticExpression) now route through
  `Mqlv2IrEmitters.translateExpression`. `extract()` and aggregate-function references
  migrated. Dead helpers removed: `appendLiteralText`, `arithmeticOpText`,
  `collectJdbcParametersDfs`, `assertParameterIndexUnchanged`. `appendExprText` reduced
  to a thin dispatcher: leaf cases delegate to IR via `isFoundationExpression` predicate;
  only the function-name arm and SelectStatement arm remain hand-rolled. SelectStatement
  (scalar subquery) deferred to Phase D — its translation cross-cuts the
  pipeline-translation machinery.
- **Phase C (predicates)** — port `translatePredicate`: `ComparisonPredicate`, `Junction`, `GroupedPredicate`, `NegatedPredicate`, `NullnessPredicate`, `BooleanExpressionPredicate`, `InListPredicate`, `InSubQueryPredicate`, `ExistsPredicate`, `SelfRenderingPredicate`. The unnest-EXISTS and IN-subquery branches are the most intricate; do them last.
- **Phase D (stages)** — port the `appendMatch` / `appendSort` / `appendLimit` / `appendGroup` / `appendHaving` / `appendFormat` / `appendJoin` / `appendUnnestJoin` methods. By this point, all sub-pieces (expressions, predicates) return AST nodes; stage methods are mostly assembly.
- **Phase E (cleanup)** — delete the `StringBuilder sb` parameter from any helpers that still have it; remove the old `appendX` methods; collapse to the new top-level `translateQuerySpec` flow.

Each phase is a separate PR; each PR keeps all tests green; rollback is per-PR.

## Test strategy

- **`Mqlv2ArrayFunctionsIntegrationTests` and the other integration tests** continue to assert what they assert today (real MongoDB execution + captured MQL text via `MqlCapture`). They're the regression net.
- **`Mqlv2ShowcaseVerificationTests`** asserts byte-exact MQL strings for ~80 showcase examples. We allow this to drift if the Serializer emits semantically equivalent text with different paren placement — update the expected strings on a case-by-case basis. *Don't* preserve current paren placement just for assertion convenience.
- **New: an AST construction test** per migrated feature. Builds the AST node directly via the IR types (not via the translator), runs `Serializer.serialize`, and asserts the text. This locks the IR-to-text behavior independently of the Hibernate-to-IR translation. Lives in driver-mqlv2 as a unit test or in mongo-hibernate as a co-located AST test — TBD; first instinct is mongo-hibernate side, since the test asserts our chosen AST shapes.
- **No mocks at any stage.** Translator tests already exercise real MongoDB execution; AST construction tests are pure unit tests over types and the Serializer.

## Rollback

Each migrated feature is its own commit. Reverting a single commit unmigrates exactly that feature — the new top-level translator can call either the new IR helper or the old `appendX` text helper for any given feature during the transition window. We keep both code paths alive until Phase E.

If the whole experiment proves a mistake (unlikely given the analysis, but possible), the migration commits sit in a sequence on a branch; reverting the branch back to the pre-migration commit returns to today's design. No data migrations, no API changes outside the translator.

## Out of scope

- **Wire-path changes.** JDBC's `MongoPreparedStatement` still consumes text; we don't bypass serialization.
- **Performance optimization.** The current translator's performance is bounded by Hibernate's own SQL-AST walk, not by string concatenation. AST construction has the same asymptotic cost. Don't migrate for speed; migrate for maintainability.
- **MQLv1 translator.** `AbstractMqlTranslator` already has its own AST (`AstFilter`, `AstFieldOperationFilter`, …). Out of scope here. The v1 and v2 designs converge on the same architectural pattern, which is fine.
- **AST-level optimization passes.** Folding constants, dedup-ing `let` bindings, peephole rewrites. Possible later because the IR makes it possible — explicitly not part of this migration.

## Open questions / risks

1. **Serializer paren divergence.** The current translator over-parenthesizes (`(scores any ($ == 30))` wraps the outer `any` in parens via `tryAppendArrayPredicateFunction`'s `sb.append("(")`). The Serializer emits parens based on the AST shape only — operator nodes get parens, `Any` nodes don't. The showcase verification tests assert the current emission; some will need updating. Risk: low; cost is mechanical test updates.
2. ~~**LimitStage upstream PR turnaround.**~~ Resolved — landed in `mongo-java-driver` `mqlv2` branch as `6e1f35e792` and published locally.
3. **Field-path qualifier handling.** Today qualifier rules (`qualifier != null && hasJoins`) live inline in `appendExprText`'s `ColumnReference` arm. In IR-world they have to land somewhere — either in `translateExpression`'s `ColumnReference` arm (building `FieldAccess` or `VarRef` based on the same rules) or as a translator-context state. Decision: keep in `translateColumnRef`, identical to today's logic, just returning `Expr` instead of mutating a StringBuilder. Risk: low; behavior is preserved verbatim.
4. **Correlated-binding `let` wrapping.** The current `wrapWithLet` helper produces text like `let $__v0 = field in (innerPipeline)`. In IR-world this is `new LetExpr(bindings, body)` where `body` is itself a `SubPipelineExpr`. Verify the Serializer produces equivalent text. Risk: low-medium; might need a Serializer fix if paren placement differs.
5. **`mqlv2Enabled` gate.** Already addressed by `@Setting` in tests; unchanged by the migration.

## Smallest first step (pre-design-acceptance probe)

Before committing to the full migration, I'd land one tiny commit:

- Add `driver-mqlv2` to `build.gradle.kts`.
- Write a single test that takes ~5 showcase strings, manually constructs the equivalent AST using driver-mqlv2 types, and asserts the Serializer's output matches (or surfaces a clean diff).
- If byte-equivalent: green light.
- If diffs: classify each (acceptable formatting change vs. semantic bug). Confirm acceptable diffs with the user. Decide whether to proceed with the full migration.

If this probe goes well, the per-feature migration is straightforward execution. If it surfaces unacceptable diffs we'd refine the design before committing further effort.

---

## Summary

| | Before | After |
|---|---|---|
| Translator output | `String` (text built via `StringBuilder`) | `String` (serialized from `Stage` AST) |
| Translator size | ~2080 lines (linear growth per feature) | smaller and slower-growing (logarithmic-ish per feature) |
| Formatting concerns | Interleaved with translation in every `appendX` | Concentrated in driver-mqlv2's `Serializer` |
| Function dispatch | Two ladders by name (`appendExprText`, `tryAppendArrayPredicateFunction`) | One `switch` returning `Expr` |
| Test surface | Integration tests + showcase verification (byte-exact strings) | Same, plus per-AST-node construction tests |
| Wire path | Translator → text → JDBC | Same. (`Pipeline.toMqlv2()` is the boundary.) |
| Upstream impact | None | `Stage.LimitStage(Stage prev, Expr count)` — landed and published. |

The IR exists. The Serializer exists. The wire path is unchanged. The work is mechanical refactor of the translator's internals, staged per-feature, with rollback at every commit.
