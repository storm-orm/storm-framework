package st.orm.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.DbColumn;
import st.orm.PersistenceException;

class RecordFieldTest {

    record TestData(int id, String name) implements Data {}

    record NonData(int value) {}

    private RecordField createField(Class<?> declaringType, String name, Class<?> type,
                                    List<Annotation> annotations) throws NoSuchMethodException {
        Method method = declaringType.getMethod(name);
        return new RecordField(declaringType, name, type, type, false, false, method, annotations);
    }

    @Test
    void basicRecordFieldCreation() throws NoSuchMethodException {
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        assertEquals(TestData.class, field.declaringType());
        assertEquals("id", field.name());
        assertEquals(int.class, field.type());
        assertFalse(field.nullable());
        assertFalse(field.mutable());
        assertNotNull(field.method());
        assertTrue(field.annotations().isEmpty());
    }

    @Test
    void isDataTypeReturnsTrueForDataClass() throws NoSuchMethodException {
        Method method = TestData.class.getMethod("id");
        RecordField field = new RecordField(TestData.class, "nested", TestData.class, TestData.class, false, false, method, List.of());
        assertTrue(field.isDataType());
    }

    @Test
    void isDataTypeReturnsFalseForNonDataClass() throws NoSuchMethodException {
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        assertFalse(field.isDataType());
    }

    @Test
    void requireDataTypeReturnsClassForDataType() throws NoSuchMethodException {
        Method method = TestData.class.getMethod("id");
        RecordField field = new RecordField(TestData.class, "nested", TestData.class, TestData.class, false, false, method, List.of());
        assertEquals(TestData.class, field.requireDataType());
    }

    @Test
    void requireDataTypeThrowsForNonDataType() throws NoSuchMethodException {
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        assertThrows(PersistenceException.class, field::requireDataType);
    }

    @Test
    void annotationsAreImmutableCopy() throws NoSuchMethodException {
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        assertThrows(UnsupportedOperationException.class, () -> field.annotations().add(null));
    }

    @Test
    void isAnnotationPresentFindsDirectAnnotation() throws NoSuchMethodException {
        Annotation[] recordAnnotations = TestData.class.getRecordComponents()[0].getAnnotations();
        // TestData id field has no annotations, test the negative case
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        assertFalse(field.isAnnotationPresent(DbColumn.class));
    }

    @Test
    void getAnnotationReturnsNullWhenNotPresent() throws NoSuchMethodException {
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        assertNull(field.getAnnotation(DbColumn.class));
    }

    @Test
    void getAnnotationsReturnsEmptyArrayWhenNotPresent() throws NoSuchMethodException {
        RecordField field = createField(TestData.class, "id", int.class, List.of());
        DbColumn[] annotations = field.getAnnotations(DbColumn.class);
        assertNotNull(annotations);
        assertEquals(0, annotations.length);
    }

    @Test
    void constructorRejectsNullDeclaringType() throws NoSuchMethodException {
        Method method = TestData.class.getMethod("id");
        assertThrows(NullPointerException.class, () ->
                new RecordField(null, "id", int.class, int.class, false, false, method, List.of()));
    }

    @Test
    void constructorRejectsNullName() throws NoSuchMethodException {
        Method method = TestData.class.getMethod("id");
        assertThrows(NullPointerException.class, () ->
                new RecordField(TestData.class, null, int.class, int.class, false, false, method, List.of()));
    }

    @Test
    void constructorRejectsNullType() throws NoSuchMethodException {
        Method method = TestData.class.getMethod("id");
        assertThrows(NullPointerException.class, () ->
                new RecordField(TestData.class, "id", null, int.class, false, false, method, List.of()));
    }

    @Test
    void constructorRejectsNullGenericType() throws NoSuchMethodException {
        Method method = TestData.class.getMethod("id");
        assertThrows(NullPointerException.class, () ->
                new RecordField(TestData.class, "id", int.class, null, false, false, method, List.of()));
    }

    @Test
    void aFieldWithoutAccessorIsAccepted() {
        RecordField field = new RecordField(TestData.class, "keywords", String.class, String.class, true, false, null, List.of());
        assertNull(field.method());
    }
}
