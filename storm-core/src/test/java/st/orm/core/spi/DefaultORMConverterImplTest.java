package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.Converter;
import st.orm.Data;
import st.orm.DbTable;
import st.orm.PK;
import st.orm.SqlTemplateException;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Unit tests for {@link DefaultORMConverterImpl} covering toDatabase, fromDatabase,
 * getParameterCount, getParameterTypes, and getColumns methods.
 */
public class DefaultORMConverterImplTest {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    // Converter implementations

    public static class IntToStringConverter implements Converter<String, Integer> {
        @Override
        public String toDatabase(@Nullable Integer value) {
            return value == null ? null : "NUM:" + value;
        }

        @Override
        public Integer fromDatabase(@Nullable String dbValue) {
            return dbValue == null ? null : Integer.parseInt(dbValue.substring(4));
        }
    }

    // Test records

    @DbTable("converter_test")
    record ConverterTestEntity(
            @PK int id,
            Integer convertedField
    ) implements Data {}

    @DbTable("primitive_converter_test")
    record PrimitiveConverterTestEntity(
            @PK int id,
            int convertedField
    ) implements Data {}

    private static final RefFactory UNUSED_REF_FACTORY = new RefFactory() {
        @Override
        public <T extends Data, ID> st.orm.Ref<T> create(Class<T> type, ID pk) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends Data, ID> st.orm.Ref<T> create(T record, ID pk) {
            throw new UnsupportedOperationException();
        }
    };

    // Helper

    private RecordField getField(Class<?> recordClass, String fieldName) {
        RecordType recordType = REFLECTION.getRecordType(recordClass);
        return recordType.fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Field not found: " + fieldName));
    }

    // Tests

    @Test
    public void testGetParameterCountReturnsOne() {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        assertEquals(1, converter.getParameterCount());
    }

    @Test
    public void testGetParameterTypesReturnsDatabaseType() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        assertEquals(List.of(String.class), converter.getParameterTypes());
    }

    @Test
    public void testFromDatabaseConvertsNonNullValue() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        // Provide a mock RefFactory since it's unused by this converter.
        RefFactory refFactory = new RefFactory() {
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(Class<T> type, ID pk) {
                throw new UnsupportedOperationException();
            }
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(T record, ID pk) {
                throw new UnsupportedOperationException();
            }
        };
        Object result = converter.fromDatabase(new Object[]{"NUM:42"}, refFactory);
        assertNotNull(result);
        assertEquals(42, result);
    }

    @Test
    public void testFromDatabaseReturnsNullForNullValue() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        RefFactory refFactory = new RefFactory() {
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(Class<T> type, ID pk) {
                throw new UnsupportedOperationException();
            }
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(T record, ID pk) {
                throw new UnsupportedOperationException();
            }
        };
        Object result = converter.fromDatabase(new Object[]{null}, refFactory);
        assertNull(result);
    }

    @Test
    public void testFromDatabaseReturnsNullForEmptyValues() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        RefFactory refFactory = new RefFactory() {
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(Class<T> type, ID pk) {
                throw new UnsupportedOperationException();
            }
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(T record, ID pk) {
                throw new UnsupportedOperationException();
            }
        };
        // Empty values array should return null.
        Object result = converter.fromDatabase(new Object[]{}, refFactory);
        assertNull(result);
    }

    @Test
    public void testToDatabaseConvertsNonNullRecord() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        ConverterTestEntity entity = new ConverterTestEntity(1, 42);
        List<Object> result = converter.toDatabase(entity);
        assertEquals(1, result.size());
        assertEquals("NUM:42", result.get(0));
    }

    @Test
    public void testToDatabaseHandlesNullRecord() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        List<Object> result = converter.toDatabase(null);
        assertEquals(1, result.size());
        assertNull(result.get(0));
    }

    @Test
    public void testFromDatabaseThrowsSqlTemplateExceptionOnConversionError() {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        // Create a converter that will throw on fromDatabase.
        Converter<String, Integer> failingConverter = new Converter<>() {
            @Override
            public String toDatabase(@Nullable Integer value) {
                return null;
            }
            @Override
            public Integer fromDatabase(@Nullable String dbValue) {
                throw new RuntimeException("Conversion error");
            }
        };
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, failingConverter, String.class);
        RefFactory refFactory = new RefFactory() {
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(Class<T> type, ID pk) {
                throw new UnsupportedOperationException();
            }
            @Override
            public <T extends Data, ID> st.orm.Ref<T> create(T record, ID pk) {
                throw new UnsupportedOperationException();
            }
        };
        assertThrows(SqlTemplateException.class,
                () -> converter.fromDatabase(new Object[]{"some value"}, refFactory));
    }

    @Test
    public void testToDatabaseValueConvertsTheValueOfTheField() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        assertEquals(List.of("NUM:42"), converter.toDatabaseValue(42));
        assertEquals(Collections.singletonList(null), converter.toDatabaseValue(null));
    }

    @Test
    public void testToDatabaseValuePassesAValueOfTheDatabaseTypeThrough() throws SqlTemplateException {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        // The stored value, as a predicate compares the column with.
        assertEquals(List.of("NUM:42"), converter.toDatabaseValue("NUM:42"));
    }

    @Test
    public void testToDatabaseValueConvertsTheBoxedValueOfAPrimitiveField() throws SqlTemplateException {
        RecordField field = getField(PrimitiveConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        assertEquals(List.of("NUM:42"), converter.toDatabaseValue(42));
        assertEquals(List.of("NUM:42"), converter.toDatabaseValue("NUM:42"));
        assertEquals(List.of("NUM:7"), converter.toDatabase(new PrimitiveConverterTestEntity(1, 7)));
    }

    @Test
    public void testToDatabaseValueNamesTheFieldForAValueOfNeitherType() {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        var exception = assertThrows(SqlTemplateException.class, () -> converter.toDatabaseValue(4.2));
        assertTrue(exception.getMessage().contains("'ConverterTestEntity.convertedField'"), exception.getMessage());
    }

    @Test
    public void testToDatabaseNamesTheFieldForARecordOfAnotherType() {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        var exception = assertThrows(SqlTemplateException.class, () -> converter.toDatabase("not an entity"));
        assertTrue(exception.getMessage().contains("'ConverterTestEntity.convertedField'"), exception.getMessage());
    }

    @Test
    public void testFromDatabaseNamesTheFieldForAValueOfAnotherType() {
        RecordField field = getField(ConverterTestEntity.class, "convertedField");
        DefaultORMConverterImpl<String, Integer> converter =
                new DefaultORMConverterImpl<>(field, new IntToStringConverter(), String.class);
        var exception = assertThrows(SqlTemplateException.class,
                () -> converter.fromDatabase(new Object[]{42}, UNUSED_REF_FACTORY));
        assertTrue(exception.getMessage().contains("'ConverterTestEntity.convertedField'"), exception.getMessage());
    }
}
