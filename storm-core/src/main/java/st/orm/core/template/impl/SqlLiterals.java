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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.SqlArray;
import st.orm.core.template.SqlTemplate.NamedParameter;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;

/**
 * Renders database values as SQL literals.
 *
 * <p>Used both when a template inlines its parameters and when a statement is logged with its values, so a value
 * reads the same either way.</p>
 *
 * @since 1.13
 */
final class SqlLiterals {

    private SqlLiterals() {
    }

    /**
     * Returns the SQL literal for the given database value.
     *
     * @param dbValue the database value.
     * @return the literal to place in a statement.
     */
    static String toLiteral(@Nullable Object dbValue) {
        return switch (dbValue) {
            case null -> "NULL";
            case Short s -> s.toString();
            case Integer i -> i.toString();
            case Long l -> l.toString();
            case Float f -> f.toString();
            case Double d -> d.toString();
            case Byte b -> b.toString();
            case Boolean b -> b ? "TRUE" : "FALSE";
            case String s -> "'%s'".formatted(s.replace("\\", "\\\\").replace("'", "''"));
            case java.sql.Date d -> "'%s'".formatted(d);
            case java.sql.Time t -> "'%s'".formatted(t);
            case java.sql.Timestamp t -> "'%s'".formatted(t);
            case Enum<?> e -> "'%s'".formatted(e.name().replace("\\", "\\\\").replace("'", "''"));
            // An array literal, {1,2,3}, which the database reads as the array the parameter bound.
            case SqlArray a -> "'%s'".formatted(a.toString().replace("'", "''"));
            default -> {
                String str = dbValue.toString().replace("\\", "\\\\").replace("'", "''");
                yield "'%s'".formatted(str);
            }
        };
    }

    /**
     * Returns the statement with its placeholders replaced by the literals of the given parameters, producing a
     * statement that can be pasted into a database console.
     *
     * <p>Placeholders are recognized outside string literals only, so a {@code ?} or {@code :name} that is part of
     * a quoted value stays untouched. A placeholder without a matching parameter is left as it is: a statement that
     * reads oddly beats one that silently reports the wrong value.</p>
     *
     * @param statement the statement carrying {@code ?} and {@code :name} placeholders.
     * @param parameters the parameters bound to the statement.
     * @return the statement with literals in place of placeholders.
     */
    static String inline(String statement, List<Parameter> parameters) {
        Map<String, String> named = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (var parameter : parameters) {
            switch (parameter) {
                case NamedParameter namedParameter ->
                        named.put(namedParameter.name(), toLiteral(namedParameter.dbValue()));
                case PositionalParameter positionalParameter ->
                        positional.add(toLiteral(positionalParameter.dbValue()));
            }
        }
        var rendered = new StringBuilder(statement.length() + 16 * parameters.size());
        boolean inQuote = false;
        int next = 0;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '\'') {
                // A doubled quote escapes itself, so toggling on each one tracks the string correctly.
                inQuote = !inQuote;
                rendered.append(c);
                continue;
            }
            if (inQuote) {
                rendered.append(c);
                continue;
            }
            if (c == '?') {
                rendered.append(next < positional.size() ? positional.get(next) : "?");
                next++;
                continue;
            }
            if (c == ':' && !named.isEmpty() && isNameStart(charAt(statement, i + 1))
                    && charAt(statement, i - 1) != ':') {
                int end = i + 1;
                while (end < statement.length() && isNamePart(statement.charAt(end))) {
                    end++;
                }
                String name = statement.substring(i + 1, end);
                String literal = named.get(name);
                if (literal != null) {
                    rendered.append(literal);
                    i = end - 1;
                    continue;
                }
            }
            rendered.append(c);
        }
        return rendered.toString();
    }

    private static char charAt(String value, int index) {
        return index >= 0 && index < value.length() ? value.charAt(index) : '\0';
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
