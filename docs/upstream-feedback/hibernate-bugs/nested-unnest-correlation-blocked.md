# Nested unnest via correlated subquery is blocked at multiple layers

**Affected version(s):** Hibernate ORM 7.3.4.Final (likely earlier 7.x too)
**Severity / type:** Bug + missing feature (see below)

## Summary

HQL that nests EXISTS over an array-of-structs-each-containing-an-array fails during HQL→SQM conversion. The natural form:

```hql
from O o where exists (
  select 1 from o.outerArray a where exists (
    select 1 from a.innerArray b where b.x = ?))
```

throws `java.lang.ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin` from `SqmSubQuery.correlate(Join)` at the outermost layer. Investigation indicates this cast is one of three stacked issues; the cast itself looks like a bug with a clean shape, but the deepest layer is a missing feature that probably wants design input.

## Layer 1 (bug): `SqmSubQuery.correlate(Join)` lacks a `SqmFunctionJoin` branch

The `correlate(Join)` method handles plural joins (collection / list / set / map) and then bare-casts to `SqmSingularValuedJoin`:

```java
@Override
public <X, Y> SqmCorrelatedJoin<X, Y> correlate(Join<X, Y> join) {
    if ( join instanceof PluralJoin<?, ?, ?> pluralJoin ) {
        return switch ( pluralJoin.getModel().getCollectionType() ) { ... };
    }
    final SqmCorrelatedSingularValuedJoin<X, Y> correlated =
            ( (SqmSingularValuedJoin<X, Y>) join ).createCorrelation();
    ...
}
```

`SqmFunctionJoin` is neither a `PluralJoin` nor a `SqmSingularValuedJoin`, so the cast throws.

## Layer 2 (bug): `SqmFunctionJoin.createCorrelation()` is unimplemented

Even after Layer 1 is addressed by dispatching to `((SqmFunctionJoin)…).createCorrelation()`, the method itself is a stub:

```java
@Override
public SqmCorrelation<Object, E> createCorrelation() {
    throw new UnsupportedOperationException();
}
```

A `SqmCorrelatedFunctionJoin` class doesn't exist either. The correlation machinery for function joins simply wasn't built out.

Implementing it appears tractable — mirror the existing `SqmCorrelatedDerivedJoin` class which handles the analogous case for derived (subquery-table) joins. (Verified locally by writing the class and wiring it up — gets past Layers 1 and 2.)

## Layer 3 (missing feature): `AnonymousTupleType` doesn't yield joinable path sources for `BasicPluralType` components

With Layers 1 and 2 addressed, the next failure is:

```
org.hibernate.query.SemanticException: Joining on basic value elements is not supported
    at QualifiedJoinPathConsumer.createJoin(QualifiedJoinPathConsumer.java:225)
```

When parsing `from a.taxes b` in the inner EXISTS subquery, Hibernate asks the correlated outer alias `a` (an unnest-of-`LineItem[]` whose result type is an `AnonymousTupleType`) for the sub-path source `taxes`. `AnonymousTupleType.subpathSource` dispatches on the component's expressible:

```java
if ( expressible instanceof SqmPluralPersistentAttribute<?, ?, T> pluralAttribute ) {
    return new AnonymousTupleSqmAssociationPathSourceNew<>(...);
}
else if ( sqmType instanceof BasicDomainType<?> ) {
    return new AnonymousTupleSimpleSqmPathSource<>(...);   // <-- BasicPluralType<Tax[], Tax> falls in here
}
...
```

The `Tax[] taxes` field's expressible is a `BasicPluralType` (an array of basic/struct elements). `BasicPluralType` is a `BasicDomainType`, so the second branch fires and returns a non-joinable simple path source. Hence the "Joining on basic value elements is not supported" error.

This is not a brittleness-style bug. Making the basic-plural-component case joinable means *implementing recursive unnest*: when the user writes `from a.taxes b` and `a` is itself an unnest result, the path source's `createSqmJoin` would need to synthesize a fresh `SqmFunctionJoin` around `unnest(a.taxes)`. That's a real feature, with semantic questions (should `from a.taxes b` even be the user-facing HQL for recursive unnest, or should users write `from lateral unnest(a.taxes) b` instead — which is currently blocked by a separate grammar issue, see `hql-grammar-lateral-unnest-in-subqueries.md`).

## Reproducer

A runnable JUnit reproducer exists at [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) — test class `NestedUnnestCorrelationTest`, two `@Test` methods covering the natural implicit-collection-path form and the outer-`lateral-unnest` variant. Uses PostgreSQL + Hibernate 7.3.4.Final + JUnit 5. Both fail today with `ClassCastException`. Investigation against a locally-modified Hibernate confirmed Layers 1 and 2 can be addressed, after which Layer 3 surfaces.

```java
import org.hibernate.cfg.Configuration;
import jakarta.persistence.*;
import org.hibernate.annotations.Struct;

public class NestedUnnestCorrelation {

    @Embeddable @Struct(name = "Tax")
    public static class Tax { public String code; public double rate; }

    @Embeddable @Struct(name = "LineItem")
    public static class LineItem { public String sku; public Tax[] taxes; }

    @Entity(name = "OrderEntity")
    public static class OrderEntity {
        @Id public int id;
        public LineItem[] lineItems;
    }

    static void bothForms(Session s) {
        // 1. Natural implicit collection paths at every level:
        s.createSelectionQuery(
                "from OrderEntity o where exists ("
                        + "  select 1 from o.lineItems a where exists ("
                        + "    select 1 from a.taxes b where b.code = 'VAT'))",
                OrderEntity.class).getResultList();

        // 2. Outer FROM uses explicit `lateral unnest`, inner stays implicit
        // (the fully-explicit form doesn't parse — see
        // hql-grammar-lateral-unnest-in-subqueries.md):
        s.createSelectionQuery(
                "from OrderEntity o join lateral unnest(o.lineItems) a "
                        + "where exists (select 1 from a.taxes b where b.code = 'VAT')",
                OrderEntity.class).getResultList();
    }
}
```

## Expected behavior

The HQL should compile to nested correlated `LATERAL unnest` joins. For PostgreSQL:

```sql
SELECT * FROM order_entity o WHERE EXISTS (
  SELECT 1 FROM LATERAL unnest(o.line_items) AS a WHERE EXISTS (
    SELECT 1 FROM LATERAL unnest(a.taxes) AS b WHERE b.code = 'VAT'))
```

That SQL is valid SQL:1999.

## Notes

- Layers 1 and 2 are clean bug fixes. Layer 3 wants design input — it's not obvious what the right HQL surface is for recursive unnest, nor exactly how the new path source should be parameterized.
- Related but distinct:
  - The bare-cast pattern that powers Layer 1 also appears in `basic-array-body-predicate-fails.md` (different code site, same shape).
  - `SqmFunctionJoin.getParent()` is independently broken — see `sqm-function-join-get-parent-latent-npe.md`. That bug surfaces *while* attempting to fix Layer 2.
  - The grammar restriction in `hql-grammar-lateral-unnest-in-subqueries.md` constrains the user-side workaround space for Layer 3.
