# MQLv2 IR Migration — Phase C (Predicates) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Port `Mqlv2SelectTranslator.appendPredicateText` (and its predicate-context callers) to driver-mqlv2 AST. After Phase C, predicates emit through `Mqlv2IrEmitters.translatePredicate(Predicate, ctx) -> Expr`, serialized through the Serializer alongside other IR nodes.

## Out of scope (deferred to Phase D)

- **`ExistsPredicate`, `InSubQueryPredicate`**: both need inner pipelines as `Stage` IR. Phase D introduces stage translation; predicates referencing pipelines absorb cleanly there.
- **`SelfRenderingPredicate`** for the array_contains/intersects family is already IR (Phase A). No work here.

## Architectural concern: MQLv2 operator precedence

Driver-mqlv2's `Serializer` wraps each `BinaryOp` and `UnaryOp` in outer parens (`(left op right)`, `(not arg)`) but does NOT add inner parens around lower-precedence operands. The MQLv2 grammar precedence table:

| Operator | Precedence |
|---|---|
| `any` | 1 (lowest) |
| `or` | 2 |
| `and` | 3 |
| `not` | 4 |
| `==`, `!=`, `<`, `<=`, `>`, `>=`, `is` | 5 |
| `+`, `-` | 6 |
| `*`, `/` | 7 (highest) |

This means a naive `BinaryOp(AND, Any(arr, cond), other)` serializes to `(arr any (cond) AND other)` — which parses as `(arr any ((cond) AND other))` because `and` binds tighter than `any`. **Bug.**

**Mitigation strategy for Phase C:**
1. **Tasks C1-C3** migrate predicate shapes that don't trigger the issue:
   - Comparison, NullnessPredicate, BooleanExpressionPredicate (no nested low-precedence operand).
   - GroupedPredicate (just unwraps, no IR node).
2. **Task C4 (Junction AND/OR)** and **C5 (NegatedPredicate)** require either:
   - An upstream Serializer fix that adds precedence-aware inner parens, OR
   - Building the IR with explicit guard nodes (currently doesn't exist in driver-mqlv2 — `Expr` has no `Grouped` constructor).
   - **Decision deferred** until C1-C3 land; pick based on what we learn empirically.
3. **Task C6 (InListPredicate)** depends on the Junction outcome.

If C4/C5 prove blocked, Phase C completes with C1-C3 only; the rest hand-rolled until the precedence problem has a clean answer.

## Files

**Modify:**
- `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java` — add `translatePredicate` + per-shape helpers.
- `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — route per-predicate-shape arms in `appendPredicateText` through IR; eventually collapse `appendPredicateText` to a thin delegate.

---

## Task C1: leaf predicates (Comparison, NullnessPredicate, BooleanExpressionPredicate)

**Files:** `Mqlv2IrEmitters.java`, `Mqlv2SelectTranslator.java`

- [ ] **Step 1: Add `translatePredicate(Predicate, ctx) -> Expr` to Mqlv2IrEmitters** as a public static method, with leaf-case arms:

```java
public static Expr translatePredicate(Predicate p, Mqlv2TranslationContext ctx) {
    if (p instanceof ComparisonPredicate cp) {
        return new Expr.BinaryOp(
                translateComparisonOp(cp.getOperator()),
                translateExpression(cp.getLeftHandExpression(), ctx),
                translateExpression(cp.getRightHandExpression(), ctx));
    }
    if (p instanceof NullnessPredicate np) {
        var inner = translateExpression(np.getExpression(), ctx);
        Expr call = new Expr.FunctionCall("isNullish", List.of(inner));
        if (np.isNegated()) {
            call = new Expr.FunctionCall("notNullish", List.of(inner));
        }
        return call;
    }
    if (p instanceof BooleanExpressionPredicate bp) {
        // Group-A's tryAppendArrayPredicateFunction already covers the array-function predicates;
        // for other boolean expressions, fall back to `(expr == true|false)`.
        return new Expr.BinaryOp(
                BinaryOpType.EQ,
                translateExpression(bp.getExpression(), ctx),
                new Expr.ValueLit(new Value.VBool(!bp.isNegated())));
    }
    throw new FeatureNotSupportedException(
            "Unsupported predicate in IR translation: " + p.getClass().getSimpleName());
}

private static BinaryOpType translateComparisonOp(ComparisonOperator op) {
    return switch (op) {
        case EQUAL -> BinaryOpType.EQ;
        case NOT_EQUAL -> BinaryOpType.NE;
        case LESS_THAN -> BinaryOpType.LT;
        case LESS_THAN_OR_EQUAL -> BinaryOpType.LE;
        case GREATER_THAN -> BinaryOpType.GT;
        case GREATER_THAN_OR_EQUAL -> BinaryOpType.GE;
        default -> throw new FeatureNotSupportedException(
                "Unsupported comparison operator in IR translation: " + op);
    };
}
```

- [ ] **Step 2: Wire into `Mqlv2SelectTranslator.appendPredicateText`**

In `appendPredicateText`, replace the arms for `ComparisonPredicate`, `NullnessPredicate`, and `BooleanExpressionPredicate`. The Comparison arm has special handling for `Any` and `Every` RHS (subquery ANY/ALL) — those are not basic Comparisons; leave them on the hand-rolled path:

```java
if (predicate instanceof ComparisonPredicate cp
        && (cp.getRightHandExpression() instanceof Any || cp.getRightHandExpression() instanceof Every)) {
    // …existing ANY/ALL subquery handling…
} else if (predicate instanceof ComparisonPredicate) {
    sb.append(serializer.serialize(Mqlv2IrEmitters.translatePredicate(predicate, newContext())));
} else if (predicate instanceof NullnessPredicate) {
    sb.append(serializer.serialize(Mqlv2IrEmitters.translatePredicate(predicate, newContext())));
} else if (predicate instanceof BooleanExpressionPredicate bp) {
    // If it wraps an array predicate function, defer to tryAppendArrayPredicateFunction (Group-A path).
    if (!tryAppendArrayPredicateFunction(sb, bp.getExpression())) {
        sb.append(serializer.serialize(Mqlv2IrEmitters.translatePredicate(predicate, newContext())));
    }
}
```

(Adapt to the existing structure — the current `appendPredicateText` has these as nested else-ifs.)

- [ ] **Step 3: Verify with showcase tests**

The showcase verification covers most predicate shapes. Drift to watch for:
- ComparisonPredicate: current emission is `(left OP right)` with outer parens; Serializer produces the same. Should be byte-identical.
- NullnessPredicate: current emission is `isNullish(expr)` or `(not isNullish(expr))`. New shape (for negated) is `notNullish(expr)` — **drift D5**. Update showcase strings.
- BooleanExpressionPredicate: `(expr == true)` or `(expr == false)` — Serializer produces identical text.

```bash
JAVA_HOME=/Users/jeff/Library/Java/JavaVirtualMachines/temurin-23.0.2/Contents/Home \
  ./gradlew :integrationTest --tests Mqlv2ShowcaseVerificationTests \
  --tests Mqlv2ArrayFunctionsIntegrationTests
```

Absorb D5 (negated isNullish → notNullish) where it surfaces; if more drift appears, report.

- [ ] **Step 4: Commit**

```bash
git commit -m "MQLv2: emit Comparison/Nullness/BooleanExpression predicates via Mqlv2IrEmitters"
```

---

## Task C2: GroupedPredicate

- [ ] **Step 1: Add to `translatePredicate`:**

```java
if (p instanceof GroupedPredicate gp) {
    return translatePredicate(gp.getSubPredicate(), ctx);
}
```

GroupedPredicate just wraps an inner predicate; the parens come implicitly from the inner's serialization. No IR node needed.

- [ ] **Step 2: Wire into `appendPredicateText`** — replace the `GroupedPredicate` arm.

- [ ] **Step 3: Verify + commit**

---

## Task C3: Decision point — Junction, NegatedPredicate precedence

Before C4/C5, empirically determine what works:

- [ ] **Step 1: Try a naive Junction translation** in a scratch test:

```java
@Test
void junctionAnd() {
    Expr ir = new Expr.BinaryOp(BinaryOpType.AND,
            new Expr.Any(new Expr.FieldAccess(new Expr.CurrentValue(), "scores"),
                    new Expr.BinaryOp(BinaryOpType.EQ, new Expr.CurrentValue(), new Expr.ValueLit(new Value.VInt(30)))),
            new Expr.BinaryOp(BinaryOpType.GT,
                    new Expr.FieldAccess(new Expr.CurrentValue(), "_id"),
                    new Expr.ValueLit(new Value.VInt(0))));
    System.out.println(new Serializer().serialize(ir));
}
```

Inspect output. Expected: `(scores any ($ == 30) AND (_id > 0))`. Per the precedence table, this is **invalid** — `AND` binds tighter than `any`, so the parse would be `(scores any (($ == 30) AND (_id > 0)))`.

- [ ] **Step 2: Decide path forward** based on empirical result:

**Option A** — upstream Serializer fix: make `BinaryOp`/`UnaryOp` add explicit parens around low-precedence operands (`Any`, child `BinaryOp(AND|OR)`, child `UnaryOp(NOT)`). One-time upstream change. Republish driver-mqlv2 SNAPSHOT.

**Option B** — add a `Expr.Grouped` constructor to driver-mqlv2 that emits `(<inner>)`. Translator builds `Grouped(Any(...))` for any low-precedence sub-expression that gets nested. More verbose but explicit.

**Option C** — keep Junction/NegatedPredicate hand-rolled for now. Phase C completes with C1+C2 only.

- [ ] **Step 3: Document decision** in this plan file before C4 dispatches.

**DECISION (2026-05-17, commit `541e473d5c` on driver-mqlv2):** Option A — targeted upstream Serializer fix. Empirical probe found:
- `BinaryOp(AND/OR, Any, X)` emits correctly: `(scores any (cond) and X)` — `any`'s required parens around its second operand prevent the precedence collision, and the BinaryOp's outer parens disambiguate the overall expression.
- `UnaryOp(NOT, Any)` emits incorrectly: `(not scores any (cond))` parses as `(not scores) any (cond)`.

Serializer fix: when `UnaryOp`'s argument is an `Any`, wrap the arg in extra parens. Targeted, no over-parenthesization elsewhere. Republished `driver-mqlv2:5.8.0-SNAPSHOT` (skip-javadoc). Phase C C4 (Junction) and C5 (NegatedPredicate) can now proceed.

---

## Task C4: Junction (AND/OR) [depends on C3 decision]

If C3 picks Option A or B, implement. Otherwise skip.

- [ ] **Step 1: Add to `translatePredicate`:**

```java
if (p instanceof Junction j) {
    BinaryOpType op = j.getNature() == Junction.Nature.CONJUNCTION ? BinaryOpType.AND : BinaryOpType.OR;
    var preds = j.getPredicates();
    if (preds.isEmpty()) {
        return new Expr.ValueLit(new Value.VBool(op == BinaryOpType.AND));
    }
    Expr acc = translatePredicate(preds.get(0), ctx);
    for (int i = 1; i < preds.size(); i++) {
        acc = new Expr.BinaryOp(op, acc, translatePredicate(preds.get(i), ctx));
    }
    return acc;
}
```

(Or use Option B's `Grouped` wrapping per the decision.)

- [ ] **Step 2/3: Wire + verify + commit**

---

## Task C5: NegatedPredicate [depends on C3 decision]

Same gating as C4. Translate to `UnaryOp(NOT, inner)` or `Grouped` wrap.

---

## Task C6: InListPredicate

Currently emits `(test OP elem) OR (test OP elem) OR …`. The IR equivalent is a chain of `BinaryOp(OR, BinaryOp(EQ, test, elem), …)`. Depends on C4 (junction precedence).

---

## Task C7: cleanup + design doc

- [ ] Update Phase C bullet in migration design doc with completion status (full or partial).
- [ ] If `appendPredicateText` is now structurally minimal (mostly delegation to IR + ANY/ALL + Exists + InSubQuery), note it.
- [ ] Commit.

---

## Rollback

Per-task commits. If C3's decision proves wrong, revert C4-C6.
