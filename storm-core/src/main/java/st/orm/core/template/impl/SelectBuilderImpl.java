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
import static st.orm.SelectMode.NESTED;
import static st.orm.SelectMode.PK;
import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.Templates.from;
import static st.orm.core.template.Templates.select;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.template.Model;
import st.orm.core.template.Query;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Fetch;
import st.orm.core.template.impl.Elements.Select;
import st.orm.core.template.impl.Elements.Where;

/**
 * A query builder for SELECT queries.
 *
 * @param <T> the type of the table being queried.
 * @param <R> the type of the result.
 * @param <ID> the type of the primary key.
 */
public class SelectBuilderImpl<T extends Data, R, ID> extends QueryBuilderImpl<T, R, ID> {
    private final TemplateString forLock;
    private final TemplateString selectTemplate;
    private final Class<R> selectType;
    private final boolean distinct;
    private final Integer limit;
    private final Integer offset;
    private final boolean subquery;
    private final Class<? extends Data> refType;
    private final Class<?> pkType;
    private final List<String> fetchPaths;

    public SelectBuilderImpl(QueryTemplate queryTemplate,
                             Class<T> fromType,
                             Class<R> selectType,
                             TemplateString selectTemplate,
                             boolean subquery,
                             Supplier<Model<T, ID>> modelSupplier) {
        this(queryTemplate, fromType, selectType, false, List.of(), List.of(), null, null, TemplateString.EMPTY, selectTemplate, List.of(), List.of(), List.of(), subquery, null, null, List.of(), modelSupplier);
    }

    public SelectBuilderImpl(QueryTemplate queryTemplate,
                             Class<T> fromType,
                             Class<? extends Data> refType,
                             Class<?> pkType,
                             Supplier<Model<T, ID>> modelSupplier) {
        //noinspection unchecked
        this(queryTemplate, fromType, (Class<R>) Ref.class, false, List.of(), List.of(), null, null, TemplateString.EMPTY, wrap(select(refType, PK)), List.of(), List.of(), List.of(), false, requireNonNull(refType), requireNonNull(pkType), List.of(), modelSupplier);
    }

    private SelectBuilderImpl(QueryTemplate ormTemplate,
                              Class<T> fromType,
                              Class<R> selectType,
                              boolean distinct,
                              List<Join> join,
                              List<Where> where,
                              @Nullable Integer limit,
                              @Nullable Integer offset,
                              TemplateString forLock,
                              TemplateString selectTemplate,
                              List<TemplateString> groupBy,
                              List<TemplateString> having,
                              List<TemplateString> orderBy,
                              boolean subquery,
                              @Nullable Class<? extends Data> refType,
                              @Nullable Class<?> pkType,
                              List<String> fetchPaths,
                              Supplier<Model<T, ID>> modelSupplier) {
        super(ormTemplate, fromType, join, where, groupBy, having, orderBy, modelSupplier);
        this.forLock = forLock;
        this.selectType = selectType;
        this.distinct = distinct;
        this.selectTemplate = selectTemplate;
        this.limit = limit;
        this.offset = offset;
        this.subquery = subquery;
        this.refType = refType;
        this.pkType = pkType;
        this.fetchPaths = List.copyOf(fetchPaths);
    }

    /**
     * Returns a query builder that does not require a WHERE clause for UPDATE and DELETE queries.
     *
     * <p>This method is used to prevent accidental updates or deletions of all records in a table when a WHERE clause
     * is not provided.</p>
     *
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> unsafe() {
        return this;
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
    QueryBuilder<T, R, ID> copyWith(QueryTemplate queryTemplate,
                                    Class<T> fromType,
                                    List<Join> join,
                                    List<Where> where,
                                    List<TemplateString> groupBy,
                                    List<TemplateString> having,
                                    List<TemplateString> orderBy) {
        return new SelectBuilderImpl<>(queryTemplate, fromType, selectType, distinct, join, where, limit, offset, forLock,
                selectTemplate, groupBy, having, orderBy, subquery, refType, pkType, fetchPaths, modelSupplier);
    }

    /**
     * Returns true to indicate that the query supports joins, false otherwise.
     *
     * @return true if the query supports joins, false otherwise.
     */
    @Override
    protected boolean supportsJoin() {
        return true;
    }

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> distinct() {
        return new SelectBuilderImpl<>(queryTemplate, fromType, selectType, true, join, where, limit, offset, forLock,
                selectTemplate, groupBy, having, orderBy, subquery, refType, pkType, fetchPaths, modelSupplier);
    }

    /**
     * Resolves the references at the specified field paths as part of this query.
     *
     * <p>The paths are validated here rather than at execution, so a path that names something the query cannot
     * resolve is reported where it was written. Paths accumulate across calls and the plan is closed over its prefixes
     * when the statement is built.</p>
     *
     * @param paths the field paths of the references to resolve, relative to the selected type.
     * @return the query builder.
     * @since 1.13
     */
    @Override
    public QueryBuilder<T, R, ID> fetch(List<? extends Navigable<T, ? extends Data>> paths) {
        if (subquery) {
            throw new PersistenceException("Cannot resolve references in a subquery: a subquery selects columns, not records.");
        }
        if (refType != null) {
            throw new PersistenceException("Cannot resolve references for a ref result: the query selects primary keys, and the records they identify are what a ref defers. Select the record itself to resolve references within it.");
        }
        if (selectType != fromType) {
            throw new PersistenceException("Cannot resolve references for %s: the paths name references of %s, which this query selects from but does not select. Select %s itself to resolve references within it."
                    .formatted(selectType.getSimpleName(), fromType.getSimpleName(), fromType.getSimpleName()));
        }
        if (!isNestedSelectOf(selectType)) {
            throw new PersistenceException("Cannot resolve references for %s: the query selects a custom column list, which has no reference to expand. Select the record itself to resolve references within it."
                    .formatted(selectType.getSimpleName()));
        }
        if (paths.isEmpty()) {
            throw new PersistenceException("At least one path must be provided to fetch.");
        }
        //noinspection unchecked
        var selectDataType = (Class<? extends Data>) selectType;
        var combined = new ArrayList<>(fetchPaths);
        for (var path : paths) {
            String fieldPath = path.fieldPath();
            RecordValidation.validateFetchPath(selectDataType, fieldPath);
            if (!combined.contains(fieldPath)) {
                combined.add(fieldPath);
            }
        }
        return new SelectBuilderImpl<>(queryTemplate, fromType, selectType, distinct, join, where, limit, offset, forLock,
                selectTemplate, groupBy, having, orderBy, subquery, refType, pkType, combined, modelSupplier);
    }

    /**
     * Returns whether the select clause is the plain nested select of the given type, the only shape in which a
     * reference occupies a position that can be expanded into the referenced table's columns.
     */
    private boolean isNestedSelectOf(Class<?> type) {
        if (!Data.class.isAssignableFrom(type)) {
            return false;
        }
        if (selectTemplate.values().size() != 1
                || !selectTemplate.fragments().stream().allMatch(String::isEmpty)) {
            return false;
        }
        return switch (selectTemplate.values().getFirst()) {
            case Class<?> selected -> selected == type;
            case Select(var table, var mode) -> table == type && mode == NESTED;
            case null, default -> false;
        };
    }

    /**
     * Executes the query with the cursor columns appended to the select list. The leading columns map to the
     * result type exactly as they do without the cursor columns, so an entity, a projection, a ref and a custom
     * select type all read their cursor values the same way.
     */
    @Override
    @SuppressWarnings("unchecked")
    List<KeyedQuery.Row<R>> getKeyedResultList(List<Metamodel<T, ?>> columns) {
        var parts = new ArrayList<TemplateString>();
        parts.add(selectClause());
        for (var column : columns) {
            parts.add(TemplateString.of(", "));
            parts.add(wrap(column));
        }
        var query = queryTemplate.query(toTemplateString(TemplateString.combine(parts), true));
        if (!(query instanceof KeyedQuery keyed)) {
            throw new PersistenceException("The query template does not support reading cursor columns.");
        }
        Class<?>[] types = columns.stream().map(SelectBuilderImpl::cursorValueType).toArray(Class<?>[]::new);
        if (refType != null) {
            assert pkType != null : "Primary key type must be specified for ref queries.";
            return (List<KeyedQuery.Row<R>>) (List<?>) keyed.getKeyedRefList(refType, pkType, types);
        }
        return keyed.getKeyedResultList(selectType, types);
    }

    /**
     * Returns the type a cursor column's value decodes to. A reference field's column carries the referenced
     * table's key, so the value takes that key's type rather than the field's own.
     */
    private static Class<?> cursorValueType(Metamodel<?, ?> column) {
        var referencedKey = MetamodelFactory.referencedKey(column);
        return referencedKey != null ? referencedKey.fieldType() : column.fieldType();
    }

    /**
     * Returns the select clause: the select template, or the nested select of the result type with its fetch
     * plan when references are resolved in the same statement.
     */
    @SuppressWarnings("unchecked")
    private TemplateString selectClause() {
        // A resolved reference is carried by the fetch element; the compiler collects it from the template before
        // any element renders, so the element's position does not matter and the referenced table's columns take
        // the place its foreign key column would have occupied.
        return fetchPaths.isEmpty()
                ? selectTemplate
                : TemplateString.combine(wrap(select((Class<? extends Data>) selectType, NESTED)), wrap(new Fetch(fetchPaths)));
    }

    private TemplateString toTemplateString() {
        return toTemplateString(selectClause(), true);
    }

    private TemplateString toTemplateString(TemplateString selectClause, boolean withOrderBy) {
        TemplateString template = TemplateString.combine(TemplateString.of("SELECT %s".formatted(distinct ? "DISTINCT " : "")));
        if (queryTemplate.dialect().applyLimitAfterSelect()) {
            if (limit != null && offset == null) {
                template = TemplateString.combine(
                        template,
                        TemplateString.of(queryTemplate.dialect().limit(limit)),
                        TemplateString.of(" "));
            }
        }
        template = TemplateString.combine(template, selectClause, TemplateString.raw("\nFROM \0", from(fromType, true)));
        boolean hasLock = hasLockHint();
        if (hasLock && queryTemplate.dialect().applyLockHintAfterFrom()) {
            template = TemplateString.combine(template, TemplateString.of("\n"), forLock);
        }
        template = appendJoinsAndWhere(template);
        if (!groupBy.isEmpty()) {
            TemplateString groupByClause = groupBy.stream()
                    .reduce((a, b) -> TemplateString.combine(a, TemplateString.of(", "), b))
                    .orElseThrow();
            template = TemplateString.combine(template, TemplateString.of("\nGROUP BY "), groupByClause);
        }
        if (!having.isEmpty()) {
            if (having.size() == 1) {
                template = TemplateString.combine(template, TemplateString.of("\nHAVING "), having.getFirst());
            } else {
                TemplateString havingClause = having.stream()
                        .map(h -> TemplateString.combine(TemplateString.of("("), h, TemplateString.of(")")))
                        .reduce((a, b) -> TemplateString.combine(a, TemplateString.of("\n  AND "), b))
                        .orElseThrow();
                template = TemplateString.combine(template, TemplateString.of("\nHAVING "), havingClause);
            }
        }
        if (withOrderBy && !orderBy.isEmpty()) {
            TemplateString orderByClause = orderBy.stream()
                    .reduce((a, b) -> TemplateString.combine(a, TemplateString.of(", "), b))
                    .orElseThrow();
            template = TemplateString.combine(template, TemplateString.of("\nORDER BY "), orderByClause);
        }
        if (withOrderBy && orderBy.isEmpty() && offset != null) {
            // An offset on an unordered result is refused by some databases; the dialect says what ordering, if
            // any, satisfies them.
            String orderByForOffset = queryTemplate.dialect().orderByForOffset();
            if (orderByForOffset != null) {
                template = TemplateString.combine(template, TemplateString.of("\n"), TemplateString.of(orderByForOffset));
            }
        }
        if (!queryTemplate.dialect().applyLimitAfterSelect()) {
            if (limit != null && offset == null) {
                template = TemplateString.combine(template, TemplateString.of("\n"), TemplateString.of(queryTemplate.dialect().limit(limit)));
            }
        }
        if (limit != null && offset != null) {
            template = TemplateString.combine(template, TemplateString.of("\n"), TemplateString.of(queryTemplate.dialect().limit(offset, limit)));
        } else if (offset != null) {
            template = TemplateString.combine(template, TemplateString.of("\n"), TemplateString.of(queryTemplate.dialect().offset(offset)));
        }
        if (hasLock && !queryTemplate.dialect().applyLockHintAfterFrom()) {
            template = TemplateString.combine(template, TemplateString.of("\n"), forLock);
        }
        return template;
    }

    @Override
    public TemplateString getSubquery() {
        return toTemplateString();
    }

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> offset(int offset) {
        return new SelectBuilderImpl<>(queryTemplate, fromType, selectType, distinct, join, where, limit, offset, forLock,
                selectTemplate, groupBy, having, orderBy, subquery, refType, pkType, fetchPaths, modelSupplier);
    }

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> limit(int limit) {
        return new SelectBuilderImpl<>(queryTemplate, fromType, selectType, distinct, join, where, limit, offset, forLock,
                selectTemplate, groupBy, having, orderBy, subquery, refType, pkType, fetchPaths, modelSupplier);
    }

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> forShare() {
        return forLock(TemplateString.of(queryTemplate.dialect().forShareLockHint()));
    }

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> forUpdate() {
        return forLock(TemplateString.of(queryTemplate.dialect().forUpdateLockHint()));
    }

    /**
     * Locks the selected rows using a custom lock mode.
     *
     * <p><strong>Note:</strong> This method results in non-portable code, as the lock mode is specific to the
     * underlying database.</p>
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> forLock(TemplateString template) {
        return new SelectBuilderImpl<>(queryTemplate, fromType, selectType, distinct, join, where, limit, offset,
                template, selectTemplate, groupBy, having, orderBy, subquery, refType, pkType, fetchPaths, modelSupplier);
    }

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    @Override
    public Query build() {
        if (subquery) {
            throw new PersistenceException("Cannot build a query from a subquery.");
        }
        return queryTemplate.query(toTemplateString());
    }

    /**
     * Compiles this query into a reusable plan.
     *
     * @return a reusable plan for this query.
     */
    @Override
    public QueryPlan plan() {
        if (subquery) {
            throw new PersistenceException("Cannot compile a plan from a subquery.");
        }
        return queryTemplate.plan(toTemplateString());
    }

    /**
     * Returns whether a lock hint has been set via {@code forShare()}, {@code forUpdate()} or {@code forLock()}.
     */
    private boolean hasLockHint() {
        return forLock.fragments().size() == 1 && !forLock.fragments().getFirst().isEmpty();
    }

    /**
     * Returns the record type whose column set forms the select clause, or {@code null} when the select clause is a
     * custom template. A custom template is opaque: it may aggregate, so the number of rows it produces cannot be
     * derived from the FROM clause alone.
     */
    @Nullable
    private Class<? extends Data> selectedRecordType() {
        if (refType != null) {
            return refType;
        }
        if (selectTemplate.values().size() != 1
                || !selectTemplate.fragments().stream().allMatch(String::isEmpty)) {
            return null;
        }
        return switch (selectTemplate.values().getFirst()) {
            case Class<?> selected when Data.class.isAssignableFrom(selected) -> selected.asSubclass(Data.class);
            case Select(var table, var ignore) -> table;
            case null, default -> null;
        };
    }

    /**
     * Returns the count query for this builder, or {@code null} when no count query can express this query's result
     * count and the results must be fetched and counted instead.
     *
     * <p>A select of a record's column set without row-shaping clauses counts the rows of its own FROM/JOIN/WHERE
     * shape, so the select clause is replaced by {@code COUNT(*)}. Row-shaping clauses (DISTINCT, GROUP BY, HAVING,
     * limit and offset) and custom select clauses determine what a row is, so those queries are counted as a derived
     * table instead. Inside the derived table, a record column set is replaced: every select
     * mode includes the record's primary key and the primary key determines the remaining columns, so a DISTINCT
     * select counts distinct primary keys, and any other select counts a constant. Both replacements keep the derived
     * table free of duplicate column names, which not every database accepts. ORDER BY does not affect a count and is
     * omitted, except inside a derived table with a limit or offset, where dialects rendering OFFSET/FETCH require
     * it.</p>
     */
    @Nullable
    private TemplateString toCountTemplateString() {
        Class<? extends Data> recordType = selectedRecordType();
        boolean rowShape = distinct || limit != null || offset != null
                || !groupBy.isEmpty() || !having.isEmpty();
        if (recordType != null && !rowShape) {
            return toTemplateString(TemplateString.of("COUNT(*)"), false);
        }
        TemplateString innerSelect;
        if (recordType == null) {
            innerSelect = selectTemplate;
        } else if (distinct) {
            Class<?> primaryKeyType = queryTemplate.model(recordType, false).primaryKeyType();
            if (primaryKeyType == Void.class) {
                // Without a primary key there is no reduced column set that preserves the distinct row identity.
                return null;
            }
            innerSelect = wrap(select(recordType, PK));
        } else {
            innerSelect = TemplateString.of("1");
        }
        boolean withOrderBy = limit != null || offset != null;
        return TemplateString.combine(
                TemplateString.of("SELECT COUNT(*)\nFROM (\n"),
                toTemplateString(innerSelect, withOrderBy),
                TemplateString.of("\n) c"));
    }

    /**
     * Returns the number of results of this query by executing a dedicated count query derived from this builder.
     *
     * <p>Queries that lock rows fetch and count the results instead, so the requested locks are acquired, as does the
     * rare shape no count query can express (a DISTINCT select of a record without a primary key).</p>
     *
     * @return the total number of results of this query as a long value.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     */
    @Override
    public long getResultCount() {
        if (subquery) {
            throw new PersistenceException("Cannot build a query from a subquery.");
        }
        if (hasLockHint()) {
            return super.getResultCount();
        }
        TemplateString countTemplate = toCountTemplateString();
        if (countTemplate == null) {
            return super.getResultCount();
        }
        return queryTemplate.query(countTemplate).withoutFetchSize().getSingleResult(Long.class);
    }

    /**
     * Executes the query and returns a stream of results.
     *
     * <p>The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @return a stream of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     */
    @Override
    public Stream<R> getResultStream() {
        return getResultStream(build());
    }

    /**
     * Executes without the fetch-size hint: eagerly consumed results gain nothing from cursor-based fetching, and
     * on dialects where cursors require a transaction this avoids wrapping auto-commit queries in a transaction.
     */
    @Override
    protected Stream<R> getMaterializedResultStream() {
        return getResultStream(build().withoutFetchSize());
    }

    private Stream<R> getResultStream(Query query) {
        if (refType != null) {
            assert pkType != null : "Primary key type must be specified for ref queries.";
            //noinspection unchecked
            return (Stream<R>) query.getRefStream(refType, pkType);
        }
        return query.getResultStream(selectType);
    }

    @Override
    protected R singleResultInternal() {
        if (refType != null) {
            // Ref results are produced through getRefStream, which the single-row query path does not cover.
            return super.singleResultInternal();
        }
        return build().withoutFetchSize().getSingleResult(selectType);
    }

    @Override
    protected Optional<R> optionalResultInternal() {
        if (refType != null) {
            // Ref results are produced through getRefStream, which the single-row query path does not cover.
            return super.optionalResultInternal();
        }
        return build().withoutFetchSize().getOptionalResult(selectType);
    }
}
