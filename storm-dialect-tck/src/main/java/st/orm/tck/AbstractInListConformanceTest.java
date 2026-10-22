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
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_IN;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.PK;
import st.orm.StormConfig;
import st.orm.core.template.ORMTemplate;

/**
 * {@code IN} list conformance: an {@code IN} or {@code NOT IN} list returns the same rows whatever shape it renders
 * in, for every length, with a {@code NULL} among its values, and over a compound key, on every database.
 *
 * <p>The shape is the concern of {@code storm.in_list}: {@code auto} binds the list as one array parameter where
 * the dialect does and renders a placeholder per value elsewhere, {@code padded} pads a list of placeholders to a
 * power of two everywhere, and {@code exact} renders one placeholder per value everywhere. Each mode runs through
 * the same rows, so a shape that changed a result would fail against the exact one.</p>
 *
 * <p>The suite creates and drops its own tables, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractInListConformanceTest {

    /** The rows of {@code in_probe}: ids 1 to 12. */
    private static final int ROWS = 12;

    private static final Metamodel<Probe, Integer> ID = Metamodel.of(Probe.class, "id");
    private static final Metamodel<Probe, String> CODE = Metamodel.of(Probe.class, "code");

    @DbTable("in_probe")
    public record Probe(@PK(generation = NONE) Integer id, String code) implements Entity<Integer> {}

    /** A compound key, whose list renders as rows of two columns. */
    public record PairId(int a, int b) {}

    public record Pair(@PK(generation = NONE) PairId id, String code) implements Entity<PairId> {}

    private DataSource dataSource;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The drop guards against a table a previous run left behind.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropTables();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE in_probe (id INT PRIMARY KEY, code VARCHAR(20) NOT NULL)");
            statement.execute("CREATE TABLE pair (a INT NOT NULL, b INT NOT NULL, code VARCHAR(20) NOT NULL, PRIMARY KEY (a, b))");
        }
        var orm = template("exact");
        var probes = orm.entity(Probe.class);
        for (int i = 1; i <= ROWS; i++) {
            probes.insert(new Probe(i, "c" + i));
        }
        var pairs = orm.entity(Pair.class);
        for (int a = 1; a <= 3; a++) {
            for (int b = 1; b <= 3; b++) {
                pairs.insert(new Pair(new PairId(a, b), a + "-" + b));
            }
        }
    }

    @AfterEach
    final void dropTables() {
        // Not every dialect spells the drop conditionally: Oracle has no DROP TABLE IF EXISTS.
        for (var table : List.of("in_probe", "pair")) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
            } catch (SQLException ignored) {
                // The table was not there to drop.
            }
        }
    }

    /** A template rendering its IN lists in the given mode. */
    private ORMTemplate template(String mode) {
        return ORMTemplate.builder(dataSource)
                .config(StormConfig.of(Map.of(StormConfig.IN_LIST, mode)))
                .build();
    }

    private static final List<String> MODES = List.of("auto", "padded", "exact");

    private static List<Integer> ids(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    /** The ids of the rows an IN list of the given ids selects, and the rest for NOT IN. */
    private static TreeSet<Integer> expected(Operator operator, List<Integer> ids) {
        var expected = new TreeSet<Integer>();
        boolean holdsNull = ids.contains(null);
        for (int id = 1; id <= ROWS; id++) {
            boolean member = ids.contains(id);
            // NOT IN over a list holding NULL is unknown for every non-member, so it selects nothing.
            if (operator == IN ? member : !member && !holdsNull) {
                expected.add(id);
            }
        }
        return expected;
    }

    private static TreeSet<Integer> select(ORMTemplate orm, Operator operator, List<Integer> ids) {
        var selected = new TreeSet<Integer>();
        orm.entity(Probe.class).select().where(ID, operator, ids).getResultList()
                .forEach(probe -> selected.add(probe.id()));
        return selected;
    }

    @Test
    public void testEveryLengthReturnsTheSameRowsInEveryMode() {
        for (var mode : MODES) {
            var orm = template(mode);
            for (int length : List.of(0, 1, 2, 3, 4, 5, 7, 8, 9, 12, 17)) {
                var ids = ids(length);
                for (var operator : List.of(IN, NOT_IN)) {
                    assertEquals(expected(operator, ids), select(orm, operator, ids),
                            mode + " " + (operator == IN ? "IN" : "NOT IN") + " of " + length);
                }
            }
        }
    }

    @Test
    public void testALongListReturnsTheSameRowsInEveryMode() {
        // Past every power of two a padded list could reach within the dialect's limits.
        var ids = ids(1_001);
        for (var mode : MODES) {
            var orm = template(mode);
            assertEquals(expected(IN, ids), select(orm, IN, ids), mode);
            assertEquals(expected(NOT_IN, ids), select(orm, NOT_IN, ids), mode);
        }
    }

    @Test
    public void testAListHoldingNullReturnsTheSameRowsInEveryMode() {
        var ids = new ArrayList<Integer>(Arrays.asList(1, 2, null, 5, 6));
        for (var mode : MODES) {
            var orm = template(mode);
            assertEquals(expected(IN, ids), select(orm, IN, ids), mode);
            assertEquals(expected(NOT_IN, ids), select(orm, NOT_IN, ids), mode);
        }
    }

    @Test
    public void testAListOfTextReturnsTheSameRowsInEveryMode() {
        var codes = List.of("c1", "c4", "c9");
        for (var mode : MODES) {
            var orm = template(mode);
            var selected = new TreeSet<Integer>();
            orm.entity(Probe.class).select().where(CODE, IN, codes).getResultList()
                    .forEach(probe -> selected.add(probe.id()));
            assertEquals(new TreeSet<>(List.of(1, 4, 9)), selected, mode);
        }
    }

    @Test
    public void testACompoundKeyListReturnsTheSameRowsInEveryMode() {
        var keys = List.of(new PairId(1, 1), new PairId(2, 3), new PairId(3, 2));
        for (var mode : MODES) {
            var orm = template(mode);
            for (int length = 0; length <= keys.size(); length++) {
                var subset = keys.subList(0, length);
                var selected = new TreeSet<String>();
                orm.entity(Pair.class).findAllById(subset).forEach(pair -> selected.add(pair.code()));
                var expected = new TreeSet<String>();
                subset.forEach(key -> expected.add(key.a() + "-" + key.b()));
                assertEquals(expected, selected, mode + " of " + length);
            }
        }
    }
}
