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
     * Specifies the relation to join on as a predicate, which states it as a whole: the keys it equates and any
     * condition on the joined rows. A term equating a key of the joined table with a key of another table, a
     * foreign key with the primary key it references, {@code where(Transfer_.to.id, EQUALS, Owner_.id)}, or two
     * foreign keys referencing one table, {@code where(Invoice_.pet, EQUALS, Visit_.pet)}, is the key the join
     * resolves by; the remaining terms are its condition. An inner or left join takes any predicate; a semi-join or
     * anti-join takes one equating a key of the joined table with a key of the table to join on.
     *
     * @param predicate the predicate relating the joined rows to the rows they join.
     * @return the query builder.
     * @see #on(Class, Function)
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Function<WhereBuilder<Data, R, ID>, PredicateBuilder<Data, ?, ?>> predicate);
}
