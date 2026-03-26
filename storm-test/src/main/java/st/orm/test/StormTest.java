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
package st.orm.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Annotation for Storm integration tests.
 *
 * <p>Provides automatic {@link javax.sql.DataSource} creation using an H2 in-memory database (by default), SQL script
 * execution, and parameter injection for test methods. Test methods can declare parameters of type
 * {@link javax.sql.DataSource}, {@link SqlCapture}, or any type with a static {@code of(DataSource)} factory
 * method (such as {@code ORMTemplate}).</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @StormTest(scripts = {"/schema.sql", "/data.sql"})
 * class MyTest {
 *
 *     @Test
 *     void myTest(ORMTemplate orm) {
 *         // orm is ready to use
 *     }
 * }
 * }</pre>
 *
 * @since 1.9
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(StormExtension.class)
public @interface StormTest {

    /**
     * Classpath SQL scripts to execute before tests run. Scripts are executed once per test class.
     *
     * <p>Path resolution follows conventions similar to Spring's {@code @Sql}:</p>
     * <ul>
     *     <li>{@code "schema.sql"} — resolved relative to the test class package.</li>
     *     <li>{@code "/schema.sql"} — resolved from the classpath root.</li>
     *     <li>{@code "classpath:schema.sql"} — resolved from the classpath root.</li>
     * </ul>
     */
    String[] scripts() default {};

    /**
     * JDBC URL for the test database. Defaults to an H2 in-memory database with a unique name per test class.
     */
    String url() default "";

    /**
     * Database username. Defaults to {@code "sa"}.
     */
    String username() default "sa";

    /**
     * Database password. Defaults to an empty string.
     */
    String password() default "";
}
