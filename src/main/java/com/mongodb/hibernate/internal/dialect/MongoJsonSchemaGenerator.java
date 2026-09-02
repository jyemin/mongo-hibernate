/*
 * Copyright 2026-present MongoDB, Inc.
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

package com.mongodb.hibernate.internal.dialect;

import static com.mongodb.hibernate.internal.MongoConstants.ID_FIELD_NAME;
import static java.lang.String.format;

import com.mongodb.hibernate.internal.EmbeddedIdColumnName;
import com.mongodb.hibernate.internal.type.MongoArrayJdbcType;
import com.mongodb.hibernate.internal.type.ObjectIdJdbcType;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.hibernate.boot.Metadata;
import org.hibernate.mapping.AggregateColumn;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.ToOne;
import org.hibernate.mapping.Value;
import org.hibernate.metamodel.CollectionClassification;
import org.hibernate.type.BasicPluralType;
import org.hibernate.type.BasicType;
import org.hibernate.type.descriptor.java.EnumJavaType;
import org.hibernate.type.descriptor.java.spi.BasicCollectionJavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.TimestampUtcAsInstantJdbcType;
import org.jspecify.annotations.Nullable;

/**
 * @hidden
 * @mongoCme Must be thread-safe.
 */
@SuppressWarnings("MissingSummary")
final class MongoJsonSchemaGenerator {

    private static final BsonString BSON_NULL = new BsonString("null");

    private MongoJsonSchemaGenerator() {}

    static BsonDocument jsonSchemaFor(Table table, Metadata metadata) {
        var entities = metadata.getEntityBindings().stream()
                .filter(entity -> Objects.equals(entity.getTable(), table))
                .toList();

        if (entities.isEmpty()) {
            return documentSchema(table.getColumns(), metadata, Set.of());
        }
        var branches = new BsonArray();
        for (var entity : entities) {
            branches.add(entitySchema(entity, table, metadata));
        }
        if (branches.size() == 1) {
            return branches.get(0).asDocument();
        }
        return new BsonDocument("bsonType", new BsonString("object")).append("anyOf", branches);
    }

    private static BsonDocument entitySchema(PersistentClass entity, Table table, Metadata metadata) {
        var primitiveLeafColumns = new LinkedHashSet<String>();
        collectPrimitiveEmbeddableLeafColumns(entity, primitiveLeafColumns);
        var entityColumns = collectEntityColumnNames(entity);
        var columns = new LinkedHashMap<String, Column>();
        for (var column : table.getColumns()) {
            if (entityColumns.containsKey(column.getName())) {
                columns.put(column.getName(), column);
            }
        }
        // the table's name-keyed column map keeps only one of two same-named columns, so replace each
        // survivor with this entity's own column: nullability and struct components are per entity
        for (var entry : entityColumns.entrySet()) {
            columns.put(entry.getKey(), entry.getValue());
        }
        return documentSchema(columns.values(), metadata, primitiveLeafColumns);
    }

    private static BsonDocument documentSchema(
            Iterable<Column> columns, Metadata metadata, Set<String> primitiveLeafColumns) {
        var nonIdProperties = new BsonDocument();
        var nonIdRequired = new BsonArray();
        var idProperties = new BsonDocument();
        var idRequired = new BsonArray();

        for (var column : columns) {
            if (EmbeddedIdColumnName.isComponent(column.getName())) {
                var componentName = EmbeddedIdColumnName.componentName(column.getName());
                idProperties.append(
                        componentName,
                        new BsonDocument("bsonType", new BsonString(bsonTypeFor(jdbcTypeOf(column, metadata)))));
                idRequired.add(new BsonString(componentName));
            } else {
                var allowsNull = column.isNullable() && !primitiveLeafColumns.contains(column.getName());
                nonIdProperties.append(column.getName(), propertySchema(column, metadata, allowsNull));
                if (!allowsNull) {
                    nonIdRequired.add(new BsonString(column.getName()));
                }
            }
        }

        var properties = new BsonDocument();
        var required = new BsonArray();
        if (!idProperties.isEmpty()) {
            var idSchema = new BsonDocument("bsonType", new BsonString("object")).append("properties", idProperties);
            appendIfNonEmpty(idSchema, idRequired);
            idSchema.append("additionalProperties", BsonBoolean.FALSE);
            properties.append(ID_FIELD_NAME, idSchema);
            required.add(new BsonString(ID_FIELD_NAME));
        }
        properties.putAll(nonIdProperties);
        required.addAll(nonIdRequired);
        if (!properties.containsKey(ID_FIELD_NAME)) {
            properties.append(ID_FIELD_NAME, new BsonDocument("bsonType", new BsonString("objectId")));
            required.add(0, new BsonString(ID_FIELD_NAME));
        }
        var schema = new BsonDocument("bsonType", new BsonString("object")).append("properties", properties);
        appendIfNonEmpty(schema, required);
        schema.append("additionalProperties", BsonBoolean.FALSE);
        return schema;
    }

    private static void appendIfNonEmpty(BsonDocument doc, BsonArray required) {
        if (!required.isEmpty()) {
            doc.append("required", required);
        }
    }

    private static void collectPrimitiveEmbeddableLeafColumns(PersistentClass entity, Set<String> result) {
        for (var property : entity.getPropertyClosure()) {
            var value = property.getValue();
            if (value instanceof Component component && component.getAggregateColumn() == null) {
                collectPrimitiveEmbeddableLeafColumnsFromComponent(component, result, value.isNullable());
            }
        }
    }

    private static void collectPrimitiveEmbeddableLeafColumnsFromComponent(
            Component component, Set<String> result, boolean allowsNull) {
        for (var property : component.getProperties()) {
            var value = property.getValue();
            if (value instanceof Component subComponent) {
                if (subComponent.getAggregateColumn() == null) {
                    collectPrimitiveEmbeddableLeafColumnsFromComponent(
                            subComponent, result, allowsNull || value.isNullable());
                }
            } else if (value instanceof SimpleValue) {
                if (!allowsNull && fieldIsPrimitive(component, property.getName())) {
                    for (var column : columnsOf(value)) {
                        result.add(column.getName());
                    }
                }
            }
        }
    }

    private static boolean fieldIsPrimitive(Component component, String propertyName) {
        var componentClass = component.getComponentClass();
        if (componentClass == null) {
            return false;
        }
        for (var clazz = componentClass; clazz != null; clazz = clazz.getSuperclass()) {
            try {
                return clazz.getDeclaredField(propertyName).getType().isPrimitive();
            } catch (NoSuchFieldException e) {
                // continue up the class hierarchy
            }
        }
        throw new AssertionError(format("Embeddable [%s] has no field [%s]", componentClass.getName(), propertyName));
    }

    private static Map<String, Column> collectEntityColumnNames(PersistentClass entity) {
        var columns = new LinkedHashMap<String, Column>();
        for (var column : columnsOf(entity.getIdentifier())) {
            columns.put(column.getName(), column);
        }
        var discriminator = entity.getDiscriminator();
        if (discriminator != null) {
            for (var column : columnsOf(discriminator)) {
                columns.put(column.getName(), column);
            }
        }
        for (var property : entity.getPropertyClosure()) {
            collectPropertyColumns(property, columns);
        }
        return columns;
    }

    private static void collectPropertyColumns(Property property, Map<String, Column> columns) {
        var value = property.getValue();
        if (value instanceof Component component) {
            if (component.getAggregateColumn() != null) {
                columns.put(component.getAggregateColumn().getName(), component.getAggregateColumn());
            } else {
                for (var subProperty : component.getProperties()) {
                    collectPropertyColumns(subProperty, columns);
                }
            }
        } else {
            for (var column : columnsOf(value)) {
                columns.put(column.getName(), column);
            }
        }
    }

    private static List<Column> columnsOf(Value value) {
        return value.getSelectables().stream()
                .filter(Column.class::isInstance)
                .map(Column.class::cast)
                .toList();
    }

    private static BsonDocument propertySchema(Column column, Metadata metadata, boolean allowsNull) {
        var jdbcType = jdbcTypeOf(column, metadata);
        if (jdbcType instanceof MongoArrayJdbcType arrayJdbcType) {
            var items = column instanceof AggregateColumn aggregate
                    ? structSchema(aggregate.getComponent(), true)
                    : elementSchema(column.getValue(), arrayJdbcType.getElementJdbcType());
            var arraySchema = new BsonDocument("bsonType", bsonType("array", allowsNull)).append("items", items);
            if (mapsSetSemantics(column.getValue())) {
                arraySchema.append("uniqueItems", BsonBoolean.TRUE);
            }
            return arraySchema;
        }
        if (column instanceof AggregateColumn aggregate) {
            return structSchema(aggregate.getComponent(), allowsNull);
        }
        var enumSchema = enumSchema(column.getValue(), allowsNull);
        if (enumSchema != null) {
            return enumSchema;
        }
        return new BsonDocument("bsonType", bsonType(bsonTypeFor(jdbcType), allowsNull));
    }

    private static @Nullable BsonDocument enumSchema(Value value, boolean allowsNull) {
        if (value instanceof SimpleValue simpleValue && simpleValue.getType() instanceof BasicType<?> basicType) {
            return enumSchemaFor(basicType, allowsNull);
        }
        return null;
    }

    private static @Nullable BsonDocument enumSchemaFor(BasicType<?> basicType, boolean allowsNull) {
        if (!(basicType.getJavaTypeDescriptor() instanceof EnumJavaType<?> enumJavaType)) {
            return null;
        }
        var ordinal =
                switch (basicType.getJdbcType().getJdbcTypeCode()) {
                    case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> true;
                    default -> false;
                };
        var constants = enumJavaType.getJavaTypeClass().getEnumConstants();
        var literals = new BsonArray();
        for (var index = 0; index < constants.length; index++) {
            literals.add(ordinal ? new BsonInt32(index) : new BsonString(constants[index].name()));
        }
        if (allowsNull) {
            literals.add(BsonNull.VALUE);
        }
        return new BsonDocument("enum", literals);
    }

    private static BsonDocument elementSchema(Value value, JdbcType elementJdbcType) {
        if (value instanceof SimpleValue simpleValue
                && simpleValue.getType() instanceof BasicPluralType<?, ?> pluralType) {
            var enumSchema = enumSchemaFor(pluralType.getElementType(), true);
            if (enumSchema != null) {
                return enumSchema;
            }
        }
        return new BsonDocument("bsonType", bsonType(bsonTypeFor(elementJdbcType), true));
    }

    private static boolean mapsSetSemantics(Value value) {
        return value instanceof SimpleValue simpleValue
                && simpleValue.getType() instanceof BasicPluralType<?, ?> pluralType
                && pluralType.getJavaTypeDescriptor() instanceof BasicCollectionJavaType<?, ?> collectionJavaType
                && collectionJavaType.getSemantics().getCollectionClassification() == CollectionClassification.SET;
    }

    private static BsonDocument structSchema(Component component, boolean allowsNull) {
        var properties = new BsonDocument();
        var required = new BsonArray();
        collectStructProperties(component, properties, required, false);
        var schema = new BsonDocument("bsonType", bsonType("object", allowsNull)).append("properties", properties);
        appendIfNonEmpty(schema, required);
        schema.append("additionalProperties", BsonBoolean.FALSE);
        return schema;
    }

    private static void collectStructProperties(
            Component component, BsonDocument properties, BsonArray required, boolean chainAllowsNull) {
        for (var property : component.getProperties()) {
            var value = property.getValue();
            if (value instanceof Component subComponent) {
                if (subComponent.getAggregateColumn() != null) {
                    properties.append(property.getName(), structSchema(subComponent, value.isNullable()));
                    if (!value.isNullable()) {
                        required.add(new BsonString(property.getName()));
                    }
                } else {
                    collectStructProperties(subComponent, properties, required, chainAllowsNull || value.isNullable());
                }
            } else if (value instanceof SimpleValue simpleValue
                    && simpleValue.getType() instanceof BasicType<?> basicType) {
                var jdbcType = basicType.getJdbcType();
                if (jdbcType instanceof MongoArrayJdbcType arrayJdbcType) {
                    var itemColumns = columnsOf(simpleValue);
                    var items = !itemColumns.isEmpty() && itemColumns.get(0) instanceof AggregateColumn aggregate
                            ? structSchema(aggregate.getComponent(), true)
                            : elementSchema(simpleValue, arrayJdbcType.getElementJdbcType());
                    var arraySchema =
                            new BsonDocument("bsonType", bsonType("array", value.isNullable())).append("items", items);
                    if (mapsSetSemantics(value)) {
                        arraySchema.append("uniqueItems", BsonBoolean.TRUE);
                    }
                    properties.append(property.getName(), arraySchema);
                    if (!value.isNullable()) {
                        required.add(new BsonString(property.getName()));
                    }
                } else {
                    var allowsNull =
                            value.isNullable() && (chainAllowsNull || !fieldIsPrimitive(component, property.getName()));
                    var enumSchema = enumSchema(simpleValue, allowsNull);
                    properties.append(
                            property.getName(),
                            enumSchema != null
                                    ? enumSchema
                                    : new BsonDocument("bsonType", bsonType(bsonTypeFor(jdbcType), allowsNull)));
                    if (!allowsNull) {
                        required.add(new BsonString(property.getName()));
                    }
                }
            } else {
                throw new AssertionError(
                        format("Struct property [%s] does not map to a basic type", property.getName()));
            }
        }
    }

    private static BsonValue bsonType(String bsonType, boolean allowsNull) {
        if (!allowsNull) {
            return new BsonString(bsonType);
        }
        return new BsonArray(List.of(new BsonString(bsonType), BSON_NULL));
    }

    private static JdbcType jdbcTypeOf(Column column, Metadata metadata) {
        var value = column.getValue();
        if (value instanceof ToOne toOne) {
            var referencedEntity = metadata.getEntityBinding(toOne.getReferencedEntityName());
            if (referencedEntity == null) {
                throw new AssertionError(format(
                        "Unresolvable entity reference [%s] for column [%s]",
                        toOne.getReferencedEntityName(), column.getName()));
            }
            var referencedIdColumns = referencedEntity.getIdentifier().getColumns();
            var fkColumns = toOne.getColumns();
            var index = fkColumns.indexOf(column);
            if (index < 0 || index >= referencedIdColumns.size()) {
                throw new AssertionError(
                        format("FK column [%s] does not correspond to a referenced id column", column.getName()));
            }
            return jdbcTypeOf(referencedIdColumns.get(index), metadata);
        }
        if (value instanceof SimpleValue simpleValue && simpleValue.getType() instanceof BasicType<?> basicType) {
            return basicType.getJdbcType();
        }
        throw new AssertionError(format("Column [%s] does not map to a basic type", column.getName()));
    }

    private static String bsonTypeFor(JdbcType jdbcType) {
        if (jdbcType instanceof ObjectIdJdbcType) {
            return "objectId";
        }
        if (jdbcType instanceof TimestampUtcAsInstantJdbcType) {
            return "date";
        }
        return switch (jdbcType.getJdbcTypeCode()) {
            case Types.INTEGER, Types.TINYINT -> "int";
            case Types.BIGINT -> "long";
            case Types.DOUBLE -> "double";
            case Types.NUMERIC, Types.DECIMAL -> "decimal";
            case Types.VARCHAR, Types.CHAR -> "string";
            case Types.BOOLEAN -> "bool";
            case Types.VARBINARY -> "binData";
            default -> throw new AssertionError(format("No BSON type for JDBC type [%s]", jdbcType));
        };
    }
}
