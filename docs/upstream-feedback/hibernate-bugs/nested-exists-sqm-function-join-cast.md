# `ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin` for nested EXISTS over `lateral unnest`

**Affected version(s):** Hibernate ORM 7.3.4.Final (likely earlier 7.x too)
**Severity / type:** Bug
**Discovered by:** MongoDB Hibernate extension `mqlv2` branch, Phase 2 elemMatch design execution (mongo-hibernate repo, May 2026)

## Summary

When an HQL `EXISTS` subquery is nested inside another `EXISTS` subquery, and the inner subquery's FROM references a collection-valued path on the OUTER `EXISTS`'s alias (which is itself bound to a `lateral unnest` set-returning function), Hibernate's SQM-to-SQL AST conversion throws:

```
java.lang.ClassCastException: class org.hibernate.query.sqm.tree.from.SqmFunctionJoin
    cannot be cast to class org.hibernate.query.sqm.tree.from.SqmSingularValuedJoin
```

The HQL parses successfully; the failure is during SQM tree construction or conversion when correlating the inner subquery's FROM to the outer's function-join alias.

This blocks **nested EXISTS over array-of-docs-each-containing-an-array** in HQL:

```hql
from O o where exists (
  select 1 from o.outerArray a where exists (
    select 1 from a.innerArray b where b.x = ?))
```

## Minimal reproducer

A runnable JUnit reproducer exists at [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) — test class `NestedExistsSqmFunctionJoinCastTest`. Uses `PostgreSQLDialect` (H2 doesn't support `@Struct`) with `hibernate.boot.allow_jdbc_metadata_access=false` and a stub `ConnectionProvider`, so no live PostgreSQL server is needed — the bug fires at SQM-to-SQL conversion, before any SQL execution.

Reproducer source (also embedded inline below for convenience):

```java
import org.hibernate.cfg.Configuration;
import jakarta.persistence.*;
import org.hibernate.annotations.Struct;

public class NestedExistsCastBug {

    @Embeddable
    @Struct(name = "Tax")
    public static class Tax {
        public String code;
        public double rate;
    }

    @Embeddable
    @Struct(name = "LineItem")
    public static class LineItem {
        public String sku;
        public Tax[] taxes;
    }

    @Entity(name = "Order")
    public static class Order {
        @Id public int id;
        public LineItem[] lineItems;
    }

    public static void main(String[] args) {
        var sf = new Configuration()
                .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:test")
                .setProperty("hibernate.hbm2ddl.auto", "create")
                .addAnnotatedClass(Order.class)
                .buildSessionFactory();
        try (var s = sf.openSession()) {
            s.createSelectionQuery(
                    "from Order o where exists ("
                            + "  select 1 from o.lineItems a where exists ("
                            + "    select 1 from a.taxes b where b.code = 'VAT'))",
                    Order.class).getResultList();
        }
    }
}
```

## Expected behavior

The HQL should compile to SQL that correlates the inner subquery to the outer's unnest alias. For a SQL dialect supporting `unnest`, the emitted SQL would be approximately:

```sql
SELECT * FROM "Order" o WHERE EXISTS (
  SELECT 1 FROM LATERAL unnest(o.lineItems) AS a WHERE EXISTS (
    SELECT 1 FROM LATERAL unnest(a.taxes) AS b WHERE b.code = 'VAT'))
```

## Actual behavior

`java.lang.ClassCastException: SqmFunctionJoin cannot be cast to SqmSingularValuedJoin` during SQM construction / conversion. The cast is in the code path that resolves the inner subquery's FROM correlation to a join in the enclosing scope; it assumes the correlated join is a `SqmSingularValuedJoin` (which would be the case for entity joins) but doesn't handle the `SqmFunctionJoin` case.

## Notes

- The MQLv2 server-side evaluation of the equivalent pipeline (nested `any`: `from $orders | match (lineItems any ($.taxes any ($.code == "VAT")))`) executes correctly. The issue is purely on the HQL/SQM side.
- Likely related to `SqmMappingModelHelper.resolveSqmPath` cannot resolve paths through a `FunctionJoin` (separate bug report, `sqm-resolve-function-join-path.md`), but the symptom is different — that one throws `AssertionError` from path resolution, this one throws `ClassCastException` from correlation resolution. Both block `SqmFunctionJoin` from being a first-class citizen.
- Fix: in the code that performs the cast, add a `SqmFunctionJoin` branch that handles correlation through a function-join alias.
