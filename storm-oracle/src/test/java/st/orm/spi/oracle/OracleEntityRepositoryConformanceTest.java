package st.orm.spi.oracle;

import static java.util.Map.entry;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractEntityRepositoryConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.tck.Expected;
import st.orm.tck.Statement;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class OracleEntityRepositoryConformanceTest extends AbstractEntityRepositoryConformanceTest {

    private static OracleContainer container;

    /**
     * {@code @StormTest} takes the data source from this method, so the suite owns its container rather than the
     * {@code database} attribute, and the module stays on the Testcontainers generation its other tests use.
     */
    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new OracleContainer("gvenzl/oracle-free:23");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Stops the database when the class is done: each Oracle test class starts its own, and one left running slows
     * every start after it toward the startup timeout.
     */
    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @Override
    protected List<String> schemaDdl() {
        return List.of(
                "DROP TABLE seq_entity",
                "DROP SEQUENCE seq_entity_id_seq",
                "CREATE SEQUENCE seq_entity_id_seq START WITH 1 INCREMENT BY 1",
                """
                        CREATE TABLE seq_entity (
                        id NUMBER DEFAULT seq_entity_id_seq.NEXTVAL PRIMARY KEY,
                        name VARCHAR2(255),
                        version NUMBER DEFAULT 0
                        )""",
                "INSERT INTO seq_entity (name) VALUES ('Alpha')",
                "INSERT INTO seq_entity (name) VALUES ('Beta')",
                "DROP TABLE specialty_note_history",
                """
                        CREATE TABLE specialty_note_history (
                        note_id NUMBER PRIMARY KEY,
                        remark VARCHAR2(255) NOT NULL
                        )""",
                "DROP TABLE vet_specialty_note_audit",
                """
                        CREATE TABLE vet_specialty_note_audit (
                        vet_id NUMBER NOT NULL,
                        specialty_id NUMBER NOT NULL,
                        remark VARCHAR2(255) NOT NULL,
                        PRIMARY KEY (vet_id, specialty_id)
                        )""",
                "DROP TABLE vet_specialty_note",
                """
                        CREATE TABLE vet_specialty_note (
                        vet_id NUMBER NOT NULL,
                        specialty_id NUMBER NOT NULL,
                        note VARCHAR2(255) NOT NULL,
                        PRIMARY KEY (vet_id, specialty_id)
                        )""",
                "DROP TABLE specialty_note",
                """
                        CREATE TABLE specialty_note (
                        specialty_id NUMBER PRIMARY KEY,
                        note VARCHAR2(255) NOT NULL,
                        updated_at TIMESTAMP NOT NULL
                        )""",
                "DROP TABLE non_autogen_entity",
                """
                        CREATE TABLE non_autogen_entity (
                        id NUMBER PRIMARY KEY,
                        name VARCHAR2(255),
                        version NUMBER DEFAULT 0
                        )""",
                "INSERT INTO non_autogen_entity (id, name) VALUES (1, 'First')",
                "INSERT INTO non_autogen_entity (id, name) VALUES (2, 'Second')",
                "DROP TABLE pk_only_entity",
                """
                        CREATE TABLE pk_only_entity (
                        id integer PRIMARY KEY
                        )""",
                "INSERT INTO pk_only_entity (id) VALUES (1)",
                "INSERT INTO pk_only_entity (id) VALUES (2)",
                "DROP TABLE version_long_entity",
                """
                        CREATE TABLE version_long_entity (
                        id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        name VARCHAR2(255),
                        version NUMBER(19) DEFAULT 0
                        )""",
                "INSERT INTO version_long_entity (name) VALUES ('Alice')",
                "INSERT INTO version_long_entity (name) VALUES ('Bob')",
                "DROP TABLE version_instant_entity",
                """
                        CREATE TABLE version_instant_entity (
                        id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        name VARCHAR2(255),
                        version TIMESTAMP DEFAULT SYSTIMESTAMP
                        )""",
                "INSERT INTO version_instant_entity (name) VALUES ('Alice')",
                "INSERT INTO version_instant_entity (name) VALUES ('Bob')");
    }

    @Override
    protected boolean supportsFetchWithSequences() {
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
                VALUES (?, ?)""").keys("id").bound(true)),
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
                VALUES (?, ?, ?, ?, ?, ?)""").keys("id").bound(true)),
                entry(Statement.SELECT_LIMIT, Expected.sql("""
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                FETCH FIRST 2 ROWS ONLY""")),
                entry(Statement.SELECT_OFFSET, Expected.sql("""
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                ORDER BY o.id
                OFFSET 1 ROWS""")),
                entry(Statement.SELECT_LIMIT_OFFSET, Expected.sql("""
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                ORDER BY o.id
                OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY""")),
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
                        VALUES (?, ?)""")),
                entry(Statement.UPSERT_AND_FETCH_BATCH, Expected.sql("""
                        UPDATE vet
                        SET first_name = ?, last_name = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_AND_FETCH_BATCH_EXISTING_COMPOUND_PK, Expected.sql("""
                        MERGE INTO vet_specialty t
                        USING (SELECT ? AS vet_id, ? AS specialty_id FROM DUAL) src
                        ON (t.vet_id = src.vet_id AND t.specialty_id = src.specialty_id)
                        WHEN NOT MATCHED THEN
                        	INSERT (vet_id, specialty_id)
                        	VALUES (src.vet_id, src.specialty_id)""")),
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
                        VALUES (?, ?)"""))
,
                entry(Statement.UPSERT_INLINE_VERSION, Expected.sql("""
                        UPDATE owner
                        SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                        WHERE id = ? AND version = ?""")),
                entry(Statement.UPSERT_AND_FETCH_NON_AUTO_GENERATED, Expected.sql("""
                        MERGE INTO specialty t
                        USING (SELECT ? AS id, ? AS name FROM DUAL) src
                        ON (t.id = src.id)
                        WHEN MATCHED THEN
                        	UPDATE SET t.name = src.name
                        WHEN NOT MATCHED THEN
                        	INSERT (id, name)
                        	VALUES (src.id, src.name)""")),
                entry(Statement.UPSERT_NON_AUTO_GENERATED, Expected.sql("""
                        MERGE INTO specialty t
                        USING (SELECT ? AS id, ? AS name FROM DUAL) src
                        ON (t.id = src.id)
                        WHEN MATCHED THEN
                        	UPDATE SET t.name = src.name
                        WHEN NOT MATCHED THEN
                        	INSERT (id, name)
                        	VALUES (src.id, src.name)""")),
                entry(Statement.UPSERT_AND_FETCH_BATCH_NEW_COMPOUND_PK, Expected.sql("""
                        MERGE INTO vet_specialty t
                        USING (SELECT ? AS vet_id, ? AS specialty_id FROM DUAL) src
                        ON (t.vet_id = src.vet_id AND t.specialty_id = src.specialty_id)
                        WHEN NOT MATCHED THEN
                        	INSERT (vet_id, specialty_id)
                        	VALUES (src.vet_id, src.specialty_id)""")),
                entry(Statement.UPSERT_AND_FETCH_NON_AUTO_GENERATED_BATCH, Expected.sql("""
                        MERGE INTO specialty t
                        USING (SELECT ? AS id, ? AS name FROM DUAL) src
                        ON (t.id = src.id)
                        WHEN MATCHED THEN
                        	UPDATE SET t.name = src.name
                        WHEN NOT MATCHED THEN
                        	INSERT (id, name)
                        	VALUES (src.id, src.name)"""))
,
                entry(Statement.UPSERT_NON_AUTO_GENERATED_BATCH, Expected.sql("""
                        MERGE INTO specialty t
                        USING (SELECT ? AS id, ? AS name FROM DUAL) src
                        ON (t.id = src.id)
                        WHEN MATCHED THEN
                        	UPDATE SET t.name = src.name
                        WHEN NOT MATCHED THEN
                        	INSERT (id, name)
                        	VALUES (src.id, src.name)"""))

,
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_NEW, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?"""))
,
                entry(Statement.UPSERT_WITH_SEQUENCE_EXISTING_STREAM, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_STREAM, Expected.sql("""
                        INSERT INTO pet (name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?)""")),
                entry(Statement.INSERT_WITH_SEQUENCE_IGNORE_AUTO_GENERATE_BATCH, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?, ?)""")),
                entry(Statement.INSERT_WITH_SEQUENCE_EMPTY_IGNORE_AUTO_GENERATE_STREAM, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_NEW, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.INSERT_WITH_SEQUENCE_IGNORE_AUTO_GENERATE_STREAM, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_NEW_BATCH, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.INSERT_WITH_SEQUENCE_EMPTY_STREAM, Expected.sql("""
                        INSERT INTO pet (name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_EXISTING_STREAM, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_NEW_STREAM, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY, Expected.sql("""
                        INSERT INTO pet (name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EXISTING, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.INSERT_WITH_SEQUENCE_STREAM, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (pet_id_seq.NEXTVAL, ?, ?, ?, ?)""")),
                entry(Statement.INSERT_WITH_SEQUENCE_EMPTY_IGNORE_AUTO_GENERATE, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (pet_id_seq.NEXTVAL, ?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_NEW_STREAM, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_NEW_BATCH, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.INSERT_WITH_SEQUENCE_EMPTY_IGNORE_AUTO_GENERATE_BATCH, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?, ?)""")),
                entry(Statement.INSERT_WITH_SEQUENCE, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (pet_id_seq.NEXTVAL, ?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EXISTING_BATCH, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_EXISTING_BATCH, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.INSERT_WITH_SEQUENCE_EMPTY, Expected.sql("""
                        INSERT INTO pet (name, birth_date, type_id, owner_id)
                        VALUES (?, ?, ?, ?)""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_EMPTY_EXISTING, Expected.sql("""
                        UPDATE pet
                        SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                        WHERE id = ?""")),
                entry(Statement.UPSERT_WITH_SEQUENCE_STREAM, Expected.sql("""
                        INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                        VALUES (pet_id_seq.NEXTVAL, ?, ?, ?, ?)"""))
,
                entry(Statement.UPSERT_UNIQUE_KEY, Expected.sql("""
                        INSERT INTO pet_type (name, description)
                        VALUES (?, ?)"""))
);
    }
}
