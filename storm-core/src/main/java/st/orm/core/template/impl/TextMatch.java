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

import static st.orm.Operator.CONTAINS;
import static st.orm.Operator.ENDS_WITH;
import static st.orm.Operator.NOT_CONTAINS;
import static st.orm.Operator.NOT_ENDS_WITH;
import static st.orm.Operator.NOT_STARTS_WITH;
import static st.orm.Operator.STARTS_WITH;

import org.jspecify.annotations.Nullable;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.SqlTemplateException;
import st.orm.core.template.SqlDialect;

/**
 * Turns the text a text-matching operator compares against into the {@code LIKE} pattern it binds. The operators
 * render {@code LIKE ? ESCAPE '!'}; the pattern is the text escaped by the dialect, with the wildcards the operator
 * implies around it. The compiled and the bound value both pass through here, so a statement compiled with inline
 * parameters and one bound per execution match alike.
 *
 * @since 1.15
 */
final class TextMatch {

    private TextMatch() {
    }

    /**
     * Returns {@code true} if the operator matches literal text, and so binds a pattern built from its value.
     *
     * @param operator the operator of the comparison.
     * @return {@code true} for the text-matching operators.
     */
    static boolean isTextMatch(Operator operator) {
        return operator == CONTAINS || operator == NOT_CONTAINS
                || operator == STARTS_WITH || operator == NOT_STARTS_WITH
                || operator == ENDS_WITH || operator == NOT_ENDS_WITH;
    }

    /**
     * Returns the value to bind for a comparison: the pattern for a text-matching operator, the value itself for any
     * other operator.
     *
     * @param operator the operator of the comparison.
     * @param value the value the comparison was given, as the column holds it.
     * @param path the path of the compared column, named when the value is not text.
     * @param dialect the dialect that escapes the text.
     * @return the value to bind.
     * @throws SqlTemplateException if a text-matching operator is given a value that is not text.
     */
    static @Nullable Object bindValue(Operator operator,
                                      @Nullable Object value,
                                      Metamodel<?, ?> path,
                                      SqlDialect dialect) throws SqlTemplateException {
        if (!isTextMatch(operator)) {
            return value;
        }
        if (!(value instanceof String text)) {
            throw new SqlTemplateException("%s compares %s against text, but was given %s. Pass a String, or use EQUALS or LIKE for other values."
                    .formatted(name(operator), path.fieldPath(), value == null ? "null" : value.getClass().getSimpleName()));
        }
        String escaped = dialect.escapeLike(text);
        if (operator == CONTAINS || operator == NOT_CONTAINS) {
            return "%" + escaped + "%";
        }
        if (operator == STARTS_WITH || operator == NOT_STARTS_WITH) {
            return escaped + "%";
        }
        return "%" + escaped;
    }

    /**
     * Refuses a text-matching comparison over a path that spans several columns, which has no single text to match.
     *
     * @param operator the operator of the comparison.
     * @param path the path of the compared columns.
     * @throws SqlTemplateException if the operator matches text.
     */
    static void requireSingleColumn(Operator operator, Metamodel<?, ?> path) throws SqlTemplateException {
        if (isTextMatch(operator)) {
            throw new SqlTemplateException("%s compares a single column against text, but %s spans several columns. Name one of its fields instead."
                    .formatted(name(operator), path.fieldPath()));
        }
    }

    private static String name(Operator operator) {
        if (operator == CONTAINS) return "CONTAINS";
        if (operator == NOT_CONTAINS) return "NOT_CONTAINS";
        if (operator == STARTS_WITH) return "STARTS_WITH";
        if (operator == NOT_STARTS_WITH) return "NOT_STARTS_WITH";
        if (operator == ENDS_WITH) return "ENDS_WITH";
        return "NOT_ENDS_WITH";
    }
}
