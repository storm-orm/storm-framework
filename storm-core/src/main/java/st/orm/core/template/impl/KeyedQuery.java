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
 * A query that reads the values of a cursor's fields from each row alongside the mapped result. The statement
 * reports where each value is found; a column the select list lacks follows the select list, so the leading
 * columns are mapped to the result type the way every other query maps its rows. A scroll window reads its sort
 * and key values this way.
 */
interface KeyedQuery {

    /**
     * A result row with the values of the cursor's fields, read from the row itself rather than from the mapped
     * result, so they are available whatever type the row was mapped to.
     *
     * @param value the mapped result.
     * @param cursor the cursor values, in the order the fields were requested.
     * @param <R> the result type.
     */
    record Row<R>(R value, Object[] cursor) {}

    /**
     * Executes the query and maps every row to the given type, reading the cursor values alongside it.
     *
     * @param type the result type mapped from the leading columns.
     * @param cursorTypes the types the cursor values decode to, one per cursor field.
     * @return the rows with their cursor values.
     */
    <T> List<Row<T>> getKeyedResultList(Class<T> type, Class<?>[] cursorTypes);

    /**
     * Executes the query and maps every row to a ref of the given type, reading the cursor values alongside it.
     *
     * @param type the referenced type.
     * @param pkType the primary key type mapped from the leading columns.
     * @param cursorTypes the types the cursor values decode to, one per cursor field.
     * @return the rows with their cursor values.
     */
    <T extends Data> List<Row<Ref<T>>> getKeyedRefList(Class<T> type, Class<?> pkType, Class<?>[] cursorTypes);
}
