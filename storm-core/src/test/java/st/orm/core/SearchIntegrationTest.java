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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Match.anywhere;
import static st.orm.Match.exact;
import static st.orm.Match.prefix;
import static st.orm.Match.wordStart;
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Match;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.SqlFunction;
import st.orm.StormConfig;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.MatchForm;
import st.orm.core.template.impl.PredicateBuilderFactory;

/**
 * A search matches every term in at least one of its fields, ignoring case and literally, over a relation as well,
 * each field in its match form: anywhere by default, at the start of a word for a suggestion, as a prefix of the
 * value or as the whole value where the call says so. The forms render the same SQL on every database.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SearchIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private List<String> statements;

    @SafeVarargs
    private List<String> search(String input, Metamodel<Owner, String>... paths) {
        var orm = ORMTemplate.of(dataSource);
        statements = new ArrayList<>();
        List<String> names = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), () -> orm.entity(Owner.class).select()
                .where(it -> PredicateBuilderFactory.<Owner, Owner, Integer>search(input, paths))
                .getResultList()
                .forEach(owner -> names.add(owner.firstName())));
        return names.stream().sorted().toList();
    }

    /** A table of titles stored lowered, as a column a prefix or exact match compares on its own. */
    @DbTable("suggestion")
    public record Suggestion(@PK Integer id, String titleLower) implements Entity<Integer> {}

    private static final Metamodel<Suggestion, String> TITLE_LOWER = Metamodel.of(Suggestion.class, "titleLower");

    private List<String> titles(boolean suggest, String input, Set<String> ignore, Match<Suggestion> field) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS suggestion (id INTEGER PRIMARY KEY, title_lower VARCHAR(100) NOT NULL)");
            statement.execute("DELETE FROM suggestion");
            statement.execute("INSERT INTO suggestion (id, title_lower) VALUES (1, 'davis'), (2, 'dave'), (3, 'pick me up'), (4, 'upside down'), (5, 'spider-man'), (6, 'o''connor (1981)')");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        var orm = ORMTemplate.of(dataSource);
        statements = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), () -> orm.entity(Suggestion.class).select()
                .where(it -> suggest
                        ? PredicateBuilderFactory.<Suggestion, Suggestion, Integer>suggest(input, ignore, field)
                        : PredicateBuilderFactory.<Suggestion, Suggestion, Integer>search(input, field))
                .getResultList()
                .forEach(suggestion -> titles.add(suggestion.titleLower())));
        return titles.stream().sorted().toList();
    }

    private List<String> suggestTitles(String input, Match<Suggestion> field) {
        return titles(true, input, Set.of(), field);
    }

    private List<String> searchTitles(String input, Match<Suggestion> field) {
        return titles(false, input, Set.of(), field);
    }

    @SafeVarargs
    private List<String> suggest(String input, Metamodel<Owner, String>... paths) {
        return suggest(input, Set.of(), ORMTemplate.of(dataSource), paths);
    }

    @SafeVarargs
    private List<String> suggest(String input, Set<String> ignore, ORMTemplate orm, Metamodel<Owner, String>... paths) {
        statements = new ArrayList<>();
        List<String> names = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), () -> orm.entity(Owner.class).select()
                .where(it -> PredicateBuilderFactory.<Owner, Owner, Integer>suggest(input, ignore, paths))
                .getResultList()
                .forEach(owner -> names.add(owner.firstName())));
        return names.stream().sorted().toList();
    }

    private String whereClause() {
        String statement = statements.getFirst().replaceAll("\\s+", " ");
        return statement.substring(statement.indexOf("WHERE ") + 6).trim();
    }

    @Test
    public void everyTermMustMatchSomePath() {
        assertEquals(List.of("Harold"), search("davis WINDSOR", Owner_.lastName, Owner_.address.city.name));
        assertEquals(List.of(), search("davis madison", Owner_.lastName, Owner_.address.city.name));
    }

    @Test
    public void termsAreGroupedPerTermAcrossPaths() {
        search("davis windsor", Owner_.lastName, Owner_.address.city.name);
        assertEquals(2, whereClause().split("\\) AND \\(").length);
        assertEquals(4, whereClause().split("LIKE").length - 1);
    }

    @Test
    public void singlePathRendersNoParentheses() {
        assertEquals(List.of("Betty", "Harold"), search("dav", Owner_.lastName));
        assertFalse(whereClause().startsWith("("));
        assertFalse(whereClause().contains(" OR "));
    }

    @Test
    public void quotedPhraseMatchesAsOneTerm() {
        assertEquals(List.of("Betty"), search("\"sun par\"", Owner_.address.city.name));
        assertEquals(List.of(), search("\"sun madison\"", Owner_.address.city.name));
    }

    @Test
    public void anOpenQuoteRunsToTheEndOfTheInput() {
        // The user is still typing the phrase: it matches as the closed phrase does.
        assertEquals(List.of("Betty"), search("\"sun par", Owner_.address.city.name));
        assertEquals(List.of("Betty"), suggest("\"sun par", Owner_.address.city.name));
        assertEquals(List.of(), search("\"par sun", Owner_.address.city.name));
        // A quote inside a word is an ordinary character.
        assertEquals(List.of(), search("sun\"", Owner_.address.city.name));
    }

    @Test
    public void wildcardsMatchLiterally() {
        assertEquals(List.of(), search("%", Owner_.lastName));
        assertEquals(List.of(), search("_", Owner_.lastName));
    }

    @Test
    public void searchMatchesAnywhereAndSuggestAtTheStartOfAWordOnEveryDialect() {
        // No substring index configured: search still matches anywhere, McTavish included, suggest at the start of
        // a word.
        assertEquals(List.of("Betty", "Harold", "Peter"), search("avis", Owner_.lastName));
        assertEquals(List.of(), suggest("avis", Owner_.lastName));
        assertEquals(List.of("Betty", "Harold"), suggest("dav", Owner_.lastName));
        assertTrue(whereClause().contains("LIKE LOWER(?) ESCAPE '!' OR LOWER("), whereClause());
        assertEquals(List.of("Betty", "Harold"), suggest("da", Owner_.lastName));
        assertEquals(List.of(), suggest("dav is", Owner_.lastName));
        assertEquals(List.of("David"), suggest("sc", Owner_.lastName));
        // A one-character word takes no part; without a word that takes part, no rows match.
        assertEquals(List.of(), suggest("d", Owner_.lastName));
        assertEquals("1 <> 1", whereClause());
        assertEquals(List.of(), suggest("", Owner_.lastName));
        // The same with a substring index configured: the form follows the function, not the dialect.
        var trigramOrm = ORMTemplate.of(dataSource, StormConfig.of(Map.of(StormConfig.SEARCH_MIN_TERM_LENGTH, "3")));
        assertEquals(List.of(), suggest("avis", Set.of(), trigramOrm, Owner_.lastName));
        assertEquals(List.of("Betty", "Harold"), suggest("dav da", Set.of(), trigramOrm, Owner_.lastName));
    }

    @Test
    public void eachFieldMatchesInItsStatedForm() {
        assertEquals(List.of("dave", "davis"), searchTitles("av", anywhere(TITLE_LOWER)));
        assertEquals(List.of(), searchTitles("av", wordStart(TITLE_LOWER)));
        assertEquals(List.of("pick me up", "upside down"), searchTitles("up", wordStart(TITLE_LOWER)));
        // A word follows a space or a punctuation character that joins or separates words, not only a space.
        assertEquals(List.of("spider-man"), searchTitles("man", wordStart(TITLE_LOWER)));
        assertEquals(List.of("o'connor (1981)"), searchTitles("connor 1981", wordStart(TITLE_LOWER)));
        assertEquals(List.of(), searchTitles("nnor", wordStart(TITLE_LOWER)));
        // A prefix or exact match leaves the column bare, for an ordinary index over it, and lowers the word: the
        // column is one that compares case-insensitively on its own, here a stored lowered title.
        assertEquals(List.of("upside down"), searchTitles("Up", prefix(TITLE_LOWER)));
        assertTrue(whereClause().contains("title_lower LIKE ? ESCAPE '!'"), whereClause());
        assertFalse(whereClause().contains("LOWER("), whereClause());
        assertEquals(List.of("davis"), searchTitles("DAVIS", exact(TITLE_LOWER)));
        assertTrue(whereClause().contains("title_lower = ?"), whereClause());
        assertEquals(List.of(), searchTitles("davi", exact(TITLE_LOWER)));
        assertEquals(List.of(), searchTitles("avis", prefix(TITLE_LOWER)));
        // A phrase is one term in every form.
        assertEquals(List.of("pick me up"), searchTitles("\"me up\"", wordStart(TITLE_LOWER)));
        assertEquals(List.of("pick me up"), searchTitles("\"pick me up\"", exact(TITLE_LOWER)));
        // suggest takes the forms as search does, with its own input contract.
        assertEquals(List.of("dave", "davis"), suggestTitles("av", anywhere(TITLE_LOWER)));
        assertEquals(List.of("upside down"), suggestTitles("Up", prefix(TITLE_LOWER)));
        assertEquals(List.of(), suggestTitles("u", prefix(TITLE_LOWER)));
        assertEquals(List.of(), suggestTitles("", exact(TITLE_LOWER)));
        assertEquals(6, searchTitles("", exact(TITLE_LOWER)).size());
    }

    @Test
    public void aMatchComposesWithASqlFunction() {
        SqlFunction<String> upper = SqlFunction.of("UPPER");
        search("DAV", Match.anywhere(upper.apply(Owner_.lastName)).path());
        assertTrue(whereClause().contains("LOWER(UPPER(o.last_name)) LIKE LOWER(UPPER(?)) ESCAPE '!'"), whereClause());
        assertEquals(List.of("upside down"), searchTitles("UPS", prefix(SqlFunction.<String>of("UPPER").apply(TITLE_LOWER))));
        assertTrue(whereClause().contains("UPPER(s.title_lower) LIKE UPPER(?) ESCAPE '!'"), whereClause());
    }

    @Test
    public void suggestIgnoresTheWordsTheSearchBoxNames() {
        var ignore = Set.of("Davis", "the");
        var orm = ORMTemplate.of(dataSource);
        // An ignored word takes no part, whatever its case; alone, it matches no rows.
        assertEquals(List.of("Betty", "Harold"), suggest("davis", Owner_.lastName));
        assertEquals(List.of(), suggest("davis", ignore, orm, Owner_.lastName));
        assertEquals("1 <> 1", whereClause());
        assertEquals(List.of(), suggest("DAVIS the", ignore, orm, Owner_.lastName));
        // The other words match as before, each over every path, at a word start on each.
        assertEquals(List.of("Betty"), suggest("the davis betty", ignore, orm, Owner_.lastName, Owner_.firstName));
        assertEquals(2 * (1 + MatchForm.WORD_SEPARATORS.length()), whereClause().split("LIKE").length - 1);
        // A quoted word is an explicit request: it is never ignored.
        assertEquals(List.of("Betty", "Harold"), suggest("\"Davis\"", ignore, orm, Owner_.lastName));
        assertEquals(List.of("Betty", "Harold"), suggest("\"davis", ignore, orm, Owner_.lastName));
        // Ignored in every form alike.
        assertEquals(List.of("pick me up"), titles(true, "the pick", ignore, prefix(TITLE_LOWER)));
        assertEquals(List.of(), titles(true, "the", ignore, prefix(TITLE_LOWER)));
    }

    @Test
    public void inputWithoutTermsMatchesEveryRow() {
        assertEquals(10, search("", Owner_.lastName).size());
        assertEquals(10, search("  \"\" ", Owner_.lastName, Owner_.firstName).size());
    }
}
