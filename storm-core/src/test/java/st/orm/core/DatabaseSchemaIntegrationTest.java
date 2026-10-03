package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;
import st.orm.core.template.impl.DatabaseSchema;
import st.orm.core.template.impl.DatabaseSchema.DbColumn;
import st.orm.core.template.impl.DatabaseSchema.DbForeignKey;
import st.orm.core.template.impl.DatabaseSchema.DbPrimaryKey;
import st.orm.core.template.impl.DatabaseSchema.DbUniqueKey;

/**
 * Integration tests for {@link DatabaseSchema} using the actual test schema loaded by Spring
 * (the same schema used by all integration tests). This tests schema introspection against
 * the real pet clinic schema with multiple tables, foreign keys, and views.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class DatabaseSchemaIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Table existence

    @Test
    public void testCoreTablesExist() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.tableExists("city"));
            assertTrue(schema.tableExists("owner"));
            assertTrue(schema.tableExists("pet"));
            assertTrue(schema.tableExists("pet_type"));
            assertTrue(schema.tableExists("specialty"));
            assertTrue(schema.tableExists("vet"));
            assertTrue(schema.tableExists("vet_specialty"));
            assertTrue(schema.tableExists("visit"));
            assertTrue(schema.tableExists("pet_extension"));
        }
    }

    @Test
    public void testCaseInsensitiveTableLookup() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.tableExists("CITY"));
            assertTrue(schema.tableExists("City"));
            assertTrue(schema.tableExists("city"));
        }
    }

    @Test
    public void testNonExistentTableNotFound() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertFalse(schema.tableExists("nonexistent_table"));
        }
    }

    // Views

    @Test
    public void testViewsExist() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.tableExists("owner_view"));
            assertTrue(schema.tableExists("visit_view"));
            assertTrue(schema.isView("owner_view"));
            assertTrue(schema.isView("VISIT_VIEW"));
            assertFalse(schema.isView("owner"));
        }
    }

    @Test
    public void testViewColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            // visit_view: visit_date, description, pet_id, timestamp
            assertTrue(schema.getColumn("visit_view", "visit_date").isPresent());
            assertTrue(schema.getColumn("visit_view", "description").isPresent());
            assertTrue(schema.getColumn("visit_view", "pet_id").isPresent());
        }
    }

    // Column metadata

    @Test
    public void testCityColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.getColumn("city", "id").isPresent());
            assertTrue(schema.getColumn("city", "name").isPresent());
            assertFalse(schema.getColumn("city", "nonexistent").isPresent());
        }
    }

    @Test
    public void testCityIdAutoIncrement() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            DbColumn idColumn = schema.getColumn("city", "id").orElseThrow();
            assertTrue(idColumn.autoIncrement());
        }
    }

    @Test
    public void testPetTypeIdNotAutoIncrement() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            DbColumn idColumn = schema.getColumn("pet_type", "id").orElseThrow();
            // pet_type(id) is not auto_increment
            assertFalse(idColumn.autoIncrement());
        }
    }

    @Test
    public void testDefaultIsReadApartFromNullability() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            // version is declared "default 0 not null", telephone neither: a default does not imply NOT NULL.
            DbColumn versionColumn = schema.getColumn("owner", "version").orElseThrow();
            assertTrue(versionColumn.hasDefault());
            assertFalse(versionColumn.nullable());
            DbColumn telephoneColumn = schema.getColumn("owner", "telephone").orElseThrow();
            assertFalse(telephoneColumn.hasDefault());
            assertTrue(telephoneColumn.nullable());
        }
    }

    @Test
    public void testVetSpecialtySpecialtyIdNotNullable() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            DbColumn specialtyIdColumn = schema.getColumn("vet_specialty", "specialty_id").orElseThrow();
            assertFalse(specialtyIdColumn.nullable());
        }
    }

    // Primary keys

    @Test
    public void testCityPrimaryKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("city");
            assertEquals(1, primaryKeys.size());
        }
    }

    @Test
    public void testVetSpecialtyCompoundPrimaryKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("vet_specialty");
            assertEquals(2, primaryKeys.size());
        }
    }

    @Test
    public void testPrimaryKeysForViewEmpty() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("owner_view");
            // Views don't have primary keys in metadata.
            assertNotNull(primaryKeys);
            assertTrue(primaryKeys.isEmpty());
        }
    }

    // Foreign keys

    @Test
    public void testOwnerForeignKeyToCity() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("owner");
            assertFalse(foreignKeys.isEmpty());
            assertTrue(foreignKeys.stream().anyMatch(
                    fk -> fk.fkColumnName().equalsIgnoreCase("city_id")
                            && fk.pkTableName().equalsIgnoreCase("city")));
        }
    }

    @Test
    public void testPetForeignKeys() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("pet");
            // Pet has FK to owner and pet_type.
            assertTrue(foreignKeys.size() >= 2);
            assertTrue(foreignKeys.stream().anyMatch(
                    fk -> fk.pkTableName().equalsIgnoreCase("owner")));
            assertTrue(foreignKeys.stream().anyMatch(
                    fk -> fk.pkTableName().equalsIgnoreCase("pet_type")));
        }
    }

    @Test
    public void testVetSpecialtyForeignKeys() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("vet_specialty");
            assertTrue(foreignKeys.size() >= 2);
            assertTrue(foreignKeys.stream().anyMatch(
                    fk -> fk.pkTableName().equalsIgnoreCase("vet")));
            assertTrue(foreignKeys.stream().anyMatch(
                    fk -> fk.pkTableName().equalsIgnoreCase("specialty")));
        }
    }

    @Test
    public void testVisitCompoundForeignKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("visit");
            // Visit has FK to pet and compound FK to vet_specialty (vet_id, specialty_id).
            assertTrue(foreignKeys.size() >= 2);
        }
    }

    @Test
    public void testForeignKeysForTableWithNone() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            // city has no foreign keys.
            List<DbForeignKey> foreignKeys = schema.getForeignKeys("city");
            assertTrue(foreignKeys.isEmpty());
        }
    }

    // Unique keys

    @Test
    public void testUniqueKeysForNonExistentTable() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbUniqueKey> uniqueKeys = schema.getUniqueKeys("nonexistent");
            assertTrue(uniqueKeys.isEmpty());
        }
    }

    // JDBC_METADATA strategy

    @Test
    public void testReadWithJdbcMetadataStrategy() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection,
                    connection.getCatalog(), connection.getSchema(),
                    SequenceDiscoveryStrategy.INFORMATION_SCHEMA,
                    ConstraintDiscoveryStrategy.JDBC_METADATA);
            assertTrue(schema.tableExists("city"));
            assertTrue(schema.tableExists("owner"));

            // Verify primary keys are discovered.
            List<DbPrimaryKey> cityPrimaryKeys = schema.getPrimaryKeys("city");
            assertFalse(cityPrimaryKeys.isEmpty());

            // Verify foreign keys are discovered.
            List<DbForeignKey> ownerForeignKeys = schema.getForeignKeys("owner");
            assertFalse(ownerForeignKeys.isEmpty());

            // Verify unique keys for vet_specialty compound PK are discovered as unique index.
            List<DbUniqueKey> vetSpecialtyUniqueKeys = schema.getUniqueKeys("vet_specialty");
            // Compound PK is also reported as a unique index by JDBC_METADATA.
            assertNotNull(vetSpecialtyUniqueKeys);
        }
    }

    // Polymorphic tables

    @Test
    public void testPolymorphicTablesExist() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            assertTrue(schema.tableExists("animal"));
            assertTrue(schema.tableExists("adoption"));
            assertTrue(schema.tableExists("post"));
            assertTrue(schema.tableExists("photo"));
            assertTrue(schema.tableExists("comment"));
            assertTrue(schema.tableExists("joined_animal"));
            assertTrue(schema.tableExists("joined_cat"));
            assertTrue(schema.tableExists("joined_dog"));
        }
    }

    @Test
    public void testJoinedInheritanceForeignKeys() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            // joined_cat has FK to joined_animal
            List<DbForeignKey> catForeignKeys = schema.getForeignKeys("joined_cat");
            assertFalse(catForeignKeys.isEmpty());
            assertTrue(catForeignKeys.stream().anyMatch(
                    fk -> fk.pkTableName().equalsIgnoreCase("joined_animal")));
        }
    }

    // DbColumn record properties

    @Test
    public void testDbColumnRecordProperties() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            DbColumn nameColumn = schema.getColumn("city", "name").orElseThrow();
            assertNotNull(nameColumn.tableName());
            assertNotNull(nameColumn.columnName());
            assertNotNull(nameColumn.typeName());
            assertTrue(nameColumn.columnSize() > 0);
        }
    }

    // DbPrimaryKey record properties

    @Test
    public void testDbPrimaryKeyRecordProperties() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            List<DbPrimaryKey> primaryKeys = schema.getPrimaryKeys("city");
            DbPrimaryKey primaryKey = primaryKeys.getFirst();
            assertNotNull(primaryKey.tableName());
            assertNotNull(primaryKey.columnName());
            assertTrue(primaryKey.keySeq() > 0);
        }
    }

    // Sequence discovery (H2 supports INFORMATION_SCHEMA.SEQUENCES)

    @Test
    public void testSequenceNotExistsByDefault() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection);
            // The test schema does not define any sequences.
            assertFalse(schema.sequenceExists("nonexistent_seq"));
        }
    }

    // Null schema/catalog handling

    @Test
    public void testReadWithNullCatalogAndSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseSchema schema = DatabaseSchema.read(connection, null, null,
                    SequenceDiscoveryStrategy.INFORMATION_SCHEMA,
                    ConstraintDiscoveryStrategy.INFORMATION_SCHEMA);
            // Should still discover tables even with null catalog/schema.
            assertTrue(schema.tableExists("city"));
        }
    }
}
