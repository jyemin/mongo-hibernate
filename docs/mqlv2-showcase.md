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
     appends to command: {"mqlv2": "…", "let": {p0: …}}
  │
  ▼  MongoStatement routes to mongoDatabase.runCommand("mqlv2", …)
     server resolves $p0/$p1/… from the let document
  │
  ▼
MongoResultSet  (reuses existing infrastructure)
```

SELECT queries go through `Mqlv2SelectTranslator`; INSERT/UPDATE/DELETE
stay on the existing MQLv1 aggregation pipeline path unchanged.

`_mqlv2FieldNames` carries the projected field names so `MongoResultSet`
can map result documents back to Hibernate without parsing the MQLv2 text.

Parameters are passed server-side via MQLv2's `let` binding: the translator
emits `$p0`, `$p1`, … in the query text; `MongoPreparedStatement` builds
`{let: {p0: <value>, …}}` and appends it to the command document before
execution. This avoids any client-side text manipulation.

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
       | match (age > $p0)
       | format {_id: _id, active: active, age: age, name: name}

       let: {p0: 25}
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
       | match ((age > $p0) && (age < $p1))
       | format {_id: _id, active: active, age: age, name: name}

       let: {p0: 20, p1: 32}
```

---

### WHERE — not-equal

```
HQL:   from Order o where o.status != 'shipped'

MQLv2: from $orders
       | match (status != $p0)
       | format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}

       let: {p0: "shipped"}
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
       | match ((age * 2) > $p0)
       | format {_id: _id, active: active, age: age, name: name}

       let: {p0: 55}
```

---

### WHERE — date-part extraction

```
HQL:   from Order o where year(o.orderDate) = 2023

MQLv2: from $orders
       | match (year(orderDate) == $p0)
       | format {_id: _id, customerId: customerId, orderDate: orderDate, status: status, total: total}

       let: {p0: 2023}
```

---

### ORDER BY DESC + LIMIT

```
HQL:   from Customer c order by c.age desc limit 2

MQLv2: from $customers
       | sort age desc
       | limit 2
       | format {_id: _id, active: active, age: age, name: name}
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
       | match (_id == $p0)
       | format {_f0: (age * 2)}

       let: {p0: 1}
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
       | match (_id == $p0)
       | format {_f0: year(orderDate)}

       let: {p0: 10}
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
       | match (o1_0.total > $p0)
       | format {_id: c1_0._id, active: c1_0.active, age: c1_0.age, name: c1_0.name}
       | distinct

       let: {p0: 100}
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

### IS NULL / IS NOT NULL

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
       | match (status == null)
       | format {…}

       → 0 rows (null == null is null, not true)
```

```
HQL:   from Order o where o.status != :status   [:status = null]

MQLv2: from $orders
       | match (status != null)
       | format {…}

       → 0 rows ("shipped" != null is null, not true)
```

Use `IS NULL` / `IS NOT NULL` rather than `= null` / `!= null`.

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
       | match (status == $p0)
       | group (status=status) (_agg0=count($->_id), _agg1=sum($->total), _agg2=min($->total), _agg3=max($->total))
       | format {status: status, _f0: _agg0, _f1: _agg1, _f2: _agg2, _f3: _agg3}

       let: {p0: "shipped"}
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

### HAVING

The HAVING predicate becomes a `| match` stage after `| group`. Aggregate
references in HAVING resolve to the same `_aggN` names used in SELECT.

```
HQL:   select o.status, count(o.id) from Order o
       group by o.status having count(o.id) > 1

MQLv2: from $orders
       | group (status=status) (_agg0=count($->_id))
       | match (_agg0 > $p0)
       | format {status: status, _f0: _agg0}

       let: {p0: 1}
```

---

## Parameter binding

Parameters are passed to the server via the `let` document. The MQLv2 text
references them as `$p0`, `$p1`, …; the server resolves each variable from
the corresponding `let` entry.

| Java type | Example value | MQLv2 reference | let value |
|---|---|---|---|
| `String` | `"Alice"` | `$p0` | `"Alice"` |
| `int` / `long` | `42` | `$p0` | `42` |
| `double` | `100.0` | `$p0` | `100.0` |
| `boolean` | `true` | `$p0` | `true` |
| `null` | — | `$p0` | `null` |
| `Instant` | `2024-01-15T00:00:00Z` | `$p0` | `ISODate("2024-01-15T00:00:00Z")` |

---

## Known limitations (current scope)

- OFFSET / skip — MQLv2 has no skip stage; throws `FeatureNotSupportedException`
- HAVING referencing aggregates not in SELECT — throws `FeatureNotSupportedException`
- Scalar aggregates (no GROUP BY) — throws `FeatureNotSupportedException`
- Subqueries — not supported
- INSERT / UPDATE / DELETE — handled by existing MQLv1 path
