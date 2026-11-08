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
package st.orm.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import st.orm.IntegrityViolation
import st.orm.IntegrityViolation.Check
import st.orm.IntegrityViolation.ForeignKey
import st.orm.IntegrityViolation.Length
import st.orm.IntegrityViolation.NotNull
import st.orm.IntegrityViolation.NumericRange
import st.orm.IntegrityViolation.Referenced
import st.orm.IntegrityViolation.Unique
import st.orm.IntegrityViolationException

/**
 * Answers an [IntegrityViolationException] with the status that says whose fault it is and a JSON body of its errors.
 *
 * A violation found before any statement ran, by `validateOrThrow` or the null check of a write, and a length,
 * numeric range or check the database refused answer 400. A unique or foreign key violation and a row still referenced
 * answer 409. A null the database refused passed Storm's own check, so the field is nullable where the column is not;
 * it answers 500 and is logged.
 *
 * Each error lists its `fields` as entity paths, its `kind` and its `message`, with `maxLength`, or `precision` and
 * `scale`, where the limit is known. The body names no table, constraint or statement, and is written without a
 * serializer, so it works with any content negotiation setup.
 *
 * ```kotlin
 * install(StatusPages) {
 *     stormIntegrityViolations()
 * }
 * ```
 *
 * @param message turns a violation into the message of its error, for localization.
 * @since 1.16
 */
public fun StatusPagesConfig.stormIntegrityViolations(
    message: (IntegrityViolation) -> String = ::defaultMessage,
) {
    exception<IntegrityViolationException> { call, cause ->
        val status = status(cause)
        if (status == HttpStatusCode.InternalServerError) {
            call.application.log.error("A field accepts a null its column refuses.", cause)
        }
        call.respondText(body(cause.violations(), message), ContentType.Application.Json, status)
    }
}

private fun status(exception: IntegrityViolationException): HttpStatusCode {
    if (exception.cause == null) {
        return HttpStatusCode.BadRequest
    }
    return when (exception.violations().first()) {
        is Unique, is ForeignKey, is Referenced -> HttpStatusCode.Conflict
        is NotNull -> HttpStatusCode.InternalServerError
        is Check, is Length, is NumericRange -> HttpStatusCode.BadRequest
    }
}

private fun defaultMessage(violation: IntegrityViolation): String = when (violation) {
    is Unique -> "Already taken."
    is ForeignKey -> "Refers to something that does not exist."
    is Referenced -> "Still in use."
    is NotNull -> "A value is required."
    is Check -> "Not an accepted value."
    is Length -> "Too long."
    is NumericRange -> "Out of range."
}

private fun body(violations: List<IntegrityViolation>, message: (IntegrityViolation) -> String): String = violations.joinToString(",", prefix = """{"errors":[""", postfix = "]}") { violation ->
    buildString {
        append("""{"fields":[""")
        append(violation.fields().joinToString(",") { json(it.fieldPath()) })
        append("""],"kind":""").append(json(violation::class.java.simpleName))
        append(""","message":""").append(json(message(violation)))
        when (violation) {
            is Length -> violation.maxLength()?.let { append(""","maxLength":""").append(it) }
            is NumericRange -> violation.precision()?.let { precision ->
                append(""","precision":""").append(precision)
                append(""","scale":""").append(violation.scale())
            }
            else -> Unit
        }
        append('}')
    }
}

private fun json(value: String): String = buildString {
    append('"')
    for (character in value) {
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character < ' ' -> append("\\u%04x".format(character.code))
            else -> append(character)
        }
    }
    append('"')
}
