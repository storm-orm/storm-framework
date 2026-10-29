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

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbForeignKey;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.DatabaseSchema.DbPrimaryKey;

/**
 * The indexes of a database as a schema read found them: for every table, the columns its indexes start with.
 *
 * <p>A catalog belongs to the database a data source connects to, not to a template: every template on the data
 * source, and the schema validation an integration runs on it, read and see the same database. It is published when
 * the schema is read, by {@code loadSchema()} or by schema validation, and is replaced as a whole by the next read.
 * Nothing reads the database to answer a lookup; the slow statement log looks a table up only for a slow execution,
 * so a lookup is rare and the registry's lock is never contended on a hot path.</p>
 *
 * <p>Names are compared case-insensitively, as {@link DatabaseSchema} compares them.</p>
 *
 * @since 1.16
 */
final class IndexCatalog {

    /**
     * The indexes of one table, as far as the index checks need them: the columns they start with, the primary key
     * included, and the foreign keys of other tables that reference the table without an index to serve them. An
     * index that starts with an expression, such as {@code lower(email)}, is left out: it serves only a statement that
     * compares the same expression, never a comparison of the bare column. An index of another kind, such as a
     * full-text index, counts for its column, whether or not it serves the comparison.
     *
     * @param leadingColumns the columns the table's indexes start with, its primary key included.
     * @param unindexedReferences the foreign keys that reference the table and that no index of their own table
     *                            starts with, which a delete from the table checks by reading their table in full.
     * @param fewValuedColumns the columns whose type holds few distinct values, by how the type reads in advice.
     * @param indexes the table's indexes with their columns in order, the primary key first.
     * @param columnBytes the bytes a value of each column takes in an index key at most, by its declared size, for
     *                    the columns whose type sizes that way.
     */
    record TableIndexes(SortedSet<String> leadingColumns, List<UnindexedReference> unindexedReferences,
                        SortedMap<String, String> fewValuedColumns, List<IndexColumns> indexes,
                        SortedMap<String, Integer> columnBytes) {
        TableIndexes {
            leadingColumns = Collections.unmodifiableSortedSet(leadingColumns);
            unindexedReferences = List.copyOf(unindexedReferences);
            fewValuedColumns = Collections.unmodifiableSortedMap(fewValuedColumns);
            indexes = List.copyOf(indexes);
            columnBytes = Collections.unmodifiableSortedMap(columnBytes);
        }

        TableIndexes(SortedSet<String> leadingColumns, List<UnindexedReference> unindexedReferences) {
            this(leadingColumns, unindexedReferences, new TreeMap<>(String.CASE_INSENSITIVE_ORDER), List.of(),
                    new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        }

        /**
         * Returns the bytes a key on the given columns takes at most, by their declared sizes; a column whose type
         * is not sized that way counts nothing.
         */
        int keyBytes(List<String> columns) {
            int bytes = 0;
            for (var column : columns) {
                bytes += columnBytes.getOrDefault(column, 0);
            }
            return bytes;
        }

        /**
         * Returns whether an index starts with the given column.
         */
        boolean leadsWith(String column) {
            return leadingColumns.contains(column);
        }

        /**
         * Returns how the type of a column with few distinct values reads, such as {@code an ENUM}, or {@code null}
         * for a column of any other type.
         */
        @Nullable String fewValued(String column) {
            return fewValuedColumns.get(column);
        }
    }

    /**
     * The columns of one index, in their order, up to the first part that is an expression rather than a column: a
     * comparison of a bare column cannot use the index past that part.
     *
     * @param name the index name, or {@code null} for the primary key.
     * @param columns the columns, at least one.
     * @param unique whether the index holds one row per value of its columns, as the primary key does.
     */
    record IndexColumns(@Nullable String name, List<String> columns, boolean unique) {
        IndexColumns {
            columns = List.copyOf(columns);
        }
    }

    /**
     * A foreign key that references a table, with no index of its own table starting with any of its columns.
     *
     * @param table the referencing table, in the schema of the table it references.
     * @param columns the columns of the foreign key, in key order.
     */
    record UnindexedReference(String table, List<String> columns) {
        UnindexedReference {
            columns = List.copyOf(columns);
        }
    }

    private static final Map<DataSource, IndexCatalog> CATALOGS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Schema as the models name it ({@code ""} for the connection's default schema) to table to its indexes. */
    private final SortedMap<String, SortedMap<String, TableIndexes>> tablesBySchema;
    private final int maxKeyBytes;

    private IndexCatalog(SortedMap<String, SortedMap<String, TableIndexes>> tablesBySchema, int maxKeyBytes) {
        this.tablesBySchema = tablesBySchema;
        this.maxKeyBytes = maxKeyBytes;
    }

    /**
     * Builds a catalog from the schemas a read found, keyed by the schema as the models name it: {@code ""} for the
     * connection's default schema. A table whose indexes could not be read is left out, so no lookup reports it as
     * having none.
     *
     * @param schemas the schemas read, by the name the models use for them.
     * @return the catalog.
     */
    static IndexCatalog of(Map<String, DatabaseSchema> schemas) {
        return of(schemas, 0);
    }

    /**
     * Builds a catalog from the schemas a read found, for a database that checks the declared sizes of an index key's
     * columns against a limit.
     *
     * @param schemas the schemas read, by the name the models use for them.
     * @param maxKeyBytes the most bytes an index key may hold, {@code 0} where the database checks no declared size.
     * @return the catalog.
     * @see st.orm.core.template.SqlDialect#maxIndexKeyBytes()
     */
    static IndexCatalog of(Map<String, DatabaseSchema> schemas, int maxKeyBytes) {
        SortedMap<String, SortedMap<String, TableIndexes>> tablesBySchema = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        schemas.forEach((schemaName, schema) -> {
            SortedMap<String, SortedSet<String>> leadingColumnsByTable = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String tableName : schema.tableNames()) {
                if (!schema.isDiscovered(tableName, ConstraintKind.INDEX) || schema.isView(tableName)) {
                    continue;
                }
                SortedSet<String> leadingColumns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (DbIndex index : schema.getIndexes(tableName)) {
                    if (index.ordinalPosition() == 1 && index.columnName() != null) {
                        leadingColumns.add(index.columnName());
                    }
                }
                // The primary key is indexed on every database, whether or not the index read lists the index that
                // enforces it.
                for (DbPrimaryKey primaryKey : schema.getPrimaryKeys(tableName)) {
                    if (primaryKey.keySeq() == 1) {
                        leadingColumns.add(primaryKey.columnName());
                    }
                }
                leadingColumnsByTable.put(tableName, leadingColumns);
            }
            var unindexedReferences = unindexedReferences(schema, leadingColumnsByTable);
            SortedMap<String, TableIndexes> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            leadingColumnsByTable.forEach((tableName, leadingColumns) -> tables.put(tableName,
                    new TableIndexes(leadingColumns, unindexedReferences.getOrDefault(tableName, List.of()),
                            fewValuedColumns(schema, tableName), indexColumns(schema, tableName),
                            columnBytes(schema, tableName))));
            tablesBySchema.put(schemaName, tables);
        });
        return new IndexCatalog(tablesBySchema, maxKeyBytes);
    }

    /**
     * Returns the most bytes an index key may hold on the database, or {@code 0} where the database checks no
     * declared size against a limit.
     */
    int maxKeyBytes() {
        return maxKeyBytes;
    }

    /**
     * Returns the bytes a value of each column of a table takes in an index key at most, by the column's declared
     * size, as a database that limits the key by declared sizes counts them: a character column by the bytes its
     * length takes in its character set, a binary column by its length, a number, date or time by its storage, and
     * an enumeration by the one or two bytes it is stored as. A column of a type not sized that way is left out.
     */
    private static SortedMap<String, Integer> columnBytes(DatabaseSchema schema, String tableName) {
        SortedMap<String, Integer> bytes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var column : schema.getColumns(tableName)) {
            int size = keyBytes(column);
            if (size > 0) {
                bytes.put(column.columnName(), size);
            }
        }
        return bytes;
    }

    /** Returns the bytes a value of the column takes in an index key at most, or {@code 0} where its type is not sized. */
    private static int keyBytes(DatabaseSchema.DbColumn column) {
        String typeName = column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT);
        if (typeName.startsWith("ENUM")) {
            return 2;
        }
        if (typeName.startsWith("SET")) {
            return 8;
        }
        if (typeName.equals("UUID") || typeName.equals("UNIQUEIDENTIFIER")) {
            return 16;
        }
        return switch (column.dataType()) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB ->
                    column.charOctetLength() > 0 ? column.charOctetLength() : column.columnSize() * 4;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> column.columnSize();
            case Types.BIT, Types.BOOLEAN, Types.TINYINT -> 1;
            case Types.SMALLINT -> 2;
            case Types.INTEGER, Types.REAL, Types.DATE -> 4;
            case Types.BIGINT, Types.FLOAT, Types.DOUBLE, Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP,
                 Types.TIMESTAMP_WITH_TIMEZONE -> 8;
            // Four bytes per nine digits, as a decimal is packed at most.
            case Types.DECIMAL, Types.NUMERIC -> (column.columnSize() + 8) / 9 * 4;
            default -> 0;
        };
    }

    /**
     * Returns the indexes of a table with their columns in order, the primary key first. An index's columns end at
     * its first expression part, and an index that starts with one is left out, as it serves no comparison of a bare
     * column.
     */
    private static List<IndexColumns> indexColumns(DatabaseSchema schema, String tableName) {
        var indexes = new ArrayList<IndexColumns>();
        var primaryKey = schema.getPrimaryKeys(tableName).stream()
                .sorted(Comparator.comparingInt(DbPrimaryKey::keySeq))
                .map(DbPrimaryKey::columnName)
                .toList();
        if (!primaryKey.isEmpty()) {
            indexes.add(new IndexColumns(null, primaryKey, true));
        }
        Map<String, List<DbIndex>> byName = new LinkedHashMap<>();
        for (DbIndex index : schema.getIndexes(tableName)) {
            byName.computeIfAbsent(index.indexName(), ignore -> new ArrayList<>()).add(index);
        }
        byName.forEach((name, parts) -> {
            parts.sort(Comparator.comparingInt(DbIndex::ordinalPosition));
            var columns = new ArrayList<String>();
            boolean complete = true;
            for (var part : parts) {
                if (part.columnName() == null) {
                    complete = false;
                    break;
                }
                columns.add(part.columnName());
            }
            if (!columns.isEmpty()) {
                // A unique index identifies a row by all of its parts, so one cut short at an expression, or one that
                // holds only some rows, does not.
                boolean unique = complete && parts.getFirst().unique() && !parts.getFirst().partial();
                indexes.add(new IndexColumns(name, columns, unique));
            }
        });
        return indexes;
    }

    /**
     * Returns the columns of a table whose type holds few distinct values, by how the type reads in advice: an enum,
     * a boolean or a bit. A condition on such a column alone rarely selects few rows, which no row count is needed to
     * say; a rare value can still be selective, so the advice says an index on it is often, not always, passed over.
     */
    private static SortedMap<String, String> fewValuedColumns(DatabaseSchema schema, String tableName) {
        SortedMap<String, String> columns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var column : schema.getColumns(tableName)) {
            String typeName = column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT);
            if (typeName.startsWith("ENUM")) {
                columns.put(column.columnName(), "an ENUM");
            } else if (column.dataType() == Types.BOOLEAN || typeName.equals("BOOLEAN") || typeName.equals("BOOL")) {
                columns.put(column.columnName(), "a BOOLEAN");
            } else if (column.dataType() == Types.BIT) {
                columns.put(column.columnName(), "a BIT");
            }
        }
        return columns;
    }

    /**
     * Returns the foreign keys no index serves, by the table they reference. A foreign key counts as served when an
     * index of its table starts with any of its columns: the check then reads the rows of one value of that column
     * rather than the table. A table whose indexes or foreign keys could not be read contributes none, so no delete is
     * advised on what the read did not see.
     *
     * <p>The columns of a compound foreign key share its constraint name. A database that does not report the name
     * leaves the columns of one table that reference one table as one key, which an index on any of them serves, so
     * an unnamed key is never advised for a column another index covers.</p>
     *
     * @param schema the schema read.
     * @param leadingColumnsByTable the columns each table's indexes start with, for the tables whose indexes were
     *                              read.
     * @return the foreign keys no index serves, by referenced table.
     */
    private static Map<String, List<UnindexedReference>> unindexedReferences(
            DatabaseSchema schema,
            SortedMap<String, SortedSet<String>> leadingColumnsByTable
    ) {
        SortedMap<String, List<UnindexedReference>> references = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        leadingColumnsByTable.forEach((tableName, leadingColumns) -> {
            if (!schema.isDiscovered(tableName, ConstraintKind.FOREIGN_KEY)) {
                return;
            }
            Map<String, List<DbForeignKey>> keys = new LinkedHashMap<>();
            for (DbForeignKey foreignKey : schema.getForeignKeys(tableName)) {
                String name = foreignKey.constraintName();
                String key = name == null || name.isBlank()
                        ? "\0" + foreignKey.pkTableName().toLowerCase(Locale.ROOT)
                        : name;
                keys.computeIfAbsent(key, ignore -> new ArrayList<>()).add(foreignKey);
            }
            for (List<DbForeignKey> columns : keys.values()) {
                if (columns.stream().anyMatch(column -> leadingColumns.contains(column.fkColumnName()))) {
                    continue;
                }
                references.computeIfAbsent(columns.getFirst().pkTableName(), ignore -> new ArrayList<>())
                        .add(new UnindexedReference(tableName,
                                columns.stream().map(DbForeignKey::fkColumnName).distinct().toList()));
            }
        });
        return references;
    }

    /**
     * Returns the indexes of the given table, or {@code null} when the catalog does not know the table: a schema that
     * was not read, a table created after the read, or one whose indexes could not be read.
     *
     * @param schema the table's schema as its model names it, {@code ""} for the default schema.
     * @param table the table name.
     * @return the table's indexes, or {@code null} when unknown.
     */
    @Nullable TableIndexes find(String schema, String table) {
        var tables = tablesBySchema.get(schema);
        return tables == null ? null : tables.get(table);
    }

    /**
     * Publishes the catalog of the database the given data source connects to, replacing the one an earlier read
     * published for the schemas this read covers.
     *
     * @param dataSource the data source the schemas were read through.
     * @param catalog the catalog.
     */
    static void publish(DataSource dataSource, IndexCatalog catalog) {
        CATALOGS.merge(dataSource, catalog, (previous, next) -> {
            SortedMap<String, SortedMap<String, TableIndexes>> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            merged.putAll(previous.tablesBySchema);
            merged.putAll(next.tablesBySchema);
            return new IndexCatalog(merged, next.maxKeyBytes);
        });
    }

    /**
     * Returns the catalog published for the given data source, or {@code null} when its schema was not read.
     *
     * @param dataSource the data source, or {@code null} for a template that has none.
     * @return the catalog, or {@code null}.
     */
    static @Nullable IndexCatalog published(@Nullable DataSource dataSource) {
        return dataSource == null ? null : CATALOGS.get(dataSource);
    }
}
