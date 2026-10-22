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
package st.orm.jackson;

import static st.orm.core.template.impl.RefJsonSupport.ENTITY_FIELD;
import static st.orm.core.template.impl.RefJsonSupport.ID_FIELD;
import static st.orm.core.template.impl.RefJsonSupport.PROJECTION_FIELD;
import static st.orm.core.template.impl.RefJsonSupport.integralId;
import static st.orm.core.template.impl.RefJsonSupport.isCompoundKey;
import static st.orm.core.template.impl.RefJsonSupport.loadedRef;
import static st.orm.core.template.impl.RefJsonSupport.pkType;
import static st.orm.core.template.impl.RefJsonSupport.refOf;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Entity;
import st.orm.Ref;
import st.orm.core.spi.RefFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson module for serializing and deserializing Storm ORM Data types.
 *
 * <p>Handles:
 * <ul>
 *   <li>Regular Data/Entity/Projection records (standard serialization)</li>
 *   <li>Ref fields: serializes as the contained entity if loaded, or just the id if not</li>
 *   <li>Deserialization of Ref fields: creates attached Refs via RefFactory if provided,
 *       otherwise creates DetachedRefs</li>
 * </ul>
 *
 * <p>Serialization formats:
 * <ul>
 *   <li>Entity (loaded): {@code {"@entity": {...}}}</li>
 *   <li>Projection (loaded): {@code {"@id": ..., "@projection": {...}}}</li>
 *   <li>Unloaded: raw id value (e.g., {@code 42} or {@code "abc"})</li>
 * </ul>
 *
 * <p>On deserialization, raw id values use the RefFactory (if provided) to create attached Refs,
 * while object formats with {@code @entity}/{@code @projection} always create Refs via {@code Ref.of()}.
 *
 * <p>Also supports nullable Ref values inside collections, e.g. {@code List<Ref<Foo>>} containing {@code null}.
 */
public class StormModule extends SimpleModule {

    /**
     * Creates a StormModule without a RefFactory.
     * Ref fields with only an id will be deserialized as DetachedRefs.
     */
    public StormModule() {
        this((RefFactory) null);
    }

    /**
     * Creates a StormModule with the specified RefFactory.
     *
     * @param refFactory factory for creating attached Ref instances during deserialization,
     *                   or null to create DetachedRefs
     */
    public StormModule(@Nullable RefFactory refFactory) {
        this(refFactory != null ? () -> refFactory : null);
    }

    /**
     * Creates a StormModule with a RefFactory supplier.
     *
     * <p>This constructor is useful when the RefFactory needs to be resolved dynamically,
     * for example from a ThreadLocal, while still allowing the ObjectMapper to be cached.
     *
     * @param refFactorySupplier supplier for the RefFactory, or null to create DetachedRefs
     */
    @SuppressWarnings("unchecked")
    public StormModule(@Nullable Supplier<RefFactory> refFactorySupplier) {
        super("StormModule");
        addSerializer((Class<Ref<?>>) (Class<?>) Ref.class, new RefSerializer(null));
        addDeserializer((Class<Ref<?>>) (Class<?>) Ref.class,
                new RefDeserializer(refFactorySupplier, null, null));
    }

    /**
     * Resolve the Ref target type {@code T} from:
     * <ul>
     *   <li>{@code Ref<T>}</li>
     *   <li>{@code List<Ref<T>>}, {@code Set<Ref<T>>}, {@code Map<K, Ref<T>>}, etc (via content/value type)</li>
     * </ul>
     */
    @Nullable
    private static JavaType resolveRefTargetType(@Nullable JavaType type) {
        if (type == null) return null;
        // If container (List/Set/Map), unwrap to element/value type.
        if (type.isContainerType()) {
            type = type.getContentType();
            if (type == null) return null;
        }
        if (!Ref.class.isAssignableFrom(type.getRawClass())) {
            return null;
        }
        // Ref<T> => T.
        return type.containedType(0);
    }

    /**
     * Serializer for Ref instances; the forms are those of {@link st.orm.core.template.impl.RefJsonSupport}.
     */
    private static class RefSerializer extends StdSerializer<Ref<?>> {

        @Nullable
        private final Class<?> pkType;

        RefSerializer(@Nullable Class<?> pkType) {
            super(Ref.class, false);
            this.pkType = pkType;
        }

        @Override
        public ValueSerializer<?> createContextual(SerializationContext ctxt, @Nullable BeanProperty property) {
            JavaType base = property != null ? property.getType() : null;
            JavaType refTargetType = resolveRefTargetType(base);
            if (refTargetType == null) {
                return this;
            }
            return new RefSerializer(pkType(refTargetType.getRawClass()));
        }

        @Override
        public void serialize(Ref<?> ref, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            Object loaded = ref.getOrNull();
            if (loaded != null) {
                // Loaded - serialize as object with @entity or @projection
                gen.writeStartObject();
                if (loaded instanceof Entity<?>) {
                    gen.writeName(ENTITY_FIELD);
                    ctxt.writeValue(gen, loaded);
                } else {
                    // Projection or other Data - include id since it can't be extracted
                    gen.writeName(ID_FIELD);
                    serializeId(ref.id(), gen, ctxt);
                    gen.writeName(PROJECTION_FIELD);
                    ctxt.writeValue(gen, loaded);
                }
                gen.writeEndObject();
            } else {
                // Unloaded - serialize as raw id value
                serializeId(ref.id(), gen, ctxt);
            }
        }

        private void serializeId(Object id, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (id != null && pkType != null) {
                ValueSerializer<Object> serializer = ctxt.findValueSerializer(pkType);
                serializer.serialize(id, gen, ctxt);
            } else {
                ctxt.writeValue(gen, id);
            }
        }
    }

    /**
     * Deserializer for Ref instances; the forms are those of {@link st.orm.core.template.impl.RefJsonSupport}.
     *
     * <p>Also supports {@code null} values (e.g. nullable elements in {@code List<Ref<T>>}).
     */
    private static class RefDeserializer extends StdDeserializer<Ref<?>> {

        @Nullable
        private final Supplier<RefFactory> refFactorySupplier;

        @Nullable
        private final JavaType targetType;

        @Nullable
        private final Class<?> pkType;

        RefDeserializer(@Nullable Supplier<RefFactory> refFactorySupplier,
                        @Nullable JavaType targetType,
                        @Nullable Class<?> pkType) {
            super(Ref.class);
            this.refFactorySupplier = refFactorySupplier;
            this.targetType = targetType;
            this.pkType = pkType;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, @Nullable BeanProperty property) {
            JavaType base = property != null ? property.getType() : context.getContextualType();
            JavaType refTargetType = resolveRefTargetType(base);
            Class<?> resolvedPkType = null;
            if (refTargetType != null) {
                resolvedPkType = pkType(refTargetType.getRawClass());
            }
            return new RefDeserializer(refFactorySupplier, refTargetType, resolvedPkType);
        }

        @Override
        @Nullable
        public Ref<?> deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            // Jackson sometimes calls deserialize with currentToken == null.
            if (parser.currentToken() == null) {
                parser.nextToken();
            }
            if (parser.currentToken() == JsonToken.VALUE_NULL) {
                // This is crucial for: List<Ref<T>> with nullable elements.
                return null;
            }
            if (targetType == null) {
                throw DatabindException.from(parser, "Cannot determine Ref target type");
            }
            @SuppressWarnings("unchecked")
            Class<Data> targetClass = (Class<Data>) targetType.getRawClass();
            return switch (parser.currentToken()) {
                case START_OBJECT -> deserializeObject(parser, context, targetClass);
                case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_STRING ->
                        refOf(targetClass, deserializeId(parser, context), refFactorySupplier);
                default -> throw DatabindException.from(
                        parser,
                        "Unexpected token " + parser.currentToken() + " when deserializing Ref"
                );
            };
        }

        private Ref<?> deserializeObject(JsonParser parser, DeserializationContext ctx, Class<Data> targetClass)
                throws JacksonException {
            ObjectNode node = parser.readValueAsTree();
            try {
                if (node.has(ENTITY_FIELD)) {
                    JsonNode entityNode = node.get(ENTITY_FIELD);
                    Data entity = entityNode.isNull() ? null : ctx.readTreeAsValue(entityNode, targetType);
                    return loadedRef(entity);
                } else if (node.has(PROJECTION_FIELD)) {
                    if (!node.has(ID_FIELD)) {
                        throw DatabindException.from(parser, "@projection requires @id field");
                    }
                    Object id = deserializeIdFromNode(node.get(ID_FIELD), ctx);
                    JsonNode projectionNode = node.get(PROJECTION_FIELD);
                    Data projection = projectionNode.isNull() ? null : ctx.readTreeAsValue(projectionNode, targetType);
                    return loadedRef(targetClass, projection, id);
                } else if (pkType != null && isCompoundKey(pkType)) {
                    // A plain object is the inline record of a compound PK; unloaded refs serialize compound PKs
                    // this way.
                    return refOf(targetClass, deserializeIdFromNode(node, ctx), refFactorySupplier);
                } else {
                    throw DatabindException.from(parser, "Ref object must contain @entity or @projection field");
                }
            } catch (IllegalArgumentException e) {
                throw DatabindException.from(parser, e.getMessage());
            }
        }

        private Object deserializeIdFromNode(JsonNode node, DeserializationContext ctx) throws JacksonException {
            if (pkType != null) {
                return ctx.readTreeAsValue(node, pkType);
            }
            // Fallback for unknown PK type.
            if (node.isInt()) {
                return node.intValue();
            } else if (node.isLong()) {
                return node.longValue();
            } else if (node.isDouble()) {
                return node.doubleValue();
            } else if (node.isTextual()) {
                return node.textValue();
            } else {
                return ctx.readTreeAsValue(node, Object.class);
            }
        }

        private Object deserializeId(JsonParser parser, DeserializationContext ctx) throws JacksonException {
            if (pkType != null) {
                return ctx.readValue(parser, pkType);
            }
            // Fallback for unknown PK type.
            return switch (parser.currentToken()) {
                case VALUE_NUMBER_INT -> integralId(parser.getLongValue());
                case VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
                case VALUE_STRING -> parser.getText();
                default -> throw DatabindException.from(parser,
                        "Cannot deserialize id from %s.".formatted(parser.currentToken()));
            };
        }
    }
}
