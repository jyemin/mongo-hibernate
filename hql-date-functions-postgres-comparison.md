# HQL datetime functions: PostgreSQL against the MongoDB extension

A measured, side-by-side comparison of what Hibernate's `extract` and `format` functions return when
the same HQL runs against PostgreSQL versus against the MongoDB extension for Hibernate ORM.

The purpose is to establish, empirically rather than by reading Javadoc or source code, what each HQL datetime
construct is supposed to evaluate to. PostgreSQL is used as the reference because it implements the
same Hibernate contract through a mature dialect, so where the two disagree, the PostgreSQL answer is
the one an application already relies on and is also more likely to be correct.

## Method

Both sides run Hibernate ORM 7.4.5.Final over identical entities, identical HQL strings and identical
stored instants.

| | reference | subject |
|---|---|---|
| dialect | `org.hibernate.dialect.PostgreSQLDialect` | `com.mongodb.hibernate.dialect.MongoDialect` |
| server | PostgreSQL 17.10 | MongoDB 8.0.24, single-node replica set |
| extension commit | n/a | `dae21175` |
| column type | `timestamp(6) with time zone` | BSON `Date` |

The entity has a single `java.time.Instant` attribute. Both sides therefore store an absolute point in
time with no zone or offset retained, which is what makes the comparison meaningful: any difference in
result is a difference in how the function is evaluated, not in what was stored.

On precision: BSON `Date` resolves to milliseconds, so `timestamp(3)` mirrors the storage and is the
better choice of the two; `timestamp(6)` was used here only because `MongoDialect` does not override
`getDefaultTimestampPrecision()` and so currently inherits Hibernate's default of six microsecond
digits, which is expected to change to three. It makes no difference to any measurement below. Every
instant carries at most milliseconds, and `to_char` pads from the stored value rather than from the
declared precision, so the two column types were compared directly and agree.

Every run is repeated under two JVM default zones, `America/New_York` and `Asia/Tokyo`, set with
`-Duser.timezone`. This matters on both sides. PostgreSQL's JDBC driver takes its session `TimeZone`
from the JVM default, and the MongoDB extension passes `ZoneId.systemDefault()` into each aggregation
operator as its `timezone` argument. So both evaluate in the JVM's zone, and both were exercised in
each of the two.

Fifteen instants were used, chosen to separate the several competing definitions of "week" and to put
month boundaries on different weekdays:

```
1970-01-02T10:17:36.789Z   2026-08-24T13:45:21.987Z   2026-01-01T12:00:00Z
2021-01-01T12:00:00Z       2023-12-31T12:00:00Z       1969-12-31T23:59:59.500Z
2026-08-24T23:45:21.987Z   2026-06-14T12:00:00Z       2021-06-15T12:00:00Z
2023-06-18T12:00:00Z       2026-02-15T12:00:00Z       2026-03-15T12:00:00Z
2026-05-15T12:00:00Z       2026-08-15T12:00:00Z       2026-11-15T12:00:00Z
```

Only one of the fifteen answers differently between the two zones,
`1969-12-31T23:59:59.500Z`, which is 1970-01-01 in Tokyo.

### Reading the tables

The instant column is the stored `Instant`, always UTC. The local column is that same instant rendered
in the zone the run used, which is what these functions actually operate on: the week rules key off
local day-of-month, day-of-year and weekday, and the format patterns off local clock fields. Where the
local column is absent, every row in that table shares one instant, `2026-08-24T13:45:21.987Z`, which
is 2026-08-24 Mon 09:45 EDT.

## Where the two agree

Identical results observed for:

- `extract` of `year`, `month`, `day`, `hour`, `minute`, `quarter`, `nanosecond`, `day of week`,
  `day of month`, `day of year`.
- `extract(second from x)` including its fractional part: `21.987` and `59.5` on both. Hibernate
  documents this unit as a floating point value including fractional seconds, and both respect that.
- The `MMM`, `MMMM`, `e`, `GG`, `YYYY`, `D`, `DDD`, `HH`, `ZZZ` and `yyyy` format patterns.
- The `SSS` format pattern, `987` on both.

`S` and `SS` also return the same thing on both, `987`, but agreeing rather than being right; both are
wrong against the pattern's meaning, so they are in section J rather than here.

## Where they differ

### A. `week`

HQL `extract(week from x)`, and identically the `week(x)` abbreviation. Zone `America/New_York`.

| instant | local | PostgreSQL | MongoDB extension |
|---|---|---|---|
| `1970-01-02T10:17:36.789Z` | 1970-01-02 Fri | 1 | 0 |
| `2026-08-24T13:45:21.987Z` | 2026-08-24 Mon | 35 | 34 |
| `2026-01-01T12:00:00Z` | 2026-01-01 Thu | 1 | 0 |
| `2021-01-01T12:00:00Z` | 2021-01-01 Fri | 53 | 0 |
| `2023-12-31T12:00:00Z` | 2023-12-31 Sun | 52 | 53 |
| `1969-12-31T23:59:59.500Z` | 1969-12-31 Wed | 1 | 52 |
| `2026-08-24T23:45:21.987Z` | 2026-08-24 Mon | 35 | 34 |
| `2023-06-18T12:00:00Z` | 2023-06-18 Sun | 24 | 25 |

`TemporalUnit.WEEK` is documented as the ISO-8601 week number, and the PostgreSQL column is exactly
that: weeks run Monday to Sunday, week 1 holds the year's first Thursday, and a week may belong to a
different ISO year than its calendar year, which is why 2021-01-01 is week 53 and 1969-12-31 is week 1.

The extension emits `$week`, which is MongoDB's own Sunday-based zero-origin week. The two differ in
three independent ways, so the discrepancy is not a constant: `$week` counts from 0, it rolls over on
Sunday rather than Monday, and it has no notion of an ISO week-year. MongoDB's `$isoWeek` reproduces
the PostgreSQL column on all eight rows.

### B. `week of year`

HQL `extract(week of year from x)`. Zone `America/New_York`. Differing rows only; four of the fifteen
instants agree, on dates where the two definitions coincide.

| instant | local | PostgreSQL | MongoDB extension |
|---|---|---|---|
| `2021-01-01T12:00:00Z` | 2021-01-01 Fri | 1 | 53 |
| `2023-12-31T12:00:00Z` | 2023-12-31 Sun | 53 | 52 |
| `1969-12-31T23:59:59.500Z` | 1969-12-31 Wed | 53 | 1 |
| `2026-06-14T12:00:00Z` | 2026-06-14 Sun | 25 | 24 |
| `2021-06-15T12:00:00Z` | 2021-06-15 Tue | 25 | 24 |
| `2023-06-18T12:00:00Z` | 2023-06-18 Sun | 25 | 24 |

`TemporalUnit.WEEK_OF_YEAR` is documented as a distinct unit from `WEEK`: the first day of the year is
in week 1, and a new week starts each Sunday. Equivalently it is one plus the number of Sundays since
1 January, and Hibernate's `ExtractFunction` computes it as
`ceiling((dayOfYear - dayOfWeek)/7.0 + 1)` with Sunday as day 1. The PostgreSQL column matches that
formula on every instant.

The extension emits `$isoWeek` here, and `$week` for `WEEK`, so the two units are given each other's
operator. The last three rows are mid-year and away from any boundary, included because the two rules
roll over on different weekdays and therefore diverge all year rather than only in January: on every
Sunday in most years, and on every day of a year beginning on a Friday.

Note for anyone mapping this unit onto a MongoDB operator: `$week + 1` is not equivalent. It agrees
with the formula in years whose 1 January is not a Sunday and disagrees on every day of a year whose
1 January is a Sunday, because in that case there is no `$week` 0 and `$week` already begins at 1.
Sweeping day by day, `$week + 1` differs from the formula on 0 of 365 days in 2021, 2022, 2025, 2026
and 2027, and on 365 of 365 in 2023. `$ceil(($dayOfYear - $dayOfWeek)/7 + 1)` matches on all 3652 days
of 1969, 1970 and 2021 through 2028.

### C. `week of month`

HQL `extract(week of month from x)`. The extension emits
`$week(t) - $week($dateTrunc(t, month))`, passing the timezone to the inner `$week` and to
`$dateTrunc` but not to the outer `$week`.

Zone `America/New_York`, where all ten differ:

| instant | local | PostgreSQL | MongoDB extension |
|---|---|---|---|
| `1970-01-02T10:17:36.789Z` | 1970-01-02 Fri | 1 | 0 |
| `2026-08-24T13:45:21.987Z` | 2026-08-24 Mon | 5 | 4 |
| `2026-01-01T12:00:00Z` | 2026-01-01 Thu | 1 | 0 |
| `2021-01-01T12:00:00Z` | 2021-01-01 Fri | 1 | 0 |
| `2023-12-31T12:00:00Z` | 2023-12-31 Sun | 6 | 5 |
| `1969-12-31T23:59:59.500Z` | 1969-12-31 Wed | 5 | 4 |
| `2026-08-24T23:45:21.987Z` | 2026-08-24 Mon | 5 | 4 |
| `2026-06-14T12:00:00Z` | 2026-06-14 Sun | 3 | 2 |
| `2021-06-15T12:00:00Z` | 2021-06-15 Tue | 3 | 2 |
| `2023-06-18T12:00:00Z` | 2023-06-18 Sun | 4 | 3 |

Zone `Asia/Tokyo`, where three of five agree:

| instant | local | 1st of month | PostgreSQL | MongoDB extension | |
|---|---|---|---|---|---|
| `2026-02-15T12:00:00Z` | 2026-02-15 Sun | Sunday | 3 | 3 | agree |
| `2026-03-15T12:00:00Z` | 2026-03-15 Sun | Sunday | 3 | 3 | agree |
| `2026-11-15T12:00:00Z` | 2026-11-15 Sun | Sunday | 3 | 3 | agree |
| `2026-05-15T12:00:00Z` | 2026-05-15 Fri | Friday | 3 | 2 | differ |
| `2026-08-15T12:00:00Z` | 2026-08-15 Sat | Saturday | 3 | 2 | differ |

`WEEK_OF_MONTH` follows the same Sunday-based rule as `WEEK_OF_YEAR` over the day of the month, so it
is one-origin, and the PostgreSQL column matches `ceiling((dayOfMonth - dayOfWeek)/7.0 + 1)`.

The zone-dependent pattern indicates two separate effects rather than one. A difference of two `$week`
values counts boundaries crossed, which is zero-origin where the unit is one-origin. Separately, the
outer `$week` receives the `$dateTrunc` result with no timezone, so it evaluates the month start in
UTC; in a positive-offset zone local midnight is the previous UTC day, and when the 1st falls on a
Sunday that lands in the previous `$week`, lowering the subtrahend by one and cancelling the
zero-origin error exactly. Hence agreement in Tokyo precisely for months beginning on a Sunday.

`$ceil(($dayOfMonth - $dayOfWeek)/7 + 1)` matches the formula on all 3652 days swept, and involves no
`$dateTrunc`, so it is unaffected by the zone question.

### D. Format patterns MQL has no specifier for

Zone `America/New_York`.

| HQL | instant | local | PostgreSQL | MongoDB extension |
|---|---|---|---|---|
| `format(x as 'hh:mm a')` | `2026-08-24T23:45:21.987Z` | 2026-08-24 Mon 19:45 EDT | `07:45 PM` | `19:45 ` |
| `format(x as 'h a')` | `2026-08-24T23:45:21.987Z` | 2026-08-24 Mon 19:45 EDT | `7 PM` | `19 ` |
| `format(x as 'EEEE')` | `2026-08-24T13:45:21.987Z` | 2026-08-24 Mon 09:45 EDT | `Monday` | `1` |
| `format(x as 'EEE')` | `2026-08-24T13:45:21.987Z` | 2026-08-24 Mon 09:45 EDT | `Mon` | `1` |
| `format(x as 'yy')` | `2026-08-24T13:45:21.987Z` | 2026-08-24 Mon 09:45 EDT | `26` | `2026` |
| `format(x as 'z')` | `2026-08-24T13:45:21.987Z` | 2026-08-24 Mon 09:45 EDT | `EDT` | `-0400` |

MongoDB's `$dateToString` has no specifier for any of these. Probing the server directly, `%I` and
`%p` for the 12-hour clock and meridiem, `%a` and `%A` for day names, and `%y` for a two-digit year
are all rejected with `Invalid format character`, and `%Z` is a count of minutes offset rather than a
zone name.

The extension substitutes the nearest available specifier instead of reporting the limitation, so `hh`
and `HH` emit byte-identical MQL, `EEEE` and `EEE` both become `%u` (the ISO day number), `yy` becomes
`%Y`, and `z` becomes `%z`. The `a` pattern is mapped to the empty string, which also strands the
separator whitespace around it, visible as the trailing space in `19:45 `.

### E. Format pattern emitted as literal text

| HQL | PostgreSQL | MongoDB extension |
|---|---|---|
| `format(x as 'W')` | `4` | `W` |

`W` is the week of the month. The extension's mapping for it is a no-op, so the letter survives into
the format string, where every non-`%` character is literal, and the output is the pattern character
itself. Neither a value nor an error.

### F. Format pattern where MQL has the right specifier but a different one is used

| HQL | PostgreSQL | MongoDB extension |
|---|---|---|
| `format(x as 'w')` | `35` | `34` |
| `format(x as 'ww')` | `35` | `34` |

`w` and `ww` are the ISO week. The extension maps them to `%U`, the Sunday-based week. MongoDB does
support `%V`, the ISO week, so unlike the patterns in section D this one is expressible.

### G. Quoted literal text in a format pattern

| HQL | PostgreSQL | MongoDB extension |
|---|---|---|
| `format(x as 'd MMMM yyyy ''at'' HH:mm')` | `24 August 2026 at 09:45` | `24 August 2026 "at" 09:45` |
| `format(x as '''T''')` | `T` | `"T"` |

In an HQL string literal `''` is one apostrophe, so both patterns contain a quoted chunk that should
appear verbatim. Hibernate's `Replacer` takes a delimiter with which to wrap such chunks, and the
extension passes `"`, which is the literal-quoting character in Oracle's format model. MQL has no
quoting mechanism, since non-`%` characters are already literal, so the delimiter is emitted as
content.

### H. Two-argument `format`, which neither side translates

| HQL | PostgreSQL | MongoDB extension |
|---|---|---|
| `format(x, 'yyyy-MM-dd')` | `2026-08-24` | `yyyy-MM-dd` |
| `format(x as 'yyyy-MM-dd')` | `2026-08-24` | `2026-08-24` |
| `format(x, 'HH:mm')` | `09:08` | `HH:mm` |
| `format(x as 'HH:mm')` | `09:45` | `09:45` |
| `format(x, 'EEEE')` | `EEEE` | `EEEE` |
| `format(x as 'EEEE')` | `Monday` | `1` |

Both spellings exist and both execute; what differs is whether the pattern is translated.
`FormatFunction.render` does not inspect its second argument, it calls `format.accept(walker)` and lets
dispatch decide. The `as` grammar rule produces a `Format` node, so `accept` reaches the dialect's
pattern translation. The two-argument spelling produces an ordinary `QueryLiteral`, so `accept` reaches
literal rendering and the pattern is emitted verbatim. Translation is attached to the node type, not to
the function, on every dialect.

PostgreSQL is therefore not the reference for this row, and the first pair is a trap: `yyyy-MM-dd`
happens to be a valid `to_char` template as well as a valid HQL pattern, so passing it through
untranslated gives the right answer by luck. The second pair shows the untranslated reality, `mm`
meaning month in `to_char`, so `HH:mm` yields hour and month. The third leaves the pattern letters as
literal text. On the extension nothing in the HQL pattern language coincides with `$dateToString`, so
the pattern always emerges verbatim, which is arguably the less dangerous failure: `yyyy-MM-dd` coming
back as itself is obviously broken, whereas `09:08` looks like a time.

The two-argument form is thus not a distinct feature that one side implements and the other does not.
It is the same untranslated path on both, and the extension is no worse behaved than the reference.

What the extension could do, and no dialect does, is translate a literal pattern in this position: the
string is available at translation time, so the same translation the `as` form uses would apply. That
would add no capability, because the documented spelling already covers literals and covers nothing
else, its grammar rule being a single `STRING_LITERAL` token such that both `format(x as :p)` and
`format(x as 'yy'||'yy')` are syntax errors. A non-literal pattern, `format(x, :p)`, cannot be
translated by anyone, since the value is unknown until execution and neither `to_char` nor
`$dateToString` translates HQL patterns at runtime.

So the options are to leave it matching the reference, or to refuse the spelling so a caller sees an
error rather than a pattern echoed back. The second is an improvement over Hibernate rather than a
defect being corrected.

### I. Offset patterns `x`, `xx` and `xxx`

Zone `America/New_York`, August, so the offset in force is -04:00.

| HQL | required | PostgreSQL | MongoDB extension |
|---|---|---|---|
| `format(x as 'x')` | `-04` | `-04` | `-0400` |
| `format(x as 'xx')` | `-0400` | `-04` | `-0400` |
| `format(x as 'xxx')` | `-04:00` | `-04` | `-0400` |

`x` is the numeric UTC offset, and the letter count selects the width only, not the field. Hibernate
states the three widths in `FormatFunction`: "`xxx` stands for the full offset i.e. `+01:00`", "`x`
patterns, which require `+01`", "`xx` patterns, which require `+0100`". That matches
`DateTimeFormatter`, and it is the required column above.

Neither implementation honours all three, and PostgreSQL is not a usable reference for this row.
`PostgreSQLDialect` maps all three widths onto one offset template that varies by shape rather than by
width: in `Asia/Kolkata` it returns `+05:30` for `x`, `xx` and `xxx` alike, where `DateTimeFormatter`
requires `+0530`, `+0530` and `+05:30`. So it is right for `x` only, and only in whole-hour zones.

The extension maps all three onto `%z`, which is the `xx` shape, so it is right for `xx` and wrong for
`x` and `xxx`. Both sides collapse three widths into one rendering; they differ in which one they pick.

DST is handled correctly on both sides and is not implicated: the same query in January returns
`-0500` and `-05:00` respectively, so the defect is the rendered width, not the offset itself.

### J. Fractional-second widths

| HQL | required | PostgreSQL | MongoDB extension |
|---|---|---|---|
| `format(x as 'S')` | `9` | `987` | `987` |
| `format(x as 'SS')` | `98` | `987` | `987` |
| `format(x as 'SSS')` | `987` | `987` | `987` |
| `format(x as 'SSSS')` | `9870` | `987000` | `987` |
| `format(x as 'SSSSS')` | `98700` | `987000` | `987` |
| `format(x as 'SSSSSS')` | `987000` | `987000` | `987` |

The required column is `DateTimeFormatter` again: the letter count is the number of fractional digits.

Neither implementation respects the width. PostgreSQL's formatter exposes two fractional widths, three
digits and six, so `PostgreSQLDialect` maps `S`, `SS` and `SSS` onto the three-digit one and `SSSS`
through `SSSSSS` onto the six-digit one; it cannot tell `SSSS` from `SSSSSS`. The extension collapses
all six onto `%L`, which is always three digits. Two buckets against one, and only `SSS` is right on
both.

The zeros past the third digit carry no precision, since BSON `Date` resolves to milliseconds, and
PostgreSQL's `987000` is padding rather than measurement. The width is still part of what the pattern
asks for, and every other dialect emits it, so a caller writing `HH:mm:ss.SSSSSS` gets six digits
everywhere except here. Nor does the declared column precision enter into it: asking PostgreSQL
directly for one, three, four and six digits returns `9`, `987`, `9870` and `987000`, identical against
a `timestamp(3)` column and a `timestamp(6)` one, because the formatter pads from the stored value.

### K. `extract(date from x)`

| HQL | PostgreSQL | MongoDB extension |
|---|---|---|
| `extract(date from x)` | `2026-08-24` | `JDBCException: Could not extract column [1] from JDBC ResultSet [getDate not implemented]` |

`extract(date from x)` is legitimate HQL and PostgreSQL returns a `LocalDate`. On the extension the
translation itself succeeds and the server accepts the emitted `$dateTrunc` pipeline; the failure is
one layer up, in the JDBC adapter's `getDate`, which is an unimplemented default. The `DATE` case in
`MongoExtractFunction` should likely just be removed, which will then throw
`FeatureNotSupportedException` from the `default` case.

## A possible fix for the format patterns

The format differences above split cleanly by how much machinery each needs, and the split does not
follow the categories.

Four patterns are a plain mapping change, because the correction is literal text that `$dateToString`
passes through unaltered:

| pattern | required | mapping | verified output |
|---|---|---|---|
| `SSSS` | `9870` | `%L0` | `9870` |
| `SSSSS` | `98700` | `%L00` | `98700` |
| `SSSSSS` | `987000` | `%L000` | `987000` |
| `w`, `ww` | ISO week | `%V` | `35` |

Embedding works: `HH:mm:ss.SSSSSS` becomes `%H:%M:%S.%L000` and yields `13:45:21.987000`.

Four more cannot be done inside a format string at all, because the correction has to modify what a
specifier produced rather than sit beside it. `S` and `SS` need `%L` truncated, and `x` and `xxx` need
a colon inserted into, or minutes stripped from, what `%z` produced:

```
S    ->  $substrBytes[ {$dateToString: %L}, 0, 1 ]
SS   ->  $substrBytes[ {$dateToString: %L}, 0, 2 ]
xxx  ->  $let z = {$dateToString: %z}
           in $concat[ $substrBytes[$$z,0,3], ":", $substrBytes[$$z,3,2] ]
x    ->  $let z = {$dateToString: %z}
           in $cond[ $eq[$substrBytes[$$z,3,2], "00"], $substrBytes[$$z,0,3], $$z ]
```

All four are verified to match `DateTimeFormatter`, including the cases that catch a naive
implementation: `%L` is zero-padded to three digits, so truncating it gives `05` for 50 milliseconds,
whereas dividing `$millisecond` and stringifying gives `5`. Offsets follow DST correctly, and
`Asia/Kolkata` keeps its minutes at every width.

What these four require is that a formatted value stop being one `$dateToString` call and become a
`$concat` of segments, with expressions spliced between the literal runs. That cannot be expressed by
`MongoExpressionNamedFunction`, whose purpose is to emit a single named operator with named arguments,
and it cannot be done in `AbstractMqlTranslator.visitFormat` either, which receives only the pattern
node and so has access to neither the temporal operand nor the timezone; it can only return a string.

The natural home is a dedicated `MongoFormatFunction`, since a function descriptor is the only
component that sees the temporal operand, the pattern and the timezone together. What it should not do
is take over parsing the pattern.

Splitting the responsibility keeps the pattern language in the translator, where the rest of
translation lives:

- `visitFormat` parses the pattern and yields the segments, under a new descriptor such as
  `AstVisitorValueDescriptor<List<AstFormatSegment>> FORMAT_SEGMENTS`, each segment being either a
  translated `$dateToString` format string or a marker for one of the four patterns that need an
  expression. Yielding something other than an expression is already the established pattern:
  `AstVisitorValueDescriptor` is generic, and existing descriptors yield `String`,
  `List<AstSortField>` and `List<AstProjectStageSpecification>`.
- `MongoFormatFunction` calls `acceptAndYield(formatNode, FORMAT_SEGMENTS)`, resolves the operand, and
  assembles the `$concat`, splicing in the offset and fraction expressions.

This is the first component able to refuse a pattern by name. The patterns MQL cannot
express under any amount of concatenation, because no rearrangement of the available specifiers
invents a day name, a zone abbreviation, a two-digit year, a meridiem marker or a week of month, are
`EEEE`, `EEE`, `z`, `yy`, `a` and `W`. Whichever component holds the whole pattern can name the
offending letter in the exception instead of silently substituting the nearest specifier, which is
what produces the wrong answers in sections D and E.
