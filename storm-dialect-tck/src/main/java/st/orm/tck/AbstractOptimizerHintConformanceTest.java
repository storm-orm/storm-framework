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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.SqlDialect;

/**
 * Optimizer hint conformance: a hint added to a query builder reaches the statement in the dialect's own hint syntax,
 * after the leading keyword or at the end of the statement, and the statement returns what it returns without the
 * hint. A dialect whose database has no hint syntax leaves the hint out.
 *
 * <p>A dialect module supplies hints its database accepts through {@link #hints()}. The default hints are ones
 * MySQL, MariaDB and Oracle read from a {@code /*+ ... *}{@code /} comment; a database without hint syntax never
 * sees them.</p>
 *
 * <p>The suite creates and drops its own table, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractOptimizerHintConformanceTest {

    private static final Metamodel<Item, Integer> ID = Metamodel.of(Item.class, "id");

    public record Item(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    private DataSource dataSource;
    private ORMTemplate orm;

    /**
     * Two hints the database accepts, used one at a time and together. A statement the database runs with them
     * returns the same rows as without them.
     */
    protected List<String> hints() {
        return List.of("QB_NAME(storm)", "MAX_EXECUTION_TIME(10000)");
    }

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The drop guards against a table a previous run left behind.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropItem();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
        var items = orm.entity(Item.class);
        for (int id = 1; id <= 5; id++) {
            items.insert(new Item(id, "item " + id));
        }
    }

    @AfterEach
    final void dropItem() {
        // Not every dialect spells the drop conditionally: Oracle has no DROP TABLE IF EXISTS.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE item");
        } catch (SQLException ignored) {
            // The table was not there to drop.
        }
    }

    private SqlDialect dialect() {
        return orm.dialect();
    }

    private <X> List<String> statements(Supplier<X> action) {
        var statements = new ArrayList<String>();
        observe(sql -> statements.add(sql.statement()), action);
        return statements;
    }

    /**
     * Asserts that the statement carries the hints where the dialect renders them, or none at all where the
     * database has no hint syntax.
     */
    private void assertHinted(String statement, List<String> hints) {
        String rendered = dialect().optimizerHint(hints);
        if (rendered.isEmpty()) {
            assertFalse(statement.contains(hints.getFirst()), statement);
        } else if (dialect().applyOptimizerHintAfterKeyword()) {
            // The hint follows the keyword of the builder's own query block, which a count over a limit wraps in a
            // derived table.
            assertTrue(Pattern.compile("(SELECT|DELETE) " + Pattern.quote(rendered)).matcher(statement).find(), statement);
        } else {
            assertTrue(statement.stripTrailing().endsWith(rendered), statement);
        }
    }

    @Test
    public void aHintedSelectReturnsTheRowsOfTheUnhintedOne() {
        var expected = orm.entity(Item.class).select().getResultList();
        var hint = List.of(hints().getFirst());
        var statements = statements(() -> orm.entity(Item.class).select().hint(hint.getFirst()).getResultList());
        assertEquals(1, statements.size());
        assertHinted(statements.getFirst(), hint);
        assertEquals(expected, orm.entity(Item.class).select().hint(hint.getFirst()).getResultList());
    }

    @Test
    public void severalHintsShareOneCommentOrClause() {
        var hints = hints();
        var statements = statements(() -> orm.entity(Item.class).select()
                .hint(hints.get(0))
                .hint(hints.get(1))
                .where(ID, EQUALS, 3)
                .getResultList());
        assertHinted(statements.getFirst(), hints);
    }

    @Test
    public void aHintedCountCountsTheRows() {
        var hint = hints().getFirst();
        assertEquals(5, orm.entity(Item.class).select().hint(hint).getResultCount());
        // A limit counts through a derived table: a hint after the keyword stays on the builder's own query block, a
        // trailing hint ends the statement.
        var statements = statements(() -> {
            assertEquals(2, orm.entity(Item.class).select().hint(hint).limit(2).getResultCount());
            return null;
        });
        assertHinted(statements.getFirst(), List.of(hint));
    }

    @Test
    public void aHintedDeleteRemovesTheRowsItNames() {
        var hint = hints().getFirst();
        var statements = statements(() -> orm.entity(Item.class).delete().hint(hint).where(ID, EQUALS, 2).executeUpdate());
        assertHinted(statements.getFirst(), List.of(hint));
        assertEquals(4, orm.entity(Item.class).count());
    }

    @Test
    public void aHintedSubqueryRunsWhereTheHintFollowsItsKeyword() {
        var hint = hints().getFirst();
        boolean trailing = !dialect().applyOptimizerHintAfterKeyword() && !dialect().optimizerHint(List.of(hint)).isEmpty();
        if (trailing) {
            assertThrows(PersistenceException.class, () -> orm.entity(Item.class).select()
                    .whereExists(orm.subquery(Item.class).hint(hint))
                    .getResultList());
        } else {
            assertEquals(5, orm.entity(Item.class).select()
                    .whereExists(orm.subquery(Item.class).hint(hint))
                    .getResultList().size());
        }
    }
}
