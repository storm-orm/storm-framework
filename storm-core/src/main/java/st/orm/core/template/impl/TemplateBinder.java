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

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Element;
import st.orm.SqlTemplateException;
import st.orm.core.template.Model;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.TemplateString;

/**
 * Binding-time API used by element processors to bind runtime values to parameters.
 *
 * <p>A {@code TemplateBinder} is created per bind invocation and holds all mutable binding state, such as the list of
 * bound parameters and bind vars handling. It provides access to models and query metadata required for binding.</p>
 *
 * <p>Binding is expected to follow the same traversal order as compilation. When compilation recorded bind hints, the
 * binder consumes these hints in the same order to preserve deterministic parameter ordering.</p>
 *
 * @since 1.8
 */
interface TemplateBinder {

    /**
     * Resolves the model for the runtime type of the given record.
     *
     * <p>This is a convenience overload for cases where only an instance is available. The model is resolved based on
     * {@code record.getClass()}.</p>
     *
     * @param record the record instance whose runtime type is used to resolve a model.
     * @param <T>    the record type.
     * @param <ID>   the identifier type.
     * @return the resolved model for the record's runtime type.
     * @throws SqlTemplateException if the model cannot be resolved.
     */
    default <T extends Data, ID> Model<T, ID> getModel(T record) throws SqlTemplateException {
        //noinspection unchecked
        return getModel((Class<T>) record.getClass());
    }

    /**
     * Resolves a model for the given data type.
     *
     * @param type the data type.
     * @param <T>  the data type.
     * @param <ID> the identifier type.
     * @return the model for the given type.
     */
    <T extends Data, ID> Model<T, ID> getModel(Class<T> type);

    /**
     * Returns the query model, throwing if none is present.
     *
     * @return the query model.
     */
    QueryModel getQueryModel();

    /**
     * Returns the query model if present.
     *
     * @return the query model, if present.
     */
    Optional<QueryModel> findQueryModel();

    /**
     * Returns whether the compiled template is version-aware.
     *
     * @return {@code true} if version-aware.
     */
    boolean isVersionAware();

    /**
     * Binds a value as a parameter.
     *
     * <p>In named parameter mode, a fresh name is generated for each parameter. In positional mode, a positional
     * parameter is added unless inline parameter rendering is enabled. Collection expansion is handled when enabled
     * by the template configuration.</p>
     *
     * @param value the value to bind.
     */
    void bindParameter(@Nullable Object value);

    /**
     * Binds a named parameter.
     *
     * @param name  the parameter name.
     * @param value the parameter value.
     */
    void bindParameter(String name, @Nullable Object value);

    /**
     * Notes that an {@code IN} list rendered with a placeholder per value, in the default mode, so the statement's
     * shape is one the database sees as a statement per length.
     *
     * @param length the number of values the list rendered with.
     * @since 1.16
     */
    void inListRendered(int length);

    /**
     * Binds a nested template using attached binding semantics.
     *
     * <p>Attached binding consumes bind hints because the nested template contributed hints during compilation.</p>
     *
     * @param template  the nested template to bind.
     * @param correlate whether correlation rules apply to the nested template.
     */
    void bind(TemplateString template, boolean correlate);

    /**
     * Binds an element using detached binding semantics.
     *
     * <p>Detached binding does not consume bind hints. It is used when binding an element outside of the compiled
     * element traversal context.</p>
     *
     * @param element the element to bind.
     */
    void bind(Element element);

    /**
     * Produces positional parameters for a single {@link BindVars} segment during binding.
     *
     * <p>A {@code ParameterFactory} is obtained from {@link TemplateBinder#setBindVars(BindVars)}. Each extraction
     * starts a fresh {@link Round}, binds the segment's values, and retrieves the resulting positional parameters.
     * Rounds own their storage, so concurrent extractions do not interfere and an abandoned round, for example after
     * a conversion failure, leaves no residue behind.</p>
     */
    interface ParameterFactory {

        /**
         * Starts a new extraction round for this bind vars segment.
         *
         * @return a collector for one extraction.
         */
        Round newRound();

        /**
         * Collects the positional parameters of a single extraction round.
         *
         * <p>Implementations typically enforce the expected arity recorded during compilation. Calling
         * {@link #getParameters()} may validate that the number of bound values matches that expected arity.</p>
         */
        interface Round {

            /**
             * Binds one value for the bind vars segment.
             *
             * <p>The value becomes a positional parameter in the order it is provided. Implementations may accept
             * {@code null} values.</p>
             *
             * @param value the value to bind.
             */
            void bind(@Nullable Object value);

            /**
             * Returns the positional parameters produced for the bind vars segment.
             *
             * @return the positional parameters for this segment.
             * @throws IllegalStateException if the number of bound values does not match the expected arity.
             */
            List<PositionalParameter> getParameters();
        }
    }

    /**
     * Sets the bind variables for the current processor.
     *
     * @param vars the bind variables to set.
     * @return the parameter position at which the bind vars are set.
     */
    ParameterFactory setBindVars(BindVars vars);
}
