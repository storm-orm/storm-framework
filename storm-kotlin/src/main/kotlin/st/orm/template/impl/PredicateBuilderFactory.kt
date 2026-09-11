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
package st.orm.template.impl

import st.orm.Data
import st.orm.Metamodel
import st.orm.Operator
import st.orm.Ref
import st.orm.core.template.impl.PredicateBuilderFactory
import st.orm.template.PredicateBuilder
import st.orm.template.impl.QueryBuilderImpl.PredicateBuilderImpl

/**
 * Creates a new instance of [PredicateBuilder] for the specified path, operator, and values.
 *
 * @param path the metamodel path representing the field to be queried
 * @param operator the operator to be used in the predicate
 * @param o the values to be used in the predicate
 * @param <T> the type of the record
 * @param <R> the type of the result
 * @param <V> the type of the values
 * @return a new instance of [PredicateBuilder]
*/
internal fun <T : Data, R : Any, V : Any> create(
    path: Metamodel<*, V>,
    operator: Operator,
    o: Iterable<V>,
): PredicateBuilder<T, R, *> = PredicateBuilderImpl(PredicateBuilderFactory.create(path, operator, o))

/**
 * Creates a new instance of [PredicateBuilder] for the specified path, operator, and values.
 *
 * @param path the metamodel path representing the field to be queried
 * @param operator the operator to be used in the predicate
 * @param o the values to be used in the predicate
 * @param <T> the type of the record
 * @param <R> the type of the result
 * @param <V> the type of the values
 * @return a new instance of [PredicateBuilder]
 */
internal fun <T : Data, R : Any, V : Data> createRef(
    path: Metamodel<*, V>,
    operator: Operator,
    o: Iterable<Ref<V>>,
): PredicateBuilder<T, R, *> = PredicateBuilderImpl(PredicateBuilderFactory.createRef(path, operator, o))

/**
 * Combines two predicates using an AND condition, rooting the result at the operands' least common root.
 *
 * @param left the predicate to combine into
 * @param right the predicate to add
 * @return the combined [PredicateBuilder]
 */
internal fun <T : Data> combineAnd(
    left: PredicateBuilder<out T, *, *>,
    right: PredicateBuilder<out T, *, *>,
): PredicateBuilder<T, *, *> {
    // The operands' cores carry only the predicate and the result is rooted at [T], so re-labelling the covariant
    // left operand to the core's invariant parameters is safe.
    @Suppress("UNCHECKED_CAST")
    return PredicateBuilderImpl((left as PredicateBuilderImpl<T, Any, Any>).core.and((right as PredicateBuilderImpl<out T, *, *>).core))
}

/**
 * Combines two predicates using an OR condition, rooting the result at the operands' least common root.
 *
 * @param left the predicate to combine into
 * @param right the predicate to add
 * @return the combined [PredicateBuilder]
 */
internal fun <T : Data> combineOr(
    left: PredicateBuilder<out T, *, *>,
    right: PredicateBuilder<out T, *, *>,
): PredicateBuilder<T, *, *> {
    // The operands' cores carry only the predicate and the result is rooted at [T], so re-labelling the covariant
    // left operand to the core's invariant parameters is safe.
    @Suppress("UNCHECKED_CAST")
    return PredicateBuilderImpl((left as PredicateBuilderImpl<T, Any, Any>).core.or((right as PredicateBuilderImpl<out T, *, *>).core))
}
