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

package com.mongodb.hibernate.query.select.temporal;

import static com.mongodb.hibernate.internal.MongoAssertions.fail;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.testing.orm.junit.DomainModel;
import org.junit.jupiter.params.provider.Arguments;

/**
 * The seed values are expressed at UTC, which is the offset the read path reconstructs, so they double as the expected
 * results.
 */
@DomainModel(annotatedClasses = {SelectionOffsetDateTimeIntegrationTests.Item.class})
class SelectionOffsetDateTimeIntegrationTests
        extends AbstractSelectionTemporalIntegrationTests<
                SelectionOffsetDateTimeIntegrationTests.Item, OffsetDateTime> {

    private static final List<Item> ITEMS = List.of(
            new Item(1, OffsetDateTime.parse("2025-01-04T10:05:01Z")),
            new Item(2, OffsetDateTime.parse("2025-05-04T14:30:15Z")),
            new Item(3, OffsetDateTime.parse("2025-12-04T23:59:59Z")),
            new Item(4, null));

    private static List<Item> getTestItems(int... ids) {
        return Arrays.stream(ids)
                .mapToObj(id -> ITEMS.stream()
                        .filter(c -> c.id == id)
                        .findAny()
                        .orElseThrow(() -> fail("id does not exist: " + id)))
                .toList();
    }

    @Override
    List<Item> getSeedData() {
        return ITEMS;
    }

    @Override
    Class<Item> getItemClass() {
        return Item.class;
    }

    @Override
    Class<OffsetDateTime> getTemporalClass() {
        return OffsetDateTime.class;
    }

    private static Stream<Arguments> testProjection() {
        return Stream.of(Arguments.of(Arrays.asList(
                OffsetDateTime.parse("2025-01-04T10:05:01Z"),
                OffsetDateTime.parse("2025-05-04T14:30:15Z"),
                OffsetDateTime.parse("2025-12-04T23:59:59Z"),
                null)));
    }

    private static Stream<Arguments> testIsNull() {
        return Stream.of(Arguments.of(getTestItems(4)));
    }

    private static Stream<Arguments> testIsNotNull() {
        return Stream.of(Arguments.of(getTestItems(1, 2, 3)));
    }

    private static Stream<Arguments> testComparisonByIn() {
        return Stream.of(Arguments.of(
                List.of(OffsetDateTime.parse("2025-01-04T10:05:01Z"), OffsetDateTime.parse("2025-12-04T23:59:59Z")),
                getTestItems(1, 3),
                """
                [{"$date": "2025-01-04T10:05:01Z"}, {"$date": "2025-12-04T23:59:59Z"}]"""));
    }

    private static Stream<Arguments> testComparisonByEq() {
        return Stream.of(Arguments.of(
                OffsetDateTime.parse("2025-01-04T10:05:01Z"),
                getTestItems(1),
                """
                {"$date": "2025-01-04T10:05:01Z"}"""));
    }

    private static Stream<Arguments> testComparisonByNe() {
        return Stream.of(Arguments.of(
                OffsetDateTime.parse("2025-01-04T10:05:01Z"),
                getTestItems(2, 3, 4),
                """
                {"$date": "2025-01-04T10:05:01Z"}"""));
    }

    private static Stream<Arguments> testComparisonByLt() {
        return Stream.of(Arguments.of(
                OffsetDateTime.parse("2025-12-04T23:59:59Z"),
                getTestItems(1, 2),
                """
                {"$date": "2025-12-04T23:59:59Z"}"""));
    }

    private static Stream<Arguments> testComparisonByLte() {
        return Stream.of(Arguments.of(
                OffsetDateTime.parse("2025-12-04T23:59:59Z"),
                getTestItems(1, 2, 3),
                """
                {"$date": "2025-12-04T23:59:59Z"}"""));
    }

    private static Stream<Arguments> testComparisonByGt() {
        return Stream.of(Arguments.of(
                OffsetDateTime.parse("2025-01-04T10:05:01Z"),
                getTestItems(2, 3),
                """
                {"$date": "2025-01-04T10:05:01Z"}"""));
    }

    private static Stream<Arguments> testComparisonByGte() {
        return Stream.of(Arguments.of(
                OffsetDateTime.parse("2025-01-04T10:05:01Z"),
                getTestItems(1, 2, 3),
                """
                {"$date": "2025-01-04T10:05:01Z"}"""));
    }

    private static Stream<Arguments> testOrderByAsc() {
        return Stream.of(Arguments.of(getTestItems(4, 1, 2, 3)));
    }

    private static Stream<Arguments> testOrderByDesc() {
        return Stream.of(Arguments.of(getTestItems(3, 2, 1, 4)));
    }

    @Entity(name = "Item")
    @Table(name = COLLECTION_NAME)
    static class Item {
        @Id
        int id;

        OffsetDateTime temporal;

        Item() {}

        Item(int id, OffsetDateTime temporal) {
            this.id = id;
            this.temporal = temporal;
        }

        @Override
        public String toString() {
            return "Item{" + "id=" + id + ", temporal=" + temporal + '}';
        }
    }
}
