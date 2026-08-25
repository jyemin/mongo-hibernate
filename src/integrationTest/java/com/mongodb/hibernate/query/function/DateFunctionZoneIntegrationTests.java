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

import com.mongodb.hibernate.junit.MongoExtension;
import com.mongodb.hibernate.query.AbstractQueryIntegrationTests;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.TimeZone;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Zone-varying coverage for the datetime functions.
 *
 * <p>Every other date test derives its expected value from {@link ZoneId#systemDefault()}, so the suite is
 * self-consistent in whatever zone it happens to run in and blind to the whole zone axis. There is no way to name a
 * zone from HQL, so the only lever is the JVM default, which makes this class the counterpart of the mongod-global
 * fail-point tests: it mutates process-global state and therefore must not run beside anything else.
 *
 * <p>{@code Asia/Tokyo} is chosen because its offset is positive, which is what exposes operators that evaluate a
 * {@code $dateTrunc} result in UTC: local midnight on the first of a month is the previous day in UTC.
 */
@SessionFactory(exportSchema = false)
@DomainModel(annotatedClasses = {DateFunctionZoneIntegrationTests.Item.class})
@ExtendWith(MongoExtension.class)
// Replaces the JVM-global default time zone, which every datetime function reads, so it must not run concurrently with
// any other test.
@Isolated
class DateFunctionZoneIntegrationTests extends AbstractQueryIntegrationTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private static final TimeZone ORIGINAL = TimeZone.getDefault();

    static {
        // Set at class-load time rather than in @BeforeAll, because the zone MongoDialect bakes into the `format`
        // function is captured when the dialect is constructed, i.e. when the SessionFactory is built.
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
    }

    @AfterAll
    static void restoreDefaultZone() {
        TimeZone.setDefault(ORIGINAL);
    }

    /** 21:00 in Tokyo, so the local calendar day matches the UTC day and only the month-start logic varies. */
    private static Instant noonUtcOn(String isoDate) {
        return Instant.parse(isoDate + "T12:00:00Z");
    }

    /** Mid-month instants whose months begin on different weekdays in Tokyo. */
    private static final List<String> DATES =
            List.of("2026-02-15", "2026-03-15", "2026-05-15", "2026-08-15", "2026-11-15");

    @BeforeEach
    void beforeEach() {
        getSessionFactoryScope().inTransaction(session -> {
            for (var i = 0; i < DATES.size(); i++) {
                session.persist(new Item(i + 1, noonUtcOn(DATES.get(i))));
            }
        });
    }

    private <T> T selectOne(String hql, Class<T> resultType, int id) {
        return getSessionFactoryScope().fromTransaction(session -> session.createSelectionQuery(
                        hql + " from Item i where i.id = " + id, resultType)
                .getSingleResult());
    }

    private static int idOf(String isoDate) {
        return DATES.indexOf(isoDate) + 1;
    }

    // The zone the functions evaluate in has to be the one in force, otherwise nothing below means anything.
    @Test
    void testExtractUsesTheDefaultZone() {
        assertThat(ZoneId.systemDefault()).isEqualTo(ZONE);
        assertThat(selectOne("select extract(hour from i.before)", Integer.class, 1))
                .isEqualTo(noonUtcOn(DATES.get(0)).atZone(ZONE).getHour());
    }

    // format captures the zone when the dialect is built, extract re-reads it on every render. If those two ever
    // disagree the same query can mix zones.
    @Test
    void testFormatUsesTheSameZoneAsExtract() {
        assertThat(selectOne("select format(i.before as 'HH')", String.class, 1))
                .isEqualTo("%02d".formatted(selectOne("select extract(hour from i.before)", Integer.class, 1)));
    }

    // Finding 2's second defect. The outer $week evaluates the $dateTrunc result in UTC while the inner one uses the
    // zone, so in a positive-offset zone the month start can land in the previous UTC week. That error opposes the
    // off-by-one, so across months the total error is not constant: whether the answer is wrong depends on the input.
    @ParameterizedTest
    @ValueSource(strings = {"2026-02-15", "2026-03-15", "2026-05-15", "2026-08-15", "2026-11-15"})
    void testExtractWeekOfMonth(String isoDate) {
        var zoned = noonUtcOn(isoDate).atZone(ZONE);
        var sundayDayOfWeek = (zoned.get(ChronoField.DAY_OF_WEEK) % 7) + 1;
        var expected = (int) Math.ceil((zoned.getDayOfMonth() - sundayDayOfWeek) / 7.0 + 1);
        assertThat(selectOne("select extract(week of month from i.before)", Integer.class, idOf(isoDate)))
                .isEqualTo(expected);
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
