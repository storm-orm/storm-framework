package st.orm.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.DbColumn;
import st.orm.PersistenceException;

class RecordTypeTest {

    record TestData(int id, String name) implements Data {}

    record NonData(int value) {}

    sealed interface Shape extends Data permits Circle {}

    record Circle(int radius) implements Shape {}

    private RecordType createRecordType(Class<?> type) throws NoSuchMethodException {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return new RecordType(type, constructor, List.of(), List.of());
    }

    @Test
    void basicRecordTypeCreation() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertEquals(TestData.class, recordType.type());
        assertNotNull(recordType.constructor());
        assertTrue(recordType.annotations().isEmpty());
        assertTrue(recordType.fields().isEmpty());
    }

    @Test
    void isDataTypeReturnsTrueForDataClass() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertTrue(recordType.isDataType());
    }

    @Test
    void isDataTypeReturnsFalseForNonDataClass() throws NoSuchMethodException {
        RecordType recordType = createRecordType(NonData.class);
        assertFalse(recordType.isDataType());
    }

    @Test
    void requireDataTypeReturnsClassForDataType() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertEquals(TestData.class, recordType.requireDataType());
    }

    @Test
    void requireDataTypeThrowsForNonDataType() throws NoSuchMethodException {
        RecordType recordType = createRecordType(NonData.class);
        assertThrows(PersistenceException.class, recordType::requireDataType);
    }

    @Test
    void newInstanceCreatesRecord() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        Object instance = recordType.newInstance(new Object[]{42, "test"});
        assertNotNull(instance);
        assertTrue(instance instanceof TestData);
        TestData data = (TestData) instance;
        assertEquals(42, data.id());
        assertEquals("test", data.name());
    }

    @Test
    void newInstanceThrowsOnWrongArgCount() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertThrows(PersistenceException.class, () -> recordType.newInstance(new Object[]{42}));
    }

    @Test
    void annotationsAreImmutableCopy() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertThrows(UnsupportedOperationException.class, () -> recordType.annotations().add(null));
    }

    @Test
    void fieldsAreImmutableCopy() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertThrows(UnsupportedOperationException.class, () -> recordType.fields().add(null));
    }

    @Test
    void isAnnotationPresentReturnsFalseWhenNoAnnotations() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertFalse(recordType.isAnnotationPresent(DbColumn.class));
    }

    @Test
    void getAnnotationReturnsNullWhenNotPresent() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        assertNull(recordType.getAnnotation(DbColumn.class));
    }

    @Test
    void getAnnotationsReturnsEmptyArrayWhenNotPresent() throws NoSuchMethodException {
        RecordType recordType = createRecordType(TestData.class);
        DbColumn[] annotations = recordType.getAnnotations(DbColumn.class);
        assertNotNull(annotations);
        assertEquals(0, annotations.length);
    }

    @Test
    void constructorRejectsNullType() throws NoSuchMethodException {
        Constructor<?> constructor = TestData.class.getDeclaredConstructors()[0];
        assertThrows(NullPointerException.class, () ->
                new RecordType(null, constructor, List.of(), List.of()));
    }

    @Test
    void newInstanceOfATypeWithoutConstructorIsRefused() {
        RecordType recordType = new RecordType(Shape.class, null, List.of(), List.of());
        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> recordType.newInstance(new Object[0]));
        assertTrue(exception.getMessage().contains("sealed entity"), exception.getMessage());
    }

    @Test
    void newInstanceWrapsConstructorException() throws NoSuchMethodException {
        record ThrowingRecord(int value) implements Data {
            ThrowingRecord {
                if (value < 0) throw new IllegalArgumentException("negative");
            }
        }
        RecordType recordType = createRecordType(ThrowingRecord.class);
        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> recordType.newInstance(new Object[]{-1}));
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void newInstanceWrapsPersistenceException() throws NoSuchMethodException {
        record PersistenceThrowingRecord(int value) implements Data {
            PersistenceThrowingRecord {
                if (value < 0) throw new PersistenceException("persistence error");
            }
        }
        RecordType recordType = createRecordType(PersistenceThrowingRecord.class);
        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> recordType.newInstance(new Object[]{-1}));
        assertEquals("persistence error", exception.getMessage());
    }
}
