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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import st.orm.Data;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Provides pluggable reflection support for the ORM to support different JVM languages, such as Java and Kotlin.
 *
 * <p>The Java implementation ({@code DefaultORMReflectionImpl}) handles Java records using the standard
 * {@link java.lang.reflect.RecordComponent} API. The Kotlin implementation ({@code ORMReflectionImpl}) additionally
 * handles Kotlin data classes using the Kotlin reflection API ({@code KClass}, {@code KProperty1}, etc.), falling back
 * to the Java implementation for plain Java records.</p>
 */
public interface ORMReflection {

    /**
     * Returns the primary key value of the specified {@link Data} instance.
     *
     * <p>Locates the first field annotated with {@link PK} via {@link #getRecordType(Class)} and invokes its accessor
     * to retrieve the value. The result is cached per class for subsequent lookups.</p>
     *
     * @param data the data instance to extract the primary key from.
     * @return the primary key value.
     * @throws PersistenceException if no {@code @PK}-annotated field is found.
     */
    Object getId(@Nonnull Data data);

    /**
     * Returns the value of the record component at the specified index.
     *
     * <p>Uses {@link #getRecordType(Class)} to obtain the field list, then invokes the accessor for the field at the
     * given position. For Java records this is the record component accessor; for Kotlin data classes it is the
     * Kotlin property getter.</p>
     *
     * @param record the record instance (Java record or Kotlin data class).
     * @param index  the zero-based index of the component.
     * @return the component value.
     * @throws PersistenceException if the type is not a recognized record type or the index is out of bounds.
     */
    Object getRecordValue(@Nonnull Object record, int index);

    /**
     * Returns an {@link Optional} containing the {@link RecordType} descriptor for the specified class if it is a
     * recognized record type, or an empty {@code Optional} otherwise.
     *
     * <p>The Java implementation recognizes Java {@code record} classes and builds the descriptor from
     * {@link java.lang.reflect.RecordComponent} metadata. The Kotlin implementation additionally recognizes Kotlin
     * data classes (detected via the {@code @Metadata} annotation and {@code KClass.isData()}) and builds the
     * descriptor from the primary constructor parameters and corresponding property accessors.</p>
     *
     * <p>Results are cached per class.</p>
     *
     * @param type the class to inspect.
     * @return the record type descriptor, or empty if the class is not a recognized record type.
     */
    Optional<RecordType> findRecordType(@Nonnull Class<?> type);

    /**
     * Returns the {@link RecordType} descriptor for the specified class, throwing if the class is not a recognized
     * record type.
     *
     * @param type the class to inspect.
     * @return the record type descriptor.
     * @throws PersistenceException if the class is not a recognized record type.
     */
    default RecordType getRecordType(@Nonnull Class<?> type) {
        return findRecordType(type)
                .orElseThrow(() -> new PersistenceException("Record type expected: %s.".formatted(type.getName())));
    }

    /**
     * Returns whether the specified object represents a type reference that this reflection implementation can handle.
     *
     * <p>The Java implementation returns {@code true} for {@link Class} instances. The Kotlin implementation
     * additionally returns {@code true} for {@code KClass} instances.</p>
     *
     * @param o the object to test.
     * @return {@code true} if this implementation can resolve the object to a Java class.
     */
    boolean isSupportedType(@Nonnull Object o);

    /**
     * Resolves a type reference to its corresponding Java {@link Class}.
     *
     * <p>The Java implementation expects a {@link Class} that implements {@link Data} and returns it directly. The
     * Kotlin implementation additionally handles {@code KClass} instances by mapping them to their Java class via
     * {@code JvmClassMappingKt.getJavaClass}.</p>
     *
     * @param o the type reference ({@link Class} or {@code KClass}).
     * @return the resolved Java class.
     * @throws PersistenceException if the object is not a supported type reference or is not a {@link Data} type.
     */
    Class<?> getType(@Nonnull Object o);

    /**
     * Resolves a type reference to its corresponding Java {@link Class}, cast to {@code Class<? extends Data>}.
     *
     * <p>Behaves like {@link #getType(Object)} but returns a {@code Data}-bounded type. Throws if the resolved class
     * does not implement {@link Data}.</p>
     *
     * @param o the type reference ({@link Class} or {@code KClass}).
     * @return the resolved Java class as a {@code Data} subtype.
     * @throws PersistenceException if the object is not a supported type reference or is not a {@link Data} type.
     */
    Class<? extends Data> getDataType(@Nonnull Object o);

    /**
     * Returns whether the specified object is a default value for its type.
     *
     * <p>Returns {@code true} for: {@code null}; primitive wrappers with their default value ({@code 0},
     * {@code false}, {@code '\u0000'}); and record instances whose every component is itself a default value
     * (checked recursively).</p>
     *
     * @param o the value to test, may be {@code null}.
     * @return {@code true} if the value is considered a default.
     */
    boolean isDefaultValue(@Nullable Object o);

    /**
     * Returns the permitted subclasses of the specified sealed class.
     *
     * <p>The Java implementation delegates to {@link Class#getPermittedSubclasses()}. The Kotlin implementation uses
     * {@code KClass.getSealedSubclasses()} to also handle Kotlin sealed classes.</p>
     *
     * @param sealedClass the sealed class to get the permitted subclasses for.
     * @return a list of permitted subclasses of the specified sealed class.
     */
    <T> List<Class<? extends T>> getPermittedSubclasses(@Nonnull Class<T> sealedClass);

    /**
     * Returns whether the specified method is a default method that can be invoked through a proxy.
     *
     * <p>The Java implementation checks {@link Method#isDefault()}. The Kotlin implementation additionally returns
     * {@code true} for methods declared on classes annotated with {@code @Metadata} (indicating Kotlin default
     * implementations compiled into a {@code DefaultImpls} companion class).</p>
     *
     * @param method the method to test.
     * @return {@code true} if the method is a default method.
     */
    boolean isDefaultMethod(@Nonnull Method method);

    /**
     * Invokes the accessor for the specified {@link RecordField} on the given record instance and returns the result.
     *
     * <p>Uses {@link java.lang.invoke.MethodHandle}-based invocation for performance, falling back to reflective
     * invocation when the method handle cannot be obtained (e.g., due to module restrictions).</p>
     *
     * @param field  the record field whose accessor should be invoked.
     * @param record the record instance.
     * @return the field value.
     * @throws PersistenceException if the invocation fails.
     */
    Object invoke(@Nonnull RecordField field, @Nonnull Object record);

    /**
     * Invokes a default or Kotlin-compiled default method on a proxy instance.
     *
     * <p>The Java implementation uses {@link java.lang.invoke.MethodHandles} to call the default method via
     * {@code findSpecial}. The Kotlin implementation locates the static {@code DefaultImpls} companion class and
     * invokes the corresponding static method, passing the proxy as the first argument.</p>
     *
     * @param proxy  the proxy instance on which the method was called.
     * @param method the default method to invoke.
     * @param args   the method arguments.
     * @return the return value of the method.
     * @throws Throwable if the invocation fails.
     */
    Object execute(@Nonnull Object proxy, @Nonnull Method method, @Nonnull Object... args) throws Throwable;
}
