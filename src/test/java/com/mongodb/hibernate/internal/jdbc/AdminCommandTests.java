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

package com.mongodb.hibernate.internal.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import java.sql.SQLFeatureNotSupportedException;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCommandTests {

    @Mock
    private MongoDatabase mongoDatabase;

    @Test
    void testCreateCommandWithValidator() throws SQLFeatureNotSupportedException {
        var command = AdminCommand.toAdminCommand(
                """
                { "create": "items", "validator": { "$jsonSchema": { "bsonType": "object" } } }
                """);
        command.execute(mongoDatabase);

        var optionsCaptor = ArgumentCaptor.forClass(CreateCollectionOptions.class);
        verify(mongoDatabase).createCollection(eq("items"), optionsCaptor.capture());
        assertEquals(
                BsonDocument.parse("{ \"$jsonSchema\": { \"bsonType\": \"object\" } }"),
                optionsCaptor.getValue().getValidationOptions().getValidator());
    }

    @Test
    void testCreateCommandWithoutValidator() throws SQLFeatureNotSupportedException {
        var command = AdminCommand.toAdminCommand("""
                { "create": "items" }
                """);
        command.execute(mongoDatabase);

        var optionsCaptor = ArgumentCaptor.forClass(CreateCollectionOptions.class);
        verify(mongoDatabase).createCollection(eq("items"), optionsCaptor.capture());
        assertNull(optionsCaptor.getValue().getValidationOptions().getValidator());
    }

    @Test
    void testCreateCommandRejectsUnknownOption() {
        var exception = assertThrows(
                SQLFeatureNotSupportedException.class,
                () -> AdminCommand.toAdminCommand(
                        """
                        { "create": "items", "collation": { "locale": "en" } }
                        """));
        assertEquals("Cannot decode command create: unknown option collation", exception.getMessage());
    }
}
