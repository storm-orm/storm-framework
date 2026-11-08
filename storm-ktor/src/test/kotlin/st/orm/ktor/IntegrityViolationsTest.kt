package st.orm.ktor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import st.orm.Entity
import st.orm.IntegrityViolation
import st.orm.IntegrityViolation.Check
import st.orm.IntegrityViolation.ForeignKey
import st.orm.IntegrityViolation.Length
import st.orm.IntegrityViolation.NotNull
import st.orm.IntegrityViolation.NumericRange
import st.orm.IntegrityViolation.Referenced
import st.orm.IntegrityViolation.Unique
import st.orm.IntegrityViolationException
import st.orm.Metamodel
import st.orm.PK
import java.sql.SQLException

class IntegrityViolationsTest {

    data class Owner(@PK val id: Int = 0, val telephone: String) : Entity<Int>

    private val type = Owner::class.java
    private val fields: List<Metamodel<*, *>> = listOf(Metamodel.of<Owner, String>(type, "telephone"))
    private val table = "secret_table"
    private val constraint = "secret_uk"

    private fun reported(violation: IntegrityViolation) = IntegrityViolationException("Refused", listOf(violation), SQLException("refused", "23000"))

    private fun check(
        failure: IntegrityViolationException,
        expectedStatus: HttpStatusCode,
        expectedBody: String,
        message: ((IntegrityViolation) -> String)? = null,
    ) = testApplication {
        environment { log = NOPLogger.NOP_LOGGER }
        application {
            install(StatusPages) {
                if (message == null) stormIntegrityViolations() else stormIntegrityViolations(message)
            }
            routing { get("/fail") { throw failure } }
        }
        val response = client.get("/fail")
        response.status shouldBe expectedStatus
        val body = response.bodyAsText()
        body shouldBe expectedBody
        response.headers["Content-Type"] shouldBe "application/json"
        body shouldNotContain "secret"
    }

    private fun body(kind: String, message: String, fields: String = "\"telephone\"", extra: String = "") = """{"errors":[{"fields":[$fields],"kind":"$kind","message":"$message"$extra}]}"""

    @Test
    fun `a violation found before any statement answers 400 and lists every error`() = check(
        IntegrityViolationException(
            "Does not fit",
            listOf(Length(table, type, fields, 20), NotNull(table, type, fields), NumericRange(table, type, fields, 5, 2)),
        ),
        HttpStatusCode.BadRequest,
        """{"errors":[{"fields":["telephone"],"kind":"Length","message":"Too long.","maxLength":20},""" +
            """{"fields":["telephone"],"kind":"NotNull","message":"A value is required."},""" +
            """{"fields":["telephone"],"kind":"NumericRange","message":"Out of range.","precision":5,"scale":2}]}""",
    )

    @Test
    fun `a length the database refused answers 400 without a limit`() = check(reported(Length(table, type, fields, null)), HttpStatusCode.BadRequest, body("Length", "Too long."))

    @Test
    fun `a numeric range the database refused answers 400 without a limit`() = check(
        reported(NumericRange(table, type, fields, null, null)),
        HttpStatusCode.BadRequest,
        body("NumericRange", "Out of range."),
    )

    @Test
    fun `a check the database refused answers 400`() = check(
        reported(Check(constraint, table, type)),
        HttpStatusCode.BadRequest,
        body("Check", "Not an accepted value.", fields = ""),
    )

    @Test
    fun `a duplicate answers 409`() = check(
        reported(Unique(constraint, table, type, fields)),
        HttpStatusCode.Conflict,
        body("Unique", "Already taken."),
    )

    @Test
    fun `a missing parent answers 409`() = check(
        reported(ForeignKey(constraint, table, type, fields)),
        HttpStatusCode.Conflict,
        body("ForeignKey", "Refers to something that does not exist."),
    )

    @Test
    fun `a row still referenced answers 409`() = check(
        reported(Referenced(constraint, table, type)),
        HttpStatusCode.Conflict,
        body("Referenced", "Still in use.", fields = ""),
    )

    @Test
    fun `a null the database refused answers 500`() = check(
        reported(NotNull(table, type, fields)),
        HttpStatusCode.InternalServerError,
        body("NotNull", "A value is required."),
    )

    @Test
    fun `a custom message function is used and escaped`() = check(
        reported(Unique(constraint, table, type, fields)),
        HttpStatusCode.Conflict,
        body("Unique", """Say \"no\" \\ \u000a."""),
        message = { "Say \"no\" \\ \n." },
    )
}
