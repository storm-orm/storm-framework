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
import static st.orm.Operator.EQUALS;
import static st.orm.SelectMode.NESTED;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.ResolveScope;
import st.orm.SelectMode;
import st.orm.core.template.Column;
import st.orm.core.template.TemplateString;
import st.orm.mapping.RecordField;

/**
 * The typed placeholders a SQL template is compiled from.
 *
 * <p>Anything dynamic in a template is an element. Values interpolated into a template resolve to elements, and the
 * query builder contributes elements directly. Each element is compiled to SQL text by its processor (see
 * {@code ElementRouter}) when the query is built, so rendering can use the model, the resolved aliases, and the SQL
 * dialect, none of which exist when the query is written.</p>
 */
public final class Elements {

    private Elements() {
    }

    /**
     * Renders the SELECT column list for a table.
     *
     * <p>The mode picks the column set: {@link SelectMode#PK} selects the identifying columns only,
     * {@link SelectMode#DECLARED} the columns declared on the table itself, and {@link SelectMode#NESTED} the full
     * hierarchical column set needed to materialize the record, expanding foreign key relationships.</p>
     *
     * <p>The references a statement resolves as part of its select list are not part of this element: they are
     * carried by {@link Fetch} and read from the compiler, so the select element states only what a template author
     * means — which table, which column set.</p>
     */
    public record Select(Class<? extends Data> table, SelectMode mode) implements Element {
        public Select {
            requireNonNull(table, "table");
            requireNonNull(mode, "mode");
        }
        public Select(Class<? extends Data> table) {
            this(table, NESTED);
        }
    }

    /**
     * The references the statement resolves as part of its select list, named by field path relative to the
     * selected record.
     *
     * <p>Renders nothing. The compiler collects this element before compiling, so its position in the template does
     * not matter: the select list rendering and the row mapper read the plan from the compiler. Paths are
     * prefix-closed on construction, so plans that resolve the same references compare equal and share a compiled
     * statement, however they were spelled.</p>
     *
     * @since 1.13
     */
    public record Fetch(Collection<String> paths) implements Element {
        public Fetch {
            requireNonNull(paths, "paths");
            paths = FetchPlan.of(paths).paths();
        }
    }

    /**
     * Names fields whose values are read from each row alongside the mapped result, without widening the select
     * list for a field it already carries.
     *
     * <p>The element follows the select clause. A column the select list already carries is read where it is; a
     * column it lacks is appended after it, so the statement returns every value exactly once and the leading
     * columns map to the result type as they would without the cursor. Where each value is found is reported
     * through {@link st.orm.core.template.Sql#cursorColumns()}. A multi-column path (an inline record) expands to
     * each of its columns, in model column order. A scroll window reads its sort and key values this way.</p>
     *
     * @param fields the fields to read, in the order their values are wanted.
     * @since 1.14
     */
    public record Cursor(List<Metamodel<?, ?>> fields) implements Element {
        public Cursor {
            fields = List.copyOf(requireNonNull(fields, "fields"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("A cursor names at least one field.");
            }
        }
    }

    /**
     * Renders the INSERT target: the table name and its insertable column list.
     *
     * <p>{@code ignoreAutoGenerate} includes auto-generated primary key columns, so caller-supplied keys are written
     * verbatim. {@code returningKeys} makes the statement hand back the generated keys, using the mechanism the
     * dialect provides.</p>
     */
    public record Insert(Class<? extends Data> table, boolean ignoreAutoGenerate,
                         boolean returningKeys) implements Element {
        public Insert(Class<? extends Data> table) {
            this(table, false, false);
        }
        public Insert(Class<? extends Data> table, boolean ignoreAutoGenerate) {
            this(table, ignoreAutoGenerate, false);
        }
        public Insert {
            requireNonNull(table, "table");
        }
    }

    /**
     * Renders the VALUES rows of an INSERT: one row per record, or a single placeholder row bound through
     * {@code bindVars} for batch execution.
     *
     * <p>{@code ignoreAutoGenerate} mirrors {@link Insert}: the supplied primary key values are bound instead of
     * omitted.</p>
     */
    public record Values(@Nullable Iterable<? extends Data> records, @Nullable BindVars bindVars, boolean ignoreAutoGenerate) implements Element {
        public Values(@Nullable Iterable<? extends Data> records, @Nullable BindVars bindVars) {
            this(records, bindVars, false);
        }
    }

    /**
     * Renders the UPDATE target: the table name under the given alias. An empty alias lets the template derive one.
     */
    public record Update(Class<? extends Data> table, String alias) implements Element {
        public Update {
            requireNonNull(table, "table");
            requireNonNull(alias, "alias");
        }
        public Update(Class<? extends Data> table) {
            this(table, "");
        }
    }

    /**
     * Renders the SET clause of an UPDATE: assignments taken from the record's columns, or placeholder assignments
     * bound through {@code bindVars} for batch execution. A non-empty {@code fields} restricts the assignments to
     * those columns (a partial update).
     */
    public record Set(@Nullable Data record, @Nullable BindVars bindVars, Collection<Metamodel<?, ?>> fields) implements Element {
        public Set {
            fields = java.util.Set.copyOf(fields);
        }
    }

    /** A condition contributed to a WHERE or HAVING clause: either value-based or a template fragment. */
    public sealed interface Expression {}

    /**
     * A value comparison: the path compared against the object using the operator. The object may be a value, a
     * collection of values, a record, a reference, or a primary key; a multi-column path expands component-wise.
     * When the metamodel is {@code null}, the object is matched against the table's primary key.
     */
    public record ObjectExpression(@Nullable Metamodel<?, ?> metamodel, Operator operator, Object object) implements Expression {
        public ObjectExpression(Operator operator, Object object) {
            this(null, operator, object);
        }
        public ObjectExpression(Object object) {
            this(null, EQUALS, object);
        }
        public ObjectExpression {
            requireNonNull(object, "object");
            requireNonNull(operator, "operator");
        }
    }
    /** A condition written as a template fragment, rendered with its interpolations in place. */
    public record TemplateExpression(TemplateString template) implements Expression {}

    /**
     * Renders a WHERE condition from the expression, or from {@code bindVars} placeholders for batch execution.
     *
     * <p>{@code bindVarsKey} compiles the bind-vars condition for a specific key, so a raw key value can supply
     * every column; query plans use this to bind key lookups without a full record.</p>
     */
    public record Where(@Nullable Expression expression, @Nullable BindVars bindVars,
                        @Nullable Metamodel<?, ?> bindVarsKey) implements Element {
        public Where(@Nullable Expression expression, @Nullable BindVars bindVars) {
            this(expression, bindVars, null);
        }
    }

    /**
     * Renders the DELETE target: the table name under the given alias. An empty alias lets the template derive one.
     */
    public record Delete(Class<? extends Data> table, String alias) implements Element {
        public Delete {
            requireNonNull(table, "table");
            requireNonNull(alias, "alias");
        }
        public Delete(Class<? extends Data> table) {
            this(table, "");
        }
    }

    /** What a FROM clause or join reads from: a mapped table, or a template such as a subquery. */
    public sealed interface Source {}
    public record TableSource(Class<? extends Data> table) implements Source {}
    public record TemplateSource(TemplateString template) implements Source {}

    /** What a join joins onto: a table whose join condition is derived, or a template supplying the ON clause. */
    public sealed interface Target {}

    /**
     * @param table the table to join against.
     * @param field the resolved foreign key field for graph-derived joins, or {@code null} when
     *              the field must be resolved from the table types.
     */
    public record TableTarget(Class<? extends Data> table, @Nullable RecordField field) implements Target {
        public TableTarget(Class<? extends Data> table) {
            this(table, null);
        }
    }
    /** An ON condition supplied as a template fragment. */
    public record TemplateTarget(TemplateString template) implements Target {}

    /**
     * Renders the FROM clause: the source under the given alias (empty derives one). With {@code autoJoin}, the
     * root table's foreign key graph is expanded into derived joins, so the statement can select and reference the
     * related tables.
     */
    public record From(Source source, String alias, boolean autoJoin) implements Element {
        public From {
            requireNonNull(source, "source");
            requireNonNull(alias, "alias");
        }
        public From(Class<? extends Data> table, boolean autoJoin) {
            this(new TableSource(table), "", autoJoin);
        }
        public From(TemplateString template) {
            this(new TemplateSource(template), "", false);
        }
    }

    /**
     * Renders the qualified table name, followed by the alias when one is given. Used to name a table verbatim
     * inside a template, for example in a custom join or correlation.
     */
    public record Table(Class<? extends Data> table, String alias) implements Element {
        public Table {
            requireNonNull(table, "table");
            requireNonNull(alias, "alias");
        }
        public Table(Class<? extends Data> table) {
            this(table, "");
        }
    }

    /**
     * Renders the alias the table resolved to, looked up by type within the given scope. The scope selects where
     * the lookup may resolve: the current template, the enclosing template of a correlated subquery, or both.
     */
    public record Alias(Class<? extends Data> table, ResolveScope scope) implements Element {
        public Alias {
            requireNonNull(table, "table");
            requireNonNull(scope, "scope");
        }
    }

    /**
     * Renders exactly one column: the alias-qualified column the path resolves to, resolved like a predicate would
     * resolve it, so a foreign key names its foreign key column on the referencing table. A bare metamodel
     * interpolated into a template becomes this element.
     *
     * <p>A path that resolves to more than one column is rejected: this placeholder may sit in arbitrary SQL, where
     * splicing in a column list would produce broken statements. List contexts use {@link Columns} instead.</p>
     */
    public record Column(Metamodel<?, ?> field, ResolveScope scope) implements Element {
        public Column {
            requireNonNull(field, "field");
            requireNonNull(scope, "scope");
        }
    }

    /**
     * Renders every column the metamodel resolves to, comma-separated, for list contexts such as GROUP BY and
     * ORDER BY.
     *
     * <p>A single-column path renders exactly like {@link Column}. A multi-column path (a foreign key to a table
     * with a compound primary key, or an inline record) expands to each of its columns. Resolution matches
     * predicate resolution: the columns are the ones the metamodel resolves to on the table that holds them, so a
     * foreign key expands to its foreign key column(s) on the referencing table without joining the referenced
     * table.</p>
     *
     * <p>A single element carries every path the clause renders at that position, so a path the statement
     * requires but the caller did not write can be added where the grouping is resolved.</p>
     *
     * <p>{@code clause} states where the columns land and, for ORDER BY, in which direction. It carries the
     * direction rather than a separate flag because sort direction is meaningless in a GROUP BY, and because the
     * two clauses resolve a key reached through a foreign key differently: see {@link Clause#GROUP_BY}.</p>
     *
     * @since 1.13
     */
    public record Columns(List<Metamodel<?, ?>> fields, ResolveScope scope, Clause clause) implements Element {
        public Columns {
            fields = List.copyOf(requireNonNull(fields, "fields"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("Columns requires at least one path.");
            }
            requireNonNull(scope, "scope");
            requireNonNull(clause, "clause");
        }
    }

    /**
     * The clause a {@link Columns} element renders into, and for ORDER BY the sort direction.
     *
     * @since 1.14
     */
    public enum Clause {
        /**
         * GROUP BY. A path naming the primary key of a table reached through a foreign key resolves to the
         * referenced table's own key column when that table is part of the query, instead of collapsing to the
         * foreign key column on the referencing table.
         *
         * <p>The two columns hold the same value, guaranteed by the foreign key constraint, so which one is named
         * is a matter of the statement the database will accept. Grouping is where that matters: functional
         * dependency is resolved syntactically and per table, so a statement that projects the referenced table's
         * columns while grouping by the referencing table's foreign key column is rejected by every dialect that
         * enforces the rule, even though the grouping determines exactly one row. Naming the referenced table's key
         * costs nothing, because a query that projects its columns has already joined it.</p>
         */
        GROUP_BY,

        /**
         * ORDER BY, ascending. Ordering places no requirement on which of two equal columns is named, so the
         * foreign key column on the referencing table is used, sparing a join where the referenced table is not
         * otherwise part of the query.
         */
        ORDER_BY_ASCENDING,

        /**
         * ORDER BY, descending: as {@link #ORDER_BY_ASCENDING}, with every expanded column followed by
         * {@code DESC}.
         */
        ORDER_BY_DESCENDING;

        /**
         * Returns whether this clause sorts descending.
         */
        public boolean isDescending() {
            return this == ORDER_BY_DESCENDING;
        }
    }

    /**
     * A bind parameter: positional ({@code ?}) when unnamed, named ({@code :name}) otherwise. Holds the
     * database-ready value; conversions are applied when the element is created.
     */
    public record Param(@Nullable String name, @Nullable Object dbValue) implements Element {
        public Param(@Nullable String name, @Nullable Object value, Function<Object, ?> converter) {
            this(name, requireNonNull(converter, "converter").apply(value));
        }

        @Override
        public String toString() {
            return name == null ? "?" : ":%s".formatted(name);
        }
    }

    /**
     * One bind-variable slot in a batch template: the extractor reads the value from each record at execution time,
     * so the same compiled statement serves every record in the batch.
     */
    public record BindVar(BindVars bindVars, Function<Data, ?> extractor) implements Element {
        public BindVar {
            requireNonNull(bindVars, "bindVars");
            requireNonNull(extractor, "extractor");
        }
    }

    /**
     * Renders a nested template as a subquery. With {@code correlate}, the subquery may resolve aliases from the
     * enclosing template; without it, the subquery is self-contained.
     */
    public record Subquery(TemplateString template, boolean correlate) implements Element {}

    /** Raw SQL rendered verbatim, bypassing the template's safety checks. */
    public record Unsafe(String sql) implements Element {
        public Unsafe {
            requireNonNull(sql, "sql");
        }
    }
}
