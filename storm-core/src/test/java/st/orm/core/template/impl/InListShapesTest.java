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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A statement shape carries the IN list advice once its list has rendered with eight distinct lengths, and reports
 * how many it has rendered with as a fact.
 */
class InListShapesTest {

    @AfterEach
    void forget() {
        InListShapes.reset();
    }

    @Test
    void aShapeCarriesTheAdviceFromItsEighthDistinctLength() {
        for (int length = 1; length <= 7; length++) {
            assertNull(InListShapes.observe(7, length), "length " + length);
        }
        var advisory = InListShapes.observe(7, 8);
        assertNotNull(advisory);
        assertTrue(advisory.startsWith("the statement runs with varying lengths of its IN list"), advisory);
        assertTrue(advisory.endsWith(" See " + Advice.PAGE + "#in-list-shapes"), advisory);
        assertEquals(8, InListShapes.lengths(7));
    }

    @Test
    void aRepeatedLengthDoesNotCount() {
        for (int i = 0; i < 100; i++) {
            assertNull(InListShapes.observe(7, 3));
        }
    }

    @Test
    void theAdviceIsOneTextWhileTheCountOfLengthsKeepsGrowing() {
        for (int length = 1; length <= 8; length++) {
            InListShapes.observe(7, length);
        }
        var first = InListShapes.observe(7, 200);
        assertSame(first, InListShapes.observe(7, 300));
        assertEquals(10, InListShapes.lengths(7));
        assertEquals(0, InListShapes.lengths(8));
        assertEquals(0, InListShapes.lengths(0));
    }

    @Test
    void shapesAreRecordedApart() {
        for (int length = 1; length <= 8; length++) {
            InListShapes.observe(7, length);
        }
        assertNull(InListShapes.observe(8, 8));
    }

    @Test
    void aStatementWithoutAShapeIsNotRecorded() {
        for (int length = 1; length <= 40; length++) {
            assertNull(InListShapes.observe(0, length));
        }
    }

    @Test
    void lengthsBeyondTheRangeShareOneLength() {
        for (int length = 1; length <= 6; length++) {
            InListShapes.observe(7, length);
        }
        // The seventh distinct length, and the same length again.
        assertNull(InListShapes.observe(7, InListShapes.MAX_LENGTH + 1));
        assertNull(InListShapes.observe(7, InListShapes.MAX_LENGTH + 2));
        assertNotNull(InListShapes.observe(7, 8));
    }

    @Test
    void theThresholdIsEight() {
        assertEquals(8, Advice.IN_LIST_LENGTHS);
    }
}
