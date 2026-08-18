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

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.annotations.Struct;
import org.hibernate.testing.orm.junit.DomainModel;
import org.junit.jupiter.params.provider.Arguments;

@DomainModel(annotatedClasses = {InstantIntegrationTests.Item.class})
class InstantIntegrationTests extends AbstractTemporalRoundTripIntegrationTests<InstantIntegrationTests.Item, Instant> {

    private static Stream<Arguments> persistAndReadParameters() {
        return differentTimeZones().flatMap(arguments -> {
            var tz0 = (ZoneId) arguments.get()[0];
            var tz1 = (ZoneId) arguments.get()[1];
            return Stream.of(
                    // We support millisecond precision, so sub-millisecond values are rounded, halves up.
                    Arguments.of(
                            tz0,
                            tz1,
                            Instant.parse("2007-12-03T10:15:30.002900000Z"),
                            Instant.parse("2007-12-03T10:15:30.003000000Z")),
                    Arguments.of(
                            tz0,
                            tz1,
                            Instant.parse("2007-12-03T10:15:30.002400000Z"),
                            Instant.parse("2007-12-03T10:15:30.002000000Z")),
                    Arguments.of(
                            tz0,
                            tz1,
                            Instant.parse("2007-12-03T10:15:30.002500000Z"),
                            Instant.parse("2007-12-03T10:15:30.003000000Z")),
                    Arguments.of(
                            tz0, tz1, Instant.parse("1500-12-03T10:15:30Z"), Instant.parse("1500-12-03T10:15:30Z")),
                    Arguments.of(
                            tz0,
                            tz1,
                            Instant.parse("1500-12-03T10:15:30.002500000Z"),
                            Instant.parse("1500-12-03T10:15:30.003000000Z")),
                    Arguments.of(
                            tz0,
                            tz1,
                            Instant.parse("-000001-12-03T10:15:30Z"),
                            Instant.parse("-000001-12-03T10:15:30Z")));
        });
    }

    @Override
    Class<Item> getItemClass() {
        return Item.class;
    }

    @Override
    Item newItem(int id, Instant value) {
        return new Item(id, value);
    }

    @Override
    Instant getStoredValue() {
        return Instant.parse("2026-08-09T08:15:30.003Z");
    }

    @Override
    String getExpectedStoredDocument() {
        return """
               {
                   _id: 1,
                   value: {"$date": "2026-08-09T08:15:30.003Z"},
                   valueCollection: [{"$date": "2026-08-09T08:15:30.003Z"}, {"$date": "2026-08-09T08:15:30.003Z"}],
                   values: [{"$date": "2026-08-09T08:15:30.003Z"}, {"$date": "2026-08-09T08:15:30.003Z"}],
                   aggregateEmbeddable: {value: {"$date": "2026-08-09T08:15:30.003Z"}},
                   flattenedValue: {"$date": "2026-08-09T08:15:30.003Z"}
               }
               """;
    }

    @Entity
    @Table(name = COLLECTION_NAME)
    static class Item {
        @Id
        int id;

        Instant value;
        Collection<Instant> valueCollection;
        Instant[] values;
        AggregateEmbeddable aggregateEmbeddable;
        FlattenedEmbeddable flattenedEmbeddable;

        Item() {}

        Item(int id, Instant value) {
            this.id = id;
            this.value = value;
            this.valueCollection = List.of(value, value);
            this.values = valueCollection.toArray(new Instant[] {});
            this.aggregateEmbeddable = new AggregateEmbeddable(value);
            this.flattenedEmbeddable = new FlattenedEmbeddable(value);
        }
    }

    @Embeddable
    static class FlattenedEmbeddable {
        Instant flattenedValue;

        FlattenedEmbeddable() {}

        FlattenedEmbeddable(Instant value) {
            flattenedValue = value;
        }
    }

    @Embeddable
    @Struct(name = "InstantAggregateEmbeddable")
    static class AggregateEmbeddable {
        Instant value;

        AggregateEmbeddable() {}

        AggregateEmbeddable(Instant value) {
            this.value = value;
        }
    }
}
