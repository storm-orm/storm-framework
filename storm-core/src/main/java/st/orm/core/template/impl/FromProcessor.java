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

import static st.orm.core.template.impl.RecordReflection.getTableName;

import java.util.List;
import java.util.function.Function;
import st.orm.SqlTemplateException;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.From;
import st.orm.core.template.impl.Elements.TableSource;
import st.orm.core.template.impl.Elements.TemplateSource;

final class FromProcessor implements ElementProcessor<From> {

    /**
     * Returns a compilation key for the given element, optionally delegating to the provided {@code keyGenerator}
     * to compute keys for nested or derived {@link TemplateString} instances.
     *
     * <p>This variant allows an element to derive its compilation key from a sub-template, or from a transformed
     * view of the original template, while still participating in the same compilation cache. Implementations may
     * invoke {@code keyGenerator} for any {@link TemplateString} that contributes to the compiled shape.</p>
     *
     * <p>If this method returns {@code null}, the element is treated as non-cacheable.</p>
     *
     * @param from the element to compute a key for.
     * @param keyGenerator a function that generates compilation keys for sub-templates.
     * @return an immutable key for caching, or {@code null} if the element or its compilation cannot be cached.
     */
    @Override
    public Object getCompilationKey(From from, Function<TemplateString, Object> keyGenerator) {
        if (from.source() instanceof TemplateSource(var template)) {
            var key = keyGenerator.apply(template);
            return key == null ? null : List.of(key, from.alias(), from.autoJoin());
        }
        return from;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(From, TemplateBinder, BindHint)}.</p>
     *
     * @param from the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    public CompiledElement compile(From from, TemplateCompiler compiler) throws SqlTemplateException {
        if (from.source() instanceof TableSource(var table)) {
            // Observability fallback for statements without a select list, such as count queries; a preceding
            // select element takes precedence because the first recorded data type wins.
            compiler.setDataType(table);
        }
        final String alias = from.alias().isEmpty() ? "" : " " + from.alias();
        return new CompiledElement(switch (from) {
            case From(TableSource ts, String s, boolean b) ->
                    compiler.dialectTemplate().process("\0\0", getTableName(ts.table(), compiler.template().tableNameResolver(), compiler.template().schemaResolver()), alias);
            case From(TemplateSource ts, String s, boolean b) ->
                    "(%s)%s".formatted(compiler.compile(ts.template(), false), alias);    // From-clause is not correlated.
        });
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param from the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(From from, TemplateBinder binder, BindHint bindHint) {
        if (from instanceof From(TemplateSource ts, String s, boolean b)) {
            binder.bind(ts.template(), false);
        }
    }
}
