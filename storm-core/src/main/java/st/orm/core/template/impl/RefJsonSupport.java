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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Entity;
import st.orm.PK;
import st.orm.Projection;
import st.orm.Ref;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.spi.RefFactory;
import st.orm.mapping.RecordField;

/**
 * The JSON form of a {@link Ref} and the part of reading and writing it that does not depend on the JSON library.
 *
 * <p>A loaded ref writes as an object: {@code {"@entity": {...}}} for an entity, {@code {"@id": ..., "@projection":
 * {...}}} for a projection or other data, whose id cannot be taken from the value. An unloaded ref writes as its
 * id: a raw value, or the inline record of a compound key as a plain object. Reading accepts each of these forms;
 * a raw id and a compound key object create a ref through the ref factory of the conversion in progress, when
 * there is one, so the ref is attached.</p>
 *
 * @since 1.16
 */
public final class RefJsonSupport {

    /** The field of the loaded projection form that carries the id. */
    public static final String ID_FIELD = "@id";

    /** The field of the loaded entity form that carries the entity. */
    public static final String ENTITY_FIELD = "@entity";

    /** The field of the loaded projection form that carries the projection. */
    public static final String PROJECTION_FIELD = "@projection";

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    // ConcurrentHashMap does NOT allow computeIfAbsent mapping functions to return null.
    private static final Class<?> NO_PK = Void.class;
    private static final Map<Class<?>, Class<?>> PK_CACHE = new ConcurrentHashMap<>();

    private RefJsonSupport() {
    }

    /**
     * Returns the type of the primary key of the given data type.
     *
     * @param target the data type a ref points at.
     * @return the type of its {@link PK} field, or {@code null} for a type without one.
     */
    public static @Nullable Class<?> pkType(Class<?> target) {
        Class<?> cached = PK_CACHE.computeIfAbsent(target, ignore -> {
            var type = REFLECTION.getRecordType(target).fields().stream()
                    .filter(field -> field.isAnnotationPresent(PK.class))
                    .findFirst()
                    .map(RecordField::type)
                    .orElse(null);
            return type == null ? NO_PK : type;
        });
        return cached == NO_PK ? null : cached;
    }

    /**
     * Returns whether the given primary key type is a record, the inline record of a compound key, which an
     * unloaded ref writes as a plain object.
     *
     * @param pkType the primary key type.
     * @return {@code true} for a compound key.
     */
    public static boolean isCompoundKey(Class<?> pkType) {
        return REFLECTION.findRecordType(pkType).isPresent();
    }

    /**
     * Returns the given integral id as an {@code Integer} when it fits, else as a {@code Long}; the form of a raw
     * id read for a type whose key type is unknown.
     *
     * @param value the id.
     * @return the id as an {@code Integer} or a {@code Long}.
     */
    public static Object integralId(long value) {
        // Two returns rather than one conditional, which would promote both branches to long.
        if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return (int) value;
        }
        return value;
    }

    /**
     * Creates the unloaded ref for the given id: through the ref factory of the conversion in progress, so the
     * ref is attached, or detached without one.
     *
     * @param targetClass the data type the ref points at.
     * @param id the id, or {@code null} for no ref.
     * @param refFactorySupplier the supplier of the ref factory, or {@code null} for detached refs.
     * @return the ref, or {@code null} for a {@code null} id.
     * @param <T> the data type.
     */
    public static <T extends Data> @Nullable Ref<T> refOf(Class<T> targetClass,
                                                           @Nullable Object id,
                                                           @Nullable Supplier<@Nullable RefFactory> refFactorySupplier) {
        if (id == null) {
            return null;
        }
        RefFactory refFactory = refFactorySupplier != null ? refFactorySupplier.get() : null;
        if (refFactory != null) {
            return refFactory.create(targetClass, id);
        }
        return Ref.of(targetClass, id);
    }

    /**
     * Creates the loaded ref for the entity read from the {@value #ENTITY_FIELD} field.
     *
     * @param entity the value read, or {@code null} for no ref.
     * @return the ref, or {@code null} for a {@code null} value.
     * @throws IllegalArgumentException if the value is not an entity; the message says so.
     */
    @SuppressWarnings({"rawtypes"})
    public static @Nullable Ref<?> loadedRef(@Nullable Data entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Entity<?> e) {
            return Ref.of((Entity) e);
        }
        throw new IllegalArgumentException("@entity must deserialize to an Entity");
    }

    /**
     * Creates the loaded ref for the value read from the {@value #PROJECTION_FIELD} field with the id read from
     * the {@value #ID_FIELD} field.
     *
     * @param targetClass the data type the ref points at.
     * @param data the value read, or {@code null} for no ref.
     * @param id the id read, or {@code null} when the field held none.
     * @return the ref, or {@code null} for a {@code null} value.
     * @throws IllegalArgumentException if the value is neither an entity nor a projection and no id was read; the
     *                                  message says so.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static @Nullable Ref<?> loadedRef(Class<? extends Data> targetClass, @Nullable Data data, @Nullable Object id) {
        if (data == null) {
            return null;
        }
        if (data instanceof Entity<?> e) {
            return Ref.of((Entity) e);
        }
        if (data instanceof Projection<?> p) {
            return Ref.of((Projection) p, id);
        }
        if (id != null) {
            return Ref.of((Class) targetClass, id);
        }
        // Should not happen for @projection, since @id is required.
        throw new IllegalArgumentException("@projection requires a non-null @id");
    }
}
