# MQLv2 IR Migration — Phase A (Group-A Function Emissions) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the seven Group-A array function emissions in `Mqlv2SelectTranslator` from hand-rolled `StringBuilder` text to driver-mqlv2 AST construction + `Serializer.serialize(...)`. This is the warm-up phase of the IR migration designed in `docs/superpowers/specs/2026-05-17-mqlv2-translator-ir-migration-design.md`.

**Architecture:** Each migrated function becomes a method on a new helper class `Mqlv2IrEmitters`, which takes Hibernate `Expression`/`SelfRenderingFunctionSqlAstExpression` arguments and returns driver-mqlv2 `Expr` nodes. The intercept arms in `Mqlv2SelectTranslator` shrink to: build the `Expr`, serialize it via `Serializer.serialize(Expr)`, and append the resulting text to the surrounding `StringBuilder`. The wider translator (predicate dispatch, format projection, stages, etc.) remains hand-rolled and unchanged. Subsequent phases (B–E) will absorb those.

**Tech Stack:** Hibernate ORM 7.3.4.Final SQL AST, MongoDB BSON, driver-mqlv2 `5.8.0-SNAPSHOT` (already a `testImplementation` dep; needs promotion to `implementation` in Task 1). All work continues on the `mqlv2` branch.

**Validation strategy:** Existing integration tests (`Mqlv2ArrayFunctionsIntegrationTests`, `Mqlv2ShowcaseVerificationTests`, etc.) are the regression net — they execute against real MongoDB and assert both the captured MQLv2 pipeline text and the result set. The probe (`Mqlv2IrRoundTripProbeTest`) already locked the expected IR-via-Serializer output for the function shapes; per-function tasks may need to update a few hand-written showcase strings to absorb the D2 (ArrayIndex paren) and D3 (`match`/`Any` paren) drift documented in the probe. The probe and migration designs both note these as semantically equivalent.

---

## Drift to absorb during this phase

Already locked by the probe (`Mqlv2IrRoundTripProbeTest`):

| Drift | Current emission | After migration |
|---|---|---|
| **D2 — `array_get` parens** | `scores[(i) - 1]` | `scores[(i - 1)]` |
| **D3a — `match` outer parens** | `match (scores any …)` | `match scores any …` |
| **D3b — `Any` body double parens** | `any ($ == x)` | `any (($ == x))` |

These show up in `Mqlv2ArrayFunctionsIntegrationTests` and `Mqlv2ShowcaseVerificationTests` assertions. Update the expected strings to match the new emission as the per-function migrations land.

D1 (quoted document keys) was resolved upstream in driver-mqlv2 `892852d6fa`; no further action.

## File structure

**Create:**
- `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java` — new package; helper class with static methods that take Hibernate SQL AST nodes and return driver-mqlv2 `Expr` nodes. One foundation method (`translateExpression`) plus one per function family.
- `src/test/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmittersTest.java` — unit tests where feasible (literal-expression cases); integration tests carry the rest.

**Modify:**
- `gradle/libs.versions.toml` — no change (catalog entry already exists from probe task).
- `build.gradle.kts` — promote `mongo-java-driver-mqlv2` from `testImplementation` to `implementation` so the AST types are available in `main` source.
- `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — per-function arm replacements; eventually the `tryAppendArrayPredicateFunction` helper.
- `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java` — update expected pipeline strings to absorb D2/D3 drift, per task.
- `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java` — same.
- `docs/mqlv2-showcase.md` — same.

**Upstream (mongo-java-driver):**
- `driver-mqlv2/src/main/java/com/mongodb/mqlv2/Serializer.java` — one-line addition: change `private String ser(Expr expr)` to `public String serialize(Expr expr)` (or add a new public method that delegates). Republish SNAPSHOT.

## Conventions

- Helper methods on `Mqlv2IrEmitters` are `static`; they receive only Hibernate SQL AST inputs and return `Expr` (or, for the foundation method, dispatch over `Expression`). No translator state.
- Field paths and qualifier rules (`unnestAliasToFieldPath`, `hasJoins`) stay in `Mqlv2SelectTranslator` for now — the foundation `translateExpression` accepts a context object or callback for column-reference rendering so it doesn't need to know about the translator's state. (Phase B will fold the context inward.)
- The cast `(Expression) fn.getArguments().get(0)` pattern from the existing code carries forward; we keep the `instanceof Expression …` guards established in Task 1 of the Group-A plan.

---

## Task 1: Upstream — expose `Serializer.serialize(Expr)`

**Files (mongo-java-driver):**
- Modify: `driver-mqlv2/src/main/java/com/mongodb/mqlv2/Serializer.java`
- Test: `driver-mqlv2/src/test/java/com/mongodb/mqlv2/SerializerTest.java`

- [ ] **Step 1: Add the public method**

In `Serializer.java`, immediately above the existing `private String ser(final Expr expr)` (around line 84), add:

```java
public String serialize(final Expr expr) {
    return ser(expr);
}
```

(Or, alternatively, change `private` to `public` on `ser` and rename — but keeping a stable `ser/serialize` naming pair mirrors the existing public/private split for `Stage`.)

- [ ] **Step 2: Add a Serializer test**

In `SerializerTest.java`, add:

```java
@Test
void serializeExprStandalone() {
    Serializer s = new Serializer();
    Expr e = new Expr.BinaryOp(BinaryOpType.EQ,
            new Expr.FieldAccess(new Expr.CurrentValue(), "sku"),
            new Expr.ValueLit(new Value.VString("WIDGET-1")));
    assertEquals("(sku == \"WIDGET-1\")", s.serialize(e));
}
```

- [ ] **Step 3: Run the driver-mqlv2 tests**

```bash
cd /Users/jeff/git/m/mongo-java-driver
JAVA_HOME=/Users/jeff/Library/Java/JavaVirtualMachines/temurin-23.0.2/Contents/Home ./gradlew :driver-mqlv2:test
```

- [ ] **Step 4: Commit and publish**

```bash
git add driver-mqlv2/src/main/java/com/mongodb/mqlv2/Serializer.java \
        driver-mqlv2/src/test/java/com/mongodb/mqlv2/SerializerTest.java
git commit -m "Mqlv2: expose Serializer.serialize(Expr) for standalone-expression rendering"
JAVA_HOME=/Users/jeff/Library/Java/JavaVirtualMachines/temurin-23.0.2/Contents/Home \
  ./gradlew :driver-mqlv2:publishToMavenLocal -x javadoc
```

Verify with `unzip -p ~/.m2/repository/org/mongodb/driver-mqlv2/5.8.0-SNAPSHOT/driver-mqlv2-5.8.0-SNAPSHOT.jar com/mongodb/mqlv2/Serializer.class | javap -p` — should show a `public String serialize(Expr)` method.

---

## Task 2: Promote dependency to `implementation` + foundation `translateExpression`

**Files (mongo-hibernate):**
- Modify: `build.gradle.kts`
- Create: `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java`
- Create: `src/test/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmittersTest.java`

- [ ] **Step 1: Promote the driver-mqlv2 dep**

In `build.gradle.kts`, remove the `testImplementation` line from Task probe and add to the `api`/`implementation` block:

```kotlin
implementation(libs.mongo.java.driver.mqlv2)
```

(Don't leak driver-mqlv2 types into our public API surface yet — `implementation`, not `api`. Phase B/C/D/E may revisit if internal callers need the AST publicly.)

- [ ] **Step 2: Create the `Mqlv2IrEmitters` skeleton**

`src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java`:

```java
/*
 * Copyright 2025-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); ...
 */
package com.mongodb.hibernate.internal.translate.mqlv2;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.mqlv2.ast.BinaryOpType;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.Value;
import java.util.List;
import org.hibernate.query.sqm.tree.expression.SqmParameterInterpretation;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticExpression;
import org.hibernate.sql.ast.tree.expression.BinaryArithmeticOperator;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.JdbcParameter;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.results.graph.basic.BasicValuedPathInterpretation;

/**
 * Translates Hibernate SQL AST expressions into driver-mqlv2 AST nodes. Currently scoped to the
 * cases used by Group-A array function arguments (literals, column references, parameters,
 * arithmetic). Extended in subsequent migration phases.
 *
 * <p>Stateless on purpose: tomorrow, when the translator's qualifier rules (joins, unnest aliases)
 * need to participate, they will arrive as method parameters or via a small context object — never
 * as instance state on this class.
 */
public final class Mqlv2IrEmitters {

    private Mqlv2IrEmitters() {}

    /**
     * Foundation: convert a Hibernate {@link Expression} into a driver-mqlv2 {@link Expr}. Covers the
     * leaves Group-A functions can pass as arguments. Throws for unsupported shapes — callers should
     * not reach those (the function descriptors validate argument types).
     */
    public static Expr translateExpression(Expression e, int[] parameterIndex) {
        if (e instanceof BasicValuedPathInterpretation<?> bvpi) {
            return translateExpression(bvpi.getColumnReference(), parameterIndex);
        }
        if (e instanceof ColumnReference cr) {
            return new Expr.FieldAccess(new Expr.CurrentValue(), cr.getColumnExpression());
        }
        if (e instanceof QueryLiteral<?> ql) {
            return new Expr.ValueLit(translateLiteralValue(ql.getLiteralValue()));
        }
        if (e instanceof SqmParameterInterpretation spi) {
            return translateExpression(spi.getResolvedExpression(), parameterIndex);
        }
        if (e instanceof JdbcParameter) {
            // Phase A scope: the index is supplied by the caller (translator) and lives outside
            // this stateless helper. Caller increments parameterIndex[0] for each parameter
            // encountered and provides the resulting reference.
            return new Expr.VarRef("p" + parameterIndex[0]++);
        }
        if (e instanceof BinaryArithmeticExpression bae) {
            return new Expr.BinaryOp(
                    translateArithmeticOp(bae.getOperator()),
                    translateExpression(bae.getLeftHandOperand(), parameterIndex),
                    translateExpression(bae.getRightHandOperand(), parameterIndex));
        }
        throw new FeatureNotSupportedException(
                "Unsupported expression in IR translation: " + e.getClass().getSimpleName());
    }

    private static Value translateLiteralValue(Object v) {
        if (v == null) {
            return new Value.VNull();
        }
        if (v instanceof String s) {
            return new Value.VString(s);
        }
        if (v instanceof Boolean b) {
            return new Value.VBool(b);
        }
        if (v instanceof Number n) {
            // Mongo + MQLv2 promote integer-valued numerics to VInt; floating to VDouble.
            if (v instanceof Double || v instanceof Float) {
                return new Value.VDouble(n.doubleValue());
            }
            return new Value.VInt(n.longValue());
        }
        throw new FeatureNotSupportedException(
                "Unsupported literal type in IR translation: " + v.getClass().getSimpleName());
    }

    private static BinaryOpType translateArithmeticOp(BinaryArithmeticOperator op) {
        return switch (op) {
            case ADD -> BinaryOpType.ADD;
            case SUBTRACT -> BinaryOpType.SUB;
            case MULTIPLY -> BinaryOpType.MUL;
            case DIVIDE, DIVIDE_PORTABLE, QUOT -> BinaryOpType.DIV;
            default -> throw new FeatureNotSupportedException(
                    "Unsupported arithmetic operator in IR translation: " + op);
        };
    }
}
```

**Important constraints baked in:**
- The `parameterIndex` is a mutable `int[]` so the caller can pass an "out parameter" to track how many `$pN` references the IR construction consumed. The translator currently uses `parameterBinders.size()` for indexing; the IR helper needs to mirror that. (Phase B will introduce a proper translation context to remove this awkwardness.)
- Column references don't yet handle qualifier rules (`hasJoins`, `unnestAliasToFieldPath`) — Group-A test cases don't exercise joins in function arguments, so the simple `FieldAccess(CurrentValue, columnName)` form suffices. Phase C will extend this.

- [ ] **Step 3: Add a unit test for `translateExpression`**

`src/test/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmittersTest.java`:

```java
package com.mongodb.hibernate.internal.translate.mqlv2;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.mqlv2.Serializer;
import com.mongodb.mqlv2.ast.BinaryOpType;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.mqlv2.ast.Value;
import org.junit.jupiter.api.Test;

class Mqlv2IrEmittersTest {

    private final Serializer s = new Serializer();

    @Test
    void literalString() {
        Expr e = new Expr.ValueLit(new Value.VString("WIDGET-1"));
        assertThat(s.serialize(e)).isEqualTo("\"WIDGET-1\"");
    }

    @Test
    void literalInt() {
        Expr e = new Expr.ValueLit(new Value.VInt(42));
        assertThat(s.serialize(e)).isEqualTo("42");
    }

    @Test
    void fieldAccess() {
        Expr e = new Expr.FieldAccess(new Expr.CurrentValue(), "scores");
        assertThat(s.serialize(e)).isEqualTo("scores");
    }

    @Test
    void arithmeticSubtract() {
        Expr e = new Expr.BinaryOp(BinaryOpType.SUB,
                new Expr.ValueLit(new Value.VInt(1)),
                new Expr.ValueLit(new Value.VInt(1)));
        assertThat(s.serialize(e)).isEqualTo("(1 - 1)");
    }
}
```

These tests don't yet exercise `translateExpression`'s dispatch over Hibernate SQL AST nodes — that requires real Hibernate state, validated through the integration tests as the per-function tasks land. The unit tests here lock the Serializer's output shape for the leaves so future Phase B/C refactors don't quietly regress them.

- [ ] **Step 4: Verify build + test**

```bash
JAVA_HOME=/Users/jeff/Library/Java/JavaVirtualMachines/temurin-23.0.2/Contents/Home \
  ./gradlew test --tests Mqlv2IrEmittersTest
```

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts \
        src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java \
        src/test/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmittersTest.java
git commit -m "MQLv2: bootstrap Mqlv2IrEmitters with translateExpression foundation"
```

---

## Task 3: Migrate `array_length` / `cardinality` to IR emission

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java` — add `translateArrayLength`.
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — replace the hand-rolled `array_length`/`cardinality` arm with an IR emit + serialize.
- No expected-string updates anticipated: `count(scores)` serializes byte-identically to the current emission.

- [ ] **Step 1: Add the helper**

In `Mqlv2IrEmitters.java`, append:

```java
/** Translate {@code array_length(arr)} / {@code cardinality(arr)} → {@code count(arr)}. */
public static Expr translateArrayLength(
        org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression<?> fn,
        int[] parameterIndex) {
    if (!(fn.getArguments().get(0) instanceof Expression arg)) {
        throw new FeatureNotSupportedException("Non-expression argument in " + fn.getFunctionName() + "()");
    }
    return new Expr.FunctionCall("count", List.of(translateExpression(arg, parameterIndex)));
}
```

- [ ] **Step 2: Wire into the translator**

In `Mqlv2SelectTranslator.appendExprText`, replace the existing `array_length`/`cardinality` arm:

```java
} else if ("array_length".equals(fn.getFunctionName())) {
    if (!(fn.getArguments().get(0) instanceof Expression argExpr)) {
        throw new FeatureNotSupportedException("Non-expression argument in array_length()");
    }
    sb.append("count(");
    appendExprText(sb, argExpr);
    sb.append(")");
```

with:

```java
} else if ("array_length".equals(fn.getFunctionName())) {
    int[] idx = {parameterBinders.size()};
    Expr ir = Mqlv2IrEmitters.translateArrayLength(fn, idx);
    // No parameters consumed for count(field) — sanity check while migrating:
    assertParameterIndexUnchanged(idx[0]);
    sb.append(serializer.serialize(ir));
}
```

Add fields/imports:

```java
import com.mongodb.mqlv2.Serializer;
import com.mongodb.mqlv2.ast.Expr;
import com.mongodb.hibernate.internal.translate.mqlv2.Mqlv2IrEmitters;
// …
private final Serializer serializer = new Serializer();
```

And the `assertParameterIndexUnchanged` helper as a private method on the translator:

```java
private void assertParameterIndexUnchanged(int newIdx) {
    if (newIdx != parameterBinders.size()) {
        throw new IllegalStateException(
                "IR translation consumed parameters not yet appended to parameterBinders");
    }
}
```

(This guard catches the case where a future helper consumes a parameter via `translateExpression` but the translator forgets to push the corresponding binder. Group-A function args rarely contain parameters at this level, but the guard is cheap and we want it in place when Task 6/7 do introduce parameters.)

- [ ] **Step 3: Run the integration tests**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.arrayLength \
                          --tests Mqlv2ArrayFunctionsIntegrationTests.cardinalityAlias \
                          --tests Mqlv2ShowcaseVerificationTests
```

Expect: all pass, byte-identical output.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: emit array_length / cardinality via Mqlv2IrEmitters"
```

---

## Task 4: Migrate `array_get` to IR emission

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java` — add `translateArrayGet`.
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`.
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java` — absorb D2 paren drift.
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java` — same.
- Modify: `docs/mqlv2-showcase.md` — same.

- [ ] **Step 1: Add the helper**

```java
/** Translate {@code array_get(arr, i)} → {@code arr[(i - 1)]} (HQL is 1-based, MQLv2 0-based). */
public static Expr translateArrayGet(
        org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression<?> fn,
        int[] parameterIndex) {
    var args = fn.getArguments();
    if (!(args.get(0) instanceof Expression arr) || !(args.get(1) instanceof Expression idx)) {
        throw new FeatureNotSupportedException("Non-expression argument in array_get()");
    }
    return new Expr.ArrayIndex(
            translateExpression(arr, parameterIndex),
            new Expr.BinaryOp(BinaryOpType.SUB,
                    translateExpression(idx, parameterIndex),
                    new Expr.ValueLit(new Value.VInt(1))));
}
```

- [ ] **Step 2: Wire into the translator**

Replace the `array_get` arm similarly:

```java
} else if ("array_get".equals(fn.getFunctionName())) {
    int[] idx = {parameterBinders.size()};
    Expr ir = Mqlv2IrEmitters.translateArrayGet(fn, idx);
    assertParameterIndexUnchanged(idx[0]);
    sb.append(serializer.serialize(ir));
}
```

- [ ] **Step 3: Update expected strings (D2 paren drift)**

In `Mqlv2ArrayFunctionsIntegrationTests.arrayGet`, change:

```
"from $array_docs | match (scores[(1) - 1] == 10) | format ..."
```

to:

```
"from $array_docs | match (scores[(1 - 1)] == 10) | format ..."
```

Same change in `Mqlv2ShowcaseVerificationTests.verifyAllShowcaseExamples` (the `array_get` line) and in `docs/mqlv2-showcase.md` (the `array_get` subsection's HQL/MQLv2 block).

- [ ] **Step 4: Run tests**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.arrayGet \
                          --tests Mqlv2ShowcaseVerificationTests
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java \
        docs/mqlv2-showcase.md
git commit -m "MQLv2: emit array_get via Mqlv2IrEmitters; absorb [(a - b)] paren drift"
```

---

## Task 5: Migrate `array` / `array_list` constructor to IR emission

**Files:**
- Modify: `Mqlv2IrEmitters.java` — add `translateArrayConstructor`.
- Modify: `Mqlv2SelectTranslator.java`.
- No expected-string updates anticipated for the standalone constructor (`[e1, e2, …]` serializes byte-identically). The constructor inside `array_intersects` test cases is migrated as part of Task 7.

- [ ] **Step 1: Add the helper**

```java
/** Translate {@code array(e1, …, en)} / {@code array_list(...)} → {@code [e1, …, en]}. */
public static Expr translateArrayConstructor(
        org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression<?> fn,
        int[] parameterIndex) {
    var args = fn.getArguments();
    java.util.List<Expr> elements = new java.util.ArrayList<>(args.size());
    for (var arg : args) {
        if (!(arg instanceof Expression elemExpr)) {
            throw new FeatureNotSupportedException(
                    "Non-expression argument in " + fn.getFunctionName() + "()");
        }
        elements.add(translateExpression(elemExpr, parameterIndex));
    }
    return new Expr.ArrayConstructor(elements);
}
```

- [ ] **Step 2: Wire into the translator**

```java
} else if ("array".equals(fn.getFunctionName()) || "array_list".equals(fn.getFunctionName())) {
    int[] idx = {parameterBinders.size()};
    Expr ir = Mqlv2IrEmitters.translateArrayConstructor(fn, idx);
    assertParameterIndexUnchanged(idx[0]);
    sb.append(serializer.serialize(ir));
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests \
                          --tests Mqlv2ShowcaseVerificationTests
```

All pass. Constructor by itself isn't directly exercised in tests yet, but it's a building block for Task 7 (intersects), so the test pass means the rest is unaffected.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: emit array / array_list constructor via Mqlv2IrEmitters"
```

---

## Task 6: Migrate `array_contains` / `array_contains_nullable` to IR emission

**Files:**
- Modify: `Mqlv2IrEmitters.java` — add `translateArrayContains`.
- Modify: `Mqlv2SelectTranslator.java` — `tryAppendArrayPredicateFunction`'s `array_contains` arm.
- Modify: `Mqlv2ArrayFunctionsIntegrationTests.java` — absorb D3 paren drift.
- Modify: `Mqlv2ShowcaseVerificationTests.java`, `docs/mqlv2-showcase.md` — same.

- [ ] **Step 1: Add the helper**

```java
/** Translate {@code array_contains(arr, x)} / {@code _nullable} → {@code (arr any ($ == x))} or {@code is}. */
public static Expr translateArrayContains(
        org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression<?> fn,
        int[] parameterIndex) {
    var name = fn.getFunctionName();
    var args = fn.getArguments();
    if (!(args.get(0) instanceof Expression haystack) || !(args.get(1) instanceof Expression needle)) {
        throw new FeatureNotSupportedException("Non-expression argument in " + name + "()");
    }
    BinaryOpType eqOp = name.endsWith("_nullable") ? BinaryOpType.IS : BinaryOpType.EQ;
    return new Expr.Any(
            translateExpression(haystack, parameterIndex),
            new Expr.BinaryOp(eqOp, new Expr.CurrentValue(),
                    translateExpression(needle, parameterIndex)));
}
```

- [ ] **Step 2: Wire into `tryAppendArrayPredicateFunction`**

Replace the existing `array_contains` arm:

```java
if ("array_contains".equals(name) || "array_contains_nullable".equals(name)) {
    int[] idx = {parameterBinders.size()};
    Expr ir = Mqlv2IrEmitters.translateArrayContains(fn, idx);
    while (idx[0] > parameterBinders.size()) {
        throw new IllegalStateException("Parameter binders out of sync");
    }
    sb.append(serializer.serialize(ir));
    return true;
}
```

Note: the `$pN` reference IS used by the nullable test (`array_contains_nullable(d.boxedScores, :needle)`), so `parameterIndex[0]` may legitimately advance. The translator must append the JdbcParameter's binder to `parameterBinders` BEFORE invoking the IR helper so the indices line up. Look at the existing code — currently `appendExprText` does `parameterBinders.add(...)` while emitting `$pN`, in a coupled fashion. For the IR path, the simplest invariant is:

- Translator pre-collects all `JdbcParameter` occurrences inside the function's argument tree (in DFS order), pushes their binders onto `parameterBinders` in that order, then invokes the IR helper. The helper's `parameterIndex[0]` starts at the pre-push value of `parameterBinders.size()` and advances one per `JdbcParameter` seen.

Implement a small helper on `Mqlv2SelectTranslator`:

```java
private int pushJdbcParametersIn(Expression e) {
    int before = parameterBinders.size();
    collectJdbcParametersDfs(e, parameterBinders);
    return before;  // returns the starting index for IR translation
}

private static void collectJdbcParametersDfs(Expression e, List<JdbcParameterBinder> out) {
    if (e instanceof JdbcParameter jp) {
        out.add(jp.getParameterBinder());
    } else if (e instanceof BasicValuedPathInterpretation<?> bvpi) {
        collectJdbcParametersDfs(bvpi.getColumnReference(), out);
    } else if (e instanceof SqmParameterInterpretation spi) {
        collectJdbcParametersDfs(spi.getResolvedExpression(), out);
    } else if (e instanceof BinaryArithmeticExpression bae) {
        collectJdbcParametersDfs(bae.getLeftHandOperand(), out);
        collectJdbcParametersDfs(bae.getRightHandOperand(), out);
    } else if (e instanceof SelfRenderingFunctionSqlAstExpression<?> fn) {
        for (var arg : fn.getArguments()) {
            if (arg instanceof Expression sub) collectJdbcParametersDfs(sub, out);
        }
    }
    // Other expression types: no nested parameters (literals, column refs).
}
```

Then the wiring becomes:

```java
if ("array_contains".equals(name) || "array_contains_nullable".equals(name)) {
    int startIdx = pushJdbcParametersIn(args.get(0) instanceof Expression a ? a : null);
    startIdx = pushJdbcParametersIn(args.get(1) instanceof Expression b ? b : null);
    // …actually simpler: collect BOTH args, then translate with the starting index
    int starting = parameterBinders.size();
    if (args.get(0) instanceof Expression aE) collectJdbcParametersDfs(aE, parameterBinders);
    if (args.get(1) instanceof Expression bE) collectJdbcParametersDfs(bE, parameterBinders);
    int[] idx = {starting};
    Expr ir = Mqlv2IrEmitters.translateArrayContains(fn, idx);
    sb.append(serializer.serialize(ir));
    return true;
}
```

This is awkward — the JdbcParameter handling is the cleanest argument for Phase B to fold the parameter state into a proper translation context. For Phase A, ship the awkward version with a `TODO(phase-b)` comment.

- [ ] **Step 3: Update expected strings (D3 paren drift)**

In `Mqlv2ArrayFunctionsIntegrationTests.arrayContains`:

```
"from $array_docs | match (scores any ($ == 30)) | format ..."
```

becomes (Serializer output):

```
"from $array_docs | match scores any (($ == 30)) | format ..."
```

Same change in `arrayContainsNegated` (which uses outer `not`), `arrayContainsNullableWithNullParameter`, and the corresponding lines in `Mqlv2ShowcaseVerificationTests` and `docs/mqlv2-showcase.md`.

- [ ] **Step 4: Run tests**

```bash
./gradlew :integrationTest --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayContains*" \
                          --tests Mqlv2ShowcaseVerificationTests
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java \
        docs/mqlv2-showcase.md
git commit -m "MQLv2: emit array_contains[_nullable] via Mqlv2IrEmitters; absorb match/any paren drift"
```

---

## Task 7: Migrate `array_intersects` / `array_overlaps` / `_nullable` to IR emission

**Files:** as Task 6.

- [ ] **Step 1: Add the helper**

```java
/** Translate {@code array_intersects(a, b)} family → {@code (a any (let $__x = $ in b any ($ == $__x)))}. */
public static Expr translateArrayIntersects(
        org.hibernate.query.sqm.function.SelfRenderingFunctionSqlAstExpression<?> fn,
        int[] parameterIndex) {
    var name = fn.getFunctionName();
    var args = fn.getArguments();
    if (!(args.get(0) instanceof Expression aE) || !(args.get(1) instanceof Expression bE)) {
        throw new FeatureNotSupportedException("Non-expression argument in " + name + "()");
    }
    BinaryOpType eqOp = name.endsWith("_nullable") ? BinaryOpType.IS : BinaryOpType.EQ;
    return new Expr.Any(
            translateExpression(aE, parameterIndex),
            new Expr.LetExpr(
                    List.of(java.util.Map.entry("__x", (Expr) new Expr.CurrentValue())),
                    new Expr.Any(
                            translateExpression(bE, parameterIndex),
                            new Expr.BinaryOp(eqOp, new Expr.CurrentValue(), new Expr.VarRef("__x")))));
}
```

- [ ] **Step 2: Wire into `tryAppendArrayPredicateFunction`**

Same pattern as Task 6 — collect JdbcParameters into `parameterBinders` first, then invoke the IR helper, serialize, append.

```java
if ("array_intersects".equals(name) || "array_intersects_nullable".equals(name)) {
    int starting = parameterBinders.size();
    if (args.get(0) instanceof Expression aE2) collectJdbcParametersDfs(aE2, parameterBinders);
    if (args.get(1) instanceof Expression bE2) collectJdbcParametersDfs(bE2, parameterBinders);
    int[] idx = {starting};
    Expr ir = Mqlv2IrEmitters.translateArrayIntersects(fn, idx);
    sb.append(serializer.serialize(ir));
    return true;
}
```

- [ ] **Step 3: Update expected strings (D3 paren drift in nested any)**

The `array_intersects` tests assert pipelines like:

```
"from $array_docs | match (scores any (let $__x = $ in [30, 99] any ($ == $__x))) | format ..."
```

These become (Serializer output):

```
"from $array_docs | match scores any (let $__x = $ in [30, 99] any (($ == $__x))) | format ..."
```

Update `Mqlv2ArrayFunctionsIntegrationTests.arrayIntersects`, `arrayOverlapsAlias`, `arrayIntersectsNullableWithNullElement`, plus the corresponding lines in `Mqlv2ShowcaseVerificationTests` and `docs/mqlv2-showcase.md`.

- [ ] **Step 4: Run the full integration suite**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests \
                          --tests Mqlv2ShowcaseVerificationTests
```

All tests pass — Phase A is complete for the function emissions.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/mqlv2/Mqlv2IrEmitters.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ShowcaseVerificationTests.java \
        docs/mqlv2-showcase.md
git commit -m "MQLv2: emit array_intersects[_nullable] family via Mqlv2IrEmitters; absorb nested-any paren drift"
```

---

## Task 8: Cleanup + update migration design doc

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — remove the per-function `int[] idx`/`assertParameterIndexUnchanged` boilerplate scattered across arms; consolidate into one `emitViaIr(Expression…argsToScan, Function<int[], Expr> build)` helper if patterns are repeating cleanly.
- Modify: `docs/superpowers/specs/2026-05-17-mqlv2-translator-ir-migration-design.md` — mark Phase A complete; note JdbcParameter context awkwardness as Phase B work.

- [ ] **Step 1: Consolidate the IR-emit wiring**

Look at the five migrated arms. If the prelude (collect JdbcParameters → starting index → invoke helper → serialize → append) repeats verbatim, extract into a single helper:

```java
private void emitViaIr(StringBuilder sb,
                      Expression[] argsToScanForParams,
                      java.util.function.IntFunction<Expr> buildIr) {
    int starting = parameterBinders.size();
    for (Expression a : argsToScanForParams) {
        if (a != null) collectJdbcParametersDfs(a, parameterBinders);
    }
    Expr ir = buildIr.apply(starting);  // helper takes the start index, builds Expr
    sb.append(serializer.serialize(ir));
}
```

This requires the Emitters' API to take a starting index (not an `int[]`) and return both the Expr and the consumed-parameter count — or just trust the count after the fact. Iterate to a shape that reads cleanly across all five arms. If it doesn't simplify (e.g., the arity is different per function and the per-arm code is already tight), skip this step.

- [ ] **Step 2: Update the IR migration design doc**

In `docs/superpowers/specs/2026-05-17-mqlv2-translator-ir-migration-design.md`, find the Phase A bullet in the Migration strategy section and update:

```
- **Phase A (warm-up)** — port the Group-A function intercepts ... Each is small,
  recently familiar, and self-contained. Net delta should be tiny — these are the
  cases where the IR pays off most obviously. Land each as one commit.
```

to:

```
- **Phase A (complete, 2026-05-17)** — Group-A function emissions ported to driver-mqlv2
  AST + Serializer. Foundation `Mqlv2IrEmitters.translateExpression` covers literals,
  column references, parameters, and arithmetic. Five function emissions migrated:
  array_length/cardinality, array_get, array/array_list, array_contains[_nullable],
  array_intersects[_nullable]/array_overlaps[_nullable]. Drift absorbed: D2 (ArrayIndex
  parens), D3 (match/any parens). JdbcParameter indexing handled via an explicit
  collect-then-translate dance; Phase B's translation context will fold this in.
```

Also mark the smallest-first-step probe as complete in the design doc's relevant section.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java \
        docs/superpowers/specs/2026-05-17-mqlv2-translator-ir-migration-design.md
git commit -m "MQLv2: Phase A cleanup + mark migration design Phase A complete"
```

---

## Self-review checklist

- [ ] All seven Group-A function names route through `Mqlv2IrEmitters` (`array_length`, `cardinality`, `array_get`, `array`, `array_list`, `array_contains`, `array_contains_nullable`, `array_intersects`, `array_intersects_nullable`, `array_overlaps`, `array_overlaps_nullable` — though `cardinality` and `array_overlaps[_nullable]` collapse to canonical names via `registerAlternateKey`).
- [ ] `Mqlv2SelectTranslator` has no remaining hand-rolled emission for these functions (no `sb.append("count(")`, `sb.append(" any (...)")`, etc., for the migrated arms).
- [ ] `parameterBinders` list remains the authoritative source of binder ordering; the IR translation reads index from it but never writes binders independently.
- [ ] Integration tests pass against real MongoDB (the regression net).
- [ ] Showcase verification test pipeline strings updated for D2/D3 drift only — no semantic changes to result-set assertions.
- [ ] Migration design doc reflects Phase A completion.

## Open question for the user

The JdbcParameter pre-collection (`collectJdbcParametersDfs`) is the awkward bit of Phase A. It exists because the IR translation needs to know the starting `$pN` index without writing binders mid-translation. Two cleaner alternatives, both deferred to Phase B:

1. **Translation context object** that holds the binder list, hands out indices, and is threaded through every IR translation method. Cleanest type system shape.
2. **Pre-walk the entire query spec to allocate binders before any translation runs.** Requires a separate AST walk but separates concerns.

Phase B will choose between these once the surrounding translator surface is also under IR.

## Rollback plan

Each task is a single commit; revert any one to back out that function's IR emission. The pre-IR hand-rolled emission code is removed by each task's commit, so reverting a single task restores the old code path for that function. Tasks are independent — reverting Task 4 does not affect Task 5.
