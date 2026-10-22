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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_IN;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.PK;
import st.orm.StormConfig;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlInterceptor;

/**
 * On H2, which binds no arrays, an IN list keeps a placeholder per value by default and, in the padded mode, is padded
 * to the next power of two by repeating its last value, so the statement text and the bound values follow the shape
 * while the rows returned follow the list. The mode is a setting of the template.
 */
public class InListIntegrationTest {

    public record Customer(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    public record PairId(int a, int b) {}

    public record Pair(@PK(generation = NONE) PairId id, String code) implements Entity<PairId> {}

    private static final Metamodel<Customer, Integer> ID = Metamodel.of(Customer.class, "id");

    private DataSource dataSource;

    @BeforeEach
    public void database() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:in_list_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            for (int i = 1; i <= 10; i++) {
                statement.execute("INSERT INTO customer (id, name) VALUES (%d, 'c%d')".formatted(i, i));
            }
            statement.execute("CREATE TABLE pair (a INTEGER NOT NULL, b INTEGER NOT NULL, code VARCHAR(20) NOT NULL, PRIMARY KEY (a, b))");
            for (int a = 1; a <= 3; a++) {
                for (int b = 1; b <= 3; b++) {
                    statement.execute("INSERT INTO pair (a, b, code) VALUES (%d, %d, '%d-%d')".formatted(a, b, a, b));
                }
            }
        }
        dataSource = h2;
    }

    private ORMTemplate template(String mode) {
        return ORMTemplate.builder(dataSource)
                .config(StormConfig.of(Map.of(StormConfig.IN_LIST, mode)))
                .build();
    }

    /** Runs the query and returns the statement it sent. */
    private static Sql observe(Supplier<?> query) {
        var captured = new AtomicReference<Sql>();
        SqlInterceptor.observe(captured::set, query);
        return captured.get();
    }

    private static TreeSet<Integer> ids(List<Customer> customers) {
        var ids = new TreeSet<Integer>();
        customers.forEach(customer -> ids.add(customer.id()));
        return ids;
    }

    private static List<Object> values(Sql sql) {
        return sql.parameters().stream().map(parameter -> (Object) parameter.dbValue()).toList();
    }

    @Test
    public void byDefaultEveryValueHasAPlaceholder() {
        var orm = ORMTemplate.of(dataSource);
        var sql = observe(() -> orm.entity(Customer.class).select().where(ID, IN, List.of(1, 2, 3)).getResultList());
        assertTrue(sql.statement().contains("WHERE c.id IN (?, ?, ?)"), sql.statement());
        assertEquals(List.of(1, 2, 3), values(sql));
    }

    @Test
    public void aListOfThreePadsToFourByRepeatingItsLastValue() {
        var orm = template("padded");
        var customers = new AtomicReference<List<Customer>>();
        var sql = observe(() -> {
            customers.set(orm.entity(Customer.class).select().where(ID, IN, List.of(1, 2, 3)).getResultList());
            return null;
        });
        assertTrue(sql.statement().contains("WHERE c.id IN (?, ?, ?, ?)"), sql.statement());
        assertEquals(List.of(1, 2, 3, 3), values(sql));
        assertEquals(new TreeSet<>(List.of(1, 2, 3)), ids(customers.get()));
    }

    @Test
    public void aPowerOfTwoIsNotPadded() {
        var orm = template("padded");
        assertTrue(observe(() -> orm.entity(Customer.class).select().where(ID, IN, List.of(1, 2)).getResultList())
                .statement().contains("IN (?, ?)"));
        assertTrue(observe(() -> orm.entity(Customer.class).select().where(ID, IN, List.of(1, 2, 3, 4)).getResultList())
                .statement().contains("IN (?, ?, ?, ?)"));
        assertEquals(1, values(observe(() -> orm.entity(Customer.class).select().where(ID, IN, List.of(7)).getResultList())).size());
    }

    @Test
    public void everyLengthUpToAPowerOfTwoSharesOneStatement() {
        var orm = template("padded");
        var statements = new TreeSet<String>();
        for (int length = 5; length <= 8; length++) {
            var ids = java.util.stream.IntStream.rangeClosed(1, length).boxed().toList();
            var sql = observe(() -> orm.entity(Customer.class).select().where(ID, IN, ids).getResultList());
            statements.add(sql.statement());
            assertEquals(8, values(sql).size(), sql.statement());
        }
        assertEquals(1, statements.size(), statements.toString());
    }

    @Test
    public void notInIsPaddedAndReturnsTheRest() {
        var orm = template("padded");
        var customers = new AtomicReference<List<Customer>>();
        var sql = observe(() -> {
            customers.set(orm.entity(Customer.class).select().where(ID, NOT_IN, List.of(1, 2, 3)).getResultList());
            return null;
        });
        assertTrue(sql.statement().contains("NOT IN (?, ?, ?, ?)"), sql.statement());
        assertEquals(new TreeSet<>(List.of(4, 5, 6, 7, 8, 9, 10)), ids(customers.get()));
    }

    @Test
    public void theExactModeKeepsEveryValue() {
        var orm = template("exact");
        var sql = observe(() -> orm.entity(Customer.class).select().where(ID, IN, List.of(1, 2, 3)).getResultList());
        assertTrue(sql.statement().contains("IN (?, ?, ?)"), sql.statement());
        assertEquals(List.of(1, 2, 3), values(sql));
    }

    @Test
    public void anUnknownModeIsRefused() {
        var orm = template("chunked");
        var refused = assertThrows(RuntimeException.class,
                () -> orm.entity(Customer.class).select().where(ID, IN, List.of(1, 2, 3)).getResultList());
        assertTrue(refused.getMessage().contains("storm.in_list"), refused.getMessage());
    }

    @Test
    public void aCompoundKeyListPadsItsRows() {
        var orm = template("padded");
        var keys = List.of(new PairId(1, 1), new PairId(2, 3), new PairId(3, 2));
        var pairs = new AtomicReference<List<Pair>>();
        var sql = observe(() -> {
            pairs.set(orm.entity(Pair.class).findAllById(keys));
            return null;
        });
        // Four rows of two columns: the last key repeated once.
        assertEquals(8, values(sql).size(), sql.statement());
        assertEquals(List.of(3, 2, 3, 2), values(sql).subList(4, 8));
        var codes = new TreeSet<String>();
        pairs.get().forEach(pair -> codes.add(pair.code()));
        assertEquals(new TreeSet<>(List.of("1-1", "2-3", "3-2")), codes);
    }

    @Test
    public void findAllByIdIsPadded() {
        var orm = template("padded");
        var customers = new AtomicReference<List<Customer>>();
        var sql = observe(() -> {
            customers.set(orm.entity(Customer.class).findAllById(List.of(2, 4, 6, 8, 10)));
            return null;
        });
        assertEquals(8, values(sql).size(), sql.statement());
        assertEquals(new TreeSet<>(List.of(2, 4, 6, 8, 10)), ids(customers.get()));
    }

    @Test
    public void aListHoldingNullBindsNullAndMatchesTheRest() {
        var orm = template("padded");
        var customers = new AtomicReference<List<Customer>>();
        var sql = observe(() -> {
            customers.set(orm.entity(Customer.class).select().where(ID, IN, java.util.Arrays.asList(1, null, 3)).getResultList());
            return null;
        });
        assertEquals(java.util.Arrays.asList(1, null, 3, 3), values(sql));
        assertEquals(new TreeSet<>(List.of(1, 3)), ids(customers.get()));
        // NOT IN over a list holding NULL is unknown for every row.
        assertEquals(List.of(), orm.entity(Customer.class).select()
                .where(ID, NOT_IN, java.util.Arrays.asList(1, null, 3)).getResultList());
    }

    @Test
    public void anEmptyListStaysTheEmptyComparison() {
        var orm = ORMTemplate.of(dataSource);
        for (var operator : List.of(IN, NOT_IN)) {
            var sql = observe(() -> orm.entity(Customer.class).select().where(ID, (Operator) operator, List.of()).getResultList());
            assertEquals(List.of(), values(sql), sql.statement());
        }
    }
}
