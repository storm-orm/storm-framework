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
package st.orm;

import org.jspecify.annotations.Nullable;

/**
 * A {@link Metamodel} that also carries the declared component type of the field it represents.
 *
 * <p>The component type parameter {@code V} reflects how the field is declared on the record: for eagerly fetched
 * fields, {@code V} equals the field type {@code E}; for {@code Ref} fields, {@code V} is {@code Ref<E>}. APIs bind
 * both parameters to the same type variable to require an eagerly fetched path at compile time, for example
 * {@code TypedMetamodel<T, V, V>}.</p>
 *
 * <p>{@code V} admits nullable arguments: the generated nullable metamodel chain binds it to the nullable form of
 * the component type, carrying the field's nullability in the type.</p>
 *
 * <p>The metamodels generated for record fields implement this interface via {@link AbstractMetamodel}.</p>
 *
 * @param <T> the root table type.
 * @param <E> the field type.
 * @param <V> the declared component type, possibly nullable.
 * @since 1.13
 */
public interface TypedMetamodel<T extends Data, E extends @Nullable Object, V extends @Nullable Object> extends Metamodel<T, E> {

    /**
     * Extracts the value of the field represented by this metamodel from the given record, typed as the declared
     * component type: the field type itself for eagerly fetched fields, {@code Ref<E>} for {@code Ref} fields.
     *
     * @param record the root record from which the value is extracted.
     * @return the extracted value, or {@code null} if the value cannot be resolved.
     * @since 1.13
     */
    @Override
    V getValue(T record);
}
