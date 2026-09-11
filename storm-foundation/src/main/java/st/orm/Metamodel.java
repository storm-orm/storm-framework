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

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The metamodel provides type-safe references to entity fields for use in queries. Generated metamodel classes
 * (e.g., {@code User_} for a {@code User} entity) contain static fields representing each entity field, enabling
 * compile-time verification of field references.
 *
 * <h2>Nested Paths (Fully Qualified)</h2>
 *
 * <p>Nested paths traverse from the root entity through relationships by chaining field accessors:
 * <pre>{@code
 * // Traverse User → City → Country → name
 * User_.city.country.name
 * }</pre>
 *
 * <p>Nested paths are <strong>always unambiguous</strong> because they explicitly specify the traversal from the
 * root entity. Storm automatically generates the necessary JOINs based on the path.
 *
 * <h2>Short Form</h2>
 *
 * <p>Short form references a table's metamodel directly without specifying the path:
 * <pre>{@code
 * // Reference Country directly
 * Country_.name
 * }</pre>
 *
 * <p>Short form works <strong>only when the table appears exactly once</strong> in the entity graph. If the table
 * is referenced in multiple places, Storm cannot determine which occurrence you mean and throws an exception.
 *
 * <p>Short form is also used to reference tables added via custom joins, since those tables are not reachable
 * through nested paths.
 *
 * <h2>Path Resolution</h2>
 *
 * <p>When resolving a metamodel reference, Storm follows this order:
 * <ol>
 *   <li><strong>Nested path</strong> — If a path is specified (e.g., {@code User_.city.country}), use the alias
 *       for that specific traversal</li>
 *   <li><strong>Unique table lookup</strong> — If short form (e.g., {@code Country_}), check if the table appears
 *       exactly once in the entity graph or registered joins</li>
 *   <li><strong>Error</strong> — If multiple paths exist, throw an exception indicating the ambiguity</li>
 * </ol>
 *
 * @param <T> the root table type (the entity from which the path originates).
 * @param <E> the field type of the designated element.
 * @since 1.2
 */
public interface Metamodel<T extends Data, E extends @Nullable Object> extends Navigable<T, E> {

    /**
     * Creates a new metamodel for the given record type.
     *
     * @param table the root table to create the metamodel for.
     * @return a new metamodel for the given record type.
     * @param <T> the root table type.
     */
    static <T extends Data> Metamodel<T, T> root(Class<T> table) {
        return MetamodelHelper.root(table);
    }

    /**
     * Creates a new metamodel for the given root rootTable and path.
     *
     * <p>This method is typically used to manually create a metamodel for a component of a record, which can be useful
     * in cases where the metamodel cannot be generated automatically, for example, local records.</p>
     *
     * @param rootTable the root rootTable to create the metamodel for.
     * @param path a dot separated path starting from the root table.
     * @return a new metamodel for the given root rootTable and path.
     * @param <T> the root rootTable type.
     * @param <E> the record component type of the designated component.
     * @throws PersistenceException if the metamodel cannot be created for the root rootTable and path.
     */
    static <T extends Data, E extends @Nullable Object> Metamodel<T, E> of(Class<T> rootTable, String path) {
        return MetamodelHelper.of(rootTable, path);
    }

    /**
     * Returns the table that holds the column to which this metamodel is pointing. If the metamodel points to an
     * inline record, the table is the parent table of the inline record. If the metamodel is a root metamodel, the
     * root table is returned.
     *
     * @return the table that holds the column to which this metamodel is pointing.
     */
    @Override
    Metamodel<T, ? extends Data> table();

    /**
     * Extracts the value from the given record as specified by this metamodel.
     *
     * <p>The returned value may be {@code null} if this metamodel represents a nullable field or if any parent
     * metamodel in the access path resolves to {@code null} (for example, when navigating through an optional or
     * nullable nested record).</p>
     *
     * <p>Implementations may return a non-null value, but callers must not rely on that unless they statically know
     * they are using a non-null metamodel variant.</p>
     *
     * @param record the root record from which the value is extracted.
     * @return the extracted value, or {@code null} if the value cannot be resolved.
     * @since 1.7
     */
    @Nullable
    Object getValue(T record);

    /**
     * Checks whether the value extracted from {@code a} is identical to the value extracted from {@code b}.
     *
     * <p><strong>Semantics:</strong>
     * This method performs an <em>identity comparison</em> on the extracted field value.
     * It returns {@code true} if and only if both extracted values refer to the
     * <strong>same object instance</strong>.
     *
     * <p>This operation is only meaningful for <em>reference-typed fields</em>.
     * It is <strong>not defined for primitive-typed fields</strong>.
     *
     * <p><strong>Performance guarantees:</strong>
     * <ul>
     *   <li>No boxing or unboxing is performed.</li>
     *   <li>No value coercion or conversion is performed.</li>
     *   <li>No {@code equals(...)} or comparator logic is used.</li>
     * </ul>
     *
     * @param a the instance from which the left-hand value is extracted, must not be {@code null}.
     * @param b the instance from which the right-hand value is extracted, must not be {@code null}.
     * @return {@code true} if both extracted values are the same object instance.
     * @since 1.7
     */
    boolean isIdentical(T a, T b);

    /**
     * Checks whether the value extracted from {@code a} is the same as the value extracted from {@code b}.
     *
     * <p><strong>Semantics:</strong>
     * This method performs a <em>value comparison</em> on the extracted field value.
     * The comparison is defined by the field type:
     *
     * <ul>
     *   <li>For primitive-typed fields, values are compared using {@code ==}.</li>
     *   <li>For reference-typed fields, values are compared using their defined value semantics
     *       (for example {@code equals(...)} or an equivalent comparator).</li>
     * </ul>
     *
     * <p><strong>Performance guarantees:</strong>
     * <ul>
     *   <li>No boxing or unboxing is performed.</li>
     *   <li>No identity comparison is performed.</li>
     *   <li>The comparison operates directly on the extracted values.</li>
     * </ul>
     *
     * @param a the instance from which the left-hand value is extracted, must not be {@code null}.
     * @param b the instance from which the right-hand value is extracted, must not be {@code null}.
     * @return {@code true} if both extracted values are equal by value.
     * @since 1.7
     */
    boolean isSame(T a, T b);

    /**
     * Marker interface for metamodel fields that correspond to columns known to be unique. Metamodel fields generated
     * for {@link UK @UK} or {@link PK @PK} record components implement this interface automatically.
     *
     * <p>{@code Key} instances can be used where a unique column is required, such as keyset pagination cursors and
     * single-result lookups via repository methods like {@code findBy} and {@code getBy}.</p>
     *
     * <p>A {@code Key} can also be created manually via the {@link #key(Metamodel)} factory method for columns that
     * are known to produce unique values in a specific query context (for example, a column used in a {@code GROUP BY}
     * clause). In such cases, the caller is responsible for ensuring that the values are unique within the result set;
     * using a non-unique column as a keyset pagination key silently skips rows when duplicates span page
     * boundaries.</p>
     *
     * @param <T> the root table type.
     * @param <E> the field type of the designated element.
     * @see UK
     * @since 1.9
     */
    interface Key<T extends Data, E extends @Nullable Object> extends Metamodel<T, E> {

        /**
         * Returns {@code true} if this key field allows NULL values with standard SQL distinct-NULL semantics,
         * meaning the UNIQUE constraint does not prevent multiple NULLs.
         *
         * <p>A nullable key is unsafe for keyset pagination because {@code WHERE key > cursor} silently excludes
         * NULL rows. The {@code scroll} methods check this flag and throw a {@link PersistenceException} if the key
         * is nullable.</p>
         *
         * @return {@code true} if this key allows duplicate NULLs (nullable with {@code nullsDistinct = true}),
         *         {@code false} otherwise.
         * @since 1.9
         * @see UK#nullsDistinct()
         */
        boolean isNullable();
    }

    /**
     * Returns a {@code Key} view of the given metamodel. If {@code metamodel} already implements {@link Key}, it is
     * returned as-is; otherwise it is wrapped in a delegate that implements {@code Key}.
     *
     * <p>This factory is intended for cases where the generated metamodel does not carry the {@code Key} marker (for
     * example, composite primary keys or dynamically constructed metamodels), or where a column is known to produce
     * unique values in a particular query context (for example, a column that appears in a {@code GROUP BY}
     * clause).</p>
     *
     * <p><strong>Important:</strong> callers are responsible for ensuring that the column produces unique values in the
     * context where the key is used. Using a non-unique column as a keyset pagination key will silently skip rows when
     * duplicate values span page boundaries.</p>
     *
     * <p>The returned key reports {@link Key#isNullable()} from the underlying field: a nullable field stays nullable
     * when viewed as a key, so keyset pagination keeps rejecting it.</p>
     *
     * @param metamodel the metamodel to view as a key.
     * @return a {@code Key} instance backed by the given metamodel.
     * @param <T> the root table type.
     * @param <E> the field type.
     * @since 1.9
     */
    static <T extends Data, E extends @Nullable Object> Key<T, E> key(Metamodel<T, E> metamodel) {
        requireNonNull(metamodel, "metamodel");
        if (metamodel instanceof Key<T, E> key) {
            return key;
        }
        return new KeyDelegate<>(metamodel);
    }

    /**
     * Delegating wrapper that adds the {@link Key} marker to an existing {@link Metamodel} instance.
     *
     * <p>Equality and hash code are delegated to the wrapped metamodel. Because {@link AbstractMetamodel#equals}
     * checks {@code instanceof Metamodel}, a {@code KeyDelegate} compares equal to its wrapped metamodel when the
     * wrapped metamodel is an {@code AbstractMetamodel}.</p>
     *
     * @param <T> the root table type.
     * @param <E> the field type.
     */
    record KeyDelegate<T extends Data, E extends @Nullable Object>(Metamodel<T, E> delegate) implements Key<T, E> {

        public KeyDelegate {
            requireNonNull(delegate, "delegate");
        }

        @Override public boolean isColumn()                          { return delegate.isColumn(); }
        @Override public boolean isInline()                          { return delegate.isInline(); }
        @Override public Class<T> root()                             { return delegate.root(); }
        @Override public Metamodel<T, ? extends Data> table()        { return delegate.table(); }
        @Override public String path()                               { return delegate.path(); }
        @Override public Class<E> fieldType()                        { return delegate.fieldType(); }
        @Override public String field()                              { return delegate.field(); }
        @Override public @Nullable Object getValue(T record)         { return delegate.getValue(record); }
        @Override public boolean isIdentical(T a, T b) { return delegate.isIdentical(a, b); }
        @Override public boolean isSame(T a, T b)  { return delegate.isSame(a, b); }
        @Override public List<Metamodel<T, ?>> flatten()             { return delegate.flatten(); }

        /**
         * Returns the nullability of the underlying field. A delegate around a {@link Key} answers through that key;
         * any other delegate derives the answer from the record field the metamodel designates, so a nullable field
         * wrapped by {@link #key(Metamodel)} is still rejected as a keyset pagination cursor.
         */
        @Override
        public boolean isNullable() {
            return delegate instanceof Key<?, ?> key ? key.isNullable() : MetamodelHelper.isNullable(delegate);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof KeyDelegate<?, ?>(Metamodel<?, ?> delegate1)) return delegate.equals(delegate1);
            return delegate.equals(o);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
