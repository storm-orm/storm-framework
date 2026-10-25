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

import static java.util.Optional.empty;

import java.lang.reflect.Type;
import java.util.Optional;
import st.orm.Json;
import st.orm.core.spi.ORMConverter;
import st.orm.core.spi.ORMConverterProvider;
import st.orm.core.spi.Orderable.AfterAny;
import st.orm.mapping.RecordField;
import tools.jackson.core.type.TypeReference;

/**
 * Provides an ORM converter for JSON annotated record components.
 *
 * <p>This implementation retrieves the {@link Json} annotation from the record component and creates a
 * {@link JsonORMConverterImpl} if the annotation is present. Jackson converts any type, so this provider is ordered
 * after any other: a converter the field selects, or a kotlinx.serialization converter for a type it has a serializer
 * for, wins over Jackson for that field.</p>
 */
@AfterAny
public class JsonORMConverterProviderImpl implements ORMConverterProvider {
    /**
     * Retrieves the converter for the specified record field.
     *
     * @param field the record field for which to get the converter.
     * @return an Optional containing the ORMConverter if available, or empty if not supported.
     */
    @Override
    public Optional<ORMConverter> getConverter(RecordField field) {
        Json json = field.getAnnotation(Json.class);
        if (json == null) {
            return empty();
        }
        TypeReference<?> typeRef = new TypeReference<>() {
            public Type getType() {
                return field.genericType();
            }
        };
        return Optional.of(new JsonORMConverterImpl(field, typeRef, json));
    }
}
