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

import static java.util.Locale.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.GenerationStrategy.NONE;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.SqlDialect;

/**
 * Reserved word conformance: the dialect quotes every word its engine refuses as an unquoted identifier, and an entity
 * whose column names are keywords reads and writes whether the engine reserves them or not.
 *
 * <p>Storm quotes a table, column or alias name when the dialect reports it as a keyword. A reserved word left
 * unquoted breaks the statement. A word quoted needlessly is harmless where quoting leaves a name's meaning
 * unchanged, and breaks the statement where the engine folds unquoted names to upper case: there, quoting turns
 * {@code position} into the case-sensitive {@code "position"}, which does not match a column created without quotes.
 * A dialect on such an engine supplies its list through {@link #quotedWords()}, and the suite checks that the engine
 * refuses every word on it.</p>
 *
 * <p>A dialect module reads its engine's own list of reserved words in {@link #reservedWords()} rather than copying
 * it, so that moving the module's test image to a newer engine version surfaces the words that version reserves. An
 * engine that publishes no list leaves it empty, which skips the case that compares against it.</p>
 *
 * <p>The suite creates and drops its own tables, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractReservedWordConformanceTest {

    private static final String COLUMN_PROBE = "reserved_word_column_probe";
    private static final String ALIAS_PROBE = "reserved_word_alias_probe";

    /** The shape of a name Storm derives, upper case; an engine's keyword list also holds operators. */
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    protected DataSource dataSource;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The drop guards against a table a previous run left behind.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropPlacement();
    }

    @AfterEach
    final void dropPlacement() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            dropIfPresent(connection, "placement");
        }
    }

    /**
     * The words the engine refuses as an unquoted identifier, upper case, as the engine itself reports them. Empty
     * when the engine publishes no such list, which skips {@link #quotesEveryReservedWord()}.
     */
    protected abstract Optional<Set<String>> reservedWords() throws SQLException;

    /**
     * The dialect's own list of the words it quotes, supplied by a dialect whose engine folds unquoted names to upper
     * case, where quoting a word the engine accepts breaks the statement. Empty where quoting leaves a name's meaning
     * unchanged, which skips {@link #quotesNoWordTheEngineAccepts()}.
     */
    protected Optional<Set<String>> quotedWords() {
        return Optional.empty();
    }

    /**
     * The words the query returns in its first column, upper case, keeping the ones shaped like a name. Oracle's list
     * holds a row without a word, which is skipped.
     */
    protected static Set<String> queryWords(Connection connection, String query) throws SQLException {
        Set<String> words = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                String word = resultSet.getString(1);
                if (word == null) {
                    continue;
                }
                String upperCase = word.toUpperCase(ROOT);
                if (NAME.matcher(upperCase).matches()) {
                    words.add(upperCase);
                }
            }
        }
        return words;
    }

    /**
     * The words among the given ones that the engine refuses unquoted where Storm renders a name: as a column name, or
     * as the table alias that heads a select list, where H2 reads {@code top} as its row limit.
     */
    protected static Set<String> refusedAsName(Connection connection, Collection<String> words) throws SQLException {
        // A probe table left behind would make every word look refused.
        dropIfPresent(connection, COLUMN_PROBE);
        dropIfPresent(connection, ALIAS_PROBE);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + ALIAS_PROBE + " (id INT)");
        }
        try {
            Set<String> refused = new TreeSet<>();
            for (String word : words) {
                if (refusesAsColumnName(connection, word) || refusesAsAlias(connection, word)) {
                    refused.add(word);
                }
            }
            return refused;
        } finally {
            dropIfPresent(connection, ALIAS_PROBE);
        }
    }

    private static boolean refusesAsColumnName(Connection connection, String word) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.execute("CREATE TABLE " + COLUMN_PROBE + " (" + word + " INT)");
            } catch (SQLException refused) {
                return true;
            }
            statement.execute("DROP TABLE " + COLUMN_PROBE);
            return false;
        }
    }

    private static boolean refusesAsAlias(Connection connection, String word) {
        try (Statement statement = connection.createStatement();
             ResultSet ignored = statement.executeQuery(
                     "SELECT " + word + ".id FROM " + ALIAS_PROBE + " " + word)) {
            return false;
        } catch (SQLException refused) {
            return true;
        }
    }

    private static void dropIfPresent(Connection connection, String table) {
        // Not every dialect spells the drop conditionally: Oracle has no DROP TABLE IF EXISTS.
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + table);
        } catch (SQLException ignored) {
            // The table was not there to drop.
        }
    }

    private SqlDialect dialect() {
        return PreparedStatementTemplate.ORM(dataSource).dialect();
    }

    @Test
    public void quotesEveryReservedWord() throws SQLException {
        var reserved = reservedWords();
        assumeTrue(reserved.isPresent());
        var dialect = dialect();
        List<String> unquoted = new ArrayList<>();
        for (String word : new TreeSet<>(reserved.get())) {
            // Storm derives lower-case names, so the lower-case spelling is the one that must be recognised.
            if (!dialect.isKeyword(word.toLowerCase(ROOT))) {
                unquoted.add(word);
            }
        }
        assertEquals(List.of(), unquoted, "Reserved by the engine but left unquoted by " + dialect.name());
    }

    @Test
    public void quotesNoWordTheEngineAccepts() throws SQLException {
        var quoted = quotedWords();
        assumeTrue(quoted.isPresent());
        Set<String> accepted = new TreeSet<>(quoted.get());
        try (Connection connection = dataSource.getConnection()) {
            accepted.removeAll(refusedAsName(connection, quoted.get()));
        }
        assertEquals(Set.of(), accepted, "Quoted by " + dialect().name() + " but accepted by the engine");
    }

    /**
     * An entity with a column named after a word no engine reserves, created without quotes, and one named after a
     * word every engine reserves, created quoted as the dialect quotes it.
     */
    public record Placement(
            @PK(generation = NONE) Integer id,
            Integer position,
            Integer order
    ) implements Entity<Integer> {}

    @Test
    public void readsAndWritesAnEntityWhoseColumnsAreKeywords() throws SQLException {
        ORMTemplate orm = PreparedStatementTemplate.ORM(dataSource);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE placement (id INT PRIMARY KEY, position INT, "
                    + orm.dialect().escape("order") + " INT)");
        }
        var placements = orm.entity(Placement.class);
        placements.insert(new Placement(1, 3, 7));
        assertEquals(new Placement(1, 3, 7), placements.getById(1));
        placements.update(new Placement(1, 4, 8));
        assertEquals(new Placement(1, 4, 8), placements.getById(1));
        placements.remove(new Placement(1, 4, 8));
        assertEquals(0, placements.count());
    }
}
