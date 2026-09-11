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

import static java.util.Objects.requireNonNullElseGet;
import static st.orm.core.spi.Providers.getSqlDialect;
import static st.orm.core.template.SqlTemplate.PS;
import static st.orm.core.template.impl.ExceptionHelper.getExceptionTransformer;
import static st.orm.core.template.impl.LazySupplier.lazy;
import static st.orm.core.template.impl.RecordValidation.validate;

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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.PersistenceException;
import st.orm.ReadOnlyTransactionException;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.core.spi.ConnectionProvider;
import st.orm.core.spi.JsonString;
import st.orm.core.spi.Provider;
import st.orm.core.spi.Providers;
import st.orm.core.spi.QueryFactory;
import st.orm.core.spi.RefFactory;
import st.orm.core.spi.RefFactoryImpl;
import st.orm.core.spi.SqlDialectProvider;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.TransactionScope;
import st.orm.core.spi.TransactionTemplateProvider;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.Query;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.BatchListener;
import st.orm.core.template.SqlTemplate.NamedParameter;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.TableAliasResolver;
import st.orm.core.template.TemplateString;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.TableNameResolver;
import st.orm.spi.ExceptionMapper;
import st.orm.spi.QueryObserver;
import st.orm.spi.SqlCommenter;

public final class PreparedStatementTemplateImpl implements PreparedStatementTemplate, QueryFactory {

    @FunctionalInterface
    private interface TemplateProcessor {
        PreparedStatement process(Sql sql,
                                  boolean unsafe) throws SQLException;
    }

    /**
     * The instance-scoped integration strategies of a template. The connection provider is {@code null} for
     * templates that are backed by a single connection rather than a data source.
     */
    record IntegrationStrategies(@Nullable ConnectionProvider connectionProvider,
                                 TransactionTemplateProvider transactionTemplateProvider,
                                 ExceptionMapper exceptionMapper,
                                 QueryObserver queryObserver,
                                 @Nullable SqlCommenter sqlCommenter) {
    }

    /**
     * Appends the commenter's content to the statement, after all statement processing and caching. A
     * commenter must never be able to alter the statement: content containing the comment terminator is
     * rejected (the only escape from a block comment), and so are semicolons (inert to the SQL parser inside
     * a comment, but naive statement splitters in drivers and proxies split on them; sqlcommenter values
     * URL-encode them instead). The content is padded with spaces, which keeps MySQL and MariaDB from
     * interpreting leading {@code !} or {@code +} as an executable comment or optimizer hint.
     */
    private static String applySqlCommenter(@Nullable SqlCommenter sqlCommenter, String statement) {
        if (sqlCommenter == null) {
            return statement;
        }
        var content = sqlCommenter.comment().orElse(null);
        if (content == null || content.isBlank()) {
            return statement;
        }
        if (content.contains("*/")) {
            throw new PersistenceException(
                    "SQL comment content must not contain the comment terminator '*/': %s".formatted(content));
        }
        if (content.indexOf(';') >= 0) {
            throw new PersistenceException(
                    "SQL comment content must not contain semicolons; URL-encode values instead: %s"
                            .formatted(content));
        }
        return statement + " /* " + content + " */";
    }

    private final TemplateProcessor templateProcessor;
    private final @Nullable DataSource dataSource;
    private final ModelBuilder modelBuilder;
    private final TableAliasResolver tableAliasResolver;
    private final Predicate<Provider> providerFilter;
    private final RefFactory refFactory;
    private final IntegrationStrategies strategies;
    private final SqlTemplate sqlTemplate;
    private final StormConfig config;
    private final SqlDialect dialect;

    public PreparedStatementTemplateImpl(DataSource dataSource) {
        this(dataSource, StormConfig.defaults());
    }

    public PreparedStatementTemplateImpl(DataSource dataSource, StormConfig config) {
        this(dataSource, config, null, null, null, null);
    }

    /**
     * Creates a data source backed template with instance-scoped integration strategies.
     *
     * <p>Strategies that are {@code null} fall back to {@code ServiceLoader} discovery for the connection and
     * transaction template providers, and to the built-in defaults for the exception mapper and query observer.</p>
     *
     * @since 1.13
     */
    public PreparedStatementTemplateImpl(DataSource dataSource,
                                         StormConfig config,
                                         @Nullable ConnectionProvider connectionProvider,
                                         @Nullable TransactionTemplateProvider transactionTemplateProvider,
                                         @Nullable ExceptionMapper exceptionMapper,
                                         @Nullable QueryObserver queryObserver) {
        this(dataSource, config, connectionProvider, transactionTemplateProvider, exceptionMapper, queryObserver,
                null);
    }

    /**
     * Creates a data source backed template with instance-scoped integration strategies and an optional
     * SQL commenter.
     *
     * @since 1.13
     */
    public PreparedStatementTemplateImpl(DataSource dataSource,
                                         StormConfig config,
                                         @Nullable ConnectionProvider connectionProvider,
                                         @Nullable TransactionTemplateProvider transactionTemplateProvider,
                                         @Nullable ExceptionMapper exceptionMapper,
                                         @Nullable QueryObserver queryObserver,
                                         @Nullable SqlCommenter sqlCommenter) {
        this(new IntegrationStrategies(
                        requireNonNullElseGet(connectionProvider, Providers::getConnectionProvider),
                        requireNonNullElseGet(transactionTemplateProvider, Providers::getTransactionTemplateProvider),
                        requireNonNullElseGet(exceptionMapper, ExceptionMapper::defaultMapper),
                        requireNonNullElseGet(queryObserver, QueryObserver::noop),
                        sqlCommenter),
                dataSource, config);
    }

    private PreparedStatementTemplateImpl(IntegrationStrategies strategies,
                                          DataSource dataSource,
                                          StormConfig config) {
        this(strategies, dataSource, config,
                Providers.getSqlDialectProvider(Providers.getDatabaseProductName(dataSource)));
    }

    private PreparedStatementTemplateImpl(IntegrationStrategies strategies,
                                          DataSource dataSource,
                                          StormConfig config,
                                          @Nullable SqlDialectProvider matchedProvider) {
        this(strategies, dataSource, config, matchedProvider,
                matchedProvider != null ? matchedProvider.getSqlDialect(config) : getSqlDialect(config));
    }

    private PreparedStatementTemplateImpl(IntegrationStrategies strategies,
                                          DataSource dataSource,
                                          StormConfig config,
                                          @Nullable SqlDialectProvider matchedProvider,
                                          SqlDialect dialect) {
        this(createDataSourceProcessor(dataSource, strategies, dialect),
                dataSource,
                ModelBuilder.newInstance(), TableAliasResolver.DEFAULT,
                matchedProvider != null ? matchedProvider.getProviderFilter() : null,
                strategies, config, dialect);
    }

    public PreparedStatementTemplateImpl(Connection connection) {
        this(connection, StormConfig.defaults());
    }

    public PreparedStatementTemplateImpl(Connection connection, StormConfig config) {
        this(connection, config, null, null, null);
    }

    /**
     * Creates a connection backed template with instance-scoped integration strategies.
     *
     * <p>Connection backed templates never acquire connections themselves, so no connection provider applies.
     * Strategies that are {@code null} fall back to {@code ServiceLoader} discovery for the transaction template
     * provider, and to the built-in defaults for the exception mapper and query observer.</p>
     *
     * @since 1.13
     */
    public PreparedStatementTemplateImpl(Connection connection,
                                         StormConfig config,
                                         @Nullable TransactionTemplateProvider transactionTemplateProvider,
                                         @Nullable ExceptionMapper exceptionMapper,
                                         @Nullable QueryObserver queryObserver) {
        this(connection, config, transactionTemplateProvider, exceptionMapper, queryObserver, null);
    }

    /**
     * Creates a connection backed template with instance-scoped integration strategies and an optional
     * SQL commenter.
     *
     * @since 1.13
     */
    public PreparedStatementTemplateImpl(Connection connection,
                                         StormConfig config,
                                         @Nullable TransactionTemplateProvider transactionTemplateProvider,
                                         @Nullable ExceptionMapper exceptionMapper,
                                         @Nullable QueryObserver queryObserver,
                                         @Nullable SqlCommenter sqlCommenter) {
        this(new IntegrationStrategies(
                        null,
                        requireNonNullElseGet(transactionTemplateProvider, Providers::getTransactionTemplateProvider),
                        requireNonNullElseGet(exceptionMapper, ExceptionMapper::defaultMapper),
                        requireNonNullElseGet(queryObserver, QueryObserver::noop),
                        sqlCommenter),
                connection, config);
    }

    private PreparedStatementTemplateImpl(IntegrationStrategies strategies,
                                          Connection connection,
                                          StormConfig config) {
        this(strategies, connection, config,
                Providers.getSqlDialectProvider(Providers.getDatabaseProductName(connection)));
    }

    private PreparedStatementTemplateImpl(IntegrationStrategies strategies,
                                          Connection connection,
                                          StormConfig config,
                                          @Nullable SqlDialectProvider matchedProvider) {
        this(strategies, connection, config, matchedProvider,
                matchedProvider != null ? matchedProvider.getSqlDialect(config) : getSqlDialect(config));
    }

    private PreparedStatementTemplateImpl(IntegrationStrategies strategies,
                                          Connection connection,
                                          StormConfig config,
                                          @Nullable SqlDialectProvider matchedProvider,
                                          SqlDialect dialect) {
        this(createConnectionProcessor(connection, strategies, dialect),
                null,
                ModelBuilder.newInstance(), TableAliasResolver.DEFAULT,
                matchedProvider != null ? matchedProvider.getProviderFilter() : null,
                strategies, config, dialect);
    }

    private PreparedStatementTemplateImpl(TemplateProcessor templateProcessor,
                                          @Nullable DataSource dataSource,
                                          ModelBuilder modelBuilder,
                                          TableAliasResolver tableAliasResolver,
                                          @Nullable Predicate<Provider> providerFilter,
                                          IntegrationStrategies strategies,
                                          StormConfig config,
                                          SqlDialect dialect) {
        validate(config);
        this.dialect = dialect;
        this.templateProcessor = templateProcessor;
        this.dataSource = dataSource;
        this.modelBuilder = modelBuilder;
        this.tableAliasResolver = tableAliasResolver;
        this.providerFilter = providerFilter;
        this.refFactory = new RefFactoryImpl(this, modelBuilder, providerFilter);
        this.strategies = strategies;
        this.config = config;
        this.sqlTemplate = createSqlTemplate();
    }

    /**
     * Refuses a statement Storm recognises as a write, an {@code INSERT}, {@code UPDATE} or {@code DELETE}, when
     * the transaction owning the connection is read-only. The refusal happens before a connection is acquired, so
     * it is the same on every driver; a statement of undefined operation, such as a stored procedure call or a
     * {@code MERGE}, is left to the database. The context is the transaction the statement runs in, Storm's own
     * or one it joined, so a template outside any transaction is never refused.
     */
    private static void refuseWriteInReadOnlyTransaction(Sql sql, @Nullable TransactionContext transactionContext) {
        if (transactionContext == null || !transactionContext.isReadOnly()) {
            return;
        }
        switch (sql.operation()) {
            case INSERT, UPDATE, DELETE -> throw new ReadOnlyTransactionException(
                    "Cannot execute %s in a read-only transaction; a write needs a transaction of its own, opened with REQUIRES_NEW, or a read-write enclosing transaction."
                            .formatted(sql.operation()));
            default -> {}
        }
    }

    private static TemplateProcessor createDataSourceProcessor(DataSource dataSource,
                                                                IntegrationStrategies strategies,
                                                                SqlDialect dialect) {
        var connectionProvider = strategies.connectionProvider();
        assert connectionProvider != null;
        var transactionTemplateProvider = strategies.transactionTemplateProvider();
        return (sql, unsafe) -> {
            if (!unsafe) {
                sql.unsafeWarning().ifPresent(warning -> {
                    throw new PersistenceException("%s Use Query.unsafe() to allow this operation.".formatted(warning));
                });
            }
            var statement = applySqlCommenter(strategies.sqlCommenter(), sql.statement());
            var parameters = sql.parameters();
            var bindVariables = sql.bindVariables().orElse(null);
            var generatedKeys = sql.generatedKeys();
            var transactionContext = TransactionScope.resolveContext(transactionTemplateProvider,
                    strategies.queryObserver());
            refuseWriteInReadOnlyTransaction(sql, transactionContext);
            Connection connection = connectionProvider.getConnection(dataSource, transactionContext);
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
                    bindVariables.setBatchListener(getBatchListener(preparedStatement, parameters, dialect,
                            getExceptionTransformer(sql, strategies.exceptionMapper(), transactionTemplateProvider)));
                }
                success = true;
            } finally {
                if (!success) {
                    if (preparedStatement != null) {
                        try {
                            preparedStatement.close();
                        } catch (SQLException ignore) {}
                    }
                    connectionProvider.releaseConnection(connection, dataSource, transactionContext);
                }
            }
            return createProxy(preparedStatement, connection, dataSource, transactionContext, connectionProvider);
        };
    }

    private static TemplateProcessor createConnectionProcessor(Connection connection,
                                                                IntegrationStrategies strategies,
                                                                SqlDialect dialect) {
        var transactionTemplateProvider = strategies.transactionTemplateProvider();
        return (sql, unsafe) -> {
            if (!unsafe) {
                sql.unsafeWarning().ifPresent(warning -> {
                    throw new PersistenceException("%s Use Query.unsafe() to allow this operation.".formatted(warning));
                });
            }
            var statement = applySqlCommenter(strategies.sqlCommenter(), sql.statement());
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
                // Connection backed templates never materialize a transaction scope; the caller manages the
                // connection. The context is only observed for statement decoration, such as timeouts.
                var transactionContext = TransactionScope.peekContext(transactionTemplateProvider);
                refuseWriteInReadOnlyTransaction(sql, transactionContext);
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
                    bindVariables.setBatchListener(getBatchListener(preparedStatement, parameters, dialect,
                            getExceptionTransformer(sql, strategies.exceptionMapper(), transactionTemplateProvider)));
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
        // The ambient template resolves a dialect without knowing which database this template is bound to, so the
        // dialect resolved for this template's database is applied on top. An explicit provider filter still wins.
        return template.withDialect(providerFilter != null ? getSqlDialect(providerFilter, config) : dialect);
    }

    /**
     * Returns a new prepared statement template with the specified table name resolver.
     *
     * @param tableNameResolver the table name resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withTableNameResolver(@Nullable TableNameResolver tableNameResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder.tableNameResolver(tableNameResolver), tableAliasResolver, providerFilter, strategies, config, dialect);
    }

    /**
     * Returns a new prepared statement template with the specified column name resolver.
     *
     * @param columnNameResolver the column name resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withColumnNameResolver(@Nullable ColumnNameResolver columnNameResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder.columnNameResolver(columnNameResolver), tableAliasResolver, providerFilter, strategies, config, dialect);
    }

    /**
     * Returns a new prepared statement template with the specified foreign key resolver.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withForeignKeyResolver(@Nullable ForeignKeyResolver foreignKeyResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder.foreignKeyResolver(foreignKeyResolver), tableAliasResolver, providerFilter, strategies, config, dialect);
    }

    /**
     * Returns a new prepared statement template with the specified table alias resolver.
     *
     * @param tableAliasResolver the table alias resolver.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplate withTableAliasResolver(TableAliasResolver tableAliasResolver) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder, tableAliasResolver, providerFilter, strategies, config, dialect);
    }

    /**
     * Returns a new prepared statement template with the specified provider filter.
     *
     * @param providerFilter the provider filter.
     * @return a new prepared statement template.
     */
    @Override
    public PreparedStatementTemplateImpl withProviderFilter(@Nullable Predicate<Provider> providerFilter) {
        return new PreparedStatementTemplateImpl(templateProcessor, dataSource, modelBuilder, tableAliasResolver, providerFilter, strategies, config, dialect);
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

    private static BatchListener getBatchListener(PreparedStatement preparedStatement,
                                                   List<Parameter> parameters,
                                                   SqlDialect dialect,
                                                   Function<Throwable, RuntimeException> exceptionTransformer) {
        var calendarSupplier = lazy(() -> Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)));
        return batchParameters -> {
            try {
                setParameters(preparedStatement, parameters, calendarSupplier, dialect);
                setParameters(preparedStatement, batchParameters, calendarSupplier, dialect);
                preparedStatement.addBatch();
            } catch (SQLException e) {
                throw exceptionTransformer.apply(e);
            }
        };
    }

    private static void setParameters(PreparedStatement preparedStatement,
                                      List<? extends Parameter> parameters,
                                      SqlDialect dialect) throws SQLException {
        var calendarSupplier = lazy(() -> Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC)));
        setParameters(preparedStatement, parameters, calendarSupplier, dialect);
    }

    private static void setParameters(PreparedStatement preparedStatement,
                                      List<? extends Parameter> parameters,
                                      Supplier<Calendar> calendarSupplier,
                                      SqlDialect dialect) throws SQLException {
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
                        case JsonString js     -> dialect.setParameter(preparedStatement, idx, js);
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

    @Override
    public @Nullable DataSource dataSource() {
        return dataSource;
    }

    /**
     * Returns the transaction template provider used by this template.
     *
     * @return the transaction template provider.
     * @since 1.13
     */
    @Override
    public TransactionTemplateProvider transactionTemplateProvider() {
        return strategies.transactionTemplateProvider();
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
    private static PreparedStatement createProxy(PreparedStatement statement, Connection connection,
                                                 DataSource dataSource, @Nullable TransactionContext context,
                                                 ConnectionProvider connectionProvider) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (ignore, method, args) -> {
                    // Check if the close method is being called on the PreparedStatement.
                    if (method.getName().equals("close")) {
                        try {
                            statement.close();
                        } finally {
                            connectionProvider.releaseConnection(connection, dataSource, context);
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
    public Query create(TemplateString template) {
        try {
            var customizedTemplate = sqlTemplate();
            var sql = customizedTemplate.process(template);
            // The dialect of the template that generated the SQL; a provider lookup could select a different
            // dialect than the one the statement was generated with.
            return createQuery(sql, customizedTemplate.dialect());
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    /**
     * Compiles the specified query {@code template} into a reusable plan.
     *
     * <p>The template is processed once; the resulting statement, the value-independent parameter extractors
     * registered for its bind variables, and the statement metadata are snapshotted into an immutable plan. The
     * single-use bind variables instance embedded in the processed SQL is never wired to a statement, so the plan
     * can bind any number of records on any connection. Templates without any parameters compile to constant plans;
     * templates with fixed parameter values are rejected.</p>
     *
     * @param template the template to compile.
     * @return a reusable plan for the template.
     * @throws PersistenceException if the template is invalid or carries fixed parameter values.
     */
    @Override
    public QueryPlan plan(TemplateString template) {
        try {
            var customizedTemplate = sqlTemplate();
            // The stored statement must not bake in Sql interceptor rewrites: binding applies the interceptor
            // chain, so every execution is intercepted exactly once, and interceptors scoped around plan
            // compilation do not leak into the cached plan.
            var sql = customizedTemplate instanceof SqlTemplateImpl sqlTemplateImpl
                    ? sqlTemplateImpl.process(template, false)
                    : customizedTemplate.process(template);
            SqlDialect dialect = customizedTemplate.dialect();
            // Fixed parameter values are rejected rather than frozen, for constant and bind-vars plans alike:
            // silently pinning a value that reads like a variable hides a likely mistake, and bind variables
            // express the variable parts explicitly.
            if (!sql.parameters().isEmpty()) {
                throw new PersistenceException("Cannot compile a plan for a template with fixed parameter values. Pass createBindVars() for the variable parts, or execute the template directly via query().");
            }
            var bindVariables = sql.bindVariables().orElse(null);
            if (bindVariables == null) {
                return new QueryPlanImpl(sql, List.of(), List.of(), bound -> createQuery(bound, dialect));
            }
            if (!(bindVariables instanceof BindVarsImpl bindVars)) {
                throw new PersistenceException("Cannot compile a plan: unsupported bind variables implementation %s.".formatted(bindVariables.getClass().getName()));
            }
            return new QueryPlanImpl(sql, bindVars.extractors(), bindVars.valueExtractors(), bound -> createQuery(bound, dialect));
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    private Query createQuery(Sql sql, SqlDialect dialect) {
        var bindVariables = sql.bindVariables().orElse(null);
        var environment = new QueryImpl.Environment(
                refFactory,
                strategies.transactionTemplateProvider(),
                strategies.queryObserver(),
                getExceptionTransformer(sql, strategies.exceptionMapper(), strategies.transactionTemplateProvider()),
                sql.operation(),
                sql.dataType().orElse(null),
                FetchPlan.of(sql.fetchPaths()),
                sql.statement(),
                sql.origin(),
                sql.shapeId(),
                sql.parameters(),
                strategies.sqlCommenter());
        return new QueryImpl(environment, unsafe -> {
            try {
                return templateProcessor.process(sql, unsafe);
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        }, bindVariables == null ? null : bindVariables.getHandle(), sql.affectedType().orElse(null), sql.versionAware(), false, false, dialect.defaultFetchSize(), dialect.streamOnlyFetchSize(), dialect.streamingRequiresTransaction());
    }

    /**
     * Creates a query for the specified query {@code template}.
     *
     * @param template the query template.
     * @return the query.
     */
    @Override
    public PreparedStatement query(TemplateString template) throws SQLException {
        var sql = sqlTemplate().process(template);
        return templateProcessor.process(sql, true);    // We allow unsafe queries in direct JDBC mode.
    }
}
