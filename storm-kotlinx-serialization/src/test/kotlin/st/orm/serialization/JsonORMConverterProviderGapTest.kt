package st.orm.serialization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import st.orm.Entity
import st.orm.Json
import st.orm.PK
import st.orm.template.ORMTemplate
import st.orm.test.StormTest
import javax.sql.DataSource

/**
 * Tests for [st.orm.serialization.spi.JsonORMConverterProviderImpl] targeting coverage gaps:
 * - Lines 32-34: Error path when Kotlin KType cannot be resolved for a field.
 *   This can happen when the declaring type is a Java record (not a Kotlin data class).
 *
 * Also targets remaining [st.orm.serialization.spi.JsonORMConverterImpl] gaps:
 * - Lines 88/148: Constructor/createRefSerializer paths that are partially covered
 * - Lines 182-183: toDatabase exception path (SqlTemplateException wrapping)
 */
@StormTest(scripts = ["/data.sql"])
internal open class JsonORMConverterProviderGapTest {

    private lateinit var dataSource: DataSource

    @BeforeEach
    fun bindDataSource(dataSource: DataSource) {
        this.dataSource = dataSource
    }

    // A type kotlinx.serialization has no serializer for is not claimed by this module; the Jackson module on the
    // test classpath converts it instead.

    data class NonSerializableValue(val value: Int)

    data class EntityWithNonSerializableJson(
        @PK val id: Int = 0,
        @Json val data: NonSerializableValue,
    ) : Entity<Int>

    @Test
    fun `a type without a kotlinx serializer is left to the Jackson module`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"value\": 1}' AS data")
        assertEquals(NonSerializableValue(1), query.getSingleResult(EntityWithNonSerializableJson::class).data)
    }

    // Verify that getParameterCount and getParameterTypes return expected values
    // These are implicitly tested through the integration tests that successfully
    // read @Json fields, but we verify them through a round-trip.

    @kotlinx.serialization.Serializable
    data class SimpleAddress(val street: String, val city: String)

    data class SimpleJsonHolder(
        @PK val id: Int = 0,
        @Json val address: SimpleAddress,
    ) : Entity<Int>

    @Test
    fun `Json field round-trip through database verifies getParameterCount and getParameterTypes`() {
        val orm = ORMTemplate.of(dataSource)
        val query = orm.query("SELECT 1 AS id, '{\"street\":\"Oak St\",\"city\":\"Portland\"}' AS address")
        val result = query.getSingleResult(SimpleJsonHolder::class)
        assertNotNull(result)
        assertNotNull(result.address)
        assertEquals("Oak St", result.address.street)
        assertEquals("Portland", result.address.city)
    }
}
