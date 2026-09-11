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
 * Base implementation for generated navigation-only metamodels — nodes that navigate the entity graph beyond a
 * {@link Ref} foreign key. Such a node locates a column for querying (filter, join, order, group, select) but does not
 * extract values, because the referenced entity is not loaded. It therefore implements {@link Navigable} rather than
 * {@link Metamodel}, which is what makes value-based operations (for example {@code getResultGroupedBy}) fail to compile
 * on a reference-crossing path.
 *
 * @param <T> the root table type.
 * @param <E> the field type of the designated element.
 * @since 1.13
 */
public abstract class AbstractNavigableMetamodel<T extends Data, E extends @Nullable Object> implements Navigable<T, E> {

    private final Class<E> fieldType;
    private final String path;
    private final String field;
    private final boolean inline;
    private final Navigable<T, ?> parent;
    private final boolean isColumn;
    private int hash;

    public AbstractNavigableMetamodel(Class<E> fieldType,
                                      String path,
                                      String field,
                                      boolean inline,
                                      @Nullable Navigable<T, ?> parent) {
        this(fieldType, path, field, inline, parent, !inline && !field.isEmpty());
    }

    protected AbstractNavigableMetamodel(Class<E> fieldType,
                                         String path,
                                         String field,
                                         boolean inline,
                                         @Nullable Navigable<T, ?> parent,
                                         boolean isColumn) {
        this.fieldType = fieldType;
        this.path = path;
        this.field = field;
        this.inline = inline;
        this.parent = parent;
        this.isColumn = isColumn;
    }

    private Optional<Navigable<T, ?>> parent() {
        return ofNullable(parent);
    }

    /**
     * Equality is based on {@link #tableType()}, {@link #path()} and {@link #field()}, matching
     * {@link AbstractMetamodel}, so a navigation-only node and a full metamodel that reach the same field through the
     * same path compare equal.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Navigable<?, ?> other)) return false;
        return Objects.equals(this.table().fieldType(), other.table().fieldType())
                && Objects.equals(this.path, other.path())
                && Objects.equals(this.field, other.field());
    }

    @Override
    public final int hashCode() {
        int cached = hash;
        if (cached == 0) {
            cached = Objects.hash(table().fieldType(), path, field);
            hash = cached;
        }
        return cached;
    }

    @Override
    public boolean isColumn() {
        return isColumn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<T> root() {
        return parent()
                .map(Navigable::root)
                .orElseGet(() -> (Class<T>) fieldType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Navigable<T, ? extends Data> table() {
        var parent = parent().orElse(null);
        if (parent == null) {
            return (Navigable<T, ? extends Data>) this;
        }
        if (parent.isInline()) {
            return parent.table();
        }
        return (Navigable<T, ? extends Data>) parent;
    }

    @Override
    public boolean isInline() {
        return inline;
    }

    @Override
    public Class<E> fieldType() {
        return fieldType;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String field() {
        return field;
    }

    @Override
    public String toString() {
        return "Navigable{root=%s, type=%s, path='%s', field='%s'}"
                .formatted(root().getSimpleName(), fieldType.getSimpleName(), path, field);
    }
}
