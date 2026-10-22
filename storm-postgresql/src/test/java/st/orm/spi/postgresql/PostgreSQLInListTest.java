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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_IN;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.PK;
import st.orm.SqlFunction;
import st.orm.StormConfig;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlArray;
import st.orm.core.template.SqlInterceptor;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * On PostgreSQL an IN list binds as one array parameter, {@code column = ANY(?)}, one statement for every length and
 * every type a key or column commonly holds; a list compared through a function, or in the padded or exact mode,
 * renders placeholders instead.
 *
 * @since 1.16
 */
@Testcontainers
@StormTest(rollback = false)
public class PostgreSQLInListTest {

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

    public enum Kind { A, B, C }

    public record Item(@PK(generation = NONE) Integer id,
                       Long big,
                       String code,
                       UUID uuid,
                       BigDecimal price,
                       Boolean flag,
                       Kind kind) implements Entity<Integer> {}

    private static final Metamodel<Item, Integer> ID = Metamodel.of(Item.class, "id");
    private static final Metamodel<Item, Long> BIG = Metamodel.of(Item.class, "big");
    private static final Metamodel<Item, String> CODE = Metamodel.of(Item.class, "code");
    private static final Metamodel<Item, UUID> UUID_ = Metamodel.of(Item.class, "uuid");
    private static final Metamodel<Item, BigDecimal> PRICE = Metamodel.of(Item.class, "price");
    private static final Metamodel<Item, Boolean> FLAG = Metamodel.of(Item.class, "flag");
    private static final Metamodel<Item, Kind> KIND = Metamodel.of(Item.class, "kind");
    private static final SqlFunction<String> LOWER = SqlFunction.of("lower");

    private static final List<UUID> UUIDS = IntStream.rangeClosed(1, 6)
            .mapToObj(i -> UUID.nameUUIDFromBytes(("item" + i).getBytes()))
            .toList();

    private DataSource dataSource;
    private ORMTemplate orm;

    @BeforeEach
    void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropItem();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE item (id INT PRIMARY KEY, big BIGINT NOT NULL, code VARCHAR(20) NOT NULL,
                        uuid UUID NOT NULL, price NUMERIC(10, 2) NOT NULL, flag BOOLEAN NOT NULL, kind VARCHAR(10) NOT NULL)""");
        }
        orm = ORMTemplate.of(dataSource);
        var items = orm.entity(Item.class);
        for (int i = 1; i <= 6; i++) {
            items.insert(new Item(i, 10_000_000_000L + i, "C" + i, UUIDS.get(i - 1), new BigDecimal(i + ".50"),
                    i % 2 == 0, Kind.values()[i % 3]));
        }
    }

    @AfterEach
    void dropItem() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS item");
        } catch (SQLException ignored) {
            // The table was not there to drop.
        }
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

    private <V> TreeSet<Integer> select(ORMTemplate orm, Metamodel<Item, V> field, Operator operator, List<V> values,
                                        AtomicReference<Sql> sql) {
        var ids = new TreeSet<Integer>();
        sql.set(observe(() -> {
            orm.entity(Item.class).select().where(field, operator, values).getResultList()
                    .forEach(item -> ids.add(item.id()));
            return null;
        }));
        return ids;
    }

    private static SqlArray array(Sql sql) {
        assertEquals(1, sql.parameters().size(), sql.statement());
        return assertInstanceOf(SqlArray.class, sql.parameters().getFirst().dbValue());
    }

    @Test
    void aListBindsAsOneArrayWhateverItsLength() {
        var statements = new TreeSet<String>();
        for (int length : List.of(1, 2, 3, 5, 9)) {
            var sql = new AtomicReference<Sql>();
            var ids = IntStream.rangeClosed(1, length).boxed().toList();
            var expected = new TreeSet<>(ids.stream().filter(id -> id <= 6).toList());
            assertEquals(expected, select(orm, ID, IN, ids, sql));
            statements.add(sql.get().statement());
            var array = array(sql.get());
            assertEquals("int4", array.elementType());
            assertEquals(length, array.elements().length);
        }
        assertEquals(1, statements.size(), statements.toString());
        assertTrue(statements.getFirst().contains("WHERE i.id = ANY(?)"), statements.getFirst());
    }

    @Test
    void notInBindsAsOneArrayCompared­WithAll() {
        var sql = new AtomicReference<Sql>();
        assertEquals(new TreeSet<>(List.of(4, 5, 6)), select(orm, ID, NOT_IN, List.of(1, 2, 3), sql));
        assertTrue(sql.get().statement().contains("WHERE i.id <> ALL(?)"), sql.get().statement());
    }

    @Test
    void eachTypeBindsAsAnArrayOfItsOwn() {
        var sql = new AtomicReference<Sql>();
        assertEquals(new TreeSet<>(List.of(1, 3)), select(orm, BIG, IN,
                List.of(10_000_000_001L, 10_000_000_003L, 10_000_000_099L), sql));
        assertEquals("int8", array(sql.get()).elementType());
        assertEquals(new TreeSet<>(List.of(2, 4)), select(orm, CODE, IN, List.of("C2", "C4", "C9"), sql));
        assertEquals("text", array(sql.get()).elementType());
        assertEquals(new TreeSet<>(List.of(1, 6)), select(orm, UUID_, IN,
                List.of(UUIDS.get(0), UUIDS.get(5), UUID.randomUUID()), sql));
        assertEquals("uuid", array(sql.get()).elementType());
        assertEquals(new TreeSet<>(List.of(2, 5)), select(orm, PRICE, IN,
                List.of(new BigDecimal("2.50"), new BigDecimal("5.5"), new BigDecimal("9.99")), sql));
        assertEquals("numeric", array(sql.get()).elementType());
        assertEquals(new TreeSet<>(List.of(2, 4, 6)), select(orm, FLAG, IN, List.of(true, true, true), sql));
        assertEquals("bool", array(sql.get()).elementType());
        assertEquals(new TreeSet<>(List.of(1, 2, 4, 5)), select(orm, KIND, IN, List.of(Kind.B, Kind.C, Kind.C), sql));
        var kinds = array(sql.get());
        assertEquals("text", kinds.elementType());
        assertEquals(List.of("B", "C", "C"), Arrays.asList(kinds.elements()));
    }

    @Test
    void aListHoldingNullBindsAsAnArrayAndMatchesTheRest() {
        var sql = new AtomicReference<Sql>();
        assertEquals(new TreeSet<>(List.of(1, 3)), select(orm, ID, IN, Arrays.asList(1, null, 3), sql));
        assertEquals(3, array(sql.get()).elements().length);
        // NOT IN over a list holding NULL is unknown for every row, as it is with placeholders.
        assertEquals(new TreeSet<>(), select(orm, ID, NOT_IN, Arrays.asList(1, null, 3), sql));
    }

    @Test
    void aLongListIsOneParameter() {
        var sql = new AtomicReference<Sql>();
        var ids = IntStream.rangeClosed(1, 5_000).boxed().toList();
        assertEquals(new TreeSet<>(List.of(1, 2, 3, 4, 5, 6)), select(orm, ID, IN, ids, sql));
        assertEquals(5_000, array(sql.get()).elements().length);
    }

    @Test
    void aColumnComparedThroughAFunctionRendersPlaceholders() {
        for (var mode : List.of("auto", "padded")) {
            var sql = new AtomicReference<Sql>();
            var ids = new TreeSet<Integer>();
            var orm = template(mode);
            sql.set(observe(() -> {
                orm.entity(Item.class).select().where(LOWER.apply(CODE), IN, List.of("c1", "c2", "c3")).getResultList()
                        .forEach(item -> ids.add(item.id()));
                return null;
            }));
            assertEquals(new TreeSet<>(List.of(1, 2, 3)), ids, mode);
            // The padded mode repeats the last value to the next power of two; the default keeps every value.
            int placeholders = mode.equals("padded") ? 4 : 3;
            assertEquals(placeholders, sql.get().parameters().size(), sql.get().statement());
            assertTrue(sql.get().statement().contains("IN (" + "lower(?), ".repeat(placeholders - 1) + "lower(?))"),
                    sql.get().statement());
        }
    }

    @Test
    void thePaddedModeKeepsTheArrayAndTheExactModeRendersPlaceholders() {
        var sql = new AtomicReference<Sql>();
        assertEquals(new TreeSet<>(List.of(1, 2, 3)), select(template("padded"), ID, IN, List.of(1, 2, 3), sql));
        assertTrue(sql.get().statement().contains("WHERE i.id = ANY(?)"), sql.get().statement());
        assertEquals(new TreeSet<>(List.of(1, 2, 3)), select(template("exact"), ID, IN, List.of(1, 2, 3), sql));
        assertTrue(sql.get().statement().contains("WHERE i.id IN (?, ?, ?)"), sql.get().statement());
    }
}
