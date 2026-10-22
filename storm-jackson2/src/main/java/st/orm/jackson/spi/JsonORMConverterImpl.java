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
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import st.orm.Json;
import st.orm.core.spi.ORMConverter;
import st.orm.core.template.impl.JsonConverterSupport;
import st.orm.core.template.impl.SegmentedLruCache;
import st.orm.jackson.StormModule;
import st.orm.mapping.RecordField;

/**
 * Implementation of {@link ORMConverter} that converts JSON fields to and from the database. It uses Jackson for JSON
 * serialization and deserialization.
 */
public final class JsonORMConverterImpl extends JsonConverterSupport {
    private static final SegmentedLruCache<MapperKey, JsonMapper> MAPPER_CACHE = new SegmentedLruCache<>(1024);

    private final TypeReference<?> typeReference;
    private final JsonMapper mapper;
    private final ObjectWriter writer;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public JsonORMConverterImpl(RecordField field,
                                TypeReference<?> typeReference,
                                Json json) {
        super(field);
        this.typeReference = requireNonNull(typeReference, "typeReference");
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
        var key = MapperKey.of(json, typeReference.getType(), serializerClass, deserializerClass);
        this.mapper = MAPPER_CACHE.getOrCompute(key, () -> {
            var builder = JsonMapper.builder();
            builder.findAndAddModules();
            // Set, not left to the library's defaults, which differ between Jackson versions.
            builder.configure(FAIL_ON_UNKNOWN_PROPERTIES, json.failOnUnknown());
            builder.configure(FAIL_ON_MISSING_CREATOR_PROPERTIES, json.failOnMissing());
            for (var sealedType : key.sealedTypes()) {
                builder.registerSubtypes(getPermittedSubtypes(sealedType));
            }
            // Register StormModule with supplier for dynamic RefFactory resolution.
            builder.addModule(new StormModule(JsonConverterSupport::currentRefFactory));
            // Register custom serializers/deserializers if specified.
            if (serializerClass != null || deserializerClass != null) {
                var customModule = new SimpleModule();
                if (serializerClass != null) {
                    try {
                        JsonSerializer serializerInstance = serializerClass.getDeclaredConstructor().newInstance();
                        customModule.addSerializer((Class) key.targetType(), serializerInstance);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to instantiate custom serializer: " + serializerClass, e);
                    }
                }
                if (deserializerClass != null) {
                    try {
                        JsonDeserializer deserializerInstance = deserializerClass.getDeclaredConstructor().newInstance();
                        customModule.addDeserializer((Class) key.targetType(), deserializerInstance);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to instantiate custom deserializer: " + deserializerClass, e);
                    }
                }
                builder.addModule(customModule);
            }
            return builder.build();
        });
        // Serialization carries the declared field type rather than the erased runtime type, so polymorphic
        // values write the discriminator that reading with the same declared type expects.
        this.writer = mapper.writerFor(typeReference);
    }

    private static NamedType[] getPermittedSubtypes(Class<?> sealedClass) {
        return permittedSubclasses(sealedClass).stream()
                .map(subclass -> {
                    JsonTypeName typeNameAnnotation = subclass.getAnnotation(JsonTypeName.class);
                    String typeName = typeNameAnnotation != null ? typeNameAnnotation.value() : subclass.getSimpleName();
                    return new NamedType(subclass, typeName);
                })
                .toArray(NamedType[]::new);
    }

    @Override
    protected String write(Object value) throws JsonProcessingException {
        return writer.writeValueAsString(value);
    }

    @Override
    protected Object read(String json) throws JsonProcessingException {
        return mapper.readValue(json, typeReference);
    }
}
