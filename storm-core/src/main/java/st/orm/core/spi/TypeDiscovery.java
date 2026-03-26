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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import st.orm.Converter;
import st.orm.Data;

public final class TypeDiscovery {

    private static final String INDEX_DIRECTORY = "META-INF/storm/";
    private static final String DATA_TYPE = "st.orm.Data";
    private static final String CONVERTER_TYPE = "st.orm.Converter";
    private static final String REPOSITORY_TYPE = "st.orm.repository.Repository";

    private TypeDiscovery() {
    }

    /**
     * Returns all discovered subtypes of st.orm.Data based on the index file.
     */
    public static List<Class<? extends Data>> getDataTypes() {
        return loadTypes(DATA_TYPE, Data.class);
    }

    /**
     * Returns all discovered subtypes of st.orm.Converter based on the index file.
     */
    public static List<Class<? extends Converter<?, ?>>> getConverterTypes() {
        //noinspection unchecked
        return (List<Class<? extends Converter<?, ?>>>) (Object) loadTypes(CONVERTER_TYPE, Converter.class);
    }

    /**
     * Returns all discovered subtypes of st.orm.repository.Repository based on the index file.
     *
     * <p>The index is generated at compile time by the Storm metamodel processor (annotation processor or KSP).
     * It contains all interfaces in the user's project that extend {@code EntityRepository} or
     * {@code ProjectionRepository}.</p>
     *
     * <p>The type check is intentionally lenient: the index is already curated at compile time, and the
     * repository interface may come from different modules ({@code st.orm.core.repository.Repository},
     * {@code st.orm.repository.Repository} in Java 21 or Kotlin). Checking against a single base type
     * would silently reject valid entries.</p>
     */
    public static List<Class<?>> getRepositoryTypes() {
        return loadClasses(REPOSITORY_TYPE);
    }

    @SuppressWarnings("SameParameterValue")
    private static List<Class<?>> loadClasses(@Nonnull String typeFqName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = TypeDiscovery.class.getClassLoader();
        }
        String resourceName = INDEX_DIRECTORY + typeFqName + ".idx";
        List<String> classNames = loadResourceLines(classLoader, resourceName);
        if (classNames.isEmpty()) {
            return List.of();
        }
        List<Class<?>> result = new ArrayList<>();
        for (String fqClassName : new LinkedHashSet<>(classNames)) {
            try {
                result.add(Class.forName(fqClassName, false, classLoader));
            } catch (Throwable ignore) {
                // Skip bad entries or missing classes.
            }
        }
        return result;
    }

    private static <T> List<Class<? extends T>> loadTypes(@Nonnull String typeFqName, @Nonnull Class<T> expectedType) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = TypeDiscovery.class.getClassLoader();
        }
        String resourceName = INDEX_DIRECTORY + typeFqName + ".idx";
        List<String> classNames = loadResourceLines(classLoader, resourceName);
        if (classNames.isEmpty()) {
            return List.of();
        }
        List<Class<? extends T>> result = new ArrayList<>();
        for (String fqClassName : new LinkedHashSet<>(classNames)) {
            try {
                Class<?> cls = Class.forName(fqClassName, false, classLoader);
                if (expectedType.isAssignableFrom(cls)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends T> cast = (Class<? extends T>) cls;
                    result.add(cast);
                }
            } catch (Throwable ignore) {
                // Skip bad entries or missing classes.
            }
        }
        return result;
    }

    private static List<String> loadResourceLines(@Nonnull ClassLoader classLoader, @Nonnull String resourceName) {
        try {
            Enumeration<URL> resources = classLoader.getResources(resourceName);
            if (!resources.hasMoreElements()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8)
                )) {
                    reader.lines()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(lines::add);
                }
            }
            return lines;
        } catch (IOException e) {
            return List.of();
        }
    }
}
