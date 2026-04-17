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
package st.orm.template.impl;

import static java.lang.System.identityHashCode;
import static java.lang.reflect.Proxy.newProxyInstance;
import static java.util.Optional.empty;

import jakarta.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import st.orm.Data;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.Projection;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.impl.SqlLogInterceptor;
import st.orm.repository.EntityRepository;
import st.orm.repository.ProjectionRepository;
import st.orm.repository.Repository;
import st.orm.repository.impl.EntityRepositoryImpl;
import st.orm.repository.impl.ProjectionRepositoryImpl;
import st.orm.template.ORMTemplate;

public final class ORMTemplateImpl extends QueryTemplateImpl implements ORMTemplate {
    private static final ORMReflection REFLECTION = Providers.getORMReflection();
    private final st.orm.core.template.ORMTemplate core;

    public ORMTemplateImpl(st.orm.core.template.ORMTemplate core) {
        super(core);
        this.core = core;
    }

    @Override
    public ORMTemplate withEntityCallback(@Nonnull EntityCallback<?> callback) {
        return new ORMTemplateImpl(core.withEntityCallback(callback));
    }

    @Override
    public ORMTemplate withEntityCallbacks(@Nonnull List<EntityCallback<?>> callbacks) {
        return new ORMTemplateImpl(core.withEntityCallbacks(callbacks));
    }

    @Override
    public List<String> validateSchema() {
        return core.validateSchema();
    }

    @Override
    public List<String> validateSchema(@Nonnull Predicate<Class<? extends Data>> filter) {
        return core.validateSchema(filter);
    }

    @Override
    public List<String> validateSchema(@Nonnull Iterable<Class<? extends Data>> types) {
        return core.validateSchema(types);
    }

    @Override
    public void validateSchemaOrThrow() {
        core.validateSchemaOrThrow();
    }

    @Override
    public void validateSchemaOrThrow(@Nonnull Predicate<Class<? extends Data>> filter) {
        core.validateSchemaOrThrow(filter);
    }

    @Override
    public void validateSchemaOrThrow(@Nonnull Iterable<Class<? extends Data>> types) {
        core.validateSchemaOrThrow(types);
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
        return new EntityRepositoryImpl<>(core.entity(type));
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
        return new ProjectionRepositoryImpl<>(core.projection(type));
    }

    /**
     * Returns a proxy for the repository of the given type.
     *
     * @param type the repository type.
     * @param <R> the repository type.
     * @return a proxy for the repository of the given type.
     */
    @Override
    public <R extends Repository> R repository(@Nonnull Class<R> type) {
        var entityRepository = EntityRepository.class.isAssignableFrom(type)
                ? createEntityRepository(type).orElse(null)
                : null;
        var projectionRepository = ProjectionRepository.class.isAssignableFrom(type)
                ? createProjectionRepository(type).orElse(null)
                : null;
        Repository repository = createRepository();
        //noinspection unchecked
        return (R) newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            try {
                if (method.getName().equals("hashCode") && method.getParameterCount() == 0) {
                    return identityHashCode(proxy);
                }
                if (method.getName().equals("equals") && method.getParameterCount() == 1) {
                    return proxy == args[0];
                }
                if (method.getName().equals("toString") && method.getParameterCount() == 0) {
                    return STR."RepositoryProxy(\{type.getSimpleName()})";
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
    }

    private static Object dispatch(Object proxy,
                                    Method method,
                                    Object[] args,
                                    Repository repository,
                                    EntityRepository<?, ?> entityRepository,
                                    ProjectionRepository<?, ?> projectionRepository,
                                    Class<?> type) throws Exception {
        try {
            if (method.getDeclaringClass().isAssignableFrom(Repository.class)) {
                return method.invoke(repository, args);
            }
            if (method.getDeclaringClass().isAssignableFrom(EntityRepository.class)) {
                assert entityRepository != null;
                return method.invoke(entityRepository, args);
            }
            if (method.getDeclaringClass().isAssignableFrom(ProjectionRepository.class)) {
                assert projectionRepository != null;
                return method.invoke(projectionRepository, args);
            }
            if (REFLECTION.isDefaultMethod(method)) {
                return REFLECTION.execute(proxy, method, args);
            }
            throw new UnsupportedOperationException(STR."Unsupported method: \{method.getName()} for \{type.getName()}.");
        } catch (InvocationTargetException e) {
            var target = e.getTargetException();
            if (target instanceof Exception ex) {
                throw ex;
            }
            throw new st.orm.PersistenceException(target);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new st.orm.PersistenceException(t);
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

    @SuppressWarnings("unchecked")
    private <T extends Entity<ID>, ID> Optional<EntityRepository<T, ID>> createEntityRepository(@Nonnull Class<?> type) {
        if (EntityRepository.class.isAssignableFrom(type)) {
            Class<?> entityClass = null;
            // Attempt to find the generic interface that directly extends Repository.
            Type[] genericInterfaces = type.getGenericInterfaces();
            for (Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType parameterizedType) {
                    Type rawType = parameterizedType.getRawType();
                    if (rawType instanceof Class && EntityRepository.class.isAssignableFrom((Class<?>) rawType)) {
                        // The entity class must be the first parameterized type.
                        Type entityType = parameterizedType.getActualTypeArguments()[0];
                        if (entityType instanceof Class<?> clazz) {
                            entityClass = clazz;
                            break;
                        }
                    }
                }
            }
            if (entityClass == null) {
                throw new IllegalArgumentException(STR."Could not determine entity class for repository: \{type.getSimpleName()}.");
            }
            return Optional.of(entity((Class<T>) entityClass));
        }
        return empty();
    }

    @SuppressWarnings("unchecked")
    private <T extends Projection<ID>, ID> Optional<ProjectionRepository<T, ID>> createProjectionRepository(@Nonnull Class<?> type) {
        if (ProjectionRepository.class.isAssignableFrom(type)) {
            Class<?> projectionClass = null;
            // Attempt to find the generic interface that directly extends Repository.
            Type[] genericInterfaces = type.getGenericInterfaces();
            for (Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType parameterizedType) {
                    Type rawType = parameterizedType.getRawType();
                    if (rawType instanceof Class && ProjectionRepository.class.isAssignableFrom((Class<?>) rawType)) {
                        // The projection class must be the first parameterized type.
                        Type projectionType = parameterizedType.getActualTypeArguments()[0];
                        if (projectionType instanceof Class<?> clazz) {
                            projectionClass = clazz;
                            break;
                        }
                    }
                }
            }
            if (projectionClass == null) {
                throw new IllegalArgumentException(STR."Could not determine projection class for repository: \{type.getSimpleName()}.");
            }
            return Optional.of(projection((Class<T>) projectionClass));
        }
        return empty();
    }

    private Repository createRepository() {
        return () -> ORMTemplateImpl.this;
    }
}
