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

package com.mongodb.hibernate.query;

import static com.mongodb.hibernate.internal.MongoConstants.MONGO_CONFIGURATION_CONTRIBUTOR_KEY;
import static com.mongodb.hibernate.internal.MongoConstants.SCHEMA_VALIDATION_PROPERTY_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.hibernate.junit.CommandHistory;
import com.mongodb.hibernate.junit.InjectCommandHistory;
import com.mongodb.hibernate.junit.InjectMongoCollection;
import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.types.ObjectId;
import org.hibernate.Session;
import org.hibernate.annotations.Struct;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MongoExtension.class)
class SchemaValidationIntegrationTests {

    private static final Map<String, Object> BASE_SETTINGS = Map.of(
            "jakarta.persistence.schema-generation.database.action",
            "create-drop",
            "hibernate.hbm2ddl.halt_on_error",
            "true",
            SCHEMA_VALIDATION_PROPERTY_NAME,
            "true");

    @InjectCommandHistory
    private CommandHistory commandHistory;

    @InjectMongoCollection("items")
    private MongoCollection<BsonDocument> itemsCollection;

    @InjectMongoCollection("unvalidatedItems")
    private MongoCollection<BsonDocument> unvalidatedItemsCollection;

    @InjectMongoCollection("addressedItems")
    private MongoCollection<BsonDocument> addressedItemsCollection;

    @InjectMongoCollection("taggedItems")
    private MongoCollection<BsonDocument> taggedItemsCollection;

    @InjectMongoCollection("structsItems")
    private MongoCollection<BsonDocument> structsItemsCollection;

    @InjectMongoCollection("nestedlyAddressedItems")
    private MongoCollection<BsonDocument> nestedlyAddressedItemsCollection;

    @InjectMongoCollection("flatItems")
    private MongoCollection<BsonDocument> flatItemsCollection;

    @InjectMongoCollection("compositeItems")
    private MongoCollection<BsonDocument> compositeItemsCollection;

    @InjectMongoCollection("typedItems")
    private MongoCollection<BsonDocument> typedItemsCollection;

    @InjectMongoCollection("lib.qualifiedItems")
    private MongoCollection<BsonDocument> qualifiedItemsCollection;

    @InjectMongoCollection("precreatedItems")
    private MongoCollection<BsonDocument> precreatedItemsCollection;

    @InjectMongoCollection("sharedItems")
    private MongoCollection<BsonDocument> sharedItemsCollection;

    @InjectMongoCollection("animals")
    private MongoCollection<BsonDocument> animalsCollection;

    @InjectMongoCollection("collisionItems")
    private MongoCollection<BsonDocument> collisionItemsCollection;

    @InjectMongoCollection("children")
    private MongoCollection<BsonDocument> childrenCollection;

    @InjectMongoCollection("tinyIntItems")
    private MongoCollection<BsonDocument> tinyIntItemsCollection;

    @InjectMongoCollection("disjointStructItems")
    private MongoCollection<BsonDocument> disjointStructItemsCollection;

    @InjectMongoCollection("enumItems")
    private MongoCollection<BsonDocument> enumItemsCollection;

    @InjectMongoCollection("setItems")
    private MongoCollection<BsonDocument> setItemsCollection;

    @InjectMongoCollection("lengthItems")
    private MongoCollection<BsonDocument> lengthItemsCollection;

    @InjectMongoCollection("nestedExtrasItems")
    private MongoCollection<BsonDocument> nestedExtrasItemsCollection;

    @InjectMongoCollection("countrySetItems")
    private MongoCollection<BsonDocument> countrySetItemsCollection;

    @InjectMongoCollection("sharedNullabilityItems")
    private MongoCollection<BsonDocument> sharedNullabilityItemsCollection;

    @BeforeEach
    void dropAssertedCollections() {
        List.of(
                        itemsCollection,
                        unvalidatedItemsCollection,
                        addressedItemsCollection,
                        taggedItemsCollection,
                        structsItemsCollection,
                        nestedlyAddressedItemsCollection,
                        flatItemsCollection,
                        compositeItemsCollection,
                        typedItemsCollection,
                        qualifiedItemsCollection,
                        precreatedItemsCollection,
                        sharedItemsCollection,
                        animalsCollection,
                        collisionItemsCollection,
                        childrenCollection,
                        tinyIntItemsCollection,
                        disjointStructItemsCollection,
                        enumItemsCollection,
                        setItemsCollection,
                        lengthItemsCollection,
                        nestedExtrasItemsCollection,
                        countrySetItemsCollection,
                        sharedNullabilityItemsCollection)
                .forEach(MongoCollection::drop);
    }

    private record Export<T>(List<BsonDocument> commands, T observed) {}

    private <T> Export<T> inRegistry(
            Class<?> entityClass, Map<String, Object> additionalSettings, Function<Session, T> observer) {
        return inRegistry(List.of(entityClass), additionalSettings, observer);
    }

    private <T> Export<T> inRegistry(
            List<Class<?>> entityClasses, Map<String, Object> additionalSettings, Function<Session, T> observer) {
        try (var registry = new StandardServiceRegistryBuilder()
                .applySettings(BASE_SETTINGS)
                .applySettings(additionalSettings)
                .applySetting(
                        MONGO_CONFIGURATION_CONTRIBUTOR_KEY,
                        MongoExtension.configurationContributorForClass(SchemaValidationIntegrationTests.class))
                .build()) {
            T observed;
            var sources = new MetadataSources();
            entityClasses.forEach(sources::addAnnotatedClass);
            try (var sessionFactory = sources.buildMetadata(registry).buildSessionFactory();
                    var session = sessionFactory.openSession()) {
                observed = observer.apply(session);
            }
            return new Export<>(commandHistory.getCommands(), observed);
        }
    }

    private static List<String> createCommands(List<BsonDocument> commands) {
        return commands.stream()
                .filter(command -> command.containsKey("create"))
                .map(command -> {
                    var rebuilt = new BsonDocument("create", command.get("create"));
                    if (command.containsKey("validator")) {
                        rebuilt.append("validator", command.get("validator"));
                    }
                    return rebuilt.toJson();
                })
                .toList();
    }

    private static String expectedCreate(String collection) {
        return new BsonDocument("create", new BsonString(collection)).toJson();
    }

    @Entity(name = "Item")
    @Table(name = "items")
    static class Item {
        @Id
        int id;

        String title;

        BigDecimal price;
    }

    @Entity(name = "UnvalidatedItem")
    @Table(name = "unvalidatedItems")
    static class UnvalidatedItem {
        @Id
        int id;

        String title;
    }

    @Entity(name = "AddressedItem")
    @Table(name = "addressedItems")
    static class AddressedItem {
        @Id
        int id;

        Address address;
    }

    @Embeddable
    @Struct(name = "Address")
    record Address(String city, int zipCode) {}

    @Entity(name = "TaggedItem")
    @Table(name = "taggedItems")
    static class TaggedItem {
        @Id
        int id;

        List<String> tags;
    }

    @Entity(name = "StructsItem")
    @Table(name = "structsItems")
    static class StructsItem {
        @Id
        int id;

        List<Single> singles;
    }

    @Embeddable
    @Struct(name = "Single")
    record Single(int value) {}

    @Entity(name = "NestedlyAddressedItem")
    @Table(name = "nestedlyAddressedItems")
    static class NestedlyAddressedItem {
        @Id
        int id;

        NestedAddress address;
    }

    @Embeddable
    @Struct(name = "NestedAddress")
    record NestedAddress(String city, Zone zone) {}

    @Embeddable
    @Struct(name = "Zone")
    record Zone(int code) {}

    @Entity(name = "FlatItem")
    @Table(name = "flatItems")
    static class FlatItem {
        @Id
        int id;

        Flat flat;
    }

    @Embeddable
    record Flat(String fa, int fb) {}

    @Entity(name = "CompositeItem")
    @Table(name = "compositeItems")
    static class CompositeItem {
        @EmbeddedId
        CompositeId id;

        String title;
    }

    @Embeddable
    record CompositeId(long bookNo, long publisherId) {}

    @Entity(name = "TypedItem")
    @Table(name = "typedItems")
    static class TypedItem {
        @Id
        int id;

        Boolean flag;

        Character initial;

        long count;

        Double weight;

        byte[] blob;

        Instant instant;

        ObjectId objectId;

        String title;

        BigDecimal price;
    }

    @Entity(name = "QualifiedItem")
    @Table(name = "qualifiedItems", schema = "lib")
    static class QualifiedItem {
        @Id
        int id;
    }

    @Entity(name = "PrecreatedItem")
    @Table(name = "precreatedItems")
    static class PrecreatedItem {
        @Id
        int id;

        String title;
    }

    @Test
    void testDefaultOffEmitsNoValidator() {
        var export =
                inRegistry(UnvalidatedItem.class, Map.of(SCHEMA_VALIDATION_PROPERTY_NAME, "false"), session -> null);
        assertThat(createCommands(export.commands())).containsExactly(expectedCreate("unvalidatedItems"));
    }

    @Test
    void testCreateCommandCarriesValidator() {
        var export = inRegistry(Item.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "items", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "price": {"bsonType": ["decimal", "null"]},
                                        "title": {"bsonType": ["string", "null"]}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerRejectsWrongTypedInsert() {
        inRegistry(Item.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> itemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "title": 42,
                                                "price": null
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerRejectsInsertMissingRequiredField() {
        inRegistry(Item.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> itemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "title": "x",
                                                "price": null
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerAcceptsInsertMissingNullableField() {
        inRegistry(Item.class, Map.of(), session -> {
            itemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2,
                                "price": null
                            }
                            """));
            return null;
        });
    }

    @Test
    void testServerRejectsInsertWithUnmappedField() {
        inRegistry(Item.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> itemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "title": "x",
                                                "price": null,
                                                "extra": 1
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerAcceptsConformingInsert() {
        var observed = inRegistry(Item.class, Map.of(), session -> {
            itemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "title": null,
                                "price": null
                            }
                            """));
            return itemsCollection.countDocuments();
        });
        assertThat(observed.observed()).isEqualTo(1);
    }

    @Test
    void testPlainEmbeddableIsFlattenedToLeafProperties() {
        var export = inRegistry(FlatItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "flatItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "fb": {"bsonType": ["int", "null"]},
                                        "fa": {"bsonType": ["string", "null"]}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testStructFieldSchema() {
        var export = inRegistry(AddressedItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "addressedItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "address": {
                                            "bsonType": ["object", "null"],
                                            "properties": {
                                                "city": {"bsonType": ["string", "null"]},
                                                "zipCode": {"bsonType": "int"}
                                            },
                                            "required": ["zipCode"],
                                            "additionalProperties": false
                                        }
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerRejectsExtraStructSubfield() {
        inRegistry(AddressedItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> addressedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "address": {
                                                    "city": "x",
                                                    "zipCode": 1,
                                                    "extra": 2
                                                }
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerRejectsMissingStructSubfield() {
        inRegistry(AddressedItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> addressedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "address": {
                                                    "city": "x"
                                                }
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerRejectsWrongTypedStructSubfield() {
        inRegistry(AddressedItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> addressedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "address": {
                                                    "city": 1,
                                                    "zipCode": 1
                                                }
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerAcceptsNullStructSubfield() {
        inRegistry(AddressedItem.class, Map.of(), session -> {
            addressedItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "address": {
                                    "city": null,
                                    "zipCode": 1
                                }
                            }
                            """));
            return null;
        });
    }

    @Test
    void testServerRejectsNullStructSubfield() {
        inRegistry(AddressedItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> addressedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "address": {
                                                    "city": null,
                                                    "zipCode": null
                                                }
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testScalarArraySchema() {
        var export = inRegistry(TaggedItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "taggedItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "tags": {
                                            "bsonType": ["array", "null"],
                                            "items": {"bsonType": ["string", "null"]}
                                        }
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerRejectsWrongTypedArrayElement() {
        inRegistry(TaggedItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> taggedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "tags": [
                                                    "a",
                                                    2
                                                ]
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerAcceptsNullArrayElement() {
        inRegistry(TaggedItem.class, Map.of(), session -> {
            taggedItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "tags": [
                                    "a",
                                    null
                                ]
                            }
                            """));
            return null;
        });
    }

    @Test
    void testStructArraySchema() {
        var export = inRegistry(StructsItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "structsItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "singles": {
                                            "bsonType": ["array", "null"],
                                            "items": {
                                                "bsonType": ["object", "null"],
                                                "properties": {
                                                    "value": {"bsonType": "int"}
                                                },
                                                "required": ["value"],
                                                "additionalProperties": false
                                            }
                                        }
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerAcceptsNullStructArrayElement() {
        inRegistry(StructsItem.class, Map.of(), session -> {
            structsItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "singles": [
                                    {"value": 1},
                                    null
                                ]
                            }
                            """));
            return null;
        });
    }

    @Test
    void testNestedStructSchema() {
        var export = inRegistry(NestedlyAddressedItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "nestedlyAddressedItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "address": {
                                            "bsonType": ["object", "null"],
                                            "properties": {
                                                "city": {"bsonType": ["string", "null"]},
                                                "zone": {
                                                    "bsonType": ["object", "null"],
                                                    "properties": {
                                                        "code": {"bsonType": "int"}
                                                    },
                                                    "required": ["code"],
                                                    "additionalProperties": false
                                                }
                                            },
                                            "additionalProperties": false
                                        }
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testCompositeIdSchema() {
        var export = inRegistry(CompositeItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "compositeItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {
                                            "bsonType": "object",
                                            "properties": {
                                                "bookNo": {"bsonType": "long"},
                                                "publisherId": {"bsonType": "long"}
                                            },
                                            "required": ["bookNo", "publisherId"],
                                            "additionalProperties": false
                                        },
                                        "title": {"bsonType": ["string", "null"]}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerRejectsMissingIdComponent() {
        inRegistry(CompositeItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> compositeItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": {
                                                    "bookNo": 1
                                                },
                                                "title": "x"
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerRejectsExtraIdComponent() {
        inRegistry(CompositeItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> compositeItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": {
                                                    "bookNo": 1,
                                                    "publisherId": 2,
                                                    "extra": 3
                                                },
                                                "title": "x"
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testEveryMappedBsonType() {
        var export = inRegistry(TypedItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .containsExactly(BsonDocument.parse(
                                """
                                {"create": "typedItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "flag": {"bsonType": ["bool", "null"]},
                                        "initial": {"bsonType": ["string", "null"]},
                                        "price": {"bsonType": ["decimal", "null"]},
                                        "weight": {"bsonType": ["double", "null"]},
                                        "count": {"bsonType": "long"},
                                        "instant": {"bsonType": ["date", "null"]},
                                        "blob": {"bsonType": ["binData", "null"]},
                                        "objectId": {"bsonType": ["objectId", "null"]},
                                        "title": {"bsonType": ["string", "null"]}
                                    },
                                    "required": ["_id", "count"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testSchemaQualifiedCollectionCarriesValidator() {
        var export = inRegistry(QualifiedItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands())).anySatisfy(command -> assertThat(command)
                .contains("\"create\": \"lib.qualifiedItems\"")
                .contains("\"$jsonSchema\""));
    }

    @Test
    void testExistingCollectionIsNeverRevalidated() {
        precreatedItemsCollection.insertOne(
                BsonDocument.parse(
                        """
                        {
                            "_id": 1,
                            "title": 42
                        }
                        """));
        var export = inRegistry(
                PrecreatedItem.class,
                Map.of(
                        "jakarta.persistence.schema-generation.database.action", "create",
                        "hibernate.hbm2ddl.halt_on_error", "false"),
                session -> {
                    // The create fails server-side (collection exists) and is logged, not halted.
                    // The pre-existing document stays and no collMod is ever sent.
                    precreatedItemsCollection.insertOne(
                            BsonDocument.parse(
                                    """
                                    {
                                        "_id": 2,
                                        "title": 43
                                    }
                                    """));
                    return null;
                });
        assertThat(export.commands()).noneMatch(command -> command.containsKey("collMod"));
        assertThat(precreatedItemsCollection.countDocuments()).isEqualTo(2);
    }

    @Entity(name = "SharedItemA")
    @Table(name = "sharedItems")
    static class SharedItemA {
        @Id
        int id;

        String title;
    }

    @Entity(name = "SharedItemB")
    @Table(name = "sharedItems")
    static class SharedItemB {
        @Id
        int id;

        Integer number;
    }

    @Test
    void testTwoEntitiesSharingTableAcceptsEitherEntityShape() {
        var export = inRegistry(List.of(SharedItemA.class, SharedItemB.class), Map.of(), session -> {
            sharedItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "title": "x"
                            }
                            """));
            sharedItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2,
                                "number": 42
                            }
                            """));
            return sharedItemsCollection.countDocuments();
        });
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "sharedItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "anyOf": [
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"}, "title": {"bsonType": ["string", "null"]}},
                                         "required": ["_id"],
                                         "additionalProperties": false},
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"}, "number": {"bsonType": ["int", "null"]}},
                                         "required": ["_id"],
                                         "additionalProperties": false}
                                    ]
                                }}}
                                """)
                        .toJson());
        assertThat(export.observed()).isEqualTo(2);
        inRegistry(List.of(SharedItemA.class, SharedItemB.class), Map.of(), session -> {
            assertThatThrownBy(
                            () -> sharedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "title": "x"
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
        inRegistry(List.of(SharedItemA.class, SharedItemB.class), Map.of(), session -> {
            assertThatThrownBy(
                            () -> sharedItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 3,
                                                "title": "x",
                                                "number": 42
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Entity(name = "Animal")
    @Table(name = "animals")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "dtype")
    static class Animal {
        @Id
        int id;

        String commonField;

        Animal() {}

        Animal(int id, String commonField) {
            this.id = id;
            this.commonField = commonField;
        }
    }

    @Entity(name = "Dog")
    @DiscriminatorValue("d")
    static class Dog extends Animal {
        String uniqueField;

        Dog() {}

        Dog(int id, String commonField, String uniqueField) {
            super(id, commonField);
            this.uniqueField = uniqueField;
        }
    }

    @Test
    void testSingleTableInheritanceAcceptsEachEntityShape() {
        var export = inRegistry(List.of(Animal.class, Dog.class), Map.of(), session -> {
            session.getTransaction().begin();
            session.persist(new Dog(1, "common", "unique"));
            session.getTransaction().commit();
            return animalsCollection.countDocuments();
        });
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "animals", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "anyOf": [
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"}, "dtype": {"bsonType": "string"},
                                                         "commonField": {"bsonType": ["string", "null"]}},
                                         "required": ["_id", "dtype"],
                                         "additionalProperties": false},
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"}, "dtype": {"bsonType": "string"},
                                                         "commonField": {"bsonType": ["string", "null"]},
                                                         "uniqueField": {"bsonType": ["string", "null"]}},
                                         "required": ["_id", "dtype"],
                                         "additionalProperties": false}
                                    ]
                                }}}
                                """)
                        .toJson());
        assertThat(export.observed()).isEqualTo(1);
    }

    @Entity(name = "CollisionItem")
    @Table(name = "collisionItems")
    static class CollisionItem {
        @Id
        int id;

        Instant instant;

        CollisionStruct aggregateEmbeddable;

        CollisionItem() {}

        CollisionItem(int id, Instant instant, CollisionStruct aggregateEmbeddable) {
            this.id = id;
            this.instant = instant;
            this.aggregateEmbeddable = aggregateEmbeddable;
        }
    }

    @Embeddable
    @Struct(name = "CollisionStruct")
    static class CollisionStruct {
        Instant instant;

        CollisionStruct() {}

        CollisionStruct(Instant instant) {
            this.instant = instant;
        }
    }

    @Test
    void testStructFieldNameCollisionIncludesTopLevelField() {
        var now = Instant.parse("2023-01-01T00:00:00Z");
        var export = inRegistry(CollisionItem.class, Map.of(), session -> {
            session.getTransaction().begin();
            session.persist(new CollisionItem(1, now, new CollisionStruct(now)));
            session.getTransaction().commit();
            return collisionItemsCollection.countDocuments();
        });
        assertThat(export.observed()).isEqualTo(1);
    }

    @Entity(name = "Parent")
    @Table(name = "parents")
    static class Parent {
        @Id
        long id;

        Parent() {}

        Parent(long id) {
            this.id = id;
        }
    }

    @Entity(name = "Child")
    @Table(name = "children")
    static class Child {
        @Id
        int id;

        @JoinColumn(name = "parentId")
        @ManyToOne
        Parent parent;

        Child() {}

        Child(int id, Parent parent) {
            this.id = id;
            this.parent = parent;
        }
    }

    @Test
    void testManyToOneFkColumnTypedFromReferencedId() {
        var export = inRegistry(List.of(Parent.class, Child.class), Map.of(), session -> {
            var parent = new Parent(1);
            session.getTransaction().begin();
            session.persist(parent);
            session.persist(new Child(1, parent));
            session.getTransaction().commit();
            return childrenCollection.countDocuments();
        });
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "children", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "parentId": {"bsonType": ["long", "null"]}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
        assertThat(export.observed()).isEqualTo(1);
        inRegistry(List.of(Parent.class, Child.class), Map.of(), session -> {
            assertThatThrownBy(
                            () -> childrenCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 2,
                                                "parentId": "not-a-long"
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Entity(name = "TinyIntItem")
    @Table(name = "tinyIntItems")
    static class TinyIntItem {
        @Id
        int id;

        Byte value;
    }

    @Test
    void testTinyIntMapsToInt() {
        var export = inRegistry(TinyIntItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "tinyIntItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "value": {"bsonType": ["int", "null"]}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Entity(name = "DisjointStructA")
    @Table(name = "disjointStructItems")
    static class DisjointStructA {
        @Id
        int id;

        DisjointStructAEmbeddable struct;

        DisjointStructA() {}

        DisjointStructA(int id, DisjointStructAEmbeddable struct) {
            this.id = id;
            this.struct = struct;
        }
    }

    @Embeddable
    @Struct(name = "DsA")
    record DisjointStructAEmbeddable(int a) {}

    @Entity(name = "DisjointStructB")
    @Table(name = "disjointStructItems")
    static class DisjointStructB {
        @Id
        int id;

        DisjointStructBEmbeddable struct;

        DisjointStructB() {}

        DisjointStructB(int id, DisjointStructBEmbeddable struct) {
            this.id = id;
            this.struct = struct;
        }
    }

    @Embeddable
    @Struct(name = "DsB")
    record DisjointStructBEmbeddable(String b) {}

    @Test
    void testSharedStructColumnAcceptsEitherWriterShape() {
        var export = inRegistry(List.of(DisjointStructA.class, DisjointStructB.class), Map.of(), session -> {
            disjointStructItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "struct": {
                                    "a": 1
                                }
                            }
                            """));
            disjointStructItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2,
                                "struct": {
                                    "b": "x"
                                }
                            }
                            """));
            return disjointStructItemsCollection.countDocuments();
        });
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "disjointStructItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "anyOf": [
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"},
                                                         "struct": {"bsonType": ["object", "null"],
                                                                    "properties": {"a": {"bsonType": "int"}},
                                                                    "required": ["a"],
                                                                    "additionalProperties": false}},
                                         "required": ["_id"],
                                         "additionalProperties": false},
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"},
                                                         "struct": {"bsonType": ["object", "null"],
                                                                    "properties": {"b": {"bsonType": ["string", "null"]}},
                                                                    "additionalProperties": false}},
                                         "required": ["_id"],
                                         "additionalProperties": false}
                                    ]
                                }}}
                                """)
                        .toJson());
        assertThat(export.observed()).isEqualTo(2);
        inRegistry(List.of(DisjointStructA.class, DisjointStructB.class), Map.of(), session -> {
            assertThatThrownBy(
                            () -> disjointStructItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 3,
                                                "struct": {
                                                    "a": "not-an-int"
                                                }
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
        inRegistry(List.of(DisjointStructA.class, DisjointStructB.class), Map.of(), session -> {
            assertThatThrownBy(
                            () -> disjointStructItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 4,
                                                "struct": {
                                                    "a": 1,
                                                    "b": "x"
                                                }
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testEnumFieldsEmitClosedSets() {
        var export = inRegistry(EnumItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "enumItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "status": {"enum": [0, 1]},
                                        "country": {"enum": ["USA", "CANADA", null]}
                                    },
                                    "required": ["_id", "status"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerRejectsUnknownEnumLiteral() {
        inRegistry(EnumItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> enumItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "country": "MEXICO",
                                                "status": 0
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerAcceptsValidEnumLiteralAndNull() {
        inRegistry(EnumItem.class, Map.of(), session -> {
            enumItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2,
                                "country": null,
                                "status": 0
                            }
                            """));
            return null;
        });
    }

    @Test
    void testSetMappedCollectionEmitsUniqueItems() {
        var export = inRegistry(SetItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "setItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "ints": {"bsonType": ["array", "null"],
                                                 "items": {"bsonType": ["int", "null"]},
                                                 "uniqueItems": true}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testServerRejectsDuplicateSetElements() {
        inRegistry(SetItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> setItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "ints": [5, 5]
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Test
    void testServerAcceptsDistinctSetElementsIncludingNull() {
        inRegistry(SetItem.class, Map.of(), session -> {
            setItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2,
                                "ints": [5, null]
                            }
                            """));
            return null;
        });
    }

    enum Country {
        USA,
        CANADA
    }

    @Entity(name = "EnumItem")
    @Table(name = "enumItems")
    static class EnumItem {
        @Id
        int id;

        @Enumerated(EnumType.STRING)
        Country country;

        @Column(nullable = false)
        Country status;

        EnumItem() {}

        EnumItem(int id, Country country, Country status) {
            this.id = id;
            this.country = country;
            this.status = status;
        }
    }

    @Entity(name = "SetItem")
    @Table(name = "setItems")
    static class SetItem {
        @Id
        int id;

        Set<Integer> ints;

        SetItem() {}

        SetItem(int id, Set<Integer> ints) {
            this.id = id;
            this.ints = ints;
        }
    }

    @Test
    void testColumnLengthIsNotExpressedAsMaxLength() {
        var export = inRegistry(LengthItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "lengthItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "name": {"bsonType": ["string", "null"]}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
    }

    @Test
    void testStructNestedEnumAndSetSemantics() {
        var export = inRegistry(NestedExtrasItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "nestedExtrasItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "extras": {"bsonType": ["object", "null"],
                                                   "properties": {"ints": {"bsonType": ["array", "null"],
                                                                            "items": {"bsonType": ["int", "null"]},
                                                                            "uniqueItems": true},
                                                                   "status": {"enum": [0, 1, null]}},
                                                   "additionalProperties": false}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
        inRegistry(NestedExtrasItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> nestedExtrasItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "extras": {"status": 5, "ints": [1, 1]}
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
        inRegistry(NestedExtrasItem.class, Map.of(), session -> {
            nestedExtrasItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2,
                                "extras": {"status": 1, "ints": [1, 2, null]}
                            }
                            """));
            return null;
        });
    }

    @Test
    void testEnumArrayElementsEmitClosedSets() {
        var export = inRegistry(CountrySetItem.class, Map.of(), session -> null);
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "countrySetItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "properties": {
                                        "_id": {"bsonType": "int"},
                                        "countries": {"bsonType": ["array", "null"],
                                                       "items": {"enum": [0, 1, null]},
                                                       "uniqueItems": true}
                                    },
                                    "required": ["_id"],
                                    "additionalProperties": false
                                }}}
                                """)
                        .toJson());
        inRegistry(CountrySetItem.class, Map.of(), session -> {
            assertThatThrownBy(
                            () -> countrySetItemsCollection.insertOne(
                                    BsonDocument.parse(
                                            """
                                            {
                                                "_id": 1,
                                                "countries": [0, 5]
                                            }
                                            """)))
                    .isInstanceOf(MongoWriteException.class)
                    .message()
                    .contains("Document failed validation");
            return null;
        });
    }

    @Entity(name = "LengthItem")
    @Table(name = "lengthItems")
    static class LengthItem {
        @Id
        int id;

        @Column(length = 50)
        String name;
    }

    @Entity(name = "NestedExtrasItem")
    @Table(name = "nestedExtrasItems")
    static class NestedExtrasItem {
        @Id
        int id;

        Extras extras;
    }

    @Embeddable
    @Struct(name = "Extras")
    static class Extras {
        Country status;

        Set<Integer> ints;
    }

    @Entity(name = "CountrySetItem")
    @Table(name = "countrySetItems")
    static class CountrySetItem {
        @Id
        int id;

        Set<Country> countries;
    }

    @Test
    void testSharedColumnNullabilityIsPerEntity() {
        var export = inRegistry(List.of(NullabilitySharedA.class, NullabilitySharedB.class), Map.of(), session -> {
            sharedNullabilityItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 1,
                                "shared": "from-a"
                            }
                            """));
            sharedNullabilityItemsCollection.insertOne(
                    BsonDocument.parse(
                            """
                            {
                                "_id": 2
                            }
                            """));
            return sharedNullabilityItemsCollection.countDocuments();
        });
        assertThat(createCommands(export.commands()))
                .contains(BsonDocument.parse(
                                """
                                {"create": "sharedNullabilityItems", "validator": {"$jsonSchema": {
                                    "bsonType": "object",
                                    "anyOf": [
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"}, "shared": {"bsonType": ["string", "null"]}},
                                         "required": ["_id"],
                                         "additionalProperties": false},
                                        {"bsonType": "object",
                                         "properties": {"_id": {"bsonType": "int"}, "shared": {"bsonType": "string"}},
                                         "required": ["_id", "shared"],
                                         "additionalProperties": false}
                                    ]
                                }}}
                                """)
                        .toJson());
        assertThat(export.observed()).isEqualTo(2);
    }

    @Entity(name = "NullabilitySharedA")
    @Table(name = "sharedNullabilityItems")
    static class NullabilitySharedA {
        @Id
        int id;

        @Column(nullable = false)
        String shared;
    }

    @Entity(name = "NullabilitySharedB")
    @Table(name = "sharedNullabilityItems")
    static class NullabilitySharedB {
        @Id
        int id;

        String shared;
    }
}
