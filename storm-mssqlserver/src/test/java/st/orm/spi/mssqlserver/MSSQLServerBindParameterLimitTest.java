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
package st.orm.spi.mssqlserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.Operator.IN;
import static st.orm.Polymorphic.Strategy.JOINED;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Polymorphic;
import st.orm.core.template.ORMTemplate;
import st.orm.tck.ContainerDataSource;
import st.orm.test.CapturedSql;
import st.orm.test.SqlCapture;
import st.orm.test.StormTest;

/**
 * Multi-row writes on SQL Server stay within the 2,098 values a statement binds, for entities whose rows bind more
 * values than the 1,000-row {@code VALUES} limit leaves room for.
 */
@StormTest(rollback = false)
public class MSSQLServerBindParameterLimitTest {

    private static final int ROWS = 2500;

    private static final int MAX_BIND_PARAMETERS = 2098;

    private static MSSQLServerContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-latest")
                .acceptLicense();
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Stops the database when the class is done: each test class starts its own, and one left running holds its
     * memory until the module's tests end.
     */
    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /** A row of three bound values and an identity key. */
    @DbTable("narrow_item")
    public record NarrowItem(@PK Integer id, String first, String second, String third) implements Entity<Integer> {}

    /** A row of ten bound values and an identity key. */
    @DbTable("wide_item")
    public record WideItem(@PK Integer id, String c1, String c2, String c3, String c4, String c5, String c6,
                           String c7, String c8, String c9, String c10) implements Entity<Integer> {}

    /** A row of three bound values and a key the database draws from the sequence its column default names. */
    @DbTable("sequence_item")
    public record SequenceItem(@PK(generation = SEQUENCE) Integer id, String first, String second, String third)
            implements Entity<Integer> {}

    /** A joined hierarchy whose base row binds the discriminator and two shared values. */
    @Discriminator @Polymorphic(JOINED) @DbTable("joined_pet")
    public sealed interface JoinedPet extends Entity<Integer> permits JoinedCat, JoinedDog {}

    @DbTable("joined_cat")
    public record JoinedCat(@PK Integer id, String name, String color, boolean indoor) implements JoinedPet {}

    @DbTable("joined_dog")
    public record JoinedDog(@PK Integer id, String name, String color, int weight) implements JoinedPet {}

    private static final Metamodel<NarrowItem, Integer> NARROW_ID = Metamodel.of(NarrowItem.class, "id");

    private ORMTemplate orm;

    @BeforeEach
    void createTables(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String ddl : List.of(
                    "DROP TABLE IF EXISTS narrow_item",
                    "DROP TABLE IF EXISTS wide_item",
                    "DROP TABLE IF EXISTS sequence_item",
                    "DROP SEQUENCE IF EXISTS sequence_item_seq",
                    "DROP TABLE IF EXISTS joined_cat",
                    "DROP TABLE IF EXISTS joined_dog",
                    "DROP TABLE IF EXISTS joined_pet",
                    "CREATE TABLE narrow_item (id INT IDENTITY(1,1) PRIMARY KEY, first VARCHAR(20), second VARCHAR(20), third VARCHAR(20))",
                    "CREATE TABLE wide_item (id INT IDENTITY(1,1) PRIMARY KEY, c1 VARCHAR(20), c2 VARCHAR(20), c3 VARCHAR(20), c4 VARCHAR(20), c5 VARCHAR(20), c6 VARCHAR(20), c7 VARCHAR(20), c8 VARCHAR(20), c9 VARCHAR(20), c10 VARCHAR(20))",
                    "CREATE SEQUENCE sequence_item_seq START WITH 1 INCREMENT BY 1",
                    "CREATE TABLE sequence_item (id INT PRIMARY KEY DEFAULT NEXT VALUE FOR sequence_item_seq, first VARCHAR(20), second VARCHAR(20), third VARCHAR(20))",
                    "CREATE TABLE joined_pet (id INT IDENTITY(1,1) PRIMARY KEY, dtype VARCHAR(50) NOT NULL, name VARCHAR(20), color VARCHAR(20))",
                    "CREATE TABLE joined_cat (id INT PRIMARY KEY, indoor BIT)",
                    "CREATE TABLE joined_dog (id INT PRIMARY KEY, weight INT)")) {
                statement.execute(ddl);
            }
        }
        orm = ORMTemplate.of(dataSource);
    }

    private static List<NarrowItem> narrowItems() {
        return IntStream.range(0, ROWS)
                .mapToObj(index -> new NarrowItem(null, "a" + index, "b" + index, "c" + index))
                .toList();
    }

    private static List<WideItem> wideItems() {
        return IntStream.range(0, ROWS)
                .mapToObj(index -> new WideItem(null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"))
                .toList();
    }

    private static List<JoinedPet> pets() {
        return IntStream.range(0, ROWS)
                .<JoinedPet>mapToObj(index -> index % 2 == 0
                        ? new JoinedCat(null, "cat" + index, "black", true)
                        : new JoinedDog(null, "dog" + index, "brown", index))
                .toList();
    }

    /** Asserts that no statement bound more values than SQL Server takes. */
    private static void assertWithinLimit(SqlCapture capture) {
        assertTrue(capture.count() > 0, "no statement was executed");
        for (CapturedSql statement : capture.statements()) {
            assertTrue(statement.parameters().size() <= MAX_BIND_PARAMETERS,
                    () -> statement.parameters().size() + " parameters: " + statement.statement());
        }
    }

    /** Returns the number of values each multi-row insert bound, in the order they ran. */
    private static List<Integer> insertParameters(SqlCapture capture) {
        return capture.statements(CapturedSql.Operation.INSERT).stream()
                .map(statement -> statement.parameters().size())
                .toList();
    }

    private static void assertDistinct(List<Integer> ids) {
        assertEquals(ROWS, ids.size());
        assertEquals(ROWS, new HashSet<>(ids).size());
    }

    @Test
    public void theDialectTakesTheParametersTheDriverLeaves() {
        assertEquals(MAX_BIND_PARAMETERS, new MSSQLServerSqlDialect().maxBindParameters());
    }

    @Test
    public void insertAndFetchIdsOfThreeValueRowsStaysWithinTheLimit(SqlCapture capture) {
        var items = orm.entity(NarrowItem.class);
        List<Integer> ids = capture.execute(() -> items.insertAndFetchIds(narrowItems().stream(), 1000));
        assertDistinct(ids);
        assertWithinLimit(capture);
        // 699 rows of three values a statement.
        assertEquals(List.of(2097, 2097, 2097, 1209), insertParameters(capture));
    }

    @Test
    public void insertAndFetchOfTenValueRowsChunksAtTheLimit(SqlCapture capture) {
        var items = orm.entity(WideItem.class);
        List<WideItem> inserted = capture.execute(() -> items.insertAndFetch(wideItems().stream(), ROWS));
        assertDistinct(inserted.stream().map(WideItem::id).toList());
        assertWithinLimit(capture);
        // 209 rows of ten values a statement.
        var parameters = insertParameters(capture);
        assertEquals(12, parameters.size());
        assertTrue(parameters.subList(0, 11).stream().allMatch(count -> count == 2090), parameters::toString);
        assertEquals(10 * (ROWS - 11 * 209), parameters.getLast());
    }

    @Test
    public void upsertAndFetchIdsOfNewRowsStaysWithinTheLimit(SqlCapture capture) {
        var items = orm.entity(WideItem.class);
        List<Integer> ids = capture.execute(() -> items.upsertAndFetchIds(wideItems().stream(), 1000));
        assertDistinct(ids);
        assertWithinLimit(capture);
        assertEquals(ROWS, items.count());
    }

    @Test
    public void upsertAndFetchIdsWithDefaultSequenceStaysWithinTheLimit(SqlCapture capture) {
        var items = orm.entity(SequenceItem.class);
        List<Integer> ids = capture.execute(() -> items.upsertAndFetchIds(IntStream.range(0, ROWS)
                .mapToObj(index -> new SequenceItem(null, "a", "b", "c")), 1000));
        assertDistinct(ids);
        assertWithinLimit(capture);
        assertEquals(ROWS, items.count());
    }

    @Test
    public void joinedInsertAndFetchIdsStaysWithinTheLimit(SqlCapture capture) {
        var pets = orm.entity(JoinedPet.class);
        List<Integer> ids = capture.execute(() -> pets.insertAndFetchIds(pets().stream(), 1000));
        assertDistinct(ids);
        assertWithinLimit(capture);
        assertEquals(ROWS, pets.count());
    }

    @Test
    public void joinedInsertStaysWithinTheLimit(SqlCapture capture) {
        var pets = orm.entity(JoinedPet.class);
        capture.record(() -> pets.insert(pets()));
        assertWithinLimit(capture);
        assertEquals(ROWS, pets.count());
        assertEquals(ROWS / 2, pets.select().getResultList().stream().filter(JoinedCat.class::isInstance).count());
    }

    @Test
    public void findAllByIdAtTheDefaultChunkSizeStaysWithinTheLimit(SqlCapture capture) {
        var items = orm.entity(NarrowItem.class);
        items.insert(narrowItems());
        var ids = IntStream.rangeClosed(1, 3000).boxed().toList();
        List<NarrowItem> found = capture.execute(() -> items.findAllById(ids));
        assertEquals(ROWS, found.size());
        assertWithinLimit(capture);
        assertEquals(List.of(1000, 1000, 1000), capture.statements().stream()
                .map(statement -> statement.parameters().size())
                .toList());
    }

    @Test
    public void removeByRefStaysWithinTheLimit(SqlCapture capture) {
        var pets = orm.entity(JoinedPet.class);
        var refs = pets.insertAndFetchIds(pets()).stream()
                .map(id -> orm.ref(JoinedPet.class, id))
                .toList();
        capture.record(() -> pets.removeByRef(refs));
        assertWithinLimit(capture);
        assertEquals(0, pets.count());
    }

    /**
     * A list the caller passes to the query builder renders a placeholder per value, so one longer than the limit
     * reaches SQL Server, which refuses it.
     */
    @Test
    public void anInListBeyondTheLimitFailsAtTheServer() {
        var items = orm.entity(NarrowItem.class);
        var values = IntStream.rangeClosed(1, MAX_BIND_PARAMETERS + 1).boxed().toList();
        assertEquals(0, items.select().where(NARROW_ID, IN, values.subList(0, MAX_BIND_PARAMETERS)).getResultCount());
        var failure = assertThrows(PersistenceException.class,
                () -> items.select().where(NARROW_ID, IN, values).getResultList());
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("maximum of 2100 parameters"), cause.getMessage());
    }
}
