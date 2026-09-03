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

package com.mongodb.hibernate.internal.type;

import static com.mongodb.hibernate.internal.MongoAssertions.assertFalse;
import static com.mongodb.hibernate.internal.MongoAssertions.assertInstanceOf;
import static com.mongodb.hibernate.internal.MongoAssertions.assertNotNull;
import static com.mongodb.hibernate.internal.MongoAssertions.assertTrue;
import static com.mongodb.hibernate.internal.MongoAssertions.fail;
import static com.mongodb.hibernate.internal.type.ValueConversions.isNull;
import static com.mongodb.hibernate.internal.type.ValueConversions.toArrayDomainValue;
import static com.mongodb.hibernate.internal.type.ValueConversions.toBsonValue;
import static com.mongodb.hibernate.internal.type.ValueConversions.toDomainValue;

import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.bson.BsonDocument;
import org.hibernate.metamodel.mapping.DiscriminatedAssociationModelPart;
import org.hibernate.metamodel.mapping.EmbeddableMappingType;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.EntityAssociationMapping;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.ValuedModelPart;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.metamodel.spi.ValueAccess;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.AggregateJdbcType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.StructuredJdbcType;
import org.jspecify.annotations.Nullable;

/**
 * @hidden
 * @mongoCme Must be thread-safe.
 */
@SuppressWarnings("MissingSummary")
public final class MongoStructJdbcType implements StructuredJdbcType {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final MongoStructJdbcType INSTANCE = new MongoStructJdbcType();
    public static final JDBCType JDBC_TYPE = JDBCType.STRUCT;
    public static final int HIBERNATE_SQL_TYPE = SqlTypes.STRUCT;

    private final transient @Nullable EmbeddableMappingType embeddableMappingType;
    private final @Nullable String structTypeName;

    private MongoStructJdbcType() {
        this(null, null);
    }

    private MongoStructJdbcType(
            @Nullable EmbeddableMappingType embeddableMappingType, @Nullable String structTypeName) {
        if (embeddableMappingType != null && embeddableMappingType.isPolymorphic()) {
            throw new FeatureNotSupportedException("Polymorphic mapping is not supported");
        }
        this.embeddableMappingType = embeddableMappingType;
        this.structTypeName = structTypeName;
    }

    @Override
    public int getJdbcTypeCode() {
        return JDBC_TYPE.getVendorTypeNumber();
    }

    @Override
    public String getStructTypeName() {
        return assertNotNull(structTypeName);
    }

    /**
     * This method may be called multiple times with equal {@code sqlType} and different {@code mappingType}.
     *
     * @param sqlType The {@link org.hibernate.annotations.Struct#name()}.
     */
    @Override
    public AggregateJdbcType resolveAggregateJdbcType(
            EmbeddableMappingType mappingType, String sqlType, RuntimeModelCreationContext creationContext) {
        return new MongoStructJdbcType(assertNotNull(mappingType), assertNotNull(sqlType));
    }

    @Override
    public EmbeddableMappingType getEmbeddableMappingType() {
        return assertNotNull(embeddableMappingType);
    }

    /**
     * We replaced this method with {@link #createBindValue(Object, WrapperOptions)}, to make it clear that this method
     * is not called by Hibernate ORM.
     */
    @Override
    public BsonDocument createJdbcValue(@Nullable Object domainValue, WrapperOptions options) {
        throw fail();
    }

    private @Nullable BsonDocument createBindValue(@Nullable Object domainValue, WrapperOptions options)
            throws SQLException {
        if (domainValue == null) {
            return null;
        }
        var embeddableMappingType = getEmbeddableMappingType();
        // The flatten walk below turns the domain value into one JDBC value per column, applying each column's
        // `ValueBinder` on the way, which is the unwrap this method needs, and yielding the `BsonDocument` a nested
        // `@Struct` binds to. Fields are written by selectable name rather than by physical position.
        var jdbcValues = getJdbcValues(embeddableMappingType, domainValue, options);
        var result = new BsonDocument();
        var jdbcValueCount = embeddableMappingType.getJdbcValueCount();
        for (var columnIndex = 0; columnIndex < jdbcValueCount; columnIndex++) {
            var jdbcValueSelectable = embeddableMappingType.getJdbcValueSelectable(columnIndex);
            assertFalse(jdbcValueSelectable.isFormula());
            if (!jdbcValueSelectable.isInsertable()) {
                throw new FeatureNotSupportedException(
                        "Persistent attributes of a `@Struct @Embeddable` must be insertable");
            }
            if (!jdbcValueSelectable.isUpdateable()) {
                throw new FeatureNotSupportedException(
                        "Persistent attributes of a `@Struct @Embeddable` must be updatable");
            }
            result.append(jdbcValueSelectable.getSelectableName(), toBsonValue(jdbcValues[columnIndex]));
        }
        return result;
    }

    /**
     * @return The struct attribute values, one per column, taken from the {@link BsonDocument} fields directly.
     *     Hibernate ORM never creates a {@link java.sql.Struct} for this type, so there is no
     *     {@linkplain java.sql.Struct#getAttributes() attribute array} to read.
     */
    @Override
    public Object @Nullable [] extractJdbcValues(@Nullable Object rawJdbcValue, WrapperOptions options)
            throws SQLException {
        if (isNull(rawJdbcValue)) {
            return null;
        }
        var bsonDocument = assertInstanceOf(assertNotNull(rawJdbcValue), BsonDocument.class);
        var embeddableMappingType = getEmbeddableMappingType();
        var jdbcValueCount = embeddableMappingType.getJdbcValueCount();
        var result = new Object[jdbcValueCount];
        for (var columnIndex = 0; columnIndex < jdbcValueCount; columnIndex++) {
            var jdbcValueSelectable = embeddableMappingType.getJdbcValueSelectable(columnIndex);
            assertFalse(jdbcValueSelectable.isFormula());
            var fieldName = jdbcValueSelectable.getSelectableName();
            var value = bsonDocument.get(fieldName);
            var jdbcMapping = jdbcValueSelectable.getJdbcMapping();
            var jdbcTypeCode = jdbcMapping.getJdbcType().getJdbcTypeCode();
            Object domainValue;
            if (isNull(value)) {
                domainValue = null;
            } else if (jdbcTypeCode == getJdbcTypeCode()) {
                var structValueExtractor = assertInstanceOf(jdbcMapping.getJdbcValueExtractor(), Extractor.class);
                domainValue = structValueExtractor.getJdbcType().extractJdbcValues(value, options);
            } else if (jdbcTypeCode == MongoArrayJdbcType.JDBC_TYPE.getVendorTypeNumber()) {
                var arrayJdbcType = assertInstanceOf(jdbcMapping.getJdbcType(), MongoArrayJdbcType.class);
                BasicExtractor<?> jdbcValueExtractor =
                        assertInstanceOf(jdbcMapping.getJdbcValueExtractor(), BasicExtractor.class);
                domainValue =
                        arrayJdbcType.getArray(jdbcValueExtractor, toArrayDomainValue(assertNotNull(value)), options);
            } else {
                // The inverse of `createBindValue`: read the JDBC-level value the binder would have written, then
                // wrap it back into the domain type. A `JdbcType` reporting no preferred Java type binds the domain
                // value unchanged, so it is read back unchanged.
                var preferredJavaTypeClass = jdbcMapping.getJdbcType().getPreferredJavaTypeClass(options);
                var mappedJavaType = jdbcMapping.getMappedJavaType();
                if (preferredJavaTypeClass == null) {
                    domainValue = toDomainValue(assertNotNull(value), mappedJavaType.getJavaTypeClass());
                } else {
                    domainValue =
                            mappedJavaType.wrap(toDomainValue(assertNotNull(value), preferredJavaTypeClass), options);
                }
            }
            result[columnIndex] = domainValue;
        }
        return result;
    }

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new Binder<>(javaType);
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new Extractor<>(javaType);
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        throw new NotSerializableException(
                "This class is not designed to be serialized despite it having to implement `Serializable`");
    }

    /** @mongoCme Must be thread-safe. */
    private final class Binder<X> extends BasicBinder<X> {
        @Serial
        private static final long serialVersionUID = 1L;

        Binder(JavaType<X> javaType) {
            super(javaType, MongoStructJdbcType.this);
        }

        @Override
        public MongoStructJdbcType getJdbcType() {
            return assertInstanceOf(super.getJdbcType(), MongoStructJdbcType.class);
        }

        @Override
        public @Nullable Object getBindValue(@Nullable X value, WrapperOptions options) throws SQLException {
            return getJdbcType().createBindValue(value, options);
        }

        @Override
        protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) throws SQLException {
            st.setObject(index, getBindValue(value, options), getJdbcType().getJdbcTypeCode());
        }

        @Override
        protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    /** @mongoCme Must be thread-safe. */
    private final class Extractor<X> extends BasicExtractor<X> {
        @Serial
        private static final long serialVersionUID = 1L;

        Extractor(JavaType<X> javaType) {
            super(javaType, MongoStructJdbcType.this);
        }

        @Override
        public MongoStructJdbcType getJdbcType() {
            return assertInstanceOf(super.getJdbcType(), MongoStructJdbcType.class);
        }

        @Override
        protected @Nullable X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
            var bsonDocument = rs.getObject(paramIndex, BsonDocument.class);
            var jdbcValues = getJdbcType().extractJdbcValues(bsonDocument, options);
            var classX = getJavaType().getJavaTypeClass();
            Object result;
            if (classX.equals(Object[].class) || jdbcValues == null) {
                result = jdbcValues;
            } else {
                var embeddableMappingType = getEmbeddableMappingType();
                assertTrue(classX.equals(embeddableMappingType.getJavaType().getJavaTypeClass()));
                result = instantiate(
                        embeddableMappingType, getAttributeValues(embeddableMappingType, jdbcValues, options));
            }
            return classX.cast(result);
        }

        @Override
        protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    // The flatten/assemble walks below are adapted from Hibernate ORM's internal
    // org.hibernate.type.descriptor.jdbc.StructHelper (Apache-2.0), reduced to what this type uses:
    // no attribute-order mapping (fields are addressed by selectable name) and no polymorphic
    // embeddables (rejected by the constructor). Associations decompose through the public
    // ModelPart contract rather than the internal ToOneAttributeMapping.

    private static Object[] getJdbcValues(
            EmbeddableMappingType embeddableMappingType, @Nullable Object domainValue, WrapperOptions options)
            throws SQLException {
        final var jdbcValues = new Object[embeddableMappingType.getJdbcValueCount()];
        injectJdbcValues(embeddableMappingType, domainValue, jdbcValues, 0, options);
        return jdbcValues;
    }

    private static int injectJdbcValues(
            EmbeddableMappingType embeddableMappingType,
            @Nullable Object domainValue,
            Object[] jdbcValues,
            int jdbcIndex,
            WrapperOptions options)
            throws SQLException {
        return injectJdbcValues(
                embeddableMappingType,
                domainValue == null ? null : embeddableMappingType.getValues(domainValue),
                jdbcValues,
                jdbcIndex,
                options);
    }

    private static int injectJdbcValues(
            EmbeddableMappingType embeddableMappingType,
            Object @Nullable [] values,
            Object[] jdbcValues,
            int jdbcIndex,
            WrapperOptions options)
            throws SQLException {
        if (values == null) {
            return embeddableMappingType.getJdbcValueCount();
        }
        int offset = 0;
        for (int i = 0; i < values.length; i++) {
            offset += injectJdbcValue(
                    embeddableMappingType.getAttributeMapping(i), values, i, jdbcValues, jdbcIndex + offset, options);
        }
        assertTrue(offset == embeddableMappingType.getJdbcValueCount());
        return offset;
    }

    private static int injectJdbcValue(
            ValuedModelPart attributeMapping,
            Object[] attributeValues,
            int attributeIndex,
            Object[] jdbcValues,
            int jdbcIndex,
            WrapperOptions options)
            throws SQLException {
        if (attributeMapping instanceof PluralAttributeMapping) {
            return 0;
        } else if (attributeMapping instanceof EntityAssociationMapping
                || attributeMapping instanceof DiscriminatedAssociationModelPart) {
            return attributeMapping.decompose(
                    attributeValues[attributeIndex],
                    jdbcIndex,
                    jdbcValues,
                    options,
                    (valueIndex, valueArray, wrapperOptions, value, jdbcValueMapping) -> valueArray[valueIndex] = value,
                    options.getSession());
        } else if (attributeMapping instanceof EmbeddableValuedModelPart embeddableValuedModelPart) {
            final EmbeddableMappingType embeddableMappingType = embeddableValuedModelPart.getMappedType();
            if (embeddableMappingType.getAggregateMapping() != null) {
                jdbcValues[jdbcIndex] = getBindValue(
                        embeddableMappingType
                                .getAggregateMapping()
                                .getJdbcMapping()
                                .getJdbcValueBinder(),
                        attributeValues[attributeIndex],
                        options);
                return 1;
            } else {
                return injectJdbcValues(
                        embeddableMappingType, attributeValues[attributeIndex], jdbcValues, jdbcIndex, options);
            }
        } else {
            assertTrue(attributeMapping.getJdbcTypeCount() == 1);
            final var jdbcMapping = attributeMapping.getSingleJdbcMapping();
            final Object relationalValue = jdbcMapping.convertToRelationalValue(attributeValues[attributeIndex]);
            if (relationalValue != null) {
                injectJdbcValue(jdbcValues, jdbcIndex, options, jdbcMapping, relationalValue);
            }
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object getBindValue(ValueBinder<?> binder, Object value, WrapperOptions options)
            throws SQLException {
        return ((ValueBinder<Object>) binder).getBindValue(value, options);
    }

    private static void injectJdbcValue(
            Object[] jdbcValues, int jdbcIndex, WrapperOptions options, JdbcMapping jdbcMapping, Object relationalValue)
            throws SQLException {
        injectCastJdbcValue(
                jdbcValues, jdbcIndex, options, jdbcMapping, jdbcMapping.getJdbcJavaType(), relationalValue);
    }

    private static <T> void injectCastJdbcValue(
            Object[] jdbcValues,
            int jdbcIndex,
            WrapperOptions options,
            JdbcMapping jdbcMapping,
            JavaType<T> javaType,
            Object relationalValue)
            throws SQLException {
        assertTrue(javaType.isInstance(relationalValue));
        injectJdbcValue(jdbcValues, jdbcIndex, options, jdbcMapping, javaType, javaType.cast(relationalValue));
    }

    private static <T> void injectJdbcValue(
            Object[] jdbcValues,
            int jdbcIndex,
            WrapperOptions options,
            JdbcMapping jdbcMapping,
            JavaType<T> javaType,
            T relationalValue)
            throws SQLException {
        jdbcValues[jdbcIndex] = switch (jdbcMapping.getJdbcType().getDefaultSqlTypeCode()) {
            case SqlTypes.BLOB, SqlTypes.MATERIALIZED_BLOB -> javaType.unwrap(relationalValue, Blob.class, options);
            case SqlTypes.CLOB, SqlTypes.MATERIALIZED_CLOB -> javaType.unwrap(relationalValue, Clob.class, options);
            case SqlTypes.NCLOB, SqlTypes.MATERIALIZED_NCLOB -> javaType.unwrap(relationalValue, NClob.class, options);
            default -> getBindValue(jdbcMapping.getJdbcValueBinder(), relationalValue, options);
        };
    }

    private static AttributeValues getAttributeValues(
            EmbeddableMappingType embeddableMappingType, Object[] rawJdbcValues, WrapperOptions options)
            throws SQLException {
        final int numberOfAttributeMappings = embeddableMappingType.getNumberOfAttributeMappings();
        final var attributeValues = new AttributeValues(numberOfAttributeMappings, rawJdbcValues);
        int jdbcIndex = 0;
        for (int i = 0; i < numberOfAttributeMappings; i++) {
            jdbcIndex += injectAttributeValue(
                    embeddableMappingType.getAttributeMapping(i),
                    attributeValues,
                    i,
                    rawJdbcValues,
                    jdbcIndex,
                    options);
        }
        return attributeValues;
    }

    private static int injectAttributeValue(
            ValuedModelPart modelPart,
            AttributeValues attributeValues,
            int attributeIndex,
            Object[] rawJdbcValues,
            int jdbcIndex,
            WrapperOptions options)
            throws SQLException {
        if (modelPart.getMappedType() instanceof EmbeddableMappingType embeddableMappingType) {
            return injectAttributeValueEmbeddable(
                    attributeValues, attributeIndex, rawJdbcValues, jdbcIndex, options, embeddableMappingType);
        } else {
            assertTrue(modelPart.getJdbcTypeCount() == 1);
            final var jdbcMapping = modelPart.getSingleJdbcMapping();
            final Object jdbcValue = jdbcMapping.getJdbcJavaType().wrap(rawJdbcValues[jdbcIndex], options);
            attributeValues.setAttributeValue(attributeIndex, jdbcMapping.convertToDomainValue(jdbcValue));
            return 1;
        }
    }

    private static int injectAttributeValueEmbeddable(
            AttributeValues attributeValues,
            int attributeIndex,
            Object[] rawJdbcValues,
            int jdbcIndex,
            WrapperOptions options,
            EmbeddableMappingType embeddableMappingType)
            throws SQLException {
        if (embeddableMappingType.getAggregateMapping() != null) {
            attributeValues.setAttributeValue(attributeIndex, rawJdbcValues[jdbcIndex]);
            return 1;
        } else {
            final int jdbcValueCount = embeddableMappingType.getJdbcValueCount();
            final Object[] subJdbcValues = new Object[jdbcValueCount];
            System.arraycopy(rawJdbcValues, jdbcIndex, subJdbcValues, 0, subJdbcValues.length);
            final var subValues = getAttributeValues(embeddableMappingType, subJdbcValues, options);
            attributeValues.setAttributeValue(attributeIndex, instantiate(embeddableMappingType, subValues));
            return jdbcValueCount;
        }
    }

    private static Object instantiate(EmbeddableMappingType embeddableMappingType, AttributeValues attributeValues) {
        return embeddableMappingType
                .getRepresentationStrategy()
                .getInstantiator()
                .instantiate(attributeValues);
    }

    private static final class AttributeValues implements ValueAccess {
        private final Object[] attributeValues;

        AttributeValues(int size, Object @Nullable [] rawJdbcValues) {
            if (rawJdbcValues == null || size != rawJdbcValues.length) {
                attributeValues = new Object[size];
            } else {
                attributeValues = rawJdbcValues;
            }
        }

        @Override
        public Object[] getValues() {
            return attributeValues;
        }

        void setAttributeValue(int index, Object value) {
            attributeValues[index] = value;
        }
    }
}
