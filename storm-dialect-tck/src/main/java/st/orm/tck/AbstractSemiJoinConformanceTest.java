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
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.Ref;
import st.orm.StormConfig;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.impl.PredicateBuilderFactory;

/**
 * Semi-join and anti-join conformance: the statements that place the conditions on a related table in subqueries run
 * on every database and return each row once.
 *
 * <p>The suite covers the {@code IN} over a {@code UNION} a search over a row and its related rows renders, with its
 * words matching different related rows; the {@code EXISTS} that holds conditions joined by AND to one related row, and
 * that keeps the rows with any related row when no condition names the table;
 * a row without related rows matching through its own columns; a composite key; the {@code NOT EXISTS} of an
 * anti-join next to a related row whose foreign key is null, which a {@code NOT IN} would turn into no rows at all; a
 * count; and a DELETE filtered by an anti-join.</p>
 *
 * <p>The suite creates and drops its own tables, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractSemiJoinConformanceTest {

    public record Author(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    public record Book(@PK(generation = NONE) Integer id, String title, @Nullable @FK Ref<Author> author)
            implements Entity<Integer> {}

    public record EditionKey(int publisherId, int editionNumber) {}

    public record Edition(@PK(generation = NONE) EditionKey id, String label) implements Entity<EditionKey> {}

    public record Printing(@PK(generation = NONE) Integer id, @FK Edition edition, int copies)
            implements Entity<Integer> {}

    private static final Metamodel<Author, String> AUTHOR_NAME = Metamodel.of(Author.class, "name");
    private static final Metamodel<Book, String> BOOK_TITLE = Metamodel.of(Book.class, "title");
    private static final Metamodel<Printing, Integer> PRINTING_COPIES = Metamodel.of(Printing.class, "copies");

    private static final List<String> TABLES = List.of("printing", "edition", "book", "author");

    private DataSource dataSource;
    private ORMTemplate orm;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The drop guards against tables a previous run left behind.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropTables();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE book (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL, author_id INT)");
            statement.execute("CREATE TABLE edition (publisher_id INT NOT NULL, edition_number INT NOT NULL, label VARCHAR(100) NOT NULL, PRIMARY KEY (publisher_id, edition_number))");
            statement.execute("CREATE TABLE printing (id INT PRIMARY KEY, publisher_id INT NOT NULL, edition_number INT NOT NULL, copies INT NOT NULL)");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
        var authors = orm.entity(Author.class);
        var tolkien = new Author(1, "Tolkien");
        var austen = new Author(2, "Austen");
        authors.insert(List.of(tolkien, austen, new Author(3, "Orwell"), new Author(4, "Herbert")));
        orm.entity(Book.class).insert(List.of(
                new Book(1, "The Hobbit", Ref.of(Author.class, 1)),
                new Book(2, "The Silmarillion", Ref.of(Author.class, 1)),
                new Book(3, "Emma", Ref.of(Author.class, 2)),
                new Book(4, "Persuasion", Ref.of(Author.class, 2)),
                new Book(5, "Dune", Ref.of(Author.class, 4)),
                new Book(6, "Anonymous Verses", null)));
        var first = new Edition(new EditionKey(1, 1), "first");
        var second = new Edition(new EditionKey(1, 2), "second");
        var other = new Edition(new EditionKey(2, 1), "other");
        orm.entity(Edition.class).insert(List.of(first, second, other));
        orm.entity(Printing.class).insert(List.of(
                new Printing(1, first, 500),
                new Printing(2, first, 5000),
                new Printing(3, second, 800)));
    }

    @AfterEach
    final void dropTables() {
        // Not every dialect spells the drop conditionally: Oracle has no DROP TABLE IF EXISTS.
        for (var table : TABLES) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
            } catch (SQLException ignored) {
                // The table was not there to drop.
            }
        }
    }

    private QueryBuilder<Data, Author, Integer> authorsByBooks() {
        return orm.entity(Author.class).select().semiJoin(Book.class).on(Author.class);
    }

    private static List<String> names(List<Author> authors) {
        return authors.stream().map(Author::name).sorted().toList();
    }

    private static List<String> labels(List<Edition> editions) {
        return editions.stream().map(Edition::label).sorted().toList();
    }

    private List<String> search(String input) {
        return names(authorsByBooks()
                .where(it -> PredicateBuilderFactory.<Data, Author, Integer>search(input, List.of(AUTHOR_NAME, BOOK_TITLE)))
                .getResultList());
    }

    @Test
    public void searchMatchesEachWordOnARelatedRowOfItsOwn() {
        assertEquals(List.of("Tolkien"), search("hobbit silmarillion"));
        assertEquals(List.of("Tolkien"), search("TOLKIEN hobbit"));
        assertEquals(List.of("Austen", "Herbert", "Orwell", "Tolkien"), search("e"));
        assertEquals(List.of(), search("hobbit emma"));
    }

    /**
     * Searches as a dialect whose substring index serves words of three characters, as a trigram index does, so the
     * short words render as they do there, whatever the dialect under test.
     */
    private List<String> trigramSearch(String input, boolean suggest) {
        var trigramOrm = ORMTemplate.of(dataSource, StormConfig.of(Map.of(StormConfig.SEARCH_MIN_TERM_LENGTH, "3")));
        var paths = List.of(AUTHOR_NAME, BOOK_TITLE);
        return names(trigramOrm.entity(Author.class).select().semiJoin(Book.class).on(Author.class)
                .where(it -> suggest
                        ? PredicateBuilderFactory.<Data, Author, Integer>suggest(input, paths)
                        : PredicateBuilderFactory.<Data, Author, Integer>search(input, paths))
                .getResultList());
    }

    @Test
    public void shortWordIsTestedOnTheRowsTheLongerWordsFound() {
        assertEquals(List.of("Tolkien"), trigramSearch("tolkien th", false));
        assertEquals(List.of(), trigramSearch("austen th", false));
        assertEquals(List.of("Orwell"), trigramSearch("or", false));
    }

    @Test
    public void suggestMatchesAShortWordAtTheStartOfAWord() {
        assertEquals(List.of("Tolkien"), trigramSearch("tolkien th", true));
        assertEquals(List.of(), trigramSearch("austen th", true));
        assertEquals(List.of("Tolkien"), trigramSearch("th", true));
        // A word shorter than the index serves at the start of a word is left out.
        assertEquals(List.of(), trigramSearch("t", true));
        assertEquals(List.of(), trigramSearch("", true));
    }

    @Test
    public void rowWithoutRelatedRowsMatchesThroughItsOwnColumns() {
        assertEquals(List.of("Orwell"), search("orwell"));
        assertEquals(List.of("Herbert", "Orwell"), names(authorsByBooks()
                .where(it -> it.where(AUTHOR_NAME, EQUALS, "Orwell").or(it.where(BOOK_TITLE, EQUALS, "Dune")))
                .getResultList()));
    }

    @Test
    public void conditionsJoinedByAndHoldForOneRelatedRow() {
        assertEquals(List.of(), names(authorsByBooks()
                .where(it -> it.where(BOOK_TITLE, EQUALS, "The Hobbit").and(it.where(BOOK_TITLE, EQUALS, "The Silmarillion")))
                .getResultList()));
        assertEquals(List.of("Austen", "Tolkien"), names(authorsByBooks()
                .where(it -> it.where(BOOK_TITLE, EQUALS, "The Hobbit").or(it.where(BOOK_TITLE, EQUALS, "Emma")))
                .getResultList()));
    }

    @Test
    public void semiJoinCountsEachRowOnce() {
        // Two of Tolkien's books and one of Austen's hold an 'o'.
        assertEquals(2, authorsByBooks()
                .where(it -> PredicateBuilderFactory.<Data, Author, Integer>search("o", List.of(BOOK_TITLE)))
                .getResultCount());
    }

    @Test
    public void semiJoinWithoutConditionKeepsTheRowsWithARelatedRow() {
        assertEquals(List.of("Austen", "Herbert", "Tolkien"), names(authorsByBooks().getResultList()));
    }

    @Test
    public void antiJoinIgnoresRelatedRowsWithoutAKey() {
        assertEquals(List.of("Orwell"), names(orm.entity(Author.class).select()
                .antiJoin(Book.class).on(Author.class)
                .getResultList()));
    }

    @Test
    public void antiJoinConditionDecidesWhichRelatedRowsExclude() {
        assertEquals(List.of("Austen", "Herbert", "Orwell"), names(orm.entity(Author.class).select()
                .antiJoin(Book.class).on(Author.class, it -> it.where(BOOK_TITLE, EQUALS, "The Hobbit"))
                .getResultList()));
    }

    @Test
    public void semiJoinAndAntiJoinRelateACompositeKey() {
        assertEquals(List.of("first"), labels(orm.entity(Edition.class).select()
                .semiJoin(Printing.class).on(Edition.class)
                .where(it -> it.where(PRINTING_COPIES, GREATER_THAN, 1000))
                .getResultList()));
        assertEquals(List.of("other"), labels(orm.entity(Edition.class).select()
                .antiJoin(Printing.class).on(Edition.class)
                .getResultList()));
    }

    @Test
    public void deleteFilteredByAnAntiJoin() {
        int deleted = orm.entity(Author.class).delete()
                .antiJoin(Book.class).on(Author.class)
                .executeUpdate();
        assertEquals(1, deleted);
        assertEquals(List.of("Austen", "Herbert", "Tolkien"), names(orm.entity(Author.class).select().getResultList()));
    }
}
