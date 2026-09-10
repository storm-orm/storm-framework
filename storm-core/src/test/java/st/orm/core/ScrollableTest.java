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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Order;
import st.orm.Scrollable;
import st.orm.core.template.impl.PositionImpl;

class ScrollableTest {

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

    @SuppressWarnings("unchecked")
    private static <T extends Data, S> Metamodel<T, S> stubSort(Class<S> fieldType, String fieldPath) {
        return (Metamodel<T, S>) Proxy.newProxyInstance(
                Metamodel.class.getClassLoader(),
                new Class<?>[] { Metamodel.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "fieldType" -> fieldType;
                    case "fieldPath" -> fieldPath;
                    default -> null;
                });
    }

    private static final Metamodel.Key<Data, Integer> KEY = stubKey(Integer.class, "id");
    private static final Metamodel<Data, String> SORT = stubSort(String.class, "name");

    // Factory and ordering

    @Test
    void ofOrdersByTheKeyAscendingFromTheStart() {
        var scrollable = Scrollable.of(KEY, 20);
        assertFalse(scrollable.keyDescending());
        assertTrue(scrollable.sort().isEmpty());
        assertNull(scrollable.position());
        assertEquals(20, scrollable.size());
        assertEquals(List.of(new Order(KEY, false)), scrollable.orders());
    }

    @Test
    void descendingFlipsTheKeyOnly() {
        var scrollable = Scrollable.of(KEY, 20).sortBy(SORT).descending();
        assertTrue(scrollable.keyDescending());
        assertEquals(List.of(Order.asc(SORT), new Order(KEY, true)), scrollable.orders());
    }

    @Test
    void sortFieldsKeepTheirOwnDirectionAndPrecedence() {
        var last = stubSort(String.class, "lastName");
        var first = stubSort(String.class, "firstName");
        var scrollable = Scrollable.of(KEY, 10).sortByDescending(last).sortBy(first);
        assertEquals(List.of(Order.desc(last), Order.asc(first), new Order(KEY, false)), scrollable.orders());
    }

    @Test
    void ofRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> Scrollable.of(KEY, 0));
        assertThrows(IllegalArgumentException.class, () -> Scrollable.of(KEY, -1));
        assertThrows(IllegalArgumentException.class, () -> Scrollable.of(KEY, 5).size(0));
    }

    @Test
    void ofRejectsNullKey() {
        assertThrows(NullPointerException.class, () -> Scrollable.of(null, 10));
    }

    // Positions

    @Test
    void afterAndBeforeCarryOneValuePerFieldThenTheKey() {
        var after = Scrollable.of(KEY, 20).sortBy(SORT).after("Carter", 3);
        assertEquals(List.of("Carter", 3), PositionImpl.of(after.position()).values());
        assertTrue(after.position().after());
        var before = Scrollable.of(KEY, 20).sortBy(SORT).before("Carter", 3);
        assertEquals(List.of("Carter", 3), PositionImpl.of(before.position()).values());
        assertFalse(before.position().after());
    }

    @Test
    void positionMustMatchTheOrdering() {
        assertThrows(IllegalArgumentException.class, () -> Scrollable.of(KEY, 20).after("Carter", 3));
        assertThrows(IllegalArgumentException.class, () -> Scrollable.of(KEY, 20).sortBy(SORT).after(3));
        assertThrows(IllegalArgumentException.class, () -> new PositionImpl(List.of(), true));
    }

    @Test
    void sortFieldsComeBeforeThePosition() {
        assertThrows(IllegalStateException.class, () -> Scrollable.of(KEY, 20).after(3).sortBy(SORT));
    }

    @Test
    void sizeMayChangeWithoutTouchingThePosition() {
        var scrollable = Scrollable.of(KEY, 20).after(3).size(50);
        assertEquals(50, scrollable.size());
        assertEquals(Scrollable.of(KEY, 20).after(3).position(), scrollable.position());
    }

    @Test
    void toCursorNeedsAPosition() {
        assertThrows(IllegalStateException.class, () -> Scrollable.of(KEY, 20).toCursor());
    }
}
