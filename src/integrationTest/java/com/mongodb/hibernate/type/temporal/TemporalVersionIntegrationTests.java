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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.annotations.TimeZoneStorageType.COLUMN;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.MongoServiceRegistryProducer;
import com.mongodb.hibernate.query.AbstractQueryIntegrationTests;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.boot.MetadataSources;
import org.hibernate.testing.orm.junit.DomainModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A temporal {@code @Version} attribute is rejected at boot: a BSON {@code Date} holds milliseconds, so two updates
 * within one millisecond produce an equal version and the optimistic-lock check on the second cannot fire. A numeric
 * version is unaffected.
 */
@DomainModel(annotatedClasses = TemporalVersionIntegrationTests.LongVersionItem.class)
class TemporalVersionIntegrationTests extends AbstractQueryIntegrationTests {

    @Test
    void testLongVersionIsAccepted() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new LongVersionItem(1, "str")));

        getSessionFactoryScope().inTransaction(session -> {
            var item = session.find(LongVersionItem.class, 1);
            assertThat(item.version).isEqualTo(0L);
            item.string = "str_updated";
        });

        var loadedItem = getSessionFactoryScope().fromTransaction(session -> session.find(LongVersionItem.class, 1));
        assertThat(loadedItem.string).isEqualTo("str_updated");
        assertThat(loadedItem.version).isEqualTo(1L);
    }

    @Nested
    class Unsupported implements MongoServiceRegistryProducer {

        @Test
        void instantVersionRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(InstantVersionItem.class)
                            .buildMetadata())
                    .hasMessageContaining(versionNotSupported(Instant.class));
        }

        @Test
        void offsetDateTimeVersionRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(OffsetDateTimeVersionItem.class)
                            .buildMetadata())
                    .hasMessageContaining(versionNotSupported(OffsetDateTime.class));
        }

        @Test
        void zonedDateTimeVersionRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(ZonedDateTimeVersionItem.class)
                            .buildMetadata())
                    .hasMessageContaining(versionNotSupported(ZonedDateTime.class));
        }

        /**
         * Hibernate ORM resolves the version mapping before this project's contributor runs, and asserts on it, so with
         * assertions enabled - which Gradle does - its bare {@link AssertionError} is what surfaces. The check this
         * project adds is what rejects the mapping when assertions are off.
         */
        @Test
        void columnStoredVersionRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(ColumnVersionItem.class)
                            .buildMetadata())
                    .satisfiesAnyOf(
                            failure -> assertThat(failure)
                                    .isInstanceOf(FeatureNotSupportedException.class)
                                    .hasMessageContaining(versionNotSupported(OffsetDateTime.class)),
                            failure -> assertThat(failure)
                                    .isInstanceOf(AssertionError.class)
                                    .hasStackTraceContaining("VersionResolution.resolve"));
        }

        private String versionNotSupported(Class<?> type) {
            return "the version attribute [version] has type [%s] that is not supported".formatted(type.getTypeName());
        }

        @Entity(name = "InstantVersionItem")
        @Table(name = "instantVersionItems")
        static class InstantVersionItem {
            @Id
            int id;

            @Version
            Instant version;
        }

        @Entity(name = "OffsetDateTimeVersionItem")
        @Table(name = "offsetDateTimeVersionItems")
        static class OffsetDateTimeVersionItem {
            @Id
            int id;

            @Version
            OffsetDateTime version;
        }

        @Entity(name = "ZonedDateTimeVersionItem")
        @Table(name = "zonedDateTimeVersionItems")
        static class ZonedDateTimeVersionItem {
            @Id
            int id;

            @Version
            ZonedDateTime version;
        }

        @Entity(name = "ColumnVersionItem")
        @Table(name = "columnVersionItems")
        static class ColumnVersionItem {
            @Id
            int id;

            @Version
            @TimeZoneStorage(COLUMN)
            OffsetDateTime version;
        }
    }

    @Entity(name = "LongVersionItem")
    @Table(name = "longVersionItems")
    static class LongVersionItem {
        @Id
        int id;

        @Version
        long version;

        String string;

        LongVersionItem() {}

        LongVersionItem(int id, String string) {
            this.id = id;
            this.string = string;
        }
    }
}
