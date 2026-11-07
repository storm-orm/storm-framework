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
package st.orm.spi.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.core.repository.EntityRepository;
import st.orm.core.template.ORMTemplate;

/**
 * An insert that binds its primary key runs as multi-row statements on a database that supports them. A statement
 * takes at most the batch size in rows, and at most the rows whose bound values fit the dialect's bind parameter
 * limit: ten bound columns against a limit of 32767 values make 3276 rows. The insert runs against a data source
 * that counts the insert statements it prepares and the batches it executes.
 */
class H2MultiRowInsertBatchSizeTest {

    @DbTable("wide_item")
    public record WideItem(@PK Integer id, String c1, String c2, String c3, String c4, String c5, String c6,
                           String c7, String c8, String c9) implements Entity<Integer> {}

    private static final int ROWS = 4000;

    private final AtomicInteger insertStatements = new AtomicInteger();
    private final AtomicInteger batches = new AtomicInteger();
    private EntityRepository<WideItem, Integer> items;

    @BeforeEach
    void setUp() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:multi_row_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE wide_item (id INTEGER AUTO_INCREMENT PRIMARY KEY, c1 VARCHAR(10), "
                    + "c2 VARCHAR(10), c3 VARCHAR(10), c4 VARCHAR(10), c5 VARCHAR(10), c6 VARCHAR(10), "
                    + "c7 VARCHAR(10), c8 VARCHAR(10), c9 VARCHAR(10))");
        }
        items = ORMTemplate.of(counting(h2)).entity(WideItem.class);
    }

    /** Wraps the data source so the insert statements it prepares and the batches it executes are counted. */
    private DataSource counting(DataSource target) {
        return proxy(DataSource.class, target, (method, arguments, result) -> result instanceof Connection connection
                ? proxy(Connection.class, connection, (connectionMethod, connectionArguments, connectionResult) -> {
                    if (connectionResult instanceof PreparedStatement statement) {
                        if (connectionArguments[0] instanceof String sql && sql.strip().toUpperCase().startsWith("INSERT")) {
                            insertStatements.incrementAndGet();
                        }
                        return proxy(PreparedStatement.class, statement, (statementMethod, statementArguments, statementResult) -> {
                            if (statementMethod.getName().equals("executeBatch")) {
                                batches.incrementAndGet();
                            }
                            return statementResult;
                        });
                    }
                    return connectionResult;
                })
                : result);
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(Method method, Object[] arguments, Object result);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Observer observer) {
        return (T) Proxy.newProxyInstance(H2MultiRowInsertBatchSizeTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(target, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    return observer.observe(method, arguments, result);
                });
    }

    private static List<WideItem> rows() {
        return IntStream.rangeClosed(1, ROWS)
                .mapToObj(id -> new WideItem(id, "a", "b", "c", "d", "e", "f", "g", "h", "i"))
                .toList();
    }

    private void assertStatements(int expected) {
        assertEquals(expected, insertStatements.get(), "multi-row insert statements");
        assertEquals(0, batches.get(), "batches");
        assertEquals(ROWS, items.count());
    }

    @Test
    void batchSizeAboveTheBindLimitTakesTheRowsTheLimitAllows() {
        items.insert(rows().stream(), ROWS, true);
        assertStatements(2);
    }

    @Test
    void batchSizeBelowTheBindLimitTakesTheBatchSize() {
        items.insert(rows().stream(), 1500, true);
        assertStatements(3);
    }

    @Test
    void collectionTakesTheDefaultBatchSize() {
        items.insert(rows(), true);
        assertStatements(4);
    }
}
