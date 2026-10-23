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
package st.orm.template.impl;

import java.util.List;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.SqlTemplateException;
import st.orm.template.Column;
import st.orm.template.Model;

/**
 * Represents the model of an entity.
 */
public class ModelImpl<E extends Data, ID> implements Model<E, ID> {

    private final st.orm.core.template.impl.ModelImpl<E, ID> core;
    private final List<Column> columns;
    private final List<Column> declaredColumns;
    private final List<Column> queryColumns;

    public ModelImpl(st.orm.core.template.impl.ModelImpl<E, ID> core) {
        this.core = core;
        this.columns = core.columns().stream()
                .map(ColumnImpl::new)
                .map(Column.class::cast)
                .toList();
        this.declaredColumns = core.declaredColumns().stream()
                .map(ColumnImpl::new)
                .map(Column.class::cast)
                .toList();
        this.queryColumns = core.queryColumns().stream()
                .map(ColumnImpl::new)
                .map(Column.class::cast)
                .toList();
    }

    /**
     * Returns the schema, or empty String if the schema is not specified.
     *
     * @return the schema, or empty String if the schema is not specified.
     */
    @Override
    public String schema() {
        return core.schema();
    }

    /**
     * Returns the name of the table or view.
     *
     * @return the name of the table or view.
     */
    @Override
    public String name() {
        return core.name();
    }

    /**
     * Returns {code true} if the specified primary key represents a default value, {@code false} otherwise.
     *
     * <p>This method is used to check if the primary key of the entity is a default value. This is useful when
     * determining if the entity is new or has been persisted before.</p>
     *
     * @param pk primary key to check.
     * @return {code true} if the specified primary key represents a default value, {@code false} otherwise.
     * @since 1.2
     */
    @Override
    public boolean isDefaultPrimaryKey(@Nullable ID pk) {
        return core.isDefaultPrimaryKey(pk);
    }

    /**
     * Returns the type of the entity or projection.
     *
     * @return the type of the entity or projection.
     */
    @Override
    public Class<E> type() {
        return core.type();
    }

    /**
     * Returns the type of the primary key.
     *
     * @return the type of the primary key.
     */
    @Override
    public Class<ID> primaryKeyType() {
        return core.primaryKeyType();
    }

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
    @Override
    public List<Column> columns() {
        return columns;
    }

    /**
     * Returns the columns declared directly on this model.
     *
     * <p>Relationship expansion is not applied. The returned list preserves declared order.</p>
     *
     * <p><strong>Index semantics:</strong> {@link st.orm.core.template.Column#index()} refers to the index in {@link #columns()},
     * not in this list.</p>
     *
     * @return the declared columns of this model.
     * @since 1.8
     */
    @Override
    public List<Column> declaredColumns() {
        return declaredColumns;
    }

    @Override
    public List<Column> queryColumns() {
        return queryColumns;
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
    @Override
    public void forEachValue(List<Column> columns, E record, BiConsumer<Column, Object> consumer) throws SqlTemplateException {
        core.forEachValue(columns.stream().map(it -> ((ColumnImpl) it).core()).toList(),
                record,
                (column, value) -> consumer.accept(new ColumnImpl(column), value));
    }
}
