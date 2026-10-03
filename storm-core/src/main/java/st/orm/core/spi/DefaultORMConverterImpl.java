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

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.Converter;
import st.orm.SqlTemplateException;
import st.orm.mapping.RecordField;

/**
 *
 * @param <D>
 * @param <E>
 * @since 1.7
 */
@SuppressWarnings("ALL")
public final class DefaultORMConverterImpl<D, E> implements ORMConverter {
    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private final RecordField field;
    private final Converter<D, E> converter;
    private final Class<D> databaseType;

    /**
     * @param field the record field this converter is bound to.
     * @param converter the value converter (object to database).
     * @param databaseType the JDBC-facing / database column Java type (D).
     */
    public DefaultORMConverterImpl(RecordField field,
                                   Converter<D, E> converter,
                                   Class<D> databaseType) {
        this.field = requireNonNull(field, "field");
        this.converter = requireNonNull(converter, "converter");
        this.databaseType = requireNonNull(databaseType, "databaseType");
    }

    /**
     * Returns the number of parameters in the SQL template.
     *
     * <p><strong>Note:</strong> The count must match the parameter as returned by {@link #getParameterTypes()}.</p>
     *
     * @return the number of parameters.
     */
    @Override
    public int getParameterCount() {
        // This implementation always maps a single component to a single column.
        return 1;
    }

    /**
     * Returns the types of the parameters in the SQL template.
     *
     * @return a list of parameter types.
     */
    @Override
    public List<Class<?>> getParameterTypes() throws SqlTemplateException {
        // The database type of the Converter is the JDBC-visible parameter type.
        return List.of(databaseType);
    }

    /**
     * Returns the names of the columns that will be used in the SQL template.
     *
     * <p><strong>Note:</strong> The names must match the parameters as returned by {@link #getParameterTypes()}.</p>
     *
     * @return a list of column names.
     */
    @Override
    public List<Name> getColumns(NameResolver nameResolver) throws SqlTemplateException {
        requireNonNull(nameResolver, "nameResolver");
        return List.of(nameResolver.getName(field));
    }

    /**
     * Converts the given record to a list of values that can be used in the SQL template.
     *
     * <p><strong>Note:</strong> The values must match the parameters as returned by {@link #getParameterTypes()}.</p>
     *
     * @param record the record to convert.
     * @return the values to be used in the SQL template.
     */
    @Override
    public List<@Nullable Object> toDatabase(@Nullable Object record) throws SqlTemplateException {
        try {
            @SuppressWarnings("unchecked")
            E value = record == null
                    ? null
                    : (E) REFLECTION.invoke(field, record);
            D dbValue = converter.toDatabase(value);
            return singletonList(dbValue);
        } catch (Throwable e) {
            throw new SqlTemplateException("Failed to convert value to database format for field '%s.%s'.".formatted(field.type().getSimpleName(), field.name()), e);
        }
    }

    /**
     * Converts the given values to an object that is used in the object model.
     *
     * @param values     the arguments to convert. The arguments match the parameter types as returned by getParameterTypes().
     * @param refFactory the factory for creating references to entities (unused here, but part of the SPI).
     * @return the converted object.
     * @throws SqlTemplateException if an error occurs during conversion.
     */
    @Override
    public @Nullable Object fromDatabase(@Nullable Object[] values,
                               RefFactory refFactory) throws SqlTemplateException {
        requireNonNull(values, "values");
        requireNonNull(refFactory, "refFactory");
        if (values.length == 0) {
            return null;
        }
        Object raw = values[0];
        if (raw == null) {
            return null;
        }
        try {
            D dbValue = databaseType.cast(raw);
            return converter.fromDatabase(dbValue);
        } catch (Throwable e) {
            throw new SqlTemplateException("Failed to convert database value to entity format for field '%s.%s'.".formatted(field.type().getSimpleName(), field.name()), e);
        }
    }
}
