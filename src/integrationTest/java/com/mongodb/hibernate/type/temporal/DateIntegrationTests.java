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

import static com.mongodb.hibernate.type.UnsupportedTypeAssertions.assertNotSupported;
import static com.mongodb.hibernate.type.temporal.UnsupportedItems.DateItems;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import java.util.Date;
import org.hibernate.boot.MetadataSources;
import org.junit.jupiter.api.Test;

class DateIntegrationTests {

    @Test
    void unsupported() {
        assertAll(
                () -> assertNotSupported(DateItems.WithId.class),
                () -> assertNotSupported(DateItems.WithFlattenedEmbeddableId.class),
                () -> assertNotSupported(DateItems.WithBasicPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithArrayPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithCollectionPersistentAttribute.class),

                // Flattened Embeddable
                () -> assertNotSupported(DateItems.WithEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithEmbeddableWithCollectionPersistentAttribute.class),

                // Nested flattened embeddable
                () -> assertNotSupported(DateItems.WithNestedEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithNestedEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithNestedEmbeddableWithCollectionPersistentAttribute.class),

                // Aggregate embeddable
                () -> assertNotSupported(DateItems.WithAggregateEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithAggregateEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithAggregateEmbeddableWithCollectionPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithCollectionOfAggregateEmbeddable.class),

                // Nested aggregate embeddable
                () -> assertNotSupported(DateItems.WithNestedAggregateEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithNestedAggregateEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(
                        DateItems.WithNestedAggregateEmbeddableWithCollectionPersistentAttribute.class),
                () -> assertNotSupported(DateItems.WithNestedCollectionOfAggregateEmbeddable.class));
    }

    /**
     * {@link Temporal} applies only to {@link Date} and {@code Calendar}, so {@link java.sql.Timestamp} needs no such
     * case. Only {@link TemporalType#TIMESTAMP}, the default, denotes an instant.
     */
    @Test
    void temporalDateRejectedAtBoot() {
        assertThatThrownBy(() -> new MetadataSources()
                        .addAnnotatedClass(TemporalDateItem.class)
                        .buildMetadata())
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessageContaining("temporal precision [DATE] that is not supported");
    }

    @Test
    void temporalTimeRejectedAtBoot() {
        assertThatThrownBy(() -> new MetadataSources()
                        .addAnnotatedClass(TemporalTimeItem.class)
                        .buildMetadata())
                .isInstanceOf(FeatureNotSupportedException.class)
                .hasMessageContaining("temporal precision [TIME] that is not supported");
    }

    @Entity(name = "TemporalDateItem")
    @Table(name = "temporalDateItems")
    static class TemporalDateItem {
        @Id
        int id;

        @SuppressWarnings("deprecation")
        @Temporal(TemporalType.DATE)
        Date value;
    }

    @Entity(name = "TemporalTimeItem")
    @Table(name = "temporalTimeItems")
    static class TemporalTimeItem {
        @Id
        int id;

        @SuppressWarnings("deprecation")
        @Temporal(TemporalType.TIME)
        Date value;
    }
}
