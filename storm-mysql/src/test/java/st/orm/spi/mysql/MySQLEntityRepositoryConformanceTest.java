package st.orm.spi.mysql;

import static java.util.Map.entry;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MySQLContainer;
import st.orm.tck.AbstractEntityRepositoryConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.tck.Expected;
import st.orm.tck.Statement;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class MySQLEntityRepositoryConformanceTest extends AbstractEntityRepositoryConformanceTest {

    private static MySQLContainer<?> container;

    /**
     * {@code @StormTest} takes the data source from this method, so the suite owns its container rather than the
     * {@code database} attribute, and the module stays on the Testcontainers generation its other tests use.
     */
    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:9.2");
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

    @Override
    protected boolean upsertMatchesAnyUniqueKey() {
        return true;
    }

    @Override
    protected List<String> schemaDdl() {
        return List.of(
                "DROP TABLE IF EXISTS specialty_note_history",
                """
                        CREATE TABLE specialty_note_history (
                        note_id integer PRIMARY KEY,
                        remark varchar(255) NOT NULL
                        )""",
                "DROP TABLE IF EXISTS vet_specialty_note_audit",
                """
                        CREATE TABLE vet_specialty_note_audit (
                        vet_id integer NOT NULL,
                        specialty_id integer NOT NULL,
                        remark varchar(255) NOT NULL,
                        PRIMARY KEY (vet_id, specialty_id)
                        )""",
                "DROP TABLE IF EXISTS vet_specialty_note",
                """
                        CREATE TABLE vet_specialty_note (
                        vet_id integer NOT NULL,
                        specialty_id integer NOT NULL,
                        note varchar(255) NOT NULL,
                        PRIMARY KEY (vet_id, specialty_id)
                        )""",
                "DROP TABLE IF EXISTS specialty_note",
                """
                        CREATE TABLE specialty_note (
                        specialty_id integer PRIMARY KEY,
                        note varchar(255) NOT NULL,
                        updated_at datetime NOT NULL
                        )""",
                "DROP TABLE IF EXISTS non_autogen_entity",
                """
                        CREATE TABLE non_autogen_entity (
                        id integer PRIMARY KEY,
                        name varchar(255),
                        version integer DEFAULT 0
                        )""",
                "INSERT INTO non_autogen_entity (id, name) VALUES (1, 'First')",
                "INSERT INTO non_autogen_entity (id, name) VALUES (2, 'Second')",
                "DROP TABLE IF EXISTS pk_only_entity",
                """
                        CREATE TABLE pk_only_entity (
                        id integer PRIMARY KEY
                        )""",
                "INSERT INTO pk_only_entity (id) VALUES (1)",
                "INSERT INTO pk_only_entity (id) VALUES (2)",
                "DROP TABLE IF EXISTS version_long_entity",
                """
                        CREATE TABLE version_long_entity (
                        id integer AUTO_INCREMENT PRIMARY KEY,
                        name varchar(255),
                        version bigint DEFAULT 0
                        )""",
                "INSERT INTO version_long_entity (name) VALUES ('Alice')",
                "INSERT INTO version_long_entity (name) VALUES ('Bob')",
                "DROP TABLE IF EXISTS version_instant_entity",
                """
                        CREATE TABLE version_instant_entity (
                        id integer AUTO_INCREMENT PRIMARY KEY,
                        name varchar(255),
                        version timestamp DEFAULT CURRENT_TIMESTAMP
                        )""",
                "INSERT INTO version_instant_entity (name) VALUES ('Alice')",
                "INSERT INTO version_instant_entity (name) VALUES ('Bob')");
    }

    @Override
    protected boolean supportsSequences() {
        return false;
    }

    @Override
    protected Map<Statement, Expected> expectedSql() {
        return Map.ofEntries(
                entry(Statement.INSERT_AND_FETCH, Expected.sql("""
                INSERT INTO vet (first_name, last_name)
                VALUES (?, ?)""").keys("id")),
                entry(Statement.INSERT_AND_FETCH_BATCH, Expected.sql("""
                INSERT INTO vet (first_name, last_name)
                VALUES (?, ?), (?, ?)""").keys("id").bound(false)),
                entry(Statement.INSERT_AND_FETCH_COMPOUND_PK, Expected.sql("""
                INSERT INTO vet_specialty (vet_id, specialty_id)
                VALUES (?, ?)""").keys()),
                entry(Statement.INSERT_AND_FETCH_BATCH_COMPOUND_PK, Expected.sql("""
                INSERT INTO vet_specialty (vet_id, specialty_id)
                VALUES (?, ?)""").keys().bound(true)),
                entry(Statement.INSERT_AND_FETCH_INLINE, Expected.sql("""
                INSERT INTO owner (first_name, last_name, address, city, telephone, version)
                VALUES (?, ?, ?, ?, ?, ?)""").keys("id")),
                entry(Statement.INSERT_AND_FETCH_INLINE_BATCH, Expected.sql("""
                INSERT INTO owner (first_name, last_name, address, city, telephone, version)
                VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)""").keys("id").bound(false)),
                entry(Statement.SELECT_LIMIT, Expected.sql("""
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                LIMIT 2""")),
                entry(Statement.SELECT_OFFSET, Expected.sql("""
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                ORDER BY o.id
                LIMIT 18446744073709551615 OFFSET 1""")),
                entry(Statement.SELECT_LIMIT_OFFSET, Expected.sql("""
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                ORDER BY o.id
                LIMIT 2 OFFSET 1""")),
                entry(Statement.UPDATE_AND_FETCH_INLINE_VERSION, Expected.sql("""
                UPDATE owner
                SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                WHERE id = ? AND version = ?""").keys()),
                entry(Statement.UPDATE_AND_FETCH_INLINE_VERSION_BATCH, Expected.sql("""
                UPDATE owner
                SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                WHERE id = ? AND version = ?""").keys().bound(true)),
                entry(Statement.UPSERT_BATCH, Expected.sql("""
                        INSERT INTO vet (first_name, last_name)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), first_name = VALUES(first_name), last_name = VALUES(last_name)""")),
                entry(Statement.UPSERT_AND_FETCH_BATCH, Expected.sql("""
                        UPDATE vet
                        SET first_name = ?, last_name = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_AND_FETCH_BATCH_EXISTING_COMPOUND_PK, Expected.sql("""
                        INSERT INTO vet_specialty (vet_id, specialty_id)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE vet_id = VALUES(vet_id), specialty_id = VALUES(specialty_id)""")),
                entry(Statement.UPSERT_AND_FETCH_INLINE_VERSION, Expected.sql("""
                        UPDATE owner
                        SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                        WHERE id = ? AND version = ?""")),
                entry(Statement.UPSERT_INLINE_VERSION_BATCH, Expected.sql("""
                        UPDATE owner
                        SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                        WHERE id = ? AND version = ?""")),
                entry(Statement.UPSERT, Expected.sql("""
                        INSERT INTO vet (first_name, last_name)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), first_name = VALUES(first_name), last_name = VALUES(last_name)"""))
,
                entry(Statement.UPSERT_INLINE_VERSION, Expected.sql("""
                        UPDATE owner
                        SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                        WHERE id = ? AND version = ?""")),
                entry(Statement.UPSERT_AND_FETCH_NON_AUTO_GENERATED, Expected.sql("""
                        INSERT INTO specialty (id, name)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = VALUES(id), name = VALUES(name)""")),
                entry(Statement.UPSERT_NON_AUTO_GENERATED, Expected.sql("""
                        INSERT INTO specialty (id, name)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = VALUES(id), name = VALUES(name)""")),
                entry(Statement.UPSERT_AND_FETCH_BATCH_NEW_COMPOUND_PK, Expected.sql("""
                        INSERT INTO vet_specialty (vet_id, specialty_id)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE vet_id = VALUES(vet_id), specialty_id = VALUES(specialty_id)""")),
                entry(Statement.UPSERT_AND_FETCH_NON_AUTO_GENERATED_BATCH, Expected.sql("""
                        INSERT INTO specialty (id, name)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = VALUES(id), name = VALUES(name)"""))
,
                entry(Statement.UPSERT_NON_AUTO_GENERATED_BATCH, Expected.sql("""
                        INSERT INTO specialty (id, name)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = VALUES(id), name = VALUES(name)"""))

,
                entry(Statement.UPSERT_UNIQUE_KEY, Expected.sql("""
                        INSERT INTO pet_type (name, description)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), name = VALUES(name), description = VALUES(description)"""))
);
    }
}
