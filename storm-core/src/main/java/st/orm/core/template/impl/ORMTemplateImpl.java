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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.WriteSet;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.repository.Repository;
import st.orm.core.repository.impl.WriteSetImpl;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Provider;
import st.orm.core.spi.Providers;
import st.orm.core.spi.QueryFactory;
import st.orm.core.spi.TypeDiscovery;
import st.orm.core.template.ORMTemplate;

public final class ORMTemplateImpl extends QueryTemplateImpl implements ORMTemplate {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private final ConcurrentMap<Class<?>, EntityRepository<?, ?>> entityRepositories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, ProjectionRepository<?, ?>> projectionRepositories = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, Repository> repositories = new ConcurrentHashMap<>();
    private final Predicate<? super Provider> providerFilter;
    private final StormConfig config;
    private final List<EntityCallback<?>> entityCallbacks;
    private volatile WriteSet writeSet;

    public ORMTemplateImpl(QueryFactory factory,
                           ModelBuilder modelBuilder,
                           @Nullable Predicate<? super Provider> providerFilter) {
        this(factory, modelBuilder, providerFilter, StormConfig.defaults());
    }

    public ORMTemplateImpl(QueryFactory factory,
                           ModelBuilder modelBuilder,
                           @Nullable Predicate<? super Provider> providerFilter,
                           StormConfig config) {
        this(factory, modelBuilder, providerFilter, config, List.of());
    }

    public ORMTemplateImpl(QueryFactory factory,
                           ModelBuilder modelBuilder,
                           @Nullable Predicate<? super Provider> providerFilter,
                           StormConfig config,
                           List<EntityCallback<?>> entityCallbacks) {
        super(factory, modelBuilder);
        this.providerFilter = providerFilter;
        this.config = config;
        this.entityCallbacks = List.copyOf(entityCallbacks);
    }

    @Override
    public StormConfig config() {
        return config;
    }

    /**
     * Returns the write set bound to this template.
     *
     * <p>The instance is cached so its per-type metadata (FK edges, key carriers) is discovered once per template
     * rather than on every {@code writeSet()} call. The write set itself is stateless per operation, so sharing one
     * instance is safe.</p>
     */
    @Override
    public WriteSet writeSet() {
        WriteSet result = writeSet;
        if (result == null) {
            // Benign race: two threads may briefly build separate instances; both are correct and the field settles.
            result = new WriteSetImpl(this);
            writeSet = result;
        }
        return result;
    }

    @Override
    public List<EntityCallback<?>> entityCallbacks() {
        return entityCallbacks;
    }

    @Override
    public ORMTemplate withEntityCallbacks(List<EntityCallback<?>> callbacks) {
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
    public List<String> validateSchema(Predicate<Class<? extends Data>> filter) {
        return createSchemaValidator().validateAndReport(filter, isStrictSchemaValidation());
    }

    @Override
    public List<String> validateSchema(Iterable<Class<? extends Data>> types) {
        return createSchemaValidator().validateAndReport(types, isStrictSchemaValidation());
    }

    @Override
    public void validateSchemaOrThrow() {
        createSchemaValidator().validateReportAndThrow(isStrictSchemaValidation());
    }

    @Override
    public void validateSchemaOrThrow(Predicate<Class<? extends Data>> filter) {
        createSchemaValidator().validateReportAndThrow(filter, isStrictSchemaValidation());
    }

    @Override
    public void validateSchemaOrThrow(Iterable<Class<? extends Data>> types) {
        List<String> errors = createSchemaValidator().validateAndReport(types, isStrictSchemaValidation());
        if (!errors.isEmpty()) {
            throw new PersistenceException(SchemaValidator.formatErrors(errors));
        }
    }

    @Override
    public void loadSchema() {
        createSchemaValidator("Loading the schema").load();
    }

    @Override
    public void loadSchema(Predicate<Class<? extends Data>> filter) {
        createSchemaValidator("Loading the schema").load(TypeDiscovery.getDataTypes().stream().filter(filter).toList());
    }

    private boolean isStrictSchemaValidation() {
        return getBoolean(config, VALIDATION_STRICT, false);
    }

    private SchemaValidator createSchemaValidator() {
        return createSchemaValidator("Schema validation");
    }

    private SchemaValidator createSchemaValidator(String operation) {
        DataSource dataSource = queryFactory.dataSource();
        if (dataSource == null) {
            throw new PersistenceException(
                    "%s requires a DataSource-backed template. Templates created from a Connection or EntityManager do not support it."
                            .formatted(operation));
        }
        // Without an explicit provider filter, the dialect comes from the database this template is bound to. The
        // schema is read with vendor-specific queries, so a dialect picked without consulting the database reads it
        // with queries the database may not understand.
        var sqlDialect = providerFilter != null
                ? Providers.getSqlDialect(providerFilter, config)
                : Providers.getSqlDialect(dataSource, config);
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
    public <T extends Entity<ID>, ID> EntityRepository<T, ID> entity(Class<T> type) {
        //noinspection unchecked
        return (EntityRepository<T, ID>) entityRepositories.computeIfAbsent(type, t -> {
            RecordReflection.findJoinedSealedParent(t).ifPresent(parent -> {
                throw new PersistenceException("Cannot create a repository for %1$s: its rows span the tables of the joined inheritance hierarchy %2$s, which the repository of %2$s reads and writes as one. Use the repository of %2$s and dispatch on %1$s."
                        .formatted(t.getSimpleName(), parent.getSimpleName()));
            });
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
    public <T extends Projection<ID>, ID> ProjectionRepository<T, ID> projection(Class<T> type) {
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
    public <R extends Repository> R repository(Class<R> type) {
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
                    return dispatch(proxy, method, args, repository, entityRepository, projectionRepository, type);
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

    private <T extends Entity<ID>, ID> Optional<EntityRepository<T, ID>> createEntityRepository(Class<?> type) {
        if (!EntityRepository.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        //noinspection unchecked
        return findGenericClass(type, EntityRepository.class, 0).map(cls -> entity((Class<T>) (Object) cls));
    }

    private <T extends Projection<ID>, ID> Optional<ProjectionRepository<T, ID>> createProjectionRepository(Class<?> type) {
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
    private static <T extends Entity<?>> Optional<Class<T>> findGenericClass(Class<?> clazz,
                                                                                      Class<?> targetInterface,
                                                                                      int typeArgumentIndex) {
        //noinspection unchecked
        return findGenericType(clazz, targetInterface, typeArgumentIndex)
                .filter(type -> type instanceof Class<?>)
                .map(type -> (Class<T>) type);
    }

    /**
     * Resolves the type argument the given class supplies at the given index of the given generic interface,
     * following the class and interface hierarchy and substituting type variables along the way. Only arguments
     * of the target interface itself qualify: an unrelated parameterized interface on the same class contributes
     * nothing, and a generic intermediate interface resolves through its own parameter positions.
     *
     * @param clazz the class whose declaration to inspect.
     * @param targetInterface the generic interface whose type argument to resolve.
     * @param typeArgumentIndex the index of the interface's type parameter to resolve.
     * @return the supplied type argument, or empty when the class does not implement the interface, implements it
     *         raw, the index is out of bounds, or the argument does not resolve.
     */
    public static Optional<Type> findGenericType(Class<?> clazz,
                                                 Class<?> targetInterface,
                                                 int typeArgumentIndex) {
        return RecordReflection.findTypeArgument(clazz, targetInterface, typeArgumentIndex);
    }
}
