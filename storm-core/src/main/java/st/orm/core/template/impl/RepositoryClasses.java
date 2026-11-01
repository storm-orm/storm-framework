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

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;

/**
 * The classes Storm writes for repository interfaces: one class per interface, named after it with
 * {@value #SUFFIX}, in its package, that implements every method by calling the shared repository implementations
 * directly. Java 24 and up write it with the class-file API; on Java 21 to 23, or where the interface's package is not
 * open to Storm, no class is written and the caller falls back to a proxy. In a native image the class is written
 * during the build, which keeps only the methods the application calls, and looked up at run time.
 *
 * @since 1.16
 */
public final class RepositoryClasses {

    /** What the name of a repository class adds to the name of its interface. */
    public static final String SUFFIX = "$$Storm";

    private RepositoryClasses() {
    }

    /**
     * The repository interfaces of an API: the core one or the one the Java and Kotlin APIs share. Each API's entity and
     * projection repository interfaces live in the package of its repository interface.
     */
    private record Api(Class<?> repository, Class<?> entity, Class<?> projection) {}

    /** The constructor of each interface's repository class, or none where no class is written. */
    private static final ClassValue<Optional<Constructor<?>>> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected Optional<Constructor<?>> computeValue(Class<?> type) {
            return Optional.ofNullable(constructorOf(type));
        }
    };

    /**
     * Returns a repository of the interface backed by the shared implementations, or {@code null} where no class is
     * written for it, so the caller creates a proxy instead.
     *
     * @param type the repository interface.
     * @param repository the shared implementation of the repository interface.
     * @param entity the shared implementation of the entity repository interface, or {@code null} where the type is
     *               not an entity repository.
     * @param projection the shared implementation of the projection repository interface, or {@code null} where the
     *                   type is not a projection repository.
     * @param <R> the repository type.
     * @return the repository, or {@code null}.
     */
    public static <R> @Nullable R create(Class<R> type,
                                         Object repository,
                                         @Nullable Object entity,
                                         @Nullable Object projection) {
        var constructor = CONSTRUCTORS.get(type).orElse(null);
        if (constructor == null) {
            return null;
        }
        try {
            return type.cast(constructor.newInstance(repository, entity, projection));
        } catch (InvocationTargetException e) {
            throw new PersistenceException("Failed to create the repository %s.".formatted(type.getName()),
                    e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new PersistenceException("Failed to create the repository %s.".formatted(type.getName()), e);
        }
    }

    /**
     * Writes and defines the repository class of the interface during a native image build, for the image to hold.
     *
     * @param type the repository interface.
     * @return the class, or {@code null} where none is written, so the image keeps the proxy.
     */
    public static @Nullable Class<?> defineForImage(Class<?> type) {
        var constructor = CONSTRUCTORS.get(type).orElse(null);
        return constructor == null ? null : constructor.getDeclaringClass();
    }

    /**
     * Returns the constructor of the interface's repository class: in a native image the class the build defined, and
     * elsewhere the one written now, defined in the interface's package.
     */
    private static @Nullable Constructor<?> constructorOf(Class<?> type) {
        var api = apiOf(type);
        if (api == null) {
            return null;
        }
        String name = type.getName() + SUFFIX;
        try {
            Class<?> repositoryClass;
            if (inImageRuntime()) {
                repositoryClass = Class.forName(name, false, type.getClassLoader());
            } else {
                byte[] bytes = RepositoryClassWriter.write(type, name, api.repository(), api.entity(), api.projection());
                if (bytes == null) {
                    return null;
                }
                repositoryClass = define(type, name, bytes);
            }
            return repositoryClass.getConstructor(api.repository(), api.entity(), api.projection());
        } catch (ReflectiveOperationException | LinkageError | IllegalArgumentException | SecurityException
                 | UnsupportedOperationException e) {
            // A package not open to Storm, or a class the image did not keep: the proxy serves the interface.
            return null;
        }
    }

    /** Defines the class in the interface's package, or returns the one defined there already. */
    private static Class<?> define(Class<?> type, String name, byte[] bytes) throws IllegalAccessException {
        // The class is defined once per class loader: a race on the first call finds the winner's class.
        synchronized (RepositoryClasses.class) {
            try {
                return Class.forName(name, false, type.getClassLoader());
            } catch (ClassNotFoundException e) {
                // Not defined yet.
            }
            RepositoryClasses.class.getModule().addReads(type.getModule());
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).defineClass(bytes);
        }
    }

    /**
     * Returns whether this code runs in a native image: the property the image sets, which {@code ImageInfo} reads too,
     * read directly, as the GraalVM SDK is not on a plain JVM's class path.
     */
    private static boolean inImageRuntime() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /** Returns the API whose repository interface the type extends, or {@code null} for none. */
    private static @Nullable Api apiOf(Class<?> type) {
        var root = repositoryRoot(type);
        if (root == null) {
            return null;
        }
        String packageName = root.getPackageName();
        try {
            var loader = root.getClassLoader();
            return new Api(root,
                    Class.forName(packageName + ".EntityRepository", false, loader),
                    Class.forName(packageName + ".ProjectionRepository", false, loader));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Returns the repository interface of an API that the type extends, or {@code null} for none. */
    private static @Nullable Class<?> repositoryRoot(Class<?> type) {
        for (var implemented : type.getInterfaces()) {
            if (implemented.getName().equals("st.orm.core.repository.Repository")
                    || implemented.getName().equals("st.orm.repository.Repository")) {
                return implemented;
            }
            var root = repositoryRoot(implemented);
            if (root != null) {
                return root;
            }
        }
        return null;
    }
}
