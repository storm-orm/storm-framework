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

import st.orm.SqlTemplateException;
import st.orm.core.template.impl.Elements.Update;
import st.orm.core.template.impl.Elements.UpdateTarget;

final class UpdateProcessor implements ElementProcessor<Update> {

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
     * @param update the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(Update update) {
        return update;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Update, TemplateBinder, BindHint)}.</p>
     *
     * @param update the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(Update update, TemplateCompiler compiler)
            throws SqlTemplateException{
        var queryModel = compiler.getQueryModel();
        assert queryModel.getTable().type() == update.table();
        var table = queryModel.getTable();
        compiler.setAffectedType(update.table());
        compiler.setDataType(update.table());
        return new CompiledElement("%s%s".formatted(table.name(), table.alias().isEmpty() ? "" : " " + table.alias()));
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param update the element that was compiled.
     * @param binder the binder used to bind runtime values.
     */
    @Override
    public void bind(Update update, TemplateBinder binder, BindHint bindHint) {
    }

    /**
     * Processes the {@link UpdateTarget} of an UPDATE whose table a FROM element renders: it marks the table the
     * statement writes, so the entity cache of that type is invalidated once it runs, and renders nothing.
     *
     * @since 1.16
     */
    static final class TargetProcessor implements ElementProcessor<UpdateTarget> {

        @Override
        public Object getCompilationKey(UpdateTarget target) {
            return target;
        }

        @Override
        public CompiledElement compile(UpdateTarget target, TemplateCompiler compiler) {
            compiler.setAffectedType(target.table());
            compiler.setDataType(target.table());
            return new CompiledElement("");
        }

        @Override
        public void bind(UpdateTarget target, TemplateBinder binder, BindHint bindHint) {
        }
    }
}
