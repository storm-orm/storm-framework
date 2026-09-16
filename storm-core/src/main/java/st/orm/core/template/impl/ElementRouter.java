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

import st.orm.Element;
import st.orm.core.template.impl.Elements.Alias;
import st.orm.core.template.impl.Elements.BindVar;
import st.orm.core.template.impl.Elements.Column;
import st.orm.core.template.impl.Elements.Columns;
import st.orm.core.template.impl.Elements.Cursor;
import st.orm.core.template.impl.Elements.Delete;
import st.orm.core.template.impl.Elements.Fetch;
import st.orm.core.template.impl.Elements.From;
import st.orm.core.template.impl.Elements.Insert;
import st.orm.core.template.impl.Elements.Param;
import st.orm.core.template.impl.Elements.Select;
import st.orm.core.template.impl.Elements.Set;
import st.orm.core.template.impl.Elements.Subquery;
import st.orm.core.template.impl.Elements.Table;
import st.orm.core.template.impl.Elements.Unsafe;
import st.orm.core.template.impl.Elements.Update;
import st.orm.core.template.impl.Elements.Values;
import st.orm.core.template.impl.Elements.Where;
import st.orm.core.template.impl.SqlTemplateImpl.Wrapped;

class ElementRouter {
    private ElementRouter() {}

    private static final SelectProcessor SELECT_PROCESSOR = new SelectProcessor();
    private static final InsertProcessor INSERT_PROCESSOR = new InsertProcessor();
    private static final UpdateProcessor UPDATE_PROCESSOR = new UpdateProcessor();
    private static final DeleteProcessor DELETE_PROCESSOR = new DeleteProcessor();
    private static final FromProcessor FROM_PROCESSOR = new FromProcessor();
    private static final JoinProcessor JOIN_PROCESSOR = new JoinProcessor();
    private static final TableProcessor TABLE_PROCESSOR = new TableProcessor();
    private static final AliasProcessor ALIAS_PROCESSOR = new AliasProcessor();
    private static final ColumnProcessor COLUMN_PROCESSOR = new ColumnProcessor();
    private static final ColumnsProcessor COLUMNS_PROCESSOR = new ColumnsProcessor();
    private static final FetchProcessor FETCH_PROCESSOR = new FetchProcessor();
    private static final CursorProcessor CURSOR_PROCESSOR = new CursorProcessor();
    private static final SetProcessor SET_PROCESSOR = new SetProcessor();
    private static final WhereProcessor WHERE_PROCESSOR = new WhereProcessor();
    private static final ValuesProcessor VALUES_PROCESSOR = new ValuesProcessor();
    private static final ParamProcessor PARAM_PROCESSOR = new ParamProcessor();
    private static final VarProcessor VAR_PROCESSOR = new VarProcessor();
    private static final SubqueryProcessor SUBQUERY_PROCESSOR = new SubqueryProcessor();
    private static final UnsafeProcessor UNSAFE_PROCESSOR = new UnsafeProcessor();
    private static final CacheableProcessor CACHEABLE_PROCESSOR = new CacheableProcessor();

    static ElementProcessor<Element> getElementProcessor(Element element) {
        //noinspection unchecked
        return (ElementProcessor<Element>) (Object) switch (element) {
            case Wrapped ignore -> throw new IllegalStateException("Wrapped element cannot be processed directly.");
            case Select ignored -> SELECT_PROCESSOR;
            case Insert ignored -> INSERT_PROCESSOR;
            case Update ignored -> UPDATE_PROCESSOR;
            case Delete ignored -> DELETE_PROCESSOR;
            case From ignored -> FROM_PROCESSOR;
            case Join ignored -> JOIN_PROCESSOR;
            case Table ignored -> TABLE_PROCESSOR;
            case Alias ignored -> ALIAS_PROCESSOR;
            case Column ignored -> COLUMN_PROCESSOR;
            case Columns ignored -> COLUMNS_PROCESSOR;
            case Fetch ignored -> FETCH_PROCESSOR;
            case Cursor ignored -> CURSOR_PROCESSOR;
            case Set ignored -> SET_PROCESSOR;
            case Where ignored -> WHERE_PROCESSOR;
            case Values ignored -> VALUES_PROCESSOR;
            case Param ignored -> PARAM_PROCESSOR;
            case BindVar ignored -> VAR_PROCESSOR;
            case Subquery ignored -> SUBQUERY_PROCESSOR;
            case Unsafe ignored -> UNSAFE_PROCESSOR;
            case Cacheable ignored -> CACHEABLE_PROCESSOR;
            default -> throw new IllegalStateException("Unsupported element type: %s."
                    .formatted(element.getClass().getName()));
        };
    }
}
