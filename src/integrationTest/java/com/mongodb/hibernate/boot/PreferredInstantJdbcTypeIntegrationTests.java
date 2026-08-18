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

package com.mongodb.hibernate.boot;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.cfg.AvailableSettings.JAKARTA_JDBC_URL;
import static org.hibernate.cfg.AvailableSettings.PREFERRED_INSTANT_JDBC_TYPE;

import com.mongodb.hibernate.junit.MongoServiceRegistryProducer;
import java.util.function.Consumer;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

/**
 * {@code hibernate.type.preferred_instant_jdbc_type} defaults to {@code TIMESTAMP_UTC}, the only value that keeps every
 * supported temporal type mapped onto a BSON {@code Date}; any other value is rejected at boot. The setting accepts
 * either the {@link SqlTypes} constant name or its numeric code, so both spellings are covered on both sides of the
 * decision.
 */
class PreferredInstantJdbcTypeIntegrationTests implements MongoServiceRegistryProducer {

    @Test
    void timestampRejectedAtBoot() {
        withRegistry("TIMESTAMP", registry -> assertThatThrownBy(() -> new MetadataSources(registry).buildMetadata())
                .hasMessageContaining(rejectionMessage("TIMESTAMP")));
    }

    @Test
    void instantRejectedAtBoot() {
        withRegistry("INSTANT", registry -> assertThatThrownBy(() -> new MetadataSources(registry).buildMetadata())
                .hasMessageContaining(rejectionMessage("INSTANT")));
    }

    @Test
    void timestampUtcBoots() {
        withRegistry("TIMESTAMP_UTC", registry -> assertThatCode(() -> new MetadataSources(registry).buildMetadata())
                .doesNotThrowAnyException());
    }

    @Test
    void numericTimestampUtcCodeBoots() {
        withRegistry(
                SqlTypes.TIMESTAMP_UTC, registry -> assertThatCode(() -> new MetadataSources(registry).buildMetadata())
                        .doesNotThrowAnyException());
    }

    @Test
    void numericTimestampCodeRejectedAtBoot() {
        withRegistry(
                SqlTypes.TIMESTAMP, registry -> assertThatThrownBy(() -> new MetadataSources(registry).buildMetadata())
                        .hasMessageContaining(rejectionMessage(SqlTypes.TIMESTAMP)));
    }

    @Test
    void numericTimestampUtcCodeAsStringBoots() {
        withRegistry(String.valueOf(SqlTypes.TIMESTAMP_UTC), registry -> assertThatCode(
                        () -> new MetadataSources(registry).buildMetadata())
                .doesNotThrowAnyException());
    }

    @Test
    void numericTimestampCodeAsStringRejectedAtBoot() {
        withRegistry(String.valueOf(SqlTypes.TIMESTAMP), registry -> assertThatThrownBy(
                        () -> new MetadataSources(registry).buildMetadata())
                .hasMessageContaining(rejectionMessage(SqlTypes.TIMESTAMP)));
    }

    @Test
    void absentSettingBoots() {
        var url = new Configuration().getProperties().getProperty(JAKARTA_JDBC_URL);
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(JAKARTA_JDBC_URL, url)
                .build();
        try {
            assertThatCode(() -> new MetadataSources(registry).buildMetadata()).doesNotThrowAnyException();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static String rejectionMessage(Object preferredInstantJdbcType) {
        return format(
                "The setting [%s] is set to [%s], but only [TIMESTAMP_UTC] (its default) is supported",
                PREFERRED_INSTANT_JDBC_TYPE, preferredInstantJdbcType);
    }

    private static void withRegistry(Object preferredInstantJdbcType, Consumer<StandardServiceRegistry> assertion) {
        var url = new Configuration().getProperties().getProperty(JAKARTA_JDBC_URL);
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(JAKARTA_JDBC_URL, url)
                .applySetting(PREFERRED_INSTANT_JDBC_TYPE, preferredInstantJdbcType)
                .build();
        try {
            assertion.accept(registry);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
