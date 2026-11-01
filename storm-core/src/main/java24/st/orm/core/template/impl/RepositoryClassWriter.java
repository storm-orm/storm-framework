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

import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_PRIVATE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_SYNTHETIC;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Writes the class of a repository interface with the class-file API, which Java 24 and up provide. The class
 * implements the interface; each abstract method hands its arguments to the shared repository implementation that
 * declares it and returns its result, a Kotlin interface method with a body calls its {@code DefaultImpls}, and a Java
 * default method is inherited as it is. The methods have no branches, so they need no stack map frames.
 *
 * @since 1.16
 */
final class RepositoryClassWriter {

    private static final ClassDesc UNSUPPORTED = ClassDesc.of(UnsupportedOperationException.class.getName());

    private RepositoryClassWriter() {
    }

    /** A method of the interface and what its implementation calls. */
    private record Target(Method method, @Nullable String field, @Nullable Method defaultImpl) {}

    /**
     * Returns the class file of the repository class for the interface.
     *
     * @param type the repository interface.
     * @param name the binary name of the class to write.
     * @param repositoryType the repository interface of the API the type extends.
     * @param entityType the entity repository interface of that API.
     * @param projectionType the projection repository interface of that API.
     * @return the class file.
     */
    static byte[] write(Class<?> type,
                        String name,
                        Class<?> repositoryType,
                        Class<?> entityType,
                        Class<?> projectionType) {
        var self = ClassDesc.of(name);
        var repository = desc(repositoryType);
        var entity = desc(entityType);
        var projection = desc(projectionType);
        var targets = targets(type, repositoryType, entityType, projectionType);
        return ClassFile.of().build(self, builder -> {
            builder.withFlags(ACC_PUBLIC | ACC_SYNTHETIC)
                    .withInterfaceSymbols(desc(type))
                    .withField("repository", repository, ACC_PRIVATE | ACC_FINAL)
                    .withField("entity", entity, ACC_PRIVATE | ACC_FINAL)
                    .withField("projection", projection, ACC_PRIVATE | ACC_FINAL)
                    .withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void, repository, entity, projection), ACC_PUBLIC,
                            code -> code
                                    .aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void)
                                    .aload(0).aload(1).putfield(self, "repository", repository)
                                    .aload(0).aload(2).putfield(self, "entity", entity)
                                    .aload(0).aload(3).putfield(self, "projection", projection)
                                    .return_())
                    .withMethodBody("toString", MethodTypeDesc.of(CD_String), ACC_PUBLIC, code -> code
                            .ldc(description(type, repositoryType))
                            .areturn());
            Map<String, ClassDesc> fields = Map.of("repository", repository, "entity", entity, "projection", projection);
            for (var target : targets.values()) {
                method(builder, self, type, target, fields);
            }
        });
    }

    /**
     * Describes the repository as its API's proxy does, so a repository reads the same on every Java version: the core
     * API names the interface in full, the Java and Kotlin APIs by its simple name.
     */
    private static String description(Class<?> type, Class<?> repositoryType) {
        return repositoryType.getPackageName().equals("st.orm.core.repository")
                ? type.getName() + "@proxy"
                : "RepositoryProxy(" + type.getSimpleName() + ")";
    }

    /**
     * Returns the abstract methods of the interface, one per name and descriptor, each with what it calls: the field of
     * the shared implementation whose interface declares it, else the Kotlin {@code DefaultImpls} method that holds its
     * body, else neither, for a method no implementation serves.
     */
    private static Map<String, Target> targets(Class<?> type,
                                               Class<?> repositoryType,
                                               Class<?> entityType,
                                               Class<?> projectionType) {
        var targets = new LinkedHashMap<String, Target>();
        for (var method : type.getMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isAbstract(modifiers) || Modifier.isStatic(modifiers)) {
                continue;
            }
            var declaring = method.getDeclaringClass();
            String field = declaring.isAssignableFrom(repositoryType) ? "repository"
                    : declaring.isAssignableFrom(entityType) ? "entity"
                    : declaring.isAssignableFrom(projectionType) ? "projection"
                    : null;
            var target = new Target(method, field, field == null ? defaultImpl(method) : null);
            var key = method.getName() + descriptor(method).descriptorString();
            var existing = targets.get(key);
            // A method several interfaces declare is served by the implementation where one serves it.
            if (existing == null || (existing.field() == null && existing.defaultImpl() == null)) {
                targets.put(key, target);
            }
        }
        return targets;
    }

    /** Writes one method: a call on a shared implementation, a call of its Kotlin body, or the refusal. */
    private static void method(ClassBuilder builder,
                               ClassDesc self,
                               Class<?> type,
                               Target target,
                               Map<String, ClassDesc> fields) {
        var method = target.method();
        var descriptor = descriptor(method);
        builder.withMethodBody(method.getName(), descriptor, ACC_PUBLIC, code -> {
            if (target.field() != null) {
                code.aload(0).getfield(self, target.field(), fields.get(target.field()));
                loadArguments(code, method, 1);
                code.invokeinterface(desc(method.getDeclaringClass()), method.getName(), descriptor);
                code.return_(TypeKind.from(method.getReturnType()));
            } else if (target.defaultImpl() != null) {
                var defaultImpl = target.defaultImpl();
                code.aload(0);
                loadArguments(code, method, 1);
                code.invokestatic(desc(defaultImpl.getDeclaringClass()), defaultImpl.getName(), descriptor(defaultImpl));
                code.return_(TypeKind.from(method.getReturnType()));
            } else {
                code.new_(UNSUPPORTED).dup()
                        .ldc("Unsupported repository method '%s' for type %s: no repository implementation serves it."
                                .formatted(method.getName(), type.getName()))
                        .invokespecial(UNSUPPORTED, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String))
                        .athrow();
            }
        });
    }

    /** Loads the method's arguments, starting at the given local slot. */
    private static void loadArguments(CodeBuilder code, Method method, int slot) {
        for (var parameter : method.getParameterTypes()) {
            var kind = TypeKind.from(parameter);
            code.loadLocal(kind, slot);
            slot += kind.slotSize();
        }
    }

    /**
     * Returns the static method of the Kotlin {@code DefaultImpls} class of the method's interface that holds its
     * body, or {@code null} where the interface has none: Kotlin compiles an interface method with a body to an
     * abstract method and a static one that takes the instance first.
     */
    private static @Nullable Method defaultImpl(Method method) {
        var declaring = method.getDeclaringClass();
        try {
            var defaultImpls = Class.forName(declaring.getName() + "$DefaultImpls", false, declaring.getClassLoader());
            var parameters = new Class<?>[method.getParameterCount() + 1];
            parameters[0] = declaring;
            System.arraycopy(method.getParameterTypes(), 0, parameters, 1, method.getParameterCount());
            var defaultImpl = defaultImpls.getMethod(method.getName(), parameters);
            return Modifier.isStatic(defaultImpl.getModifiers()) ? defaultImpl : null;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    private static MethodTypeDesc descriptor(Method method) {
        return MethodTypeDesc.of(desc(method.getReturnType()),
                Arrays.stream(method.getParameterTypes()).map(RepositoryClassWriter::desc).toArray(ClassDesc[]::new));
    }

    private static ClassDesc desc(Class<?> type) {
        return type.describeConstable().orElseThrow(() -> new IllegalArgumentException(type.getName()));
    }
}
