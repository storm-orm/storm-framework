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
    fun `sealed interfaces declare their entity-typed properties as foreign keys`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Discriminator
            import st.orm.Entity
            import st.orm.FK
            import st.orm.PK

            data class Pet(@PK val id: Int, val name: String) : Entity<Int>

            @Discriminator
            sealed interface Treatment : Entity<Int> {
                val id: Int
                val pet: Pet
            }

            data class Checkup(@PK override val id: Int, @FK override val pet: Pet, val notes: String) : Treatment
            """.trimIndent(),
        )
        val metamodelInterface = compilation.generatedSource("Treatment_.kt")
        assertTrue("val pet: PetMetamodel<Treatment>" in metamodelInterface) {
            "expected the entity-typed property as a nested metamodel in the sealed metamodel:\n$metamodelInterface"
        }
    }

    @Test
    fun `type index lists sealed Data interfaces with their subtypes`() {
        val compilation = compilation(
            """
            package com.example

            import st.orm.Discriminator
            import st.orm.Entity
            import st.orm.PK

            @Discriminator
            sealed interface Treatment : Entity<Int> {
                val id: Int
            }

            data class Checkup(@PK override val id: Int, val notes: String) : Treatment
            """.trimIndent(),
        )
        compilation.symbolProcessorProviders.add(TypeIndexProcessorProvider())
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val index = compilation.kspSourcesDir.walkTopDown().first { it.isFile && it.name == "st.orm.Data.idx" }.readText()
        assertTrue("com.example.Treatment" in index) { "expected the sealed entity in the index:\n$index" }
        assertTrue("com.example.Checkup" in index) { "expected the subtype in the index:\n$index" }
    }

    @Test
    fun `type index lists the classes annotated GenerateMetamodel outside the entity graph`() {
        val compilation = compilation(
            """
            package com.example

            import st.orm.Entity
            import st.orm.GenerateMetamodel
            import st.orm.PK

            @GenerateMetamodel
            data class OwnerPetCount(val ownerId: Int, val pets: Long)

            @GenerateMetamodel
            data class Owner(@PK val id: Int, val name: String) : Entity<Int>
            """.trimIndent(),
        )
        compilation.symbolProcessorProviders.add(TypeIndexProcessorProvider())
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val index = compilation.kspSourcesDir.walkTopDown().first { it.isFile && it.name == "st.orm.GenerateMetamodel.idx" }.readText()
        assertEquals("com.example.OwnerPetCount\n", index) {
            "expected the result class alone in the index: a Data type is listed in the Data index only"
        }
        val dataIndex = compilation.kspSourcesDir.walkTopDown().first { it.isFile && it.name == "st.orm.Data.idx" }.readText()
        assertTrue("com.example.Owner" in dataIndex) { "expected the entity in the Data index:\n$dataIndex" }
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
    fun `repeated query columns generate throwing fields`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.FK
            import st.orm.PK
            import st.orm.QueryColumn
            import st.orm.Ref

            @QueryColumn(field = "titleFolded", type = String::class)
            @QueryColumn(field = "releaseYear", type = Int::class)
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>

            data class Credit(@PK val id: Int = 0, @FK val movie: Ref<Movie>) : Entity<Int>
            """.trimIndent(),
        )
        val movieInterface = compilation.generatedSource("Movie_.kt")
        assertTrue(
            "        /** Represents the titleFolded query column of Movie. */\n" +
                "        val titleFolded: AbstractMetamodel<Movie, String, String> = MovieMetamodel.instance<Movie>().titleFolded"
                in movieInterface,
        ) { "expected the query column in the metamodel interface:\n$movieInterface" }
        assertTrue("val releaseYear: AbstractMetamodel<Movie, Int, Int>" in movieInterface) {
            "expected the second query column in the metamodel interface:\n$movieInterface"
        }
        assertTrue(movieInterface.indexOf("val title:") < movieInterface.indexOf("val titleFolded:")) {
            "query columns follow the components:\n$movieInterface"
        }
        assertTrue(movieInterface.indexOf("val titleFolded:") < movieInterface.indexOf("val releaseYear:")) {
            "query columns keep their declaration order:\n$movieInterface"
        }
        val movieMetamodel = compilation.generatedSource("MovieMetamodel.kt")
        assertTrue("    val titleFolded: AbstractMetamodel<T, String, String>\n" in movieMetamodel) {
            "expected the query column field in the metamodel class:\n$movieMetamodel"
        }
        val failure = "throw UnsupportedOperationException(\"Movie.titleFolded is a query column; a record holds no value for it.\")"
        assertTrue(
            "        this.titleFolded = object : AbstractMetamodel<T, String, String>(" +
                "String::class.java, subPath, fieldBase + \"titleFolded\", false, this) {\n" +
                "            override fun getValue(record: T): String = $failure\n\n" +
                "            override fun isIdentical(a: T, b: T): Boolean = $failure\n\n" +
                "            override fun isSame(a: T, b: T): Boolean = $failure\n" +
                "        }"
                in movieMetamodel,
        ) { "a record holds no value for a query column, so its value methods throw:\n$movieMetamodel" }
        assertTrue("object : AbstractMetamodel<T, Int, Int>(Int::class.javaObjectType, subPath, fieldBase + \"releaseYear\"" in movieMetamodel) {
            "a primitive query column type renders as its object type:\n$movieMetamodel"
        }
        val movieNullableMetamodel = compilation.generatedSource("MovieNullableMetamodel.kt")
        assertTrue("val titleFolded: AbstractMetamodel<T, String, String?>" in movieNullableMetamodel) {
            "the nullable-chain variant reads every value as nullable, as for a component:\n$movieNullableMetamodel"
        }
        val movieRefMetamodel = compilation.generatedSource("MovieRefMetamodel.kt")
        assertTrue(
            "this.titleFolded = object : st.orm.AbstractNavigableMetamodel<T, String>(" +
                "String::class.java, subPath, fieldBase + \"titleFolded\", false, this) {}"
                in movieRefMetamodel,
        ) { "a query column is reachable beyond a reference:\n$movieRefMetamodel" }
    }

    @Test
    fun `query columns in the container generate fields`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.QueryColumn
            import st.orm.QueryColumns

            @QueryColumns(
                QueryColumn(field = "titleFolded", type = String::class),
                QueryColumn(field = "releaseYear", type = Int::class),
            )
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>
            """.trimIndent(),
        )
        val movieInterface = compilation.generatedSource("Movie_.kt")
        assertTrue("val titleFolded: AbstractMetamodel<Movie, String, String>" in movieInterface) {
            "expected the first contained query column in the interface:\n$movieInterface"
        }
        assertTrue("val releaseYear: AbstractMetamodel<Movie, Int, Int>" in movieInterface) {
            "expected the second contained query column in the interface:\n$movieInterface"
        }
        val movieMetamodel = compilation.generatedSource("MovieMetamodel.kt")
        assertTrue("UnsupportedOperationException(\"Movie.releaseYear is a query column; a record holds no value for it.\")" in movieMetamodel) {
            "expected throwing value methods for the contained query columns:\n$movieMetamodel"
        }
    }

    @Test
    fun `a nullable query column renders a nullable value type`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.QueryColumn

            @QueryColumn(field = "titleFolded", type = String::class, nullable = true)
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>
            """.trimIndent(),
        )
        val movieInterface = compilation.generatedSource("Movie_.kt")
        assertTrue("val titleFolded: AbstractMetamodel<Movie, String, String?>" in movieInterface) {
            "a nullable query column carries a nullable value type:\n$movieInterface"
        }
        val movieMetamodel = compilation.generatedSource("MovieMetamodel.kt")
        assertTrue("override fun getValue(record: T): String? = throw" in movieMetamodel) {
            "a nullable query column carries a nullable value type:\n$movieMetamodel"
        }
    }

    @Test
    fun `a keyword-named query column is escaped`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.QueryColumn

            @QueryColumn(field = "object", type = String::class)
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>
            """.trimIndent(),
        )
        val movieMetamodel = compilation.generatedSource("MovieMetamodel.kt")
        assertTrue("this.`object` = object : AbstractMetamodel<T, String, String>" in movieMetamodel) {
            "expected the keyword-named query column backticked:\n$movieMetamodel"
        }
        assertTrue("fieldBase + \"object\"" in movieMetamodel) { "the field string literal keeps the raw name:\n$movieMetamodel" }
    }

    @Test
    fun `a duplicate query column field fails compilation`() {
        val messages = compileExpectingError(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.QueryColumn

            @QueryColumn(field = "titleFolded", type = String::class)
            @QueryColumn(field = "titleFolded", type = String::class)
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>
            """.trimIndent(),
        )
        assertTrue("Query column field 'titleFolded' is declared more than once on Movie." in messages) { messages }
    }

    @Test
    fun `a query column field named like a component fails compilation`() {
        val messages = compileExpectingError(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.QueryColumn

            @QueryColumn(field = "title", type = String::class)
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>
            """.trimIndent(),
        )
        assertTrue("Query column field 'title' on Movie has the name of a component." in messages) { messages }
    }

    @Test
    fun `an empty query column field fails compilation`() {
        val messages = compileExpectingError(
            """
            package com.example

            import st.orm.Entity
            import st.orm.PK
            import st.orm.QueryColumn

            @QueryColumn(field = "", type = String::class)
            data class Movie(@PK val id: Int = 0, val title: String) : Entity<Int>
            """.trimIndent(),
        )
        assertTrue("Query column on Movie declares an empty field." in messages) { messages }
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

    @Test
    fun `a converted data class property is a column`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Convert
            import st.orm.Converter
            import st.orm.GenerateMetamodel

            @GenerateMetamodel
            data class Owner(val name: String, @Convert(converter = StreetConverter::class) val street: Street)

            data class Street(val value: String)

            class StreetConverter : Converter<String, Street> {
                override fun toDatabase(value: Street?): String? = value?.value
                override fun fromDatabase(dbValue: String?): Street? = dbValue?.let { Street(it) }
            }
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("OwnerMetamodel.kt")
        assertTrue("<T, Street, Street>" in metamodel) {
            "a converted property is a column of its declared type:\n$metamodel"
        }
        assertTrue("StreetMetamodel.kt" !in compilation.generatedFileNames()) {
            "a converted data class gets no metamodel of its own: ${compilation.generatedFileNames()}"
        }
    }

    @Test
    fun `a converted property with conversion disabled stays an inline record`() {
        val compilation = compile(
            """
            package com.example

            import st.orm.Convert
            import st.orm.GenerateMetamodel

            @GenerateMetamodel
            data class Owner(val name: String, @Convert(disableConversion = true) val address: Address)

            data class Address(val street: String, val city: String)
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("OwnerMetamodel.kt")
        assertTrue("AddressMetamodel<T>" in metamodel) {
            "disabled conversion leaves the data class inline:\n$metamodel"
        }
    }

    @Test
    fun `a property of a default converted type is a column`() {
        val compilation = compile(
            """
            package com.example

            import java.math.BigDecimal
            import st.orm.Convert
            import st.orm.Converter
            import st.orm.DefaultConverter
            import st.orm.GenerateMetamodel

            @GenerateMetamodel
            data class Product(val name: String, val price: Money, @Convert(disableConversion = true) val listPrice: Money)

            data class Money(val amount: BigDecimal)

            @DefaultConverter
            class MoneyConverter : Converter<BigDecimal, Money> {
                override fun toDatabase(value: Money?): BigDecimal? = value?.amount
                override fun fromDatabase(dbValue: BigDecimal?): Money? = dbValue?.let { Money(it) }
            }
            """.trimIndent(),
        )
        val metamodel = compilation.generatedSource("ProductMetamodel.kt")
        assertTrue(Regex("val price: Abstract\\w*Metamodel<T, Money, Money>").containsMatchIn(metamodel)) {
            "a property of a default-converted type is a column:\n$metamodel"
        }
        assertTrue("val listPrice: MoneyMetamodel<T>" in metamodel) {
            "disabled conversion keeps the data class inline:\n$metamodel"
        }
    }
}
