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

import static st.orm.ResolveScope.INNER;
import static st.orm.ResolveScope.OUTER;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getForeignKeys;
import static st.orm.core.template.impl.RecordReflection.getPrimaryKeys;

import java.util.ArrayList;
import java.util.List;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.SqlTemplateException;
import st.orm.core.template.impl.Elements.Correlation;
import st.orm.core.template.impl.Elements.RelationKey;
import st.orm.mapping.RecordField;

/**
 * Renders the columns by which a table takes part in a relation, for the {@code IN} that relates a query to the rows
 * of a semi-joined table.
 *
 * @since 1.15
 */
final class RelationKeyProcessor implements ElementProcessor<RelationKey> {

    @Override
    public Object getCompilationKey(RelationKey key) {
        return key;
    }

    @Override
    public CompiledElement compile(RelationKey key, TemplateCompiler compiler) throws SqlTemplateException {
        var alias = compiler.getAlias(Metamodel.root(key.table()), key.scope());
        var columns = keyColumns(key.table(), key.foreignKey(), key.holdsKey(), compiler);
        var rendered = new ArrayList<String>(columns.size());
        for (var column : columns) {
            rendered.add(compiler.dialectTemplate().process("\0.\0", alias, column));
        }
        return new CompiledElement(String.join(", ", rendered));
    }

    @Override
    public void bind(RelationKey key, TemplateBinder binder, BindHint bindHint) {
    }

    /**
     * Returns the key columns of one side of a relation: the foreign key columns on the side holding the foreign key,
     * the primary key columns on the side it references, in matching order.
     */
    static List<ColumnName> keyColumns(Class<? extends Data> table,
                                       RecordField foreignKey,
                                       boolean holdsKey,
                                       TemplateCompiler compiler) throws SqlTemplateException {
        var foreignKeyResolver = compiler.template().foreignKeyResolver();
        var columnNameResolver = compiler.template().columnNameResolver();
        if (holdsKey) {
            return getForeignKeys(foreignKey, foreignKeyResolver, columnNameResolver);
        }
        var primaryKey = findPkField(table).orElseThrow(() ->
                new SqlTemplateException("No primary key found for table %s.".formatted(table.getSimpleName())));
        return getPrimaryKeys(primaryKey, foreignKeyResolver, columnNameResolver);
    }

    /**
     * Renders the condition relating the rows of a subquery's table to the rows of the enclosing query.
     */
    static final class CorrelationProcessor implements ElementProcessor<Correlation> {

        @Override
        public Object getCompilationKey(Correlation correlation) {
            return correlation;
        }

        @Override
        public CompiledElement compile(Correlation correlation, TemplateCompiler compiler) throws SqlTemplateException {
            var innerAlias = compiler.getAlias(Metamodel.root(correlation.inner()), INNER);
            var outerAlias = compiler.getAlias(Metamodel.root(correlation.outer()), OUTER);
            var innerColumns = keyColumns(correlation.inner(), correlation.foreignKey(), correlation.innerHoldsKey(), compiler);
            var outerColumns = keyColumns(correlation.outer(), correlation.foreignKey(), !correlation.innerHoldsKey(), compiler);
            if (innerColumns.size() != outerColumns.size()) {
                throw new SqlTemplateException("Mismatch in PK/FK column count between %s and %s: found %d and %d column(s). Ensure the foreign key definition matches the referenced primary key structure."
                        .formatted(correlation.inner().getSimpleName(), correlation.outer().getSimpleName(), innerColumns.size(), outerColumns.size()));
            }
            var condition = new StringBuilder();
            for (int i = 0; i < innerColumns.size(); i++) {
                if (i > 0) {
                    condition.append(" AND ");
                }
                condition.append(compiler.dialectTemplate()
                        .process("\0.\0 = \0.\0", innerAlias, innerColumns.get(i), outerAlias, outerColumns.get(i)));
            }
            return new CompiledElement(condition.toString());
        }

        @Override
        public void bind(Correlation correlation, TemplateBinder binder, BindHint bindHint) {
        }
    }
}
