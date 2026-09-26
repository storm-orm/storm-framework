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

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
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

    @Override
    public String getDescription() {
        return "Registers Storm entities, entity callbacks, converters, and repositories from the compile-time type index.";
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
        int dataTypes = 0;
        int callbackTypes = 0;
        for (Class<?> type : TypeDiscovery.getDataTypes()) {
            registerDataType(access, type);
            for (Class<?> componentType : TypeDiscovery.getComponentTypes(type)) {
                registerDataType(access, componentType);
            }
            callbackTypes += registerEntityCallbacks(type);
            dataTypes++;
        }
        int converterTypes = 0;
        for (Class<?> type : TypeDiscovery.getConverterTypes()) {
            RuntimeReflection.register(type);
            RuntimeReflection.register(type.getDeclaredConstructors());
            converterTypes++;
        }
        int repositoryTypes = 0;
        for (Class<?> type : TypeDiscovery.getRepositoryTypes()) {
            RuntimeProxyCreation.register(type);
            Class<?> defaultImplementations = access.findClassByName(type.getName() + "$DefaultImpls");
            if (defaultImplementations != null) {
                RuntimeReflection.register(defaultImplementations);
                RuntimeReflection.register(defaultImplementations.getDeclaredMethods());
            }
            repositoryTypes++;
        }
        System.out.printf(
                "Storm: registered %d data types, %d entity callbacks, %d converters, and %d repositories from "
                        + "the type index.%n",
                dataTypes, callbackTypes, converterTypes, repositoryTypes);
    }

    /**
     * Registers the callbacks an entity declares with {@link EntityCallbacks}. Storm creates each of them through
     * its no-argument constructor the first time the entity is written, which needs the constructor at runtime.
     */
    private static int registerEntityCallbacks(Class<?> type) {
        EntityCallbacks declared = type.getAnnotation(EntityCallbacks.class);
        if (declared == null) {
            return 0;
        }
        for (Class<?> callbackType : declared.value()) {
            RuntimeReflection.register(callbackType);
            RuntimeReflection.register(callbackType.getDeclaredConstructors());
        }
        return declared.value().length;
    }

    private static void registerDataType(BeforeAnalysisAccess access, Class<?> type) {
        RuntimeReflection.register(type);
        RuntimeReflection.register(type.getDeclaredConstructors());
        RuntimeReflection.register(type.getMethods());
        // The declared fields include the static Companion instance, which kotlinx.serialization
        // reads reflectively when it resolves a serializer at runtime.
        RuntimeReflection.register(type.getDeclaredFields());
        registerMetamodel(access, type.getName() + "Metamodel");
        registerMetamodel(access, type.getName() + "NullableMetamodel");
        // kotlinx.serialization resolves serializers reflectively through the generated companions.
        registerCompanion(access, type.getName() + "$serializer");
        registerCompanion(access, type.getName() + "$Companion");
    }

    private static void registerCompanion(BeforeAnalysisAccess access, String className) {
        Class<?> companion = access.findClassByName(className);
        if (companion == null) {
            return;
        }
        RuntimeReflection.register(companion);
        RuntimeReflection.register(companion.getDeclaredConstructors());
        RuntimeReflection.register(companion.getMethods());
        RuntimeReflection.register(companion.getFields());
    }

    private static void registerMetamodel(BeforeAnalysisAccess access, String className) {
        Class<?> metamodel = access.findClassByName(className);
        if (metamodel == null) {
            return;
        }
        RuntimeReflection.register(metamodel);
        RuntimeReflection.register(metamodel.getFields());
        RuntimeReflection.register(metamodel.getMethods());
    }
}
