package st.orm.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Entity;
import st.orm.PK;
import st.orm.Projection;
import st.orm.Ref;
import st.orm.core.spi.RefFactory;
import st.orm.jackson.model.VetSpecialty;
import st.orm.jackson.model.VetSpecialtyPK;

/**
 * The JSON forms of a {@code Ref}, written and read through {@link StormModule}: a loaded entity as
 * {@code {"@entity": ...}}, a loaded projection as {@code {"@id": ..., "@projection": ...}}, an unloaded ref as its
 * id, a compound key as a plain object, inside containers and with {@code null}, for keys of every type and for a
 * data type without a key, whose id is read by its JSON form.
 */
class StormModuleTest {

    public record SimpleEntity(@PK Integer id, String name) implements Entity<Integer> {}

    public record StringIdEntity(@PK String id, String name) implements Entity<String> {}

    public record LongIdEntity(@PK Long id, String name) implements Entity<Long> {}

    public record DoubleIdEntity(@PK Double id, String name) implements Entity<Double> {}

    public record SimpleProjection(@PK Integer id, String label) implements Projection<Integer> {}

    /** A data type without a key: the id of its refs is read by its JSON form. */
    public record NoPkData(Integer id, String value) implements Data {}

    /** A projection without a key: the id of the loaded form is read by its JSON form. */
    public record NoPkProjection(String label) implements Projection<Void> {}

    public record EntityHolder(@Nullable Ref<SimpleEntity> entity) {}

    public record StringIdHolder(Ref<StringIdEntity> entity) {}

    public record LongIdHolder(Ref<LongIdEntity> entity) {}

    public record DoubleIdHolder(Ref<DoubleIdEntity> entity) {}

    public record ProjectionHolder(@Nullable Ref<SimpleProjection> projection) {}

    public record CompoundPkHolder(@Nullable Ref<VetSpecialty> entity) {}

    public record NoPkHolder(@Nullable Ref<NoPkData> data) {}

    public record NoPkProjectionHolder(@Nullable Ref<NoPkProjection> projection) {}

    public record EntityListHolder(List<Ref<SimpleEntity>> entities) {}

    public record EntitySetHolder(Set<Ref<SimpleEntity>> entities) {}

    public record EntityMapHolder(Map<String, Ref<SimpleEntity>> entities) {}

    public record ProjectionListHolder(List<Ref<SimpleProjection>> projections) {}

    public record NoPkListHolder(List<Ref<NoPkData>> data) {}

    public record MultiTypeHolder(@Nullable Ref<SimpleEntity> entity, @Nullable Ref<SimpleProjection> projection) {}

    private static JsonMapper mapper() {
        return mapper(new StormModule());
    }

    private static JsonMapper mapper(StormModule module) {
        return JsonMapper.builder().addModule(module).build();
    }

    /** A ref factory that counts the refs it creates, standing in for the attached factory of a template. */
    private static final class CountingRefFactory implements RefFactory {
        int created;

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        public <T extends Data, ID> Ref<T> create(Class<T> type, ID id) {
            created++;
            return (Ref<T>) Ref.of((Class) type, id);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        public <T extends Data, ID> Ref<T> create(T record, ID id) {
            return (Ref<T>) Ref.of((Class) record.getClass(), id);
        }
    }

    // Writing.

    @Test
    void anUnloadedRefWritesAsItsId() throws Exception {
        assertEquals("{\"entity\":42}", mapper().writeValueAsString(new EntityHolder(Ref.of(SimpleEntity.class, 42))));
        assertEquals("{\"entity\":\"abc-123\"}",
                mapper().writeValueAsString(new StringIdHolder(Ref.of(StringIdEntity.class, "abc-123"))));
        assertEquals("{\"entity\":9999999999}",
                mapper().writeValueAsString(new LongIdHolder(Ref.of(LongIdEntity.class, 9999999999L))));
        assertEquals("{\"entity\":3.14}",
                mapper().writeValueAsString(new DoubleIdHolder(Ref.of(DoubleIdEntity.class, 3.14))));
    }

    @Test
    void aLoadedEntityRefWritesAsTheEntityWrapper() throws Exception {
        var holder = new EntityHolder(Ref.of(new SimpleEntity(7, "Test")));
        assertEquals("{\"entity\":{\"@entity\":{\"id\":7,\"name\":\"Test\"}}}", mapper().writeValueAsString(holder));
    }

    @Test
    void aLoadedProjectionRefWritesAsTheProjectionWrapperWithItsId() throws Exception {
        var holder = new ProjectionHolder(Ref.of(new SimpleProjection(3, "Label"), 3));
        assertEquals("{\"projection\":{\"@id\":3,\"@projection\":{\"id\":3,\"label\":\"Label\"}}}",
                mapper().writeValueAsString(holder));
    }

    @Test
    void anUnloadedCompoundKeyRefWritesAsTheKeyObject() throws Exception {
        var holder = new CompoundPkHolder(Ref.of(VetSpecialty.class, new VetSpecialtyPK(1, 2)));
        assertEquals("{\"entity\":{\"vetId\":1,\"specialtyId\":2}}", mapper().writeValueAsString(holder));
    }

    @Test
    void aNullRefWritesAsNull() throws Exception {
        assertEquals("{\"entity\":null}", mapper().writeValueAsString(new EntityHolder(null)));
        assertEquals("{\"projection\":null}", mapper().writeValueAsString(new ProjectionHolder(null)));
    }

    @Test
    void aRefOfADataTypeWithoutAKeyWritesItsIdAsIs() throws Exception {
        // Without a key type to find a serializer for, the id serializes as the value it is.
        assertTrue(mapper().writeValueAsString(new NoPkHolder(Ref.of(NoPkData.class, 42))).contains("42"));
        assertTrue(mapper().writeValueAsString(new NoPkHolder(Ref.of(NoPkData.class, "text-id"))).contains("text-id"));
    }

    @Test
    void aRefWritesOutsideAPropertyToo() throws Exception {
        // Serialized directly, the serializer has no property to resolve the key type from.
        var json = mapper().writeValueAsString(Ref.of(SimpleEntity.class, 42));
        assertTrue(json.contains("42"), json);
    }

    @Test
    void aFieldThatIsNotARefIsLeftToJackson() throws Exception {
        record NonRefHolder(String value) {}
        assertEquals("{\"value\":\"test\"}", mapper().writeValueAsString(new NonRefHolder("test")));
    }

    // Reading.

    @Test
    void anIdReadsAsAnUnloadedRef() throws Exception {
        var entity = mapper().readValue("{\"entity\":42}", EntityHolder.class).entity();
        assertNotNull(entity);
        assertEquals(42, entity.id());
        assertNull(entity.getOrNull());
        assertEquals("abc", mapper().readValue("{\"entity\":\"abc\"}", StringIdHolder.class).entity().id());
        assertEquals(9999999999L, mapper().readValue("{\"entity\":9999999999}", LongIdHolder.class).entity().id());
        assertEquals(3.14, mapper().readValue("{\"entity\":3.14}", DoubleIdHolder.class).entity().id());
    }

    @Test
    void anEntityWrapperReadsAsALoadedRef() throws Exception {
        var holder = mapper().readValue("{\"entity\":{\"@entity\":{\"id\":7,\"name\":\"Test\"}}}", EntityHolder.class);
        assertNotNull(holder.entity());
        assertTrue(holder.entity().isLoaded());
        var entity = holder.entity().fetch();
        assertInstanceOf(SimpleEntity.class, entity);
        assertEquals(7, entity.id());
        assertEquals("Test", entity.name());
    }

    @Test
    void aProjectionWrapperReadsAsALoadedRef() throws Exception {
        var json = "{\"projection\":{\"@id\":3,\"@projection\":{\"id\":3,\"label\":\"Label\"}}}";
        var holder = mapper().readValue(json, ProjectionHolder.class);
        assertNotNull(holder.projection());
        assertTrue(holder.projection().isLoaded());
        var projection = holder.projection().fetch();
        assertInstanceOf(SimpleProjection.class, projection);
        assertEquals(3, projection.id());
        assertEquals("Label", projection.label());
    }

    @Test
    void aProjectionWrapperHoldingAnEntityReadsAsALoadedEntityRef() throws Exception {
        var json = "{\"entity\":{\"@id\":1,\"@projection\":{\"id\":1,\"name\":\"Via Projection Path\"}}}";
        var holder = mapper().readValue(json, EntityHolder.class);
        assertNotNull(holder.entity());
        assertEquals(1, holder.entity().id());
        var loaded = holder.entity().getOrNull();
        assertNotNull(loaded);
        assertEquals("Via Projection Path", loaded.name());
    }

    @Test
    void aProjectionWrapperHoldingPlainDataReadsAsARefOfItsId() throws Exception {
        // Neither entity nor projection: the id of the wrapper is the ref's id.
        var json = "{\"data\":{\"@id\":42,\"@projection\":{\"id\":42,\"value\":\"test\"}}}";
        var holder = mapper().readValue(json, NoPkHolder.class);
        assertNotNull(holder.data());
        assertEquals(42, ((Number) holder.data().id()).intValue());
    }

    @Test
    void aCompoundKeyObjectReadsAsAnUnloadedRef() throws Exception {
        var holder = mapper().readValue("{\"entity\":{\"vetId\":1,\"specialtyId\":2}}", CompoundPkHolder.class);
        assertNotNull(holder.entity());
        assertEquals(new VetSpecialtyPK(1, 2), holder.entity().id());
    }

    @Test
    void nullReadsAsNoRef() throws Exception {
        assertNull(mapper().readValue("{\"entity\":null}", EntityHolder.class).entity());
        assertNull(mapper().readValue("{\"projection\":null}", ProjectionHolder.class).projection());
    }

    @Test
    void aWrapperHoldingNullReadsAsNoRef() throws Exception {
        assertNull(mapper().readValue("{\"entity\":{\"@entity\":null}}", EntityHolder.class).entity());
        assertNull(mapper().readValue("{\"projection\":{\"@id\":3,\"@projection\":null}}", ProjectionHolder.class)
                .projection());
    }

    // Round trips.

    @Test
    void anEntityRefRoundTripsLoadedAndUnloaded() throws Exception {
        var unloaded = new EntityHolder(Ref.of(SimpleEntity.class, 42));
        assertEquals(unloaded, mapper().readValue(mapper().writeValueAsString(unloaded), EntityHolder.class));
        var entity = new SimpleEntity(5, "RoundTrip");
        var loaded = new EntityHolder(Ref.of(entity));
        var restored = mapper().readValue(mapper().writeValueAsString(loaded), EntityHolder.class);
        assertEquals(loaded, restored);
        assertTrue(restored.entity().isLoaded());
        assertEquals(entity, restored.entity().fetch());
    }

    @Test
    void aProjectionRefRoundTripsLoadedAndUnloaded() throws Exception {
        var unloaded = new ProjectionHolder(Ref.of(SimpleProjection.class, 5));
        assertEquals(unloaded, mapper().readValue(mapper().writeValueAsString(unloaded), ProjectionHolder.class));
        var projection = new SimpleProjection(10, "Proj");
        var loaded = new ProjectionHolder(Ref.of(projection, 10));
        var restored = mapper().readValue(mapper().writeValueAsString(loaded), ProjectionHolder.class);
        assertEquals(loaded, restored);
        assertTrue(restored.projection().isLoaded());
        assertEquals(projection, restored.projection().fetch());
    }

    @Test
    void aCompoundKeyRefRoundTrips() throws Exception {
        var holder = new CompoundPkHolder(Ref.of(VetSpecialty.class, new VetSpecialtyPK(3, 4)));
        assertEquals(holder, mapper().readValue(mapper().writeValueAsString(holder), CompoundPkHolder.class));
    }

    @Test
    void refsOfEveryKeyTypeRoundTrip() throws Exception {
        var string = new StringIdHolder(Ref.of(StringIdEntity.class, "my-id"));
        assertEquals(string, mapper().readValue(mapper().writeValueAsString(string), StringIdHolder.class));
        var loadedString = new StringIdHolder(Ref.of(new StringIdEntity("abc", "Test")));
        assertEquals(loadedString, mapper().readValue(mapper().writeValueAsString(loadedString), StringIdHolder.class));
        var loadedLong = new LongIdHolder(Ref.of(new LongIdEntity(999L, "Test")));
        assertEquals(loadedLong, mapper().readValue(mapper().writeValueAsString(loadedLong), LongIdHolder.class));
        var loadedDouble = new DoubleIdHolder(Ref.of(new DoubleIdEntity(2.71, "Euler")));
        var json = mapper().writeValueAsString(loadedDouble);
        assertTrue(json.contains("@entity"), json);
        assertEquals(loadedDouble, mapper().readValue(json, DoubleIdHolder.class));
    }

    // Containers.

    @Test
    void refsInAListReadWithNullElements() throws Exception {
        var holder = mapper().readValue("{\"entities\":[42, null, 7]}", EntityListHolder.class);
        assertEquals(3, holder.entities().size());
        assertEquals(42, holder.entities().get(0).id());
        assertNull(holder.entities().get(1));
        assertEquals(7, holder.entities().get(2).id());
        assertEquals(0, mapper().readValue("{\"entities\":[]}", EntityListHolder.class).entities().size());
    }

    @Test
    void refsInAListReadLoadedAndUnloadedAlike() throws Exception {
        var json = "{\"entities\":[null,1,{\"@entity\":{\"id\":2,\"name\":\"Bob\"}}]}";
        var holder = mapper().readValue(json, EntityListHolder.class);
        assertEquals(3, holder.entities().size());
        assertNull(holder.entities().get(0));
        assertEquals(1, holder.entities().get(1).id());
        assertNull(holder.entities().get(1).getOrNull());
        assertEquals(2, holder.entities().get(2).id());
        assertNotNull(holder.entities().get(2).getOrNull());
    }

    @Test
    void refsInAListRoundTrip() throws Exception {
        var entities = new EntityListHolder(List.of(
                Ref.of(new SimpleEntity(1, "Alice")),
                Ref.of(SimpleEntity.class, 2),
                Ref.of(new SimpleEntity(3, "Charlie"))));
        var restored = mapper().readValue(mapper().writeValueAsString(entities), EntityListHolder.class);
        assertEquals(3, restored.entities().size());
        assertNotNull(restored.entities().get(0).getOrNull());
        assertNull(restored.entities().get(1).getOrNull());
        assertNotNull(restored.entities().get(2).getOrNull());
        var projections = new ProjectionListHolder(List.of(
                Ref.of(new SimpleProjection(1, "First"), 1),
                Ref.of(SimpleProjection.class, 2)));
        var restoredProjections = mapper().readValue(mapper().writeValueAsString(projections), ProjectionListHolder.class);
        assertEquals(2, restoredProjections.projections().size());
        assertNotNull(restoredProjections.projections().get(0).getOrNull());
        assertNull(restoredProjections.projections().get(1).getOrNull());
    }

    @Test
    void refsInASetAndAMapValueRoundTrip() throws Exception {
        var set = new EntitySetHolder(Set.of(Ref.of(SimpleEntity.class, 1), Ref.of(SimpleEntity.class, 2)));
        assertEquals(set, mapper().readValue(mapper().writeValueAsString(set), EntitySetHolder.class));
        assertEquals(3, mapper().readValue("{\"entities\":[1,2,3]}", EntitySetHolder.class).entities().size());
        var map = new EntityMapHolder(Map.of("a", Ref.of(SimpleEntity.class, 1), "b", Ref.of(SimpleEntity.class, 2)));
        assertEquals(map, mapper().readValue(mapper().writeValueAsString(map), EntityMapHolder.class));
        var read = mapper().readValue("{\"entities\":{\"a\":1,\"b\":2}}", EntityMapHolder.class);
        assertEquals(1, read.entities().get("a").id());
        assertEquals(2, read.entities().get("b").id());
    }

    @Test
    void refsOfDifferentTypesInOneHolderReadEachByTheirOwnType() throws Exception {
        var holder = mapper().readValue("{\"entity\":42,\"projection\":7}", MultiTypeHolder.class);
        assertNotNull(holder.entity());
        assertEquals(42, holder.entity().id());
        assertNotNull(holder.projection());
        assertEquals(7, holder.projection().id());
    }

    // The ref factory.

    @Test
    void anIdReadsThroughTheRefFactoryWhenThereIsOne() throws Exception {
        var factory = new CountingRefFactory();
        var holder = mapper(new StormModule(factory)).readValue("{\"entities\":[1,2,3]}", EntityListHolder.class);
        assertEquals(3, holder.entities().size());
        assertEquals(3, factory.created);
        var supplied = new CountingRefFactory();
        assertEquals(99, mapper(new StormModule(() -> supplied)).readValue("{\"entity\":99}", EntityHolder.class).entity().id());
        assertEquals(1, supplied.created);
    }

    @Test
    void withoutARefFactoryAnIdReadsAsADetachedRef() throws Exception {
        for (var module : List.of(new StormModule(), new StormModule((RefFactory) null), new StormModule(() -> null))) {
            var entity = mapper(module).readValue("{\"entity\":7}", EntityHolder.class).entity();
            assertNotNull(entity);
            assertEquals(7, entity.id());
            assertNull(entity.getOrNull());
        }
    }

    // A data type without a key: the id is read by its JSON form.

    @Test
    void anIdOfADataTypeWithoutAKeyReadsByItsJsonForm() throws Exception {
        assertEquals(42, ((Number) mapper().readValue("{\"data\":42}", NoPkHolder.class).data().id()).intValue());
        assertEquals("abc", mapper().readValue("{\"data\":\"abc\"}", NoPkHolder.class).data().id());
        assertEquals(3.14, mapper().readValue("{\"data\":3.14}", NoPkHolder.class).data().id());
        long large = (long) Integer.MAX_VALUE + 1L;
        assertEquals(large, mapper().readValue("{\"data\":" + large + "}", NoPkHolder.class).data().id());
        long negative = (long) Integer.MIN_VALUE - 1L;
        assertEquals(negative, mapper().readValue("{\"data\":" + negative + "}", NoPkHolder.class).data().id());
    }

    @Test
    void idsOfADataTypeWithoutAKeyReadInAList() throws Exception {
        var holder = mapper().readValue("{\"data\":[1,\"two\",3,null]}", NoPkListHolder.class);
        assertEquals(4, holder.data().size());
        assertEquals(1, ((Number) holder.data().get(0).id()).intValue());
        assertEquals("two", holder.data().get(1).id());
        assertEquals(3, ((Number) holder.data().get(2).id()).intValue());
        assertNull(holder.data().get(3));
    }

    @Test
    void theIdOfAProjectionWrapperWithoutAKeyTypeReadsByItsJsonForm() throws Exception {
        assertEquals(42, projectionId("42"));
        assertEquals(9999999999L, projectionId("9999999999"));
        assertEquals(3.14, projectionId("3.14"));
        assertEquals("abc", projectionId("\"abc\""));
        assertEquals(Map.of("key", "val"), projectionId("{\"key\":\"val\"}"));
    }

    private static Object projectionId(String idJson) throws Exception {
        var json = "{\"projection\":{\"@id\":" + idJson + ",\"@projection\":{\"label\":\"Test\"}}}";
        var holder = mapper().readValue(json, NoPkProjectionHolder.class);
        assertNotNull(holder.projection());
        assertTrue(holder.projection().isLoaded());
        return holder.projection().id();
    }

    @Test
    void aProjectionWrapperOfATypeWithoutAKeyReadsItsIdByItsJsonForm() throws Exception {
        var json = "{\"data\":{\"@id\":3000000000,\"@projection\":{\"id\":5,\"value\":\"test\"}}}";
        assertEquals(3000000000L, ((Number) mapper().readValue(json, NoPkHolder.class).data().id()).longValue());
        json = "{\"data\":{\"@id\":\"uuid-value\",\"@projection\":{\"id\":5,\"value\":\"test\"}}}";
        assertEquals("uuid-value", mapper().readValue(json, NoPkHolder.class).data().id());
    }

    // Refusals.

    @Test
    void anObjectWithoutAWrapperFieldOrACompoundKeyIsRefused() {
        assertThrows(DatabindException.class,
                () -> mapper().readValue("{\"entity\":{\"unknown\":1}}", EntityHolder.class));
        // A type without a key has no compound key an object could be.
        assertThrows(DatabindException.class,
                () -> mapper().readValue("{\"data\":{\"unknown\":1}}", NoPkHolder.class));
    }

    @Test
    void aProjectionWrapperWithoutAnIdIsRefused() {
        assertThrows(DatabindException.class, () -> mapper().readValue(
                "{\"projection\":{\"@projection\":{\"id\":5,\"label\":\"Test\"}}}", ProjectionHolder.class));
        // A null id is no id either, when the value is neither entity nor projection.
        assertThrows(DatabindException.class, () -> mapper().readValue(
                "{\"data\":{\"@id\":null,\"@projection\":{\"id\":5,\"value\":\"test\"}}}", NoPkHolder.class));
    }

    @Test
    void anEntityWrapperHoldingSomethingElseThanAnEntityIsRefused() {
        assertThrows(DatabindException.class, () -> mapper().readValue(
                "{\"data\":{\"@entity\":{\"id\":42,\"value\":\"test\"}}}", NoPkHolder.class));
    }

    @Test
    void aTokenThatIsNotAnIdOrAnObjectIsRefused() {
        assertThrows(DatabindException.class, () -> mapper().readValue("{\"entity\":true}", EntityHolder.class));
        assertThrows(DatabindException.class, () -> mapper().readValue("{\"entity\":[1,2,3]}", EntityHolder.class));
        assertThrows(DatabindException.class, () -> mapper().readValue("{\"data\":true}", NoPkHolder.class));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void aRawRefWithoutATargetTypeIsRefused() {
        assertThrows(DatabindException.class, () -> mapper().readValue("42", Ref.class));
    }
}
