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

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier

class TypeIndexProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val indexEntries: MutableMap<String, MutableSet<String>> =
        INDEXED_TYPES.associateWith { linkedSetOf<String>() }.toMutableMap()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getAllFiles().forEach { file ->
            file.declarations
                .filterIsInstance<KSClassDeclaration>()
                .forEach { clazz ->
                    val fqName = clazz.qualifiedName?.asString() ?: return@forEach
                    INDEXED_SUPERTYPES.forEach { typeFqName ->
                        // A sealed Data interface is a type of its own: a sealed entity, or the target of a
                        // polymorphic foreign key, which the runtime and the native-image feature find through
                        // the index.
                        val allowInterfaces = typeFqName in INDEXED_INTERFACE_TYPES ||
                            (typeFqName == DATA_TYPE && Modifier.SEALED in clazz.modifiers)
                        if (clazz.isSubtypeOfFqName(typeFqName, allowInterfaces)) {
                            indexEntries.getValue(typeFqName) += fqName
                        }
                    }
                    // A class outside the entity graph that the runtime constructs, such as the result row of a
                    // custom query, is indexed by its annotation; a Data type is listed in the Data index only.
                    if (fqName !in indexEntries.getValue(DATA_TYPE) &&
                        clazz.classKind == ClassKind.CLASS &&
                        clazz.annotations.any {
                            it.annotationType.resolve().declaration.qualifiedName?.asString() == GENERATE_METAMODEL_TYPE
                        }
                    ) {
                        indexEntries.getValue(GENERATE_METAMODEL_TYPE) += fqName
                    }
                }
        }
        return emptyList()
    }

    override fun finish() {
        indexEntries.forEach { (typeFqName, lines) ->
            writeIndex(typeFqName, lines)
        }
    }

    private fun writeIndex(typeFqName: String, lines: Set<String>) {
        if (lines.isEmpty()) return
        val file = codeGenerator.createNewFile(
            Dependencies.ALL_FILES,
            INDEX_PACKAGE,
            typeFqName,
            "idx",
        )
        file.bufferedWriter().use { out ->
            lines.sorted().forEach { fqcn ->
                out.appendLine(fqcn)
            }
        }
    }

    companion object {
        private const val INDEX_PACKAGE = "META-INF.storm"

        private const val DATA_TYPE = "st.orm.Data"

        private val INDEXED_CLASS_TYPES = setOf(
            DATA_TYPE,
            "st.orm.Converter",
        )

        private val INDEXED_INTERFACE_TYPES = setOf(
            "st.orm.repository.Repository",
        )

        private const val GENERATE_METAMODEL_TYPE = "st.orm.GenerateMetamodel"

        private val INDEXED_SUPERTYPES = INDEXED_CLASS_TYPES + INDEXED_INTERFACE_TYPES

        private val INDEXED_TYPES = INDEXED_SUPERTYPES + GENERATE_METAMODEL_TYPE
    }
}

private fun KSClassDeclaration.isSubtypeOfFqName(targetFqName: String, allowInterfaces: Boolean): Boolean {
    when (classKind) {
        ClassKind.CLASS -> {} // Always allowed.
        ClassKind.INTERFACE -> if (!allowInterfaces) return false
        else -> return false
    }
    val selfFqName = qualifiedName?.asString() ?: return false
    if (selfFqName == targetFqName) return false
    return getAllSuperTypes().any { superType ->
        superType.declaration.qualifiedName?.asString() == targetFqName
    }
}
