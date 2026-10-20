package st.orm.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Tests for the interpolation safety check applied by [build].
 *
 * The Storm compiler plugin only transforms lambdas, so the named extension functions at the bottom of this file
 * execute without the [TemplateContext.autoInterpolation] marker: they model templates compiled without the plugin.
 * Lambdas declared in this module are transformed by the plugin during test compilation and carry the marker.
 */
internal class InterpolationSafetyTest {

    @Test
    fun `fail mode throws for a template without the plugin marker`() {
        withInterpolationMode("fail") {
            val exception = shouldThrow<IllegalStateException> {
                TemplateString.raw(TemplateContext::literalWithoutMarker)
            }
            exception.message shouldContain "compiler plugin"
        }
    }

    @Test
    fun `fail mode throws when explicit t calls are present but the plugin marker is absent`() {
        withInterpolationMode("fail") {
            val exception = shouldThrow<IllegalStateException> {
                TemplateString.raw(TemplateContext::explicitTWithoutMarker)
            }
            exception.message shouldContain "compiler plugin"
        }
    }

    @Test
    fun `fail mode accepts a lambda transformed by the compiler plugin`() {
        withInterpolationMode("fail") {
            val name = "Alice"
            TemplateString.raw { "SELECT * FROM city WHERE name = $name" }.shouldNotBeNull()
        }
    }

    @Test
    fun `fail mode accepts a pure literal lambda transformed by the compiler plugin`() {
        withInterpolationMode("fail") {
            TemplateString.raw { "SELECT COUNT(*) FROM city" }.shouldNotBeNull()
        }
    }

    @Test
    fun `warn mode builds a template without the plugin marker`() {
        withInterpolationMode("warn") {
            withoutTemplateLog {
                TemplateString.raw(TemplateContext::explicitTWithoutMarker).shouldNotBeNull()
            }
        }
    }

    @Test
    fun `none mode disables the check`() {
        withInterpolationMode("none") {
            TemplateString.raw(TemplateContext::explicitTWithoutMarker).shouldNotBeNull()
        }
    }

    @Test
    fun `mode matching is trimmed and case-insensitive`() {
        withInterpolationMode(" FAIL ") {
            val exception = shouldThrow<IllegalStateException> {
                TemplateString.raw(TemplateContext::literalWithoutMarker)
            }
            exception.message shouldContain "compiler plugin"
        }
    }

    @Test
    fun `unknown mode fails fast naming the valid values`() {
        withInterpolationMode("off") {
            val exception = shouldThrow<IllegalStateException> {
                TemplateString.raw(TemplateContext::literalWithoutMarker)
            }
            exception.message shouldContain "Valid values are: warn, fail, none"
        }
    }

    @Test
    fun `unknown mode is not consulted when the plugin marker is present`() {
        withInterpolationMode("bogus") {
            TemplateString.raw { "SELECT COUNT(*) FROM city" }.shouldNotBeNull()
        }
    }
}

private const val MODE_PROPERTY = "storm.validation.interpolation_mode"

private fun withInterpolationMode(mode: String, block: () -> Unit) {
    val previous = System.getProperty(MODE_PROPERTY)
    System.setProperty(MODE_PROPERTY, mode)
    try {
        block()
    } finally {
        if (previous == null) System.clearProperty(MODE_PROPERTY) else System.setProperty(MODE_PROPERTY, previous)
    }
}

/** Keeps the warning the warn mode logs for a template without the plugin marker out of the build log. */
private fun withoutTemplateLog(block: () -> Unit) {
    val logger = Logger.getLogger("st.orm.template")
    val previous = logger.level
    logger.level = Level.OFF
    try {
        block()
    } finally {
        logger.level = previous
    }
}

private fun TemplateContext.literalWithoutMarker(): String = "SELECT COUNT(*) FROM city"

private fun TemplateContext.explicitTWithoutMarker(): String {
    val name = "Alice"
    return "SELECT * FROM city WHERE name = ${t(name)} OR alt_name = '$name'"
}
