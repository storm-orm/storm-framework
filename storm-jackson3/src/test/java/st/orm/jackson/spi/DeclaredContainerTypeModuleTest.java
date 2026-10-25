package st.orm.jackson.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapType;

/**
 * A collection or map property of final element types writes by its declared type, so the class holding the value
 * is never introspected; a property of an open element type keeps the runtime typing that writes the subtype's
 * properties.
 */
class DeclaredContainerTypeModuleTest {

    /** The classes Jackson built a collection or map serializer for: the runtime class, when it is consulted. */
    private final Set<Class<?>> introspected = new LinkedHashSet<>();

    private final SimpleModule spy = new SimpleModule("Spy").setSerializerModifier(new ValueSerializerModifier() {
        @Override
        public ValueSerializer<?> modifyCollectionSerializer(SerializationConfig config, CollectionType type,
                                                             BeanDescription.Supplier beanDesc, ValueSerializer<?> serializer) {
            introspected.add(type.getRawClass());
            return serializer;
        }

        @Override
        public ValueSerializer<?> modifyMapSerializer(SerializationConfig config, MapType type,
                                                      BeanDescription.Supplier beanDesc, ValueSerializer<?> serializer) {
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
    void aCollectionOfAFinalTypeWritesByItsDeclaredType() {
        assertEquals("{\"clinics\":[\"Downtown\"]}", mapper.writeValueAsString(new Clinics(clinics())));
        assertFalse(introspected.contains(RuntimeList.class), "the runtime class is not consulted: " + introspected);
        introspected.clear();
        JsonMapper.builder().addModule(spy).build().writeValueAsString(new Clinics(clinics()));
        assertTrue(introspected.contains(RuntimeList.class), "the runtime class decides without the module: " + introspected);
    }

    @Test
    void aNestedCollectionOfAFinalTypeWritesByItsDeclaredType() {
        assertEquals("{\"schedule\":[[\"Downtown\"]]}", mapper.writeValueAsString(new Nested(List.of(clinics()))));
        assertFalse(introspected.contains(RuntimeList.class), "the runtime class is not consulted: " + introspected);
    }

    @Test
    void aMapOfFinalTypesWritesByItsDeclaredType() {
        var scores = new RuntimeMap();
        scores.put("Downtown", 3);
        assertEquals("{\"scores\":{\"Downtown\":3}}", mapper.writeValueAsString(new Scores(scores)));
        assertFalse(introspected.contains(RuntimeMap.class), "the runtime class is not consulted: " + introspected);
    }

    @Test
    void anExplicitTypingOnThePropertyWins() {
        mapper.writeValueAsString(new Dynamic(clinics()));
        assertTrue(introspected.contains(RuntimeList.class), "explicit dynamic typing consults the runtime class: " + introspected);
    }

    @Test
    void aCollectionOfAnOpenTypeKeepsTheRuntimeTyping() {
        assertEquals("{\"animals\":[{\"name\":\"Rex\",\"goodBoy\":true}]}",
                mapper.writeValueAsString(new Zoo(List.of(new Dog("Rex", true)))));
    }
}
