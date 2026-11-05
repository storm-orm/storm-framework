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

import static st.orm.Operator.EQUALS;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getRecordField;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isTableJoinCandidate;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.FK;
import st.orm.Navigable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.impl.Elements.PathExpression;
import st.orm.core.template.impl.PredicateNode.Group;
import st.orm.core.template.impl.PredicateNode.Term;
import st.orm.mapping.RecordField;

/**
 * Recognises the keys a join's predicate relates the joined table by.
 *
 * <p>A predicate states the relation of a join as a whole. A term of it that equates a key of the joined table with
 * a key of another table is a key match: a foreign key of the joined table with the primary key of the table it
 * references, the primary key of the joined table with a foreign key referencing it, or a foreign key of each
 * referencing one table. The first key match among the terms joined by AND is the relation the join resolves by;
 * the remaining terms are its condition. The key beyond a foreign key, {@code Pet_.owner.id}, is that key.</p>
 *
 * @since 1.16
 */
final class JoinKeys {

    /**
     * The table a join joins on and the keys it joins by.
     *
     * @param table the table to join on.
     * @param sourceKey the key of the joined table, or {@code null} when the table joined on holds the key.
     * @param targetKey the key of the table joined on, or {@code null} when the joined table holds the key.
     */
    record Keys(Class<? extends Data> table, @Nullable RecordField sourceKey, @Nullable RecordField targetKey) {}

    /**
     * A predicate resolved into the keys it relates the joined table by and the condition it adds to them.
     *
     * @param keys the keys, or {@code null} when no term of the predicate equates a key of the joined table with a
     *             key of another table.
     * @param condition the terms beyond the key match, or {@code null} when the key match is the whole predicate.
     */
    record Resolved(@Nullable Keys keys, @Nullable PredicateNode condition) {}

    /**
     * A key a path names: a foreign key with the table it references, or a primary key.
     *
     * @param holder the table holding the key.
     * @param field the key's field.
     * @param referenced the table a foreign key references, or {@code null} for a primary key.
     */
    private record Key(Class<? extends Data> holder, RecordField field, @Nullable Class<? extends Data> referenced) {}

    private JoinKeys() {
    }

    /**
     * Resolves the keys a join's predicate relates the joined table by.
     *
     * @param queryTemplate the template the query belongs to.
     * @param source the joined table.
     * @param fromType the query's table.
     * @param predicate the predicate stating the relation.
     */
    static Resolved resolve(QueryTemplate queryTemplate,
                            Class<? extends Data> source,
                            Class<? extends Data> fromType,
                            PredicateNode predicate) {
        Keys keys = null;
        var rest = new ArrayList<PredicateNode>();
        for (var conjunct : conjuncts(predicate)) {
            if (keys == null) {
                var expression = pathEquality(conjunct);
                keys = expression == null ? null : keys(queryTemplate, source, fromType, expression);
                if (keys != null) {
                    continue;
                }
            }
            rest.add(conjunct);
        }
        return new Resolved(keys, rest.isEmpty() ? null : PredicateNode.and(rest));
    }

    private static List<PredicateNode> conjuncts(PredicateNode node) {
        return node instanceof Group(var and, var operands) && and ? operands : List.of(node);
    }

    /**
     * Returns the comparison of two paths a term holds when it equates them, or {@code null} for any other term.
     */
    private static @Nullable PathExpression pathEquality(PredicateNode node) {
        if (!(node instanceof Term(var template, var ignore))
                || template.values().size() != 1
                || template.fragments().stream().anyMatch(fragment -> !fragment.isBlank())) {
            return null;
        }
        return template.values().getFirst() instanceof PathExpression expression && expression.operator() == EQUALS ? expression : null;
    }

    /**
     * Returns the keys a comparison of two paths relates the joined table by, or {@code null} when it does not
     * equate a key of the joined table with a key of another table.
     */
    private static @Nullable Keys keys(QueryTemplate queryTemplate,
                                       Class<? extends Data> source,
                                       Class<? extends Data> fromType,
                                       PathExpression expression) {
        try {
            var left = key(expression.path());
            var right = key(expression.other());
            if (left == null || right == null) {
                return null;
            }
            if (left.holder() == source && right.holder() == source) {
                // A filter join on the query's own table finds the rows holding the same key as the query's row.
                return source == fromType && left.referenced() != null && left.field().equals(right.field())
                        ? new Keys(left.referenced(), left.field(), null)
                        : null;
            }
            if (left.holder() == source) {
                return keys(queryTemplate, left, right);
            }
            if (right.holder() == source) {
                return keys(queryTemplate, right, left);
            }
            return null;
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    /**
     * Returns the keys a key of the joined table and a key of another table relate the tables by, or {@code null}
     * when the keys do not relate them.
     */
    private static @Nullable Keys keys(QueryTemplate queryTemplate, Key joined, Key anchor) {
        if (joined.referenced() != null && anchor.referenced() == null) {
            return references(queryTemplate, joined.referenced(), anchor.holder())
                    ? new Keys(anchor.holder(), joined.field(), null)
                    : null;
        }
        if (joined.referenced() == null && anchor.referenced() != null) {
            return references(queryTemplate, anchor.referenced(), joined.holder())
                    ? new Keys(anchor.holder(), null, anchor.field())
                    : null;
        }
        if (joined.referenced() != null && anchor.referenced() != null) {
            return references(queryTemplate, joined.referenced(), anchor.referenced())
                    ? new Keys(anchor.holder(), joined.field(), anchor.field())
                    : null;
        }
        return null;
    }

    /**
     * Returns the key a path names: a foreign key of the table holding it, the primary key of that table, or the
     * foreign key a path beyond it ends in, {@code Pet_.owner.id}. Returns {@code null} for any other column.
     */
    private static @Nullable Key key(Navigable<?, ?> path) throws SqlTemplateException {
        if (!path.isColumn() || path.isInline()) {
            return null;
        }
        var table = path.table();
        if (!table.fieldPath().isEmpty()) {
            var holder = table.tableType();
            var foreignKey = getRecordField(holder, table.field());
            var primaryKey = findPkField(table.fieldType());
            if (foreignKey.isAnnotationPresent(FK.class) && primaryKey.isPresent()
                    && primaryKey.get().name().equals(path.field())) {
                return new Key(holder, foreignKey, referenced(foreignKey));
            }
        }
        var holder = path.tableType();
        var field = getRecordField(holder, path.field());
        if (field.isAnnotationPresent(FK.class)) {
            var referenced = referenced(field);
            return referenced == null ? null : new Key(holder, field, referenced);
        }
        var primaryKey = findPkField(holder);
        return primaryKey.isPresent() && primaryKey.get().equals(field) ? new Key(holder, field, null) : null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Data> referenced(RecordField field) throws SqlTemplateException {
        Class<?> type = Ref.class.isAssignableFrom(field.type()) ? getRefDataType(field) : field.type();
        return Data.class.isAssignableFrom(type) ? (Class<? extends Data>) type : null;
    }

    /**
     * Returns whether a key referencing the given type joins the table: the type itself, or a projection or another
     * entity mapping the same table.
     */
    private static boolean references(QueryTemplate queryTemplate, Class<? extends Data> referenced, Class<? extends Data> table) {
        if (referenced == table) {
            return true;
        }
        if (!isTableJoinCandidate(referenced) || !isTableJoinCandidate(table)) {
            return false;
        }
        var referencedModel = queryTemplate.model(referenced, false);
        var tableModel = queryTemplate.model(table, false);
        return referencedModel.name().equals(tableModel.name()) && referencedModel.schema().equals(tableModel.schema());
    }
}
