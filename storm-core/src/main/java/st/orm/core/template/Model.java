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
package st.orm.core.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.SqlTemplateException;
import st.orm.mapping.RecordType;

/**
 * Represents the model of an entity or projection.
 *
 * <p>A model describes the structural mapping between a record type and its database representation.
 * It exposes metadata such as schema, table/view name, and columns, and provides mechanisms to extract
 * JDBC-ready values from entity or projection instances.</p>
 *
 * @param <E> the type of the entity or projection.
 * @param <ID> the type of the primary key, or {@code Void} for projections without a primary key.
 */
public interface Model<E extends Data, ID> {

    /**
     * Returns the schema, or an empty string if the schema is not specified.
     *
     * @return the schema, or an empty string if not specified.
     */
    String schema();

    /**
     * Returns the name of the table or view.
     *
     * @return the table or view name.
     */
    String name();

    /**
     * Returns the qualified name of the table or view, including schema and quoting.
     *
     * @param dialect the SQL dialect used for quoting.
     * @return the qualified table or view name.
     */
    String qualifiedName(SqlDialect dialect);

    /**
     * Returns the Java type of the entity or projection.
     *
     * @return the record type.
     */
    Class<E> type();

    /**
     * Returns {@code true} if this model represents a joined inheritance entity (Joined Table inheritance),
     * where the base table has common fields and each permitted subclass has its own extension table.
     *
     * @return {@code true} if this model uses joined table inheritance.
     * @since 1.9
     */
    boolean isJoinedInheritance();

    /**
     * Returns the type of the primary key.
     *
     * @return the primary key type, or {@code Void.class} if no primary key exists.
     */
    Class<ID> primaryKeyType();

    /**
     * Returns all columns of this model, including columns of expanded relationships.
     *
     * <p>The returned list is deterministic and stable. Declared columns are processed in declaration order.
     * Foreign relationships are expanded depth-first at the position of the foreign-key column.</p>
     *
     * <p>Expanded columns always correspond to physical columns of the parent record. Join keys come from the
     * parent row, not the referenced row.</p>
     *
     * @return all columns of this model, including expanded relations.
     * @since 1.7
     */
    List<Column> columns();

    /**
     * Returns the columns declared directly on this model.
     *
     * <p>Relationship expansion is not applied. The returned list preserves declared order.</p>
     *
     * <p><strong>Index semantics:</strong> {@link Column#index()} refers to the index in {@link #columns()},
     * not in this list.</p>
     *
     * @return the declared columns of this model.
     * @since 1.8
     */
    List<Column> declaredColumns();

    /**
     * Returns the query columns declared on this model: the columns a query names but the record does not carry,
     * declared with {@link st.orm.QueryColumn}. They are not among {@link #columns()} or {@link #declaredColumns()},
     * so a select, insert or update never names them; {@link #getColumns(Metamodel)} resolves them, for a query
     * column of this type and for one of a type reached through an expanded relation.
     *
     * <p>A query column has no record field: its {@link Column#index()} is {@code 0}, and it is neither insertable
     * nor updatable.</p>
     *
     * @return the query columns declared on this model, in declaration order.
     * @since 1.16
     */
    List<Column> queryColumns();

    /**
     * Returns the record type metadata.
     *
     * @return the record type.
     * @since 1.7
     */
    RecordType recordType();

    /**
     * Returns {@code true} if the given primary key represents a default value.
     *
     * @param pk the primary key to check.
     * @return {@code true} if the primary key is a default value.
     * @since 1.2
     */
    boolean isDefaultPrimaryKey(@Nullable ID pk);

    /**
     * Returns the metamodel of the primary key, if present.
     *
     * @return the primary key metamodel, or empty if none exists.
     * @since 1.7
     */
    Optional<Metamodel<E, ID>> getPrimaryKeyMetamodel();

    /**
     * Resolves all columns associated with the given metamodel.
     *
     * @param metamodel the metamodel identifying one or more columns.
     * @return the resolved columns.
     * @throws SqlTemplateException if the metamodel is invalid or not present.
     * @since 1.7
     */
    List<Column> getColumns(Metamodel<?, ?> metamodel) throws SqlTemplateException;

    /**
     * Resolves a single column for the given metamodel.
     *
     * @param metamodel the metamodel identifying exactly one column.
     * @return the resolved column.
     * @throws SqlTemplateException if zero or multiple columns are resolved.
     * @since 1.7
     */
    default Column getSingleColumn(Metamodel<?, ?> metamodel) throws SqlTemplateException {
        var columns = getColumns(metamodel);
        if (columns.size() != 1) {
            throw new SqlTemplateException("Expected exactly one column for metamodel: %s.%s.%s"
                    .formatted(metamodel.fieldType(), metamodel.path(), metamodel.field()));
        }
        return columns.getFirst();
    }

    /**
     * Iterates over the values of the given columns for the supplied record.
     *
     * <p>Values are JDBC-ready. Conversions have already been applied.</p>
     *
     * <p><strong>Ordering requirement:</strong> {@code columns} must be ordered according to the model's
     * column order (usually {@link #columns()} or {@link #declaredColumns()}).</p>
     *
     * @param columns the columns to extract values for, ordered in model column order.
     * @param record the record to extract values from.
     * @param consumer receives each column and its extracted value.
     * @throws SqlTemplateException if extraction fails.
     * @since 1.8
     */
    void forEachValue(List<Column> columns,
                      E record,
                      BiConsumer<Column, Object> consumer)
            throws SqlTemplateException;

    /**
     * Collects column values into an ordered map.
     *
     * <p><strong>Ordering requirement:</strong> {@code columns} must be ordered according to the model's
     * column order (usually {@link #columns()} or {@link #declaredColumns()}).</p>
     *
     * @param columns the columns to extract values for.
     * @param record the record to extract values from.
     * @return a map of columns to extracted values.
     * @throws SqlTemplateException if extraction fails.
     * @since 1.8
     */
    default SequencedMap<Column, Object> values(List<Column> columns,
                                                E record)
            throws SqlTemplateException {
        var values = new LinkedHashMap<Column, Object>();
        forEachValue(columns, record, values::put);
        return values;
    }

    /**
     * Collects all column values into an ordered map.
     *
     * <p>This method is equivalent to {@link #values(List, Data)} with {@link #columns()}.</p>
     *
     * @param record the record to extract values from.
     * @return a map of columns to extracted values.
     * @throws SqlTemplateException if extraction fails.
     * @since 1.8
     */
    default SequencedMap<Column, Object> values(E record) throws SqlTemplateException {
        return values(columns(), record);
    }

    /**
     * Collects declared column values into an ordered map.
     *
     * <p>The returned map preserves the iteration order of {@link #declaredColumns()}.</p>
     *
     * @param record the record to extract values from.
     * @return a map of declared columns to extracted values.
     * @throws SqlTemplateException if extraction fails.
     * @since 1.8
     */
    default SequencedMap<Column, Object> declaredValues(E record)
            throws SqlTemplateException {
        return values(declaredColumns(), record);
    }

    /**
     * Validates that foreign key columns in the given record do not contain default primary key values.
     *
     * <p>A default primary key value (such as {@code 0} for {@code Integer}) on a foreign key reference typically
     * indicates that the referenced entity has not been persisted yet. This method should be called before inserting
     * or updating a record.</p>
     *
     * @param columns the columns to validate.
     * @param record the record to validate.
     * @throws SqlTemplateException if extraction fails.
     * @since 1.10
     */
    void validateForeignKeys(List<Column> columns, E record) throws SqlTemplateException;

    /**
     * Finds a unique metamodel referring to the given entity type.
     *
     * @param type the referenced entity type.
     * @return the matching metamodel, or empty if not found or ambiguous.
     * @since 1.8
     */
    Optional<Metamodel<E, ?>> findMetamodel(Class<? extends Data> type);

    /**
     * Extracts values for a given metamodel and object.
     *
     * <p>If {@code object} is a {@link Data} instance, the model extracts its id and maps columns from that id.
     * Otherwise, {@code object} must be compatible with {@code metamodel.fieldType()}.</p>
     *
     * <p>If the resolved value is {@code null} and the metamodel resolves to multiple columns, each column is
     * emitted with {@code null}.</p>
     *
     * @param metamodel the metamodel identifying the column(s).
     * @param object the value holder or entity instance.
     * @param consumer receives each resolved column and value.
     * @throws SqlTemplateException if extraction fails.
     * @since 1.8
     */
    void forEachValue(Metamodel<E, ?> metamodel,
                      Object object,
                      BiConsumer<Column, Object> consumer)
            throws SqlTemplateException;

}
