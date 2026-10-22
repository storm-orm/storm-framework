package st.orm.jackson;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static st.orm.core.template.ORMTemplate.of;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbColumn;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Json;
import st.orm.PK;
import st.orm.Persist;
import st.orm.Projection;
import st.orm.Ref;
import st.orm.jackson.model.Address;
import st.orm.jackson.model.Owner;
import st.orm.jackson.model.Pet;
import st.orm.jackson.model.PetType;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class JsonIntegrationTest {

    private DataSource dataSource;

    @BeforeEach
    void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    public void ownerEntityWithJsonAddressShouldRoundTripThroughJackson() throws Exception {
        // Owner id=1 is "Betty Davis" with a JSON-stored address. The @Json annotation on the address
        // field causes Storm to deserialize the JSON column into an Address record. When serialized
        // to JSON using Jackson, the entity should produce valid JSON and deserialize back to an equal object.
        var orm = of(dataSource);
        var owner = orm.entity(Owner.class).getById(1);
        var mapper = JsonMapper.builder().build();
        var json = mapper.writeValueAsString(owner);
        assertEquals("{\"id\":1,\"firstName\":\"Betty\",\"lastName\":\"Davis\",\"address\":{\"address\":\"638 Cardinal Ave.\",\"city\":\"Sun Prairie\"},\"telephone\":\"6085551749\"}", json);
        var fromJson = mapper.readValue(json, Owner.class);
        assertEquals(owner, fromJson);
    }

    public record Person(String firstName, String lastName) {}

    @DbTable("owner")
    public record OwnerWithJsonPerson(
            @PK Integer id,
            @Json Person person,
            @Json Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void entityWithMultipleJsonFieldsShouldRoundTripIncludingComputedJsonColumns() throws Exception {
        // Uses a raw query that constructs a JSON person object from first_name/last_name columns.
        // Both the @Json person field and @Json address field should serialize/deserialize correctly.
        // Owner id=1 is "Betty Davis" at "638 Cardinal Ave., Sun Prairie".
        var orm = of(dataSource);
        var query = orm.query("SELECT id, JSON_OBJECT('firstName' VALUE first_name, 'lastName' VALUE last_name) AS person, address, telephone FROM owner LIMIT 1");
        var owner = query.getSingleResult(OwnerWithJsonPerson.class);
        var mapper = JsonMapper.builder().build();
        var json = mapper.writeValueAsString(owner);
        assertEquals("""
                {"id":1,"person":{"firstName":"Betty","lastName":"Davis"},"address":{"address":"638 Cardinal Ave.","city":"Sun Prairie"},"telephone":"6085551749"}""", json);
        var fromJson = mapper.readValue(json, OwnerWithJsonPerson.class);
        assertEquals(owner, fromJson);
    }

    @Test
    public void entityWithFkRelationshipsShouldSerializeNestedEntitiesInline() throws Exception {
        // Pet id=1 is "Leo", a cat owned by Betty Davis. The entity graph includes FK-joined
        // PetType and Owner entities. When serialized with Jackson, dates as ISO strings,
        // all nested entities should appear inline with their full field values, and the result should round-trip.
        var orm = of(dataSource);
        var pet = orm.entity(Pet.class).getById(1);
        var mapper = JsonMapper.builder().findAndAddModules().disable(WRITE_DATES_AS_TIMESTAMPS).build();
        var json = mapper.writeValueAsString(pet);
        assertEquals("""
                {"id":1,"name":"Leo","birthDate":"2020-09-07","petType":{"id":1,"name":"cat"},"owner":{"id":1,"firstName":"Betty","lastName":"Davis","address":{"address":"638 Cardinal Ave.","city":"Sun Prairie"},"telephone":"6085551749"}}""", json);
        var fromJson = mapper.readValue(json, Pet.class);
        assertEquals(pet, fromJson);
    }

    @DbTable("pet")
    public record PetWithRefOwner(
            @PK Integer id,
            String name,
            @Persist(updatable = false) LocalDate birthDate,
            @FK @Persist(updatable = false) @DbColumn("type_id") PetType petType,
            @Nullable @FK Ref<Owner> owner
    ) implements Entity<Integer> {}

    @Test
    public void unloadedEntityRefShouldSerializeToRawPrimaryKeyValue() throws Exception {
        // Per API contract, an unloaded Ref (lazy reference) serializes to just the primary key value,
        // not the full entity. Pet id=1 has owner_id=1, so the unloaded owner Ref should serialize as 1.
        // StormModule must be registered to enable Ref serialization support.
        var orm = of(dataSource);
        var pet = orm.entity(PetWithRefOwner.class).getById(1);
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(WRITE_DATES_AS_TIMESTAMPS)
                .addModule(new StormModule())
                .build();
        var json = mapper.writeValueAsString(pet);
        assertEquals("""
                {"id":1,"name":"Leo","birthDate":"2020-09-07","petType":{"id":1,"name":"cat"},"owner":1}""", json);
        var fromJson = mapper.readValue(json, PetWithRefOwner.class);
        assertEquals(pet, fromJson);
    }

    @Test
    public void loadedEntityRefShouldSerializeWithEntityWrapper() throws Exception {
        // Per API contract, a loaded Ref (where the entity has been fetched) serializes as an object
        // with an "@entity" wrapper containing the full entity data. This allows the deserializer to
        // reconstruct a loaded Ref with the entity available.
        var orm = of(dataSource);
        var pet = orm.entity(PetWithRefOwner.class).getById(1);
        var owner = ofNullable(pet.owner()).map(Ref::fetch).orElseThrow();
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(WRITE_DATES_AS_TIMESTAMPS)
                .addModule(new StormModule())
                .build();
        var json = mapper.writeValueAsString(pet);
        assertEquals("""
                {"id":1,"name":"Leo","birthDate":"2020-09-07","petType":{"id":1,"name":"cat"},"owner":{"@entity":{"id":1,"firstName":"Betty","lastName":"Davis","address":{"address":"638 Cardinal Ave.","city":"Sun Prairie"},"telephone":"6085551749"}}}""", json);
        var fromJson = mapper.readValue(json, PetWithRefOwner.class);
        assertEquals(pet, fromJson);
        assertEquals(owner, ofNullable(fromJson.owner()).map(Ref::fetch).orElseThrow());
    }

    @DbTable("owner")
    public record ProjectionOwner(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json Address address,
            @Nullable String telephone
    ) implements Projection<Integer> {
    }

    @DbTable("pet")
    public record PetWithProjectionRefOwner(
            @PK Integer id,
            String name,
            @Persist(updatable = false) LocalDate birthDate,
            @FK @Persist(updatable = false) @DbColumn("type_id") PetType petType,
            @Nullable @FK Ref<ProjectionOwner> owner
    ) implements Entity<Integer> {}

    @Test
    public void unloadedProjectionRefShouldSerializeToRawPrimaryKeyValue() throws Exception {
        // When the Ref target is a Projection (not an Entity), unloaded Refs still serialize
        // to just the primary key value, same as entity Refs.
        var orm = of(dataSource);
        var pet = orm.entity(PetWithProjectionRefOwner.class).getById(1);
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(WRITE_DATES_AS_TIMESTAMPS)
                .addModule(new StormModule())
                .build();
        var json = mapper.writeValueAsString(pet);
        assertEquals("""
                {"id":1,"name":"Leo","birthDate":"2020-09-07","petType":{"id":1,"name":"cat"},"owner":1}""", json);
        var fromJson = mapper.readValue(json, PetWithProjectionRefOwner.class);
        assertEquals(pet, fromJson);
    }

    @Test
    public void loadedProjectionRefShouldSerializeWithProjectionWrapperAndId() throws Exception {
        // Unlike entity Refs, loaded projection Refs serialize with both "@id" and "@projection" fields.
        // The "@id" field is needed because projections may not have an id() accessor, so the id
        // must be serialized separately to enable reconstruction.
        var orm = of(dataSource);
        var pet = orm.entity(PetWithProjectionRefOwner.class).getById(1);
        var owner = ofNullable(pet.owner()).map(Ref::fetch).orElseThrow();
        var mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(WRITE_DATES_AS_TIMESTAMPS)
                .addModule(new StormModule())
                .build();
        var json = mapper.writeValueAsString(pet);
        assertEquals("""
                {"id":1,"name":"Leo","birthDate":"2020-09-07","petType":{"id":1,"name":"cat"},"owner":{"@id":1,"@projection":{"id":1,"firstName":"Betty","lastName":"Davis","address":{"address":"638 Cardinal Ave.","city":"Sun Prairie"},"telephone":"6085551749"}}}""", json);
        var fromJson = mapper.readValue(json, PetWithProjectionRefOwner.class);
        assertEquals(pet, fromJson);
        assertEquals(owner, ofNullable(fromJson.owner()).map(Ref::fetch).orElseThrow());
    }
}
