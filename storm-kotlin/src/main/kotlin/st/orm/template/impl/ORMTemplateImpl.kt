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
package st.orm.template.impl

import st.orm.Data
import st.orm.Entity
import st.orm.EntityCallback
import st.orm.Projection
import st.orm.core.spi.ORMReflection
import st.orm.core.spi.Providers
import st.orm.core.template.impl.SqlLogInterceptor
import st.orm.repository.EntityRepository
import st.orm.repository.ProjectionRepository
import st.orm.repository.Repository
import st.orm.repository.impl.EntityRepositoryImpl
import st.orm.repository.impl.ProjectionRepositoryImpl
import st.orm.template.ORMTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import kotlin.reflect.KClass

class ORMTemplateImpl(private val core: st.orm.core.template.ORMTemplate) :
    QueryTemplateImpl(core),
    ORMTemplate {
    companion object {
        val REFLECTION: ORMReflection = Providers.getORMReflection()

        private fun dispatch(
            proxy: Any,
            method: Method,
            args: Array<Any>,
            repository: Repository,
            entityRepository: EntityRepository<*, *>?,
            projectionRepository: ProjectionRepository<*, *>?,
            type: KClass<*>,
        ): Any? {
            try {
                return when {
                    method.declaringClass.isAssignableFrom(Repository::class.java) ->
                        method.invoke(repository, *args)
                    method.declaringClass.isAssignableFrom(EntityRepository::class.java) -> {
                        requireNotNull(entityRepository)
                        method.invoke(entityRepository, *args)
                    }
                    method.declaringClass.isAssignableFrom(ProjectionRepository::class.java) -> {
                        requireNotNull(projectionRepository)
                        method.invoke(projectionRepository, *args)
                    }
                    REFLECTION.isDefaultMethod(method) ->
                        REFLECTION.execute(proxy, method, *args)
                    else ->
                        throw UnsupportedOperationException("Unsupported method: ${method.name} for ${type.java.name}.")
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        private fun toShortSignature(method: Method): String = buildString {
            append(method.name)
            append('(')
            method.parameterTypes.forEachIndexed { i, p ->
                if (i > 0) append(", ")
                append(p.simpleName)
            }
            append(')')
        }
    }

    override fun withEntityCallback(callback: EntityCallback<*>): ORMTemplate = ORMTemplateImpl(core.withEntityCallback(callback))

    override fun withEntityCallbacks(callbacks: List<EntityCallback<*>>): ORMTemplate = ORMTemplateImpl(core.withEntityCallbacks(callbacks))

    override fun validateSchema(): List<String> = core.validateSchema()

    override fun validateSchema(vararg types: KClass<out Data>): List<String> = core.validateSchema(types.map { it.java })

    override fun validateSchemaOrThrow() = core.validateSchemaOrThrow()

    override fun validateSchemaOrThrow(vararg types: KClass<out Data>) = core.validateSchemaOrThrow(types.map { it.java })

    /**
     * Returns the repository for the given entity type.
     *
     * @param type the entity type.
     * @param <T> the entity type.
     * @param <ID> the type of the entity's primary key.
     * @return the repository for the given entity type.
     */
    override fun <T : Entity<ID>, ID : Any> entity(type: KClass<T>): EntityRepository<T, ID> = EntityRepositoryImpl(core.entity(type.java))

    /**
     * Returns the repository for the given projection type.
     *
     * @param type the projection type.
     * @param <T> the projection type.
     * @param <ID> the type of the projection's primary key, or Void if the projection specifies no primary key.
     * @return the repository for the given projection type.
     */
    override fun <T : Projection<ID>, ID : Any> projection(type: KClass<T>): ProjectionRepository<T, ID> = ProjectionRepositoryImpl(core.projection(type.java))

    @Suppress("UNCHECKED_CAST")
    override fun <R : Repository> repository(type: KClass<R>): R {
        val entityRepository = createEntityRepository(type)
        val projectionRepository = createProjectionRepository(type)
        val repository = createRepository()
        return Proxy.newProxyInstance(
            type.java.classLoader,
            arrayOf(type.java),
        ) { proxy, method, args ->
            val arguments = args ?: emptyArray()
            try {
                when {
                    method.name == "hashCode" && method.parameterCount == 0 ->
                        System.identityHashCode(proxy)
                    method.name == "equals" && method.parameterCount == 1 ->
                        proxy === arguments[0]
                    method.name == "toString" && method.parameterCount == 0 ->
                        "RepositoryProxy(${type.simpleName})"
                    else ->
                        SqlLogInterceptor.wrapIfNeeded(
                            SqlLogInterceptor.resolve(type.java, method),
                            type.java,
                            toShortSignature(method),
                        ) {
                            dispatch(proxy, method, arguments, repository, entityRepository, projectionRepository, type)
                        }
                }
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as R
    }

    private fun createEntityRepository(type: KClass<*>): EntityRepository<*, *>? {
        if (!EntityRepository::class.java.isAssignableFrom(type.java)) return null
        var entityClass: Class<*>? = null
        for (iface in type.java.genericInterfaces) {
            if (iface is ParameterizedType &&
                (iface.rawType as? Class<*>)?.let {
                    EntityRepository::class.java.isAssignableFrom(it)
                } == true
            ) {
                val arg = iface.actualTypeArguments[0]
                if (arg is Class<*>) {
                    entityClass = arg
                    break
                }
            }
        }
        requireNotNull(entityClass) {
            "Could not determine entity class for repository: ${type.simpleName}."
        }
        // Use Java reflection to invoke the generic 'entity' method at runtime.
        val method = this.javaClass.getMethod("entity", KClass::class.java)
        return method.invoke(this, entityClass.kotlin) as EntityRepository<*, *>
    }

    private fun createProjectionRepository(type: KClass<*>): ProjectionRepository<*, *>? {
        if (!ProjectionRepository::class.java.isAssignableFrom(type.java)) return null
        var projectionClass: Class<*>? = null
        for (iface in type.java.genericInterfaces) {
            if (iface is ParameterizedType &&
                (iface.rawType as? Class<*>)?.let {
                    ProjectionRepository::class.java.isAssignableFrom(it)
                } == true
            ) {
                val arg = iface.actualTypeArguments[0]
                if (arg is Class<*>) {
                    projectionClass = arg
                    break
                }
            }
        }
        requireNotNull(projectionClass) {
            "Could not determine projection class for repository: ${type.simpleName}."
        }
        // Use Java reflection to invoke the generic 'projection' method at runtime.
        val method = this.javaClass.getMethod("projection", KClass::class.java)
        return method.invoke(this, projectionClass.kotlin) as ProjectionRepository<*, *>
    }

    private fun createRepository(): Repository = object : Repository {
        override val orm: ORMTemplate
            get() = this@ORMTemplateImpl
    }
}
