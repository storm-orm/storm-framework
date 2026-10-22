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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.Operator;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.SqlArray;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.impl.InListShape.Mode;

/**
 * An IN list is padded to the next power of two by repeating its last value, within the dialect's limits, or bound
 * as one array where the dialect binds one; an exact mode keeps every length.
 */
class InListShapeTest {

    enum Color { RED, GREEN }

    /** A dialect that binds integers as arrays. */
    private static final SqlDialect ARRAYS = new DefaultSqlDialect() {
        @Override
        public @Nullable String arrayElementType(Class<?> type) {
            return type == Integer.class ? "int4" : null;
        }
    };

    private static final SqlDialect NO_ARRAYS = new DefaultSqlDialect();

    @Test
    void aListPadsToTheNextPowerOfTwoByRepeatingItsLastValue() {
        assertEquals(List.of(), InListShape.pad(List.of(), 1_000));
        assertEquals(List.of(1), InListShape.pad(List.of(1), 1_000));
        assertEquals(List.of(1, 2), InListShape.pad(List.of(1, 2), 1_000));
        assertEquals(List.of(1, 2, 3, 3), InListShape.pad(List.of(1, 2, 3), 1_000));
        assertEquals(List.of(1, 2, 3, 4), InListShape.pad(List.of(1, 2, 3, 4), 1_000));
        assertEquals(List.of(1, 2, 3, 4, 5, 5, 5, 5), InListShape.pad(List.of(1, 2, 3, 4, 5), 1_000));
        assertEquals(16, InListShape.pad(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), 1_000).size());
    }

    @Test
    void aListKeepsItsLengthWherePaddingWouldPassTheLimit() {
        var seven = List.of(1, 2, 3, 4, 5, 6, 7);
        assertSame(seven, InListShape.pad(seven, 7));
        assertEquals(8, InListShape.pad(seven, 8).size());
    }

    @Test
    void theModeComesFromTheConfiguration() {
        assertEquals(Mode.AUTO, Mode.of(StormConfig.defaults()));
        assertEquals(Mode.PADDED, Mode.of(StormConfig.of(Map.of(StormConfig.IN_LIST, "padded"))));
        assertEquals(Mode.EXACT, Mode.of(StormConfig.of(Map.of(StormConfig.IN_LIST, " Exact "))));
        var invalid = assertThrows(IllegalArgumentException.class,
                () -> Mode.of(StormConfig.of(Map.of(StormConfig.IN_LIST, "chunked"))));
        assertTrue(invalid.getMessage().contains("storm.in_list"), invalid.getMessage());
    }

    @Test
    void onlyTheListOperatorsAreShaped() {
        assertTrue(InListShape.applies(Operator.IN));
        assertTrue(InListShape.applies(Operator.NOT_IN));
        assertTrue(!InListShape.applies(Operator.EQUALS));
    }

    @Test
    void aListBindsAsOneArrayWhereTheDialectBindsOne() {
        var shape = InListShape.shape(Mode.AUTO, List.of(1, 2, 3), false, ARRAYS);
        assertNotNull(shape.array());
        assertEquals("int4", shape.array().elementType());
        assertArrayEquals(new Object[] {1, 2, 3}, shape.array().elements());
        assertEquals(List.of(), shape.values());
    }

    @Test
    void aListKeepsItsValuesWhereTheDialectBindsNoArrayAndPadsInThePaddedMode() {
        assertNull(InListShape.shape(Mode.AUTO, List.of(1, 2, 3), false, NO_ARRAYS).array());
        assertEquals(List.of(1, 2, 3), InListShape.shape(Mode.AUTO, List.of(1, 2, 3), false, NO_ARRAYS).values());
        assertEquals(List.of(1, 2, 3, 3), InListShape.shape(Mode.PADDED, List.of(1, 2, 3), false, NO_ARRAYS).values());
        // The padded mode keeps the array where the dialect binds one.
        assertNotNull(InListShape.shape(Mode.PADDED, List.of(1, 2, 3), false, ARRAYS).array());
        assertEquals(List.of(1, 2, 3), InListShape.shape(Mode.EXACT, List.of(1, 2, 3), false, ARRAYS).values());
    }

    @Test
    void aColumnComparedThroughAFunctionBindsNoArray() {
        var shape = InListShape.shape(Mode.AUTO, List.of(1, 2, 3), true, ARRAYS);
        assertNull(shape.array());
        assertEquals(List.of(1, 2, 3), shape.values());
        assertEquals(List.of(1, 2, 3, 3), InListShape.shape(Mode.PADDED, List.of(1, 2, 3), true, ARRAYS).values());
    }

    @Test
    void anArrayNeedsOneTypeTheDialectBinds() {
        assertNull(SqlArray.of(List.of(1, 2L), ARRAYS));
        assertNull(SqlArray.of(List.of("a", "b"), ARRAYS));
        assertNull(SqlArray.of(Arrays.asList(null, null), ARRAYS));
        var withNull = SqlArray.of(Arrays.asList(1, null, 3), ARRAYS);
        assertNotNull(withNull);
        assertArrayEquals(new Object[] {1, null, 3}, withNull.elements());
    }

    @Test
    void anEnumBindsByItsName() {
        var dialect = new DefaultSqlDialect() {
            @Override
            public @Nullable String arrayElementType(Class<?> type) {
                return type == String.class ? "text" : null;
            }
        };
        var array = SqlArray.of(List.of(Color.RED, Color.GREEN), dialect);
        assertNotNull(array);
        assertArrayEquals(new Object[] {"RED", "GREEN"}, array.elements());
        assertEquals("{\"RED\",\"GREEN\"}", array.toString());
        assertEquals("{1,NULL,3}", new SqlArray("int4", new Object[] {1, null, 3}).toString());
    }

    @Test
    void rowsOfACompoundKeyPadByRepeatingTheLastRow() {
        var rows = List.of("a", "b", "c");
        assertEquals(List.of("a", "b", "c", "c"), InListShape.padRows(Mode.PADDED, rows, 2, NO_ARRAYS));
        assertSame(rows, InListShape.padRows(Mode.AUTO, rows, 2, NO_ARRAYS));
        assertSame(rows, InListShape.padRows(Mode.EXACT, rows, 2, NO_ARRAYS));
    }
}
