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

import org.jspecify.annotations.Nullable;

/**
 * Writes the class of a repository interface. Java 21 to 23 have no class-file API, so this version writes none and
 * the repository falls back to a proxy; the multi-release part of the jar holds the version that writes one on Java
 * 24 and up.
 *
 * @since 1.16
 */
final class RepositoryClassWriter {

    private RepositoryClassWriter() {
    }

    /**
     * Returns the class file of the repository class for the interface, or {@code null} where this runtime writes none.
     *
     * @param type the repository interface.
     * @param name the binary name of the class to write.
     * @param repositoryType the repository interface of the API the type extends.
     * @param entityType the entity repository interface of that API.
     * @param projectionType the projection repository interface of that API.
     * @return the class file, or {@code null}.
     */
    static byte @Nullable [] write(Class<?> type,
                                   String name,
                                   Class<?> repositoryType,
                                   Class<?> entityType,
                                   Class<?> projectionType) {
        return null;
    }
}
