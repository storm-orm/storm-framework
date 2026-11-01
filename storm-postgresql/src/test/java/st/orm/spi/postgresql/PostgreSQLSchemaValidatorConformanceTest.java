package st.orm.spi.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.StormConfig;
import st.orm.core.spi.Providers;
import st.orm.core.template.impl.DatabaseSchema;
import st.orm.core.template.impl.SchemaValidator;
import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class PostgreSQLSchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {
    private static PostgreSQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:17");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected String expressionIndexDdl() {
        return "CREATE UNIQUE INDEX index_probe_lower ON index_probe ((lower(a)))";
    }

    @Test
    public void testAPartialIndexIsMarked() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE index_partial (id INTEGER NOT NULL PRIMARY KEY, a VARCHAR(50), c INTEGER)");
            try {
                statement.execute("CREATE INDEX index_partial_a ON index_partial (a) WHERE c > 0");
                var schema = readSchema(connection);
                var partial = schema.getIndexes("index_partial").stream()
                        .filter(index -> index.indexName().equals("index_partial_a"))
                        .toList();
                assertEquals(1, partial.size(), () -> schema.getIndexes("index_partial").toString());
                assertTrue(partial.getFirst().partial());
            } finally {
                statement.execute("DROP TABLE index_partial");
            }
        }
    }

    @DbTable("partitioned_visit")
    public record PartitionedVisit(@PK Integer id, Vet vet, @Nullable String description) implements Entity<Integer> {}

    @Test
    public void testAPartitionedTableIsValidatedAsATable() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE partitioned_visit (id INTEGER NOT NULL PRIMARY KEY, "
                    + "vet_id INTEGER NOT NULL REFERENCES vet (id), description VARCHAR(255)) PARTITION BY HASH (id)");
            statement.execute("CREATE TABLE partitioned_visit_0 PARTITION OF partitioned_visit "
                    + "FOR VALUES WITH (MODULUS 2, REMAINDER 0)");
            statement.execute("CREATE TABLE partitioned_visit_1 PARTITION OF partitioned_visit "
                    + "FOR VALUES WITH (MODULUS 2, REMAINDER 1)");
            try {
                var errors = SchemaValidator.of(dataSource).validate(List.of(PartitionedVisit.class));
                assertTrue(errors.isEmpty(), () -> "Expected no errors but got: " + errors);
                var schema = readSchema(connection);
                assertEquals(1, schema.getPrimaryKeys("partitioned_visit").size());
                assertEquals(1, schema.getForeignKeys("partitioned_visit").size());
                assertFalse(schema.getIndexes("partitioned_visit").isEmpty());
                // The partitions are tables of their own as well.
                assertTrue(schema.tableExists("partitioned_visit_0"));
            } finally {
                statement.execute("DROP TABLE partitioned_visit");
            }
        }
    }

    @DbTable("vet_name")
    public record VetName(@PK(constraint = false) Integer id, String firstName) implements Entity<Integer> {}

    @Test
    public void testAMaterializedViewIsValidatedAsAViewAndItsIndexIsRead() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE MATERIALIZED VIEW vet_name AS SELECT id, first_name FROM vet");
            try {
                statement.execute("CREATE INDEX vet_name_first_name ON vet_name (first_name)");
                // A materialized view declares no column NOT NULL, so a non-null field passes as on a view.
                var errors = SchemaValidator.of(dataSource).validate(List.of(VetName.class));
                assertTrue(errors.isEmpty(), () -> "Expected no errors but got: " + errors);
                var schema = readSchema(connection);
                assertTrue(schema.isView("vet_name"));
                assertTrue(schema.getIndexes("vet_name").stream()
                        .anyMatch(index -> index.indexName().equals("vet_name_first_name")));
            } finally {
                statement.execute("DROP MATERIALIZED VIEW vet_name");
            }
        }
    }

    @Test
    public void testAForeignTableIsRead() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS file_fdw");
            statement.execute("CREATE SERVER vet_files FOREIGN DATA WRAPPER file_fdw");
            statement.execute("CREATE FOREIGN TABLE vet_import (id INTEGER, first_name VARCHAR(255)) "
                    + "SERVER vet_files OPTIONS (filename '/dev/null', format 'csv')");
            try {
                var schema = readSchema(connection);
                assertTrue(schema.tableExists("vet_import"));
                assertTrue(schema.getColumn("vet_import", "first_name").isPresent());
                // Not a plain table and no index found on it: no index can be created on it, so the index catalog
                // leaves it out and no index is advised for it.
                assertFalse(schema.isBaseTable("vet_import"));
                assertTrue(schema.getIndexes("vet_import").isEmpty());
            } finally {
                statement.execute("DROP FOREIGN TABLE vet_import");
                statement.execute("DROP SERVER vet_files");
            }
        }
    }

    private static DatabaseSchema readSchema(Connection connection) throws SQLException {
        var dialect = Providers.getSqlDialect(connection, StormConfig.defaults());
        return DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                DatabaseSchema.Discovery.of(dialect));
    }

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_folded VARCHAR(50) GENERATED ALWAYS AS (LOWER(name)) STORED NOT NULL)";
    }
}
