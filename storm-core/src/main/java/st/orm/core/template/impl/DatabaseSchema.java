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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;

/**
 * Reads JDBC {@link DatabaseMetaData} into an in-memory representation for schema validation.
 *
 * <p>All name lookups are case-insensitive, since database systems vary in how they handle identifier casing
 * (some fold unquoted identifiers to lower case, others to upper case).</p>
 *
 * @since 1.9
 */
public final class DatabaseSchema {

    /**
     * Returns whether the result set has a column with the given label.
     */
    private static boolean hasColumn(ResultSetMetaData resultSetMetaData, String label) throws SQLException {
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            if (label.equalsIgnoreCase(resultSetMetaData.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.validation");

    /**
     * Represents a column discovered from the database metadata.
     *
     * @param tableName     the table containing this column.
     * @param columnName    the column name.
     * @param dataType      the SQL type from {@link java.sql.Types}.
     * @param typeName      the database-specific type name.
     * @param columnSize    the column size (precision for numeric types, length for character types).
     * @param nullable      whether the column allows NULL values.
     * @param autoIncrement whether the column is auto-incremented.
     * @param hasDefault    whether the column has a default value defined.
     * @param generated     whether the database computes the column from an expression, as the driver reports it;
     *                      a driver that does not report it reads as {@code false}.
     */
    public record DbColumn(
            String tableName,
            String columnName,
            int dataType,
            String typeName,
            int columnSize,
            boolean nullable,
            boolean autoIncrement,
            boolean hasDefault,
            boolean generated
    ) {}

    /**
     * Represents a primary key column discovered from the database metadata.
     *
     * @param tableName  the table containing this primary key column.
     * @param columnName the column name that is part of the primary key.
     * @param keySeq     the sequence number within the primary key (1-based).
     */
    public record DbPrimaryKey(
            String tableName,
            String columnName,
            int keySeq
    ) {}

    /**
     * Represents a column that is part of a unique index discovered from the database metadata.
     *
     * @param tableName       the table containing this unique index.
     * @param indexName        the name of the unique index.
     * @param columnName      the column name that is part of the unique index.
     * @param ordinalPosition the ordinal position of the column within the index (1-based).
     */
    public record DbUniqueKey(
            String tableName,
            String indexName,
            String columnName,
            int ordinalPosition
    ) {}

    /**
     * Represents a column of a foreign key discovered from the database metadata. The columns of a compound foreign
     * key share its constraint name.
     *
     * @param fkTableName    the table containing the foreign key column.
     * @param constraintName the name of the foreign key constraint, or {@code null} when the database does not
     *                       report it.
     * @param fkColumnName   the foreign key column name.
     * @param pkTableName    the referenced (primary key) table name.
     * @param pkColumnName   the referenced (primary key) column name.
     */
    public record DbForeignKey(
            String fkTableName,
            @Nullable String constraintName,
            String fkColumnName,
            String pkTableName,
            String pkColumnName
    ) {}

    /**
     * Represents a column of an index discovered from the database metadata, unique or not.
     *
     * @param tableName       the table the index is on.
     * @param indexName       the name of the index.
     * @param columnName      the column at this position of the index, or {@code null} when the position holds an
     *                        expression.
     * @param ordinalPosition the position of the column within the index (1-based).
     * @param unique          whether the index is unique.
     * @param partial         whether the index covers only the rows a condition selects.
     * @since 1.16
     */
    public record DbIndex(
            String tableName,
            String indexName,
            @Nullable String columnName,
            int ordinalPosition,
            boolean unique,
            boolean partial
    ) {}

    /**
     * A kind of constraint a schema read discovers.
     *
     * <p>The kinds are separate because a strategy can read one and fail on another: the JDBC metadata strategy
     * asks for each with its own call. See {@link #isDiscovered(String, ConstraintKind)} for why the outcome is
     * recorded.</p>
     */
    public enum ConstraintKind {
        /** Primary keys. */
        PRIMARY_KEY,
        /** Unique keys. */
        UNIQUE_KEY,
        /** Foreign keys. */
        FOREIGN_KEY,
        /** Indexes, unique and non-unique. */
        INDEX
    }

    /**
     * How a schema read discovers what the columns do not show: the standard strategies, and the queries a dialect
     * supplies for a database that lists its keys, sequences or indexes in a catalog of its own. A query takes the
     * schema name as its only parameter; without a schema name to bind, the strategy is used instead.
     *
     * @param sequences the strategy for discovering sequences.
     * @param constraints the strategy for discovering primary keys, unique keys, and foreign keys.
     * @param keyQuery the query that lists primary and unique keys, as {@link SqlDialect#keyDiscoveryQuery()}
     *                 describes it, or {@code null} to use the strategy.
     * @param foreignKeyQuery the query that lists foreign keys, as {@link SqlDialect#foreignKeyDiscoveryQuery()}
     *                        describes it, or {@code null} to use the strategy.
     * @param sequenceQuery the query that lists sequences, as {@link SqlDialect#sequenceDiscoveryQuery()} describes
     *                      it, or {@code null} to use the strategy.
     * @param indexQuery the query that lists indexes, as {@link SqlDialect#indexDiscoveryQuery()} describes it, or
     *                   {@code null} to read them per table through JDBC metadata.
     * @since 1.16
     */
    public record Discovery(
            SequenceDiscoveryStrategy sequences,
            ConstraintDiscoveryStrategy constraints,
            @Nullable String keyQuery,
            @Nullable String foreignKeyQuery,
            @Nullable String sequenceQuery,
            @Nullable String indexQuery
    ) {
        /**
         * Returns the discovery the given strategies describe, without dialect queries.
         *
         * @param sequences the strategy for discovering sequences.
         * @param constraints the strategy for discovering primary keys, unique keys, and foreign keys.
         * @return the discovery.
         */
        public static Discovery of(SequenceDiscoveryStrategy sequences, ConstraintDiscoveryStrategy constraints) {
            return new Discovery(sequences, constraints, null, null, null, null);
        }

        /**
         * Returns the discovery the dialect describes: its strategies and its queries.
         *
         * @param dialect the dialect of the database.
         * @return the discovery.
         */
        public static Discovery of(SqlDialect dialect) {
            return new Discovery(dialect.sequenceDiscoveryStrategy(), dialect.constraintDiscoveryStrategy(),
                    dialect.keyDiscoveryQuery(), dialect.foreignKeyDiscoveryQuery(), dialect.sequenceDiscoveryQuery(),
                    dialect.indexDiscoveryQuery());
        }
    }

    // Case-insensitive maps: table name -> columns/PKs/UKs/FKs.
    private final SortedMap<String, List<DbColumn>> columnsByTable;
    private final SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable;
    private final SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable;
    private final SortedMap<String, List<DbForeignKey>> foreignKeysByTable;
    private final SortedMap<String, List<DbIndex>> indexesByTable;
    private final SortedMap<String, Boolean> sequences;
    private final boolean sequencesDiscovered;
    private final Map<ConstraintKind, SortedSet<String>> discoveredByKind;
    private final SortedSet<String> views;

    private DatabaseSchema(
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            SortedMap<String, List<DbIndex>> indexesByTable,
            SortedMap<String, Boolean> sequences,
            boolean sequencesDiscovered,
            Map<ConstraintKind, SortedSet<String>> discoveredByKind,
            SortedSet<String> views
    ) {
        this.columnsByTable = columnsByTable;
        this.primaryKeysByTable = primaryKeysByTable;
        this.uniqueKeysByTable = uniqueKeysByTable;
        this.foreignKeysByTable = foreignKeysByTable;
        this.indexesByTable = indexesByTable;
        this.sequences = sequences;
        this.sequencesDiscovered = sequencesDiscovered;
        this.discoveredByKind = discoveredByKind;
        this.views = views;
    }

    /**
     * Returns whether constraints of the given kind were actually read for the given table.
     *
     * <p>A metadata query that fails leaves the corresponding map empty, which reads exactly like a table that has
     * no such constraints. Callers that would otherwise report a constraint as missing must check this first, so a
     * database that cannot answer the query is reported as unknown rather than as wrong.</p>
     *
     * <p>The answer is per table and per kind, because a read can fail for one table while succeeding for the rest,
     * and for one kind while succeeding for another. A failure that costs the schema its foreign keys leaves its
     * primary keys, and every other table, still worth validating.</p>
     *
     * @param tableName the table to check, case-insensitively.
     * @param kind the constraint kind to check.
     * @return {@code true} if the read succeeded, {@code false} if it failed and the constraints are unknown.
     */
    public boolean isDiscovered(String tableName, ConstraintKind kind) {
        return discoveredByKind.getOrDefault(kind, EMPTY_TABLES).contains(tableName);
    }

    private static final SortedSet<String> EMPTY_TABLES = Collections.emptySortedSet();

    /** Records that the given kind was read successfully for the given tables. */
    private static void discovered(
            Map<ConstraintKind, SortedSet<String>> discoveredByKind,
            ConstraintKind kind,
            Collection<String> tableNames
    ) {
        discoveredByKind.computeIfAbsent(kind, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                .addAll(tableNames);
    }

    /**
     * Reads the database schema from the given connection using default catalog and schema.
     *
     * @param connection the JDBC connection.
     * @return the database schema.
     * @throws SQLException if a database access error occurs.
     */
    public static DatabaseSchema read(Connection connection) throws SQLException {
        return read(connection, connection.getCatalog(), connection.getSchema(),
                Discovery.of(SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA));
    }

    /**
     * Reads the database schema from the given connection as the given discovery describes.
     *
     * @param connection    the JDBC connection.
     * @param catalog       the catalog name (may be null).
     * @param schemaPattern the schema pattern (may be null).
     * @param discovery     the strategies and queries to discover keys, sequences, and indexes with.
     * @return the database schema.
     * @throws SQLException if a database access error occurs.
     */
    public static DatabaseSchema read(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            Discovery discovery
    ) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        // Normalize the schema pattern to match the database's identifier casing convention.
        if (schemaPattern != null) {
            if (metadata.storesUpperCaseIdentifiers()) {
                schemaPattern = schemaPattern.toUpperCase();
            } else if (metadata.storesLowerCaseIdentifiers()) {
                schemaPattern = schemaPattern.toLowerCase();
            }
        }
        SortedMap<String, List<DbColumn>> columnsByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SortedMap<String, List<DbForeignKey>> foreignKeysByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SortedMap<String, List<DbIndex>> indexesByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SortedMap<String, Boolean> sequences = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        SortedSet<String> views = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Discover tables and views.
        try (ResultSet tables = metadata.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                // Initialize entries so tableExists() works even for tables with no columns.
                columnsByTable.putIfAbsent(tableName, new ArrayList<>());
                if ("VIEW".equalsIgnoreCase(tables.getString("TABLE_TYPE"))) {
                    views.add(tableName);
                }
            }
        }
        // Discover columns for all tables.
        try (ResultSet columns = metadata.getColumns(catalog, schemaPattern, "%", "%")) {
            // IS_GENERATEDCOLUMN is the last column JDBC specifies, and not every driver returns it.
            boolean reportsGenerated = hasColumn(columns.getMetaData(), "IS_GENERATEDCOLUMN");
            while (columns.next()) {
                String tableName = columns.getString("TABLE_NAME");
                // Only include columns for tables we discovered.
                if (!columnsByTable.containsKey(tableName)) {
                    continue;
                }
                // The columns are read in their result set order. A driver may return COLUMN_DEF as a streamed long
                // value: reading a later column first closes the stream, and COLUMN_DEF can no longer be read.
                String columnName = columns.getString("COLUMN_NAME");
                int dataType = columns.getInt("DATA_TYPE");
                String typeName = columns.getString("TYPE_NAME");
                int columnSize = columns.getInt("COLUMN_SIZE");
                String columnDef = columns.getString("COLUMN_DEF");
                boolean hasDefault = columnDef != null;
                String nullableStr = columns.getString("IS_NULLABLE");
                boolean nullable = !"NO".equalsIgnoreCase(nullableStr);
                String autoIncrementStr = columns.getString("IS_AUTOINCREMENT");
                boolean autoIncrement = "YES".equalsIgnoreCase(autoIncrementStr);
                boolean generated = reportsGenerated && "YES".equalsIgnoreCase(columns.getString("IS_GENERATEDCOLUMN"));

                columnsByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                        .add(new DbColumn(tableName, columnName, dataType, typeName, columnSize, nullable, autoIncrement, hasDefault, generated));
            }
        }
        // A database that names its schemas as catalogs has no schema pattern; its catalog is the schema the
        // dialect's queries filter on.
        String schema = schemaPattern != null ? schemaPattern : catalog;
        // Discover primary keys, unique keys, and foreign keys with the dialect's queries, or its strategy.
        Map<ConstraintKind, SortedSet<String>> discoveredByKind = new EnumMap<>(ConstraintKind.class);
        String keyQuery = schema == null ? null : discovery.keyQuery();
        String foreignKeyQuery = schema == null ? null : discovery.foreignKeyQuery();
        if (keyQuery == null || foreignKeyQuery == null) {
            readConstraints(connection, metadata, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable, discovery.constraints(),
                    discoveredByKind);
        }
        if (keyQuery != null) {
            primaryKeysByTable.clear();
            uniqueKeysByTable.clear();
            discoveredByKind.remove(ConstraintKind.PRIMARY_KEY);
            discoveredByKind.remove(ConstraintKind.UNIQUE_KEY);
            readKeys(connection, keyQuery, List.of(schema), columnsByTable, primaryKeysByTable, uniqueKeysByTable,
                    discoveredByKind);
        }
        if (foreignKeyQuery != null) {
            foreignKeysByTable.clear();
            discoveredByKind.remove(ConstraintKind.FOREIGN_KEY);
            readForeignKeys(connection, foreignKeyQuery, List.of(schema), columnsByTable, foreignKeysByTable,
                    discoveredByKind);
        }
        // Discover indexes with the dialect's query, or per table.
        String indexQuery = schema == null ? null : discovery.indexQuery();
        if (indexQuery == null) {
            readIndexesFromJdbcMetadata(metadata, catalog, schemaPattern, columnsByTable, indexesByTable,
                    discoveredByKind);
        } else {
            readIndexesWithQuery(connection, indexQuery, schema, columnsByTable, indexesByTable, discoveredByKind);
        }
        // Discover sequences with the dialect's query, or its strategy.
        String sequenceQuery = schema == null ? null : discovery.sequenceQuery();
        boolean sequencesDiscovered = sequenceQuery != null
                ? readSequences(connection, sequenceQuery, List.of(schema), sequences)
                : readSequences(connection, catalog, schemaPattern, sequences, discovery.sequences());
        return new DatabaseSchema(columnsByTable, primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable,
                indexesByTable, sequences, sequencesDiscovered, discoveredByKind, views);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Constraint discovery strategies.
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Dispatches constraint discovery to the appropriate strategy.
     */
    private static void readConstraints(
            Connection connection,
            DatabaseMetaData metadata,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            ConstraintDiscoveryStrategy strategy,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) throws SQLException {
        switch (strategy) {
            case JDBC_METADATA -> readConstraintsFromJdbcMetadata(
                    metadata, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable, discovered);
            case INFORMATION_SCHEMA -> readConstraintsFromInformationSchema(
                    connection, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable, discovered);
            case INFORMATION_SCHEMA_REFERENCING -> readConstraintsFromInformationSchemaReferencing(
                    connection, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable, discovered);
        }
    }

    /**
     * Reads constraints using per-table JDBC {@link DatabaseMetaData} calls.
     *
     * <p>This is the portable fallback that works with any JDBC driver but issues three metadata queries per table
     * in the schema.</p>
     */
    private static void readConstraintsFromJdbcMetadata(
            DatabaseMetaData metadata,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) throws SQLException {
        for (String tableName : new ArrayList<>(columnsByTable.keySet())) {
            try (ResultSet primaryKeys = metadata.getPrimaryKeys(catalog, schemaPattern, tableName)) {
                while (primaryKeys.next()) {
                    String pkTableName = primaryKeys.getString("TABLE_NAME");
                    String columnName = primaryKeys.getString("COLUMN_NAME");
                    int keySeq = primaryKeys.getShort("KEY_SEQ");
                    primaryKeysByTable.computeIfAbsent(pkTableName, k -> new ArrayList<>())
                            .add(new DbPrimaryKey(pkTableName, columnName, keySeq));
                }
                discovered(discovered, ConstraintKind.PRIMARY_KEY, List.of(tableName));
            } catch (SQLException e) {
                // Some databases/views may not support getPrimaryKeys; this table's primary key stays unknown.
                LOGGER.debug("Failed to read primary keys for table '{}'.", tableName, e);
            }
        }
        for (String tableName : new ArrayList<>(columnsByTable.keySet())) {
            try (ResultSet indexInfo = metadata.getIndexInfo(catalog, schemaPattern, tableName, true, true)) {
                while (indexInfo.next()) {
                    // In result set order, as for the columns.
                    String indexName = indexInfo.getString("INDEX_NAME");
                    if (indexInfo.getShort("TYPE") == 0) {
                        continue;
                    }
                    int ordinalPosition = indexInfo.getShort("ORDINAL_POSITION");
                    String columnName = indexInfo.getString("COLUMN_NAME");
                    if (indexName == null || columnName == null) {
                        continue;
                    }
                    uniqueKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new DbUniqueKey(tableName, indexName, columnName, ordinalPosition));
                }
                discovered(discovered, ConstraintKind.UNIQUE_KEY, List.of(tableName));
            } catch (SQLException e) {
                // Some databases/views may not support getIndexInfo; this table's unique keys stay unknown.
                LOGGER.debug("Failed to read unique indexes for table '{}'.", tableName, e);
            }
        }
        for (String tableName : new ArrayList<>(columnsByTable.keySet())) {
            try (ResultSet importedKeys = metadata.getImportedKeys(catalog, schemaPattern, tableName)) {
                while (importedKeys.next()) {
                    // In result set order, as for the columns.
                    String pkTableName = importedKeys.getString("PKTABLE_NAME");
                    String pkColumnName = importedKeys.getString("PKCOLUMN_NAME");
                    String fkTableName = importedKeys.getString("FKTABLE_NAME");
                    String fkColumnName = importedKeys.getString("FKCOLUMN_NAME");
                    String constraintName = importedKeys.getString("FK_NAME");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, constraintName, fkColumnName, pkTableName, pkColumnName));
                }
                discovered(discovered, ConstraintKind.FOREIGN_KEY, List.of(tableName));
            } catch (SQLException e) {
                // Some databases/views may not support getImportedKeys; this table's foreign keys stay unknown.
                LOGGER.debug("Failed to read foreign keys for table '{}'.", tableName, e);
            }
        }
    }

    /**
     * Appends a {@code WHERE} or {@code AND} clause filtering by the given column and value.
     *
     * @param sql          the SQL builder to append to.
     * @param hasCondition whether a {@code WHERE} clause has already been appended.
     * @param column       the column name to filter on.
     * @param value        the value to match (may be null, in which case no clause is appended).
     * @return {@code true} if a condition was appended.
     */
    private static boolean appendFilter(
            StringBuilder sql,
            boolean hasCondition,
            String column,
            @Nullable String value,
            List<String> parameters
    ) {
        if (value == null || value.isEmpty()) {
            return hasCondition;
        }
        sql.append(hasCondition ? " AND " : " WHERE ");
        // Bind the value as a parameter; the column is always a compile-time constant.
        sql.append(column).append(" = ?");
        parameters.add(value);
        return true;
    }

    /**
     * Prepares the given metadata query and binds the collected filter values as parameters.
     */
    private static PreparedStatement prepareSchemaQuery(
            Connection connection,
            String sql,
            List<String> parameters
    ) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setString(index + 1, parameters.get(index));
            }
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
        return statement;
    }

    /**
     * Reads primary keys and unique keys from {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} joined with
     * {@code KEY_COLUMN_USAGE}. This helper is shared by both {@code INFORMATION_SCHEMA} strategies.
     */
    private static void readPrimaryAndUniqueKeysFromInformationSchema(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT tc.CONSTRAINT_TYPE, tc.CONSTRAINT_NAME,
                           kcu.TABLE_NAME, kcu.COLUMN_NAME, kcu.ORDINAL_POSITION
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                      ON tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                      AND tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                      AND tc.TABLE_NAME = kcu.TABLE_NAME
                    WHERE tc.CONSTRAINT_TYPE IN ('PRIMARY KEY', 'UNIQUE')""");
            List<String> parameters = new ArrayList<>();
            appendFilter(sql, true, "tc.TABLE_CATALOG", catalog, parameters);
            appendFilter(sql, true, "tc.TABLE_SCHEMA", schemaPattern, parameters);
            try (var statement = prepareSchemaQuery(connection, sql.toString(), parameters);
                 var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (!columnsByTable.containsKey(tableName)) {
                        continue;
                    }
                    String constraintType = resultSet.getString("CONSTRAINT_TYPE");
                    String constraintName = resultSet.getString("CONSTRAINT_NAME");
                    String columnName = resultSet.getString("COLUMN_NAME");
                    int ordinalPosition = resultSet.getInt("ORDINAL_POSITION");
                    if ("PRIMARY KEY".equals(constraintType)) {
                        primaryKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                                .add(new DbPrimaryKey(tableName, columnName, ordinalPosition));
                    } else {
                        uniqueKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                                .add(new DbUniqueKey(tableName, constraintName, columnName, ordinalPosition));
                    }
                }
            }
            discovered(discovered, ConstraintKind.PRIMARY_KEY, columnsByTable.keySet());
            discovered(discovered, ConstraintKind.UNIQUE_KEY, columnsByTable.keySet());
        } catch (SQLException e) {
            // INFORMATION_SCHEMA views not available; the primary and unique keys stay unknown.
            LOGGER.debug("Failed to read primary and unique keys from INFORMATION_SCHEMA.", e);
        }
    }

    /**
     * Reads constraints using standard {@code INFORMATION_SCHEMA} views. Foreign keys are discovered via
     * {@code REFERENTIAL_CONSTRAINTS} joined with {@code KEY_COLUMN_USAGE} using
     * {@code POSITION_IN_UNIQUE_CONSTRAINT}.
     */
    private static void readConstraintsFromInformationSchema(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        readPrimaryAndUniqueKeysFromInformationSchema(
                connection, catalog, schemaPattern, columnsByTable, primaryKeysByTable, uniqueKeysByTable, discovered);
        // Foreign keys: join REFERENTIAL_CONSTRAINTS with KEY_COLUMN_USAGE on both sides, matching columns by
        // POSITION_IN_UNIQUE_CONSTRAINT.
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT kcu.TABLE_NAME AS FK_TABLE, rc.CONSTRAINT_NAME AS FK_NAME, kcu.COLUMN_NAME AS FK_COLUMN,
                           kcu2.TABLE_NAME AS PK_TABLE, kcu2.COLUMN_NAME AS PK_COLUMN
                    FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc
                    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                      ON rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                      AND rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu2
                      ON rc.UNIQUE_CONSTRAINT_SCHEMA = kcu2.CONSTRAINT_SCHEMA
                      AND rc.UNIQUE_CONSTRAINT_NAME = kcu2.CONSTRAINT_NAME
                      AND kcu.POSITION_IN_UNIQUE_CONSTRAINT = kcu2.ORDINAL_POSITION
                    WHERE 1=1""");
            List<String> parameters = new ArrayList<>();
            appendFilter(sql, true, "rc.CONSTRAINT_CATALOG", catalog, parameters);
            appendFilter(sql, true, "rc.CONSTRAINT_SCHEMA", schemaPattern, parameters);
            try (var statement = prepareSchemaQuery(connection, sql.toString(), parameters);
                 var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String fkTableName = resultSet.getString("FK_TABLE");
                    if (!columnsByTable.containsKey(fkTableName)) {
                        continue;
                    }
                    String constraintName = resultSet.getString("FK_NAME");
                    String fkColumnName = resultSet.getString("FK_COLUMN");
                    String pkTableName = resultSet.getString("PK_TABLE");
                    String pkColumnName = resultSet.getString("PK_COLUMN");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, constraintName, fkColumnName, pkTableName, pkColumnName));
                }
            }
            discovered(discovered, ConstraintKind.FOREIGN_KEY, columnsByTable.keySet());
        } catch (SQLException e) {
            // REFERENTIAL_CONSTRAINTS not available; the foreign keys stay unknown.
            LOGGER.debug("Failed to read foreign keys from INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS.", e);
        }
    }

    /**
     * Reads constraints using {@code INFORMATION_SCHEMA} views with {@code REFERENCED_TABLE_NAME} and
     * {@code REFERENCED_COLUMN_NAME} columns in {@code KEY_COLUMN_USAGE} for foreign key discovery.
     */
    private static void readConstraintsFromInformationSchemaReferencing(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        // For databases that use catalogs as schemas, the catalog value represents the database name and maps to
        // TABLE_SCHEMA in INFORMATION_SCHEMA views (not TABLE_CATALOG).
        String effectiveSchema = schemaPattern != null ? schemaPattern : catalog;
        readPrimaryAndUniqueKeysFromInformationSchema(
                connection, null, effectiveSchema, columnsByTable, primaryKeysByTable, uniqueKeysByTable, discovered);
        // Foreign keys: use REFERENCED_TABLE_NAME and REFERENCED_COLUMN_NAME columns in KEY_COLUMN_USAGE.
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                    FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
                    WHERE REFERENCED_TABLE_NAME IS NOT NULL""");
            List<String> parameters = new ArrayList<>();
            appendFilter(sql, true, "TABLE_SCHEMA", effectiveSchema, parameters);
            try (var statement = prepareSchemaQuery(connection, sql.toString(), parameters);
                 var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String fkTableName = resultSet.getString("TABLE_NAME");
                    if (!columnsByTable.containsKey(fkTableName)) {
                        continue;
                    }
                    String constraintName = resultSet.getString("CONSTRAINT_NAME");
                    String fkColumnName = resultSet.getString("COLUMN_NAME");
                    String pkTableName = resultSet.getString("REFERENCED_TABLE_NAME");
                    String pkColumnName = resultSet.getString("REFERENCED_COLUMN_NAME");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, constraintName, fkColumnName, pkTableName, pkColumnName));
                }
            }
            discovered(discovered, ConstraintKind.FOREIGN_KEY, columnsByTable.keySet());
        } catch (SQLException e) {
            // REFERENCED columns not available; the foreign keys stay unknown.
            LOGGER.debug("Failed to read foreign keys from INFORMATION_SCHEMA.KEY_COLUMN_USAGE.", e);
        }
    }

    /**
     * Reads primary and unique keys with a query that selects {@code CONSTRAINT_TYPE} ({@code PRIMARY KEY} or
     * {@code UNIQUE}), {@code CONSTRAINT_NAME}, {@code TABLE_NAME}, {@code COLUMN_NAME} and {@code ORDINAL_POSITION}.
     * A query that fails leaves the keys unknown.
     */
    private static void readKeys(
            Connection connection,
            String sql,
            List<String> parameters,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        try (var statement = prepareSchemaQuery(connection, sql, parameters);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String tableName = resultSet.getString("TABLE_NAME");
                if (!columnsByTable.containsKey(tableName)) {
                    continue;
                }
                String constraintType = resultSet.getString("CONSTRAINT_TYPE");
                String constraintName = resultSet.getString("CONSTRAINT_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                int ordinalPosition = resultSet.getInt("ORDINAL_POSITION");
                if ("PRIMARY KEY".equals(constraintType)) {
                    primaryKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new DbPrimaryKey(tableName, columnName, ordinalPosition));
                } else {
                    uniqueKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new DbUniqueKey(tableName, constraintName, columnName, ordinalPosition));
                }
            }
            discovered(discovered, ConstraintKind.PRIMARY_KEY, columnsByTable.keySet());
            discovered(discovered, ConstraintKind.UNIQUE_KEY, columnsByTable.keySet());
        } catch (SQLException e) {
            // The query is not available; the primary and unique keys stay unknown.
            LOGGER.debug("Failed to read primary and unique keys.", e);
        }
    }

    /**
     * Reads foreign keys with a query that selects {@code FK_TABLE}, {@code FK_NAME}, {@code FK_COLUMN},
     * {@code PK_TABLE} and {@code PK_COLUMN}, one row per column, in key order. A query that fails leaves the foreign
     * keys unknown.
     */
    private static void readForeignKeys(
            Connection connection,
            String sql,
            List<String> parameters,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        try (var statement = prepareSchemaQuery(connection, sql, parameters);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String fkTableName = resultSet.getString("FK_TABLE");
                if (!columnsByTable.containsKey(fkTableName)) {
                    continue;
                }
                String constraintName = resultSet.getString("FK_NAME");
                String fkColumnName = resultSet.getString("FK_COLUMN");
                String pkTableName = resultSet.getString("PK_TABLE");
                String pkColumnName = resultSet.getString("PK_COLUMN");
                foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                        .add(new DbForeignKey(fkTableName, constraintName, fkColumnName, pkTableName, pkColumnName));
            }
            discovered(discovered, ConstraintKind.FOREIGN_KEY, columnsByTable.keySet());
        } catch (SQLException e) {
            // The query is not available; the foreign keys stay unknown.
            LOGGER.debug("Failed to read foreign keys.", e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Index discovery.
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Reads indexes using per-table {@link DatabaseMetaData#getIndexInfo} calls. Statistic rows, which some drivers
     * return alongside the indexes, are skipped. The call is approximate, so it never makes the database compute
     * statistics to answer it.
     */
    private static void readIndexesFromJdbcMetadata(
            DatabaseMetaData metadata,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbIndex>> indexesByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        for (String tableName : new ArrayList<>(columnsByTable.keySet())) {
            try (ResultSet indexInfo = metadata.getIndexInfo(catalog, schemaPattern, tableName, false, true)) {
                while (indexInfo.next()) {
                    // In result set order, as for the columns.
                    boolean nonUnique = indexInfo.getBoolean("NON_UNIQUE");
                    String indexName = indexInfo.getString("INDEX_NAME");
                    short type = indexInfo.getShort("TYPE");
                    int ordinalPosition = indexInfo.getShort("ORDINAL_POSITION");
                    String columnName = indexInfo.getString("COLUMN_NAME");
                    String filterCondition = indexInfo.getString("FILTER_CONDITION");
                    if (type == DatabaseMetaData.tableIndexStatistic || indexName == null) {
                        continue;
                    }
                    indexesByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new DbIndex(tableName, indexName, columnName, ordinalPosition, !nonUnique,
                                    filterCondition != null && !filterCondition.isBlank()));
                }
                discovered(discovered, ConstraintKind.INDEX, List.of(tableName));
            } catch (SQLException e) {
                // Some databases/views may not support getIndexInfo; this table's indexes stay unknown.
                LOGGER.debug("Failed to read indexes for table '{}'.", tableName, e);
            }
        }
    }

    /**
     * Reads the indexes of the schema with one query, which takes the schema name as its only parameter and selects
     * the columns {@link st.orm.core.template.SqlDialect#indexDiscoveryQuery()} lists.
     */
    private static void readIndexesWithQuery(
            Connection connection,
            String query,
            String schema,
            SortedMap<String, List<DbColumn>> columnsByTable,
            SortedMap<String, List<DbIndex>> indexesByTable,
            Map<ConstraintKind, SortedSet<String>> discovered
    ) {
        try {
            try (var statement = prepareSchemaQuery(connection, query, List.of(schema));
                 var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (!columnsByTable.containsKey(tableName)) {
                        continue;
                    }
                    String indexName = resultSet.getString("INDEX_NAME");
                    String columnName = resultSet.getString("COLUMN_NAME");
                    int ordinalPosition = resultSet.getInt("ORDINAL_POSITION");
                    boolean unique = resultSet.getInt("IS_UNIQUE") == 1;
                    boolean partial = resultSet.getInt("IS_PARTIAL") == 1;
                    indexesByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new DbIndex(tableName, indexName, columnName, ordinalPosition, unique, partial));
                }
            }
            discovered(discovered, ConstraintKind.INDEX, columnsByTable.keySet());
        } catch (SQLException e) {
            // The catalog is not available; the indexes stay unknown.
            LOGGER.debug("Failed to read indexes.", e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Sequence discovery strategies.
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Reads sequences using the specified discovery strategy.
     *
     * @return {@code true} if sequences were actually read, {@code false} if the strategy is
     *         {@link SequenceDiscoveryStrategy#NONE} or the read failed, leaving the sequences unknown.
     */
    private static boolean readSequences(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, Boolean> sequences,
            SequenceDiscoveryStrategy strategy
    ) {
        return switch (strategy) {
            case INFORMATION_SCHEMA -> readSequencesFromInformationSchema(connection, catalog, schemaPattern, sequences);
            case INFORMATION_SCHEMA_TABLES -> readSequencesFromInformationSchemaTables(connection, catalog, schemaPattern, sequences);
            case NONE -> false;
        };
    }

    /**
     * Attempts to read sequences from INFORMATION_SCHEMA.SEQUENCES.
     */
    private static boolean readSequencesFromInformationSchema(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, Boolean> sequences
    ) {
        try {
            StringBuilder sql = new StringBuilder("SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES");
            List<String> parameters = new ArrayList<>();
            boolean hasCondition = appendFilter(sql, false, "SEQUENCE_CATALOG", catalog, parameters);
            appendFilter(sql, hasCondition, "SEQUENCE_SCHEMA", schemaPattern, parameters);
            try (var statement = prepareSchemaQuery(connection, sql.toString(), parameters);
                 var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sequences.put(resultSet.getString("SEQUENCE_NAME"), Boolean.TRUE);
                }
            }
            return true;
        } catch (SQLException ignored) {
            // INFORMATION_SCHEMA.SEQUENCES not available; sequence validation will be skipped.
            return false;
        }
    }

    /**
     * Attempts to read sequences from INFORMATION_SCHEMA.TABLES rows with TABLE_TYPE = 'SEQUENCE'.
     *
     * <p>For a database that exposes sequences as a table type rather than through a dedicated view, or whose
     * {@code INFORMATION_SCHEMA.SEQUENCES} view exists only in recent versions and keys {@code SEQUENCE_CATALOG} to a
     * constant, the {@code TABLES} view is the source that works across every version that supports sequences.</p>
     */
    private static boolean readSequencesFromInformationSchemaTables(
            Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            SortedMap<String, Boolean> sequences
    ) {
        // For databases that use catalogs as schemas, the catalog value represents the database name and maps to
        // TABLE_SCHEMA in INFORMATION_SCHEMA views (not TABLE_CATALOG).
        String effectiveSchema = schemaPattern != null ? schemaPattern : catalog;
        try {
            StringBuilder sql = new StringBuilder("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'SEQUENCE'");
            List<String> parameters = new ArrayList<>();
            appendFilter(sql, true, "TABLE_SCHEMA", effectiveSchema, parameters);
            try (var statement = prepareSchemaQuery(connection, sql.toString(), parameters);
                 var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    sequences.put(resultSet.getString("TABLE_NAME"), Boolean.TRUE);
                }
            }
            return true;
        } catch (SQLException ignored) {
            // INFORMATION_SCHEMA.TABLES not available; sequence validation will be skipped.
            return false;
        }
    }

    /**
     * Reads sequences with a query that selects {@code SEQUENCE_NAME}.
     *
     * @return {@code true} if the sequences were read, {@code false} if the query failed, leaving them unknown.
     */
    private static boolean readSequences(
            Connection connection,
            String sql,
            List<String> parameters,
            SortedMap<String, Boolean> sequences
    ) {
        try (var statement = prepareSchemaQuery(connection, sql, parameters);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                sequences.put(resultSet.getString("SEQUENCE_NAME"), Boolean.TRUE);
            }
            return true;
        } catch (SQLException e) {
            // The query is not available; sequence validation will be skipped.
            LOGGER.debug("Failed to read sequences.", e);
            return false;
        }
    }

    /**
     * Returns whether a table or view with the given name exists in the schema.
     *
     * @param tableName the table name (case-insensitive).
     * @return {@code true} if the table exists.
     */
    public boolean tableExists(String tableName) {
        return columnsByTable.containsKey(tableName);
    }

    /**
     * Returns whether the given name is a view rather than a table.
     *
     * <p>A view cannot declare its columns {@code NOT NULL}, so the metadata reports them as nullable whatever the
     * query beneath returns.</p>
     *
     * @param tableName the table or view name (case-insensitive).
     * @return {@code true} if the name is a view.
     * @since 1.15
     */
    public boolean isView(String tableName) {
        return views.contains(tableName);
    }

    /**
     * Returns the column metadata for the given table and column name.
     *
     * @param tableName  the table name (case-insensitive).
     * @param columnName the column name (case-insensitive).
     * @return the column metadata, or empty if not found.
     */
    public Optional<DbColumn> getColumn(String tableName, String columnName) {
        List<DbColumn> columns = columnsByTable.get(tableName);
        if (columns == null) {
            return Optional.empty();
        }
        return columns.stream()
                .filter(column -> column.columnName().equalsIgnoreCase(columnName))
                .findFirst();
    }

    /**
     * Returns all columns for the given table.
     *
     * @param tableName the table name (case-insensitive).
     * @return the columns, or an empty list if the table is not found.
     */
    public List<DbColumn> getColumns(String tableName) {
        List<DbColumn> columns = columnsByTable.get(tableName);
        if (columns == null) {
            return List.of();
        }
        return List.copyOf(columns);
    }

    /**
     * Returns the primary key columns for the given table, ordered by key sequence.
     *
     * @param tableName the table name (case-insensitive).
     * @return the primary key columns, or an empty list if none found.
     */
    public List<DbPrimaryKey> getPrimaryKeys(String tableName) {
        List<DbPrimaryKey> primaryKeys = primaryKeysByTable.get(tableName);
        if (primaryKeys == null) {
            return List.of();
        }
        return List.copyOf(primaryKeys);
    }

    /**
     * Returns the unique key columns for the given table.
     *
     * @param tableName the table name (case-insensitive).
     * @return the unique key columns, or an empty list if none found.
     */
    public List<DbUniqueKey> getUniqueKeys(String tableName) {
        List<DbUniqueKey> uniqueKeys = uniqueKeysByTable.get(tableName);
        if (uniqueKeys == null) {
            return List.of();
        }
        return List.copyOf(uniqueKeys);
    }

    /**
     * Returns the foreign key constraints for the given table.
     *
     * @param tableName the table name (case-insensitive).
     * @return the foreign key constraints, or an empty list if none found.
     */
    public List<DbForeignKey> getForeignKeys(String tableName) {
        List<DbForeignKey> foreignKeys = foreignKeysByTable.get(tableName);
        if (foreignKeys == null) {
            return List.of();
        }
        return List.copyOf(foreignKeys);
    }

    /**
     * Returns the index columns of the given table: one entry per column of every index, unique or not.
     *
     * @param tableName the table name (case-insensitive).
     * @return the index columns, or an empty list if none found.
     * @since 1.16
     */
    public List<DbIndex> getIndexes(String tableName) {
        List<DbIndex> indexes = indexesByTable.get(tableName);
        if (indexes == null) {
            return List.of();
        }
        return List.copyOf(indexes);
    }

    /**
     * Returns the names of the tables and views in the schema.
     *
     * @return the table names, in case-insensitive order.
     * @since 1.16
     */
    public SortedSet<String> tableNames() {
        SortedSet<String> tableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        tableNames.addAll(columnsByTable.keySet());
        return Collections.unmodifiableSortedSet(tableNames);
    }

    /**
     * Returns whether a sequence with the given name exists in the schema.
     *
     * @param sequenceName the sequence name (case-insensitive).
     * @return {@code true} if the sequence exists.
     */
    public boolean sequenceExists(String sequenceName) {
        return sequences.containsKey(sequenceName);
    }

    /**
     * Returns whether sequences were actually read from the database.
     *
     * <p>A discovery strategy that is {@link SequenceDiscoveryStrategy#NONE} or that fails leaves the sequence map
     * empty, which reads exactly like a schema without sequences. Callers that would otherwise report a sequence as
     * missing must check this first, so a database that cannot answer the query is reported as unknown rather than
     * as wrong.</p>
     *
     * @return {@code true} if the read succeeded, {@code false} if the sequences are unknown.
     */
    public boolean sequencesDiscovered() {
        return sequencesDiscovered;
    }
}
