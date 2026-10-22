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
package st.orm.serialization

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import st.orm.*
import st.orm.core.spi.RefFactory
import st.orm.core.template.impl.RefJsonSupport
import st.orm.core.template.impl.RefJsonSupport.ENTITY_FIELD
import st.orm.core.template.impl.RefJsonSupport.ID_FIELD
import st.orm.core.template.impl.RefJsonSupport.PROJECTION_FIELD
import java.util.function.Supplier
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * The default serializers module for Storm ORM types.
 *
 * Includes contextual serialization support for [Ref] types using detached refs.
 * For attached refs (database-connected), use [StormSerializersModule] with a [RefFactory].
 *
 * Usage:
 * ```
 * val json = Json {
 *     serializersModule = StormSerializers
 * }
 * ```
 */
public val StormSerializers: SerializersModule = StormSerializersModule()

/**
 * Creates a [SerializersModule] for Storm ORM types with optional [RefFactory] support.
 *
 * This module registers contextual serializers for [Ref] types. Because kotlinx.serialization only
 * consults the [SerializersModule] for fields annotated with
 * [@Contextual][kotlinx.serialization.Contextual], [Ref] fields must carry that annotation:
 * ```
 * @Serializable
 * data class Order(
 *     @PK val id: Int = 0,
 *     @Contextual val customer: Ref<Customer>,
 * ) : Entity<Int>
 * ```
 *
 * Usage:
 * ```
 * val json = Json {
 *     serializersModule = StormSerializersModule()
 * }
 * ```
 *
 * Usage with RefFactory:
 * ```
 * val json = Json {
 *     serializersModule = StormSerializersModule {
 *         // Resolve from ThreadLocal, scope, or DI container
 *         MyRefFactoryHolder.current()
 *     }
 * }
 * ```
 *
 * @param refFactoryProvider optional provider for [RefFactory] to create attached refs.
 *   When null, refs are created as detached via [Ref.of].
 *   The provider is invoked on each deserialization, allowing for ThreadLocal or
 *   scoped value resolution.
 */
public fun StormSerializersModule(
    refFactoryProvider: (() -> RefFactory?)? = null,
): SerializersModule = SerializersModule {
    contextual(Ref::class) { typeArgs ->
        val targetSerializerAny = typeArgs.firstOrNull()
            ?: throw SerializationException(
                "Cannot determine Ref<T> target type: missing type argument. Ensure Ref has a concrete type.",
            )
        val targetClass = resolveTargetClass(targetSerializerAny)
        @Suppress("UNCHECKED_CAST")
        RefSerializer(
            targetClass = targetClass,
            targetSerializerProvider = { targetSerializerAny as KSerializer<Data> },
            refFactoryProvider = refFactoryProvider,
        ) as KSerializer<*>
    }
}

/**
 * Serializer for Storm ORM [Ref] types.
 *
 * Serialization formats:
 * - **Loaded Entity**: `{"@entity": {...}}`
 * - **Loaded Projection**: `{"@id": ..., "@projection": {...}}`
 * - **Unloaded**: Raw id value (e.g., `42`, `"abc"`, or object for compound PK)
 *
 * Notes:
 * - Supports nullable Ref values *and* nullable values inside collections, e.g. `List<Ref<Foo>?>` and
 *   `List<Ref<Foo>>` containing `null` elements (JSON `null`).
 * - Requires JSON format (kotlinx.serialization Json).
 */
public class RefSerializer<T : Data>(
    private val targetClass: Class<out Data>,
    targetSerializerProvider: () -> KSerializer<T>,
    private val refFactoryProvider: (() -> RefFactory?)? = null,
) : KSerializer<Ref<T>?> {
    // Lazy - Only resolved when serializing/deserializing loaded entities.
    private val targetSerializer: KSerializer<T> by lazy(targetSerializerProvider)

    @OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("st.orm.Ref", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: Ref<T>?) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RefSerializer requires JSON format")
        val element = serializeToJsonElement(jsonEncoder.json, value)
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): Ref<T>? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RefSerializer requires JSON format")
        val element = jsonDecoder.decodeJsonElement()
        return deserializeFromJsonElement(jsonDecoder.json, element)
    }

    private fun serializeToJsonElement(json: Json, ref: Ref<*>?): JsonElement {
        val loaded = ref?.getOrNull()
        return if (loaded != null) {
            buildJsonObject {
                if (loaded is Entity<*>) {
                    @Suppress("UNCHECKED_CAST")
                    put(ENTITY_FIELD, json.encodeToJsonElement(targetSerializer, loaded as T))
                } else {
                    put(ID_FIELD, encodeId(json, ref.type(), ref.id()))
                    @Suppress("UNCHECKED_CAST")
                    put(PROJECTION_FIELD, json.encodeToJsonElement(targetSerializer, loaded as T))
                }
            }
        } else {
            // If the Ref itself is null, or the id is null, serialize JSON null.
            encodeId(json, ref?.type(), ref?.id())
        }
    }

    private fun deserializeFromJsonElement(json: Json, element: JsonElement): Ref<T>? = when (element) {
        is JsonNull -> null
        is JsonObject -> deserializeObject(json, element)
        else -> {
            val id = decodeId(json, element, targetClass)
            @Suppress("UNCHECKED_CAST")
            createRef(targetClass as Class<Data>, id) as Ref<T>?
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializeObject(json: Json, obj: JsonObject): Ref<T>? = try {
        when {
            obj.containsKey(ENTITY_FIELD) -> {
                val entity = json.decodeFromJsonElement(targetSerializer, obj.getValue(ENTITY_FIELD))
                RefJsonSupport.loadedRef(entity) as Ref<T>
            }
            obj.containsKey(PROJECTION_FIELD) -> {
                val idElement = obj[ID_FIELD]
                    ?: throw SerializationException("$PROJECTION_FIELD requires $ID_FIELD field")
                // For @projection, @id must be present and non-null.
                val id = decodeId(json, idElement, targetClass)
                    ?: throw SerializationException("$PROJECTION_FIELD requires non-null $ID_FIELD")
                val data = json.decodeFromJsonElement(targetSerializer, obj.getValue(PROJECTION_FIELD))
                RefJsonSupport.loadedRef(targetClass, data, id) as Ref<T>
            }
            else -> {
                val pkType = RefJsonSupport.pkType(targetClass)
                if (pkType == null || !RefJsonSupport.isCompoundKey(pkType)) {
                    throw SerializationException(
                        "Ref object must contain $ENTITY_FIELD or $PROJECTION_FIELD",
                    )
                }
                // A plain object is the inline record of a compound PK; unloaded refs serialize compound PKs this way.
                createRef(targetClass as Class<Data>, decodeId(json, obj, targetClass)) as Ref<T>?
            }
        }
    } catch (e: IllegalArgumentException) {
        throw SerializationException(e.message, e)
    }

    private fun <D : Data> createRef(targetClass: Class<D>, id: Any?): Ref<D>? {
        val refFactory = refFactoryProvider?.let { provider -> Supplier { provider() } }
        return RefJsonSupport.refOf(targetClass, id, refFactory)
    }

    private fun encodeId(json: Json, targetClass: Class<*>?, id: Any?): JsonElement {
        if (id == null || targetClass == null) return JsonNull
        val pkType = RefJsonSupport.pkType(targetClass)
        if (pkType != null) {
            val serializer = json.serializerOrNull(pkType)
            if (serializer != null) {
                @Suppress("UNCHECKED_CAST")
                return json.encodeToJsonElement(serializer as KSerializer<Any>, id)
            }
        }
        // Fallback for common primitive ids when PK type / serializer can't be resolved.
        return when (id) {
            is String -> JsonPrimitive(id)
            is Int -> JsonPrimitive(id)
            is Long -> JsonPrimitive(id)
            is Double -> JsonPrimitive(id)
            is Float -> JsonPrimitive(id)
            is Boolean -> JsonPrimitive(id)
            else -> throw SerializationException(
                "Cannot encode Ref id '$id': no serializer found for PK type '$pkType' and no primitive fallback applies",
            )
        }
    }

    private fun decodeId(json: Json, element: JsonElement, targetClass: Class<*>): Any? {
        if (element is JsonNull) return null
        val pkType = RefJsonSupport.pkType(targetClass)
        if (pkType != null) {
            val serializer = json.serializerOrNull(pkType)
                ?: throw SerializationException(
                    "Cannot decode Ref id: no serializer found for PK type '$pkType'.",
                )
            return json.decodeFromJsonElement(serializer, element)
        }
        // Fallback for unknown PK type.
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.boolean
                    element.intOrNull != null -> element.int
                    element.longOrNull != null -> element.long
                    element.doubleOrNull != null -> element.double
                    else -> throw SerializationException("Cannot decode Ref id from primitive: ${element.jsonPrimitive}")
                }
            }
            else -> throw SerializationException("Cannot decode Ref id from element: $element")
        }
    }
}

/**
 * Extension to get a serializer or null for a given class (kotlinx Json).
 */
private fun Json.serializerOrNull(kclass: KClass<*>): KSerializer<*>? = try {
    serializersModule.serializer(kclass.starProjectedType)
} catch (_: Exception) {
    null
}

/**
 * Extension to get a serializer or null for a given class (kotlinx Json).
 */
private fun Json.serializerOrNull(clazz: Class<*>): KSerializer<*>? = serializerOrNull(clazz.kotlin)

/**
 * Resolve the target class that a serializer was generated for.
 *
 * This is inherently a bit brittle in kotlinx.serialization; if Storm has a better source of truth
 * (e.g. ORMReflection mapping from serialName to class), prefer that and replace this method.
 */
private fun resolveTargetClass(serializer: KSerializer<*>): Class<out Data> {
    val serializerClass = serializer::class.java
    val serializerClassName = serializerClass.name
    // Strategy 1: For generated serializer class naming.
    when {
        serializerClassName.endsWith("\$\$\$serializer") -> {
            val targetClassName = serializerClassName.removeSuffix("\$\$\$serializer")
            loadDataClassOrNull(targetClassName, serializerClass.classLoader)?.let { return it }
        }
        serializerClassName.endsWith("\$\$serializer") -> {
            val targetClassName = serializerClassName.removeSuffix("\$\$serializer")
            loadDataClassOrNull(targetClassName, serializerClass.classLoader)?.let { return it }
        }
    }
    // Strategy 2: Use serialName from descriptor.
    @Suppress("OPT_IN_USAGE")
    val serialName = serializer.descriptor.serialName
    try {
        @Suppress("UNCHECKED_CAST")
        return resolveClassFromSerialName(serialName, serializerClass.classLoader)
    } catch (_: ClassNotFoundException) {
        // Continue.
    }
    throw SerializationException(
        "Cannot determine target class from serializer: ${serializerClass.name}, serialName: $serialName.",
    )
}

private fun resolveClassFromSerialName(name: String, classLoader: ClassLoader): Class<out Data> {
    // Try direct name first.
    loadDataClassOrNull(name, classLoader)?.let { return it }
    // Try converting dots to $ for nested classes, from right to left.
    val parts = name.split('.')
    for (i in parts.size - 2 downTo 1) {
        if (parts[i].firstOrNull()?.isUpperCase() == true) {
            val nestedName = parts.take(i).joinToString(".") + "." + parts.drop(i).joinToString("$")
            loadDataClassOrNull(nestedName, classLoader)?.let { return it }
        }
    }
    throw ClassNotFoundException("Could not resolve class from serialName: $name")
}

/**
 * Loads a [Data] class by name, or returns null when the name does not resolve to a loadable [Data] type.
 *
 * The class is loaded without initialization and validated to be a [Data] subtype before it is returned, so
 * a name that resolves to an unrelated class never triggers that class's static initializers. The serial
 * name a serializer reports is normally the declared entity or projection type, so this is defense in depth
 * against a serializer configured to report an arbitrary name.
 */
private fun loadDataClassOrNull(name: String, classLoader: ClassLoader): Class<out Data>? {
    val cls = try {
        Class.forName(name, false, classLoader)
    } catch (_: ClassNotFoundException) {
        return null
    } catch (_: LinkageError) {
        return null
    }
    if (!Data::class.java.isAssignableFrom(cls)) {
        throw SerializationException(
            "Refusing to deserialize Ref target that is not a Storm Data type: $name.",
        )
    }
    @Suppress("UNCHECKED_CAST")
    return cls as Class<out Data>
}
