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

import static st.orm.core.spi.Providers.getConnection;
import static st.orm.core.spi.Providers.getSqlDialect;
import static st.orm.core.spi.Providers.releaseConnection;
import static st.orm.core.template.SqlTemplate.PS;
import static st.orm.core.template.impl.ExceptionHelper.getExceptionTransformer;
import static st.orm.core.template.impl.LazySupplier.lazy;
import static st.orm.core.template.impl.RecordValidation.validate;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.sql.DataSource;
import st.orm.BindVars;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.spi.Provider;
import st.orm.core.spi.Providers;
import st.orm.core.spi.QueryFactory;
import st.orm.core.spi.RefFactory;
import st.orm.core.spi.RefFactoryImpl;
import st.orm.core.spi.SqlDialectProvider;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.TransactionTemplate;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.Query;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.BatchListener;
import st.orm.core.template.SqlTemplate.NamedParameter;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.TableAliasResolver;
import st.orm.core.template.TemplateString;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.TableNameResolver;

public final class PreparedStatementTemplateImpl implements PreparedStatementTemplate, QueryFactory {

    @FunctionalInterface
    private interface TemplateProcessor {
        PreparedStatement process(@Nonnull Sql sql,
                                  boolean unsafe) throws SQLException;
    }

    private final TemplateProcessor templateProcessor;
    private final @Nullable DataSource dataSource;
    private final ModelBuilder modelBuilder;
    private final TableAliasResolver tableAliasResolver;
    private final Predicate<Provider> providerFilter;
    private final RefFactory refFactory;
    private final TransactionTemplate transactionTemplate;
    private final SqlTemplate sqlTemplate;
    private final StormConfig config;

    public PreparedStatementTemplateImpl(@Nonnull DataSource dataSource) {
        this(dataSource, StormConfig.defaults());
    }

    public PreparedStatementTemplateImpl(@Nonnull DataSource dataSource, @Nonnull StormConfig config) {
        this(Providers.getTransactionTemplate(), dataSource, config);
    }

    // Note that this logic does not use Spring's DataSourceUtils, so it is not aware of Spring's transaction
    // management.
    private PreparedStatementTemplateImpl(@Nonnull TransactionTemplate transactionTemplate,
                                          @Nonnull DataSource dataSource,
                                          @Nonnull StormConfig config) {
        this(transactionTemplate, dataSource, config,
                Providers.getSqlDialectProvider(Providers.getDatabaseProductName(dataSource)));
    }

    private PreparedStatementTemplateImpl(@Nonnull TransactionTemplate transactionTemplate,
                                          @Nonnull DataSource dataSource,
                                          @Nonnull StormConfig config,
                                          @Nullable SqlDialectProvider matchedProvider) {
        this(createDataSourceProcessor(dataSource, transactionTemplate,
                        matchedProvider != null ? matchedProvider.getSqlDialect(config) : getSqlDialect(config)),
                dataSource,
                ModelBuilder.newInstance(), TableAliasResolver.DEFAULT,
                matchedProvider != null ? matchedProvider.getProviderFilter() : null,
                transactionTemplate, config);
    }

    public PreparedStatementTemplateImpl(@Nonnull Connection connection) {
        this(connection, StormConfig.defaults());
    }

    public PreparedStatementTemplateImpl(@Nonnull Connection connection, @Nonnull StormConfig config) {
        this(Providers.getTransactionTemplate(), connection, config);
    }

    private PreparedStatementTemplateImpl(@Nonnull TransactionTemplate transactionTemplate,
                                          @Nonnull Connection connection,
                                          @Nonnull StormConfig config) {
        this(transactionTemplate, connection, config,
                Providers.getSqlDialectProvider(Providers.getDatabaseProductName(connection)));
    }

    private PreparedStatementTemplateImpl(@Nonnull TransactionTemplate transactionTemplate,
                                          @Nonnull Connection connection,
                                          @Nonnull StormConfig config,
                                          @Nullable SqlDialectProvider matchedProvider) {
        this(createConnectionProcessor(connection, transactionTemplate,
                        matchedProvider != null ? matchedProvider.getSqlDialect(config) : getSqlDialect(config)),
                null,
                ModelBuilder.newInstance(), TableAliasResolver.DEFAULT,
                matchedProvider != null ? matchedProvider.getProviderFilter() : null,
                transactionTemplate, config);
    }

    private PreparedStatementTemplateImpl(@Nonnull TemplateProcessor templateProcessor,
                                          @Nullable DataSource dataSource,
                                          @Nonnull ModelBuilder modelBuilder,
                                          @Nonnull TableAliasResolver tableAliasResolver,
                                          @Nullable Predicate<Provider> providerFilter,
                                          @Nonnull TransactionTemplate transactionTemplate,
                                          @Nonnull StormConfig config) {
        validate(config);
        this.templateProcessor = templateProcessor;
        this.dataSource = dataSource;
        this.modelBuilder = modelBuilder;
        this.tableAliasResolver = tableAliasResolver;
        this.providerFilter = providerFilter;
        this.refFactory = new RefFactoryImpl(this, modelBuilder, providerFilter);
        this.transactionTemplate = transactionTemplate;
        this.config = config;
        this.sqlTemplate = createSqlTemplate();
    }

    private static TemplateProcessor createDataSourceProcessor(@Nonnull DataSource dataSource,
                                                                @Nonnull TransactionTemplate transactionTemplate,
                                                                @Nonnull SqlDialect dialect) {
        return (sql, unsafe) -> {
            if (!unsafe) {
                sql.unsafeWarning().ifPresent(warning -> {
                    throw new PersistenceException("%s Use Query.unsafe() to allow this operation.".formatted(warning));
                });
            }
            var statement = sql.statement();
            var parameters = sql.parameters();
            var bindVariables = sql.bindVariables().orElse(null);
            var generatedKeys = sql.generatedKeys();
            var transactionContext = transactionTemplate.currentContext().orElse(null);
            Connection connection = getConnection(dataSource, transactionContext);
            PreparedStatement preparedStatement = null;
            boolean success = false;
            try {
                if (!generatedKeys.isEmpty()) {
                    try {
                        //noinspection SqlSourceToSinkFlow
                        preparedStatement = connection.prepareStatement(statement, generatedKeys.toArray(new String[0]));
                    } catch (SQLFeatureNotSupportedException ignore) {}
                }
                if (preparedStatement == null) {
                    //noinspection SqlSourceToSinkFlow
                    preparedStatement = connection.prepareStatement(statement);
                }
                if (transactionContext != null) {
                    preparedStatement = transactionContext.getDecorator(PreparedStatement.class)
                            .decorate(preparedStatement);
                }
                if (!dialect.streamOnlyFetchSize() && dialect.defaultFetchSize() != 0) {
                    preparedStatement.setFetchSize(dialect.defaultFetchSize());
                }
                if (bindVariables == null) {
                    setParameters(preparedStatement, parameters, dialect);
                } else {
                    bindVariables.setBatchListener(getBatchListener(preparedStatement, parameters, dialect));
                }
                success = true;
            } finally {
                if (!success) {
                    if (preparedStatement != null) {
                        try {
                            preparedStatement.close();
                        } catch (SQLException ignore) {}
                    }
                    releaseConnection(connection, dataSource, transactionContext);
                }
            }
            return createProxy(preparedStatement, connection, dataSource, transactionContext);
        };
    }

    private static TemplateProcessor createConnectionProcessor(@Nonnull Connection connection,
                                                                @Nonnull TransactionTemplate transactionTemplate,
                                                                @Nonnull SqlDialect dialect) {
        return (sql, unsafe) -> {
            if (!unsafe) {
                sql.unsafeWarning().ifPresent(warning -> {
                    throw new PersistenceException("%s Use Query.unsafe() to allow this operation.".formatted(warning));
                });
            }
            var statement = sql.statement();
            var parameters = sql.parameters();
            var bindVariables = sql.bindVariables().orElse(null);
            var generatedKeys = sql.generatedKeys();
            PreparedStatement preparedStatement = null;
            boolean success = false;
            try {
                if (!generatedKeys.isEmpty()) {
                    try {
                        //noinspection SqlSourceToSinkFlow
                        preparedStatement = connection.prepareStatement(statement, generatedKeys.toArray(new String[0]));
                    } catch (SQLFeatureNotSupportedException ignore) {}
                }
                if (preparedStatement == null) {
                    //noinspection SqlSourceToSinkFlow
                    preparedStatement = connection.prepareStatement(statement);
                }
                var transactionContext = transactionTemplate.currentContext().orElse(null);
                if (transactionContext != null) {
                    preparedStatement = transactionContext.getDecorator(PreparedStatement.class)
                            .decorate(preparedStatement);
                }
                if (!dialect.streamOnlyFetchSize() && dialect.defaultFetchSize() != 0) {
                    preparedStatement.setFetchSize(dialect.defaultFetchSize());
                }
                if (bindVariables == null) {
                    setParameters(preparedStatement, parameters, dialect);
                } else {
                    bindVariables.setBatchListener(getBatchListener(preparedStatement, parameters, dialect));
                }
                success = true;
                return preparedStatement;
            } finally {
                if (!success && preparedStatement != null) {
                    try {
                        preparedStatement.close();
                    } catch (SQLException ignore) {}
                }
            }
        };
    }

    private SqlTemplate createSqlTemplate() {
        SqlTemplate template = PS.withConfig(config)
                .withTableNameResolver(modelBuilder.tableNameResolver())
                .withColumnNameResolver(modelBuilder.columnNameResolver())
                .withForeignKeyResolver(modelBuilder.foreignKeyResolver())
                .withTableAliasResolver(tableAliasResolver);
        if (providerFilter != null) {
            template = template.withDialect(getSqlDialect(providerFilter, config));
        }
        return template;
    }

    /**
     * Returns a new prepared statement template with the specified table name resolver.
     *
     * @param tableNameResolver the table name resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withTableNameResolver(@Nullable TableNameResolver tableNameResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder.tableNameResolver(tableNameResolver), tableAliasResolver, providerFilter, transactionTemplate, config);
    }

    /**
     * Returns a new prepared statement template with the specified column name resolver.
     *
     * @param columnNameResolver the column name resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withColumnNameResolver(@Nullable ColumnNameResolver columnNameResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder.columnNameResolver(columnNameResolver), tableAliasResolver, providerFilter, transactionTemplate, config);
    }

    /**
     * Returns a new prepared statement template with the specified foreign key resolver.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withForeignKeyResolver(@Nullable ForeignKeyResolver foreignKeyResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder.foreignKeyResolver(foreignKeyResolver), tableAliasResolver, providerFilter, transactionTemplate, config);
    }

    /**
     * Returns a new prepared statement template with the specified table alias resolver.
     *
     * @param tableAliasResolver the table alias resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplate withTableAliasResolver(@Nonnull TableAliasResolver tableAliasResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder, tableAliasResolver, providerFilter, transactionTemplate, config);
    }

    /**
     * Returns a new prepared statement template with the specified provider filter.
     *
     * @param providerFilter the provider filter.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withProviderFilter(@Nullable Predicate<Provider> providerFilter) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder, tableAliasResolver, providerFilter, transactionTemplate, config);
    }

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     */
    @Override
    public BindVars createBindVars() {
        return sqlTemplate().createBindVars();
    }

    private static BatchListener getBatchListener(@Nonnull PreparedStatement preparedStatement,
                                                   @Nonnull List<Parameter> parameters,
                                                   @Nonnull SqlDialect dialect) {
        var calendarSupplier = lazy(() -> Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)));
        return batchParameters -> {
            try {
                setParameters(preparedStatement, parameters, calendarSupplier, dialect);
                setParameters(preparedStatement, batchParameters, calendarSupplier, dialect);
                preparedStatement.addBatch();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        };
    }

    private static void setParameters(@Nonnull PreparedStatement preparedStatement,
                                      @Nonnull List<? extends Parameter> parameters,
                                      @Nonnull SqlDialect dialect) throws SQLException {
        var calendarSupplier = lazy(() -> Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)));
        setParameters(preparedStatement, parameters, calendarSupplier, dialect);
    }

    private static void setParameters(@Nonnull PreparedStatement preparedStatement,
                                      @Nonnull List<? extends Parameter> parameters,
                                      @Nonnull Supplier<Calendar> calendarSupplier,
                                      @Nonnull SqlDialect dialect) throws SQLException {
        for (var parameter : parameters) {
            switch (parameter) {
                case PositionalParameter p -> {
                    final int idx = p.position();
                    final Object v = p.dbValue();
                    switch (v) {
                        case null              -> preparedStatement.setObject(idx, null);
                        case Short s           -> preparedStatement.setShort(idx, s);
                        case Integer i         -> preparedStatement.setInt(idx, i);
                        case Long l            -> preparedStatement.setLong(idx, l);
                        case Float f           -> preparedStatement.setFloat(idx, f);
                        case Double d          -> preparedStatement.setDouble(idx, d);
                        case Byte b            -> preparedStatement.setByte(idx, b);
                        case Boolean b         -> preparedStatement.setBoolean(idx, b);
                        case String s          -> preparedStatement.setString(idx, s);
                        case BigDecimal bd     -> preparedStatement.setBigDecimal(idx, bd);
                        case ByteBuffer buf -> {
                            byte[] bytes = new byte[buf.remaining()];
                            buf.duplicate().get(bytes);
                            preparedStatement.setBytes(idx, bytes);
                        }
                        case java.sql.Date d   -> preparedStatement.setDate(idx, d);
                        case Time t            -> preparedStatement.setTime(idx, t);
                        case Timestamp ts      -> dialect.setParameter(preparedStatement, idx, ts, calendarSupplier.get());
                        case UUID u            -> dialect.setParameter(preparedStatement, idx, u);
                        case Enum<?> e         -> preparedStatement.setString(idx, e.name());   // Enum handled by ORM layer.
                        // java.time using vendor-safe approach.
                        case LocalDate ld      -> preparedStatement.setDate(idx, java.sql.Date.valueOf(ld));
                        case LocalTime lt      -> preparedStatement.setTime(idx, java.sql.Time.valueOf(lt));
                        case LocalDateTime ldt -> preparedStatement.setTimestamp(idx, Timestamp.valueOf(ldt));
                        case OffsetDateTime odt-> dialect.setParameter(preparedStatement, idx, Timestamp.from(odt.toInstant()), calendarSupplier.get());
                        case ZonedDateTime zdt -> dialect.setParameter(preparedStatement, idx, Timestamp.from(zdt.toInstant()), calendarSupplier.get());
                        case Instant inst      -> dialect.setParameter(preparedStatement, idx, Timestamp.from(inst), calendarSupplier.get());
                        default                -> preparedStatement.setObject(idx, v);
                    }
                }
                case NamedParameter ignored ->
                        throw new SQLException("Named parameters not supported for PreparedStatement.");
            }
        }
    }

    @FunctionalInterface
    interface SqlRunnable { void run() throws SQLException; }

    private static void setObjectOr(PreparedStatement ps,
                                    AtomicBoolean supportsSetObject,
                                    SqlRunnable typedSetter,
                                    SqlRunnable legacyFallback) throws SQLException {
        if (supportsSetObject.get()) {
            try {
                typedSetter.run();
                return;
            } catch (SQLFeatureNotSupportedException e) {
                supportsSetObject.set(false);
            }
        }
        legacyFallback.run();
    }

    @Override
    public @Nullable DataSource dataSource() {
        return dataSource;
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
        return SqlInterceptorManager.customize(sqlTemplate);
    }

    /**
     * Returns an ORM template that is backed by this prepared statement template.
     *
     * @return the ORM template.
     */
    @Override
    public ORMTemplate toORM() {
        return new ORMTemplateImpl(this, modelBuilder, providerFilter, config);
    }

    /**
     * Returns the configuration associated with this template.
     *
     * @return the Storm configuration; never {@code null}.
     */
    public StormConfig config() {
        return config;
    }

    /**
     * Creates a proxy for the PreparedStatement that closes the connection when the PreparedStatement is closed.
     *
     * @param statement the PreparedStatement to create a proxy for.
     * @param connection the connection to close when the PreparedStatement is closed.
     * @return a proxy for the PreparedStatement that closes the connection when the PreparedStatement is closed.
     */
    private static PreparedStatement createProxy(@Nonnull PreparedStatement statement, @Nonnull Connection connection, @Nonnull DataSource dataSource, @Nullable TransactionContext context) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (ignore, method, args) -> {
                    // Check if the close method is being called on the PreparedStatement.
                    if (method.getName().equals("close")) {
                        try {
                            statement.close();
                        } finally {
                            releaseConnection(connection, dataSource, context);
                        }
                        return null;
                    }
                    try {
                        // For other methods, just invoke the method on the actual PreparedStatement.
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                }
        );
    }

    /**
     * Create a new query for the specified {@code template}.
     *
     * @param template the template to process.
     * @return a query that can be executed.
     * @throws PersistenceException if the template is invalid.
     */
    @Override
    public Query create(@Nonnull TemplateString template) {
        try {
            var sql = sqlTemplate().process(template);
            var bindVariables = sql.bindVariables().orElse(null);
            SqlDialect dialect = providerFilter != null
                    ? getSqlDialect(providerFilter, config)
                    : getSqlDialect(config);
            return new QueryImpl(refFactory, unsafe -> {
                try {
                    return templateProcessor.process(sql, unsafe);
                } catch (SQLException e) {
                    throw new PersistenceException(e);
                }
            }, bindVariables == null ? null : bindVariables.getHandle(), sql.affectedType().orElse(null), sql.versionAware(), false, false, dialect.defaultFetchSize(), dialect.streamOnlyFetchSize(), dialect.streamingRequiresTransaction(), getExceptionTransformer(sql));
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    /**
     * Creates a query for the specified query {@code template}.
     *
     * @param template the query template.
     * @return the query.
     */
    @Override
    public PreparedStatement query(@Nonnull TemplateString template) throws SQLException {
        var sql = sqlTemplate().process(template);
        return templateProcessor.process(sql, true);    // We allow unsafe queries in direct JDBC mode.
    }
}
