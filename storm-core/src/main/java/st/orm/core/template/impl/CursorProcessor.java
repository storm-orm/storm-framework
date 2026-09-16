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

import static st.orm.ResolveScope.CASCADE;

import java.util.ArrayList;
import java.util.List;
import st.orm.SqlTemplateException;
import st.orm.core.template.impl.Elements.Cursor;

/**
 * Renders a {@link Cursor} element: the columns the select list lacks, appended after it, and records where every
 * value is read from.
 *
 * <p>A cursor field resolves the way a select-list column does, so an equal rendering is the same column: it is
 * read at the position the select list gives it and nothing is appended for it. The select list is known for a
 * select the query model generated; a cursor over a select list the caller wrote appends every column. Resolution
 * matches {@link ColumnsProcessor}, so an inline record expands to each of its columns.</p>
 *
 * @since 1.14
 */
final class CursorProcessor implements ElementProcessor<Cursor> {

    /**
     * Returns a key that represents the compiled shape of the given element.
     *
     * @param cursor the element to compute a key for.
     * @return an immutable key for caching.
     */
    @Override
    public Object getCompilationKey(Cursor cursor) {
        return cursor;
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * @param cursor the element to compile.
     * @param compiler the active compiler context.
     * @return the columns appended after the select list, empty when it carries every cursor column.
     * @throws SqlTemplateException if a field does not resolve to a column.
     */
    @Override
    public CompiledElement compile(Cursor cursor, TemplateCompiler compiler) throws SqlTemplateException {
        List<String> selectColumns = compiler.getSelectColumns().orElse(null);
        var positions = new ArrayList<List<Integer>>(cursor.fields().size());
        var appended = new StringBuilder();
        int appendedCount = 0;
        for (var field : cursor.fields()) {
            var metamodel = MetamodelFactory.canonical(field);
            String prefix = ColumnProcessor.aliasPrefix(metamodel, CASCADE, compiler);
            var fieldPositions = new ArrayList<Integer>();
            for (var column : ColumnsProcessor.resolve(metamodel, compiler)) {
                String rendered = prefix + column.qualifiedName(compiler.dialect());
                int index = selectColumns == null ? -1 : selectColumns.indexOf(rendered);
                if (index >= 0) {
                    fieldPositions.add(index + 1);
                } else {
                    appended.append(", ").append(rendered);
                    fieldPositions.add(-(++appendedCount));
                }
            }
            positions.add(List.copyOf(fieldPositions));
        }
        compiler.setCursorColumns(positions);
        return new CompiledElement(appended.toString());
    }

    /**
     * A cursor binds nothing: its columns are read, not written.
     */
    @Override
    public void bind(Cursor cursor, TemplateBinder binder, BindHint bindHint) {
    }
}
