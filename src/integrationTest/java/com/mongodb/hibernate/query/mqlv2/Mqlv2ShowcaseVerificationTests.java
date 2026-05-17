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
import static org.hibernate.cfg.JdbcSettings.STATEMENT_INSPECTOR;

import com.mongodb.hibernate.junit.MongoExtension;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Query;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.assertj.core.api.SoftAssertions;
import org.bson.BsonDocument;
import org.hibernate.annotations.Struct;
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

/**
 * Verifies that every MQLv2 pipeline shown in docs/mqlv2-showcase.md exactly matches what the translator produces. Run
 * this test when editing either the translator or the showcase doc.
 */
@DomainModel(
        annotatedClasses = {
            Mqlv2SelectIntegrationTests.Customer.class,
            Mqlv2SelectIntegrationTests.Order.class,
            Mqlv2ShowcaseVerificationTests.Cart.class,
            Mqlv2ShowcaseVerificationTests.Inventory.class
        })
@SessionFactory(exportSchema = false)
@ServiceRegistry(
        settings = {
            @Setting(name = DIALECT, value = "com.mongodb.hibernate.query.mqlv2.TestMqlv2Dialect"),
            @Setting(name = STATEMENT_INSPECTOR, value = "com.mongodb.hibernate.query.mqlv2.MqlCapture"),
            @Setting(name = "mongo.hibernate.mqlv2.enabled", value = "true")
        })
@ExtendWith(MongoExtension.class)
class Mqlv2ShowcaseVerificationTests implements SessionFactoryScopeAware, ServiceRegistryScopeAware {

    private SessionFactoryScope sessionFactoryScope;

    @Override
    public void injectSessionFactoryScope(SessionFactoryScope scope) {
        this.sessionFactoryScope = scope;
    }

    @Override
    public void injectServiceRegistryScope(ServiceRegistryScope scope) {}

    private static final List<Mqlv2SelectIntegrationTests.Customer> CUSTOMERS = List.of(
            new Mqlv2SelectIntegrationTests.Customer(1, "Alice", 30, true),
            new Mqlv2SelectIntegrationTests.Customer(2, "Bob", 25, false),
            new Mqlv2SelectIntegrationTests.Customer(3, "Carol", 35, true));

    private static final List<Mqlv2SelectIntegrationTests.Order> ORDERS = List.of(
            new Mqlv2SelectIntegrationTests.Order(10, 1, 150.0, "shipped", Instant.parse("2023-03-15T10:00:00Z")),
            new Mqlv2SelectIntegrationTests.Order(11, 1, 80.0, "pending", Instant.parse("2024-07-04T14:30:00Z")),
            new Mqlv2SelectIntegrationTests.Order(12, 2, 200.0, "shipped", Instant.parse("2023-11-20T09:15:45Z")),
            new Mqlv2SelectIntegrationTests.Order(13, 3, 50.0, "cancelled", Instant.parse("2024-01-08T18:45:00Z")));

    private static final List<Cart> CARTS = List.of(
            new Cart(1, 3, new LineItem[] {new LineItem("WIDGET-1", 5), new LineItem("WIDGET-2", 1)}),
            new Cart(2, 5, new LineItem[] {new LineItem("GADGET-1", 10)}),
            new Cart(3, 1, new LineItem[] {new LineItem("WIDGET-1", 0)}));

    private static final List<Inventory> INVENTORY = List.of(
            new Inventory(1, new int[] {10, 20, 30}, new Integer[] {}),
            new Inventory(2, new int[] {30, 40}, new Integer[] {}),
            new Inventory(3, new int[] {}, new Integer[] {}));

    @BeforeEach
    void setUp() {
        sessionFactoryScope.inTransaction(session -> {
            CUSTOMERS.forEach(session::persist);
            ORDERS.forEach(session::persist);
            CARTS.forEach(session::persist);
            INVENTORY.forEach(session::persist);
        });
    }

    @Test
    void verifyAllShowcaseExamples() {
        // Shorthand for the trailing format stages (without the leading "from $collection |")
        var fmtC = "format {_id: _id, active: active, age: age, name: name}";
        var fmtO = "format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}";
        var fromC = "from $customers | " + fmtC;

        SoftAssertions.assertSoftly(soft -> {
            // Basic SELECT
            check(soft, "from Customer", fromC);

            // WHERE — simple comparison
            check(soft, "from Customer c where c.age > 25", "from $customers | match (age > 25) | " + fmtC);

            // WHERE — named parameter
            check(
                    soft,
                    "from Customer c where c.name = :name",
                    q -> q.setParameter("name", "Alice"),
                    "from $customers | match (name == $p0) | " + fmtC);

            // WHERE — AND conjunction
            check(
                    soft,
                    "from Customer c where c.age > 20 and c.age < 32",
                    "from $customers | match ((age > 20) and (age < 32)) | " + fmtC);

            // WHERE — not-equal
            check(
                    soft,
                    "from Order o where o.status != 'shipped'",
                    "from $orders | match (status != \"shipped\") | " + fmtO);

            // WHERE — boolean field predicate
            check(soft, "from Customer c where c.active", "from $customers | match (active == true) | " + fmtC);

            // WHERE — arithmetic expression
            check(soft, "from Customer c where c.age * 2 > 55", "from $customers | match ((age * 2) > 55) | " + fmtC);

            // WHERE — date-part extraction
            check(
                    soft,
                    "from Order o where year(o.orderDate) = 2023",
                    "from $orders | match (year(orderDate) == 2023) | " + fmtO);

            // WHERE — IN list
            check(
                    soft,
                    "from Customer c where c.age in (25, 30)",
                    "from $customers | match ((age == 25) or (age == 30)) | " + fmtC);

            // WHERE — NOT IN list
            check(
                    soft,
                    "from Customer c where c.age not in (25, 30)",
                    "from $customers | match ((age != 25) and (age != 30)) | " + fmtC);

            // WHERE — IS NULL
            check(soft, "from Order o where o.status is null", "from $orders | match isNullish(status) | " + fmtO);

            // WHERE — IS NOT NULL
            check(
                    soft,
                    "from Order o where o.status is not null",
                    "from $orders | match notNullish(status) | " + fmtO);

            // NULL semantics — equality with null parameter (MQLv2 is same regardless of value)
            check(
                    soft,
                    "from Order o where o.status = :status",
                    q -> q.setParameter("status", "x"),
                    "from $orders | match (status == $p0) | " + fmtO);

            // NULL semantics — inequality with null parameter
            check(
                    soft,
                    "from Order o where o.status != :status",
                    q -> q.setParameter("status", "x"),
                    "from $orders | match (status != $p0) | " + fmtO);

            // ORDER BY DESC + LIMIT (HQL clause)
            check(
                    soft,
                    "from Customer c order by c.age desc limit 2",
                    "from $customers | sort age desc | limit 2 | " + fmtC);

            // ORDER BY DESC + LIMIT (setMaxResults — dynamic limit via let variable)
            check(
                    soft,
                    "from Customer c order by c.age desc",
                    q -> q.setMaxResults(2),
                    "from $customers | sort age desc | limit $p0 | " + fmtC);

            // ORDER BY ASC (asc is MQLv2 default — keyword omitted)
            check(soft, "from Customer c order by c.age asc", "from $customers | sort age | " + fmtC);

            // SELECT — arithmetic expression
            check(
                    soft,
                    "select c.age * 2 from Customer c where c.id = 1",
                    "from $customers | match (_id == 1) | format {_f0: (age * 2)}");

            // SELECT — arithmetic with parameter
            check(
                    soft,
                    "select c.age + :bonus from Customer c where c.id = :id",
                    q -> q.setParameter("id", 2).setParameter("bonus", 5),
                    "from $customers | match (_id == $p0) | format {_f0: (age + $p1)}");

            // SELECT — date-part extraction
            check(
                    soft,
                    "select year(o.orderDate) from Order o where o.id = 10",
                    "from $orders | match (_id == 10) | format {_f0: year(orderDate)}");

            // INNER JOIN — IR path: join condition gets an extra layer of parens from JoinStage's
            // serializer wrapping ser(condition), which for a BinaryOp already adds outer parens.
            check(
                    soft,
                    "select distinct c from Customer c join Order o on c.id = o.customerId",
                    "from c1_0=$customers | join o1_0=$orders ((c1_0._id == o1_0.customerId))"
                            + " | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}"
                            + " | distinct");

            // INNER JOIN with WHERE
            check(
                    soft,
                    "select distinct c from Customer c join Order o on c.id = o.customerId where o.total > 100",
                    "from c1_0=$customers | join o1_0=$orders ((c1_0._id == o1_0.customerId))"
                            + " | match (o1_0.total > 100)"
                            + " | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}"
                            + " | distinct");

            // LEFT OUTER JOIN
            check(
                    soft,
                    "select distinct c from Customer c left join Order o on c.id = o.customerId",
                    "from c1_0=$customers | join leftOuter o1_0=$orders ((c1_0._id == o1_0.customerId))"
                            + " | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}"
                            + " | distinct");

            // RIGHT OUTER JOIN
            check(
                    soft,
                    "select distinct o from Customer c right join Order o on c.id = o.customerId",
                    "from c1_0=$customers | join rightOuter o1_0=$orders ((c1_0._id == o1_0.customerId))"
                            + " | format {_id: o1_0._id, customerId: o1_0.customerId, orderDate: o1_0.orderDate, status: o1_0.status, total: o1_0.total}"
                            + " | distinct");

            // FULL OUTER JOIN
            check(
                    soft,
                    "select distinct c from Customer c full join Order o on c.id = o.customerId",
                    "from c1_0=$customers | join fullOuter o1_0=$orders ((c1_0._id == o1_0.customerId))"
                            + " | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}"
                            + " | distinct");

            // Scalar aggregate — count
            check(
                    soft,
                    "select count(c) from Customer c",
                    "from $customers | agg {_agg0: count($)} | format {_f0: _agg0}");

            // Scalar aggregate — multiple aggregates
            check(
                    soft,
                    "select count(c), sum(c.age), avg(c.age), min(c.age), max(c.age) from Customer c",
                    "from $customers"
                            + " | agg {_agg0: count($), _agg1: sum($->age), _agg2: avg($->age), _agg3: min($->age), _agg4: max($->age)}"
                            + " | format {_f0: _agg0, _f1: _agg1, _f2: _agg2, _f3: _agg3, _f4: _agg4}");

            // GROUP BY — count
            check(
                    soft,
                    "select o.status, count(o.id) from Order o group by o.status",
                    "from $orders | group (status=status) (_agg0=count($->_id)) | format {status: status, _f0: _agg0}");

            // GROUP BY — sum
            check(
                    soft,
                    "select o.status, sum(o.total) from Order o group by o.status",
                    "from $orders | group (status=status) (_agg0=sum($->total)) | format {status: status, _f0: _agg0}");

            // GROUP BY — multiple aggregates
            check(
                    soft,
                    "select o.status, count(o.id), sum(o.total), min(o.total), max(o.total)"
                            + " from Order o where o.status = 'shipped' group by o.status",
                    "from $orders | match (status == \"shipped\")"
                            + " | group (status=status) (_agg0=count($->_id), _agg1=sum($->total), _agg2=min($->total), _agg3=max($->total))"
                            + " | format {status: status, _f0: _agg0, _f1: _agg1, _f2: _agg2, _f3: _agg3}");

            // GROUP BY — multiple keys
            check(
                    soft,
                    "select o.customerId, o.status, count(o.id) from Order o group by o.customerId, o.status",
                    "from $orders | group (customerId=customerId, status=status) (_agg0=count($->_id))"
                            + " | format {customerId: customerId, status: status, _f0: _agg0}");

            // HAVING — aggregate in SELECT
            check(
                    soft,
                    "select o.status, count(o.id) from Order o group by o.status having count(o.id) > 1",
                    "from $orders | group (status=status) (_agg0=count($->_id)) | match (_agg0 > 1)"
                            + " | format {status: status, _f0: _agg0}");

            // HAVING — aggregate not in SELECT
            check(
                    soft,
                    "select o.status from Order o group by o.status having count(o.id) > 1",
                    "from $orders | group (status=status) (_agg0=count($->_id)) | match (_agg0 > 1)"
                            + " | format {status: status}");

            // EXISTS
            check(
                    soft,
                    "from Customer c where exists (from Order o where o.customerId = c.id)",
                    "from $customers"
                            + " | match (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) > 0)"
                            + " | " + fmtC);

            // NOT EXISTS
            check(
                    soft,
                    "from Customer c where not exists (from Order o where o.customerId = c.id)",
                    "from $customers"
                            + " | match (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) == 0)"
                            + " | " + fmtC);

            // IN subquery — no intermediate format stage; correlated match applied directly
            check(
                    soft,
                    "from Customer c where c.id in (select o.customerId from Order o where o.total > 100)",
                    "from $customers"
                            + " | match (count(let $__v0 = _id in (from $orders | match (total > 100) | match (customerId == $__v0))) > 0)"
                            + " | " + fmtC);

            // NOT IN subquery
            check(
                    soft,
                    "from Customer c where c.id not in (select o.customerId from Order o)",
                    "from $customers"
                            + " | match (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) == 0)"
                            + " | " + fmtC);

            // ANY subquery
            check(
                    soft,
                    "from Customer c where c.id > any (select o.customerId from Order o where o.total > 100)",
                    "from $customers"
                            + " | match (count(let $__v0 = _id in (from $orders | match (total > 100) | match (customerId < $__v0))) > 0)"
                            + " | " + fmtC);

            // ALL subquery
            check(
                    soft,
                    "from Customer c where c.id > all (select o.customerId from Order o where o.total > 100)",
                    "from $customers"
                            + " | match (count(let $__v0 = _id in (from $orders | match (total > 100) | match (customerId >= $__v0))) == 0)"
                            + " | " + fmtC);

            // Scalar subquery in SELECT
            check(
                    soft,
                    "select c.name, (select count(o) from Order o where o.customerId = c.id) from Customer c",
                    "from $customers"
                            + " | format {name: name, _f0: count(let $__v0 = _id in (from $orders | match (customerId == $__v0)))}");

            // UNION ALL
            check(
                    soft,
                    "from Customer c where c.age > 30 union all from Customer c where c.active = true",
                    "from << (from $customers | match (age > 30) | " + fmtC + "),"
                            + " (from $customers | match (active == true) | " + fmtC + ") >>"
                            + " | unwind $*");

            // UNION
            check(
                    soft,
                    "from Customer c where c.age > 30 union from Customer c where c.active = true",
                    "from << (from $customers | match (age > 30) | " + fmtC + "),"
                            + " (from $customers | match (active == true) | " + fmtC + ") >>"
                            + " | unwind $* | distinct");

            // INTERSECT
            check(
                    soft,
                    "from Customer c where c.age > 30 intersect from Customer c where c.active = true",
                    "from $customers | match (age > 30) | " + fmtC
                            + " | match (count(let $__v0 = $ in (from $customers | match (active == true) | " + fmtC
                            + " | match ($ == $__v0))) > 0)");

            // EXCEPT
            check(
                    soft,
                    "from Customer c where c.active = true except from Customer c where c.age > 30",
                    "from $customers | match (active == true) | " + fmtC
                            + " | match (count(let $__v0 = $ in (from $customers | match (age > 30) | " + fmtC
                            + " | match ($ == $__v0))) == 0)");

            // $elemMatch — querying embedded arrays
            var fmtCart = "format {_id: _id, lineItems: lineItems, minQty: minQty}";

            // EXISTS — single predicate
            check(
                    soft,
                    "from Cart c where exists (from c.lineItems li where li.sku = 'WIDGET-1')",
                    "from $carts | match (lineItems any ($.sku == \"WIDGET-1\")) | " + fmtCart);

            // EXISTS — AND conjunction in body
            check(
                    soft,
                    "from Cart c where exists (" + "from c.lineItems li where li.sku = 'WIDGET-1' and li.qty > 0)",
                    "from $carts | match (lineItems any (($.sku == \"WIDGET-1\") and ($.qty > 0))) | " + fmtCart);

            // EXISTS — OR disjunction in body
            check(
                    soft,
                    "from Cart c where exists (" + "from c.lineItems li where li.sku = 'WIDGET-1' or li.qty > 5)",
                    "from $carts | match (lineItems any (($.sku == \"WIDGET-1\") or ($.qty > 5))) | " + fmtCart);

            // EXISTS — NOT in body
            check(
                    soft,
                    "from Cart c where exists (" + "from c.lineItems li where not (li.sku = 'WIDGET-1'))",
                    "from $carts | match (lineItems any (not ($.sku == \"WIDGET-1\"))) | " + fmtCart);

            // NOT EXISTS
            check(
                    soft,
                    "from Cart c where not exists (from c.lineItems li where li.sku = 'WIDGET-1')",
                    "from $carts | match (not (lineItems any ($.sku == \"WIDGET-1\"))) | " + fmtCart);

            // EXISTS — correlated outer reference
            check(
                    soft,
                    "from Cart c where exists (from c.lineItems li where li.qty > c.minQty)",
                    "from $carts | match let $__v0 = minQty in (lineItems any ($.qty > $__v0)) | " + fmtCart);

            // JOIN — single predicate (row-multiplying; re-wraps matched element)
            // IR path: unwind uses $__elem=lineItems (no spaces around = from JoinStage serializer)
            check(
                    soft,
                    "select c from Cart c join c.lineItems li where li.sku = 'WIDGET-1'",
                    "from $carts"
                            + " | unwind $__elem=lineItems in {_id: _id, lineItems: $__elem, minQty: minQty}"
                            + " | match (lineItems.sku == \"WIDGET-1\")"
                            + " | format {_id: _id, lineItems: [lineItems], minQty: minQty}");

            // JOIN — projecting the alias field
            check(
                    soft,
                    "select c.id, li.sku from Cart c join c.lineItems li where li.sku = 'WIDGET-1'",
                    "from $carts"
                            + " | unwind $__elem=lineItems in {_id: _id, lineItems: $__elem}"
                            + " | match (lineItems.sku == \"WIDGET-1\")"
                            + " | format {_id: _id, sku: lineItems.sku}");

            // Scalar count(*) subquery over struct array
            check(
                    soft,
                    "select c.id, (select count(*) from c.lineItems li where li.qty > 4) from Cart c order by c.id",
                    "from $carts | sort _id | format {_id: _id, _f0: (count((from lineItems | match ($.qty > 4))))}");

            // Array functions
            var fmtInv = "format {_id: _id, boxedScores: boxedScores, scores: scores}";

            check(
                    soft,
                    "from Inventory i where array_length(i.scores) > 2",
                    "from $inventory | match (count(scores) > 2) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where cardinality(i.scores) = 0",
                    "from $inventory | match (count(scores) == 0) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where array_get(i.scores, 1) = 10",
                    "from $inventory | match (scores[(1 - 1)] == 10) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where array_contains(i.scores, 30)",
                    "from $inventory | match scores any (($ == 30)) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where not array_contains(i.scores, 30)",
                    "from $inventory | match (not (scores any (($ == 30)))) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where array_contains_nullable(i.boxedScores, :needle)",
                    q -> q.setParameter("needle", (Integer) null),
                    "from $inventory | match boxedScores any (($ is $p0)) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where array_intersects(i.scores, array(30, 99))",
                    "from $inventory | match scores any (let $__x = $ in [30, 99] any (($ == $__x))) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where array_overlaps(i.scores, array(10, 40))",
                    "from $inventory | match scores any (let $__x = $ in [10, 40] any (($ == $__x))) | " + fmtInv);

            check(
                    soft,
                    "from Inventory i where array_intersects_nullable(i.boxedScores, :needles)",
                    q -> q.setParameter("needles", new Integer[] {null}),
                    "from $inventory | match boxedScores any (let $__x = $ in $p0 any (($ is $__x))) | " + fmtInv);
        });
    }

    private void check(SoftAssertions soft, String hql, String expected) {
        check(soft, hql, q -> {}, expected);
    }

    @SuppressWarnings("deprecation")
    private void check(SoftAssertions soft, String hql, Consumer<Query> setup, String expected) {
        MqlCapture.LAST.remove();
        try {
            sessionFactoryScope.inSession(session -> {
                var query = session.createQuery(hql);
                setup.accept(query);
                query.getResultList();
            });
        } catch (Exception ignored) {
        }
        var json = MqlCapture.LAST.get();
        if (json == null) {
            soft.fail("No MQLv2 captured for HQL: " + hql);
            return;
        }
        soft.assertThat(BsonDocument.parse(json).getString("mqlv2").getValue())
                .as("HQL: " + hql)
                .isEqualTo(expected);
    }

    @Entity(name = "Cart")
    @Table(name = "carts")
    static class Cart {
        @Id
        int id;

        int minQty;

        LineItem[] lineItems;

        Cart() {}

        Cart(int id, int minQty, LineItem[] lineItems) {
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

    @Entity(name = "Inventory")
    @Table(name = "inventory")
    static class Inventory {
        @Id
        int id;

        int[] scores;

        Integer[] boxedScores;

        Inventory() {}

        Inventory(int id, int[] scores, Integer[] boxedScores) {
            this.id = id;
            this.scores = scores;
            this.boxedScores = boxedScores;
        }
    }
}
