/*
 * Copyright 2025-present MongoDB, Inc.
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

package com.mongodb.hibernate.type.temporal;

import static com.mongodb.hibernate.MongoTestAssertions.assertEq;
import static com.mongodb.hibernate.type.temporal.TemporalTestSupport.SYSTEM_TIME_ZONE_LOCK;
import static com.mongodb.hibernate.type.temporal.TemporalTestSupport.withSystemTimeZone;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoCollection;
import com.mongodb.hibernate.junit.InjectMongoCollection;
import com.mongodb.hibernate.junit.MongoExtension;
import com.mongodb.hibernate.junit.MongoServiceRegistryProducer;
import jakarta.persistence.EntityManager;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.transaction.TransactionUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Round-trip coverage shared by every temporal type stored as a BSON {@code Date}. A subclass supplies its entity, the
 * values to persist together with the values expected on read, and the document those values are stored as.
 *
 * <p>The session time zone is parameterized because {@code hibernate.jdbc.time_zone} is a no-op for these types: they
 * denote an instant, not a wall clock, so neither it nor the JVM default zone may change what is stored.
 */
@SessionFactory(exportSchema = false)
@ExtendWith(MongoExtension.class)
@ResourceLock(SYSTEM_TIME_ZONE_LOCK)
abstract class AbstractTemporalRoundTripIntegrationTests<I, T>
        implements SessionFactoryScopeAware, MongoServiceRegistryProducer {

    static final String COLLECTION_NAME = "items";

    private static final int ITEM_ID = 1;
    private static final String SYSTEM_ZONE_OFFSET_ID = "+11:13";

    @InjectMongoCollection(COLLECTION_NAME)
    private MongoCollection<BsonDocument> mongoCollection;

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope sessionFactoryScope) {
        this.sessionFactoryScope = sessionFactoryScope;
    }

    abstract Class<I> getItemClass();

    abstract I newItem(int id, T value);

    /** The value stored as {@link #getExpectedStoredDocument()}. */
    abstract T getStoredValue();

    abstract String getExpectedStoredDocument();

    static Stream<Arguments> differentTimeZones() {
        return Stream.of(
                Arguments.of(ZoneId.of("Etc/GMT+1"), ZoneId.of("Etc/UTC")),
                Arguments.of(ZoneId.of("Etc/GMT-1"), ZoneId.of("Etc/GMT+2")));
    }

    @Test
    void testStoredDocument() {
        sessionFactoryScope.inTransaction(session -> session.persist(newItem(ITEM_ID, getStoredValue())));
        assertThat(mongoCollection.find()).containsExactly(BsonDocument.parse(getExpectedStoredDocument()));
    }

    @ParameterizedTest(name = "testRoundTripSessionTimeZonesEqual: Write(sys={0}, sess={1}). Read(sys={0}, sess={1})")
    @MethodSource("persistAndReadParameters")
    void testRoundTripSessionTimeZonesEqual(ZoneId systemTimeZone, ZoneId sessionTimeZone, T toSave, T toRead)
            throws Exception {
        var item = newItem(ITEM_ID, toSave);
        withSystemTimeZone(systemTimeZone, () -> inTransaction(sessionTimeZone, session -> session.persist(item)));
        var loadedItem = withSystemTimeZone(
                systemTimeZone,
                () -> fromTransaction(sessionTimeZone, session -> session.find(getItemClass(), ITEM_ID)));

        assertEq(newItem(ITEM_ID, toRead), loadedItem);
    }

    @ParameterizedTest(
            name = "testRoundTripWriteAndReadPathTimeZonesNotEqual: Write(sys={0}, sess={0}). Read(sys={1}, sess={1})")
    @MethodSource("persistAndReadParameters")
    void testRoundTripWriteAndReadPathTimeZonesNotEqual(
            ZoneId writePathTimeZone, ZoneId readPathTimeZone, T toSave, T toRead) throws Exception {
        var item = newItem(ITEM_ID, toSave);
        withSystemTimeZone(writePathTimeZone, () -> inTransaction(writePathTimeZone, session -> session.persist(item)));
        var loadedItem = withSystemTimeZone(
                readPathTimeZone,
                () -> fromTransaction(readPathTimeZone, session -> session.find(getItemClass(), ITEM_ID)));

        assertEq(newItem(ITEM_ID, toRead), loadedItem);
    }

    @ParameterizedTest(
            name = "testRoundTripSessionTimeZonesNotEqual:" + "Write(sys=" + SYSTEM_ZONE_OFFSET_ID
                    + ", sess={0}). Read(sys=" + SYSTEM_ZONE_OFFSET_ID + ", sess={1})")
    @MethodSource("persistAndReadParameters")
    void testRoundTripSessionTimeZonesNotEqual(
            ZoneId sessionWriteTimeZone, ZoneId sessionReadTimeZone, T toSave, T toRead) throws Exception {
        var systemTimeZone = ZoneId.of(SYSTEM_ZONE_OFFSET_ID);
        var item = newItem(ITEM_ID, toSave);
        withSystemTimeZone(systemTimeZone, () -> inTransaction(sessionWriteTimeZone, session -> session.persist(item)));
        var loadedItem = withSystemTimeZone(
                systemTimeZone,
                () -> fromTransaction(sessionReadTimeZone, session -> session.find(getItemClass(), ITEM_ID)));

        assertEq(newItem(ITEM_ID, toRead), loadedItem);
    }

    private void inTransaction(ZoneId timeZone, Consumer<EntityManager> action) {
        try (var session = sessionFactoryScope
                .getSessionFactory()
                .withOptions()
                .jdbcTimeZone(TimeZone.getTimeZone(timeZone))
                .openSession()) {
            TransactionUtil.inTransaction(session, action);
        }
    }

    private <R> R fromTransaction(ZoneId timeZone, Function<EntityManager, R> action) {
        try (var session = sessionFactoryScope
                .getSessionFactory()
                .withOptions()
                .jdbcTimeZone(TimeZone.getTimeZone(timeZone))
                .openSession()) {
            return TransactionUtil.fromTransaction(session, action);
        }
    }
}
