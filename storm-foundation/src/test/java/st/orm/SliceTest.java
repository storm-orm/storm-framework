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
package st.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SliceTest {

    @Test
    void carriesContentAndFlags() {
        var slice = Slice.of(List.of("a", "b"), true, false);
        assertEquals(List.of("a", "b"), slice.content());
        assertTrue(slice.hasNext());
        assertFalse(slice.hasPrevious());
        assertEquals(2, slice.size());
        assertFalse(slice.isEmpty());
        assertEquals(List.of("a", "b"), slice.stream().toList());
        var seen = new ArrayList<String>();
        for (var element : slice) {
            seen.add(element);
        }
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void contentIsAnImmutableCopy() {
        var original = new ArrayList<>(List.of("a"));
        var slice = Slice.of(original, false, true);
        original.add("b");
        assertEquals(1, slice.size());
        assertTrue(slice.hasPrevious());
        assertThrows(UnsupportedOperationException.class, () -> slice.content().add("c"));
    }

    @Test
    void pageAndWindowAreSlices() {
        Slice<String> page = new Page<>(List.of("a"), 1, 0, 10);
        Slice<String> window = new Window<>(List.of("a"), false, false, null, null);
        assertEquals(page.content(), window.content());
        assertTrue(Slice.of(List.of(), false, false).isEmpty());
    }
}
