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

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.annotation.JsonSerialize.Typing;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.Annotated;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.module.SimpleModule;

/**
 * Serializes a collection or map property by its declared type when the declared type settles what the JSON holds:
 * every element, key and value type is final, or a collection or map of such types. Jackson otherwise picks the
 * serializer from the collection's runtime class, which for a standard library collection of Kotlin, such as the one
 * {@code emptySet()} returns, means introspecting that class through Kotlin reflection; a native image refuses this
 * for a class it has no metadata for. The JSON is the same either way, as a collection writes as an array and a map
 * as an object whatever class holds it.
 *
 * <p>A property whose element type is open, such as a list of a sealed interface, keeps Jackson's runtime typing, so
 * the subtype's properties are written as before. A property that names its typing with
 * {@code @JsonSerialize(typing = ...)} keeps that.</p>
 *
 * @since 1.16
 */
final class DeclaredContainerTypeModule extends SimpleModule {

    DeclaredContainerTypeModule() {
        super("StormDeclaredContainerType");
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        // Appended, so an explicit @JsonSerialize(typing = ...) on the property wins.
        context.appendAnnotationIntrospector(new Introspector());
    }

    static final class Introspector extends NopAnnotationIntrospector {

        @Override
        public @Nullable Typing findSerializationTyping(MapperConfig<?> config, Annotated annotated) {
            if (annotated instanceof AnnotatedMember member && isSettledContainer(member.getType())) {
                return Typing.STATIC;
            }
            return null;
        }

        /**
         * Returns whether the type is a collection or map whose element, key and value types are final, or settled
         * containers themselves, so the declared type determines the JSON of any value it holds.
         */
        static boolean isSettledContainer(JavaType type) {
            if (!type.isContainerType()) {
                return false;
            }
            JavaType keyType = type.getKeyType();
            if (keyType != null && !isSettled(keyType)) {
                return false;
            }
            JavaType contentType = type.getContentType();
            return contentType != null && isSettled(contentType);
        }

        private static boolean isSettled(JavaType type) {
            return type.isFinal() || isSettledContainer(type);
        }
    }
}
