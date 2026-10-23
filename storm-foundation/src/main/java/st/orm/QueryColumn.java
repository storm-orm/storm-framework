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

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares a column a query names but the entity or projection does not carry: a query column. It gets a metamodel
 * field, generated as a record component's is, but no component, so it is never selected, inserted or updated. A
 * generated column that stores a derived form of another column, which a search matches and an index is built on,
 * is the typical case: the database computes it, no caller reads it, and a record built by the application has no
 * value to give it.
 *
 * <pre>{@code
 * @DbTable("movie")
 * @QueryColumn(field = "titleFolded", type = String.class)
 * record Movie(@PK Integer id, String title, Integer year) implements Entity<Integer> {}
 *
 * orm.entity(Movie.class).select()
 *     .where(it -> it.search(input, Movie_.titleFolded))
 *     .getResultList();
 * }</pre>
 *
 * <p>The generated {@code Movie_.titleFolded} is a {@code Metamodel<Movie, String>} and goes wherever a column does:
 * {@code where}, {@code orderBy}, {@code orderFirst}, {@code search} and {@code suggest}, a {@link SqlFunction}, a
 * keyset sort and a template placeholder. It is reached through paths like any other field, so a query column of a
 * referenced table is {@code Credit_.movie.titleFolded} from a query on credits. A record holds no value for it:
 * {@link Metamodel#getValue(Data)} throws, and an {@code update} cannot set it.</p>
 *
 * <p>The column follows from the field name through the column name resolver, as a component's does;
 * {@link #column()} names it where the resolver does not. Schema validation checks that the column exists with a
 * compatible type, and on an entity that the database fills it, since every insert leaves it out: it must be
 * generated, an identity column, have a default, or allow null.</p>
 *
 * @since 1.16
 */
@Target(TYPE)
@Retention(RUNTIME)
@Repeatable(QueryColumns.class)
public @interface QueryColumn {

    /**
     * The name of the metamodel field, such as {@code titleFolded} for {@code Movie_.titleFolded}.
     */
    String field();

    /**
     * The type of the column's values, as a record component's type: {@code String.class}, {@code Integer.class},
     * {@code LocalDate.class}, an enum type.
     */
    Class<?> type();

    /**
     * The database column name. By default the column name resolver derives it from the field name, as it does for a
     * record component: {@code titleFolded} is {@code title_folded} under the default resolver.
     */
    String column() default "";

    /**
     * Whether the column allows null. A nullable query column compares as a nullable field does and is refused as a
     * keyset sort field, as a nullable field is.
     */
    boolean nullable() default false;

    /**
     * Whether schema validation checks the column. {@code false} skips it, as {@link DbIgnore} skips a record
     * component: for a column the database fills in a way its metadata does not show, such as a trigger.
     */
    boolean validate() default true;
}
