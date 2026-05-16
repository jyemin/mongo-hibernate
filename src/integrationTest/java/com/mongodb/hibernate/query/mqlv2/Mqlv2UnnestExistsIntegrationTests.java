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
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
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
 * Phase 2 of the elemMatch design: HQL {@code WHERE EXISTS (SELECT 1 FROM o.array a WHERE …)}
 * translates to MQLv2 {@code array any (<rewritten body>)}. Each test asserts both the emitted
 * pipeline text (via {@link MqlCapture}) and the returned rows.
 */
@DomainModel(annotatedClasses = {Mqlv2UnnestExistsIntegrationTests.Order.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2UnnestExistsIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void seed() {
        sessionFactoryScope.inTransaction(session -> {
            session.createMutationQuery("delete from Order").executeUpdate();
            session.persist(
                    new Order(1, 3, new LineItem[] {new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 1)}));
            session.persist(new Order(2, 5, new LineItem[] {new LineItem("GADGET-1", 10)}));
            session.persist(new Order(3, 1, new LineItem[] {new LineItem("WIDGET-1", 0)}));
        });
        MqlCapture.LAST.remove();
    }

    @Test
    void existsOverStructArray_singlePredicate() {
        var hql = "from Order o where exists (select 1 from o.lineItems li where li.sku = 'WIDGET-1')";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        // Translation assertion: implicit collection-path EXISTS becomes `lineItems any (…)`.
        var captured = MqlCapture.LAST.get();
        assertThat(captured).isNotNull();
        assertThat(BsonDocument.parse(captured).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any ($.sku == \"WIDGET-1\"))"
                        + " | format {_id: _id, lineItems: lineItems, minQty: minQty}");

        // Execution assertion: orders 1 and 3 contain a WIDGET-1 line item; order 2 does not.
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 3);
    }

    @Test
    void existsOverStructArray_andConjunctionInBody() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any (($.sku == \"WIDGET-1\") and ($.qty > 0)))"
                        + " | format {_id: _id, lineItems: lineItems, minQty: minQty}");
        // Order 1 has WIDGET-1 with qty=5 (matches); order 3 has WIDGET-1 with qty=0 (doesn't).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1);
    }

    @Test
    void existsOverStructArray_orDisjunctionInBody() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where li.sku = 'WIDGET-1' or li.qty > 5)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any (($.sku == \"WIDGET-1\") or ($.qty > 5)))"
                        + " | format {_id: _id, lineItems: lineItems, minQty: minQty}");
        // Order 1 matches (WIDGET-1), order 2 matches (qty=10), order 3 matches (WIDGET-1).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void existsOverStructArray_notInsideBody() {
        var hql = "from Order o where exists ("
                + "select 1 from o.lineItems li where not (li.sku = 'WIDGET-1'))";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (lineItems any (not ($.sku == \"WIDGET-1\")))"
                        + " | format {_id: _id, lineItems: lineItems, minQty: minQty}");
        // Order 1 has WIDGET-2 (matches NOT WIDGET-1), order 2 has GADGET-1 (matches), order 3 has only WIDGET-1
        // (doesn't match).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void notExistsOverStructArray() {
        var hql = "from Order o where not exists (select 1 from o.lineItems li where li.sku = 'WIDGET-1')";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match (not (lineItems any ($.sku == \"WIDGET-1\")))"
                        + " | format {_id: _id, lineItems: lineItems, minQty: minQty}");
        // Only order 2 lacks WIDGET-1.
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(2);
    }

    @Test
    void existsOverStructArray_correlatedOuterRef() {
        var hql = "from Order o where exists (select 1 from o.lineItems li where li.qty > o.minQty)";
        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery(hql, Order.class).getResultList());

        assertThat(BsonDocument.parse(MqlCapture.LAST.get()).getString("mqlv2").getValue())
                .isEqualTo("from $orders | match let $__v0 = minQty in (lineItems any ($.qty > $__v0))"
                        + " | format {_id: _id, lineItems: lineItems, minQty: minQty}");
        // Order 1: minQty=3, has qty=5 (matches). Order 2: minQty=5, has qty=10 (matches).
        // Order 3: minQty=1, has qty=0 (doesn't match).
        assertThat(results).extracting(o -> o.id).containsExactlyInAnyOrder(1, 2);
    }

    // ---- Test entity / embeddable ----

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
