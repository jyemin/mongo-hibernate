# Review: PR #209, HIBERNATE-88 Support HQL date functions

Commit reviewed: `dae21175` (PR #209 head, open, non-draft, no prior reviews).
Parent: `0d8fa55c`.

HIBERNATE-88 was split, so `HIBERNATE-242` (date functions relying on other temporal types) and
`HIBERNATE-243` (datetime arithmetic) are out of scope here. This PR covers `extract` and `format`
over `Instant`.

## Baseline

`./gradlew build -x integrationTest` and `./gradlew :integrationTest` both pass on `dae21175`.

Parent `0d8fa55c`: every extract and format shape probed throws
`FeatureNotSupportedException: TODO-HIBERNATE-196`, and `extract(native from ...)` is an HQL parse
error on both sides. So nothing below is a regression; all of it is new-feature correctness.

Evidence label: everything marked Verified was executed against the local replica set
(mongod 8.0.24, JVM default zone `America/New_York`) through a throwaway probe class, or in
`mongosh`. Items with no label are design or code-quality observations that need no execution.

## Failing tests

Two classes under `src/integrationTest/java/com/mongodb/hibernate/query/function/`.

`DateFunctionReviewFindingsIntegrationTests` holds one test per finding, in the ambient zone:

```
./gradlew :integrationTest --tests DateFunctionReviewFindingsIntegrationTests
53 tests completed, 45 failed
```

`DateFunctionZoneIntegrationTests` runs the zone axis, which nothing in the suite covered before. It
replaces the JVM default zone with `Asia/Tokyo` and is `@Isolated` for the same reason the fail-point
tests are: the default zone is process-global state, and every datetime function this PR adds reads
it. The zone is set in a static initializer rather than `@BeforeAll`, because `MongoDialect` captures
it into the `format` function when the dialect is constructed, which happens when the SessionFactory
is built:

```
./gradlew :integrationTest --tests DateFunctionZoneIntegrationTests
7 tests completed, 2 failed
```

Whole suite: 765 tests, 47 failed, all 47 inside those two classes.

`extract(week of month ...)` fails on all ten instants. `extract(week of year ...)` fails on six of
ten, and `extract(week ...)` on eight of ten; the passes there are dates where the two definitions
happen to coincide, which is the same coincidence the PR's own week tests rely on. Three of the ten
instants are mid-year and deliberately away from any year boundary, because the first pass mistakenly
read this as a boundary artifact. Per-instant values are in the comparison below.

Assertions are on returned values, not on emitted MQL, because the pipeline for a wrong answer is not
the contract.

## PostgreSQL reference

The expected values below were not derived from javadoc alone. The same HQL was run through Hibernate
7.4.5.Final against PostgreSQL 17.10 via `PostgreSQLDialect`, over the same instants and under the
same two JVM default zones, and the measured comparison is in
[hql-date-functions-postgres-comparison.md](hql-date-functions-postgres-comparison.md). That document
stands alone and is the evidence base for findings 1 through 10.

Two things the reference run turned up that the first pass of this review had wrong, corrected below:
finding 11 is withdrawn, and finding 8's fractional-second and offset claims were mischaracterised.

That document covers result comparisons only. The HQL grammar and function-registry questions that
bear on the zone-overload item are established in that item itself, under Significant.

## Blockers

### 1. `week` and `week of year` are given each other's operator

`MongoExtractFunction.java:218` maps `WEEK_OF_YEAR` to `$isoWeek`; `:227` maps `WEEK` to `$week`.

`TemporalUnit.WEEK` javadoc: "the ISO-8601 week number when passed to `extract()`. This is different
to `WEEK_OF_YEAR`." `WEEK_OF_YEAR`: "the first day of the year is in week 1, and a new week starts
each Sunday". Hibernate's own `ExtractFunction.extractWeek` computes it as
`ceiling((dayOfYear - dayOfWeek)/7.0 + 1)`, with `DAY_OF_WEEK` running 1 (Sunday) to 7 (Saturday).

`WEEK` wants `$isoWeek`, which is ISO-8601 exactly: weeks Monday to Sunday, week 1 being the week
holding the year's first Thursday, so `2021-01-01` is week 53 of ISO year 2020 and `1969-12-31` is
week 1 of ISO year 1970. `$isoWeek` was measured in `mongosh` and agrees with the PostgreSQL `week`
column on every instant where both were measured.

`WEEK_OF_YEAR` needs the formula transliterated, not `$week` with an adjustment. `$week + 1` is right
only when 1 January is not a Sunday; in a Sunday-start year there is no week 0, so `$week` already
begins at 1 and the `+1` overshoots for the whole year. Swept day by day, `$week + 1` mismatches
Hibernate's rule on 0 of 365 days in 2021, 2022, 2025, 2026 and 2027, and on 365 of 365 in 2023,
where plain `$week` is the one that matches on every day. No constant adjustment covers both cases.

What does work, with zero mismatches over 3652 days spanning 1969, 1970 and 2021 through 2028, is the
formula itself, built from operators MongoDB already has:

```
week of year:  $ceil(($dayOfYear  - $dayOfWeek)/7 + 1)
week of month: $ceil(($dayOfMonth - $dayOfWeek)/7 + 1)
```

Verified:

| local date | `week` expected / actual | `week of year` expected / actual |
|---|---|---|
| 1970-01-02 | 1 / 0 | 1 / 1 |
| 2026-08-24 | 35 / 34 | 35 / 35 |
| 2026-01-01 | 1 / 0 | 1 / 1 |
| 2021-01-01 | 53 / 0 | 1 / 53 |
| 2023-12-31 | 52 / 53 | 53 / 52 |
| 1969-12-31 | 1 / 52 | 53 / 1 |

### 2. `week of month` is off by one, and the outer `$week` ignores the timezone

`MongoExtractFunction.java:236-266`. Hibernate's rule is `ceiling((dayOfMonth - dayOfWeek)/7.0 + 1)`,
1-based. The emitted `$week(t) - $week($dateTrunc(t, month))` counts Sunday boundaries crossed,
0-based.

Verified: every one of the six dates above came back exactly one low (1970-01-02 gives 0 against 1;
2026-08-24 gives 4 against 5; 2023-12-31 gives 5 against 6).

Second defect in the same expression: the outer `$week` at `:250` is an
`AstUnaryOperatorExpression`, so it evaluates the truncated instant in UTC, while the inner `$week`
at `:240` passes the timezone. In a positive-offset zone `$dateTrunc` returns the previous UTC day.
Verified in `mongosh` with `Asia/Tokyo` and February 2026 (a month beginning on a Sunday): the UTC
evaluation shifts the result by one and cancels the off-by-one, so the answer is accidentally
correct there. The error is input-dependent, not a uniform `+1`.

`DateFunctionZoneIntegrationTests` demonstrates that the two defects are independent, by running the
same unit in `Asia/Tokyo`. There, `extract(week of month ...)` is correct for February, March and
November 2026 and wrong for May and August. The three that pass are exactly the months beginning on a
Sunday: local midnight on a Sunday is 15:00 UTC the previous Saturday, which falls in the previous
`$week`, so the subtrahend drops by one and cancels the off-by-one. In `America/New_York` the same
expression is wrong on all ten instants. One off-by-one cannot be wrong everywhere in one zone and
right in three months of five in another; two opposing errors can.

So this is not a `+1` on the difference. `$ceil(($dayOfMonth - $dayOfWeek)/7 + 1)` from finding 1
replaces the whole `$let` and its two `$week` calls, which retires the timezone inconsistency along
with the off-by-one.

### 3. `format(x as 'hh')` emits 24-hour time and `'a'` is silently deleted

`AbstractMqlTranslator.java:1461-1464` map `hh`, `HH`, `h`, `H` all to `%H`; `:1458` maps `a` to the
empty string.

Verified: `format(before as 'hh:mm a')` on `2026-08-24T23:45:21.987Z` (19:45 local) returns
`"19:45 "`. Expected `07:45 PM`. `hh` and `HH` emit byte-identical MQL. The dropped `a` leaves its
separator space behind.

`$dateToString` has no 12-hour or AM/PM specifier: verified in `mongosh` that `%I` and `%p` are both
rejected with `Invalid format character`. These three patterns cannot be translated and need a
throw.

### 4. Day-of-week patterns emit a number instead of a name

`AbstractMqlTranslator.java:1443-1444`: `EEEE` and `EEE` both map to `%u`. The reference mapping in
`OracleDialect.datetimeFormat`, which this chain is structurally copied from, is `EEEE` to `Day` and
`EEE` to `Dy`.

Verified: `format(before as 'EEEE')` returns `"1"`, not `"Monday"`. `$dateToString` has no day-name
specifier (`%a` and `%A` are both rejected by the server), so both need a throw. For contrast
`MMM`/`MMMM` do work: `%b`/`%B` exist and return `"Aug"`/`"August"`.

### 5. `format(x as 'W')` emits the letter W

`AbstractMqlTranslator.java:1440`: `.replace("W", "W")` is a no-op, so week-of-month survives into
the format string as a literal character. Verified: result is `"W"`.

### 6. `format` week patterns use the Sunday-based week, not ISO

`AbstractMqlTranslator.java:1431-1432` map `ww` and `w` to `%U`, the Sunday-based week. Both patterns
mean the ISO week, which is what the Oracle mapping this chain is copied from selects, and what
PostgreSQL returns. Verified: `format(before as 'w')` on 2026-08-24 returns `"34"`; the ISO week is 35.
`%V` is the ISO specifier and is supported (verified in `mongosh`).

### 7. Quoted literal chunks emit stray double quotes

`AbstractMqlTranslator.java:1413`: `new Replacer(format.getFormat(), "'", "\"")`. The third argument
is the delimiter `Replacer` wraps literal chunks in, and `"` is Oracle's literal-quoting character.
`$dateToString` treats every non-`%` character as literal and has no quoting mechanism, so the
delimiter lands in the output.

Verified: `format(before as 'd MMMM yyyy ''at'' HH:mm')` returns `24 August 2026 "at" 09:45`, from
format string `%d %B %Y \"at\" %H:%M`. `format(before as '''T''')` returns `"T"`. The delimiter
should be `""`.

### 8. `yy` ignores the requested width, and `z` returns an offset instead of a zone name

- `:1421` maps `yy` to `%Y`. PostgreSQL returns `26`, this PR returns `2026`. Test
  `testFormatTwoDigitYear`.
- `:1483-1485` map `zzz`/`zz`/`z` to `%z`. PostgreSQL returns `EDT`, this PR returns `-0400`. MQL has
  no zone-name specifier at all, since `%Z` is a minute count, so `z` has no translation and needs a
  throw. Test `testFormatZoneNameIsNotNumericOffset`.

Two further pattern families diverge, both fixable, neither gating merge.

The fractional-second widths at `:1475-1480` map `S` through `SSSSSS` all to `%L`, three digits, where
the letter count is the number of digits asked for. The zeros past the third carry no precision, since
BSON `Date` resolves to milliseconds, but the width is part of what the pattern requests and every
other dialect emits it. Widths four to six are a one-line mapping change each, to `%L0`, `%L00` and
`%L000`, verified to produce `9870`, `98700` and `987000`; widths one and two need `%L` truncated,
which a format string cannot do.

The offset patterns at `:1489-1491` map `x`, `xx` and `xxx` all to `%z`, which is the `xx` shape, so
`xx` is correct and `x` and `xxx` are not; `xxx` wants `-04:00` and `x` wants `-04`. Note PostgreSQL
is not a reference here, mapping all three onto one template that is right for `x` alone, and only in
whole-hour zones.

`S`, `SS`, `x` and `xxx` share one enabling change, since each needs a specifier's output modified
rather than appended to, which means `format` becoming a `$concat` of segments rather than a single
`$dateToString`. That is beyond `MongoExpressionNamedFunction`, which emits one named operator, and a
descriptor is the only component seeing the operand, the pattern and the timezone together. The
comparison document sketches a `MongoFormatFunction` for it, with `visitFormat` continuing to own the
pattern language and yielding segments through a new `AstVisitorValueDescriptor` rather than a string.
That is also the first point at which `EEEE`, `EEE`, `z`, `yy`, `a` and `W` can be refused by name
instead of silently substituted.

### 9. `format(x, 'pattern')` returns the pattern text instead of a formatted date

The registered descriptor at `MongoDialect.java:331-345` declares its second parameter as
`FunctionParameterType.STRING`, so a plain two-argument call satisfies it without going through the
`format(x as 'pattern')` grammar rule. `visitFormat` is never reached, and the untranslated HQL
pattern is handed straight to `$dateToString` as its `format`.

Verified, on 2026-08-24T13:45:21.987Z:

| HQL | result |
|---|---|
| `format(t.before, 'yyyy-MM-dd')` | `"yyyy-MM-dd"` |
| `format(t.before as 'yyyy-MM-dd')` | `"2026-08-24"` |

Reachable, silent, and no test covers the two-argument form.

Scope correction, from Andre. PostgreSQL does not translate the two-argument form either, so this is
not a case of the extension breaking something the reference dialect handles. My earlier claim that it
did rested on a single pattern, `yyyy-MM-dd`, which happens to be valid in both pattern languages and
so came out right by luck. Untranslated, PostgreSQL renders `HH:mm` as `09:08`, hour and month, and
`EEEE` as the literal `EEEE`.

The remedy is to refuse the two-argument spelling, and refusing it costs nothing. The documented
spelling takes a string literal and only a literal: the grammar's `format` rule is a single
`STRING_LITERAL` token, and `SemanticQueryBuilder.visitFormat` reads it with
`unquoteStringLiteral(ctx.STRING_LITERAL().getText())`. Verified: `format(x as :p)` and
`format(x as 'yy'||'yy')` are both syntax errors. So the only capability the two-argument form adds is
a non-literal pattern, and that is precisely what cannot be translated, since MQL has no operator that
translates a pattern at execution time. Accepting a literal there would merely duplicate the `as` form.

`testFormatTwoArgumentFormIsRefused` asserts the refusal.

### 10. `extract(date from x)` emits a pipeline and then fails in the JDBC layer

`MongoExtractFunction.java:81-91` translates `DATE` to `$dateTrunc`. Verified: the pipeline is
emitted and accepted, then reading the row hits `ResultSetAdapter.getDate` and throws
`SQLFeatureNotSupportedException: getDate not implemented`. Reachable HQL, no test, and the failure
violates the exception contract.

The recommendation is to remove the `DATE` case so it throws `FeatureNotSupportedException` with a
`TODO-HIBERNATE-242` reference, rather than to implement the read path.

`DATE` and `TIME` have the same blocker and this PR treats them differently. `extract(time from x)`
returns a `LocalTime`, which is in `UNSUPPORTED_TYPES` at
`MongoAdditionalMappingContributor.java:99-126` and rejected at boot as a mapped type, and the switch
has no `TIME` case, so it throws. `extract(date from x)` returns a `LocalDate`, which is in neither
set, neither supported nor rejected, and the switch does have a `DATE` case. Nothing in the code or
the ticket explains the asymmetry.

Implementing `getDate` would commit the extension to `LocalDate` as a query result type while it
remains unguarded as a mapped type, where it currently boots and then fails at write. That belongs
with the temporal-type work, not here.

### 11. Withdrawn: `extract(epoch from x)` before 1970

The first pass called this a blocker on the grounds that `MongoExtractFunction.java:119-125` does
`$toLong($toLong(date)/1000)`, which truncates toward zero, so `1969-12-31T23:59:59.500Z` returns `0`
where `Instant.getEpochSecond()` is `-1`.

PostgreSQL returns `0` for the same HQL. The two implementations agree, so there is nothing here to
fix and no test asserting otherwise.

For the record, the disagreement is inside Hibernate rather than in this extension. PostgreSQL
generates `select extract(epoch from before)`, and in the database that expression is `-0.500000`;
both `floor(...)` and `cast(... as bigint)` give `-1` when applied in SQL. The `0` appears when
Hibernate reads the column back as a `Long`. Whatever the merits, matching that is the correct
behaviour for this dialect, and diverging from it would be the defect.

## Significant

### No way to name an evaluation zone: add zone-taking overloads

The JVM default zone at `MongoExtractFunction.java:89`, at fifteen more sites in that file, and at
`MongoDialect.java:342` is the right default. It matches what a JDBC driver against a SQL database
does, where the zone comes from server session state the driver sets from the connecting JVM. That the
emitted pipeline and the results depend on the host follows from the choice and is what those drivers
already do. No finding on the default itself.

What is missing is any way for a caller to override it. BSON `Date` carries no zone, so every one of
these operators has to name one, and MongoDB has no session-zone equivalent to change. HQL has no `at
time zone` operator, and the only route on other dialects is native passthrough through `function()`
or `sql()`, which the MQL translator cannot consume. So on this extension the JVM default is not a
default, it is the only reachable behaviour, and an application that wants a fixed reporting zone has
nowhere to say so.

Two-argument overloads close it for the abbreviation family: `year`, `month`, `day`, `hour`, `minute`,
`second`, `week`, and `quarter`, each taking a zone as a second argument. Verified that this is
reachable: `hour(t.before, 'UTC')` and each of its siblings parse and are not arity-checked, which is
what it looks like when a name has no function-registry entry. A registered name behaves differently,
`upper(t.before, 'x')` gives `FunctionArgumentException: Function upper() has 1 parameters, but 2
arguments given`, so a two-argument descriptor registered under `hour` would bind and be validated.

The one-argument spelling is claimed by the grammar rather than by the registry, which is what leaves
the two-argument form free: `hour(x)` matches the `extractFunction` rule and is rewritten into
`extract`. Hibernate confirms the same split from the other direction, in the comment on
`CommonFunctionFactory.hourMinuteSecond()`, which registers descriptors under these very names and
notes that "since their names collide with the HQL abbreviations for extract(), they can't actually be
called from HQL". Since the rule matches exactly one expression, a two-argument call is outside it and
reaches the generic-function path. Worth knowing before implementing: do not register a one-argument
form, it would be dead.

The corollary is that this route is only open for names with no existing entry: registering an overload
of a name that does have one replaces it rather than adding to it, which is why `format` needs its zone
on the existing descriptor.

The two ANSI forms cannot take a zone argument at all, so they need the zone on the descriptor rather
than as an overload.

- `extract(field from x, :tz)` does not parse. The `extractFunction` grammar rule holds one
  `expression()` and no comma, and `SemanticQueryBuilder.visitExtractFunction` builds the argument
  list as a fixed `asList(extractFieldExpression, expressionToExtract)`. Verified:
  `extract(hour from t.before, 'UTC')` gives `SyntaxException: no viable alternative` at the comma.
- `format(x as 'pattern', :tz)` does not parse either. Verified, same `SyntaxException`. Since
  `format` is a registry entry, widening the registered descriptor to accept an optional third
  argument is the route; today `format(t.before, 'yyyy', 'UTC')` gives `FunctionArgumentException:
  Function format() requires between 2 and 2 arguments, but 3 arguments given`.

Neither HIBERNATE-242 nor HIBERNATE-243 covers any of this, so it needs a ticket if it is not being
done here.

### The tests are blind to the zone axis this PR introduces

This PR adds the first behaviour in the product whose results depend on the JVM default zone. Before
it, every zone-touching site in `src/main` is inert: the `Calendar`-taking overloads in
`ResultSetAdapter` and `PreparedStatementAdapter` all throw `SQLFeatureNotSupportedException`, and
`MongoDialect`'s javadoc on `contributeInstantType` states that its purpose is to make Hibernate use
`setObject`/`getObject` instead of the `Calendar`-taking `setTimestamp`/`getTimestamp`. An `Instant`
round-trips as an absolute instant and nothing consults the zone. This PR adds 17
`ZoneId.systemDefault()` sites.

No test in the repo touches the zone. Searching the whole test tree for `TimeZone.setDefault`,
`user.timezone`, or any Gradle or JUnit timezone configuration returns nothing, and every
`systemDefault()` use in `DateFunctionIntegrationTests` reads it to compute the expected value. So the
tests are self-consistent in whatever zone they run in and would pass unchanged in `UTC`,
`Asia/Tokyo`, or `Asia/Kolkata`. The week-of-month defect in finding 2 is not a special case, it is
the one instance that happened to surface.

Other things the suite cannot currently see, all of them reachable:

- Any instant whose local calendar day differs from its UTC day. The fixtures use `13:45Z`, the same
  day in `America/New_York`; in `Asia/Tokyo` the `23:45Z` row is the next day, so day, week, month and
  year extraction all shift.
- `format` captures the zone at dialect construction while `extract` re-reads it per render, so the
  two can disagree within one query.
- Half-hour and 45-minute offset zones against the minute-level operators.

`DateFunctionZoneIntegrationTests` covers the first two and the week-of-month case. `@Isolated` is the
mechanism, so this needs no new Gradle task or forked JVM.

### `case NATIVE` is unreachable and returns a date

`MongoExtractFunction.java:194` maps `NATIVE` to `$toDate(input)`. `TemporalUnit.NATIVE` means the
platform's native fractional-second resolution, not a date. It is unreachable: `ExtractFunction`
throws `SemanticException("NATIVE is not a legal field for extract()")` and
`SemanticQueryBuilder.visitDatetimeField` has no `native` case. Verified: `extract(native from
t.before)` is an HQL parse error on both the PR and the parent.

### The eight abbreviation functions work and none is tested

HIBERNATE-88's description lists `year()`, `month()`, `day()`, `hour()`, `minute()`, `second()` as
in-scope abbreviations of `extract`. Verified: they desugar to the `extract` descriptor and work for
free off this PR's registration, as do `week()` and `quarter()`.

| HQL | result on 2026-08-24T13:45:21.987Z | emitted |
|---|---|---|
| `year(before)` | 2026 | `$year` |
| `month(before)` | 8 | `$month` |
| `day(before)` | 24 | `$dayOfMonth` |
| `hour(before)` | 9 | `$hour` |
| `minute(before)` | 45 | `$minute` |
| `second(before)` | 21.987 | `$let` with `$second + $millisecond/1000` |
| `week(before)` | 34 | `$week` |
| `quarter(before)` | 3 | `$toInt($ceil($month/3))` |

`week(before)` returning 34 where the ISO week is 35 is blocker 1 reached by a second route. Eight
working entry points named in the ticket, no test on any of them.

### Three week tests pass by coincidence and one tests the wrong thing

- `DateFunctionIntegrationTests.java:371` `testExtractWeekOfMonth` queries `from after` while the
  expected value is computed from `ITEM.before`, and uses `IsoFields.WEEK_OF_WEEK_BASED_YEAR`, the
  ISO week of the year, as the expected week of the month. Two independent mismatches; it passes
  because both sides happen to be 1.
- `:316` and `:343` both expect `ChronoField.ALIGNED_WEEK_OF_YEAR`, which is `(dayOfYear-1)/7+1`,
  neither the ISO week nor the Sunday-based week. For 1970-01-02 all three definitions agree.
- `:344` the comment "Java uses 1-based weeks while Mongo uses 0-based" and the `- 1` applied to the
  expected value encode blocker 1 as intended behaviour.

### One of fifty format replacements is tested

`testFormat` is a `@CsvSource` with a single row, `yyyy-MM-dd HH:mm:ss`. `visitFormat` contains 50
`replace` calls; probing the pattern groups found wrong output in eight of them.

### Negative-test messages carry no ticket

`Unsupported.testExtractTime` and friends assert `"Time unit time not supported"`. `time` and `date`
are things we mean to support, and HIBERNATE-242 ("Support date functions that rely on other
temporal types") is the ticket, so those want `TODO-HIBERNATE-242` plus the browse URL. `offset`,
`timezone_hour`, and `timezone_minute` are permanent, since a BSON date carries no offset, so a bare
message is correct there, but it should say that rather than just "not supported".

## Code quality

- `MongoExtractFunction.java:278`: stray `;` after the `yield` call.
- `MongoExtractFunction.java:52-56`: the class javadoc describes an operator provider used by
  `MongoExpressionPositionalFunction`, `MongoExpressionNamedFunction`, and
  `MongoExpressionUnaryFunction`. It has nothing to do with this class.
- `new AstLiteralExpression(new AstLiteral(new BsonString(ZoneId.systemDefault().getId())))` occurs
  16 times in that one file, 17 in all of `src/main`. A single private helper collapses it and
  removes the indentation cliff at `:153-193` and `:236-266`.
- `MongoExtractFunction.java:211` and `:215`: the `SECOND` case applies `$second` and `$millisecond`
  as bare unary operators, with no `timezone`, unlike every other branch. Second-of-minute is
  timezone-independent for every zone whose offset is a whole number of minutes, which is all of them
  since 1972, so this is not a wrong result for realistic data; it is an unexplained inconsistency
  that reads as an oversight. Same at `:250` in the `WEEK_OF_MONTH` case, where it does change the
  answer (blocker 2).
- The zone is read at two different times: `MongoDialect.java:342` bakes
  `ZoneId.systemDefault()` into the registered `format` function at dialect construction, while
  `MongoExtractFunction` re-reads it on every render. A `TimeZone.setDefault` after boot leaves the
  two disagreeing within one query.
- `FunctionParameterDefinition.divideAndSomethingAsInt`: the name carries no meaning, the javadoc's
  parenthesis is unbalanced, and it sits in a class about parameter definitions with its only caller
  in another file. `$ceil($month/3)` for quarter reads better inline.
- `MongoExpressionNamedFunction:110-111`: `supplementalArguments` seeds the `TreeMap` and then
  `processArguments` puts on top of it, so a declared parameter silently overwrites a supplemental
  argument of the same name. Not reachable today, since `format`'s parameters are `date` and
  `format`, but the constraint is undocumented.
- `DateFunctionIntegrationTests.Item.toString()` labels `before` as `s=` and `after` as `u=`.

## Merge gate

Blockers 1 through 10 are wrong results for reachable HQL, each with a failing test and each
confirmed against PostgreSQL, so they gate merge. Blocker 11 is withdrawn. Within blocker 8, the `yy`
and `z` patterns gate; the fractional-second widths and the offset patterns are real but do not, and
the two lists overlap, since four of them are unblocked by the same `MongoFormatFunction` change.

The test gaps that matter for this PR are the untested pattern letters and the coincidental week
assertions, which are what let blockers 1 through 8 through in the first place.

Follow-up rather than merge-gating: the fractional-second and offset widths, the `MongoFormatFunction`
restructuring they and the throw-by-name behaviour all depend on, the zone-taking overloads, the
`NATIVE` dead branch, the negative-test ticket references, and everything under code quality.
