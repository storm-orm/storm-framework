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
import org.jspecify.annotations.Nullable;
import st.orm.Operator;
import st.orm.StormConfig;
import st.orm.core.template.SqlArray;
import st.orm.core.template.SqlDialect;

/**
 * The shape an {@code IN} list renders in, so that the statement does not change with the length of the list.
 *
 * <p>An {@code IN} list with one placeholder per value is a different statement to the database for every length,
 * each parsed and planned on its own and each taking a slot in the statement caches of the database and the driver.
 * The list renders as one array parameter where the dialect binds one, {@code column = ANY(?)}, one statement for
 * every length, which also binds fewer parameters than a list of placeholders. Elsewhere it keeps a placeholder per
 * value, or, in the padded mode, is padded to the next power of two by repeating its last value, which changes no
 * result, since membership of a set is not changed by a repeated member, for {@code IN}, {@code NOT IN} and a list
 * holding {@code NULL} alike: lengths 1 to 1,000 render as 11 statements instead of 1,000, at the cost of the
 * parameters the padding binds. A list of one or two values is a power of two already. A padded list never exceeds
 * what the dialect allows in a list or a statement; where the next power of two would, the list keeps its length.</p>
 *
 * <p>The compilation of a statement and its binding shape a list the same way, as both go through here with the
 * same values, so the placeholders and the bound values agree.</p>
 *
 * @since 1.16
 */
final class InListShape {

    private InListShape() {
    }

    /** How an {@code IN} list renders, as {@link StormConfig#IN_LIST} selects it. */
    enum Mode {

        /** An array parameter where the dialect binds one, one placeholder per value elsewhere. */
        AUTO,

        /** An array parameter where the dialect binds one, padding to a power of two elsewhere. */
        PADDED,

        /** One placeholder per value. */
        EXACT;

        /**
         * Returns the mode the configuration selects.
         *
         * @param config the configuration.
         * @return the mode; {@link #AUTO} when none is configured.
         * @throws IllegalArgumentException if the configured value names no mode.
         */
        static Mode of(StormConfig config) {
            String value = config.getProperty(StormConfig.IN_LIST);
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "padded" -> PADDED;
                case "exact" -> EXACT;
                default -> throw new IllegalArgumentException(
                        "Invalid value '%s' for %s. Valid values are: auto, padded, exact."
                                .formatted(value, StormConfig.IN_LIST));
            };
        }
    }

    /**
     * The shape of one list: the values to render and bind one placeholder each, or the array to bind as one
     * parameter.
     *
     * @param values the values, padded where the shape pads; empty when the list binds as an array.
     * @param array the array the list binds as, or {@code null} when it binds its values one by one.
     */
    record Shape(List<Object> values, @Nullable SqlArray array) {
    }

    /**
     * Returns whether the operator compares a column against a list, whose shape is this class's concern.
     *
     * @param operator the operator.
     * @return {@code true} for {@code IN} and {@code NOT IN}.
     */
    static boolean applies(Operator operator) {
        return operator == Operator.IN || operator == Operator.NOT_IN;
    }

    /**
     * Returns the shape of a single-column list.
     *
     * @param mode the configured mode.
     * @param values the values of the list, in order.
     * @param functionApplied whether the column is compared through a SQL function call, which is applied to each
     *                        value, so the values cannot bind as one array.
     * @param dialect the dialect of the database.
     * @return the shape.
     */
    static Shape shape(Mode mode, List<Object> values, boolean functionApplied, SqlDialect dialect) {
        if (mode == Mode.EXACT) {
            return new Shape(values, null);
        }
        if (!functionApplied && !values.isEmpty()) {
            var array = SqlArray.of(values, dialect);
            if (array != null) {
                return new Shape(List.of(), array);
            }
        }
        return new Shape(mode == Mode.PADDED ? pad(values, limit(dialect, 1)) : values, null);
    }

    /**
     * Returns the rows of a multi-column list, padded by repeating the last row where the mode pads.
     *
     * @param mode the configured mode.
     * @param rows the rows of the list, each a row of column values.
     * @param dialect the dialect of the database.
     * @param <R> the row type.
     * @return the rows, padded or as given.
     */
    static <R> List<R> padRows(Mode mode, List<R> rows, int columns, SqlDialect dialect) {
        if (mode != Mode.PADDED) {
            return rows;
        }
        return pad(rows, limit(dialect, columns));
    }

    /**
     * Pads the list to the next power of two by repeating its last element, unless it has fewer than three elements,
     * which is a power of two already, or the next power of two exceeds the limit.
     *
     * @param values the values.
     * @param limit the most elements the padded list may hold.
     * @param <T> the element type.
     * @return the padded list, or the list as given.
     */
    static <T> List<T> pad(List<T> values, long limit) {
        int size = values.size();
        if (size < 3) {
            return values;
        }
        long padded = Integer.highestOneBit(size - 1) << 1;
        if (padded > limit) {
            return values;
        }
        var result = new ArrayList<T>((int) padded);
        result.addAll(values);
        T last = values.getLast();
        for (long i = size; i < padded; i++) {
            result.add(last);
        }
        return result;
    }

    /** The most elements a list of rows of the given width may hold on the dialect. */
    private static long limit(SqlDialect dialect, int columns) {
        return Math.min(dialect.maxInListElements(), dialect.maxBindParameters() / Math.max(1, columns));
    }
}
