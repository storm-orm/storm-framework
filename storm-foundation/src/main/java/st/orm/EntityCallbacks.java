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
package st.orm;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares the {@link EntityCallback} types that apply to the annotated entity.
 *
 * <p>Storm creates each declared callback through its public no-argument constructor, once per entity type, and
 * applies it to the annotated entity only, whatever its own type parameter would otherwise match. Declared callbacks
 * fire before the callbacks registered on the template, in the order they are declared here.</p>
 *
 * <pre>{@code
 * @EntityCallbacks(PostAuditCallback.class)
 * public record Post(@PK Integer id, String title, Instant createdAt) implements Entity<Integer> {}
 * }</pre>
 *
 * <p>This is the place for a callback that is part of the entity's own definition and needs nothing but the entity:
 * audit fields, normalization, a check. A callback that needs collaborators cannot be created this way; register that
 * one on the template instead, as a bean in Spring Boot, through the Ktor plugin's {@code entityCallback(...)}, or
 * with {@code ORMTemplate.withEntityCallback}. Registering an instance of a declared type replaces
 * the declared one, so a callback that is both declared here and registered fires once, as the registered
 * instance.</p>
 *
 * <p>A declared callback applies wherever the entity is written, including templates an application creates for
 * migrations, imports or tests. A callback that has to apply to some of an application's templates and not others
 * belongs on those templates rather than on the entity.</p>
 *
 * @since 1.14
 */
@Target(TYPE)
@Retention(RUNTIME)
public @interface EntityCallbacks {

    /**
     * The callback types that apply to the annotated entity.
     *
     * @return the callback types; each must have a public no-argument constructor and a type parameter that matches
     * the annotated entity.
     */
    Class<? extends EntityCallback<?>>[] value();
}
