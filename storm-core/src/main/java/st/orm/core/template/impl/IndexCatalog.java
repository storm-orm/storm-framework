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

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.DatabaseSchema.DbPrimaryKey;

/**
 * The indexes of a database as a schema read found them: for every table, the columns its indexes start with.
 *
 * <p>A catalog belongs to the database a data source connects to, not to a template: every template on the data
 * source, and the schema validation an integration runs on it, read and see the same database. It is published when
 * the schema is read, by {@code readSchema()} or by schema validation, and is replaced as a whole by the next read.
 * Nothing reads the database to answer a lookup; the slow statement log looks a table up only for a slow execution,
 * so a lookup is rare and the registry's lock is never contended on a hot path.</p>
 *
 * <p>Names are compared case-insensitively, as {@link DatabaseSchema} compares them.</p>
 *
 * @since 1.16
 */
final class IndexCatalog {

    /**
     * The indexes of one table, as far as the missing-index check needs them: the columns they start with, the
     * primary key included. An index that starts with an expression, such as {@code lower(email)}, is left out: it
     * serves only a statement that compares the same expression, never a comparison of the bare column. An index of
     * another kind, such as a full-text index, counts for its column, whether or not it serves the comparison.
     *
     * @param leadingColumns the columns the table's indexes start with, its primary key included.
     */
    record TableIndexes(SortedSet<String> leadingColumns) {
        TableIndexes {
            leadingColumns = Collections.unmodifiableSortedSet(leadingColumns);
        }

        /**
         * Returns whether an index starts with the given column.
         */
        boolean leadsWith(String column) {
            return leadingColumns.contains(column);
        }
    }

    private static final Map<DataSource, IndexCatalog> CATALOGS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Schema as the models name it ({@code ""} for the connection's default schema) to table to its indexes. */
    private final SortedMap<String, SortedMap<String, TableIndexes>> tablesBySchema;

    private IndexCatalog(SortedMap<String, SortedMap<String, TableIndexes>> tablesBySchema) {
        this.tablesBySchema = tablesBySchema;
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
        SortedMap<String, SortedMap<String, TableIndexes>> tablesBySchema = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        schemas.forEach((schemaName, schema) -> {
            SortedMap<String, TableIndexes> tables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
                tables.put(tableName, new TableIndexes(leadingColumns));
            }
            tablesBySchema.put(schemaName, tables);
        });
        return new IndexCatalog(tablesBySchema);
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
            return new IndexCatalog(merged);
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
