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

import static java.util.regex.Pattern.DOTALL;
import static st.orm.spi.SqlOperation.DELETE;
import static st.orm.spi.SqlOperation.INSERT;
import static st.orm.spi.SqlOperation.SELECT;
import static st.orm.spi.SqlOperation.UNDEFINED;
import static st.orm.spi.SqlOperation.UPDATE;

import java.util.List;
import java.util.regex.Pattern;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Unsafe;
import st.orm.spi.SqlOperation;

/**
 * SQL parser for basic SQL processing.
 */
final class SqlParser {

    private static final Pattern WITH_PATTERN = Pattern.compile("^(?i:WITH)\\b.*", DOTALL);

    private SqlParser() {
    }

    private static String getRawSql(TemplateString template) {
        StringBuilder sb = new StringBuilder();
        List<String> fragments = template.fragments();
        List<Object> values = template.values();
        // Go through as many fragments as there are values.
        int size = Math.min(fragments.size(), values.size());
        for (int i = 0; i < size; i++) {
            sb.append(fragments.get(i));
            Object value = values.get(i);
            if (value instanceof Unsafe) {
                sb.append(((Unsafe) value).sql());
            }
        }
        // Append any leftover fragments if there are more fragments than values.
        for (int i = size; i < fragments.size(); i++) {
            sb.append(fragments.get(i));
        }
        return sb.toString();
    }

    /**
     * Determines the SQL mode for the specified {@code sql} string.
     *
     * @param sql the sql to inspect.
     * @return the SQL mode.
     */
    private static SqlOperation getSqlOperation(String sql) {
        // Match a leading SQL keyword followed by a word boundary, mirroring the "^(?i:KEYWORD)\b" patterns without
        // evaluating a stream of regexes on every call.
        if (startsWithKeyword(sql, "SELECT")) return SELECT;
        if (startsWithKeyword(sql, "INSERT")) return INSERT;
        if (startsWithKeyword(sql, "UPDATE")) return UPDATE;
        if (startsWithKeyword(sql, "DELETE")) return DELETE;
        return UNDEFINED;
    }

    /**
     * Returns whether {@code sql} begins with {@code keyword} (case-insensitive) followed by a word boundary, matching
     * the semantics of the {@code ^(?i:keyword)\b} anchored pattern.
     */
    static boolean startsWithKeyword(String sql, String keyword) {
        int length = keyword.length();
        if (sql.length() < length || !sql.regionMatches(true, 0, keyword, 0, length)) {
            return false;
        }
        return sql.length() == length || !isIdentifierChar(sql.charAt(length));
    }

    /**
     * Returns whether {@code sql} ends with {@code keyword} (case-insensitive) preceded by a word boundary, matching
     * the semantics of the {@code \b(?i:keyword)$} anchored pattern. A bare {@code endsWith} would also match an
     * identifier that merely carries the keyword as a suffix, such as {@code nowhere} or {@code dataset}.
     */
    static boolean endsWithKeyword(String sql, String keyword) {
        int offset = sql.length() - keyword.length();
        if (offset < 0 || !sql.regionMatches(true, offset, keyword, 0, keyword.length())) {
            return false;
        }
        return offset == 0 || !isIdentifierChar(sql.charAt(offset - 1));
    }

    /**
     * Returns whether {@code ch} can be part of an SQL identifier, the character class that separates a keyword from
     * an identifier that carries it as a prefix or suffix.
     */
    private static boolean isIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    /**
     * Determines the SQL operation for the specified {@code template}.
     *
     * @param template the string template.
     * @return the SQL operation.
     */
    static SqlOperation getSqlOperation(TemplateString template, SqlDialect dialect) {
        String rawSql = getRawSql(template);
        // Templates regularly start with a newline or indentation; stripping it lets them hit the keyword fast
        // path directly.
        SqlOperation operation = getSqlOperation(rawSql.stripLeading());  // First try directly.
        if (operation != UNDEFINED) {
            return operation;
        }
        rawSql = removeComments(rawSql, dialect).stripLeading();
        operation = getSqlOperation(rawSql);  // Remove potential leading comments and retry.
        if (operation != UNDEFINED) {
            return operation;
        }
        if (WITH_PATTERN.matcher(rawSql).matches()) {   // Remove potential WITH clause and retry.
            operation = getSqlOperation(removeWithClause(rawSql, dialect));
        }
        return operation;
    }

    /**
     * Returns whether {@code sql} has a WHERE clause at the top level of the statement. A WHERE inside parentheses
     * belongs to a subquery and says nothing about the statement itself: an unqualified UPDATE or DELETE whose only
     * WHERE sits in a subquery still touches every row, which is exactly what the safety check exists to flag.
     */
    static boolean hasWhereClause(String sql, SqlDialect dialect) {
        String cleared = clearStringLiterals(clearQuotedIdentifiers(removeComments(sql, dialect), dialect), dialect);
        int depth = 0;
        for (int i = 0; i < cleared.length(); i++) {
            char ch = cleared.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (depth == 0
                    && (ch == 'W' || ch == 'w')
                    && cleared.regionMatches(true, i, "WHERE", 0, 5)
                    && (i == 0 || !isIdentifierChar(cleared.charAt(i - 1)))
                    && (i + 5 == cleared.length() || !isIdentifierChar(cleared.charAt(i + 5)))) {
                return true;
            }
        }
        return false;
    }

    private static String removeWithClause(String sql, SqlDialect dialect) {
        sql = clearStringLiterals(clearQuotedIdentifiers(sql, dialect), dialect);
        assert sql.trim().toUpperCase().startsWith("WITH");
        int depth = 0; // Depth of nested parentheses.
        int startIndex = sql.indexOf('('); // Find the first opening parenthesis.
        // If there's no opening parenthesis after "WITH", return the original string.
        if (startIndex == -1) {
            return sql;
        }
        for (int i = startIndex; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    // Found the matching closing parenthesis for the first opening parenthesis.
                    String afterWithClause = sql.substring(i + 1).trim();
                    // Check if it needs to remove a comma right after the closing parenthesis of WITH clause.
                    if (afterWithClause.startsWith(",")) {
                        afterWithClause = afterWithClause.substring(1).trim();
                    }
                    return afterWithClause;
                }
            }
        }
        // If depth never reaches 0, return the original string as it might be malformed or the logic above didn't
        // correctly parse it.
        return sql;
    }

    private static String replaceAll(String sql, Pattern pattern, String replacement) {
        return pattern.matcher(sql).replaceAll(replacement);
    }

    /**
     * Returns whether {@code sql} contains any of the given marker characters. Used to skip regex passes on
     * fragments that cannot contain the construct being removed.
     */
    private static boolean containsAny(String sql, char first, char second, char third) {
        for (int i = 0, length = sql.length(); i < length; i++) {
            char c = sql.charAt(i);
            if (c == first || c == second || c == third) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes both single-line and multi-line comments from a SQL string.
     *
     * @param sql the original SQL string.
     * @param dialect the SQL dialect.
     * @return the SQL string with comments removed.
     */
    static String removeComments(String sql, SqlDialect dialect) {
        // Every supported comment syntax starts with '-' (--), '/' (/*) or '#' (a hash comment); skip the regex passes when
        // none of these characters appear at all.
        if (!containsAny(sql, '-', '/', '#')) {
            return sql;
        }
        // Remove multi-line comments, then single-line comments.
        return replaceAll(
                replaceAll(sql, dialect.getMultiLineCommentPattern(), ""),
                    dialect.getSingleLineCommentPattern(), "");
    }

    /**
     * Replaces all double-quoted identifiers with an empty double-quoted placeholder ("").
     *
     * <p>This is roughly analogous to removing the content of double-quoted identifiers
     * in an ANSI SQL statement while leaving "" as a placeholder.</p>
     *
     * @param sql the original SQL string.
     * @param dialect the SQL dialect.
     * @return the modified SQL string.
     */
    static String clearQuotedIdentifiers(String sql, SqlDialect dialect) {
        // Every supported quoted-identifier syntax starts with '"', '`' or '['; skip the regex
        // pass when none of these characters appear at all.
        if (!containsAny(sql, '"', '`', '[')) {
            return sql;
        }
        return replaceAll(sql, dialect.getIdentifierPattern(), dialect.escape(""));
    }

    /**
     * Replaces all single-quoted string literals with an empty string literal ('').
     *
     * @param sql the original SQL string.
     * @param dialect the SQL dialect.
     * @return the modified SQL string.
     */
    static String clearStringLiterals(String sql, SqlDialect dialect) {
        if (sql.indexOf('\'') < 0) {
            return sql;
        }
        return replaceAll(sql, dialect.getQuoteLiteralPattern(), "''");
    }
}
