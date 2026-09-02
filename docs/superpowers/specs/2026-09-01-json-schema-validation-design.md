# HIBERNATE-256: JSON schema validation on collection creation

Collections are created bare today. The table exporter emits `{"create": "<name>"}` and the
adapter calls the driver's no-arg `createCollection`, so the server enforces nothing about the
documents an application writes. This derives a `$jsonSchema` validator from the entity mapping
and attaches it when the collection is created, so MongoDB rejects a write whose shape does not
match the mapping: a field of the wrong BSON type, a missing field the mapping requires, or a
field the mapping does not know about. Applications opt in.

## Configuration

`com.mongodb.hibernate.schema.validation`, boolean, default `false`. When false, the create
command carries no validator and collections are created bare, exactly as today. Opting in does
not by itself create anything: the property matters only when a create command is generated, so
with schema-generation action `update`, `validate` or `none` it has no effect.

The dialect reads the property in its `MongoDialect(DialectResolutionInfo)` constructor, which
Hibernate calls with the full configuration map. The no-arg constructor, used only when that one
fails, cannot see the property and leaves validation off, which is the default anyway.

## Scope

The validator rides the create command and nothing else. An existing collection is never
re-validated because no `collMod` path exists. `collMod` was considered and set aside: with
`validationLevel: strict` the server refuses to apply a stricter validator while any existing
document violates it, which makes a routine mapping change fail startup; `moderate` would
grandfather old documents silently, which is the same as no validation until the data is
rewritten. Skipping both keeps `update` and `validate` schema actions behaving exactly as they
do today: nothing.

`validationAction` (error) and `validationLevel` (strict) stay at server defaults, so the
command carries only the validator document.

## The derived schema

The generator emits one document schema per entity mapped to the table, built from that
entity's identifier column(s), the discriminator column of a single-table hierarchy, and its
property closure — recursing into plain embeddables to their leaf columns, taking the single
`AggregateColumn` for `@Struct` and array properties, and resolving association columns
through the referenced entity's identifier type. Columns are ordered by the table's own column
order where the table has them, with the entity's remaining columns appended; this matters
because Hibernate drops a top-level column from the `Table` when a `@Struct` sub-field shares
its name, while the write path still writes the property. A table one entity maps gets that
entity's schema directly. A table several entities map gets an `anyOf` over their schemas, so
each writer's document is validated against exactly its own shape and a document mixing
fields from two writers is rejected. A table no entity maps still gets a schema, for its
columns plus a synthetic `_id`: MongoDB generates the id regardless. Every mapped column
becomes a property; the column's JDBC type decides the BSON type. The mapping matches the one
`ValueConversions` already implements:

| Column type | `bsonType` |
|---|---|
| Boolean | `bool` |
| Character, String | `string` |
| Integer | `int` |
| Byte | `int` |
| Long | `long` |
| Double | `double` |
| BigDecimal | `decimal` |
| `byte[]` | `binData` |
| ObjectId | `objectId` |
| Instant | `date` |
| `@Struct` embeddable | `object`, with the rules below applied recursively to the embeddable's fields |
| array or collection | `array`, with `items` derived from the element type |

A `@Struct` embeddable registers as a single aggregate column, and its
`MongoStructJdbcType` carries the `EmbeddableMappingType`, which is where the recursive
sub-schema comes from.

A plain embeddable, one without `@Struct`, is stored flattened: Hibernate gives each of its
fields its own column (`flattened1_a` alongside `flattened1_b`), including fields of nested
plain embeddables. Those fields are ordinary leaf columns, so each becomes a top-level
property with its own BSON type, nullability and `required` status. There is no object level
and no nested `additionalProperties` for them, because there is no subdocument to close.

Rules applied at every object level, the collection and each nested struct:

- `properties` for every mapped field.
- `required` covers exactly the non-nullable fields: a field whose `bsonType` is a single
  type must be present; a field whose `bsonType` list contains `"null"` may be omitted or
  stored null. This is SQL's shape — a nullable column may be absent, a NOT NULL column must
  be present — and it holds uniformly at the top level and inside every subdocument.
- When several entities share one collection, the schema is an `anyOf` over their document
  schemas. Each branch demands its own entity's non-nullable fields, so every writer's insert
  passes while a document mixing fields from two writers fails every branch's
  `additionalProperties: false`. Each branch also uses its own entity's `@Struct` component
  for a struct column: the table's name-keyed column map keeps only one of two same-named
  struct columns (the same upstream defect that drops colliding top-level columns), so the
  surviving table column alone cannot describe both writers.
- An association column (`@ManyToOne`, `@OneToOne`) is stored as the referenced entity's id
  value, so the generator types it from the referenced identifier's columns, reusing the
  composite-id grouping when the identifier is embedded.
- An enum field is stored as its name for `@Enumerated(EnumType.STRING)` and its ordinal
  otherwise, so the generator emits `enum` with exactly those literals — the closed set the
  write path can produce. A nullable enum field appends a BSON `null` literal to the list.
- A collection field classified as `Set` semantics emits `uniqueItems: true`: a Java `Set`
  cannot contain duplicates, so the write path can never produce a duplicate-element array
  and the constraint only binds external writers. `List`-classified fields get no such
  constraint.
- An enum-typed collection element emits its closed literal set as the array's `items`
  schema, with a `null` literal appended, matching the always-null-permitted element rule.
- `@Column(length)` is deliberately not expressed as `maxLength`: the write path does not
  enforce string length, so emitting it would newly reject writes that currently succeed.
- Nullability, which governs both the list and `required`, comes from the mapping, made
  uniform by derivation: `@Column(nullable = false)` is tracked everywhere, and a primitive
  field is non-nullable at every level — Hibernate tracks entity-level primitives itself, and
  the generator derives primitive-ness for embeddable fields by reflection over the
  embeddable's Java class. Jakarta validation constraints are mirrored into nullability only
  when the application supplies hibernate-validator on the classpath, only at the entity
  level, and inside an embeddable only when the embeddable property is `@Valid` and itself
  not-null-constrained.
- One asymmetry survives because the two storage shapes differ: a null `@Struct` binds the
  whole column as null, so its sub-fields are written only when the struct is present and a
  primitive sub-field is non-nullable. A null plain embeddable binds each of its flattened
  columns as null, so a primitive leaf inside a nullable plain embeddable allows null and is
  not required; when the embeddable chain is non-nullable, the primitive leaf is non-nullable
  again.
- A nullable field gets a two-element list, `"long"` and `"null"` for a nullable Long. Nulls
  bind as `BsonNull` and are stored as BSON null, never omitted: `setNull` routes through
  `ValueConversions.toBsonValue(null)`, which returns `BsonNull.VALUE`. A single-element
  `bsonType` would reject every stored null.
- A nullable array element likewise gets the two-element list, because a null embeddable or
  scalar is stored as an array element (`[{a: 1}, null]`), not removed from the array.
- `additionalProperties: false`. The document contains the mapped fields and nothing else.

The identifier column, `_id`, is typed like any other column.

A composite identifier, `@EmbeddedId`, arrives as leaf columns named with the `_id.` dot-path
encoding that `EmbeddedIdColumnName` owns (`_id.bookNo`, `_id.publisherId`). The generator
groups those columns into one `_id` property of `bsonType: object`, with one sub-property per
component, every component `required`, and `additionalProperties: false` on the `_id` object.
Components are restricted to basic values at boot, so the sub-properties are always scalars.
`@IdClass` is forbidden at boot, so `@EmbeddedId` is the only composite form to handle.

A column reaching the generator without a BSON mapping is an invariant violation, not an
unsupported feature. The HIBERNATE-73 forbid-types work means every mapped field already has a
known BSON representation by the time DDL export runs, so the generator throws `AssertionError`
per the ARCHITECTURE.md failure contract rather than `FeatureNotSupportedException`.

### Limitations, no ticket

The generator's type mapping and the write path's conversions are two mappings over the same
type set. The integration test suite runs with the property on, so every type and document
shape the suite exercises is written through a validated collection: a field type added to the
write path without a matching generator entry fails the suite as soon as a test writes it. The
export-time `AssertionError` remains the backstop for a type no test exercises.

## Create command

```json
{"create": "books", "validator": {"$jsonSchema": {
  "bsonType": "object",
  "properties": {
    "_id": {"bsonType": "long"},
    "title": {"bsonType": ["string", "null"]},
    "author": {"bsonType": "object", "properties": {"name": {"bsonType": "string"}},
               "required": ["name"], "additionalProperties": false}
  },
  "required": ["_id"],
  "additionalProperties": false}}}
```

Rendered with `MongoConstants.EXTENDED_JSON_WRITER_SETTINGS`, like every command the extension
generates.

`AdminCommand.CreateCollectionCommand` decodes the `validator` document when present and passes
it to `createCollection` as `CreateCollectionOptions` with `ValidationOptions.validator(...)`;
when absent, the create runs bare. The switch is stated in the emitted command, by the exporter,
not keyed on the command name in the adapter, so a captured command tells the whole story.

## Implementation

One new class, `MongoJsonSchemaGenerator` in `internal/dialect/`, alongside
`MongoIndexExporter`. It takes the `Table` and returns the `$jsonSchema` `BsonDocument`. The
table exporter's `getSqlCreateStrings` embeds it under `validator` when the property is set;
`getSqlDropStrings` is unchanged. `MongoAdditionalMappingContributor` is not involved: DDL
generation stays in the dialect layer, and the contributor runs regardless of the
schema-management action, which does not match a DDL-only concern.

`ARCHITECTURE.md`'s Schema DDL section changes with this. Its claim that `getTableExporter()`
has nothing to render is no longer true, and it gains the invariant from the limitations
above: a field type's BSON representation exists in two places, the write path and the schema
generator, and adding a field type means updating both. The note is there because that is easy
to miss; UUID support, for instance, would otherwise land with a binder and no validator.

## Tests

Integration tests follow `IndexIntegrationTests`: `create-drop` with
`halt_on_error = true`, captured commands asserted exactly, server-side state asserted through
the driver. Validation is enforced by the server, so the tests write through the driver
directly, bypassing Hibernate, which cannot produce a non-conforming document. Every test in
the table below sets `com.mongodb.hibernate.schema.validation = true` except the first, which
asserts the default.

| Test | Covers |
|---|---|
| default configuration | create command carries no validator |
| create command with validator, exact JSON | emitted shape, asserted once per schema shape |
| insert of a conforming document via the driver | accepted |
| insert with a wrong BSON type | `DocumentValidationFailure` (code 121) |
| insert missing a non-nullable field | rejected |
| insert missing a nullable field | accepted; `required` covers non-nullable fields only |
| insert with a null for a nullable field | accepted, the two-element list at work |
| insert with a literal outside an enum's closed set | rejected |
| insert with duplicate elements in a `Set`-mapped array | rejected, `uniqueItems` |
| insert mixing fields from two entities sharing a collection | rejected, fails every `anyOf` branch |
| insert with an unmapped field | rejected, `additionalProperties: false` |
| nested struct: wrong subfield type, extra subfield, missing required subfield | recursion |
| array with a null element | accepted when the element is nullable |
| one entity per BSON type in the table above | the full mapping |
| `@EmbeddedId` create command and enforcement | `_id` as a closed object; insert with an extra or missing `_id` component rejected |
| schema-qualified collection name | validator on `schema.name` |
| boot against a pre-created collection | no `collMod` in the command history |

The generator has no unit tests: building a boot-model `Table` fixture requires a
`MetadataBuildingContext`, which costs more than it pins. The exact command assertions in the
integration tests are the coverage.
