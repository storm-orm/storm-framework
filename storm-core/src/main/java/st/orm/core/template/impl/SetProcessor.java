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

import static java.util.stream.Collectors.joining;

import jakarta.annotation.Nonnull;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.impl.Elements.Set;

final class SetProcessor implements ElementProcessor<Set> {
    record SetBindHint(@Nonnull List<Column> columns) implements BindHint {}

    private static final Data EMPTY_DATA = new Data() {};

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
     * @param set the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(@Nonnull Set set) {
        if (set.record() != null) {
            return new Set(EMPTY_DATA, null, set.fields());
        }
        return new Set(null, null, set.fields());
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Set, TemplateBinder, BindHint)}.</p>
     *
     * @param set the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(@Nonnull Set set, @Nonnull TemplateCompiler compiler) throws SqlTemplateException {
        if (set.record() != null) {
            return compileSet(set.record(), set.fields(), compiler);
        }
        if (set.bindVars() != null) {
            return compileSetBindVars(set.bindVars(), set.fields(), compiler);
        }
        throw new SqlTemplateException("No values found for SET clause. Ensure the entity or record passed to the set() method has at least one field to update.");
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param set the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(@Nonnull Set set, @Nonnull TemplateBinder binder, @Nonnull BindHint bindHint) throws SqlTemplateException {
        if (bindHint instanceof SetBindHint(List<Column> columns)) {
            if (set.record() != null) {
                var queryModel = binder.getQueryModel();
                var table = queryModel.getTable();
                //noinspection unchecked
                var model = (Model<Data, ?>) binder.getModel(table.type());
                model.validateForeignKeys(columns, set.record());
                var mapped = model.values(columns, set.record());
                for (var entry : mapped.entrySet()) {
                    var column = entry.getKey();
                    if (!column.version()) {
                        binder.bindParameter(entry.getValue());
                    }
                }
            }
            if (set.bindVars() instanceof BindVarsImpl vars) {
                var queryModel = binder.getQueryModel();
                var table = queryModel.getTable();
                //noinspection unchecked
                var model = (Model<Data, ?>) binder.getModel(table.type());
                var parameterFactory = binder.setBindVars(vars);
                vars.addParameterExtractor(record -> {
                    try {
                        model.validateForeignKeys(columns, record);
                        model.forEachValue(columns, record, (column, value) -> parameterFactory.bind(value));
                        return parameterFactory.getParameters();
                    } catch (SqlTemplateException ex) {
                        throw new UncheckedSqlTemplateException(ex);
                    }
                });
            }
        } else {
            throw new IllegalStateException("Unexpected bind hint: %s.".formatted(bindHint.getClass().getSimpleName()));
        }
    }

    private List<Column> getColumns(@Nonnull Model<?, ?> model, @Nonnull Collection<Metamodel<?, ?>> fields) {
        return model.declaredColumns().stream()
                .filter(column -> !column.primaryKey() && column.updatable()
                        && (fields.isEmpty() || fields.contains(column.metamodel())))
                .toList();
    }

    /**
     * Returns the SQL string for the specified record.
     *
     * @param record the record to process.
     * @return the SQL string for the specified record.
     * @throws SqlTemplateException if the template does not comply to the specification.
     */
    private CompiledElement compileSet(@Nonnull Data record, @Nonnull Collection<Metamodel<?, ?>> fields, @Nonnull TemplateCompiler compiler) throws SqlTemplateException {
        var queryModel = compiler.getQueryModel();
        var table = queryModel.getTable();
        //noinspection unchecked
        var model = (Model<Data, ?>) compiler.getModel(table.type());
        var columns = getColumns(model, fields);
        var mapped = model.values(columns, record);
        var dialect = compiler.dialect();
        List<String> args = new ArrayList<>();
        for (var entry : mapped.entrySet()) {
            var column = entry.getKey();
            if (!column.version()) {
                args.add("%s%s = %s".formatted(table.alias().isEmpty()
                        ? ""
                        : table.alias() + ".",
                        column.qualifiedName(compiler.dialect()), compiler.mapParameter(entry.getValue())));
                args.add(", ");
            } else {
                compiler.setVersionAware();
                var versionString = compileVersion(column.qualifiedName(dialect), column.type(), table.alias(), dialect);
                args.add(versionString);
                args.add(", ");
            }
        }
        if (!args.isEmpty()) {
            args.removeLast();
        }
        return new CompiledElement(String.join("", args),
                new SetBindHint(columns.stream().filter(column -> !column.version()).toList()));
    }

    /**
     * Returns the SQL string for the specified bindVars.
     *
     * @param bindVars the bindVars to process.
     * @return the SQL string for the specified bindVars.
     * @throws SqlTemplateException if the template does not comply to the specification.
     */
    private CompiledElement compileSetBindVars(@Nonnull BindVars bindVars, @Nonnull Collection<Metamodel<?, ?>> fields, @Nonnull TemplateCompiler compiler) throws SqlTemplateException {
        if (bindVars instanceof BindVarsImpl) {
            var queryModel = compiler.getQueryModel();
            var table = queryModel.getTable();
            //noinspection unchecked
            var model = (Model<Data, ?>) compiler.getModel(table.type());
            var columns = getColumns(model, fields);
            AtomicInteger bindVarsCount = new AtomicInteger();
            String bindVarsString = columns.stream()
                    .map(column -> {
                        if (!column.version()) {
                            bindVarsCount.incrementAndGet();
                            return "%s%s = ?".formatted(
                                    table.alias().isEmpty() ? "" : table.alias() + ".",
                                    column.qualifiedName(compiler.dialect())
                            );
                        }
                        compiler.setVersionAware();
                        return compileVersion(column.qualifiedName(compiler.dialect()), column.type(), table.alias(), compiler.dialect());
                    })
                    .collect(joining(", "));
            compiler.mapBindVars(bindVarsCount.getPlain());
            return new CompiledElement(bindVarsString,
                    new SetBindHint(columns.stream().filter(column -> !column.version()).toList()));
        }
        throw new SqlTemplateException("Unsupported BindVars type in SET clause. Expected a standard BindVars implementation.");
    }

    /**
     * Returns the version string for the version column.
     *
     * @param columnName the column name of the version column.
     * @param type the type of the version column.
     * @param alias the alias of the table.
     * @param dialect the SQL dialect.
     * @return the version string for the version column.
     */
    private static String compileVersion(@Nonnull String columnName, @Nonnull Class<?> type, @Nonnull String alias, @Nonnull SqlDialect dialect) {
        String a = alias.isEmpty() ? "" : alias + ".";
        String value = switch (type) {
            case Class<?> c when
                    Integer.TYPE.isAssignableFrom(c)
                            || Long.TYPE.isAssignableFrom(c)
                            || Integer.class.isAssignableFrom(c)
                            || Long.class.isAssignableFrom(c)
                            || BigInteger.class.isAssignableFrom(c) -> "%s%s + 1".formatted(a, columnName);
            case Class<?> c when
                    Instant.class.isAssignableFrom(c)
                            || Date.class.isAssignableFrom(c)
                            || Calendar.class.isAssignableFrom(c)
                            || Timestamp.class.isAssignableFrom(c) -> dialect.currentTimestamp();
            default -> columnName;
        };
        return "%s%s = %s".formatted(a, columnName, value);
    }
}
