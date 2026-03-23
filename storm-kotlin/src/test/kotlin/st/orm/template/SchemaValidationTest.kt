package st.orm.template

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.DbTable
import st.orm.Entity
import st.orm.PK
import st.orm.PersistenceException
import st.orm.template.model.*
import javax.sql.DataSource

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class SchemaValidationTest(
    @Autowired val orm: ORMTemplate,
    @Autowired val dataSource: DataSource,
) {

    // validateSchema(types) tests with valid types

    @Test
    fun `validateSchema with City should return no errors`() {
        val errors = orm.validateSchema(City::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchema with Owner should return no errors`() {
        val errors = orm.validateSchema(Owner::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchema with Pet should return no errors`() {
        val errors = orm.validateSchema(Pet::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchema with Vet should return no errors`() {
        val errors = orm.validateSchema(Vet::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchema with PetType should return no errors`() {
        val errors = orm.validateSchema(PetType::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchema with Visit should return no errors`() {
        val errors = orm.validateSchema(Visit::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchema with multiple valid types should return no errors`() {
        val errors = orm.validateSchema(
            City::class,
            Owner::class,
            Pet::class,
            PetType::class,
            Vet::class,
            Visit::class,
        )
        errors.shouldBeEmpty()
    }

    // validateSchema(types) tests with invalid types

    // Entity mapped to a table that does not exist in the database.
    @DbTable("nonexistent_table")
    data class NonExistentEntity(
        @PK val id: Int = 0,
        val name: String,
    ) : Entity<Int>

    @Test
    fun `validateSchema with nonexistent table entity should return errors`() {
        val errors = orm.validateSchema(NonExistentEntity::class)
        errors.shouldNotBeEmpty()
    }

    // Entity mapped to a table that exists but with mismatched columns.
    @DbTable("city")
    data class CityMismatch(
        @PK val id: Int = 0,
        val name: String,
        val nonexistentColumn: String,
    ) : Entity<Int>

    @Test
    fun `validateSchema with mismatched columns should return errors`() {
        val errors = orm.validateSchema(CityMismatch::class)
        errors.shouldNotBeEmpty()
    }

    // validateSchemaOrThrow(types) tests

    @Test
    fun `validateSchemaOrThrow with valid types should not throw`() {
        // Should complete without exception for valid entity types.
        orm.validateSchemaOrThrow(City::class, Vet::class)
    }

    @Test
    fun `validateSchemaOrThrow with invalid type should throw PersistenceException`() {
        assertThrows<PersistenceException> {
            orm.validateSchemaOrThrow(NonExistentEntity::class)
        }
    }

    @Test
    fun `validateSchemaOrThrow with mixed valid and invalid types should throw`() {
        assertThrows<PersistenceException> {
            orm.validateSchemaOrThrow(City::class, NonExistentEntity::class)
        }
    }

    // validateSchema with no args (zero varargs)

    @Test
    fun `validateSchema on connection-backed template should throw PersistenceException`() {
        // Templates created from a raw Connection do not support schema validation.
        dataSource.connection.use { connection ->
            val connectionOrm = ORMTemplate.of(connection)
            assertThrows<PersistenceException> {
                connectionOrm.validateSchema()
            }
        }
    }

    @Test
    fun `validateSchemaOrThrow on connection-backed template should throw PersistenceException`() {
        dataSource.connection.use { connection ->
            val connectionOrm = ORMTemplate.of(connection)
            assertThrows<PersistenceException> {
                connectionOrm.validateSchemaOrThrow()
            }
        }
    }

    // OwnerView (projection) validation

    @Test
    fun `validateSchema with OwnerView projection should return no errors`() {
        val errors = orm.validateSchema(OwnerView::class)
        errors.shouldBeEmpty()
    }

    @Test
    fun `validateSchemaOrThrow with OwnerView projection should not throw`() {
        orm.validateSchemaOrThrow(OwnerView::class)
    }
}
