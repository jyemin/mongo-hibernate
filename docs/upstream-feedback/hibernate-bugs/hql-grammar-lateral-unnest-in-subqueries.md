# HQL grammar rejects `LATERAL unnest(...)` inside EXISTS and scalar SELECT subqueries

**Affected version(s):** Hibernate ORM 7.3.4.Final
**Severity / type:** Enhancement (or possibly bug — depending on whether the restriction is intentional)
**Discovered by:** MongoDB Hibernate extension `mqlv2` branch, Phase 0 elemMatch design execution (mongo-hibernate repo, May 2026)

## Summary

Hibernate's HQL grammar accepts `LATERAL unnest(...)` as a JOIN target in the outer FROM clause (e.g., `from Item i join lateral unnest(i.tags) t`). It rejects the same construct inside subqueries:

```hql
-- both throw org.hibernate.query.SyntaxException

from Item i where exists (
  select 1 from lateral unnest(i.tags) t where t.x = ?)

select i.id, (select count(*) from lateral unnest(i.tags) t where t.x = ?)
  from Item i
```

Both forms are legitimate SQL:1999 syntax — `LATERAL` is permitted in any `<table reference>` position, including subquery FROM clauses. Hibernate restricting it to outer FROM only is either a grammar oversight or an intentional limitation that the design rationale doesn't surface.

## Minimal reproducer

```java
import org.hibernate.cfg.Configuration;
import jakarta.persistence.*;

public class LateralUnnestSubqueryGrammarBug {

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
            // EXISTS subquery with LATERAL unnest — fails to parse:
            s.createSelectionQuery(
                    "from Item i where exists ("
                            + "  select 1 from lateral unnest(i.tags) t where t > 5)",
                    Item.class).getResultList();
        }
    }
}
```

## Expected behavior

Both subquery forms should parse. The semantics map cleanly: the inner `LATERAL unnest(...)` references the outer's collection-valued path (`i.tags`) and produces a row stream that the inner subquery filters / counts.

## Actual behavior

```
org.hibernate.query.SyntaxException: At 1:43 and token '(', mismatched input '(',
  expecting one of the following tokens:
  <EOF>, ',', CROSS, EXCEPT, FETCH, FULL, GROUP, HAVING, INNER, INTERSECT,
  JOIN, LEFT, LIMIT, OFFSET, ORDER, OUTER, RIGHT, UNION, WHERE
```

The grammar at position 1:43 expects an alias or junction after `unnest`, not a `(` — i.e., it's treating `unnest` as an identifier (potential table name) in the subquery context, not as the set-returning-function keyword.

## Notes

- Workaround for the EXISTS case: use the implicit collection-path form `from i.tags t` instead of `from lateral unnest(i.tags) t`. The implicit form parses fine and produces an equivalent SQL AST. However, the implicit form has its own limitations (see `sqm-resolve-function-join-path.md`).
- No workaround for the scalar SELECT subquery case using `LATERAL unnest` — must also use the implicit collection-path form.
- If the restriction is intentional (e.g., to avoid ambiguity with some other grammar rule), the error message could be more helpful: pointing users toward the implicit collection-path form.
- Fix: extend the HQL grammar's subquery-FROM-clause rule to accept `lateral` function-table-references, matching the rule used in the outer FROM clause.
