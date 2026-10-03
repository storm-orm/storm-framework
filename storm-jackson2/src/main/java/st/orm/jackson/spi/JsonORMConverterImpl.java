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
package st.orm.jackson.spi;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
import st.orm.core.template.impl.SegmentedLruCache;
import st.orm.jackson.StormModule;
import st.orm.mapping.RecordField;

/**
 * Implementation of {@link ORMConverter} that converts JSON fields to and from the database. It uses Jackson for JSON
 * serialization and deserialization.
 */
public final class JsonORMConverterImpl implements ORMConverter {
    private static final ORMReflection REFLECTION = Providers.getORMReflection();
    private static final SegmentedLruCache<CacheKey, ObjectMapper> OBJECT_MAPPER = new SegmentedLruCache<>(1024);
    private static final ThreadLocal<RefFactory> REF_FACTORY = new ThreadLocal<>();

    private final RecordField field;
    private final TypeReference<?> typeReference;
    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    record CacheKey(Json json,
                    List<Class<?>> sealedTypes,
                    @Nullable Class<?> targetType,
                    @Nullable Class<? extends JsonSerializer<?>> serializer,
                    @Nullable Class<? extends JsonDeserializer<?>> deserializer) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public JsonORMConverterImpl(RecordField field,
                                TypeReference<?> typeReference,
                                Json json) {
        this.field = requireNonNull(field, "field");
        this.typeReference = requireNonNull(typeReference, "typeReference");
        var sealedTypes = getSealedTypes(typeReference.getType());
        // Check for custom serializer/deserializer annotations.
        var serializeAnnotation = field.getAnnotation(JsonSerialize.class);
        var deserializeAnnotation = field.getAnnotation(JsonDeserialize.class);
        Class<? extends JsonSerializer<?>> serializerClass =
                serializeAnnotation != null && serializeAnnotation.using() != JsonSerializer.None.class
                        ? (Class<? extends JsonSerializer<?>>) serializeAnnotation.using()
                        : null;
        Class<? extends JsonDeserializer<?>> deserializerClass =
                deserializeAnnotation != null && deserializeAnnotation.using() != JsonDeserializer.None.class
                        ? (Class<? extends JsonDeserializer<?>>) deserializeAnnotation.using()
                        : null;
        // Custom serializers are registered against the raw field type, so that type is part of the cache key
        // whenever one is present; without it, fields of different types sharing a serializer class would share
        // a mapper that only serves the first field's type.
        Class<?> targetType = serializerClass != null || deserializerClass != null
                ? getRawType(typeReference.getType()).orElse(Object.class)
                : null;
        this.mapper = OBJECT_MAPPER.getOrCompute(
                new CacheKey(requireNonNull(json, "json"), sealedTypes, targetType, serializerClass, deserializerClass),
                () -> {
                    var mapper = new ObjectMapper();
                    mapper.findAndRegisterModules();
                    if (!json.failOnUnknown()) {
                        mapper.disable(FAIL_ON_UNKNOWN_PROPERTIES);
                    }
                    if (!json.failOnMissing()) {
                        mapper.disable(FAIL_ON_MISSING_CREATOR_PROPERTIES);
                    }
                    for (var sealedType : sealedTypes) {
                        mapper.registerSubtypes(getPermittedSubtypes(sealedType));
                    }
                    // Register StormModule with supplier for dynamic RefFactory resolution.
                    mapper.registerModule(new StormModule(REF_FACTORY::get));
                    // Register custom serializers/deserializers if specified.
                    if (serializerClass != null || deserializerClass != null) {
                        var customModule = new SimpleModule();
                        if (serializerClass != null) {
                            try {
                                JsonSerializer serializerInstance = serializerClass.getDeclaredConstructor().newInstance();
                                customModule.addSerializer((Class) targetType, serializerInstance);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to instantiate custom serializer: " + serializerClass, e);
                            }
                        }
                        if (deserializerClass != null) {
                            try {
                                JsonDeserializer deserializerInstance = deserializerClass.getDeclaredConstructor().newInstance();
                                customModule.addDeserializer((Class) targetType, deserializerInstance);
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to instantiate custom deserializer: " + deserializerClass, e);
                            }
                        }
                        mapper.registerModule(customModule);
                    }
                    return mapper;
                });
        // Serialization carries the declared field type rather than the erased runtime type, so polymorphic
        // values write the discriminator that reading with the same declared type expects.
        this.writer = mapper.writerFor(typeReference);
    }

    private static Optional<Class<?>> getRawType(Type type) {
        if (type instanceof ParameterizedType) {
            return Optional.of((Class<?>) ((ParameterizedType) type).getRawType());
        } else if (type instanceof Class<?>) {
            return Optional.of((Class<?>) type);
        } else {
            return empty();
        }
    }

    /**
     * Collects the sealed classes appearing anywhere in the field's generic type, so that permitted subtypes are
     * registered for container-typed fields such as {@code List<Shape>} as well as top-level sealed fields.
     */
    private static List<Class<?>> getSealedTypes(Type type) {
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

    private static NamedType[] getPermittedSubtypes(Class<?> sealedClass) {
        return REFLECTION.getPermittedSubclasses(sealedClass).stream()
                .map(subclass -> {
                    JsonTypeName typeNameAnnotation = subclass.getAnnotation(JsonTypeName.class);
                    String typeName = typeNameAnnotation != null ? typeNameAnnotation.value() : subclass.getSimpleName();
                    return new NamedType(subclass, typeName);
                })
                .toArray(NamedType[]::new);
    }

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
            Object o = record == null ? null : REFLECTION.invoke(field, record);
            return singletonList(o == null ? null : new JsonString(writer.writeValueAsString(o)));
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
            return mapper.readValue((String) value, typeReference);
        } catch (JsonProcessingException e) {
            throw new SqlTemplateException(e);
        } finally {
            if (outerRefFactory == null) {
                REF_FACTORY.remove();
            } else {
                REF_FACTORY.set(outerRefFactory);
            }
        }
    }
}
