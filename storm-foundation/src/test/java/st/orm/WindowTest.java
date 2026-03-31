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
        assertFalse(window.hasNext());
        assertFalse(window.hasPrevious());
        assertNull(window.nextScrollable());
        assertNull(window.previousScrollable());
    }

    @Test
    void windowWithNextScrollableHasNext() {
        var next = Scrollable.of(KEY, 42, 20);
        var window = new Window<>(List.of("a", "b"), true, false, next, null);
        assertTrue(window.hasNext());
        assertFalse(window.hasPrevious());
        assertNotNull(window.nextScrollable());
        assertNull(window.previousScrollable());
    }

    @Test
    void windowWithPreviousScrollableHasPrevious() {
        var prev = Scrollable.of(KEY, 1, 20).backward();
        var window = new Window<>(List.of("a", "b"), false, true, null, prev);
        assertFalse(window.hasNext());
        assertTrue(window.hasPrevious());
        assertNull(window.nextScrollable());
        assertNotNull(window.previousScrollable());
    }

    @Test
    void windowWithBothNavigations() {
        var next = Scrollable.of(KEY, 42, 20);
        var prev = Scrollable.of(KEY, 1, 20).backward();
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
