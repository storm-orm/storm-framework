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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Json;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.spi.JsonString;
import st.orm.core.spi.Name;
import st.orm.core.spi.Providers;
import st.orm.core.spi.RefFactory;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.impl.JsonConverterSupport.MapperKey;
import st.orm.mapping.RecordField;

/**
 * The library-independent part of a JSON column converter: what a conversion writes and reads, the ref factory it
 * exposes while it runs, and the walk of a field's type for the sealed types a library has to know.
 */
class JsonConverterSupportTest {

    sealed interface Shape permits Circle, Square {}

    record Circle(double radius) implements Shape {}

    record Square(double side) implements Shape {}

    record Sample(@Json Shape shape,
                  @Json List<Shape> shapes,
                  @Json Shape[] array,
                  @Json List<Shape>[] genericArray,
                  @Json Map<String, ? extends Shape> bounded,
                  @Json(failOnUnknown = true) String plain) implements Data {}

    record Generic<T>(@Json T value) implements Data {}

    /** A converter whose library is a pair of functions, so the test controls what writing and reading do. */
    static class StubConverter extends JsonConverterSupport {
        final List<Object> written = new ArrayList<>();
        @Nullable RefFactory seenWhileReading;
        @Nullable RuntimeException failure;

        StubConverter(RecordField field) {
            super(field);
        }

        @Override
        protected String write(Object value) {
            if (failure != null) {
                throw failure;
            }
            written.add(value);
            return "\"" + value + "\"";
        }

        @Override
        protected @Nullable Object read(String json) {
            if (failure != null) {
                throw failure;
            }
            seenWhileReading = currentRefFactory();
            return json.equals("null") ? null : json.substring(1, json.length() - 1);
        }
    }

    /** A ref factory that creates detached refs; each instance stands for the factory of one conversion. */
    static final class DetachedRefFactory implements RefFactory {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Data, ID> Ref<T> create(Class<T> type, ID id) {
            return Ref.of((Class) type, id);
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Data, ID> Ref<T> create(T record, ID id) {
            return Ref.of((Class) record.getClass(), id);
        }
    }

    private static final RefFactory DETACHED = new DetachedRefFactory();

    private static RecordField field(Class<?> type, String name) {
        return Providers.getORMReflection().getRecordType(type).fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Type type(String name) {
        try {
            return Sample.class.getDeclaredField(name).getGenericType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void theConverterServesOneStringColumn() throws SqlTemplateException {
        var converter = new StubConverter(field(Sample.class, "plain"));
        assertEquals(1, converter.getParameterCount());
        assertEquals(List.of(String.class), converter.getParameterTypes());
        var columns = converter.getColumns(field -> new Name() {
            @Override
            public String name() {
                return field.name();
            }

            @Override
            public String qualified(SqlDialect dialect) {
                return field.name();
            }
        });
        assertEquals(List.of("plain"), columns.stream().map(Name::name).toList());
    }

    @Test
    void writingTakesTheFieldOfTheRecordAndNullForNoRecord() throws SqlTemplateException {
        var converter = new StubConverter(field(Sample.class, "plain"));
        var written = converter.toDatabase(new Sample(null, List.of(), new Shape[0], null, Map.of(), "text"));
        assertEquals(1, written.size());
        assertEquals("\"text\"", ((JsonString) written.getFirst()).value());
        assertEquals(List.of("text"), converter.written);
        assertEquals(Collections.singletonList(null), converter.toDatabase(null));
        assertEquals(Collections.singletonList(null),
                converter.toDatabase(new Sample(null, List.of(), new Shape[0], null, Map.of(), null)));
    }

    @Test
    void aValueOfTheFieldIsWrittenOnItsOwnAndStoredJsonTextPassesThrough() throws SqlTemplateException {
        var shape = new StubConverter(field(Sample.class, "shape"));
        var written = shape.toDatabaseValue(new Circle(1));
        assertEquals("\"Circle[radius=1.0]\"", ((JsonString) written.getFirst()).value());
        assertEquals(List.of(new Circle(1)), shape.written);
        // The JSON text a predicate compares the column with is the stored value.
        assertEquals(List.of("{\"radius\":1.0}"), shape.toDatabaseValue("{\"radius\":1.0}"));
        assertEquals(List.of(new Circle(1)), shape.written, "the text is not written again");
        var plain = new StubConverter(field(Sample.class, "plain"));
        assertEquals("\"text\"", ((JsonString) plain.toDatabaseValue("text").getFirst()).value());
        assertEquals(Collections.singletonList(null), shape.toDatabaseValue(null));
    }

    @Test
    void aRecordOfAnotherTypeSurfacesAsATemplateException() {
        var converter = new StubConverter(field(Sample.class, "plain"));
        assertThrows(SqlTemplateException.class, () -> converter.toDatabase("not a sample"));
    }

    @Test
    void aWriteFailureSurfacesAsATemplateException() {
        var converter = new StubConverter(field(Sample.class, "plain"));
        converter.failure = new IllegalStateException("refused");
        var sample = new Sample(null, List.of(), new Shape[0], null, Map.of(), "text");
        var thrown = assertThrows(SqlTemplateException.class, () -> converter.toDatabase(sample));
        assertSame(converter.failure, thrown.getCause());
    }

    @Test
    void readingExposesTheRefFactoryOfTheConversionAndNullForNull() throws SqlTemplateException {
        var converter = new StubConverter(field(Sample.class, "plain"));
        assertNull(JsonConverterSupport.currentRefFactory());
        assertEquals("text", converter.fromDatabase(new Object[] {"\"text\""}, DETACHED));
        assertSame(DETACHED, converter.seenWhileReading);
        assertNull(JsonConverterSupport.currentRefFactory(), "the factory is bound to the conversion only");
        assertNull(converter.fromDatabase(new Object[] {null}, DETACHED));
    }

    @Test
    void aNestedConversionRestoresTheOuterRefFactory() throws SqlTemplateException {
        var inner = new StubConverter(field(Sample.class, "plain"));
        var outer = new StubConverter(field(Sample.class, "plain")) {
            @Override
            protected @Nullable Object read(String json) {
                try {
                    // A custom deserializer that queries the database nests a conversion with another factory.
                    inner.fromDatabase(new Object[] {"\"nested\""}, DETACHED);
                } catch (SqlTemplateException e) {
                    throw new IllegalStateException(e);
                }
                return super.read(json);
            }
        };
        RefFactory outerFactory = new DetachedRefFactory();
        assertEquals("text", outer.fromDatabase(new Object[] {"\"text\""}, outerFactory));
        assertSame(DETACHED, inner.seenWhileReading);
        assertSame(outerFactory, outer.seenWhileReading, "the outer conversion keeps its own factory");
        assertNull(JsonConverterSupport.currentRefFactory());
    }

    @Test
    void aReadFailureSurfacesAsATemplateExceptionAndUnbindsTheFactory() {
        var converter = new StubConverter(field(Sample.class, "plain"));
        converter.failure = new IllegalStateException("refused");
        var thrown = assertThrows(SqlTemplateException.class,
                () -> converter.fromDatabase(new Object[] {"\"text\""}, DETACHED));
        assertSame(converter.failure, thrown.getCause());
        assertNull(JsonConverterSupport.currentRefFactory());
    }

    @Test
    void theRawTypeIsTheClassOrTheClassOfAParameterizedTypeAndObjectOtherwise() {
        assertEquals(Shape.class, JsonConverterSupport.rawType(type("shape")));
        assertEquals(List.class, JsonConverterSupport.rawType(type("shapes")));
        assertEquals(Shape[].class, JsonConverterSupport.rawType(type("array")));
        assertEquals(Object.class, JsonConverterSupport.rawType(field(Generic.class, "value").genericType()));
    }

    @Test
    void theSealedTypesAreFoundAtTheTopInTypeArgumentsInArraysAndInBounds() {
        assertEquals(List.of(Shape.class), JsonConverterSupport.sealedTypes(type("shape")));
        assertEquals(List.of(Shape.class), JsonConverterSupport.sealedTypes(type("shapes")));
        assertEquals(List.of(Shape.class), JsonConverterSupport.sealedTypes(type("array")));
        assertEquals(List.of(Shape.class), JsonConverterSupport.sealedTypes(type("genericArray")));
        assertEquals(List.of(Shape.class), JsonConverterSupport.sealedTypes(type("bounded")));
        assertEquals(List.of(), JsonConverterSupport.sealedTypes(type("plain")));
        assertEquals(List.of(), JsonConverterSupport.sealedTypes(field(Generic.class, "value").genericType()));
        assertEquals(List.of(Circle.class, Square.class), JsonConverterSupport.permittedSubclasses(Shape.class));
    }

    @Test
    void theMapperKeyCarriesTheRawTypeOnlyWithACustomSerializerOrDeserializer() {
        var json = field(Sample.class, "plain").getAnnotation(Json.class);
        var plain = MapperKey.of(json, type("shapes"), null, null);
        assertNull(plain.targetType());
        assertEquals(List.of(Shape.class), plain.sealedTypes());
        assertTrue(plain.json().failOnUnknown());
        assertEquals(List.class, MapperKey.of(json, type("shapes"), String.class, null).targetType());
        assertEquals(List.class, MapperKey.of(json, type("shapes"), null, String.class).targetType());
        assertEquals(MapperKey.of(json, type("shapes"), null, String.class),
                MapperKey.of(json, type("shapes"), null, String.class));
        assertInstanceOf(MapperKey.class, plain);
    }
}
