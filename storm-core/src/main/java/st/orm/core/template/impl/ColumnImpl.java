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

import st.orm.Data;
import st.orm.GenerationStrategy;
import st.orm.Metamodel;
import st.orm.core.spi.Name;
import st.orm.core.template.Column;
import st.orm.core.template.SqlDialect;

/**
 * Represents a column in a database table.
 *
 * @param columnName the name of the column.
 * @param index the 1-based index of the column.
 * @param type the type of the column.
 * @param primaryKey whether the column is a primary key.
 * @param generation the generation strategy.
 * @param sequence the sequence name.
 * @param foreignKey whether the column is a foreign key.
 * @param foreignKeyGeneration the generation strategy for the primary key of the referenced entity.
 * @param keyIndex the 1-based index of the key.
 * @param nullable whether the column is nullable.
 * @param insertable whether the column is insertable.
 * @param updatable whether the column is updatable.
 * @param version whether the column is a version column.
 * @param ref whether the column is a lazily fetched record.
 * @param function the SQL function whose result a query column stores, which a value compared with the column
 *                 passes through; empty for a column compared with a value as it is.
 * @param metamodel the metamodel for the column.
 * @param secondaryMetamodel the secondary metamodel for the column: the referenced primary key metamodel for
 *                           foreign keys, or the component's own metamodel for compound key components.
 */
public record ColumnImpl(
        Name columnName,
        int index,
        Class<?> type,
        Class<?> persistedType,
        boolean primaryKey,
        GenerationStrategy generation,
        String sequence,
        boolean foreignKey,
        GenerationStrategy foreignKeyGeneration,
        int keyIndex,
        boolean nullable,
        boolean insertable,
        boolean updatable,
        boolean version,
        boolean ref,
        String function,
        Metamodel<Data, ?> metamodel,
        Metamodel<Data, ?> secondaryMetamodel
) implements Column {

    /**
     * Gets the name of the column.
     *
     * @return the column name.
     */
    @Override
    public String name() {
        return columnName.name();
    }

    /**
     * Gets the qualified name of the column, including escape characters where necessary.
     *
     * @param dialect the SQL dialect.
     * @return the qualified column name.
     */
    @Override
    public String qualifiedName(SqlDialect dialect) {
        return columnName.qualified(dialect);
    }
}
