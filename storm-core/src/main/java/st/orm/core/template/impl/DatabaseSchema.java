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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;

/**
 * Reads JDBC {@link DatabaseMetaData} into an in-memory representation for schema validation.
 *
 * <p>All name lookups are case-insensitive, since database systems vary in how they handle identifier casing
 * (PostgreSQL lowercases, Oracle uppercases, H2 uppercases by default).</p>
 *
 * @since 1.9
 */
public final class DatabaseSchema {

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
     */
    public record DbColumn(
            @Nonnull String tableName,
            @Nonnull String columnName,
            int dataType,
            @Nonnull String typeName,
            int columnSize,
            boolean nullable,
            boolean autoIncrement,
            boolean hasDefault
    ) {}

    /**
     * Represents a primary key column discovered from the database metadata.
     *
     * @param tableName  the table containing this primary key column.
     * @param columnName the column name that is part of the primary key.
     * @param keySeq     the sequence number within the primary key (1-based).
     */
    public record DbPrimaryKey(
            @Nonnull String tableName,
            @Nonnull String columnName,
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
            @Nonnull String tableName,
            @Nonnull String indexName,
            @Nonnull String columnName,
            int ordinalPosition
    ) {}

    /**
     * Represents a foreign key relationship discovered from the database metadata.
     *
     * @param fkTableName  the table containing the foreign key column.
     * @param fkColumnName the foreign key column name.
     * @param pkTableName  the referenced (primary key) table name.
     * @param pkColumnName the referenced (primary key) column name.
     */
    public record DbForeignKey(
            @Nonnull String fkTableName,
            @Nonnull String fkColumnName,
            @Nonnull String pkTableName,
            @Nonnull String pkColumnName
    ) {}

    // Case-insensitive maps: table name -> columns/PKs/UKs/FKs.
    private final SortedMap<String, List<DbColumn>> columnsByTable;
    private final SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable;
    private final SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable;
    private final SortedMap<String, List<DbForeignKey>> foreignKeysByTable;
    private final SortedMap<String, Boolean> sequences;

    private DatabaseSchema(
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            @Nonnull SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            @Nonnull SortedMap<String, Boolean> sequences
    ) {
        this.columnsByTable = columnsByTable;
        this.primaryKeysByTable = primaryKeysByTable;
        this.uniqueKeysByTable = uniqueKeysByTable;
        this.foreignKeysByTable = foreignKeysByTable;
        this.sequences = sequences;
    }

    /**
     * Reads the database schema from the given connection using default catalog and schema.
     *
     * @param connection the JDBC connection.
     * @return the database schema.
     * @throws SQLException if a database access error occurs.
     */
    public static DatabaseSchema read(@Nonnull Connection connection) throws SQLException {
        return read(connection, connection.getCatalog(), connection.getSchema(),
                SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA);
    }

    /**
     * Reads the database schema from the given connection using the specified discovery strategies.
     *
     * @param connection                    the JDBC connection.
     * @param catalog                       the catalog name (may be null).
     * @param schemaPattern                 the schema pattern (may be null).
     * @param sequenceDiscoveryStrategy     the strategy for discovering sequences.
     * @param constraintDiscoveryStrategy   the strategy for discovering primary keys, unique keys, and foreign keys.
     * @return the database schema.
     * @throws SQLException if a database access error occurs.
     */
    public static DatabaseSchema read(
            @Nonnull Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SequenceDiscoveryStrategy sequenceDiscoveryStrategy,
            @Nonnull ConstraintDiscoveryStrategy constraintDiscoveryStrategy
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
        SortedMap<String, Boolean> sequences = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // Discover tables and views.
        try (ResultSet tables = metadata.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                // Initialize entries so tableExists() works even for tables with no columns.
                columnsByTable.putIfAbsent(tableName, new ArrayList<>());
            }
        }
        // Discover columns for all tables.
        try (ResultSet columns = metadata.getColumns(catalog, schemaPattern, "%", "%")) {
            while (columns.next()) {
                String tableName = columns.getString("TABLE_NAME");
                // Only include columns for tables we discovered.
                if (!columnsByTable.containsKey(tableName)) {
                    continue;
                }
                String columnName = columns.getString("COLUMN_NAME");
                int dataType = columns.getInt("DATA_TYPE");
                String typeName = columns.getString("TYPE_NAME");
                int columnSize = columns.getInt("COLUMN_SIZE");
                String nullableStr = columns.getString("IS_NULLABLE");
                boolean nullable = !"NO".equalsIgnoreCase(nullableStr);
                String autoIncrementStr = columns.getString("IS_AUTOINCREMENT");
                boolean autoIncrement = "YES".equalsIgnoreCase(autoIncrementStr);
                String columnDef = columns.getString("COLUMN_DEF");
                boolean hasDefault = columnDef != null;

                columnsByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                        .add(new DbColumn(tableName, columnName, dataType, typeName, columnSize, nullable, autoIncrement, hasDefault));
            }
        }
        // Discover primary keys, unique keys, and foreign keys using the dialect-provided strategy.
        readConstraints(connection, metadata, catalog, schemaPattern, columnsByTable,
                primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable, constraintDiscoveryStrategy);
        // Discover sequences using the dialect-provided strategy.
        readSequences(connection, catalog, schemaPattern, sequences, sequenceDiscoveryStrategy);
        return new DatabaseSchema(columnsByTable, primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable, sequences);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Constraint discovery strategies.
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Dispatches constraint discovery to the appropriate strategy.
     */
    private static void readConstraints(
            @Nonnull Connection connection,
            @Nonnull DatabaseMetaData metadata,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            @Nonnull SortedMap<String, List<DbForeignKey>> foreignKeysByTable,
            @Nonnull ConstraintDiscoveryStrategy strategy
    ) throws SQLException {
        switch (strategy) {
            case JDBC_METADATA -> readConstraintsFromJdbcMetadata(
                    metadata, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable);
            case INFORMATION_SCHEMA -> readConstraintsFromInformationSchema(
                    connection, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable);
            case INFORMATION_SCHEMA_REFERENCING -> readConstraintsFromInformationSchemaReferencing(
                    connection, catalog, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable);
            case ALL_CONSTRAINTS -> readConstraintsFromAllConstraints(
                    connection, schemaPattern, columnsByTable,
                    primaryKeysByTable, uniqueKeysByTable, foreignKeysByTable);
        }
    }

    /**
     * Reads constraints using per-table JDBC {@link DatabaseMetaData} calls.
     *
     * <p>This is the portable fallback that works with any JDBC driver but issues three metadata queries per table
     * in the schema.</p>
     */
    private static void readConstraintsFromJdbcMetadata(
            @Nonnull DatabaseMetaData metadata,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            @Nonnull SortedMap<String, List<DbForeignKey>> foreignKeysByTable
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
            } catch (SQLException ignored) {
                // Some databases/views may not support getPrimaryKeys; skip gracefully.
            }
        }
        for (String tableName : new ArrayList<>(columnsByTable.keySet())) {
            try (ResultSet indexInfo = metadata.getIndexInfo(catalog, schemaPattern, tableName, true, true)) {
                while (indexInfo.next()) {
                    if (indexInfo.getShort("TYPE") == 0) {
                        continue;
                    }
                    String indexName = indexInfo.getString("INDEX_NAME");
                    String columnName = indexInfo.getString("COLUMN_NAME");
                    int ordinalPosition = indexInfo.getShort("ORDINAL_POSITION");
                    if (indexName == null || columnName == null) {
                        continue;
                    }
                    uniqueKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new DbUniqueKey(tableName, indexName, columnName, ordinalPosition));
                }
            } catch (SQLException ignored) {
                // Some databases/views may not support getIndexInfo; skip gracefully.
            }
        }
        for (String tableName : new ArrayList<>(columnsByTable.keySet())) {
            try (ResultSet importedKeys = metadata.getImportedKeys(catalog, schemaPattern, tableName)) {
                while (importedKeys.next()) {
                    String fkTableName = importedKeys.getString("FKTABLE_NAME");
                    String fkColumnName = importedKeys.getString("FKCOLUMN_NAME");
                    String pkTableName = importedKeys.getString("PKTABLE_NAME");
                    String pkColumnName = importedKeys.getString("PKCOLUMN_NAME");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, fkColumnName, pkTableName, pkColumnName));
                }
            } catch (SQLException ignored) {
                // Some databases/views may not support getImportedKeys; skip gracefully.
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
            @Nonnull StringBuilder sql,
            boolean hasCondition,
            @Nonnull String column,
            @Nullable String value
    ) {
        if (value == null || value.isEmpty()) {
            return hasCondition;
        }
        sql.append(hasCondition ? " AND " : " WHERE ");
        sql.append(column).append(" = '").append(value.replace("'", "''")).append("'");
        return true;
    }

    /**
     * Reads primary keys and unique keys from {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} joined with
     * {@code KEY_COLUMN_USAGE}. This helper is shared by both {@code INFORMATION_SCHEMA} strategies.
     */
    private static void readPrimaryAndUniqueKeysFromInformationSchema(
            @Nonnull Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable
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
            appendFilter(sql, true, "tc.TABLE_CATALOG", catalog);
            appendFilter(sql, true, "tc.TABLE_SCHEMA", schemaPattern);
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
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
        } catch (SQLException ignored) {
            // INFORMATION_SCHEMA views not available; constraint validation will be skipped.
        }
    }

    /**
     * Reads constraints using standard {@code INFORMATION_SCHEMA} views. Foreign keys are discovered via
     * {@code REFERENTIAL_CONSTRAINTS} joined with {@code KEY_COLUMN_USAGE} using
     * {@code POSITION_IN_UNIQUE_CONSTRAINT}.
     */
    private static void readConstraintsFromInformationSchema(
            @Nonnull Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            @Nonnull SortedMap<String, List<DbForeignKey>> foreignKeysByTable
    ) {
        readPrimaryAndUniqueKeysFromInformationSchema(
                connection, catalog, schemaPattern, columnsByTable, primaryKeysByTable, uniqueKeysByTable);
        // Foreign keys: join REFERENTIAL_CONSTRAINTS with KEY_COLUMN_USAGE on both sides, matching columns by
        // POSITION_IN_UNIQUE_CONSTRAINT.
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT kcu.TABLE_NAME AS FK_TABLE, kcu.COLUMN_NAME AS FK_COLUMN,
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
            appendFilter(sql, true, "rc.CONSTRAINT_CATALOG", catalog);
            appendFilter(sql, true, "rc.CONSTRAINT_SCHEMA", schemaPattern);
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
                while (resultSet.next()) {
                    String fkTableName = resultSet.getString("FK_TABLE");
                    if (!columnsByTable.containsKey(fkTableName)) {
                        continue;
                    }
                    String fkColumnName = resultSet.getString("FK_COLUMN");
                    String pkTableName = resultSet.getString("PK_TABLE");
                    String pkColumnName = resultSet.getString("PK_COLUMN");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, fkColumnName, pkTableName, pkColumnName));
                }
            }
        } catch (SQLException ignored) {
            // REFERENTIAL_CONSTRAINTS not available; foreign key validation will be skipped.
        }
    }

    /**
     * Reads constraints using {@code INFORMATION_SCHEMA} views with {@code REFERENCED_TABLE_NAME} and
     * {@code REFERENCED_COLUMN_NAME} columns in {@code KEY_COLUMN_USAGE} for foreign key discovery.
     */
    private static void readConstraintsFromInformationSchemaReferencing(
            @Nonnull Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            @Nonnull SortedMap<String, List<DbForeignKey>> foreignKeysByTable
    ) {
        // For databases that use catalogs as schemas, the catalog value represents the database name and maps to
        // TABLE_SCHEMA in INFORMATION_SCHEMA views (not TABLE_CATALOG).
        String effectiveSchema = schemaPattern != null ? schemaPattern : catalog;
        readPrimaryAndUniqueKeysFromInformationSchema(
                connection, null, effectiveSchema, columnsByTable, primaryKeysByTable, uniqueKeysByTable);
        // Foreign keys: use REFERENCED_TABLE_NAME and REFERENCED_COLUMN_NAME columns in KEY_COLUMN_USAGE.
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                    FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
                    WHERE REFERENCED_TABLE_NAME IS NOT NULL""");
            appendFilter(sql, true, "TABLE_SCHEMA", effectiveSchema);
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
                while (resultSet.next()) {
                    String fkTableName = resultSet.getString("TABLE_NAME");
                    if (!columnsByTable.containsKey(fkTableName)) {
                        continue;
                    }
                    String fkColumnName = resultSet.getString("COLUMN_NAME");
                    String pkTableName = resultSet.getString("REFERENCED_TABLE_NAME");
                    String pkColumnName = resultSet.getString("REFERENCED_COLUMN_NAME");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, fkColumnName, pkTableName, pkColumnName));
                }
            }
        } catch (SQLException ignored) {
            // REFERENCED columns not available; foreign key validation will be skipped.
        }
    }

    /**
     * Reads constraints using {@code ALL_CONSTRAINTS} and {@code ALL_CONS_COLUMNS} dictionary views.
     */
    private static void readConstraintsFromAllConstraints(
            @Nonnull Connection connection,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, List<DbColumn>> columnsByTable,
            @Nonnull SortedMap<String, List<DbPrimaryKey>> primaryKeysByTable,
            @Nonnull SortedMap<String, List<DbUniqueKey>> uniqueKeysByTable,
            @Nonnull SortedMap<String, List<DbForeignKey>> foreignKeysByTable
    ) {
        // Primary keys and unique constraints.
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT ac.CONSTRAINT_TYPE, ac.CONSTRAINT_NAME,
                           acc.TABLE_NAME, acc.COLUMN_NAME, acc.POSITION
                    FROM ALL_CONSTRAINTS ac
                    JOIN ALL_CONS_COLUMNS acc
                      ON ac.OWNER = acc.OWNER
                      AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME
                    WHERE ac.CONSTRAINT_TYPE IN ('P', 'U')""");
            appendFilter(sql, true, "ac.OWNER", schemaPattern);
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
                while (resultSet.next()) {
                    String tableName = resultSet.getString("TABLE_NAME");
                    if (!columnsByTable.containsKey(tableName)) {
                        continue;
                    }
                    String constraintType = resultSet.getString("CONSTRAINT_TYPE");
                    String constraintName = resultSet.getString("CONSTRAINT_NAME");
                    String columnName = resultSet.getString("COLUMN_NAME");
                    int position = resultSet.getInt("POSITION");
                    if ("P".equals(constraintType)) {
                        primaryKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                                .add(new DbPrimaryKey(tableName, columnName, position));
                    } else {
                        uniqueKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>())
                                .add(new DbUniqueKey(tableName, constraintName, columnName, position));
                    }
                }
            }
        } catch (SQLException ignored) {
            // ALL_CONSTRAINTS not available; primary key and unique key validation will be skipped.
        }
        // Foreign keys.
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT fkc.TABLE_NAME AS FK_TABLE, fkc.COLUMN_NAME AS FK_COLUMN,
                           pkc.TABLE_NAME AS PK_TABLE, pkc.COLUMN_NAME AS PK_COLUMN
                    FROM ALL_CONSTRAINTS fk
                    JOIN ALL_CONS_COLUMNS fkc
                      ON fk.OWNER = fkc.OWNER
                      AND fk.CONSTRAINT_NAME = fkc.CONSTRAINT_NAME
                    JOIN ALL_CONSTRAINTS pk
                      ON fk.R_OWNER = pk.OWNER
                      AND fk.R_CONSTRAINT_NAME = pk.CONSTRAINT_NAME
                    JOIN ALL_CONS_COLUMNS pkc
                      ON pk.OWNER = pkc.OWNER
                      AND pk.CONSTRAINT_NAME = pkc.CONSTRAINT_NAME
                      AND fkc.POSITION = pkc.POSITION
                    WHERE fk.CONSTRAINT_TYPE = 'R'""");
            appendFilter(sql, true, "fk.OWNER", schemaPattern);
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
                while (resultSet.next()) {
                    String fkTableName = resultSet.getString("FK_TABLE");
                    if (!columnsByTable.containsKey(fkTableName)) {
                        continue;
                    }
                    String fkColumnName = resultSet.getString("FK_COLUMN");
                    String pkTableName = resultSet.getString("PK_TABLE");
                    String pkColumnName = resultSet.getString("PK_COLUMN");
                    foreignKeysByTable.computeIfAbsent(fkTableName, k -> new ArrayList<>())
                            .add(new DbForeignKey(fkTableName, fkColumnName, pkTableName, pkColumnName));
                }
            }
        } catch (SQLException ignored) {
            // ALL_CONSTRAINTS FK query not available; foreign key validation will be skipped.
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Sequence discovery strategies.
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * Reads sequences using the specified discovery strategy.
     *
     * <p>If the selected strategy fails, sequence validation is silently skipped.</p>
     */
    private static void readSequences(
            @Nonnull Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, Boolean> sequences,
            @Nonnull SequenceDiscoveryStrategy strategy
    ) {
        switch (strategy) {
            case INFORMATION_SCHEMA -> readSequencesFromInformationSchema(connection, catalog, schemaPattern, sequences);
            case ALL_SEQUENCES -> readSequencesFromAllSequences(connection, schemaPattern, sequences);
            case NONE -> { }
        }
    }

    /**
     * Attempts to read sequences from INFORMATION_SCHEMA.SEQUENCES.
     */
    private static void readSequencesFromInformationSchema(
            @Nonnull Connection connection,
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, Boolean> sequences
    ) {
        try {
            StringBuilder sql = new StringBuilder("SELECT SEQUENCE_NAME FROM INFORMATION_SCHEMA.SEQUENCES");
            boolean hasCondition = false;
            if (catalog != null && !catalog.isEmpty()) {
                sql.append(" WHERE SEQUENCE_CATALOG = '").append(catalog.replace("'", "''")).append("'");
                hasCondition = true;
            }
            if (schemaPattern != null && !schemaPattern.isEmpty()) {
                sql.append(hasCondition ? " AND" : " WHERE");
                sql.append(" SEQUENCE_SCHEMA = '").append(schemaPattern.replace("'", "''")).append("'");
            }
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
                while (resultSet.next()) {
                    sequences.put(resultSet.getString("SEQUENCE_NAME"), Boolean.TRUE);
                }
            }
        } catch (SQLException ignored) {
            // INFORMATION_SCHEMA.SEQUENCES not available; sequence validation will be skipped.
        }
    }

    /**
     * Attempts to read sequences from Oracle's ALL_SEQUENCES dictionary view.
     */
    private static void readSequencesFromAllSequences(
            @Nonnull Connection connection,
            @Nullable String schemaPattern,
            @Nonnull SortedMap<String, Boolean> sequences
    ) {
        try {
            StringBuilder sql = new StringBuilder("SELECT SEQUENCE_NAME FROM ALL_SEQUENCES");
            if (schemaPattern != null && !schemaPattern.isEmpty()) {
                sql.append(" WHERE SEQUENCE_OWNER = '").append(schemaPattern.replace("'", "''")).append("'");
            }
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(sql.toString())) {
                while (resultSet.next()) {
                    sequences.put(resultSet.getString("SEQUENCE_NAME"), Boolean.TRUE);
                }
            }
        } catch (SQLException ignored) {
            // ALL_SEQUENCES not available; sequence validation will be skipped.
        }
    }

    /**
     * Returns whether a table or view with the given name exists in the schema.
     *
     * @param tableName the table name (case-insensitive).
     * @return {@code true} if the table exists.
     */
    public boolean tableExists(@Nonnull String tableName) {
        return columnsByTable.containsKey(tableName);
    }

    /**
     * Returns the column metadata for the given table and column name.
     *
     * @param tableName  the table name (case-insensitive).
     * @param columnName the column name (case-insensitive).
     * @return the column metadata, or empty if not found.
     */
    public Optional<DbColumn> getColumn(@Nonnull String tableName, @Nonnull String columnName) {
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
    public List<DbColumn> getColumns(@Nonnull String tableName) {
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
    public List<DbPrimaryKey> getPrimaryKeys(@Nonnull String tableName) {
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
    public List<DbUniqueKey> getUniqueKeys(@Nonnull String tableName) {
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
    public List<DbForeignKey> getForeignKeys(@Nonnull String tableName) {
        List<DbForeignKey> foreignKeys = foreignKeysByTable.get(tableName);
        if (foreignKeys == null) {
            return List.of();
        }
        return List.copyOf(foreignKeys);
    }

    /**
     * Returns whether a sequence with the given name exists in the schema.
     *
     * @param sequenceName the sequence name (case-insensitive).
     * @return {@code true} if the sequence exists.
     */
    public boolean sequenceExists(@Nonnull String sequenceName) {
        return sequences.containsKey(sequenceName);
    }
}
