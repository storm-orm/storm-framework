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

import static jakarta.persistence.TemporalType.DATE;
import static jakarta.persistence.TemporalType.TIME;
import static jakarta.persistence.TemporalType.TIMESTAMP;
import static st.orm.core.template.SqlTemplate.JPA;
import static st.orm.core.template.impl.LazySupplier.lazy;
import static st.orm.core.template.impl.RecordValidation.validate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.core.spi.JsonString;
import st.orm.core.spi.Provider;
import st.orm.core.spi.Providers;
import st.orm.core.spi.QueryFactory;
import st.orm.core.spi.RefFactory;
import st.orm.core.spi.RefFactoryImpl;
import st.orm.core.spi.WeakInterner;
import st.orm.core.template.JpaTemplate;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedQuery;
import st.orm.core.template.Query;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.NamedParameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.TableAliasResolver;
import st.orm.core.template.TemplateString;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;
import st.orm.spi.QueryContext.ExecutionKind;
import st.orm.spi.QueryObserver.Observation;

public final class JpaTemplateImpl implements JpaTemplate, QueryFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(JpaTemplateImpl.class);

    /** The properties a persistence unit publishes its data source under, newest name first. */
    private static final List<String> DATA_SOURCE_PROPERTIES = List.of(
            "jakarta.persistence.nonJtaDataSource",
            "jakarta.persistence.jtaDataSource",
            "javax.persistence.nonJtaDataSource",
            "javax.persistence.jtaDataSource");

    @FunctionalInterface
    private interface TemplateProcessor {
        jakarta.persistence.Query process(Sql sql, @Nullable Class<?> resultClass, boolean unsafe);
    }

    private final TemplateProcessor templateProcessor;
    private final ModelBuilder modelBuilder;
    private final TableAliasResolver tableAliasResolver;
    private final Predicate<Provider> providerFilter;
    private final RefFactory refFactory;

    /**
     * Built on first use: without a provider filter it consumes the resolved dialect, whose classpath fallback must
     * stay untouched until the template is actually used.
     */
    private final Supplier<SqlTemplate> sqlTemplate;

    private final StormConfig config;

    /**
     * The dialect of the persistence unit's database, or a classpath fallback resolved on first use when the database
     * is unknown. Deferring the fallback keeps an ambiguous classpath from failing template construction, so a
     * provider filter can still be applied via {@link #withProviderFilter(Predicate)}.
     */
    private final Supplier<SqlDialect> dialect;

    public JpaTemplateImpl(EntityManager entityManager) {
        this(entityManager, StormConfig.defaults());
    }

    public JpaTemplateImpl(EntityManager entityManager, StormConfig config) {
        validate(config);
        templateProcessor = (sql, resultClass, unsafe) -> {
            if (!unsafe) {
                sql.unsafeWarning().ifPresent(warning -> {
                    throw new PersistenceException("%s Use Query.unsafe() to allow this operation.".formatted(warning));
                });
            }
            //noinspection SqlSourceToSinkFlow
            jakarta.persistence.Query query = resultClass == null
                    ? entityManager.createNativeQuery(sql.statement())
                    : entityManager.createNativeQuery(sql.statement(), resultClass);
            setParameters(query, sql.parameters());
            return query;
        };
        this.modelBuilder = ModelBuilder.newInstance();
        this.tableAliasResolver = TableAliasResolver.DEFAULT;
        this.providerFilter = null;
        this.config = config;
        this.dialect = resolveDialect(entityManager, config);
        this.refFactory = new RefFactoryImpl(this, modelBuilder, providerFilter);
        this.sqlTemplate = lazy(this::createSqlTemplate);
    }

    /**
     * Resolves the dialect for the database behind the given entity manager.
     *
     * <p>Asking the persistence unit for its data source is the portable way to reach the database: unwrapping an
     * entity manager to a {@link java.sql.Connection} is not supported by every provider. A persistence unit
     * configured with a connection URL rather than a data source, or one whose database cannot be reached while the
     * template is being built, leaves the database unknown, and the dialect then comes from the classpath, resolved
     * on first use.</p>
     */
    private static Supplier<SqlDialect> resolveDialect(EntityManager entityManager, StormConfig config) {
        DataSource dataSource = dataSourceOf(entityManager);
        if (dataSource == null) {
            return lazy(() -> Providers.getSqlDialect(config));
        }
        try {
            return new LazySupplier<>(Providers.getSqlDialect(dataSource, config));
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to determine the database of the persistence unit.", e);
            return lazy(() -> Providers.getSqlDialect(config));
        }
    }

    /**
     * Returns the data source the persistence unit was configured with, or {@code null} if it was configured
     * without one. Both the Jakarta Persistence property names and their Java Persistence predecessors are
     * consulted, since providers carry the legacy names forward.
     */
    private static @Nullable DataSource dataSourceOf(EntityManager entityManager) {
        Map<String, Object> properties;
        try {
            properties = entityManager.getEntityManagerFactory().getProperties();
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to read the properties of the persistence unit.", e);
            return null;
        }
        for (String property : DATA_SOURCE_PROPERTIES) {
            if (properties.get(property) instanceof DataSource dataSource) {
                return dataSource;
            }
        }
        return null;
    }

    private JpaTemplateImpl(TemplateProcessor templateProcessor,
                            ModelBuilder modelBuilder,
                            TableAliasResolver tableAliasResolver,
                            @Nullable Predicate<Provider> providerFilter,
                            StormConfig config,
                            Supplier<SqlDialect> dialect) {
        this.dialect = dialect;
        this.templateProcessor = templateProcessor;
        this.modelBuilder = modelBuilder;
        this.tableAliasResolver = tableAliasResolver;
        this.providerFilter = providerFilter;
        this.config = config;
        this.refFactory = new RefFactoryImpl(this, modelBuilder, providerFilter);
        this.sqlTemplate = lazy(this::createSqlTemplate);
    }

    private SqlTemplate createSqlTemplate() {
        SqlTemplate template = JPA.withConfig(config)
                .withTableNameResolver(modelBuilder.tableNameResolver())
                .withSchemaResolver(modelBuilder.schemaResolver())
                .withColumnNameResolver(modelBuilder.columnNameResolver())
                .withForeignKeyResolver(modelBuilder.foreignKeyResolver())
                .withTableAliasResolver(tableAliasResolver);
        // The shared template resolves a dialect without a database in view, so the dialect resolved for this
        // persistence unit is applied on top. An explicit provider filter still wins.
        return template.withDialect(
                providerFilter != null ? Providers.getSqlDialect(providerFilter, config) : dialect.get());
    }

    private void setParameters(jakarta.persistence.Query query, List<SqlTemplate.Parameter> parameters) {
        for (var parameter : parameters) {
            var dbValue = parameter.dbValue();
            switch (parameter) {
                case PositionalParameter p -> {
                    switch (dbValue) {
                        case null -> query.setParameter(p.position(), null);
                        case java.sql.Date d -> query.setParameter(p.position(), d, DATE);
                        case java.sql.Time d -> query.setParameter(p.position(), d, TIME);
                        case java.sql.Timestamp d -> query.setParameter(p.position(), d, TIMESTAMP);
                        case JsonString js -> query.setParameter(p.position(), js.value());
                        default -> query.setParameter(p.position(), dbValue);
                    }
                }
                case NamedParameter n -> {
                    switch (dbValue) {
                        case null -> query.setParameter(n.name(), null);
                        case java.sql.Date d -> query.setParameter(n.name(), d, DATE);
                        case java.sql.Time d -> query.setParameter(n.name(), d, TIME);
                        case java.sql.Timestamp d -> query.setParameter(n.name(), d, TIMESTAMP);
                        case JsonString js -> query.setParameter(n.name(), js.value());
                        default -> query.setParameter(n.name(), dbValue);
                    }
                }
            }
        }
    }

    /**
     * Creates a query for the specified query {@code template}.
     *
     * @param template the query template.
     * @return the query.
     */
    @Override
    public jakarta.persistence.Query query(TemplateString template) {
        try {
            var sql = sqlTemplate().process(template);
            return templateProcessor.process(sql, null, true);  // We allow unsafe queries in direct JPA mode.
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e.getMessage(), e);
        }
    }

    private jakarta.persistence.Query query(TemplateString template, Class<?> resultClass) {
        try {
            var sql = sqlTemplate().process(template);
            return templateProcessor.process(sql, resultClass, true);  // We allow unsafe queries in direct JPA mode.
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e.getMessage(), e);
        }
    }

    /**
     * Get the SQL template used by this factory.
     *
     * <p>Query factory implementations must ensure that the SQL Template returned by this method is processed by any
     * registered {@code SqlInterceptor} instances before being returned. As a result, this method is expected to
     * return a new instance of the SQL template each time it is called, ensuring that any modifications made by
     * interceptors are applied correctly.</p>
     *
     * @return the SQL template.
     * @since 1.3
     */
    @Override
    public SqlTemplate sqlTemplate() {
        return  SqlInterceptorManager.customize(sqlTemplate.get());
    }

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     */
    @Override
    public BindVars createBindVars() {
        throw new PersistenceException("Not supported by JPA.");
    }

    @Override
    public Query create(TemplateString template) {
        return new JpaPreparedQuery(template);
    }

    /**
     * Returns an ORM template for this JPA template.
     */
    @Override
    public ORMTemplate toORM() {
        return new ORMTemplateImpl(this, ModelBuilder.newInstance(), providerFilter, config);
    }

    /**
     * Returns a new JPA template with the specified table name resolver.
     *
     * @param tableNameResolver the table name resolver.
     * @return a new JPA template.
     */
    @Override
    public JpaTemplate withTableNameResolver(@Nullable TableNameResolver tableNameResolver) {
        return new JpaTemplateImpl(templateProcessor, modelBuilder.tableNameResolver(tableNameResolver), tableAliasResolver, providerFilter, config, dialect);
    }

    /**
     * Returns a new JPA template with the specified schema resolver.
     *
     * @param schemaResolver the schema resolver.
     * @return a new JPA template.
     */
    @Override
    public JpaTemplate withSchemaResolver(@Nullable SchemaResolver schemaResolver) {
        return new JpaTemplateImpl(templateProcessor, modelBuilder.schemaResolver(schemaResolver), tableAliasResolver, providerFilter, config, dialect);
    }

    /**
     * Returns a new jpa statement template with the specified column name resolver.
     *
     * @param columnNameResolver the column name resolver.
     * @return a new jpa statement template.
     */
    @Override
    public JpaTemplate withColumnNameResolver(@Nullable ColumnNameResolver columnNameResolver) {
        return new JpaTemplateImpl(templateProcessor, modelBuilder.columnNameResolver(columnNameResolver), tableAliasResolver, providerFilter, config, dialect);
    }

    /**
     * Returns a new jpa statement template with the specified foreign key resolver.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return a new jpa statement template.
     */
    @Override
    public JpaTemplate withForeignKeyResolver(@Nullable ForeignKeyResolver foreignKeyResolver) {
        return new JpaTemplateImpl(templateProcessor, modelBuilder.foreignKeyResolver(foreignKeyResolver), tableAliasResolver, providerFilter, config, dialect);
    }

    /**
     * Returns a new JPA template with the specified table alias resolver.
     *
     * @param tableAliasResolver the table alias resolver.
     * @return a new JPA template.
     */
    @Override
    public JpaTemplate withTableAliasResolver(TableAliasResolver tableAliasResolver) {
        return new JpaTemplateImpl(templateProcessor, modelBuilder, tableAliasResolver, providerFilter, config, dialect);
    }

    /**
     * Returns a new jpa statement template with the specified provider filter.
     *
     * @param providerFilter the provider filter.
     * @return a new jpa statement template.
     */
    @Override
    public JpaTemplate withProviderFilter(@Nullable Predicate<Provider> providerFilter) {
        return new JpaTemplateImpl(templateProcessor, modelBuilder, tableAliasResolver, providerFilter, config, dialect);
    }

    private class JpaPreparedQuery implements PreparedQuery {
        private final TemplateString template;

        public JpaPreparedQuery(TemplateString template) {
            this.template = template;
        }

        /** One processed template: the statement a scope's bracket describes, and the query that runs it. */
        private record Processed(Sql sql, jakarta.persistence.Query query) {
        }

        private Processed process(@Nullable Class<?> resultClass) {
            try {
                var sql = sqlTemplate().process(template);
                // Unsafe queries are allowed in direct JPA mode.
                return new Processed(sql, templateProcessor.process(sql, resultClass, true));
            } catch (SqlTemplateException e) {
                throw new PersistenceException(e.getMessage(), e);
            }
        }

        /**
         * Notifies the scopes on the calling thread that an execution is starting, or returns {@code null} when
         * none listens. A scope reports what a call cost whichever template ran it, so the JPA path brackets its
         * executions exactly like the JDBC path.
         */
        @SuppressWarnings("unchecked")
        private QueryImpl.ListenedObservation listen(Sql sql,
                                                     @Nullable Class<?> resultClass,
                                                     ExecutionKind kind) {
            try {
                var operators = SqlInterceptorManager.localOperators();
                if (operators == null) {
                    return null;
                }
                Class<? extends Data> dataType = resultClass != null && Data.class.isAssignableFrom(resultClass)
                        ? (Class<? extends Data>) resultClass
                        : null;
                var context = new QueryImpl.QueryContextImpl(sql.operation(), dataType, kind, sql.statement(),
                        sql.origin(), sql.shapeId());
                var handles = QueryImpl.listen(operators, context, sql.parameters());
                if (handles == null) {
                    return null;
                }
                return new QueryImpl.ListenedObservation(Observation.NOOP, handles);
            } catch (Throwable ignore) {
                // Scope failures never affect query execution.
                return null;
            }
        }

        /** Counts the rows the stream produces into the observation, closing it with the stream. */
        private static <T> Stream<T> counted(Stream<T> stream,
                                             QueryImpl.ListenedObservation listened) {
            return StreamSupport.stream(QueryImpl.counting(stream.spliterator(), listened), false)
                    .onClose(stream::close)
                    .onClose(listened::close);
        }

        @Override
        public PreparedQuery prepare() {
            return this;
        }

        /**
         * Returns this query unchanged. The managed flag has no effect for JPA queries since JPA does not use the
         * entity cache mechanism.
         */
        @Override
        public Query managed() {
            return this;
        }

        /**
         * Returns this query unchanged. The unsafe flag has no effect for JPA queries since unsafe query checks are not
         * enforced in direct JPA mode.
         */
        @Override
        public Query unsafe() {
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Stream<Object[]> getResultStream() {
            var processed = process(null);
            var listened = listen(processed.sql(), null, ExecutionKind.QUERY);
            Stream<Object[]> stream = processed.query().getResultStream().map(this::convert);
            return listened == null ? stream : counted(stream, listened);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> Stream<T> getResultStream(Class<T> type) {
            var processed = process(type);
            var listened = listen(processed.sql(), type, ExecutionKind.QUERY);
            Stream<T> stream = processed.query().getResultStream();
            return listened == null ? stream : counted(stream, listened);
        }

        @Override
        public <T extends Data> Stream<Ref<T>> getRefStream(Class<T> type, Class<?> pkType) {
            var interner = new WeakInterner();
            return getResultStream(pkType)
                    .map(pk -> pk == null ? null : interner.intern(refFactory.create(type, pk)));
        }

        @Override
        public boolean isVersionAware() {
            throw new UnsupportedOperationException("Not supported by JPA.");
        }

        @Override
        public int executeUpdate() {
            var processed = process(null);
            var listened = listen(processed.sql(), null, ExecutionKind.UPDATE);
            if (listened == null) {
                return processed.query().executeUpdate();
            }
            try {
                int result = processed.query().executeUpdate();
                listened.rows(result);
                return result;
            } finally {
                listened.close();
            }
        }

        /**
         * Converts a database row into a list of values.
         *
         * @param row row to convert.
         * @return an array of values.
         */
        private Object[] convert(@Nullable Object row) {
            if (row == null || !row.getClass().isArray()) {
                return new Object[]{row};
            }
            return (Object[]) row;
        }

        @Override
        public void addBatch(Data record) {
            throw new UnsupportedOperationException("Not supported by JPA.");
        }

        @Override
        public int[] executeBatch() {
            throw new UnsupportedOperationException("Not supported by JPA.");
        }

        @Override
        public <ID> Stream<ID> getGeneratedKeys(Class<ID> type) {
            throw new UnsupportedOperationException("Not supported by JPA.");
        }

        @Override
        public void close() {
        }
    }
}
