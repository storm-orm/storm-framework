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

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import st.orm.Json;
import st.orm.SqlTemplateException;
import st.orm.core.spi.JsonString;
import st.orm.core.spi.Name;
import st.orm.core.spi.ORMConverter;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.spi.RefFactory;
import st.orm.mapping.RecordField;

/**
 * The part of a converter for a {@link Json} column that does not depend on the JSON library: the column the
 * converter serves, the conversion of the field's value to and from the database, the ref factory of the
 * conversion in progress, and the walk of the field's type for the sealed types whose subtypes the library has to
 * know. A converter binds a JSON library by implementing {@link #write} and {@link #read}.
 *
 * @since 1.16
 */
public abstract class JsonConverterSupport implements ORMConverter {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    /** The ref factory of the conversion in progress on this thread, for the refs the JSON holds. */
    private static final ThreadLocal<RefFactory> REF_FACTORY = new ThreadLocal<>();

    /** The field the converter serves. */
    protected final RecordField field;

    /**
     * Creates the converter for the given field.
     *
     * @param field the field the converter serves.
     */
    protected JsonConverterSupport(RecordField field) {
        this.field = requireNonNull(field, "field");
    }

    /**
     * Returns the ref factory of the conversion in progress on this thread, or {@code null} outside a conversion.
     * A deserializer of refs resolves the factory through this method at the moment it creates a ref, so one
     * cached mapper serves every conversion.
     *
     * @return the ref factory of the conversion in progress, or {@code null}.
     */
    public static @Nullable RefFactory currentRefFactory() {
        return REF_FACTORY.get();
    }

    /**
     * Writes the field's value as JSON.
     *
     * @param value the value; never {@code null}.
     * @return the JSON.
     * @throws Exception if the library refuses the value.
     */
    protected abstract String write(Object value) throws Exception;

    /**
     * Reads the field's value from JSON.
     *
     * @param json the JSON; never {@code null}.
     * @return the value; {@code null} when the JSON is the null literal.
     * @throws Exception if the library refuses the JSON.
     */
    protected abstract @Nullable Object read(String json) throws Exception;

    @Override
    public int getParameterCount() {
        return 1;
    }

    @Override
    public List<Class<?>> getParameterTypes() {
        return List.of(String.class);
    }

    @Override
    public List<Name> getColumns(NameResolver nameResolver) throws SqlTemplateException {
        return List.of(nameResolver.getName(field));
    }

    @Override
    public List<@Nullable Object> toDatabase(@Nullable Object record) throws SqlTemplateException {
        try {
            Object value = record == null ? null : REFLECTION.invoke(field, record);
            return singletonList(value == null ? null : new JsonString(write(value)));
        } catch (Throwable e) {
            throw new SqlTemplateException(e);
        }
    }

    @Override
    public @Nullable Object fromDatabase(@Nullable Object[] values, RefFactory refFactory) throws SqlTemplateException {
        Object value = values[0];
        if (value == null) {
            return null;
        }
        // A custom deserializer may issue a query, nesting another fromDatabase call on this thread. The previous
        // factory is therefore restored rather than removed, so the remaining fields of the outer value still
        // deserialize with the outer factory and produce attached refs.
        RefFactory outerRefFactory = REF_FACTORY.get();
        REF_FACTORY.set(refFactory);
        try {
            return read((String) value);
        } catch (Exception e) {
            throw new SqlTemplateException(e);
        } finally {
            if (outerRefFactory == null) {
                REF_FACTORY.remove();
            } else {
                REF_FACTORY.set(outerRefFactory);
            }
        }
    }

    /**
     * The key under which a converter caches the mapper of a field: the field's {@link Json} options, the sealed
     * types in its type, and its custom serializer and deserializer classes. A custom serializer is registered
     * against the field's raw type, so that type is part of the key whenever one is present; without it, fields of
     * different types sharing a serializer class would share a mapper that only serves the first field's type.
     *
     * @param json the field's {@link Json} options.
     * @param sealedTypes the sealed types in the field's type, whose subtypes the mapper registers.
     * @param targetType the field's raw type when a custom serializer or deserializer is present, else {@code null}.
     * @param serializer the custom serializer class, or {@code null}.
     * @param deserializer the custom deserializer class, or {@code null}.
     */
    public record MapperKey(Json json,
                            List<Class<?>> sealedTypes,
                            @Nullable Class<?> targetType,
                            @Nullable Class<?> serializer,
                            @Nullable Class<?> deserializer) {

        /**
         * Creates the key for a field of the given type.
         *
         * @param json the field's {@link Json} options.
         * @param type the field's generic type.
         * @param serializer the custom serializer class, or {@code null}.
         * @param deserializer the custom deserializer class, or {@code null}.
         * @return the key.
         */
        public static MapperKey of(Json json,
                                   Type type,
                                   @Nullable Class<?> serializer,
                                   @Nullable Class<?> deserializer) {
            Class<?> targetType = serializer != null || deserializer != null
                    ? rawType(type)
                    : null;
            return new MapperKey(requireNonNull(json, "json"), JsonConverterSupport.sealedTypes(type), targetType,
                    serializer, deserializer);
        }
    }

    /**
     * Returns the raw class of the given type: the class itself, or the raw type of a parameterized type.
     *
     * @param type the type.
     * @return the raw class; {@code Object} for a type without one, such as a type variable.
     */
    public static Class<?> rawType(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            return (Class<?>) parameterizedType.getRawType();
        }
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }

    /**
     * Returns the permitted subclasses of the given sealed class, through the reflection provider in use.
     *
     * @param sealedClass the sealed class.
     * @return the permitted subclasses.
     */
    public static List<Class<?>> permittedSubclasses(Class<?> sealedClass) {
        return List.copyOf(REFLECTION.getPermittedSubclasses(sealedClass));
    }

    /**
     * Collects the sealed classes appearing anywhere in the given generic type, so that permitted subtypes are
     * registered for container-typed fields such as {@code List<Shape>} as well as top-level sealed fields.
     *
     * @param type the field's generic type.
     * @return the sealed classes, in order of appearance.
     */
    public static List<Class<?>> sealedTypes(Type type) {
        var sealedTypes = new LinkedHashSet<Class<?>>();
        collectSealedTypes(type, sealedTypes);
        return List.copyOf(sealedTypes);
    }

    private static void collectSealedTypes(Type type, Set<Class<?>> sealedTypes) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isSealed()) {
                sealedTypes.add(clazz);
            }
        } else if (type instanceof ParameterizedType parameterizedType) {
            collectSealedTypes(parameterizedType.getRawType(), sealedTypes);
            for (Type typeArgument : parameterizedType.getActualTypeArguments()) {
                collectSealedTypes(typeArgument, sealedTypes);
            }
        } else if (type instanceof GenericArrayType arrayType) {
            collectSealedTypes(arrayType.getGenericComponentType(), sealedTypes);
        } else if (type instanceof WildcardType wildcardType) {
            for (Type bound : wildcardType.getUpperBounds()) {
                collectSealedTypes(bound, sealedTypes);
            }
        }
    }
}
