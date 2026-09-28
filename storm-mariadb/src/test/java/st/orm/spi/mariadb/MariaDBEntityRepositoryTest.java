package st.orm.spi.mariadb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.core.template.SqlInterceptor.observe;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.Persist;
import st.orm.Version;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@Testcontainers
@StormTest(scripts = "/data.sql")
public class MariaDBEntityRepositoryTest {

    @SuppressWarnings("resource")
    @Container
    public static MariaDBContainer<?> mariadbContainer = new MariaDBContainer<>("mariadb:11.8")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forListeningPort());
    ;

    public static DataSource dataSource() {
        return ContainerDataSource.of(mariadbContainer.getJdbcUrl(), mariadbContainer.getUsername(),
                mariadbContainer.getPassword());
    }

    private DataSource dataSource;

    @BeforeEach
    void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Builder(toBuilder = true)
    public record Vet(
            @PK Integer id,
            String firstName,
            String lastName
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    public record Address(
            String address,
            String city
    ) {}

    @Builder(toBuilder = true)
    public record Owner(
            @PK Integer id,
            String firstName,
            String lastName,
            Address address,
            @Nullable String telephone,
            @Version int version
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    public record PetType(
            @PK Integer id,
            String name,
            @Nullable String description
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    public record Specialty(
            @PK(generation = NONE) Integer id,
            String name
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    public record VetSpecialtyPK(
            int vetId,
            int specialtyId
    ) {}

    @Builder(toBuilder = true)
    public record VetSpecialty(
            @PK(generation = NONE) VetSpecialtyPK id,  // Implicitly @Inlined
            @Persist(insertable = false, updatable = false) @FK Vet vet,
            @Persist(insertable = false, updatable = false) @FK Specialty specialty) implements Entity<VetSpecialtyPK> {
        public VetSpecialty(VetSpecialtyPK pk) {
            //noinspection DataFlowIssue
            this(pk, null, null);
        }
    }

    @Test
    public void testRemoveByCompoundPkExpandsWhere() {
        // The compound key expands rather than rendering as the row value tuple the dialect is otherwise willing
        // to emit. MariaDB does not resolve (vet_id, specialty_id) = (?, ?) against the primary key in a DELETE or
        // an UPDATE and scans the table for every row it identifies.
        String expectedSql = """
                DELETE FROM vet_specialty
                WHERE vet_id = ? AND specialty_id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VetSpecialty.class);
        var pk = VetSpecialtyPK.builder().vetId(1).specialtyId(2).build();
        repo.insert(VetSpecialty.builder().id(pk).build());
        var observed = new AtomicBoolean(false);
        observe(sql -> {
            if (sql.statement().startsWith("DELETE") && !observed.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(1, sql.parameters().get(0).dbValue());
                assertEquals(2, sql.parameters().get(1).dbValue());
            }
        }, () -> repo.removeById(pk));
        assertTrue(observed.get());
        assertTrue(repo.findById(pk).isEmpty());
    }

    @Builder(toBuilder = true)
    @DbTable("pet")
    public record Pet(
            @PK(generation = SEQUENCE, sequence = "pet_id_seq") Integer id,
            String name,
            LocalDate birthDate,
            @FK PetType type,
            @Nullable @FK Owner owner
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("pet")
    public record PetSequenceEmpty(
            @PK(generation = SEQUENCE) Integer id,
            String name,
            LocalDate birthDate,
            @FK PetType type,
            @Nullable @FK Owner owner
    ) implements Entity<Integer> {}

    @BeforeEach
    void setUpBranchTables() {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS version_long_entity");
        jdbc.execute("""
                CREATE TABLE version_long_entity (
                    id integer AUTO_INCREMENT PRIMARY KEY,
                    name varchar(255),
                    version bigint DEFAULT 0
                )""");
        jdbc.execute("INSERT INTO version_long_entity (name) VALUES ('Alice')");
        jdbc.execute("INSERT INTO version_long_entity (name) VALUES ('Bob')");

        jdbc.execute("DROP TABLE IF EXISTS version_instant_entity");
        jdbc.execute("""
                CREATE TABLE version_instant_entity (
                    id integer AUTO_INCREMENT PRIMARY KEY,
                    name varchar(255),
                    version timestamp DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("INSERT INTO version_instant_entity (name) VALUES ('Alice')");
        jdbc.execute("INSERT INTO version_instant_entity (name) VALUES ('Bob')");

        jdbc.execute("DROP TABLE IF EXISTS non_autogen_entity");
        jdbc.execute("""
                CREATE TABLE non_autogen_entity (
                    id integer PRIMARY KEY,
                    name varchar(255),
                    version integer DEFAULT 0
                )""");
        jdbc.execute("INSERT INTO non_autogen_entity (id, name) VALUES (1, 'First')");
        jdbc.execute("INSERT INTO non_autogen_entity (id, name) VALUES (2, 'Second')");

        jdbc.execute("DROP TABLE IF EXISTS seq_entity");
        jdbc.execute("DROP SEQUENCE IF EXISTS seq_entity_id_seq");
        jdbc.execute("CREATE SEQUENCE seq_entity_id_seq START WITH 1 INCREMENT BY 1");
        jdbc.execute("""
                CREATE TABLE seq_entity (
                    id integer PRIMARY KEY DEFAULT (NEXT VALUE FOR seq_entity_id_seq),
                    name varchar(255),
                    version integer DEFAULT 0
                )""");
        jdbc.execute("INSERT INTO seq_entity (name) VALUES ('Alpha')");
        jdbc.execute("INSERT INTO seq_entity (name) VALUES ('Beta')");
    }

    @Builder(toBuilder = true)
    @DbTable("version_long_entity")
    public record VersionLongEntity(
            @PK Integer id,
            String name,
            @Version long version
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("version_instant_entity")
    public record VersionInstantEntity(
            @PK Integer id,
            String name,
            @Version @Nullable Instant version
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("non_autogen_entity")
    public record NonAutoGenEntity(
            @PK(generation = NONE) Integer id,
            String name,
            @Version int version
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("seq_entity")
    public record SeqEntity(
            @PK(generation = SEQUENCE, sequence = "seq_entity_id_seq") Integer id,
            String name,
            @Version int version
    ) implements Entity<Integer> {}

    @Test
    public void testUpsertAndFetchIdWithSequence() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertTrue(sql.statement().contains("RETURNING id"));
            }
        }, () -> {
            var id = repo.upsertAndFetchId(SeqEntity.builder().name("Zeta").version(0).build());
            assertNotNull(id);
            assertTrue(id > 0);
        });
    }

    @Test
    public void testUpsertAndFetchIdsOnUniqueKeyReturnsTheRowsWritten() {
        // A batch mixing rows that meet the unique key on name with rows that insert. Each id is the row the
        // entity at its position was written to, whether the statement inserted or updated it, on servers that
        // report a batch's results per row (11.5 and later) as well as on those that do not.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetType.class);
        var statements = new ArrayList<String>();
        var ids = observe(sql -> statements.add(sql.statement()), () -> repo.upsertAndFetchIds(List.of(
                PetType.builder().name("dog").description("barks").build(),
                PetType.builder().name("ferret").description("sleeps").build(),
                PetType.builder().name("cat").description("purrs").build(),
                PetType.builder().name("rabbit").description("hops").build())));
        assertEquals(1, statements.size());
        assertTrue(statements.getFirst().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(statements.getFirst().contains("RETURNING id"));
        assertEquals(4, ids.size());
        assertEquals(2, ids.get(0));
        assertEquals(1, ids.get(2));
        assertTrue(ids.get(1) > 6);
        assertTrue(ids.get(3) > 6);
        assertNotEquals(ids.get(1), ids.get(3));
        assertEquals(List.of("dog", "ferret", "cat", "rabbit"),
                ids.stream().map(id -> repo.getById(id).name()).toList());
        assertEquals("purrs", repo.getById(1).description());
    }

    @Test
    public void testUpsertAndFetchOnUniqueKeyReturnsTheRowsWritten() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetType.class);
        var fetched = repo.upsertAndFetch(List.of(
                PetType.builder().name("snake").description("hisses").build(),
                PetType.builder().name("gecko").description("climbs").build(),
                PetType.builder().name("bird").description("sings").build()));
        assertEquals(3, fetched.size());
        var byName = fetched.stream().collect(Collectors.toMap(PetType::name, Function.identity()));
        assertEquals(4, byName.get("snake").id());
        assertEquals("hisses", byName.get("snake").description());
        assertEquals(5, byName.get("bird").id());
        assertEquals("sings", byName.get("bird").description());
        assertTrue(byName.get("gecko").id() > 6);
    }

    // UUID support

    @Builder(toBuilder = true)
    @DbTable("api_key")
    public record ApiKey(
            @PK(generation = NONE) UUID id,
            String name,
            @Nullable UUID externalReference
    ) implements Entity<UUID> {}

    private static final UUID DEFAULT_KEY_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID SECONDARY_KEY_ID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID DEFAULT_KEY_EXTERNAL_REF = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");

    // Entity callbacks on the dialect-specific sequence insert paths.

}
