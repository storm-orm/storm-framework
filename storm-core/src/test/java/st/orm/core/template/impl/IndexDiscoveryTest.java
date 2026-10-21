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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.UK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.IndexDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;

/**
 * Index discovery reads every index of a table, unique and non-unique, column by column; a read the database cannot
 * answer leaves the indexes unknown rather than empty.
 */
class IndexDiscoveryTest {

    public record Probe(@PK Integer id, @UK String email) implements Entity<Integer> {
    }

    private String jdbcUrl;

    @BeforeEach
    void database() throws SQLException {
        jdbcUrl = "jdbc:h2:mem:index_discovery_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY, email VARCHAR(255), name VARCHAR(255), age INTEGER)");
            statement.execute("CREATE INDEX probe_name_age ON probe (name, age)");
            statement.execute("CREATE UNIQUE INDEX probe_age ON probe (age)");
        }
    }

    private DatabaseSchema read(Connection connection, IndexDiscoveryStrategy strategy) throws SQLException {
        return DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA, strategy);
    }

    private static List<DbIndex> index(DatabaseSchema schema, String name) {
        return schema.getIndexes("probe").stream()
                .filter(index -> index.indexName().equalsIgnoreCase(name))
                .toList();
    }

    @Test
    void indexesAreReadColumnByColumn() throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl)) {
            var schema = read(connection, IndexDiscoveryStrategy.JDBC_METADATA);
            assertTrue(schema.isDiscovered("probe", ConstraintKind.INDEX));
            var composite = index(schema, "probe_name_age");
            assertEquals(2, composite.size(), composite.toString());
            assertEquals("NAME", composite.get(0).columnName());
            assertEquals(1, composite.get(0).ordinalPosition());
            assertEquals("AGE", composite.get(1).columnName());
            assertEquals(2, composite.get(1).ordinalPosition());
            assertFalse(composite.get(0).unique());
            var unique = index(schema, "probe_age");
            assertEquals(1, unique.size(), unique.toString());
            assertTrue(unique.getFirst().unique());
            assertFalse(unique.getFirst().partial());
            assertTrue(schema.getIndexes("no_such_table").isEmpty());
        }
    }

    @Test
    void aCatalogTheDatabaseLacksLeavesTheIndexesUnknown() throws SQLException {
        for (var strategy : List.of(IndexDiscoveryStrategy.PG_INDEX, IndexDiscoveryStrategy.ALL_IND_COLUMNS,
                IndexDiscoveryStrategy.INFORMATION_SCHEMA_STATISTICS)) {
            try (var connection = DriverManager.getConnection(jdbcUrl)) {
                var schema = read(connection, strategy);
                assertFalse(schema.isDiscovered("probe", ConstraintKind.INDEX), strategy.name());
                assertTrue(schema.getIndexes("probe").isEmpty(), strategy.name());
                // The rest of the schema is read as usual.
                assertTrue(schema.isDiscovered("probe", ConstraintKind.PRIMARY_KEY), strategy.name());
            }
        }
    }

    @Test
    void aFailedIndexReadLeavesTheIndexesUnknown() throws SQLException {
        try (var connection = stub((method, args) -> {
            throw new SQLException("metadata call not supported");
        })) {
            var schema = read(connection, IndexDiscoveryStrategy.JDBC_METADATA);
            assertFalse(schema.isDiscovered("probe", ConstraintKind.INDEX));
            assertTrue(schema.getIndexes("probe").isEmpty());
        }
    }

    @Test
    void statisticRowsAreSkippedAndAFilterMakesAnIndexPartial() throws SQLException {
        var statistic = row(DatabaseMetaData.tableIndexStatistic, null, null, 0, false, null);
        var unnamed = row(DatabaseMetaData.tableIndexOther, null, "email", 1, false, null);
        var partial = row(DatabaseMetaData.tableIndexOther, "probe_recent", "email", 1, true, "age > 18");
        var blank = row(DatabaseMetaData.tableIndexOther, "probe_full", "name", 1, true, " ");
        try (var connection = stub((method, args) -> resultSet(List.of(statistic, unnamed, partial, blank)))) {
            var schema = read(connection, IndexDiscoveryStrategy.JDBC_METADATA);
            var indexes = schema.getIndexes("probe");
            assertEquals(2, indexes.size(), indexes.toString());
            assertTrue(index(schema, "probe_recent").getFirst().partial());
            assertFalse(index(schema, "probe_full").getFirst().partial());
        }
    }

    @Test
    void aUniqueIndexOnPartOfTheRowsOrOnAnExpressionLeavesAUniqueKeyMissing() throws SQLException {
        var partial = row(DatabaseMetaData.tableIndexOther, "probe_email_recent", "email", 1, false, "age > 18");
        var expression = row(DatabaseMetaData.tableIndexOther, "probe_email_lower", null, 1, false, null);
        DataSource dataSource = dataSource(() -> stub((method, args) -> resultSet(List.of(partial, expression))));
        var errors = SchemaValidator.of(dataSource).validate(List.of(Probe.class));
        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING), errors.toString());
        // A unique index on the column and an expression is unique for the pair, not for the column.
        var mixedColumn = row(DatabaseMetaData.tableIndexOther, "probe_email_mixed", "email", 1, false, null);
        var mixedExpression = row(DatabaseMetaData.tableIndexOther, "probe_email_mixed", null, 2, false, null);
        DataSource mixed = dataSource(() -> stub((method, args) -> resultSet(List.of(mixedColumn, mixedExpression))));
        var mixedErrors = SchemaValidator.of(mixed).validate(List.of(Probe.class));
        assertTrue(mixedErrors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                mixedErrors.toString());
        var whole = row(DatabaseMetaData.tableIndexOther, "probe_email", "email", 1, false, null);
        DataSource enforced = dataSource(() -> stub((method, args) -> resultSet(List.of(whole))));
        var none = SchemaValidator.of(enforced).validate(List.of(Probe.class));
        assertFalse(none.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING), none.toString());
    }

    @Test
    void anExpressionIndexServesNoColumn() throws SQLException {
        var expression = row(DatabaseMetaData.tableIndexOther, "probe_email_lower", null, 1, false, null);
        try (var connection = stub((method, args) -> resultSet(List.of(expression)))) {
            var catalog = IndexCatalog.of(Map.of("", read(connection, IndexDiscoveryStrategy.JDBC_METADATA)));
            var indexes = catalog.find("", "probe");
            assertTrue(indexes != null && indexes.leadingColumns().contains("id"), String.valueOf(indexes));
            assertFalse(indexes.leadsWith("email"));
            assertNull(catalog.find("other", "probe"));
        }
    }

    @Test
    void anInterfaceWithoutASchemaToLoadRefusesTheLoad() {
        var template = (ORMTemplate) Proxy.newProxyInstance(ORMTemplate.class.getClassLoader(),
                new Class<?>[] {ORMTemplate.class}, (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args));
        assertThrows(PersistenceException.class, template::loadSchema);
        assertThrows(PersistenceException.class, () -> template.loadSchema(type -> true));
    }

    /** One getIndexInfo row, with the columns the index read uses. */
    private static Map<String, Object> row(short type, String indexName, String columnName, int ordinalPosition,
                                           boolean nonUnique, String filterCondition) {
        var row = new HashMap<String, Object>();
        row.put("TYPE", type);
        row.put("INDEX_NAME", indexName);
        row.put("COLUMN_NAME", columnName);
        row.put("ORDINAL_POSITION", (short) ordinalPosition);
        row.put("NON_UNIQUE", nonUnique);
        row.put("FILTER_CONDITION", filterCondition);
        return row;
    }

    /** A result set over the given rows, answering the getters the index read calls. */
    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        var iterator = rows.iterator();
        @SuppressWarnings("unchecked")
        Map<String, Object>[] current = new Map[1];
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        current[0] = iterator.hasNext() ? iterator.next() : null;
                        yield current[0] != null;
                    }
                    case "getString" -> (String) current[0].get((String) args[0]);
                    case "getShort" -> current[0].get((String) args[0]) instanceof Short value ? value : (short) 0;
                    case "getBoolean" -> Boolean.TRUE.equals(current[0].get((String) args[0]));
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @FunctionalInterface
    private interface IndexInfo {
        ResultSet answer(Method method, Object[] args) throws SQLException;
    }

    /** A connection to the test database whose metadata answers getIndexInfo with the given answer. */
    private Connection stub(IndexInfo indexInfo) throws SQLException {
        Connection delegate = DriverManager.getConnection(jdbcUrl);
        DatabaseMetaData real = delegate.getMetaData();
        var metaData = (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, (proxy, method, args) -> method.getName().equals("getIndexInfo")
                        ? indexInfo.answer(method, args)
                        : invoke(method, real, args));
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> method.getName().equals("getMetaData") ? metaData : invoke(method, delegate, args));
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    /** A data source handing out the given connections. */
    private static DataSource dataSource(ConnectionSupplier connections) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connections.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
