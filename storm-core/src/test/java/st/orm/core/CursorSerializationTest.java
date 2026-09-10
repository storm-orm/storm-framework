package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import st.orm.InvalidCursorException;
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

    private static <T extends st.orm.Data> Scrollable<T> after(Metamodel.Key<T, ?> key, Object value) {
        return Scrollable.of(key, 20).after(value);
    }

    private static void assertRoundTrip(Scrollable<?> original) {
        var restored = Scrollable.of(original.key(), original.size()).from(original.toCursor());
        assertEquals(original.position(), restored.position());
    }

    @Test
    void roundTripIntegerCursor() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        assertRoundTrip(after(key, 42));
    }

    @Test
    void roundTripLongCursor() {
        var key = Metamodel.key(Metamodel.of(LongEntity.class, "id"));
        assertRoundTrip(Scrollable.of(key, 10).before(123456789012345L));
    }

    @Test
    void roundTripStringCursor() {
        var key = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        assertRoundTrip(after(key, "hello world"));
    }

    @Test
    void roundTripStringWithSpecialCharacters() {
        var key = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        String value = "line1\nline2\ttab|pipe:colon\"quote\\backslash\0null";
        assertRoundTrip(after(key, value));
    }

    @Test
    void roundTripUuidCursor() {
        var key = Metamodel.key(Metamodel.of(UuidEntity.class, "id"));
        UUID uuid = UUID.randomUUID();
        assertRoundTrip(after(key, uuid));
    }

    @Test
    void roundTripInstantCursor() {
        var key = Metamodel.key(Metamodel.of(InstantEntity.class, "id"));
        Instant instant = Instant.parse("2026-03-16T12:30:45.123456789Z");
        assertRoundTrip(after(key, instant));
    }

    @Test
    void roundTripLocalDateCursor() {
        var key = Metamodel.key(Metamodel.of(LocalDateEntity.class, "id"));
        LocalDate date = LocalDate.of(2026, 3, 16);
        assertRoundTrip(after(key, date));
    }

    @Test
    void roundTripLocalDateTimeCursor() {
        var key = Metamodel.key(Metamodel.of(LocalDateTimeEntity.class, "id"));
        LocalDateTime dateTime = LocalDateTime.of(2026, 3, 16, 14, 30, 45, 123456789);
        assertRoundTrip(after(key, dateTime));
    }

    @Test
    void roundTripOffsetDateTimeCursor() {
        var key = Metamodel.key(Metamodel.of(OffsetDateTimeEntity.class, "id"));
        OffsetDateTime dateTime = OffsetDateTime.of(2026, 3, 16, 14, 30, 45, 0, ZoneOffset.ofHours(2));
        assertRoundTrip(after(key, dateTime));
    }

    @Test
    void roundTripBigDecimalCursor() {
        var key = Metamodel.key(Metamodel.of(BigDecimalEntity.class, "id"));
        BigDecimal value = new BigDecimal("12345.6789012345");
        assertRoundTrip(after(key, value));
    }

    @Test
    void roundTripBooleanCursor() {
        var key = Metamodel.key(Metamodel.of(BooleanEntity.class, "id"));
        assertRoundTrip(after(key, true));
    }

    @Test
    void roundTripShortCursor() {
        var key = Metamodel.key(Metamodel.of(ShortEntity.class, "id"));
        assertRoundTrip(after(key, (short) 32000));
    }

    @Test
    void roundTripByteCursor() {
        var key = Metamodel.key(Metamodel.of(ByteEntity.class, "id"));
        assertRoundTrip(after(key, (byte) 127));
    }

    // Float/Double are excluded from default codecs

    @Test
    void doubleIsNotSupportedByDefaultCodecs() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        assertThrows(IllegalStateException.class, after(key, 3.14159)::toCursor);
    }

    @Test
    void floatIsNotSupportedByDefaultCodecs() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        assertThrows(IllegalStateException.class, after(key, 2.5f)::toCursor);
    }

    // Multi-field round trips

    @Test
    void roundTripSortedCursor() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var sort = Metamodel.of(CompositeEntity.class, "createdAt");
        Instant sortValue = Instant.parse("2026-01-15T08:00:00Z");
        var original = Scrollable.of(key, 20).sortBy(sort).after(sortValue, 42);
        var restored = Scrollable.of(key, 20).sortBy(sort).from(original.toCursor());
        assertEquals(original.position(), restored.position());
        assertTrue(restored.position().after());
    }

    @Test
    void roundTripBeforeCursorWithTwoSortFields() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var label = Metamodel.of(CompositeEntity.class, "label");
        var createdAt = Metamodel.of(CompositeEntity.class, "createdAt");
        Instant instant = Instant.parse("2026-01-15T08:00:00Z");
        var original = Scrollable.of(key, 15).sortByDescending(label).sortBy(createdAt).before("desc_value", instant, 99);
        var restored = Scrollable.of(key, 15).sortByDescending(label).sortBy(createdAt).from(original.toCursor());
        assertEquals(original.position(), restored.position());
        assertFalse(restored.position().after());
    }

    @Test
    void sizeIsNotPartOfTheCursor() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var restored = Scrollable.of(key, 50).from(after(key, 42).toCursor());
        assertEquals(50, restored.size());
        assertEquals(after(key, 42).position(), restored.position());
    }

    // Error cases

    @Test
    void fromCursorRejectsInvalidBase64() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        assertThrows(InvalidCursorException.class,
                () -> Scrollable.of(key, 20).from("not-valid-base64!!!"));
    }

    @Test
    void fromCursorRejectsTruncatedCursor() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        String cursor = after(key, 42).toCursor();
        String truncated = cursor.substring(0, cursor.length() / 2);
        assertThrows(InvalidCursorException.class, () -> Scrollable.of(key, 20).from(truncated));
    }

    @Test
    void toCursorProducesUrlSafeString() {
        var key = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        String cursor = after(key, "some/value+with=special&chars").toCursor();
        assertFalse(cursor.contains("+"), "Cursor should not contain '+'");
        assertFalse(cursor.contains("/"), "Cursor should not contain '/'");
        assertFalse(cursor.contains("="), "Cursor should not contain '='");
    }

    // Window cursor convenience methods

    @Test
    void windowNextCursorProducesStringFromScrollable() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var window = new Window<>(java.util.List.of("a"), true, false, after(key, 42), null);
        String cursor = window.nextCursor();
        assertNotNull(cursor);
        var restored = Scrollable.of(key, 20).from(cursor);
        assertEquals(after(key, 42).position(), restored.position());
        assertTrue(restored.position().after());
    }

    @Test
    void windowPreviousCursorProducesStringFromScrollable() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        var window = new Window<>(java.util.List.of("a"), false, true, null, Scrollable.of(key, 10).before(5));
        String cursor = window.previousCursor();
        assertNotNull(cursor);
        var restored = Scrollable.of(key, 10).from(cursor);
        assertEquals(Scrollable.of(key, 10).before(5).position(), restored.position());
        assertFalse(restored.position().after());
    }

    // Tampered and foreign cursors

    private static String tampered(String cursor, java.util.function.UnaryOperator<byte[]> change) {
        byte[] bytes = java.util.Base64.getUrlDecoder().decode(cursor);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(change.apply(bytes));
    }

    @Test
    void fromCursorRejectsAnotherFormatVersion() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        String earlier = tampered(after(key, 42).toCursor(), bytes -> { bytes[0] = 1; return bytes; });
        var exception = assertThrows(InvalidCursorException.class, () -> Scrollable.of(key, 20).from(earlier));
        assertTrue(exception.getMessage().contains("version"), exception.getMessage());
    }

    @Test
    void fromCursorRejectsTrailingBytes() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        String longer = tampered(after(key, 42).toCursor(), bytes -> java.util.Arrays.copyOf(bytes, bytes.length + 1));
        var exception = assertThrows(InvalidCursorException.class, () -> Scrollable.of(key, 20).from(longer));
        assertTrue(exception.getMessage().contains("trailing"), exception.getMessage());
    }

    @Test
    void fromCursorRejectsAValueCountThatDoesNotMatchTheOrdering() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var sort = Metamodel.of(CompositeEntity.class, "label");
        // The value count sits right after the version, the two fingerprints and the direction flag.
        String twoValues = tampered(Scrollable.of(key, 20).sortBy(sort).after("x", 42).toCursor(), bytes -> {
            bytes[1 + 4 + 4 + 1] = 1;
            return bytes;
        });
        // The fingerprint still matches the sorted ordering, so the count is what is refused.
        var exception = assertThrows(InvalidCursorException.class,
                () -> Scrollable.of(key, 20).sortBy(sort).from(twoValues));
        assertTrue(exception.getMessage().contains("values"), exception.getMessage());
    }

    @Test
    void fromCursorRejectsANullValue() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        // The value's type tag follows the count; tag 0 is the null marker.
        String withNull = tampered(after(key, 42).toCursor(), bytes -> {
            int tag = 1 + 4 + 4 + 1 + 1;
            bytes[tag] = 0;
            return java.util.Arrays.copyOf(bytes, tag + 1);
        });
        var exception = assertThrows(InvalidCursorException.class, () -> Scrollable.of(key, 20).from(withNull));
        assertTrue(exception.getMessage().contains("null"), exception.getMessage());
    }

    @Test
    void invalidCursorIsAPersistenceException() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        assertThrows(st.orm.PersistenceException.class, () -> Scrollable.of(key, 20).from("nope"));
    }

    // Ordering validation

    @Test
    void fromCursorWithMismatchedKeyRejects() {
        var stubKey = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        String cursor = after(stubKey, 42).toCursor();
        var longKey = Metamodel.key(Metamodel.of(LongEntity.class, "id"));
        assertThrows(InvalidCursorException.class, () -> Scrollable.of(longKey, 20).from(cursor));
    }

    @Test
    void fromCursorWithMismatchedSortRejects() {
        var key = Metamodel.key(Metamodel.of(CompositeEntity.class, "id"));
        var sort = Metamodel.of(CompositeEntity.class, "createdAt");
        String cursor = Scrollable.of(key, 20).sortBy(sort).after(Instant.parse("2026-01-15T08:00:00Z"), 42).toCursor();
        var differentSort = Metamodel.of(CompositeEntity.class, "label");
        assertThrows(InvalidCursorException.class, () -> Scrollable.of(key, 20).sortBy(differentSort).from(cursor));
    }

    @Test
    void fromCursorWithMismatchedDirectionRejects() {
        var key = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        String cursor = after(key, 42).toCursor();
        assertThrows(InvalidCursorException.class, () -> Scrollable.of(key, 20).descending().from(cursor));
    }

    @Test
    void fromCursorWithWrongValueTypeRejects() {
        var stringKey = Metamodel.key(Metamodel.of(StringEntity.class, "id"));
        var intKey = Metamodel.key(Metamodel.of(StubEntity.class, "id"));
        // Same path and direction, so the ordering fingerprints agree; the value type does not.
        String cursor = after(stringKey, "text").toCursor();
        assertThrows(InvalidCursorException.class, () -> Scrollable.of(intKey, 20).from(cursor));
    }
}
