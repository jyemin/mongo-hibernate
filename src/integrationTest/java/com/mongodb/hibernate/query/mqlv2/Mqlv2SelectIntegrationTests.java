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
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

        Customer() {}

        Customer(int id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
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

        Order() {}

        Order(int id, int customerId, double total, String status) {
            this.id = id;
            this.customerId = customerId;
            this.total = total;
            this.status = status;
        }
    }

    // ---- Test data ----

    private static final List<Customer> CUSTOMERS = List.of(
            new Customer(1, "Alice", 30),
            new Customer(2, "Bob", 25),
            new Customer(3, "Carol", 35));

    private static final List<Order> ORDERS = List.of(
            new Order(10, 1, 150.0, "shipped"),
            new Order(11, 1, 80.0, "pending"),
            new Order(12, 2, 200.0, "shipped"),
            new Order(13, 3, 50.0, "cancelled"));

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

}