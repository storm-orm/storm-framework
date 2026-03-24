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

import static java.util.Objects.requireNonNull;
import static st.orm.core.spi.Providers.getSqlDialect;
import static st.orm.core.template.impl.ElementRouter.getElementProcessor;
import static st.orm.core.template.impl.SqlInterceptorManager.intercept;

import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.BindVars;
import st.orm.Element;
import st.orm.StormConfig;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.TableAliasResolver;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.TemplatePreparation.BindingContext;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.TableNameResolver;

/**
 * The sql template implementation that is responsible for generating SQL queries.
 */
public final class SqlTemplateImpl implements SqlTemplate {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.sql");

    /**
     * Holder class for the global template cache map. Uses the initialization-on-demand holder idiom to avoid a
     * circular class initialization issue: {@link SqlTemplate} static fields {@code PS} and {@code JPA} create
     * {@link SqlTemplateImpl} instances, which would require the cache to be initialized. Since the JVM may
     * initialize {@link SqlTemplate} before {@link SqlTemplateImpl}'s own static fields are set (as part of
     * superinterface initialization), a direct static field would be {@code null} at that point.
     */
    private static final class CacheHolder {
        static final SegmentedLruCache<Object, SegmentedLruCache<Object, TemplateProcessor>> INSTANCE =
                new SegmentedLruCache<>(64);
    }

    /**
     * Config keys that affect the shape of generated SQL and must therefore be part of the template cache key.
     */
    private static final Set<String> TEMPLATE_SHAPE_KEYS = Set.of(StormConfig.ANSI_ESCAPING);

    record ElementNode(@Nonnull Element element, boolean synthetic) {}

    record Wrapped(@Nonnull List<ElementNode> elements) implements Element {
        public Wrapped {
            elements = List.copyOf(elements);
        }
    }

    private final boolean positionalOnly;
    private final boolean expandCollection;
    private final boolean supportRecords;
    private final boolean inlineParameters;
    private final ModelBuilder modelBuilder;
    private final TableAliasResolver tableAliasResolver;
    private final SqlDialect dialect;
    private final TemplatePreparation templatePreparation;
    private final Function<TemplateString, Object> keyGenerator;
    private final StormConfig config;
    private final SegmentedLruCache<Object, TemplateProcessor> cache;
    private final TemplateMetrics templateMetrics;

    public SqlTemplateImpl(boolean positionalOnly, boolean expandCollection, boolean supportRecords) {
        this(positionalOnly, expandCollection, supportRecords, false, ModelBuilder.newInstance(), TableAliasResolver.DEFAULT, getSqlDialect());
    }

    public SqlTemplateImpl(boolean positionalOnly,
                           boolean expandCollection,
                           boolean supportRecords,
                           boolean inlineParameters,
                           @Nonnull ModelBuilder modelBuilder,
                           @Nonnull TableAliasResolver tableAliasResolver,
                           @Nonnull SqlDialect dialect) {
        this(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, dialect, StormConfig.defaults());
    }

    SqlTemplateImpl(boolean positionalOnly,
                    boolean expandCollection,
                    boolean supportRecords,
                    boolean inlineParameters,
                    @Nonnull ModelBuilder modelBuilder,
                    @Nonnull TableAliasResolver tableAliasResolver,
                    @Nonnull SqlDialect dialect,
                    @Nonnull StormConfig config) {
        this.positionalOnly = positionalOnly;
        this.expandCollection = expandCollection;
        this.supportRecords = supportRecords;
        this.inlineParameters = inlineParameters;
        this.modelBuilder = requireNonNull(modelBuilder);
        this.tableAliasResolver = requireNonNull(tableAliasResolver);
        this.dialect = requireNonNull(dialect);
        this.config = requireNonNull(config);
        this.templatePreparation = new TemplatePreparation(this, modelBuilder);
        this.keyGenerator = keyGenerator();
        int templateCacheSize = Math.max(0, Integer.parseInt(config.getProperty(StormConfig.TEMPLATE_CACHE_SIZE, "2048")));
        if (templateCacheSize == 0 || inlineParameters) {
            // We don't want to cache templates with inline parameters. No caching takes place if inline parameters are enabled.
            this.cache = null;
        } else {
            var key = List.of(positionalOnly, expandCollection, supportRecords, new IdentityKey(modelBuilder), new IdentityKey(tableAliasResolver), dialect.name(), configCacheKey(config));
            this.cache = CacheHolder.INSTANCE.getOrCompute(key, () -> new SegmentedLruCache<>(templateCacheSize));
        }
        this.templateMetrics = TemplateMetrics.getInstance();
        this.templateMetrics.registerCacheSize(templateCacheSize);
        LOGGER.debug("Storm config: templateCacheSize={}", templateCacheSize);
    }

    private static Map<String, String> configCacheKey(@Nonnull StormConfig config) {
        var map = new HashMap<String, String>();
        for (String key : TEMPLATE_SHAPE_KEYS) {
            String value = config.getProperty(key);
            map.put(key, value != null ? value : "");
        }
        return Map.copyOf(map);
    }

    private Function<TemplateString, Object> keyGenerator() {
        return template -> {
            try {
                return getCompilationKey(templatePreparation.preprocess(template));
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        };
    }

    /**
     * Returns {@code true} if the template only support positional parameters, {@code false} otherwise.
     *
     * @return {@code true} if the template only support positional parameters, {@code false} otherwise.
     */
    @Override
    public boolean positionalOnly() {
        return positionalOnly;
    }

    /**
     * Returns {@code true} if collection parameters must be expanded as multiple (positional) parameters,
     * {@code false} otherwise.
     *
     * @return {@code true} if the template expands collection parameters, {@code false} otherwise.
     */
    @Override
    public boolean expandCollection() {
        return expandCollection;
    }

    /**
     * Returns a new SQL template with the specified table name resolver.
     *
     * @param tableNameResolver the table name resolver.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withTableNameResolver(@Nonnull TableNameResolver tableNameResolver) {
        if (tableNameResolver == modelBuilder.tableNameResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.tableNameResolver(tableNameResolver), tableAliasResolver, dialect, config);
    }

    /**
     * Returns the table name resolver used by this template.
     *
     * @return the table name resolver used by this template.
     */
    @Override
    public TableNameResolver tableNameResolver() {
        return modelBuilder.tableNameResolver();
    }

    /**
     * Returns a new SQL template with the specified table alias resolver.
     *
     * @param tableAliasResolver the table alias resolver.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withTableAliasResolver(@Nonnull TableAliasResolver tableAliasResolver) {
        if (tableAliasResolver == this.tableAliasResolver) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, dialect, config);
    }

    /**
     * Returns the table alias resolver used by this template.
     *
     * @return the table alias resolver used by this template.
     */
    @Override
    public TableAliasResolver tableAliasResolver() {
        return tableAliasResolver;
    }

    /**
     * Returns a new SQL template with the specified column name resolver.
     *
     * @param columnNameResolver the column name resolver.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withColumnNameResolver(@Nonnull ColumnNameResolver columnNameResolver) {
        if (columnNameResolver == modelBuilder.columnNameResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.columnNameResolver(columnNameResolver), tableAliasResolver, dialect, config);
    }

    /**
     * Returns the column name resolver used by this template.
     *
     * @return the column name resolver used by this template.
     */
    @Override
    public ColumnNameResolver columnNameResolver() {
        return modelBuilder.columnNameResolver();
    }

    /**
     * Returns a new SQL template with the specified foreign key resolver.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withForeignKeyResolver(@Nonnull ForeignKeyResolver foreignKeyResolver) {
        if (foreignKeyResolver == modelBuilder.foreignKeyResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.foreignKeyResolver(foreignKeyResolver), tableAliasResolver, dialect, config);
    }

    /**
     * Returns the foreign key resolver used by this template.
     *
     * @return the foreign key resolver used by this template.
     */
    @Override
    public ForeignKeyResolver foreignKeyResolver() {
        return modelBuilder.foreignKeyResolver();
    }

    /**
     * Returns a new SQL template with the specified SQL dialect.
     *
     * @param dialect the SQL dialect to use.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplate withDialect(@Nonnull SqlDialect dialect) {
        if (dialect == this.dialect) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, dialect, config);
    }

    /**
     * Returns the SQL dialect used by this template.
     *
     * @return the SQL dialect used by this template.
     * @since 1.2
     */
    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public SqlTemplate withConfig(@Nonnull StormConfig config) {
        if (config == this.config) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, getSqlDialect(config), config);
    }

    /**
     * Returns a new SQL template with support for records enabled or disabled.
     *
     * @param supportRecords {@code true} if the template should support records, {@code false} otherwise.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withSupportRecords(boolean supportRecords) {
        if (supportRecords == this.supportRecords) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, dialect, config);
    }

    /**
     * Returns {@code true} if the template supports tables represented as records, {@code false} otherwise.
     *
     * @return {@code true} if the template supports records, {@code false} otherwise.
     */
    @Override
    public boolean supportRecords() {
        return supportRecords;
    }

    /**
     * Returns a new SQL template instance configured to inline parameters directly into the SQL string,
     * rather than using bind variables.
     *
     * @param inlineParameters if true, parameters will be inlined as literals into the SQL. If false, parameters are
     *                         passed via bind variables (default behavior).
     * @return a new SqlTemplate instance configured with the specified parameter handling.
     * @since 1.3
     */
    @Override
    public SqlTemplate withInlineParameters(boolean inlineParameters) {
        if (inlineParameters == this.inlineParameters) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, dialect, config);
    }

    /**
     * Indicates whether the SQL parameters should be inlined directly as literals into the SQL string,
     * or whether bind variables should be used.
     *
     * @return true if parameters are inlined as literals; false if using bind variables.
     * @since 1.3
     */
    @Override
    public boolean inlineParameters() {
        return inlineParameters;
    }

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     */
    @Override
    public BindVars createBindVars() {
        return new BindVarsImpl();
    }

    /**
     * Processes the specified {@code template} and returns the resulting SQL and parameters.
     *
     * @param template the string template to process.
     * @return the resulting SQL and parameters.
     * @throws SqlTemplateException if an error occurs while processing the input.
     */
    @Override
    public Sql process(@Nonnull TemplateString template) throws SqlTemplateException {
        BindingContext bindingContext;
        Object compilationKey;
        TemplateProcessor processor;
        try {
            try (var request = templateMetrics.startRequest()) {
                bindingContext = templatePreparation.preprocess(template);
                compilationKey = cache == null ? null : getCompilationKey(bindingContext);
                processor = compilationKey == null ? null : cache.get(compilationKey);
                if (processor == null) {
                    request.miss();
                    var preparedTemplate = templatePreparation.prepare(bindingContext);
                    preparedTemplate.processor().compile(preparedTemplate.context(), false);
                    processor = preparedTemplate.processor();
                    if (compilationKey != null) {
                        var existing = cache.putIfAbsent(compilationKey, processor);
                        if (existing != null) {
                            processor = existing;  // Use the processor that won the race.
                        }
                    }
                } else {
                    request.hit();
                }
            }
            Sql sql = intercept(processor.bind(bindingContext));
            if (LOGGER.isDebugEnabled()) {
                String log = "SQL:\n%s".formatted(sql.statement());
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace(log);
                } else {
                    LOGGER.debug(log);
                }
            }
            return sql;
        } catch (UncheckedSqlTemplateException e) {
            throw e.getCause();
        }
    }

    private Object getCompilationKey(@Nonnull BindingContext bindingContext) {
        try {
            var fragments = bindingContext.fragments();
            var elements = bindingContext.elements();
            var compilationKey = new ArrayList<>();
            for (int i = 0, size = fragments.size(); i < size; i++) {
                compilationKey.add(fragments.get(i));
                if (i < elements.size()) {
                    var element = elements.get(i);
                    if (element instanceof Wrapped(var wrapped)) {
                        for (var e : wrapped) {
                            if (!e.synthetic()) {   // Ignore synthetic elements for the compilation key.
                                var key = getElementProcessor(e.element()).getCompilationKey(e.element(), keyGenerator);
                                if (key != null) {
                                    compilationKey.add(key);
                                } else {
                                    return null;
                                }
                            }
                        }
                    } else {
                        var key = getElementProcessor(element).getCompilationKey(element, keyGenerator);
                        if (key != null) {
                            compilationKey.add(key);
                        } else {
                            return null;
                        }
                    }
                }
            }
            return compilationKey;
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }
}
