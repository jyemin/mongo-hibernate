# `SqmFunctionJoin.getParent()` always throws (latent NPE)

**Affected version(s):** Hibernate ORM 7.3.4.Final (likely earlier 7.x too)
**Severity / type:** Bug (latent — no production code path currently triggers it)

## Summary

`SqmFunctionJoin.getParent()` throws `AssertionError: Misuse of castNonNull: called with a null argument` for every call, regardless of input. The bug is latent because no production code path currently invokes `getParent()` on a `SqmFunctionJoin` instance — it was discovered while investigating an unrelated correlation issue that introduced a new caller.

## Mechanism

`SqmFunctionJoin` intentionally overrides `getLhs()` to return null — function joins have no semantic left-hand side:

```java
@Override
public @Nullable SqmFrom<?, Object> getLhs() {
    // A derived-join has no LHS
    return null;
}
```

Its `getParent()` override asserts that `super.getParent()` is non-null:

```java
@Override
public @NonNull SqmFrom<?, Object> getParent() {
    return castNonNull( super.getParent() );
}
```

`AbstractSqmJoin.getParent()` is implemented as `return getLhs();` — and because `getLhs()` is virtually dispatched, it resolves back to `SqmFunctionJoin`'s override, which returns null. So `super.getParent()` returns null, `castNonNull` throws.

The parent FROM passed into the constructor IS stored — `AbstractSqmPath.lhs` holds it — but the `getLhs()` override shadows that field for every reader.

## Fix shape

Track the parent FROM in a dedicated field on `SqmFunctionJoin` (the value is already passed in via the constructor) and have `getParent()` return that directly, bypassing the `getLhs()` override:

```java
private final SqmFrom<?, Object> parentFrom;

public SqmFunctionJoin(... SqmFrom<?, Object> sqmFrom) {
    super(...);
    this.parentFrom = sqmFrom;
    ...
}

@Override
public @NonNull SqmFrom<?, Object> getParent() {
    return castNonNull( parentFrom );
}
```

Verified locally — addresses the NPE for the new caller that surfaced it.

## Reproducer

No JUnit reproducer in the [`hibernate7-unnest-bug-reproducers`](https://github.com/jyemin/hibernate7-unnest-bug-reproducers) repo for this one — there's no HQL today that reaches `SqmFunctionJoin.getParent()`. The bug is reachable only by calling the method directly:

```java
SqmFunctionJoin<?> join = /* any non-null SqmFunctionJoin */;
join.getParent();   // throws AssertionError unconditionally
```

## Notes

- This is independent of the other `SqmFunctionJoin` reports — those involve missing branches in visitor code. This one is a bug in `SqmFunctionJoin` itself.
- Surfaces if any new code path correlates or otherwise navigates from a `SqmFunctionJoin` to its parent FROM — for example, the work described in `nested-unnest-correlation-blocked.md`.
