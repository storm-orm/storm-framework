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
package st.orm.template

import st.orm.Data
import st.orm.GenerationStrategy
import st.orm.Metamodel
import kotlin.reflect.KClass

/**
 * Represents metadata about a single column in a database table, as derived from the entity or projection model.
 *
 * A `Column` provides information about the column's name, type, nullability, and role (primary key,
 * foreign key, version column, etc.). Columns are obtained from a [Model] via [Model.columns] or
 * [Model.declaredColumns].
 *
 * The [index] property returns the 1-based index of the column within the expanded column list of the
 * model, which includes columns from foreign key relationships.
 *
 * @see Model
 */
public interface Column {
    /**
     * Gets the 1-based index of the column among the model's columns, or `0` for a query column, which has no
     * position among them.
     *
     * @return the column index.
     */
    public val index: Int

    /**
     * Gets the name of the column.
     *
     * @return the column name.
     */
    public val name: String

    /**
     * Gets the type of the column.
     *
     * @return the type of the column.
     */
    public val type: KClass<*>

    /**
     * Determines if the column is a primary key.
     *
     * @return true if it is a primary key, false otherwise.
     */
    public val primaryKey: Boolean

    /**
     * Gets the generation strategy for the primary key.
     *
     * @return the generation strategy for the primary key.
     */
    public val generation: GenerationStrategy

    /**
     * Gets the name of the sequence to use for generating values for the primary key, or null if the column is not a
     * primary key or does not require a sequence.
     */
    public val sequence: String?

    /**
     * Determines if the column is a foreign key.
     *
     * @return true if it is a foreign key, false otherwise.
     */
    public val foreignKey: Boolean

    /**
     * Determines if the column is nullable.
     *
     * @return true if the column can be null, false otherwise.
     */
    public val nullable: Boolean

    /**
     * Determines if the column is insertable.
     *
     * @return true if the column can be inserted, false otherwise.
     */
    public val insertable: Boolean

    /**
     * Determines if the column is updatable.
     *
     * @return true if the column can be updated, false otherwise.
     */
    public val updatable: Boolean

    /**
     * Determines if the column is used for versioning.
     *
     * @return true if it is a version column, false otherwise.
     */
    public val version: Boolean

    /**
     * Determines if the column is a ref column.
     *
     * @return if the column is a ref column, false otherwise.
     */
    public val ref: Boolean

    /**
     * Gets the metamodel of the column.
     *
     * @return the metamodel of the column.
     * @since 1.7
     */
    public val metamodel: Metamodel<Data, *>
}
