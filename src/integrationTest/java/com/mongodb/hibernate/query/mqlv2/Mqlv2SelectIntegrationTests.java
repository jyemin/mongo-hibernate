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
import static org.assertj.core.groups.Tuple.tuple;
import static org.hibernate.cfg.AvailableSettings.DIALECT;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.ServiceRegistryScope;
import org.hibernate.testing.orm.junit.ServiceRegistryScopeAware;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@DomainModel(
        annotatedClasses = {
            Mqlv2SelectIntegrationTests.Customer.class,
            Mqlv2SelectIntegrationTests.Order.class
        })
@SessionFactory(exportSchema = false)
@ServiceRegistry(settings = @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"))
@ExtendWith(MongoExtension.class)
class Mqlv2SelectIntegrationTests implements SessionFactoryScopeAware, ServiceRegistryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @Override
    public void injectServiceRegistryScope(ServiceRegistryScope scope) {}

    // ---- Test entities ----

    @Entity(name = "Customer")
    @Table(name = "customers")
    static class Customer {
        @Id
        int id;

        String name;
        int age;
        boolean active;

        Customer() {}

        Customer(int id, String name, int age, boolean active) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.active = active;
        }
    }

    @Entity(name = "Order")
    @Table(name = "orders")
    static class Order {
        @Id
        int id;

        int customerId;
        double total;
        String status;
        Instant orderDate;

        Order() {}

        Order(int id, int customerId, double total, String status, Instant orderDate) {
            this.id = id;
            this.customerId = customerId;
            this.total = total;
            this.status = status;
            this.orderDate = orderDate;
        }
    }

    // ---- Test data ----

    private static final List<Customer> CUSTOMERS = List.of(
            new Customer(1, "Alice", 30, true),
            new Customer(2, "Bob", 25, false),
            new Customer(3, "Carol", 35, true));

    private static final List<Order> ORDERS = List.of(
            new Order(10, 1, 150.0, "shipped",   Instant.parse("2023-03-15T10:00:00Z")),
            new Order(11, 1, 80.0,  "pending",   Instant.parse("2024-07-04T14:30:00Z")),
            new Order(12, 2, 200.0, "shipped",   Instant.parse("2023-11-20T09:15:45Z")),
            new Order(13, 3, 50.0,  "cancelled", Instant.parse("2024-01-08T18:45:00Z")));

    @BeforeEach
    void setUp() {
        sessionFactoryScope.inTransaction(session -> {
            CUSTOMERS.forEach(session::persist);
            ORDERS.forEach(session::persist);
        });
    }

    // ---- Task 6: Basic SELECT ----

    @Test
    void testBasicSelect() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Customer", Customer.class).getResultList();
            assertThat(result).hasSize(3);
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        });
    }

    // ---- Task 7: WHERE, sort, limit, parameterized ----

    @Test
    void testWhereWithLiteralComparison() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Customer c where c.age > 25", Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Carol");
        });
    }

    @Test
    void testWhereWithParameter() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Customer c where c.name = :name", Customer.class)
                    .setParameter("name", "Alice")
                    .getResultList();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name).isEqualTo("Alice");
        });
    }

    @Test
    void testWhereWithAndConjunction() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "from Customer c where c.age > 20 and c.age < 32", Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    @Test
    void testWhereWithNotEqual() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Order o where o.status != 'shipped'", Order.class)
                    .getResultList();
            assertThat(result).hasSize(2); // pending + cancelled
        });
    }

    @Test
    void testOrderByDescWithLimit() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Customer c order by c.age desc limit 2", Customer.class)
                    .getResultList();
            assertThat(result).hasSize(2);
            // top 2 by age desc: Carol (35), Alice (30)
            assertThat(result.stream().map(c -> c.name)).containsExactly("Carol", "Alice");
        });
    }

    @Test
    void testOrderByAsc() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Customer c order by c.age asc", Customer.class)
                    .getResultList();
            assertThat(result).hasSize(3);
            assertThat(result.stream().map(c -> c.name)).containsExactly("Bob", "Alice", "Carol");
        });
    }
    // ---- Task 9: IS NULL / IS NOT NULL ----

    @Test
    void testIsNull() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Order o where o.status is null", Order.class)
                    .getResultList();
            assertThat(result).isEmpty();
        });
    }

    @Test
    void testIsNotNull() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Order o where o.status is not null", Order.class)
                    .getResultList();
            assertThat(result).hasSize(4);
        });
    }

    @Test
    void testEqualityWithNullParam() {
        sessionFactoryScope.inSession(session -> {
            // SQL 3VL: col = null is always null (unknown), never true — use IS NULL instead
            var result = session.createSelectionQuery("from Order o where o.status = :status", Order.class)
                    .setParameter("status", null, String.class)
                    .getResultList();
            assertThat(result).isEmpty();
        });
    }

    @Test
    void testInequalityWithNullParam() {
        sessionFactoryScope.inSession(session -> {
            // SQL 3VL: col != null is always null (unknown), never true — use IS NOT NULL instead
            var result = session.createSelectionQuery("from Order o where o.status != :status", Order.class)
                    .setParameter("status", null, String.class)
                    .getResultList();
            assertThat(result).isEmpty();
        });
    }

    // ---- Arithmetic and date-part expressions in SELECT ----

    @Test
    void testSelectArithmetic() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select c.age * 2 from Customer c where c.id = 1", Integer.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(60); // Alice age 30 * 2
        });
    }

    @Test
    void testSelectArithmeticWithParameter() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select c.age + :bonus from Customer c where c.id = 2", Integer.class)
                    .setParameter("bonus", 5)
                    .getSingleResult();
            assertThat(result).isEqualTo(30); // Bob age 25 + 5
        });
    }

    @Test
    void testSelectYear() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select year(o.orderDate) from Order o where o.id = 10", Integer.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(2023);
        });
    }

    @Test
    void testSelectMonth() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select month(o.orderDate) from Order o where o.id = 11", Integer.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(7); // July
        });
    }

    @Test
    void testSelectDayOfMonth() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select day(o.orderDate) from Order o where o.id = 10", Integer.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(15);
        });
    }

    @Test
    void testSelectHour() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select hour(o.orderDate) from Order o where o.id = 10", Integer.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(10); // 10:00:00Z
        });
    }

    @Test
    void testSelectMinute() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select minute(o.orderDate) from Order o where o.id = 11", Integer.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(30); // 14:30:00Z
        });
    }

    @Test
    void testSelectSecond() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select second(o.orderDate) from Order o where o.id = 12", Float.class)
                    .getSingleResult();
            assertThat(result).isEqualTo(45.0f); // 09:15:45Z
        });
    }

    @Test
    void testArithmeticInWhere() {
        sessionFactoryScope.inSession(session -> {
            // age * 2 > 55: Alice 60 ✓, Bob 50 ✗, Carol 70 ✓
            var result = session.createSelectionQuery(
                            "from Customer c where c.age * 2 > 55", Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Carol");
        });
    }

    @Test
    void testExtractInWhere() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "from Order o where year(o.orderDate) = 2023", Order.class)
                    .getResultList();
            assertThat(result.stream().map(o -> o.id)).containsExactlyInAnyOrder(10, 12);
        });
    }

    @Test
    void testUnsupportedFunctionInSelect() {
        sessionFactoryScope.inSession(session -> {
            assertThatThrownBy(() -> session.createSelectionQuery(
                            "select upper(c.name) from Customer c", String.class)
                    .getResultList())
                    .isInstanceOf(FeatureNotSupportedException.class)
                    .hasMessageContaining("Unsupported function: upper()");
        });
    }

    @Test
    void testUnsupportedExtractUnitInSelect() {
        sessionFactoryScope.inSession(session -> {
            assertThatThrownBy(() -> session.createSelectionQuery(
                            "select week(o.orderDate) from Order o", Integer.class)
                    .getResultList())
                    .isInstanceOf(FeatureNotSupportedException.class)
                    .hasMessageContaining("Unsupported extract() unit:");
        });
    }

    @Test
    void testWhereBooleanField() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery("from Customer c where c.active", Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Carol");
        });
    }

    // ---- Task 8: JOIN queries ----

    @Test
    void testInnerJoin() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select distinct c from Customer c join Order o on c.id = o.customerId",
                            Customer.class)
                    .getResultList();
            assertThat(result).hasSize(3);
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        });
    }

    @Test
    void testInnerJoinWithWhere() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select distinct c from Customer c join Order o on c.id = o.customerId where o.total > 100",
                            Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    @Test
    void testLeftOuterJoin() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select distinct c from Customer c left join Order o on c.id = o.customerId",
                            Customer.class)
                    .getResultList();
            assertThat(result).hasSize(3);
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        });
    }

    @Test
    void testRightOuterJoin() {
        sessionFactoryScope.inSession(session -> {
            // All 4 orders have a matching customer, so right join returns all 4 orders
            var result = session.createSelectionQuery(
                            "select distinct o from Customer c right join Order o on c.id = o.customerId",
                            Order.class)
                    .getResultList();
            assertThat(result).hasSize(4);
            assertThat(result.stream().map(o -> o.id)).containsExactlyInAnyOrder(10, 11, 12, 13);
        });
    }

    // ---- Task: GROUP BY ----

    @Test
    void testGroupByCount() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select o.status, count(o.id) from Order o group by o.status", Object[].class)
                    .getResultList();
            assertThat(result).hasSize(3);
            assertThat(result)
                    .extracting(r -> r[0], r -> r[1])
                    .containsExactlyInAnyOrder(
                            tuple("shipped", 2L), tuple("pending", 1L), tuple("cancelled", 1L));
        });
    }

    @Test
    void testGroupBySum() {
        sessionFactoryScope.inSession(session -> {
            var result = session.createSelectionQuery(
                            "select o.status, sum(o.total) from Order o group by o.status", Object[].class)
                    .getResultList();
            assertThat(result).hasSize(3);
            assertThat(result)
                    .extracting(r -> r[0], r -> r[1])
                    .containsExactlyInAnyOrder(
                            tuple("shipped", 350.0), tuple("pending", 80.0), tuple("cancelled", 50.0));
        });
    }

    @Test
    void testGroupByMultipleAggregates() {
        sessionFactoryScope.inSession(session -> {
            // shipped: count=2, sum=350, min=150, max=200
            var result = session.createSelectionQuery(
                            "select o.status, count(o.id), sum(o.total), min(o.total), max(o.total)"
                                    + " from Order o where o.status = 'shipped' group by o.status",
                            Object[].class)
                    .getSingleResult();
            assertThat(result[0]).isEqualTo("shipped");
            assertThat(result[1]).isEqualTo(2L);
            assertThat(result[2]).isEqualTo(350.0);
            assertThat(result[3]).isEqualTo(150.0);
            assertThat(result[4]).isEqualTo(200.0);
        });
    }

    @Test
    void testGroupByMultipleKeys() {
        sessionFactoryScope.inSession(session -> {
            // Each order has a unique (customerId, status) pair → 4 groups
            var result = session.createSelectionQuery(
                            "select o.customerId, o.status, count(o.id) from Order o"
                                    + " group by o.customerId, o.status",
                            Object[].class)
                    .getResultList();
            assertThat(result).hasSize(4);
            assertThat(result)
                    .extracting(r -> r[0], r -> r[1], r -> r[2])
                    .containsExactlyInAnyOrder(
                            tuple(1, "shipped", 1L),
                            tuple(1, "pending", 1L),
                            tuple(2, "shipped", 1L),
                            tuple(3, "cancelled", 1L));
        });
    }

    @Test
    void testHavingWithCount() {
        sessionFactoryScope.inSession(session -> {
            // Only "shipped" has count > 1 (orders 10 and 12 are both shipped)
            var result = session.createSelectionQuery(
                            "select o.status, count(o.id) from Order o group by o.status"
                                    + " having count(o.id) > 1",
                            Object[].class)
                    .getResultList();
            assertThat(result).hasSize(1);
            assertThat(result.get(0)[0]).isEqualTo("shipped");
            assertThat(result.get(0)[1]).isEqualTo(2L);
        });
    }

    @Test
    void testHavingWithSum() {
        sessionFactoryScope.inSession(session -> {
            // shipped=350, pending=80, cancelled=50 — only shipped exceeds 100
            var result = session.createSelectionQuery(
                            "select o.status, sum(o.total) from Order o group by o.status"
                                    + " having sum(o.total) > 100",
                            Object[].class)
                    .getResultList();
            assertThat(result).hasSize(1);
            assertThat(result.get(0)[0]).isEqualTo("shipped");
            assertThat(result.get(0)[1]).isEqualTo(350.0);
        });
    }

    @Test
    void testHavingAggregateNotInSelectThrows() {
        sessionFactoryScope.inSession(session -> {
            assertThatThrownBy(() -> session.createSelectionQuery(
                            "select o.status from Order o group by o.status having count(o.id) > 1",
                            String.class)
                    .getResultList())
                    .isInstanceOf(FeatureNotSupportedException.class);
        });
    }

    @Test
    void testFullOuterJoin() {
        sessionFactoryScope.inSession(session -> {
            // All customers have orders and all orders have customers, so full join returns all 3 customers
            var result = session.createSelectionQuery(
                            "select distinct c from Customer c full join Order o on c.id = o.customerId",
                            Customer.class)
                    .getResultList();
            assertThat(result).hasSize(3);
            assertThat(result.stream().map(c -> c.name)).containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        });
    }

    // ---- Subquery and set operation tests ----

    @Test
    void testInList() {
        sessionFactoryScope.inSession(session -> {
            // ages 25 and 30 → Alice(30) and Bob(25) match; Carol(35) does not
            var result = session.createSelectionQuery(
                            "from Customer c where c.age in (25, 30)", Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name))
                    .containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    @Test
    void testNotInList() {
        sessionFactoryScope.inSession(session -> {
            // not in (25, 30) → only Carol (35)
            var result = session.createSelectionQuery(
                            "from Customer c where c.age not in (25, 30)", Customer.class)
                    .getResultList();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name).isEqualTo("Carol");
        });
    }

    @Test
    void testExists() {
        sessionFactoryScope.inSession(session -> {
            // Alice (id=1) has orders with total 150 and 80; Bob (id=2) has total 200; Carol (id=3) has total 50 only
            // EXISTS (total > 100): Alice ✓, Bob ✓, Carol ✗
            var result = session.createSelectionQuery(
                            "from Customer c where exists (select 1 from Order o where o.customerId = c.id and o.total > 100)",
                            Customer.class)
                    .getResultList();
            assertThat(result.stream().map(c -> c.name))
                    .containsExactlyInAnyOrder("Alice", "Bob");
        });
    }

    @Test
    void testNotExists() {
        sessionFactoryScope.inSession(session -> {
            // NOT EXISTS (total > 100): only Carol has no orders with total > 100
            var result = session.createSelectionQuery(
                            "from Customer c where not exists (select 1 from Order o where o.customerId = c.id and o.total > 100)",
                            Customer.class)
                    .getResultList();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name).isEqualTo("Carol");
        });
    }

    @Test
    void testExistsUncorrelated() {
        sessionFactoryScope.inSession(session -> {
            // Uncorrelated EXISTS: no orders have total > 1000, so subquery returns nothing → 0 customers
            var result = session.createSelectionQuery(
                            "from Customer c where exists (select 1 from Order o where o.total > 1000)",
                            Customer.class)
                    .getResultList();
            assertThat(result).isEmpty();
        });
    }

}