# MQLv2 IR Migration — Phase B (Core Expressions) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` syntax.

**Goal:** Port the remaining `appendExprText` cases (ColumnReference with qualifier rules, JdbcParameter, `extract`, aggregate function references) to driver-mqlv2 AST via `Mqlv2IrEmitters`. Replace Phase A's awkward `int[] parameterIndex` hack with a `Mqlv2TranslationContext` object threaded through translation methods.

**Out of scope (deferred to Phase D):** `SelectStatement` (scalar subquery) — entangled with `appendQuerySpecPipeline`, which is pipeline-level translation. Defers cleanly until Phase D ports stages.

**Out of scope (deferred to Phase C):** Predicate-context functions; the `tryAppendArrayPredicateFunction` arms migrated in Phase A keep working via Phase B's context too — only the parameter-handling boilerplate updates.

## Architecture

**`Mqlv2TranslationContext`** — new class. Holds:
- `parameterBinders: List<JdbcParameterBinder>` — same list the translator owns; context provides allocation
- `unnestAliasToFieldPath: Map<String, String>` — read-only view of the translator's map (or backed by it)
- `hasJoins: boolean` — whether the current query spec has joins (affects column-reference qualifier rendering)

Method: `int allocateParameter(JdbcParameterBinder binder)` — appends to the binder list and returns the new `$pN` index.

Future Phase C will add: correlated-binding stack, outer-qualifier set, let-binding scope for `$__x` style references.

**Mqlv2IrEmitters** signature change: all methods take `Mqlv2TranslationContext ctx` instead of `int[] parameterIndex`. Callers create one context per top-level translation invocation and thread it down.

## Files

**Create:**
- `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2TranslationContext.java`

**Modify:**
- `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java` — replace `int[] parameterIndex` with `Mqlv2TranslationContext ctx` across all methods; add `translateColumnRef`, `translateExtract`, `translateAggregateReference` helpers.
- `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — wire context through migrated arms; replace remaining hand-rolled arms in `appendExprText`.
- `src/test/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmittersTest.java` — update to use the new context signature.

---

## Task 1: Introduce `Mqlv2TranslationContext`; refactor existing methods

**Files:**
- Create: `Mqlv2TranslationContext.java`
- Modify: `Mqlv2IrEmitters.java`, `Mqlv2SelectTranslator.java`, `Mqlv2IrEmittersTest.java`

- [ ] **Step 1: Create the context class**

```java
package com.mongodb.hibernate.internal.translate.mqlv2;

import java.util.List;
import java.util.Map;
import org.hibernate.sql.ast.tree.expression.JdbcParameterBinder;

/**
 * Mutable translation state threaded through {@link Mqlv2IrEmitters} during IR construction.
 * Holds the JDBC parameter binder list and the qualifier-rendering rules currently active in the
 * outer translator.
 *
 * <p>Phase B introduction. Phase C/D will extend with correlated-binding stack, outer-qualifier
 * set, and let-binding scope.
 *
 * @hidden
 */
public final class Mqlv2TranslationContext {

    private final List<JdbcParameterBinder> parameterBinders;
    private final Map<String, String> unnestAliasToFieldPath;
    private final boolean hasJoins;

    public Mqlv2TranslationContext(
            List<JdbcParameterBinder> parameterBinders,
            Map<String, String> unnestAliasToFieldPath,
            boolean hasJoins) {
        this.parameterBinders = parameterBinders;
        this.unnestAliasToFieldPath = unnestAliasToFieldPath;
        this.hasJoins = hasJoins;
    }

    /**
     * Append {@code binder} to the binder list and return the resulting {@code $pN} index.
     */
    public int allocateParameter(JdbcParameterBinder binder) {
        int idx = parameterBinders.size();
        parameterBinders.add(binder);
        return idx;
    }

    public Map<String, String> unnestAliasToFieldPath() {
        return unnestAliasToFieldPath;
    }

    public boolean hasJoins() {
        return hasJoins;
    }

    /** For Phase A compatibility — exposes the binder list for any caller that still needs raw access. */
    public List<JdbcParameterBinder> parameterBinders() {
        return parameterBinders;
    }
}
```

- [ ] **Step 2: Refactor `Mqlv2IrEmitters.translateExpression`**

Change the signature from `(Expression, int[])` to `(Expression, Mqlv2TranslationContext)`. The `JdbcParameter` case becomes:

```java
if (e instanceof JdbcParameter jp) {
    return new Expr.VarRef("p" + ctx.allocateParameter(jp.getParameterBinder()));
}
```

Update all recursive calls and the helper signatures `translateArrayLength/Get/Constructor/Contains/Intersects` accordingly.

- [ ] **Step 3: Refactor the `IrFunctionTranslator` functional interface** on `Mqlv2SelectTranslator`

Change from `(SelfRenderingFunctionSqlAstExpression<?>, int[]) -> Expr` to `(SelfRenderingFunctionSqlAstExpression<?>, Mqlv2TranslationContext) -> Expr`.

- [ ] **Step 4: Update `appendIrExprFunction` and `emitIrPredicateFunction` helpers**

The expression-context helper becomes:

```java
private void appendIrExprFunction(
        StringBuilder sb,
        SelfRenderingFunctionSqlAstExpression<?> fn,
        IrFunctionTranslator translator) {
    var ctx = newContext();
    Expr ir = translator.translate(fn, ctx);
    sb.append(serializer.serialize(ir));
}
```

The predicate-context helper:

```java
private boolean emitIrPredicateFunction(
        StringBuilder sb,
        SelfRenderingFunctionSqlAstExpression<?> fn,
        IrFunctionTranslator translator) {
    var ctx = newContext();
    Expr ir = translator.translate(fn, ctx);
    sb.append("(").append(serializer.serialize(ir)).append(")");
    return true;
}
```

Add a private helper on the translator:

```java
private Mqlv2TranslationContext newContext() {
    return new Mqlv2TranslationContext(parameterBinders, unnestAliasToFieldPath, hasJoins);
}
```

The pre-collection DFS (`collectJdbcParametersDfs`) is no longer needed — parameters are allocated as the IR walk encounters them, in DFS order, identical to what the pre-walk produced. **Remove `collectJdbcParametersDfs` entirely.**

- [ ] **Step 5: Remove `assertParameterIndexUnchanged`**

It was a Phase A invariant that no longer holds — `translateExpression` may now legitimately allocate parameters (e.g., for `JdbcParameter` cases reached via array_contains's needle). Remove the method and its calls.

- [ ] **Step 6: Update `Mqlv2IrEmittersTest`**

Adjust the test setup to construct a `Mqlv2TranslationContext` with empty `parameterBinders`, empty `unnestAliasToFieldPath`, `hasJoins=false`. The leaf-shape assertions on `Serializer` output are unchanged (they construct `Expr` directly, not via `translateExpression`).

- [ ] **Step 7: Run all tests**

```bash
JAVA_HOME=/Users/jeff/Library/Java/JavaVirtualMachines/temurin-23.0.2/Contents/Home \
  ./gradlew test --tests Mqlv2IrEmittersTest && \
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests && \
./gradlew :integrationTest --tests Mqlv2ShowcaseVerificationTests
```

All should pass — pure refactor, behavior unchanged.

- [ ] **Step 8: Commit**

```bash
git commit -m "MQLv2: introduce Mqlv2TranslationContext; replace int[] parameter-index hack"
```

---

## Task 2: ColumnReference with qualifier rules

**Files:** `Mqlv2IrEmitters.java`, `Mqlv2SelectTranslator.java`

The current foundation `translateExpression`'s `ColumnReference` arm always emits `FieldAccess(CurrentValue, column)`. But the translator's hand-rolled `appendExprText` also handles:

1. Unnest alias: if `qualifier ∈ unnestAliasToFieldPath`, emit `<aliasPath>.<column>` (e.g. `lineItems.sku`).
2. Joins active: if `hasJoins && qualifier non-empty`, emit `<qualifier>.<column>` (e.g. `o.id`).
3. Otherwise: emit `<column>` bare.

We mirror this in IR. Build a `FieldAccess` chain rooted at `CurrentValue`. For `lineItems.sku`:

```
FieldAccess(FieldAccess(CurrentValue, "lineItems"), "sku")
```

The Serializer renders nested `FieldAccess` as dot-separated; the inner-`CurrentValue` case is special-cased to drop the `$.` prefix.

- [ ] **Step 1: Implement `translateColumnRef`**

```java
private static Expr translateColumnRef(ColumnReference cr, Mqlv2TranslationContext ctx) {
    String qualifier = cr.getQualifier();
    String column = cr.getColumnExpression();
    if (qualifier != null && ctx.unnestAliasToFieldPath().containsKey(qualifier)) {
        return buildFieldChain(ctx.unnestAliasToFieldPath().get(qualifier) + "." + column);
    }
    if (ctx.hasJoins() && qualifier != null && !qualifier.isEmpty()) {
        return buildFieldChain(qualifier + "." + column);
    }
    return new Expr.FieldAccess(new Expr.CurrentValue(), column);
}

/**
 * Build a nested FieldAccess chain from a dot-separated path. {@code "a.b.c"} →
 * {@code FieldAccess(FieldAccess(FieldAccess($, a), b), c)}.
 */
private static Expr buildFieldChain(String dotPath) {
    String[] segs = dotPath.split("\\.");
    Expr e = new Expr.CurrentValue();
    for (String seg : segs) {
        e = new Expr.FieldAccess(e, seg);
    }
    return e;
}
```

Update the `ColumnReference` arm in `translateExpression`:

```java
if (e instanceof ColumnReference cr) {
    return translateColumnRef(cr, ctx);
}
```

- [ ] **Step 2: Verify**

Run all integration tests. Existing Group-A queries don't exercise qualified columns in function arguments, so no test changes needed yet — but this prepares the foundation for predicates (Phase C) that will use it.

- [ ] **Step 3: Commit**

```bash
git commit -m "MQLv2: translate ColumnReference with qualifier rules in IR"
```

---

## Task 3: Migrate `extract` function emission

**Files:** `Mqlv2IrEmitters.java`, `Mqlv2SelectTranslator.java`

Current emission for `extract(YEAR FROM date)` is `year(date)`. The IR equivalent is `FunctionCall("year", [translateExpression(date)])`.

- [ ] **Step 1: Add `translateExtract` to Mqlv2IrEmitters**

```java
public static Expr translateExtract(
        SelfRenderingFunctionSqlAstExpression<?> fn, Mqlv2TranslationContext ctx) {
    var args = fn.getArguments();
    if (args.size() != 2 || !(args.get(0) instanceof ExtractUnit eu)) {
        throw new FeatureNotSupportedException("Unsupported extract() form");
    }
    if (!(args.get(1) instanceof Expression dateExpr)) {
        throw new FeatureNotSupportedException("Non-expression date argument in extract()");
    }
    return new Expr.FunctionCall(mqlv2ExtractName(eu.getUnit()), List.of(translateExpression(dateExpr, ctx)));
}

private static String mqlv2ExtractName(TemporalUnit unit) {
    return switch (unit) {
        case YEAR -> "year";
        case MONTH -> "month";
        case DAY, DAY_OF_MONTH -> "dayOfMonth";
        case DAY_OF_YEAR -> "dayOfYear";
        case DAY_OF_WEEK -> "dayOfWeek";
        case HOUR -> "hour";
        case MINUTE -> "minute";
        case SECOND -> "second";
        default -> throw new FeatureNotSupportedException("Unsupported extract() unit: " + unit);
    };
}
```

Imports: `org.hibernate.sql.ast.tree.expression.ExtractUnit`, `java.time.temporal.TemporalUnit`.

(The `mqlv2ExtractName` helper currently lives in `Mqlv2SelectTranslator` as a static method. Move it to `Mqlv2IrEmitters` since the IR translation now owns it; remove from the translator.)

- [ ] **Step 2: Wire into Mqlv2SelectTranslator**

Replace the `extract` arm in `appendExprText`:

```java
} else if ("extract".equals(fn.getFunctionName())) {
    appendIrExprFunction(sb, fn, Mqlv2IrEmitters::translateExtract);
}
```

- [ ] **Step 3: Verify**

Showcase verification has examples like `where extract(year from c.orderDate) = 2024` — they should pass byte-identically. Any drift would surface; defer to actual emission and update.

- [ ] **Step 4: Commit**

```bash
git commit -m "MQLv2: emit extract() via Mqlv2IrEmitters"
```

---

## Task 4: Migrate aggregate function references in expression position

**Files:** `Mqlv2IrEmitters.java`, `Mqlv2SelectTranslator.java`

When an aggregate function appears in SELECT (e.g. `select count(*), sum(o.total) from Order`), the translator emits an alias name (`_agg0`, `_agg1`) — the actual aggregation happens in the `| group` stage; the SELECT-position reference is just the alias.

The IR equivalent is `VarRef(aggName)` — a plain variable reference.

- [ ] **Step 1: Add `translateAggregateReference` to Mqlv2IrEmitters**

The aggSignature → name map lives on the translator. We can either:
- (a) Pass the resolved name as a parameter — simplest.
- (b) Put the map on the context.

Pick (a) for now (the map is built during query-spec analysis on the translator, accessing it from IrEmitters would force the context to carry it).

```java
public static Expr translateAggregateReference(String aggName) {
    return new Expr.VarRef(aggName);
}
```

(Trivial enough that we could inline at the call site instead. Add the method anyway for symmetry with the other Phase B helpers.)

- [ ] **Step 2: Wire into the translator**

Replace the aggregate arm in `appendExprText`:

```java
if (isAggregateFunction(expression)) {
    var aggName = aggSignatureToName.get(aggSignature(fn));
    if (aggName == null) {
        throw new FeatureNotSupportedException(
                "Aggregate function in expression not found in SELECT: " + fn.getFunctionName() + "()");
    }
    sb.append(serializer.serialize(Mqlv2IrEmitters.translateAggregateReference(aggName)));
}
```

(The serialized form of `VarRef("_agg0")` is `"$_agg0"`. Hmm — but the current emission is `_agg0` without the `$`. **Investigate:** is the current emission really bare, or does it have `$`? Check actual showcase output. If bare, this is a drift to absorb (or we need a different IR shape — perhaps just a `FieldAccess(CurrentValue, "_agg0")`).)

Let the integration test surface the actual emission; update accordingly.

- [ ] **Step 3: Verify**

Run showcase tests. If aggregate-reference emission drifts (`_agg0` vs `$_agg0` vs something else), pick the correct IR node (`FieldAccess` vs `VarRef`) to match what the original translator was emitting, and update the helper.

- [ ] **Step 4: Commit**

```bash
git commit -m "MQLv2: emit aggregate function references via Mqlv2IrEmitters"
```

---

## Task 5: Migrate the remaining leaf cases through translateExpression

The `appendExprText` method still has standalone arms for `QueryLiteral`, `UnparsedNumericLiteral`, `SqmParameterInterpretation`, `JdbcParameter`, `BinaryArithmeticExpression`, `BasicValuedPathInterpretation` — these are all handled by the foundation `translateExpression`, but `appendExprText` currently doesn't call into it for these cases (the IR migrations only replaced the function arms).

Collapse these arms: route them through `translateExpression` + serialize, the same way the function arms do.

**Files:** `Mqlv2SelectTranslator.java`

- [ ] **Step 1: Replace the literal/parameter/arithmetic/path arms**

```java
private void appendExprText(StringBuilder sb, Expression expression) {
    if (expression instanceof BasicValuedPathInterpretation<?>
            || expression instanceof ColumnReference
            || expression instanceof QueryLiteral<?>
            || expression instanceof UnparsedNumericLiteral<?>
            || expression instanceof SqmParameterInterpretation
            || expression instanceof JdbcParameter
            || expression instanceof BinaryArithmeticExpression) {
        Expr ir = Mqlv2IrEmitters.translateExpression(expression, newContext());
        sb.append(serializer.serialize(ir));
    } else if (expression instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
        // …existing dispatch over aggregates / extract / array_length / array_get / array …
    } else if (expression instanceof SelectStatement ss) {
        // …existing SelectStatement handling (out of scope, Phase D)…
    } else {
        throw new FeatureNotSupportedException(
                "Unsupported expression: " + expression.getClass().getSimpleName());
    }
}
```

- [ ] **Step 2: Verify**

All integration tests must still pass. Watch for paren drift on `BinaryArithmeticExpression` — the current emission wraps in parens; the Serializer's binop emission also wraps in parens; should be byte-identical. If drift surfaces, update expected strings.

- [ ] **Step 3: Commit**

```bash
git commit -m "MQLv2: route all leaf expression cases through Mqlv2IrEmitters.translateExpression"
```

---

## Task 6: Phase B cleanup

**Files:** `Mqlv2SelectTranslator.java`, `docs/superpowers/specs/2026-05-17-mqlv2-translator-ir-migration-design.md`

- [ ] **Step 1: Verify `appendExprText` is now structurally minimal**

It should be roughly:

```java
private void appendExprText(StringBuilder sb, Expression expression) {
    if (isFoundationExpression(expression)) {
        sb.append(serializer.serialize(Mqlv2IrEmitters.translateExpression(expression, newContext())));
    } else if (expression instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
        // …function-name dispatch using IR helpers…
    } else if (expression instanceof SelectStatement ss) {
        // …Phase D…
    } else {
        throw new FeatureNotSupportedException(...);
    }
}
```

Where `isFoundationExpression` collapses the predicate into one place. The SelfRenderingFunction arm and the SelectStatement arm remain as-is for now.

- [ ] **Step 2: Update the migration design doc**

Find the Phase B bullet, replace with:

```
- **Phase B (complete, 2026-05-17)** — Mqlv2TranslationContext introduced; appendExprText's
  expression-leaf arms (column references with qualifier rules, literals, parameters,
  arithmetic, path interpretations) routed through Mqlv2IrEmitters. Extract function and
  aggregate function references migrated. SelectStatement scalar subqueries deferred to
  Phase D (pipeline-translation cross-cuts).
```

- [ ] **Step 3: Commit**

```bash
git commit -m "MQLv2: Phase B cleanup; mark Phase B complete in design doc"
```

---

## Self-review checklist

- [ ] `Mqlv2TranslationContext` is the single source of truth for parameter allocation; no other place writes to `parameterBinders` during IR translation.
- [ ] `Mqlv2IrEmitters.translateExpression` is the canonical entry point for converting `Expression` → `Expr`; every Phase B migration funnels through it.
- [ ] No remaining `int[] parameterIndex` parameters anywhere.
- [ ] `appendExprText` no longer hand-rolls any leaf-case emission for the cases above; only function-dispatch and SelectStatement remain.
- [ ] `collectJdbcParametersDfs` is removed.
- [ ] `assertParameterIndexUnchanged` is removed.
- [ ] All integration tests pass.

## Rollback

Per-task commits; revert any individually. Task 1 (context introduction) is the riskiest — if behavior changes, revert and reconsider the shape. Subsequent tasks build on it.
