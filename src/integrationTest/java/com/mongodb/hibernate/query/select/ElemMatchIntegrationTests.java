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

package com.mongodb.hibernate.query.select;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.MongoExtension;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for v1 translator's {@code $elemMatch} emission for HQL of shape
 * {@code WHERE EXISTS (FROM <entity>.<arrayPath> a WHERE <body>)}.
 */
@DomainModel(annotatedClasses = {ElemMatchCart.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(settings = {@Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.select.MqlCapture")})
@ExtendWith(MongoExtension.class)
class ElemMatchIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from ElemMatchCart").executeUpdate();
            session.persist(new ElemMatchCart(
                    1,
                    0,
                    new ElemMatchLineItem[] {new ElemMatchLineItem("WIDGET-1", 3), new ElemMatchLineItem("GIZMO-1", 1)}));
            session.persist(new ElemMatchCart(
                    2,
                    0,
                    new ElemMatchLineItem[] {new ElemMatchLineItem("WIDGET-1", 0), new ElemMatchLineItem("BOLT-2", 10)}));
            session.persist(
                    new ElemMatchCart(3, 0, new ElemMatchLineItem[] {new ElemMatchLineItem("GIZMO-1", 5)}));
        });
        MqlCapture.LAST.remove();
    }

    @Test
    void existsSinglePredicate() {
        var results = sessionFactoryScope.fromSession(s -> s.createSelectionQuery(
                        "from ElemMatchCart c where exists (from c.lineItems li where li.sku = 'WIDGET-1') order by c.id",
                        ElemMatchCart.class)
                .getResultList());

        // Result assertion: only carts whose lineItems contain a "WIDGET-1" should match.
        assertThat(results).extracting(c -> c.id).containsExactly(1, 2);

        // Pipeline assertion: $match stage uses $elemMatch with the expected body shape.
        var pipelineJson = MqlCapture.LAST.get();
        assertThat(pipelineJson).isNotNull();
        var parsed = org.bson.BsonDocument.parse(pipelineJson);
        var matchStage = parsed.getArray("pipeline").stream()
                .map(org.bson.BsonValue::asDocument)
                .filter(stage -> stage.containsKey("$match"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No $match stage found in: " + pipelineJson));
        assertThat(matchStage)
                .isEqualTo(org.bson.BsonDocument.parse(
                        "{\"$match\": {\"lineItems\": {\"$elemMatch\": {\"sku\": {\"$eq\": \"WIDGET-1\"}}}}}"));
    }

    @Test
    void existsCorrelatedBody_throwsFeatureNotSupported() {
        assertThatThrownBy(() -> sessionFactoryScope.fromSession(s -> s.createSelectionQuery(
                                "from ElemMatchCart c where exists (from c.lineItems li where li.qty > c.minQty)",
                                ElemMatchCart.class)
                        .getResultList()))
                .isInstanceOf(FeatureNotSupportedException.class);
    }
}
