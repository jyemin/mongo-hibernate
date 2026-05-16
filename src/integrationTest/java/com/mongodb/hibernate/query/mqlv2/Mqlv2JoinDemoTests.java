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

import static org.hibernate.cfg.AvailableSettings.DIALECT;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import com.mongodb.hibernate.junit.InjectMongoClient;
import com.mongodb.hibernate.junit.InjectMongoCollection;
import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.json.JsonWriterSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.ServiceRegistryScope;
import org.hibernate.testing.orm.junit.ServiceRegistryScopeAware;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.SessionFactoryScopeAware;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Demo: three-way JOIN query over 10 000 customers, 50 000 orders, and 150 000 line items
 * translated to MQLv2 and executed against a real MongoDB server. Inspired by TPC-H schema.
 *
 * <p>Run with: {@code ./gradlew integrationTest --tests "*.Mqlv2JoinDemoTests"}
 */
@DomainModel(
        annotatedClasses = {
            Mqlv2JoinDemoTests.Customer.class,
            Mqlv2JoinDemoTests.Order.class,
            Mqlv2JoinDemoTests.LineItem.class
        })
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(
                    name = "hibernate.session_factory.statement_inspector",
                    value =
                            "com.mongodb.hibernate.query.mqlv2.Mqlv2ShowcaseVerificationTests$MqlCapture")
        })
@ExtendWith(MongoExtension.class)
@Disabled
class Mqlv2JoinDemoTests implements SessionFactoryScopeAware, ServiceRegistryScopeAware {

    private static final int NUM_CUSTOMERS = 10_000;
    private static final int NUM_ORDERS = 50_000;
    private static final int NUM_LINE_ITEMS_PER_ORDER = 3;
    private static final int NUM_LINE_ITEMS = NUM_ORDERS * NUM_LINE_ITEMS_PER_ORDER;
    private static final String[] STATUSES = {"shipped", "pending", "cancelled", "processing"};
    private static final String[] SHIP_MODES = {"AIR", "TRUCK", "SHIP", "RAIL", "MAIL", "FOB", "REG AIR"};

    @InjectMongoClient
    static MongoClient mongoClient;

    @InjectMongoCollection("customers")
    static MongoCollection<BsonDocument> customersCollection;

    @InjectMongoCollection("orders")
    static MongoCollection<BsonDocument> ordersCollection;

    @InjectMongoCollection("lineitems")
    static MongoCollection<BsonDocument> lineitemsCollection;

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @Override
    public void injectServiceRegistryScope(ServiceRegistryScope scope) {}

    @Entity(name = "Customer")
    @Table(name = "customers")
    static class Customer {
        @Id
        int id;

        String name;
        int age;
        boolean active;

        Customer() {}
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
    }

    @Entity(name = "LineItem")
    @Table(name = "lineitems")
    static class LineItem {
        @Id
        int id;

        int orderId;
        int quantity;
        double extendedPrice;
        double discount;
        String shipMode;

        LineItem() {}
    }

    @BeforeEach
    void setUp() {
        ordersCollection.drop();
        customersCollection.drop();
        lineitemsCollection.drop();
        // { customerId: 1 } supports the current join order (customers → orders).
        ordersCollection.createIndex(Indexes.ascending("customerId"));
        // { shipMode: 1, quantity: 1 } is optimal once the join optimizer can reorder to
        // lineitems → orders → customers, probing orders/customers by PK.
        lineitemsCollection.createIndex(
                Indexes.compoundIndex(Indexes.ascending("shipMode"), Indexes.ascending("quantity")));

        System.out.printf("%nInserting %,d customers...%n", NUM_CUSTOMERS);
        long t0 = System.nanoTime();
        var customers = new ArrayList<BsonDocument>(NUM_CUSTOMERS);
        for (int i = 1; i <= NUM_CUSTOMERS; i++) {
            customers.add(new BsonDocument()
                    .append("_id", new BsonInt32(i))
                    .append("name", new BsonString("Customer_" + i))
                    .append("age", new BsonInt32(20 + (i % 60)))
                    .append("active", new BsonBoolean(i % 5 != 0)));
        }
        customersCollection.insertMany(customers);
        System.out.printf("  done in %.2fs%n", (System.nanoTime() - t0) / 1e9);

        System.out.printf("Inserting %,d orders...%n", NUM_ORDERS);
        t0 = System.nanoTime();
        long now = System.currentTimeMillis();
        var batch = new ArrayList<BsonDocument>(1000);
        for (int i = 1; i <= NUM_ORDERS; i++) {
            batch.add(new BsonDocument()
                    .append("_id", new BsonInt32(i))
                    .append("customerId", new BsonInt32(1 + ((i - 1) % NUM_CUSTOMERS)))
                    .append("total", new BsonDouble(10.0 + ((i * 37L) % 490)))
                    .append("status", new BsonString(STATUSES[i % STATUSES.length]))
                    .append("orderDate", new BsonDateTime(now - (long) i * 86_400_000L)));
            if (batch.size() == 1000) {
                ordersCollection.insertMany(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            ordersCollection.insertMany(batch);
        }
        System.out.printf("  done in %.2fs%n", (System.nanoTime() - t0) / 1e9);

        System.out.printf("Inserting %,d line items...%n", NUM_LINE_ITEMS);
        t0 = System.nanoTime();
        batch = new ArrayList<>(1000);
        for (int i = 1; i <= NUM_LINE_ITEMS; i++) {
            batch.add(new BsonDocument()
                    .append("_id", new BsonInt32(i))
                    .append("orderId", new BsonInt32(1 + (i - 1) / NUM_LINE_ITEMS_PER_ORDER))
                    .append("quantity", new BsonInt32(1 + (i * 17 % 50)))
                    .append("extendedPrice", new BsonDouble(1.0 + ((i * 53L) % 10000) / 100.0))
                    .append("discount", new BsonDouble((i % 11) / 100.0))
                    .append("shipMode", new BsonString(SHIP_MODES[i % SHIP_MODES.length])));
            if (batch.size() == 1000) {
                lineitemsCollection.insertMany(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            lineitemsCollection.insertMany(batch);
        }
        System.out.printf("  done in %.2fs%n", (System.nanoTime() - t0) / 1e9);
    }

    @Test
    void demo() {
        System.out.println("\n=== MQLv2 Three-Way Join Demo ===");
        for (var coll : List.of(customersCollection, ordersCollection, lineitemsCollection)) {
            coll.listIndexes(BsonDocument.class).forEach(idx -> {
                if (!idx.getString("name").getValue().equals("_id_")) {
                    System.out.printf("Index: %s on %s%n",
                            idx.getDocument("key").toJson(), coll.getNamespace().getCollectionName());
                }
            });
        }

        runAndExplain(
                "customers → orders → lineitems",
                "select distinct c from Customer c"
                        + " join Order o on c.id = o.customerId"
                        + " join LineItem li on o.id = li.orderId"
                        + " where c.active = true"
                        + " and o.status = 'shipped'"
                        + " and li.shipMode = 'AIR'"
                        + " and li.quantity = 50");

        runAndExplain(
                "lineitems → orders → customers",
                "select distinct c from LineItem li"
                        + " join Order o on li.orderId = o.id"
                        + " join Customer c on o.customerId = c.id"
                        + " where c.active = true"
                        + " and o.status = 'shipped'"
                        + " and li.shipMode = 'AIR'"
                        + " and li.quantity = 50");
    }

    private void runAndExplain(String label, String hql) {
        System.out.println("\n--- " + label + " ---");
        System.out.println("HQL:   " + hql);

        long t0 = System.nanoTime();
        List<Customer> results = sessionFactoryScope.fromSession(session ->
                session.createQuery(hql, Customer.class).getResultList());
        double elapsed = (System.nanoTime() - t0) / 1e9;

        var captured = Mqlv2ShowcaseVerificationTests.MqlCapture.LAST.get();
        var mqlv2 = captured != null
                ? BsonDocument.parse(captured).getString("mqlv2").getValue()
                : "(not captured)";
        System.out.println("MQLv2: " + mqlv2);

        var db = mongoClient.getDatabase(customersCollection.getNamespace().getDatabaseName());
        var explainResult = db.runCommand(
                new BsonDocument("explain", new BsonDocument("mqlv2", new BsonString(mqlv2))),
                BsonDocument.class);
        var queryPlan = explainResult
                .getArray("stages").get(0).asDocument()
                .getDocument("$cursor")
                .getDocument("queryPlanner")
                .getDocument("winningPlan");
        System.out.println("QueryPlan:\n" + queryPlan.toJson(JsonWriterSettings.builder().indent(true).build()));

        System.out.printf("%nResult: %,d distinct customers matched in %.3fs%n%n",
                results.size(), elapsed);
        System.out.println("First 10:");
        results.stream().limit(10).forEach(c ->
                System.out.printf("  {id: %5d, name: %-14s, age: %2d, active: %b}%n",
                        c.id, c.name, c.age, c.active));
    }
}
