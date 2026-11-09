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

import static java.util.Objects.requireNonNull;
import static st.orm.core.template.impl.SqlInterceptorManager.intercept;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import st.orm.Data;
import st.orm.IntegrityViolationException;
import st.orm.PersistenceException;
import st.orm.core.template.Query;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;

/**
 * Compiled query plan backed by a processed template.
 *
 * <p>The plan holds the processed SQL and an immutable snapshot of the value-independent parameter extractors that
 * template processing registered for the template's bind variables. Binding a record runs the extractors and derives
 * a value-bound {@link Sql} that flows through the regular one-shot execution path. The plan carries no connection or
 * statement state, making it thread-safe and reusable across executions.</p>
 */
final class QueryPlanImpl implements QueryPlan {

    private final Sql sql;
    private final List<Function<Data, List<PositionalParameter>>> parameterExtractors;
    private final List<Function<Object, List<PositionalParameter>>> valueParameterExtractors;
    private final Function<Sql, Query> queryFactory;
    private final Function<Throwable, RuntimeException> exceptionTransformer;

    /**
     * @param sql the processed statement.
     * @param parameterExtractors the extractors of the parameters a bound record supplies.
     * @param valueParameterExtractors the extractors of the parameters a bound value supplies.
     * @param queryFactory creates the query of a bound statement.
     * @param exceptionTransformer maps a violation binding a record detects, a null in a field whose column refuses
     *                             it, to the exception thrown to the caller, via the template's exception mapper
     *                             with the statement as context.
     */
    QueryPlanImpl(Sql sql,
                  List<Function<Data, List<PositionalParameter>>> parameterExtractors,
                  List<Function<Object, List<PositionalParameter>>> valueParameterExtractors,
                  Function<Sql, Query> queryFactory,
                  Function<Throwable, RuntimeException> exceptionTransformer) {
        this.sql = requireNonNull(sql, "sql");
        this.parameterExtractors = List.copyOf(parameterExtractors);
        this.valueParameterExtractors = List.copyOf(valueParameterExtractors);
        this.queryFactory = requireNonNull(queryFactory, "queryFactory");
        this.exceptionTransformer = requireNonNull(exceptionTransformer, "exceptionTransformer");
    }

    @Override
    public Query bind(Data record) {
        requireNonNull(record, "record");
        if (parameterExtractors.isEmpty()) {
            throw new PersistenceException("Cannot bind a record against a constant plan: the plan's template has no bind variables. Use query() instead.");
        }
        try {
            var parameters = new ArrayList<Parameter>(sql.parameters().size() + parameterExtractors.size());
            parameters.addAll(sql.parameters());
            for (var extractor : parameterExtractors) {
                parameters.addAll(extractor.apply(record));
            }
            // Interceptors observe every bound statement, matching the observability of per-call processing.
            return queryFactory.apply(intercept(sql.parameters(parameters).bindVariables(null)));
        } catch (UncheckedSqlTemplateException e) {
            throw new PersistenceException(e.getCause());
        } catch (IntegrityViolationException e) {
            throw exceptionTransformer.apply(e);
        }
    }

    @Override
    public Query bindValue(Object value) {
        requireNonNull(value, "value");
        if (parameterExtractors.isEmpty()) {
            throw new PersistenceException("Cannot bind a value against a constant plan: the plan's template has no bind variables. Use query() instead.");
        }
        // A single value can feed exactly one key; a plan with multiple bind variables segments would bind the same
        // value through every segment, which is only correct for a record that supplies each segment's own values.
        if (parameterExtractors.size() != 1 || valueParameterExtractors.size() != 1) {
            throw new PersistenceException("Cannot bind a value: the plan's bind variables must consist of a single key-based WHERE clause. Use bind() with a record instead.");
        }
        try {
            var extracted = valueParameterExtractors.getFirst().apply(value);
            var parameters = new ArrayList<Parameter>(sql.parameters().size() + extracted.size());
            parameters.addAll(sql.parameters());
            parameters.addAll(extracted);
            // Interceptors observe every bound statement, matching the observability of per-call processing.
            return queryFactory.apply(intercept(sql.parameters(parameters).bindVariables(null)));
        } catch (UncheckedSqlTemplateException e) {
            throw new PersistenceException(e.getCause());
        }
    }

    @Override
    public Query query() {
        if (!parameterExtractors.isEmpty()) {
            throw new PersistenceException("Cannot execute a plan with bind variables without a record. Use bind() instead.");
        }
        // Interceptors observe every executed statement, matching the observability of per-call processing.
        return queryFactory.apply(intercept(sql));
    }
}
