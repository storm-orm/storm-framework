package st.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class PageableTest {

    // Minimal Metamodel stubs for sort tests.
    private static final Metamodel<?, ?> FIELD_A = stubMetamodel();
    private static final Metamodel<?, ?> FIELD_B = stubMetamodel();

    @SuppressWarnings("unchecked")
    private static Metamodel<?, ?> stubMetamodel() {
        return (Metamodel<?, ?>) Proxy.newProxyInstance(
                Metamodel.class.getClassLoader(),
                new Class<?>[] { Metamodel.class },
                (proxy, method, args) -> null);
    }

    @Test
    void ofCreatesPageable() {
        Pageable pageable = Pageable.of(2, 10);
        assertEquals(2, pageable.pageNumber());
        assertEquals(10, pageable.pageSize());
    }

    @Test
    void ofSizeCreatesFirstPage() {
        Pageable pageable = Pageable.ofSize(10);
        assertEquals(0, pageable.pageNumber());
        assertEquals(10, pageable.pageSize());
    }

    @Test
    void nextIncrementsPageNumber() {
        Pageable pageable = Pageable.of(2, 10);
        Pageable next = pageable.next();
        assertEquals(3, next.pageNumber());
        assertEquals(10, next.pageSize());
    }

    @Test
    void previousDecrementsPageNumber() {
        Pageable pageable = Pageable.of(2, 10);
        Pageable previous = pageable.previous();
        assertEquals(1, previous.pageNumber());
        assertEquals(10, previous.pageSize());
    }

    @Test
    void previousOnFirstPageIsNull() {
        Pageable pageable = Pageable.ofSize(10);
        assertNull(pageable.previous());
    }

    @Test
    void offsetComputation() {
        assertEquals(0, Pageable.of(0, 10).offset());
        assertEquals(10, Pageable.of(1, 10).offset());
        assertEquals(50, Pageable.of(5, 10).offset());
        assertEquals(75, Pageable.of(3, 25).offset());
    }

    @Test
    void negativePageNumberThrows() {
        assertThrows(IllegalArgumentException.class, () -> Pageable.of(-1, 10));
    }

    @Test
    void zeroPageSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> Pageable.of(0, 0));
    }

    @Test
    void negativePageSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> Pageable.of(0, -1));
    }

    @Test
    void sortByAddsAscendingOrder() {
        Pageable pageable = Pageable.ofSize(10).sortBy(FIELD_A);
        assertEquals(1, pageable.orders().size());
        assertSame(FIELD_A, pageable.orders().getFirst().field());
        assertFalse(pageable.orders().getFirst().descending());
    }

    @Test
    void sortByDescendingAddsDescendingOrder() {
        Pageable pageable = Pageable.ofSize(10).sortByDescending(FIELD_A);
        assertEquals(1, pageable.orders().size());
        assertTrue(pageable.orders().getFirst().descending());
    }

    @Test
    void multipleOrdersAccumulate() {
        Pageable pageable = Pageable.ofSize(10)
                .sortBy(FIELD_A)
                .sortByDescending(FIELD_B);
        assertEquals(2, pageable.orders().size());
        assertSame(FIELD_A, pageable.orders().get(0).field());
        assertFalse(pageable.orders().get(0).descending());
        assertSame(FIELD_B, pageable.orders().get(1).field());
        assertTrue(pageable.orders().get(1).descending());
    }

    @Test
    void sortByDoesNotMutateOriginal() {
        Pageable original = Pageable.ofSize(10);
        Pageable sorted = original.sortBy(FIELD_A);
        assertTrue(original.orders().isEmpty());
        assertEquals(1, sorted.orders().size());
    }

    @Test
    void nextPreservesSortOrders() {
        Pageable pageable = Pageable.ofSize(10).sortBy(FIELD_A).sortByDescending(FIELD_B);
        Pageable next = pageable.next();
        assertEquals(1, next.pageNumber());
        assertEquals(2, next.orders().size());
        assertSame(FIELD_A, next.orders().get(0).field());
        assertSame(FIELD_B, next.orders().get(1).field());
    }

    @Test
    void previousPreservesSortOrders() {
        Pageable pageable = Pageable.of(2, 10).sortBy(FIELD_A);
        Pageable previous = pageable.previous();
        assertEquals(1, previous.pageNumber());
        assertEquals(1, previous.orders().size());
        assertSame(FIELD_A, previous.orders().getFirst().field());
    }

    @Test
    void ordersListIsUnmodifiable() {
        Pageable pageable = Pageable.ofSize(10).sortBy(FIELD_A);
        assertThrows(UnsupportedOperationException.class,
                () -> pageable.orders().add(new Order(FIELD_B, false)));
    }
}
