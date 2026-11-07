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
package st.orm.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.repository.EntityRepository;

/**
 * A fetching stream write with a batch size executes one batch per that many entities, for a batch size above the
 * default of 1000 as below it, before it fetches. The writes run against a data source that counts the batches its
 * prepared statements execute.
 */
class StreamBatchSizeTest {

    @DbTable("stream_item")
    public record StreamItem(@PK Integer id, String name) implements Entity<Integer> {}

    private static final int ROWS = 2500;

    private final AtomicInteger batches = new AtomicInteger();
    private EntityRepository<StreamItem, Integer> items;

    @BeforeEach
    void setUp() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:stream_batch_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stream_item (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
        }
        items = ORMTemplate.of(counting(h2)).entity(StreamItem.class);
    }

    /** Wraps the data source so every batch its prepared statements execute is counted. */
    private DataSource counting(DataSource target) {
        return proxy(DataSource.class, target, (method, result) -> result instanceof Connection connection
                ? proxy(Connection.class, connection, (connectionMethod, connectionResult) ->
                        connectionResult instanceof PreparedStatement statement
                                ? proxy(PreparedStatement.class, statement, (statementMethod, statementResult) -> {
                                    if (statementMethod.getName().equals("executeBatch")) {
                                        batches.incrementAndGet();
                                    }
                                    return statementResult;
                                })
                                : connectionResult)
                : result);
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(Method method, Object result);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Observer observer) {
        return (T) Proxy.newProxyInstance(StreamBatchSizeTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(target, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    return observer.observe(method, result);
                });
    }

    private static List<StreamItem> newItems() {
        return IntStream.rangeClosed(1, ROWS).mapToObj(index -> new StreamItem(null, "item" + index)).toList();
    }

    /** Inserts the rows and returns them as read, with the ids they were given. */
    private List<StreamItem> existingItems() {
        items.insert(newItems());
        return items.findAll();
    }

    /** Runs the write and asserts that it executed one batch per batch size. */
    private <T> T assertBatches(int batchSize, Supplier<T> write) {
        batches.set(0);
        T result = write.get();
        assertEquals((ROWS + batchSize - 1) / batchSize, batches.get(), "batches for a batch size of " + batchSize);
        return result;
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    void insertAndFetchIdsTakesTheBatchSize(int batchSize) {
        var ids = assertBatches(batchSize, () -> items.insertAndFetchIds(newItems().stream(), batchSize));
        assertEquals(ROWS, new HashSet<>(ids).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    void insertAndFetchTakesTheBatchSize(int batchSize) {
        var inserted = assertBatches(batchSize, () -> items.insertAndFetch(newItems().stream(), batchSize));
        assertEquals(ROWS, inserted.size());
        assertTrue(inserted.stream().allMatch(item -> item.id() != null));
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    void updateAndFetchTakesTheBatchSize(int batchSize) {
        var existing = existingItems();
        var updated = assertBatches(batchSize, () -> items.updateAndFetch(
                existing.stream().map(item -> new StreamItem(item.id(), "updated")), batchSize));
        assertEquals(ROWS, updated.size());
        assertTrue(updated.stream().allMatch(item -> item.name().equals("updated")));
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    void upsertAndFetchIdsTakesTheBatchSize(int batchSize) {
        var existing = existingItems();
        var ids = assertBatches(batchSize, () -> items.upsertAndFetchIds(
                existing.stream().map(item -> new StreamItem(item.id(), "upserted")), batchSize));
        assertEquals(new HashSet<>(existing.stream().map(StreamItem::id).toList()), new HashSet<>(ids));
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 700})
    void upsertAndFetchTakesTheBatchSize(int batchSize) {
        var existing = existingItems();
        var upserted = assertBatches(batchSize, () -> items.upsertAndFetch(
                existing.stream().map(item -> new StreamItem(item.id(), "upserted")), batchSize));
        assertEquals(ROWS, upserted.size());
        assertTrue(upserted.stream().allMatch(item -> item.name().equals("upserted")));
    }
}
