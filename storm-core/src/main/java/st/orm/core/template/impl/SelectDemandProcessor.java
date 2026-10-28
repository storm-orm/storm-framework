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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.SqlTemplateException;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Columns;
import st.orm.core.template.impl.Elements.SelectDemand;

/**
 * Records what a select list built from a template reads: the columns of the query's own table it names through the
 * metamodel, and whether it names every column it reads, which decides whether an index that holds those columns is
 * known to serve the select without reading the table.
 *
 * <p>A select names every column it reads when its values are paths, parameters or bind variables and its text names
 * no column: words in it are function names, keywords, type names or aliases. A word that could be a column, a quoted
 * identifier, a value that is a template of its own, or a bare {@code *} outside a function leaves the select partly
 * opaque, and the advice hedges. The decision is made once per statement shape, as the element compiles.</p>
 *
 * @since 1.16
 */
final class SelectDemandProcessor implements ElementProcessor<SelectDemand> {

    /** A word, a quoted identifier, a string literal, or a star. */
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")*\"|`(?:[^`]|``)*`|\\[[^\\]]*]|'(?:[^']|'')*'|\\*");

    /** Words a select list uses without naming a column. */
    private static final Set<String> KEYWORDS = Set.of(
            "DISTINCT", "AS", "CASE", "WHEN", "THEN", "ELSE", "END", "NULL", "AND", "OR", "NOT", "IS", "IN",
            "TRUE", "FALSE", "CAST", "INTERVAL", "LIKE", "ESCAPE", "BETWEEN", "ASC", "DESC", "OVER", "PARTITION",
            "BY", "ORDER", "ROWS", "RANGE", "UNBOUNDED", "PRECEDING", "FOLLOWING", "CURRENT", "ROW", "ALL",
            "SEPARATOR", "FROM", "FOR", "FILTER", "WHERE", "COLLATE", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE",
            "SECOND", "INTEGER", "INT", "BIGINT", "SMALLINT", "VARCHAR", "CHAR", "TEXT", "DECIMAL", "NUMERIC",
            "DATE", "TIME", "TIMESTAMP", "DOUBLE", "FLOAT", "REAL", "BOOLEAN", "PRECISION", "VARYING", "CHARACTER",
            "WITH", "WITHOUT", "ZONE", "SIGNED", "UNSIGNED", "NULLS", "FIRST", "LAST");

    /**
     * Returns a constant key: the select template the element carries is the select clause of the same statement,
     * which the statement's own key already covers, so two statements keyed alike demand alike.
     */
    @Override
    public Object getCompilationKey(SelectDemand demand) {
        return SelectDemand.class;
    }

    @Override
    public CompiledElement compile(SelectDemand demand, TemplateCompiler compiler) throws SqlTemplateException {
        var queryModel = compiler.findQueryModel().orElse(null);
        if (queryModel == null) {
            return new CompiledElement("");
        }
        Class<? extends Data> fromType = queryModel.getTable().type();
        var select = demand.select();
        var paths = new ArrayList<Metamodel<?, ?>>();
        boolean whole = true;
        for (var value : select.values()) {
            switch (value) {
                case Metamodel<?, ?> metamodel -> {
                    if (metamodel.tableType() == fromType) {
                        paths.add(metamodel);
                    }
                }
                case Columns columns -> {
                    for (var field : columns.fields()) {
                        if (field.tableType() == fromType) {
                            paths.add(field);
                        }
                    }
                }
                case BindVars ignored -> {
                }
                case Element ignored -> whole = false;
                case TemplateString ignored -> whole = false;
                case Record ignored -> whole = false;
                case null, default -> {
                }
            }
        }
        whole = whole && namesNoColumn(select.fragments());
        var kind = whole ? IndexDemands.Kind.WHOLE_SELECT : IndexDemands.Kind.SELECT;
        if (paths.isEmpty()) {
            if (whole && !IndexDemandProcessor.inJoinedHierarchy(fromType)) {
                compiler.demandIndex(compiler.getModel(fromType), "", kind);
            }
            return new CompiledElement("");
        }
        for (var path : paths) {
            var metamodel = MetamodelFactory.canonical(path);
            IndexDemandProcessor.demand(compiler.getModel(metamodel.tableType()).getColumns(metamodel), kind, compiler);
        }
        return new CompiledElement("");
    }

    @Override
    public void bind(SelectDemand demand, TemplateBinder binder, BindHint bindHint) {
    }

    /**
     * Returns whether the text of a select names no column: every word in it is followed by a parenthesis, as a
     * function name is, follows {@code AS}, as an alias does, or is a keyword or type name; a star stands inside a
     * function only; and no identifier is quoted.
     */
    static boolean namesNoColumn(List<String> fragments) {
        for (var fragment : fragments) {
            var matcher = TOKEN.matcher(fragment);
            @Nullable String previous = null;
            while (matcher.find()) {
                String token = matcher.group();
                char first = token.charAt(0);
                if (first == '\'') {
                    previous = null;
                    continue;
                }
                if (first == '*') {
                    if (!precededByParenthesis(fragment, matcher.start())) {
                        return false;
                    }
                    previous = null;
                    continue;
                }
                if (first == '"' || first == '`' || first == '[') {
                    if (!"AS".equals(previous)) {
                        return false;
                    }
                    previous = null;
                    continue;
                }
                String word = token.toUpperCase(Locale.ROOT);
                boolean call = followedByParenthesis(fragment, matcher.end());
                if (!call && !KEYWORDS.contains(word) && !"AS".equals(previous)) {
                    return false;
                }
                previous = call ? null : word;
            }
        }
        return true;
    }

    private static boolean precededByParenthesis(String text, int position) {
        for (int i = position - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return ch == '(';
            }
        }
        return false;
    }

    private static boolean followedByParenthesis(String text, int position) {
        for (int i = position; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                return ch == '(';
            }
        }
        return false;
    }
}
