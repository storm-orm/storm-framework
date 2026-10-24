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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.impl.Elements.Values;

final class ValuesProcessor implements ElementProcessor<Values> {
    record ValuesBindHint(List<Column> columns) implements BindHint {}

    private static final List<Data> EMPTY_DATA = List.of();

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
     * @param values the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(Values values) {
        if (values.records() != null) {
            if (hasAtMostOneElement(values.records())) {
                // Only cache when record-count is 1.
                return new Values(EMPTY_DATA, null, values.ignoreAutoGenerate());
            }
            return null;
        }
        return new Values(null, null, values.ignoreAutoGenerate());
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Values, TemplateBinder, BindHint)}.</p>
     *
     * @param values the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(Values values, TemplateCompiler compiler) throws SqlTemplateException {
        if (values.records() != null) {
            return compileValues(values, compiler);
        }
        if (values.bindVars() != null) {
            return compileValuesBindVars(values, compiler);
        }
        throw new SqlTemplateException("No values found for VALUES clause. Ensure the entity or record passed to the values() method is not empty and has at least one field to insert.");
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param values the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(Values values, TemplateBinder binder, BindHint bindHint) throws SqlTemplateException {
        if (bindHint instanceof ValuesBindHint(List<Column> columns)) {
            var queryModel = binder.getQueryModel();
            var table = queryModel.getTable();
            //noinspection unchecked
            var model = (Model<Data, ?>) binder.getModel(table.type());
            var discriminator = SubtypeDiscriminator.of(table.type()).orElse(null);
            if (values.records() != null) {
                values.records().forEach(record -> {
                    try {
                        if (discriminator != null) {
                            binder.bindParameter(discriminator.value());
                        }
                        model.validateForeignKeys(columns, record);
                        model.forEachValue(columns, record, (column, value) -> {
                            switch (column.generation()) {
                                case NONE -> binder.bindParameter(value);
                                case IDENTITY, SEQUENCE -> {
                                    if (values.ignoreAutoGenerate()) {
                                        binder.bindParameter(value);
                                    }
                                }
                            }
                        });
                    } catch (SqlTemplateException e) {
                        throw new UncheckedSqlTemplateException(e);
                    }
                });
            }
            if (values.bindVars() instanceof BindVarsImpl vars) {
                var parameterFactory = binder.setBindVars(vars);
                vars.addParameterExtractor(record -> {
                    var round = parameterFactory.newRound();
                    try {
                        if (discriminator != null) {
                            round.bind(discriminator.value());
                        }
                        model.validateForeignKeys(columns, record);
                        model.forEachValue(columns, record, (column, value) -> {
                            switch (column.generation()) {
                                case NONE -> round.bind(value);
                                case IDENTITY, SEQUENCE -> {
                                    if (values.ignoreAutoGenerate()) {
                                        round.bind(value);
                                    }
                                }
                            }
                        });
                        return round.getParameters();
                    } catch (SqlTemplateException ex) {
                        throw new UncheckedSqlTemplateException(ex);
                    }
                });
            }
        } else {
            throw new IllegalStateException("Unexpected bind hint: %s.".formatted(bindHint.getClass().getSimpleName()));
        }
    }

    private static boolean hasAtMostOneElement(Iterable<?> iterable) {
        Iterator<?> it = iterable.iterator();
        if (!it.hasNext()) {
            return true; // 0.
        }
        it.next();
        return !it.hasNext(); // True if exactly 1.
    }

    private CompiledElement compileValues(Values values, TemplateCompiler compiler) throws SqlTemplateException {
        assert values.records() != null;
        var queryModel = compiler.getQueryModel();
        var table = queryModel.getTable();
        //noinspection unchecked
        var model = (Model<Data, ?>) compiler.getModel(table.type());
        var columns = insertableColumns(model);
        var discriminator = SubtypeDiscriminator.of(table.type()).orElse(null);
        List<String> rows = new ArrayList<>();
        for (var record : values.records()) {
            if (record == null) {
                throw new SqlTemplateException("Cannot generate VALUES clause: the record is null. Ensure a non-null entity is provided for insert operations.");
            }
            if (!table.type().isInstance(record)) {
                throw new SqlTemplateException("Record type %s does not match the target entity type %s. Ensure the record passed to values() matches the entity type specified in the INSERT INTO clause.".formatted(record.getClass().getSimpleName(), table.type().getSimpleName()));
            }
            var mapped = model.values(columns, record);
            // The discriminator of a single-table subtype leads the row; its parameter precedes the columns' too.
            String lead = discriminator == null ? null : compiler.mapParameter(discriminator.value());
            rows.add(renderRow(lead, columns, values.ignoreAutoGenerate(), compiler, column -> compiler.mapParameter(mapped.get(column))));
        }
        return new CompiledElement(String.join(", ", rows), new ValuesBindHint(columns));
    }

    private CompiledElement compileValuesBindVars(Values values, TemplateCompiler compiler)
            throws SqlTemplateException {
        assert values.bindVars() != null;
        if (values.bindVars() instanceof BindVarsImpl) {
            var queryModel = compiler.getQueryModel();
            var table = queryModel.getTable();
            //noinspection unchecked
            var model = (Model<Data, ?>) compiler.getModel(table.type());
            var columns = insertableColumns(model);
            boolean discriminator = SubtypeDiscriminator.of(table.type()).isPresent();
            compiler.mapBindVars((int) columns.stream()
                    .filter(column -> switch (column.generation()) {
                        case NONE -> true;
                        case IDENTITY, SEQUENCE -> values.ignoreAutoGenerate();
                    })
                    .count() + (discriminator ? 1 : 0));
            return new CompiledElement(renderRow(discriminator ? "?" : null, columns, values.ignoreAutoGenerate(), compiler, column -> "?"),
                    new ValuesBindHint(columns));
        }
        throw new SqlTemplateException("Unsupported BindVars type in VALUES clause. Expected a standard BindVars implementation.");
    }

    private static List<Column> insertableColumns(Model<Data, ?> model) {
        return model.declaredColumns().stream().filter(Column::insertable).toList();
    }

    /**
     * Renders one VALUES row once, regardless of parameter source. The generation switch and sequence-retrieval
     * are produced here; {@code parameterSql} supplies each bound column's parameter fragment, either an immediately
     * bound value or a deferred bind placeholder. Sharing this keeps the value and bindVars paths from drifting on
     * dialect-specific rendering such as sequence next-value.
     *
     * @param lead the parameter fragment of the discriminator a single-table subtype writes first, or {@code null}.
     * @param columns the insertable columns, in model order.
     * @param ignoreAutoGenerate whether database-generated columns are supplied explicitly.
     * @param compiler the active compiler context.
     * @param parameterSql renders the parameter fragment for a bound column.
     * @return the rendered row, parenthesized.
     */
    private String renderRow(@Nullable String lead, List<Column> columns, boolean ignoreAutoGenerate,
                             TemplateCompiler compiler, Function<Column, String> parameterSql) {
        List<String> placeholders = new ArrayList<>();
        if (lead != null) {
            placeholders.add(lead);
        }
        for (var column : columns) {
            switch (column.generation()) {
                case NONE -> placeholders.add(parameterSql.apply(column));
                case IDENTITY -> {
                    if (ignoreAutoGenerate) {
                        placeholders.add(parameterSql.apply(column));
                    }
                }
                case SEQUENCE -> {
                    if (ignoreAutoGenerate) {
                        placeholders.add(parameterSql.apply(column));
                    } else {
                        String sequenceName = column.sequence();
                        if (!sequenceName.isEmpty()) {
                            // Do NOT bind a value; emit sequence retrieval instead.
                            placeholders.add(compiler.dialect().sequenceNextVal(sequenceName));
                        }
                    }
                }
            }
        }
        return "(%s)".formatted(String.join(", ", placeholders));
    }
}
