# HIBERNATE-225: Zoned temporal types

## Summary

Support `java.time.OffsetDateTime` and `java.time.ZonedDateTime` alongside the already-supported
`java.time.Instant`. All three map to BSON `Date`.

The wall-clock types stay unsupported: `java.util.Date`, `java.sql.Timestamp`, `LocalDateTime`, `LocalDate`,
`LocalTime`, `OffsetTime`, `java.sql.Date`, `java.sql.Time`, and `@Temporal(DATE)` / `@Temporal(TIME)`.
`java.util.Calendar` stays unsupported permanently. HIBERNATE-226 covers the date-only and time-only types.

The design also closes gaps an audit of the current behavior turned up. `LocalDate` boots and then fails at
write, and `Period`, `YearMonth`, and `MonthDay` silently persist as Java-serialized byte arrays; all four are
rejected at boot.

This work depends on HIBERNATE-224, which must land first. No type works inside a `@Struct` embeddable or as an
HQL literal unless `ValueConversions` happens to have a branch for its domain class, which is why
`OffsetDateTime` fails in those two positions today. That is a general defect rather than a temporal one, so it
is designed and ticketed separately. Until it lands, the types added here cannot be supported inside `@Struct`
embeddables or as literals, and shipping them with that gap would be worse than sequencing, since `Instant`
already works in both.

## Current state

`java.time.Instant` is the only supported temporal type. It reaches BSON `Date` through
`MongoDialect.contributeInstantType`, which registers `TimestampUtcAsInstantJdbcType` for
`SqlTypes.TIMESTAMP_UTC` so that Hibernate binds through `setObject`/`getObject` rather than
`setTimestamp`/`getTimestamp`. That registration is what `OffsetDateTime` and `ZonedDateTime` reuse, so no new
`JdbcType` is needed.

`MongoAdditionalMappingContributor.UNSUPPORTED_TYPES` rejects `Calendar`, `java.util.Date`, `java.sql.Date`,
`java.sql.Time`, `java.sql.Timestamp`, `LocalTime`, `LocalDateTime`, `ZonedDateTime`, `OffsetTime`, and
`OffsetDateTime` at boot. `LocalDate` is absent from both the supported and the rejected set.

Anything Hibernate routes to a `JdbcType` this extension has not registered falls through to
`setTimestamp`/`setDate`/`setTime`, which are unimplemented, and fails at write. Boot-time rejection is
therefore the mechanism that turns an unsupported mapping into a diagnosable one, and the rejection list has to
be complete because there is no catch-all.

## The organizing rule

BSON `Date` is PostgreSQL `timestamptz`. Both are an instant: a fixed-width integer count since an epoch,
normalized to UTC on write, with no zone or offset retained, and with native comparison and indexing. They
differ in precision, which is a fixed 3 digits here against a declarable 0 to 6 there, and in that PostgreSQL
also has a wall-clock `timestamp` type, which MongoDB has no counterpart for.

That yields the rule this design follows: support exactly what Hibernate maps to `timestamptz`, and reject what
it maps to `timestamp`, `date`, or `time`. Every rejection below is an instance of it.

The behavior recorded here was measured against PostgreSQL 17 with Hibernate 7.4.5 in a throwaway probe, not
inferred from documentation. The probe is not checked in.

## Type mapping

Hibernate's own choice of column type, read back from `information_schema`, is what partitions the types:

| Java type | PostgreSQL column | Instant survives a cross-host read | Status |
|---|---|---|---|
| `Instant` | `timestamptz` | yes | supported |
| `OffsetDateTime` | `timestamptz` | yes | supported |
| `ZonedDateTime` | `timestamptz` | yes | supported |
| `java.sql.Timestamp` | `timestamp` | no | rejected |
| `java.util.Date` | `timestamp` | no | rejected |
| `LocalDateTime` | `timestamp` | not applicable | rejected |
| `LocalDate`, `LocalTime` | `date`, `time` | not applicable | rejected |

Written under `America/New_York` and read under `Asia/Kolkata`, the wall-clock types returned
`2026-08-08T22:45:30.002Z` for a value written as `2026-08-09T08:15:30.002Z`. The three supported types were
stable across both hosts.

`OffsetDateTime` and `ZonedDateTime` reach BSON `Date` through the existing `TIMESTAMP_UTC` registration: both
unwrap to `Instant` before reaching `ValueConversions`, so no per-type branch is needed. A `ZonedDateTime`
preserves its instant; a named zone degrades to a fixed offset, and only under `COLUMN`.

`Duration`, `Year`, `ZoneId`, `ZoneOffset`, and `java.util.TimeZone` are owned by HIBERNATE-224.

## Time zone storage strategies

`TimeZoneStorageStrategy` resolves per attribute in `BasicValue.timeZoneStorageStrategy`, not only globally, so
every value is reachable through `@TimeZoneStorage` even when the global setting is left alone. Checks are
written against the resolved strategy rather than the annotation value, because `AUTO` and `DEFAULT` resolve to
supported strategies.

| Configuration | Resolves to | Status |
|---|---|---|
| no annotation, `hibernate.timezone.default_storage` unset | `NORMALIZE_UTC` | supported |
| `@TimeZoneStorage(DEFAULT)`, global `DEFAULT` | `NORMALIZE_UTC` | supported |
| `@TimeZoneStorage(NORMALIZE_UTC)`, global `NORMALIZE_UTC` | `NORMALIZE_UTC` | supported |
| `@TimeZoneStorage` with no argument, `@TimeZoneStorage(AUTO)`, global `AUTO` | `COLUMN` | supported |
| `@TimeZoneStorage(COLUMN)`, global `COLUMN` | `COLUMN` | supported |
| `@TimeZoneStorage(NORMALIZE)`, global `NORMALIZE` | `NORMALIZE` | rejected at boot |
| `@TimeZoneStorage(NATIVE)` | `NATIVE` | rejected at boot |
| global `NATIVE` | throws in Hibernate | no check needed |

`NORMALIZE` is the wall-clock strategy. Hibernate documents it as normalizing to the JVM default zone on write
and setting the zone to the JVM default on read, and it maps to `timestamp without time zone`. The same value
read back under two hosts gave `04:15:30.002-04:00` and `04:15:30.002+05:30`, which are different instants. It
is rejected for the same reason `LocalDateTime` is. Hibernate does not reject it for us at either scope, so both
the annotation and the global setting need a check.

`NORMALIZE` cannot be honoured even setting the wall-clock problem aside. It is defined to preserve the instant
and return it at the JVM default zone, which requires Hibernate ORM to convert through `java.sql.Timestamp`; for
values before 1905 that conversion goes through calendar fields belonging to a calendar that is Julian before the
Gregorian cutover, so a BSON `Date` would hold something other than the instant - ten days out for a year-1500
value - even though the round trip cancels out and hides it. Reading through `Instant` instead keeps the stored
value exact but returns UTC, which is `NORMALIZE_UTC`, so there is nothing left for `NORMALIZE` to mean here.

`NATIVE` is documented as an error unless `Dialect.getTimeZoneSupport()` is `NATIVE`. Hibernate enforces that
only for the global setting and leaves the annotation unguarded, where it currently fails at write. The check
here implements Hibernate's stated contract.

`@TimeZoneStorage` with no argument defaults to `AUTO` and therefore to `COLUMN`, so the bare annotation, which
reads like a no-op, silently switches an attribute to two-field storage. This is called out in the user
documentation in `module-info.java`.

Under `COLUMN`, an attribute occupies a BSON `Date` plus an `Int32` offset-seconds field, named `<property>_tz`
by default and renameable with `@TimeZoneColumn`. Hibernate generates the surrounding SQL consistently: equality is a row
comparison over both fields, `is null` tests both, `order by` sorts on both, and a null attribute writes null to
both.

Of those four, `is null` is the one the translator refuses. Testing both fields reaches it as a row-valued
nullness predicate, and `visitNullnessPredicate` handles a field path only, so the query throws
`FeatureNotSupportedException` rather than running. The refusal is not staged behind anything: HIBERNATE-210
covered row-value `=`, `<>` and `IN`, and HIBERNATE-211 covers the row-value ordering comparisons, but no
ticket covers row-valued nullness. The other three hold. Equality and the two-field null write are covered by
tests, and `order by` over a `COLUMN` attribute translates and executes.

`COLUMN` inside a `@Struct` embeddable needs work rather than falling out. The composite user type does not
decompose there, so the whole `OffsetDateTime` reaches `MongoStructJdbcType` as one domain value and fails to
bind. The write path emits the same two fields the flat path emits, `value` and `value_tz`, inside the
subdocument, and the read path recomposes them.

`COLUMN` is rejected at boot on an identifier and on a plural attribute. An identifier cannot span two fields.
On a plural attribute Hibernate silently ignores the strategy rather than honouring or rejecting it: the
collection survives and its elements lose their offsets, so two elements written with different offsets read
back identical. Rejecting is the only way a user learns the mapping did not do what it says.

## Datetime function evaluation zone

BSON `Date` carries no zone, so every datetime function has to name one, and MQL takes a `timezone` argument on
each operator. MongoDB has no session-zone equivalent, so the value comes from the extension.

| Argument | No zone argument | Explicit zone argument |
|---|---|---|
| plain instant attribute | JVM default zone | the named zone |
| `COLUMN` attribute | UTC, applied to the offset-adjusted expression | the named zone, applied to the raw instant |

The JVM default matches what SQL databases do, where the zone comes from server session state that the driver
sets from the connecting JVM. It also means the emitted pipeline and the query results depend on the host, which
is inherent to the choice and is what those databases already do.

The `COLUMN` row is a correctness constraint rather than a preference. Hibernate rewrites a `COLUMN` attribute
at the SQM level into `instant + toDuration(offset, SECOND)`, so the value's UTC fields are already its local
wall clock; evaluating at any other zone double-counts the offset. PostgreSQL gets this wrong, returning hour 6
where 10 is correct, because it casts the adjusted value back through the session zone.

Only `ExtractFunction` performs that rewrite. Any function this extension registers receives a `COLUMN`
attribute as an unusable two-column record and must unpack it through `SqmExpressionHelper`: with
`getOffsetAdjustedExpression` when no zone is named, and with `getActualExpression` when one is, so that an
explicit zone overrides the stored offset instead of compounding with it.

Naming a zone from HQL is not otherwise possible. The grammar has no `at time zone` operator, and the only
route on other dialects is native passthrough through `function()` or `sql()`, which the MQL translator cannot
consume. Zone-taking overloads such as `hour(x, :tz)` are therefore the only way to reach it, and they are
available: `hour` and its siblings are grammar productions with no function-registry entry, so a dialect can
register a two-argument form that coexists with the built-in one-argument form. Registering an overload of a
name that is already a registry entry, such as `date_trunc`, replaces it rather than adding to it. This belongs
to HIBERNATE-88; it is recorded here because it is the reason the default zone is a decision rather than a
convention.

## Precision

BSON `Date` holds milliseconds; every supported type carries finer precision.

Narrowing rounds to the nearest millisecond, halves going up. `Dialect.doesRoundTemporalOnOverflow()` already
declares rounding by inheritance, but nothing in hibernate-core narrows temporal values to the dialect's
precision: `DateTimeUtils.adjustToPrecision` and its three siblings have no callers. The rounding is therefore
implemented in `ValueConversions`, over `Instant` only, since the other two supported types unwrap to `Instant`
first. This changes existing `Instant` behavior, which truncates today.

`Dialect.getDefaultTimestampPrecision()` is overridden to return 3. It feeds `ClockHelper`, which
`CurrentTimestampGeneration` builds its clock from, and which backs `@CreationTimestamp`, `@UpdateTimestamp`,
and `@CurrentTimestamp(source = VM)`. At the inherited default of 6 the generated value is narrowed on write and
the in-memory entity no longer equals a re-read. PostgreSQL reproduces this against a millisecond column,
writing `...T23:03:54.158916Z` and reading back `...159Z`.

`@Column(precision)` needs no check. Hibernate ignores it for temporal attributes; an attribute annotated
`precision = 3` still resolved to precision 6.

## Settings

`hibernate.type.preferred_instant_jdbc_type` is rejected at boot unless it is `TIMESTAMP_UTC`, which is its
default. Set to `TIMESTAMP` it redirects `Instant` attributes onto the wall-clock type this design rejects, so
an instant written on one host reads back as a different instant on another. It affects `Instant` only, not
`OffsetDateTime` or `ZonedDateTime`.

`hibernate.jdbc.time_zone` is inert and stays inert. It is implemented as the `Calendar` argument to
`setTimestamp` and `getTimestamp`, which exists to interpret zone-less wall-clock values, and this design has no
wall-clock values. PostgreSQL behaves the same way for `timestamptz`: the setting provably does not affect
either the stored value or server-side evaluation. Documented, not rejected.

## Optimistic locking

A temporal `@Version` attribute is rejected at boot. At millisecond precision two updates inside the same
millisecond produce an equal version, so the optimistic-lock check on the second cannot fire. Measured over 300
contended rounds against a millisecond column, 102 rounds committed the second session's change over the first,
matching exactly the 102 rounds where the version value did not change; the same test at microsecond precision
lost none of 300. Numeric `@Version` is unaffected.

This subsumes the `COLUMN`-on-`@Version` case. That combination additionally trips a bare `assert` in
Hibernate's `VersionResolution.resolve` before this extension's contributor runs, so under `-ea` an
`AssertionError` surfaces first; the negative test accepts either failure.

## Implementation approach

No new MongoDB AST node classes, and `AbstractMqlTranslator` does not change. The new types reach the translator
as `AstLiteral` or `AstParameterMarker` exactly as `Instant` does.

`COLUMN` is the first supported mapping where one attribute spans two columns, and it exposes a latent defect in
`MongoStructJdbcType`: both directions index selectables by column while indexing values by attribute, which
coincide only while every attribute has exactly one column. Both directions go through `StructHelper`, whose
`getJdbcValues` and `getAttributeValues` are the flattening and recomposition Hibernate itself uses, and which
also supply each field's mapped name rather than a synthesized one.

`MongoStructJdbcType` additionally handles a `COLUMN`-stored attribute inside the embeddable, since Hibernate
does not decompose the composite user type in that position and hands over the whole domain value.

Fixing the `@Struct` indexing also promotes a shape this extension previously rejected: a flattened
`@Embeddable` inside a `@Struct` one now stores as the enclosing subdocument's own fields and reads back intact,
because `StructHelper` recurses into an embeddable-valued attribute. That is correct relational behaviour and is
covered by a round-trip test, but it belongs to embeddable support rather than to this ticket and deserves
coverage of its own.

`internal/dialect/MongoDialect`
- Override `getDefaultTimestampPrecision()` to return 3.

`internal/type/ValueConversions`
- Round `Instant` to the nearest millisecond, halves up: `toEpochMilli()` plus one when
  `getNano() % 1_000_000 >= 500_000`. `getNano()` is non-negative and `toEpochMilli()` floors, so one rule
  covers pre- and post-epoch values.

`internal/boot/MongoAdditionalMappingContributor`
- `UNSUPPORTED_TYPES`: remove `OffsetDateTime` and `ZonedDateTime`; add `LocalDate`, `Period`, `YearMonth`,
  `MonthDay`.
- Reject a resolved `NORMALIZE` or `NATIVE` strategy.
- Reject `COLUMN` storage on an identifier and on a plural attribute.
- Reject a temporal `@Version` attribute.
- Reject `BasicValue.getTemporalPrecision()` of `DATE` or `TIME`, which is how `@Temporal(DATE)` and
  `@Temporal(TIME)` arrive.
- Reject `hibernate.type.preferred_instant_jdbc_type` unless `TIMESTAMP_UTC`.

`module-info.java`
- Add the new temporal rows to the default type mapping table.

## Shapes

Writing T for `Instant`, `OffsetDateTime`, `ZonedDateTime`.

| Shape | Status |
|---|---|
| T as a basic attribute | supported |
| T as `@Id` | supported under `NORMALIZE_UTC`; rejected under `COLUMN`, which spans two columns |
| `T[]`, `Collection<T>` | supported under `NORMALIZE_UTC`; rejected under `COLUMN` |
| T in a flattened `@Embeddable` | supported |
| T in a `@Struct` aggregate `@Embeddable`, including nested | supported, under both supported strategies |
| T in `SELECT`, `WHERE` against a parameter or an HQL literal, `is null`, `in`, `ORDER BY`, mutation `SET` | supported, except `is null` over a `COLUMN`-stored attribute |
| A `COLUMN` attribute in `WHERE` equality | supported, and compares both fields. `createdAt = 2026-08-09T10:15:30+02:00` does not match a document holding that instant with offset 0. |
| `is null` over a `COLUMN` attribute | rejected: it reaches the translator as a row-valued nullness predicate, which is unsupported. No ticket covers it. |
| T as `@Version` | rejected at boot, permanently |
| `@TimeZoneStorage(NORMALIZE)`, `@TimeZoneStorage(NATIVE)` | rejected at boot, permanently |
| `COLUMN` with `@Id` or a plural attribute | rejected at boot, permanently |
| `hibernate.type.preferred_instant_jdbc_type` other than `TIMESTAMP_UTC` | rejected at boot, permanently |
| `java.util.Date`, `java.sql.Timestamp`, `java.util.Calendar` | rejected at boot, permanently |
| `@Temporal(DATE)`, `@Temporal(TIME)` | rejected at boot, HIBERNATE-226 |
| `LocalDate`, `LocalDateTime`, `LocalTime`, `OffsetTime`, `java.sql.Date`, `java.sql.Time` | rejected at boot, HIBERNATE-226 |
| `Period`, `YearMonth`, `MonthDay` | rejected at boot, permanently, to stop the Java-serialized `Binary` representation from becoming a compatibility commitment |

`Calendar` is permanent rather than deferred because MongoDB cannot store the zone and `CalendarJavaType.areEqual`
compares local field values rather than the instant, so a round trip silently reports inequality on any host
whose default zone differs from the writing `Calendar`'s. That would corrupt dirty checking, not merely
round-trip assertions.

## Tests

Round-trip tests live in `src/integrationTest/java/com/mongodb/hibernate/type/temporal/`, query-side tests in
`src/integrationTest/java/com/mongodb/hibernate/query/select/temporal/`.

| Test | Covers |
|---|---|
| `OffsetDateTimeIntegrationTests`, `ZonedDateTimeIntegrationTests` converted from negative to round-trip | The basic, plural, flattened-embeddable, and `@Struct` positions, parameterized over JVM and session time zones, following the existing `InstantIntegrationTests` shape |
| `InstantIntegrationTests` expectations updated | Rounding replaces truncation: `…30.002900000Z` now stores as `…30.003Z` |
| New `@Nested` class per type for `COLUMN` storage | `@TimeZoneStorage(COLUMN)`, the bare `@TimeZoneStorage`, `hibernate.timezone.default_storage = AUTO`, `@TimeZoneColumn(name = …)`, and the resulting two-field document |
| `COLUMN` predicate tests | The emitted `$and` over both fields for equality, that an instant-equal but offset-different value does not match, a null attribute writing null to both fields, and a negative test pinning that `is null` is rejected |
| `@CreationTimestamp` round-trip test | The case `getDefaultTimestampPrecision()` fixes; the entity must equal a re-read |
| Query-side tests per type | Full MQL assertion for projection, predicate against a parameter, predicate against an HQL literal, `is null`, `in`, `ORDER BY`, and mutation `SET` |
| `DateIntegrationTests`, `SqlTimestampIntegrationTests`, `CalendarIntegrationTests` and the remaining files stay negative | The permanently and temporarily rejected types |
| New negative tests | `@TimeZoneStorage(NORMALIZE)` and `(NATIVE)`, both scopes where applicable; `COLUMN` with a plural attribute and with `@Id`; temporal `@Version`; `hibernate.type.preferred_instant_jdbc_type` set to `TIMESTAMP`; `@Temporal(DATE)`, `@Temporal(TIME)`, `LocalDate`, `Period`, `YearMonth`, `MonthDay`, each asserting the boot-time message |

Every rejected shape gets a negative test asserting the message, so a future change that silently starts
accepting one of them fails.

Full-MQL assertions become host-dependent once datetime functions evaluate at the JVM default zone. The
integration test JVM's zone has to be pinned for those assertions to be deterministic.

## Carried into the plan

- Whether the evaluation zone is emitted as a literal or bound as a parameter. Hibernate caches query plans, so
  a literal resolves once at first translation and will not track a later `TimeZone.setDefault`. MQL accepts an
  expression for `timezone`, so binding is available.
- Whether MQL accepts an offset-style zone id such as `GMT+02:00`, which `ZoneId.systemDefault()` returns when
  `user.timezone` is set that way.
- Confirming the plural-`COLUMN` behaviour against MongoDB. PostgreSQL keeps the collection and silently drops
  the strategy; the failure mode here may differ, and the negative test should assert whichever it is.

## Tickets

Filed as HIBERNATE-225 (New Feature, component Model), depending on HIBERNATE-224 and related to HIBERNATE-226,
which covers the date-only and time-only types this work leaves rejected. The zone-taking function overloads
described under the evaluation zone belong to HIBERNATE-88.
