package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.EntityCallback
import st.orm.template.model.City
import st.orm.template.model.Owner
import st.orm.template.model.OwnerView
import st.orm.template.model.Visit
import javax.sql.DataSource

/**
 * Tests for [ORMTemplate] factory methods and extension properties, and
 * [st.orm.template.impl.ORMTemplateImpl] methods such as withEntityCallback,
 * validateSchema, entity, and projection.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class ORMTemplateFactoryTest(
    @Autowired val orm: ORMTemplate,
    @Autowired val dataSource: DataSource,
) {

    // Factory: ORMTemplate.of(DataSource)

    @Test
    fun `ORMTemplate of DataSource should return functional template`() {
        val template = ORMTemplate.of(dataSource)
        template.shouldNotBeNull()
        template.shouldBeInstanceOf<ORMTemplate>()
        val cities = template.entity(City::class).select().resultList
        cities shouldHaveSize 6
    }

    // Factory: ORMTemplate.of(Connection)

    @Test
    fun `ORMTemplate of Connection should return functional template`() {
        val connection = dataSource.connection
        try {
            val template = ORMTemplate.of(connection)
            template.shouldNotBeNull()
            template.shouldBeInstanceOf<ORMTemplate>()
            val cities = template.entity(City::class).select().resultList
            cities shouldHaveSize 6
        } finally {
            connection.close()
        }
    }

    // Extension properties: DataSource.orm, Connection.orm

    @Test
    fun `DataSource orm extension should return functional template`() {
        val template = dataSource.orm
        template.shouldNotBeNull()
        val count = template.entity(City::class).select().resultCount
        count shouldBe 6L
    }

    @Test
    fun `Connection orm extension should return functional template`() {
        val connection = dataSource.connection
        try {
            val template = connection.orm
            template.shouldNotBeNull()
            val count = template.entity(City::class).select().resultCount
            count shouldBe 6L
        } finally {
            connection.close()
        }
    }

    // Extension functions with decorator

    @Test
    fun `DataSource orm with decorator should apply decorator`() {
        val template = dataSource.orm { it }
        template.shouldNotBeNull()
        val cities = template.entity(City::class).select().resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `Connection orm with decorator should apply decorator`() {
        val connection = dataSource.connection
        try {
            val template = connection.orm { it }
            template.shouldNotBeNull()
            val cities = template.entity(City::class).select().resultList
            cities shouldHaveSize 6
        } finally {
            connection.close()
        }
    }

    // withEntityCallback

    @Test
    fun `withEntityCallback should return new template with callback`() {
        val callback = object : EntityCallback<City> {
            override fun beforeInsert(entity: City): City = entity
        }
        val templateWithCallback = orm.withEntityCallback(callback)
        templateWithCallback.shouldNotBeNull()
        templateWithCallback.shouldBeInstanceOf<ORMTemplate>()

        // The callback template should still be functional
        val cities = templateWithCallback.entity(City::class).select().resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `withEntityCallbacks should return new template with multiple callbacks`() {
        val callback1 = object : EntityCallback<City> {}
        val callback2 = object : EntityCallback<Owner> {}
        val templateWithCallbacks = orm.withEntityCallbacks(listOf(callback1, callback2))
        templateWithCallbacks.shouldNotBeNull()
        templateWithCallbacks.shouldBeInstanceOf<ORMTemplate>()

        // The template should still be functional
        val cities = templateWithCallbacks.entity(City::class).select().resultList
        cities shouldHaveSize 6
    }

    // entity() and projection()

    @Test
    fun `entity should return typed EntityRepository`() {
        val repo = orm.entity(City::class)
        repo.shouldNotBeNull()
        repo.count() shouldBe 6
    }

    @Test
    fun `entity should return typed EntityRepository for Owner`() {
        val repo = orm.entity(Owner::class)
        repo.shouldNotBeNull()
        repo.count() shouldBe 10
    }

    @Test
    fun `projection should return typed ProjectionRepository`() {
        val repo = orm.projection(OwnerView::class)
        repo.shouldNotBeNull()
        repo.count() shouldBe 10
    }

    @Test
    fun `projection should return results matching view`() {
        val repo = orm.projection(OwnerView::class)
        val owners = repo.findAll()
        owners shouldHaveSize 10
        owners[0].firstName shouldBe "Betty"
    }

    // validateSchema

    @Test
    fun `validateSchema should return list of errors or empty`() {
        val errors = orm.validateSchema(City::class)
        // City should validate fine against H2
        errors shouldHaveSize 0
    }

    // query method

    @Test
    fun `query with raw SQL should return results`() {
        val result = orm.query("SELECT COUNT(*) FROM city").singleResult
        result shouldBe arrayOf(6L)
    }

    @Test
    fun `selectFrom should return builder for entity`() {
        val cities = orm.selectFrom(City::class).resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `deleteFrom should return builder for entity`() {
        val deleted = orm.deleteFrom(Visit::class).unsafe().executeUpdate()
        deleted shouldBe 14
    }
}
