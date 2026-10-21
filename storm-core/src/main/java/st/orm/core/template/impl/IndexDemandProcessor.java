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
import st.orm.core.template.impl.Elements.IndexDemand;

/**
 * A processor for an index demand element of a template: it renders nothing and records the demand on the
 * statement.
 *
 * @since 1.15
 */
final class IndexDemandProcessor implements ElementProcessor<IndexDemand> {

    @Override
    public Object getCompilationKey(IndexDemand demand) {
        return demand;
    }

    @Override
    public CompiledElement compile(IndexDemand demand, TemplateCompiler compiler) throws SqlTemplateException {
        var metamodel = MetamodelFactory.canonical(demand.field());
        demand(compiler.getModel(metamodel.tableType()).getColumns(metamodel), demand.kind(), compiler);
        return new CompiledElement("");
    }

    @Override
    public void bind(IndexDemand demand, TemplateBinder binder, BindHint bindHint) {
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
    static void demand(List<Column> columns, IndexDemands.Kind kind, TemplateCompiler compiler)
            throws SqlTemplateException {
        for (var column : columns) {
            var type = column.metamodel().tableType();
            if (inJoinedHierarchy(type)) {
                return;
            }
            compiler.demandIndex(compiler.getModel(type), column.name(), kind);
            if (kind != IndexDemands.Kind.EQUALITY) {
                return;
            }
        }
    }

    /**
     * Returns whether the type is the root or a subtype of a joined inheritance hierarchy, whose columns are spread
     * over the tables of the hierarchy.
     */
    static boolean inJoinedHierarchy(Class<?> type) {
        return RecordReflection.isJoinedEntity(type) || RecordReflection.findJoinedSealedParent(type).isPresent();
    }
}
