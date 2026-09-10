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

import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.Templates.from;
import static st.orm.core.template.Templates.subquery;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.Query;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Where;

/**
 * A query builder for DELETE queries.
 *
 * @param <T> the type of the table being queried.
 * @param <ID> the type of the primary key.
 */
public class DeleteBuilderImpl<T extends Data, ID> extends QueryBuilderImpl<T, Object, ID> {

    private final boolean unsafe;

    public DeleteBuilderImpl(QueryTemplate queryTemplate, Class<T> fromType, Supplier<Model<T, ID>> modelSupplier) {
        this(queryTemplate, fromType, List.of(), List.of(), List.of(), List.of(), List.of(), modelSupplier, false);
    }

    private DeleteBuilderImpl(QueryTemplate queryTemplate,
                              Class<T> fromType,
                              List<Join> join,
                              List<Where> where,
                              List<TemplateString> groupBy,
                              List<TemplateString> having,
                              List<TemplateString> orderBy,
                              Supplier<Model<T, ID>> modelSupplier,
                              boolean unsafe) {
        super(queryTemplate, fromType, join, where, groupBy, having, orderBy, modelSupplier);
        this.unsafe = unsafe;
    }

    /**
     * Returns a query builder that allows UPDATE and DELETE queries without a WHERE clause.
     *
     * <p>By default, Storm rejects UPDATE and DELETE queries that lack a WHERE clause, throwing a
     * {@link PersistenceException}. Call this method to disable that check when you intentionally want to affect all
     * rows in the table.</p>
     *
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, Object, ID> unsafe() {
        return new DeleteBuilderImpl<>(queryTemplate, fromType, join, where, groupBy, having, orderBy, modelSupplier, true);
    }

    /**
     * Returns a new query builder instance with the specified parameters.
     *
     * @param queryTemplate the query template.
     * @param fromType the type of the table being queried.
     * @param join the list of joins.
     * @param where the list of where clauses.
     * @return a new query builder.
     */
    @Override
    QueryBuilder<T, Object, ID> copyWith(QueryTemplate queryTemplate,
                                         Class<T> fromType,
                                         List<Join> join,
                                         List<Where> where,
                                         List<TemplateString> groupBy,
                                         List<TemplateString> having,
                                         List<TemplateString> orderBy) {
        return new DeleteBuilderImpl<>(queryTemplate, fromType, join, where, groupBy, having, orderBy, modelSupplier, unsafe);
    }

    /**
     * Indicates whether the DELETE query supports joins.
     */
    protected boolean supportsJoin() {
        // Only use joins if DELETE alias is supported by the SQL dialect.
        return queryTemplate.dialect().supportsDeleteAlias();
    }

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, Object, ID> distinct() {
        throw new PersistenceException("Cannot use DISTINCT in a DELETE query.");
    }

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, Object, ID> offset(int offset) {
        throw new PersistenceException("Cannot use OFFSET in a DELETE query.");
    }

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, Object, ID> limit(int limit) {
        throw new PersistenceException("Cannot use LIMIT in a DELETE query.");
    }

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     */
    @Override
    public QueryBuilder<T, Object, ID> forShare() {
        throw new PersistenceException("Cannot use FOR SHARE in a DELETE query.");
    }

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     */
    @Override
    public QueryBuilder<T, Object, ID> forUpdate() {
        throw new PersistenceException("Cannot use FOR UPDATE in a DELETE query.");
    }

    /**
     * Locks the selected rows using a custom lock mode.
     *
     * <p><strong>Note:</strong> This method results in non-portable code, as the lock mode is specific to the
     * underlying database.</p>
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     */
    @Override
    public QueryBuilder<T, Object, ID> forLock(TemplateString template) {
        throw new PersistenceException("Cannot use FOR LOCK in a DELETE query.");
    }

    private TemplateString getPrimaryKeyTemplate(boolean alias) {
        var model = modelSupplier.get();
        return model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .map(c -> {
                    if (alias) {
                        return TemplateString.combine(wrap(fromType), TemplateString.of("."), TemplateString.of(c.qualifiedName(queryTemplate.dialect())));
                    }
                    return TemplateString.of(c.qualifiedName(queryTemplate.dialect()));
                })
                .reduce((a, b) -> TemplateString.combine(a, TemplateString.of(", "), b))
                .orElseThrow(() -> new PersistenceException("No primary key found for table: %s.".formatted(fromType.getSimpleName())));
    }

    private TemplateString toTemplateString() {
        TemplateString template;
        if (supportsJoin()) {
            template = TemplateString.raw("DELETE \0\nFROM \0", fromType, from(fromType, supportsJoin()));
        } else {
            template = TemplateString.combine(TemplateString.of("SELECT "), getPrimaryKeyTemplate(true), TemplateString.raw("\nFROM \0", from(fromType, true)));
        }
        template = appendJoinsAndWhere(template);
        if (!supportsJoin()) {
            template = TemplateString.combine(TemplateString.raw("DELETE\nFROM \0\nWHERE (", from(fromType, false)),
                    getPrimaryKeyTemplate(false), TemplateString.of(") IN ("), wrap(subquery(template, false)), TemplateString.of(")"));
        }
        return template;
    }

    @Override
    public TemplateString getSubquery() {
        throw new PersistenceException("Cannot use a DELETE query as subquery.");
    }

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    @Override
    public Query build() {
        Query query = queryTemplate.query(toTemplateString());
        if (unsafe || !where.isEmpty()) {
            query = query.unsafe();
        }
        return query;
    }

    /**
     * Compiles this query into a reusable plan.
     *
     * @return a reusable plan for this query.
     */
    @Override
    public QueryPlan plan() {
        var plan = queryTemplate.plan(toTemplateString());
        if (!unsafe && where.isEmpty()) {
            return plan;
        }
        // Queries built by this builder suppress the unsafe warning under the same condition; the returned plan
        // applies it to every query it produces.
        return new QueryPlan() {
            @Override
            public Query bind(Data record) {
                return plan.bind(record).unsafe();
            }

            @Override
            public Query bindValue(Object id) {
                return plan.bindValue(id).unsafe();
            }

            @Override
            public Query query() {
                return plan.query().unsafe();
            }
        };
    }

    @Override
    List<KeyedQuery.Row<Object>> getKeyedResultList(List<Metamodel<T, ?>> columns) {
        throw new PersistenceException("A delete query cannot be scrolled.");
    }

    @Override
    public Stream<Object> getResultStream() {
        throw new PersistenceException("Cannot get a result stream from a DELETE query.");
    }
}
