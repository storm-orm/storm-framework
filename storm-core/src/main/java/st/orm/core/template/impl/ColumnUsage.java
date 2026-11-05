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
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;

/**
 * Records the columns a statement uses in a way an index can serve, on the table that holds them, as the statement
 * compiles: the processors of the elements that compare, join, group or sort by columns call it, and so does the
 * compile for what the builder asserts beside the template.
 *
 * @since 1.16
 */
final class ColumnUsage {

    private ColumnUsage() {
    }

    /**
     * Records that the statement uses the given columns in a way an index can serve: every column of an equality, as
     * a compound key is compared column by column, and the first column of a range or an ordering, the one an index
     * can serve it on. Each column is recorded on the table that holds it. A joined inheritance hierarchy spreads its
     * columns over several tables, which its types do not name one by one, so its columns are left out.
     *
     * @param columns the columns, in key order.
     * @param kind how the statement uses them.
     * @param compiler the compiler of the statement.
     * @throws SqlTemplateException if the model of a column's table cannot be built.
     */
    static void record(List<Column> columns, IndexUsage.Kind kind, TemplateCompiler compiler)
            throws SqlTemplateException {
        for (var column : columns) {
            var type = column.metamodel().tableType();
            if (inJoinedHierarchy(type)) {
                return;
            }
            compiler.recordIndexUse(compiler.getModel(type), column.name(), kind);
            // A range or an order is served on one column; every column of a key, a group or a select counts.
            if (kind == IndexUsage.Kind.RANGE || kind == IndexUsage.Kind.ORDER) {
                return;
            }
        }
    }

    /**
     * Records that a search of the statement matches the given column in a match form. The column is recorded on
     * the table that holds it, with the expression the match compares around its bare name, so the search advice
     * can tell an index on the expression from one on the column. A column of a joined inheritance hierarchy is left
     * out, as for {@link #record(List, IndexUsage.Kind, TemplateCompiler)}.
     *
     * @param column the column.
     * @param form the match form.
     * @param field the column as the match names it, bare or wrapped in its function calls.
     * @param expression the expression the match compares, rendered around the bare column name.
     * @param compiler the compiler of the statement.
     * @throws SqlTemplateException if the model of the column's table cannot be built.
     */
    static void recordSearchUse(Column column, MatchForm form, String field, String expression,
                             TemplateCompiler compiler) throws SqlTemplateException {
        var type = column.metamodel().tableType();
        if (inJoinedHierarchy(type)) {
            return;
        }
        compiler.recordSearchUse(compiler.getModel(type), column.name(), form, field, expression);
    }

    /**
     * Returns whether the type is the root or a subtype of a joined inheritance hierarchy, whose columns are spread
     * over the tables of the hierarchy.
     */
    static boolean inJoinedHierarchy(Class<?> type) {
        return RecordReflection.isJoinedEntity(type) || RecordReflection.findJoinedSealedParent(type).isPresent();
    }
}
