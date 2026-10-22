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
package st.orm.jackson

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import st.orm.DbTable
import st.orm.Entity
import st.orm.Json
import st.orm.PK
import st.orm.core.spi.Providers
import st.orm.core.template.ORMTemplate.of
import st.orm.core.template.impl.BindHint
import st.orm.test.StormTest
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import javax.sql.DataSource

/**
 * Runs the Jackson converter in the configuration every Kotlin application has: storm-kotlin on the class path,
 * so its reflection provider builds the field metadata. Covers the language seams the Java suite cannot reach:
 * annotations placed on Kotlin constructor properties, Kotlin sealed hierarchies in the sealed-type walk, and
 * Java sealed hierarchies enumerated through Kotlin reflection.
 */
@StormTest(scripts = ["/data.sql"])
open class JsonORMConverterKotlinInteropTest {

    private lateinit var dataSource: DataSource

    @BeforeEach
    fun bindDataSource(dataSource: DataSource) {
        this.dataSource = dataSource
    }

    data class RawOwnerRow(val address: String?, val telephone: String?)

    @Test
    fun `kotlin reflection provider is active`() {
        // The interop premise: with storm-kotlin on the class path, its provider outranks the default one.
        assertEquals("st.orm.template.impl.ORMReflectionImpl", Providers.getORMReflection().javaClass.name)
    }

    // Custom serializer/deserializer annotations on a Kotlin constructor property.

    data class KotlinAddress(val address: String, val city: String)

    class PipeAddressSerializer : ValueSerializer<KotlinAddress>() {
        override fun serialize(value: KotlinAddress, gen: JsonGenerator, context: SerializationContext) {
            gen.writeString("${value.address} | ${value.city}")
        }
    }

    class PipeAddressDeserializer : ValueDeserializer<KotlinAddress>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): KotlinAddress {
            val parts = parser.text.split(" | ")
            return KotlinAddress(parts[0], parts[1])
        }
    }

    @DbTable("owner")
    data class OwnerWithCustomSerde(
        @PK val id: Int = 0,
        @Json
        @JsonSerialize(using = PipeAddressSerializer::class)
        @JsonDeserialize(using = PipeAddressDeserializer::class)
        val address: KotlinAddress,
        val telephone: String? = null,
    ) : Entity<Int>

    @Test
    fun `custom serde on constructor property should be honored`() {
        // The annotations land on the constructor parameter, the default Kotlin use-site target for them.
        val orm = of(dataSource)
        val repository = orm.entity(OwnerWithCustomSerde::class.java)
        val inserted = repository.insertAndFetch(OwnerWithCustomSerde(address = KotlinAddress("1 Way", "Town")))
        assertEquals(KotlinAddress("1 Way", "Town"), inserted.address)
        // The raw column value proves the serializer engaged; a bypassed serializer/deserializer pair would
        // round trip plain JSON and pass the assertion above.
        val row = orm.query("SELECT address, telephone FROM owner WHERE id = ${inserted.id}")
            .getSingleResult(RawOwnerRow::class.java)
        assertEquals("\"1 Way | Town\"", row.address)
    }

    // One serializer class shared by two fields of different Kotlin types.

    data class KotlinPhone(val number: String)

    class TypeNameMarkerSerializer : ValueSerializer<Any>() {
        override fun serialize(value: Any, gen: JsonGenerator, context: SerializationContext) {
            gen.writeString("marker:${value.javaClass.simpleName}")
        }
    }

    @DbTable("owner")
    data class OwnerWithSharedSerializer(
        @PK val id: Int = 0,
        @Json @JsonSerialize(using = TypeNameMarkerSerializer::class) val address: KotlinAddress,
        @Json @JsonSerialize(using = TypeNameMarkerSerializer::class) val telephone: KotlinPhone,
    ) : Entity<Int>

    @Test
    fun `fields of different types sharing serializer class should each use the serializer`() {
        val orm = of(dataSource)
        val repository = orm.entity(OwnerWithSharedSerializer::class.java)
        repository.insert(OwnerWithSharedSerializer(address = KotlinAddress("1 Way", "Town"), telephone = KotlinPhone("555")))
        val row = orm.query("SELECT address, telephone FROM owner WHERE address LIKE '%marker%'")
            .getSingleResult(RawOwnerRow::class.java)
        assertEquals("\"marker:KotlinAddress\"", row.address)
        assertEquals("\"marker:KotlinPhone\"", row.telephone)
    }

    // Kotlin sealed hierarchies, top-level and inside containers.

    @JsonTypeInfo(use = NAME)
    sealed interface KotlinPerson

    data class KotlinPersonA(val firstName: String, val lastName: String) : KotlinPerson

    data class KotlinPersonB(val firstName: String, val lastName: String) : KotlinPerson

    @DbTable("owner")
    data class OwnerWithSealedAddress(
        @PK val id: Int = 0,
        @Json val address: KotlinPerson,
        val telephone: String? = null,
    ) : Entity<Int>

    @Test
    fun `kotlin sealed field should round trip through database`() {
        val orm = of(dataSource)
        val repository = orm.entity(OwnerWithSealedAddress::class.java)
        val inserted = repository.insertAndFetch(OwnerWithSealedAddress(address = KotlinPersonB("Jane", "Doe")))
        assertEquals(KotlinPersonB("Jane", "Doe"), inserted.address)
    }

    @Test
    fun `kotlin sealed field should resolve subtype via discriminator`() {
        val orm = of(dataSource)
        val result = orm.query(
            """
            SELECT id,
                   '{"@type":"KotlinPersonA","firstName":"Jane","lastName":"Doe"}' AS address,
                   telephone
            FROM owner WHERE id = 1
            """.trimIndent(),
        ).getSingleResult(OwnerWithSealedAddress::class.java)
        assertTrue(result.address is KotlinPersonA)
    }

    @DbTable("owner")
    data class OwnerWithSealedList(
        @PK val id: Int = 0,
        @Json val address: List<KotlinPerson>,
        val telephone: String? = null,
    ) : Entity<Int>

    @Test
    fun `kotlin sealed list field should round trip through database`() {
        // The sealed interface appears as the List element type; the walk registers its subtypes from the
        // generic type the Kotlin reflection provider reports.
        val orm = of(dataSource)
        val repository = orm.entity(OwnerWithSealedList::class.java)
        val persons = listOf(KotlinPersonA("Jane", "Doe"), KotlinPersonB("John", "Doe"))
        val inserted = repository.insertAndFetch(OwnerWithSealedList(address = persons))
        assertEquals(persons, inserted.address)
    }

    // Java sealed hierarchies enumerated through Kotlin reflection.

    @Test
    fun `java sealed types enumerate through kotlin reflection`() {
        // BindHint is a Java sealed interface from storm-core; the Kotlin provider resolves its permitted
        // subclasses through KClass.sealedSubclasses, which must match Java reflection.
        val permittedSubclasses = Providers.getORMReflection().getPermittedSubclasses(BindHint::class.java)
        assertEquals(BindHint::class.java.permittedSubclasses.toSet(), permittedSubclasses.toSet())
        assertTrue(permittedSubclasses.isNotEmpty())
    }
}
