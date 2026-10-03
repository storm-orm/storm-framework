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
package st.orm.metamodel

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compiles fixture sources with the processor attached and asserts on the generated files. The fixtures compile
 * against the actual storm-foundation classes, so the `st.orm.GenerateMetamodel` annotation the processor matches
 * by name is the real one and the generated code compiles against the real metamodel base classes.
 */
@OptIn(ExperimentalCompilerApi::class)
class MetamodelProcessorTest {

    private fun compilation(source: String): KotlinCompilation = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("CityStats.kt", source))
        useKsp2()
        symbolProcessorProviders = mutableListOf(MetamodelProcessorProvider())
        inheritClassPath = true
        verbose = false
        // The messages are the fixtures' own diagnostics, which the tests read from the result.
        messageOutputStream = java.io.OutputStream.nullOutputStream()
    }

    private fun compile(source: String): KotlinCompilation {
        val compilation = compilation(source)
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return compilation
    }

    private fun compileForMessages(source: String): String {
        val result = compilation(source).compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        return result.messages
    }

    private fun compileExpectingError(source: String): String {
        val compilation = compilation(source)
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        return result.messages
    }

    private fun KotlinCompilation.generatedFileNames(): Set<String> = kspSourcesDir.walkTopDown().filter { it.isFile }.map { it.name }.toSet()

    private fun KotlinCompilation.generatedSource(name: String): String = kspSourcesDir.walkTopDown().first { it.isFile && it.name == name }.readText()

    @Test
    fun `generates metamodel for annotated plain data class`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.GenerateMetamodel

            @GenerateMetamodel
            data class CityStats(val name: String, val inhabitants: Int)
            """.trimIndent(),
        )
        val generated = compilation.generatedFileNames()
        assertTrue("CityStatsMetamodel.kt" in generated) {
            "expected a metamodel for the @GenerateMetamodel data class, generated: $generated"
        }
        assertTrue("CityStatsNullableMetamodel.kt" in generated) {
            "expected a nullable-chain metamodel for the @GenerateMetamodel data class, generated: $generated"
        }
        assertTrue("CityStatsInstantiator.kt" in generated) {
            "expected an instantiator for the @GenerateMetamodel data class, generated: $generated"
        }
        assertFalse("CityStats_.kt" in generated) {
            "the root metamodel interface is reserved for Data types, generated: $generated"
        }
        val services = compilation.workingDir.walkTopDown().filter { it.isFile }
        assertTrue(services.any { it.name == "st.orm.mapping.Instantiator" && "CityStatsInstantiator" in it.readText() }) {
            "expected an instantiator service registration"
        }
    }

    @Test
    fun `sources components from the primary constructor only`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.GenerateMetamodel

            interface Labeled {
                val label: String get() = "label"
            }

            @GenerateMetamodel
            data class CityStats(val name: String, val inhabitants: Int) : Labeled {
                val density: Int get() = inhabitants / 2
            }
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("CityStatsMetamodel.kt")
        assertTrue("val name" in metamodel) { "expected the constructor component in the metamodel:\n$metamodel" }
        assertFalse("density" in metamodel) { "a body-declared property has no column and no metamodel field:\n$metamodel" }
        assertFalse("label" in metamodel) { "an inherited property has no column and no metamodel field:\n$metamodel" }
    }

    @Test
    fun `escapes keyword-named properties at every emission site`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.GenerateMetamodel

            @GenerateMetamodel
            data class CityStats(val name: String, val `object`: String, val `fun`: Int)
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("CityStatsMetamodel.kt")
        assertTrue("`object`" in metamodel) { "expected the keyword-named property backticked:\n$metamodel" }
        assertTrue("fieldBase + \"object\"" in metamodel) { "the field string literal keeps the raw name:\n$metamodel" }
    }

    @Test
    fun `selects child metamodel by property nullability`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.GenerateMetamodel

            @GenerateMetamodel
            data class Owner(val name: String, val address: Address, val previousAddress: Address?)

            data class Address(val street: String, val city: String)
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("OwnerMetamodel.kt")
        assertTrue("val address: AddressMetamodel<T>" in metamodel) {
            "a non-null property selects the base child metamodel:\n$metamodel"
        }
        assertTrue("val previousAddress: AddressNullableMetamodel<T>" in metamodel) {
            "a nullable property selects the nullable-chain child metamodel:\n$metamodel"
        }
        val nullableMetamodel = compilation.generatedSource("OwnerNullableMetamodel.kt")
        assertTrue("val address: AddressNullableMetamodel<T>" in nullableMetamodel) {
            "inside a nullable chain every child is the nullable-chain variant:\n$nullableMetamodel"
        }
    }

    @Test
    fun `sealed interfaces contribute abstract declared properties only`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Data

            sealed interface Vehicle : Data {
                val code: String
                val label: String get() = "vehicle"
            }

            data class Car(override val code: String, val doors: Int) : Vehicle
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("VehicleMetamodel.kt")
        assertTrue("val code" in metamodel) { "expected the abstract property in the sealed metamodel:\n$metamodel" }
        assertFalse("label" in metamodel) { "a defaulted property has no column and no metamodel field:\n$metamodel" }
    }

    @Test
    fun `escapes keyword-named properties in the metamodel interface and primary key`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK

            data class Registry(@PK val `object`: Int, val `fun`: String) : Entity<Int>
            """.trimIndent(),
        )
        val metamodelInterface = compilation.generatedSource("Registry_.kt")
        assertTrue("`object`" in metamodelInterface) {
            "expected the keyword-named property backticked in the interface:\n$metamodelInterface"
        }
        val metamodel = compilation.generatedSource("RegistryMetamodel.kt")
        assertTrue("ra.`object` == rb.`object`" in metamodel) {
            "expected the primary key backticked in isSame:\n$metamodel"
        }
    }

    @Test
    fun `rejects non-Ref foreign key cycle between entities`() {
        val messages = compileExpectingError(
            """
            package com.example

            import st.orm.Entity
            import st.orm.FK
            import st.orm.PK

            data class Owner(@PK val id: Int, @FK val pet: Pet) : Entity<Int>

            data class Pet(@PK val id: Int, @FK val owner: Owner) : Entity<Int>
            """.trimIndent(),
        )
        assertTrue(
            "Cycle of non-Ref foreign keys: Owner -> Pet -> Owner" in messages ||
                "Cycle of non-Ref foreign keys: Pet -> Owner -> Pet" in messages,
        ) { messages }
        assertTrue("Mark one of the foreign keys as Ref" in messages) { messages }
    }

    @Test
    fun `rejects self-referencing non-Ref foreign key`() {
        val messages = compileExpectingError(
            """
            package com.example

            import st.orm.Entity
            import st.orm.FK
            import st.orm.PK

            data class Employee(@PK val id: Int, @FK val manager: Employee) : Entity<Int>
            """.trimIndent(),
        )
        assertTrue("Cycle of non-Ref foreign keys: Employee -> Employee" in messages) { messages }
    }

    @Test
    fun `accepts foreign key cycle through a Ref boundary`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.FK
            import st.orm.PK
            import st.orm.Ref

            data class Owner(@PK val id: Int, @FK val pet: Pet) : Entity<Int>

            data class Pet(@PK val id: Int, @FK val owner: Ref<Owner>) : Entity<Int>
            """.trimIndent(),
        )
        val generated = compilation.generatedFileNames()
        assertTrue("OwnerMetamodel.kt" in generated) {
            "a cycle through a Ref boundary is loadable and generates as usual, generated: $generated"
        }
        assertTrue("PetMetamodel.kt" in generated) {
            "a cycle through a Ref boundary is loadable and generates as usual, generated: $generated"
        }
    }

    @Test
    fun `accepts deep foreign key chain closed by a Ref boundary`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.FK
            import st.orm.PK
            import st.orm.Ref

            data class Country(@PK val id: Int, @FK val region: Region) : Entity<Int>

            data class Region(@PK val id: Int, @FK val city: City) : Entity<Int>

            data class City(@PK val id: Int, @FK val country: Ref<Country>) : Entity<Int>
            """.trimIndent(),
        )
        val generated = compilation.generatedFileNames()
        assertTrue("CityMetamodel.kt" in generated) {
            "the chain closed by a Ref at depth three generates as usual, generated: $generated"
        }
        assertTrue("CountryRefMetamodel.kt" in generated) {
            "the Ref boundary generates a reference metamodel, generated: $generated"
        }
        assertTrue("NavigableRegionMetamodel.kt" in generated) {
            "navigation beyond the Ref boundary reaches the deeper graph, generated: $generated"
        }
    }

    @Test
    fun `ignores plain data class without annotation`() {
        val compilation = compile(
            """
            package com.example

            data class CityStats(val name: String, val inhabitants: Int)
            """.trimIndent(),
        )
        val generated = compilation.generatedFileNames()
        assertTrue(generated.isEmpty()) {
            "a plain data class without @GenerateMetamodel should not get generated files, generated: $generated"
        }
    }

    @Test
    fun `a nullable unique key warns`() {
        val messages = compileForMessages(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.UK

            data class Account(@PK val id: Int = 0, @UK val email: String?) : Entity<Int>
            """,
        )
        assertTrue("Unique key field 'email' is nullable" in messages) { messages }
    }

    @Test
    fun `a nullable unique key does not warn where the property suppresses it`() {
        val messages = compileForMessages(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.UK

            data class Account(
                @PK val id: Int = 0,
                @Suppress("storm.nullable-unique-key") @UK val email: String?,
            ) : Entity<Int>
            """,
        )
        assertTrue("Unique key field" !in messages) { messages }
    }

    @Test
    fun `a nullable unique key does not warn where the class suppresses it`() {
        val messages = compileForMessages(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.UK

            @Suppress("storm.nullable-unique-key")
            data class Account(@PK val id: Int = 0, @UK val email: String?) : Entity<Int>
            """,
        )
        assertTrue("Unique key field" !in messages) { messages }
    }
}
