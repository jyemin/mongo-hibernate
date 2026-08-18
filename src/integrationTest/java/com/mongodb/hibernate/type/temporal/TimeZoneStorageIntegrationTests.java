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

import static com.mongodb.hibernate.MongoTestAssertions.assertIterableEq;
import static com.mongodb.hibernate.type.temporal.TemporalTestSupport.SYSTEM_TIME_ZONE_LOCK;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.annotations.TimeZoneStorageType.COLUMN;
import static org.hibernate.annotations.TimeZoneStorageType.NATIVE;
import static org.hibernate.annotations.TimeZoneStorageType.NORMALIZE;
import static org.hibernate.annotations.TimeZoneStorageType.NORMALIZE_UTC;
import static org.hibernate.cfg.AvailableSettings.DIALECT;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JDBC_URL;
import static org.hibernate.cfg.AvailableSettings.TIMEZONE_DEFAULT_STORAGE;

import com.mongodb.client.MongoCollection;
import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.InjectMongoCollection;
import com.mongodb.hibernate.junit.MongoServiceRegistryProducer;
import com.mongodb.hibernate.query.AbstractQueryIntegrationTests;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.hibernate.annotations.Struct;
import org.hibernate.annotations.TimeZoneColumn;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@link TimeZoneStorage} strategies for {@link OffsetDateTime} and {@link ZonedDateTime}. Under {@code COLUMN} the
 * instant and the offset are stored in two fields, so the offset survives the round trip; under the default
 * {@code NORMALIZE_UTC} only the instant is stored. {@code NATIVE} and {@code NORMALIZE} are rejected at boot.
 */
@ResourceLock(SYSTEM_TIME_ZONE_LOCK)
@DomainModel(
        annotatedClasses = {
            TimeZoneStorageIntegrationTests.OffsetColumnItem.class,
            TimeZoneStorageIntegrationTests.ZonedColumnItem.class,
            TimeZoneStorageIntegrationTests.RenamedColumnItem.class,
            TimeZoneStorageIntegrationTests.StructColumnItem.class,
            TimeZoneStorageIntegrationTests.RenamedStructColumnItem.class,
            TimeZoneStorageIntegrationTests.SharedNameStructColumnItem.class,
            TimeZoneStorageIntegrationTests.BareStorageItem.class
        })
class TimeZoneStorageIntegrationTests extends AbstractQueryIntegrationTests {

    static final String OFFSET_COLUMN_COLLECTION_NAME = "offsetColumnItems";
    static final String ZONED_COLUMN_COLLECTION_NAME = "zonedColumnItems";
    static final String RENAMED_COLUMN_COLLECTION_NAME = "renamedColumnItems";
    static final String STRUCT_COLUMN_COLLECTION_NAME = "structColumnItems";
    static final String AUTO_STORAGE_COLLECTION_NAME = "autoStorageItems";
    static final String RENAMED_STRUCT_COLUMN_COLLECTION_NAME = "renamedStructColumnItems";
    static final String SHARED_NAME_STRUCT_COLUMN_COLLECTION_NAME = "sharedNameStructColumnItems";
    static final String BARE_STORAGE_COLLECTION_NAME = "bareStorageItems";

    /** The reference instant, expressed at the offset the {@code COLUMN} strategy stores alongside it. */
    private static final OffsetDateTime PERSISTED = OffsetDateTime.parse("2026-08-09T10:15:30.0029+02:00");

    private static final OffsetDateTime READ_BACK = OffsetDateTime.parse("2026-08-09T10:15:30.003+02:00");

    private static final List<Instant> INSTANTS =
            List.of(Instant.parse("2026-08-09T08:15:30.003Z"), Instant.parse("2026-01-01T00:00:00Z"));

    private static final String NATIVE_NOT_SUPPORTED = "time zone storage strategy [NATIVE] that is not supported";
    private static final String NORMALIZE_NOT_SUPPORTED =
            "time zone storage strategy [NORMALIZE] that is not supported";

    @InjectMongoCollection(OFFSET_COLUMN_COLLECTION_NAME)
    private MongoCollection<BsonDocument> offsetColumnItems;

    @InjectMongoCollection(ZONED_COLUMN_COLLECTION_NAME)
    private MongoCollection<BsonDocument> zonedColumnItems;

    @InjectMongoCollection(RENAMED_COLUMN_COLLECTION_NAME)
    private MongoCollection<BsonDocument> renamedColumnItems;

    @InjectMongoCollection(STRUCT_COLUMN_COLLECTION_NAME)
    private MongoCollection<BsonDocument> structColumnItems;

    @InjectMongoCollection(RENAMED_STRUCT_COLUMN_COLLECTION_NAME)
    private MongoCollection<BsonDocument> renamedStructColumnItems;

    @InjectMongoCollection(SHARED_NAME_STRUCT_COLUMN_COLLECTION_NAME)
    private MongoCollection<BsonDocument> sharedNameStructColumnItems;

    @InjectMongoCollection(BARE_STORAGE_COLLECTION_NAME)
    private MongoCollection<BsonDocument> bareStorageItems;

    @Test
    void testColumnStoresInstantAndOffset() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new OffsetColumnItem(1, PERSISTED)));

        assertThat(offsetColumnItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, value: {"$date": "2026-08-09T08:15:30.003Z"}, value_tz: 7200}"""));

        var loadedItem = getSessionFactoryScope().fromTransaction(session -> session.find(OffsetColumnItem.class, 1));
        assertThat(loadedItem.value).isEqualTo(READ_BACK);
    }

    @Test
    void testColumnNullAttributeWritesNullToBothFields() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new OffsetColumnItem(1, null)));

        assertThat(offsetColumnItems.find())
                .containsExactly(BsonDocument.parse("{_id: 1, value: null, value_tz: null}"));

        var loadedItem = getSessionFactoryScope().fromTransaction(session -> session.find(OffsetColumnItem.class, 1));
        assertThat(loadedItem.value).isNull();
    }

    /**
     * Hibernate ORM tests both fields, which reaches the translator as a row-valued nullness predicate rather than as a
     * field path, and only a field path is supported.
     */
    @Test
    void testColumnIsNullIsUnsupported() {
        getSessionFactoryScope().inTransaction(session -> assertThatThrownBy(() -> session.createSelectionQuery(
                                "from OffsetColumnItem where value is null", OffsetColumnItem.class)
                        .getResultList())
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessage("Only the following nullness predicates are supported: field is [not] null"));
    }

    /** A bare {@code @TimeZoneStorage} resolves to {@code AUTO}, and so to {@code COLUMN}, adding the offset field. */
    @Test
    void testBareTimeZoneStorageStoresInstantAndOffset() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new BareStorageItem(1, PERSISTED)));

        assertThat(bareStorageItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, value: {"$date": "2026-08-09T08:15:30.003Z"}, value_tz: 7200}"""));

        var loadedItem = getSessionFactoryScope().fromTransaction(session -> session.find(BareStorageItem.class, 1));
        assertThat(loadedItem.value).isEqualTo(READ_BACK);
    }

    @Test
    void testColumnZonedDateTimeReadBackCarriesZoneOffset() {
        var persisted = ZonedDateTime.parse("2026-08-09T10:15:30.0029+02:00[Europe/Paris]");
        getSessionFactoryScope().inTransaction(session -> session.persist(new ZonedColumnItem(1, persisted)));

        assertThat(zonedColumnItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, value: {"$date": "2026-08-09T08:15:30.003Z"}, value_tz: 7200}"""));

        var loadedItem = getSessionFactoryScope().fromTransaction(session -> session.find(ZonedColumnItem.class, 1));
        assertThat(loadedItem.value.getZone()).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(loadedItem.value).isEqualTo(ZonedDateTime.parse("2026-08-09T10:15:30.003+02:00"));
    }

    @Test
    void testTimeZoneColumnNamesTheOffsetField() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new RenamedColumnItem(1, PERSISTED)));

        assertThat(renamedColumnItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, value: {"$date": "2026-08-09T08:15:30.003Z"}, valueOffset: 7200}"""));
    }

    @Test
    void testColumnInsideStructEmbeddable() {
        getSessionFactoryScope()
                .inTransaction(session -> session.persist(new StructColumnItem(1, new StructAggregate(PERSISTED))));

        assertThat(structColumnItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, aggregate: {value: {"$date": "2026-08-09T08:15:30.003Z"}, value_tz: 7200}}"""));

        var loadedItem = getSessionFactoryScope().fromTransaction(session -> session.find(StructColumnItem.class, 1));
        assertThat(loadedItem.aggregate.value).isEqualTo(READ_BACK);
    }

    /**
     * Materializing the embeddable itself, rather than reading it as part of an entity, goes through
     * {@link com.mongodb.hibernate.internal.type.MongoStructJdbcType}'s {@code getObject}. An attribute stored under
     * {@code COLUMN} spans two columns, so its value has to be recomposed from them rather than taken one column per
     * attribute.
     */
    @Test
    void testColumnInsideStructEmbeddableMaterializedDirectly() {
        getSessionFactoryScope()
                .inTransaction(session -> session.persist(new StructColumnItem(1, new StructAggregate(PERSISTED))));

        var mql =
                """
                {"aggregate": "%s", "pipeline": [{"$match": {"_id": 1}}, {"$project": {"aggregate": true, "_id": false}}]}"""
                        .formatted(STRUCT_COLUMN_COLLECTION_NAME);
        var aggregate = getSessionFactoryScope()
                .fromTransaction(session ->
                        session.createNativeQuery(mql, StructAggregate.class).getSingleResult());
        assertThat(aggregate.value).isEqualTo(READ_BACK);
    }

    /**
     * The {@code COLUMN} checks are about the attribute that declares the strategy, so an entity attribute of an
     * unrelated type that happens to carry the same name as the embeddable's attribute has no bearing on them.
     */
    @Test
    void testColumnInsideStructEmbeddableWhoseNameIsAlsoAnEntityAttributeName() {
        getSessionFactoryScope()
                .inTransaction(session -> session.persist(
                        new SharedNameStructColumnItem(1, "text", new SharedNameStructAggregate(PERSISTED))));

        assertThat(sharedNameStructColumnItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, value: "text", aggregate: {value: {"$date": "2026-08-09T08:15:30.003Z"}, value_tz: 7200}}"""));

        var loadedItem =
                getSessionFactoryScope().fromTransaction(session -> session.find(SharedNameStructColumnItem.class, 1));
        assertThat(loadedItem.aggregate.value).isEqualTo(READ_BACK);
    }

    @Test
    void testTimeZoneColumnNamesTheOffsetFieldInsideStructEmbeddable() {
        getSessionFactoryScope()
                .inTransaction(session ->
                        session.persist(new RenamedStructColumnItem(1, new RenamedStructAggregate(PERSISTED))));

        assertThat(renamedStructColumnItems.find())
                .containsExactly(
                        BsonDocument.parse(
                                """
                                {_id: 1, aggregate: {value: {"$date": "2026-08-09T08:15:30.003Z"}, valueOffset: 7200}}"""));

        var loadedItem =
                getSessionFactoryScope().fromTransaction(session -> session.find(RenamedStructColumnItem.class, 1));
        assertThat(loadedItem.aggregate.value).isEqualTo(READ_BACK);
    }

    @Test
    void testColumnEqualityComparesInstantAndOffset() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new OffsetColumnItem(1, PERSISTED)));
        commandHistory.clear();

        assertSelectionQuery(
                "from OffsetColumnItem where value = :v",
                OffsetColumnItem.class,
                q -> q.setParameter("v", READ_BACK),
                """
                {
                  "aggregate": "offsetColumnItems",
                  "pipeline": [
                    {
                      "$match": {
                        "$and": [
                          {"value": {"$eq": {"$date": "2026-08-09T08:15:30.003Z"}}},
                          {"value_tz": {"$eq": 7200}}
                        ]
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "value": true,
                        "value_tz": true
                      }
                    }
                  ]
                }""",
                List.of(new OffsetColumnItem(1, READ_BACK)),
                Set.of(OFFSET_COLUMN_COLLECTION_NAME));
    }

    @Test
    void testColumnEqualitySameInstantDifferentOffsetMatchesNothing() {
        getSessionFactoryScope().inTransaction(session -> session.persist(new OffsetColumnItem(1, PERSISTED)));
        commandHistory.clear();

        assertSelectionQuery(
                "from OffsetColumnItem where value = :v",
                OffsetColumnItem.class,
                q -> q.setParameter("v", READ_BACK.withOffsetSameInstant(ZoneOffset.UTC)),
                """
                {
                  "aggregate": "offsetColumnItems",
                  "pipeline": [
                    {
                      "$match": {
                        "$and": [
                          {"value": {"$eq": {"$date": "2026-08-09T08:15:30.003Z"}}},
                          {"value_tz": {"$eq": 0}}
                        ]
                      }
                    },
                    {
                      "$project": {
                        "_id": true,
                        "value": true,
                        "value_tz": true
                      }
                    }
                  ]
                }""",
                List.of(),
                Set.of(OFFSET_COLUMN_COLLECTION_NAME));
    }

    /**
     * The {@code COLUMN} rejections share a tail, so an expectation that stops short of the attribute they name is
     * satisfied by any of them.
     */
    private static String columnNotSupported(Class<?> entityClass, String usage, String propertyPath) {
        return format(
                "%s): the %s attribute [%s] uses the time zone storage strategy [COLUMN] that is not supported",
                entityClass.getName(), usage, propertyPath);
    }

    private static void withGlobalTimeZoneStorage(String storageType, Consumer<StandardServiceRegistry> action) {
        var url = new Configuration().getProperties().getProperty(JAKARTA_JDBC_URL);
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(JAKARTA_JDBC_URL, url)
                .applySetting(TIMEZONE_DEFAULT_STORAGE, storageType)
                .build();
        try {
            action.accept(registry);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    // hibernate.timezone.default_storage is not on the shared registry, so this case declares its own
    // @ServiceRegistry, and with it its own dialect binding.
    @Nested
    @DomainModel(annotatedClasses = AutoStorageItem.class)
    @ServiceRegistry(
            settings = {
                @Setting(name = TIMEZONE_DEFAULT_STORAGE, value = "AUTO"),
                @Setting(
                        name = DIALECT,
                        value = "com.mongodb.hibernate.query.AbstractQueryIntegrationTests$TranslateResultAwareDialect")
            })
    class GlobalAutoStorage extends AbstractQueryIntegrationTests {

        @InjectMongoCollection(AUTO_STORAGE_COLLECTION_NAME)
        private MongoCollection<BsonDocument> autoStorageItems;

        /**
         * The plural {@link Instant} attribute is the guard against the {@code COLUMN} rejections being written more
         * broadly than the strategy they reject: no strategy is resolved for {@link Instant}, so a global {@code AUTO}
         * must leave that attribute alone.
         */
        @Test
        void testAutoResolvesToColumn() {
            var item = new AutoStorageItem(1, PERSISTED, INSTANTS);
            getSessionFactoryScope().inTransaction(session -> session.persist(item));

            var storedDocument = autoStorageItems.find().first();
            assertThat(storedDocument).isNotNull();
            assertThat(storedDocument.get("value"))
                    .isEqualTo(new BsonDateTime(READ_BACK.toInstant().toEpochMilli()));
            assertThat(storedDocument.get("value_tz")).isEqualTo(new BsonInt32(7200));

            var loadedItem =
                    getSessionFactoryScope().fromTransaction(session -> session.find(AutoStorageItem.class, 1));
            assertThat(loadedItem.value).isEqualTo(READ_BACK);
            assertIterableEq(INSTANTS, loadedItem.instants);
        }
    }

    @Nested
    class AttributeStorage implements MongoServiceRegistryProducer {

        /**
         * An attribute-level strategy overrides the global setting, including one the global setting cannot ask for.
         */
        @Test
        void attributeStorageOverridesGlobalStorage() {
            withGlobalTimeZoneStorage("NORMALIZE", registry -> assertThatCode(() -> new MetadataSources(registry)
                            .addAnnotatedClass(NormalizeUtcStorageItem.class)
                            .buildMetadata())
                    .doesNotThrowAnyException());
        }

        @Entity(name = "NormalizeUtcStorageItem")
        @Table(name = "normalizeUtcStorageItems")
        static class NormalizeUtcStorageItem {
            @Id
            int id;

            @TimeZoneStorage(NORMALIZE_UTC)
            OffsetDateTime value;
        }
    }

    @Nested
    class Unsupported implements MongoServiceRegistryProducer {

        @Test
        void nativeStorageRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(NativeStorageItem.class)
                            .buildMetadata())
                    .hasMessageContaining(NATIVE_NOT_SUPPORTED);
        }

        @Test
        void globalNativeStorageRejectedAtBoot() {
            withGlobalTimeZoneStorage("NATIVE", registry -> assertThatThrownBy(() -> new MetadataSources(registry)
                            .addAnnotatedClass(PlainItem.class)
                            .buildMetadata())
                    .hasMessageContaining("The configured time zone storage type NATIVE is not supported"));
        }

        @Test
        void normalizeStorageRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(NormalizeStorageItem.class)
                            .buildMetadata())
                    .hasMessageContaining(NORMALIZE_NOT_SUPPORTED);
        }

        @Test
        void globalNormalizeStorageRejectedAtBoot() {
            withGlobalTimeZoneStorage("NORMALIZE", registry -> assertThatThrownBy(() -> new MetadataSources(registry)
                            .addAnnotatedClass(PlainItem.class)
                            .buildMetadata())
                    .hasMessageContaining(NORMALIZE_NOT_SUPPORTED));
        }

        @Test
        void globalNormalizeStorageOnPluralAttributeRejectedAtBoot() {
            withGlobalTimeZoneStorage("NORMALIZE", registry -> assertThatThrownBy(() -> new MetadataSources(registry)
                            .addAnnotatedClass(PlainCollectionItem.class)
                            .buildMetadata())
                    .hasMessageContaining(NORMALIZE_NOT_SUPPORTED));
        }

        @Test
        void globalAutoStorageOnPluralAttributeRejectedAtBoot() {
            withGlobalTimeZoneStorage("AUTO", registry -> assertThatThrownBy(() -> new MetadataSources(registry)
                            .addAnnotatedClass(PlainCollectionItem.class)
                            .buildMetadata())
                    .hasMessageContaining(columnNotSupported(PlainCollectionItem.class, "plural", "values")));
        }

        @Test
        void columnOnPluralAttributeRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(ColumnCollectionItem.class)
                            .buildMetadata())
                    .hasMessageContaining(columnNotSupported(ColumnCollectionItem.class, "plural", "values"));
        }

        @Test
        void columnOnIdentifierRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(ColumnIdItem.class)
                            .buildMetadata())
                    .hasMessageContaining(columnNotSupported(ColumnIdItem.class, "identifier", "id"));
        }

        /**
         * Under {@code COLUMN} the attribute is a composite value, so inside an {@code @EmbeddedId} it is refused as a
         * non-scalar id component before the {@code COLUMN} checks get to it. What matters is that the mapping is
         * refused rather than reaching the identifier column renaming, which spans two columns there.
         */
        @Test
        void columnInsideEmbeddedIdRejectedAtBoot() {
            assertThatThrownBy(() -> new MetadataSources()
                            .addAnnotatedClass(ColumnEmbeddedIdItem.class)
                            .buildMetadata())
                    .isInstanceOf(FeatureNotSupportedException.class)
                    .hasMessageContaining("a non-scalar id component");
        }

        @Entity(name = "NativeStorageItem")
        @Table(name = "nativeStorageItems")
        static class NativeStorageItem {
            @Id
            int id;

            @TimeZoneStorage(NATIVE)
            OffsetDateTime value;
        }

        @Entity(name = "NormalizeStorageItem")
        @Table(name = "normalizeStorageItems")
        static class NormalizeStorageItem {
            @Id
            int id;

            @TimeZoneStorage(NORMALIZE)
            OffsetDateTime value;
        }

        @Entity(name = "PlainCollectionItem")
        @Table(name = "plainCollectionItems")
        static class PlainCollectionItem {
            @Id
            int id;

            Collection<OffsetDateTime> values;
        }

        @Entity(name = "PlainItem")
        @Table(name = "plainItems")
        static class PlainItem {
            @Id
            int id;

            OffsetDateTime value;
        }

        @Entity(name = "ColumnCollectionItem")
        @Table(name = "columnCollectionItems")
        static class ColumnCollectionItem {
            @Id
            int id;

            @TimeZoneStorage(COLUMN)
            Collection<OffsetDateTime> values;
        }

        @Entity(name = "ColumnIdItem")
        @Table(name = "columnIdItems")
        static class ColumnIdItem {
            @Id
            @TimeZoneStorage(COLUMN)
            OffsetDateTime id;
        }

        @Entity(name = "ColumnEmbeddedIdItem")
        @Table(name = "columnEmbeddedIdItems")
        static class ColumnEmbeddedIdItem {
            @EmbeddedId
            ColumnId id;
        }

        @Embeddable
        static class ColumnId {
            @TimeZoneStorage(COLUMN)
            OffsetDateTime value;
        }
    }

    @Entity(name = "OffsetColumnItem")
    @Table(name = OFFSET_COLUMN_COLLECTION_NAME)
    static class OffsetColumnItem {
        @Id
        int id;

        @TimeZoneStorage(COLUMN)
        OffsetDateTime value;

        OffsetColumnItem() {}

        OffsetColumnItem(int id, OffsetDateTime value) {
            this.id = id;
            this.value = value;
        }
    }

    @Entity(name = "BareStorageItem")
    @Table(name = BARE_STORAGE_COLLECTION_NAME)
    static class BareStorageItem {
        @Id
        int id;

        @TimeZoneStorage
        OffsetDateTime value;

        BareStorageItem() {}

        BareStorageItem(int id, OffsetDateTime value) {
            this.id = id;
            this.value = value;
        }
    }

    @Entity(name = "ZonedColumnItem")
    @Table(name = ZONED_COLUMN_COLLECTION_NAME)
    static class ZonedColumnItem {
        @Id
        int id;

        @TimeZoneStorage(COLUMN)
        ZonedDateTime value;

        ZonedColumnItem() {}

        ZonedColumnItem(int id, ZonedDateTime value) {
            this.id = id;
            this.value = value;
        }
    }

    @Entity(name = "RenamedColumnItem")
    @Table(name = RENAMED_COLUMN_COLLECTION_NAME)
    static class RenamedColumnItem {
        @Id
        int id;

        @TimeZoneStorage(COLUMN)
        @TimeZoneColumn(name = "valueOffset")
        OffsetDateTime value;

        RenamedColumnItem() {}

        RenamedColumnItem(int id, OffsetDateTime value) {
            this.id = id;
            this.value = value;
        }
    }

    @Entity(name = "StructColumnItem")
    @Table(name = STRUCT_COLUMN_COLLECTION_NAME)
    static class StructColumnItem {
        @Id
        int id;

        StructAggregate aggregate;

        StructColumnItem() {}

        StructColumnItem(int id, StructAggregate aggregate) {
            this.id = id;
            this.aggregate = aggregate;
        }
    }

    @Entity(name = "AutoStorageItem")
    @Table(name = AUTO_STORAGE_COLLECTION_NAME)
    static class AutoStorageItem {
        @Id
        int id;

        OffsetDateTime value;

        Collection<Instant> instants;

        AutoStorageItem() {}

        AutoStorageItem(int id, OffsetDateTime value, Collection<Instant> instants) {
            this.id = id;
            this.value = value;
            this.instants = instants;
        }
    }

    @Entity(name = "RenamedStructColumnItem")
    @Table(name = RENAMED_STRUCT_COLUMN_COLLECTION_NAME)
    static class RenamedStructColumnItem {
        @Id
        int id;

        RenamedStructAggregate aggregate;

        RenamedStructColumnItem() {}

        RenamedStructColumnItem(int id, RenamedStructAggregate aggregate) {
            this.id = id;
            this.aggregate = aggregate;
        }
    }

    @Embeddable
    @Struct(name = "RenamedTimeZoneStorageAggregate")
    static class RenamedStructAggregate {
        @TimeZoneStorage(COLUMN)
        @TimeZoneColumn(name = "valueOffset")
        OffsetDateTime value;

        RenamedStructAggregate() {}

        RenamedStructAggregate(OffsetDateTime value) {
            this.value = value;
        }
    }

    @Entity(name = "SharedNameStructColumnItem")
    @Table(name = SHARED_NAME_STRUCT_COLUMN_COLLECTION_NAME)
    static class SharedNameStructColumnItem {
        @Id
        int id;

        String value;

        SharedNameStructAggregate aggregate;

        SharedNameStructColumnItem() {}

        SharedNameStructColumnItem(int id, String value, SharedNameStructAggregate aggregate) {
            this.id = id;
            this.value = value;
            this.aggregate = aggregate;
        }
    }

    @Embeddable
    @Struct(name = "SharedNameTimeZoneStorageAggregate")
    static class SharedNameStructAggregate {
        @TimeZoneStorage(COLUMN)
        OffsetDateTime value;

        SharedNameStructAggregate() {}

        SharedNameStructAggregate(OffsetDateTime value) {
            this.value = value;
        }
    }

    @Embeddable
    @Struct(name = "TimeZoneStorageAggregate")
    static class StructAggregate {
        @TimeZoneStorage(COLUMN)
        OffsetDateTime value;

        StructAggregate() {}

        StructAggregate(OffsetDateTime value) {
            this.value = value;
        }
    }
}
