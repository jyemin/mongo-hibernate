# HQL grammar rejects `LATERAL unnest(...)` inside EXISTS and scalar SELECT subqueries

**Affected version(s):** Hibernate ORM 7.3.4.Final
**Severity / type:** Enhancement (or possibly bug — depending on whether the restriction is intentional)

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

A runnable JUnit reproducer exists at [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) — test class `HqlGrammarLateralUnnestInSubqueriesTest`, three `@Test` methods covering both blocked subquery forms plus the unblocked outer-FROM form for contrast. Uses H2 + Hibernate 7.3.4.Final + JUnit 5.

Reproducer source (also embedded inline below for convenience):

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
            // Form 1 — EXISTS subquery with LATERAL unnest. Fails with SyntaxException
            // at the `(` after `unnest`:
            s.createSelectionQuery(
                    "from Item i where exists ("
                            + "  select 1 from lateral unnest(i.tags) t where t > 5)",
                    Item.class).getResultList();

            // Form 2 — scalar SELECT subquery with LATERAL unnest. Same SyntaxException:
            s.createSelectionQuery(
                    "select i.id, (select count(*) from lateral unnest(i.tags) t where t > 5)"
                            + "  from Item i",
                    Object[].class).getResultList();

            // For comparison, this form (LATERAL unnest in OUTER FROM) parses fine:
            s.createSelectionQuery(
                    "from Item i join lateral unnest(i.tags) t",
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
