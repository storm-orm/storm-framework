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

import static st.orm.core.template.impl.RecordReflection.getRecordField;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isTableJoinCandidate;

import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.template.QueryTemplate;
import st.orm.mapping.RecordField;

/**
 * Resolves the foreign keys a typed join names to the table it joins on and the keys it joins by.
 *
 * <p>One key relates the joined table and the table on the other side of the key: a key of the joined table joins
 * the table it references, and a key of another table joins that table where the key references the joined table.
 * Two keys relate the joined table to the table holding the second key where neither references the other: both
 * reference one table, and the join matches the keys with each other.</p>
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

    private JoinKeys() {
    }

    /**
     * Resolves the keys a join names.
     *
     * @param queryTemplate the template the query belongs to.
     * @param source the joined table.
     * @param key a foreign key of the joined table, or of the table to join on.
     * @param anchorKey a foreign key of the table to join on referencing the table {@code key} references, or
     *                  {@code null} when {@code key} relates the two tables by itself.
     * @throws PersistenceException if a key is not a foreign key, or the keys do not relate the joined table to the
     *                              table to join on.
     */
    static Keys resolve(QueryTemplate queryTemplate,
                        Class<? extends Data> source,
                        Metamodel<? extends Data, ?> key,
                        @Nullable Metamodel<? extends Data, ?> anchorKey) {
        try {
            var field = foreignKey(key);
            var referenced = referenced(key, field);
            if (anchorKey == null) {
                if (key.root() == source) {
                    return new Keys(referenced, field, null);
                }
                if (!references(queryTemplate, referenced, source)) {
                    throw new PersistenceException("on(%1$s): %1$s references %2$s, not the joined %3$s. Name a key of %3$s, or a key referencing %3$s."
                            .formatted(describe(key), referenced.getSimpleName(), source.getSimpleName()));
                }
                return new Keys(key.root(), null, field);
            }
            if (key.root() != source) {
                throw new PersistenceException("on(%1$s, %2$s): the first key is a key of the joined %3$s, and %1$s is a key of %4$s."
                        .formatted(describe(key), describe(anchorKey), source.getSimpleName(), key.root().getSimpleName()));
            }
            var anchorField = foreignKey(anchorKey);
            var anchorReferenced = referenced(anchorKey, anchorField);
            if (!references(queryTemplate, referenced, anchorReferenced)) {
                throw new PersistenceException("on(%1$s, %2$s): the keys reference %3$s and %4$s, so no table relates %5$s and %6$s through them. Name two keys referencing the same table."
                        .formatted(describe(key), describe(anchorKey), referenced.getSimpleName(), anchorReferenced.getSimpleName(),
                                source.getSimpleName(), anchorKey.root().getSimpleName()));
            }
            return new Keys(anchorKey.root(), field, anchorField);
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    private static RecordField foreignKey(Metamodel<? extends Data, ?> key) throws SqlTemplateException {
        var field = getRecordField(key.root(), key.fieldPath());
        if (!field.isAnnotationPresent(FK.class)) {
            throw new PersistenceException("on(%1$s): %1$s is not a foreign key. Name a field annotated @FK."
                    .formatted(describe(key)));
        }
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Data> referenced(Metamodel<? extends Data, ?> key, RecordField field)
            throws SqlTemplateException {
        Class<?> type = Ref.class.isAssignableFrom(field.type()) ? getRefDataType(field) : field.type();
        if (!Data.class.isAssignableFrom(type)) {
            throw new PersistenceException("on(%1$s): %1$s references %2$s, which is not a table."
                    .formatted(describe(key), type.getSimpleName()));
        }
        return (Class<? extends Data>) type;
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

    private static String describe(Metamodel<? extends Data, ?> key) {
        return key.root().getSimpleName() + "_." + key.fieldPath();
    }
}
