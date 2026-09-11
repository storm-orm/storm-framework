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
package st.orm.core.spi;

import java.util.function.Supplier;
import st.orm.Data;
import st.orm.Ref;
import st.orm.core.template.Model;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.TemplateString;

/**
 * Provides pluggable query builder logic.
 *
 * @since 1.1
 */
// The type parameters of this interface and of its default implementation carry no nullness bound: module-info.java
// names both, and the source parser the compiler plugin runs over the classes a module descriptor names (QDox) rejects
// an annotation inside a type-parameter bound.
public interface QueryBuilderProvider extends Provider {

    /**
     * Creates a query builder for the specified table and select type using the given {@code template}.
     *
     * @param queryTemplate provides the template logic for the query builder.
     * @param fromType the table to select from.
     * @param selectType the result type of the query.
     * @param template the select clause template.
     * @param subquery whether the query is a subquery.
     * @param modelSupplier a supplier that creates the model when needed.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    <T extends Data, R, ID> QueryBuilder<T, R, ID> selectFrom(QueryTemplate queryTemplate,
                                                              Class<T> fromType,
                                                              Class<R> selectType,
                                                              TemplateString template,
                                                              boolean subquery,
                                                              Supplier<Model<T, ID>> modelSupplier);

    /**
     * Creates a query builder for the specified table and select type using the given {@code template}.
     *
     * @param queryTemplate provides the template logic for the query builder.
     * @param fromType the table to select from.
     * @param refType the type that is selected as ref.
     * @param pkType the primary key type of the table.
     * @param modelSupplier a supplier that creates the model when needed.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <ID> the primary key type.
     */
    <T extends Data, R extends Data, ID> QueryBuilder<T, Ref<R>, ID> selectRefFrom(QueryTemplate queryTemplate,
                                                                                   Class<T> fromType,
                                                                                   Class<R> refType,
                                                                                   Class<?> pkType,
                                                                                   Supplier<Model<T, ID>> modelSupplier);

    /**
     * Creates a query builder for the specified table to delete from.
     *
     * @param queryTemplate provides the template logic for the query builder.
     * @param fromType the table to delete from.
     * @param modelSupplier a supplier that creates the model when needed.
     * @return the query builder.
     * @param <T> the table type to delete from.
     */
    <T extends Data, ID> QueryBuilder<T, ?, ID> deleteFrom(QueryTemplate queryTemplate,
                                                           Class<T> fromType,
                                                           Supplier<Model<T, ID>> modelSupplier);
}
