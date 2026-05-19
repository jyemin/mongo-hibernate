# Non-`count` aggregates over a sequence-expression return singleton-array instead of bare scalar

**Affected version(s):** MQLv2 (as of mongo-hibernate `mqlv2` branch, May 2026 — server version per `gradle/libs.versions.toml` mongo-java-driver-sync `5.8.0-SNAPSHOT`)
**Severity / type:** Awkwardness (workaround exists; ergonomics issue)
**Discovered by:** MongoDB Hibernate extension `mqlv2` branch, Phase 4 elemMatch design execution

## Summary

MQLv2 has `count(<sequence-expr>)` as an expression-position aggregate that returns a bare scalar (e.g., `5`). The other aggregates (`max`, `min`, `sum`, `avg`) are accessible in expression position only via the `(from <sequence> | agg <fn>($->field))` subpipeline form, which returns a **singleton array** (`[25]`), not a bare scalar.

This is awkward when composing scalar values. For example:

```
from $orders | format {_id: _id, maxPrice: (from lineItems | agg max($->price))}
=> {_id: 1, maxPrice: [25]}, {_id: 2, maxPrice: [100]}, ...
```

A user expecting `maxPrice: 25` (matching SQL's `SELECT MAX(price)` semantics) instead sees `maxPrice: [25]`. The array wrap leaks through to clients, who must unwrap it.

## Proposed enhancement

Either:

**Option A — expression-position scalar forms.** Add `max(<sequence-expr>)`, `min(<sequence-expr>)`, `sum(<sequence-expr>)`, `avg(<sequence-expr>)` as expression-position aggregates returning bare scalars, symmetric with `count(<sequence-expr>)`:

```
from $orders | format {_id: _id, maxPrice: max(lineItems | format $.price)}
=> {_id: 1, maxPrice: 25}, ...
```

(or whatever input shape `max(...)` accepts — the analogue of how `count(<sequence>)` works today)

**Option B — unwrap operator.** Add an explicit "take the singleton" operator for cases where the user knows the sequence has exactly one element:

```
from $orders | format {_id: _id, maxPrice: single(from lineItems | agg max($->price))}
=> {_id: 1, maxPrice: 25}, ...
```

This is more general but verbose at the call site.

## Use case

The Hibernate extension's Phase 4 design (scalar-subquery over array, e.g., `SELECT o.id, (SELECT max(li.price) FROM o.lineItems li) FROM Order o`) translates the HQL into a single-row scalar value in the outer pipeline's `format` stage. With `count()` only this works fine (`count(<pipeline>)` returns a scalar). For `max`/`min`/`sum`/`avg`, the current API forces an array-wrap that the translator would have to unwrap separately — or expose the array as the result type, surprising users.

This is documented in the elemMatch design as a known limitation; Phase 4 currently throws `FeatureNotSupportedException` for non-`count` scalar aggregates over arrays. If either Option A or B lands in MQLv2, the Hibernate translator can lift that restriction.

## Notes

- The current workaround (`(from <array> | agg <fn>($->field))`) does work — it just returns the wrong shape. Server-side correctness isn't the issue.
- Option A is symmetric with how `count(<pipeline>)` is already specified. The spec section on aggregate functions (mqlv2 spec.md around line 1620) covers `count`, `min`, `max`, `avg` — extending each to accept a sequence-expression argument in expression position would be a natural extension.
