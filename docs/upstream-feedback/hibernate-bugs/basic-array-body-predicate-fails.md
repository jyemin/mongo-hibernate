# Basic-array body predicates fail with `AssertionError` (with a second cast bug hiding behind it)

**Affected version(s):** Hibernate ORM 7.3.4.Final (likely earlier 7.x too)
**Severity / type:** Bug

## Summary

HQL that references a basic-typed array element in a body predicate fails during SQM-to-SQL conversion with a bare `java.lang.AssertionError`. Affected forms all desugar to a `SqmFunctionJoin` over `lateral unnest(...)` of a basic plural attribute:

- Sugar JOIN with body predicate: `from O o join o.scalarArray a where a > ?`
- Explicit `lateral unnest(...)` JOIN: `from O o join lateral unnest(o.scalarArray) a where a > ?`
- EXISTS over implicit collection path: `from O o where exists (select 1 from o.scalarArray a where a > ?)`
- IN-subquery over implicit collection path: `from O o where x in (select t from o.array t)` — the IN-subquery case affects **struct arrays too**, because type inference at `BaseSqmToSqlAstConverter.visitInSubQueryPredicate` calls into the failing helper

The visible failure is a single `AssertionError` from `SqmMappingModelHelper.resolveSqmPath`. **Investigation revealed a second bug stacked behind the first**: after the assertion is relaxed, the next failure is a `ClassCastException` in `BaseSqmToSqlAstConverter.visitHqlNumericLiteral`. Both must be addressed for any of the affected HQL forms to work end-to-end.

## Failure layer 1: `SqmMappingModelHelper.resolveSqmPath`

Stack trace:

```
java.lang.AssertionError
    at org.hibernate.query.sqm.internal.SqmMappingModelHelper.resolveSqmPath(SqmMappingModelHelper.java:217)
    at org.hibernate.query.sqm.internal.SqmMappingModelHelper.resolveMappingModelExpressible(SqmMappingModelHelper.java:159)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.determineValueMapping(BaseSqmToSqlAstConverter.java:6001)
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.lambda$visitComparisonPredicate$1(BaseSqmToSqlAstConverter.java:7891)
    ...
```

The code at line 217 assumes that whenever `sqmPath.getLhs() == null` and the path source isn't an `EntityDomainType`, the path source must be a `SqmCteTable`:

```java
if ( sqmPath.getLhs() == null ) {
    final SqmPathSource<?> referencedPathSource = sqmPath.getReferencedPathSource();
    if ( referencedPathSource instanceof EntityDomainType<?> entityDomainType ) {
        return domainModel.findEntityDescriptor( entityDomainType.getHibernateEntityName() );
    }
    assert referencedPathSource instanceof SqmCteTable<?>;
    return null;
}
```

`SqmFunctionJoin.getLhs()` is overridden to return null (function joins have no semantic LHS), and the function's path source is the `AnonymousTupleType` produced by the set-returning function — neither an entity nor a CTE table. The assertion fires.

Adding a `SqmFunctionJoin` branch that returns null appears sufficient — the downstream code recovers via the path's node type. Verified locally by attempting the fix and rerunning the reproducer.

## Failure layer 2: `BaseSqmToSqlAstConverter.visitHqlNumericLiteral`

Once layer 1 is addressed, the same HQL forms next fail with:

```
java.lang.ClassCastException: TupleMappingModelExpressible cannot be cast to BasicValuedMapping
    at org.hibernate.query.sqm.sql.BaseSqmToSqlAstConverter.visitHqlNumericLiteral(BaseSqmToSqlAstConverter.java:6251)
```

The cast site:

```java
@Override
public <N extends Number> Expression visitHqlNumericLiteral(SqmHqlNumericLiteral<N> numericLiteral) {
    final var inferredExpressible = (BasicValuedMapping) getInferredValueMapping();
    ...
}
```

For type inference of a numeric literal compared against a function-join alias (e.g., the `5` in `t > 5`), `getInferredValueMapping()` returns a `TupleMappingModelExpressible` (the unnest result is modeled as a tuple of value + ordinality). The bare cast fails.

Note that `visitLiteral` — the sibling method for non-numeric literals — already handles this case gracefully via an `instanceof BasicValuedMapping` check before casting, and falls through to a `literalExpressible(literal, inferableExpressible)` fallback. **Only the numeric path is brittle.** Mirroring the existing pattern (instanceof check, fallback to the literal's own type) is sufficient.

This same bare-cast pattern appears at several other sites in `BaseSqmToSqlAstConverter` (the surrounding code does many `(BasicValuedMapping) getInferredValueMapping()` / `(BasicValuedMapping) inferredValueMapping` casts). Worth a broader audit: any visit method that infers against a function-join alias and bare-casts to `BasicValuedMapping` has the same latent issue.

## Reproducer

A runnable JUnit reproducer exists at [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) — test class `BasicArrayBodyPredicateTest`, four `@Test` methods (one per affected HQL form). Uses PostgreSQL + Hibernate 7.3.4.Final + JUnit 5. Each test asserts the query executes without exception; against released Hibernate all four fail with `AssertionError`. Investigation against a locally-modified Hibernate confirmed both layers and that all four tests pass once both are addressed.

```java
import org.hibernate.cfg.Configuration;
import jakarta.persistence.*;

public class BasicArrayBodyPredicate {

    @Entity(name = "Item")
    public static class Item {
        @Id public int id;
        public int[] tags;
    }

    static void allFourForms(Session s) {
        // 1. Sugar JOIN with body predicate (natural HQL):
        s.createSelectionQuery(
                "from Item i join i.tags t where t > 5", Item.class).getResultList();

        // 2. Implicit collection path inside EXISTS:
        s.createSelectionQuery(
                "from Item i where exists (select 1 from i.tags t where t > 5)", Item.class).getResultList();

        // 3. IN-subquery over implicit collection path:
        s.createSelectionQuery(
                "from Item i where 5 in (select t from i.tags t)", Item.class).getResultList();

        // 4. Explicit `lateral unnest(...)` JOIN form:
        s.createSelectionQuery(
                "from Item i join lateral unnest(i.tags) t where t > 5", Item.class).getResultList();
    }
}
```

## Expected behavior

Hibernate should resolve SQM paths through `SqmFunctionJoin` aliases and produce a valid SQL AST. For PostgreSQL the emission should be approximately:

```sql
SELECT * FROM item i, LATERAL unnest(i.tags) AS t(value) WHERE t.value > 5
```

That SQL is itself valid SQL:1999 — the issue is purely on the HQL/SQM side.

## Notes

- Struct-array body predicates at single nesting depth (e.g., `where exists (select 1 from o.lineItems a where a.sku = ?)`) work fine — those paths use direct column references that bypass `resolveSqmPath`. This bug specifically affects basic-array variants of those same shapes, plus IN-subqueries over any array type.
- Related but distinct bugs:
  - `SqmFunctionJoin.getParent()` always throws — see `sqm-function-join-get-parent-latent-npe.md`.
  - Nested EXISTS over struct array — see `nested-unnest-correlation-blocked.md`.
  - HQL grammar rejects `lateral unnest(...)` inside subquery FROM clauses — see `hql-grammar-lateral-unnest-in-subqueries.md`.
