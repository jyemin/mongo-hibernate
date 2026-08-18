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
import static com.mongodb.hibernate.type.temporal.UnsupportedItems.LocalDateItems;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Test;

class LocalDateIntegrationTests {

    @Test
    void unsupported() {
        assertAll(
                () -> assertNotSupported(LocalDateItems.WithId.class),
                () -> assertNotSupported(LocalDateItems.WithFlattenedEmbeddableId.class),
                () -> assertNotSupported(LocalDateItems.WithBasicPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithArrayPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithCollectionPersistentAttribute.class),

                // Flattened Embeddable
                () -> assertNotSupported(LocalDateItems.WithEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithEmbeddableWithCollectionPersistentAttribute.class),

                // Nested flattened embeddable
                () -> assertNotSupported(LocalDateItems.WithNestedEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithNestedEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithNestedEmbeddableWithCollectionPersistentAttribute.class),

                // Aggregate embeddable
                () -> assertNotSupported(LocalDateItems.WithAggregateEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithAggregateEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithAggregateEmbeddableWithCollectionPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithCollectionOfAggregateEmbeddable.class),

                // Nested aggregate embeddable
                () -> assertNotSupported(
                        LocalDateItems.WithNestedAggregateEmbeddableWithBasicPersistentAttribute.class),
                () -> assertNotSupported(
                        LocalDateItems.WithNestedAggregateEmbeddableWithArrayPersistentAttribute.class),
                () -> assertNotSupported(
                        LocalDateItems.WithNestedAggregateEmbeddableWithCollectionPersistentAttribute.class),
                () -> assertNotSupported(LocalDateItems.WithNestedCollectionOfAggregateEmbeddable.class));
    }
}
