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

import static com.mongodb.hibernate.MongoTestAssertions.assertUsingRecursiveComparison;
import static java.util.Comparator.comparing;

import com.mongodb.hibernate.junit.MongoExtension;
import com.mongodb.hibernate.junit.MongoServiceRegistryProducer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A generated timestamp is produced in memory and then stored, so it is the case where the dialect's timestamp
 * precision has to match what a BSON {@code Date} can hold: a value carrying microseconds would survive in the
 * persistence context while the stored value lost them, and the two would disagree on re-read.
 *
 * <p>A generated {@link OffsetDateTime} keeps only its instant, like any other under the default {@code NORMALIZE_UTC}
 * strategy: it is produced at the JVM default zone and read back at UTC. That zone cannot be fixed for the test either,
 * because {@code ClockHelper} captures {@link java.time.Clock#systemDefaultZone()} in a static field at class load.
 */
@SessionFactory(exportSchema = false)
@DomainModel(annotatedClasses = {CreationTimestampIntegrationTests.Item.class})
@ExtendWith(MongoExtension.class)
class CreationTimestampIntegrationTests implements SessionFactoryScopeAware, MongoServiceRegistryProducer {

    private static final String COLLECTION_NAME = "items";
    private static final int ITEM_ID = 1;

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope sessionFactoryScope) {
        this.sessionFactoryScope = sessionFactoryScope;
    }

    @Test
    void testGeneratedTimestampsSurviveTheRoundTrip() {
        var item = new Item(ITEM_ID);
        sessionFactoryScope.inTransaction(session -> session.persist(item));
        var loadedItem = sessionFactoryScope.fromTransaction(session -> session.find(Item.class, ITEM_ID));

        assertUsingRecursiveComparison(item, loadedItem, (comparison, expected) -> comparison
                .withComparatorForType(comparing(OffsetDateTime::toInstant), OffsetDateTime.class)
                .isEqualTo(expected));
    }

    @Entity
    @Table(name = COLLECTION_NAME)
    static class Item {
        @Id
        int id;

        @CreationTimestamp
        Instant createdInstant;

        @UpdateTimestamp
        Instant updatedInstant;

        @CreationTimestamp
        OffsetDateTime createdOffsetDateTime;

        @UpdateTimestamp
        OffsetDateTime updatedOffsetDateTime;

        Item() {}

        Item(int id) {
            this.id = id;
        }
    }
}
