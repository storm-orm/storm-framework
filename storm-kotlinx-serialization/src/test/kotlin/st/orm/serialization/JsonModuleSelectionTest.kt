package st.orm.serialization

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import st.orm.DbTable
import st.orm.Entity
import st.orm.Json
import st.orm.PK
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With storm-jackson3 beside storm-kotlinx-serialization on the classpath, kotlinx.serialization converts the JSON
 * fields whose type it has a serializer for and Jackson converts the rest. Each library honours its own renaming
 * annotation, so the stored JSON tells which one wrote the column.
 */
@StormTest(scripts = ["/data.sql"])
internal open class JsonModuleSelectionTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun bindDataSource(dataSource: DataSource) {
        this.dataSource = dataSource
    }

    @Serializable
    data class SerializablePreferences(@SerialName("favourite_clinic") val clinic: String)

    data class JacksonPreferences(@JsonProperty("favourite_clinic") val clinic: String)

    @DbTable("owner")
    data class OwnerWithSerializablePreferences(
        @PK val id: Int = 0,
        val firstName: String,
        val lastName: String,
        @Json val address: SerializablePreferences,
        val telephone: String,
    ) : Entity<Int>

    @DbTable("owner")
    data class OwnerWithJacksonPreferences(
        @PK val id: Int = 0,
        val firstName: String,
        val lastName: String,
        @Json val address: JacksonPreferences,
        val telephone: String,
    ) : Entity<Int>

    private fun storedAddress(id: Int): String = ORMTemplate.of(dataSource).query("SELECT address FROM owner WHERE id = $id").getSingleResult(String::class)

    @Test
    fun `a serializable type is converted by kotlinx serialization`() {
        val owners = ORMTemplate.of(dataSource).entity(OwnerWithSerializablePreferences::class)
        val owner = owners.insertAndFetch(
            OwnerWithSerializablePreferences(
                firstName = "Pat",
                lastName = "Owner",
                address = SerializablePreferences("Downtown"),
                telephone = "5550001",
            ),
        )
        assertTrue("favourite_clinic" in storedAddress(owner.id), "kotlinx.serialization honours @SerialName")
        assertEquals(SerializablePreferences("Downtown"), owners.getById(owner.id).address)
    }

    @Test
    fun `a type kotlinx serialization has no serializer for is converted by Jackson`() {
        val owners = ORMTemplate.of(dataSource).entity(OwnerWithJacksonPreferences::class)
        val owner = owners.insertAndFetch(
            OwnerWithJacksonPreferences(
                firstName = "Sam",
                lastName = "Owner",
                address = JacksonPreferences("Riverside"),
                telephone = "5550002",
            ),
        )
        assertTrue("favourite_clinic" in storedAddress(owner.id), "Jackson honours @JsonProperty")
        assertEquals(JacksonPreferences("Riverside"), owners.getById(owner.id).address)
    }
}
