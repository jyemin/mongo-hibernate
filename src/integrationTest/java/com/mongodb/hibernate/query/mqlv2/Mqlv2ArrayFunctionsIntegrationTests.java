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
            session.persist(new ArrayDoc(1, new int[] {10, 20, 30}, new Integer[] {}));
            session.persist(new ArrayDoc(2, new int[] {30, 40}, new Integer[] {}));
            session.persist(new ArrayDoc(3, new int[] {}, new Integer[] {}));
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
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (count(scores) > 2)"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(1);
    }

    @Test
    void cardinalityAlias() {
        var hql = "from ArrayDoc d where cardinality(d.scores) = 0";
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (count(scores) == 0)"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(3);
    }

    @Test
    void arrayGet() {
        var hql = "from ArrayDoc d where array_get(d.scores, 1) = 10";
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (scores[(1 - 1)] == 10)"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(1);
    }

    @Test
    void arrayContains() {
        var hql = "from ArrayDoc d where array_contains(d.scores, 30)";
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (scores any ($ == 30))"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void arrayContainsNegated() {
        var hql = "from ArrayDoc d where not array_contains(d.scores, 30)";
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs | match (not (scores any ($ == 30)))"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactly(3);
    }

    @Test
    void arrayContainsNullableWithNullParameter() {
        sessionFactoryScope.inTransaction(
                session -> session.persist(new ArrayDoc(4, new int[] {0}, new Integer[] {null, 50})));
        var hql = "from ArrayDoc d where array_contains_nullable(d.boxedScores, :needle)";
        var rows = sessionFactoryScope.fromSession(session -> session.createSelectionQuery(hql, ArrayDoc.class)
                .setParameter("needle", (Integer) null)
                .getResultList());
        assertThat(capturedPipeline()).contains("(boxedScores any ($ is $p0))");
        assertThat(rows).extracting(d -> d.id).containsExactly(4);
    }

    @Test
    void arrayIntersects() {
        var hql = "from ArrayDoc d where array_intersects(d.scores, array(30, 99))";
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs"
                        + " | match (scores any (let $__x = $ in [30, 99] any ($ == $__x)))"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void arrayOverlapsAlias() {
        // Asserts that array_overlaps canonicalizes to the same intercept as array_intersects.
        var hql = "from ArrayDoc d where array_overlaps(d.scores, array(10, 40))";
        var rows = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, ArrayDoc.class).getResultList());
        assertThat(capturedPipeline())
                .isEqualTo("from $array_docs"
                        + " | match (scores any (let $__x = $ in [10, 40] any ($ == $__x)))"
                        + " | format {_id: _id, boxedScores: boxedScores, scores: scores}");
        assertThat(rows).extracting(d -> d.id).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void arrayIntersectsNullableWithNullElement() {
        sessionFactoryScope.inTransaction(
                session -> session.persist(new ArrayDoc(5, new int[] {0}, new Integer[] {null, 50})));
        var hql = "from ArrayDoc d where array_intersects_nullable(d.boxedScores, :needles)";
        var rows = sessionFactoryScope.fromSession(session -> session.createSelectionQuery(hql, ArrayDoc.class)
                .setParameter("needles", new Integer[] {null})
                .getResultList());
        assertThat(capturedPipeline()).contains("any (let $__x = $ in $p0 any ($ is $__x))");
        assertThat(rows).extracting(d -> d.id).containsExactly(5);
    }

    @Entity(name = "ArrayDoc")
    @Table(name = "array_docs")
    static class ArrayDoc {
        @Id
        int id;

        int[] scores;

        Integer[] boxedScores;

        ArrayDoc() {}

        ArrayDoc(int id, int[] scores) {
            this(id, scores, null);
        }

        ArrayDoc(int id, int[] scores, Integer[] boxedScores) {
            this.id = id;
            this.scores = scores;
            this.boxedScores = boxedScores;
        }
    }
}
