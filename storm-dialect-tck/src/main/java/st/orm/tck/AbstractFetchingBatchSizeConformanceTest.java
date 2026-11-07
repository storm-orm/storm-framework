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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.GenerationStrategy.SEQUENCE;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlDialect;

/**
 * Batch size conformance of the fetching writes: {@code insertAndFetchIds}, {@code insertAndFetch},
 * {@code updateAndFetch}, {@code upsertAndFetchIds} and {@code upsertAndFetch} write a stream in batches of the
 * size they are given, for a size above the default of 1000 as below it, on every database and through every path a
 * dialect writes them by.
 *
 * <p>The writes run against a data source that records each write the database executes and the rows it wrote: a
 * JDBC batch writes as many rows as it holds, and a multi-row statement as many as it reports. A batch takes the batch
 * size in rows. A multi-row statement takes at most the batch size in rows, and at most the rows its bound values
 * allow, as {@link #statementRowLimit(SqlDialect, int)} states.</p>
 *
 * <p>The suite creates and drops its own tables, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractFetchingBatchSizeConformanceTest {

    private static final int ROWS = 2500;

    /** A table whose key the database generates as an identity. */
    @DbTable("batch_item")
    public record BatchItem(@PK Integer id, String name) implements Entity<Integer> {}

    /** A table whose key comes from the sequence {@code batch_item_seq}. */
    @DbTable("batch_sequence_item")
    public record BatchSequenceItem(@PK(generation = SEQUENCE, sequence = "batch_item_seq") Integer id, String name)
            implements Entity<Integer> {}

    /** A table whose key the database draws from the sequence its column default names. */
    @DbTable("batch_sequence_item")
    public record BatchDefaultSequenceItem(@PK(generation = SEQUENCE) Integer id, String name)
            implements Entity<Integer> {}

    /** A write the database executed: a JDBC batch or a single statement, and the rows it wrote. */
    private record Write(boolean batch, AtomicInteger rows) {}

    private final List<Write> writes = new CopyOnWriteArrayList<>();
    private DataSource dataSource;
    private ORMTemplate orm;

    /**
     * The tables of the suite, in the order they must be created: {@code batch_item} with an identity key of type
     * integer and a {@code name} column, and, where the dialect fetches sequence keys, the sequence
     * {@code batch_item_seq} and {@code batch_sequence_item} with an integer key and a {@code name} column.
     */
    protected abstract List<String> schemaDdl();

    /** The statements that drop what {@link #schemaDdl()} creates, in the order they must run. */
    protected abstract List<String> dropDdl();

    /** Whether the dialect can hand back generated keys for a sequence-keyed entity it inserts. */
    protected boolean supportsFetchWithSequences() {
        return true;
    }

    /** Whether the dialect can hand back generated keys for a batch of sequence-keyed entities it upserts. */
    protected boolean supportsBatchUpsertAndFetchWithSequences() {
        return supportsFetchWithSequences();
    }

    /**
     * Whether the dialect can hand back generated keys for a batch of entities it upserts whose key the database
     * draws from the sequence the column default names, without the entity naming the sequence.
     */
    protected boolean supportsBatchUpsertAndFetchWithDefaultSequences() {
        return false;
    }

    /**
     * The most rows a multi-row statement with the given number of bound columns per row takes: the rows whose
     * bound values fit the dialect's bind parameter limit. A dialect with a row limit of its own lowers it.
     */
    protected int statementRowLimit(SqlDialect dialect, int boundColumns) {
        return dialect.maxBindParameters() / boundColumns;
    }

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
            for (String ddl : schemaDdl()) {
                statement.execute(ddl);
            }
        }
        orm = ORMTemplate.of(recording(dataSource));
    }

    @AfterEach
    final void dropTables() {
        for (String ddl : dropDdl()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            } catch (SQLException ignored) {
                // The object was not there to drop.
            }
        }
    }

    /** Wraps the data source so every write its prepared statements execute is recorded with the rows it wrote. */
    private DataSource recording(DataSource target) {
        return proxy(DataSource.class, target, (methodName, arguments, result) -> result instanceof Connection connection
                ? proxy(Connection.class, connection, (connectionMethod, connectionArguments, connectionResult) ->
                        connectionResult instanceof PreparedStatement statement
                                && connectionArguments[0] instanceof String sql && isWrite(sql)
                                ? recording(statement)
                                : connectionResult)
                : result);
    }

    private PreparedStatement recording(PreparedStatement statement) {
        return proxy(PreparedStatement.class, statement, (methodName, arguments, result) -> switch (methodName) {
            case "executeBatch" -> {
                writes.add(new Write(true, new AtomicInteger(((int[]) result).length)));
                yield result;
            }
            case "executeUpdate" -> {
                writes.add(new Write(false, new AtomicInteger((int) result)));
                yield result;
            }
            case "executeQuery" -> {
                var rows = new AtomicInteger();
                writes.add(new Write(false, rows));
                yield proxy(ResultSet.class, (ResultSet) result, (resultMethod, resultArguments, resultValue) -> {
                    if (resultMethod.equals("next") && Boolean.TRUE.equals(resultValue)) {
                        rows.incrementAndGet();
                    }
                    return resultValue;
                });
            }
            default -> result;
        });
    }

    private static boolean isWrite(String sql) {
        String statement = sql.strip().toUpperCase(Locale.ROOT);
        return statement.startsWith("INSERT") || statement.startsWith("UPDATE") || statement.startsWith("MERGE");
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(String methodName, Object[] arguments, Object result) throws SQLException;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Observer observer) {
        return (T) Proxy.newProxyInstance(AbstractFetchingBatchSizeConformanceTest.class.getClassLoader(),
                new Class<?>[] {type}, (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(target, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    return observer.observe(method.getName(), arguments, result);
                });
    }

    private static List<BatchItem> newItems() {
        return IntStream.rangeClosed(1, ROWS).mapToObj(index -> new BatchItem(null, "item" + index)).toList();
    }

    private static List<BatchSequenceItem> newSequenceItems() {
        return IntStream.rangeClosed(1, ROWS).mapToObj(index -> new BatchSequenceItem(null, "item" + index)).toList();
    }

    /** Inserts the rows and returns them as read, with the ids they were given. */
    private List<BatchItem> existingItems() {
        var items = orm.entity(BatchItem.class);
        items.insert(newItems());
        return items.findAll();
    }

    /**
     * How a write splits its rows into multi-row statements. An insert binds the name of each row and splits the
     * stream into statements of the batch size bounded by the statement row limit. An upsert binds the key and the
     * name of each row and splits each batch into statements of at most the statement row limit, since the batch
     * also holds the rows it updates.
     */
    private enum Shape {
        INSERT(1),
        UPSERT(2);

        final int boundColumns;

        Shape(int boundColumns) {
            this.boundColumns = boundColumns;
        }
    }

    /** Splits the rows into consecutive parts of at most the given size. */
    private static List<Integer> split(int rows, int size) {
        var parts = new ArrayList<Integer>();
        for (int written = 0; written < rows; written += size) {
            parts.add(Math.min(size, rows - written));
        }
        return parts;
    }

    /**
     * Runs the write and asserts the rows each write of it took: the batch size for a JDBC batch, and for a
     * multi-row statement the rows its shape gives it.
     *
     * @param batchSize the batch size the write is given.
     * @param shape how the write splits its rows into multi-row statements.
     */
    private <T> T assertWrites(int batchSize, Shape shape, Supplier<T> write) {
        writes.clear();
        T result = write.get();
        assertTrue(!writes.isEmpty(), "no write was executed");
        boolean batch = writes.getFirst().batch();
        assertTrue(writes.stream().allMatch(executed -> executed.batch() == batch), () -> "mixed writes: " + writes);
        int statementRows = statementRowLimit(orm.dialect(), shape.boundColumns);
        List<Integer> expected;
        if (batch) {
            expected = split(ROWS, batchSize);
        } else if (shape == Shape.INSERT) {
            expected = split(ROWS, Math.min(batchSize, statementRows));
        } else {
            expected = split(ROWS, batchSize).stream().flatMap(rows -> split(rows, statementRows).stream()).toList();
        }
        assertEquals(expected, writes.stream().map(executed -> executed.rows().get()).toList(),
                () -> (batch ? "batches" : "statements") + " for a batch size of " + batchSize);
        return result;
    }

    private List<Integer> rowsWritten() {
        return writes.stream().map(executed -> executed.rows().get()).toList();
    }

    private static void assertDistinctIds(List<Integer> ids) {
        assertEquals(ROWS, ids.size());
        assertEquals(ROWS, new HashSet<>(ids).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void insertAndFetchIdsTakesTheBatchSize(int batchSize) {
        var items = orm.entity(BatchItem.class);
        var ids = assertWrites(batchSize, Shape.INSERT, () -> items.insertAndFetchIds(newItems().stream(), batchSize));
        assertDistinctIds(ids);
        assertEquals(ROWS, items.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void insertAndFetchTakesTheBatchSize(int batchSize) {
        var items = orm.entity(BatchItem.class);
        var inserted = assertWrites(batchSize, Shape.INSERT, () -> items.insertAndFetch(newItems().stream(), batchSize));
        assertDistinctIds(inserted.stream().map(BatchItem::id).toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void updateAndFetchTakesTheBatchSize(int batchSize) {
        var items = orm.entity(BatchItem.class);
        var existing = existingItems();
        var updated = assertWrites(batchSize, Shape.UPSERT, () -> items.updateAndFetch(
                existing.stream().map(item -> new BatchItem(item.id(), "updated")), batchSize));
        assertEquals(ROWS, updated.size());
        assertTrue(updated.stream().allMatch(item -> item.name().equals("updated")));
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void upsertAndFetchIdsOfNewEntitiesTakesTheBatchSize(int batchSize) {
        var items = orm.entity(BatchItem.class);
        var ids = assertWrites(batchSize, Shape.UPSERT, () -> items.upsertAndFetchIds(newItems().stream(), batchSize));
        assertDistinctIds(ids);
        assertEquals(ROWS, items.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void upsertAndFetchIdsOfExistingEntitiesTakesTheBatchSize(int batchSize) {
        var items = orm.entity(BatchItem.class);
        var existing = existingItems();
        var ids = assertWrites(batchSize, Shape.UPSERT, () -> items.upsertAndFetchIds(
                existing.stream().map(item -> new BatchItem(item.id(), "upserted")), batchSize));
        assertEquals(new HashSet<>(existing.stream().map(BatchItem::id).toList()), new HashSet<>(ids));
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void upsertAndFetchTakesTheBatchSize(int batchSize) {
        var items = orm.entity(BatchItem.class);
        var upserted = assertWrites(batchSize, Shape.UPSERT, () -> items.upsertAndFetch(newItems().stream(), batchSize));
        assertDistinctIds(upserted.stream().map(BatchItem::id).toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void insertAndFetchIdsWithSequenceTakesTheBatchSize(int batchSize) {
        assumeTrue(supportsFetchWithSequences());
        var items = orm.entity(BatchSequenceItem.class);
        var ids = assertWrites(batchSize, Shape.INSERT, () -> items.insertAndFetchIds(newSequenceItems().stream(), batchSize));
        assertDistinctIds(ids);
        assertEquals(ROWS, items.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void upsertAndFetchIdsWithSequenceTakesTheBatchSize(int batchSize) {
        assumeTrue(supportsBatchUpsertAndFetchWithSequences());
        var items = orm.entity(BatchSequenceItem.class);
        var ids = assertWrites(batchSize, Shape.UPSERT, () -> items.upsertAndFetchIds(newSequenceItems().stream(), batchSize));
        assertDistinctIds(ids);
        assertEquals(ROWS, items.count());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    public void upsertAndFetchIdsWithDefaultSequenceTakesTheBatchSize(int batchSize) {
        assumeTrue(supportsBatchUpsertAndFetchWithDefaultSequences());
        var items = orm.entity(BatchDefaultSequenceItem.class);
        var newItems = IntStream.rangeClosed(1, ROWS)
                .mapToObj(index -> new BatchDefaultSequenceItem(null, "item" + index));
        var ids = assertWrites(batchSize, Shape.UPSERT, () -> items.upsertAndFetchIds(newItems, batchSize));
        assertDistinctIds(ids);
        assertEquals(ROWS, items.count());
    }

    @Test
    public void fetchingWritesOfAnEmptyStreamWriteNothing() {
        var items = orm.entity(BatchItem.class);
        assertEquals(List.of(), items.insertAndFetchIds(Stream.empty(), 10));
        assertEquals(List.of(), items.insertAndFetch(Stream.empty(), 10));
        assertEquals(List.of(), items.updateAndFetch(Stream.empty(), 10));
        assertEquals(List.of(), items.upsertAndFetchIds(Stream.empty(), 10));
        assertEquals(List.of(), items.upsertAndFetch(Stream.empty(), 10));
        assertEquals(List.of(), writes);
    }

    @Test
    public void batchSizeOfOneWritesEachEntityAlone() {
        var items = orm.entity(BatchItem.class);
        var first = newItems().subList(0, 3);
        var ids = items.insertAndFetchIds(first.stream(), 1);
        assertEquals(List.of(1, 1, 1), rowsWritten());
        writes.clear();
        items.updateAndFetch(ids.stream().map(id -> new BatchItem(id, "updated")), 1);
        assertEquals(List.of(1, 1, 1), rowsWritten());
        writes.clear();
        items.upsertAndFetchIds(newItems().subList(0, 3).stream(), 1);
        assertEquals(List.of(1, 1, 1), rowsWritten());
        assertEquals(6, items.count());
    }

    @Test
    public void batchSizeBelowOneIsRefused() {
        var items = orm.entity(BatchItem.class);
        assertThrows(IllegalArgumentException.class, () -> items.insertAndFetchIds(newItems().stream(), 0));
        assertThrows(IllegalArgumentException.class, () -> items.insertAndFetch(newItems().stream(), 0));
        assertThrows(IllegalArgumentException.class, () -> items.updateAndFetch(newItems().stream(), 0));
        assertThrows(IllegalArgumentException.class, () -> items.upsertAndFetchIds(newItems().stream(), 0));
        assertThrows(IllegalArgumentException.class, () -> items.upsertAndFetch(newItems().stream(), 0));
        assertEquals(0, items.count());
    }
}
