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
import java.lang.annotation.Inherited;
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
 * <p>The annotation is {@code @Inherited}: it may be placed on an abstract base class and applies to every concrete
 * subclass, each of which gets its own database.</p>
 *
 * <p>Each test runs inside a database transaction that is rolled back afterwards, so tests never observe each
 * other's writes. Set {@link #rollback()} to {@code false} for tests that need their writes to commit.</p>
 *
 * <p>Tests run on H2 by default. {@link #database()} runs them on the database the application deploys on instead,
 * in a Testcontainers-managed container that all test classes of the run share:</p>
 * <pre>{@code
 * @StormTest(database = POSTGRESQL, scripts = {"/schema.sql", "/data.sql"})
 * class VisitRepositoryTest {
 *
 *     @Test
 *     void findsVisitsByPet(ORMTemplate orm) {
 *         // running against PostgreSQL
 *     }
 * }
 * }</pre>
 *
 * @since 1.9
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
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
     * The database to run the tests on. Defaults to {@link TestDatabase#H2 H2} in memory.
     *
     * <p>Every other database runs in a Docker container managed by Testcontainers 2, whose module for the database
     * must be on the test classpath together with the database's JDBC driver:
     * {@code org.testcontainers:testcontainers-postgresql} and {@code org.postgresql:postgresql} for
     * {@link TestDatabase#POSTGRESQL POSTGRESQL}, and so on. The container is
     * started once per JVM for a given database and {@link #image()} and shared by all test classes that ask for it;
     * each class gets a fresh database inside the container, created before the {@link #scripts()} run and dropped
     * when the class completes, so classes never observe each other's tables or rows.</p>
     *
     * <p>Mutually exclusive with {@link #url()} and with a static {@code dataSource()} factory method on the test
     * class, which both point at a database of the caller's own.</p>
     *
     * @since 1.14
     */
    TestDatabase database() default TestDatabase.H2;

    /**
     * The Docker image to run {@link #database()} from, including its tag; for example {@code postgres:16} or
     * {@code pgvector/pgvector:pg17} for {@link TestDatabase#POSTGRESQL POSTGRESQL}. Defaults to the database's
     * {@linkplain TestDatabase#defaultImage() default image}, which is pinned to a version rather than
     * {@code latest}. Only meaningful with a container database.
     *
     * @since 1.14
     */
    String image() default "";

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

    /**
     * Whether each test runs inside a transaction that is rolled back after the test. Defaults to {@code true}.
     *
     * <p>With rollback enabled, all connections handed out during a test share a single database transaction that is
     * rolled back when the test completes, so every test observes the database exactly as the {@link #scripts()}
     * left it. Storm's transaction API works as usual inside such a test; transactions are demarcated with
     * savepoints, so a commit keeps the changes pending in the surrounding test transaction and everything is still
     * undone afterwards.</p>
     *
     * <p>Set to {@code false} for tests that need real commit semantics: writes that must be visible to other
     * connections or threads, {@code REQUIRES_NEW} transactions that must be independent of the caller, or DDL
     * statements (which implicitly commit on most databases). With rollback disabled, writes persist across the
     * tests of the class, so such tests must not depend on test execution order or clean up after themselves.</p>
     *
     * <p>Transactions of separate threads or coroutines cannot run at once on the shared connection: a transaction
     * that begins while another one's is open fails with an exception that names this setting. One thread or
     * coroutine nesting transactions, and threads or coroutines taking turns, work with rollback enabled.</p>
     *
     * @since 1.14
     */
    boolean rollback() default true;
}
