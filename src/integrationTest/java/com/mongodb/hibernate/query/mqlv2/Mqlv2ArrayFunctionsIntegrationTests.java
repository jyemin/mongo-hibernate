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

package com.mongodb.hibernate.query.mqlv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.cfg.AvailableSettings.DIALECT;
import static org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.bson.BsonDocument;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@DomainModel(annotatedClasses = {Mqlv2ArrayFunctionsIntegrationTests.ArrayDoc.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture"),
            @Setting(name = "mongo.hibernate.mqlv2.enabled", value = "true")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2ArrayFunctionsIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from ArrayDoc").executeUpdate();
            session.persist(new ArrayDoc(1, new int[] {10, 20, 30}));
            session.persist(new ArrayDoc(2, new int[] {30, 40}));
            session.persist(new ArrayDoc(3, new int[] {}));
        });
        MqlCapture.LAST.remove();
    }

    private String capturedPipeline() {
        var captured = MqlCapture.LAST.get();
        assertThat(captured).isNotNull();
        return BsonDocument.parse(captured).getString("mqlv2").getValue();
    }

    @Test
    void arrayLength() {
        var hql = "from ArrayDoc d where array_length(d.scores) > 2";
        var rows = sessionFactoryScope.fromSession(session ->
                session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (count(scores) > 2)"
                        + " | format {_id: _id, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(1);
    }

    @Test
    void cardinalityAlias() {
        var hql = "from ArrayDoc d where cardinality(d.scores) = 0";
        var rows = sessionFactoryScope.fromSession(session ->
                session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (count(scores) == 0)"
                        + " | format {_id: _id, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(3);
    }

    @Entity(name = "ArrayDoc")
    @Table(name = "array_docs")
    static class ArrayDoc {
        @Id
        int id;

        int[] scores;

        ArrayDoc() {}

        ArrayDoc(int id, int[] scores) {
            this.id = id;
            this.scores = scores;
        }
    }
}
