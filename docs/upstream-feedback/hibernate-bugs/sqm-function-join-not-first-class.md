# `SqmFunctionJoin` is not first-class in SQM-to-SQL visitors

**Affected version(s):** Hibernate ORM 7.3.4.Final (likely earlier 7.x too)
**Severity / type:** Bug

## Summary

Several SQM-to-SQL conversion code paths don't have branches for `SqmFunctionJoin` (i.e., the SQM node produced when a HQL alias is bound to a set-returning function like `lateral unnest(...)`, including the basic-plural-attribute JOIN sugar that desugars to one). When user HQL exercises any of these paths, the converter fails with one of two different exceptions — different symptoms, but the same underlying gap.

We observe at least two failing visitor sites:

### Failure A — `SqmMappingModelHelper.resolveSqmPath`

Bare `java.lang.AssertionError` (no message) when resolving a SQM path through a `SqmFunctionJoin` alias. Stack:

```
java.lang.AssertionError
    at org.hibernate.query.sqm.internal.SqmMappingModelHelper.resolveSqmPath(SqmMappingModelHelper.java:217)
    at org.hibernate.query.sqm.internal.SqmMappingModelHelper.resolveMappingModelExpressible(SqmMappingModelHelper.java:159)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.determineValueMapping(BaseSqmToSqlAstConverter.java:6001)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.lambda$visitComparisonPredicate$1(BaseSqmToSqlAstConverter.java:7891)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.resolveInferredType(BaseSqmToSqlAstConverter.java:5615)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.getInferredValueMapping(BaseSqmToSqlAstConverter.java:6113)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.visitHqlNumericLiteral(BaseSqmToSqlAstConverter.java:5871)
    at org.hibernate.query.sqm.tree.expression.SqmHqlNumericLiteral.accept(SqmHqlNumericLiteral.java:78)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.visitComparisonPredicate(BaseSqmToSqlAstConverter.java:7895)
    ...
```

Affected HQL forms:

- Body predicate on a sugar-form JOIN: `from O o join o.scalarArray a where a > ?`
- Body predicate on an explicit lateral-unnest JOIN: `from O o join lateral unnest(o.scalarArray) a where a > ?`
- EXISTS-subquery body predicate via implicit collection path: `from O o where exists (select 1 from o.scalarArray a where a > ?)`
- IN-subquery whose projected expression is on a function-join alias: `from O o where x in (select t from o.array t)`. Type inference at `BaseSqmToSqlAstConverter.visitInSubQueryPredicate` calls `resolveSqmPath` on the projected expression and fails. (This case affects **struct arrays too**, not just scalar — even though struct EXISTS body predicates work fine, because EXISTS bodies use direct column references like `a.sku = ?` that bypass the helper.)

### Failure B — correlation cast to `SqmSingularValuedJoin`

`java.lang.ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin` when an inner EXISTS subquery's FROM references a collection-valued path on the outer EXISTS's alias (which is itself a `SqmFunctionJoin`). The cast site assumes the correlated parent join is a `SqmSingularValuedJoin` (the entity-join case) and doesn't handle `SqmFunctionJoin`.

Affected HQL form:

```hql
from O o where exists (
  select 1 from o.outerArray a where exists (
    select 1 from a.innerArray b where b.x = ?))
```

i.e., nested EXISTS over array-of-structs-each-containing-an-array.

## Why these are one issue

Both failures stem from the same omission: when Hibernate added `SqmFunctionJoin` / set-returning-function support, not every SQM visitor was extended to handle it. `SqmMappingModelHelper.resolveSqmPath` and the correlation cast site are two such gaps. There may be others — a systematic audit of visitor methods over the SQM join hierarchy would likely surface more.

Fixing them probably requires distinct code edits in distinct methods, but they're one design fix: make `SqmFunctionJoin` a first-class citizen in SQM-to-SQL conversion.

## Minimal reproducer

A runnable JUnit reproducer exists at [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) — test class `SqmFunctionJoinNotFirstClassTest`. The class has two groups of methods:

- **Group A** (`resolveSqmPath` failure) — 4 tests against H2, one per affected HQL form. Each asserts the query executes without exception; all fail today with `AssertionError`.
- **Group B** (correlation cast failure) — 2 tests against PostgreSQL dialect with a stub `ConnectionProvider` (no live server; the bug fires before JDBC). Each asserts the resulting exception is the stub's `SQLException` (i.e., SQM-to-SQL conversion completed and Hibernate reached the JDBC layer). Both fail today because `ClassCastException` fires first.

Drop a Hibernate SNAPSHOT into `build.gradle.kts` to verify a fix.

Reproducer source (also embedded inline below for convenience):

```java
import org.hibernate.cfg.Configuration;
import jakarta.persistence.*;
import org.hibernate.annotations.Struct;

public class SqmFunctionJoinNotFirstClass {

    // Group A — basic-array entity for the resolveSqmPath failure
    @Entity(name = "Item")
    public static class Item {
        @Id public int id;
        public int[] tags;
    }

    // Group B — nested struct array for the correlation cast failure
    @Embeddable @Struct(name = "Tax")
    public static class Tax { public String code; public double rate; }

    @Embeddable @Struct(name = "LineItem")
    public static class LineItem { public String sku; public Tax[] taxes; }

    @Entity(name = "OrderEntity")
    public static class OrderEntity {
        @Id public int id;
        public LineItem[] lineItems;
    }

    // Group A — all four forms throw AssertionError from
    // SqmMappingModelHelper.resolveSqmPath:
    static void groupA(Session s) {
        // Form 1 — sugar JOIN with body predicate (natural HQL):
        s.createSelectionQuery("from Item i join i.tags t where t > 5", Item.class).getResultList();
        // Form 2 — implicit collection path inside EXISTS:
        s.createSelectionQuery(
                "from Item i where exists (select 1 from i.tags t where t > 5)", Item.class).getResultList();
        // Form 3 — IN-subquery over implicit collection path:
        s.createSelectionQuery(
                "from Item i where 5 in (select t from i.tags t)", Item.class).getResultList();
        // Form 4 — explicit `lateral unnest(...)` JOIN form:
        s.createSelectionQuery(
                "from Item i join lateral unnest(i.tags) t where t > 5", Item.class).getResultList();
    }

    // Group B — both forms throw ClassCastException from correlation cast:
    static void groupB(Session s) {
        // Form 1 — natural implicit collection paths at every level:
        s.createSelectionQuery(
                "from OrderEntity o where exists ("
                        + "  select 1 from o.lineItems a where exists ("
                        + "    select 1 from a.taxes b where b.code = 'VAT'))",
                OrderEntity.class).getResultList();
        // Form 2 — outer FROM uses explicit `lateral unnest`, inner stays implicit
        // (the fully-explicit form doesn't parse — see hql-grammar-lateral-unnest-in-subqueries.md):
        s.createSelectionQuery(
                "from OrderEntity o join lateral unnest(o.lineItems) a "
                        + "where exists (select 1 from a.taxes b where b.code = 'VAT')",
                OrderEntity.class).getResultList();
    }
}
```

## Expected behavior

Hibernate should resolve SQM paths through `SqmFunctionJoin` aliases and produce a valid SQL AST. For Group A, the SQL emission for H2 would be something like `SELECT * FROM Item i JOIN LATERAL UNNEST(i.tags) AS t(value) WHERE t.value > 5`. For Group B, the emission for PostgreSQL would be approximately:

```sql
SELECT * FROM order_entity o WHERE EXISTS (
  SELECT 1 FROM LATERAL unnest(o.line_items) AS a WHERE EXISTS (
    SELECT 1 FROM LATERAL unnest(a.taxes) AS b WHERE b.code = 'VAT'))
```

Both are valid SQL:1999 — the issue is purely on the HQL/SQM side.

## Notes

- For struct arrays in single-level `EXISTS` and `JOIN` body predicates (e.g., `where exists (select 1 from o.lineItems a where a.sku = ?)`), Hibernate does NOT hit Failure A — those paths use direct column references that bypass `resolveSqmPath`. So struct-array EXISTS works at single nesting depth; what doesn't work is (a) basic-array variants of the same shape (Failure A) and (b) struct-array variants at *nested* depth (Failure B).
- Presumed fix for Failure A: extend `SqmMappingModelHelper.resolveSqmPath` (around line 217) to handle `SqmFunctionJoin` / function-table paths, returning the basic-element mapping for the unnest result.
- Presumed fix for Failure B: in the correlation-resolution cast site, add a `SqmFunctionJoin` branch that handles correlation through a function-join alias.
- Related but distinct: HQL grammar rejects `lateral unnest(...)` inside subquery FROM clauses (separate report: `hql-grammar-lateral-unnest-in-subqueries.md`). That one is a parser-layer issue.
