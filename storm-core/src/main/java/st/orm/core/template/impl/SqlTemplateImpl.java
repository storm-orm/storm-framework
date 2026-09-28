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
import static st.orm.StormConfig.TEMPLATE_CACHE_SIZE;
import static st.orm.core.spi.Providers.getSqlDialect;
import static st.orm.core.spi.StormConfigHelper.*;
import static st.orm.core.template.impl.ElementRouter.getElementProcessor;
import static st.orm.core.template.impl.SqlInterceptorManager.intercept;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.BindVars;
import st.orm.Element;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.TableAliasResolver;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.TemplatePreparation.BindingContext;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.SchemaResolver;
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

    record ElementNode(Element element, boolean synthetic) {}

    record Wrapped(List<ElementNode> elements) implements Element {
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

    /**
     * The dialect set via {@link #withDialect(SqlDialect)} or a dialect-taking constructor, or {@code null} when the
     * dialect is resolved from the classpath. An explicit dialect is preserved across {@link #withConfig(StormConfig)},
     * whereas a classpath-resolved dialect is re-resolved under the new configuration.
     */
    private final @Nullable SqlDialect explicitDialect;

    /**
     * The dialect in use, resolved on first use. Classpath resolution fails fast when multiple dialect providers are
     * eligible without a defined order, so templates that receive an explicit dialect before processing, such as the
     * shared {@link SqlTemplate#PS} and {@link SqlTemplate#JPA} instances customized by database-bound templates,
     * must never trigger it. All dialect-dependent state is therefore initialized lazily.
     */
    private final LazySupplier<SqlDialect> dialect;

    private final Supplier<TemplatePreparation> templatePreparation;
    private final Function<TemplateString, Object> keyGenerator;
    private final StormConfig config;

    /** The template cache, keyed by the resolved dialect and therefore lazy; {@code null} when caching is disabled. */
    private final @Nullable Supplier<SegmentedLruCache<Object, TemplateProcessor>> cache;

    private final TemplateMetrics templateMetrics;

    public SqlTemplateImpl(boolean positionalOnly, boolean expandCollection, boolean supportRecords) {
        this(positionalOnly, expandCollection, supportRecords, false, ModelBuilder.newInstance(), TableAliasResolver.DEFAULT, null, StormConfig.defaults());
    }

    public SqlTemplateImpl(boolean positionalOnly,
                           boolean expandCollection,
                           boolean supportRecords,
                           boolean inlineParameters,
                           ModelBuilder modelBuilder,
                           TableAliasResolver tableAliasResolver,
                           SqlDialect dialect) {
        this(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, requireNonNull(dialect), StormConfig.defaults());
    }

    SqlTemplateImpl(boolean positionalOnly,
                    boolean expandCollection,
                    boolean supportRecords,
                    boolean inlineParameters,
                    ModelBuilder modelBuilder,
                    TableAliasResolver tableAliasResolver,
                    @Nullable SqlDialect dialect,
                    StormConfig config) {
        this.positionalOnly = positionalOnly;
        this.expandCollection = expandCollection;
        this.supportRecords = supportRecords;
        this.inlineParameters = inlineParameters;
        this.modelBuilder = requireNonNull(modelBuilder);
        this.tableAliasResolver = requireNonNull(tableAliasResolver);
        this.explicitDialect = dialect;
        this.config = requireNonNull(config);
        this.dialect = dialect != null ? new LazySupplier<>(dialect) : new LazySupplier<>(() -> getSqlDialect(config));
        this.templatePreparation = new LazySupplier<>(() -> new TemplatePreparation(this, modelBuilder));
        this.keyGenerator = keyGenerator();
        int templateCacheSize = Math.max(0, getInt(config, TEMPLATE_CACHE_SIZE, 2048));
        if (templateCacheSize == 0 || inlineParameters) {
            // We don't want to cache templates with inline parameters. No caching takes place if inline parameters are enabled.
            this.cache = null;
        } else {
            this.cache = new LazySupplier<>(() -> {
                var key = List.of(positionalOnly, expandCollection, supportRecords, new IdentityKey(modelBuilder), new IdentityKey(tableAliasResolver), dialect().name(), configCacheKey(config));
                return CacheHolder.INSTANCE.getOrCompute(key, () -> new SegmentedLruCache<>(templateCacheSize));
            });
        }
        this.templateMetrics = TemplateMetrics.getInstance();
        this.templateMetrics.registerCacheSize(templateCacheSize);
        LOGGER.debug("Storm config: templateCacheSize={}", templateCacheSize);
    }

    private static Map<String, String> configCacheKey(StormConfig config) {
        var map = new HashMap<String, String>();
        for (String key : StormConfig.sqlShapingKeys()) {
            String value = config.getProperty(key);
            map.put(key, value != null ? value : "");
        }
        return Map.copyOf(map);
    }

    private Function<TemplateString, Object> keyGenerator() {
        return template -> {
            try {
                return getCompilationKey(templatePreparation.get().preprocess(template));
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        };
    }

    private Function<TemplateString, Object> shapeGenerator() {
        return template -> {
            try {
                return getShapeKey(templatePreparation.get().preprocess(template));
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
    public SqlTemplateImpl withTableNameResolver(TableNameResolver tableNameResolver) {
        if (tableNameResolver == modelBuilder.tableNameResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.tableNameResolver(tableNameResolver), tableAliasResolver, explicitDialect, config);
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
     * Returns a new SQL template with the specified schema resolver.
     *
     * @param schemaResolver the schema resolver.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withSchemaResolver(SchemaResolver schemaResolver) {
        if (schemaResolver == modelBuilder.schemaResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.schemaResolver(schemaResolver), tableAliasResolver, explicitDialect, config);
    }

    /**
     * Returns the schema resolver used by this template.
     *
     * @return the schema resolver used by this template.
     */
    @Override
    public SchemaResolver schemaResolver() {
        return modelBuilder.schemaResolver();
    }

    /**
     * Returns a new SQL template with the specified table alias resolver.
     *
     * @param tableAliasResolver the table alias resolver.
     * @return a new SQL template.
     */
    @Override
    public SqlTemplateImpl withTableAliasResolver(TableAliasResolver tableAliasResolver) {
        if (tableAliasResolver == this.tableAliasResolver) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, explicitDialect, config);
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
    public SqlTemplateImpl withColumnNameResolver(ColumnNameResolver columnNameResolver) {
        if (columnNameResolver == modelBuilder.columnNameResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.columnNameResolver(columnNameResolver), tableAliasResolver, explicitDialect, config);
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
    public SqlTemplateImpl withForeignKeyResolver(ForeignKeyResolver foreignKeyResolver) {
        if (foreignKeyResolver == modelBuilder.foreignKeyResolver()) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder.foreignKeyResolver(foreignKeyResolver), tableAliasResolver, explicitDialect, config);
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
    public SqlTemplate withDialect(SqlDialect dialect) {
        requireNonNull(dialect);
        if (dialect == this.explicitDialect || this.dialect.value().orElse(null) == dialect) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, dialect, config);
    }

    /**
     * Returns the SQL dialect used by this template, resolving it from the classpath on first use when no dialect
     * has been set explicitly.
     *
     * @return the SQL dialect used by this template.
     * @since 1.2
     */
    @Override
    public SqlDialect dialect() {
        return dialect.get();
    }

    @Override
    public SqlTemplate withConfig(StormConfig config) {
        if (config == this.config) {
            return this;
        }
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, explicitDialect, config);
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
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, explicitDialect, config);
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
        return new SqlTemplateImpl(positionalOnly, expandCollection, supportRecords, inlineParameters, modelBuilder, tableAliasResolver, explicitDialect, config);
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
    public Sql process(TemplateString template) throws SqlTemplateException {
        return process(template, true);
    }

    /**
     * Processes the specified {@code template}, optionally without applying SQL interceptors.
     *
     * <p>Query plans process their template without interceptors: the stored statement must not bake in interceptor
     * rewrites, and binding applies the interceptor chain instead, so every execution is intercepted exactly once
     * and interceptors scoped around plan compilation do not leak into the cached plan.</p>
     *
     * @param template the string template to process.
     * @param applyInterceptors whether to run the registered SQL interceptors on the result.
     * @return the resulting SQL and parameters.
     * @throws SqlTemplateException if an error occurs while processing the input.
     */
    Sql process(TemplateString template, boolean applyInterceptors) throws SqlTemplateException {
        BindingContext bindingContext;
        Object compilationKey;
        TemplateProcessor processor;
        try {
            try (var request = templateMetrics.startRequest()) {
                var templatePreparation = this.templatePreparation.get();
                var cache = this.cache == null ? null : this.cache.get();
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
            // The shape identifies the statement's structure with collection arity erased, so statements whose
            // collection parameters expand to different placeholder counts share it. SQL log scopes group by it
            // and query observers tag with it, so it is derived unconditionally, and cached on the processor:
            // every binding of one compiled template shares its shape.
            long shapeId = processor.shapeId(() -> {
                try {
                    return ShapeHash.of(getShapeKey(bindingContext));
                } catch (UncheckedSqlTemplateException e) {
                    // A template whose shape cannot be derived groups by text instead.
                    return 0L;
                }
            });
            Sql sql = processor.bind(bindingContext, shapeId);
            if (applyInterceptors) {
                // Interception logs the statement, so a plan compiled here logs on execution rather than now.
                sql = intercept(sql);
            }
            return sql;
        } catch (UncheckedSqlTemplateException e) {
            throw e.getCause();
        }
    }

    /**
     * Returns the shape key of the template: its compilation key with collection arity erased, so statements
     * that differ only in how far a collection expanded share it.
     */
    private Object getShapeKey(BindingContext bindingContext) {
        var shapeGenerator = shapeGenerator();
        return buildKey(bindingContext,
                element -> getElementProcessor(element).getShapeKey(element, shapeGenerator));
    }

    private Object getCompilationKey(BindingContext bindingContext) {
        return buildKey(bindingContext,
                element -> getElementProcessor(element).getCompilationKey(element, keyGenerator));
    }

    /**
     * The per-element contribution to a template key, or {@code null} when the element does not support keying.
     */
    @FunctionalInterface
    private interface KeyExtractor {
        @Nullable Object apply(Element element) throws SqlTemplateException;
    }

    /**
     * Interleaves the template's fragments with the per-element keys the extractor produces, or returns
     * {@code null} as soon as any element yields no key.
     */
    private @Nullable Object buildKey(BindingContext bindingContext, KeyExtractor extractor) {
        try {
            var fragments = bindingContext.fragments();
            var elements = bindingContext.elements();
            // Runs for every processed template; sized for the common case of one key per fragment and element.
            var templateKey = new ArrayList<>(fragments.size() + elements.size());
            for (int i = 0, size = fragments.size(); i < size; i++) {
                templateKey.add(fragments.get(i));
                if (i < elements.size()) {
                    var element = elements.get(i);
                    if (element instanceof Wrapped(var wrapped)) {
                        for (var e : wrapped) {
                            if (!e.synthetic()) {   // Ignore synthetic elements for the key.
                                var key = extractor.apply(e.element());
                                if (key != null) {
                                    templateKey.add(key);
                                } else {
                                    return null;
                                }
                            }
                        }
                    } else {
                        var key = extractor.apply(element);
                        if (key != null) {
                            templateKey.add(key);
                        } else {
                            return null;
                        }
                    }
                }
            }
            return templateKey;
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }
}
