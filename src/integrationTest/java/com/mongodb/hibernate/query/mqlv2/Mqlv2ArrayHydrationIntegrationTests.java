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

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
 * Phase 1 of the elemMatch design: verifies the load-bearing assumption that the v2 SELECT translator correctly
 * hydrates array-valued entity fields back from MongoDB. Storage (INSERT / UPDATE) is shared with the v1 translator and
 * already covered elsewhere — what's not exercised by any existing test is reading array fields back through the v2
 * SELECT pipeline. Without this, Phases 2-4 would have no foundation to assert on (every translator test asserts on
 * returned entity state).
 *
 * <p>Three array shapes are tested on equal footing:
 *
 * <ul>
 *   <li>Struct array: {@code LineItem[]} where {@code LineItem} is {@code @Embeddable @Struct}.
 *   <li>Scalar primitive array: {@code int[]}.
 *   <li>Scalar collection: {@code Collection<String>}.
 * </ul>
 *
 * <p>The HQL surface exercised here is the minimum required to confirm hydration: {@code session.find(...)} and trivial
 * HQL {@code SELECT}. Element-filter HQL (the actual elemMatch feature) is the subject of Phases 2-4.
 */
@DomainModel(annotatedClasses = {Mqlv2ArrayHydrationIntegrationTests.ArrayOrder.class})
@SessionFactory(exportSchema = false)
@ServiceRegistry(settings = @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"))
@ExtendWith(MongoExtension.class)
class Mqlv2ArrayHydrationIntegrationTests implements SessionFactoryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @BeforeEach
    void cleanCollection() {
        sessionFactoryScope.inTransaction(
                session -> session.createMutationQuery("delete from ArrayOrder").executeUpdate());
    }

    @Test
    void findByIdHydratesAllArrayFields() {
        var order = new ArrayOrder(
                1,
                new LineItem[] {new LineItem("WIDGET-1", 2, 9.99), new LineItem("WIDGET-2", 1, 19.99)},
                new int[] {10, 20, 30},
                List.of("urgent", "fragile"));
        sessionFactoryScope.inTransaction(session -> session.persist(order));

        var loaded = sessionFactoryScope.fromSession(session -> session.find(ArrayOrder.class, 1));

        assertThat(loaded).isNotNull();
        assertThat(loaded.id).isEqualTo(1);
        assertThat(loaded.lineItems)
                .containsExactly(new LineItem("WIDGET-1", 2, 9.99), new LineItem("WIDGET-2", 1, 19.99));
        assertThat(loaded.scores).containsExactly(10, 20, 30);
        assertThat(loaded.tags).containsExactlyInAnyOrder("urgent", "fragile");
    }

    @Test
    void hqlSelectByIdHydratesAllArrayFields() {
        var order = new ArrayOrder(
                2,
                new LineItem[] {new LineItem("GADGET-1", 5, 4.99)},
                new int[] {1, 2, 3, 4, 5},
                List.of("a", "b", "c"));
        sessionFactoryScope.inTransaction(session -> session.persist(order));

        var result = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery("from ArrayOrder o where o.id = :id", ArrayOrder.class)
                        .setParameter("id", 2)
                        .getSingleResult());

        assertThat(result.lineItems).containsExactly(new LineItem("GADGET-1", 5, 4.99));
        assertThat(result.scores).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.tags).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void hqlFullScanHydratesAllArrayFields() {
        sessionFactoryScope.inTransaction(session -> {
            session.persist(
                    new ArrayOrder(3, new LineItem[] {new LineItem("A", 1, 1.0)}, new int[] {1}, List.of("first")));
            session.persist(new ArrayOrder(
                    4,
                    new LineItem[] {new LineItem("B", 2, 2.0), new LineItem("C", 3, 3.0)},
                    new int[] {10, 20},
                    List.of("second", "extra")));
        });

        var results = sessionFactoryScope.fromSession(
                session -> session.createSelectionQuery("from ArrayOrder o order by o.id", ArrayOrder.class)
                        .getResultList());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id).isEqualTo(3);
        assertThat(results.get(0).lineItems).containsExactly(new LineItem("A", 1, 1.0));
        assertThat(results.get(0).scores).containsExactly(1);
        assertThat(results.get(0).tags).containsExactlyInAnyOrder("first");

        assertThat(results.get(1).id).isEqualTo(4);
        assertThat(results.get(1).lineItems).containsExactly(new LineItem("B", 2, 2.0), new LineItem("C", 3, 3.0));
        assertThat(results.get(1).scores).containsExactly(10, 20);
        assertThat(results.get(1).tags).containsExactlyInAnyOrder("second", "extra");
    }

    @Test
    void emptyArrayFieldsHydrateAsEmpty() {
        var order = new ArrayOrder(5, new LineItem[0], new int[0], List.of());
        sessionFactoryScope.inTransaction(session -> session.persist(order));

        var loaded = sessionFactoryScope.fromSession(session -> session.find(ArrayOrder.class, 5));

        assertThat(loaded).isNotNull();
        assertThat(loaded.lineItems).isEmpty();
        assertThat(loaded.scores).isEmpty();
        assertThat(loaded.tags).isEmpty();
    }

    @Test
    void nullArrayFieldsHydrateAsNull() {
        var order = new ArrayOrder(6, null, null, null);
        sessionFactoryScope.inTransaction(session -> session.persist(order));

        var loaded = sessionFactoryScope.fromSession(session -> session.find(ArrayOrder.class, 6));

        assertThat(loaded).isNotNull();
        assertThat(loaded.lineItems).isNull();
        assertThat(loaded.scores).isNull();
        assertThat(loaded.tags).isNull();
    }

    // ---- Test entity / embeddable ----

    @Entity(name = "ArrayOrder")
    @Table(name = "array_orders")
    static class ArrayOrder {
        @Id
        int id;

        LineItem[] lineItems;
        int[] scores;
        Collection<String> tags;

        ArrayOrder() {}

        ArrayOrder(int id, LineItem[] lineItems, int[] scores, Collection<String> tags) {
            this.id = id;
            this.lineItems = lineItems;
            this.scores = scores;
            this.tags = tags;
        }
    }

    @Embeddable
    @Struct(name = "LineItem")
    static class LineItem {
        String sku;
        int qty;
        double price;

        LineItem() {}

        LineItem(String sku, int qty, double price) {
            this.sku = sku;
            this.qty = qty;
            this.price = price;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LineItem li)) return false;
            return qty == li.qty && Double.compare(price, li.price) == 0 && Objects.equals(sku, li.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sku, qty, price);
        }

        @Override
        public String toString() {
            return "LineItem{sku=" + sku + ", qty=" + qty + ", price=" + price + "}";
        }
    }
}
