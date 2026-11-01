package com.example.scope

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.template.IntegrationConfig
import st.orm.template.ORMTemplate
import java.lang.reflect.Proxy

/**
 * A repository is an instance of the class Storm writes for its interface on Java 24 and up, and a proxy before: both
 * serve the built-in calls and the interface's own methods with a body. The interface lives outside the framework
 * packages, as an application's does.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class GeneratedRepositoryTest(
    @Autowired val orm: ORMTemplate,
) {

    @Test
    fun `a repository is the class written for its interface from Java 24 and a proxy before`() {
        val owners = orm.repository(PetOwnerRefs::class)
        if (Runtime.version().feature() >= 24) {
            owners.javaClass.name shouldBe PetOwnerRefs::class.java.name + "\$\$Storm"
            owners.toString() shouldBe "RepositoryProxy(PetOwnerRefs)"
        } else {
            Proxy.isProxyClass(owners.javaClass) shouldBe true
        }
    }

    @Test
    fun `a repository serves the built-in calls and the interface's own methods`(): Unit = runBlocking {
        val owners = orm.repository(PetOwnerRefs::class)
        owners.findById(1).shouldNotBeNull()
        owners.all().toList().shouldNotBeEmpty()
        owners.allRenamed().toList().all { it.name == it.name.uppercase() } shouldBe true
        owners.orm shouldBe orm
    }
}
