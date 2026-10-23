package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbColumn;
import st.orm.core.template.impl.DatabaseSchema.DbForeignKey;
import st.orm.core.template.impl.DatabaseSchema.DbPrimaryKey;
import st.orm.core.template.impl.DatabaseSchema.DbUniqueKey;

/**
 * Tests for {@link DatabaseSchema} to cover schema reading, table/column/PK/UK/FK/sequence
 * lookups with various discovery strategies.
 */
class DatabaseSchemaTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private String jdbcUrl;

    @BeforeEach
    void setUp() {
        jdbcUrl = "jdbc:h2:mem:db_schema_test_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // Basic read() tests

    // Constraint discovery outcome

    @Test
    void testConstraintsAreDiscoveredWithAFittingStrategy() throws SQLException {
        execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
        execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.isDiscovered("child", ConstraintKind.PRIMARY_KEY));
            assertTrue(schema.isDiscovered("child", ConstraintKind.UNIQUE_KEY));
            assertTrue(schema.isDiscovered("child", ConstraintKind.FOREIGN_KEY));
            assertEquals(1, schema.getForeignKeys("child").size());
        }
    }

    @Test
    void testForeignKeysStayUnknownWhenTheStrategyDoesNotFitTheDatabase() throws SQLException {
        execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
        execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent (id))");
        try (Connection connection = getConnection()) {
            // INFORMATION_SCHEMA_REFERENCING reads KEY_COLUMN_USAGE.REFERENCED_TABLE_NAME, a column H2 does not
            // have, so the foreign key query fails and the foreign keys stay unknown.
            DatabaseSchema schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA_REFERENCING));
            assertFalse(schema.isDiscovered("child", ConstraintKind.FOREIGN_KEY));
            assertTrue(schema.getForeignKeys("child").isEmpty());
            // The keys come from a query H2 does understand, so they are known and were read.
            assertTrue(schema.isDiscovered("child", ConstraintKind.PRIMARY_KEY));
            assertEquals(1, schema.getPrimaryKeys("child").size());
            // A table nothing was read for is unknown rather than assumed empty.
            assertFalse(schema.isDiscovered("no_such_table", ConstraintKind.PRIMARY_KEY));
        }
    }

    /**
     * Wraps the connection so one {@link DatabaseMetaData} call fails, optionally only for one table, the way a
     * driver refuses a call it does not support for a particular relation.
     */
    private Connection metadataFailing(String failingMethod, String failingTable) throws SQLException {
        Connection delegate = getConnection();
        DatabaseMetaData realMetaData = delegate.getMetaData();
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(failingMethod)
                            && (failingTable == null || failingTable.equalsIgnoreCase(String.valueOf(args[2])))) {
                        throw new SQLException("metadata call not supported");
                    }
                    return invoke(method, realMetaData, args);
                });
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> method.getName().equals("getMetaData")
                        ? metaData
                        : invoke(method, delegate, args));
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private DatabaseSchema readWithJdbcMetadata(Connection connection) throws SQLException {
        return DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.JDBC_METADATA));
    }

    @Test
    void testAFailedReadForOneTableLeavesTheOthersDiscovered() throws SQLException {
        execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
        execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent (id))");
        try (Connection connection = metadataFailing("getImportedKeys", "CHILD")) {
            DatabaseSchema schema = readWithJdbcMetadata(connection);
            // Only the table whose read failed is unknown; the rest of the schema stays worth validating.
            assertFalse(schema.isDiscovered("child", ConstraintKind.FOREIGN_KEY));
            assertTrue(schema.isDiscovered("parent", ConstraintKind.FOREIGN_KEY));
        }
    }

    @Test
    void testAFailedUniqueKeyReadLeavesPrimaryKeysDiscovered() throws SQLException {
        execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
        try (Connection connection = metadataFailing("getIndexInfo", null)) {
            DatabaseSchema schema = readWithJdbcMetadata(connection);
            // Primary keys and unique keys are separate calls, so losing one keeps the other.
            assertFalse(schema.isDiscovered("parent", ConstraintKind.UNIQUE_KEY));
            assertTrue(schema.isDiscovered("parent", ConstraintKind.PRIMARY_KEY));
            assertEquals(1, schema.getPrimaryKeys("parent").size());
        }
    }

    @Test
    void testReadEmptySchema() throws SQLException {
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertFalse(schema.tableExists("nonexistent"));
        }
    }

    @Test
    void testTableExists() throws SQLException {
        execute("CREATE TABLE test_table (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.tableExists("test_table"));
            assertTrue(schema.tableExists("TEST_TABLE")); // case-insensitive
            assertFalse(schema.tableExists("no_such_table"));
        }
    }

    @Test
    void testGetColumn() throws SQLException {
        execute("CREATE TABLE col_test (id INTEGER AUTO_INCREMENT, name VARCHAR(100) NOT NULL, description VARCHAR(500), PRIMARY KEY (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            assertTrue(schema.getColumn("col_test", "id").isPresent());
            assertTrue(schema.getColumn("col_test", "name").isPresent());
            assertTrue(schema.getColumn("col_test", "description").isPresent());
            assertTrue(schema.getColumn("col_test", "NAME").isPresent()); // case-insensitive

            assertFalse(schema.getColumn("col_test", "nonexistent").isPresent());
            assertFalse(schema.getColumn("nonexistent_table", "id").isPresent());
        }
    }

    @Test
    void testGeneratedColumnIsReported() throws SQLException {
        execute("CREATE TABLE generated_test (id INTEGER AUTO_INCREMENT, name VARCHAR(100) NOT NULL, name_folded VARCHAR(100) GENERATED ALWAYS AS (LOWER(name)) NOT NULL, PRIMARY KEY (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            DbColumn foldedColumn = schema.getColumn("generated_test", "name_folded").orElseThrow();
            assertTrue(foldedColumn.generated());
            assertFalse(foldedColumn.nullable());

            DbColumn nameColumn = schema.getColumn("generated_test", "name").orElseThrow();
            assertFalse(nameColumn.generated());
        }
    }

    @Test
    void testGetColumnProperties() throws SQLException {
        execute("CREATE TABLE prop_test (id INTEGER AUTO_INCREMENT, name VARCHAR(100) NOT NULL, description VARCHAR(500), PRIMARY KEY (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            DbColumn idColumn = schema.getColumn("prop_test", "id").orElseThrow();
            assertTrue(idColumn.autoIncrement());

            DbColumn nameColumn = schema.getColumn("prop_test", "name").orElseThrow();
            assertFalse(nameColumn.nullable());
            assertFalse(nameColumn.autoIncrement());

            DbColumn descColumn = schema.getColumn("prop_test", "description").orElseThrow();
            assertTrue(descColumn.nullable());
        }
    }

    @Test
    void testGetPrimaryKeys() throws SQLException {
        execute("CREATE TABLE pk_test (id INTEGER, name VARCHAR(255), PRIMARY KEY (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("pk_test");
            assertFalse(primaryKeys.isEmpty());
            assertEquals(1, primaryKeys.size());
            assertEquals("ID", primaryKeys.getFirst().columnName());
        }
    }

    @Test
    void testGetCompoundPrimaryKeys() throws SQLException {
        execute("CREATE TABLE cpk_test (col_a INTEGER, col_b INTEGER, name VARCHAR(255), PRIMARY KEY (col_a, col_b))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("cpk_test");
            assertEquals(2, primaryKeys.size());
        }
    }

    @Test
    void testGetPrimaryKeysForNonExistentTable() throws SQLException {
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("nonexistent");
            assertTrue(primaryKeys.isEmpty());
        }
    }

    @Test
    void testGetUniqueKeys() throws SQLException {
        execute("CREATE TABLE uk_test (id INTEGER AUTO_INCREMENT, email VARCHAR(255) NOT NULL, PRIMARY KEY (id), UNIQUE (email))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            List<DbUniqueKey> uniqueKeys = schema.getUniqueKeys("uk_test");
            assertFalse(uniqueKeys.isEmpty());
        }
    }

    @Test
    void testGetUniqueKeysForNonExistentTable() throws SQLException {
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbUniqueKey> uniqueKeys = schema.getUniqueKeys("nonexistent");
            assertTrue(uniqueKeys.isEmpty());
        }
    }

    @Test
    void testGetForeignKeys() throws SQLException {
        execute("CREATE TABLE fk_parent (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");
        execute("CREATE TABLE fk_child (id INTEGER AUTO_INCREMENT, parent_id INTEGER, PRIMARY KEY (id), FOREIGN KEY (parent_id) REFERENCES fk_parent(id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            List<DbForeignKey> foreignKeys = schema.getForeignKeys("fk_child");
            assertFalse(foreignKeys.isEmpty());
            assertEquals(1, foreignKeys.size());
            DbForeignKey foreignKey = foreignKeys.getFirst();
            assertEquals("FK_PARENT", foreignKey.pkTableName());
        }
    }

    @Test
    void testGetForeignKeysForNonExistentTable() throws SQLException {
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("nonexistent");
            assertTrue(foreignKeys.isEmpty());
        }
    }

    @Test
    void testSequenceExists() throws SQLException {
        execute("CREATE SEQUENCE test_seq START WITH 1");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            assertTrue(schema.sequencesDiscovered());
            assertTrue(schema.sequenceExists("test_seq"));
            assertTrue(schema.sequenceExists("TEST_SEQ")); // case-insensitive
            assertFalse(schema.sequenceExists("nonexistent_seq"));
        }
    }

    // Discovery strategy tests

    @Test
    void testReadWithJdbcMetadataStrategy() throws SQLException {
        execute("CREATE TABLE jdbc_test (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE jdbc_ref (id INTEGER AUTO_INCREMENT, test_id INTEGER, PRIMARY KEY (id), FOREIGN KEY (test_id) REFERENCES jdbc_test(id))");
        execute("CREATE UNIQUE INDEX idx_name ON jdbc_test(name)");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.JDBC_METADATA));

            assertTrue(schema.tableExists("jdbc_test"));
            assertTrue(schema.getColumn("jdbc_test", "id").isPresent());

            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("jdbc_test");
            assertFalse(primaryKeys.isEmpty());

            List<DbUniqueKey> uniqueKeys = schema.getUniqueKeys("jdbc_test");
            assertFalse(uniqueKeys.isEmpty());

            List<DbForeignKey> foreignKeys = schema.getForeignKeys("jdbc_ref");
            assertFalse(foreignKeys.isEmpty());
        }
    }

    @Test
    void testReadWithInformationSchemaStrategy() throws SQLException {
        execute("CREATE TABLE info_test (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE info_ref (id INTEGER AUTO_INCREMENT, test_id INTEGER, PRIMARY KEY (id), FOREIGN KEY (test_id) REFERENCES info_test(id))");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA));

            assertTrue(schema.tableExists("info_test"));
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("info_test");
            assertFalse(primaryKeys.isEmpty());
        }
    }

    @Test
    void testReadWithNoneSequenceStrategy() throws SQLException {
        execute("CREATE TABLE none_seq_test (id INTEGER AUTO_INCREMENT, PRIMARY KEY (id))");
        execute("CREATE SEQUENCE my_seq START WITH 1");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(SequenceDiscoveryStrategy.NONE, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA));

            // With NONE strategy, sequences should not be discovered
            assertFalse(schema.sequencesDiscovered());
            assertFalse(schema.sequenceExists("my_seq"));
        }
    }

    // View test

    @Test
    void testViewExists() throws SQLException {
        execute("CREATE TABLE view_base (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");
        execute("CREATE VIEW view_test AS SELECT * FROM view_base");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.tableExists("view_test"));
            assertTrue(schema.getColumn("view_test", "id").isPresent());
        }
    }

    // Table with no columns case

    @Test
    void testTableWithMultipleForeignKeys() throws SQLException {
        execute("CREATE TABLE parent_a (id INTEGER AUTO_INCREMENT, PRIMARY KEY (id))");
        execute("CREATE TABLE parent_b (id INTEGER AUTO_INCREMENT, PRIMARY KEY (id))");
        execute("CREATE TABLE child_multi_fk (id INTEGER AUTO_INCREMENT, a_id INTEGER, b_id INTEGER, "
                + "PRIMARY KEY (id), "
                + "FOREIGN KEY (a_id) REFERENCES parent_a(id), "
                + "FOREIGN KEY (b_id) REFERENCES parent_b(id))");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            List<DbForeignKey> foreignKeys = schema.getForeignKeys("child_multi_fk");
            assertEquals(2, foreignKeys.size());
        }
    }

    @Test
    void testCompoundUniqueKey() throws SQLException {
        execute("CREATE TABLE compound_uk (id INTEGER AUTO_INCREMENT, col_a VARCHAR(50), col_b VARCHAR(50), "
                + "PRIMARY KEY (id), UNIQUE (col_a, col_b))");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);

            List<DbUniqueKey> uniqueKeys = schema.getUniqueKeys("compound_uk");
            // Compound UK should have 2 entries with the same index name
            assertTrue(uniqueKeys.size() >= 2);
        }
    }

    @Test
    void testReadWithNullCatalogAndSchema() throws SQLException {
        execute("CREATE TABLE null_ctx (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");

        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection, null, null,
                    DatabaseSchema.Discovery.of(SequenceDiscoveryStrategy.INFORMATION_SCHEMA, ConstraintDiscoveryStrategy.INFORMATION_SCHEMA));

            assertTrue(schema.tableExists("null_ctx"));
        }
    }

    @Test
    void testAutoIncrementColumnNotIncludedInPrimaryKeyMetadata() throws SQLException {
        execute("CREATE TABLE auto_pk_test (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            DbColumn idColumn = schema.getColumn("auto_pk_test", "id").orElseThrow();
            assertTrue(idColumn.autoIncrement());
            // The PK metadata should still correctly identify the auto-increment column.
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("auto_pk_test");
            assertEquals(1, primaryKeys.size());
            assertEquals("ID", primaryKeys.getFirst().columnName());
        }
    }

    @Test
    void testForeignKeyReferencesCorrectParentColumn() throws SQLException {
        execute("CREATE TABLE fk_detail_parent (id INTEGER AUTO_INCREMENT, code VARCHAR(50) UNIQUE, PRIMARY KEY (id))");
        execute("CREATE TABLE fk_detail_child (id INTEGER AUTO_INCREMENT, parent_id INTEGER, PRIMARY KEY (id), FOREIGN KEY (parent_id) REFERENCES fk_detail_parent(id))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("fk_detail_child");
            assertEquals(1, foreignKeys.size());
            DbForeignKey foreignKey = foreignKeys.getFirst();
            assertEquals("FK_DETAIL_CHILD", foreignKey.fkTableName());
            assertEquals("PARENT_ID", foreignKey.fkColumnName());
            assertEquals("FK_DETAIL_PARENT", foreignKey.pkTableName());
            assertEquals("ID", foreignKey.pkColumnName());
        }
    }

    @Test
    void testUniqueKeyColumnNamesAndOrdering() throws SQLException {
        execute("CREATE TABLE uk_detail (id INTEGER AUTO_INCREMENT, first_name VARCHAR(50), last_name VARCHAR(50), "
                + "PRIMARY KEY (id), UNIQUE (last_name, first_name))");
        try (Connection connection = getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbUniqueKey> uniqueKeys = schema.getUniqueKeys("uk_detail");
            // The compound unique key should have 2 entries sharing the same index name.
            assertEquals(2, uniqueKeys.size());
            String indexName = uniqueKeys.getFirst().indexName();
            assertTrue(uniqueKeys.stream().allMatch(uk -> uk.indexName().equals(indexName)),
                    "All UK entries should share the same index name");
            // Verify both columns are present.
            assertTrue(uniqueKeys.stream().anyMatch(uk -> uk.columnName().equals("LAST_NAME")));
            assertTrue(uniqueKeys.stream().anyMatch(uk -> uk.columnName().equals("FIRST_NAME")));
        }
    }
}
