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

import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.SqlTemplateException;
import st.orm.mapping.RecordField;

/**
 * Interface for converting ORM parameters to SQL parameters.
 */
public interface ORMConverter {

    /**
     * Interface for resolving names of record components.
     */
    interface NameResolver {

        /**
         * Gets the column name of the given record field.
         *
         * @param field the record field to resolve.
         * @return the name of the column.
         * @throws SqlTemplateException if an error occurs while resolving the name.
         */
        Name getName(RecordField field) throws SqlTemplateException;
    }

    /**
     * Returns the number of parameters in the SQL template.
     *
     * <p><strong>Note:</strong> The count must match the parameter as returned by {@link #getParameterTypes()}.</p>
     *
     * @return the number of parameters.
     */
    default int getParameterCount() throws SqlTemplateException {
        return getParameterTypes().size();
    }

    /**
     * Returns the types of the parameters in the SQL template.
     *
     * @return a list of parameter types.
     */
    List<Class<?>> getParameterTypes() throws SqlTemplateException;

    /**
     * Returns the names of the columns that will be used in the SQL template.
     *
     * <p><strong>Note:</strong> The names must match the parameters as returned by {@link #getParameterTypes()}.</p>
     *
     * @return a list of column names.
     */
    List<Name> getColumns(NameResolver nameResolver) throws SqlTemplateException;

    /**
     * Converts the given record to a list of values that can be used in the SQL template.
     *
     * <p><strong>Note:</strong> The values must match the parameters as returned by {@link #getParameterTypes()}.</p>
     *
     * @param record the record to convert.
     * @return the values to be used in the SQL template; a value is {@code null} for a column written as NULL.
     */
    List<@Nullable Object> toDatabase(@Nullable Object record) throws SqlTemplateException;

    /**
     * Converts the given values to an object that is used in the object model.
     *
     * @param values the arguments to convert. The arguments match the parameter types as returned by getParameterTypes(),
     *               and a column read as NULL arrives as {@code null}.
     * @return the converted object, or {@code null} when the columns hold no value.
     * @param refFactory the factory for creating references to entities.
     * @throws SqlTemplateException if an error occurs during conversion.
     */
    @Nullable Object fromDatabase(@Nullable Object[] values, RefFactory refFactory) throws SqlTemplateException;
}
