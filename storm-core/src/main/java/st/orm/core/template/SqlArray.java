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

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * The values of an {@code IN} list bound as one array parameter, so the statement keeps one shape whatever the
 * length of the list. A {@link SqlTemplate.Parameter} carries it as its database value, and the statement binds it
 * through {@link java.sql.Connection#createArrayOf(String, Object[])}.
 *
 * @param elementType the name of the array's element type, as the dialect's database spells it.
 * @param elements the elements, in the order of the list; an element may be {@code null}. Kept as given, in an
 *                 unmodifiable list.
 * @since 1.16
 */
public record SqlArray(String elementType, List<@Nullable Object> elements) {

    public SqlArray {
        requireNonNull(elementType, "elementType");
        elements = unmodifiableList(new ArrayList<>(elements));
    }

    /**
     * Returns the array that binds the given values on the given dialect, or {@code null} when the dialect binds no
     * array of their type: values of more than one type, values of a type the dialect does not bind as an array, or
     * no value but {@code null}. An enum is bound by its name, as a single enum value is.
     *
     * @param values the values of the list.
     * @param dialect the dialect of the database.
     * @return the array, or {@code null}.
     */
    public static @Nullable SqlArray of(List<?> values, SqlDialect dialect) {
        Class<?> type = null;
        for (var value : values) {
            if (value == null) {
                continue;
            }
            var valueType = value instanceof Enum<?> ? String.class : value.getClass();
            if (type == null) {
                type = valueType;
            } else if (type != valueType) {
                return null;
            }
        }
        if (type == null) {
            return null;
        }
        String elementType = dialect.arrayElementType(type);
        if (elementType == null) {
            return null;
        }
        var elements = new ArrayList<@Nullable Object>(values.size());
        for (var value : values) {
            elements.add(value instanceof Enum<?> e ? e.name() : value);
        }
        return new SqlArray(elementType, elements);
    }

    /**
     * Returns the elements as the array {@link java.sql.Connection#createArrayOf(String, Object[])} takes.
     *
     * @return a new array of the elements.
     */
    public @Nullable Object[] toArray() {
        return elements.toArray();
    }

    /**
     * Returns the array as an array literal, {@code {1,2,3}}, with text elements quoted; what the statement log
     * inlines for the parameter.
     */
    @Override
    public String toString() {
        var joiner = new StringJoiner(",", "{", "}");
        for (var element : elements) {
            if (element == null) {
                joiner.add("NULL");
            } else if (element instanceof String text) {
                joiner.add('"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"');
            } else {
                joiner.add(element.toString());
            }
        }
        return joiner.toString();
    }
}
