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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Accepts advice for the annotated entity, repository, class or method, so the advice log stops reporting it there.
 *
 * <p>Advice on a statement is accepted when the entity the statement reads or writes accepts it, or when a method or
 * class on the statement's call path does: the service method that issues a query, or a repository interface or one
 * of its methods, including the methods a repository inherits. Advice on a transaction is accepted on the method
 * that runs it, through the proxy Spring puts around a transactional service as well. An entity's own design advice
 * is accepted on the entity. The slow statement log and the performance summary still show accepted advice, labelled
 * {@code accepted:}.</p>
 *
 * <p>Without values, every piece of advice is accepted. With values, only the advice they name is accepted, by the
 * anchor its log line links to, such as {@code page-count} in {@code https://orm.st/docs/advice#page-count}. A value
 * that names no advice is ignored.</p>
 *
 * <pre>{@code
 * // The admin export reads every order with its full graph.
 * @AcceptAdvice("entity-budget")
 * record Order(@PK Integer id, ...) implements Entity<Integer> {}
 *
 * interface OrderRepository extends EntityRepository<Order, Integer> {
 *
 *     // The admin screen shows the total.
 *     @AcceptAdvice("page-count")
 *     default Page<Order> adminOrders(Pageable pageable) {
 *         return select().page(pageable);
 *     }
 * }
 * }</pre>
 *
 * @since 1.16
 */
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface AcceptAdvice {

    /**
     * The anchors of the advice to accept, such as {@code page-count}; empty to accept every piece of advice.
     *
     * @return the anchors of the accepted advice.
     */
    String[] value() default {};
}
