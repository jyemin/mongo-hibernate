# mongo-hibernate MQLv2 Backend — Query Showcase

**Date:** 2026-05-12  
**Status:** Skunkworks

## Architecture

```
HQL string
  │
  ▼  Hibernate parses to SQL AST
Mqlv2SelectTranslator
  │  walks SQL AST, appends MQLv2 text stages to StringBuilder
  │  JdbcParameter nodes become {?0}, {?1}, … placeholders
  ▼
{"mqlv2": "from $customers | match … | format {…}", "_mqlv2FieldNames": ["name","age"]}
  │
  ▼  MongoStatement.executeQuery() detects "mqlv2" key
MongoPreparedStatement.substituteMqlv2Params()
  │  replaces {?N} with rendered BsonValue literals
  ▼
mongoDatabase.mqlv2(clientSession, text)  ←  driver MQLv2 API
  │
  ▼
MongoResultSet  (reuses existing infrastructure)
```

SELECT queries go through `Mqlv2SelectTranslator`; INSERT/UPDATE/DELETE
stay on the existing MQLv1 aggregation pipeline path unchanged.

`_mqlv2FieldNames` carries the projected field names so `MongoResultSet`
can map result documents back to Hibernate without parsing the MQLv2 text.

Hibernate binds even HQL literal values (e.g., `c.age > 25`) as JDBC
parameters at the SQL AST level, so nearly all scalar values in predicates
appear as `{?N}` placeholders in the generated text.

---

## Entity models

```java
@Entity(name = "Customer")
@Table(name = "customers")
class Customer {
    @Id int id;       // stored as _id in MongoDB
    String name;
    int age;
}

@Entity(name = "Order")
@Table(name = "orders")
class Order {
    @Id int id;
    int customerId;
    double total;
    String status;
}
```

---

## Sample queries

### Basic SELECT

```
HQL:   from Customer

MQLv2: from $customers
       | format {_id: _id, age: age, name: name}
```

---

### WHERE — simple comparison

```
HQL:   from Customer c where c.age > 25

MQLv2: from $customers
       | match (age > {?0})
       | format {_id: _id, age: age, name: name}

       {?0} = 25
```

---

### WHERE — named parameter

```
HQL:   from Customer c where c.name = :name   [:name = "Alice"]

MQLv2: from $customers
       | match (name == {?0})
       | format {_id: _id, age: age, name: name}

       {?0} = "Alice"
```

---

### WHERE — AND conjunction

```
HQL:   from Customer c where c.age > 20 and c.age < 32

MQLv2: from $customers
       | match ((age > {?0}) && (age < {?1}))
       | format {_id: _id, age: age, name: name}

       {?0} = 20, {?1} = 32
```

---

### WHERE — not-equal

```
HQL:   from Order o where o.status != 'shipped'

MQLv2: from $orders
       | match (status != {?0})
       | format {_id: _id, customerId: customerId, status: status, total: total}

       {?0} = "shipped"
```

---

### ORDER BY DESC + LIMIT

```
HQL:   from Customer c order by c.age desc limit 2

MQLv2: from $customers
       | sort age desc
       | limit 2
       | format {_id: _id, age: age, name: name}
```

---

### ORDER BY ASC

```
HQL:   from Customer c order by c.age asc

MQLv2: from $customers
       | sort age asc
       | format {_id: _id, age: age, name: name}
```

---

### INNER JOIN

Hibernate assigns internal table aliases (`c1_0`, `o1_0`) that appear
in the generated MQLv2 regardless of the aliases used in HQL.

```
HQL:   select distinct c
       from Customer c join Order o on c.id = o.customerId

MQLv2: from c1_0=$customers
       | join o1_0=$orders (c1_0._id == o1_0.customerId)
       | format {_id: c1_0._id, age: c1_0.age, name: c1_0.name}
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
       | match (o1_0.total > {?0})
       | format {_id: c1_0._id, age: c1_0.age, name: c1_0.name}
       | distinct

       {?0} = 100
```

---

### LEFT OUTER JOIN

```
HQL:   select distinct c
       from Customer c left join Order o on c.id = o.customerId

MQLv2: from c1_0=$customers
       | join leftOuter o1_0=$orders (c1_0._id == o1_0.customerId)
       | format {_id: c1_0._id, age: c1_0.age, name: c1_0.name}
       | distinct
```

---

### IS NULL

```
HQL:   from Order o where o.status is null

MQLv2: from $orders
       | match isNullish(status)
       | format {_id: _id, customerId: customerId, status: status, total: total}
```

---

### IS NOT NULL

```
HQL:   from Order o where o.status is not null

MQLv2: from $orders
       | match (not isNullish(status))
       | format {_id: _id, customerId: customerId, status: status, total: total}
```

---

### NULL semantics — equality with null parameter

MQLv2 follows SQL three-valued logic (3VL): any comparison with `null`
evaluates to `null` (unknown), not `true` or `false`. Both queries below
return zero rows regardless of the data.

```
HQL:   from Order o where o.status = :status   [:status = null]

MQLv2: from $orders
       | match (status == null)
       | format {_id: _id, customerId: customerId, status: status, total: total}

       → 0 rows (null == null is null, not true)
```

```
HQL:   from Order o where o.status != :status   [:status = null]

MQLv2: from $orders
       | match (status != null)
       | format {_id: _id, customerId: customerId, status: status, total: total}

       → 0 rows ("shipped" != null is null, not true)
```

Use `IS NULL` / `IS NOT NULL` rather than `= null` / `!= null`.

---

## Parameter substitution

`{?N}` markers are substituted in `MongoPreparedStatement.substituteMqlv2Params()`
before the text reaches the server. String values are JSON-escaped; numeric and
boolean values are rendered as bare literals.

| Java type | Example value | MQLv2 literal |
|---|---|---|
| `String` | `"Alice"` | `"Alice"` |
| `int` / `long` | `42` | `42` |
| `double` | `100.0` | `100.0` |
| `boolean` | `true` | `true` |
| `null` | — | `null` |
| `Instant` | `2024-01-15T00:00:00Z` | `date("2024-01-15T00:00:00Z")` |

---

## Known limitations (current scope)

- OFFSET / skip — MQLv2 has no skip stage; throws `FeatureNotSupportedException`
- RIGHT / FULL OUTER JOIN — not supported
- GROUP BY / aggregation functions — not supported
- Subqueries — not supported
- INSERT / UPDATE / DELETE — handled by existing MQLv1 path
