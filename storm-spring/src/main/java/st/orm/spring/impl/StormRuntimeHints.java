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
package st.orm.spring.impl;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.DecoratingProxy;
import st.orm.EntityCallbacks;
import st.orm.core.spi.TypeDiscovery;

/**
 * Registers the reachability metadata a Storm application needs to run as a GraalVM native image.
 *
 * <p>Row construction goes through the instantiators the metamodel processor generates at compile time, so
 * constructing entities and projections needs no reflection hints. What remains is registered here, driven
 * by the type index the metamodel processor writes to {@code META-INF/storm/}, so applications get their
 * types registered during Spring AOT processing without writing hints themselves:</p>
 *
 * <ul>
 *   <li><strong>Data types</strong>: Storm reads each entity's and projection's constructor parameters,
 *       accessors and annotations once to build its model.</li>
 *   <li><strong>Generated metamodels</strong>: {@code MetamodelFactory} looks up the generated
 *       {@code <Type>Metamodel} companion by name and walks its public fields; without the hint the lookup
 *       silently falls back to the slower runtime-built metamodel.</li>
 *   <li><strong>Converters</strong>: converter implementations are instantiated through their declared
 *       constructor.</li>
 *   <li><strong>Repositories</strong>: Storm implements repository interfaces as JDK dynamic proxies, and
 *       {@link RepositoryProxyingPostProcessor} wraps each repository bean in a Spring AOP proxy, which is
 *       a JDK proxy as well. A native image only supports proxies whose exact ordered interface list is
 *       registered ahead of time, so both shapes are registered per repository. Kotlin repositories
 *       additionally dispatch default methods through the compiler-generated {@code $DefaultImpls}
 *       class, which is looked up and invoked reflectively.</li>
 * </ul>
 *
 * <p>Hints may reference generated companions that do not exist for a given type (for example
 * {@code $DefaultImpls} for Java repositories); the native image builder skips metadata entries it cannot
 * resolve.</p>
 *
 * <p>The application independent shapes (the stream and prepared query guarding proxies, the JDBC statement
 * proxy, and the type index resources) ship as static reachability metadata in the storm-core and
 * storm-foundation jars and need no registration here.</p>
 *
 * <p>The index is required for these registrations: when no Data index is present on the classpath,
 * the metamodel processor did not run on the application's code and a warning is logged, and an index
 * that cannot be read fails the AOT build.</p>
 *
 * @since 1.13
 */
public class StormRuntimeHints implements RuntimeHintsRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.spring.aot");

    private static final String INDEX_DIRECTORY = "META-INF/storm/";
    private static final String DATA_INDEX = INDEX_DIRECTORY + "st.orm.Data.idx";
    private static final String CONVERTER_INDEX = INDEX_DIRECTORY + "st.orm.Converter.idx";
    private static final String REPOSITORY_INDEX = INDEX_DIRECTORY + "st.orm.repository.Repository.idx";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        ClassLoader loader = classLoader == null ? StormRuntimeHints.class.getClassLoader() : classLoader;
        if (!hasDataIndex(loader)) {
            LOGGER.warn("No Storm type index found for the application's entities on the classpath "
                    + "(META-INF/storm/st.orm.Data.idx). The native image will miss the reflection "
                    + "metadata for entities, so they will fail at runtime. Add the Storm metamodel "
                    + "processor (storm-metamodel-processor for Java, storm-metamodel-ksp for Kotlin) "
                    + "to the build so the index is generated at compile time.");
        }
        for (String typeName : readIndex(loader, DATA_INDEX)) {
            registerDataType(hints, typeName);
            // Compound primary keys and inline components are introspected like the Data types that
            // carry them but do not appear in the index themselves; resolve them through the shared
            // component walk when the type is loadable.
            try {
                Class<?> type = Class.forName(typeName, false, loader);
                for (Class<?> componentType : TypeDiscovery.getComponentTypes(type)) {
                    registerDataType(hints, componentType.getName());
                }
                // Storm creates the callbacks an entity declares through their no-argument constructor the first
                // time the entity is written, so the constructor has to survive into the image.
                EntityCallbacks declared = type.getAnnotation(EntityCallbacks.class);
                if (declared != null) {
                    for (Class<?> callbackType : declared.value()) {
                        hints.reflection().registerType(TypeReference.of(callbackType.getName()),
                                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
                    }
                }
            } catch (Throwable ignore) {
                // Entries that do not load still get their hints registered by name above.
            }
        }
        for (String typeName : readIndex(loader, CONVERTER_INDEX)) {
            hints.reflection().registerType(TypeReference.of(typeName),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
        for (String typeName : readIndex(loader, REPOSITORY_INDEX)) {
            TypeReference repository = TypeReference.of(typeName);
            hints.proxies().registerJdkProxy(repository);
            hints.proxies().registerJdkProxy(repository,
                    TypeReference.of(Serializable.class),
                    TypeReference.of(SpringProxy.class),
                    TypeReference.of(Advised.class),
                    TypeReference.of(DecoratingProxy.class));
            hints.reflection().registerType(TypeReference.of(typeName + "$DefaultImpls"),
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }
    }

    private static void registerDataType(RuntimeHints hints, String typeName) {
        // The declared fields include the static Companion instance, which kotlinx.serialization
        // reads reflectively when it resolves a serializer at runtime.
        hints.reflection().registerType(TypeReference.of(typeName),
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.DECLARED_FIELDS);
        hints.reflection().registerType(TypeReference.of(typeName + "Metamodel"),
                MemberCategory.PUBLIC_FIELDS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(TypeReference.of(typeName + "NullableMetamodel"),
                MemberCategory.PUBLIC_FIELDS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        // kotlinx.serialization resolves serializers reflectively through the generated companions.
        hints.reflection().registerType(TypeReference.of(typeName + "$serializer"),
                MemberCategory.PUBLIC_FIELDS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(TypeReference.of(typeName + "$Companion"),
                MemberCategory.PUBLIC_FIELDS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private static boolean hasDataIndex(ClassLoader loader) {
        // The Data index is the reliable marker for the metamodel processor having run on application
        // code: every Storm application declares Data types, while the framework jars themselves only
        // ship repository index entries.
        try {
            return loader.getResources(DATA_INDEX).hasMoreElements();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Storm type index " + DATA_INDEX + ".", e);
        }
    }

    private static List<String> readIndex(ClassLoader loader, String resourceName) {
        Set<String> typeNames = new LinkedHashSet<>();
        try {
            Enumeration<URL> resources = loader.getResources(resourceName);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), UTF_8))) {
                    reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .forEach(typeNames::add);
                }
            }
        } catch (IOException e) {
            // A hint that is silently dropped only surfaces as a broken native image at runtime;
            // an unreadable index must fail the AOT build instead.
            throw new UncheckedIOException("Failed to read Storm type index " + resourceName + ".", e);
        }
        return List.copyOf(typeNames);
    }
}
