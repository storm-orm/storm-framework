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
package st.orm.core.template.impl;

import java.util.List;
import st.orm.Data;
import st.orm.Ref;

/**
 * A query that reads a number of trailing columns of each row alongside the mapped result, for the cursor values
 * of a scroll window. The select list of such a query ends with the cursor columns; the columns before them are
 * mapped to the result type the way every other query maps its rows.
 */
interface KeyedQuery {

    /**
     * A result row with the values of its cursor columns, read from the row itself rather than from the mapped
     * result, so a scroll window can hand out navigation tokens whatever type the row was mapped to.
     *
     * @param value the mapped result.
     * @param cursor the cursor column values, in the order the columns were requested.
     * @param <R> the result type.
     */
    record Row<R>(R value, Object[] cursor) {}

    /**
     * Executes the query and maps every row to the given type, reading the trailing columns as cursor values.
     *
     * @param type the result type mapped from the leading columns.
     * @param trailingTypes the target types of the trailing columns, one per cursor column.
     * @return the rows with their cursor values.
     */
    <T> List<Row<T>> getKeyedResultList(Class<T> type, Class<?>[] trailingTypes);

    /**
     * Executes the query and maps every row to a ref of the given type, reading the trailing columns as cursor
     * values.
     *
     * @param type the referenced type.
     * @param pkType the primary key type mapped from the leading columns.
     * @param trailingTypes the target types of the trailing columns, one per cursor column.
     * @return the rows with their cursor values.
     */
    <T extends Data> List<Row<Ref<T>>> getKeyedRefList(Class<T> type, Class<?> pkType, Class<?>[] trailingTypes);
}
