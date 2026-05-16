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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hibernate.cfg.AvailableSettings.DIALECT;
import static org.hibernate.cfg.AvailableSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.bson.BsonDocument;
import org.hibernate.annotations.Struct;
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
 * Phase 3 of the elemMatch design: HQL {@code FROM O o JOIN o.array a WHERE …} translates to MQLv2 {@code | unwind
 * array | match (…)}. Row-multiplying join semantics; struct arrays only.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestJoinIntegrationTests.Order.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestJoinIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from Order").executeUpdate();
            session.persist(new Order(
                    1, new LineItem[] {new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 1)}, new int[] {10, 20, 30
                    }));
            session.persist(new Order(2, new LineItem[] {new LineItem("GADGET-1", 10)}, new int[] {1, 2, 3}));
            session.persist(new Order(3, new LineItem[] {new LineItem("WIDGET-1", 0)}, new int[] {}));
        });
        MqlCapture.LAST.remove();
    }

    @Test
    void joinOverStructArray_singlePredicate() {
        var hql = "select o from Order o join o.lineItems a where a.sku = 'WIDGET-1'";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | unwind $__elem = lineItems in {_id: _id, lineItems: $__elem, scores: scores}"
                        + " | match (lineItems.sku == \"WIDGET-1\")"
                        + " | format {_id: _id, lineItems: [lineItems], scores: scores}");
        // Order 1 has WIDGET-1 (one match) → 1 row; order 3 has WIDGET-1 (one match) → 1 row; order 2 → 0 rows.
        // Row-multiplying JOIN: two rows total (NOT deduplicated like EXISTS).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 3);
    }

    @Test
    void joinOverStructArray_projectsAliasField() {
        var hql = "select o.id, a.sku from Order o join o.lineItems a where a.sku = 'WIDGET-1'";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | unwind $__elem = lineItems in {_id: _id, lineItems: $__elem}"
                        + " | match (lineItems.sku == \"WIDGET-1\") | format {_id: _id, sku: lineItems.sku}");
        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> r[1]).containsOnly("WIDGET-1");
    }

    @Test
    void joinOverStructArray_aggregateOverAliasWithGroupBy() {
        var hql = "select o.id, sum(a.qty) from Order o join o.lineItems a group by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        // Order 1: qty 5 + 1 = 6. Order 2: qty 10. Order 3: qty 0.
        // Map result rows by id → sum for stable assertion.
        var byId = new java.util.HashMap<Integer, Long>();
        for (var r : results) {
            byId.put((Integer) r[0], ((Number) r[1]).longValue());
        }
        assertThat(byId).containsEntry(1, 6L).containsEntry(2, 10L).containsEntry(3, 0L);
    }

    @Test
    void joinOverStructArray_cardinalityIsRowMultiplying() {
        // Order 1 has TWO line items; the JOIN should produce TWO rows (NOT deduplicated).
        var hql = "select o.id, a.sku from Order o join o.lineItems a where o.id = 1";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());
        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> r[1]).containsExactlyInAnyOrder("WIDGET-1", "WIDGET-2");
    }

    @Test
    void joinOverScalarArray_withBodyPredicate_unsupported() {
        // Scalar JOIN with a body predicate (s > 5) fails at HQL semantic-analysis time.
        // Hibernate throws AssertionError when it cannot resolve the scalar path for the join alias.
        var hql = "select o from Order o join o.scores s where s > 5";
        assertThatThrownBy(() -> sessionFactoryScope.inSession(session ->
                        session.createSelectionQuery(hql, Order.class).getResultList()))
                .isInstanceOf(AssertionError.class);
    }

    // ---- Test entity / embeddable ----

    @Entity(name = "Order")
    @Table(name = "orders")
    static class Order {
        @Id
        int id;

        LineItem[] lineItems;

        int[] scores;

        Order() {}

        Order(int id, LineItem[] lineItems, int[] scores) {
            this.id = id;
            this.lineItems = lineItems;
            this.scores = scores;
        }
    }

    @Embeddable
    @Struct(name = "LineItem")
    static class LineItem {
        String sku;
        int qty;

        LineItem() {}

        LineItem(String sku, int qty) {
            this.sku = sku;
            this.qty = qty;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LineItem li)) return false;
            return qty == li.qty && Objects.equals(sku, li.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku, qty);
        }
    }
}
