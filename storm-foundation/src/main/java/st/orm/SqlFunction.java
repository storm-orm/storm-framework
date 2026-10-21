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
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A SQL function of one argument, applied to a column wherever a query takes a column. Applied to a path it returns a
 * path that renders as the function's call, so the predicate operators, {@code search}, {@code suggest},
 * {@code orderFirst}, {@code orderBy}, {@code groupBy} and a template placeholder take it as they take the column.
 *
 * <p>Its use is an expression index: the database serves a query that names the expression the index is built on.
 * An index on {@code LOWER(fold_accents(primary_title))} serves a search over
 * {@code foldAccents.apply(Movie_.primaryTitle)}, which compares as
 * {@code LOWER(fold_accents(m.primary_title)) LIKE LOWER(fold_accents(?))}.</p>
 *
 * <pre>{@code
 * SqlFunction<String> foldAccents = SqlFunction.of("fold_accents");
 *
 * movies.select()
 *         .where(it -> it.search(input, foldAccents.apply(Movie_.primaryTitle)))
 *         .getResultList();
 * }</pre>
 *
 * <p>A comparison applies the function to both sides: to the column, and to the value the column is compared
 * against, so the database computes both and the application computes neither. For the text-matching operators the
 * value is the pattern, escaped and with its wildcards, so a function compared this way must leave the wildcards
 * {@code %} and {@code _}, SQL Server's {@code [}, and the escape character {@code !} as they are.</p>
 *
 * <p>The path names a single column. A call is not a value the result holds, so a keyset scroll key and a scroll sort
 * field refuse it, and it never equals the path it wraps.</p>
 *
 * @param name the function's name, a SQL identifier optionally qualified by a schema.
 * @param <E> the type of the column the function takes and returns.
 * @since 1.16
 */
public record SqlFunction<E>(String name) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?");

    public SqlFunction {
        requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "A SQL function name is an identifier, optionally qualified by a schema, such as fold_accents or public.fold_accents; got '%s'."
                            .formatted(name));
        }
    }

    /**
     * Returns the function of the given name.
     *
     * @param name the function's name, a SQL identifier optionally qualified by a schema.
     * @param <E> the type of the column the function takes and returns.
     * @return the function.
     */
    public static <E> SqlFunction<E> of(String name) {
        return new SqlFunction<>(name);
    }

    /**
     * Returns the call of this function on the given path, which a query takes where it takes the path.
     *
     * @param path the column to apply the function to.
     * @param <T> the root table type of the path.
     * @return the call.
     */
    public <T extends Data> Metamodel<T, E> apply(Metamodel<T, E> path) {
        return new Call<>(this, path);
    }

    /**
     * Returns {@code true} if the path is a function call, directly or as the key it is viewed as.
     *
     * @param path the path to test.
     * @return {@code true} for a function call.
     */
    public static boolean isCall(Metamodel<?, ?> path) {
        return switch (path) {
            case Call<?, ?> ignore -> true;
            case Metamodel.KeyDelegate<?, ?> key -> isCall(key.delegate());
            default -> false;
        };
    }

    /**
     * The call of a function on a path. It designates the path's column, wrapped in the function, and answers for the
     * column's table, path and type as the path does, so the column's table is joined as for the path itself.
     *
     * @param function the function.
     * @param argument the path the function is applied to.
     * @param <T> the root table type.
     * @param <E> the type of the column.
     */
    public record Call<T extends Data, E>(SqlFunction<E> function, Metamodel<T, E> argument) implements Metamodel<T, E> {

        public Call {
            requireNonNull(function, "function");
            requireNonNull(argument, "argument");
        }

        @Override public boolean isColumn()                   { return argument.isColumn(); }
        @Override public boolean isInline()                   { return argument.isInline(); }
        @Override public Class<T> root()                      { return argument.root(); }
        @Override public Metamodel<T, ? extends Data> table() { return argument.table(); }
        @Override public String path()                        { return argument.path(); }
        @Override public Class<E> fieldType()                 { return argument.fieldType(); }
        @Override public String field()                       { return argument.field(); }
        @Override public List<Metamodel<T, ?>> flatten()      { return List.of(this); }

        @Override
        public @Nullable Object getValue(T record) {
            throw new UnsupportedOperationException(
                    "%s is computed by the database; a record holds no value for it.".formatted(this));
        }

        @Override
        public boolean isIdentical(T a, T b) {
            throw new UnsupportedOperationException(
                    "%s is computed by the database; records are not compared by it.".formatted(this));
        }

        @Override
        public boolean isSame(T a, T b) {
            throw new UnsupportedOperationException(
                    "%s is computed by the database; records are not compared by it.".formatted(this));
        }

        @Override
        public String toString() {
            return "%s(%s)".formatted(function.name(), argument);
        }
    }
}
