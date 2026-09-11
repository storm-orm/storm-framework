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
package st.orm;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The navigational part of a {@link Metamodel}: everything needed to locate a field within the entity graph and use it
 * in a query (filter, join, order, group, select), without exposing value extraction.
 *
 * <p>{@link Metamodel} extends {@code Navigable} and adds the value-based operations ({@code getValue}, {@code isSame},
 * {@code isIdentical}). Splitting these apart lets a path navigate <em>beyond a {@link Ref} foreign key</em>: the
 * referenced entity is not loaded, so such a path is {@code Navigable} (queryable) but not a full {@code Metamodel}
 * (not value-extractable). Query methods that only need to locate a column accept {@code Navigable}; methods that read
 * a value from a record (for example {@code getResultGroupedBy}) require a {@code TypedMetamodel}, so applying them to a
 * reference-crossing path fails to compile rather than at runtime.</p>
 *
 * @param <T> the root table type (the entity from which the path originates).
 * @param <E> the field type of the designated element.
 * @since 1.13
 */
public interface Navigable<T extends Data, E extends @Nullable Object> {

    /**
     * Returns the canonical metamodel for the field represented by {@code this}. The result captures only the table
     * type and field, independent of position within a table graph, which makes it suitable for equality checks and
     * column resolution.
     *
     * @return the canonical metamodel for this navigable.
     */
    default Metamodel<? extends Data, E> canonical() {
        return Metamodel.of(tableType(), field());
    }

    /**
     * Returns {@code true} if this navigable corresponds to a database column, {@code false} otherwise (for example the
     * root navigable or an inline record). A foreign key column is also a column.
     *
     * @return {@code true} if this navigable maps to a column, {@code false} otherwise.
     */
    boolean isColumn();

    /**
     * Returns {@code true} if this navigable corresponds to an inline record, {@code false} otherwise.
     *
     * @return {@code true} if this navigable maps to an inline record, {@code false} otherwise.
     */
    boolean isInline();

    /**
     * Returns the root table type. This is typically the table specified in the FROM clause of a query.
     *
     * @return the root table type.
     */
    Class<T> root();

    /**
     * Returns the navigable of the table that holds the column this navigable points to. For an inline record the table
     * is the parent table; for the root navigable the root table is returned.
     *
     * @return the navigable of the table that holds the column this navigable points to.
     */
    Navigable<T, ? extends Data> table();

    /**
     * Returns the type of the table that holds the column this navigable points to.
     *
     * @return the type of the table that holds the column this navigable points to.
     */
    default Class<? extends Data> tableType() {
        return table().fieldType();
    }

    /**
     * Returns the path to the database table.
     *
     * @return path to the database table.
     */
    String path();

    /**
     * Returns the field type of the designated element.
     *
     * @return the field type of the designated element.
     */
    Class<E> fieldType();

    /**
     * Returns the field name.
     *
     * @return field name.
     */
    String field();

    /**
     * Returns the field path.
     *
     * @return field path.
     */
    default String fieldPath() {
        String path = path();
        String field = field();
        return path.isEmpty() ? field : field.isEmpty() ? path : "%s.%s".formatted(path, field);
    }

    /**
     * Returns a flat list of leaf metamodels for this navigable. An inline record is recursively expanded into its
     * individual column metamodels; any other navigable returns a singleton list, because it names its own
     * column(s): an entity node names the foreign key column(s) that reference it, and a scalar names its column.
     * The returned metamodels keep their position in the graph, so they are not canonical.
     *
     * <p>The query builder's {@code groupBy} and {@code orderBy} metamodel overloads do not use this expansion: they
     * resolve a path to the same columns a predicate on that path would use, so a multi-column path (a compound
     * foreign key, an inline record) expands during column resolution rather than here.</p>
     *
     * @return a list of leaf metamodels.
     */
    default List<Metamodel<T, ?>> flatten() {
        return MetamodelHelper.flatten(this);
    }

    /**
     * Returns this navigable as a metamodel that can locate a column. A full metamodel is returned as-is; a
     * navigation-only node (one that navigates beyond a {@link Ref}) is rebuilt into a resolvable metamodel for its
     * path, so it can name a column in WHERE, ORDER BY, GROUP BY and HAVING.
     *
     * <p>A rebuilt metamodel is query-only: it names a column but cannot extract a value from a record, which is why
     * value operations keep taking {@link Metamodel} rather than {@code Navigable}.</p>
     *
     * @return this navigable as a metamodel.
     * @since 1.13
     */
    @SuppressWarnings("unchecked")
    default Metamodel<T, E> asMetamodel() {
        return this instanceof Metamodel<?, ?>
                ? (Metamodel<T, E>) this
                : Metamodel.of(root(), fieldPath());
    }
}
