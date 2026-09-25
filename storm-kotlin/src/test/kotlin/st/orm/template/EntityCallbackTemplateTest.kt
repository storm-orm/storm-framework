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
package st.orm.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.EntityCallback
import st.orm.PersistenceException
import st.orm.template.model.City

/**
 * An entity callback reaches the template of the operation that fired it through [ORMTemplate.current], rather than
 * capturing one of its own.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class EntityCallbackTemplateTest(
    @Autowired val orm: ORMTemplate,
) {

    /** Writes an audit row through the template that fired it, capturing nothing. */
    private class AuditingCallback : EntityCallback<City> {
        override fun afterInsert(entities: List<City>) {
            ORMTemplate.current().writeSet().insert(entities.map { City(name = "${it.name} (audit)") })
        }
    }

    private fun countCities(name: String) = orm.entity(City::class).findAll().count { it.name == name }

    @Test
    fun `callback writes through the template that fired it`() {
        orm.withEntityCallback(AuditingCallback()).entity(City::class).insert(City(name = "Kotlin current"))
        countCities("Kotlin current (audit)") shouldBe 1
    }

    @Test
    fun `work through the current template fires no callbacks`() {
        orm.withEntityCallback(AuditingCallback()).entity(City::class).insert(City(name = "Kotlin guarded"))
        countCities("Kotlin guarded (audit)") shouldBe 1
        countCities("Kotlin guarded (audit) (audit)") shouldBe 0
    }

    @Test
    fun `current is refused outside a callback`() {
        val thrown = shouldThrow<PersistenceException> { ORMTemplate.current() }
        thrown.message!! shouldContain "entity callback"
    }
}
