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

import static java.lang.System.identityHashCode;
import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.Optional.ofNullable;
import static java.util.ServiceLoader.load;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Entity;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.Ref;
import st.orm.StormConfig;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.TemplateString;
import st.orm.mapping.RecordField;

/**
 * Helper class for loading providers from the storm framework.
 */
public final class Providers {

    private static final Supplier<List<ORMReflectionProvider>> ORM_REFLECTION_PROVIDERS = createProviders(ORMReflectionProvider.class);
    private static final Supplier<List<ORMConverterProvider>> ORM_CONVERTER_PROVIDERS = createProviders(ORMConverterProvider.class);
    private static final Supplier<List<EntityRepositoryProvider>> ENTITY_REPOSITORY_PROVIDERS = createProviders(EntityRepositoryProvider.class);
    private static final Supplier<List<ProjectionRepositoryProvider>> PROJECTION_REPOSITORY_PROVIDERS = createProviders(ProjectionRepositoryProvider.class);
    private static final Supplier<List<QueryBuilderProvider>> QUERY_BUILDER_REPOSITORY_PROVIDERS = createProviders(QueryBuilderProvider.class);
    private static final Supplier<List<SqlDialectProvider>> SQL_DIALECT_PROVIDERS = createProviders(SqlDialectProvider.class);
    private static final Supplier<List<ConnectionProvider>> CONNECTION_PROVIDERS = createProviders(ConnectionProvider.class);
    private static final Supplier<List<TransactionTemplateProvider>> TRANSACTION_TEMPLATE_PROVIDERS = createProviders(TransactionTemplateProvider.class);
    private static final Supplier<List<ExternalTransactionProvider>> EXTERNAL_TRANSACTION_PROVIDERS = createProviders(ExternalTransactionProvider.class);

    /**
     * Provider instances per class loader, keyed by provider class. The loaded instances keep their class loader
     * reachable, so the per-loader map is scoped to the loader's lifetime via {@link ClassLoaderCache} rather than
     * pinned for the lifetime of the JVM.
     */
    private static final ClassLoaderCache<ConcurrentMap<Class<?>, List<?>>> PROVIDER_CACHE = new ClassLoaderCache<>();

    /**
     * Returns a supplier that caches the provider instances responsible for providing the actual service
     * implementation classes.
     *
     * @param providerClass provider class to request supplier for.
     * @param <S> type of the requested service.
     * @return a supplier that returns the provider instances responsible for providing the actual service instances.
     */
    @SuppressWarnings("unchecked")
    private static <S extends Provider> Supplier<List<S>> createProviders(Class<S> providerClass) {
        return () -> {
            ClassLoader contextClassLoader = currentThread().getContextClassLoader();
            ClassLoader providersClassloader = Providers.class.getClassLoader();
            ClassLoader loader = ofNullable(contextClassLoader).orElse(providersClassloader);
            var providersByClass = PROVIDER_CACHE.computeIfAbsent(loader, ignore -> new ConcurrentHashMap<>());
            // Prefetch all providers to prevent race conditions in case of parallel execution.
            return (List<S>) providersByClass.computeIfAbsent(providerClass, ignore -> {
                    if (contextClassLoader != null) {
                        // Try the context class loader first.
                        List<S> list = toUnmodifiableList(load(providerClass, contextClassLoader));
                        if (!list.isEmpty()) {
                            return list;
                        }
                    }
                    // Revert to the providers' class loader.
                    return toUnmodifiableList(load(providerClass, providersClassloader));
                });
        };
    }

    /**
     * Returns a list of all services that are loaded by the specified {@code loader}, sorted by their
     * {@link Orderable} constraints. Sorting once at load time keeps every resolution in provider order: a filtered
     * subset of a valid topological order is itself a valid topological order.
     *
     * <p>Note that {@link Provider#isEnabled()} is deliberately not evaluated here: the returned list is cached for
     * the lifetime of the class loader, whereas enablement may depend on runtime state. Enablement is re-evaluated
     * at each resolution via {@link #enabled}.</p>
     *
     * @param loader loader of services.
     * @param <S> service type.
     * @return a list of all services loaded by the specified {@code loader}, in provider order.
     */
    private static <S extends Provider> List<S> toUnmodifiableList(ServiceLoader<S> loader) {
        return stream(loader.spliterator(), false)
                .collect(collectingAndThen(toList(), list -> Collections.unmodifiableList(Orderable.sort(list))));
    }

    /**
     * Returns a stream of the currently enabled providers from the given cached provider list, in provider order.
     *
     * @param providers the cached provider list supplier.
     * @param <S> provider type.
     * @return a stream of enabled providers, in provider order.
     */
    private static <S extends Provider> Stream<S> enabled(Supplier<List<S>> providers) {
        return providers.get().stream().filter(Provider::isEnabled);
    }

    private static final AtomicReference<ORMReflection> ORM_REFLECTION = new AtomicReference<>();

    /**
     * Resolved converters per declaring record class, keyed by field name. {@link ClassValue} ties each entry to
     * the lifetime of the declaring class, so cached converters never pin the class or its class loader.
     */
    private static final ClassValue<ConcurrentMap<String, Optional<ORMConverter>>> ORM_CONVERTERS = new ClassValue<>() {
        @Override
        protected ConcurrentMap<String, Optional<ORMConverter>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    public static ORMReflection getORMReflection() {
        return ORM_REFLECTION.updateAndGet(value -> requireNonNullElseGet(value, () -> enabled(ORM_REFLECTION_PROVIDERS)
                .map(ORMReflectionProvider::getReflection)
                .findFirst()
                .orElseThrow()));
    }

    public static Optional<ORMConverter> getORMConverter(RecordField field) {
        return ORM_CONVERTERS.get(field.declaringType()).computeIfAbsent(field.name(), ignore ->
                enabled(ORM_CONVERTER_PROVIDERS)
                        .map(p -> p.getConverter(field))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .findFirst());
    }

    public static <ID, E extends Entity<ID>> EntityRepository<E, ID> getEntityRepository(
            ORMTemplate ormTemplate,
            Model<E, ID> model,
            Predicate<? super EntityRepositoryProvider> filter) {
        return enabled(ENTITY_REPOSITORY_PROVIDERS)
                .filter(filter)
                .map(provider -> provider.getEntityRepository(ormTemplate, model))
                .findFirst()
                .orElseThrow();
    }

    public static <ID, P extends Projection<ID>> ProjectionRepository<P, ID> getProjectionRepository(
            ORMTemplate ormTemplate,
            Model<P, ID> model,
            Predicate<? super ProjectionRepositoryProvider> filter) {
        return enabled(PROJECTION_REPOSITORY_PROVIDERS)
                .filter(filter)
                .map(provider -> provider.getProjectionRepository(ormTemplate, model))
                .findFirst()
                .orElseThrow();
    }

    private static final AtomicReference<QueryBuilderProvider> QUERY_BUILDER_PROVIDER = new AtomicReference<>();

    /**
     * Resolves the query builder provider once and reuses it, mirroring {@link #getORMReflection()}: query builders
     * are created on every select and the provider order is fixed after startup.
     */
    private static QueryBuilderProvider queryBuilderProvider() {
        return QUERY_BUILDER_PROVIDER.updateAndGet(value -> requireNonNullElseGet(value, () ->
                enabled(QUERY_BUILDER_REPOSITORY_PROVIDERS)
                        .findFirst()
                        .orElseThrow()));
    }

    public static <T extends Data, R extends @Nullable Object, ID extends @Nullable Object> QueryBuilder<T, R, ID> selectFrom(
            QueryTemplate queryTemplate,
            Class<T> fromType,
            Class<R> selectType,
            TemplateString template,
            boolean subquery,
            Supplier<Model<T, ID>> modelSupplier) {
        return queryBuilderProvider().selectFrom(queryTemplate, fromType, selectType, template, subquery, modelSupplier);
    }

    public static <T extends Data, R extends Data, ID extends @Nullable Object> QueryBuilder<T, Ref<R>, ID> selectRefFrom(
            QueryTemplate queryTemplate,
            Class<T> fromType,
            Class<R> refType,
            Class<?> pkType,
            Supplier<Model<T, ID>> modelSupplier) {
        return queryBuilderProvider().selectRefFrom(queryTemplate, fromType, refType, pkType, modelSupplier);
    }

    public static <T extends Data, ID> QueryBuilder<T, ?, ID> deleteFrom(
            QueryTemplate queryTemplate,
            Class<T> fromType,
            Supplier<Model<T, ID>> modelSupplier) {
        return queryBuilderProvider().deleteFrom(queryTemplate, fromType, modelSupplier);
    }

    public static SqlDialect getSqlDialect() {
        return getSqlDialect(StormConfig.defaults());
    }

    /**
     * Resolves the SQL dialect from the classpath, without a database in view.
     *
     * <p>Enablement is re-evaluated on every resolution, and an ambiguous resolution fails fast.</p>
     *
     * @param config the Storm configuration to apply.
     * @return the SQL dialect.
     * @throws PersistenceException if no dialect provider is found or the resolution is ambiguous.
     */
    public static SqlDialect getSqlDialect(StormConfig config) {
        return selectUnique(SQL_DIALECT_PROVIDERS, "SQL dialect provider",
                "SqlTemplate.withDialect(...), or by binding the template to a DataSource or Connection so the " +
                        "dialect is derived from the database")
                .getSqlDialect(config);
    }

    public static SqlDialect getSqlDialect(Predicate<? super SqlDialectProvider> filter) {
        return getSqlDialect(filter, StormConfig.defaults());
    }

    public static SqlDialect getSqlDialect(Predicate<? super SqlDialectProvider> filter,
                                            StormConfig config) {
        return enabled(SQL_DIALECT_PROVIDERS)
                .filter(filter)
                .map(p -> p.getSqlDialect(config))
                .findFirst()
                .orElseThrow();
    }

    /** A weak reference to a data source with identity-based equality, usable as a map key. */
    private static final class DataSourceIdentity extends WeakReference<DataSource> {
        private final int hash;

        DataSourceIdentity(DataSource dataSource, ReferenceQueue<DataSource> queue) {
            super(dataSource, queue);
            this.hash = identityHashCode(dataSource);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                // Required to locate this key after its referent is cleared, so a stale entry can be removed.
                return true;
            }
            return other instanceof DataSourceIdentity identity && get() != null && get() == identity.get();
        }
    }

    private static final ReferenceQueue<DataSource> DATA_SOURCE_QUEUE = new ReferenceQueue<>();

    /**
     * Database product names per data source. The data source is held weakly so the cache never pins it, or the
     * connection pool behind it, once the application discards it; stale entries are drained on each access.
     */
    private static final ConcurrentMap<DataSourceIdentity, String> DATABASE_PRODUCT_NAMES = new ConcurrentHashMap<>();

    /**
     * Returns the database product name for the given data source, caching the result per data source identity.
     *
     * @param dataSource the data source to inspect.
     * @return the database product name.
     * @since 1.11
     */
    public static String getDatabaseProductName(DataSource dataSource) {
        Reference<? extends DataSource> stale;
        while ((stale = DATA_SOURCE_QUEUE.poll()) != null) {
            if (stale instanceof DataSourceIdentity identity) {
                DATABASE_PRODUCT_NAMES.remove(identity);
            }
        }
        return DATABASE_PRODUCT_NAMES.computeIfAbsent(new DataSourceIdentity(dataSource, DATA_SOURCE_QUEUE), ignore -> {
            try (Connection connection = dataSource.getConnection()) {
                return getDatabaseProductName(connection);
            } catch (SQLException e) {
                throw new PersistenceException("Failed to determine database product name.", e);
            }
        });
    }

    /**
     * Returns the database product name for the given connection.
     *
     * @param connection the connection to inspect.
     * @return the database product name.
     * @since 1.11
     */
    public static String getDatabaseProductName(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to determine database product name.", e);
        }
    }

    /**
     * Returns the first dialect provider that supports the given database product name, or {@code null} if no
     * specific provider matches (the default provider will be used as a fallback).
     *
     * @param databaseProductName the database product name.
     * @return the matching dialect provider, or {@code null}.
     * @since 1.11
     */
    public static @Nullable SqlDialectProvider getSqlDialectProvider(String databaseProductName) {
        return enabled(SQL_DIALECT_PROVIDERS)
                .filter(p -> p.supports(databaseProductName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the SQL dialect for the given data source by inspecting the database product name and selecting the
     * appropriate dialect provider.
     *
     * @param dataSource the data source to inspect.
     * @param config the Storm configuration to apply.
     * @return the SQL dialect.
     * @since 1.11
     */
    public static SqlDialect getSqlDialect(DataSource dataSource, StormConfig config) {
        return sqlDialectFor(getDatabaseProductName(dataSource), config);
    }

    /**
     * Returns the SQL dialect for the given connection by inspecting the database product name and selecting the
     * appropriate dialect provider.
     *
     * @param connection the connection to inspect.
     * @param config the Storm configuration to apply.
     * @return the SQL dialect.
     * @since 1.11
     */
    public static SqlDialect getSqlDialect(Connection connection, StormConfig config) {
        return sqlDialectFor(getDatabaseProductName(connection), config);
    }

    private static SqlDialect sqlDialectFor(String productName, StormConfig config) {
        return enabled(SQL_DIALECT_PROVIDERS)
                .filter(p -> p.supports(productName))
                .map(p -> p.getSqlDialect(config))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Resolves the fallback connection provider via {@code ServiceLoader} discovery.
     *
     * <p>This is the provider used by templates that have not been configured with an explicit
     * {@link ConnectionProvider} via the template builder. Enablement is re-evaluated on every resolution, and an
     * ambiguous resolution fails fast.</p>
     *
     * @return the fallback connection provider.
     * @throws PersistenceException if no provider is found or the resolution is ambiguous.
     * @since 1.13
     */
    public static ConnectionProvider getConnectionProvider() {
        return selectUnique(CONNECTION_PROVIDERS, "connection provider",
                "ORMTemplate.builder(dataSource).connectionProvider(...)");
    }

    /**
     * Resolves the fallback transaction template provider via {@code ServiceLoader} discovery.
     *
     * <p>This is the provider used by templates that have not been configured with an explicit
     * {@link TransactionTemplateProvider} via the template builder. Enablement is re-evaluated on every resolution,
     * and an ambiguous resolution fails fast.</p>
     *
     * @return the fallback transaction template provider.
     * @throws PersistenceException if no provider is found or the resolution is ambiguous.
     * @since 1.13
     */
    public static TransactionTemplateProvider getTransactionTemplateProvider() {
        return selectUnique(TRANSACTION_TEMPLATE_PROVIDERS, "transaction template provider",
                "ORMTemplate.builder(dataSource).transactionTemplateProvider(...)");
    }

    /**
     * Resolves the external transaction providers via {@code ServiceLoader} discovery, in resolution order.
     *
     * <p>Unlike the other lookups this one returns every enabled provider rather than a single winner: each
     * detects only the transaction manager it bridges, so several can coexist and at most one has a
     * transaction active on a given thread. Enablement is re-evaluated on every resolution.</p>
     *
     * @return the external transaction providers, in the order they are consulted.
     * @since 1.13
     */
    public static List<ExternalTransactionProvider> getExternalTransactionProviders() {
        return enabled(EXTERNAL_TRANSACTION_PROVIDERS).toList();
    }

    /**
     * Selects the single winning provider from the given cached provider list.
     *
     * <p>Fails fast when the two top-ranked candidates are unordered peers: silently picking one of several equally
     * eligible providers would make behavior dependent on classpath order.</p>
     */
    private static <S extends Provider> S selectUnique(Supplier<List<S>> providers,
                                                       String description,
                                                       String remedy) {
        var sorted = enabled(providers).toList();
        if (sorted.isEmpty()) {
            throw new PersistenceException("No %s found on the classpath.".formatted(description));
        }
        if (sorted.size() > 1 && !isOrderedBefore(sorted.get(0).getClass(), sorted.get(1).getClass())) {
            throw new PersistenceException(("Multiple candidates found for %s without a defined order: %s. " +
                    "Configure the desired implementation explicitly via %s.")
                    .formatted(description,
                            sorted.stream().map(provider -> provider.getClass().getName()).collect(joining(", ")),
                            remedy));
        }
        return sorted.getFirst();
    }

    /**
     * Returns whether {@code first} is explicitly ordered before {@code second} by the {@link Orderable} annotations.
     */
    private static boolean isOrderedBefore(Class<?> first, Class<?> second) {
        boolean firstBeforeAny = first.isAnnotationPresent(Orderable.BeforeAny.class);
        boolean secondBeforeAny = second.isAnnotationPresent(Orderable.BeforeAny.class);
        if (firstBeforeAny && !secondBeforeAny) {
            return true;
        }
        boolean firstAfterAny = first.isAnnotationPresent(Orderable.AfterAny.class);
        boolean secondAfterAny = second.isAnnotationPresent(Orderable.AfterAny.class);
        if (secondAfterAny && !firstAfterAny) {
            return true;
        }
        var before = first.getAnnotation(Orderable.Before.class);
        if (before != null && asList(before.value()).contains(second)) {
            return true;
        }
        var after = second.getAnnotation(Orderable.After.class);
        if (after != null && asList(after.value()).contains(first)) {
            return true;
        }
        return false;
    }
}
