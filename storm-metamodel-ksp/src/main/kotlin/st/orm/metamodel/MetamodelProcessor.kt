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
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.OutputStreamWriter

/**
 * KSP processor for generating metamodel classes and interfaces for Kotlin data classes.
 *
 * Nullable chain semantics (generated *NullableMetamodel* variants):
 * - We always generate:
 *     - ${ClassName}Metamodel<T>          : normal (non-null root record)
 *     - ${ClassName}NullableMetamodel<T>  : nullable chain, where all leaf getValue() values are nullable.
 * - If a parent record in the graph can be null, all metamodels “down the line” must return nullable values from getValue().
 * - In a ${Parent}NullableMetamodel, nested record metamodel fields must be ${Child}NullableMetamodel (including FKs).
 *
 * Root record nullability:
 * - ${ClassName}Metamodel: getter returns non-null ${ClassName} and AbstractMetamodel V is non-null ${ClassName}.
 * - ${ClassName}NullableMetamodel: getter returns ${ClassName}? and AbstractMetamodel V is ${ClassName}?.
 *
 * @since 1.7
 */
class MetamodelProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    /**
     * Track classes we’ve already generated metamodel classes for.
     */
    private val processedClasses = mutableSetOf<String>()

    /**
     * Track classes we’ve already generated metamodel interfaces for.
     */
    private val processedInterfaces = mutableSetOf<String>()

    /**
     * Track classes we’ve already expanded (walked referenced data-class properties) for.
     * Prevents infinite recursion on cyclic graphs.
     */
    private val expandedReferencedTypes = mutableSetOf<String>()

    companion object {
        private const val GENERATE_METAMODEL = "st.orm.GenerateMetamodel"
        private const val DATA = "st.orm.Data"
        private const val REF = "st.orm.Ref"
        private const val FOREIGN_KEY = "st.orm.FK"
        private const val PRIMARY_KEY = "st.orm.PK"
        private const val UNIQUE_KEY = "st.orm.UK"

        private const val K_BOOLEAN = "kotlin.Boolean"
        private const val K_BYTE = "kotlin.Byte"
        private const val K_SHORT = "kotlin.Short"
        private const val K_INT = "kotlin.Int"
        private const val K_LONG = "kotlin.Long"
        private const val K_CHAR = "kotlin.Char"
        private const val K_FLOAT = "kotlin.Float"
        private const val K_DOUBLE = "kotlin.Double"

        /**
         * Kotlin warns on identity checks (===) for Java "value-based" types (for example java.time.Instant).
         * These types are not meant to have stable identity semantics, so we emit == instead.
         *
         * This list is intentionally small and can be extended when new warnings show up.
         */
        private val VALUE_BASED_QNS: Set<String> = setOf(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.Duration",
            "java.time.Period",
            "java.util.Optional",
            "java.util.OptionalInt",
            "java.util.OptionalLong",
            "java.util.OptionalDouble",
        )

        private const val JDK_VALUE_BASED_ANNOTATION = "jdk.internal.ValueBased"

        private val KOTLIN_PRIMITIVE_QNS: Set<String> = setOf(
            K_BOOLEAN,
            K_BYTE,
            K_SHORT,
            K_INT,
            K_LONG,
            K_CHAR,
            K_FLOAT,
            K_DOUBLE,
        )
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("Storm Metamodel KSP is running.")
        val deferred = mutableListOf<KSAnnotated>()
        val symbols = resolver.getSymbolsWithAnnotation(GENERATE_METAMODEL)
            .plus(
                resolver.getAllFiles()
                    .flatMap { it.declarations }
                    .filterIsInstance<KSClassDeclaration>()
                    .filter { it.implementsInterface(DATA) },
            )
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.isDataClass() }

        symbols.forEach { clazz ->
            if (!clazz.validate()) {
                deferred.add(clazz)
            } else {
                try {
                    generateMetamodelArtifacts(clazz, resolver)
                } catch (e: Exception) {
                    logger.error(
                        "Failed to process metamodel for ${clazz.qualifiedName?.asString()}: ${e.message}\n" +
                            e.stackTraceToString(),
                        clazz,
                    )
                    throw e
                }
            }
        }

        // Also process sealed interfaces that implement Data and have declared properties.
        resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .filter { it.modifiers.contains(Modifier.SEALED) }
            .filter { it.implementsInterface(DATA) }
            .filter { it.getDeclaredProperties().any() }
            .forEach { sealedInterface ->
                if (!sealedInterface.validate()) {
                    deferred.add(sealedInterface)
                } else {
                    try {
                        generateMetamodelArtifacts(sealedInterface, resolver)
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to process metamodel for ${sealedInterface.qualifiedName?.asString()}: ${e.message}\n" +
                                e.stackTraceToString(),
                            sealedInterface,
                        )
                        throw e
                    }
                }
            }

        return deferred
    }

    private fun KSClassDeclaration.isDataClass(): Boolean = modifiers.contains(Modifier.DATA)

    private fun KSClassDeclaration.hasAnnotation(annotationQn: String): Boolean = annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == annotationQn }

    private fun isSealedInterface(classDeclaration: KSClassDeclaration): Boolean = classDeclaration.classKind == ClassKind.INTERFACE && classDeclaration.modifiers.contains(Modifier.SEALED)

    /**
     * Returns the properties to include in the metamodel for the given class declaration.
     * For sealed interfaces, only declared properties are included.
     * For data classes, all properties (including inherited) are included.
     */
    private fun getModelProperties(classDeclaration: KSClassDeclaration): Sequence<KSPropertyDeclaration> = if (isSealedInterface(classDeclaration)) {
        classDeclaration.getDeclaredProperties()
    } else {
        classDeclaration.getAllProperties()
    }

    private fun KSClassDeclaration.implementsInterface(interfaceName: String): Boolean = try {
        getAllSuperTypes().any { superType ->
            superType.declaration.qualifiedName?.asString() == interfaceName
        }
    } catch (e: Exception) {
        logger.warn("Error checking interface implementation for ${qualifiedName?.asString()}: ${e.message}")
        false
    }

    private fun KSTypeReference.isNestedDataClass(): Boolean {
        return try {
            val decl = resolve().declaration as? KSClassDeclaration ?: return false
            decl.modifiers.contains(Modifier.DATA) && decl.parentDeclaration is KSClassDeclaration
        } catch (e: Exception) {
            logger.warn("Error checking nested data class: ${e.message}")
            false
        }
    }

    private fun KSTypeReference.isDataClass(): Boolean {
        return try {
            val decl = resolve().declaration as? KSClassDeclaration ?: return false
            decl.modifiers.contains(Modifier.DATA)
        } catch (e: Exception) {
            logger.warn("Error checking data class: ${e.message}")
            false
        }
    }

    /**
     * Unwrap Ref<X> -> X for the metamodel generic E.
     */
    private fun unwrapRef(typeReference: KSTypeReference): KSTypeReference {
        val resolved = typeReference.resolve()
        val qn = resolved.declaration.qualifiedName?.asString() ?: return typeReference
        if (qn != REF) return typeReference
        val arg = resolved.arguments.firstOrNull()?.type
        return arg ?: typeReference
    }

    private fun getSimpleTypeName(typeReference: KSTypeReference, packageName: String): String {
        return try {
            val effective = unwrapRef(typeReference)
            val resolvedType = effective.resolve()
            val decl = resolvedType.declaration
            val qualifiedName = decl.qualifiedName?.asString() ?: return decl.simpleName.asString()
            if (qualifiedName.startsWith(packageName) && packageName.isNotEmpty()) {
                val simpleName = qualifiedName.substring(packageName.length + 1)
                if (!simpleName.contains(".")) return simpleName
            }
            qualifiedName
        } catch (e: Exception) {
            logger.error("Error getting simple type name: ${e.message}")
            "Object"
        }
    }

    private fun getKotlinTypeName(typeReference: KSTypeReference, currentPackage: String): String {
        return try {
            val effective = unwrapRef(typeReference)
            val resolvedType = effective.resolve()
            val decl = resolvedType.declaration
            val qualifiedName = decl.qualifiedName?.asString() ?: return "Any"

            val baseName = when (qualifiedName) {
                "kotlin.String", "java.lang.String" -> "String"
                "kotlin.Int", "java.lang.Integer" -> "Int"
                "kotlin.Long", "java.lang.Long" -> "Long"
                "kotlin.Short", "java.lang.Short" -> "Short"
                "kotlin.Byte", "java.lang.Byte" -> "Byte"
                "kotlin.Boolean", "java.lang.Boolean" -> "Boolean"
                "kotlin.Double", "java.lang.Double" -> "Double"
                "kotlin.Float", "java.lang.Float" -> "Float"
                "kotlin.Char", "java.lang.Character" -> "Char"
                "java.lang.Object" -> "Any"
                else -> {
                    if (qualifiedName.startsWith(currentPackage) && currentPackage.isNotEmpty()) {
                        val simpleName = qualifiedName.substring(currentPackage.length + 1)
                        if (!simpleName.contains(".")) simpleName else qualifiedName
                    } else {
                        qualifiedName
                    }
                }
            }
            val typeArguments = resolvedType.arguments
            if (typeArguments.isNotEmpty() && typeArguments.all { it.variance != Variance.STAR }) {
                val wildcards = typeArguments.joinToString(", ") { "*" }
                "$baseName<$wildcards>"
            } else {
                baseName
            }
        } catch (e: Exception) {
            logger.error(
                "Error getting Kotlin type name for $typeReference: ${e.message}\n${e.stackTraceToString()}",
            )
            "Any"
        }
    }

    /**
     * Kotlin value type name for actual runtime value returned by getValue() for fields.
     *
     * Unlike getKotlinTypeName(..), this does NOT unwrap Ref<X> -> X.
     * It renders Ref as `st.orm.Ref` so generated code needs no import.
     */
    private fun getKotlinValueTypeName(typeReference: KSTypeReference, currentPackage: String): String {
        fun render(type: KSType): String {
            val decl = type.declaration
            val qualifiedName = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
            val baseName = when (qualifiedName) {
                "st.orm.Ref" -> "st.orm.Ref"
                "kotlin.String", "java.lang.String" -> "String"
                "kotlin.Int", "java.lang.Integer" -> "Int"
                "kotlin.Long", "java.lang.Long" -> "Long"
                "kotlin.Short", "java.lang.Short" -> "Short"
                "kotlin.Byte", "java.lang.Byte" -> "Byte"
                "kotlin.Boolean", "java.lang.Boolean" -> "Boolean"
                "kotlin.Double", "java.lang.Double" -> "Double"
                "kotlin.Float", "java.lang.Float" -> "Float"
                "kotlin.Char", "java.lang.Character" -> "Char"
                "java.lang.Object" -> "Any"
                else -> {
                    if (qualifiedName.startsWith(currentPackage) && currentPackage.isNotEmpty()) {
                        val simpleName = qualifiedName.substring(currentPackage.length + 1)
                        if (!simpleName.contains(".")) simpleName else qualifiedName
                    } else {
                        qualifiedName
                    }
                }
            }
            val args = type.arguments
            val typeArgs = if (args.isNotEmpty()) {
                args.joinToString(", ", prefix = "<", postfix = ">") { arg ->
                    val t = arg.type?.resolve()
                    when {
                        arg.variance == Variance.STAR || t == null -> "*"
                        else -> render(t)
                    }
                }
            } else {
                ""
            }
            val nullableSuffix = if (type.isMarkedNullable) "?" else ""
            return baseName + typeArgs + nullableSuffix
        }
        return render(typeReference.resolve())
    }

    private fun getJavaTypeName(typeReference: KSTypeReference, currentPackage: String): String {
        return try {
            val effective = unwrapRef(typeReference)
            val resolvedType = effective.resolve()
            val decl = resolvedType.declaration
            val qualifiedName = decl.qualifiedName?.asString() ?: return "java.lang.Object::class.java"
            when (qualifiedName) {
                "kotlin.String", "java.lang.String" -> "String::class.java"
                "kotlin.Int", "java.lang.Integer" -> "Int::class.javaObjectType"
                "kotlin.Long", "java.lang.Long" -> "Long::class.javaObjectType"
                "kotlin.Short", "java.lang.Short" -> "Short::class.javaObjectType"
                "kotlin.Byte", "java.lang.Byte" -> "Byte::class.javaObjectType"
                "kotlin.Boolean", "java.lang.Boolean" -> "Boolean::class.javaObjectType"
                "kotlin.Double", "java.lang.Double" -> "Double::class.javaObjectType"
                "kotlin.Float", "java.lang.Float" -> "Float::class.javaObjectType"
                "kotlin.Char", "java.lang.Character" -> "Char::class.javaObjectType"
                "kotlin.collections.List" -> "List::class.java"
                "kotlin.collections.Set" -> "Set::class.java"
                "kotlin.collections.Map" -> "Map::class.java"
                else -> {
                    if (qualifiedName.startsWith(currentPackage) && currentPackage.isNotEmpty()) {
                        val simpleName = qualifiedName.substring(currentPackage.length + 1)
                        if (!simpleName.contains(".")) "$simpleName::class.java" else "$qualifiedName::class.java"
                    } else {
                        "$qualifiedName::class.java"
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(
                "Error getting Java type name for $typeReference: ${e.message}\n${e.stackTraceToString()}",
            )
            "java.lang.Object::class.java"
        }
    }

    private fun isDataType(property: KSPropertyDeclaration): Boolean {
        return try {
            if (isDataType(property.type.resolve())) return true
            val parentClass = property.parentDeclaration as? KSClassDeclaration
            if (parentClass != null && parentClass.modifiers.contains(Modifier.DATA)) {
                val ctor = parentClass.primaryConstructor
                val param = ctor?.parameters?.find { it.name?.asString() == property.simpleName.asString() }
                if (param != null) {
                    return isDataType(param.type.resolve())
                }
            }
            false
        } catch (e: Exception) {
            logger.warn("Error checking data type for ${property.simpleName.asString()}: ${e.message}")
            false
        }
    }

    private fun isDataType(type: KSType): Boolean {
        val decl = type.declaration as? KSClassDeclaration ?: return false
        val qn = decl.qualifiedName?.asString() ?: return false

        if (qn == DATA) return true

        // Covers: class X : Data, interface Y : Data, etc.
        return decl.superTypes
            .mapNotNull { st -> runCatching { st.resolve() }.getOrNull() }
            .any { st ->
                val stDecl = st.declaration as? KSClassDeclaration
                stDecl?.qualifiedName?.asString() == DATA || isDataType(st)
            }
    }

    private fun hasAnnotationOrMeta(annotated: KSAnnotated, annotationQn: String): Boolean {
        for (ann in annotated.annotations) {
            val annDecl = ann.annotationType.resolve().declaration as? KSClassDeclaration ?: continue
            val annQn = annDecl.qualifiedName?.asString()
            if (annQn == annotationQn) return true

            if (annDecl.annotations.any { meta ->
                    meta.annotationType.resolve().declaration.qualifiedName?.asString() == annotationQn
                }
            ) {
                return true
            }
        }
        return false
    }

    private fun isPrimaryKey(prop: KSPropertyDeclaration): Boolean {
        if (hasAnnotationOrMeta(prop, PRIMARY_KEY)) return true

        val parent = prop.parentDeclaration as? KSClassDeclaration ?: return false

        // Check constructor parameter annotations (data classes).
        val ctor = parent.primaryConstructor
        if (ctor != null) {
            val param = ctor.parameters.firstOrNull { it.name?.asString() == prop.simpleName.asString() }
            if (param != null && hasAnnotationOrMeta(param, PRIMARY_KEY)) return true
        }

        // Fallback for sealed interface properties: delegate to first sealed subclass.
        if (isSealedInterface(parent)) {
            return parent.getSealedSubclasses().firstOrNull()?.let { subclass ->
                val matchingProp = subclass.getAllProperties()
                    .firstOrNull { it.simpleName.asString() == prop.simpleName.asString() }
                matchingProp != null && isPrimaryKey(matchingProp)
            } ?: false
        }

        return false
    }

    private fun isUniqueField(prop: KSPropertyDeclaration): Boolean {
        if (hasAnnotationOrMeta(prop, UNIQUE_KEY)) return true

        val parent = prop.parentDeclaration as? KSClassDeclaration ?: return false

        val ctor = parent.primaryConstructor
        if (ctor != null) {
            val param = ctor.parameters.firstOrNull { it.name?.asString() == prop.simpleName.asString() }
            if (param != null && hasAnnotationOrMeta(param, UNIQUE_KEY)) return true
        }

        // Fallback for sealed interface properties: delegate to first sealed subclass.
        if (isSealedInterface(parent)) {
            return parent.getSealedSubclasses().firstOrNull()?.let { subclass ->
                val matchingProp = subclass.getAllProperties()
                    .firstOrNull { it.simpleName.asString() == prop.simpleName.asString() }
                matchingProp != null && isUniqueField(matchingProp)
            } ?: false
        }

        return false
    }

    private fun getNullsDistinct(prop: KSPropertyDeclaration): Boolean {
        // Check property annotations.
        for (ann in prop.annotations) {
            val annDecl = ann.annotationType.resolve().declaration as? KSClassDeclaration ?: continue
            val annQn = annDecl.qualifiedName?.asString()
            if (annQn == UNIQUE_KEY) {
                val argument = ann.arguments.firstOrNull { it.name?.asString() == "nullsDistinct" }
                return argument?.value as? Boolean ?: true
            }
        }
        // Check constructor parameter annotations.
        val parent = prop.parentDeclaration as? KSClassDeclaration ?: return true
        val ctor = parent.primaryConstructor
        if (ctor != null) {
            val param = ctor.parameters.firstOrNull { it.name?.asString() == prop.simpleName.asString() }
            if (param != null) {
                for (ann in param.annotations) {
                    val annDecl = ann.annotationType.resolve().declaration as? KSClassDeclaration ?: continue
                    val annQn = annDecl.qualifiedName?.asString()
                    if (annQn == UNIQUE_KEY) {
                        val argument = ann.arguments.firstOrNull { it.name?.asString() == "nullsDistinct" }
                        return argument?.value as? Boolean ?: true
                    }
                }
            }
        }
        // Fallback for sealed interface properties: delegate to first sealed subclass.
        if (isSealedInterface(parent)) {
            return parent.getSealedSubclasses().firstOrNull()?.let { subclass ->
                val matchingProp = subclass.getAllProperties()
                    .firstOrNull { it.simpleName.asString() == prop.simpleName.asString() }
                if (matchingProp != null) getNullsDistinct(matchingProp) else true
            } ?: true
        }
        return true
    }

    /**
     * Returns true if the field should be treated as a unique key for metamodel generation.
     */
    private fun isEffectivelyUniqueField(prop: KSPropertyDeclaration): Boolean = isUniqueField(prop)

    private fun isEffectivelyNullable(prop: KSPropertyDeclaration): Boolean {
        // PK fields are always non-null.
        if (isPrimaryKey(prop)) return false
        // Primitive types are never null.
        if (primitiveKind(prop.type) != null) return false
        // Check if marked nullable in Kotlin.
        return prop.type.resolve().isMarkedNullable
    }

    /**
     * Returns true if the given data class has at least one leaf field that is nullable.
     * Recursively walks inline sub-records. Used to determine if a compound key has nullable constituents.
     */
    private fun hasNullableLeaf(classDeclaration: KSClassDeclaration): Boolean {
        classDeclaration.getAllProperties().forEach { prop ->
            val typeRef = prop.type
            if (typeRef.isDataClass()) {
                if (typeRef.isNestedDataClass()) return@forEach
                if (!isDataType(prop)) {
                    // Inline sub-record: recurse into it.
                    val decl = typeRef.resolve().declaration as? KSClassDeclaration
                    if (decl != null && hasNullableLeaf(decl)) return true
                }
                // FK (Data) fields: not part of compound key leaves; skip.
            } else {
                // Scalar leaf: nullable if not primitive, not @PK, not marked nullable.
                if (isEffectivelyNullable(prop)) return true
            }
        }
        return false
    }

    private fun findPrimaryKeyProperty(clazz: KSClassDeclaration): KSPropertyDeclaration? = getModelProperties(clazz).firstOrNull { isPrimaryKey(it) }

    private enum class PrimitiveKind { BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE }

    private fun primitiveKind(typeRef: KSTypeReference): PrimitiveKind? {
        val t = typeRef.resolve()
        if (t.isMarkedNullable) return null

        val qn = t.declaration.qualifiedName?.asString() ?: return null
        return when (qn) {
            K_BOOLEAN -> PrimitiveKind.BOOLEAN
            K_BYTE -> PrimitiveKind.BYTE
            K_SHORT -> PrimitiveKind.SHORT
            K_INT -> PrimitiveKind.INT
            K_LONG -> PrimitiveKind.LONG
            K_CHAR -> PrimitiveKind.CHAR
            K_FLOAT -> PrimitiveKind.FLOAT
            K_DOUBLE -> PrimitiveKind.DOUBLE
            else -> null
        }
    }

    private fun isNullableKotlinPrimitive(typeRef: KSTypeReference): Boolean {
        val t = typeRef.resolve()
        if (!t.isMarkedNullable) return false
        val qn = t.declaration.qualifiedName?.asString() ?: return false
        return qn in KOTLIN_PRIMITIVE_QNS
    }

    private fun isValueBasedType(typeRef: KSTypeReference): Boolean {
        val decl = typeRef.resolve().declaration as? KSClassDeclaration ?: return false
        val qn = decl.qualifiedName?.asString() ?: return false
        if (qn in VALUE_BASED_QNS) return true

        return decl.annotations.any { ann ->
            ann.annotationType.resolve().declaration.qualifiedName?.asString() == JDK_VALUE_BASED_ANNOTATION
        }
    }

    private fun ensureNullable(typeName: String): String = if (typeName.endsWith("?")) typeName else "$typeName?"

    private fun sameExpr(left: String, right: String, typeRef: KSTypeReference, forceNullableChain: Boolean): String {
        val pk = primitiveKind(typeRef)
        return if (forceNullableChain && pk != null) {
            when (pk) {
                PrimitiveKind.FLOAT -> "($left)?.toBits() == ($right)?.toBits()"
                PrimitiveKind.DOUBLE -> "($left)?.toBits() == ($right)?.toBits()"
                PrimitiveKind.BOOLEAN,
                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.INT,
                PrimitiveKind.LONG,
                PrimitiveKind.CHAR,
                -> "$left == $right"
            }
        } else {
            when (pk) {
                PrimitiveKind.FLOAT -> "($left).toBits() == ($right).toBits()"
                PrimitiveKind.DOUBLE -> "($left).toBits() == ($right).toBits()"
                PrimitiveKind.BOOLEAN,
                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.INT,
                PrimitiveKind.LONG,
                PrimitiveKind.CHAR,
                -> "$left == $right"
                null -> "$left == $right"
            }
        }
    }

    private fun identicalExpr(left: String, right: String, typeRef: KSTypeReference, forceNullableChain: Boolean): String {
        val pk = primitiveKind(typeRef)
        return if (forceNullableChain && pk != null) {
            when (pk) {
                PrimitiveKind.FLOAT -> "($left)?.toBits() == ($right)?.toBits()"
                PrimitiveKind.DOUBLE -> "($left)?.toBits() == ($right)?.toBits()"
                PrimitiveKind.BOOLEAN,
                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.INT,
                PrimitiveKind.LONG,
                PrimitiveKind.CHAR,
                -> "$left == $right"
            }
        } else {
            when (pk) {
                PrimitiveKind.FLOAT -> "($left).toBits() == ($right).toBits()"
                PrimitiveKind.DOUBLE -> "($left).toBits() == ($right).toBits()"
                PrimitiveKind.BOOLEAN,
                PrimitiveKind.BYTE,
                PrimitiveKind.SHORT,
                PrimitiveKind.INT,
                PrimitiveKind.LONG,
                PrimitiveKind.CHAR,
                -> "$left == $right"
                null -> {
                    if (isNullableKotlinPrimitive(typeRef)) return "$left == $right"
                    if (isValueBasedType(typeRef)) "$left == $right" else "$left === $right"
                }
            }
        }
    }

    private fun buildInterfaceFields(
        classDeclaration: KSClassDeclaration,
        packageName: String,
        resolver: Resolver,
    ): String {
        val builder = StringBuilder()
        val className = classDeclaration.simpleName.asString()
        val modelRef = "${className}Metamodel.instance<$className>()"
        getModelProperties(classDeclaration).forEach { prop ->
            val fieldName = prop.simpleName.asString()
            val typeRef = prop.type
            val propNullable = typeRef.resolve().isMarkedNullable
            if (typeRef.isDataClass()) {
                if (typeRef.isNestedDataClass()) return@forEach

                val simpleTypeName = getSimpleTypeName(typeRef, packageName)
                val referencedDecl = typeRef.resolve().declaration as? KSClassDeclaration
                if (referencedDecl != null) generateMetamodelArtifacts(referencedDecl, resolver)

                val childMetaClass = if (propNullable) "${simpleTypeName}NullableMetamodel" else "${simpleTypeName}Metamodel"
                val childMetaType = "$childMetaClass<$className>"
                builder.append("        /** Represents the $className.$fieldName record. */\n")
                builder.append(
                    "        val $fieldName: $childMetaType = " +
                        "$modelRef.$fieldName\n",
                )
            } else {
                val kotlinTypeName = getKotlinTypeName(typeRef, packageName) // E (unwrap Ref)
                val valueKotlinTypeName = getKotlinValueTypeName(typeRef, packageName) // V (keep Ref)
                val unique = isEffectivelyUniqueField(prop)
                val baseClass = if (unique) "AbstractKeyMetamodel" else "AbstractMetamodel"
                builder.append("        /** Represents the $className.$fieldName field. */\n")
                builder.append(
                    "        val $fieldName: $baseClass<$className, $kotlinTypeName, $valueKotlinTypeName> = " +
                        "$modelRef.$fieldName\n",
                )
            }
        }

        if (builder.isNotEmpty()) builder.setLength(builder.length - 1)
        return builder.toString()
    }

    private fun buildClassFields(
        classDeclaration: KSClassDeclaration,
        packageName: String,
        forceNullableChain: Boolean,
    ): String {
        val builder = StringBuilder()
        getModelProperties(classDeclaration).forEach { prop ->
            val fieldName = prop.simpleName.asString()
            val typeRef = prop.type
            val propNullable = typeRef.resolve().isMarkedNullable
            if (typeRef.isDataClass()) {
                if (typeRef.isNestedDataClass()) return@forEach
                val simpleTypeName = getSimpleTypeName(typeRef, packageName)
                val childForceNullable = forceNullableChain || propNullable
                val childType = if (childForceNullable) "${simpleTypeName}NullableMetamodel" else "${simpleTypeName}Metamodel"
                builder.append("    val $fieldName: $childType<T>\n")
            } else {
                val kotlinTypeName = getKotlinTypeName(typeRef, packageName) // E
                val valueKotlinTypeName = getKotlinValueTypeName(typeRef, packageName) // V
                val v = if (forceNullableChain) ensureNullable(valueKotlinTypeName) else valueKotlinTypeName
                val unique = isEffectivelyUniqueField(prop)
                val isData = classDeclaration.implementsInterface(DATA)
                val baseClass = if (!isData || unique) "AbstractKeyMetamodel" else "AbstractMetamodel"
                builder.append("    val $fieldName: $baseClass<T, $kotlinTypeName, $v>\n")
            }
        }
        return builder.toString()
    }

    private fun initClassFields(
        classDeclaration: KSClassDeclaration,
        packageName: String,
        metaClassName: String,
        forceNullableChain: Boolean,
    ): String {
        val builder = StringBuilder()

        getModelProperties(classDeclaration).forEach { prop ->
            val fieldName = prop.simpleName.asString()
            val typeRef = prop.type
            val propNullable = typeRef.resolve().isMarkedNullable

            if (typeRef.isDataClass()) {
                if (typeRef.isNestedDataClass()) return@forEach

                val simpleTypeName = getSimpleTypeName(typeRef, packageName)
                val isChildData = isDataType(prop)
                // Validate: @PK, @FK, and @UK are not supported on inline record fields.
                if (!classDeclaration.implementsInterface(DATA)) {
                    if (hasAnnotationOrMeta(prop, PRIMARY_KEY)) {
                        logger.error(
                            "@PK is not supported on inline record fields. " +
                                "Primary keys are only supported on top-level entity fields.",
                            prop,
                        )
                    }
                    if (hasAnnotationOrMeta(prop, FOREIGN_KEY)) {
                        logger.error(
                            "@FK is not supported on inline record fields. " +
                                "Foreign keys are only supported on top-level entity fields.",
                            prop,
                        )
                    }
                    if (hasAnnotationOrMeta(prop, UNIQUE_KEY)) {
                        logger.error(
                            "@UK is not supported on inline record fields. " +
                                "Unique keys are only supported on top-level entity fields.",
                            prop,
                        )
                    }
                }
                val inlineFlag = if (isChildData) "false" else "true"
                val childForceNullable = forceNullableChain || propNullable
                val childMetaClassName =
                    if (childForceNullable) "${simpleTypeName}NullableMetamodel" else "${simpleTypeName}Metamodel"

                val effectiveGetterExpr = if (forceNullableChain) {
                    "{ t: T -> this@$metaClassName.getValue(t)?.$fieldName }"
                } else {
                    "{ t: T -> this@$metaClassName.getValue(t).$fieldName }"
                }
                if (!isChildData && isEffectivelyUniqueField(prop)) {
                    val nullsDistinct = getNullsDistinct(prop)
                    val referencedDecl = typeRef.resolve().declaration as? KSClassDeclaration
                    if (nullsDistinct && referencedDecl != null && hasNullableLeaf(referencedDecl)) {
                        logger.warn(
                            "Unique key field '$fieldName' has nullable constituent fields. " +
                                "Scrolling (scroll/scrollAfter/scrollBefore) will be rejected at runtime. " +
                                "Consider making constituent fields non-nullable, or setting @UK(nullsDistinct = false) " +
                                "if the database constraint prevents duplicate NULLs.",
                            prop,
                        )
                    }
                    builder.append(
                        "        $fieldName = $childMetaClassName(" +
                            "subPath, fieldBase + \"$fieldName\", $inlineFlag, this, " +
                            "$effectiveGetterExpr, $nullsDistinct)\n",
                    )
                } else if (!isChildData) {
                    // Inline (non-Data) record: getter must be inside parens (nullable param follows in constructor).
                    builder.append(
                        "        $fieldName = $childMetaClassName(" +
                            "subPath, fieldBase + \"$fieldName\", $inlineFlag, this, " +
                            "$effectiveGetterExpr)\n",
                    )
                } else {
                    // Data (FK) record: trailing lambda syntax still works.
                    builder.append(
                        "        $fieldName = $childMetaClassName(" +
                            "subPath, fieldBase + \"$fieldName\", $inlineFlag, this) $effectiveGetterExpr\n",
                    )
                }
            } else {
                val javaTypeName = getJavaTypeName(typeRef, packageName) // E (unwrap Ref)
                val kotlinTypeName = getKotlinTypeName(typeRef, packageName) // E (unwrap Ref)
                val valueKotlinTypeName = getKotlinValueTypeName(typeRef, packageName) // V (keep Ref)
                val v = if (forceNullableChain) ensureNullable(valueKotlinTypeName) else valueKotlinTypeName

                val readRecords =
                    "val ra = this@$metaClassName.getValue(a)\n" +
                        "                val rb = this@$metaClassName.getValue(b)\n"

                val leftValue = if (forceNullableChain) "ra?.$fieldName" else "ra.$fieldName"
                val rightValue = if (forceNullableChain) "rb?.$fieldName" else "rb.$fieldName"

                val isSameExpr = sameExpr(leftValue, rightValue, typeRef, forceNullableChain)
                val isIdenticalExpr = identicalExpr(leftValue, rightValue, typeRef, forceNullableChain)

                val getValueBody = if (forceNullableChain) {
                    "            override fun getValue(record: T): $v =\n" +
                        "                this@$metaClassName.getValue(record)?.$fieldName\n"
                } else {
                    "            override fun getValue(record: T): $v =\n" +
                        "                this@$metaClassName.getValue(record).$fieldName\n"
                }
                val unique = isEffectivelyUniqueField(prop)
                val isData = classDeclaration.implementsInterface(DATA)
                // Validate: @PK, @FK, and @UK are not supported on inline record fields.
                if (!isData) {
                    if (hasAnnotationOrMeta(prop, PRIMARY_KEY)) {
                        logger.error(
                            "@PK is not supported on inline record fields. " +
                                "Primary keys are only supported on top-level entity fields.",
                            prop,
                        )
                    }
                    if (hasAnnotationOrMeta(prop, FOREIGN_KEY)) {
                        logger.error(
                            "@FK is not supported on inline record fields. " +
                                "Foreign keys are only supported on top-level entity fields.",
                            prop,
                        )
                    }
                    if (hasAnnotationOrMeta(prop, UNIQUE_KEY)) {
                        logger.error(
                            "@UK is not supported on inline record fields. " +
                                "Unique keys are only supported on top-level entity fields.",
                            prop,
                        )
                    }
                }
                val effectivelyNullable = if (!isData) {
                    // Leaf of a compound key: report raw field nullability for runtime derivation.
                    isEffectivelyNullable(prop)
                } else if (unique) {
                    val nullable = isEffectivelyNullable(prop)
                    val nullsDistinct = getNullsDistinct(prop)
                    val result = nullable && nullsDistinct
                    if (result) {
                        logger.warn(
                            "Unique key field '$fieldName' is nullable. " +
                                "Scrolling (scroll/scrollAfter/scrollBefore) will be rejected at runtime. " +
                                "Consider making the field non-nullable, or setting @UK(nullsDistinct = false) " +
                                "if the database constraint prevents duplicate NULLs.",
                            prop,
                        )
                    }
                    result
                } else {
                    false
                }
                val baseClass = if (!isData || unique) "AbstractKeyMetamodel" else "AbstractMetamodel"
                val constructorArgs = if (!isData || unique) {
                    "$javaTypeName, subPath, fieldBase + \"$fieldName\", false, this, true, $effectivelyNullable"
                } else {
                    "$javaTypeName, subPath, fieldBase + \"$fieldName\", false, this"
                }
                builder.append(
                    "        $fieldName = object : $baseClass<T, $kotlinTypeName, $v>(" +
                        "$constructorArgs" +
                        ") {\n" +
                        getValueBody +
                        "\n" +
                        "            override fun isIdentical(a: T, b: T): Boolean {\n" +
                        "                $readRecords" +
                        "                return $isIdenticalExpr\n" +
                        "            }\n\n" +
                        "            override fun isSame(a: T, b: T): Boolean {\n" +
                        "                $readRecords" +
                        "                return $isSameExpr\n" +
                        "            }\n" +
                        "        }\n",
                )
            }
        }

        if (builder.isNotEmpty()) builder.setLength(builder.length - 1)
        return builder.toString()
    }

    /**
     * Ensure we also generate metamodels for referenced data classes when reached via metamodel class generation,
     * not only when building the "_" interface.
     */
    private fun generateReferencedDataClassMetamodels(
        classDeclaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
        getModelProperties(classDeclaration).forEach { prop ->
            val typeRef = prop.type

            if (!typeRef.isDataClass()) return@forEach
            if (typeRef.isNestedDataClass()) return@forEach

            val referencedDecl = typeRef.resolve().declaration as? KSClassDeclaration ?: return@forEach
            generateMetamodelArtifacts(referencedDecl, resolver)
        }
    }

    private fun generateMetamodelArtifacts(classDeclaration: KSClassDeclaration, resolver: Resolver) {
        val qualifiedName = classDeclaration.qualifiedName?.asString() ?: return

        // Always generate classes (both variants) once.
        if (processedClasses.add(qualifiedName)) {
            generateMetamodelClass(classDeclaration, forceNullableChain = false)
            generateMetamodelClass(classDeclaration, forceNullableChain = true)
        }

        // Only generate interface if this type implements Data.
        if (classDeclaration.implementsInterface(DATA) && processedInterfaces.add(qualifiedName)) {
            generateMetamodelInterface(classDeclaration, resolver)
        }

        // Recurse into referenced data classes only once per type to avoid cycles.
        if (expandedReferencedTypes.add(qualifiedName)) {
            generateReferencedDataClassMetamodels(classDeclaration, resolver)
        }
    }

    private fun generateMetamodelInterface(classDeclaration: KSClassDeclaration, resolver: Resolver) {
        val packageName = classDeclaration.packageName.asString()
        val className = classDeclaration.simpleName.asString()
        val metaInterfaceName = "${className}_"
        val containingFile = classDeclaration.containingFile
        val deps = if (containingFile != null) Dependencies(true, containingFile) else Dependencies(false)
        val file = codeGenerator.createNewFile(
            dependencies = deps,
            packageName = packageName,
            fileName = metaInterfaceName,
        )
        OutputStreamWriter(file).use { writer ->
            writer.write(
                """
                |package $packageName
                |
                |import st.orm.Metamodel
                |import st.orm.AbstractMetamodel
                |import st.orm.AbstractKeyMetamodel
                |import javax.annotation.processing.Generated
                |
                |/**
                | * Metamodel for $className.
                | */
                |@Suppress("ClassName")
                |@Generated("${this::class.java.name}")
                |interface $metaInterfaceName : Metamodel<$className, $className> {
                |    companion object {
                |${buildInterfaceFields(classDeclaration, packageName, resolver)}
                |    }
                |}
                """.trimMargin(),
            )
        }
    }

    private fun buildFlattenMethod(classDeclaration: KSClassDeclaration, metaClassName: String): String {
        var hasInlineSubRecords = false
        val fieldNames = mutableListOf<String>()
        val fieldIsInline = mutableListOf<Boolean>()

        getModelProperties(classDeclaration).forEach { prop ->
            val fieldName = prop.simpleName.asString()
            val typeRef = prop.type

            if (typeRef.isDataClass()) {
                if (typeRef.isNestedDataClass()) return@forEach
                val inline = !isDataType(prop)
                fieldNames.add(fieldName)
                fieldIsInline.add(inline)
                if (inline) hasInlineSubRecords = true
            } else {
                fieldNames.add(fieldName)
                fieldIsInline.add(false)
            }
        }

        val builder = StringBuilder()
        builder.append("    override fun flatten(): List<Metamodel<T, *>> {\n")

        if (!hasInlineSubRecords) {
            builder.append("        return listOf(")
            fieldNames.forEachIndexed { index, name ->
                if (index > 0) builder.append(", ")
                builder.append("this.$name")
            }
            builder.append(")\n")
        } else {
            builder.append("        return buildList {\n")
            fieldNames.forEachIndexed { index, name ->
                if (fieldIsInline[index]) {
                    builder.append("            addAll(this@$metaClassName.$name.flatten())\n")
                } else {
                    builder.append("            add(this@$metaClassName.$name)\n")
                }
            }
            builder.append("        }\n")
        }

        builder.append("    }")
        return builder.toString()
    }

    private fun generateMetamodelClass(classDeclaration: KSClassDeclaration, forceNullableChain: Boolean) {
        val packageName = classDeclaration.packageName.asString()
        val className = classDeclaration.simpleName.asString()
        val isDataRoot = classDeclaration.implementsInterface(DATA)
        val metaClassName = if (forceNullableChain) "${className}NullableMetamodel" else "${className}Metamodel"
        val recordGetterType = if (forceNullableChain) "$className?" else className
        val recordValueType = recordGetterType
        val abstractVType = recordValueType
        val pkProp = findPrimaryKeyProperty(classDeclaration)
        val isSameMethod = if (pkProp != null) {
            val pkName = pkProp.simpleName.asString()
            if (forceNullableChain) {
                """
                |    override fun isSame(a: T, b: T): Boolean {
                |        val ra = getter(a)
                |        val rb = getter(b)
                |        if (ra == null || rb == null) return ra == rb
                |        return ra.$pkName == rb.$pkName
                |    }
                """.trimMargin().trim('\n')
            } else {
                """
                |    override fun isSame(a: T, b: T): Boolean {
                |        val ra = getter(a)
                |        val rb = getter(b)
                |        return ra.$pkName == rb.$pkName
                |    }
                """.trimMargin().trim('\n')
            }
        } else {
            """
            |    override fun isSame(a: T, b: T): Boolean {
            |        return getter(a) == getter(b)
            |    }
            """.trimMargin().trim('\n')
        }
        val isIdenticalMethod = """
            |    override fun isIdentical(a: T, b: T): Boolean {
            |        return getter(a) === getter(b)
            |    }
        """.trimMargin().trim('\n')

        val containingFile = classDeclaration.containingFile
        val deps = if (containingFile != null) Dependencies(true, containingFile) else Dependencies(false)
        val file = codeGenerator.createNewFile(
            dependencies = deps,
            packageName = packageName,
            fileName = metaClassName,
        )
        val convenienceCtors = if (isDataRoot) {
            val rootGetterForNoArgCtor = "{ it as $className }"
            """
            |
            |    @Suppress("UNCHECKED_CAST")
            |    constructor() : this(
            |        "", "", false,
            |        Metamodel.root($className::class.java) as Metamodel<T, *>,
            |        $rootGetterForNoArgCtor
            |    )
            |
            |    constructor(field: String, parent: Metamodel<T, *>, getter: (T) -> $recordGetterType)
            |        : this("", field, false, parent, getter)
            |
            |    constructor(path: String, field: String, parent: Metamodel<T, *>, getter: (T) -> $recordGetterType)
            |        : this(path, field, false, parent, getter)
            """.trimMargin()
        } else {
            """
            |
            |    constructor(path: String, field: String, parent: Metamodel<T, *>, getter: (T) -> $recordGetterType)
            |        : this(path, field, false, parent, getter)
            """.trimMargin()
        }
        val flattenMethod = buildFlattenMethod(classDeclaration, metaClassName)
        val isNullableOverride = if (!isDataRoot) {
            """
            |    override fun isNullable(): Boolean {
            |        if (!super.isNullable()) return false
            |        return flatten().any { it is Metamodel.Key<*, *> && it.isNullable }
            |    }
            """.trimMargin().trim('\n')
        } else {
            ""
        }
        val baseAbstractClass = if (isDataRoot) "AbstractMetamodel" else "AbstractKeyMetamodel"
        val nullableCtorParam = if (isDataRoot) "" else ",\n                |    nullable: Boolean = false"
        val superCtorArgs = if (isDataRoot) {
            "$className::class.java, path, field, inline, parent"
        } else {
            "$className::class.java, path, field, inline, parent, !inline && field.isNotEmpty(), nullable"
        }
        val classFields = buildClassFields(classDeclaration, packageName, forceNullableChain)
        val methods = listOf(
            "    override fun getValue(record: T): $recordValueType = getter(record)",
            isIdenticalMethod,
            isSameMethod,
            flattenMethod,
            isNullableOverride,
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
        val classBody = methods + "\n\n" + classFields

        OutputStreamWriter(file).use { writer ->
            writer.write(
                """
                |package $packageName
                |
                |import st.orm.Metamodel
                |import st.orm.AbstractMetamodel
                |import st.orm.AbstractKeyMetamodel
                |import javax.annotation.processing.Generated
                |
                |@Generated("${this::class.java.name}")
                |class $metaClassName<T : st.orm.Data>(
                |    path: String,
                |    field: String,
                |    inline: Boolean,
                |    parent: Metamodel<T, *>,
                |    private val getter: (T) -> $recordGetterType$nullableCtorParam
                |) : $baseAbstractClass<T, $className, $abstractVType>($superCtorArgs) {
                |
                |$classBody
                |    init {
                |        val subPath = if (inline) path else if (field.isEmpty()) path else if (path.isEmpty()) field else "${'$'}path.${'$'}field"
                |        val fieldBase = if (inline) if (field.isEmpty()) "" else "${'$'}field." else ""
                |
                |${initClassFields(classDeclaration, packageName, metaClassName, forceNullableChain)}
                |    }
                |$convenienceCtors
                |${
                    if (isDataRoot && !forceNullableChain) {
                        """
                |    companion object {
                |        private val INSTANCE: $metaClassName<*> = $metaClassName<st.orm.Data>()
                |
                |        @Suppress("UNCHECKED_CAST")
                |        @JvmStatic
                |        fun <T : st.orm.Data> instance(): $metaClassName<T> = INSTANCE as $metaClassName<T>
                |    }"""
                    } else {
                        ""
                    }
                }
                |}
                """.trimMargin(),
            )
        }
    }
}
