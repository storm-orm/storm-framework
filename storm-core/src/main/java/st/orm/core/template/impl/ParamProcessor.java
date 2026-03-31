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

import static st.orm.core.template.impl.RecordReflection.isRecord;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import st.orm.Data;
import st.orm.Entity;
import st.orm.Ref;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.impl.Elements.Param;

final class ParamProcessor implements ElementProcessor<Param> {

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
     * @param param the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(@Nonnull Param param) {
        return new Param(param.name(), null);
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Param, TemplateBinder, BindHint)}.</p>
     *
     * @param param the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(@Nonnull Param param, @Nonnull TemplateCompiler compiler)
            throws SqlTemplateException {
        Object value = resolveParamValue(param.dbValue());
        if (param.name() != null) {
            return new CompiledElement(compiler.mapParameter(param.name(), value));
        }
        return new CompiledElement(compiler.mapParameter(value));
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param param the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(@Nonnull Param param, @Nonnull TemplateBinder binder, @Nonnull BindHint bindHint) throws SqlTemplateException {
        Object value = resolveParamValue(param.dbValue());
        if (param.name() != null) {
            binder.bindParameter(param.name(), value);
        } else {
            binder.bindParameter(value);
        }
    }

    /**
     * Resolves a parameter value for binding. {@link Ref} and {@link Entity} instances are unwrapped to their primary
     * key value. Other {@link Data} instances (projections, inline types) are rejected since they cannot be used as
     * single bind parameters.
     *
     * <p>This allows {@code Ref<T>} and {@code Entity<ID>} instances to be used directly as bind variables in raw SQL
     * templates (e.g., {@code "WHERE id = $ref"} or {@code "WHERE id = $entity"}) without requiring the caller to
     * extract the ID manually. Compound primary keys (where the ID is itself a record) are rejected because they span
     * multiple columns and cannot be bound as a single parameter.</p>
     *
     * @param value the parameter value.
     * @return the resolved value suitable for JDBC binding.
     * @throws SqlTemplateException if the value has a compound primary key, or is a non-entity {@code Data} instance.
     */
    @Nullable
    private static Object resolveParamValue(@Nullable Object value) throws SqlTemplateException {
        if (value instanceof Ref<?> ref) {
            Object id = ref.id();
            if (isRecord(id.getClass())) {
                throw new SqlTemplateException(
                        "Ref<%s> has a compound primary key and cannot be used as a single bind parameter. Extract the individual key fields instead."
                                .formatted(ref.type().getSimpleName()));
            }
            return id;
        }
        if (value instanceof Entity<?> entity) {
            Object id = entity.id();
            if (isRecord(id.getClass())) {
                throw new SqlTemplateException(
                        "%s has a compound primary key and cannot be used as a single bind parameter. Extract the individual key fields instead."
                                .formatted(entity.getClass().getSimpleName()));
            }
            return id;
        }
        if (value instanceof Data) {
            throw new SqlTemplateException(
                    "%s is a Data type and cannot be used as a single bind parameter. Use a Ref or Entity with a simple primary key, or extract the value manually."
                            .formatted(value.getClass().getSimpleName()));
        }
        return value;
    }
}
