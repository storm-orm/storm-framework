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

import java.util.ArrayList;
import java.util.Objects;
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;
import st.orm.core.template.impl.Elements.Insert;

final class InsertProcessor implements ElementProcessor<Insert> {

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
     * @param insert the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(Insert insert) {
        return insert;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Insert, TemplateBinder, BindHint)}.</p>
     *
     * @param insert the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(Insert insert, TemplateCompiler compiler) throws SqlTemplateException {
        var queryModel = compiler.getQueryModel();
        assert queryModel.getTable().type() == insert.table();
        var model = compiler.getModel(insert.table());
        var dialect = compiler.dialect();
        var generatedKeys = new ArrayList<String>();
        String columns = model.declaredColumns().stream()
                .filter(Column::insertable)
                .map(column -> {
                    if (column.primaryKey() && !insert.ignoreAutoGenerate()) {
                        return switch (column.generation()) {
                            case NONE -> column.qualifiedName(dialect);
                            case IDENTITY -> {
                                // When the keys are fetched via a RETURNING clause, the identity column is still
                                // excluded from the column list (the database generates it) but must not be registered
                                // as a JDBC generated key, otherwise the driver prepares the statement for
                                // getGeneratedKeys and the RETURNING result set cannot be read.
                                if (!insert.returningKeys()) {
                                    generatedKeys.add(column.qualifiedName(dialect));
                                }
                                yield null;
                            }
                            case SEQUENCE -> {
                                if (!column.sequence().isEmpty()) {
                                    yield column.qualifiedName(dialect);
                                }
                                yield null;
                            }
                        };
                    }
                    return column.qualifiedName(dialect);
                })
                .filter(Objects::nonNull)
                .collect(joining(", "));
        // A subtype of a single-table hierarchy writes its discriminator, which its record does not carry.
        var discriminator = SubtypeDiscriminator.of(insert.table());
        if (discriminator.isPresent()) {
            columns = discriminator.get().columnName(dialect) + ", " + columns;
        }
        compiler.setGeneratedKeys(generatedKeys);
        var table = queryModel.getTable();
        compiler.setAffectedType(insert.table());
        compiler.setDataType(insert.table());
        return new CompiledElement("%s%s (%s)".formatted(table.name(), table.alias().isEmpty() ? "" : " " + table.alias(), columns));
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param insert the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(Insert insert, TemplateBinder binder, BindHint bindHint) {
    }
}
