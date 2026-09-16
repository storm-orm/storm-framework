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

import st.orm.Metamodel;
import st.orm.ResolveScope;
import st.orm.SqlTemplateException;
import st.orm.core.template.impl.Elements.Column;

final class ColumnProcessor implements ElementProcessor<Column> {

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
     * @param column the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(Column column) {
        return column;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Column, TemplateBinder, BindHint)}.</p>
     *
     * @param column the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(Column column, TemplateCompiler compiler)
            throws SqlTemplateException {
        var metamodel = MetamodelFactory.canonical(column.field());
        var model = compiler.getModel(metamodel.tableType());
        var columnName = model.getSingleColumn(metamodel).qualifiedName(compiler.dialect());
        return new CompiledElement(aliasPrefix(metamodel, column.scope(), compiler) + columnName);
    }

    /**
     * Returns the alias prefix of the table occurrence the metamodel's columns belong to: the query's own table for
     * a path on the root, the occurrence the path reaches otherwise; empty when that occurrence has no alias.
     */
    static String aliasPrefix(Metamodel<?, ?> metamodel, ResolveScope scope, TemplateCompiler compiler) {
        String alias = compiler.findQueryModel()
                .map(QueryModel::getTable)
                .filter(table -> table.type() == metamodel.root() && metamodel.path().isEmpty())
                .map(AliasedTable::alias)
                .orElseGet(() -> compiler.getAlias(metamodel, scope));
        return alias.isEmpty() ? "" : alias + ".";
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param column the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(Column column, TemplateBinder binder, BindHint bindHint) {
    }
}
