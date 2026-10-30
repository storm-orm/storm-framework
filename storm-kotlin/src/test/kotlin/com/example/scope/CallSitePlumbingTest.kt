@file:OptIn(InternalStormApi::class)

package com.example.scope

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.core.template.SqlLog.Summary
import st.orm.core.template.impl.CallSiteCapture
import st.orm.repository.exists
import st.orm.template.DEFAULT_SQL_LOG_LIMIT
import st.orm.template.IntegrationConfig
import st.orm.template.InternalStormApi
import st.orm.template.ORMTemplate
import st.orm.template.model.PetOwnerRef
import st.orm.template.recordSqlLog

/**
 * Verifies that a declared plumbing file hides the frames of its inline functions: their lambdas compile into
 * the caller's class, where a package prefix cannot see them, while the frame keeps the declaring file's name.
 * This class deliberately lives outside the framework packages, since frames inside them are infrastructure to
 * the call-site walker.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class CallSitePlumbingTest(
    @Autowired val orm: ORMTemplate,
) {

    private suspend fun <T> record(name: String, block: suspend () -> T): Summary {
        var summary: Summary? = null
        recordSqlLog(name, DEFAULT_SQL_LOG_LIMIT, true, block) { summary = it }
        return summary!!
    }

    @Test
    fun `a plumbing file entry hides the frames of its inline functions`(): Unit = runBlocking {
        CallSiteCapture.ignoreCallSites("Plumbing.kt")
        val summary = record("plumbed") {
            throughDbLayer { orm.entity(PetOwnerRef::class).select().resultList }
        }
        val callSite = summary.byStatement().first().callSite()
        callSite.shouldNotBeNull()
        // The plumbing frames step aside; the row names this test, the caller beyond the layer.
        callSite shouldStartWith "CallSitePlumbingTest.kt:"
    }

    @Test
    fun `work launched onto another dispatcher names the frame that launched it`(): Unit = runBlocking {
        CallSiteCapture.ignoreCallSites("Plumbing.kt")
        val summary = record("launched") {
            fetchAllOnDispatcher(orm)
        }
        val callSite = summary.byStatement().first().callSite()
        callSite.shouldNotBeNull()
        // The dispatcher thread's stack is plumbing end to end; without the carried launch site this row would
        // name the plumbing's innermost frame.
        callSite shouldStartWith "CallSitePlumbingTest.kt:"
    }

    @Test
    fun `a statement run inside a flow operator names the caller, not the library's inlined frame`(): Unit = runBlocking {
        val summary = record("flow") {
            flowOf(1, 2).map { orm.entity(PetOwnerRef::class).findById(it) }.toList()
        }
        val callSite = summary.byStatement().first().callSite()
        callSite.shouldNotBeNull()
        callSite shouldStartWith "CallSitePlumbingTest.kt:"
    }

    @Test
    fun `a flow a repository method maps before returning names the caller through the repository`(): Unit = runBlocking {
        val owners = orm.repository(PetOwnerRefs::class)
        val summary = record("repository flow") {
            owners.allRenamed().toList()
        }
        val callSite = summary.byStatement().first().callSite()
        callSite.shouldNotBeNull()
        callSite shouldStartWith "CallSitePlumbingTest.kt:"
        callSite shouldEndWith " -> PetOwnerRefs.allRenamed"
    }

    @Test
    fun `a flow a repository method returns as is names the repository method that built it`(): Unit = runBlocking {
        val owners = orm.repository(PetOwnerRefs::class)
        val summary = record("returned flow") {
            val flow = owners.all()
            // The flow runs here, after the repository method returned: its frame is no longer on the stack.
            flow.toList()
        }
        val callSite = summary.byStatement().first().callSite()
        callSite.shouldNotBeNull()
        callSite shouldStartWith "CallSitePlumbingTest.kt:"
        callSite shouldEndWith " -> PetOwnerRefs.all"
    }

    @Test
    fun `a statement Storm's inline function runs names the line that called it and the built-in call`(): Unit = runBlocking {
        var line = 0
        val summary = record("inline") {
            // The call follows on the next line.
            line = Throwable().stackTrace[0].lineNumber + 1
            orm.exists<PetOwnerRef>()
        }
        val callSite = summary.byStatement().first().callSite()
        callSite shouldBe "CallSitePlumbingTest.kt:$line -> exists"
    }
}
