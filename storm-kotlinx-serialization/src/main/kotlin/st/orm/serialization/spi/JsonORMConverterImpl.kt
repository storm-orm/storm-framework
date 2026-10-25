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
package st.orm.serialization.spi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.serializer
import st.orm.Data
import st.orm.Json
import st.orm.Ref
import st.orm.core.template.impl.JsonConverterSupport
import st.orm.mapping.RecordField
import st.orm.serialization.RefSerializer
import st.orm.serialization.StormSerializersModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createInstance
import kotlinx.serialization.json.Json as JsonMapper

internal class JsonORMConverterImpl private constructor(
    field: RecordField,
    private val json: JsonMapper,
    private val serializer: KSerializer<Any?>,
) : JsonConverterSupport(field) {

    companion object {
        // The mapper depends only on the Json flags, so the cache holds at most one entry per flag combination.
        private val JSON_CACHE = ConcurrentHashMap<Json, JsonMapper>()

        /**
         * Creates the converter for a field, or returns null when kotlinx.serialization has no serializer for the
         * field's type: such a field is left to another JSON module.
         */
        fun of(field: RecordField, kType: KType, json: Json): JsonORMConverterImpl? {
            val mapper = JSON_CACHE.computeIfAbsent(json) { buildJson(it) }
            val serializer = try {
                createSerializer(mapper, field, kType)
            } catch (_: SerializationException) {
                return null
            }
            return JsonORMConverterImpl(field, mapper, serializer)
        }

        private fun buildJson(json: Json): JsonMapper = JsonMapper {
            serializersModule = StormSerializersModule { currentRefFactory() }
            ignoreUnknownKeys = !json.failOnUnknown
            coerceInputValues = !json.failOnMissing
            explicitNulls = true
            encodeDefaults = true
        }

        /**
         * Creates a serializer for the given KType, with special handling for Ref types
         * that doesn't require the target type to be @Serializable.
         */
        private fun createSerializer(json: JsonMapper, field: RecordField, kType: KType): KSerializer<Any?> {
            // First, check if the property has a custom serializer annotation.
            getPropertySerializer(field)?.let { return it }
            // Then try to build a Ref-aware serializer.
            tryCreateRefAwareSerializer(json, kType)?.let { return it }
            // Fall back to standard serializer lookup.
            return json.serializersModule.serializer(kType)
        }

        /**
         * Attempts to extract a custom serializer from the property's @Serializable(with = ...) annotation.
         */
        private fun getPropertySerializer(field: RecordField): KSerializer<Any?>? {
            try {
                // Get the @Serializable annotation from the field.
                val serializableAnnotation = field.getAnnotation(Serializable::class.java) ?: return null
                // Check if a custom serializer is specified.
                val serializerClass = serializableAnnotation.with
                if (serializerClass == kotlinx.serialization.KSerializer::class) {
                    // No custom serializer specified (default value).
                    return null
                }
                // Instantiate the custom serializer.
                @Suppress("UNCHECKED_CAST")
                val serializer = serializerClass.objectInstance
                    ?: serializerClass.createInstance()
                @Suppress("UNCHECKED_CAST")
                return serializer as? KSerializer<Any?>
            } catch (_: Exception) {
                // If we can't extract the serializer, return null to fall back to default behavior.
                return null
            }
        }

        private fun tryCreateRefAwareSerializer(json: JsonMapper, kType: KType): KSerializer<Any?>? {
            val classifier = kType.classifier as? KClass<*> ?: return null
            // Handle Ref<T> directly.
            if (classifier == Ref::class) {
                return createRefSerializer(json, kType)
            }
            // Handle List<...>, Set<...>, Collection<...>.
            if (classifier == List::class || classifier == Set::class || classifier == Collection::class) {
                val elementType = kType.arguments.firstOrNull()?.type ?: return null
                val elementSerializer = tryCreateRefAwareSerializer(json, elementType)
                    ?: return null // Element type doesn't need special handling.
                @Suppress("UNCHECKED_CAST")
                return when (classifier) {
                    List::class -> ListSerializer(elementSerializer as KSerializer<Any>) as KSerializer<Any?>
                    Set::class -> SetSerializer(elementSerializer as KSerializer<Any>) as KSerializer<Any?>
                    else -> ListSerializer(elementSerializer as KSerializer<Any>) as KSerializer<Any?>
                }
            }
            // Handle Map<K, V> where key or value might be Ref.
            if (classifier == Map::class) {
                val keyType = kType.arguments.getOrNull(0)?.type ?: return null
                val valueType = kType.arguments.getOrNull(1)?.type ?: return null
                val keyRefSerializer = tryCreateRefAwareSerializer(json, keyType)
                val valueRefSerializer = tryCreateRefAwareSerializer(json, valueType)
                // Only return custom serializer if at least one needs special handling.
                if (keyRefSerializer == null && valueRefSerializer == null) return null
                val keySerializer = keyRefSerializer ?: json.serializersModule.serializer(keyType)
                val valueSerializer = valueRefSerializer ?: json.serializersModule.serializer(valueType)
                @Suppress("UNCHECKED_CAST")
                return MapSerializer(
                    keySerializer as KSerializer<Any>,
                    valueSerializer as KSerializer<Any>,
                ) as KSerializer<Any?>
            }
            return null
        }

        private fun createRefSerializer(json: JsonMapper, kType: KType): KSerializer<Any?>? {
            val targetType = kType.arguments.firstOrNull()?.type ?: return null
            val targetClass = (targetType.classifier as? KClass<*>)?.java ?: return null
            if (!Data::class.java.isAssignableFrom(targetClass)) return null
            @Suppress("UNCHECKED_CAST")
            return RefSerializer(
                targetClass = targetClass as Class<Data>,
                targetSerializerProvider = {
                    // Lazy - only called when serializing/deserializing loaded refs.
                    json.serializersModule.serializer(targetType) as KSerializer<Data>
                },
                refFactoryProvider = { currentRefFactory() },
            ) as KSerializer<Any?>
        }
    }

    override fun write(value: Any): String = json.encodeToString(serializer, value)

    override fun read(json: String): Any? = this.json.decodeFromString(serializer, json)
}
