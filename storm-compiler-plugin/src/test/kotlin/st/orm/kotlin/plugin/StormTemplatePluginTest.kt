package st.orm.kotlin.plugin

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the Storm Template compiler plugin.
 *
 * Each test compiles a Kotlin source snippet with the plugin applied and verifies
 * that string interpolations inside TemplateBuilder lambdas are auto-wrapped in t() calls.
 */
@OptIn(ExperimentalCompilerApi::class)
class StormTemplatePluginTest {

    private val templateContextStub = SourceFile.kotlin(
        "TemplateContext.kt",
        """
        package st.orm.template

        interface TemplateContext {
            fun t(o: Any?): String = interpolate(o)
            fun interpolate(o: Any?): String
            fun autoInterpolation() {}
        }

        typealias TemplateBuilder = TemplateContext.() -> String

        data class TemplateString(
            val fragments: List<String>,
            val values: List<Any?>,
            val autoInterpolation: Boolean = false,
        )

        fun TemplateBuilder.build(): TemplateString {
            var autoInterpolation = false
            val values = mutableListOf<Any?>()
            val raw = this(object : TemplateContext {
                override fun interpolate(o: Any?): String {
                    values.add(o)
                    return "\u0000"
                }
                override fun autoInterpolation() {
                    autoInterpolation = true
                }
            })
            val fragments = raw.split("\u0000")
            return TemplateString(fragments, values, autoInterpolation)
        }
        """,
    )

    private fun compile(vararg sources: SourceFile, languageVersion: String = "2.0"): JvmCompilationResult = KotlinCompilation().apply {
        this.sources = listOf(templateContextStub) + sources.toList()
        compilerPluginRegistrars = listOf(StormTemplatePluginRegistrar())
        inheritClassPath = true
        this.languageVersion = languageVersion
        verbose = false
        // The messages are the fixtures' own diagnostics, which the tests read from the result.
        messageOutputStream = java.io.OutputStream.nullOutputStream()
    }.compile()

    /** Assumes compilation succeeded; skips the test if the compiler does not support the required language features. */
    private fun assumeCompilationSuccess(result: JvmCompilationResult) {
        assumeTrue(
            result.exitCode == KotlinCompilation.ExitCode.OK,
            "Compilation failed (compiler may not support the required language features): ${result.messages.lines().take(5).joinToString("\n")}",
        )
    }

    private fun JvmCompilationResult.runMain(): String {
        val output = StringBuilder()
        val mainClass = classLoader.loadClass("TestKt")
        val oldOut = System.out
        val capture = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capture))
        try {
            mainClass.getMethod("main").invoke(null)
        } finally {
            System.setOut(oldOut)
        }
        return capture.toString().trim()
    }

    @Test
    fun `interpolation inside a string literal is rejected`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val format = "%Y-%m"
                val builder: TemplateBuilder = { "SELECT DATE_FORMAT(created, '${'$'}format') FROM users" }
                println(builder.build().fragments.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("interpolated inside a SQL string literal"), result.messages)
    }

    @Test
    fun `a like pattern built inside quotes is rejected too`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val name = "ann"
                val builder: TemplateBuilder = { "SELECT id FROM users WHERE name LIKE '%${'$'}name%'" }
                println(builder.build().fragments.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    }

    @Test
    fun `a quoted constant with no interpolation is left alone`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 1
                val builder: TemplateBuilder = { "SELECT id FROM users WHERE mark = '?' AND id = ${'$'}id" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("1", result.runMain().lines()[1])
    }

    @Test
    fun `plain string literal is unchanged`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val builder: TemplateBuilder = { "SELECT COUNT(*) FROM users" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT COUNT(*) FROM users", lines[0])
        assertEquals("0", lines[1])
    }

    @Test
    fun `single interpolation is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}id" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `multiple interpolations are auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val status = "active"
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}id AND status = ${'$'}status" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND status = |", lines[0])
        assertEquals("42,active", lines[1])
    }

    @Test
    fun `explicit t() is not double-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}{t(id)}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `mixed explicit and auto-wrapped interpolations`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val name = "Alice"
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}{t(id)} AND name = ${'$'}name" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND name = |", lines[0])
        assertEquals("42,Alice", lines[1])
    }

    @Test
    fun `multiline template is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val name = "Alice"
                val builder: TemplateBuilder = {
                    ${"\"\"\""}
                    SELECT *
                    FROM users
                    WHERE id = ${'$'}id
                      AND name = ${'$'}name
                    ${"\"\"\""}.trimIndent()
                }
                val result = builder.build()
                println(result.values.joinToString(","))
                println(result.fragments.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("42,Alice", lines[0])
        assertEquals("3", lines[1])
    }

    @Test
    fun `function parameter typed as TemplateBuilder is rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun query(template: TemplateBuilder): TemplateString = template.build()

            fun main() {
                val id = 42
                val result = query { "SELECT * FROM users WHERE id = ${'$'}id" }
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `interpolate() is not double-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}{interpolate(id)}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `string outside TemplateBuilder is not rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val regular = "id is ${'$'}id"
                println(regular)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        assertEquals("id is 42", output)
    }

    @Test
    fun `null value interpolation is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val name: String? = null
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE name = ${'$'}name" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(",") { it?.toString() ?: "NULL" })
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE name = |", lines[0])
        assertEquals("NULL", lines[1])
    }

    @Test
    fun `autoInterpolation is called for template with interpolations`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}id" }
                val result = builder.build()
                println(result.autoInterpolation)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        assertEquals("true", output)
    }

    @Test
    fun `autoInterpolation is called for plain literal template`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val builder: TemplateBuilder = { "SELECT COUNT(*) FROM users" }
                val result = builder.build()
                println(result.autoInterpolation)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        assertEquals("true", output)
    }

    @Test
    fun `inline string constant between expressions is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val status = "active"
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}id AND email LIKE ${'$'}{"%@gmail.com"} AND status = ${'$'}status" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND email LIKE | AND status = |", lines[0])
        assertEquals("42,%@gmail.com,active", lines[1])
    }

    @Test
    fun `inline string constant at end of template is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}id AND email LIKE ${'$'}{"%@gmail.com"}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND email LIKE |", lines[0])
        assertEquals("42,%@gmail.com", lines[1])
    }

    @Test
    fun `inline string constant in multiline template is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val status = "active"
                val builder: TemplateBuilder = {
                    ${"\"\"\""}
                    SELECT *
                    FROM users
                    WHERE id = ${'$'}id
                      AND email LIKE ${'$'}{"%@gmail.com"}
                      AND status = ${'$'}status
                    ${"\"\"\""}.trimIndent()
                }
                val result = builder.build()
                println(result.values.joinToString(","))
                println(result.values.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("42,%@gmail.com,active", lines[0])
        assertEquals("3", lines[1])
    }

    @Test
    fun `nested TemplateBuilder lambdas are independently rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun query(template: TemplateBuilder): TemplateString = template.build()

            fun main() {
                val outerValue = 1
                val innerValue = 2
                var innerValues = ""
                val outerResult = query {
                    val innerResult = query { "SELECT ${'$'}innerValue" }
                    innerValues = innerResult.values.joinToString(",")
                    "SELECT ${'$'}outerValue"
                }
                println(innerValues)
                println(outerResult.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("2", lines[0])
        assertEquals("1", lines[1])
    }

    @Test
    fun `nested TemplateBuilder lambda inside string interpolation is rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun subquery(template: TemplateBuilder): TemplateString = template.build()

            fun main() {
                val outerValue = 1
                val innerValue = 2
                val outerResult: TemplateBuilder = {
                    "HAVING COUNT(${'$'}outerValue) = (${'$'}{subquery { "COUNT(${'$'}innerValue)" }})"
                }
                val result = outerResult.build()
                println(result.values.size)
                println(result.values[0])
                val subResult = result.values[1] as TemplateString
                println(subResult.values.size)
                println(subResult.values[0])
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("2", lines[0]) // outer has 2 interpolated values
        assertEquals("1", lines[1]) // first outer value is 1
        assertEquals("1", lines[2]) // inner subquery has 1 interpolated value
        assertEquals("2", lines[3]) // inner value is 2
    }

    @Test
    fun `lambda with non-TemplateContext receiver is not rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun StringBuilder.buildString(block: StringBuilder.() -> String): String = block()

            fun main() {
                val id = 42
                val result = StringBuilder().buildString { "id = ${'$'}id" }
                println(result)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        assertEquals("id = 42", output)
    }

    @Test
    fun `non-t function call in interpolation is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}{id.toString()}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `multiple string templates in one lambda are all rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val name = "Alice"
                val builder: TemplateBuilder = {
                    if (id > 0) {
                        "SELECT * FROM users WHERE id = ${'$'}id AND name = ${'$'}name"
                    } else {
                        "SELECT * FROM users WHERE name = ${'$'}name"
                    }
                }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND name = |", lines[0])
        assertEquals("42,Alice", lines[1])
    }

    @Test
    fun `expression interpolation is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val ids = listOf(1, 2, 3)
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id IN ${'$'}{ids}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values[0])
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id IN |", lines[0])
        assertEquals("[1, 2, 3]", lines[1])
    }

    // -- String concatenation (+) tests --

    @Test
    fun `interpolation concatenated with a literal is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val country = "NL"
                val builder: TemplateBuilder = { "SELECT ${'$'}{country}, " + "COUNT(*) FROM users" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT |, COUNT(*) FROM users", lines[0])
        assertEquals("NL", lines[1])
    }

    @Test
    fun `literal concatenated with an interpolation is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users " + "WHERE id = ${'$'}id" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `value concatenated with a literal is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = " + id }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `value between literals is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = " + id + " ORDER BY name" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | ORDER BY name", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `concatenated non-string constant is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = " + 42 + " ORDER BY name" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | ORDER BY name", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `concatenated literals stay a single fragment`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val builder: TemplateBuilder = { "SELECT COUNT(*) " + "FROM users" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.size)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT COUNT(*) FROM users", lines[0])
        assertEquals("0", lines[1])
    }

    @Test
    fun `concatenated interpolations are auto-wrapped in order`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val status = "active"
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = ${'$'}id" + " AND status = ${'$'}status" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND status = |", lines[0])
        assertEquals("42,active", lines[1])
    }

    @Test
    fun `explicit t() concatenated with a literal is not double-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE id = " + t(id) + " ORDER BY name" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | ORDER BY name", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `concatenation in a conditional branch is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = {
                    if (id > 0) "SELECT * FROM users WHERE id = " + id else "SELECT * FROM users"
                }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `concatenation inside an interpolation stays a single value`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val name = "Alice"
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE name LIKE ${'$'}{"%" + name + "%"}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE name LIKE |", lines[0])
        assertEquals("%Alice%", lines[1])
    }

    @Test
    fun `nested template inside an interpolation stays a single value`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val name = "Alice"
                val builder: TemplateBuilder = { "SELECT * FROM users WHERE name LIKE ${'$'}{"%${'$'}name%"}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE name LIKE |", lines[0])
        assertEquals("%Alice%", lines[1])
    }

    @Test
    fun `concatenation outside TemplateBuilder is not rewritten`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val regular = "id is " + id
                println(regular)
            }
            """,
        )
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val output = result.runMain()
        assertEquals("id is 42", output)
    }

    // -- Multi-dollar string interpolation ($$) tests --

    @Test
    fun `multi-dollar single interpolation is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { ${'$'}${'$'}"SELECT * FROM users WHERE id = ${'$'}${'$'}id" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source, languageVersion = "2.2")
        assumeCompilationSuccess(result)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `multi-dollar literal dollar sign is preserved as fragment`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { ${'$'}${'$'}"SELECT * FROM users WHERE cost > ${'$'}5 AND id = ${'$'}${'$'}id" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source, languageVersion = "2.2")
        assumeCompilationSuccess(result)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE cost > ${'$'}5 AND id = |", lines[0])
        assertEquals("42", lines[1])
    }

    @Test
    fun `multi-dollar multiple interpolations are auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val status = "active"
                val builder: TemplateBuilder = { ${'$'}${'$'}"SELECT * FROM users WHERE id = ${'$'}${'$'}id AND status = ${'$'}${'$'}status" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source, languageVersion = "2.2")
        assumeCompilationSuccess(result)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND status = |", lines[0])
        assertEquals("42,active", lines[1])
    }

    @Test
    fun `multi-dollar inline string constant is auto-wrapped`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val id = 42
                val builder: TemplateBuilder = { ${'$'}${'$'}"SELECT * FROM users WHERE id = ${'$'}${'$'}id AND email LIKE ${'$'}${'$'}{"%@gmail.com"}" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source, languageVersion = "2.2")
        assumeCompilationSuccess(result)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT * FROM users WHERE id = | AND email LIKE |", lines[0])
        assertEquals("42,%@gmail.com", lines[1])
    }

    @Test
    fun `multi-dollar plain string literal is unchanged`() {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            fun main() {
                val builder: TemplateBuilder = { ${'$'}${'$'}"SELECT COUNT(*) FROM users" }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.size)
            }
            """,
        )
        val result = compile(source, languageVersion = "2.2")
        assumeCompilationSuccess(result)
        val output = result.runMain()
        val lines = output.lines()
        assertEquals("SELECT COUNT(*) FROM users", lines[0])
        assertEquals("0", lines[1])
    }

    // -- Folded constant tests --
    //
    // The compiler folds constant interpolations like ${"value"} into the surrounding template text before the
    // plugin runs. The plugin parses the source to split such constants back into text and values, verifying the
    // result against the folded value, and reports a compiler error when a constant cannot be split. These tests
    // pin the split for the source shapes that used to defeat it: escape sequences, adjacent constants, fully
    // constant templates, and multi-dollar interpolation.

    private fun JvmCompilationResult.runMainEscaped(): List<String> {
        val mainClass = classLoader.loadClass("TestKt")
        val oldOut = System.out
        val capture = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(capture))
        try {
            mainClass.getMethod("main").invoke(null)
        } finally {
            System.setOut(oldOut)
        }
        return capture.toString().trim().lines()
    }

    /** Compiles a TemplateBuilder body and asserts the resulting fragments and values, newlines and tabs escaped. */
    private fun assertTemplate(body: String, expectedFragments: String, expectedValues: String, languageVersion: String = "2.0", prelude: String = "") {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            $prelude

            fun main() {
                val builder: TemplateBuilder = { $body }
                val result = builder.build()
                println(result.fragments.joinToString("|").replace("\n", "\\n").replace("\t", "\\t"))
                println(result.values.joinToString(",").replace("\n", "\\n"))
            }
            """,
        )
        val result = compile(source, languageVersion = languageVersion)
        if (languageVersion != "2.0") {
            assumeCompilationSuccess(result)
        }
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val lines = result.runMainEscaped()
        assertEquals(expectedFragments, lines[0])
        assertEquals(expectedValues, lines.getOrElse(1) { "" })
    }

    /**
     * Compiles a TemplateBuilder body whose folded constant the plugin cannot split. Compiler versions that fold
     * the constant must report an error rather than leave the interpolation as SQL text; versions that keep the
     * interpolation as a runtime value already bind it correctly and must compile.
     */
    private fun assertUnsplittableConstant(body: String, expectedFragments: String, expectedValues: String, prelude: String = "") {
        val source = SourceFile.kotlin(
            "Test.kt",
            """
            import st.orm.template.*

            $prelude

            fun main() {
                val builder: TemplateBuilder = { $body }
                val result = builder.build()
                println(result.fragments.joinToString("|"))
                println(result.values.joinToString(","))
            }
            """,
        )
        val result = compile(source)
        if (result.exitCode == KotlinCompilation.ExitCode.OK) {
            val lines = result.runMainEscaped()
            assertEquals(expectedFragments, lines[0])
            assertEquals(expectedValues, lines.getOrElse(1) { "" })
        } else {
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(
                result.messages.contains("Storm compiler plugin cannot determine"),
                "Expected the Storm unsplittable-constant error, got: ${result.messages}",
            )
        }
    }

    @Test
    fun `escape sequence before inline constant is split`() {
        assertTemplate(""" "a\nb${'$'}{"c"}d" """.trim(), """a\nb|d""", "c")
    }

    @Test
    fun `escape sequence before inline constant at end of template is split`() {
        assertTemplate(""" "a\tb${'$'}{"c"}" """.trim(), """a\tb|""", "c")
    }

    @Test
    fun `escaped quote before inline constant is split`() {
        assertTemplate(""" "a\"b${'$'}{"c"}" """.trim(), """a"b|""", "c")
    }

    @Test
    fun `unicode escape before inline constant is split`() {
        assertTemplate(""" "a\u00e9${'$'}{"c"}" """.trim(), "aé|", "c")
    }

    @Test
    fun `escape sequence after inline constant is split`() {
        assertTemplate(""" "a${'$'}{"c"}\nd" """.trim(), """a|\nd""", "c")
    }

    @Test
    fun `escape sequence inside inline constant is split`() {
        assertTemplate(""" "x${'$'}{"a\nb"}y" """.trim(), "x|y", """a\nb""")
    }

    @Test
    fun `escaped dollar before inline constant is split`() {
        assertTemplate(""" "a\${'$'}x ${'$'}{"c"} b" """.trim(), "a${'$'}x | b", "c")
    }

    @Test
    fun `escaped interpolation marker stays text`() {
        assertTemplate(""" "a\${'$'}{x}b" """.trim(), "a${'$'}{x}b", "")
    }

    @Test
    fun `template consisting of only an inline constant is a value`() {
        assertTemplate(""" "${'$'}{"c"}" """.trim(), "|", "c")
    }

    @Test
    fun `adjacent inline constants are values`() {
        assertTemplate(""" "${'$'}{"a"}${'$'}{"b"}" """.trim(), "||", "a,b")
    }

    @Test
    fun `leading inline constant is a value`() {
        assertTemplate(""" "${'$'}{"a"} b" """.trim(), "| b", "a")
    }

    @Test
    fun `raw string with backslash before inline constant is split`() {
        assertTemplate("\"\"\"a\\n${'$'}{\"c\"}b\"\"\"", """a\n|b""", "c")
    }

    @Test
    fun `chain operand with escape and inline constant is split`() {
        assertTemplate(""" "a\n" + "b${'$'}{"c"}d" """.trim(), """a\nb|d""", "c")
    }

    @Test
    fun `inline int constant is a value`() {
        assertTemplate(""" "LIMIT ${'$'}{42}" """.trim(), "LIMIT |", "42")
    }

    @Test
    fun `inline char constant is a value`() {
        assertTemplate(""" "a${'$'}{'c'}b" """.trim(), "a|b", "c")
    }

    @Test
    fun `inline boolean constant is a value`() {
        assertTemplate(""" "WHERE active = ${'$'}{true}" """.trim(), "WHERE active = |", "true")
    }

    @Test
    fun `inline constant with whitespace inside braces is a value`() {
        assertTemplate(""" "x${'$'}{ "c" }y" """.trim(), "x|y", "c")
    }

    @Test
    fun `multi-dollar escape before inline constant is split`() {
        assertTemplate(
            """ ${'$'}${'$'}"a\nb${'$'}${'$'}{"c"}d" """.trim(),
            """a\nb|d""",
            "c",
            languageVersion = "2.2",
        )
    }

    @Test
    fun `multi-dollar literal marker with inline constant stays text`() {
        assertTemplate(
            """ ${'$'}${'$'}"WHERE ${'$'}{x} = ${'$'}${'$'}{"c"}" """.trim(),
            "WHERE ${'$'}{x} = |",
            "c",
            languageVersion = "2.2",
        )
    }

    @Test
    fun `multi-dollar surplus dollar before inline constant stays text`() {
        assertTemplate(
            """ ${'$'}${'$'}"a${'$'}${'$'}${'$'}{"c"}b" """.trim(),
            "a${'$'}|b",
            "c",
            languageVersion = "2.2",
        )
    }

    @Test
    fun `fully constant raw string with trimIndent is split`() {
        assertTemplate(
            "\"\"\"SELECT ${'$'}{\"c\"} FROM users\"\"\".trimIndent()",
            "SELECT | FROM users",
            "c",
        )
    }

    @Test
    fun `fully constant conditional branches are split`() {
        assertTemplate(
            """ if (System.currentTimeMillis() > 0) "a${'$'}{"c"}b" else "x${'$'}{"y"}z" """.trim(),
            "a|b",
            "c",
        )
    }

    @Test
    fun `folded constant reference is a value`() {
        assertTemplate(
            """
                val id = 42
                "a ${'$'}id ${'$'}{LIMIT} b"
            """.trimIndent(),
            "a | | b",
            "42,10",
            prelude = """const val LIMIT = "10"""",
        )
    }

    @Test
    fun `folded simple-name constant reference is a value`() {
        assertTemplate(
            """ "a ${'$'}LIMIT b" """.trim(),
            "a | b",
            "10",
            prelude = """const val LIMIT = "10"""",
        )
    }

    @Test
    fun `numbers in template text stay text`() {
        assertTemplate(""" "SELECT name FROM users LIMIT 5" """.trim(), "SELECT name FROM users LIMIT 5", "")
    }

    @Test
    fun `numbers in template text next to an inline constant stay text`() {
        assertTemplate(""" "SELECT ${'$'}{"name"} FROM users LIMIT 5" """.trim(), "SELECT | FROM users LIMIT 5", "name")
    }

    @Test
    fun `inline float constant binds or is reported`() {
        // Kotlin 2.0 folds numeric interpolations into the template text; a float's rendering is not derived from
        // the source, so the fold must surface as a compiler error rather than SQL text. Later compilers keep the
        // interpolation as a runtime value.
        assertUnsplittableConstant(""" "LIMIT ${'$'}{1.5}" """.trim(), "LIMIT |", "1.5")
    }
}
