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
package st.orm.mapping;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.PersistenceException;

/**
 * Represents metadata about a field in a data record type.
 *
 * <p>This record captures all relevant information about a record fields, including its type information, annotations,
 * and the accessor method used to retrieve its value. It provides convenient methods for working with annotations and
 * for invoking the accessor method.</p>
 *
 * @param declaringType the class that declares this record field.
 * @param name the name of the record field.
 * @param type the raw type of the field.
 * @param genericType the generic type of the field, preserving type parameters.
 * @param nullable whether the field value can be null.
 * @param mutable whether the field value can be modified.
 * @param method the accessor method for this field, or {@code null} for a field no record component carries, such
 *               as a query column.
 * @param annotations all annotations present on this field.
 * @since 1.7
 */
public record RecordField(Class<?> declaringType,
                          String name,
                          Class<?> type,
                          Type genericType,
                          boolean nullable,
                          boolean mutable,
                          @Nullable Method method,
                          List<Annotation> annotations) {
    public RecordField {
        requireNonNull(declaringType, "declaringType must not be null");
        requireNonNull(name, "name must not be null");
        requireNonNull(type, "type must not be null");
        requireNonNull(genericType, "genericType must not be null");
        annotations = List.copyOf(annotations);
    }

    /**
     * Checks whether this field's type is a {@link Data} type.
     *
     * @return {@code true} if the field type implements or extends {@link Data}
     */
    public boolean isDataType() {
        return Data.class.isAssignableFrom(type);
    }

    /**
     * Returns the field type as a {@link Data} subtype, or throws an exception if the type
     * does not implement {@link Data}.
     *
     * @return the field type cast to {@code Class<? extends Data>}
     * @throws PersistenceException if the field type does not implement {@link Data}
     */
    @SuppressWarnings("unchecked")
    public Class<? extends Data> requireDataType() {
        if (!Data.class.isAssignableFrom(type)) {
            throw new PersistenceException("Data type required.");
        }
        return (Class<? extends Data>) type;
    }

    /**
     * Checks whether an annotation of the specified type is present on this field.
     *
     * <p>This method properly handles repeatable annotations, finding them whether they
     * appear directly or wrapped in their container annotation.
     *
     * @param annotationType the annotation type to look for
     * @return {@code true} if at least one annotation of the specified type is present
     */
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return Annotations.isAnnotationPresent(annotations, annotationType);
    }

    /**
     * Returns all annotations of the specified type present on this field.
     *
     * <p>This method properly handles repeatable annotations by unwrapping them from their
     * container annotation when multiple instances are present.
     *
     * @param <A> the type of the annotation
     * @param annotationType the annotation type to retrieve
     * @return an array of all matching annotations, or an empty array if none are found
     */
    public <A extends Annotation> A[] getAnnotations(Class<A> annotationType) {
        return Annotations.getAnnotations(annotations, annotationType);
    }

    /**
     * Returns a single annotation of the specified type from this field, or {@code null} if
     * not present or if multiple instances are found.
     *
     * <p>If multiple instances of a repeatable annotation are present, this method returns
     * {@code null} to indicate ambiguity. Use {@link #getAnnotations(Class)} to retrieve
     * all instances.
     *
     * @param <A> the type of the annotation
     * @param annotationType the annotation type to retrieve
     * @return the annotation if exactly one instance is present, otherwise {@code null}
     */
    public <A extends Annotation> @Nullable A getAnnotation(Class<A> annotationType) {
        return Annotations.getAnnotation(annotations, annotationType);
    }
}
