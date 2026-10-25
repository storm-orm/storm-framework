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
package st.orm.core.graal;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.EntityCallbacks;
import st.orm.core.spi.TypeDiscovery;

/**
 * GraalVM native-image feature that registers the application's Storm types, driven by the compile-time
 * type index the metamodel processor writes to {@code META-INF/storm/}.
 *
 * <p>Spring applications receive these registrations through the Spring AOT hints in storm-spring. This
 * feature provides the equivalent for applications without an AOT phase, such as Ktor services and plain
 * JVM applications. It activates automatically through this jar's {@code native-image.properties}
 * whenever storm-core is on the image classpath, so no configuration is needed.</p>
 *
 * <p>Per Data type (including compound primary keys and inline components, resolved through
 * {@link TypeDiscovery#getComponentTypes(Class)}), the declared constructors and public methods are
 * registered for reflection (model introspection), along with the generated metamodel companion classes.
 * Per repository interface, the JDK proxy shape Storm creates behind {@code ORMTemplate.repository(..)}
 * is registered, along with the Kotlin {@code $DefaultImpls} class that carries default method bodies.
 * Converters are registered for reflective construction.</p>
 *
 * @since 1.13
 */
public final class StormFeature implements Feature {

    /**
     * Receives the registrations the feature makes. The image build registers through {@link RuntimeReflection} and
     * {@link RuntimeProxyCreation}, which exist in the build only; behind this seam the registration runs in a plain
     * JVM too, where a test reads what the type index makes the feature register.
     */
    interface Registrar {

        /** Returns the class of the name on the image classpath, or {@code null} for none. */
        @Nullable Class<?> findClassByName(String name);

        /** Registers the class for reflection. */
        void register(Class<?> type);

        /** Registers the constructors or methods for reflective invocation. */
        void register(Executable... executables);

        /** Registers the fields for reflective access. */
        void register(Field... fields);

        /** Registers the permitted subclasses of the sealed type for reflection. */
        void registerAllPermittedSubclasses(Class<?> type);

        /** Registers the interface for JDK proxy creation. */
        void registerProxy(Class<?> type);
    }

    /** The counts of what the feature registered, as the build output reports them. */
    record Summary(int dataTypes, int resultTypes, int callbackTypes, int converterTypes, int repositoryTypes) {}

    /** The data types registered so far: a sealed hierarchy is reached from each of its types and registered once. */
    private final Set<Class<?>> registered = new HashSet<>();

    @Override
    public String getDescription() {
        return "Registers Storm entities, query result types, entity callbacks, converters, and repositories from the compile-time type index.";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (!TypeDiscovery.isIndexAvailable()) {
            System.err.println("Storm: no type index found for the application's entities on the image "
                    + "classpath (META-INF/storm/st.orm.Data.idx). Entities will not be registered for "
                    + "reflection, so they will fail at runtime in the native image. Add the Storm "
                    + "metamodel processor (storm-metamodel-processor for Java, storm-metamodel-ksp for "
                    + "Kotlin) to the build so the index is generated at compile time.");
        }
        Summary summary = register(new ImageRegistrar(access));
        System.out.printf(
                "Storm: registered %d data types, %d query result types, %d entity callbacks, %d converters, and "
                        + "%d repositories from the type index.%n",
                summary.dataTypes(), summary.resultTypes(), summary.callbackTypes(), summary.converterTypes(),
                summary.repositoryTypes());
    }

    /**
     * Registers the types the index names, and the types reached from them, with the registrar.
     *
     * @return the counts of what was registered.
     */
    Summary register(Registrar registrar) {
        int callbackTypes = 0;
        for (Class<?> type : TypeDiscovery.getDataTypes()) {
            registerDataType(registrar, type);
            for (Class<?> componentType : TypeDiscovery.getComponentTypes(type)) {
                registerDataType(registrar, componentType);
            }
            callbackTypes += registerEntityCallbacks(registrar, type);
        }
        int dataTypes = registered.size();
        for (Class<?> type : TypeDiscovery.getResultTypes()) {
            registerDataType(registrar, type);
            for (Class<?> componentType : TypeDiscovery.getComponentTypes(type)) {
                registerDataType(registrar, componentType);
            }
        }
        int resultTypes = registered.size() - dataTypes;
        int converterTypes = 0;
        for (Class<?> type : TypeDiscovery.getConverterTypes()) {
            registrar.register(type);
            registrar.register(type.getDeclaredConstructors());
            converterTypes++;
        }
        int repositoryTypes = 0;
        for (Class<?> type : TypeDiscovery.getRepositoryTypes()) {
            registrar.registerProxy(type);
            Class<?> defaultImplementations = registrar.findClassByName(type.getName() + "$DefaultImpls");
            if (defaultImplementations != null) {
                registrar.register(defaultImplementations);
                registrar.register(defaultImplementations.getDeclaredMethods());
            }
            repositoryTypes++;
        }
        registerKotlinCollections(registrar);
        return new Summary(dataTypes, resultTypes, callbackTypes, converterTypes, repositoryTypes);
    }

    /**
     * The Kotlin standard library's collection classes that are not JDK classes: the empty collections and the
     * builders of {@code buildList} and friends. A JSON converter hands a collection of a Kotlin record to Jackson,
     * whose Kotlin module introspects the collection's runtime class through Kotlin reflection, which a native image
     * refuses for a class that is not registered.
     */
    private static final List<String> KOTLIN_COLLECTIONS = List.of(
            "kotlin.collections.EmptyList",
            "kotlin.collections.EmptySet",
            "kotlin.collections.EmptyMap",
            "kotlin.collections.builders.ListBuilder",
            "kotlin.collections.builders.SetBuilder",
            "kotlin.collections.builders.MapBuilder");

    private static void registerKotlinCollections(Registrar registrar) {
        for (String className : KOTLIN_COLLECTIONS) {
            Class<?> type = registrar.findClassByName(className);
            if (type == null) {
                continue;
            }
            registrar.register(type);
            registrar.register(type.getDeclaredConstructors());
            registrar.register(type.getDeclaredMethods());
            registrar.register(type.getDeclaredFields());
        }
    }

    /**
     * Registers the callbacks an entity declares with {@link EntityCallbacks}. Storm creates each of them through
     * its no-argument constructor the first time the entity is written, which needs the constructor at runtime.
     */
    private static int registerEntityCallbacks(Registrar registrar, Class<?> type) {
        EntityCallbacks declared = type.getAnnotation(EntityCallbacks.class);
        if (declared == null) {
            return 0;
        }
        for (Class<?> callbackType : declared.value()) {
            registrar.register(callbackType);
            registrar.register(callbackType.getDeclaredConstructors());
        }
        return declared.value().length;
    }

    private void registerDataType(Registrar registrar, Class<?> type) {
        if (!registered.add(type)) {
            return;
        }
        registrar.register(type);
        registrar.register(type.getDeclaredConstructors());
        registrar.register(type.getMethods());
        // The declared fields include the static Companion instance, which kotlinx.serialization
        // reads reflectively when it resolves a serializer at runtime.
        registrar.register(type.getDeclaredFields());
        registerMetamodel(registrar, type.getName() + "Metamodel");
        registerMetamodel(registrar, type.getName() + "NullableMetamodel");
        // kotlinx.serialization resolves serializers reflectively through the generated companions.
        registerCompanion(registrar, type.getName() + "$serializer");
        registerCompanion(registrar, type.getName() + "$Companion");
        // Storm detects a sealed entity, and the target of a polymorphic foreign key, through the type's permitted
        // subclasses, which a native image reports only when they are registered. The index lists the concrete
        // types, so the sealed interface is reached from its subtypes, and each subtype from the interface, in case
        // the index lists only one side.
        if (type.isSealed()) {
            registrar.registerAllPermittedSubclasses(type);
            for (Class<?> subtype : type.getPermittedSubclasses()) {
                registerDataType(registrar, subtype);
                for (Class<?> componentType : TypeDiscovery.getComponentTypes(subtype)) {
                    registerDataType(registrar, componentType);
                }
            }
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (implemented.isSealed() && Data.class.isAssignableFrom(implemented)) {
                registerDataType(registrar, implemented);
            }
        }
    }

    private static void registerCompanion(Registrar registrar, String className) {
        Class<?> companion = registrar.findClassByName(className);
        if (companion == null) {
            return;
        }
        registrar.register(companion);
        registrar.register(companion.getDeclaredConstructors());
        registrar.register(companion.getMethods());
        registrar.register(companion.getFields());
    }

    private static void registerMetamodel(Registrar registrar, String className) {
        Class<?> metamodel = registrar.findClassByName(className);
        if (metamodel == null) {
            return;
        }
        registrar.register(metamodel);
        registrar.register(metamodel.getFields());
        registrar.register(metamodel.getMethods());
    }

    /** Registers with the native image build. */
    private record ImageRegistrar(BeforeAnalysisAccess access) implements Registrar {

        @Override
        public @Nullable Class<?> findClassByName(String name) {
            return access.findClassByName(name);
        }

        @Override
        public void register(Class<?> type) {
            RuntimeReflection.register(type);
        }

        @Override
        public void register(Executable... executables) {
            RuntimeReflection.register(executables);
        }

        @Override
        public void register(Field... fields) {
            RuntimeReflection.register(fields);
        }

        @Override
        public void registerAllPermittedSubclasses(Class<?> type) {
            RuntimeReflection.registerAllPermittedSubclasses(type);
        }

        @Override
        public void registerProxy(Class<?> type) {
            RuntimeProxyCreation.register(type);
        }
    }
}
