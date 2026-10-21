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
package st.orm.spi.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS_IGNORE_CASE;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.SqlFunction;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.impl.PredicateBuilderFactory;
import st.orm.tck.ContainerDataSource;
import st.orm.test.SqlCapture;
import st.orm.test.StormTest;

/**
 * An accent-insensitive search served by an expression index, with no column that stores the folded text: the index is
 * built on {@code LOWER(fold_accents(name))}, and a search through the {@code fold_accents} function names that
 * expression, on the column and on the input alike.
 *
 * @since 1.16
 */
@Testcontainers
@StormTest(rollback = false)
public class PostgreSQLSqlFunctionTest {

    @SuppressWarnings("resource")
    @Container
    public static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forListeningPort());

    public static DataSource dataSource() {
        return ContainerDataSource.of(postgresContainer.getJdbcUrl(), postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }

    public record Title(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    private static final Metamodel<Title, String> NAME = Metamodel.of(Title.class, "name");
    private static final SqlFunction<String> FOLD_ACCENTS = SqlFunction.of("fold_accents");

    private DataSource dataSource;
    private ORMTemplate orm;

    @BeforeEach
    void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS title");
            statement.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            statement.execute("""
                    CREATE OR REPLACE FUNCTION fold_accents(text) RETURNS text
                        LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
                        RETURN public.unaccent('public.unaccent'::regdictionary, $1)""");
            statement.execute("CREATE TABLE title (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            statement.execute("INSERT INTO title SELECT g, 'filler ' || g FROM generate_series(1, 2000) g");
            statement.execute("""
                    INSERT INTO title VALUES (3001, 'Léon'), (3002, 'LEON the Professional'), (3003, 'Amélie'),
                        (3004, 'Søren'), (3005, '50% Léon')""");
            statement.execute("CREATE INDEX title_name_folded ON title USING gin (LOWER(fold_accents(name)) gin_trgm_ops)");
            statement.execute("ANALYZE title");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
    }

    private Set<String> search(String input) {
        var names = new TreeSet<String>();
        orm.entity(Title.class).select()
                .where(it -> PredicateBuilderFactory.<Title, Title, Integer>search(input, List.of(FOLD_ACCENTS.apply(NAME))))
                .getResultList()
                .forEach(title -> names.add(title.name()));
        return names;
    }

    @Test
    public void searchIgnoresAccentsInTheColumnAndInTheInput() {
        Set<String> leon = Set.of("Léon", "LEON the Professional", "50% Léon");
        assertEquals(leon, search("leon"));
        assertEquals(leon, search("LÉON"));
        assertEquals(Set.of("Amélie"), search("amelie"));
        // unaccent maps ø to o, which stripping combining marks in the application would leave alone.
        assertEquals(Set.of("Søren"), search("soren"));
        assertEquals(Set.of("Søren"), search("søren"));
        assertEquals(Set.of("50% Léon"), search("50% leon"));
    }

    @Test
    public void equalityIgnoresAccentsThroughTheFunction() {
        var names = new ArrayList<String>();
        orm.entity(Title.class).select()
                .where(FOLD_ACCENTS.apply(NAME), EQUALS_IGNORE_CASE, "LÉON")
                .getResultList()
                .forEach(title -> names.add(title.name()));
        assertEquals(List.of("Léon"), names);
    }

    @Test
    public void theExpressionIndexServesTheSearch(SqlCapture capture) throws SQLException {
        capture.record(() -> search("léon"));
        var captured = capture.statements().getFirst();
        assertTrue(captured.statement().contains("LOWER(fold_accents(t.name)) LIKE LOWER(fold_accents(?))"),
                captured.statement());
        var plan = new StringBuilder();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan = off");
            }
            try (var explain = connection.prepareStatement("EXPLAIN " + captured.statement())) {
                for (int i = 0; i < captured.parameters().size(); i++) {
                    explain.setObject(i + 1, captured.parameters().get(i));
                }
                try (var rows = explain.executeQuery()) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
            }
            connection.rollback();
        }
        assertTrue(plan.toString().contains("title_name_folded"), plan.toString());
    }
}
