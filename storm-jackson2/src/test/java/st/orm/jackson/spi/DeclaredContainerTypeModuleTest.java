package st.orm.jackson.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A collection or map property of final element types writes by its declared type, so the class holding the value
 * is never introspected; a property of an open element type keeps the runtime typing that writes the subtype's
 * properties.
 */
class DeclaredContainerTypeModuleTest {

    /** The classes Jackson built a collection or map serializer for: the runtime class, when it is consulted. */
    private final Set<Class<?>> introspected = new LinkedHashSet<>();

    private final SimpleModule spy = new SimpleModule("Spy").setSerializerModifier(new BeanSerializerModifier() {
        @Override
        public JsonSerializer<?> modifyCollectionSerializer(SerializationConfig config, CollectionType type,
                                                             BeanDescription beanDesc, JsonSerializer<?> serializer) {
            introspected.add(type.getRawClass());
            return serializer;
        }

        @Override
        public JsonSerializer<?> modifyMapSerializer(SerializationConfig config, MapType type,
                                                      BeanDescription beanDesc, JsonSerializer<?> serializer) {
            introspected.add(type.getRawClass());
            return serializer;
        }
    });

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(spy)
            .addModule(new DeclaredContainerTypeModule())
            .build();

    /** The runtime class of a value, as {@code emptySet()} returns one in Kotlin. */
    static class RuntimeList extends ArrayList<String> {}

    static class RuntimeMap extends LinkedHashMap<String, Integer> {}

    record Clinics(List<String> clinics) {}

    record Nested(List<List<String>> schedule) {}

    record Scores(Map<String, Integer> scores) {}

    static class RuntimeKeyedMap extends LinkedHashMap<CharSequence, Integer> {}

    record Visits(Map<CharSequence, Integer> visits) {}

    record Dynamic(@JsonSerialize(typing = JsonSerialize.Typing.DYNAMIC) List<String> clinics) {}

    interface Animal {
        String name();
    }

    record Dog(String name, boolean goodBoy) implements Animal {}

    record Zoo(List<Animal> animals) {}

    private static RuntimeList clinics() {
        var list = new RuntimeList();
        list.add("Downtown");
        return list;
    }

    @Test
    void aCollectionOfAFinalTypeWritesByItsDeclaredType() throws Exception {
        assertEquals("{\"clinics\":[\"Downtown\"]}", mapper.writeValueAsString(new Clinics(clinics())));
        assertFalse(introspected.contains(RuntimeList.class), "the runtime class is not consulted: " + introspected);
        introspected.clear();
        JsonMapper.builder().addModule(spy).build().writeValueAsString(new Clinics(clinics()));
        assertTrue(introspected.contains(RuntimeList.class), "the runtime class decides without the module: " + introspected);
    }

    @Test
    void aNestedCollectionOfAFinalTypeWritesByItsDeclaredType() throws Exception {
        assertEquals("{\"schedule\":[[\"Downtown\"]]}", mapper.writeValueAsString(new Nested(List.of(clinics()))));
        assertFalse(introspected.contains(RuntimeList.class), "the runtime class is not consulted: " + introspected);
    }

    @Test
    void aMapOfFinalTypesWritesByItsDeclaredType() throws Exception {
        var scores = new RuntimeMap();
        scores.put("Downtown", 3);
        assertEquals("{\"scores\":{\"Downtown\":3}}", mapper.writeValueAsString(new Scores(scores)));
        assertFalse(introspected.contains(RuntimeMap.class), "the runtime class is not consulted: " + introspected);
    }

    @Test
    void anExplicitTypingOnThePropertyWins() throws Exception {
        mapper.writeValueAsString(new Dynamic(clinics()));
        assertTrue(introspected.contains(RuntimeList.class), "explicit dynamic typing consults the runtime class: " + introspected);
    }

    @Test
    void aCollectionOfAnOpenTypeKeepsTheRuntimeTyping() throws Exception {
        assertEquals("{\"animals\":[{\"name\":\"Rex\",\"goodBoy\":true}]}",
                mapper.writeValueAsString(new Zoo(List.of(new Dog("Rex", true)))));
    }

    @Test
    void aMapWithAnOpenKeyTypeKeepsTheRuntimeTyping() throws Exception {
        var visits = new RuntimeKeyedMap();
        visits.put("Downtown", 3);
        assertEquals("{\"visits\":{\"Downtown\":3}}", mapper.writeValueAsString(new Visits(visits)));
        assertTrue(introspected.contains(RuntimeKeyedMap.class), "an open key type consults the runtime class: " + introspected);
    }
}
