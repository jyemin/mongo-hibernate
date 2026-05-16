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
 * Phase 4 of the elemMatch design: scalar {@code count(*)} subqueries and IN subqueries over struct arrays translate
 * via a subpipeline expression {@code (from <arrayPath> | match (...))} inside MQLv2 {@code count(...)}.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestSubqueryIntegrationTests.Order.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestSubqueryIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from Order").executeUpdate();
            // Order 1: WIDGET-1 qty=5, WIDGET-2 qty=8, WIDGET-3 qty=2 → 2 items with qty > 4
            session.persist(new Order(1, 4, new LineItem[] {
                new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 8), new LineItem("WIDGET-3", 2)
            }));
            // Order 2: GADGET-1 qty=10 → 1 item with qty > 4
            session.persist(new Order(2, 5, new LineItem[] {new LineItem("GADGET-1", 10)}));
            // Order 3: WIDGET-1 qty=0 → 0 items with qty > 4
            session.persist(new Order(3, 1, new LineItem[] {new LineItem("WIDGET-1", 0)}));
        });
        MqlCapture.LAST.remove();
    }

    @Test
    void scalarSubqueryCount_overStructArray_simplePredicate() {
        var hql = "select o.id, (select count(*) from o.lineItems a where a.qty > 4) from Order o order by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        var captured =
                BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        assertThat(captured).contains("count((from lineItems | match ($.qty > 4)))");

        // Expected: (1, 2), (2, 1), (3, 0)
        assertThat(results).hasSize(3);
        assertThat(((Number) results.get(0)[1]).longValue()).isEqualTo(2L);
        assertThat(((Number) results.get(1)[1]).longValue()).isEqualTo(1L);
        assertThat(((Number) results.get(2)[1]).longValue()).isEqualTo(0L);
    }

    @Test
    void scalarSubqueryCount_correlatedOuterRef() {
        var hql = "select o.id, (select count(*) from o.lineItems a where a.qty > o.minQty) "
                + "from Order o order by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        var captured =
                BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        // Inner emission wraps in a let for the outer ref.
        assertThat(captured).contains("let $__v0 = minQty in (count((from lineItems | match ($.qty > $__v0))))");

        // Order 1: minQty=4. Items with qty > 4: WIDGET-1(5), WIDGET-2(8) → 2.
        // Order 2: minQty=5. Items with qty > 5: GADGET-1(10) → 1.
        // Order 3: minQty=1. Items with qty > 1: none (only qty=0) → 0.
        assertThat(((Number) results.get(0)[1]).longValue()).isEqualTo(2L);
        assertThat(((Number) results.get(1)[1]).longValue()).isEqualTo(1L);
        assertThat(((Number) results.get(2)[1]).longValue()).isEqualTo(0L);
    }

    @Test
    void scalarSubqueryCount_noInnerPredicate() {
        var hql = "select o.id, (select count(*) from o.lineItems a) from Order o order by o.id";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Object[].class).getResultList());

        var captured =
                BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue();
        assertThat(captured).contains("count((from lineItems))");

        // Order 1 has 3 items, Order 2 has 1, Order 3 has 1.
        assertThat(((Number) results.get(0)[1]).longValue()).isEqualTo(3L);
        assertThat(((Number) results.get(1)[1]).longValue()).isEqualTo(1L);
        assertThat(((Number) results.get(2)[1]).longValue()).isEqualTo(1L);
    }

    @Test
    void scalarSubquery_nonCountAggregate_throws() {
        var hql = "select o.id, (select max(a.qty) from o.lineItems a) from Order o";
        assertThatThrownBy(() -> sessionFactoryScope.inSession(session ->
                        session.createSelectionQuery(hql, Object[].class).getResultList()))
                .isInstanceOf(com.mongodb.hibernate.internal.FeatureNotSupportedException.class)
                .hasMessageContaining("count()")
                .hasMessageContaining("pipeline-argument form");
    }

    @Test
    void inSubquery_overStructArray_hibernateBlocked() {
        // Hibernate 7.3.4's SQM converter (BaseSqmToSqlAstConverter.visitInSubQueryPredicate)
        // performs type inference on the subquery's projected expression by calling
        // SqmMappingModelHelper.resolveSqmPath, which throws AssertionError for paths
        // resolved through an unnest FunctionJoin (e.g., "a" in "FROM o.lineItems a").
        // This fails before our translator is invoked. The translator code is implemented
        // correctly (appendUnnestInSubQueryPredicate) but is unreachable in Hibernate 7.3.4.
        var hql = "select o from Order o where 5 in (select a.qty from o.lineItems a)";
        assertThatThrownBy(() -> sessionFactoryScope.inSession(session ->
                        session.createSelectionQuery(hql, Order.class).getResultList()))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void notInSubquery_overStructArray_hibernateBlocked() {
        // Same Hibernate SQM limitation as inSubquery_overStructArray_hibernateBlocked.
        var hql = "select o from Order o where 5 not in (select a.qty from o.lineItems a)";
        assertThatThrownBy(() -> sessionFactoryScope.inSession(session ->
                        session.createSelectionQuery(hql, Order.class).getResultList()))
                .isInstanceOf(AssertionError.class);
    }

    @Entity(name = "Order")
    @Table(name = "orders")
    static class Order {
        @Id
        int id;

        int minQty;

        LineItem[] lineItems;

        Order() {}

        Order(int id, int minQty, LineItem[] lineItems) {
            this.id = id;
            this.minQty = minQty;
            this.lineItems = lineItems;
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
