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

import st.orm.core.template.impl.Elements.Select;

final class SelectProcessor implements ElementProcessor<Select> {

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
     * @param select the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(Select select) {
        return select;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Select, TemplateBinder, BindHint)}.</p>
     *
     * @param select the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     */
    @Override
    public CompiledElement compile(Select select, TemplateCompiler compiler) {
        compiler.setDataType(select.table());
        FetchPlan fetchPlan = compiler.getFetchPlan();
        // The closed plan is what shaped the select list, so that is the form the row mapper has to read back.
        compiler.setFetchPaths(fetchPlan.toList());
        var columns = compiler.getQueryModel()
                .getColumns(select.table(), select.mode(), fetchPlan).stream()
                .map(ColumnExpression::toSql)
                .toList();
        // The list is what a cursor element finds its columns in.
        compiler.setSelectColumns(columns);
        return new CompiledElement(String.join(", ", columns));
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param select the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(Select select, TemplateBinder binder, BindHint bindHint) {
    }
}
