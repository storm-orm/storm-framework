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
import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.Templates.alias;
import static st.orm.core.template.Templates.from;
import static st.orm.core.template.Templates.param;
import static st.orm.core.template.Templates.subquery;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlFunction;
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.Query;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.UpdateTarget;
import st.orm.core.template.impl.Elements.Where;

/**
 * A query builder for UPDATE queries.
 *
 * <p>The builder sets the columns named through {@code set} on the rows its conditions select, and increments the
 * version column of a versioned entity alongside, so a later update of an entity read before this statement fails its
 * optimistic lock check. It reaches rows through other tables as {@link DeleteBuilderImpl} does, in the form
 * {@link st.orm.core.template.SqlDialect#writeJoinSyntax()} states: in the statement itself where the dialect joins
 * tables into an UPDATE, and through a subquery matching the primary key elsewhere.</p>
 *
 * @param <T> the type of the table being updated.
 * @param <ID> the type of the primary key.
 * @since 1.16
 */
public class UpdateBuilderImpl<T extends Data, ID> extends QueryBuilderImpl<T, Object, ID> {

    /**
     * One assignment of the SET clause: the columns a field maps to with the value each column takes, or the template
     * computing the value of the single column the field maps to.
     */
    private record Assignment(Metamodel<?, ?> field,
                              List<Column> columns,
                              List<@Nullable Object> values,
                              @Nullable TemplateString expression) {}

    private final List<Assignment> assignments;
    private final boolean unsafe;

    public UpdateBuilderImpl(QueryTemplate queryTemplate, Class<T> fromType, Supplier<Model<T, ID>> modelSupplier) {
        this(queryTemplate, fromType, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, modelSupplier, List.of(), false);
    }

    private UpdateBuilderImpl(QueryTemplate queryTemplate,
                              Class<T> fromType,
                              List<Join> join,
                              List<Where> where,
                              List<TemplateString> groupBy,
                              List<TemplateString> having,
                              List<TemplateString> orderBy,
                              List<String> hints,
                              int orderFirstKeys,
                              Supplier<Model<T, ID>> modelSupplier,
                              List<Assignment> assignments,
                              boolean unsafe) {
        super(queryTemplate, fromType, join, where, groupBy, having, orderBy, hints, orderFirstKeys, modelSupplier);
        this.assignments = List.copyOf(assignments);
        this.unsafe = unsafe;
    }

    /**
     * Returns a query builder that allows UPDATE and DELETE queries without a WHERE clause.
     *
     * <p>By default, Storm rejects UPDATE and DELETE queries that lack a WHERE clause, throwing a
     * {@link PersistenceException}. Call this method to disable that check when you intentionally want to affect all
     * rows in the table.</p>
     */
    @Override
    public QueryBuilder<T, Object, ID> unsafe() {
        return new UpdateBuilderImpl<>(queryTemplate, fromType, join, where, groupBy, having, orderBy, hints, orderFirstKeys, modelSupplier, assignments, true);
    }

    @Override
    QueryBuilder<T, Object, ID> copyWith(QueryTemplate queryTemplate,
                                         Class<T> fromType,
                                         List<Join> join,
                                         List<Where> where,
                                         List<TemplateString> groupBy,
                                         List<TemplateString> having,
                                         List<TemplateString> orderBy,
                                         List<String> hints,
                                         int orderFirstKeys) {
        return new UpdateBuilderImpl<>(queryTemplate, fromType, join, where, groupBy, having, orderBy, hints, orderFirstKeys, modelSupplier, assignments, unsafe);
    }

    private QueryBuilder<T, Object, ID> with(Assignment assignment) {
        for (var existing : assignments) {
            if (existing.columns().stream().anyMatch(assignment.columns()::contains)) {
                throw new PersistenceException("Field %s is set twice in the same UPDATE query.".formatted(describe(assignment.field())));
            }
        }
        var copy = new ArrayList<>(assignments);
        copy.add(assignment);
        return new UpdateBuilderImpl<>(queryTemplate, fromType, join, where, groupBy, having, orderBy, hints, orderFirstKeys, modelSupplier, copy, unsafe);
    }

    /**
     * Sets the columns of the field to the given value.
     *
     * @param field the field to set.
     * @param value the value, or {@code null} to set the field's columns to {@code NULL}.
     * @return the query builder.
     */
    @Override
    public <V> QueryBuilder<T, Object, ID> set(Metamodel<? extends T, V> field, @Nullable V value) {
        return with(assignValue(field, value));
    }

    /**
     * Sets the foreign key columns of the field to the key of the record the ref refers to.
     *
     * @param field the foreign key field to set.
     * @param ref the ref to the record the foreign key refers to.
     * @return the query builder.
     */
    @Override
    public <V extends Data> QueryBuilder<T, Object, ID> set(Metamodel<? extends T, V> field, Ref<V> ref) {
        requireNonNull(ref, "ref");
        return with(assignValue(field, ref));
    }

    /**
     * Sets the single column of the field to the value the template computes.
     *
     * @param field the field to set.
     * @param template the SQL expression computing the value.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, Object, ID> set(Metamodel<? extends T, ?> field, TemplateString template) {
        requireNonNull(template, "template");
        var columns = columnsOf(field);
        if (columns.size() != 1) {
            throw new PersistenceException("Field %s maps to %d columns, and an expression sets one: set each column on its own, or set the field to a value."
                    .formatted(describe(field), columns.size()));
        }
        return with(new Assignment(field, columns, List.of(), template));
    }

    private Assignment assignValue(Metamodel<? extends T, ?> field, @Nullable Object value) {
        var columns = columnsOf(field);
        var values = new LinkedHashMap<Column, Object>();
        if (value != null) {
            try {
                //noinspection unchecked
                modelSupplier.get().forEachValue((Metamodel<T, ?>) field, value, values::put);
            } catch (SqlTemplateException e) {
                throw new PersistenceException("Cannot set field %s: %s".formatted(describe(field), e.getMessage()), e);
            }
        }
        List<@Nullable Object> ordered = new ArrayList<>(columns.size());
        for (var column : columns) {
            Object columnValue = values.get(column);
            if (columnValue == null && !column.nullable()) {
                throw new PersistenceException(value == null
                        ? "Cannot set field %s to null: column %s is not nullable.".formatted(describe(field), column.name())
                        : "Cannot set field %s: the value leaves column %s, which is not nullable, without a value.".formatted(describe(field), column.name()));
            }
            ordered.add(columnValue);
        }
        return new Assignment(field, columns, Collections.unmodifiableList(ordered), null);
    }

    /**
     * Returns the columns the field maps to on the updated table, refusing a field an UPDATE cannot set.
     */
    private List<Column> columnsOf(Metamodel<? extends T, ?> field) {
        requireNonNull(field, "field");
        if (SqlFunction.isCall(field)) {
            throw new PersistenceException("Cannot set %s: an UPDATE sets a column, not a function of one.".formatted(field));
        }
        if (field.root() != fromType) {
            throw new PersistenceException("Cannot set %s: an UPDATE of %s sets the columns of %s, not of %s."
                    .formatted(describe(field), fromType.getSimpleName(), fromType.getSimpleName(), field.root().getSimpleName()));
        }
        if (!field.path().isEmpty()) {
            throw new PersistenceException("Cannot set %s: an UPDATE of %s sets the columns of %s itself, not of a related table."
                    .formatted(describe(field), fromType.getSimpleName(), fromType.getSimpleName()));
        }
        if (!field.isColumn() && !field.isInline()) {
            throw new PersistenceException("Cannot set %s: the path names the table, not a column.".formatted(fromType.getSimpleName()));
        }
        refuseJoinedInheritance("UPDATE");
        var model = modelSupplier.get();
        List<Column> columns;
        try {
            columns = model.getColumns(field);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Cannot set %s: %s".formatted(describe(field), e.getMessage()), e);
        }
        for (var column : columns) {
            if (column.primaryKey()) {
                throw new PersistenceException("Cannot set %s: column %s is part of the primary key.".formatted(describe(field), column.name()));
            }
            if (column.version()) {
                throw new PersistenceException("Cannot set %s: it is the version field, which the UPDATE increments itself.".formatted(describe(field)));
            }
            if (!column.updatable()) {
                throw new PersistenceException("Cannot set %s: column %s is not updatable.".formatted(describe(field), column.name()));
            }
        }
        return columns;
    }

    private String describe(Metamodel<?, ?> field) {
        return "%s.%s".formatted(field.root().getSimpleName(), field.fieldPath());
    }

    /**
     * Joins are supported on every dialect: in the statement itself where the dialect joins tables into an UPDATE,
     * and in the subquery that matches the rows elsewhere.
     */
    @Override
    protected boolean supportsJoin() {
        return true;
    }

    /**
     * Some databases refuse a subquery reading the table an UPDATE updates, so a semi-join matches the rows through
     * their own columns in the statement itself.
     */
    @Override
    protected boolean subqueriesMayReadTable() {
        return false;
    }

    @Override
    public QueryBuilder<T, Object, ID> distinct() {
        throw new PersistenceException("Cannot use DISTINCT in an UPDATE query.");
    }

    @Override
    public QueryBuilder<T, Object, ID> offset(int offset) {
        throw new PersistenceException("Cannot use OFFSET in an UPDATE query.");
    }

    @Override
    public QueryBuilder<T, Object, ID> limit(int limit) {
        throw new PersistenceException("Cannot use LIMIT in an UPDATE query.");
    }

    @Override
    public QueryBuilder<T, Object, ID> forShare() {
        throw new PersistenceException("Cannot use FOR SHARE in an UPDATE query.");
    }

    @Override
    public QueryBuilder<T, Object, ID> forUpdate() {
        throw new PersistenceException("Cannot use FOR UPDATE in an UPDATE query.");
    }

    @Override
    public QueryBuilder<T, Object, ID> forLock(TemplateString template) {
        throw new PersistenceException("Cannot use FOR LOCK in an UPDATE query.");
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

    /**
     * Renders the assignments of the SET clause, with the version increment of a versioned entity last. The columns
     * carry the alias of the updated table where the statement joins, and stand unqualified where it matches its rows
     * through a subquery and names the updated table alone.
     */
    private TemplateString getSetTemplate(boolean qualified) {
        if (assignments.isEmpty()) {
            throw new PersistenceException("An UPDATE query sets at least one column: call set(...) before running it.");
        }
        var dialect = queryTemplate.dialect();
        TemplateString prefix = qualified ? TemplateString.combine(wrap(alias(fromType)), TemplateString.of(".")) : TemplateString.EMPTY;
        var parts = new ArrayList<TemplateString>();
        for (var assignment : assignments) {
            if (assignment.expression() != null) {
                parts.add(TemplateString.combine(prefix, TemplateString.of(assignment.columns().getFirst().qualifiedName(dialect) + " = "), assignment.expression()));
                continue;
            }
            for (int i = 0; i < assignment.columns().size(); i++) {
                parts.add(TemplateString.combine(prefix, TemplateString.of(assignment.columns().get(i).qualifiedName(dialect) + " = "),
                        wrap(param(assignment.values().get(i)))));
            }
        }
        for (var column : modelSupplier.get().declaredColumns()) {
            if (column.version()) {
                var increment = versionIncrement(column, prefix);
                if (increment != null) {
                    parts.add(TemplateString.combine(prefix, TemplateString.of(column.qualifiedName(dialect) + " = "), increment));
                }
            }
        }
        var set = parts.getFirst();
        for (int i = 1; i < parts.size(); i++) {
            set = TemplateString.combine(set, TemplateString.of(", "), parts.get(i));
        }
        return set;
    }

    /**
     * Returns the value a version column takes in the UPDATE: one more for a counter, the current timestamp for a
     * timestamp, as an update of an entity writes it; {@code null} for a version of another type, which the update
     * leaves as it is.
     */
    private @Nullable TemplateString versionIncrement(Column column, TemplateString prefix) {
        var dialect = queryTemplate.dialect();
        return switch (column.type()) {
            case Class<?> c when
                    Integer.TYPE.isAssignableFrom(c)
                            || Long.TYPE.isAssignableFrom(c)
                            || Integer.class.isAssignableFrom(c)
                            || Long.class.isAssignableFrom(c)
                            || BigInteger.class.isAssignableFrom(c) ->
                    TemplateString.combine(prefix, TemplateString.of(column.qualifiedName(dialect) + " + 1"));
            case Class<?> c when
                    Instant.class.isAssignableFrom(c)
                            || Date.class.isAssignableFrom(c)
                            || Calendar.class.isAssignableFrom(c)
                            || Timestamp.class.isAssignableFrom(c) -> TemplateString.of(dialect.currentTimestamp());
            default -> null;
        };
    }

    private TemplateString toTemplateString(StatementFacts.Collector facts) {
        refuseJoinedInheritance("UPDATE");
        TemplateString template = switch (queryTemplate.dialect().writeJoinSyntax()) {
            // UPDATE pet p INNER JOIN owner o ON ... SET p.name = ? WHERE o.last_name = ?. The joins render after the
            // FROM element wherever they sit in the template, so they are appended after the SET clause.
            case MULTI_TABLE -> appendJoinsAndWhere(TemplateString.combine(
                    TemplateString.of("UPDATE" + hintAfterKeyword() + " "),
                    wrap(new UpdateTarget(fromType)),
                    wrap(from(fromType, true)),
                    TemplateString.of("\nSET "),
                    getSetTemplate(true)), facts);
            // UPDATE p SET p.name = ? FROM pet p INNER JOIN owner o ON ... WHERE o.last_name = ?.
            case FROM_CLAUSE -> appendJoinsAndWhere(TemplateString.combine(
                    TemplateString.of("UPDATE" + hintAfterKeyword() + " "),
                    wrap(new UpdateTarget(fromType)),
                    wrap(alias(fromType)),
                    TemplateString.of("\nSET "),
                    getSetTemplate(true),
                    TemplateString.of("\nFROM "),
                    wrap(from(fromType, true))), facts);
            // UPDATE pet SET name = ? WHERE (id) IN (SELECT p.id FROM pet p INNER JOIN owner o ON ... WHERE ...).
            case SUBQUERY -> {
                var select = appendJoinsAndWhere(TemplateString.combine(TemplateString.of("SELECT "), getPrimaryKeyTemplate(true),
                        TemplateString.raw("\nFROM \0", from(fromType, true))), facts);
                yield TemplateString.combine(
                        TemplateString.raw("UPDATE" + hintAfterKeyword() + " \0\nSET ", fromType),
                        getSetTemplate(false),
                        TemplateString.of("\nWHERE "), discriminatorCondition(),
                        TemplateString.of("("), getPrimaryKeyTemplate(false), TemplateString.of(") IN ("),
                        wrap(subquery(select, false)), TemplateString.of(")"));
            }
        };
        if (!hasCondition()) {
            facts.unconditional();
        }
        String hint = hintAtEnd();
        return hint.isEmpty() ? template : TemplateString.combine(template, TemplateString.of(hint));
    }

    @Override
    public TemplateString getSubquery() {
        throw new PersistenceException("Cannot use an UPDATE query as subquery.");
    }

    /**
     * Builds the query based on the current state of the query builder.
     *
     * <p>A query without a condition refuses to run unless {@link #unsafe()} was called on the builder, or
     * {@link Query#unsafe()} on the query.</p>
     *
     * @return the constructed query.
     * @throws PersistenceException if the query sets no column.
     */
    @Override
    public Query build() {
        var facts = new StatementFacts.Collector();
        var template = toTemplateString(facts);
        Query query = QueryTemplateImpl.query(queryTemplate, template, facts.build());
        if (unsafe || hasCondition()) {
            query = query.unsafe();
        }
        return query;
    }

    /**
     * Compiles this query into a reusable plan.
     *
     * <p>The queries of a plan without a condition refuse to run unless {@link #unsafe()} was called on the builder,
     * or {@link Query#unsafe()} on the query.</p>
     *
     * @return a reusable plan for this query.
     * @throws PersistenceException if the query sets no column.
     */
    @Override
    public QueryPlan plan() {
        var facts = new StatementFacts.Collector();
        var template = toTemplateString(facts);
        var plan = QueryTemplateImpl.plan(queryTemplate, template, facts.build());
        if (!unsafe && !hasCondition()) {
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
    List<KeyedQuery.Row<Object>> getKeyedResultList(List<Metamodel<T, ?>> columns, List<TemplateString> expressions) {
        throw new PersistenceException("An update query cannot be scrolled.");
    }

    @Override
    public Stream<Object> getResultStream() {
        throw new PersistenceException("Cannot get a result stream from an UPDATE query.");
    }
}
