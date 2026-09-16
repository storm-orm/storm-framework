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

import static java.lang.String.join;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static st.orm.core.template.impl.ElementRouter.getElementProcessor;
import static st.orm.core.template.impl.RecordValidation.validateParameters;
import static st.orm.core.template.impl.RecordValidation.validatePlaceholders;
import static st.orm.core.template.impl.SqlParser.hasWhereClause;

import java.util.ArrayList;
import java.util.List;
import java.util.MissingFormatArgumentException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.ResolveScope;
import st.orm.SqlTemplateException;
import st.orm.core.template.Model;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.BindVariables;
import st.orm.core.template.SqlTemplate.NamedParameter;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.BindHint.NoBindHint;
import st.orm.core.template.impl.Elements.Fetch;
import st.orm.core.template.impl.SqlTemplateImpl.Wrapped;
import st.orm.core.template.impl.TemplatePreparation.BindingContext;
import st.orm.core.template.impl.TemplatePreparation.CompilationContext;
import st.orm.core.template.impl.TemplatePreparation.PreparedTemplate;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Compiles and binds a {@link SqlTemplate} in two phases.
 *
 * <h2>Compilation</h2>
 * <p>During compilation, each {@link Element} is compiled using its element processor and a bind hint is recorded in a
 * deterministic traversal order. The resulting SQL string is stored once and reused for later bindings.</p>
 *
 * <h2>Binding</h2>
 * <p>During binding, a new {@link BindingSession} is created per invocation. The session binds runtime values while
 * consuming the previously recorded bind hints in the same order they were produced during compilation.</p>
 *
 * <p>The processor instance is safe to cache and reuse across threads. All mutable binding state is kept inside
 * {@link BindingSession}. Compile-time state is produced once and treated as immutable after compilation completes.
 * The compile-time fields are neither volatile nor final, so their cross-thread visibility rests entirely on the
 * publication through {@link SegmentedLruCache}{@code .putIfAbsent}: its synchronized segments supply the
 * happens-before edge between the compiling thread and every later reader. Publishing a processor to other threads
 * through any channel without such an edge is unsafe.</p>
 *
 * @since 1.8
 */
class TemplateProcessor {

    /**
     * Placeholder used to reserve a parent hint slot before nested compilation appends its hints.
     *
     * <p>The placeholder is replaced with the compiled element's hint after compilation returns. Encountering this value
     * during binding indicates an incomplete compilation.</p>
     */
    private static final BindHint PLACEHOLDER_HINT = null;

    /**
     * The template being processed.
     */
    private final SqlTemplate template;

    /**
     * Preparation component used to preprocess and prepare nested templates for compilation and binding.
     */
    private final TemplatePreparation templatePreparation;

    /**
     * Operation represented by the compiled SQL (for example SELECT, UPDATE).
     */
    private final SqlOperation operation;

    /**
     * Builder for resolving ORM models used by element processors.
     */
    private final ModelBuilder modelBuilder;

    /**
     * Tracks table usage to support alias correlation and reference checks.
     */
    private final TableUse tableUse;

    /**
     * Resolves and allocates table aliases used during compilation.
     */
    private final AliasMapper aliasMapper;

    /**
     * Optional query model used for column and expression compilation when a primary table is present.
     */
    private final QueryModel queryModel;

    /**
     * Dialect helpers used for rendering dialect-specific fragments.
     */
    private final SqlDialectTemplate dialectTemplate;

    /**
     * Shared tape of bind hints, appended during compilation in traversal order.
     *
     * <p>This list is treated as immutable after compilation completes. Binding sessions consume hints using a cursor.</p>
     */
    private final List<BindHint> bindHints;

    /**
     * Compile-time only: arity of each encountered {@link BindVars} occurrence.
     *
     * <p>The order matches compilation traversal. Binding uses a cursor to assign positional indexes for bind vars.</p>
     */
    private final List<Integer> bindVarsCounts;

    /**
     * Compile-time only: expected number of positional parameters produced by compilation.
     *
     * <p>This value is validated against runtime bindings when assembling the final {@link Sql} object.</p>
     */
    private final AtomicInteger positionalParameterCount;

    /**
     * Compile-time only: index used to generate unique named parameter identifiers when named parameters are supported.
     */
    private final AtomicInteger nameIndex;

    /**
     * Compile-time only: set if the compiled template requires version-aware binding behavior.
     */
    @Nullable
    private Boolean versionAware;

    /**
     * Compile-time only: generated keys configured during compilation (if any).
     */
    @Nullable
    private List<String> generatedKeys;

    /**
     * Compile-time only: type affected by INSERT, UPDATE, or DELETE operation.
     */
    @Nullable
    private Class<? extends Data> affectedType;

    /**
     * Compile-time only: the primary entity or projection type of the statement, recorded for observability.
     */
    @Nullable
    private Class<? extends Data> dataType;

    /**
     * Compile-time only: the references the statement resolves as part of its select list, which the row mapper reads
     * back to match the shape of that list.
     */
    @Nullable
    private List<String> fetchPaths;

    /**
     * Compile-time only: the select list the query model generated, which a cursor element finds its columns in.
     */
    @Nullable
    private List<String> selectColumns;

    /**
     * Compile-time only: where the values the statement reads alongside its mapped result are found, reported
     * through {@link Sql#cursorColumns()}.
     */
    @Nullable
    private List<List<Integer>> cursorColumns;

    /**
     * The fetch plan of the compilation in progress, collected from the {@link Fetch} elements of the context before
     * its elements compile, so the select list can read it whatever position the element occupies. Nested
     * compilations save and restore the enclosing plan.
     */
    private FetchPlan activeFetchPlan = FetchPlan.NONE;

    /**
     * The compiled SQL string.
     *
     * <p>This is set exactly once by {@link #compile(CompilationContext, boolean)}.</p>
     */
    @Nullable
    private String sql;

    /**
     * The safety warning for the compiled SQL, computed once on first bind. A pure function of {@link #sql} and the
     * operation; the benign race on initialization publishes an immutable {@link Optional}.
     */
    @Nullable
    private Optional<String> unsafeWarning;

    /** Whether the compiled SQL was checked for a placeholder per positional parameter. Benign race. */
    private volatile boolean placeholdersValidated;

    /**
     * Compile-time only: whether the compiled template requires binding.
     */
    private boolean requiresBinding;

    /**
     * The statement's shape identity, computed on first demand and stable for the processor's lifetime: every
     * binding of one processor shares its shape. The sentinel marks it uncomputed; the benign race publishes a
     * deterministic value.
     */
    private volatile long shapeId = Long.MIN_VALUE;

    /**
     * Compiler implementation used during compilation of elements and nested templates.
     */
    private final TemplateCompiler compiler;

    /**
     * Creates a new processor for the given template and compilation context.
     *
     * <p>This constructor initializes fresh compile-time state and a dialect template. Nested processors created via
     * {@link #child(TemplatePreparation, SqlOperation, ModelBuilder, TableUse, AliasMapper, QueryModel)} share the same
     * compile-time state and hint tape.</p>
     *
     * @param template            the SQL template to process.
     * @param templatePreparation the preparation component for nested templates.
     * @param operation           the operation represented by the resulting SQL.
     * @param modelBuilder        model builder used by element processors.
     * @param tableUse            table usage tracker for alias correlation.
     * @param aliasMapper         alias mapper for metamodel-based alias resolution.
     * @param queryModel          optional query model for primary table operations.
     */
    TemplateProcessor(
            SqlTemplate template,
            TemplatePreparation templatePreparation,
            SqlOperation operation,
            ModelBuilder modelBuilder,
            TableUse tableUse,
            AliasMapper aliasMapper,
            @Nullable QueryModel queryModel
    ) {
        this(template, templatePreparation, operation, modelBuilder, tableUse, aliasMapper, queryModel,
                new SqlDialectTemplate(template.dialect()),
                new ArrayList<>(),
                new AtomicInteger(),
                new AtomicInteger());
    }

    /**
     * Internal constructor used for root and child processors.
     *
     * <p>Child processors share compile-time state with the root processor by receiving the same instances for the hint
     * tape and counters.</p>
     */
    private TemplateProcessor(
            SqlTemplate template,
            TemplatePreparation templatePreparation,
            SqlOperation operation,
            ModelBuilder modelBuilder,
            TableUse tableUse,
            AliasMapper aliasMapper,
            @Nullable QueryModel queryModel,
            SqlDialectTemplate dialectTemplate,
            List<BindHint> bindHints,
            AtomicInteger positionalParameterCount,
            AtomicInteger nameIndex
    ) {
        this.template = template;
        this.templatePreparation = templatePreparation;
        this.operation = operation;
        this.modelBuilder = modelBuilder;
        this.tableUse = tableUse;
        this.aliasMapper = aliasMapper;
        this.queryModel = queryModel;
        this.dialectTemplate = dialectTemplate;
        this.bindHints = bindHints;
        this.bindVarsCounts = new ArrayList<>();
        this.positionalParameterCount = positionalParameterCount;
        this.nameIndex = nameIndex;
        this.compiler = new TemplateCompilerImpl();
    }

    /**
     * Returns the alias mapper used by this processor.
     *
     * @return the alias mapper.
     */
    public AliasMapper aliasMapper() {
        return aliasMapper;
    }

    private void checkState(boolean frozen) {
        if (frozen) {
            if (sql == null) {
                throw new IllegalStateException("Template processor is not frozen.");
            }
        } else {
            if (sql != null) {
                throw new IllegalStateException("Template processor is already frozen.");
            }
        }
    }

    /**
     * Creates a child processor used for nested compilation.
     *
     * <p>The child shares compile-time mutable state with the root processor, including bind hints and counters. Binding
     * state is not shared, because binding always happens through a {@link BindingSession} created per invocation.</p>
     *
     * @param templatePreparation preparation component to use for nested templates.
     * @param operation           nested operation context.
     * @param modelBuilder        model builder for nested compilation.
     * @param tableUse            nested table usage tracker.
     * @param aliasMapper         nested alias mapper.
     * @param queryModel          nested query model.
     * @return a child processor sharing compile-time state with the root processor.
     */
    TemplateProcessor child(
            TemplatePreparation templatePreparation,
            SqlOperation operation,
            ModelBuilder modelBuilder,
            TableUse tableUse,
            AliasMapper aliasMapper,
            QueryModel queryModel
    ) {
        // Share compilation state across nested compilation.
        // Binding state is NOT shared; it is created per bind invocation in BindingSession.
        return new TemplateProcessor(
                template,
                templatePreparation,
                operation,
                modelBuilder,
                tableUse,
                aliasMapper,
                queryModel,
                dialectTemplate,
                bindHints,
                positionalParameterCount,
                nameIndex
        ) {
            @Override
            protected void mapPositionalParameter() {
                TemplateProcessor.this.mapPositionalParameter();
            }

            @Override
            protected void mapNamedParameter() {
                TemplateProcessor.this.mapNamedParameter();
            }

            @Override
            protected void mapBindVars(int count) {
                TemplateProcessor.this.mapBindVars(count);
            }

            @Override
            protected void markVersionAware() {
                TemplateProcessor.this.markVersionAware();
            }

            @Override
            protected void setGeneratedKeys(List<String> keys) {
                TemplateProcessor.this.setGeneratedKeys(keys);
            }
        };
    }

    /**
     * Records that a positional parameter was produced by compilation.
     */
    protected void mapPositionalParameter() {
        checkState(false);
        requiresBinding = true;
        positionalParameterCount.incrementAndGet();
    }

    /**
     * Records that a named parameter was produced by compilation.
     */
    protected void mapNamedParameter() {
        checkState(false);
        requiresBinding = true;
    }

    /**
     * Records the arity of a {@link BindVars} segment produced during compilation.
     *
     * @param count the number of values expected for the bind vars segment.
     */
    protected void mapBindVars(int count) {
        checkState(false);
        if (count == 0) {
            throw new IllegalArgumentException("Bind vars segment cannot be empty.");
        }
        requiresBinding = true;
        bindVarsCounts.add(count);
    }

    /**
     * Marks this compiled template as version-aware.
     *
     * <p>This flag is set once during compilation by element processors that require version checks.</p>
     *
     * @throws IllegalStateException if version awareness was already set.
     */
    protected void markVersionAware() {
        checkState(false);
        if (versionAware != null) {
            throw new IllegalStateException("Version aware already set.");
        }
        versionAware = true;
    }

    /**
     * Sets the list of generated keys configured during compilation.
     *
     * @param keys the generated key column names.
     * @throws IllegalStateException if generated keys were already set.
     */
    protected void setGeneratedKeys(List<String> keys) {
        checkState(false);
        if (generatedKeys != null) {
            throw new IllegalStateException("Generated keys already set.");
        }
        generatedKeys = List.copyOf(keys);
    }

    /**
     * Stores the compiled SQL string.
     *
     * @param sql the compiled SQL.
     * @throws IllegalStateException if SQL was already set.
     */
    private void freeze(String sql) {
        checkState(false);
        this.sql = sql;
    }

    /**
     * Compiles a single (already-unwrapped) element and records its bind hint.
     *
     * <p>Hint ordering is fixed by reserving a slot before compilation so that any nested compilation appends its hints
     * after the parent slot. After compilation returns, the reserved slot is replaced by the compiled element's hint.</p>
     *
     * @param element    the element to compile.
     * @param synthetic  whether the element is synthetic and should not produce a bind hint.
     * @return the compiled element wrapper.
     * @throws SqlTemplateException if compilation fails.
     */
    private CompiledElement compile(Element element, boolean synthetic) throws SqlTemplateException {
        checkState(false);
        int hintIndex = bindHints.size();
        if (!synthetic) {
            bindHints.add(PLACEHOLDER_HINT);
        }
        var compiledElement = getElementProcessor(element).compile(element, compiler());
        if (synthetic) {
            if (compiledElement.bindHint() != NoBindHint.INSTANCE) {
                throw new IllegalStateException("Synthetic element cannot have bind hint.");
            }
        } else {
            bindHints.set(hintIndex, compiledElement.bindHint());
        }
        return compiledElement;
    }

    /**
     * Compiles the template fragments and elements into a single SQL string.
     *
     * <p>This method walks fragments and elements in order, compiling each element and appending its SQL representation.
     * It also records bind hints and positional parameter expectations as part of compilation.</p>
     *
     * <p>If {@code subquery} is {@code true}, the resulting SQL may be indented to align with surrounding formatting.</p>
     *
     * @param context  compilation context holding fragments and elements.
     * @param subquery whether the compilation target is a nested subquery.
     * @return the compiled SQL string.
     * @throws SqlTemplateException if compilation fails or formatting placeholders are invalid.
     */
    String compile(CompilationContext context, boolean subquery) throws SqlTemplateException {
        FetchPlan previousFetchPlan = activeFetchPlan;
        activeFetchPlan = scanFetchPlan(context.elements());
        try {
            return compileElements(context, subquery);
        } finally {
            activeFetchPlan = previousFetchPlan;
        }
    }

    /**
     * Collects the fetch plan of the given elements: the union of every {@link Fetch} element's paths, including the
     * elements wrapped for fragment alignment. An element list without fetch elements yields the empty plan.
     */
    private static FetchPlan scanFetchPlan(List<Element> elements) {
        List<String> paths = null;
        for (Element element : elements) {
            if (element instanceof Wrapped(var wrapped)) {
                for (var node : wrapped) {
                    if (node.element() instanceof Fetch(var paths1)) {
                        paths = paths == null ? new ArrayList<>() : paths;
                        paths.addAll(paths1);
                    }
                }
            } else if (element instanceof Fetch(var paths1)) {
                paths = paths == null ? new ArrayList<>() : paths;
                paths.addAll(paths1);
            }
        }
        return paths == null ? FetchPlan.NONE : FetchPlan.of(paths);
    }

    private String compileElements(CompilationContext context, boolean subquery) throws SqlTemplateException {
        checkState(false);
        var fragments = context.fragments();
        var elements = context.elements();
        var parts = new ArrayList<String>();
        var rawSql = new StringBuilder();
        var compilers = new ArrayList<DelayedCompilation>();
        for (int i = 0, size = fragments.size(); i < size; i++) {
            String fragment = fragments.get(i);
            try {
                if (i < elements.size()) {
                    Element element = elements.get(i);  // Use wrapped elements here to maintain fragment-value order.
                    var compiledElements = new ArrayList<CompiledElement>();
                    if (element instanceof Wrapped(var wrapped)) {
                        for (var e : wrapped) {
                            compiledElements.add(compile(e.element(), e.synthetic()));
                        }
                    } else {
                        compiledElements.add(compile(element, false));
                    }
                    compilers.add(() -> {
                        rawSql.append(fragment);
                        if (element instanceof Elements.Param) {
                            assert compiledElements.size() == 1;
                            parts.add(rawSql.toString());
                            rawSql.setLength(0);
                        }
                        for (CompiledElement compiledElement : compiledElements) {
                            rawSql.append(compiledElement.get());
                        }
                    });
                } else {
                    compilers.add(() -> {
                        rawSql.append(fragment);
                        parts.add(rawSql.toString());
                    });
                }
            } catch (MissingFormatArgumentException ex) {
                throw new SqlTemplateException(
                        "Invalid number of argument placeholders found. Template appears to specify custom %s " +
                                "placeholders.",
                        ex
                );
            }
        }
        for (var compiler : compilers) {
            compiler.compile();
        }
        String sql = String.join("", parts);
        if (subquery && !sql.startsWith("\n") && sql.contains("\n")) {
            sql = "\n" + sql.indent(2);
        }
        freeze(sql);
        return sql;
    }

    /**
     * Delays compilation side effects until the full fragment and element traversal order is established.
     *
     * <p>This allows the compilation loop to preserve correct fragment boundaries for parameter segmentation.</p>
     */
    private interface DelayedCompilation {
        /**
         * Executes the delayed compilation step.
         *
         * @throws SqlTemplateException if compilation fails.
         */
        void compile() throws SqlTemplateException;
    }

    /**
     * Returns the cached shape identity, or the given fallback computed by the caller on first demand.
     */
    long shapeId(LongSupplier compute) {
        long cached = shapeId;
        if (cached == Long.MIN_VALUE) {
            cached = compute.getAsLong();
            shapeId = cached;
        }
        return cached;
    }

    /**
     * Binds runtime values and assembles the final {@link Sql} object for a compiled template.
     *
     * <p>This method is reusable and thread-safe, because it creates a new {@link BindingSession} for every invocation.
     * The session binds values while consuming bind hints recorded during compilation.</p>
     *
     * @param context binding context holding flattened elements.
     * @param shapeId the identity of the statement's shape, derived from the compilation key; {@code 0} when
     *                unknown.
     * @return the assembled SQL object containing the SQL string and bound parameters.
     * @throws SqlTemplateException if binding fails or parameter validation fails.
     * @throws IllegalStateException if called before {@link #compile(CompilationContext, boolean)}.
     */
    Sql bind(BindingContext context, long shapeId) throws SqlTemplateException {
        checkState(true);
        assert sql != null;
        var session = new BindingSession();
        if (requiresBinding) {
            session.bindElements(context);
            session.assertAllHintsConsumed();
        }
        validateParameters(session.parameters, positionalParameterCount.getPlain());
        // A pure function of the compiled SQL and its parameter count, so it is checked once per processor.
        if (!placeholdersValidated) {
            validatePlaceholders(sql, positionalParameterCount.getPlain(), template.dialect());
            placeholdersValidated = true;
        }
        // The safety check is a pure function of the compiled SQL and operation and is computed once per
        // processor. The benign race publishes an immutable Optional.
        var warning = unsafeWarning;
        //noinspection OptionalAssignedToNull
        if (warning == null) {
            warning = checkSafety(sql, operation);
            unsafeWarning = warning;
        }
        return new SqlImpl(
                operation,
                sql,
                session.parameters,
                ofNullable(session.bindVariables),
                generatedKeys != null ? generatedKeys : List.of(),
                ofNullable(affectedType),
                ofNullable(dataType != null ? dataType : affectedType),
                fetchPaths != null ? fetchPaths : List.of(),
                cursorColumns != null ? cursorColumns : List.of(),
                versionAware != null && versionAware,
                warning,
                StatementOrigin.DIRECT,
                shapeId
        );
    }

    /**
     * Performs a basic safety check for potentially unsafe statements.
     *
     * <p>UPDATE and DELETE statements without a WHERE clause are flagged as potentially unsafe. The returned message is
     * informational and can be used by callers to warn users or reject execution.</p>
     *
     * @param sql       the compiled SQL to analyze.
     * @param operation the operation represented by the SQL.
     * @return an optional warning message when the statement is potentially unsafe.
     */
    private Optional<String> checkSafety(String sql, SqlOperation operation) {
        return switch (operation) {
            case SELECT, INSERT, UNDEFINED -> empty();
            case UPDATE, DELETE -> {
                if (!hasWhereClause(sql, template.dialect())) {
                    yield Optional.of("%s without a WHERE clause is potentially unsafe.".formatted(operation));
                }
                yield empty();
            }
        };
    }

    /**
     * Returns the compiler used for element compilation.
     *
     * @return the template compiler.
     * @throws IllegalStateException if SQL has already been finalized.
     */
    private TemplateCompiler compiler() {
        if (sql != null) {
            throw new IllegalStateException("SQL already set.");
        }
        return compiler;
    }

    /**
     * Template compiler used during compilation of elements and nested templates.
     *
     * <p>This compiler delegates to the surrounding {@link TemplateProcessor} for shared compile-time state such as bind
     * hint recording and positional parameter counting.</p>
     */
    private final class TemplateCompilerImpl implements TemplateCompiler {

        /**
         * Returns the SQL template.
         *
         * @return the SQL template.
         */
        @Override
        public SqlTemplate template() {
            return template;
        }

        /**
         * Returns the dialect used for SQL rendering.
         *
         * @return the SQL dialect.
         */
        @Override
        public SqlDialect dialect() {
            return template.dialect();
        }

        /**
         * Returns dialect-specific helpers used for template rendering.
         *
         * @return the dialect template helper.
         */
        @Override
        public SqlDialectTemplate dialectTemplate() {
            return dialectTemplate;
        }

        /**
         * Resolves a model for the given data type.
         *
         * @param type the data type.
         * @param <T>  the data type.
         * @param <ID> the identifier type.
         * @return the model for the given type.
         * @throws UncheckedSqlTemplateException if model resolution fails.
         */
        @Override
        public <T extends Data, ID> Model<T, ID> getModel(Class<T> type) {
            try {
                return modelBuilder.build(type, false);
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Returns the query model if one is available.
         *
         * @return the query model, if present.
         */
        @Override
        public Optional<QueryModel> findQueryModel() {
            return Optional.ofNullable(queryModel);
        }

        /**
         * Returns the query model, throwing if none is available.
         *
         * @return the query model.
         * @throws UncheckedSqlTemplateException if no primary table was specified.
         */
        @Override
        public QueryModel getQueryModel() {
            return findQueryModel().orElseThrow(
                    () -> new UncheckedSqlTemplateException(new SqlTemplateException("No primary table specified."))
            );
        }

        /**
         * Checks whether a given table alias is referenced in the current compilation context.
         *
         * @param table the table type.
         * @param alias the table alias.
         * @return {@code true} if the alias is referenced.
         */
        @Override
        public boolean isReferenced(Class<? extends Data> table, String alias) {
            return tableUse.isReferenced(table, alias);
        }

        /**
         * Resolves the SQL alias for the given metamodel and resolve scope.
         *
         * @param metamodel the metamodel to resolve an alias for.
         * @param scope     resolve scope controlling join visibility.
         * @return the resolved alias.
         * @throws UncheckedSqlTemplateException if alias resolution fails.
         */
        @Override
        public String getAlias(Metamodel<?, ?> metamodel, ResolveScope scope) {
            try {
                return aliasMapper.getAlias(metamodel, scope, template.dialect());
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Allocates or reuses an alias for the given table.
         *
         * @param table the table type.
         * @param alias preferred alias.
         * @return the actual alias used.
         * @throws UncheckedSqlTemplateException if alias allocation fails.
         */
        @Override
        public String useAlias(Class<? extends Data> table, String alias) {
            try {
                return aliasMapper.useAlias(table, alias);
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Compiles a nested template string and returns its SQL fragment.
         *
         * <p>This method performs preprocessing and preparation to ensure bind hints are recorded on the shared hint tape
         * and that nested compilation correlates correctly with the outer query when requested.</p>
         *
         * @param templateString the nested template to compile.
         * @param correlate      whether the nested template should correlate with the outer query.
         * @return the compiled SQL fragment.
         * @throws UncheckedSqlTemplateException if compilation fails.
         */
        @Override
        public String compile(TemplateString templateString, boolean correlate) {
            try {
                var preparedTemplate = templatePreparation.preprocess(templateString);
                var preparedProcessor = templatePreparation.prepare(preparedTemplate, TemplateProcessor.this, correlate);
                return preparedProcessor.processor().compile(preparedProcessor.context(), true);
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Compiles an element into its SQL representation.
         *
         * @param element the element to compile.
         * @return the compiled SQL fragment.
         * @throws UncheckedSqlTemplateException if compilation fails.
         */
        @Override
        public String compile(Element element) {
            try {
                return getElementProcessor(element).compile(element, this).get();
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Returns whether the current compilation is version-aware.
         *
         * @return {@code true} if version-aware.
         */
        @Override
        public boolean isVersionAware() {
            return versionAware != null && versionAware;
        }

        /**
         * Marks the compiled template as version-aware.
         */
        @Override
        public void setVersionAware() {
            markVersionAware();
        }

        /**
         * Configures generated keys for the compiled template.
         *
         * @param generatedKeys the generated key column names.
         */
        @Override
        public void setGeneratedKeys(List<String> generatedKeys) {
            TemplateProcessor.this.setGeneratedKeys(generatedKeys);
        }

        /**
         * Maps a runtime value to a SQL parameter placeholder.
         *
         * <p>If named parameters are supported, a fresh name is generated. If positional-only mode is active, a question
         * mark placeholder is produced and compile-time positional parameter count is incremented. When collection
         * expansion is enabled, arrays and iterables may expand into multiple placeholders or literals.</p>
         *
         * @param value the value to map.
         * @return the SQL parameter placeholder or an inlined literal.
         */
        @Override
        public String mapParameter(@Nullable Object value) {
            if (!template.positionalOnly()) {
                String name = "_p%d".formatted(nameIndex.getAndIncrement());
                return mapParameter(name, value);
            }
            return switch (value) {
                case Object[] array when template.expandCollection() -> mapArgs(List.of(array), template.inlineParameters());
                case Iterable<?> it when template.expandCollection() -> mapArgs(it, template.inlineParameters());
                case Object[] ignore -> throw new UncheckedSqlTemplateException(new SqlTemplateException("Array parameters are not supported in SQL templates. Use a List instead of an array to pass multiple values."));
                case Iterable<?> ignore ->
                        throw new UncheckedSqlTemplateException(new SqlTemplateException("Collection parameters are not supported at this position in the SQL template. Use individual parameters, or pass the collection to a query builder method such as whereId(ids) or where(path, IN, values), which renders a WHERE ... IN clause."));
                case null, default -> {
                    if (template.inlineParameters()) {
                        yield toLiteral(value);
                    }
                    TemplateProcessor.this.mapPositionalParameter();
                    yield "?";
                }
            };
        }

        /**
         * Maps a runtime value to a named SQL parameter placeholder.
         *
         * @param name  the parameter name.
         * @param value the runtime value.
         * @return the named SQL parameter placeholder.
         * @throws UncheckedSqlTemplateException if the template is configured for positional-only parameters.
         */
        @Override
        public String mapParameter(String name, @Nullable Object value) {
            if (template.positionalOnly()) {
                throw new UncheckedSqlTemplateException(new SqlTemplateException("Named parameters are not supported at this position in the SQL template. Use positional parameters instead."));
            }
            TemplateProcessor.this.mapNamedParameter();
            return ":%s".formatted(name);
        }

        /**
         * Records a {@link BindVars} segment length.
         *
         * @param bindVarsCount the number of positional placeholders to be produced by the bind vars segment.
         */
        @Override
        public void mapBindVars(int bindVarsCount) {
            TemplateProcessor.this.mapBindVars(bindVarsCount);
        }

        /**
         * Sets the type affected by an INSERT, UPDATE, or DELETE operation.
         *
         * @param type the type affected by the operation.
         */
        @Override
        public void setAffectedType(Class<? extends Data> type) {
            if (affectedType != null) {
                throw new IllegalStateException("Affected type already set.");
            }
            affectedType = type;
        }

        /**
         * Records the primary entity or projection type of the statement for observability purposes.
         *
         * <p>The first recorded type wins, so the outermost select takes precedence over the sources it queries.</p>
         *
         * @param type the entity or projection type the statement operates on.
         */
        @Override
        public void setDataType(Class<? extends Data> type) {
            if (dataType == null) {
                dataType = type;
            }
        }

        /**
         * Records the references the statement resolves as part of its select list.
         *
         * <p>The first recorded value wins, so the outermost select takes precedence over the sources it queries.</p>
         *
         * @param paths the field paths of the resolved references, relative to the selected type.
         */
        @Override
        public void setFetchPaths(List<String> paths) {
            if (fetchPaths == null) {
                fetchPaths = List.copyOf(paths);
            }
        }

        /**
         * Records the select list the query model generated. The first recorded list wins, so the outermost select
         * takes precedence over the sources it queries.
         *
         * @param columns the rendered select-list columns, in select order.
         */
        @Override
        public void setSelectColumns(List<String> columns) {
            if (selectColumns == null) {
                selectColumns = List.copyOf(columns);
            }
        }

        /**
         * Returns the select list the query model generated, empty when the caller wrote the select list.
         */
        @Override
        public Optional<List<String>> getSelectColumns() {
            return ofNullable(selectColumns);
        }

        /**
         * Records where the values the statement reads alongside its mapped result are found.
         *
         * @param positions the cursor column positions, one entry per field.
         */
        @Override
        public void setCursorColumns(List<List<Integer>> positions) {
            cursorColumns = List.copyOf(positions);
        }

        /**
         * Returns the fetch plan of the compilation in progress, collected from the template's {@link Fetch}
         * elements before its elements compile.
         */
        @Override
        public FetchPlan getFetchPlan() {
            return activeFetchPlan;
        }

        /**
         * Maps an iterable into an argument list suitable for IN clauses.
         *
         * @param iterable the iterable to expand.
         * @param inline   whether to inline each value as a literal.
         * @return a comma-separated list of placeholders or literals.
         */
        private String mapArgs(Iterable<?> iterable, boolean inline) {
            List<String> args = new ArrayList<>();
            for (var v : iterable) {
                if (inline) {
                    args.add(toLiteral(v));
                } else {
                    TemplateProcessor.this.mapPositionalParameter();
                    args.add("?");
                }
                args.add(", ");
            }
            if (!args.isEmpty()) {
                args.removeLast();
            }
            return join("", args);
        }

        /**
         * Converts a supported value into a SQL literal suitable for inline parameter rendering.
         *
         * <p>This method performs basic string escaping for backslashes and single quotes.</p>
         *
         * @param dbValue the value to convert.
         * @return the SQL literal.
         */
        private static String toLiteral(@Nullable Object dbValue) {
            return SqlLiterals.toLiteral(dbValue);
        }
    }

    /**
     * A single binding session created per {@link #bind(BindingContext, long)} invocation.
     *
     * <p>The session owns all mutable binding state, including bound parameters and cursor positions for bind hint and
     * bind vars consumption. Nested attached binding uses the same session and advances the same cursors.</p>
     */
    private final class BindingSession implements TemplateBinder {

        /**
         * Collected parameters in binding order.
         */
        private final ArrayList<Parameter> parameters = new ArrayList<>();

        /**
         * The bind variables instance used for this binding invocation, if any.
         */
        @Nullable
        private BindVariables bindVariables;

        /**
         * Per-session index used to generate unique named parameter names.
         */
        private int nameIndex;

        /**
         * Cursor into the shared bind hint tape.
         */
        private int hintCursor;

        /**
         * Cursor into the recorded bind vars counts.
         */
        private int bindVarsCursor;

        /**
         * Binds all elements in the given binding context, consuming bind hints for each non-synthetic element.
         *
         * @param context the binding context.
         * @throws SqlTemplateException if binding fails.
         */
        void bindElements(BindingContext context) throws SqlTemplateException {
            bindAll(context.elements());
            if (hintCursor != bindHints.size()) {
                throw new UncheckedSqlTemplateException(new SqlTemplateException(
                        "Bind hint consumption mismatch. Used %d hints but %d were produced."
                                .formatted(hintCursor, bindHints.size())));
            }
        }

        /**
         * Returns the next bind hint from the shared hint tape.
         *
         * @return the next bind hint.
         * @throws IllegalStateException if no hint is available or a placeholder is encountered.
         */
        private BindHint nextHint() {
            if (hintCursor >= bindHints.size()) {
                throw new IllegalStateException(
                        "Not enough bind hints. Cursor=%d but only %d hints available."
                                .formatted(hintCursor, bindHints.size()));
            }
            var hint = bindHints.get(hintCursor++);
            if (hint == PLACEHOLDER_HINT) {
                throw new IllegalStateException(
                        "Bind hint placeholder encountered at index %d. Compilation did not finalize hints."
                                .formatted(hintCursor - 1));
            }
            return hint;
        }

        /**
         * Asserts that all bind hints were consumed during binding.
         *
         * @throws IllegalStateException if the hint cursor does not match the number of produced hints.
         */
        private void assertAllHintsConsumed() {
            if (hintCursor != bindHints.size()) {
                throw new IllegalStateException(
                        "Bind hint consumption mismatch. Used %d hints but %d were produced."
                                .formatted(hintCursor, bindHints.size()));
            }
        }

        /**
         * Binds a prepared nested template using attached binding semantics.
         *
         * <p>Attached binding consumes bind hints from the shared tape, because nested compilation appended its hints to
         * the same tape.</p>
         *
         * @param preparedTemplate the prepared nested template.
         * @throws SqlTemplateException if binding fails.
         */
        private void bindPreparedAttached(PreparedTemplate preparedTemplate) throws SqlTemplateException {
            bindAll(preparedTemplate.context().elements());
        }

        /**
         * Binds the given elements, consuming a bind hint from the shared tape for each non-synthetic element.
         */
        private void bindAll(List<Element> elements) throws SqlTemplateException {
            for (Element element : elements) {
                if (element instanceof Wrapped(var wrapped)) {
                    for (var e : wrapped) {
                        if (!e.synthetic()) {
                            var hint = nextHint();
                            getElementProcessor(e.element()).bind(e.element(), this, hint);
                        }
                    }
                } else {
                    var hint = nextHint();
                    getElementProcessor(element).bind(element, this, hint);
                }
            }
        }

        /**
         * Resolves a model for the given data type.
         *
         * @param type the data type.
         * @param <T>  the data type.
         * @param <ID> the identifier type.
         * @return the model for the given type.
         */
        @Override
        public <T extends Data, ID> Model<T, ID> getModel(Class<T> type) {
            try {
                return modelBuilder.build(type, false);
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Returns the query model if present.
         *
         * @return the query model, if present.
         */
        @Override
        public Optional<QueryModel> findQueryModel() {
            return Optional.ofNullable(queryModel);
        }

        /**
         * Returns the query model, throwing if none is present.
         *
         * @return the query model.
         */
        @Override
        public QueryModel getQueryModel() {
            return findQueryModel().orElseThrow(
                    () -> new UncheckedSqlTemplateException(new SqlTemplateException("No primary table specified."))
            );
        }

        /**
         * Returns whether the compiled template is version-aware.
         *
         * @return {@code true} if version-aware.
         */
        @Override
        public boolean isVersionAware() {
            return versionAware != null && versionAware;
        }

        /**
         * Binds a value as a parameter.
         *
         * <p>In named parameter mode, a fresh name is generated for each parameter. In positional mode, a positional
         * parameter is added unless inline parameter rendering is enabled. Collection expansion is handled when enabled
         * by the template configuration.</p>
         *
         * @param value the value to bind.
         */
        @Override
        public void bindParameter(@Nullable Object value) {
            if (!template.positionalOnly()) {
                String name = "_p%d".formatted(nameIndex++);
                bindParameter(name, value);
                return;
            }
            switch (value) {
                case Object[] array when template.expandCollection() -> bindArgs(List.of(array), template.inlineParameters());
                case Iterable<?> it when template.expandCollection() -> bindArgs(it, template.inlineParameters());
                case Object[] ignore -> throw new UncheckedSqlTemplateException(new SqlTemplateException("Array parameters are not supported in SQL templates. Use a List instead of an array to pass multiple values."));
                case Iterable<?> ignore ->
                        throw new UncheckedSqlTemplateException(new SqlTemplateException("Collection parameters are not supported at this position in the SQL template. Use individual parameters, or pass the collection to a query builder method such as whereId(ids) or where(path, IN, values), which renders a WHERE ... IN clause."));
                case null, default -> {
                    if (!template.inlineParameters()) {
                        parameters.add(new PositionalParameter(parameters.size() + 1, value));
                    }
                }
            }
        }

        /**
         * Binds a named parameter.
         *
         * @param name  the parameter name.
         * @param value the parameter value.
         */
        @Override
        public void bindParameter(String name, @Nullable Object value) {
            if (template.positionalOnly()) {
                throw new UncheckedSqlTemplateException(new SqlTemplateException("Named parameters are not supported at this position in the SQL template. Use positional parameters instead."));
            }
            parameters.add(new NamedParameter(name, value));
        }

        /**
         * Binds a nested template using attached binding semantics.
         *
         * <p>Attached binding consumes bind hints because the nested template contributed hints during compilation.</p>
         *
         * @param templateString the nested template to bind.
         * @param correlate      whether correlation rules apply to the nested template.
         */
        @Override
        public void bind(TemplateString templateString, boolean correlate) {
            try {
                var context = templatePreparation.preprocess(templateString);
                var preparedTemplate = templatePreparation.prepare(context, TemplateProcessor.this, correlate);
                bindPreparedAttached(preparedTemplate);
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Binds an element using detached binding semantics.
         *
         * <p>Detached binding does not consume bind hints. It is used when binding an element outside of the compiled
         * element traversal context.</p>
         *
         * @param element the element to bind.
         */
        @Override
        public void bind(Element element) {
            try {
                getElementProcessor(element).bind(element, this, NoBindHint.INSTANCE);
            } catch (SqlTemplateException e) {
                throw new UncheckedSqlTemplateException(e);
            }
        }

        /**
         * Sets the {@link BindVars} instance for this session and returns a factory that binds values for one bind vars
         * segment.
         *
         * <p>The factory enforces the expected arity recorded during compilation. The start position is computed based on
         * already bound parameters and previously consumed bind vars segments.</p>
         *
         * @param vars the bind vars instance to use.
         * @return a parameter factory for a single bind vars segment.
         */
        @Override
        public ParameterFactory setBindVars(BindVars vars) {
            var current = bindVariables;
            if (current != null && current != vars) {
                throw new UncheckedSqlTemplateException(new SqlTemplateException("Multiple BindVars instances are not supported in a single template. Combine your bind variables into a single BindVars instance."));
            }
            if (vars instanceof BindVarsImpl bindVars) {
                bindVariables = bindVars;
            } else {
                throw new UncheckedSqlTemplateException(new SqlTemplateException("Unsupported BindVars type: %s. Expected a standard BindVars implementation.".formatted(vars.getClass().getSimpleName())));
            }
            int startPosition = parameters.size()
                    + bindVarsCounts.subList(0, bindVarsCursor).stream().mapToInt(count -> count).sum()
                    + 1;
            if (bindVarsCursor >= bindVarsCounts.size()) {
                throw new IllegalStateException("Not enough bind variables.");
            }
            // Each round owns its storage: the factory outlives its binding session through the parameter
            // extractors, which query plans invoke concurrently, and an abandoned round leaves no residue behind.
            final int expectedBindVarCount = bindVarsCounts.get(bindVarsCursor++);
            return () -> new ParameterFactory.Round() {
                final List<PositionalParameter> parameters = new ArrayList<>(expectedBindVarCount);

                @Override
                public void bind(@Nullable Object value) {
                    parameters.add(new PositionalParameter(startPosition + parameters.size(), value));
                }

                @Override
                public List<PositionalParameter> getParameters() {
                    if (parameters.size() != expectedBindVarCount) {
                        throw new IllegalStateException("Bind var count mismatch.");
                    }
                    return List.copyOf(parameters);
                }
            };
        }

        /**
         * Binds all values from an iterable in positional mode when collection expansion is enabled.
         *
         * @param iterable the iterable to bind.
         * @param inline   whether values are inlined in SQL and therefore not bound as parameters.
         */
        private void bindArgs(Iterable<?> iterable, boolean inline) {
            for (var v : iterable) {
                if (!inline) {
                    parameters.add(new PositionalParameter(parameters.size() + 1, v));
                }
            }
        }
    }
}
