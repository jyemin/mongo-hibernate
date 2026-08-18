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

package com.mongodb.hibernate.internal.boot;

import static com.mongodb.hibernate.internal.MongoAssertions.assertFalse;
import static com.mongodb.hibernate.internal.MongoAssertions.assertInstanceOf;
import static com.mongodb.hibernate.internal.MongoAssertions.assertNotNull;
import static com.mongodb.hibernate.internal.MongoAssertions.assertTrue;
import static com.mongodb.hibernate.internal.MongoConstants.ID_FIELD_NAME;
import static java.lang.String.format;
import static java.util.stream.Collectors.toSet;

import com.mongodb.hibernate.internal.EmbeddedIdColumnName;
import com.mongodb.hibernate.internal.FeatureNotSupportedException;
import com.mongodb.hibernate.internal.dialect.MongoDialect;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.TemporalType;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWrapper;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.types.BSONTimestamp;
import org.bson.types.Binary;
import org.bson.types.Code;
import org.bson.types.CodeWScope;
import org.bson.types.CodeWithScope;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.Symbol;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GeneratedColumn;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Struct;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.ResourceStreamLocator;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.spi.AdditionalMappingContributions;
import org.hibernate.boot.spi.AdditionalMappingContributor;
import org.hibernate.boot.spi.InFlightMetadataCollector;
import org.hibernate.boot.spi.MetadataBuildingContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.mapping.AggregateColumn;
import org.hibernate.mapping.BasicValue;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.SimpleValue;
import org.hibernate.mapping.ToOne;
import org.hibernate.mapping.UniqueKey;
import org.hibernate.mapping.Value;
import org.hibernate.type.BasicPluralType;
import org.hibernate.type.ComponentType;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.TimeZoneStorageStrategy;
import org.hibernate.type.UserComponentType;
import org.jspecify.annotations.Nullable;

/**
 * @hidden
 * @mongoCme The instance methods of {@link AdditionalMappingContributor} are called multiple times if multiple
 *     {@link Metadata} instances are {@linkplain MetadataSources#buildMetadata() built} using the same
 *     {@link BootstrapServiceRegistry}.
 */
@SuppressWarnings("MissingSummary")
public final class MongoAdditionalMappingContributor implements AdditionalMappingContributor {
    /**
     * We do not support these characters because BSON fields with names containing them must be handled specially as
     * described in <a href="https://www.mongodb.com/docs/manual/core/dot-dollar-considerations/">Field Names with
     * Periods and Dollar Signs</a>. We also reserve '#' as a separator for computed projections in MQL joins.
     */
    private static final Set<String> UNSUPPORTED_FIELD_NAME_CHARACTERS = Set.of(".", "$", "#");

    /** The types {@code TimeZoneStorageHelper} resolves a time zone storage strategy for. */
    private static final Set<Class<?>> TIME_ZONE_STORAGE_TYPES = Set.of(OffsetDateTime.class, ZonedDateTime.class);

    /** The supported temporal types, each of which denotes an instant and is stored as a BSON {@code Date}. */
    private static final Set<Class<?>> INSTANT_DENOTING_TYPES =
            Set.of(Instant.class, OffsetDateTime.class, ZonedDateTime.class);

    private static final Set<TimeZoneStorageStrategy> UNSUPPORTED_TIME_ZONE_STORAGE_STRATEGIES =
            Set.of(TimeZoneStorageStrategy.NATIVE, TimeZoneStorageStrategy.NORMALIZE);

    private static final Set<Class<?>> UNSUPPORTED_TYPES = Set.of(
            // Temporal types
            Calendar.class,
            Time.class,
            Date.class,
            java.sql.Date.class,
            Timestamp.class,
            LocalDate.class,
            LocalTime.class,
            LocalDateTime.class,
            OffsetTime.class,
            Period.class,
            YearMonth.class,
            MonthDay.class,
            // BSON value types
            BSONTimestamp.class,
            Binary.class,
            Code.class,
            CodeWithScope.class,
            CodeWScope.class,
            MinKey.class,
            MaxKey.class,
            Symbol.class,
            Decimal128.class,
            // BSON document types
            Document.class,
            BsonDocument.class,
            RawBsonDocument.class,
            BsonDocumentWrapper.class,
            // java.util types
            UUID.class);

    public MongoAdditionalMappingContributor() {}

    @Override
    public String getContributorName() {
        return getClass().getSimpleName();
    }

    @Override
    public void contribute(
            AdditionalMappingContributions contributions,
            InFlightMetadataCollector metadata,
            ResourceStreamLocator resourceStreamLocator,
            MetadataBuildingContext buildingContext) {
        if (!(metadata.getDatabase().getDialect() instanceof MongoDialect)) {
            // avoid interfering with bootstrapping unrelated to the MongoDB Extension for Hibernate ORM
            return;
        }
        forbidIdClassIdentifiers(metadata);
        forbidEmbeddablesWithoutPersistentAttributes(metadata);
        metadata.getEntityBindings().forEach(persistentClass -> {
            checkPropertyTypes(persistentClass);
            checkColumnNames(persistentClass);
            forbidStructIdentifier(persistentClass);
            forbidNonScalarIdComponent(persistentClass);
            forbidDerivedIdentity(persistentClass);
            forbidJdbcTypeCodeAnnotation(persistentClass);
            forbidColumnFragmentAnnotations(persistentClass);
            setIdentifierColumnName(persistentClass);
            materializeUniqueColumns(persistentClass);
        });
        forbidCatalog(metadata, buildingContext);
        forbidDottedTableQualifiers(metadata);
        forbidUnsupportedPreferredInstantJdbcType(buildingContext);
    }

    /**
     * Creates a {@link UniqueKey} for each column mapped with {@code @Column(unique = true)} or
     * {@link org.hibernate.annotations.NaturalId}.
     *
     * <p>For SQL dialects, a unique column is specified as part of the table definition. In MongoDB it is an index, so
     * it is represented as an ordinary unique key.
     */
    private static void materializeUniqueColumns(PersistentClass persistentClass) {
        var table = persistentClass.getTable();
        for (var column : table.getColumns()) {
            if (column.isUnique() && !table.isPrimaryKey(column)) {
                var keyName = column.getUniqueKeyName();
                assertNotNull(keyName);
                table.getOrCreateUniqueKey(keyName).addColumn(column);
            }
        }
    }

    /**
     * A MongoDB database is the analog of a SQL catalog, and catalog {@code ->} database is not yet supported.
     * Reporting {@link org.hibernate.engine.jdbc.env.spi.NameQualifierSupport#SCHEMA} makes Hibernate silently drop a
     * catalog qualifier, so reject it here instead: the per-table {@code @Table(catalog)} (and
     * secondary/join/collection tables) surfaces on a namespace, while the global {@code hibernate.default_catalog} is
     * applied only at SQL-render time (not a namespace at this stage), so it is read from configuration. Both are
     * checked.
     */
    private static void forbidCatalog(InFlightMetadataCollector metadata, MetadataBuildingContext buildingContext) {
        var defaultCatalog = buildingContext
                .getBootstrapContext()
                .getServiceRegistry()
                .requireService(ConfigurationService.class)
                .getSettings()
                .get(AvailableSettings.DEFAULT_CATALOG);
        if (defaultCatalog != null && !defaultCatalog.toString().isBlank()) {
            throw catalogNotSupported(defaultCatalog.toString());
        }
        for (var namespace : metadata.getDatabase().getNamespaces()) {
            var catalog = namespace.getName().catalog();
            if (catalog != null) {
                throw catalogNotSupported(catalog.getText());
            }
        }
    }

    private static FeatureNotSupportedException catalogNotSupported(String catalog) {
        return new FeatureNotSupportedException(format(
                "Catalog is not supported: [%s]. A MongoDB database is the analog of a SQL catalog; use a separate"
                        + " SessionFactory per database.",
                catalog));
    }

    /**
     * A schema folds into the collection name as {@code schema.name}, so the '.' is the extension's own separator. A
     * '.' written by the user makes the resolved name ambiguous: {@code @Table(schema = "a", name = "b")} and
     * {@code @Table(name = "a.b")} would resolve to the same collection, and nothing downstream could tell the two
     * qualifiers apart.
     */
    private static void forbidDottedTableQualifiers(InFlightMetadataCollector metadata) {
        for (var namespace : metadata.getDatabase().getNamespaces()) {
            var schema = namespace.getName().schema();
            if (schema != null) {
                forbidDot(schema.getText(), "schema");
            }
            for (var table : namespace.getTables()) {
                forbidDot(table.getName(), "table");
            }
        }
    }

    private static void forbidDot(String name, String kind) {
        if (name.contains(".")) {
            throw new FeatureNotSupportedException(format(
                    "The character [.] in a %s name is not supported, but is present in [%s]. A schema folds into the"
                            + " collection name as [schema.name], so a '.' written by the user would make the resolved"
                            + " collection name ambiguous.",
                    kind, name));
        }
    }

    /**
     * {@code hibernate.type.preferred_instant_jdbc_type} redirects {@link Instant} attributes only, not
     * {@link OffsetDateTime} or {@link ZonedDateTime}. Its default, {@link SqlTypes#TIMESTAMP_UTC}, is the only value
     * with a working path: {@code TIMESTAMP} redirects onto the wall-clock type this design rejects everywhere else,
     * and an instant written on a host in one zone would read back as a different instant on a host in another;
     * {@code INSTANT} reaches a JDBC type code this extension does not register. The setting accepts either the
     * {@link SqlTypes} constant name or its numeric code, so both are checked.
     */
    private static void forbidUnsupportedPreferredInstantJdbcType(MetadataBuildingContext buildingContext) {
        var preferredInstantJdbcType = buildingContext
                .getBootstrapContext()
                .getServiceRegistry()
                .requireService(ConfigurationService.class)
                .getSettings()
                .get(AvailableSettings.PREFERRED_INSTANT_JDBC_TYPE);
        if (preferredInstantJdbcType != null && !denotesTimestampUtc(preferredInstantJdbcType)) {
            throw new FeatureNotSupportedException(format(
                    "The setting [%s] is set to [%s], but only [TIMESTAMP_UTC] (its default) is supported",
                    AvailableSettings.PREFERRED_INSTANT_JDBC_TYPE, preferredInstantJdbcType));
        }
    }

    private static boolean denotesTimestampUtc(Object preferredInstantJdbcType) {
        if (preferredInstantJdbcType instanceof Number number) {
            return number.intValue() == SqlTypes.TIMESTAMP_UTC;
        }
        var text = preferredInstantJdbcType.toString().trim();
        if ("TIMESTAMP_UTC".equalsIgnoreCase(text)) {
            return true;
        }
        try {
            return Integer.parseInt(text) == SqlTypes.TIMESTAMP_UTC;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void checkPropertyTypes(PersistentClass persistentClass) {
        var mappedClass = persistentClass.getMappedClass();
        checkPropertyType(persistentClass, mappedClass, persistentClass.getIdentifierProperty(), new StringJoiner("."));
        persistentClass.getProperties().forEach(property -> {
            checkPropertyType(persistentClass, mappedClass, property, new StringJoiner("."));
        });
    }

    private static void checkColumnNames(PersistentClass persistentClass) {
        persistentClass
                .getTable()
                .getColumns()
                .forEach(column -> forbidUnsupportedFieldNameCharacters(column.getName(), persistentClass));
    }

    private static void forbidUnsupportedFieldNameCharacters(String fieldName, PersistentClass persistentClass) {
        UNSUPPORTED_FIELD_NAME_CHARACTERS.forEach(unsupportedCharacter -> {
            if (fieldName.contains(unsupportedCharacter)) {
                throw new FeatureNotSupportedException(format(
                        "%s: the character [%s] in field names is not supported, but is present in the field name [%s]",
                        persistentClass, unsupportedCharacter, fieldName));
            }
        });
    }

    private static void forbidStructIdentifier(PersistentClass persistentClass) {
        if (persistentClass.getIdentifier() instanceof Component aggregateEmbeddableIdentifier
                && aggregateEmbeddableIdentifier.getStructName() != null) {
            throw new FeatureNotSupportedException(format(
                    "%s: aggregate embeddable primary keys are not supported, you may want to use [@%s] instead of [@%s @%s]",
                    persistentClass,
                    Embeddable.class.getSimpleName(),
                    Embeddable.class.getSimpleName(),
                    Struct.class.getSimpleName()));
        }
    }

    /**
     * Forbid a non-aggregated composite identifier ({@code @IdClass}, or multiple {@code @Id} attributes). Must run
     * before {@link #forbidEmbeddablesWithoutPersistentAttributes}, which would otherwise misfire on the synthetic
     * identifier-mapper {@link Component} with a misleading message.
     */
    private static void forbidIdClassIdentifiers(InFlightMetadataCollector metadata) {
        metadata.getEntityBindings().forEach(persistentClass -> {
            if (persistentClass.getIdentifierMapper() != null) {
                throw new FeatureNotSupportedException(format(
                        "%s: a non-aggregated composite identifier (@IdClass, or multiple @Id attributes) is not"
                                + " supported; declare the composite key with @EmbeddedId."
                                + " TODO-HIBERNATE-235 https://jira.mongodb.org/browse/HIBERNATE-235",
                        persistentClass));
            }
        });
    }

    /**
     * Forbid a composite id component that is not a basic value: a nested embeddable or a collection, or an association
     * (derived identity nested inside the id, as opposed to the entity-level {@code @MapsId} shape caught by
     * {@link #forbidDerivedIdentity}). Every component of a composite id must be a basic value.
     */
    private static void forbidNonScalarIdComponent(PersistentClass persistentClass) {
        if (persistentClass.getIdentifier() instanceof Component idComponent) {
            for (var property : idComponent.getProperties()) {
                var value = property.getValue();
                if (value instanceof ToOne) {
                    throw new FeatureNotSupportedException(format(
                            "%s: an association inside the id (derived identity) is not supported;"
                                    + " every component of a composite id must be a basic value."
                                    + " TODO-HIBERNATE-237 https://jira.mongodb.org/browse/HIBERNATE-237",
                            persistentClass));
                }
                if (value instanceof Component || value instanceof Collection) {
                    throw new FeatureNotSupportedException(format(
                            "%s: a non-scalar id component (nested embeddable or collection) is not supported;"
                                    + " every component of a composite id must be a basic value."
                                    + " TODO-HIBERNATE-236 https://jira.mongodb.org/browse/HIBERNATE-236",
                            persistentClass));
                }
            }
        }
    }

    /**
     * Forbid derived identity ({@code @MapsId}): an entity-level {@link ToOne} property whose column(s) overlap the
     * identifier's columns. Must run before {@link #setIdentifierColumnName}, which renames the id column(s) to
     * {@code _id} (or {@code _id.<component>}) and would destroy the overlap this check relies on.
     */
    private static void forbidDerivedIdentity(PersistentClass persistentClass) {
        var idColumnNames = persistentClass.getIdentifier().getColumns().stream()
                .map(column -> column.getName())
                .collect(toSet());
        for (var property : persistentClass.getProperties()) {
            if (property.getValue() instanceof ToOne toOne
                    && !toOne.hasFormula()
                    && toOne.getColumns().stream().anyMatch(column -> idColumnNames.contains(column.getName()))) {
                throw new FeatureNotSupportedException(format(
                        "%s: derived identity (an association participating in the id, e.g. @MapsId) is not"
                                + " supported. TODO-HIBERNATE-237 https://jira.mongodb.org/browse/HIBERNATE-237",
                        persistentClass));
            }
        }
    }

    /** Forbid usage of {@link JdbcTypeCode} annotation. */
    private static void forbidJdbcTypeCodeAnnotation(PersistentClass persistentClass) {
        ClassElementChecker.check(persistentClass, true, ClassElementChecker.forbid(JdbcTypeCode.class));
    }

    private static void forbidEmbeddablesWithoutPersistentAttributes(InFlightMetadataCollector metadata) {
        metadata.visitRegisteredComponents(component -> {
            if (!component.hasAnyInsertableColumns()) {
                throw new FeatureNotSupportedException(
                        format("%s: must have at least one persistent attribute", component));
            }
        });
    }

    /**
     * Forbid usage of {@link org.hibernate.annotations.Generated} and
     * {@link org.hibernate.annotations.CurrentTimestamp} annotations.
     */
    private static void forbidColumnFragmentAnnotations(PersistentClass persistentClass) {
        ClassElementChecker.check(
                persistentClass,
                false,
                ClassElementChecker.forbid(Generated.class),
                ClassElementChecker.forbid(GeneratedColumn.class),
                ClassElementChecker.forbid(GeneratedValue.class),
                ClassElementChecker.CURRENT_TIMESTAMP_WITH_DB_SOURCE);
    }

    /** @param declaringClass the class the {@code property} is declared by, {@code null} if there is no such class. */
    private static void checkPropertyType(
            PersistentClass persistentClass,
            @Nullable Class<?> declaringClass,
            Property property,
            StringJoiner propertyPath) {
        propertyPath.add(property.getName());
        var value = property.getValue();
        var type = value.getType();
        forbidUnsupportedTemporalPrecision(persistentClass, value, propertyPath);
        forbidTemporalVersion(persistentClass, property, value, propertyPath);
        forbidUnsupportedTimeZoneStorage(persistentClass, declaringClass, property, value, propertyPath);
        if (type instanceof BasicPluralType<?, ?> pluralType) {
            var columns = value.getColumns();
            assertTrue(columns.size() == 1);
            if (columns.get(0) instanceof AggregateColumn aggregateColumn) {
                checkComponentPropertyTypes(persistentClass, aggregateColumn.getComponent(), propertyPath);
            } else {
                forbidUnsupportedTypes(
                        persistentClass, pluralType.getElementType().getJavaType(), true, propertyPath);
            }
        } else if (type instanceof ComponentType) {
            checkComponentPropertyTypes(persistentClass, assertInstanceOf(value, Component.class), propertyPath);
        } else {
            forbidUnsupportedTypes(persistentClass, type.getReturnedClass(), false, propertyPath);
        }
    }

    /**
     * Only {@link TemporalType#TIMESTAMP}, which is the default, denotes an instant. {@code @Temporal} narrows a
     * {@link Date} to a wall-clock date or time instead.
     */
    @SuppressWarnings("deprecation")
    private static void forbidUnsupportedTemporalPrecision(
            PersistentClass persistentClass, Value value, StringJoiner propertyPath) {
        if (value instanceof BasicValue basicValue) {
            var temporalPrecision = basicValue.getTemporalPrecision();
            if (temporalPrecision != null && temporalPrecision != TemporalType.TIMESTAMP) {
                throw new FeatureNotSupportedException(format(
                        "%s: the persistent attribute [%s] has temporal precision [%s] that is not supported."
                                + " TODO-HIBERNATE-226 https://jira.mongodb.org/browse/HIBERNATE-226",
                        persistentClass, propertyPath, temporalPrecision));
            }
        }
    }

    /**
     * A BSON {@code Date} holds milliseconds, so two updates within one millisecond produce an equal version and the
     * optimistic-lock check on the second cannot fire.
     *
     * <p>This subsumes {@code COLUMN} storage on a version attribute, which is why
     * {@link #timeZoneStorageColumnRejectedUsage} does not consider that position.
     */
    private static void forbidTemporalVersion(
            PersistentClass persistentClass, Property property, Value value, StringJoiner propertyPath) {
        if (property.equals(persistentClass.getVersion())) {
            var storedType = storedType(value);
            if (INSTANT_DENOTING_TYPES.contains(storedType)) {
                throw new FeatureNotSupportedException(format(
                        "%s: the version attribute [%s] has type [%s] that is not supported, because a BSON Date holds"
                                + " milliseconds and two updates within one millisecond would produce an equal version",
                        persistentClass, propertyPath, storedType.getTypeName()));
            }
        }
    }

    /**
     * The type the mapping is about: for a plural attribute the element type, since the collection's own says nothing.
     */
    private static Class<?> storedType(Value value) {
        var type = value.getType();
        return type instanceof BasicPluralType<?, ?> pluralType
                ? pluralType.getElementType().getJavaType()
                : type.getReturnedClass();
    }

    /**
     * {@code NATIVE} asks for an offset the server does not store.
     *
     * <p>{@code NORMALIZE} is rejected because it cannot be honoured. It is defined to preserve the instant and return
     * it at the JVM default zone, which requires Hibernate ORM to convert through {@link Timestamp}; for values before
     * 1905 that conversion goes through calendar fields, and the legacy calendar those belong to is Julian before the
     * Gregorian cutover. A BSON {@code Date} would then hold something other than the instant - ten days out for a
     * year-1500 value - even though the round trip cancels out and hides it. Reading through {@link java.time.Instant}
     * instead keeps the stored value exact but returns UTC, which is {@code NORMALIZE_UTC}, so there is nothing left
     * for {@code NORMALIZE} to mean here.
     *
     * <p>{@code COLUMN} stores an offset in a second field, which works for an ordinary attribute but not where a
     * single field is required or where the strategy is not honoured. Hibernate ORM resolves it for singular attributes
     * only, and a plural one degrades differently depending on how the strategy was asked for: the annotation replaces
     * the attribute with one scalar value, dropping the collection, while a global setting keeps the collection and
     * maps its elements to the wall-clock type instead, which loses their offsets and makes the stored value depend on
     * the writing host's zone.
     *
     * <p>Every check applies only to the two types a strategy is resolved for, so that a global setting leaves every
     * other temporal attribute alone.
     */
    private static void forbidUnsupportedTimeZoneStorage(
            PersistentClass persistentClass,
            @Nullable Class<?> declaringClass,
            Property property,
            Value value,
            StringJoiner propertyPath) {
        if (value instanceof BasicValue basicValue) {
            var strategy = basicValue.getDefaultTimeZoneStorageStrategy();
            if (TIME_ZONE_STORAGE_TYPES.contains(storedType(basicValue))) {
                if (UNSUPPORTED_TIME_ZONE_STORAGE_STRATEGIES.contains(strategy)) {
                    throw new FeatureNotSupportedException(format(
                            "%s: the persistent attribute [%s] uses the time zone storage strategy [%s] that is not supported",
                            persistentClass, propertyPath, strategy));
                }
                if (strategy == TimeZoneStorageStrategy.COLUMN && basicValue.getType() instanceof BasicPluralType) {
                    throw new FeatureNotSupportedException(format(
                            "%s: the plural attribute [%s] uses the time zone storage strategy [COLUMN] that is not supported",
                            persistentClass, propertyPath));
                }
            }
        } else if (value.getType() instanceof UserComponentType
                && TIME_ZONE_STORAGE_TYPES.contains(value.getType().getReturnedClass())) {
            var rejectedUsage = timeZoneStorageColumnRejectedUsage(persistentClass, declaringClass, property, value);
            if (rejectedUsage != null) {
                throw new FeatureNotSupportedException(format(
                        "%s: the %s attribute [%s] uses the time zone storage strategy [COLUMN] that is not supported",
                        persistentClass, rejectedUsage, propertyPath));
            }
        }
    }

    private static @Nullable String timeZoneStorageColumnRejectedUsage(
            PersistentClass persistentClass, @Nullable Class<?> declaringClass, Property property, Value value) {
        if (property.equals(persistentClass.getIdentifierProperty())) {
            return "identifier";
        }
        // Hibernate ORM maps a plural attribute under `COLUMN` as a single scalar value, so the mapping model no longer
        // says the attribute is plural. The declared member is what still does.
        var declaredType = declaredAttributeType(declaringClass, property);
        return declaredType != null && !declaredType.equals(value.getType().getReturnedClass()) ? "plural" : null;
    }

    private static @Nullable Class<?> declaredAttributeType(@Nullable Class<?> declaringClass, Property property) {
        for (var c = declaringClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(property.getName()).getType();
            } catch (NoSuchFieldException e) {
                // keep walking up; a property may also be accessed through a getter, in which case there is no field
                // to compare and the check does not apply
            }
        }
        return null;
    }

    private static void checkComponentPropertyTypes(
            PersistentClass persistentClass, Component component, StringJoiner propertyPath) {
        var componentClass = component.getComponentClass();
        component
                .getProperties()
                .forEach(componentProperty ->
                        checkPropertyType(persistentClass, componentClass, componentProperty, propertyPath));
    }

    private static void forbidUnsupportedTypes(
            PersistentClass persistentClass, Class<?> typeToCheck, boolean plural, StringJoiner propertyPath) {
        if (UNSUPPORTED_TYPES.contains(typeToCheck)) {
            throw new FeatureNotSupportedException(format(
                    plural
                            ? "%s: the plural persistent attribute [%s] has element type [%s] that is not supported"
                            : "%s: the persistent attribute [%s] has type [%s] that is not supported",
                    persistentClass,
                    propertyPath,
                    typeToCheck.getTypeName()));
        }
    }

    private static void setIdentifierColumnName(PersistentClass persistentClass) {
        var identifier = persistentClass.getIdentifier();
        assertFalse(identifier.hasFormula());
        var idColumns = identifier.getColumns();
        if (idColumns.size() > 1) {
            // Non-scalar id components (nested embeddable or collection) were already rejected by
            // forbidNonScalarIdComponent, so each component of this composite id has exactly one column.
            var idComponent = assertInstanceOf(identifier, Component.class);
            for (var property : idComponent.getProperties()) {
                var componentColumns = property.getValue().getColumns();
                assertTrue(componentColumns.size() == 1);
                componentColumns.get(0).setName(EmbeddedIdColumnName.forComponent(property.getName()));
            }
            return;
        }
        assertTrue(idColumns.size() == 1);
        var idColumn = idColumns.get(0);
        if (!ID_FIELD_NAME.equals(idColumn.getName()) && identifier instanceof SimpleValue simpleValue) {
            var memberDetails = simpleValue.getMemberDetails();
            if (memberDetails != null) {
                var columnAnnotation = memberDetails.getDirectAnnotationUsage(Column.class);
                if (columnAnnotation != null && !columnAnnotation.name().isBlank()) {
                    throw new FeatureNotSupportedException(format(
                            "%s: the @Id column name cannot be overridden to [%s];"
                                    + " MongoDB requires the primary key field to be named [%s]"
                                    + " — remove @Column(name = \"%s\") or change it to @Column(name = \"%s\")",
                            persistentClass, idColumn.getName(), ID_FIELD_NAME, idColumn.getName(), ID_FIELD_NAME));
                }
            }
        }
        idColumn.setName(ID_FIELD_NAME);
    }
}
