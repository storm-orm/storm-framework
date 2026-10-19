package st.orm.template

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Entity
import st.orm.PK
import st.orm.PersistenceException
import st.orm.repository.entity

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class RecordValidationTest(
    @Autowired val orm: ORMTemplate,
) {

    data class VarField(
        @PK val id: Int = 0,
        private var invalidField: Int,
    ) : Entity<Int>

    data class PrivateField(
        @PK val id: Int = 0,
        private val invalidField: Int,
    ) : Entity<Int>

    @Test
    fun `entity with var field should throw PersistenceException`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            orm.entity(VarField::class)
        }
    }

    @Test
    fun `entity with private field should throw PersistenceException`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            orm.entity(PrivateField::class)
        }
    }
}
