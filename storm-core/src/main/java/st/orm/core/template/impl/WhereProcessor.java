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

import static st.orm.Operator.EQUALS;
import static st.orm.core.template.impl.ElementRouter.getElementProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.function.Function;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Where;

final class WhereProcessor implements ElementProcessor<Where> {
    /**
     * @param columns the identifying columns a bind-vars condition compares, empty for an expression.
     * @param discriminator whether the condition carries the discriminator of the statement's table, a subtype of a
     *                      single-table hierarchy, whose value is bound before the condition's parameters.
     */
    record WhereBindHint(List<Column> columns, boolean discriminator) implements BindHint {
        WhereBindHint(List<Column> columns) {
            this(columns, false);
        }
    }

    /** Marker passed as the placeholder value; the dialect reads only column names and the parameter function. */
    private static final Object PLACEHOLDER = new Object();

    /** The compilation key of a condition holding a discriminator alone. */
    private static final Object DISCRIMINATOR_KEY = "discriminator";

    /**
     * Returns a key that represents the compiled shape of the given element.
     *
     * <p>The compilation key is used for caching compiled results. It must include all fields that can affect the
     * compilation output (SQL text, emitted fragments, placeholder shape, etc.). The key is compared using
     * value-based equality, so it should be immutable and implement stable {@code equals}/{@code hashCode}.</p>
     *
     * <p>If this method returns {@code null} for any element in a template, the compiled result is considered
     * non-cacheable and the template must be recompiled each time it is requested.</p>
     *
     * @param where the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     * @throws SqlTemplateException if the key generation fails.
     */
    @Override
    public Object getCompilationKey(Where where, Function<TemplateString, Object> keyGenerator)
            throws SqlTemplateException {
        if (where.expression() != null) {
            var cacheable = new Cacheable(where.expression());
            return getElementProcessor(cacheable).getCompilationKey(cacheable, keyGenerator);
        }
        if (where.bindVars() != null) {
            return new Where(null, null, where.bindVarsKey());
        }
        // A condition without expression and bind variables holds the discriminator of a single-table subtype alone,
        // which the statement's table decides.
        return DISCRIMINATOR_KEY;
    }

    /**
     * Returns the shape key by delegating to the wrapped expression's shape key, so collection arity stays
     * erased through the delegation.
     */
    @Override
    public Object getShapeKey(Where where, Function<TemplateString, Object> keyGenerator)
            throws SqlTemplateException {
        if (where.expression() != null) {
            var cacheable = new Cacheable(where.expression());
            return getElementProcessor(cacheable).getShapeKey(cacheable, keyGenerator);
        }
        return getCompilationKey(where, keyGenerator);
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Where, TemplateBinder, BindHint)}.</p>
     *
     * @param where the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(Where where, TemplateCompiler compiler) throws SqlTemplateException {
        // The discriminator comes first, so its parameter precedes the condition's in the bind order.
        String discriminator = where.discriminator() ? compileDiscriminator(compiler) : null;
        CompiledElement condition;
        if (where.expression() != null) {
            condition = new CompiledElement(compiler.getQueryModel().compileExpression(where.expression(), compiler),
                    new WhereBindHint(List.of()));
        } else if (where.bindVars() != null) {
            condition = compileWhereBindVars(where, compiler);
        } else if (discriminator != null) {
            return new CompiledElement(discriminator, new WhereBindHint(List.of(), true));
        } else {
            throw new SqlTemplateException("No expression or bind variables found for WHERE clause. Provide at least one filter condition.");
        }
        if (discriminator == null) {
            return condition;
        }
        var hint = (WhereBindHint) condition.bindHint();
        return new CompiledElement(() -> "%s AND (%s)".formatted(discriminator, condition.get()),
                new WhereBindHint(hint.columns(), true));
    }

    /**
     * Renders the condition selecting the rows of the statement's table, a subtype of a single-table hierarchy.
     */
    private static String compileDiscriminator(TemplateCompiler compiler) throws SqlTemplateException {
        var table = compiler.getQueryModel().getTable();
        var discriminator = SubtypeDiscriminator.of(table.type()).orElseThrow(
                () -> new SqlTemplateException("%s is not a subtype of a single-table hierarchy, so its condition carries no discriminator.".formatted(table.type().getSimpleName())));
        return discriminator.predicate(table.alias(), compiler);
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param where the element that was compiled.
     * @param binder the binder used to bind runtime values.
     */
    @Override
    public void bind(Where where, TemplateBinder binder, BindHint bindHint) throws SqlTemplateException {
        if (bindHint instanceof WhereBindHint(var columns, var discriminator)) {
            if (discriminator) {
                var table = binder.getQueryModel().getTable();
                binder.bindParameter(SubtypeDiscriminator.of(table.type()).orElseThrow().value());
            }
            if (where.expression() != null) {
                assert columns.isEmpty();
                binder.getQueryModel().bindExpression(where.expression(), binder);
            }
            if (where.bindVars() instanceof BindVarsImpl vars) {
                var table = binder.getQueryModel().getTable();
                //noinspection unchecked
                var model = (Model<Data, ?>) binder.getModel(table.type());
                var parameterFactory = binder.setBindVars(where.bindVars());
                vars.addParameterExtractor(record -> {
                    var round = parameterFactory.newRound();
                    try {
                        model.forEachValue(columns, record, (column, value) -> round.bind(value));
                        return round.getParameters();
                    } catch (SqlTemplateException ex) {
                        throw new UncheckedSqlTemplateException(ex);
                    }
                });
                if (where.bindVarsKey() != null) {
                    // The WHERE clause is compiled for a specific key, so a raw value can supply every column;
                    // query plans use this to bind key lookups without a full record.
                    //noinspection unchecked
                    var key = (Metamodel<Data, ?>) where.bindVarsKey();
                    vars.addValueParameterExtractor(value -> {
                        var round = parameterFactory.newRound();
                        try {
                            model.forEachValue(key, value, (column, columnValue) -> round.bind(columnValue));
                            return round.getParameters();
                        } catch (SqlTemplateException ex) {
                            throw new UncheckedSqlTemplateException(ex);
                        }
                    });
                } else if (columns.stream().allMatch(Column::primaryKey)) {
                    // The identifying columns are exactly the primary key, so a raw id can supply every value;
                    // query plans use this to bind by-id statements without a full record.
                    model.getPrimaryKeyMetamodel().ifPresent(primaryKeyMetamodel ->
                            vars.addValueParameterExtractor(id -> {
                                var round = parameterFactory.newRound();
                                try {
                                    model.forEachValue(primaryKeyMetamodel, id,
                                            (column, value) -> round.bind(value));
                                    return round.getParameters();
                                } catch (SqlTemplateException ex) {
                                    throw new UncheckedSqlTemplateException(ex);
                                }
                            }));
                }
            }
        } else {
            throw new IllegalStateException("Unexpected bind hint: %s.".formatted(bindHint.getClass().getSimpleName()));
        }
    }

    private List<Column> getIdentifyingColumns(TemplateCompiler compiler)
            throws SqlTemplateException {
        var table = compiler.getQueryModel().getTable();
        var columns = compiler.getModel(table.type()).declaredColumns();
        boolean hasPk = columns.stream().anyMatch(Column::primaryKey);
        if (!hasPk) {
            throw new SqlTemplateException("No primary key found for table %s. Entity types used in WHERE clauses must have a primary key field annotated with @PK.".formatted(table.type().getSimpleName()));
        }
        return columns.stream()
                .filter(c -> c.primaryKey() || (compiler.isVersionAware() && c.version()))
                .toList();
    }

    private CompiledElement compileWhereBindVars(Where where,
                                                 TemplateCompiler compiler) throws SqlTemplateException {
        if (where.bindVars() instanceof BindVarsImpl) {
            var queryModel = compiler.getQueryModel();
            var columns = where.bindVarsKey() != null
                    ? getKeyColumns(where.bindVarsKey(), compiler)
                    : getIdentifyingColumns(compiler);
            compiler.mapBindVars(columns.size());
            // Hand the key columns to the shared comparison renderer without deciding single-versus-multi here: the
            // renderer owns that decision, so the bindVars WHERE cannot diverge from the value-based WHERE. The
            // placeholder values are markers; the renderer reads only the column names and the parameter function,
            // which emits "?". EQUALS lists the columns in map order, matching the extractors' column-order binding.
            SequencedMap<String, Object> row = new LinkedHashMap<>();
            for (var column : columns) {
                row.put(queryModel.toColumnExpression(column).toSql(), PLACEHOLDER);
            }
            String sql = ColumnComparison.render(EQUALS, null, List.of(), List.of(row), value -> "?", compiler.dialect());
            return new CompiledElement(sql, new WhereBindHint(columns));
        }
        throw new SqlTemplateException("Unsupported BindVars type in WHERE clause. Expected a standard BindVars implementation.");
    }

    private List<Column> getKeyColumns(Metamodel<?, ?> key, TemplateCompiler compiler)
            throws SqlTemplateException {
        var table = compiler.getQueryModel().getTable();
        var columns = compiler.getModel(table.type()).getColumns(key);
        if (columns.isEmpty()) {
            throw new SqlTemplateException("No columns found for key %s on table %s.".formatted(key, table.type().getSimpleName()));
        }
        return columns;
    }
}
