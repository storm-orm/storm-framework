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

import static java.lang.System.identityHashCode;
import static java.lang.reflect.Proxy.newProxyInstance;
import static st.orm.StormConfig.VALIDATION_STRICT;
import static st.orm.core.spi.Providers.getEntityRepository;
import static st.orm.core.spi.Providers.getProjectionRepository;
import static st.orm.core.spi.StormConfigHelper.*;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import javax.sql.DataSource;
import st.orm.Data;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.StormConfig;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.repository.Repository;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Provider;
import st.orm.core.spi.Providers;
import st.orm.core.spi.QueryFactory;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlTemplateException;

public final class ORMTemplateImpl extends QueryTemplateImpl implements ORMTemplate {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private final ConcurrentMap<Class<?>, EntityRepository<?, ?>> entityRepositories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, ProjectionRepository<?, ?>> projectionRepositories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, Repository> repositories = new ConcurrentHashMap<>();
    private final Predicate<? super Provider> providerFilter;
    private final StormConfig config;
    private final List<EntityCallback<?>> entityCallbacks;

    public ORMTemplateImpl(@Nonnull QueryFactory factory,
                           @Nonnull ModelBuilder modelBuilder,
                           @Nullable Predicate<? super Provider> providerFilter) {
        this(factory, modelBuilder, providerFilter, StormConfig.defaults());
    }

    public ORMTemplateImpl(@Nonnull QueryFactory factory,
                           @Nonnull ModelBuilder modelBuilder,
                           @Nullable Predicate<? super Provider> providerFilter,
                           @Nonnull StormConfig config) {
        this(factory, modelBuilder, providerFilter, config, List.of());
    }

    public ORMTemplateImpl(@Nonnull QueryFactory factory,
                           @Nonnull ModelBuilder modelBuilder,
                           @Nullable Predicate<? super Provider> providerFilter,
                           @Nonnull StormConfig config,
                           @Nonnull List<EntityCallback<?>> entityCallbacks) {
        super(factory, modelBuilder);
        this.providerFilter = providerFilter;
        this.config = config;
        this.entityCallbacks = List.copyOf(entityCallbacks);
    }

    @Override
    public StormConfig config() {
        return config;
    }

    @Override
    public List<EntityCallback<?>> entityCallbacks() {
        return entityCallbacks;
    }

    @Override
    public ORMTemplate withEntityCallbacks(@Nonnull List<EntityCallback<?>> callbacks) {
        if (callbacks.isEmpty()) {
            return this;
        }
        var newCallbacks = new ArrayList<>(entityCallbacks);
        newCallbacks.addAll(callbacks);
        return new ORMTemplateImpl(queryFactory, modelBuilder, providerFilter, config, newCallbacks);
    }

    @Override
    public List<String> validateSchema() {
        return createSchemaValidator().validateAndReport(isStrictSchemaValidation());
    }

    @Override
    public List<String> validateSchema(@Nonnull Iterable<Class<? extends Data>> types) {
        return createSchemaValidator().validateAndReport(types, isStrictSchemaValidation());
    }

    @Override
    public void validateSchemaOrThrow() {
        createSchemaValidator().validateReportAndThrow(isStrictSchemaValidation());
    }

    @Override
    public void validateSchemaOrThrow(@Nonnull Iterable<Class<? extends Data>> types) {
        List<String> errors = createSchemaValidator().validateAndReport(types, isStrictSchemaValidation());
        if (!errors.isEmpty()) {
            throw new PersistenceException(SchemaValidator.formatErrors(errors));
        }
    }

    private boolean isStrictSchemaValidation() {
        return getBoolean(config, VALIDATION_STRICT, false);
    }

    private SchemaValidator createSchemaValidator() {
        DataSource dataSource = queryFactory.dataSource();
        if (dataSource == null) {
            throw new PersistenceException(
                    "Schema validation requires a DataSource-backed template. "
                    + "Templates created from a Connection or EntityManager do not support schema validation.");
        }
        var sqlDialect = providerFilter != null
                ? Providers.getSqlDialect(providerFilter, config)
                : Providers.getSqlDialect(config);
        return SchemaValidator.of(dataSource, modelBuilder, sqlDialect);
    }

    /**
     * Returns the repository for the given entity type.
     *
     * @param type the entity type.
     * @param <T> the entity type.
     * @param <ID> the type of the entity's primary key.
     * @return the repository for the given entity type.
     */
    @Override
    public <T extends Entity<ID>, ID> EntityRepository<T, ID> entity(@Nonnull Class<T> type) {
        //noinspection unchecked
        return (EntityRepository<T, ID>) entityRepositories.computeIfAbsent(type, t -> {
            try {
                return getEntityRepository(this, modelBuilder.build(type, true), providerFilter == null ? ignore -> true : providerFilter);
            } catch (SqlTemplateException e) {
                throw new PersistenceException("Failed to create entity repository for type %s.".formatted(t.getName()), e);
            }
        });
    }

    /**
     * Returns the repository for the given projection type.
     *
     * @param type the projection type.
     * @param <T> the projection type.
     * @param <ID> the type of the projection's primary key, or Void if the projection specifies no primary key.
     * @return the repository for the given projection type.
     */
    @Override
    public <T extends Projection<ID>, ID> ProjectionRepository<T, ID> projection(@Nonnull Class<T> type) {
        //noinspection unchecked
        return (ProjectionRepository<T, ID>) projectionRepositories.computeIfAbsent(type, t -> {
            try {
                return getProjectionRepository(this, modelBuilder.build(type, false), providerFilter == null ? ignore -> true : providerFilter);
            } catch (SqlTemplateException e) {
                throw new PersistenceException("Failed to create projection repository for type %s.".formatted(t.getName()), e);
            }
        });
    }

    /**
     * Returns a proxy for the repository of the given type.
     *
     * @param type the repository type.
     * @param <R> the repository type.
     * @return a proxy for the repository of the given type.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <R extends Repository> R repository(@Nonnull Class<R> type) {
        return (R) repositories.computeIfAbsent(type, t -> {
            EntityRepository<?, ?> entityRepository = createEntityRepository(type).orElse(null);
            ProjectionRepository<?, ?> projectionRepository = createProjectionRepository(type).orElse(null);
            Repository repository = createRepository();
            return (R) newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                try {
                    if (method.getName().equals("hashCode") && method.getParameterCount() == 0) {
                        return identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals") && method.getParameterCount() == 1) {
                        return proxy == args[0];
                    }
                    if (method.getName().equals("toString") && method.getParameterCount() == 0) {
                        return "%s@proxy".formatted(type.getName());
                    }
                    return SqlLogInterceptor.wrapIfNeeded(
                            SqlLogInterceptor.resolve(type, method),
                            type,
                            toShortSignature(method),
                            () -> dispatch(proxy, method, args, repository, entityRepository, projectionRepository, type)
                    );
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
            });
        });
    }

    private static Object dispatch(Object proxy,
                                    Method method,
                                    Object[] args,
                                    Repository repository,
                                    EntityRepository<?, ?> entityRepository,
                                    ProjectionRepository<?, ?> projectionRepository,
                                    Class<?> type) throws Exception {
        try {
            if (REFLECTION.isDefaultMethod(method)) {
                return REFLECTION.execute(proxy, method, args);
            }
            if (method.getDeclaringClass().isAssignableFrom(Repository.class)) {
                return method.invoke(repository, args);
            }
            if (EntityRepository.class.isAssignableFrom(method.getDeclaringClass())) {
                if (entityRepository == null) {
                    throw new UnsupportedOperationException("EntityRepository not available for %s. Ensure the type implements the Entity interface and has a valid @DbTable annotation.".formatted(type.getName()));
                }
                return method.invoke(entityRepository, args);
            }
            if (ProjectionRepository.class.isAssignableFrom(method.getDeclaringClass())) {
                if (projectionRepository == null) {
                    throw new UnsupportedOperationException("ProjectionRepository not available for %s. Ensure the type implements the Projection interface and has a valid @DbTable annotation.".formatted(type.getName()));
                }
                return method.invoke(projectionRepository, args);
            }
            throw new UnsupportedOperationException("Unsupported repository method '%s' for type %s. This method is not available for the repository type associated with this class.".formatted(method.getName(), type.getName()));
        } catch (InvocationTargetException e) {
            var target = e.getTargetException();
            if (target instanceof Exception ex) {
                throw ex;
            }
            throw new PersistenceException("Repository method invocation failed for '%s' on type %s.".formatted(method.getName(), type.getName()), target);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException("Unexpected error invoking repository method '%s' on type %s.".formatted(method.getName(), type.getName()), t);
        }
    }

    private static String toShortSignature(@Nonnull Method method) {
        var sb = new StringBuilder(method.getName()).append('(');
        var params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private <T extends Entity<ID>, ID> Optional<EntityRepository<T, ID>> createEntityRepository(@Nonnull Class<?> type) {
        if (!EntityRepository.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        //noinspection unchecked
        return findGenericClass(type, EntityRepository.class, 0).map(cls -> entity((Class<T>) (Object) cls));
    }

    private <T extends Projection<ID>, ID> Optional<ProjectionRepository<T, ID>> createProjectionRepository(@Nonnull Class<?> type) {
        if (!ProjectionRepository.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        //noinspection unchecked
        return findGenericClass(type, ProjectionRepository.class, 0).map(cls -> projection((Class<T>) (Object) cls));
    }

    private Repository createRepository() {
        //noinspection Convert2Lambda
        return new Repository() {
            @Override
            public ORMTemplate orm() {
                return ORMTemplateImpl.this;
            }
        };
    }

    @SuppressWarnings("SameParameterValue")
    private static <T extends Entity<?>> Optional<Class<T>> findGenericClass(@Nonnull Class<?> clazz,
                                                                                      @Nonnull Class<?> targetInterface,
                                                                                      int typeArgumentIndex) {
        //noinspection unchecked
        return findGenericType(clazz, targetInterface, typeArgumentIndex)
                .filter(type -> type instanceof Class<?>)
                .map(type -> (Class<T>) type);
    }

    @SuppressWarnings("ConstantValue")
    public static Optional<Type> findGenericType(@Nonnull Class<?> clazz,
                                                 @Nonnull Class<?> targetInterface,
                                                 int typeArgumentIndex) {
        // Inspect interfaces directly implemented by the class.
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType parameterizedType) {
                Type rawType = parameterizedType.getRawType();
                // Check if this is the interface we're interested in.
                if (rawType.equals(targetInterface) || rawType.equals(parameterizedType.getRawType())) {
                    Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                    if (typeArgumentIndex >= 0 && typeArgumentIndex < actualTypeArguments.length) {
                        return Optional.of(actualTypeArguments[typeArgumentIndex]); // Found the type
                    }
                }
            }
            // If the current interface itself extends other interfaces, recursively check them.
            if (genericInterface instanceof Class<?>) {
                Optional<Type> foundType = findGenericType((Class<?>) genericInterface, targetInterface, typeArgumentIndex);
                if (foundType.isPresent()) {
                    return foundType;
                }
            }
        }
        // If not found, check the superclass.
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && !superclass.equals(Object.class)) {
            return findGenericType(superclass, targetInterface, typeArgumentIndex);
        }
        return Optional.empty(); // Type not found.
    }
}
