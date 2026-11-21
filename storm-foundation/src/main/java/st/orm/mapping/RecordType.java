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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.PersistenceException;

/**
 * Represents metadata about a data record type.
 *
 * <p>This record captures all relevant information about a data record class, including its canonical constructor,
 * annotations, and field components. It provides convenient methods for working with annotations and for instantiating
 * new record instances.</p>
 *
 * <p>A sealed entity is described by its sealed interface: it has the annotations of the interface and the fields of
 * its record subtypes, and no constructor, since a row is constructed as one of the subtypes.</p>
 *
 * @param type the data record class, or the sealed interface of a sealed entity.
 * @param constructor the canonical constructor for this data record type, or {@code null} for a sealed entity.
 * @param annotations all annotations present on the type.
 * @param fields metadata for all record components, in declaration order.
 * @since 1.7
 */
public record RecordType(Class<?> type,
                         @Nullable Constructor<?> constructor,
                         List<Annotation> annotations,
                         List<RecordField> fields) {
    public RecordType {
        requireNonNull(type, "type must not be null");
        annotations = List.copyOf(annotations);
        fields = List.copyOf(fields);
    }

    /**
     * Checks whether this record type is a {@link Data} type.
     *
     * @return {@code true} if the record implements or extends {@link Data}
     */
    public boolean isDataType() {
        return Data.class.isAssignableFrom(type);
    }

    /**
     * Returns this record type as a {@link Data} subtype, or throws an exception if the type
     * does not implement {@link Data}.
     *
     * @return the record type cast to {@code Class<? extends Data>}
     * @throws PersistenceException if the record type does not implement {@link Data}
     */
    @SuppressWarnings("unchecked")
    public Class<? extends Data> requireDataType() {
        if (!Data.class.isAssignableFrom(type)) {
            throw new PersistenceException("Data type required: %s.".formatted(type.getName()));
        }
        return (Class<? extends Data>) type;
    }

    /**
     * Checks whether an annotation of the specified type is present on this record class.
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
     * Returns all annotations of the specified type present on this record class.
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
     * Returns a single annotation of the specified type from this record class, or {@code null}
     * if not present or if multiple instances are found.
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

    /**
     * Creates a new instance of this record type using the canonical constructor.
     *
     * <p>The arguments array must contain values for all record components in declaration order,
     * matching the parameters of the canonical constructor. This method assumes the constructor
     * is already accessible.
     *
     * @param args the constructor arguments, one for each record component in declaration order
     * @return a new instance of this record type
     * @throws PersistenceException if instantiation fails, if the number of arguments is incorrect,
     *         if argument types don't match, if the constructor throws an exception, or if this is a sealed entity
     */
    public Object newInstance(Object[] args) {
        if (constructor == null) {
            throw new PersistenceException("%s is a sealed entity; instantiate one of its subtypes.".formatted(type.getName()));
        }
        try {
            try {
                // Constructor is expected to be accessible.
                return constructor.newInstance(args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        } catch (PersistenceException e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }
}
