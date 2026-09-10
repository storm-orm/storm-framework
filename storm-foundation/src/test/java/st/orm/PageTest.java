package st.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void pageWithContent() {
        Page<String> page = new Page<>(List.of("a", "b", "c"), 10, 0, 3);
        assertEquals(3, page.content().size());
        assertEquals("a", page.content().get(0));
        assertEquals(10, page.totalCount());
        assertEquals(0, page.pageNumber());
        assertEquals(3, page.pageSize());
    }

    @Test
    void pageWithEmptyContent() {
        Page<String> page = new Page<>(List.of(), 0, 0, 10);
        assertTrue(page.content().isEmpty());
        assertEquals(0, page.totalCount());
    }

    @Test
    void pageContentIsImmutableCopy() {
        List<String> original = new ArrayList<>(List.of("a", "b"));
        Page<String> page = new Page<>(original, 2, 0, 10);
        original.add("c");
        assertEquals(2, page.content().size());
    }

    @Test
    void pageContentIsUnmodifiable() {
        Page<String> page = new Page<>(List.of("a"), 1, 0, 10);
        assertThrows(UnsupportedOperationException.class, () -> page.content().add("b"));
    }

    @Test
    void totalPagesRoundsUp() {
        Page<String> page = new Page<>(List.of("a"), 11, 0, 10);
        assertEquals(2, page.totalPages());
    }

    @Test
    void totalPagesExactFit() {
        Page<String> page = new Page<>(List.of("a"), 10, 0, 10);
        assertEquals(1, page.totalPages());
    }

    @Test
    void totalPagesZeroElements() {
        Page<String> page = new Page<>(List.of(), 0, 0, 10);
        assertEquals(0, page.totalPages());
    }

    @Test
    void hasNextTrue() {
        Page<String> page = new Page<>(List.of("a", "b"), 20, 0, 10);
        assertTrue(page.hasNext());
    }

    @Test
    void hasNextFalseLastPage() {
        Page<String> page = new Page<>(List.of("a", "b"), 20, 1, 10);
        assertFalse(page.hasNext());
    }

    @Test
    void hasNextFalseSinglePage() {
        Page<String> page = new Page<>(List.of("a"), 5, 0, 10);
        assertFalse(page.hasNext());
    }

    @Test
    void hasNextFalseEmptyResult() {
        Page<String> page = new Page<>(List.of(), 0, 0, 10);
        assertFalse(page.hasNext());
    }

    @Test
    void hasPreviousTrue() {
        Page<String> page = new Page<>(List.of("a"), 20, 1, 10);
        assertTrue(page.hasPrevious());
    }

    @Test
    void hasPreviousFalseFirstPage() {
        Page<String> page = new Page<>(List.of("a"), 20, 0, 10);
        assertFalse(page.hasPrevious());
    }

    @Test
    void pageableReturnsCurrentPage() {
        Page<String> page = new Page<>(List.of("a"), 20, 1, 10);
        Pageable pageable = page.pageable();
        assertEquals(1, pageable.pageNumber());
        assertEquals(10, pageable.pageSize());
    }

    @Test
    void nextPageableIncrementsPage() {
        Page<String> page = new Page<>(List.of("a"), 20, 0, 10);
        Pageable next = page.next();
        assertEquals(1, next.pageNumber());
        assertEquals(10, next.pageSize());
    }

    @Test
    void previousDecrementsPage() {
        Page<String> page = new Page<>(List.of("a"), 20, 2, 10);
        Pageable prev = page.previous();
        assertEquals(1, prev.pageNumber());
        assertEquals(10, prev.pageSize());
    }

    @Test
    void previousOnFirstPageIsNull() {
        Page<String> page = new Page<>(List.of("a"), 20, 0, 10);
        assertNull(page.previous());
        assertFalse(page.hasPrevious());
    }

    @Test
    void negativeTotalElementsThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), -1, 0, 10));
    }

    @Test
    void negativePageNumberThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 0, -1, 10));
    }

    @Test
    void zeroPageSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 0, 0, 0));
    }

    @Test
    void negativePageSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 0, 0, -1));
    }
}
