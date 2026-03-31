package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Scrollable;
import st.orm.Window;

/**
 * Tests cursor serialization (toCursor/fromCursor) which requires storm-core on the classpath.
 */
class CursorSerializationTest {

    record StubEntity(int id, String name) implements Data {}
    record LongEntity(long id) implements Data {}
    record StringEntity(String id) implements Data {}
    record UuidEntity(UUID id) implements Data {}
    record InstantEntity(Instant id) implements Data {}
    record LocalDateEntity(LocalDate id) implements Data {}
    record LocalDateTimeEntity(LocalDateTime id) implements Data {}
    record OffsetDateTimeEntity(OffsetDateTime id) implements Data {}
    record BigDecimalEntity(BigDecimal id) implements Data {}
    record BooleanEntity(boolean id) implements Data {}
    record ShortEntity(short id) implements Data {}
    record ByteEntity(byte id) implements Data {}
    record CompositeEntity(int id, Instant createdAt, String label) implements Data {}

    // Single-key round trips

    @Test
    void roundTripIntegerCursor() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(key, 42, null, null, 20, true);
        String cursor = original.toCursor();
        assertNotNull(cursor);
        var restored = Scrollable.fromCursor(key, cursor);
        assertEquals(42, restored.keyCursor());
        assertEquals(20, restored.size());
        assertTrue(restored.isForward());
        assertNull(restored.sortCursor());
    }

    @Test
    void roundTripLongCursor() {
        var key = Metamodel.key(Metamodel.of(LongEntity.class, "id"));
        var original = new Scrollable<>(key, 123456789012345L, null, null, 10, false);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(123456789012345L, restored.keyCursor());
        assertFalse(restored.isForward());
    }

    @Test
    void roundTripStringCursor() {
        var key = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        var original = new Scrollable<>(key, "hello world", null, null, 5, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals("hello world", restored.keyCursor());
    }

    @Test
    void roundTripStringWithSpecialCharacters() {
        var key = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        String value = "line1\nline2\ttab|pipe:colon\"quote\\backslash\0null";
        var original = new Scrollable<>(key, value, null, null, 5, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(value, restored.keyCursor());
    }

    @Test
    void roundTripUuidCursor() {
        var key = Metamodel.key(Metamodel.of(UuidEntity.class, "id"));
        UUID uuid = UUID.randomUUID();
        var original = new Scrollable<>(key, uuid, null, null, 15, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(uuid, restored.keyCursor());
    }

    @Test
    void roundTripInstantCursor() {
        var key = Metamodel.key(Metamodel.of(InstantEntity.class, "id"));
        Instant instant = Instant.parse("2026-03-16T12:30:45.123456789Z");
        var original = new Scrollable<>(key, instant, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(instant, restored.keyCursor());
    }

    @Test
    void roundTripLocalDateCursor() {
        var key = Metamodel.key(Metamodel.of(LocalDateEntity.class, "id"));
        LocalDate date = LocalDate.of(2026, 3, 16);
        var original = new Scrollable<>(key, date, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(date, restored.keyCursor());
    }

    @Test
    void roundTripLocalDateTimeCursor() {
        var key = Metamodel.key(Metamodel.of(LocalDateTimeEntity.class, "id"));
        LocalDateTime dateTime = LocalDateTime.of(2026, 3, 16, 14, 30, 45, 123456789);
        var original = new Scrollable<>(key, dateTime, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(dateTime, restored.keyCursor());
    }

    @Test
    void roundTripOffsetDateTimeCursor() {
        var key = Metamodel.key(Metamodel.of(OffsetDateTimeEntity.class, "id"));
        OffsetDateTime dateTime = OffsetDateTime.of(2026, 3, 16, 14, 30, 45, 0, ZoneOffset.ofHours(2));
        var original = new Scrollable<>(key, dateTime, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(dateTime, restored.keyCursor());
    }

    @Test
    void roundTripBigDecimalCursor() {
        var key = Metamodel.key(Metamodel.of(BigDecimalEntity.class, "id"));
        BigDecimal value = new BigDecimal("12345.6789012345");
        var original = new Scrollable<>(key, value, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(value, restored.keyCursor());
    }

    @Test
    void roundTripBooleanCursor() {
        var key = Metamodel.key(Metamodel.of(BooleanEntity.class, "id"));
        var original = new Scrollable<>(key, true, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals(true, restored.keyCursor());
    }

    @Test
    void roundTripShortCursor() {
        var key = Metamodel.key(Metamodel.of(ShortEntity.class, "id"));
        var original = new Scrollable<>(key, (short) 32000, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals((short) 32000, restored.keyCursor());
    }

    @Test
    void roundTripByteCursor() {
        var key = Metamodel.key(Metamodel.of(ByteEntity.class, "id"));
        var original = new Scrollable<>(key, (byte) 127, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertEquals((byte) 127, restored.keyCursor());
    }

    @Test
    void roundTripNullCursor() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(key, null, null, null, 20, true);
        var restored = Scrollable.fromCursor(key, original.toCursor());
        assertNull(restored.keyCursor());
    }

    // Float/Double are excluded from default codecs

    @Test
    void doubleIsNotSupportedByDefaultCodecs() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(key, 3.14159, null, null, 20, true);
        assertThrows(IllegalStateException.class, original::toCursor);
    }

    @Test
    void floatIsNotSupportedByDefaultCodecs() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(key, 2.5f, null, null, 20, true);
        assertThrows(IllegalStateException.class, original::toCursor);
    }

    // Composite cursor round trips

    @Test
    void roundTripCompositeCursor() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var sort = Metamodel.of(CompositeEntity.class, "createdAt");
        Instant sortValue = Instant.parse("2026-01-15T08:00:00Z");
        var original = new Scrollable<>(key, 42, sort, sortValue, 20, true);
        var restored = Scrollable.fromCursor(key, sort, original.toCursor());
        assertEquals(42, restored.keyCursor());
        assertEquals(sortValue, restored.sortCursor());
        assertEquals(20, restored.size());
        assertTrue(restored.isForward());
    }

    @Test
    void roundTripCompositeBackwardCursor() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var sort = Metamodel.of(CompositeEntity.class, "label");
        var original = new Scrollable<>(key, 99, sort, "desc_value", 15, false);
        var restored = Scrollable.fromCursor(key, sort, original.toCursor());
        assertEquals(99, restored.keyCursor());
        assertEquals("desc_value", restored.sortCursor());
        assertEquals(15, restored.size());
        assertFalse(restored.isForward());
    }

    // Error cases

    @Test
    void fromCursorRejectsInvalidBase64() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        assertThrows(IllegalArgumentException.class,
                () -> Scrollable.fromCursor(key, "not-valid-base64!!!"));
    }

    @Test
    void fromCursorRejectsTruncatedCursor() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(key, 42, null, null, 20, true);
        String cursor = original.toCursor();
        String truncated = cursor.substring(0, cursor.length() / 2);
        assertThrows(IllegalArgumentException.class,
                () -> Scrollable.fromCursor(key, truncated));
    }

    @Test
    void toCursorProducesUrlSafeString() {
        var key = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        var original = new Scrollable<>(key, "some/value+with=special&chars", null, null, 20, true);
        String cursor = original.toCursor();
        assertFalse(cursor.contains("+"), "Cursor should not contain '+'");
        assertFalse(cursor.contains("/"), "Cursor should not contain '/'");
        assertFalse(cursor.contains("="), "Cursor should not contain '='");
    }

    // Window cursor convenience methods

    @Test
    void windowNextCursorProducesStringFromScrollable() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var next = new Scrollable<>(key, 42, null, null, 20, true);
        var window = new Window<>(java.util.List.of("a"), true, false, next, null);
        String cursor = window.nextCursor();
        assertNotNull(cursor);
        var restored = Scrollable.fromCursor(key, cursor);
        assertEquals(42, restored.keyCursor());
        assertEquals(20, restored.size());
        assertTrue(restored.isForward());
    }

    @Test
    void windowPreviousCursorProducesStringFromScrollable() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var prev = new Scrollable<>(key, 5, null, null, 10, false);
        var window = new Window<>(java.util.List.of("a"), false, true, null, prev);
        String cursor = window.previousCursor();
        assertNotNull(cursor);
        var restored = Scrollable.fromCursor(key, cursor);
        assertEquals(5, restored.keyCursor());
        assertEquals(10, restored.size());
        assertFalse(restored.isForward());
    }

    // Size and metamodel validation

    @Test
    void fromCursorRejectsExcessiveSize() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(key, 1, null, null, 5000, true);
        String cursor = original.toCursor();
        assertThrows(IllegalArgumentException.class,
                () -> Scrollable.fromCursor(key, cursor));
    }

    @Test
    void fromCursorWithMismatchedKeyRejects() {
        var stubKey = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var original = new Scrollable<>(stubKey, 42, null, null, 20, true);
        String cursor = original.toCursor();
        var longKey = Metamodel.key(Metamodel.of(LongEntity.class, "id"));
        assertThrows(IllegalArgumentException.class,
                () -> Scrollable.fromCursor(longKey, cursor));
    }

    @Test
    void fromCursorWithMismatchedSortRejects() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var sort = Metamodel.of(CompositeEntity.class, "createdAt");
        var original = new Scrollable<>(key, 42, sort, Instant.parse("2026-01-15T08:00:00Z"), 20, true);
        String cursor = original.toCursor();
        var differentSort = Metamodel.of(CompositeEntity.class, "label");
        assertThrows(IllegalArgumentException.class,
                () -> Scrollable.fromCursor(key, differentSort, cursor));
    }

    @Test
    void programmaticScrollableAllowsLargeSize() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var scrollable = Scrollable.of(key, 5000);
        assertEquals(5000, scrollable.size());
        var large = new Scrollable<>(key, null, null, null, 10_000, true);
        assertEquals(10_000, large.size());
    }
}
