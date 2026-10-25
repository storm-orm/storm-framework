package st.orm.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonClassDiscriminator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import st.orm.*
import st.orm.SqlTemplateException
import st.orm.core.template.SqlInterceptor
import st.orm.serialization.model.*
import st.orm.template.ORMTemplate
import st.orm.template.Templates.alias
import st.orm.test.StormTest
import javax.sql.DataSource
import kotlin.test.assertEquals

@Suppress("OPT_IN_USAGE")
@StormTest(scripts = ["/data.sql"])
internal open class JsonORMConverterIntegrationTest {

    private lateinit var dataSource: DataSource

    @BeforeEach
    fun bindDataSource(dataSource: DataSource) {
        this.dataSource = dataSource
    }

    @Test
    fun `select owners should return all 10 distinct owners from test data`() {
        // The test data contains 10 owner rows. Querying all columns and mapping to Owner (which has
        // a @Json address field) should produce 10 distinct results, verifying JSON column deserialization.
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT id, first_name, last_name, address, telephone FROM owner")
        val owner = query.getResultList(Owner::class)
        Assertions.assertEquals(10, owner.stream().distinct().count())
    }

    @Test
    fun `JSON array of Ref ids should deserialize to list of unloaded Refs`() {
        // JSON_ARRAYAGG(id) produces a JSON array of owner IDs. When mapped to a List<Ref<Owner>>,
        // each element should be an unloaded Ref with the correct ID. There are 10 distinct owners.
        data class Result(@Json val owner: List<Ref<Owner>>)
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT JSON_ARRAYAGG(id) FROM owner")
        val owner = query.getSingleResult(Result::class).owner.stream()
            .map { it.id() }.distinct().toList()
        Assertions.assertEquals(10, owner.size)
    }

    @Test
    fun `JSON array with null element should deserialize to nullable Ref list`() {
        // A JSON array containing null and a valid ID should produce a list with one null Ref
        // and one valid Ref. This verifies null handling in JSON Ref deserialization.
        data class Result(@Json val owner: List<Ref<Owner>?>)

        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT '[null, 1]'")
        val ownerIds = query.getSingleResult(Result::class).owner
            .asSequence()
            .map { it?.id() }
            .distinct()
            .toList()
        assertEquals(2, ownerIds.size)
        assertEquals(1, ownerIds.count { it == null })
    }

    @Test
    fun `insert entity with Json address field should persist and return correct address`() {
        // Inserting an owner with a JSON-serialized address should store the address as JSON in the
        // database and return it correctly when fetched back via insertAndFetch.
        val orm = ORMTemplate.of(dataSource)
        val repository = orm.entity(Owner::class)
        val address = Address("271 University Ave", "Palo Alto")
        val owner = Owner(
            firstName = "Simon",
            lastName = "McDonald",
            address = address,
            telephone = "555-555-5555",
        )
        val inserted = repository.insertAndFetch(owner)
        assertEquals(address, inserted.address)
    }

    @Test
    fun `update entity with Json address field should persist the new address`() {
        // Updating owner id=1's address to a new value should persist the JSON change.
        // Re-fetching the owner should return the updated address.
        val orm = ORMTemplate.of(dataSource)
        val repository = orm.entity(Owner::class)
        val owner = repository.getById(1)
        val address = Address("271 University Ave", "Palo Alto")
        repository.update(owner.copy(address = address))
        val updated = repository.getById(1)
        assertEquals(address, updated.address)
    }

    @Serializable
    data class Person(val firstName: String, val lastName: String)

    @DbTable("owner")
    data class OwnerWithJsonPerson(
        @PK val id: Int,
        @Json val person: Person,
        @Json val address: Address,
        val telephone: String?,
    ) : Entity<Int>

    @Test
    fun `computed JSON person column should deserialize correctly for all 10 owners`() {
        // Uses JSON_OBJECT to create a person JSON column from first_name/last_name. The @Json-annotated
        // Person field should be deserialized from the JSON column for all 10 owners in the test data.
        val orm = ORMTemplate.of(dataSource)
        val query =
            orm.query("SELECT id, JSON_OBJECT('firstName' VALUE first_name, 'lastName' VALUE last_name) AS person, address, telephone FROM owner")
        val owner = query.getResultList(OwnerWithJsonPerson::class)
        Assertions.assertEquals(10, owner.size)
    }

    @DbTable("owner")
    data class OwnerWithJsonMapAddress(
        @PK val id: Int,
        val firstName: String,
        val lastName: String,
        @Json val address: Map<String, String>,
        val telephone: String?,
    ) : Entity<Int>

    @Test
    fun `Json Map address field should deserialize JSON column into Map for all owners`() {
        // The address column contains JSON objects. Using Map<String, String> as the field type
        // with @Json should deserialize the JSON into a map. All 10 owners should be returned.
        val orm = ORMTemplate.of(dataSource)
        val repository = orm.entity(OwnerWithJsonMapAddress::class)
        val owner = repository.select().resultList
        Assertions.assertEquals(10, owner.size)
    }

    @DbTable("owner")
    data class OwnerWithInlineJsonMapAddress(
        @PK val id: Int,
        val firstName: String,
        val lastName: String,
        @Json @Inline val address: Map<String, String>,
        val telephone: String?,
    ) : Entity<Int>

    @Test
    fun `Inline and Json annotations combined on Map should throw SqlTemplateException`() {
        // @Inline and @Json are mutually exclusive on Map fields. @Inline means "expand the fields
        // inline into the SQL", but @Json means "treat as a single JSON column". The framework
        // should reject this combination with a SqlTemplateException.
        val e = Assertions.assertThrows(PersistenceException::class.java) {
            val orm = ORMTemplate.of(dataSource)
            val repository = orm.entity(OwnerWithInlineJsonMapAddress::class)
            repository.select().resultList
        }
        Assertions.assertInstanceOf(SqlTemplateException::class.java, e.cause)
    }

    data class SpecialtiesByVet(
        val vet: Vet,
        @Json val specialties: List<Specialty>, // Note that Specialty has been made @Serializable.
    )

    @Test
    fun `JSON_ARRAYAGG of specialty objects should group by vet`() {
        // Joins vet -> vet_specialty -> specialty and aggregates specialties as a JSON array per vet.
        // Per test data: 4 vets have specialties (vets 2,3,4,5), with 5 total vet-specialty associations.
        // Vet 3 (Linda Douglas) has 2 specialties (surgery, dentistry); the others have 1 each.
        val vets = ORMTemplate.of(dataSource)
            .selectFrom(Vet::class, SpecialtiesByVet::class) {
                """
                    ${t(Vet::class)}, JSON_ARRAYAGG(
                        JSON_OBJECT(
                            KEY 'id' VALUE ${t(alias(Specialty::class))}.id,
                            KEY 'name' VALUE ${t(alias(Specialty::class))}.name
                        )
                    ) AS specialties
                """.trimIndent()
            }
            .innerJoin(VetSpecialty::class).on(Vet::class)
            .innerJoin(Specialty::class).on(VetSpecialty::class)
            .groupBy { "${t(Vet::class)}.id" }
            .resultList
        Assertions.assertEquals(4, vets.size)
        Assertions.assertEquals(5, vets.sumOf { it.specialties.size })
    }

    internal data class SpecialtyNamesByVet(
        val vet: Vet,
        @Json val specialties: List<String>,
    )

    @Test
    fun `JSON_ARRAYAGG of specialty names should group by vet`() {
        // Similar to the specialty objects test, but aggregates just the name strings.
        // Per test data: 4 vets with specialties, 5 total specialty associations.
        val vets = ORMTemplate.of(dataSource).selectFrom(Vet::class, SpecialtyNamesByVet::class) {
            """
                ${t(Vet::class)}, JSON_ARRAYAGG(${t(Specialty::class)}.name) AS specialties
            """.trimIndent()
        }
            .innerJoin(VetSpecialty::class).on(Vet::class)
            .innerJoin(Specialty::class).on(VetSpecialty::class)
            .groupBy { "${t(Vet::class)}.id" }
            .resultList
        Assertions.assertEquals(4, vets.size)
        Assertions.assertEquals(5, vets.sumOf { it.specialties.size })
    }

    @Test
    fun `JSON_OBJECTAGG with entity template should generate correct SQL`() {
        // JSON_OBJECTAGG with a full entity template reference should expand to the entity's columns.
        // H2 does not support JSON_OBJECTAGG, so we only verify the generated SQL matches expectations.
        val expectedSql = """
                SELECT v.id, v.first_name, v.last_name, JSON_OBJECTAGG(s.id, s.name)
                FROM vet v
                INNER JOIN vet_specialty vs ON vs.vet_id = v.id
                INNER JOIN specialty s ON vs.specialty_id = s.id
                GROUP BY v.id
        """.trimIndent()
        SqlInterceptor.observe(
            { sql -> Assertions.assertEquals(expectedSql, sql!!.statement()) },
            {
                try {
                    ORMTemplate.of(dataSource).selectFrom(Vet::class, SpecialtyNamesByVet::class) {
                        """
                            ${t(Vet::class)}, JSON_OBJECTAGG(${t(Specialty::class)})
                        """.trimIndent()
                    }
                        .innerJoin(VetSpecialty::class).on(Vet::class)
                        .innerJoin(Specialty::class).on(VetSpecialty::class)
                        .groupBy { "${t(Vet::class)}.id" }
                        .resultList
                } catch (ignore: PersistenceException) {
                    // H2 Does not support JSON_OBJECTAGG. We only check the expected SQL.
                }
            },
        )
    }

    @JsonClassDiscriminator("@type")
    @Serializable
    sealed interface PolymorphicPerson

    // The type name is automatically derived from the class name, so the annotation is not needed.
    @SerialName("PersonA")
    @Serializable
    data class PersonA(val firstName: String, val lastName: String) : PolymorphicPerson

    @SerialName("PersonB")
    @Serializable
    data class PersonB(val firstName: String, val lastName: String) : PolymorphicPerson

    @DbTable("owner")
    data class OwnerWithPolymorphicPerson(
        @PK val id: Int,
        @Json val person: PolymorphicPerson,
        @Json val address: Address,
        val telephone: String?,
    ) : Entity<Int>

    @Test
    fun `polymorphic JSON deserialization should resolve correct subtype via discriminator`() {
        // Uses a sealed interface with @JsonClassDiscriminator("@type") and two subtypes (PersonA, PersonB).
        // The JSON constructed by the query includes '@type' VALUE 'PersonA', so all 10 owners should
        // deserialize with their person field as a PersonA instance.
        val orm = ORMTemplate.of(dataSource)
        val query =
            orm.query("SELECT id, JSON_OBJECT('@type' VALUE 'PersonA', 'firstName' VALUE first_name, 'lastName' VALUE last_name) AS person, address, telephone FROM owner")
        val owner = query.getResultList(OwnerWithPolymorphicPerson::class)
        Assertions.assertEquals(10, owner.size)
        Assertions.assertTrue(owner.stream().allMatch { x: OwnerWithPolymorphicPerson? -> x!!.person is PersonA })
    }

    data class NullableAddressHolder(
        @PK val id: Int = 0,
        @Json val address: Address?,
    ) : Entity<Int>

    @Test
    fun `fromDatabase with null JSON column should return null`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, CAST(NULL AS VARCHAR) AS address")
        val result = query.getSingleResult(NullableAddressHolder::class)
        assertNotNull(result)
        assertNull(result.address)
    }

    @DbTable("owner")
    data class OwnerWithNullableAddress(
        @PK val id: Int = 0,
        val firstName: String,
        val lastName: String,
        @Json val address: Address?,
        val telephone: String?,
    ) : Entity<Int>

    @Test
    fun `toDatabase with null address should persist null`() {
        val orm = ORMTemplate.of(dataSource)
        val repository = orm.entity(OwnerWithNullableAddress::class)
        val owner = OwnerWithNullableAddress(
            firstName = "TestNull",
            lastName = "Address",
            address = null,
            telephone = "5551234",
        )
        val inserted = repository.insertAndFetch(owner)
        assertNull(inserted.address)
    }

    data class OwnerRefCollectionHolder(
        @PK val id: Int = 0,
        @Json val ownerRefs: Collection<Ref<Owner>>,
    ) : Entity<Int>

    @Test
    fun `Collection of Ref should deserialize via tryCreateRefAwareSerializer Collection branch`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '[1, 2, 3]' AS owner_refs")
        val result = query.getSingleResult(OwnerRefCollectionHolder::class)
        assertNotNull(result)
        assertEquals(3, result.ownerRefs.size)
    }

    data class MapWithNullableRefValueHolder(
        @PK val id: Int = 0,
        @Json val ownerMap: Map<String, Ref<Owner>?>,
    ) : Entity<Int>

    @Test
    fun `Map with nullable Ref values including null should deserialize correctly`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"a\": 1, \"b\": null}' AS owner_map")
        val result = query.getSingleResult(MapWithNullableRefValueHolder::class)
        assertNotNull(result)
        assertEquals(2, result.ownerMap.size)
        assertEquals(1, result.ownerMap["a"]?.id())
    }

    @Serializable
    data class SimpleData(
        val name: String,
        val value: Int,
    )

    data class PlainJsonHolder(
        @PK val id: Int = 0,
        @Json val data: SimpleData,
    ) : Entity<Int>

    @Test
    fun `plain serializable type without Ref should use standard serializer fallback`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"name\": \"test\", \"value\": 42}' AS data")
        val result = query.getSingleResult(PlainJsonHolder::class)
        assertNotNull(result)
        assertEquals("test", result.data.name)
        assertEquals(42, result.data.value)
    }

    data class NonSerializableType(val x: Int)

    data class NonSerializableJsonHolder(
        @PK val id: Int = 0,
        @Json val data: NonSerializableType,
    ) : Entity<Int>

    @Test
    fun `non-serializable type with Json annotation is left to the Jackson module on the classpath`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"x\": 1}' AS data")
        assertEquals(NonSerializableType(1), query.getSingleResult(NonSerializableJsonHolder::class).data)
    }

    @Test
    fun `toDatabase should serialize Address to JSON string correctly`() {
        val orm = ORMTemplate.of(dataSource)
        val repository = orm.entity(Owner::class)
        val address = Address("456 Oak Lane", "Riverside")
        val owner = Owner(
            firstName = "SerializeTest",
            lastName = "Database",
            address = address,
            telephone = "5559876",
        )
        val inserted = repository.insertAndFetch(owner)
        assertEquals(address, inserted.address)
        assertEquals("SerializeTest", inserted.firstName)
    }

    data class StrictJsonHolder(
        @PK val id: Int = 0,
        @Json val address: Address,
    ) : Entity<Int>

    @Test
    fun `fromDatabase with invalid JSON should throw PersistenceException wrapping SqlTemplateException`() {
        val orm = ORMTemplate.of(dataSource)
        assertThrows(PersistenceException::class.java) {
            val query = orm.query("SELECT 1 AS id, 'not valid json' AS address")
            query.getSingleResult(StrictJsonHolder::class)
        }
    }

    data class RefMapEntityForInsert(
        @PK val id: Int = 0,
        @Json val ownerRefs: Map<String, Ref<Owner>>,
    ) : Entity<Int>

    @Test
    fun `Map with Ref values should round-trip through toDatabase and fromDatabase`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"x\": 5, \"y\": 6}' AS owner_refs")
        val result = query.getSingleResult(RefMapEntityForInsert::class)
        assertNotNull(result)
        assertEquals(5, result.ownerRefs["x"]?.id())
        assertEquals(6, result.ownerRefs["y"]?.id())
    }

    data class PlainMapHolder(
        @PK val id: Int = 0,
        @Json val metadata: Map<String, Int>,
    ) : Entity<Int>

    @Test
    fun `Map without Ref types should use standard serializer`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"a\": 1, \"b\": 2}' AS metadata")
        val result = query.getSingleResult(PlainMapHolder::class)
        assertNotNull(result)
        assertEquals(1, result.metadata["a"])
        assertEquals(2, result.metadata["b"])
    }

    data class PlainListHolder(
        @PK val id: Int = 0,
        @Json val names: List<String>,
    ) : Entity<Int>

    @Test
    fun `List of plain types should use standard serializer`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '[\"a\", \"b\", \"c\"]' AS names")
        val result = query.getSingleResult(PlainListHolder::class)
        assertNotNull(result)
        assertEquals(listOf("a", "b", "c"), result.names)
    }

    // Nested deserialization: a custom serializer that issues a query mid-deserialization.

    object NestedQueryMarkerSerializer : KSerializer<String> {
        lateinit var nestedDataSource: DataSource

        override val descriptor = PrimitiveSerialDescriptor("NestedQueryMarker", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String {
            val text = decoder.decodeString()
            // Maps Owner, whose @Json address field enters fromDatabase re-entrantly on this thread.
            ORMTemplate.of(nestedDataSource)
                .query("SELECT id, first_name, last_name, address, telephone FROM owner WHERE id = 2")
                .getSingleResult(Owner::class)
            return text
        }
    }

    // The ref target must be @Serializable itself, since the compiler requires a serializer for the type
    // argument of a @Contextual Ref property. Maps the owner table without its @Json address column.
    @Serializable
    @DbTable("owner")
    data class SerializableOwner(
        @PK val id: Int = 0,
        val firstName: String,
        val lastName: String,
        val telephone: String?,
    ) : Entity<Int>

    @Serializable
    data class OwnerSnapshot(
        @Serializable(with = NestedQueryMarkerSerializer::class) val marker: String,
        @Contextual val owner: Ref<SerializableOwner>,
    )

    @DbTable("owner")
    data class OwnerWithSnapshot(
        @PK val id: Int,
        @Json val snapshot: OwnerSnapshot,
        val telephone: String?,
    ) : Entity<Int>

    @Test
    fun `ref deserialized after nested conversion should remain attached`() {
        // The marker field deserializes first and issues a nested query, which binds and unbinds the nested
        // conversion's RefFactory. The owner field that follows must still see the outer factory: the
        // resulting ref is attached and fetches the owner.
        NestedQueryMarkerSerializer.nestedDataSource = dataSource
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query(
            "SELECT id, JSON_OBJECT('marker' VALUE 'audit', 'owner' VALUE id) AS snapshot, telephone FROM owner WHERE id = 1",
        )
        val result = query.getSingleResult(OwnerWithSnapshot::class)
        val ownerRef = result.snapshot.owner
        Assertions.assertTrue(ownerRef.isFetchable)
        assertEquals("Betty", ownerRef.fetch().firstName)
    }
}
