/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core.template.impl;

import static st.orm.core.template.impl.RecordReflection.isPolymorphicData;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.Data;
import st.orm.DbIgnore;
import st.orm.Entity;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.PK;
import st.orm.ProjectionQuery;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.UK;
import st.orm.core.spi.Providers;
import st.orm.core.spi.TypeDiscovery;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbColumn;
import st.orm.core.template.impl.DatabaseSchema.DbForeignKey;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.DatabaseSchema.DbUniqueKey;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;
import st.orm.core.template.impl.TypeCompatibility.Compatibility;
import st.orm.mapping.RecordField;

/**
 * Validates entity and projection definitions against the actual database schema.
 *
 * <p>This validator compares Storm's {@link Model}/{@link Column} metadata (built from entity classes) against the
 * database's {@link java.sql.DatabaseMetaData}. It detects mismatches such as missing tables, missing columns,
 * type incompatibilities, nullability mismatches, primary key mismatches, and missing sequences.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * SchemaValidator validator = SchemaValidator.of(dataSource);
 * validator.validateOrThrow();  // throws SchemaValidationException on mismatch
 * }</pre>
 *
 * @since 1.9
 */
public final class SchemaValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.validation");

    private final DataSource dataSource;
    private final ModelBuilder modelBuilder;
    private final TypeCompatibility typeCompatibility;
    /** The dialect to validate with, or {@code null} to resolve it from the connection when validating. */
    private final @Nullable SqlDialect sqlDialect;

    private SchemaValidator(
            DataSource dataSource,
            ModelBuilder modelBuilder,
            TypeCompatibility typeCompatibility,
            @Nullable SqlDialect sqlDialect
    ) {
        this.dataSource = dataSource;
        this.modelBuilder = modelBuilder;
        this.typeCompatibility = typeCompatibility;
        this.sqlDialect = sqlDialect;
    }

    /**
     * Creates a new schema validator with the default model builder.
     *
     * @param dataSource the data source to validate against.
     * @return a new schema validator.
     */
    public static SchemaValidator of(DataSource dataSource) {
        return new SchemaValidator(dataSource, ModelBuilder.newInstance(), TypeCompatibility.defaultCompatibility(),
                null);
    }

    /**
     * Creates a new schema validator with the specified model builder.
     *
     * @param dataSource   the data source to validate against.
     * @param modelBuilder the model builder to use for constructing entity models.
     * @return a new schema validator.
     */
    public static SchemaValidator of(DataSource dataSource, ModelBuilder modelBuilder) {
        return new SchemaValidator(dataSource, modelBuilder, TypeCompatibility.defaultCompatibility(), null);
    }

    /**
     * Creates a new schema validator with the specified model builder and SQL dialect.
     *
     * @param dataSource   the data source to validate against.
     * @param modelBuilder the model builder to use for constructing entity models.
     * @param sqlDialect   the SQL dialect to use for database-specific behavior.
     * @return a new schema validator.
     */
    public static SchemaValidator of(DataSource dataSource, ModelBuilder modelBuilder,
                                     SqlDialect sqlDialect) {
        return new SchemaValidator(dataSource, modelBuilder, TypeCompatibility.defaultCompatibility(), sqlDialect);
    }

    /**
     * Validates all entity and projection types discovered on the classpath via {@link TypeDiscovery}.
     *
     * @return the list of validation errors (empty if all entities match the database schema).
     */
    public List<SchemaValidationError> validate() {
        return validate(TypeDiscovery.getDataTypes());
    }

    /**
     * Validates the specified entity and projection types against the database schema.
     *
     * @param types the types to validate.
     * @return the list of validation errors (empty if all types match the database schema).
     */
    public List<SchemaValidationError> validate(Iterable<Class<? extends Data>> types) {
        List<SchemaValidationError> errors = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            var reads = new SchemaReads(connection);
            for (Class<? extends Data> type : types) {
                validateType(type, reads, errors);
            }
            // Validation has read the schema, so the slow statement log can check statements against its indexes
            // without a read of its own.
            reads.publish();
        } catch (SQLException e) {
            throw new st.orm.PersistenceException("Failed to read database schema for validation.", e);
        }
        return errors;
    }

    /**
     * Loads the database schema for all entity and projection types discovered on the classpath via
     * {@link TypeDiscovery}, without validating them, and keeps it for the data source.
     *
     * @see #load(Iterable)
     * @since 1.16
     */
    public void load() {
        load(TypeDiscovery.getDataTypes());
    }

    /**
     * Loads the database schema the specified types map to, without validating them, and keeps it for the data
     * source. The connection's default schema is always loaded, and each schema a type names besides it.
     *
     * <p>Loading the schema is optional. Every template on the data source shares what it loads and uses it for
     * advice and optimization: the slow statement log, for example, checks a slow statement against the indexes the
     * schema holds, and names an index the statement lacks. {@link #validate(Iterable)} loads the schema as it
     * validates, so an application that validates needs no separate load. Each load reads the schema anew and
     * replaces what an earlier load or validation kept for the same schemas.</p>
     *
     * @param types the types whose schemas to load.
     * @throws st.orm.PersistenceException if the schema cannot be read.
     * @since 1.16
     */
    public void load(Iterable<Class<? extends Data>> types) {
        try (Connection connection = dataSource.getConnection()) {
            var reads = new SchemaReads(connection);
            reads.resolve("");
            for (Class<? extends Data> type : types) {
                var model = modelOf(type);
                if (model != null) {
                    reads.resolve(model.schema());
                }
            }
            reads.publish();
            LOGGER.debug("Loaded the database schema.");
        } catch (SQLException e) {
            throw new st.orm.PersistenceException("Failed to load the database schema.", e);
        }
    }

    /**
     * The schemas one validation or load reads, on one connection, each read once.
     */
    private final class SchemaReads {
        private final Connection connection;
        private final SqlDialect dialect;
        private final @Nullable String defaultCatalog;
        private final @Nullable String defaultSchema;
        /** The schemas read, per schema name (case-insensitive), to avoid redundant metadata reads. */
        private final SortedMap<String, DatabaseSchema> schemaCache = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        /** The schemas read, per schema as the models name it ({@code ""} for the default schema). */
        private final SortedMap<String, DatabaseSchema> byModelSchema = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        SchemaReads(Connection connection) throws SQLException {
            this.connection = connection;
            this.defaultCatalog = connection.getCatalog();
            this.defaultSchema = connection.getSchema();
            // The strategies a dialect reads constraints and sequences with are vendor-specific, so a dialect that
            // does not match the database reads the schema with queries the database does not understand. Unless a
            // dialect was supplied, it comes from the database on the other end of this connection.
            this.dialect = sqlDialect != null
                    ? sqlDialect
                    : Providers.getSqlDialect(connection, StormConfig.defaults());
        }

        /**
         * Returns the schema a model names, reading it on first use.
         */
        DatabaseSchema resolve(String entitySchema) throws SQLException {
            var schema = resolveSchema(connection, dialect, defaultCatalog, defaultSchema, entitySchema, schemaCache);
            byModelSchema.put(entitySchema, schema);
            return schema;
        }

        /**
         * Publishes the indexes of the schemas read for the data source.
         */
        void publish() {
            if (!byModelSchema.isEmpty()) {
                IndexCatalog.publish(dataSource, IndexCatalog.of(byModelSchema));
            }
        }
    }

    /**
     * Returns the model of a type whose table the schema holds, or {@code null} for a type that has none to check: an
     * interface, a sealed type whose subtypes are checked instead, a type that cannot be modeled, or one annotated
     * with {@link DbIgnore} or {@link ProjectionQuery}.
     */
    private @Nullable Model<?, ?> modelOf(Class<? extends Data> type) {
        // A single-table hierarchy is one table: the sealed type's model holds the union of the subtypes' columns
        // and the discriminator, and a subtype checks that table through it.
        var singleTableParent = RecordReflection.findSingleTableSealedParent(type);
        if (singleTableParent.isPresent()) {
            type = singleTableParent.get();
        }
        // A joined hierarchy's sealed type checks the base table; each subtype checks its extension table.
        boolean sealedEntity = type.isSealed() && RecordReflection.isSealedEntity(type);
        if (!sealedEntity && (type.isInterface() || (type.isSealed() && !type.isRecord()))) {
            return null;
        }
        boolean requirePrimaryKey = Entity.class.isAssignableFrom(type);
        Model<?, ?> model;
        try {
            model = modelBuilder.build(type, requirePrimaryKey);
        } catch (SqlTemplateException e) {
            // Type cannot be modeled (e.g., invalid record structure). Skip; RecordValidation handles this.
            return null;
        }
        // Skip types annotated with @DbIgnore or @ProjectionQuery.
        if (model.recordType().isAnnotationPresent(DbIgnore.class)
                || model.recordType().isAnnotationPresent(ProjectionQuery.class)) {
            return null;
        }
        return model;
    }

    /**
     * Validates all discovered types and throws if any errors are found.
     *
     * @throws SchemaValidationException if one or more validation errors are detected.
     */
    public void validateOrThrow() throws SchemaValidationException {
        List<Class<? extends Data>> types = TypeDiscovery.getDataTypes();
        List<SchemaValidationError> errors = validate(types);
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
        logSuccess("Successfully validated {} Data types against the database schema.", types.size());
    }

    /**
     * Validates the specified types and throws if any errors are found.
     *
     * @param types the types to validate.
     * @throws SchemaValidationException if one or more validation errors are detected.
     */
    public void validateOrThrow(Iterable<Class<? extends Data>> types) throws SchemaValidationException {
        List<SchemaValidationError> errors = validate(types);
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
    }

    /**
     * Validates all discovered types, logs each finding, and returns the error messages.
     *
     * <p>In strict mode, all findings (including warnings like type narrowing and nullability mismatches) are treated
     * as errors. In non-strict mode, warnings are logged at WARN level but excluded from the returned error list.</p>
     *
     * @param strict whether to treat warnings as errors.
     * @return the list of error messages (empty on success).
     */
    public List<String> validateAndReport(boolean strict) {
        LOGGER.debug("Validating Data types for schema compatibility.");
        List<Class<? extends Data>> types = TypeDiscovery.getDataTypes();
        return reportErrors(validate(types), strict, types.size());
    }

    /**
     * Validates discovered types matching the filter, logs each finding, and returns the error messages.
     *
     * <p>Discovers all entity and projection types via the classpath index, applies the given filter, and validates
     * the matching types.</p>
     *
     * <p>In strict mode, all findings (including warnings like type narrowing and nullability mismatches) are treated
     * as errors. In non-strict mode, warnings are logged at WARN level but excluded from the returned error list.</p>
     *
     * @param filter predicate to select which discovered types to validate.
     * @param strict whether to treat warnings as errors.
     * @return the list of error messages (empty on success).
     * @since 1.11
     */
    public List<String> validateAndReport(Predicate<Class<? extends Data>> filter, boolean strict) {
        LOGGER.debug("Validating Data types for schema compatibility.");
        List<Class<? extends Data>> types = TypeDiscovery.getDataTypes().stream()
                .filter(filter)
                .toList();
        return reportErrors(validate(types), strict, types.size());
    }

    /**
     * Validates the specified types, logs each finding, and returns the error messages.
     *
     * <p>In strict mode, all findings (including warnings like type narrowing and nullability mismatches) are treated
     * as errors. In non-strict mode, warnings are logged at WARN level but excluded from the returned error list.</p>
     *
     * @param types  the types to validate.
     * @param strict whether to treat warnings as errors.
     * @return the list of error messages (empty on success).
     */
    public List<String> validateAndReport(Iterable<Class<? extends Data>> types, boolean strict) {
        LOGGER.debug("Validating Data types for schema compatibility.");
        return reportErrors(validate(types), strict, countTypes(types));
    }

    /**
     * Validates all discovered types and logs mismatches at WARN level.
     *
     * <p>This is a convenience method for "warn" mode: validation issues are logged but never cause an exception.</p>
     */
    public void validateOrWarn() {
        validateAndReport(false);
    }

    /**
     * Validates all discovered types, reports errors with logging, and throws if any errors remain.
     *
     * @param strict whether to treat warnings as errors.
     * @throws st.orm.PersistenceException if one or more validation errors are detected after reporting.
     */
    public void validateReportAndThrow(boolean strict) {
        List<String> errors = validateAndReport(strict);
        if (!errors.isEmpty()) {
            throw new st.orm.PersistenceException(formatErrors(errors));
        }
    }

    /**
     * Validates discovered types matching the filter, reports errors with logging, and throws if any errors remain.
     *
     * @param filter predicate to select which discovered types to validate.
     * @param strict whether to treat warnings as errors.
     * @throws st.orm.PersistenceException if one or more validation errors are detected after reporting.
     * @since 1.11
     */
    public void validateReportAndThrow(Predicate<Class<? extends Data>> filter, boolean strict) {
        List<String> errors = validateAndReport(filter, strict);
        if (!errors.isEmpty()) {
            throw new st.orm.PersistenceException(formatErrors(errors));
        }
    }

    private static List<String> reportErrors(
            List<SchemaValidationError> validationErrors,
            boolean strict,
            int typeCount
    ) {
        if (validationErrors.isEmpty()) {
            logSuccess("Successfully validated {} Data types for schema compatibility.", typeCount);
            return List.of();
        }
        // In strict mode, all findings are treated as errors.
        // In non-strict mode, warnings are logged separately and excluded from the returned list.
        List<String> errors = new ArrayList<>();
        for (SchemaValidationError validationError : validationErrors) {
            String message = validationError.toString();
            if (!strict && validationError.kind().warning()) {
                LOGGER.warn(message);
            } else {
                LOGGER.error(message);
                errors.add(message);
            }
        }
        if (errors.isEmpty()) {
            LOGGER.info("Successfully validated {} Data types for schema compatibility (with warnings).", typeCount);
        } else {
            LOGGER.warn("Schema validation found {} issue(s).", errors.size());
        }
        return errors;
    }

    /**
     * Logs a successful validation: at info when it covered types, at debug when there were none to validate.
     */
    private static void logSuccess(String message, int typeCount) {
        if (typeCount == 0) {
            LOGGER.debug(message, typeCount);
        } else {
            LOGGER.info(message, typeCount);
        }
    }

    private static int countTypes(Iterable<?> types) {
        if (types instanceof Collection<?> collection) {
            return collection.size();
        }
        int count = 0;
        for (var ignored : types) {
            count++;
        }
        return count;
    }

    static String formatErrors(List<String> errors) {
        return "Schema validation failed with %d error(s):\n%s\nIf intentional, use @DbIgnore to exclude specific types or fields from validation.".formatted(
                errors.size(),
                String.join("\n", errors.stream().map(e -> "  - " + e).toList()));
    }

    /**
     * Resolves the {@link DatabaseSchema} for the given entity schema, reading from the database if not already cached.
     *
     * <p>On databases that use catalogs as schemas (as indicated by {@link SqlDialect#useCatalogAsSchema()}), the
     * {@code @DbTable(schema = ...)} value represents a database name, which maps to the JDBC catalog. In that case,
     * the entity schema is passed as the catalog parameter instead of the schema pattern.</p>
     */
    private DatabaseSchema resolveSchema(
            Connection connection,
            SqlDialect dialect,
            @Nullable String defaultCatalog,
            @Nullable String defaultSchema,
            String entitySchema,
            SortedMap<String, DatabaseSchema> schemaCache
    ) throws SQLException {
        // Use the entity's schema if specified, otherwise fall back to the connection's default schema.
        String schemaKey = entitySchema.isEmpty()
                ? (defaultSchema != null ? defaultSchema : "")
                : entitySchema;
        DatabaseSchema cached = schemaCache.get(schemaKey);
        if (cached != null) {
            return cached;
        }
        String catalog;
        String schemaPattern;
        if (!entitySchema.isEmpty() && dialect.useCatalogAsSchema()) {
            // Database uses catalogs as schemas. The entity's schema represents a
            // database name, which maps to the JDBC catalog.
            catalog = entitySchema;
            schemaPattern = null;
        } else {
            catalog = defaultCatalog;
            schemaPattern = entitySchema.isEmpty() ? defaultSchema : entitySchema;
        }
        DatabaseSchema databaseSchema = DatabaseSchema.read(connection, catalog, schemaPattern,
                    DatabaseSchema.Discovery.of(dialect));
        schemaCache.put(schemaKey, databaseSchema);
        return databaseSchema;
    }

    /**
     * Validates a single type against the database schema.
     */
    private void validateType(
            Class<? extends Data> type,
            SchemaReads reads,
            List<SchemaValidationError> errors
    ) {
        Model<?, ?> model = modelOf(type);
        if (model == null) {
            return;
        }
        boolean requirePrimaryKey = Entity.class.isAssignableFrom(type);
        String tableName = model.name();
        String entitySchema = model.schema();
        DatabaseSchema schema;
        try {
            schema = reads.resolve(entitySchema);
        } catch (SQLException e) {
            throw new st.orm.PersistenceException(
                    "Failed to read database schema '%s' for validation.".formatted(entitySchema), e);
        }
        // Use schema-qualified table name in error messages when a custom schema is specified.
        String qualifiedTableName = entitySchema.isEmpty()
                ? tableName
                : entitySchema + "." + tableName;
        // Collect field names annotated with @DbIgnore.
        Set<String> ignoredComponents = getIgnoredFields(model);
        // 1. Table existence.
        if (!schema.tableExists(tableName)) {
            errors.add(new SchemaValidationError(type, ErrorKind.TABLE_NOT_FOUND,
                    "Table '%s' not found in database.".formatted(qualifiedTableName)));
            return;  // No point checking columns if the table doesn't exist.
        }
        // 2-4. Column existence, type compatibility, nullability.
        // Track all column names (including @DbIgnore) for the unmapped column check (step 9).
        List<Column> columns = tableColumns(type, model);
        Set<String> mappedColumns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Column column : columns) {
            mappedColumns.add(column.name());
            if (isIgnored(column, ignoredComponents)) {
                continue;
            }
            validateColumn(type, column, schema, tableName, qualifiedTableName, errors);
        }
        // A query column is checked as a declared column is. It stays out of the mapped set: every insert leaves it
        // out, so the unmapped column check (step 9) covers it as it covers a column no field maps.
        for (Column column : model.queryColumns()) {
            validateColumn(type, column, schema, tableName, qualifiedTableName, errors);
        }
        // 5. Primary key match. Skipped when this table's primary key could not be read: an empty result would
        // otherwise read as "the table has no primary key".
        if (requirePrimaryKey && schema.isDiscovered(tableName, ConstraintKind.PRIMARY_KEY)) {
            Set<String> entityPkColumns = columns.stream()
                    .filter(Column::primaryKey)
                    .map(column -> column.name().toUpperCase())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
            Set<String> dbPkColumns = schema.getPrimaryKeys(tableName).stream()
                    .map(pk -> pk.columnName().toUpperCase())
                    .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
            if (dbPkColumns.isEmpty() && !entityPkColumns.isEmpty()) {
                // No PK constraint in the database. Only warn if @PK(constraint = true).
                boolean validatePkConstraint = model.recordType().fields().stream()
                        .filter(field -> field.isAnnotationPresent(PK.class))
                        .findFirst()
                        .map(field -> field.getAnnotation(PK.class))
                        .map(PK::constraint)
                        .orElse(true);
                if (validatePkConstraint) {
                    errors.add(new SchemaValidationError(type, ErrorKind.PRIMARY_KEY_MISSING,
                            "No primary key constraint found in table '%s', but entity defines primary key columns %s. If intentional, use @PK(constraint = false) to suppress this check."
                                    .formatted(qualifiedTableName, entityPkColumns)));
                }
            } else if (!dbPkColumns.isEmpty() && !entityPkColumns.equals(dbPkColumns)) {
                // PK constraint exists but differs: always a hard error regardless of constraint flag.
                errors.add(new SchemaValidationError(type, ErrorKind.PRIMARY_KEY_MISMATCH,
                        "Primary key mismatch for table '%s': entity defines %s, database has %s."
                                .formatted(qualifiedTableName, entityPkColumns, dbPkColumns)));
            }
        }
        // 6. Sequence existence. Only validated when sequences were actually discovered: a dialect that cannot
        // enumerate sequences leaves them unknown, and unknown must not be reported as missing.
        if (schema.sequencesDiscovered()) {
            for (Column column : columns) {
                if (isIgnored(column, ignoredComponents)) {
                    continue;
                }
                if (column.generation() == GenerationStrategy.SEQUENCE) {
                    String sequenceName = column.sequence();
                    if (!sequenceName.isEmpty() && !schema.sequenceExists(sequenceName)) {
                        errors.add(new SchemaValidationError(type, ErrorKind.SEQUENCE_NOT_FOUND,
                                "Sequence '%s' not found in database (referenced by column '%s' in table '%s')."
                                        .formatted(sequenceName, column.name(), qualifiedTableName)));
                    }
                }
            }
        }
        // 7. Unique key validation.
        validateUniqueKeys(type, model, columns, schema, tableName, qualifiedTableName, ignoredComponents, errors);
        // 8. Foreign key validation.
        if (requirePrimaryKey) {
            validateForeignKeys(type, model, columns, schema, tableName, qualifiedTableName, ignoredComponents, errors);
        }
        // 9. Unmapped column check: detect NOT NULL columns without a default that are not mapped in the entity.
        // Only applies to Entity types (projections intentionally select a subset of columns).
        if (requirePrimaryKey) {
            validateUnmappedColumns(type, schema, tableName, qualifiedTableName, mappedColumns, errors);
        }
    }

    /**
     * Validates unique key alignment between {@code @UK}-annotated fields and database unique constraints.
     *
     * <p>Forward check: for each {@code @UK} field, verifies a matching constraint exists in the database
     * (unless suppressed by {@code @UK(constraint = false)}).</p>
     *
     * <p>Reverse check: for each single-column unique constraint in the database, warns if the corresponding
     * entity field is not annotated with {@code @UK}. Composite (multi-column) constraints are not flagged,
     * since modeling them requires structural changes (inline records) that should be a deliberate choice.</p>
     */
    /**
     * Returns the columns of the table a type checks. A joined hierarchy splits its columns over tables: the sealed
     * type checks the base table, which holds the discriminator and the fields every subtype declares, and a subtype
     * checks its extension table, which holds the primary key and the fields only some subtypes declare. Any other
     * type checks all of its declared columns.
     */
    private static List<Column> tableColumns(Class<? extends Data> type, Model<?, ?> model) {
        if (type.isSealed() && RecordReflection.isJoinedEntity(type)) {
            boolean discriminator = RecordReflection.hasDiscriminator(type);
            return model.declaredColumns().stream()
                    .filter(column -> column.metamodel().fieldPath().isEmpty()
                            ? discriminator
                            : RecordReflection.isCommonField(type, column.metamodel().fieldPath()))
                    .toList();
        }
        var joinedParent = RecordReflection.findJoinedSealedParent(type);
        if (joinedParent.isPresent()) {
            return model.declaredColumns().stream()
                    .filter(column -> column.primaryKey()
                            || !RecordReflection.isCommonField(joinedParent.get(), column.metamodel().fieldPath()))
                    .toList();
        }
        return model.declaredColumns();
    }

    /**
     * Returns the fields whose keys a type checks: the union of the subtypes' fields for a sealed type, since a key
     * a later subtype declares is a key of the shared table too, and the record's fields otherwise.
     */
    private static List<RecordField> tableFields(Class<? extends Data> type, Model<?, ?> model) {
        return type.isSealed() && RecordReflection.isSealedEntity(type)
                ? RecordReflection.getTableFields(type)
                : model.recordType().fields();
    }

    private void validateUniqueKeys(
            Class<? extends Data> type,
            Model<?, ?> model,
            List<Column> columns,
            DatabaseSchema schema,
            String tableName,
            String qualifiedTableName,
            Set<String> ignoredComponents,
            List<SchemaValidationError> errors
    ) {
        if (!schema.isDiscovered(tableName, ConstraintKind.UNIQUE_KEY)) {
            // This table's unique keys could not be read, so nothing can be said about them.
            return;
        }
        // Build a map of unique index name -> set of column names from the database.
        List<DbUniqueKey> dbUniqueKeys = schema.getUniqueKeys(tableName);
        SortedMap<String, SortedSet<String>> indexColumnsByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (DbUniqueKey uk : dbUniqueKeys) {
            indexColumnsByName.computeIfAbsent(uk.indexName(), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .add(uk.columnName());
        }
        // A unique index enforces uniqueness as a unique constraint does, and a database can have one without the
        // other. An index on an expression, or on part of the rows, does not make the columns unique.
        SortedSet<String> partialIndexes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        SortedMap<String, SortedSet<String>> uniqueIndexes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (DbIndex index : schema.getIndexes(tableName)) {
            if (!index.unique()) {
                continue;
            }
            if (index.partial() || index.columnName() == null) {
                partialIndexes.add(index.indexName());
                continue;
            }
            uniqueIndexes.computeIfAbsent(index.indexName(), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .add(index.columnName());
        }
        uniqueIndexes.forEach((indexName, indexColumns) -> {
            if (!partialIndexes.contains(indexName)) {
                indexColumnsByName.putIfAbsent(indexName, indexColumns);
            }
        });
        // Forward check: @UK fields must have a matching database constraint.
        for (RecordField field : tableFields(type, model)) {
            if (field.isAnnotationPresent(DbIgnore.class)) continue;
            if (!field.isAnnotationPresent(UK.class)) continue;
            if (field.isAnnotationPresent(PK.class)) continue;
            if (ignoredComponents.contains(field.name())) continue;
            UK ukAnnotation = field.getAnnotation(UK.class);
            if (ukAnnotation == null) continue;
            if (!ukAnnotation.constraint()) continue;
            SortedSet<String> expectedColumns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Column column : columns) {
                String fieldPath = column.metamodel().fieldPath();
                if (fieldPath.equals(field.name()) || fieldPath.startsWith(field.name() + ".")) {
                    expectedColumns.add(column.name());
                }
            }
            if (expectedColumns.isEmpty()) continue;
            boolean found = indexColumnsByName.values().stream()
                    .anyMatch(indexColumns -> indexColumns.equals(expectedColumns));
            if (!found) {
                String columnDescription = expectedColumns.size() == 1
                        ? "column '%s'".formatted(expectedColumns.first())
                        : "columns %s".formatted(expectedColumns);
                errors.add(new SchemaValidationError(type, ErrorKind.UNIQUE_KEY_MISSING,
                        "No unique constraint found on %s in table '%s' for @UK field '%s'. If intentional, use @UK(constraint = false) to suppress this check."
                                .formatted(columnDescription, qualifiedTableName, field.name())));
            }
        }
    }

    /**
     * Validates that {@code @FK}-annotated fields have a matching foreign key constraint in the database.
     */
    private void validateForeignKeys(
            Class<? extends Data> type,
            Model<?, ?> model,
            List<Column> columns,
            DatabaseSchema schema,
            String tableName,
            String qualifiedTableName,
            Set<String> ignoredComponents,
            List<SchemaValidationError> errors
    ) {
        if (!schema.isDiscovered(tableName, ConstraintKind.FOREIGN_KEY)) {
            // This table's foreign keys could not be read, so nothing can be said about them.
            return;
        }
        List<DbForeignKey> dbForeignKeys = schema.getForeignKeys(tableName);
        for (RecordField field : tableFields(type, model)) {
            if (field.isAnnotationPresent(DbIgnore.class)) {
                continue;
            }
            if (!field.isAnnotationPresent(FK.class)) {
                continue;
            }
            if (ignoredComponents.contains(field.name())) {
                continue;
            }
            // Determine the target entity type.
            Class<?> targetType = field.type();
            if (Ref.class.isAssignableFrom(targetType)) {
                Type genericType = field.genericType();
                if (genericType instanceof ParameterizedType parameterizedType) {
                    Type[] typeArgs = parameterizedType.getActualTypeArguments();
                    if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> refTarget) {
                        targetType = refTarget;
                    }
                }
            }
            if (!Data.class.isAssignableFrom(targetType)) {
                continue;
            }
            // Polymorphic FK: the target is a sealed Data interface whose subtypes are independent
            // entities in separate tables. Standard DB foreign key constraints cannot express this
            // (the FK id column can reference any of the target tables, determined by the
            // discriminator column at runtime), so skip FK constraint validation for these fields.
            if (isPolymorphicData(targetType)) {
                continue;
            }
            // Build a model for the target entity to get its table name.
            @SuppressWarnings("unchecked")
            Class<? extends Data> targetDataType = (Class<? extends Data>) targetType;
            Model<?, ?> targetModel;
            try {
                targetModel = modelBuilder.build(targetDataType, Entity.class.isAssignableFrom(targetDataType));
            } catch (SqlTemplateException e) {
                continue;
            }
            String targetTableName = targetModel.name();
            // Find the FK column(s) for this field in the current model.
            List<String> fkColumnNames = new ArrayList<>();
            for (Column column : columns) {
                if (!column.foreignKey()) {
                    continue;
                }
                String fieldPath = column.metamodel().fieldPath();
                if (fieldPath.equals(field.name()) || fieldPath.startsWith(field.name() + ".")) {
                    fkColumnNames.add(column.name());
                }
            }
            if (fkColumnNames.isEmpty()) {
                continue;
            }
            // Check if the @FK annotation requests constraint validation.
            FK fkAnnotation = field.getAnnotation(FK.class);
            boolean validateFkConstraint = fkAnnotation == null || fkAnnotation.constraint();
            // Check if the database has matching FK constraints for each FK column.
            for (String fkColumnName : fkColumnNames) {
                // Check for an exact match (correct column and correct target table).
                boolean found = dbForeignKeys.stream()
                        .anyMatch(fk -> fk.fkColumnName().equalsIgnoreCase(fkColumnName)
                                && fk.pkTableName().equalsIgnoreCase(targetTableName));
                if (!found) {
                    // Check if there is a FK constraint on this column that references a different table.
                    Optional<DbForeignKey> mismatch = dbForeignKeys.stream()
                            .filter(fk -> fk.fkColumnName().equalsIgnoreCase(fkColumnName))
                            .findFirst();
                    if (mismatch.isPresent()) {
                        // FK constraint exists but points to the wrong table: always a hard error.
                        errors.add(new SchemaValidationError(type, ErrorKind.FOREIGN_KEY_MISMATCH,
                                "Foreign key mismatch on column '%s' in table '%s': entity expects reference to table '%s', but database references table '%s'."
                                        .formatted(fkColumnName, qualifiedTableName, targetTableName,
                                                mismatch.get().pkTableName())));
                    } else if (validateFkConstraint) {
                        // No FK constraint at all: only warn if constraint validation is enabled.
                        errors.add(new SchemaValidationError(type, ErrorKind.FOREIGN_KEY_MISSING,
                                "No foreign key constraint found on column '%s' in table '%s' referencing table '%s'. If intentional, use @FK(constraint = false) to suppress this check."
                                        .formatted(fkColumnName, qualifiedTableName, targetTableName)));
                    }
                }
            }
        }
    }

    /**
     * Validates that the database table does not contain NOT NULL columns without a default value that are not mapped
     * in the entity.
     *
     * <p>Such columns would cause INSERT failures at runtime because the database requires a value that Storm cannot
     * provide. Columns that are nullable, have a default value, or are auto-incremented are safe to omit.</p>
     *
     * @param mappedColumns the set of entity-mapped column names (case-insensitive), collected during column validation.
     */
    private void validateUnmappedColumns(
            Class<? extends Data> type,
            DatabaseSchema schema,
            String tableName,
            String qualifiedTableName,
            Set<String> mappedColumns,
            List<SchemaValidationError> errors
    ) {
        for (DbColumn dbColumn : schema.getColumns(tableName)) {
            if (mappedColumns.contains(dbColumn.columnName())) {
                continue;  // Column is mapped in the entity.
            }
            // Skip columns that are safe to omit: nullable, has a default, auto-incremented, or generated.
            if (dbColumn.nullable() || dbColumn.hasDefault() || dbColumn.autoIncrement() || dbColumn.generated()) {
                continue;
            }
            errors.add(new SchemaValidationError(type, ErrorKind.UNMAPPED_COLUMN,
                    "Column '%s' in table '%s' is NOT NULL without a default value, but is not mapped in entity '%s'. Inserts will fail. If intentional, use @DbIgnore to suppress this check."
                            .formatted(dbColumn.columnName(), qualifiedTableName, type.getSimpleName())));
        }
    }

    /**
     * Checks that a column exists in the table with a compatible type and nullability, and returns the database
     * column, or {@code null} when the table has none by that name.
     */
    private @Nullable DbColumn validateColumn(
            Class<? extends Data> type,
            Column column,
            DatabaseSchema schema,
            String tableName,
            String qualifiedTableName,
            List<SchemaValidationError> errors
    ) {
        String columnName = column.name();
        Optional<DbColumn> dbColumn = schema.getColumn(tableName, columnName);
        if (dbColumn.isEmpty()) {
            errors.add(new SchemaValidationError(type, ErrorKind.COLUMN_NOT_FOUND,
                    "Column '%s' not found in table '%s'.".formatted(columnName, qualifiedTableName)));
            return null;
        }
        DbColumn dbCol = dbColumn.get();
        // Type compatibility check against the persisted type, so foreign key columns are validated
        // against the referenced key's terminal type rather than being skipped as unknown entity types.
        Compatibility compatibility = typeCompatibility.check(column.persistedType(), dbCol.dataType(), dbCol.typeName());
        if (compatibility == Compatibility.NARROWING) {
            errors.add(new SchemaValidationError(type, ErrorKind.TYPE_NARROWING,
                    "Column '%s' in table '%s': Java type '%s' mapped to SQL type '%s' (%d) may involve precision or range loss."
                            .formatted(columnName, qualifiedTableName, column.persistedType().getSimpleName(),
                                    dbCol.typeName(), dbCol.dataType())));
        } else if (compatibility == Compatibility.INCOMPATIBLE) {
            errors.add(new SchemaValidationError(type, ErrorKind.TYPE_INCOMPATIBLE,
                    "Column '%s' in table '%s': Java type '%s' is not compatible with SQL type '%s' (%d)."
                            .formatted(columnName, qualifiedTableName, column.persistedType().getSimpleName(),
                                    dbCol.typeName(), dbCol.dataType())));
        }
        // Nullability check: entity field is non-nullable but database column allows NULL.
        // We only flag the case where the entity says non-null but the DB says nullable,
        // since the reverse (entity nullable, DB not null) is safe. A view's columns read as nullable
        // whatever its query returns, so they carry no answer to check against.
        if (!column.nullable() && dbCol.nullable() && !schema.isView(tableName)) {
            errors.add(new SchemaValidationError(type, ErrorKind.NULLABILITY_MISMATCH,
                    "Column '%s' in table '%s': entity field is non-nullable but database column allows NULL."
                            .formatted(columnName, qualifiedTableName)));
        }
        return dbCol;
    }

    /**
     * Returns the names of fields annotated with {@link DbIgnore}.
     */
    private static Set<String> getIgnoredFields(Model<?, ?> model) {
        Set<String> ignored = new HashSet<>();
        for (RecordField field : model.recordType().fields()) {
            if (field.isAnnotationPresent(DbIgnore.class)) {
                ignored.add(field.name());
            }
        }
        return ignored;
    }

    /**
     * Returns whether the given column should be skipped because its source record component is annotated with
     * {@link DbIgnore}.
     *
     * <p>For direct columns, the metamodel's field path matches the component name. For inline records, the field
     * path starts with the component name (e.g., "address.street" for the "address" component).</p>
     */
    private static boolean isIgnored(Column column, Set<String> ignoredComponents) {
        if (ignoredComponents.isEmpty()) {
            return false;
        }
        String fieldPath = column.metamodel().fieldPath();
        for (String component : ignoredComponents) {
            if (fieldPath.equals(component) || fieldPath.startsWith(component + ".")) {
                return true;
            }
        }
        return false;
    }
}
