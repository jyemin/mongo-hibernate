/*
 * Copyright 2026-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hibernate.query.function;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.MongoExtension;
import com.mongodb.hibernate.query.AbstractQueryIntegrationTests;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Failing tests for the findings in the PR #209 review.
 *
 * <p>Each expected value here was cross-checked against Hibernate running the same HQL against PostgreSQL 17.10, with
 * the same instants and the same JVM default zone.
 *
 * <p>Where PostgreSQL disagreed with the review's original claim, the claim was withdrawn rather than asserted here;
 * {@code extract(epoch from x)} before 1970 is the one such case, and it has no test.
 */
@SessionFactory(exportSchema = false)
@DomainModel(annotatedClasses = {DateFunctionReviewFindingsIntegrationTests.Item.class})
@ExtendWith(MongoExtension.class)
class DateFunctionReviewFindingsIntegrationTests extends AbstractQueryIntegrationTests {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * Chosen to separate the ISO week, the Sunday-based week of year, and the week of month.
     *
     * <p>The first seven sit at or near year boundaries, or mid-year on a Monday. The last three are mid-year and away
     * from any boundary, because the two week-of-year definitions roll over on different weekdays, Sunday against
     * Monday, and so diverge all year rather than only in January: on every Sunday in most years, and on every single
     * day of a year that begins on a Friday. Appended rather than inserted, so {@link #MORNING} and {@link #EVENING}
     * keep pointing at the instants they name.
     */
    private static final List<Instant> DATES = List.of(
            Instant.parse("1970-01-02T10:17:36.789Z"),
            Instant.parse("2026-08-24T13:45:21.987Z"),
            Instant.parse("2026-01-01T12:00:00.000Z"),
            Instant.parse("2021-01-01T12:00:00.000Z"),
            Instant.parse("2023-12-31T12:00:00.000Z"),
            Instant.parse("1969-12-31T23:59:59.500Z"),
            Instant.parse("2026-08-24T23:45:21.987Z"),
            // A mid-year Sunday, the day the Sunday-based count rolls over and the ISO count has not.
            Instant.parse("2026-06-14T12:00:00.000Z"),
            // Mid-year and not a Sunday, in a year beginning on a Friday, where the two disagree daily.
            Instant.parse("2021-06-15T12:00:00.000Z"),
            // A year beginning on a Sunday, so `$week` has no week 0 and starts at 1 of its own accord.
            Instant.parse("2023-06-18T12:00:00.000Z"));

    /** The 09:45-local row, used by every format test except the ones that need an evening hour. */
    private static final int MORNING = 2;

    /** The 19:45-local row, which is where a 12-hour clock and a 24-hour clock diverge. */
    private static final int EVENING = 7;

    @BeforeEach
    void beforeEach() {
        getSessionFactoryScope().inTransaction(session -> {
            for (var i = 0; i < DATES.size(); i++) {
                session.persist(new Item(i + 1, DATES.get(i)));
            }
        });
    }

    private static List<Integer> ids() {
        return IntStream.rangeClosed(1, DATES.size()).boxed().toList();
    }

    private <T> T selectOne(String hql, Class<T> resultType, int id) {
        return getSessionFactoryScope().fromTransaction(session -> session.createSelectionQuery(
                        hql + " from Item i where i.id = " + id, resultType)
                .getSingleResult());
    }

    private String formatted(String pattern, int id) {
        return selectOne("select format(i.before as '" + pattern + "')", String.class, id);
    }

    /** What {@code format} should produce, per {@link DateTimeFormatter} in the same zone. */
    private static String expectedFormat(String pattern, int id) {
        return DATES.get(id - 1).atZone(ZONE).format(DateTimeFormatter.ofPattern(pattern, Locale.US));
    }

    // Finding 1. TemporalUnit.WEEK is the ISO-8601 week number. PostgreSQL returns the ISO week for
    // every one of these instants; the translator emits $week, MongoDB's 0-based Sunday-week.
    @ParameterizedTest
    @MethodSource("ids")
    void testExtractWeekIsIsoWeek(int id) {
        var expected = DATES.get(id - 1).atZone(ZONE).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        assertThat(selectOne("select extract(week from i.before)", Integer.class, id))
                .isEqualTo(expected);
    }

    // Finding 1, reached through the abbreviation rather than through extract().
    @ParameterizedTest
    @MethodSource("ids")
    void testWeekAbbreviationIsIsoWeek(int id) {
        var expected = DATES.get(id - 1).atZone(ZONE).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        assertThat(selectOne("select week(i.before)", Integer.class, id)).isEqualTo(expected);
    }

    // Finding 1. TemporalUnit.WEEK_OF_YEAR is 1-based with weeks starting Sunday, which is what
    // Hibernate's own ExtractFunction computes as ceiling((dayOfYear - dayOfWeek)/7.0 + 1). The
    // translator emits $isoWeek, so the two units are swapped.
    @ParameterizedTest
    @MethodSource("ids")
    void testExtractWeekOfYearIsSundayBased(int id) {
        assertThat(selectOne("select extract(week of year from i.before)", Integer.class, id))
                .isEqualTo(sundayWeek(id, DATES.get(id - 1).atZone(ZONE).getDayOfYear()));
    }

    // Finding 2. Same rule over the day of the month, so week of month is 1-based too. The
    // translator emits a difference of two $week values, which counts from 0.
    @ParameterizedTest
    @MethodSource("ids")
    void testExtractWeekOfMonthIsOneBased(int id) {
        assertThat(selectOne("select extract(week of month from i.before)", Integer.class, id))
                .isEqualTo(sundayWeek(id, DATES.get(id - 1).atZone(ZONE).getDayOfMonth()));
    }

    /** Hibernate's rule for both Sunday-based week units, verified to match PostgreSQL on every instant above. */
    private static int sundayWeek(int id, int dayOfPeriod) {
        var isoDayOfWeek = DATES.get(id - 1).atZone(ZONE).get(ChronoField.DAY_OF_WEEK);
        var sundayDayOfWeek = (isoDayOfWeek % 7) + 1;
        return (int) Math.ceil((dayOfPeriod - sundayDayOfWeek) / 7.0 + 1);
    }

    // Finding 3. 'hh' is the 12-hour clock and 'a' is the AM/PM marker. PostgreSQL returns
    // "07:45 PM"; the translator maps hh to %H and deletes 'a', giving "19:45 ".
    @Test
    void testFormatTwelveHourClockWithMeridiem() {
        assertThat(formatted("hh:mm a", EVENING)).isEqualTo(expectedFormat("hh:mm a", EVENING));
    }

    @Test
    void testFormatShortTwelveHourClockWithMeridiem() {
        assertThat(formatted("h a", EVENING)).isEqualTo(expectedFormat("h a", EVENING));
    }

    // Finding 4. 'EEEE' is the full day name and 'EEE' the abbreviated one. PostgreSQL returns
    // "Monday" and "Mon"; the translator maps both to %u, the ISO day number.
    @ParameterizedTest
    @CsvSource({"EEEE", "EEE"})
    void testFormatDayName(String pattern) {
        assertThat(formatted(pattern, MORNING)).isEqualTo(expectedFormat(pattern, MORNING));
    }

    // Finding 5. 'W' is the week of the month. PostgreSQL returns a number; the translator maps W to
    // itself, so the letter W reaches the output as literal text.
    @Test
    void testFormatWeekOfMonthIsNotLiteralText() {
        assertThat(formatted("W", MORNING)).isNotEqualTo("W").containsOnlyDigits();
    }

    // Finding 6. 'w' and 'ww' are the ISO week. PostgreSQL returns 35 for this instant; the
    // translator maps them to %U, the Sunday-based week, giving 34.
    @ParameterizedTest
    @CsvSource({"w", "ww"})
    void testFormatWeekIsIsoWeek(String pattern) {
        var expected = DATES.get(MORNING - 1).atZone(ZONE).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        assertThat(formatted(pattern, MORNING)).isEqualTo(String.valueOf(expected));
    }

    // Finding 7. A quoted chunk is literal text. PostgreSQL returns "24 August 2026 at 09:45"; the
    // translator passes "\"" to Replacer as the delimiter, which wraps the chunk in double quotes
    // that $dateToString then emits verbatim.
    @Test
    void testFormatQuotedLiteralHasNoQuotesInOutput() {
        assertThat(formatted("d MMMM yyyy ''at'' HH:mm", MORNING)).doesNotContain("\"");
    }

    @Test
    void testFormatQuotedLiteralAlone() {
        assertThat(formatted("''T''", MORNING)).isEqualTo("T");
    }

    // Finding 8. 'yy' is a two-digit year. PostgreSQL returns "26"; the translator maps it to %Y.
    @Test
    void testFormatTwoDigitYear() {
        assertThat(formatted("yy", MORNING)).isEqualTo(expectedFormat("yy", MORNING));
    }

    // Finding 8. 'z' is the zone name. PostgreSQL returns "EDT"; the translator maps it to %z, the
    // numeric offset.
    @Test
    void testFormatZoneNameIsNotNumericOffset() {
        assertThat(formatted("z", MORNING)).isEqualTo(expectedFormat("z", MORNING));
    }

    // Finding 9. The registered descriptor takes its pattern as a plain STRING parameter, so the
    // two-argument call never reaches visitFormat and the untranslated pattern is handed to
    // $dateToString, which treats every character of it as literal text.
    //
    // Asserted as a refusal. The documented spelling, format(x as 'literal'), takes a string literal
    // only: the grammar rule is a single STRING_LITERAL token, so neither a parameter nor a
    // concatenation parses. The sole thing the two-argument form adds is a non-literal pattern, which
    // cannot be translated because MQL has no runtime pattern translation, so there is nothing to
    // support and nothing lost by rejecting it.
    @Test
    void testFormatTwoArgumentFormIsRefused() {
        assertSelectQueryFailure(
                "select format(i.before, 'yyyy-MM-dd') from Item i where i.id = " + MORNING,
                String.class,
                FeatureNotSupportedException.class,
                "format");
    }

    // Finding 10. extract(date from x) returns a LocalDate, which this extension neither supports nor
    // rejects as a mapped type, so the unit should refuse translation the way `time` already does
    // rather than emit a pipeline whose result cannot be read back. Today the pipeline is emitted and
    // accepted, and the failure surfaces a layer down as
    // SQLFeatureNotSupportedException("getDate not implemented") wrapped in a JDBCException.
    //
    // Asserted as a throw rather than as a LocalDate value on purpose: implementing the JDBC read path
    // would commit the extension to LocalDate as a query result type while it is still unguarded as a
    // mapped type, which belongs with the temporal-type work.
    @Test
    void testExtractDate() {
        assertSelectQueryFailure(
                "select extract(date from i.before) from Item i where i.id = " + MORNING,
                LocalDate.class,
                FeatureNotSupportedException.class,
                "TODO-HIBERNATE-242");
    }

    @Entity(name = "Item")
    @Table(name = "items")
    static class Item {
        @Id
        int id;

        Instant before;

        Item() {}

        Item(int id, Instant before) {
            this.id = id;
            this.before = before;
        }
    }
}
