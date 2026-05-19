# MQLv2 Group-A Array Functions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make seven Hibernate array functions usable under the MQLv2 translator: `array`, `array_list`, `array_length` (+ alias `cardinality`), `array_get`, `array_contains[_nullable]`, `array_intersects[_nullable]` (+ alias `array_overlaps[_nullable]`).

**Architecture:** Each HQL array function compiles, through Hibernate's SQM machinery, into a `SelfRenderingFunctionSqlAstExpression` node. The MQLv2 translator (`Mqlv2SelectTranslator`) intercepts these by function name in two existing dispatch points — `appendExprText` (expression context) and `appendPredicateText` (predicate context, via `BooleanExpressionPredicate`) — and emits MQLv2 surface syntax directly. The function descriptors that aren't already registered in `MongoDialect` are added conditionally on `mqlv2Enabled`; their `render()` methods throw `FeatureNotSupportedException` because they are never reached under v2 (intercepted earlier) and v1 does not yet implement them.

**Development style:** TDD per task. Each task writes its failing test(s) first, observes the expected failure mode (unknown function, unsupported function, ClassCastException from v1's renderer, or wrong MQL text), then implements the intercept and descriptor, then commits.

**Tech Stack:** Hibernate ORM 7.3.4.Final, MongoDB BSON, MQLv2 text DSL (per `mql-model` Haskell AST). Tests run against a real MongoDB via `MongoExtension` and assert the emitted MQLv2 pipeline text via `MqlCapture` (a StatementInspector) plus result-set verification.

---

## MQLv2 emission summary

| HQL | MQLv2 emission |
|---|---|
| `array(e1, …, en)` / `array_list(...)` | `[e1, …, en]` |
| `array_length(a)` / `cardinality(a)` | `count(a)` |
| `array_get(a, i)` | `a[(i) - 1]` |
| `array_contains(a, x)` | `(a any ($ == x))` |
| `array_contains_nullable(a, x)` | `(a any ($ is x))` |
| `array_intersects(a, b)` / `array_overlaps(a, b)` | `(a any (let $__x = $ in b any ($ == $__x)))` |
| `array_intersects_nullable(a, b)` / `array_overlaps_nullable(a, b)` | `(a any (let $__x = $ in b any ($ is $__x)))` |

**Why `is` vs `==`:** the `_nullable` variants must treat null as a matchable value. MQLv2's `==` is type-bracketed (`null == null` → null), so `arr any ($ == ?)` with a null-valued parameter coerces to false and silently misses null elements. `is` is structural equality (`null is null` → true; type mismatch → false), so it's correct for both null and non-null bound values. Since the translator can't inspect `JdbcParameter` values at translation time, the `_nullable` form commits to `is` unconditionally. For non-nullable variants we keep `==` — Hibernate's non-nullable contract says the result is null/false when either side is null, which is what `==` + `any`-coercion delivers.

Negation (`NOT array_contains(...)`) is signalled by `BooleanExpressionPredicate.isNegated()` — wrap the emitted expression with `not`.

## File structure

**Create:**
- `src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayLengthFunction.java` — descriptor for `array_length` (with `cardinality` registered as an alternate key). `render()` throws.
- `src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayGetFunction.java` — descriptor for `array_get`. `render()` throws.
- `src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayIntersectsFunction.java` — descriptor for `array_intersects[_nullable]` (with `array_overlaps[_nullable]` as alternate keys). `render()` throws.
- `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java` — one integration test class covering all Group-A functions.

**Modify:**
- `src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java` — gate the three new descriptor registrations + four alternate keys on `mqlv2Enabled`.
- `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java` — extend `appendExprText`'s `SelfRenderingFunctionSqlAstExpression` branch with three new intercepts; extend `appendPredicateText`'s `BooleanExpressionPredicate` branch with two new intercepts (array_contains-family, array_intersects-family).

## Conventions in this codebase

- Field paths in v2 may be either bare column names (no joins) or qualified (`alias.col`); use `appendExprText` recursively to render argument expressions — never hand-format paths.
- `BasicValuedPathInterpretation` and `SqmParameterInterpretation` unwrapping is already handled inside `appendExprText` — pass arguments straight in.
- `Mqlv2OnlyArrayXFunction` classes mirror the style of Hibernate-core's `OracleArrayLengthFunction`/`AbstractArrayIntersectsFunction` (see those files for arg validators / return-type resolvers to copy).
- Test pattern: `Mqlv2UnnestExistsIntegrationTests.java` (already in the tree) demonstrates the StatementInspector capture + result-set assertion idiom. Reuse the same DomainModel/SessionFactory wiring.

---

## Task 1: Test scaffolding + `array_length` / `cardinality`

This task stands up the integration test file and lands the simplest of the seven functions end-to-end. Subsequent tasks add tests to the same file and extend the translator.

**Files:**
- Test: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java`
- Create: `src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayLengthFunction.java`
- Modify: `src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java`
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Write the failing test class**

```java
/*
 * Copyright 2025-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); ... (standard header)
 */
package com.mongodb.hibernate.query.mqlv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.cfg.AvailableSettings.DIALECT;
import static org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.bson.BsonDocument;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@DomainModel(annotatedClasses = {Mqlv2ArrayFunctionsIntegrationTests.ArrayDoc.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2ArrayFunctionsIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from ArrayDoc").executeUpdate();
            session.persist(new ArrayDoc(1, new int[] {10, 20, 30}));
            session.persist(new ArrayDoc(2, new int[] {30, 40}));
            session.persist(new ArrayDoc(3, new int[] {}));
        });
        MqlCapture.LAST.remove();
    }

    private String capturedPipeline() {
        var captured = MqlCapture.LAST.get();
        assertThat(captured).isNotNull();
        return BsonDocument.parse(captured).getString("mqlv2").getValue();
    }

    @Test
    void arrayLength() {
        var hql = "from ArrayDoc d where array_length(d.scores) > 2";
        var rows = sessionFactoryScope.fromSession(session ->
                session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (count(scores) > 2)"
                        + " | format {_id: _id, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(1);
    }

    @Test
    void cardinalityAlias() {
        var hql = "from ArrayDoc d where cardinality(d.scores) = 0";
        var rows = sessionFactoryScope.fromSession(session ->
                session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (count(scores) == 0)"
                        + " | format {_id: _id, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(3);
    }

    @Entity(name = "ArrayDoc")
    @Table(name = "array_docs")
    static class ArrayDoc {
        @Id
        int id;

        int[] scores;

        ArrayDoc() {}

        ArrayDoc(int id, int[] scores) {
            this.id = id;
            this.scores = scores;
        }
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.arrayLength :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.cardinalityAlias
```

Expected: both fail at HQL compile time with "unknown function: array_length / cardinality" — `array_length` isn't registered in `MongoDialect` yet.

- [ ] **Step 3: Create the descriptor**

`src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayLengthFunction.java`:

```java
package com.mongodb.hibernate.internal.dialect.function.array;

// (standard header omitted)

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import java.util.List;
import org.hibernate.dialect.function.array.ArrayArgumentValidator;
import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.query.sqm.function.AbstractSqmSelfRenderingFunctionDescriptor;
import org.hibernate.query.sqm.produce.function.StandardArgumentsValidators;
import org.hibernate.query.sqm.produce.function.StandardFunctionReturnTypeResolvers;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * MQLv2-only descriptor for {@code array_length(arr)}. The MQLv2 translator intercepts this
 * function by name in {@code appendExprText} and emits {@code count(arr)}; this descriptor's
 * {@code render()} method is never called under v2 and throws under v1.
 *
 * @hidden
 */
public final class Mqlv2OnlyArrayLengthFunction extends AbstractSqmSelfRenderingFunctionDescriptor {
    public Mqlv2OnlyArrayLengthFunction(TypeConfiguration typeConfiguration) {
        super(
                "array_length",
                StandardArgumentsValidators.composite(
                        StandardArgumentsValidators.exactly(1),
                        ArrayArgumentValidator.DEFAULT_INSTANCE),
                StandardFunctionReturnTypeResolvers.invariant(
                        typeConfiguration.standardBasicTypeForJavaType(Integer.class)),
                null);
    }

    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> sqlAstArguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        throw new FeatureNotSupportedException("array_length() is only supported by the MQLv2 translator");
    }

    @Override
    public String getArgumentListSignature() {
        return "(ARRAY array)";
    }
}
```

- [ ] **Step 4: Register conditionally in MongoDialect**

In `MongoDialect.initializeFunctionRegistry`, after the existing `array_includes_nullable` registration, add:

```java
if (mqlv2Enabled) {
    functionRegistry.register(
            "array_length", new Mqlv2OnlyArrayLengthFunction(typeConfiguration));
    functionRegistry.registerAlternateKey("cardinality", "array_length");
}
```

- [ ] **Step 5: Add the v2 translator intercept**

In `Mqlv2SelectTranslator.java`'s `appendExprText`, inside the `SelfRenderingFunctionSqlAstExpression` branch (currently around line 1382), insert this `else if` arm above the final `throw`:

```java
} else if ("array_length".equals(fn.getFunctionName()) || "cardinality".equals(fn.getFunctionName())) {
    sb.append("count(");
    appendExprText(sb, (Expression) fn.getArguments().get(0));
    sb.append(")");
```

The cast `(Expression) fn.getArguments().get(0)` is safe — the descriptor validators enforce that arguments are expressions. (`MongoArrayContainsFunction.getArgumentAsExpression` is the v1 precedent for the same cast.)

- [ ] **Step 6: Re-run and confirm green**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.arrayLength :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.cardinalityAlias
```

Expected: both pass.

- [ ] **Step 7: Commit**

```bash
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayLengthFunction.java \
        src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: support array_length / cardinality (descriptor + intercept + integration tests)"
```

---

## Task 2: `array_get`

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java`
- Create: `src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayGetFunction.java`
- Modify: `src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java`
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Add the failing test**

Append to `Mqlv2ArrayFunctionsIntegrationTests`:

```java
@Test
void arrayGet() {
    var hql = "from ArrayDoc d where array_get(d.scores, 1) = 10";
    var rows = sessionFactoryScope.fromSession(session ->
            session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
    assertThat(capturedPipeline())
            .isEqualTo("from $array_docs | match (scores[(1) - 1] == 10)"
                    + " | format {_id: _id, scores: scores}");
    assertThat(rows).extracting(d -> d.id).containsExactly(1);
}
```

- [ ] **Step 2: Run to confirm "unknown function: array_get"**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.arrayGet
```

- [ ] **Step 3: Create the descriptor**

`src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayGetFunction.java`:

```java
package com.mongodb.hibernate.internal.dialect.function.array;

// (standard header omitted)

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import java.util.List;
import org.hibernate.dialect.function.array.AbstractArrayGetFunction;
import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;

/**
 * MQLv2-only descriptor for {@code array_get(arr, i)}. Intercepted by the v2 translator and
 * emitted as {@code arr[(i) - 1]} (HQL is 1-based, MQLv2 is 0-based).
 *
 * @hidden
 */
public final class Mqlv2OnlyArrayGetFunction extends AbstractArrayGetFunction {
    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> sqlAstArguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        throw new FeatureNotSupportedException("array_get() is only supported by the MQLv2 translator");
    }
}
```

- [ ] **Step 4: Register conditionally in MongoDialect**

Inside the existing `if (mqlv2Enabled)` block, append:

```java
functionRegistry.register("array_get", new Mqlv2OnlyArrayGetFunction());
```

- [ ] **Step 5: Add the v2 translator intercept**

In `appendExprText`'s function branch, after the `array_length` arm:

```java
} else if ("array_get".equals(fn.getFunctionName())) {
    var args = fn.getArguments();
    appendExprText(sb, (Expression) args.get(0));
    sb.append("[(");
    appendExprText(sb, (Expression) args.get(1));
    sb.append(") - 1]");
```

- [ ] **Step 6: Re-run and confirm green**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests.arrayGet
```

- [ ] **Step 7: Commit**

```bash
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayGetFunction.java \
        src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: support array_get (descriptor + intercept + integration test)"
```

---

## Task 3: `array_contains` and `array_contains_nullable`

These are already registered in `MongoDialect` (`MongoArrayContainsFunction`, both nullable and non-nullable). Under v2, calling them today fails because the v1 renderer's `cast(walker)` returns the wrong type. Test should observe that. Then add the v2 intercept and re-run.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java`
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Add the failing tests (and extend the entity for the nullable case)**

First, extend the inner `ArrayDoc` entity in the test class with a boxed-Integer array so a parameter can be bound to `null`:

```java
@Entity(name = "ArrayDoc")
@Table(name = "array_docs")
static class ArrayDoc {
    @Id
    int id;

    int[] scores;

    @org.hibernate.annotations.Array(length = 10)
    Integer[] boxedScores;

    ArrayDoc() {}

    ArrayDoc(int id, int[] scores) { this(id, scores, null); }

    ArrayDoc(int id, int[] scores, Integer[] boxedScores) {
        this.id = id;
        this.scores = scores;
        this.boxedScores = boxedScores;
    }
}
```

(If `@Array` is unnecessary in this codebase's existing array-mapping setup, omit it — match the conventions used in `Mqlv2ArrayHydrationIntegrationTests`.)

Now add tests:

```java
@Test
void arrayContains() {
    var hql = "from ArrayDoc d where array_contains(d.scores, 30)";
    var rows = sessionFactoryScope.fromSession(session ->
            session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
    assertThat(capturedPipeline())
            .isEqualTo("from $array_docs | match (scores any ($ == 30))"
                    + " | format {_id: _id, scores: scores, boxedScores: boxedScores}");
    assertThat(rows).extracting(d -> d.id).containsExactlyInAnyOrder(1, 2);
}

@Test
void arrayContainsNegated() {
    var hql = "from ArrayDoc d where not array_contains(d.scores, 30)";
    var rows = sessionFactoryScope.fromSession(session ->
            session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
    assertThat(capturedPipeline())
            .isEqualTo("from $array_docs | match (not (scores any ($ == 30)))"
                    + " | format {_id: _id, scores: scores, boxedScores: boxedScores}");
    assertThat(rows).extracting(d -> d.id).containsExactly(3);
}

@Test
void arrayContainsNullableWithNullParameter() {
    sessionFactoryScope.inTransaction(session ->
            session.persist(new ArrayDoc(4, new int[] {0}, new Integer[] {null, 50})));
    var hql = "from ArrayDoc d where array_contains_nullable(d.boxedScores, :needle)";
    var rows = sessionFactoryScope.fromSession(session -> session.createSelectionQuery(hql, ArrayDoc.class)
            .setParameter("needle", (Integer) null)
            .getResultList());
    assertThat(capturedPipeline()).contains("(boxedScores any ($ is $p0))");
    assertThat(rows).extracting(d -> d.id).containsExactly(4);
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :integrationTest --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayContains*"
```

Expected: tests fail because the v1 renderer is invoked under v2. The exception will be a `ClassCastException` or similar from `AbstractMqlTranslator.cast(walker)`. Inspect the failure to confirm it's the v1-renderer path, not something else.

- [ ] **Step 3: Add the predicate-context intercept helper**

In `Mqlv2SelectTranslator.java`, add a private helper near `appendPredicateText`:

```java
/**
 * Returns {@code true} and emits MQLv2 surface text if {@code expression} is a known
 * boolean-returning array function that v2 supports as a predicate; returns {@code false}
 * otherwise (caller falls through to the generic {@code (expr == true)} emission).
 */
private boolean tryAppendArrayPredicateFunction(StringBuilder sb, Expression expression, boolean negated) {
    if (!(expression instanceof SelfRenderingFunctionSqlAstExpression<?> fn)) {
        return false;
    }
    var name = fn.getFunctionName();
    var args = fn.getArguments();
    if ("array_contains".equals(name) || "array_contains_nullable".equals(name)) {
        // _nullable uses `is` so a null-valued JdbcParameter matches null elements;
        // non-nullable uses `==` per Hibernate's null-propagation contract.
        var eqOp = "array_contains_nullable".equals(name) ? "is" : "==";
        if (negated) sb.append("(not ");
        sb.append("(");
        appendExprText(sb, (Expression) args.get(0));
        sb.append(" any ($ ").append(eqOp).append(" ");
        appendExprText(sb, (Expression) args.get(1));
        sb.append("))");
        if (negated) sb.append(")");
        return true;
    }
    return false;
}
```

- [ ] **Step 4: Wire the helper into `appendPredicateText`'s BEP branch**

Replace the existing `BooleanExpressionPredicate` branch (around line 1276):

```java
} else if (predicate instanceof BooleanExpressionPredicate bp) {
    sb.append("(");
    appendExprText(sb, bp.getExpression());
    sb.append(bp.isNegated() ? " == false)" : " == true)");
}
```

with:

```java
} else if (predicate instanceof BooleanExpressionPredicate bp) {
    if (!tryAppendArrayPredicateFunction(sb, bp.getExpression(), bp.isNegated())) {
        sb.append("(");
        appendExprText(sb, bp.getExpression());
        sb.append(bp.isNegated() ? " == false)" : " == true)");
    }
}
```

- [ ] **Step 5: Re-run and confirm green**

```bash
./gradlew :integrationTest --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayContains*"
```

- [ ] **Step 6: Commit**

```bash
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: intercept array_contains[_nullable] in appendPredicateText"
```

---

## Task 4: `array` / `array_list` constructor + `array_intersects[_nullable]` / `array_overlaps[_nullable]`

The constructor is naturally exercised when used as the second argument of `array_intersects`, so we test both in this task — the constructor intercept lives in `appendExprText`, the intersects intercept lives in `appendPredicateText`'s helper.

**Files:**
- Modify: `src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java`
- Create: `src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayIntersectsFunction.java`
- Modify: `src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java`
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java`

- [ ] **Step 1: Add the failing tests**

```java
@Test
void arrayIntersects() {
    var hql = "from ArrayDoc d where array_intersects(d.scores, array(30, 99))";
    var rows = sessionFactoryScope.fromSession(session ->
            session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
    assertThat(capturedPipeline())
            .isEqualTo("from $array_docs"
                    + " | match (scores any (let $__x = $ in [30, 99] any ($ == $__x)))"
                    + " | format {_id: _id, scores: scores, boxedScores: boxedScores}");
    assertThat(rows).extracting(d -> d.id).containsExactlyInAnyOrder(1, 2);
}

@Test
void arrayOverlapsAlias() {
    var hql = "from ArrayDoc d where array_overlaps(d.scores, array(10, 40))";
    var rows = sessionFactoryScope.fromSession(session ->
            session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
    assertThat(capturedPipeline())
            .isEqualTo("from $array_docs"
                    + " | match (scores any (let $__x = $ in [10, 40] any ($ == $__x)))"
                    + " | format {_id: _id, scores: scores, boxedScores: boxedScores}");
    assertThat(rows).extracting(d -> d.id).containsExactlyInAnyOrder(1, 2);
}

@Test
void arrayIntersectsNullableWithNullElement() {
    sessionFactoryScope.inTransaction(session ->
            session.persist(new ArrayDoc(5, new int[] {0}, new Integer[] {null, 50})));
    var hql = "from ArrayDoc d where array_intersects_nullable(d.boxedScores, array(cast(null as Integer)))";
    var rows = sessionFactoryScope.fromSession(session ->
            session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
    assertThat(capturedPipeline()).contains("any (let $__x = $ in [null] any ($ is $__x))");
    assertThat(rows).extracting(d -> d.id).containsExactly(5);
}
```

The third test confirms `_nullable` uses `is`. If the `cast(null as Integer)` form in HQL doesn't shake out cleanly inside `array(...)`, fall back to binding a parameter array (e.g., `:needles` with `new Integer[] {null}`) — the assertion still holds on the `is`-vs-`==` emission.

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :integrationTest --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayIntersects*" \
                          --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayOverlaps*"
```

Expected: `array_intersects` is "unknown function" (not registered yet). The `array(...)` constructor argument either also errors or is silently fine until intersects is added — either way, all three tests fail.

- [ ] **Step 3: Create the descriptor**

`src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayIntersectsFunction.java`:

```java
package com.mongodb.hibernate.internal.dialect.function.array;

// (standard header omitted)

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import java.util.List;
import org.hibernate.dialect.function.array.AbstractArrayIntersectsFunction;
import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * MQLv2-only descriptor for {@code array_intersects(arr1, arr2)} and the nullable variant.
 * Intercepted by the v2 translator and emitted as
 * {@code (arr1 any (let $__x = $ in arr2 any ($ <eqOp> $__x)))} where {@code eqOp} is {@code ==}
 * for the non-nullable variant and {@code is} for {@code _nullable}.
 *
 * @hidden
 */
public final class Mqlv2OnlyArrayIntersectsFunction extends AbstractArrayIntersectsFunction {
    public Mqlv2OnlyArrayIntersectsFunction(boolean nullable, TypeConfiguration typeConfiguration) {
        super(nullable, typeConfiguration);
    }

    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> sqlAstArguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        throw new FeatureNotSupportedException(
                "array_intersects() / array_overlaps() are only supported by the MQLv2 translator");
    }
}
```

- [ ] **Step 4: Register conditionally in MongoDialect**

Inside the existing `if (mqlv2Enabled)` block, append:

```java
functionRegistry.register(
        "array_intersects", new Mqlv2OnlyArrayIntersectsFunction(false, typeConfiguration));
functionRegistry.register(
        "array_intersects_nullable", new Mqlv2OnlyArrayIntersectsFunction(true, typeConfiguration));
functionRegistry.registerAlternateKey("array_overlaps", "array_intersects");
functionRegistry.registerAlternateKey("array_overlaps_nullable", "array_intersects_nullable");
```

- [ ] **Step 5: Add the array constructor intercept in `appendExprText`**

In the function branch, above the final `throw`:

```java
} else if ("array".equals(fn.getFunctionName()) || "array_list".equals(fn.getFunctionName())) {
    var args = fn.getArguments();
    sb.append("[");
    for (var i = 0; i < args.size(); i++) {
        if (i > 0) sb.append(", ");
        appendExprText(sb, (Expression) args.get(i));
    }
    sb.append("]");
```

- [ ] **Step 6: Extend `tryAppendArrayPredicateFunction` with the intersects family**

Inside the helper added in Task 3, after the `array_contains` arm:

```java
if ("array_intersects".equals(name) || "array_intersects_nullable".equals(name)
        || "array_overlaps".equals(name) || "array_overlaps_nullable".equals(name)) {
    var eqOp = name.endsWith("_nullable") ? "is" : "==";
    if (negated) sb.append("(not ");
    sb.append("(");
    appendExprText(sb, (Expression) args.get(0));
    sb.append(" any (let $__x = $ in ");
    appendExprText(sb, (Expression) args.get(1));
    sb.append(" any ($ ").append(eqOp).append(" $__x)))");
    if (negated) sb.append(")");
    return true;
}
```

- [ ] **Step 7: Re-run and confirm green**

```bash
./gradlew :integrationTest --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayIntersects*" \
                          --tests "Mqlv2ArrayFunctionsIntegrationTests.arrayOverlaps*"
```

- [ ] **Step 8: Full-file regression run**

```bash
./gradlew :integrationTest --tests Mqlv2ArrayFunctionsIntegrationTests
```

Expected: every test in the file passes.

- [ ] **Step 9: Commit**

```bash
git add src/integrationTest/java/com/mongodb/hibernate/query/mqlv2/Mqlv2ArrayFunctionsIntegrationTests.java \
        src/main/java/com/mongodb/hibernate/internal/dialect/function/array/Mqlv2OnlyArrayIntersectsFunction.java \
        src/main/java/com/mongodb/hibernate/dialect/MongoDialect.java \
        src/main/java/com/mongodb/hibernate/internal/translate/Mqlv2SelectTranslator.java
git commit -m "MQLv2: support array / array_list constructor + array_intersects[_nullable] family"
```

---

## Task 5: README documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document v2-supported array functions**

In the MQLv2 Backend section, add a sub-section listing the seven Group-A functions and their MQLv2 emission. Mirror the table at the top of this plan.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document Group-A array function support in MQLv2"
```

---

## Self-review checklist

- [ ] All seven HQL function names route through exactly one intercept (no overlap between `appendExprText` and `appendPredicateText`).
- [ ] Negation via `BooleanExpressionPredicate.isNegated()` produces a valid MQLv2 `(not ...)` wrap.
- [ ] The five new descriptors are registered only when `mqlv2Enabled` — v1 SQM parsing still rejects them with "unknown function".
- [ ] `_nullable` variants emit `is`; non-nullable variants emit `==`.
- [ ] Existing v1 tests still pass — none of the conditional registrations affect the v1 code path.
- [ ] No reformatting of unrelated code; only additive changes to `appendExprText`, `appendPredicateText`, and `initializeFunctionRegistry`.
