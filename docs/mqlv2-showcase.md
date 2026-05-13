# mongo-hibernate MQLv2 Backend — Query Showcase

**Date:** 2026-05-13
**Status:** Skunkworks

## Architecture

```
HQL string
  │
  ▼  Hibernate parses to SQL AST
Mqlv2SelectTranslator
  │  walks SQL AST, appends MQLv2 text stages to StringBuilder
  │  JdbcParameter nodes become $p0, $p1, … variable references
  ▼
{"mqlv2": "from $customers | match … | format {…}",
 "_mqlv2FieldNames": ["name","age"],
 "_mqlv2ParamCount": 1}
  │
  ▼  MongoPreparedStatement.executeQuery()
     builds let doc: {p0: <value>, p1: <value>, …}
  │
  ▼  MongoStatement.executeMqlv2Query()
     mongoDatabase.mqlv2(session, () -> mqlv2Text, BsonDocument.class)
                  .let({p0: …, p1: …})
                  .cursor()
  │
  ▼
MongoResultSet  (reuses existing infrastructure)
```

SELECT queries go through `Mqlv2SelectTranslator`; INSERT/UPDATE/DELETE
stay on the existing MQLv1 aggregation pipeline path unchanged.

`_mqlv2FieldNames` carries the projected field names so `MongoResultSet`
can map result documents back to Hibernate without parsing the MQLv2 text.

---

## Entity models

```java
@Entity(name = "Customer")
@Table(name = "customers")
class Customer {
    @Id int id;          // stored as _id in MongoDB
    String name;
    int age;
    boolean active;
}

@Entity(name = "Order")
@Table(name = "orders")
class Order {
    @Id int id;
    int customerId;
    double total;
    String status;
    Instant orderDate;
}
```

---

## Parameter binding

HQL named/positional parameters (`:name`, `?1`) are passed to the server via
the `let` document. The MQLv2 text references them as `$p0`, `$p1`, …; the
server resolves each variable from the corresponding `let` entry.

HQL literal values (`25`, `'shipped'`, `true`, etc.) are rendered **inline**
in the MQLv2 text and do not appear in the `let` document.

| Java type | Example value | MQLv2 reference | let value |
|---|---|---|---|
| `String` | `"Alice"` | `$p0` | `"Alice"` |
| `int` / `long` | `42` | `$p0` | `42` |
| `double` | `100.0` | `$p0` | `100.0` |
| `boolean` | `true` | `$p0` | `true` |
| `null` | — | `$p0` | `null` |
| `Instant` | `2024-01-15T00:00:00Z` | `$p0` | `ISODate("2024-01-15T00:00:00Z")` |

---

## Sample queries

### Basic SELECT

```
HQL:   from Customer

MQLv2: from $customers
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — simple comparison

```
HQL:   from Customer c where c.age > 25

MQLv2: from $customers
       | match (age > 25)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — named parameter

```
HQL:   from Customer c where c.name = :name   [:name = "Alice"]

MQLv2: from $customers
       | match (name == $p0)
       | format {_id: _id, active: active, age: age, name: name}

       let: {p0: "Alice"}
```

---

### WHERE — AND conjunction

```
HQL:   from Customer c where c.age > 20 and c.age < 32

MQLv2: from $customers
       | match ((age > 20) and (age < 32))
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — not-equal

```
HQL:   from Order o where o.status != 'shipped'

MQLv2: from $orders
       | match (status != "shipped")
       | format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}
```

---

### WHERE — boolean field predicate

```
HQL:   from Customer c where c.active

MQLv2: from $customers
       | match (active == true)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — arithmetic expression

```
HQL:   from Customer c where c.age * 2 > 55

MQLv2: from $customers
       | match ((age * 2) > 55)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — date-part extraction

```
HQL:   from Order o where year(o.orderDate) = 2023

MQLv2: from $orders
       | match (year(orderDate) == 2023)
       | format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}
```

---

### WHERE — IN list

```
HQL:   from Customer c where c.age in (25, 30)

MQLv2: from $customers
       | match ((age == 25) or (age == 30))
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — NOT IN list

```
HQL:   from Customer c where c.age not in (25, 30)

MQLv2: from $customers
       | match ((age != 25) and (age != 30))
       | format {_id: _id, active: active, age: age, name: name}
```

---

### WHERE — IS NULL / IS NOT NULL

MQLv2 follows SQL three-valued logic (3VL): `== null` is always `null`
(unknown), never `true`. Use `isNullish()` for null checks.

```
HQL:   from Order o where o.status is null

MQLv2: from $orders
       | match isNullish(status)
       | format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}
```

```
HQL:   from Order o where o.status is not null

MQLv2: from $orders
       | match (not isNullish(status))
       | format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}
```

---

### NULL semantics — equality with null parameter

Both queries below return zero rows regardless of the data.

```
HQL:   from Order o where o.status = :status   [:status = null]

MQLv2: from $orders
       | match (status == $p0)
       | format {…}

       let: {p0: null}
       → 0 rows (null == null is null, not true)
```

```
HQL:   from Order o where o.status != :status   [:status = null]

MQLv2: from $orders
       | match (status != $p0)
       | format {…}

       let: {p0: null}
       → 0 rows ("shipped" != null is null, not true)
```

Use `IS NULL` / `IS NOT NULL` rather than `= null` / `!= null`.

---

### ORDER BY DESC + LIMIT (HQL clause)

```
HQL:   from Customer c order by c.age desc limit 2

MQLv2: from $customers
       | sort age desc
       | limit 2
       | format {_id: _id, active: active, age: age, name: name}
```

---

### ORDER BY DESC + LIMIT (setMaxResults)

`setMaxResults(n)` binds the limit via a `let` variable so the same cached translation works for any value of `n`.

```
Java:  session.createSelectionQuery("from Customer c order by c.age desc", Customer.class)
              .setMaxResults(2).getResultList()

MQLv2: from $customers
       | sort age desc
       | limit $p0
       | format {_id: _id, active: active, age: age, name: name}

       let: {p0: 2}
```

---

### ORDER BY ASC

Ascending is the MQLv2 default — the `asc` keyword is omitted.

```
HQL:   from Customer c order by c.age asc

MQLv2: from $customers
       | sort age
       | format {_id: _id, active: active, age: age, name: name}
```

---

### SELECT — arithmetic expression

```
HQL:   select c.age * 2 from Customer c where c.id = 1

MQLv2: from $customers
       | match (_id == 1)
       | format {_f0: (age * 2)}
```

---

### SELECT — arithmetic with parameter

```
HQL:   select c.age + :bonus from Customer c where c.id = 2   [:bonus = 5]

MQLv2: from $customers
       | match (_id == $p0)
       | format {_f0: (age + $p1)}

       let: {p0: 2, p1: 5}
```

---

### SELECT — date-part extraction

Date-part functions (`year`, `month`, `day`, `hour`, `minute`) return
`Integer`; `second` returns `Float` (fractional seconds).

```
HQL:   select year(o.orderDate) from Order o where o.id = 10

MQLv2: from $orders
       | match (_id == 10)
       | format {_f0: year(orderDate)}
```

Supported units: `year`, `month`, `day` → `dayOfMonth`, `dayOfYear`,
`dayOfWeek`, `hour`, `minute`, `second`. Others throw
`FeatureNotSupportedException`.

---

### INNER JOIN

Hibernate assigns internal table aliases (`c1_0`, `o1_0`) that appear
in the generated MQLv2 regardless of the aliases used in HQL.

```
HQL:   select distinct c
       from Customer c join Order o on c.id = o.customerId

MQLv2: from c1_0=$customers
       | join o1_0=$orders (c1_0._id == o1_0.customerId)
       | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}
       | distinct
```

---

### INNER JOIN with WHERE

```
HQL:   select distinct c
       from Customer c join Order o on c.id = o.customerId
       where o.total > 100

MQLv2: from c1_0=$customers
       | join o1_0=$orders (c1_0._id == o1_0.customerId)
       | match (o1_0.total > 100)
       | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}
       | distinct
```

---

### LEFT OUTER JOIN

```
HQL:   select distinct c
       from Customer c left join Order o on c.id = o.customerId

MQLv2: from c1_0=$customers
       | join leftOuter o1_0=$orders (c1_0._id == o1_0.customerId)
       | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}
       | distinct
```

---

### RIGHT OUTER JOIN

```
HQL:   select distinct o
       from Customer c right join Order o on c.id = o.customerId

MQLv2: from c1_0=$customers
       | join rightOuter o1_0=$orders (c1_0._id == o1_0.customerId)
       | format {_id: o1_0._id, customerId: o1_0.customerId, orderDate: o1_0.orderDate, status: o1_0.status, total: o1_0.total}
       | distinct
```

---

### FULL OUTER JOIN

```
HQL:   select distinct c
       from Customer c full join Order o on c.id = o.customerId

MQLv2: from c1_0=$customers
       | join fullOuter o1_0=$orders (c1_0._id == o1_0.customerId)
       | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}
       | distinct
```

---

### Scalar aggregate — count

Without GROUP BY, aggregate functions use `| agg {key: fn($)}`.
`count(c)` and `count(*)` both map to `count($)`.

```
HQL:   select count(c) from Customer c

MQLv2: from $customers
       | agg {_agg0: count($)}
       | format {_f0: _agg0}
```

---

### Scalar aggregate — multiple aggregates

```
HQL:   select count(c), sum(c.age), avg(c.age), min(c.age), max(c.age)
       from Customer c

MQLv2: from $customers
       | agg {_agg0: count($), _agg1: sum($->age), _agg2: avg($->age), _agg3: min($->age), _agg4: max($->age)}
       | format {_f0: _agg0, _f1: _agg1, _f2: _agg2, _f3: _agg3, _f4: _agg4}
```

---

### GROUP BY — count

Aggregate expressions are assigned synthetic `_aggN` names in the group
stage and referenced as `_fN` keys in the format stage.

```
HQL:   select o.status, count(o.id) from Order o group by o.status

MQLv2: from $orders
       | group (status=status) (_agg0=count($->_id))
       | format {status: status, _f0: _agg0}
```

---

### GROUP BY — sum

```
HQL:   select o.status, sum(o.total) from Order o group by o.status

MQLv2: from $orders
       | group (status=status) (_agg0=sum($->total))
       | format {status: status, _f0: _agg0}
```

---

### GROUP BY — multiple aggregates

```
HQL:   select o.status, count(o.id), sum(o.total), min(o.total), max(o.total)
       from Order o where o.status = 'shipped' group by o.status

MQLv2: from $orders
       | match (status == "shipped")
       | group (status=status) (_agg0=count($->_id), _agg1=sum($->total), _agg2=min($->total), _agg3=max($->total))
       | format {status: status, _f0: _agg0, _f1: _agg1, _f2: _agg2, _f3: _agg3}
```

---

### GROUP BY — multiple keys

```
HQL:   select o.customerId, o.status, count(o.id)
       from Order o group by o.customerId, o.status

MQLv2: from $orders
       | group (customerId=customerId, status=status) (_agg0=count($->_id))
       | format {customerId: customerId, status: status, _f0: _agg0}
```

---

### HAVING — aggregate in SELECT

The HAVING predicate becomes a `| match` stage after `| group`. Aggregate
references in HAVING resolve to the same `_aggN` names used in SELECT.

```
HQL:   select o.status, count(o.id) from Order o
       group by o.status having count(o.id) > 1

MQLv2: from $orders
       | group (status=status) (_agg0=count($->_id))
       | match (_agg0 > 1)
       | format {status: status, _f0: _agg0}
```

---

### HAVING — aggregate not in SELECT

Aggregates referenced only in HAVING are computed in the group stage under
a synthetic `_aggN` name and dropped by the format stage.

```
HQL:   select o.status from Order o
       group by o.status having count(o.id) > 1

MQLv2: from $orders
       | group (status=status) (_agg0=count($->_id))
       | match (_agg0 > 1)
       | format {status: status}
```

---

### EXISTS

```
HQL:   from Customer c where exists (select 1 from Order o where o.customerId = c.id)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) > 0)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### NOT EXISTS

```
HQL:   from Customer c where not exists (select 1 from Order o where o.customerId = c.id)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (customerId == $__v0))) == 0)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### IN subquery

```
HQL:   from Customer c where c.id in (select o.customerId from Order o where o.total > 100)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (total > 100) | format {customerId: customerId} | match (customerId == $__v0))) > 0)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### NOT IN subquery

```
HQL:   from Customer c where c.id not in (select o.customerId from Order o)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | format {customerId: customerId} | match (customerId == $__v0))) == 0)
       | format {_id: _id, active: active, age: age, name: name}
```

---

### ANY subquery

```
HQL:   from Customer c where c.id > any (select o.customerId from Order o where o.total > 100)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (total > 100) | format {customerId: customerId} | match (customerId < $__v0))) > 0)
       | format {_id: _id, active: active, age: age, name: name}
```

`SOME` is an alias for `ANY` and produces identical MQLv2.

---

### ALL subquery

```
HQL:   from Customer c where c.id > all (select o.customerId from Order o where o.total > 100)

MQLv2: from $customers
       | match (count(let $__v0 = _id in (from $orders | match (total > 100) | format {customerId: customerId} | match (customerId >= $__v0))) == 0)
       | format {_id: _id, active: active, age: age, name: name}
```

`ALL` uses `== 0` because the condition must hold for every row in the subquery result — zero rows may violate it.

---

### Scalar subquery in SELECT

```
HQL:   select c.name, (select count(o) from Order o where o.customerId = c.id) from Customer c

MQLv2: from $customers
       | format {name: name, _f0: count(let $__v0 = _id in (from $orders | match (customerId == $__v0)))}
```

`count(pipeline)` returns the cardinality of the subpipeline — analogous to SQL `COUNT(*)`.

---

### UNION ALL

```
HQL:   from Customer c where c.age > 30 union all from Customer c where c.active = true

MQLv2: from << (from $customers | match (age > 30) | format {_id: _id, active: active, age: age, name: name}),
              (from $customers | match (active == true) | format {_id: _id, active: active, age: age, name: name}) >>
       | unwind $*
```

`UNION ALL` keeps duplicates. `from << (p1), (p2) >> | unwind $*` concatenates subpipelines into a flat stream.

---

### UNION

```
HQL:   from Customer c where c.age > 30 union from Customer c where c.active = true

MQLv2: from << (from $customers | match (age > 30) | format {_id: _id, active: active, age: age, name: name}),
              (from $customers | match (active == true) | format {_id: _id, active: active, age: age, name: name}) >>
       | unwind $*
       | distinct
```

---

### INTERSECT

```
HQL:   from Customer c where c.age > 30 intersect from Customer c where c.active = true

MQLv2: from $customers | match (age > 30) | format {_id: _id, active: active, age: age, name: name}
       | match (count(let $__v0 = $ in (from $customers | match (active == true) | format {_id: _id, active: active, age: age, name: name} | match ($ == $__v0))) > 0)
```

`$ == $__v0` tests whole-document equality — valid because both sides of a set operation project the same columns.

---

### EXCEPT

```
HQL:   from Customer c where c.active = true except from Customer c where c.age > 30

MQLv2: from $customers | match (active == true) | format {_id: _id, active: active, age: age, name: name}
       | match (count(let $__v0 = $ in (from $customers | match (age > 30) | format {_id: _id, active: active, age: age, name: name} | match ($ == $__v0))) == 0)
```

---

## Known limitations (current scope)

- OFFSET / skip — MQLv2 has no skip stage; throws `FeatureNotSupportedException`
- INTERSECT ALL / EXCEPT ALL — throws `FeatureNotSupportedException`
- Scalar subquery with non-count aggregate — throws `FeatureNotSupportedException`
- Subquery in FROM (derived table) — throws `FeatureNotSupportedException`
- INSERT / UPDATE / DELETE — handled by existing MQLv1 path
