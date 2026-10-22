package st.orm.jackson;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.ORMTemplate.of;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.Templates.alias;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Inline;
import st.orm.Json;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.jackson.model.Address;
import st.orm.jackson.model.Owner;
import st.orm.jackson.model.Specialty;
import st.orm.jackson.model.Vet;
import st.orm.jackson.model.VetSpecialty;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class JsonORMConverterIntegrationTest {

    private DataSource dataSource;

    @BeforeEach
    void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    public void selectOwnersShouldReturnAll10DistinctOwnersFromTestData() {
        // The test data contains 10 owner rows. Querying all columns and mapping to Owner (which has
        // a @Json address field) should produce 10 distinct results, verifying JSON column deserialization.
        var orm = of(dataSource);
        var query = orm.query("SELECT id, first_name, last_name, address, telephone FROM owner");
        var owner = query.getResultList(Owner.class);
        assertEquals(10, owner.stream().distinct().count());
    }

    @Test
    public void jsonArrayOfRefIdsShouldDeserializeToListOfUnloadedRefs() {
        // JSON_ARRAYAGG(id) produces a JSON array of owner IDs. When mapped to a List<Ref<Owner>>,
        // each element should be an unloaded Ref with the correct ID. There are 10 distinct owners.
        record Result(@Json List<Ref<Owner>> owner) {}
        var orm = of(dataSource);
        var query = orm.query("SELECT JSON_ARRAYAGG(id) FROM owner");
        var owner = query.getSingleResult(Result.class).owner().stream().map(Ref::id).distinct().toList();
        assertEquals(10, owner.size());
    }

    @Test
    public void jsonArrayWithNullElementShouldDeserializeToNullableRefList() {
        // A JSON array containing null and a valid ID should produce a list with one null Ref
        // and one valid Ref. This verifies null handling in JSON Ref deserialization.
        record Result(@Json List<Ref<Owner>> owner) {}
        var orm = of(dataSource);
        var query = orm.query("SELECT '[null, 1]'");
        var owner = query.getSingleResult(Result.class).owner().stream().map(ref -> {
            if (ref == null) {
                return null;
            } else {
                return ref.id();
            }
        }).distinct().toList();
        assertEquals(2, owner.size());
        assertEquals(1, owner.stream().filter(Objects::isNull).count());
    }

    @Test
    public void insertEntityWithJsonAddressFieldShouldPersistAndReturnCorrectAddress() {
        // Inserting an owner with a JSON-serialized address should store the address as JSON in the
        // database and return it correctly when fetched back via insertAndFetch.
        var orm = of(dataSource);
        var repository = orm.entity(Owner.class);
        var address = new Address("271 University Ave", "Palo Alto");
        var owner = Owner.builder().firstName("Simon").lastName("McDonald").address(address).telephone("555-555-5555").build();
        var inserted = repository.insertAndFetch(owner);
        assertEquals(address, inserted.address());
    }

    @Test
    public void updateEntityWithJsonAddressFieldShouldPersistTheNewAddress() {
        // Updating owner id=1's address to a new value should persist the JSON change.
        // Re-fetching the owner should return the updated address.
        var orm = of(dataSource);
        var repository = orm.entity(Owner.class);
        var owner = repository.getById(1);
        var address = new Address("271 University Ave", "Palo Alto");
        repository.update(owner.toBuilder().address(address).build());
        var updated = repository.getById(1);
        assertEquals(address, updated.address());
    }

    public record Person(String firstName, String lastName) {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithJsonPerson(
            @PK Integer id,
            @Json Person person,
            @Json Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void computedJsonPersonColumnShouldDeserializeCorrectlyForAllOwners() {
        // Uses JSON_OBJECT to create a person JSON column from first_name/last_name. The @Json-annotated
        // Person field should be deserialized from the JSON column for all 10 owners in the test data.
        var orm = of(dataSource);
        var query = orm.query("SELECT id, JSON_OBJECT('firstName' VALUE first_name, 'lastName' VALUE last_name) AS person, address, telephone FROM owner");
        var owner = query.getResultList(OwnerWithJsonPerson.class);
        assertEquals(10, owner.size());
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithJsonMapAddress(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json Map<String, String> address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void jsonMapAddressFieldShouldDeserializeJsonColumnIntoMapForAllOwners() {
        // The address column contains JSON objects. Using Map<String, String> as the field type
        // with @Json should deserialize the JSON into a map. All 10 owners should be returned.
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithJsonMapAddress.class);
        var owner = repository.select().getResultList();
        assertEquals(10, owner.size());
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithInlineJsonMapAddress(
            @PK Integer id,
            String firstName,
            String lastName,
            @Inline @Json Map<String, String> address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void inlineAndJsonAnnotationsCombinedOnMapShouldThrowSqlTemplateException() {
        // @Inline and @Json are mutually exclusive on Map fields. @Inline means "expand the fields
        // inline into the SQL", but @Json means "treat as a single JSON column". The framework
        // should reject this combination with a SqlTemplateException.
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            var orm = of(dataSource);
            var repository = orm.entity(OwnerWithInlineJsonMapAddress.class);
            repository.select().getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    public record SpecialtiesByVet(Vet vet, @Json List<Specialty> specialties) {}

    @Test
    public void jsonArrayAggOfSpecialtyObjectsShouldGroupByVet() {
        // Joins vet -> vet_specialty -> specialty and aggregates specialties as a JSON array per vet.
        // Per test data: 4 vets have specialties (vets 2,3,4,5), with 5 total vet-specialty associations.
        // Vet 3 (Linda Douglas) has 2 specialties (surgery, dentistry); the others have 1 each.
        var vets = of(dataSource)
                .selectFrom(Vet.class, SpecialtiesByVet.class, raw("""
                        \0, JSON_ARRAYAGG(
                            JSON_OBJECT(
                                KEY 'id' VALUE \0.id,
                                KEY 'name' VALUE \0.name
                            )
                        ) AS specialties""", Vet.class, alias(Specialty.class), alias(Specialty.class)))
                .innerJoin(VetSpecialty.class).on(Vet.class)
                .innerJoin(Specialty.class).on(VetSpecialty.class)
                .groupBy(raw("\0.id",  Vet.class))
                .getResultList();
        assertEquals(4, vets.size());
        assertEquals(5, vets.stream().mapToLong(v -> v.specialties().size()).sum());
    }

    record SpecialtyNamesByVet(Vet vet, @Json List<String> specialties) {}

    @Test
    public void jsonArrayAggOfSpecialtyNamesShouldGroupByVet() {
        // Similar to the specialty objects test, but aggregates just the name strings.
        // Per test data: 4 vets with specialties, 5 total specialty associations.
        var vets = of(dataSource).selectFrom(Vet.class, SpecialtyNamesByVet.class, raw("""
                        \0, JSON_ARRAYAGG(\0.name) AS specialties""", Vet.class, Specialty.class))
                .innerJoin(VetSpecialty.class).on(Vet.class)
                .innerJoin(Specialty.class).on(VetSpecialty.class)
                .groupBy(raw("\0.id", Vet.class))
                .getResultList();
        assertEquals(4, vets.size());
        assertEquals(5, vets.stream().mapToLong(v -> v.specialties().size()).sum());
    }

    @Test
    public void jsonObjectAggWithEntityTemplateShouldGenerateCorrectSql() {
        // JSON_OBJECTAGG with a full entity template reference should expand to the entity's columns.
        // H2 does not support JSON_OBJECTAGG, so we only verify the generated SQL matches expectations.
        String expectedSql = """
                SELECT v.id, v.first_name, v.last_name, JSON_OBJECTAGG(s.id, s.name)
                FROM vet v
                INNER JOIN vet_specialty vs ON vs.vet_id = v.id
                INNER JOIN specialty s ON vs.specialty_id = s.id
                GROUP BY v.id""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            try {
                of(dataSource).selectFrom(Vet.class, SpecialtyNamesByVet.class, raw("""
                            \0, JSON_OBJECTAGG(\0)""", Vet.class, Specialty.class))
                        .innerJoin(VetSpecialty.class).on(Vet.class)
                        .innerJoin(Specialty.class).on(VetSpecialty.class)
                        .groupBy(raw("\0.id", Vet.class))
                        .getResultList();
            } catch (PersistenceException ignore) {
                // H2 Does not support JSON_OBJECTAGG. We only check the expected SQL.
            }
        });
    }

    // No need to specify the sub types here, as we're automatically registering the implementations of the sealed interface.

    // @JsonSubTypes({
    //         @JsonSubTypes.Type(value = PersonA.class, name = "A"),
    //         @JsonSubTypes.Type(value = PersonB.class, name = "B")
    // })

    @JsonTypeInfo(use = NAME)
    public sealed interface PolymorphicPerson permits PersonA, PersonB {}

    // The type name is automatically derived from the class name, so the annotation is not needed.

    // @JsonTypeName("PersonA")
    public record PersonA(String firstName, String lastName) implements PolymorphicPerson {}

    // @JsonTypeName("PersonB")
    public record PersonB(String firstName, String lastName) implements PolymorphicPerson {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithPolymorphicPerson(
            @PK Integer id,
            @Json PolymorphicPerson person,
            @Json Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithNullableAddress(
            @PK Integer id,
            String firstName,
            String lastName,
            @Nullable @Json Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void insertEntityWithNullJsonFieldShouldPersistNullAndReadBackAsNull() {
        // When the @Json field is nullable and the value is null, toDatabase should serialize as null
        // and fromDatabase should return null when reading from the database.
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithNullableAddress.class);
        var owner = OwnerWithNullableAddress.builder()
                .firstName("NullAddr")
                .lastName("Test")
                .address(null)
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertNull(inserted.address());
    }

    @Test
    public void selectEntityWithNullJsonFieldShouldReturnNull() {
        // Insert a row with null address directly, then select it.
        var orm = of(dataSource);
        orm.query("INSERT INTO owner (first_name, last_name, address, telephone) VALUES ('Null', 'Test', NULL, '555')")
                .executeUpdate();
        var result = orm.query("SELECT id, first_name, last_name, address, telephone FROM owner WHERE first_name = 'Null'")
                .getSingleResult(OwnerWithNullableAddress.class);
        assertNull(result.address());
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithFailOnUnknown(
            @PK Integer id,
            @Json(failOnUnknown = true) Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void jsonWithFailOnUnknownTrueShouldCreateMapperWithStrictMode() {
        // Exercises the @Json(failOnUnknown = true) branch in JsonORMConverterImpl constructor,
        // which skips calling builder.disable(FAIL_ON_UNKNOWN_PROPERTIES).
        // Owner id=1 has a valid address, so deserialization should succeed.
        var orm = of(dataSource);
        var result = orm.query("SELECT id, address, telephone FROM owner WHERE id = 1")
                .getSingleResult(OwnerWithFailOnUnknown.class);
        assertNotNull(result.address());
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithFailOnMissing(
            @PK Integer id,
            @Json(failOnMissing = true) Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void jsonWithFailOnMissingTrueShouldRejectMissingProperties() {
        var orm = of(dataSource);
        var query = orm.query("""
                SELECT id,
                       '{"address":"test"}' AS address,
                       telephone
                FROM owner WHERE id = 1""");
        assertThrows(PersistenceException.class, () -> query.getSingleResult(OwnerWithFailOnMissing.class));
    }

    @Test
    public void jsonWithFailOnMissingTrueShouldCreateMapperWithStrictMode() {
        // Exercises the @Json(failOnMissing = true) branch in JsonORMConverterImpl constructor,
        // which skips calling builder.disable(FAIL_ON_MISSING_CREATOR_PROPERTIES).
        // Owner id=1 has a valid address with all fields, so deserialization should succeed.
        var orm = of(dataSource);
        var result = orm.query("SELECT id, address, telephone FROM owner WHERE id = 1")
                .getSingleResult(OwnerWithFailOnMissing.class);
        assertNotNull(result.address());
    }

    // Custom serializer for Address.
    public static class AddressSerializer extends tools.jackson.databind.ser.std.StdSerializer<Address> {
        public AddressSerializer() {
            super(Address.class);
        }

        @Override
        public void serialize(Address value, tools.jackson.core.JsonGenerator gen,
                              tools.jackson.databind.SerializationContext ctxt)
                throws tools.jackson.core.JacksonException {
            gen.writeString(value.address() + " | " + value.city());
        }
    }

    // Custom deserializer for Address.
    public static class AddressDeserializer extends tools.jackson.databind.deser.std.StdDeserializer<Address> {
        public AddressDeserializer() {
            super(Address.class);
        }

        @Override
        public Address deserialize(tools.jackson.core.JsonParser parser,
                                   tools.jackson.databind.DeserializationContext ctxt)
                throws tools.jackson.core.JacksonException {
            String text = parser.getText();
            String[] parts = text.split(" \\| ");
            return new Address(parts[0], parts[1]);
        }
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithCustomSerializers(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json
            @tools.jackson.databind.annotation.JsonSerialize(using = AddressSerializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using = AddressDeserializer.class)
            Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {
    }

    @Test
    public void customJsonSerializerAndDeserializerShouldBeUsedForJsonField() {
        // Exercises the custom @JsonSerialize/@JsonDeserialize annotation branches
        // in JsonORMConverterImpl constructor (lines 106-126).
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithCustomSerializers.class);
        var address = new Address("123 Main St", "Springfield");
        var owner = OwnerWithCustomSerializers.builder()
                .firstName("Test")
                .lastName("User")
                .address(address)
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        // The custom serializer stores "address | city" as a plain string.
        // The custom deserializer should parse it back.
        assertEquals("123 Main St", inserted.address().address());
        assertEquals("Springfield", inserted.address().city());
        // A later read from the database uses the custom deserializer as well.
        var fetched = repository.getById(inserted.id());
        assertEquals("123 Main St", fetched.address().address());
        assertEquals("Springfield", fetched.address().city());
        // The raw column value proves the serializer engaged; a bypassed pair would round trip plain JSON.
        var row = orm.query("SELECT address, telephone FROM owner WHERE id = " + inserted.id())
                .getSingleResult(RawOwnerRow.class);
        assertEquals("\"123 Main St | Springfield\"", row.address());
    }

    @Test
    public void jsonWithFailOnUnknownTrueShouldRejectExtraProperties() {
        var orm = of(dataSource);
        var query = orm.query("""
                SELECT id,
                       '{"address":"test","city":"test","extraField":"unexpected"}' AS address,
                       telephone
                FROM owner WHERE id = 1""");
        assertThrows(PersistenceException.class, () -> query.getSingleResult(OwnerWithFailOnUnknown.class));
    }

    @Test
    public void polymorphicJsonDeserializationShouldResolveCorrectSubtypeViaDiscriminator() {
        // Uses a sealed interface with @JsonTypeInfo(use = NAME) and two permitted subtypes.
        // The JSON constructed by the query includes '@type' VALUE 'PersonA', so all 10 owners should
        // deserialize with their person field as a PersonA instance.
        var orm = of(dataSource);
        var query = orm.query("SELECT id, JSON_OBJECT('@type' VALUE 'PersonA', 'firstName' VALUE first_name, 'lastName' VALUE last_name) AS person, address, telephone FROM owner");
        var owner = query.getResultList(OwnerWithPolymorphicPerson.class);
        assertEquals(10, owner.size());
        assertTrue(owner.stream().allMatch(x -> x.person instanceof PersonA));
    }

    // Sealed interface with @JsonTypeName annotations on subtypes to cover that branch in getPermittedSubtypes.

    @JsonTypeInfo(use = NAME)
    public sealed interface NamedPerson permits NamedPersonA, NamedPersonB {}

    @com.fasterxml.jackson.annotation.JsonTypeName("A")
    public record NamedPersonA(String firstName, String lastName) implements NamedPerson {}

    @com.fasterxml.jackson.annotation.JsonTypeName("B")
    public record NamedPersonB(String firstName, String lastName) implements NamedPerson {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithNamedPolymorphicPerson(
            @PK Integer id,
            @Json NamedPerson person,
            @Json Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Test
    public void polymorphicJsonWithExplicitTypeNamesShouldResolveSubtype() {
        // Uses @JsonTypeName("A") and @JsonTypeName("B") on sealed subtypes.
        // This exercises the branch in getPermittedSubtypes where typeNameAnnotation is non-null.
        var orm = of(dataSource);
        var query = orm.query("SELECT id, JSON_OBJECT('@type' VALUE 'A', 'firstName' VALUE first_name, 'lastName' VALUE last_name) AS person, address, telephone FROM owner");
        var owner = query.getResultList(OwnerWithNamedPolymorphicPerson.class);
        assertEquals(10, owner.size());
        assertTrue(owner.stream().allMatch(x -> x.person instanceof NamedPersonA));
    }

    // Nested deserialization: a custom deserializer that issues a query mid-deserialization.

    public static class NestedQueryMarkerDeserializer extends tools.jackson.databind.deser.std.StdDeserializer<String> {
        static DataSource nestedDataSource;

        public NestedQueryMarkerDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(tools.jackson.core.JsonParser parser,
                                  tools.jackson.databind.DeserializationContext ctxt)
                throws tools.jackson.core.JacksonException {
            String text = parser.getText();
            // Maps Owner, whose @Json address field enters fromDatabase re-entrantly on this thread.
            of(nestedDataSource)
                    .query("SELECT id, first_name, last_name, address, telephone FROM owner WHERE id = 2")
                    .getSingleResult(Owner.class);
            return text;
        }
    }

    public record OwnerSnapshot(
            @tools.jackson.databind.annotation.JsonDeserialize(using = NestedQueryMarkerDeserializer.class) String marker,
            Ref<Owner> owner
    ) {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithSnapshot(
            @PK Integer id,
            @Json OwnerSnapshot snapshot,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Test
    public void refDeserializedAfterNestedConversionShouldRemainAttached() {
        // The marker field deserializes first and issues a nested query, which binds and unbinds the nested
        // conversion's RefFactory. The owner field that follows must still see the outer factory: the
        // resulting ref is attached and fetches the owner.
        NestedQueryMarkerDeserializer.nestedDataSource = dataSource;
        var orm = of(dataSource);
        var query = orm.query("SELECT id, JSON_OBJECT('marker' VALUE 'audit', 'owner' VALUE id) AS snapshot, telephone FROM owner WHERE id = 1");
        var result = query.getSingleResult(OwnerWithSnapshot.class);
        var ownerRef = result.snapshot().owner();
        assertTrue(ownerRef.isFetchable());
        assertEquals("Betty", ownerRef.fetch().firstName());
    }

    // Two @Json fields of different types sharing one custom serializer class.

    public static class TypeNameMarkerSerializer extends tools.jackson.databind.ser.std.StdSerializer<Object> {
        public TypeNameMarkerSerializer() {
            super(Object.class);
        }

        @Override
        public void serialize(Object value, tools.jackson.core.JsonGenerator gen,
                              tools.jackson.databind.SerializationContext ctxt)
                throws tools.jackson.core.JacksonException {
            gen.writeString("marker:" + value.getClass().getSimpleName());
        }
    }

    public record Phone(String number) {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithSharedSerializer(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json
            @tools.jackson.databind.annotation.JsonSerialize(using = TypeNameMarkerSerializer.class)
            Address address,
            @Json
            @tools.jackson.databind.annotation.JsonSerialize(using = TypeNameMarkerSerializer.class)
            Phone telephone
    ) implements Entity<Integer> {}

    public record RawOwnerRow(String address, String telephone) {}

    @Test
    public void fieldsOfDifferentTypesSharingSerializerClassShouldEachUseTheSerializer() {
        // Both fields use the same serializer class but have different types, so each field needs a mapper with
        // the serializer registered for its own type.
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithSharedSerializer.class);
        var owner = OwnerWithSharedSerializer.builder()
                .firstName("Shared")
                .lastName("Serializer")
                .address(new Address("1 Way", "Town"))
                .telephone(new Phone("555"))
                .build();
        repository.insert(owner);
        var row = orm.query("SELECT address, telephone FROM owner WHERE first_name = 'Shared'")
                .getSingleResult(RawOwnerRow.class);
        assertEquals("\"marker:Address\"", row.address());
        assertEquals("\"marker:Phone\"", row.telephone());
    }

    // Sealed element type inside a container.

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithPolymorphicPersonList(
            @PK Integer id,
            @Json List<PolymorphicPerson> address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Test
    public void polymorphicJsonListShouldResolveSubtypesViaDiscriminator() {
        // The sealed interface appears as the List element type; its permitted subtypes are registered from the
        // generic type, not just the raw List type.
        var orm = of(dataSource);
        var query = orm.query("""
                SELECT id,
                       '[{"@type":"PersonA","firstName":"Jane","lastName":"Doe"},{"@type":"PersonB","firstName":"John","lastName":"Doe"}]' AS address,
                       telephone
                FROM owner WHERE id = 1""");
        var result = query.getSingleResult(OwnerWithPolymorphicPersonList.class);
        assertEquals(2, result.address().size());
        assertTrue(result.address().get(0) instanceof PersonA);
        assertTrue(result.address().get(1) instanceof PersonB);
    }

    @Test
    public void polymorphicJsonListShouldRoundTripThroughDatabase() {
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithPolymorphicPersonList.class);
        var owner = OwnerWithPolymorphicPersonList.builder()
                .address(List.of(new PersonA("Jane", "Doe"), new PersonB("John", "Doe")))
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertEquals(List.of(new PersonA("Jane", "Doe"), new PersonB("John", "Doe")), inserted.address());
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithPolymorphicAddress(
            @PK Integer id,
            @Json PolymorphicPerson address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Test
    public void polymorphicJsonFieldShouldRoundTripThroughDatabase() {
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithPolymorphicAddress.class);
        var owner = OwnerWithPolymorphicAddress.builder()
                .address(new PersonB("Jane", "Doe"))
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertEquals(new PersonB("Jane", "Doe"), inserted.address());
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithPolymorphicPersonMap(
            @PK Integer id,
            @Json Map<String, PolymorphicPerson> address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Test
    public void polymorphicJsonMapValuesShouldRoundTripThroughDatabase() {
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithPolymorphicPersonMap.class);
        var persons = Map.of(
                "primary", (PolymorphicPerson) new PersonA("Jane", "Doe"),
                "secondary", new PersonB("John", "Doe"));
        var owner = OwnerWithPolymorphicPersonMap.builder()
                .address(persons)
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertEquals(persons, inserted.address());
    }
}
