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

import st.orm.Json
import st.orm.core.spi.ORMConverter
import st.orm.core.spi.ORMConverterProvider
import st.orm.mapping.RecordField
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties

/**
 * Provides a kotlinx.serialization converter for a [Json] field whose type kotlinx.serialization has a serializer for:
 * a `@Serializable` type, a type with a serializer registered in the serializers module, or a collection or `Ref` of
 * one. A field of another type is left unclaimed, so a Jackson module on the classpath converts it; without one, the
 * field is refused.
 */
internal class JsonORMConverterProviderImpl : ORMConverterProvider {

    override fun getConverter(field: RecordField): Optional<ORMConverter> {
        val jsonAnn = field.getAnnotation(Json::class.java) ?: return Optional.empty()
        val kType = resolveKType(field.declaringType().kotlin, field.name()) ?: return Optional.empty()
        return Optional.ofNullable(JsonORMConverterImpl.of(field, kType, jsonAnn))
    }

    private fun resolveKType(kClass: KClass<*>, fieldName: String): KType? = kClass.declaredMemberProperties.firstOrNull { it.name == fieldName }?.returnType
}
