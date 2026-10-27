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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The intervals of a repeat give the time its executions covered on the clock, and stay bounded however many
 * executions a long unit of work runs: past their capacity they merge where they overlap, and join across the
 * shortest gaps, which may count a few gaps as covered but never less than the executions took.
 */
class RepeatIntervalsTest {

    @Test
    void overlappingExecutionsCoverTheirUnion() {
        var intervals = new Repeats.Intervals();
        intervals.add(0, 10);
        intervals.add(5, 15);
        intervals.add(20, 30);
        assertEquals(25, Repeats.Intervals.union(List.of(intervals)));
    }

    @Test
    void thePartsOfARepeatCoverTheirUnionTogether() {
        var first = new Repeats.Intervals();
        first.add(0, 10);
        var second = new Repeats.Intervals();
        second.add(5, 20);
        assertEquals(20, Repeats.Intervals.union(List.of(first, second)));
    }

    @Test
    void concurrentExecutionsPastTheCapacityStayExact() {
        var intervals = new Repeats.Intervals();
        // Ten thousand executions in flight at once over the same second: they merge into one interval.
        for (int i = 0; i < 10_000; i++) {
            intervals.add(i, 1_000_000 + i);
        }
        assertEquals(1_000_000 + 9_999, Repeats.Intervals.union(List.of(intervals)));
    }

    @Test
    void sequentialExecutionsPastTheCapacityStayBoundedAndNeverUndercount() {
        var intervals = new Repeats.Intervals();
        long covered = 0;
        for (int i = 0; i < 100_000; i++) {
            // One millisecond each, separated by gaps of one to seven microseconds.
            long start = i * 1_010_000L + (i % 7) * 1_000L;
            intervals.add(start, start + 1_000_000L);
            covered += 1_000_000L;
        }
        long union = Repeats.Intervals.union(List.of(intervals));
        assertTrue(union >= covered, union + " < " + covered);
        // The joined gaps are the shortest ones, a small part of the whole.
        assertTrue(union <= covered * 102 / 100, union + " > " + covered * 102 / 100);
    }
}
