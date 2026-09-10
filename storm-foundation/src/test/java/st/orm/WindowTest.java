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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowTest {

    @SuppressWarnings("unchecked")
    private static <T extends Data, E> Metamodel.Key<T, E> stubKey(Class<E> fieldType, String fieldPath) {
        return (Metamodel.Key<T, E>) Proxy.newProxyInstance(
                Metamodel.Key.class.getClassLoader(),
                new Class<?>[] { Metamodel.Key.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "isNullable" -> false;
                    case "fieldType" -> fieldType;
                    case "fieldPath" -> fieldPath;
                    default -> null;
                });
    }

    private static final Metamodel.Key<Data, Integer> KEY = stubKey(Integer.class, "id");

    @Test
    void emptyWindowHasNoNavigation() {
        var window = new Window<>(List.of(), false, false, null, null);
        assertTrue(window.content().isEmpty());
        assertTrue(window.isEmpty());
        assertEquals(0, window.size());
        assertFalse(window.hasNext());
        assertFalse(window.hasPrevious());
        assertNull(window.nextScrollable());
        assertNull(window.previousScrollable());
    }

    @Test
    void windowWithNextScrollableHasNext() {
        var next = Scrollable.of(KEY, 20);
        var window = new Window<>(List.of("a", "b"), true, false, next, null);
        assertTrue(window.hasNext());
        assertFalse(window.hasPrevious());
        assertNotNull(window.nextScrollable());
        assertNull(window.previousScrollable());
    }

    @Test
    void windowWithPreviousScrollableHasPrevious() {
        var prev = Scrollable.of(KEY, 10);
        var window = new Window<>(List.of("a", "b"), false, true, null, prev);
        assertFalse(window.hasNext());
        assertTrue(window.hasPrevious());
        assertNull(window.nextScrollable());
        assertNotNull(window.previousScrollable());
    }

    @Test
    void windowWithBothNavigations() {
        var next = Scrollable.of(KEY, 20);
        var prev = Scrollable.of(KEY, 10);
        var window = new Window<>(List.of("a", "b"), true, true, next, prev);
        assertTrue(window.hasNext());
        assertTrue(window.hasPrevious());
    }

    @Test
    void contentIsImmutable() {
        var list = new ArrayList<>(List.of("a", "b"));
        var window = new Window<>(list, false, false, null, null);
        list.add("c");
        assertEquals(2, window.content().size());
    }

    @Test
    void windowIteratesOverItsContent() {
        var window = new Window<>(List.of("a", "b"), false, false, null, null);
        var seen = new ArrayList<String>();
        for (var element : window) {
            seen.add(element);
        }
        assertEquals(List.of("a", "b"), seen);
        assertEquals(List.of("a", "b"), window.stream().toList());
        assertEquals(2, window.size());
    }

    @Test
    void nextReturnsTypedScrollable() {
        var scrollable = Scrollable.of(KEY, 20);
        var window = new Window<>(List.of("a", "b"), true, false, scrollable, null);
        Scrollable<Data> typed = window.next();
        assertNotNull(typed);
        assertEquals(scrollable, typed);
        assertEquals(20, typed.size());
    }

    @Test
    void previousReturnsTypedScrollable() {
        var scrollable = Scrollable.of(KEY, 10);
        var window = new Window<>(List.of("a", "b"), false, true, null, scrollable);
        Scrollable<Data> typed = window.previous();
        assertNotNull(typed);
        assertEquals(scrollable, typed);
    }

    @Test
    void nextReturnsNullForEmptyWindow() {
        var window = new Window<>(List.of(), false, false, null, null);
        assertNull(window.<Data>next());
        assertNull(window.<Data>previous());
    }

    @Test
    void nextCursorIsNullWhenNoNext() {
        var window = new Window<>(List.of("a"), false, false, null, null);
        assertNull(window.nextCursor());
    }

    @Test
    void previousCursorIsNullWhenNoPrevious() {
        var window = new Window<>(List.of("a"), false, false, null, null);
        assertNull(window.previousCursor());
    }
}
