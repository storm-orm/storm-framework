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

import static java.util.Optional.ofNullable;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Implementation that is used by the generated models.
 *
 * @param <T> the primary table type.
 * @param <E> the field type of the designated element.
 * @param <V> the value type of the designated element.
 * @since 1.2
 */
public abstract class AbstractMetamodel<T extends Data, E, V extends @Nullable Object> implements TypedMetamodel<T, E, V> {

    private final Class<E> fieldType;
    private final String path;
    private final String field;
    private final boolean inline;
    private final Metamodel<T, ?> parent;
    private final boolean isColumn;

    public AbstractMetamodel(Class<E> fieldType) {
        this(fieldType, "", "", false, null, false);
    }

    public AbstractMetamodel(Class<E> fieldType,
                             String path) {
        this(fieldType, path, "", false, null, true);
    }

    public AbstractMetamodel(Class<E> fieldType,
                             String path,
                             String field,
                             boolean inline,
                             @Nullable Metamodel<T, ?> parent) {
        this(fieldType, path, field, inline, parent, !inline && !field.isEmpty());
    }

    protected AbstractMetamodel(Class<E> fieldType,
                                String path,
                                String field,
                                boolean inline,
                                @Nullable Metamodel<T, ?> parent,
                                boolean isColumn) {
        this.fieldType = fieldType;
        this.path = path;
        this.field = field;
        this.inline = inline;
        this.parent = parent;
        this.isColumn = isColumn;
    }

    /**
     * Equality is based on {@link #fieldType()} of {@link #table()}, {@link #path()} and {@link #field()}. A
     * {@link SqlFunction} call on the same field is a different expression and never equal.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Metamodel<?, ?> other) || SqlFunction.isCall(other)) return false;
        return Objects.equals(this.table().fieldType(), other.table().fieldType())
                && Objects.equals(this.path, other.path())
                && Objects.equals(this.field, other.field());
    }

    /**
     * Hash code is based on {@link #fieldType()} of {@link #table()}, {@link #path()} and {@link #field()}.
     *
     * <p>The hash is cached: metamodel instances are immutable and are hashed on every compilation-key lookup. The
     * benign race follows the {@link String#hashCode()} pattern, recomputing only when the hash happens to be zero.</p>
     */
    @Override
    public final int hashCode() {
        int cached = hash;
        if (cached == 0) {
            cached = Objects.hash(table().fieldType(), path, field);
            hash = cached;
        }
        return cached;
    }

    private int hash;

    /**
     * Returns {@code true} if the metamodel corresponds to a database column, returns {@code false} otherwise, for
     * example, if the metamodel refers to the root metamodel or an inline record.
     *
     * <p>Note that a column can also be a table, for example, in the case of a foreign key.</p>
     *
     * @return {@code true} if this metamodel maps to a column, {@code false} otherwise.
     */
    @Override
    public boolean isColumn() {
        return isColumn;
    }

    /**
     * Returns the root metamodel. This is typically the table specified in the FROM clause of a query.
     *
     * @return the root metamodel.
     */
    @Override
    public Class<T> root() {
        //noinspection unchecked
        return parent()
                .map(Metamodel::root)
                .orElseGet(() -> (Class<T>) fieldType());
    }

    /**
     * Returns the table that holds the column to which this metamodel is pointing. If the metamodel points to an
     * inline record, the table is the parent table of the inline record. If the metamodel is a root metamodel, the
     * root table is returned.
     *
     * @return the table that holds the column to which this metamodel is pointing.
     */
    @Override
    public Metamodel<T, ? extends Data> table() {
        var parent = parent().orElse(null);
        if (parent == null) {
            //noinspection unchecked
            return (Metamodel<T, ? extends Data>) this;
        }
        if (parent.isInline()) {
            return parent.table();
        }
        //noinspection unchecked
        return (Metamodel<T, ? extends Data>) parent;
    }

    private Optional<Metamodel<T, ?>> parent() {
        return ofNullable(parent);
    }

    /**
     * Returns {@code true} if the metamodel corresponds to an inline record, returns {@code false} otherwise.
     *
     * @return {@code true} if this metamodel maps to an inline record, {@code false} otherwise.
     */
    @Override
    public boolean isInline() {
        return inline;
    }

    /**
     * Returns the field type of the designated element.
     *
     * @return the field type of the designated element.
     */
    @Override
    public Class<E> fieldType() {
        return fieldType;
    }

    /**
     * Returns the path to the database table.
     *
     * @return path to the database table.
     */
    @Override
    public String path() {
        return path;
    }

    /**
     * Returns the field name.
     *
     * @return field name.
     */
    @Override
    public String field() {
        return field;
    }

    @Override
    public String toString() {
        return "Metamodel{root=%s, type=%s, path='%s', field='%s'}"
                .formatted(root().getSimpleName(), fieldType.getSimpleName(), path, field);
    }
}
