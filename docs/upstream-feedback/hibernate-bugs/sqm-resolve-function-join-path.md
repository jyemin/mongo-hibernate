# `SqmMappingModelHelper.resolveSqmPath` throws `AssertionError` on paths through a `FunctionJoin`/`SqmFunctionJoin`

**Affected version(s):** Hibernate ORM 7.3.4.Final (likely earlier 7.x too)
**Severity / type:** Bug

## Summary

When HQL references a column on an alias bound to a `lateral unnest(...)` set-returning function (i.e., a basic plural attribute joined as syntax-sugar for `lateral unnest`, OR an explicit `lateral unnest(...)` join target), Hibernate's SQM-to-SQL AST conversion throws a bare `java.lang.AssertionError` (no message) from `SqmMappingModelHelper.resolveSqmPath`. The query parses and reaches SQM construction successfully; the failure is during type inference / path resolution as Hibernate walks the SQM tree.

This blocks all of:

- Body predicates on a scalar-array unnest alias: `from O o join lateral unnest(o.scalarArray) a where a > ?`
- Body predicates on a scalar-array sugar-form join: `from O o join o.scalarArray a where a > ?`
- Scalar EXISTS over an array (via implicit collection-path) with a body predicate referencing the alias: `where exists (select 1 from o.scalarArray a where a > ?)`
- IN-subqueries over an array (struct OR scalar): `where x in (select a.col from o.array a)`. Type inference at `BaseSqmToSqlAstConverter.visitInSubQueryPredicate` calls `resolveSqmPath` on the subquery's projected expression and fails.

Note the IN-subquery case affects **struct arrays too**, not just scalar — even though struct EXISTS body predicates work fine. The difference is that EXISTS bodies use direct column references (`a.sku == ?`) that bypass the failing helper, while IN-subquery's type inference forces a call into the helper.

## Minimal reproducer

A runnable JUnit reproducer exists at [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) — test class `SqmResolveFunctionJoinPathTest`, four `@Test` methods (one per affected HQL form). The repo uses H2 + Hibernate 7.3.4.Final + JUnit 5. Run `gradle test`; all four tests pass, which means the bug still fires.

Reproducer source (also embedded inline below for convenience):

```java
import org.hibernate.cfg.Configuration;
import jakarta.persistence.*;

public class UnnestSqmBug {

    @Entity(name = "Item")
    public static class Item {
        @Id public int id;
        public int[] tags;
    }

    public static void main(String[] args) {
        var sf = new Configuration()
                .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:test")
                .setProperty("hibernate.hbm2ddl.auto", "create")
                .addAnnotatedClass(Item.class)
                .buildSessionFactory();
        try (var s = sf.openSession()) {
            // This HQL parses successfully but fails during SQM-to-SQL conversion.
            s.createSelectionQuery(
                    "from Item i join lateral unnest(i.tags) t where t > 5",
                    Item.class).getResultList();
        }
    }
}
```

Run it and you get `java.lang.AssertionError` (no message) thrown from `SqmMappingModelHelper.resolveSqmPath`. The same shape reproduces for the `IN`-subquery case:

```java
s.createSelectionQuery(
        "from Item i where 5 in (select t from i.tags t)",
        Item.class).getResultList();
```

## Expected behavior

Hibernate should resolve the SQM path through the function-join alias and produce a valid SQL AST. The SQL emission for H2 would be something like `SELECT * FROM Item i JOIN LATERAL UNNEST(i.tags) AS t(value) ON 1=1 WHERE t.value > 5`; for any dialect that registers `unnest` as a set-returning function, an analogous SQL form should be reachable.

## Actual behavior

Stack trace:

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

(Stack abbreviated. Full trace shows the failure chains from `visitComparisonPredicate` resolving `t > 5`, where `t` is a basic-array unnest alias.)

## Notes

- The issue is purely on the HQL/SQM side. The SQL that *would* be produced for a dialect that supports `unnest` is itself valid SQL:1999.
- For struct arrays in `EXISTS` and `JOIN` body predicates (e.g., `where exists (select 1 from o.lineItems a where a.sku = ?)`), Hibernate does NOT hit this AssertionError — those paths resolve correctly. The difference is the basic-array case has no `MappingModelExpressible` representation for the unnest result's single anonymous column.
- The presumed fix: extend `SqmMappingModelHelper.resolveSqmPath` (line 217 area) to handle `SqmFunctionJoin` / function-table paths, returning the basic-element mapping for the unnest result.
- Related but distinct: `ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin` (separate bug report, `nested-exists-sqm-function-join-cast.md`).
