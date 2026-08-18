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

package com.mongodb.hibernate.query.mutation;

import static com.mongodb.hibernate.BasicCrudIntegrationTests.Item.COLLECTION_NAME;
import static java.lang.String.format;

import com.mongodb.client.MongoCollection;
import com.mongodb.hibernate.embeddable.StructAggregateEmbeddableIntegrationTests;
import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.InjectMongoCollection;
import com.mongodb.hibernate.junit.MongoServiceRegistryProducer;
import com.mongodb.hibernate.query.AbstractQueryIntegrationTests;
import com.mongodb.hibernate.query.Book;
import com.mongodb.hibernate.query.select.QueryLiteralConstants;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Struct;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.testing.orm.junit.DomainModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DomainModel(
        annotatedClasses = {
            Book.class,
            UpdatingIntegrationTests.ItemWithNestedValue.class,
            UpdatingIntegrationTests.ItemWithPair.class
        })
class UpdatingIntegrationTests extends AbstractQueryIntegrationTests {

    @InjectMongoCollection(Book.COLLECTION_NAME)
    private MongoCollection<BsonDocument> booksCollection;

    @InjectMongoCollection(COLLECTION_NAME)
    private MongoCollection<BsonDocument> itemsCollection;

    private static final List<Book> testingBooks = List.of(
            new Book(1, "War & Peace", 1869, true),
            new Book(2, "Crime and Punishment", 1866, false),
            new Book(3, "Anna Karenina", 1877, false),
            new Book(4, "The Brothers Karamazov", 1880, false),
            new Book(5, "War & Peace", 2025, false));

    @BeforeEach
    void beforeEach() {
        getSessionFactoryScope().inTransaction(session -> testingBooks.forEach(session::persist));
    }

    @Test
    void testUpdateWithNonZeroMutationCount() {
        assertMutationQuery(
                "update Book set title = :newTitle, outOfStock = false where title = :oldTitle",
                q -> q.setParameter("oldTitle", "War & Peace").setParameter("newTitle", "War and Peace"),
                2,
                """
                {
                   "update": "books",
                   "updates": [
                     {
                       "multi": true,
                       "q": {
                         "title": {
                           "$eq": "War & Peace"
                         }
                       },
                       "u": {
                         "$set": {
                           "title": "War and Peace",
                           "outOfStock": false
                         }
                       }
                     }
                   ]
                }
                """,
                booksCollection,
                List.of(
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 1,
                                  "title": "War and Peace",
                                  "outOfStock": false,
                                  "publishYear": 1869,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 2,
                                  "title": "Crime and Punishment",
                                  "outOfStock": false,
                                  "publishYear": 1866,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 3,
                                  "title": "Anna Karenina",
                                  "outOfStock": false,
                                  "publishYear": 1877,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 4,
                                  "title": "The Brothers Karamazov",
                                  "outOfStock": false,
                                  "publishYear": 1880,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 5,
                                  "title": "War and Peace",
                                  "outOfStock": false,
                                  "publishYear": 2025,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """)),
                Set.of(Book.COLLECTION_NAME));
    }

    // A CASE assignment is a computed value, so the update is emitted as a $set pipeline stage whose value
    // is the $switch translation of the CASE.
    @Test
    void testCaseExpressionAssignment() {
        assertMutationQuery(
                "update Book b set b.publishYear = case when b.id = 1 then 100 else 200 end where b.id = 1",
                1,
                """
                {
                  "update": "books",
                  "updates": [
                    {
                      "q": {"_id": {"$eq": 1}},
                      "u": [
                        {
                          "$set": {
                            "publishYear": {
                              "$switch": {
                                "branches": [
                                  {"case": {"$eq": ["$_id", 1]}, "then": 100}
                                ],
                                "default": 200
                              }
                            }
                          }
                        }
                      ],
                      "multi": true
                    }
                  ]
                }""",
                booksCollection,
                List.of(
                        BsonDocument.parse(
                                """
                                {"_id": 1, "title": "War & Peace", "outOfStock": true, "publishYear": 100, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 2, "title": "Crime and Punishment", "outOfStock": false, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": 1877, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 5, "title": "War & Peace", "outOfStock": false, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                Set.of(Book.COLLECTION_NAME));
    }

    @Test
    void testFunctionExpressionAssignment() {
        var hql = "update Book b set b.title = upper(b.title) where b.id = 1";
        assertMutationQuery(
                hql,
                query -> {},
                1,
                """
                {
                    "update": "books",
                    "updates": [
                        {
                            "q": {"_id": {"$eq": {"$numberInt": "1"}}},
                            "u": [{"$set": {"title": {"$toUpper": "$title"}}}],
                            "multi": true
                        }
                    ]
                }
                """,
                booksCollection,
                List.of(
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 1,
                                  "title": "WAR & PEACE",
                                  "outOfStock": true,
                                  "publishYear": 1869,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 2,
                                  "title": "Crime and Punishment",
                                  "outOfStock": false,
                                  "publishYear": 1866,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 3,
                                  "title": "Anna Karenina",
                                  "outOfStock": false,
                                  "publishYear": 1877,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 4,
                                  "title": "The Brothers Karamazov",
                                  "outOfStock": false,
                                  "publishYear": 1880,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 5,
                                  "title": "War & Peace",
                                  "outOfStock": false,
                                  "publishYear": 2025,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """)),
                Set.of(Book.COLLECTION_NAME));
    }

    @Test
    void testUpdateWithZeroMutationCount() {
        assertMutationQuery(
                "update Book set outOfStock = false where publishYear < :year",
                q -> q.setParameter("year", 1850),
                0,
                """
                {
                   "update": "books",
                   "updates": [
                     {
                       "multi": true,
                       "q": {
                         "publishYear": {
                           "$lt": 1850
                         }
                       },
                       "u": {
                         "$set": {
                           "outOfStock": false
                         }
                       }
                     }
                   ]
                }
                """,
                booksCollection,
                List.of(
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 1,
                                  "title": "War & Peace",
                                  "outOfStock": true,
                                  "publishYear": 1869,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 2,
                                  "title": "Crime and Punishment",
                                  "outOfStock": false,
                                  "publishYear": 1866,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 3,
                                  "title": "Anna Karenina",
                                  "outOfStock": false,
                                  "publishYear": 1877,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 4,
                                  "title": "The Brothers Karamazov",
                                  "outOfStock": false,
                                  "publishYear": 1880,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 5,
                                  "title": "War & Peace",
                                  "outOfStock": false,
                                  "publishYear": 2025,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """)),
                Set.of(Book.COLLECTION_NAME));
    }

    @Test
    void testUpdateNoFilter() {
        assertMutationQuery(
                "update Book set title = :newTitle",
                q -> q.setParameter("newTitle", "Unknown"),
                5,
                """
                {
                   "update": "books",
                   "updates": [
                     {
                       "multi": true,
                       "q": {},
                       "u": {
                         "$set": {
                           "title": "Unknown"
                         }
                       }
                     }
                   ]
                }
                """,
                booksCollection,
                List.of(
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 1,
                                  "title": "Unknown",
                                  "outOfStock": true,
                                  "publishYear": 1869,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 2,
                                  "title": "Unknown",
                                  "outOfStock": false,
                                  "publishYear": 1866,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 3,
                                  "title": "Unknown",
                                  "outOfStock": false,
                                  "publishYear": 1877,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 4,
                                  "title": "Unknown",
                                  "outOfStock": false,
                                  "publishYear": 1880,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """),
                        BsonDocument.parse(
                                """
                                {
                                  "_id": 5,
                                  "title": "Unknown",
                                  "outOfStock": false,
                                  "publishYear": 2025,
                                  "isbn13": null,
                                  "discount": null,
                                  "price": null
                                }
                                """)),
                Set.of(Book.COLLECTION_NAME));
    }

    @Test
    void testUpdateMutationCountIsMatchedCount() {
        assertMutationQuery(
                "update Book set outOfStock = false where id = 1 or id = 2",
                2,
                """
                {
                   "update": "books",
                   "updates": [
                     {
                       "multi": true,
                       "q": {
                         "$or": [
                           {"_id": {"$eq": 1}},
                           {"_id": {"$eq": 2}}
                         ]
                       },
                       "u": {
                         "$set": {
                           "outOfStock": false
                         }
                       }
                     }
                   ]
                }
                """,
                booksCollection,
                List.of(
                        BsonDocument.parse(
                                """
                                {"_id": 1, "title": "War & Peace", "outOfStock": false, "publishYear": 1869, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 2, "title": "Crime and Punishment", "outOfStock": false, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": 1877, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                        BsonDocument.parse(
                                """
                                {"_id": 5, "title": "War & Peace", "outOfStock": false, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                Set.of(Book.COLLECTION_NAME));
    }

    @Nested
    class Unsupported implements MongoServiceRegistryProducer {

        @Test
        void testScalarSubqueryAssignment() {
            var hql = "update Book b set b.publishYear = (select max(b2.publishYear) from Book b2) where b.id = 1";
            assertMutationQueryFailure(hql, query -> {}, FeatureNotSupportedException.class, "Subquery not supported");
        }
    }

    @Nested
    class StructAggregateEmbeddablePathExpressionTests implements MongoServiceRegistryProducer {

        @BeforeEach
        void seed() {
            getSessionFactoryScope()
                    .inTransaction(session -> session.persist(
                            new ItemWithNestedValue(1, new StructAggregateEmbeddableIntegrationTests.Single(7))));
        }

        @Test
        void testStructAggregateEmbeddablePathExpressionAssignment() {
            assertMutationQuery(
                    "update ItemWithNestedValue set nested.a = 0",
                    1,
                    """
                    {
                      "update": "items",
                      "updates": [
                        {
                          "q": {},
                          "u": {
                            "$set": {
                              "nested.a": 0
                            }
                          },
                          "multi": true
                        }
                      ]
                    }""",
                    itemsCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {
                                      "_id": 1,
                                      "nested": {
                                        "a": 0
                                      }
                                    }""")),
                    Set.of(COLLECTION_NAME));
        }

        @Test
        void testStructAggregateEmbeddableComputedAssignment() {
            assertMutationQuery(
                    "update ItemWithNestedValue set nested.a = nested.a + 1",
                    1,
                    """
                    {
                      "update": "items",
                      "updates": [
                        {
                          "q": {},
                          "u": [{"$set": {"nested.a": {"$add": ["$nested.a", 1]}}}],
                          "multi": true
                        }
                      ]
                    }""",
                    itemsCollection,
                    List.of(BsonDocument.parse("""
                            {"_id": 1, "nested": {"a": 8}}""")),
                    Set.of(COLLECTION_NAME));
        }
    }

    @Nested
    class StructAggregateEmbeddableMultiFieldPathExpressionTests implements MongoServiceRegistryProducer {

        @BeforeEach
        void seed() {
            getSessionFactoryScope().inTransaction(session -> session.persist(new ItemWithPair(1, new Pair(10, 20))));
        }

        @Test
        void testStructAggregateEmbeddableMultiFieldAssignment() {
            assertMutationQuery(
                    "update ItemWithPair set pair.a = 1, pair.b = 2",
                    1,
                    """
                    {
                      "update": "items",
                      "updates": [
                        {
                          "q": {},
                          "u": {
                            "$set": {
                              "pair.a": 1,
                              "pair.b": 2
                            }
                          },
                          "multi": true
                        }
                      ]
                    }""",
                    itemsCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {
                                      "_id": 1,
                                      "pair": {
                                        "a": 1,
                                        "b": 2
                                      }
                                    }""")),
                    Set.of(COLLECTION_NAME));
        }
    }

    @Nested
    class ComputedExpressionAssignment implements MongoServiceRegistryProducer {

        @Test
        void testArithmeticAssignment() {
            assertMutationQuery(
                    "update Book b set b.publishYear = b.publishYear + 1 where b.id = 1",
                    1,
                    """
                    {
                      "update": "books",
                      "updates": [
                        {
                          "q": {"_id": {"$eq": 1}},
                          "u": [{"$set": {"publishYear": {"$add": ["$publishYear", 1]}}}],
                          "multi": true
                        }
                      ]
                    }""",
                    booksCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {"_id": 1, "title": "War & Peace", "outOfStock": true, "publishYear": 1870, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 2, "title": "Crime and Punishment", "outOfStock": false, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": 1877, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 5, "title": "War & Peace", "outOfStock": false, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                    Set.of(Book.COLLECTION_NAME));
        }

        @Test
        void testFieldReferenceAssignment() {
            assertMutationQuery(
                    "update Book b set b.publishYear = b.isbn13 where b.id = 3",
                    1,
                    """
                    {
                      "update": "books",
                      "updates": [
                        {
                          "q": {"_id": {"$eq": 3}},
                          "u": [{"$set": {"publishYear": "$isbn13"}}],
                          "multi": true
                        }
                      ]
                    }""",
                    booksCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {"_id": 1, "title": "War & Peace", "outOfStock": true, "publishYear": 1869, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 2, "title": "Crime and Punishment", "outOfStock": false, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": null, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 5, "title": "War & Peace", "outOfStock": false, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                    Set.of(Book.COLLECTION_NAME));
        }

        @Test
        void testComparisonAssignment() {
            assertMutationQuery(
                    "update Book b set b.outOfStock = (b.publishYear > 2000) where b.id = 5",
                    1,
                    """
                    {
                      "update": "books",
                      "updates": [
                        {
                          "q": {"_id": {"$eq": 5}},
                          "u": [{"$set": {"outOfStock": {"$gt": ["$publishYear", 2000]}}}],
                          "multi": true
                        }
                      ]
                    }""",
                    booksCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {"_id": 1, "title": "War & Peace", "outOfStock": true, "publishYear": 1869, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 2, "title": "Crime and Punishment", "outOfStock": false, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": 1877, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 5, "title": "War & Peace", "outOfStock": true, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                    Set.of(Book.COLLECTION_NAME));
        }

        @Test
        void testPredicateFamilyAssignment() {
            assertMutationQuery(
                    "update Book b set b.outOfStock = (b.publishYear > 1800 and b.publishYear < 1900) where b.id = 2",
                    1,
                    """
                    {
                      "update": "books",
                      "updates": [
                        {
                          "q": {"_id": {"$eq": 2}},
                          "u": [{"$set": {"outOfStock": {"$and": [{"$gt": ["$publishYear", 1800]}, {"$lt": ["$publishYear", 1900]}]}}}],
                          "multi": true
                        }
                      ]
                    }""",
                    booksCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {"_id": 1, "title": "War & Peace", "outOfStock": true, "publishYear": 1869, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 2, "title": "Crime and Punishment", "outOfStock": true, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": 1877, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 5, "title": "War & Peace", "outOfStock": false, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                    Set.of(Book.COLLECTION_NAME));
        }

        @Test
        void testMixedValueAndComputedAssignment() {
            assertMutationQuery(
                    "update Book b set b.title = :t, b.outOfStock = false, b.publishYear = b.publishYear + 1 where b.id = 1",
                    q -> q.setParameter("t", "New Title"),
                    1,
                    """
                    {
                      "update": "books",
                      "updates": [
                        {
                          "q": {"_id": {"$eq": 1}},
                          "u": [{"$set": {"title": {"$literal": "New Title"}, "outOfStock": false, "publishYear": {"$add": ["$publishYear", 1]}}}],
                          "multi": true
                        }
                      ]
                    }""",
                    booksCollection,
                    List.of(
                            BsonDocument.parse(
                                    """
                                    {"_id": 1, "title": "New Title", "outOfStock": false, "publishYear": 1870, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 2, "title": "Crime and Punishment", "outOfStock": false, "publishYear": 1866, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 3, "title": "Anna Karenina", "outOfStock": false, "publishYear": 1877, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 4, "title": "The Brothers Karamazov", "outOfStock": false, "publishYear": 1880, "isbn13": null, "discount": null, "price": null}"""),
                            BsonDocument.parse(
                                    """
                                    {"_id": 5, "title": "War & Peace", "outOfStock": false, "publishYear": 2025, "isbn13": null, "discount": null, "price": null}""")),
                    Set.of(Book.COLLECTION_NAME));
        }
    }

    @Test
    void testColumnTransformerWriteExpressionThrows() {
        assertBootstrapThrows(() -> new MetadataSources()
                        .addAnnotatedClass(ItemWithColumnTransformer.class)
                        .buildMetadata(new StandardServiceRegistryBuilder().build())
                        .buildSessionFactory())
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessage("@ColumnTransformer expressions are not supported");
    }

    @Entity(name = "ItemWithColumnTransformer")
    @Table(name = COLLECTION_NAME)
    static class ItemWithColumnTransformer {
        @Id
        int id;

        @ColumnTransformer(write = "test(?)")
        String value;

        ItemWithColumnTransformer() {}
    }

    @Entity(name = "ItemWithNestedValue")
    @Table(name = COLLECTION_NAME)
    static class ItemWithNestedValue {
        @Id
        int id;

        StructAggregateEmbeddableIntegrationTests.Single nested;

        ItemWithNestedValue() {}

        ItemWithNestedValue(int id, StructAggregateEmbeddableIntegrationTests.Single nested) {
            this.id = id;
            this.nested = nested;
        }
    }

    @Embeddable
    @Struct(name = "Pair")
    static class Pair {
        int a;
        int b;

        Pair() {}

        Pair(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    @Entity(name = "ItemWithPair")
    @Table(name = COLLECTION_NAME)
    static class ItemWithPair {
        @Id
        int id;

        Pair pair;

        ItemWithPair() {}

        ItemWithPair(int id, Pair pair) {
            this.id = id;
            this.pair = pair;
        }
    }

    /**
     * Every instant-like type has to render as a BSON {@code Date} in a {@code $set}, from a parameter and from an
     * inlined literal alike. The entity's three attributes are seeded to one instant and each update moves exactly one
     * of them to another, so the asserted document also shows that the other two are left alone.
     */
    @Nested
    @DomainModel(annotatedClasses = TemporalItem.class)
    class Temporal extends AbstractQueryIntegrationTests {

        private static final String CONSTANTS = QueryLiteralConstants.class.getName();

        @InjectMongoCollection(TemporalItem.COLLECTION_NAME)
        private MongoCollection<BsonDocument> temporalItemsCollection;

        @BeforeEach
        void seed() {
            getSessionFactoryScope().inTransaction(session -> session.persist(new TemporalItem(1)));
        }

        @ParameterizedTest(name = "testUpdateSetFromParameter: {0}")
        @MethodSource("temporalAttributes")
        void testUpdateSetFromParameter(String attribute, Object value, String constant) {
            assertMutationQuery(
                    format("update TemporalItem set %s = :t", attribute),
                    q -> q.setParameter("t", value),
                    1,
                    expectedMql(attribute),
                    temporalItemsCollection,
                    List.of(expectedDocument(attribute)),
                    Set.of(TemporalItem.COLLECTION_NAME));
        }

        @ParameterizedTest(name = "testUpdateSetFromLiteral: {0}")
        @MethodSource("temporalAttributes")
        void testUpdateSetFromLiteral(String attribute, Object value, String constant) {
            assertMutationQuery(
                    format("update TemporalItem set %s = %s.%s", attribute, CONSTANTS, constant),
                    1,
                    expectedMql(attribute),
                    temporalItemsCollection,
                    List.of(expectedDocument(attribute)),
                    Set.of(TemporalItem.COLLECTION_NAME));
        }

        private static Stream<Arguments> temporalAttributes() {
            return Stream.of(
                    Arguments.of("instantValue", Instant.parse(TemporalItem.UPDATED), "INSTANT"),
                    Arguments.of("offsetDateTimeValue", OffsetDateTime.parse(TemporalItem.UPDATED), "OFFSET_DATE_TIME"),
                    Arguments.of("zonedDateTimeValue", ZonedDateTime.parse(TemporalItem.UPDATED), "ZONED_DATE_TIME"));
        }

        private static String expectedMql(String attribute) {
            return format(
                    """
                    {
                      "update": "temporalItems",
                      "updates": [
                        {
                          "q": {},
                          "u": {"$set": {"%s": {"$date": "%s"}}},
                          "multi": true
                        }
                      ]
                    }""",
                    attribute, TemporalItem.UPDATED);
        }

        private static BsonDocument expectedDocument(String updatedAttribute) {
            var document = new BsonDocument().append("_id", new BsonInt32(1));
            temporalAttributes()
                    .map(arguments -> (String) arguments.get()[0])
                    .forEach(attribute -> document.append(
                            attribute,
                            new BsonDateTime(Instant.parse(
                                            attribute.equals(updatedAttribute)
                                                    ? TemporalItem.UPDATED
                                                    : TemporalItem.SEEDED)
                                    .toEpochMilli())));
            return document;
        }
    }

    @Entity(name = "TemporalItem")
    @Table(name = TemporalItem.COLLECTION_NAME)
    static class TemporalItem {
        static final String COLLECTION_NAME = "temporalItems";
        static final String SEEDED = "2025-05-04T14:30:15Z";

        /** The value every {@link QueryLiteralConstants} temporal constant holds. */
        static final String UPDATED = "2025-01-04T10:05:01Z";

        @Id
        int id;

        Instant instantValue;
        OffsetDateTime offsetDateTimeValue;
        ZonedDateTime zonedDateTimeValue;

        TemporalItem() {}

        TemporalItem(int id) {
            this.id = id;
            this.instantValue = Instant.parse(SEEDED);
            this.offsetDateTimeValue = OffsetDateTime.parse(SEEDED);
            this.zonedDateTimeValue = ZonedDateTime.parse(SEEDED);
        }
    }
}
