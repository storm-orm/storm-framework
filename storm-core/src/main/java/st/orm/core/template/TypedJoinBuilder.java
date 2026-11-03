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

import java.util.function.Function;
import st.orm.Data;
import st.orm.Metamodel;

/**
 * A builder for constructing join clause of the query.
 *
 * @param <T>  the type of the table being queried.
 * @param <R>  the type of the result.
 * @param <ID> the type of the primary key.
 */
public interface TypedJoinBuilder<T extends Data, R, ID> extends JoinBuilder<T, R, ID> {

    /**
     * Specifies the relation to join on.
     *
     * @param relation the relation to join on.
     * @return the query builder.
     */
    QueryBuilder<Data, R, ID> on(Class<? extends Data> relation);

    /**
     * Specifies the relation to join on, with a condition the join adds to the foreign key it resolves. On a left
     * join, the condition decides which related rows join, so a row without a match is kept; on an anti-join, it
     * decides which related rows exclude a row.
     *
     * @param relation the relation to join on.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Class<? extends Data> relation,
                                 Function<WhereBuilder<Data, R, ID>, PredicateBuilder<Data, ?, ?>> condition);

    /**
     * Specifies the foreign key to join on, where the table holds more than one key to the table joined on. A key of
     * the joined table joins the table it references; a key of another table joins that table, and must reference
     * the joined table.
     *
     * @param key a foreign key of the joined table, or of the table to join on.
     * @return the query builder.
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Metamodel<? extends Data, ?> key);

    /**
     * Specifies the foreign key to join on, with a condition the join adds to the key it joins by.
     *
     * @param key a foreign key of the joined table, or of the table to join on.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @see #on(Class, Function)
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Metamodel<? extends Data, ?> key,
                                 Function<WhereBuilder<Data, R, ID>, PredicateBuilder<Data, ?, ?>> condition);

    /**
     * Specifies the foreign keys to join on, where neither table references the other and both hold a key to one
     * table: the join matches the two keys with each other.
     *
     * @param key a foreign key of the joined table.
     * @param anchorKey a foreign key of the table to join on, referencing the table {@code key} references.
     * @return the query builder.
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Metamodel<? extends Data, ?> key, Metamodel<? extends Data, ?> anchorKey);

    /**
     * Specifies the foreign keys to join on, with a condition the join adds to the keys it joins by.
     *
     * @param key a foreign key of the joined table.
     * @param anchorKey a foreign key of the table to join on, referencing the table {@code key} references.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @see #on(Class, Function)
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Metamodel<? extends Data, ?> key,
                                 Metamodel<? extends Data, ?> anchorKey,
                                 Function<WhereBuilder<Data, R, ID>, PredicateBuilder<Data, ?, ?>> condition);
}
