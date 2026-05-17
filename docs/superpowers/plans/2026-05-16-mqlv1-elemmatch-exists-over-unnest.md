# MQLv1 — `$elemMatch` via EXISTS-over-unnest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support HQL of the form `from Parent p where exists (from p.array a where <body>)` in the MQLv1 select translator, emitting `{<arrayField>: {$elemMatch: <body>}}` in the generated `$match` stage. Covers single predicates, AND/OR junctions, NOT, and the surrounding `NOT EXISTS` form.

**Architecture:** Intercept `ExistsPredicate` in `AbstractMqlTranslator`. When the subquery's SQL AST matches the "unnest-over-parent-collection-path with body predicate" shape, walk the body with an "alias-stripping" context and produce an `AstElemMatchFilterOperation` wrapped in an `AstFieldOperationFilter`. Anything outside the supported shape keeps throwing `FeatureNotSupportedException` (existing behavior) — purely additive. v1 stays independent of MQLv2 — detection logic is copied, not shared.

**Tech Stack:** Hibernate ORM 7.3.4, MongoDB Java driver, existing v1 AST + translator infrastructure.

---

## File Structure

- Create: `src/main/java/com/mongodb/hibernate/internal/translate/mongoast/filter/AstElemMatchFilterOperation.java`
  — new AST node: `{$elemMatch: <body filter>}`.
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/AbstractMqlTranslator.java`
  — implement `visitExistsPredicate`, helper `recognizeExistsOverUnnest`, helper `referencesOutsideAlias`.
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/MqlCapture.java`
  — StatementInspector that captures the MQL pipeline string emitted by the v1 dialect, mirroring the v2 `query.mqlv2.MqlCapture` but kept independent (no shared code with v2).
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchIntegrationTests.java`
  — end-to-end against a live MongoDB. Each test asserts BOTH the captured pipeline JSON string AND the query result rows on seeded data.
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchCart.java` + `ElemMatchLineItem.java`
  — self-contained entities for the tests (no dependency on v2 test fixtures).

Note: there is no v1 translator-only unit test harness; all v1 translator behavior is exercised via integration tests. Pipeline-string assertion happens via the `MqlCapture` StatementInspector — same pattern v2 uses.

---

## Task 1: `AstElemMatchFilterOperation` AST node

**Files:**
- Create: `src/main/java/com/mongodb/hibernate/internal/translate/mongoast/filter/AstElemMatchFilterOperation.java`
- Test: `src/test/java/com/mongodb/hibernate/internal/translate/mongoast/filter/AstElemMatchFilterOperationTests.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mongodb.hibernate.internal.translate.mongoast.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.hibernate.internal.translate.mongoast.AstLiteralValue;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.junit.jupiter.api.Test;
import org.bson.BsonString;

class AstElemMatchFilterOperationTests {

    @Test
    void rendersAsElemMatchWrappingBody() {
        AstFilter body = new AstFieldOperationFilter(
                "sku",
                new AstComparisonFilterOperation(AstComparisonFilterOperator.EQ, new AstLiteralValue(new BsonString("WIDGET"))));
        AstElemMatchFilterOperation op = new AstElemMatchFilterOperation(body);

        var doc = new BsonDocument();
        try (var writer = new BsonDocumentWriter(doc)) {
            writer.writeStartDocument();
            writer.writeName("lineItems");
            op.render(writer);
            writer.writeEndDocument();
        }

        assertThat(doc.toJson()).isEqualTo("{\"lineItems\": {\"$elemMatch\": {\"sku\": {\"$eq\": \"WIDGET\"}}}}");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.mongodb.hibernate.internal.translate.mongoast.filter.AstElemMatchFilterOperationTests'`
Expected: FAIL with `AstElemMatchFilterOperation` not found.

- [ ] **Step 3: Write minimal implementation**

```java
package com.mongodb.hibernate.internal.translate.mongoast.filter;

import org.bson.BsonWriter;

/**
 * Wraps a filter body inside a {@code $elemMatch} operator. Combine with an enclosing
 * {@link AstFieldOperationFilter} to produce {@code {<array>: {$elemMatch: <body>}}}.
 *
 * @hidden
 */
public record AstElemMatchFilterOperation(AstFilter body) implements AstFilterOperation {
    @Override
    public void render(BsonWriter writer) {
        writer.writeStartDocument();
        {
            writer.writeName("$elemMatch");
            body.render(writer);
        }
        writer.writeEndDocument();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.mongodb.hibernate.internal.translate.mongoast.filter.AstElemMatchFilterOperationTests'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/mongoast/filter/AstElemMatchFilterOperation.java \
        src/test/java/com/mongodb/hibernate/internal/translate/mongoast/filter/AstElemMatchFilterOperationTests.java
git commit -m "v1: add AstElemMatchFilterOperation AST node"
```

---

## Task 2: Detect EXISTS-over-unnest shape and emit `$elemMatch`

**Files:**
- Modify: `src/main/java/com/mongodb/hibernate/internal/translate/AbstractMqlTranslator.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/MqlCapture.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchCart.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchLineItem.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchIntegrationTests.java`

The shape we accept: an `ExistsPredicate` whose inner `SelectStatement` has a `QuerySpec` whose `FromClause` has exactly one root that is a `TableGroup` whose primary `TableReference` is a `FunctionTableReference` named `"unnest"`. The unnest argument is a `ColumnReference` whose qualifier is the outer FROM's alias; its column expression is the array field name. The subquery has no further joins, GROUP BY, ORDER BY, OFFSET, or LIMIT. The subquery's SELECT list is irrelevant (we never project it).

- [ ] **Step 1: Add a translator unit test for the single-condition case**

In `SelectMqlTranslatorTests.java`:

```java
@Test
void existsOverCollectionPath_singlePredicate_emitsElemMatch() {
    String hql = "from Cart c where exists (from c.lineItems li where li.sku = 'WIDGET-1')";
    String mql = translate(hql);
    assertThat(mql).isEqualTo(
            "[{\"$match\": {\"lineItems\": {\"$elemMatch\": {\"sku\": {\"$eq\": \"WIDGET-1\"}}}}}, "
            + "{\"$project\": {\"_id\": true, \"minQty\": true, \"lineItems\": true}}]");
}
```

(Re-use the `Cart` test entity from `src/integrationTest/.../mqlv2/Mqlv2ShowcaseVerificationTests.java` — copy it into a v1 test fixture, do not depend on the MQLv2 test class.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.mongodb.hibernate.internal.translate.SelectMqlTranslatorTests.existsOverCollectionPath_singlePredicate_emitsElemMatch'`
Expected: FAIL — `visitExistsPredicate` still throws `FeatureNotSupportedException`.

- [ ] **Step 3: Add the shape-detection helper to `AbstractMqlTranslator`**

Insert near the bottom of `AbstractMqlTranslator` (alongside `checkFromClauseSupportability`):

```java
/**
 * Returns true iff the table reference is a {@link FunctionTableReference} named {@code "unnest"}.
 */
private static boolean isUnnestFunctionTable(TableReference ref) {
    return ref instanceof FunctionTableReference ftr
            && "unnest".equals(ftr.getFunctionExpression().getFunctionName());
}

/**
 * @return the array column name if {@code existsPredicate}'s subquery matches the
 *         {@code from <outer>.<arrayField> li where <body>} shape, otherwise {@code null}.
 */
private static @Nullable ExistsOverUnnestShape recognizeExistsOverUnnest(ExistsPredicate existsPredicate) {
    if (!(existsPredicate.getExpression() instanceof SelectStatement select)) {
        return null;
    }
    var qs = select.getQueryPart() instanceof QuerySpec spec ? spec : null;
    if (qs == null
            || qs.getFromClause().getRoots().size() != 1
            || qs.getGroupByClauseExpressions() != null && !qs.getGroupByClauseExpressions().isEmpty()
            || qs.getSortSpecifications() != null && !qs.getSortSpecifications().isEmpty()
            || qs.getOffsetClauseExpression() != null
            || qs.getFetchClauseExpression() != null) {
        return null;
    }
    var root = qs.getFromClause().getRoots().get(0);
    if (!root.getTableGroupJoins().isEmpty() || !root.getNestedTableGroupJoins().isEmpty()) {
        return null;
    }
    if (!(root.getPrimaryTableReference() instanceof FunctionTableReference ftr)
            || !"unnest".equals(ftr.getFunctionExpression().getFunctionName())) {
        return null;
    }
    var args = ftr.getFunctionExpression().getArguments();
    if (args.size() != 1 || !(args.get(0) instanceof ColumnReference colRef)) {
        return null;
    }
    return new ExistsOverUnnestShape(colRef.getColumnExpression(),
            root.getPrimaryTableReference().getIdentificationVariable(),
            qs.getWhereClauseRestrictions());
}

private record ExistsOverUnnestShape(String arrayFieldName, String innerAlias, Predicate body) {}
```

- [ ] **Step 4: Reject correlated body references**

v1's `visitColumnReference` already emits just the bare column name regardless of qualifier — so a body that references the outer alias (e.g., `where li.qty > c.minQty`) would silently emit `"minQty"` inside the `$elemMatch` body, which MongoDB would interpret as a field on the *array element*, not the parent document. That's a wrong-results bug. Reject these explicitly with `FeatureNotSupportedException`.

Add a helper to `AbstractMqlTranslator`:

```java
/** Returns true iff any {@link ColumnReference} in {@code predicate} has a qualifier other than {@code innerAlias}. */
private static boolean referencesOutsideAlias(Predicate predicate, String innerAlias) {
    var found = new boolean[]{false};
    predicate.accept(new AbstractSqlAstWalker() {
        @Override
        public void visitColumnReference(ColumnReference columnReference) {
            var qualifier = columnReference.getQualifier();
            if (qualifier != null && !qualifier.equals(innerAlias)) {
                found[0] = true;
            }
        }
    });
    return found[0];
}
```

(If `AbstractSqlAstWalker` isn't already imported / available, write the walk by hand against the `Predicate` subtypes Hibernate actually produces: `ComparisonPredicate`, `Junction`, `NegatedPredicate`, `GroupedPredicate`, `BooleanExpressionPredicate`, `NullnessPredicate`. The visitor needs to recurse into each.)

- [ ] **Step 5: Implement `visitExistsPredicate`**

Replace the throwing body:

```java
@Override
public void visitExistsPredicate(ExistsPredicate existsPredicate) {
    var shape = recognizeExistsOverUnnest(existsPredicate);
    if (shape == null || shape.body() == null
            || referencesOutsideAlias(shape.body(), shape.innerAlias())) {
        throw new FeatureNotSupportedException();
    }
    var bodyFilter = acceptAndYield(shape.body(), FILTER);
    var filter = new AstFieldOperationFilter(shape.arrayFieldName(),
            new AstElemMatchFilterOperation(bodyFilter));
    astVisitorValueHolder.yield(FILTER, existsPredicate.isNegated()
            ? new AstLogicalFilter(AstLogicalFilterOperator.NOR, java.util.List.of(filter))
            : filter);
}
```

No alias-stripping context is needed because `visitColumnReference` already yields just `getColumnExpression()` (the bare column name) regardless of qualifier — so once correlated references are rejected up front, the body walker produces the right field names by accident-of-existing-behavior.

- [ ] **Step 6: Add a unit test for the correlated-reject case**

```java
@Test
void existsOverCollectionPath_correlatedBody_throwsFeatureNotSupported() {
    String hql = "from Cart c where exists (from c.lineItems li where li.qty > c.minQty)";
    assertThatThrownBy(() -> translate(hql)).isInstanceOf(FeatureNotSupportedException.class);
}
```

- [ ] **Step 7: Run the unit tests**

Run: `./gradlew test --tests 'com.mongodb.hibernate.internal.translate.SelectMqlTranslatorTests.existsOverCollectionPath_singlePredicate_emitsElemMatch' --tests 'com.mongodb.hibernate.internal.translate.SelectMqlTranslatorTests.existsOverCollectionPath_correlatedBody_throwsFeatureNotSupported'`
Expected: 2 tests PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mongodb/hibernate/internal/translate/AbstractMqlTranslator.java \
        src/test/java/com/mongodb/hibernate/internal/translate/SelectMqlTranslatorTests.java
git commit -m "v1: emit \$elemMatch for EXISTS over collection-valued path"
```

---

## Task 3: Cover AND / OR / NOT body shapes via translator unit tests

**Files:**
- Modify: `src/test/java/com/mongodb/hibernate/internal/translate/SelectMqlTranslatorTests.java`

The body predicate is walked by the existing junction/negation visitors, which already produce `AstLogicalFilter`. No new translator code is expected — this task verifies via tests that the recursion produces the right thing.

- [ ] **Step 1: Add tests for AND, OR, NOT body**

```java
@Test
void existsOverCollectionPath_andBody_emitsElemMatchWithAnd() {
    String hql = "from Cart c where exists (from c.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0)";
    String mql = translate(hql);
    assertThat(mql).isEqualTo(
            "[{\"$match\": {\"lineItems\": {\"$elemMatch\": "
            + "{\"$and\": [{\"sku\": {\"$eq\": \"WIDGET-1\"}}, {\"qty\": {\"$gt\": 0}}]}}}}, "
            + "{\"$project\": {\"_id\": true, \"minQty\": true, \"lineItems\": true}}]");
}

@Test
void existsOverCollectionPath_orBody_emitsElemMatchWithOr() {
    String hql = "from Cart c where exists (from c.lineItems li where li.sku = 'WIDGET-1' or li.qty > 5)";
    String mql = translate(hql);
    assertThat(mql).isEqualTo(
            "[{\"$match\": {\"lineItems\": {\"$elemMatch\": "
            + "{\"$or\": [{\"sku\": {\"$eq\": \"WIDGET-1\"}}, {\"qty\": {\"$gt\": 5}}]}}}}, "
            + "{\"$project\": {\"_id\": true, \"minQty\": true, \"lineItems\": true}}]");
}

@Test
void existsOverCollectionPath_notBody_emitsElemMatchWithNor() {
    String hql = "from Cart c where exists (from c.lineItems li where not (li.sku = 'WIDGET-1'))";
    String mql = translate(hql);
    assertThat(mql).isEqualTo(
            "[{\"$match\": {\"lineItems\": {\"$elemMatch\": "
            + "{\"$nor\": [{\"sku\": {\"$eq\": \"WIDGET-1\"}}]}}}}, "
            + "{\"$project\": {\"_id\": true, \"minQty\": true, \"lineItems\": true}}]");
}

@Test
void notExistsOverCollectionPath_emitsNorWrappingElemMatch() {
    String hql = "from Cart c where not exists (from c.lineItems li where li.sku = 'WIDGET-1')";
    String mql = translate(hql);
    assertThat(mql).isEqualTo(
            "[{\"$match\": {\"$nor\": [{\"lineItems\": {\"$elemMatch\": {\"sku\": {\"$eq\": \"WIDGET-1\"}}}}]}}, "
            + "{\"$project\": {\"_id\": true, \"minQty\": true, \"lineItems\": true}}]");
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests 'com.mongodb.hibernate.internal.translate.SelectMqlTranslatorTests.existsOverCollectionPath_*' --tests 'com.mongodb.hibernate.internal.translate.SelectMqlTranslatorTests.notExistsOverCollectionPath_*'`
Expected: 4 tests PASS.

If a test fails because the visitor produces a different shape than expected (e.g. the `NOT (a = b)` form might serialize to a `$ne` rather than `$nor` depending on how Hibernate's SQL AST normalizes it), update the expected string to match what the translator emits — but verify by hand that the result is *semantically equivalent* before adjusting.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/mongodb/hibernate/internal/translate/SelectMqlTranslatorTests.java
git commit -m "v1: verify AND/OR/NOT body and NOT EXISTS forms for \$elemMatch"
```

---

## Task 4: Integration test against real MongoDB

**Files:**
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchIntegrationTests.java`
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchCart.java` (`@Entity`)
- Create: `src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchLineItem.java` (`@Embeddable @Struct`)

The test uses a fresh `Cart` entity to keep the v1 test self-contained (no dependency on MQLv2 test classes).

- [ ] **Step 1: Write the failing integration test**

```java
package com.mongodb.hibernate.query.select;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@DomainModel(annotatedClasses = ElemMatchCart.class)
@SessionFactory(exportSchema = false)
@ExtendWith(MongoExtension.class)
class ElemMatchIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(s -> {
            s.persist(new ElemMatchCart(1, 0,
                    new ElemMatchLineItem[] {
                            new ElemMatchLineItem("WIDGET-1", 3),
                            new ElemMatchLineItem("GIZMO-1", 1)
                    }));
            s.persist(new ElemMatchCart(2, 0,
                    new ElemMatchLineItem[] {
                            new ElemMatchLineItem("WIDGET-1", 0),
                            new ElemMatchLineItem("BOLT-2", 10)
                    }));
            s.persist(new ElemMatchCart(3, 0,
                    new ElemMatchLineItem[] {
                            new ElemMatchLineItem("GIZMO-1", 5)
                    }));
        });
    }

    @Test
    void existsSinglePredicate() {
        var results = sessionFactoryScope.fromSession(s -> s.createSelectionQuery(
                "from ElemMatchCart c where exists (from c.lineItems li where li.sku = 'WIDGET-1') order by c.id",
                ElemMatchCart.class).getResultList());
        assertThat(results).extracting(c -> c.id).containsExactly(1, 2);
    }

    @Test
    void existsAndBody() {
        var results = sessionFactoryScope.fromSession(s -> s.createSelectionQuery(
                "from ElemMatchCart c where exists (from c.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0) order by c.id",
                ElemMatchCart.class).getResultList());
        assertThat(results).extracting(c -> c.id).containsExactly(1);
    }

    @Test
    void notExists() {
        var results = sessionFactoryScope.fromSession(s -> s.createSelectionQuery(
                "from ElemMatchCart c where not exists (from c.lineItems li where li.sku = 'WIDGET-1') order by c.id",
                ElemMatchCart.class).getResultList());
        assertThat(results).extracting(c -> c.id).containsExactly(3);
    }
}
```

`ElemMatchCart.java`:

```java
package com.mongodb.hibernate.query.select;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "ElemMatchCart")
@Table(name = "elemmatch_carts")
class ElemMatchCart {
    @Id int id;
    int minQty;
    ElemMatchLineItem[] lineItems;

    ElemMatchCart() {}

    ElemMatchCart(int id, int minQty, ElemMatchLineItem[] lineItems) {
        this.id = id;
        this.minQty = minQty;
        this.lineItems = lineItems;
    }
}
```

`ElemMatchLineItem.java`:

```java
package com.mongodb.hibernate.query.select;

import jakarta.persistence.Embeddable;
import org.hibernate.annotations.Struct;

@Embeddable
@Struct(name = "ElemMatchLineItem")
class ElemMatchLineItem {
    String sku;
    int qty;

    ElemMatchLineItem() {}

    ElemMatchLineItem(String sku, int qty) {
        this.sku = sku;
        this.qty = qty;
    }
}
```

- [ ] **Step 2: Run test to verify it fails first**

Run: `./gradlew integrationTest --tests 'com.mongodb.hibernate.query.select.ElemMatchIntegrationTests'`
Expected: 3 tests FAIL — Task 2 emits `$elemMatch` correctly, but check that MongoDB returns the expected ids. If it returns wrong rows, investigate the body filter shape via translator output and adjust.

(This is the "verify the live database actually returns what we expect" gate. Translator unit tests assert the emitted MQL string; this asserts MongoDB executes it correctly.)

- [ ] **Step 3: Iterate on any failures**

If a test fails on actual result rows (not on a Mongo error):
- Dump the generated pipeline from `MongoStatement` debug logs
- Hand-execute the pipeline in `mongosh` against the seeded collection
- If the pipeline is wrong, fix the translator (back to Task 2 / Task 3)
- If the pipeline is right but a result is wrong, that's a MongoDB-side semantic issue — investigate `$elemMatch` semantics specifically

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchIntegrationTests.java \
        src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchCart.java \
        src/integrationTest/java/com/mongodb/hibernate/query/select/ElemMatchLineItem.java
git commit -m "v1: integration test for EXISTS-over-collection-path \$elemMatch"
```

---

## Task 5: README + showcase blurb

**Files:**
- Modify: `README.md` — short note in the existing query-support section that v1 now supports the EXISTS-over-collection-path form, with the example HQL and expected pipeline.

- [ ] **Step 1: Add the README section**

In `README.md`, find the v1 query section. Add a subsection:

```markdown
### EXISTS over embedded arrays (`$elemMatch`)

v1 supports a single HQL shape for predicates against `@Struct`-annotated embeddable arrays:

```hql
from Cart c where exists (from c.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0)
```

Emits:

```js
[{ $match: { lineItems: { $elemMatch: { $and: [{ sku: { $eq: "WIDGET-1" } }, { qty: { $gt: 0 } }] } } } }, ...]
```

The body predicate can use AND / OR / NOT. Other forms (JOIN, IN-subquery, correlated parent references, nested EXISTS) are not yet supported in v1; for those, use the MQLv2 backend.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: README blurb for v1 \$elemMatch support"
```

---

## Out of scope (deliberately)

- Correlated body references to outer fields (`where li.qty > c.minQty`) — needs `$expr` / `$let` plumbing, separate plan
- JOIN form (`from Cart c join c.lineItems li where li.sku = ...`) — multiplies rows; doesn't map to `$elemMatch`
- IN-subquery (`where 'WIDGET-1' in (select li.sku from c.lineItems li)`) — different SQL AST shape
- Nested EXISTS / array-in-array — blocked at the Hibernate SQM layer (see HHH-20438 and friends)
- Scalar count subquery (`select count(*) from c.lineItems li`)
- Basic-array predicates (`MEMBER OF`, `IN ELEMENTS`) — separate, smaller feature
