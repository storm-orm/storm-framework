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
package st.orm.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.CONTAINS;
import static st.orm.Operator.ENDS_WITH;
import static st.orm.Operator.NOT_CONTAINS;
import static st.orm.Operator.NOT_ENDS_WITH;
import static st.orm.Operator.NOT_STARTS_WITH;
import static st.orm.Operator.STARTS_WITH;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;

/**
 * Text match conformance: {@link Operator#CONTAINS}, {@link Operator#STARTS_WITH}, {@link Operator#ENDS_WITH} and their
 * negations match their text literally on every database.
 *
 * <p>The rows hold the characters a {@code LIKE} pattern gives meaning to on some database: the standard wildcards
 * {@code %} and {@code _}, the escape character {@code !} Storm renders, SQL Server's character range {@code [...]},
 * and the backslash that PostgreSQL, MySQL, MariaDB and H2 read as their default escape character. Each search is
 * answered by the rows that hold its text, and by no row a wildcard reading would add.</p>
 *
 * <p>The suite creates and drops its own table, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractTextMatchConformanceTest {

    private static final List<String> CONTENTS = List.of(
            "50%", "50 dollars", "a_b", "axb", "a!b", "a[b]c", "abc", "a\\b", "a\\%b");

    private static final Metamodel<Phrase, String> CONTENT = Metamodel.of(Phrase.class, "content");

    public record Phrase(@PK(generation = NONE) Integer id, String content) implements Entity<Integer> {}

    private DataSource dataSource;
    private ORMTemplate orm;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The drop guards against a table a previous run left behind.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropPhrase();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE phrase (id INT PRIMARY KEY, content VARCHAR(100) NOT NULL)");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
        var phrases = orm.entity(Phrase.class);
        for (int i = 0; i < CONTENTS.size(); i++) {
            phrases.insert(new Phrase(i + 1, CONTENTS.get(i)));
        }
    }

    @AfterEach
    final void dropPhrase() {
        // Not every dialect spells the drop conditionally: Oracle has no DROP TABLE IF EXISTS.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE phrase");
        } catch (SQLException ignored) {
            // The table was not there to drop.
        }
    }

    private Set<String> match(Operator operator, String text) {
        var contents = new TreeSet<String>();
        orm.entity(Phrase.class).select().where(CONTENT, operator, text).getResultList()
                .forEach(phrase -> contents.add(phrase.content()));
        return contents;
    }

    private static Set<String> allBut(String... excluded) {
        var contents = new TreeSet<>(CONTENTS);
        List.of(excluded).forEach(contents::remove);
        return contents;
    }

    @Test
    public void containsMatchesAPercentSignLiterally() {
        assertEquals(Set.of("50%", "a\\%b"), match(CONTAINS, "%"));
    }

    @Test
    public void containsMatchesAnUnderscoreLiterally() {
        assertEquals(Set.of("a_b"), match(CONTAINS, "_"));
    }

    @Test
    public void containsMatchesTheEscapeCharacterLiterally() {
        assertEquals(Set.of("a!b"), match(CONTAINS, "!"));
    }

    @Test
    public void containsMatchesBracketsLiterally() {
        assertEquals(Set.of("a[b]c"), match(CONTAINS, "[b]"));
    }

    @Test
    public void containsMatchesABackslashLiterally() {
        assertEquals(Set.of("a\\b", "a\\%b"), match(CONTAINS, "\\"));
        assertEquals(Set.of("a\\%b"), match(CONTAINS, "\\%"));
    }

    @Test
    public void emptyTextMatchesEveryRow() {
        assertEquals(new TreeSet<>(CONTENTS), match(CONTAINS, ""));
    }

    @Test
    public void startsWithAndEndsWithAnchorTheirText() {
        assertEquals(Set.of("50%", "50 dollars"), match(STARTS_WITH, "50"));
        assertEquals(Set.of("50%"), match(STARTS_WITH, "50%"));
        assertEquals(Set.of("50%"), match(ENDS_WITH, "%"));
        assertEquals(Set.of("a_b"), match(ENDS_WITH, "_b"));
    }

    @Test
    public void theNegationsMatchTheRemainingRows() {
        assertEquals(allBut("50%", "a\\%b"), match(NOT_CONTAINS, "%"));
        assertEquals(allBut("a_b"), match(NOT_STARTS_WITH, "a_"));
        assertEquals(allBut("a[b]c"), match(NOT_ENDS_WITH, "]c"));
    }
}
