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

import static java.util.Optional.empty;
import static java.util.stream.Collectors.joining;
import static st.orm.ResolveScope.INNER;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.findRecordFields;
import static st.orm.core.template.impl.RecordReflection.findRecordFieldsByTable;
import static st.orm.core.template.impl.RecordReflection.getFkFields;
import static st.orm.core.template.impl.RecordReflection.getForeignKeys;
import static st.orm.core.template.impl.RecordReflection.getPrimaryKeys;
import static st.orm.core.template.impl.RecordReflection.getTableName;
import static st.orm.core.template.impl.RecordReflection.isTableJoinCandidate;
import static st.orm.core.template.impl.RecordValidation.validateDataType;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.SqlTemplateException;
import st.orm.core.template.impl.Elements.TableSource;
import st.orm.core.template.impl.Elements.TableTarget;
import st.orm.core.template.impl.Elements.TemplateSource;
import st.orm.core.template.impl.Elements.TemplateTarget;
import st.orm.mapping.RecordField;

final class JoinProcessor implements ElementProcessor<Join> {

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
     * @param join the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     */
    @Override
    public Object getCompilationKey(Join join) {
        if (join.source() instanceof TemplateSource || join.target() instanceof TemplateTarget) {
            return null;
        }
        return join;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Join, TemplateBinder, BindHint)}.</p>
     *
     * @param join the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    @Override
    public CompiledElement compile(Join join, TemplateCompiler compiler)
            throws SqlTemplateException{
        if (join.autoJoin() && join.source() instanceof TableSource(var table)) {
            // Prune the join if the table is not referenced in the template, for instance, in case of a SelectMode.DECLARED.
            return new CompiledElement(
                    () -> compiler.isReferenced(table, join.sourceAlias()) ? compileJoin(join, compiler) : "");
        }
        return new CompiledElement(compileJoin(join, compiler));
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param join the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(Join join, TemplateBinder binder, BindHint bindHint) {
        if (join.target() instanceof TemplateTarget(var template)) {
            binder.bind(template, true);
        }
        if (join.source() instanceof TemplateSource(var template)) {
            binder.bind(template, false);
        }
    }

    private String compileJoin(Join join, TemplateCompiler compiler)
            throws SqlTemplateException {
        String joinType = join.type().sql();
        if (!join.type().hasOnClause() && hasJoinCondition(join)) {
            throw new SqlTemplateException("%s has no ON clause, so it cannot take a join condition. Use a join type with an ON clause, such as an inner or left join, to join on a condition.".formatted(joinType));
        }
        String onClause = join.type().hasOnClause() ? switch (join.target()) {
            case TableTarget(var toTable, var toField) when join.source() instanceof TableSource(var fromTable) ->
                    compileJoinCondition(fromTable, join.sourceAlias(), toTable, join.targetAlias(), toField, compiler);
            case TemplateTarget ts -> compiler.compile(ts.template(), true);
            default -> throw new SqlTemplateException("Unsupported join target type: %s. Join targets must be either a table type (Class<? extends Data>) or a template expression.".formatted(join.target().getClass().getSimpleName()));
        } : "";
        final String clause = onClause.isEmpty() ? "" : " ON " + onClause;
        return switch (join.source()) {
            case TableSource ts -> {
                var table = getTableName(ts.table(), compiler.template().tableNameResolver());
                var alias = compiler.useAlias(ts.table(), join.sourceAlias());
                yield compiler.dialectTemplate().process("\n\0 \0 \0\0", joinType, table, alias, clause);
            }
            case TemplateSource ts -> {
                var source = compiler.compile(ts.template(), false);
                var alias = join.sourceAlias();
                yield compiler.dialectTemplate().process("\n\0 (\0) \0\0", joinType, source, alias, clause);
            }
        };
    }

    /**
     * Returns whether the join carries a condition: a table to join on, or an ON template with content.
     */
    private static boolean hasJoinCondition(Join join) {
        return !(join.target() instanceof TemplateTarget(var template))
                || !template.values().isEmpty()
                || template.fragments().stream().anyMatch(fragment -> !fragment.isBlank());
    }

    private String compileJoinCondition(
            Class<? extends Data> fromTable,
            String alias,
            Class<? extends Data> toTable,
            @Nullable String toAlias,
            @Nullable RecordField toField,
            TemplateCompiler compiler
    ) throws SqlTemplateException {
        if (toField != null) {
            // Graph-derived joins carry the resolved foreign key field of the target table; no
            // type-based resolution is needed, so multiple foreign keys of the same type stay
            // unambiguous.
            validateDataType(fromTable, true);
            return compileJoinCondition(fromTable, alias, toTable, toAlias, toField,
                    findPkField(fromTable).orElseThrow(), compiler);
        }
        var rightComponent = findExactJoinField(toTable, fromTable);
        if (rightComponent.isPresent()) {
            validateDataType(fromTable, true);
            // Joins foreign key of right table to the primary key of left table.
            return compileJoinCondition(fromTable, alias, toTable, toAlias, rightComponent.get(),
                    findPkField(fromTable).orElseThrow(), compiler);
        }
        var leftComponent = findExactJoinField(fromTable, toTable);
        if (leftComponent.isPresent()) {
            validateDataType(toTable, true);
            // Joins foreign key of left table to the primary key of right table.
            return compileJoinCondition(toTable, toAlias, fromTable, alias, leftComponent.get(),
                    findPkField(toTable).orElseThrow(), compiler);
        }
        // Table-based fallback: foreign keys reference entity types, but other table-backed types
        // (projections, alternative entities over the same table) map the same table — any foreign
        // key referencing that table can join them.
        var rightTableComponent = findTableJoinField(toTable, fromTable, compiler);
        if (rightTableComponent.isPresent()) {
            validateDataType(fromTable, true);
            return compileJoinCondition(fromTable, alias, toTable, toAlias, rightTableComponent.get(),
                    findPkField(fromTable).orElseThrow(), compiler);
        }
        var leftTableComponent = findTableJoinField(fromTable, toTable, compiler);
        if (leftTableComponent.isPresent()) {
            validateDataType(toTable, true);
            return compileJoinCondition(toTable, toAlias, fromTable, alias, leftTableComponent.get(),
                    findPkField(toTable).orElseThrow(), compiler);
        }
        throw new SqlTemplateException(
                "Failed to join %s with %s: no matching foreign key relationship found. Ensure one of the types has an @FK-annotated field referencing the other, or use an explicit ON clause with a template-based join.".formatted(fromTable.getSimpleName(), toTable.getSimpleName()));
    }

    /**
     * Attempts to resolve the foreign key field of {@code fkSide} whose declared type matches the
     * specified target type. Returns empty when no foreign key matches; throws when multiple
     * foreign keys reference the target type, as the join is ambiguous and must be specified
     * explicitly.
     */
    private Optional<RecordField> findExactJoinField(
            Class<? extends Data> fkSide,
            Class<? extends Data> targetSide
    ) throws SqlTemplateException {
        var matches = findRecordFields(getFkFields(fkSide).toList(), targetSide);
        if (matches.size() > 1) {
            throw new SqlTemplateException(
                    "Failed to join %s with %s: multiple foreign keys reference %s (fields: %s). Use an explicit ON clause with a template-based join to select the intended foreign key.".formatted(
                            fkSide.getSimpleName(),
                            targetSide.getSimpleName(),
                            targetSide.getSimpleName(),
                            matches.stream().map(RecordField::name).collect(joining(", "))));
        }
        return matches.stream().findFirst();
    }

    /**
     * Attempts to resolve the foreign key field of {@code fkSide} that joins the specified
     * table-backed type by matching the referenced type's table against that type's table.
     * Returns empty when {@code tableSide} is not a table-backed type with a primary key, or when
     * no foreign key references its table; throws when multiple foreign keys reference the table,
     * as the join is ambiguous and must be specified explicitly.
     */
    private Optional<RecordField> findTableJoinField(
            Class<? extends Data> fkSide,
            Class<? extends Data> tableSide,
            TemplateCompiler compiler
    ) throws SqlTemplateException {
        if (!isTableJoinCandidate(tableSide)) {
            return empty();
        }
        var tableNameResolver = compiler.template().tableNameResolver();
        var matches = findRecordFieldsByTable(getFkFields(fkSide).toList(), tableSide, tableNameResolver);
        if (matches.size() > 1) {
            throw new SqlTemplateException(
                    "Failed to join %s with %s: multiple foreign keys reference table '%s' (fields: %s). Use an explicit ON clause with a template-based join to select the intended foreign key.".formatted(
                            fkSide.getSimpleName(),
                            tableSide.getSimpleName(),
                            getTableName(tableSide, tableNameResolver).table(),
                            matches.stream().map(RecordField::name).collect(joining(", "))));
        }
        return matches.stream().findFirst();
    }

    @SuppressWarnings("DuplicatedCode")
    private String compileJoinCondition(
            Class<? extends Data> fromTable,
            @Nullable String fromAlias,
            Class<? extends Data> toTable,
            @Nullable String toAlias,
            RecordField left,
            RecordField right,
            TemplateCompiler compiler
    ) throws SqlTemplateException {
        fromAlias = fromAlias == null ? compiler.getAlias(Metamodel.root(fromTable), INNER) : fromAlias;
        toAlias = toAlias == null ? compiler.getAlias(Metamodel.root(toTable), INNER) : toAlias;
        var foreignKeyResolver = compiler.template().foreignKeyResolver();
        var columnNameResolver = compiler.template().columnNameResolver();
        var fkColumns = getForeignKeys(left, foreignKeyResolver, columnNameResolver);
        var pkColumns = getPrimaryKeys(right, foreignKeyResolver, columnNameResolver);
        if (fkColumns.size() != pkColumns.size()) {
            throw new SqlTemplateException("Mismatch in PK/FK column count between %s and %s: found %d foreign key column(s) but %d primary key column(s). Ensure the foreign key definition matches the referenced primary key structure.".formatted(toTable.getSimpleName(), fromTable.getSimpleName(), fkColumns.size(), pkColumns.size()));
        }
        StringBuilder joinCondition = new StringBuilder();
        for (int i = 0; i < fkColumns.size(); i++) {
            if (i > 0) {
                joinCondition.append(" AND ");
            }
            joinCondition.append(compiler.dialectTemplate()
                    .process("\0.\0 = \0.\0", toAlias, fkColumns.get(i), fromAlias, pkColumns.get(i)));
        }
        return joinCondition.toString();
    }
}
