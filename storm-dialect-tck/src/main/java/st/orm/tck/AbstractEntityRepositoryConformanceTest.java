package st.orm.tck;

import static java.util.Arrays.stream;
import static java.util.Collections.nCopies;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.core.template.SqlInterceptor.observe;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.EntityCallback;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.Sql;
import st.orm.tck.model.Address;
import st.orm.tck.model.ApiKey;
import st.orm.tck.model.CycleA;
import st.orm.tck.model.NonAutoGenEntity;
import st.orm.tck.model.Owner;
import st.orm.tck.model.Pet;
import st.orm.tck.model.PetSequenceEmpty;
import st.orm.tck.model.PetType;
import st.orm.tck.model.PkOnlyEntity;
import st.orm.tck.model.SeqEntity;
import st.orm.tck.model.Specialty;
import st.orm.tck.model.SpecialtyNote;
import st.orm.tck.model.SpecialtyNoteHistory;
import st.orm.tck.model.VersionInstantEntity;
import st.orm.tck.model.VersionLongEntity;
import st.orm.tck.model.Vet;
import st.orm.tck.model.VetSpecialty;
import st.orm.tck.model.VetSpecialtyNote;
import st.orm.tck.model.VetSpecialtyNoteAudit;
import st.orm.tck.model.VetSpecialtyPK;

/**
 * The repository behavior every dialect is expected to implement.
 *
 * <p>A dialect module runs this suite by extending it and annotating the subclass with {@code @StormTest}, naming the
 * database to run on and the script that creates the schema. Behavior is asserted here so that it is asserted
 * identically everywhere; the statement text a dialect generates is supplied by the dialect through
 * {@link #expectedSql()}, because that text is the part that legitimately differs. Where a dialect cannot support a
 * behavior at all, it says so by overriding the matching {@code supports} method, which states the exception in one
 * place instead of leaving a test absent.
 */
public abstract class AbstractEntityRepositoryConformanceTest {

    protected static final UUID DEFAULT_KEY_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    protected static final UUID SECONDARY_KEY_ID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    protected static final UUID DEFAULT_KEY_EXTERNAL_REF = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");

    protected DataSource dataSource;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The tables the schema script does not create are created here, from the DDL the dialect
     * supplies.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            for (String ddl : schemaDdl()) {
                if (ddl.regionMatches(true, 0, "DROP", 0, 4)) {
                    // A drop is a guard against a previous run, and not every dialect spells it conditionally:
                    // Oracle has no DROP TABLE IF EXISTS, so a drop that finds nothing is the normal case.
                    try {
                        statement.execute(ddl);
                    } catch (SQLException ignored) {
                        // The table was not there to drop.
                    }
                } else {
                    statement.execute(ddl);
                }
            }
        }
    }

    /**
     * DDL for the tables this suite needs that the schema script does not create, in the order it must run. The
     * statements differ per dialect down to the identity and timestamp syntax, so the dialect states them.
     */
    protected abstract List<String> schemaDdl();

    /**
     * The expression this dialect writes for the current timestamp, which a version column of type
     * {@link Instant} is set to.
     */
    protected String currentTimestampExpression() {
        return "CURRENT_TIMESTAMP";
    }

    /** Whether the dialect has sequences at all. MySQL and SQLite do not. */
    protected boolean supportsSequences() {
        return true;
    }

    /**
     * Whether the dialect can hand back generated keys for a sequence-keyed entity. H2, MySQL, SQL Server and Oracle
     * reject the combination up front with a descriptive error, which each module's own test verifies.
     */
    protected boolean supportsFetchWithSequences() {
        return supportsSequences();
    }

    /**
     * Whether the dialect can hand back generated keys for a whole batch of sequence-keyed rows it upserts. SQL
     * Server manages the insert form and a single upsert, but not the batch upsert.
     */
    protected boolean supportsBatchUpsertAndFetchWithSequences() {
        return supportsFetchWithSequences();
    }

    /**
     * Whether an upsert whose row collides on a unique key other than the primary key updates that row instead of
     * raising.
     *
     * <p>MySQL and MariaDB match on any unique constraint, so the statement finds the existing row and updates it.
     * PostgreSQL, SQL Server and Oracle match only on the conflict target the statement names, which is the primary
     * key, so the duplicate reaches the database and the driver raises. Both are correct for the dialect and neither
     * is what the entity asked for, since the model has no way to name the unique constraint; {@code @UK} would give
     * it one.
     */
    protected boolean upsertMatchesAnyUniqueKey() {
        return false;
    }

    /** What this dialect is expected to generate for each pinned {@link Statement}. */
    protected abstract Map<Statement, Expected> expectedSql();

    /**
     * Every pinned statement carries an expectation, so that a dialect joining the suite cannot quietly opt out of the
     * statement assertions one constant at a time.
     */
    /**
     * Whether a capability this dialect lacks keeps it from ever generating {@code statement}, in which case there is
     * nothing for it to pin. Derived from the constant's name so that adding a statement to an existing family needs
     * no bookkeeping in seven modules.
     */
    private boolean unreachable(Statement statement) {
        String name = statement.name();
        if (name.contains("SEQUENCE")) {
            if (!supportsSequences()) {
                return true;
            }
            if (!name.contains("AND_FETCH")) {
                return false;
            }
            return name.startsWith("UPSERT") && name.contains("BATCH")
                    ? !supportsBatchUpsertAndFetchWithSequences()
                    : !supportsFetchWithSequences();
        }
        return false;
    }

    /**
     * Every statement this dialect can reach carries an expectation, so that a dialect cannot quietly opt out of the
     * statement assertions one constant at a time. Statements gated off by a missing capability are exempt, since the
     * dialect never generates them.
     */
    @Test
    public void everyPinnedStatementHasAnExpectation() {
        var missing = stream(Statement.values())
                .filter(statement -> !unreachable(statement))
                .filter(statement -> !expectedSql().containsKey(statement))
                .toList();
        assertTrue(missing.isEmpty(), () -> "no expected SQL for " + missing);
    }

    /**
     * Runs {@code action} and asserts the first statement it generates against this dialect's expectation, returning
     * that statement so a test can assert the properties that hold on every dialect.
     */
    protected Sql assertStatement(Statement statement, Runnable action) {
        Expected expected = expectedSql().get(statement);
        var seen = new AtomicReference<Sql>();
        observe(sql -> {
            if (seen.compareAndSet(null, sql)) {
                if (expected == null) {
                    // Fail with the entry to add, so a single run of the suite hands the dialect its table.
                    throw new AssertionError("no expected SQL pinned for " + statement + "; the dialect generated:\n"
                            + "                entry(Statement." + statement + ", Expected.sql(\"\"\"\n"
                            + sql.statement().indent(24).stripTrailing() + "\"\"\")),");
                }
                assertEquals(expected.statement(), sql.statement());
                if (expected.generatedKeys() != null) {
                    assertEquals(expected.generatedKeys(), sql.generatedKeys());
                }
                if (expected.bindVariables() != null) {
                    assertEquals(expected.bindVariables(), sql.bindVariables().isPresent());
                }
            }
        }, action);
        return seen.get();
    }

    @Test
    public void testInsertAndFetch() {
        var vets = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH, () -> {
            var vet = vets.insertAndFetch(Vet.builder().firstName("John").lastName("Doe").build());
            assertTrue(vet.id() > 0);
            assertEquals("John", vet.firstName());
            assertEquals("Doe", vet.lastName());
        });
        assertFalse(sql.versionAware());
        assertEquals("John", sql.parameters().get(0).dbValue());
        assertEquals("Doe", sql.parameters().get(1).dbValue());
    }

    @Test
    public void testInsertAndFetchBatch() {
        var vets = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_BATCH, () -> {
            var inserted = vets.insertAndFetch(List.of(
                            Vet.builder().firstName("John").lastName("Doe").build(),
                            Vet.builder().firstName("Jane").lastName("Doe").build()))
                    .stream().sorted(Comparator.comparingInt(Vet::id)).toList();
            assertEquals(2, inserted.size());
            assertEquals("John", inserted.getFirst().firstName());
            assertEquals("Doe", inserted.getFirst().lastName());
            assertEquals("Jane", inserted.getLast().firstName());
            assertEquals("Doe", inserted.getLast().lastName());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertAndFetchCompoundPk() {
        var vetSpecialties = PreparedStatementTemplate.ORM(dataSource).entity(VetSpecialty.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_COMPOUND_PK, () -> {
            var entity = vetSpecialties.insertAndFetch(VetSpecialty.builder()
                    .id(VetSpecialtyPK.builder().vetId(1).specialtyId(2).build()).build());
            assertEquals(1, entity.id().vetId());
            assertEquals(2, entity.id().specialtyId());
        });
        assertFalse(sql.versionAware());
        assertEquals(1, sql.parameters().get(0).dbValue());
        assertEquals(2, sql.parameters().get(1).dbValue());
    }

    @Test
    public void testInsertAndFetchBatchCompoundPk() {
        var vetSpecialties = PreparedStatementTemplate.ORM(dataSource).entity(VetSpecialty.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_BATCH_COMPOUND_PK, () -> {
            var entities = vetSpecialties.insertAndFetch(List.of(
                            VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(1).specialtyId(2).build()).build(),
                            VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(6).specialtyId(3).build()).build()))
                    .stream().sorted(Comparator.comparingInt(a -> a.id().vetId())).toList();
            assertEquals(2, entities.size());
            assertEquals(1, entities.getFirst().id().vetId());
            assertEquals(2, entities.getFirst().id().specialtyId());
            assertEquals(6, entities.getLast().id().vetId());
            assertEquals(3, entities.getLast().id().specialtyId());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertAndFetchInline() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_INLINE, () -> {
            var entity = owners.insertAndFetch(Owner.builder().firstName("John").lastName("Doe")
                    .address(Address.builder().address("243 Acalanes Dr").city("Sunnyvale").build()).build());
            assertTrue(entity.id() > 0);
            assertEquals("John", entity.firstName());
            assertEquals("Doe", entity.lastName());
            assertEquals("243 Acalanes Dr", entity.address().address());
            assertEquals("Sunnyvale", entity.address().city());
            assertNull(entity.telephone());
            assertEquals(0, entity.version());
        });
        assertFalse(sql.versionAware());
        assertEquals("John", sql.parameters().get(0).dbValue());
        assertEquals("Doe", sql.parameters().get(1).dbValue());
    }

    @Test
    public void testInsertAndFetchInlineBatch() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_INLINE_BATCH, () -> {
            var entities = owners.insertAndFetch(List.of(
                            Owner.builder().firstName("John").lastName("Doe").address(
                                    Address.builder().address("243 Acalanes Dr").city("Sunnyvale").build()).build(),
                            Owner.builder().firstName("Jane").lastName("Doe").address(
                                    Address.builder().address("243 Acalanes Dr").city("Sunnyvale").build()).build()))
                    .stream().sorted(Comparator.comparingInt(Owner::id)).toList();
            assertEquals(2, entities.size());
            assertEquals("John", entities.getFirst().firstName());
            assertEquals("Jane", entities.getLast().firstName());
            assertEquals("243 Acalanes Dr", entities.getFirst().address().address());
            assertEquals("Sunnyvale", entities.getFirst().address().city());
            assertNull(entities.getFirst().telephone());
            assertEquals(0, entities.getFirst().version());
            assertEquals(0, entities.getLast().version());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testSelectLimit() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        assertStatement(Statement.SELECT_LIMIT, () -> {
            var entities = owners.select().limit(2).getResultList();
            assertEquals(2, entities.size());
            assertEquals("Betty", entities.getFirst().firstName());
            assertEquals("Davis", entities.getFirst().lastName());
            assertEquals("638 Cardinal Ave.", entities.getFirst().address().address());
            assertEquals("Sun Prairie", entities.getFirst().address().city());
            assertEquals("6085551749", entities.getFirst().telephone());
            assertEquals(0, entities.getFirst().version());
            assertEquals("George", entities.getLast().firstName());
            assertEquals("Franklin", entities.getLast().lastName());
        });
    }

    @Test
    public void testSelectOffset() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        assertStatement(Statement.SELECT_OFFSET, () -> {
            var entities = owners.select().orderBy(Metamodel.of(Owner.class, "id")).offset(1).getResultList();
            assertEquals(9, entities.size());
            assertEquals("George", entities.getFirst().firstName());
            assertEquals("Franklin", entities.getFirst().lastName());
            assertEquals("110 W. Liberty St.", entities.getFirst().address().address());
            assertEquals("Madison", entities.getFirst().address().city());
            assertEquals("6085551023", entities.getFirst().telephone());
        });
    }

    @Test
    public void testSelectLimitOffset() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        assertStatement(Statement.SELECT_LIMIT_OFFSET, () -> {
            var entities = owners.select().orderBy(Metamodel.of(Owner.class, "id")).offset(1).limit(2)
                    .getResultList();
            assertEquals(2, entities.size());
            assertEquals("George", entities.getFirst().firstName());
            assertEquals("Franklin", entities.getFirst().lastName());
            assertEquals("Eduardo", entities.getLast().firstName());
            assertEquals("Rodriquez", entities.getLast().lastName());
            assertEquals("2693 Commerce St.", entities.getLast().address().address());
            assertEquals("McFarland", entities.getLast().address().city());
        });
    }

    @Test
    public void testUpdateAndFetchInlineVersion() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entity = owners.getById(1);
        var sql = assertStatement(Statement.UPDATE_AND_FETCH_INLINE_VERSION, () -> {
            var update = owners.updateAndFetch(entity.toBuilder().lastName("Smith").build());
            assertEquals("Betty", update.firstName());
            assertEquals("Smith", update.lastName());
            assertEquals("638 Cardinal Ave.", update.address().address());
            assertEquals("Sun Prairie", update.address().city());
            assertEquals("6085551749", update.telephone());
            assertEquals(1, update.version());
        });
        assertTrue(sql.versionAware());
        assertEquals("Betty", sql.parameters().get(0).dbValue());
        assertEquals("Smith", sql.parameters().get(1).dbValue());
        assertEquals(1, sql.parameters().get(5).dbValue());
        assertEquals(0, sql.parameters().get(6).dbValue());
    }

    @Test
    public void testUpdateAndFetchInlineVersionBatch() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var first = owners.getById(1);
        var second = owners.getById(2);
        var sql = assertStatement(Statement.UPDATE_AND_FETCH_INLINE_VERSION_BATCH, () -> {
            var updated = owners.updateAndFetch(List.of(
                            first.toBuilder().lastName("Smith").build(),
                            second.toBuilder().lastName("Jones").build()))
                    .stream().sorted(Comparator.comparingInt(Owner::id)).toList();
            assertEquals(2, updated.size());
            assertEquals("Smith", updated.getFirst().lastName());
            assertEquals("Jones", updated.getLast().lastName());
            assertEquals(1, updated.getFirst().version());
            assertEquals(1, updated.getLast().version());
        });
        assertTrue(sql.versionAware());
    }

    @Test
    public void testUpsertWithVersionLong() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(VersionLongEntity.class);
        var entity = entities.getById(1);
        assertEquals("Alice", entity.name());
        assertEquals(0L, entity.version());
        observe(sql -> assertTrue(sql.versionAware()),
                () -> entities.upsert(entity.toBuilder().name("Alice Updated").build()));
        var updated = entities.getById(1);
        assertEquals("Alice Updated", updated.name());
        assertEquals(1L, updated.version());
    }

    @Test
    public void testUpsertBatchWithVersionLong() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(VersionLongEntity.class);
        var first = entities.getById(1);
        var second = entities.getById(2);
        entities.upsert(List.of(
                first.toBuilder().name("Alice Batch").build(),
                second.toBuilder().name("Bob Batch").build()));
        var updatedFirst = entities.getById(1);
        var updatedSecond = entities.getById(2);
        assertEquals("Alice Batch", updatedFirst.name());
        assertEquals(1L, updatedFirst.version());
        assertEquals("Bob Batch", updatedSecond.name());
        assertEquals(1L, updatedSecond.version());
    }

    @Test
    public void testUpsertWithVersionInstant() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(VersionInstantEntity.class);
        var entity = entities.getById(1);
        assertEquals("Alice", entity.name());
        assertNotNull(entity.version());
        Instant versionBefore = entity.version();
        observe(sql -> {
            assertTrue(sql.versionAware());
            assertTrue(sql.statement().contains(currentTimestampExpression()));
        }, () -> entities.upsert(entity.toBuilder().name("Alice Instant").build()));
        var updated = entities.getById(1);
        assertEquals("Alice Instant", updated.name());
        assertNotNull(updated.version());
        assertTrue(updated.version().compareTo(versionBefore) >= 0);
    }

    @Test
    public void testUuidInsert() {
        var apiKeys = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        UUID newId = UUID.randomUUID();
        UUID newExternalReference = UUID.randomUUID();
        apiKeys.insert(new ApiKey(newId, "New Key", newExternalReference));
        ApiKey inserted = apiKeys.getById(newId);
        assertNotNull(inserted);
        assertEquals("New Key", inserted.name());
        assertEquals(newExternalReference, inserted.externalReference());
        assertEquals(3, apiKeys.count());
    }

    @Test
    public void testUuidUpdate() {
        var apiKeys = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        ApiKey key = apiKeys.getById(DEFAULT_KEY_ID);
        UUID newExternalReference = UUID.randomUUID();
        apiKeys.update(key.toBuilder().name("Updated Key").externalReference(newExternalReference).build());
        ApiKey fetched = apiKeys.getById(DEFAULT_KEY_ID);
        assertEquals("Updated Key", fetched.name());
        assertEquals(newExternalReference, fetched.externalReference());
    }

    @Test
    public void testUuidDelete() {
        var apiKeys = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        long before = apiKeys.count();
        apiKeys.remove(apiKeys.getById(DEFAULT_KEY_ID));
        assertEquals(before - 1, apiKeys.count());
    }

    @Test
    public void testUuidFindAll() {
        var apiKeys = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        assertEquals(2, apiKeys.findAll().size());
    }

    @Test
    public void testUuidGetById() {
        var apiKeys = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        ApiKey key = apiKeys.getById(DEFAULT_KEY_ID);
        assertNotNull(key);
        assertEquals("Default Key", key.name());
        assertEquals(DEFAULT_KEY_EXTERNAL_REF, key.externalReference());
    }

    @Test
    public void testUuidGetByIdWithNullExternalReference() {
        var apiKeys = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        ApiKey key = apiKeys.getById(SECONDARY_KEY_ID);
        assertNotNull(key);
        assertEquals("Secondary Key", key.name());
        assertNull(key.externalReference());
    }

    @Test
    public void testUpsert() {
        var vets = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var sql = assertStatement(Statement.UPSERT,
                () -> vets.upsert(Vet.builder().firstName("John").lastName("Doe").build()));
        assertFalse(sql.versionAware());
        assertEquals("John", sql.parameters().get(0).dbValue());
        assertEquals("Doe", sql.parameters().get(1).dbValue());
        var entity = vets.select().where(Metamodel.of(Vet.class, "firstName"), EQUALS, "John").getSingleResult();
        vets.upsert(entity.toBuilder().lastName("Smith").build());
        var updated = vets.select().where(Metamodel.of(Vet.class, "firstName"), EQUALS, "John").getSingleResult();
        assertEquals(entity.id(), updated.id());
        assertEquals("John", updated.firstName());
        assertEquals("Smith", updated.lastName());
    }

    @Test
    public void testUpsertBatch() {
        var vets = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var sql = assertStatement(Statement.UPSERT_BATCH, () -> vets.upsert(List.of(
                Vet.builder().firstName("John").lastName("Doe").build(),
                Vet.builder().firstName("Jane").lastName("Doe").build())));
        assertFalse(sql.versionAware());
        var entities = vets.select().where(Metamodel.of(Vet.class, "lastName"), EQUALS, "Doe").getResultList();
        vets.upsert(entities.stream().map(entity -> entity.toBuilder().lastName("Smith").build()).toList());
        var updated = vets.select().where(Metamodel.of(Vet.class, "lastName"), EQUALS, "Smith").getResultList();
        var none = vets.select().where(Metamodel.of(Vet.class, "lastName"), EQUALS, "Doe").getResultCount();
        assertEquals(2, updated.size());
        assertTrue(updated.stream().allMatch(entity -> entity.lastName().equals("Smith")));
        assertEquals(0, none);
    }

    @Test
    public void testResultCountOfALimitedAndAGroupedSelect() {
        var vets = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        long total = vets.count();
        assertTrue(total > 2);
        // Both counts run over a derived table, whose columns every database accepts only when they carry names.
        assertEquals(2, vets.select().limit(2).getResultCount());
        assertEquals(total, vets.select().groupBy(Metamodel.of(Vet.class, "id")).getResultCount());
    }

    @Test
    public void testUpsertAndFetchBatch() {
        var vets = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_BATCH, () -> {
            var entities = vets.upsertAndFetch(List.of(
                            Vet.builder().id(1).firstName("John").lastName("Doe").build(),
                            Vet.builder().id(2).firstName("Jane").lastName("Doe").build()))
                    .stream().sorted(Comparator.comparingInt(Vet::id)).toList();
            assertEquals(2, entities.size());
            assertEquals("John", entities.getFirst().firstName());
            assertEquals("Doe", entities.getFirst().lastName());
            assertEquals("Jane", entities.getLast().firstName());
            assertEquals("Doe", entities.getLast().lastName());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchInlineVersion() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entity = owners.getById(1);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_INLINE_VERSION, () -> {
            var update = owners.upsertAndFetch(entity.toBuilder().lastName("Smith").build());
            assertEquals("Betty", update.firstName());
            assertEquals("Smith", update.lastName());
            assertEquals("638 Cardinal Ave.", update.address().address());
            assertEquals("Sun Prairie", update.address().city());
            assertEquals("6085551749", update.telephone());
            assertEquals(1, update.version());
        });
        assertTrue(sql.versionAware());
        assertEquals("Betty", sql.parameters().get(0).dbValue());
        assertEquals("Smith", sql.parameters().get(1).dbValue());
    }

    @Test
    public void testUpsertInlineVersionBatch() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entities = owners.findAllById(List.of(1, 2));
        var sql = assertStatement(Statement.UPSERT_INLINE_VERSION_BATCH, () -> {
            owners.upsert(entities.stream().map(entity -> entity.toBuilder().lastName("Smith").build()).toList());
            var updates = owners.findAllById(List.of(1, 2)).stream()
                    .sorted(Comparator.comparingInt(Owner::id)).toList();
            assertEquals(2, updates.size());
            assertEquals("Betty", updates.getFirst().firstName());
            assertEquals("Smith", updates.getFirst().lastName());
            assertEquals(1, updates.getFirst().version());
            assertEquals("George", updates.getLast().firstName());
            assertEquals("Smith", updates.getLast().lastName());
            assertEquals(1, updates.getLast().version());
        });
        assertTrue(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchBatchExistingCompoundPk() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var vetSpecialties = orm.entity(VetSpecialty.class);
        var vet1 = orm.entity(Vet.class).getById(1);
        var vet3 = orm.entity(Vet.class).getById(3);
        var specialty1 = orm.entity(Specialty.class).getById(1);
        var specialty2 = orm.entity(Specialty.class).getById(2);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_BATCH_EXISTING_COMPOUND_PK, () -> {
            var entities = vetSpecialties.upsertAndFetch(List.of(
                            VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(2).specialtyId(1).build())
                                    .vet(vet1).specialty(specialty1).build(),
                            VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(3).specialtyId(2).build())
                                    .vet(vet3).specialty(specialty2).build()))
                    .stream().sorted(Comparator.comparingInt(a -> a.id().vetId())).toList();
            assertEquals(2, entities.size());
            assertEquals(2, entities.getFirst().id().vetId());
            assertEquals(1, entities.getFirst().id().specialtyId());
            assertEquals(3, entities.getLast().id().vetId());
            assertEquals(2, entities.getLast().id().specialtyId());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchIdsEmptyList() {
        assumeTrue(supportsBatchUpsertAndFetchWithSequences());
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        assertTrue(entities.upsertAndFetchIds(List.of()).isEmpty());
    }

    @Test
    public void testUpsertPkOnlyEntity() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(PkOnlyEntity.class);
        entities.upsert(PkOnlyEntity.builder().id(1).build());
        entities.upsert(PkOnlyEntity.builder().id(3).build());
        assertEquals(3, entities.findAll().size());
    }

    @Test
    public void testUpsertBatchPkOnlyEntity() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(PkOnlyEntity.class);
        entities.upsert(List.of(
                PkOnlyEntity.builder().id(1).build(),
                PkOnlyEntity.builder().id(2).build(),
                PkOnlyEntity.builder().id(4).build()));
        assertEquals(3, entities.findAll().size());
    }

    @Test
    public void testUpsertBatchWithVersionInstant() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(VersionInstantEntity.class);
        var first = entities.getById(1);
        var second = entities.getById(2);
        entities.upsert(List.of(
                first.toBuilder().name("Alice Instant Batch").build(),
                second.toBuilder().name("Bob Instant Batch").build()));
        assertEquals("Alice Instant Batch", entities.getById(1).name());
        assertEquals("Bob Instant Batch", entities.getById(2).name());
    }

    @Test
    public void testUpsertNonAutoGenerated() {
        var specialties = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var sql = assertStatement(Statement.UPSERT_NON_AUTO_GENERATED,
                () -> specialties.upsert(Specialty.builder().id(4).name("anaesthetics").build()));
        assertFalse(sql.versionAware());
        assertEquals(4, sql.parameters().get(0).dbValue());
        assertEquals("anaesthetics", sql.parameters().get(1).dbValue());
        var entity = specialties.select()
                .where(Metamodel.of(Specialty.class, "name"), EQUALS, "anaesthetics").getSingleResult();
        specialties.upsert(entity.toBuilder().name("anaesthetist").build());
        var updated = specialties.select()
                .where(Metamodel.of(Specialty.class, "name"), EQUALS, "anaesthetist").getSingleResult();
        assertEquals(entity.id(), updated.id());
        assertEquals("anaesthetist", updated.name());
    }

    @Test
    public void testUpsertAndFetchNonAutoGenerated() {
        var specialties = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_NON_AUTO_GENERATED, () -> {
            var entity = specialties.upsertAndFetch(Specialty.builder().id(4).name("anaesthetics").build());
            var updated = specialties.upsertAndFetch(entity.toBuilder().name("anaesthetist").build());
            assertEquals(entity.id(), updated.id());
            assertEquals("anaesthetist", updated.name());
        });
        assertFalse(sql.versionAware());
        assertEquals(4, sql.parameters().get(0).dbValue());
        assertEquals("anaesthetics", sql.parameters().get(1).dbValue());
    }

    @Test
    public void testUpsertAndFetchNonAutoGeneratedBatch() {
        var specialties = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_NON_AUTO_GENERATED_BATCH, () -> {
            var entities = specialties.upsertAndFetch(List.of(
                    Specialty.builder().id(4).name("anaesthetics").build(),
                    Specialty.builder().id(5).name("nurse").build()));
            var updated = specialties.upsertAndFetch(entities.stream()
                    .map(entity -> entity.toBuilder().name("%ss".formatted(entity.name())).build()).toList());
            assertEquals(2, updated.size());
            assertTrue(updated.stream().allMatch(entity -> entity.name().endsWith("s")));
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertInlineVersion() {
        var owners = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entity = owners.getById(1);
        var sql = assertStatement(Statement.UPSERT_INLINE_VERSION, () -> {
            owners.upsert(entity.toBuilder().lastName("Smith").build());
            var update = owners.getById(1);
            assertEquals("Betty", update.firstName());
            assertEquals("Smith", update.lastName());
            assertEquals(1, update.version());
        });
        assertTrue(sql.versionAware());
        assertEquals("Betty", sql.parameters().get(0).dbValue());
        assertEquals("Smith", sql.parameters().get(1).dbValue());
    }

    @Test
    public void testUpsertAndFetchBatchNewCompoundPk() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var vetSpecialties = orm.entity(VetSpecialty.class);
        var vet1 = orm.entity(Vet.class).getById(1);
        var vet6 = orm.entity(Vet.class).getById(6);
        var specialty2 = orm.entity(Specialty.class).getById(2);
        var specialty3 = orm.entity(Specialty.class).getById(3);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_BATCH_NEW_COMPOUND_PK, () -> {
            var entities = vetSpecialties.upsertAndFetch(List.of(
                            VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(1).specialtyId(2).build())
                                    .vet(vet1).specialty(specialty2).build(),
                            VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(6).specialtyId(3).build())
                                    .vet(vet6).specialty(specialty3).build()))
                    .stream().sorted(Comparator.comparingInt(a -> a.id().vetId())).toList();
            assertEquals(2, entities.size());
            assertEquals(1, entities.getFirst().id().vetId());
            assertEquals(2, entities.getFirst().id().specialtyId());
            assertEquals(6, entities.getLast().id().vetId());
            assertEquals(3, entities.getLast().id().specialtyId());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertNonAutoGeneratedBatch() {
        var specialties = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var sql = assertStatement(Statement.UPSERT_NON_AUTO_GENERATED_BATCH, () -> specialties.upsert(List.of(
                Specialty.builder().id(4).name("anaesthetics").build(),
                Specialty.builder().id(5).name("nurse").build())));
        assertFalse(sql.versionAware());
        var entities = specialties.select()
                .where(Metamodel.of(Specialty.class, "id"), GREATER_THAN_OR_EQUAL, 4).getResultList();
        specialties.upsert(entities.stream()
                .map(entity -> entity.toBuilder().name("%ss".formatted(entity.name())).build()).toList());
        var updated = specialties.select()
                .where(Metamodel.of(Specialty.class, "id"), GREATER_THAN_OR_EQUAL, 4).getResultList();
        assertEquals(2, updated.size());
        assertTrue(updated.stream().allMatch(entity -> entity.name().endsWith("s")));
    }

    @Test
    public void testUpsertBatchNonAutoGen() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(NonAutoGenEntity.class);
        entities.upsert(List.of(
                NonAutoGenEntity.builder().id(1).name("First Batch").version(0).build(),
                NonAutoGenEntity.builder().id(5).name("Fifth").version(0).build()));
        assertEquals("First Batch", entities.getById(1).name());
        assertEquals("Fifth", entities.getById(5).name());
    }

    @Test
    public void testUpsertAndFetchIdNonAutoGen() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(NonAutoGenEntity.class);
        assertEquals(4, entities.upsertAndFetchId(
                NonAutoGenEntity.builder().id(4).name("Fourth").version(0).build()));
    }

    @Test
    public void testUpsertAndFetchIdsBatchNonAutoGen() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(NonAutoGenEntity.class);
        var ids = entities.upsertAndFetchIds(List.of(
                NonAutoGenEntity.builder().id(1).name("First FetchIds").version(0).build(),
                NonAutoGenEntity.builder().id(6).name("Sixth").version(0).build()));
        assertEquals(2, ids.size());
        assertTrue(ids.contains(1));
        assertTrue(ids.contains(6));
    }

    @Test
    public void testInsertAndFetchWithSequence() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_WITH_SEQUENCE, () -> {
            var entity = pets.insertAndFetch(Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyNew() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_NEW, () -> {
            // Asking for a sequence without naming one is the dialect's own error, not the driver's.
            var exception = assertThrows(PersistenceException.class, () -> pets.upsert(PetSequenceEmpty.builder()
                    .id(100)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()));
            assertNull(exception.getCause(), "Exception must be raised by storm.");
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertAndFetchWithSequenceBatch() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_WITH_SEQUENCE_BATCH, () -> {
            var entities = pets.insertAndFetch(nCopies(2, Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build())).stream().distinct().toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertAndFetchWithSequenceEmpty() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_WITH_SEQUENCE_EMPTY, () -> {
            var entity = pets.insertAndFetch(PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertAndFetchWithSequenceEmptyBatch() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_WITH_SEQUENCE_EMPTY_BATCH, () -> {
            var entities = pets.insertAndFetch(nCopies(2, PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build())).stream().distinct().toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertAndFetchWithSequenceIgnoreAutoGenerate() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_AND_FETCH_WITH_SEQUENCE_IGNORE_AUTO_GENERATE, () -> {
            pets.insert(Pet.builder()
                    .id(100)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build(), true);
            var entity = pets.getById(100);
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequence() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE, () -> {
            pets.insert(Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = pets.findAll().stream().max(Comparator.comparingInt(Pet::id)).orElseThrow();
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceEmpty() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_EMPTY, () -> {
            pets.insert(PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = pets.findAll().stream().max(Comparator.comparingInt(PetSequenceEmpty::id)).orElseThrow();
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceEmptyIgnoreAutoGenerate() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_EMPTY_IGNORE_AUTO_GENERATE, () -> {
            pets.insert(PetSequenceEmpty.builder()
                    .id(100)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build(), true);
            var entity = pets.getById(100);
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceEmptyIgnoreAutoGenerateBatch() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_EMPTY_IGNORE_AUTO_GENERATE_BATCH, () -> {
            var ids = List.of(100, 101);
            pets.insert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList(), true);
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceEmptyIgnoreAutoGenerateStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_EMPTY_IGNORE_AUTO_GENERATE_STREAM, () -> {
            var ids = List.of(100, 101);
            pets.insert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()), true);
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceEmptyStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_EMPTY_STREAM, () -> {
            pets.insert(nCopies(2, PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).stream());
            var entities = pets.findAll().stream().sorted(Comparator.comparingInt(PetSequenceEmpty::id)).skip(13).toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceIgnoreAutoGenerateBatch() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_IGNORE_AUTO_GENERATE_BATCH, () -> {
            var ids = List.of(100, 101);
            pets.insert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList(), true);
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceIgnoreAutoGenerateStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_IGNORE_AUTO_GENERATE_STREAM, () -> {
            var ids = List.of(100, 101);
            pets.insert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()), true);
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testInsertWithSequenceStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.INSERT_WITH_SEQUENCE_STREAM, () -> {
            pets.insert(nCopies(2, Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).stream());
            var entities = pets.findAll().stream().sorted(Comparator.comparingInt(Pet::id)).skip(13).toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchWithSequence() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_WITH_SEQUENCE, () -> {
            var entity = pets.upsertAndFetch(Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchWithSequenceBatch() {
        assumeTrue(supportsSequences() && supportsBatchUpsertAndFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_WITH_SEQUENCE_BATCH, () -> {
            var entities = pets.upsertAndFetch(nCopies(2, Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build())).stream().distinct().toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchWithSequenceEmpty() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_WITH_SEQUENCE_EMPTY, () -> {
            var entity = pets.upsertAndFetch(PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertAndFetchWithSequenceEmptyBatch() {
        assumeTrue(supportsSequences() && supportsBatchUpsertAndFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_AND_FETCH_WITH_SEQUENCE_EMPTY_BATCH, () -> {
            var entities = pets.upsertAndFetch(nCopies(2, PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build())).stream().distinct().toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequence() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE, () -> {
            pets.upsert(Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = pets.findAll().stream().max(Comparator.comparingInt(Pet::id)).orElseThrow();
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmpty() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY, () -> {
            pets.upsert(PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = pets.findAll().stream().max(Comparator.comparingInt(PetSequenceEmpty::id)).orElseThrow();
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyExisting() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_EXISTING, () -> {
            var id = 1;
            pets.upsert(PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = pets.getById(id);
            assertEquals(id, entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyExistingBatch() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_EXISTING_BATCH, () -> {
            var ids = List.of(1, 2);
            pets.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList());
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyExistingStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_EXISTING_STREAM, () -> {
            var ids = List.of(1, 2);
            pets.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()));
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyNewBatch() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_NEW_BATCH, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    pets.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()).toList()));
            assertNull(e.getCause(), "Exception must be raised by storm.");
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyNewStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_NEW_STREAM, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    pets.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build())));
            assertNull(e.getCause(), "Exception must be raised by storm.");
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceEmptyStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EMPTY_STREAM, () -> {
            pets.upsert(nCopies(2, PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).stream());
            var entities = pets.findAll().stream().sorted(Comparator.comparingInt(PetSequenceEmpty::id)).skip(13).toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceExisting() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EXISTING, () -> {
            var id = 1;
            pets.upsert(Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = pets.getById(id);
            assertEquals(id, entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceExistingBatch() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EXISTING_BATCH, () -> {
            var ids = List.of(1, 2);
            pets.upsert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList());
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceExistingStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_EXISTING_STREAM, () -> {
            var ids = List.of(1, 2);
            pets.upsert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()));
            ids.forEach(id -> {
                var entity = pets.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceNew() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_NEW, () -> {
            var id = 100;
            var e = assertThrows(PersistenceException.class, () ->
                    pets.upsert(Pet.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()));
            assertNull(e.getCause(), "Exception must be raised by storm.");
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceNewBatch() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_NEW_BATCH, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    pets.upsert(ids.stream().map(id -> Pet.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()).toList()));
            assertNull(e.getCause(), "Exception must be raised by storm.");
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceNewStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_NEW_STREAM, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    pets.upsert(ids.stream().map(id -> Pet.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build())));
            assertNull(e.getCause(), "Exception must be raised by storm.");
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertWithSequenceStream() {
        assumeTrue(supportsSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var sql = assertStatement(Statement.UPSERT_WITH_SEQUENCE_STREAM, () -> {
            pets.upsert(nCopies(2, Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).stream());
            var entities = pets.findAll().stream().sorted(Comparator.comparingInt(Pet::id)).skip(13).toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUpsertDependentOneToOne() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var specialty = orm.entity(Specialty.class).getById(1);
        var notes = orm.entity(SpecialtyNote.class);
        notes.upsert(SpecialtyNote.builder()
                .specialty(specialty)
                .note("first")
                .updatedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .build());
        var stored = notes.getById(specialty);
        assertEquals("first", stored.note());
        notes.upsert(stored.toBuilder()
                .note("second")
                .updatedAt(Instant.parse("2026-01-02T10:00:00Z"))
                .build());
        var updated = notes.getById(specialty);
        assertEquals("second", updated.note());
        assertEquals(Instant.parse("2026-01-02T10:00:00Z"), updated.updatedAt());
    }

    @Test
    public void testUpsertDependentOneToOneBatch() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var specialties = orm.entity(Specialty.class);
        var notes = orm.entity(SpecialtyNote.class);
        var pending = List.of(
                SpecialtyNote.builder().specialty(specialties.getById(2)).note("surgery note")
                        .updatedAt(Instant.parse("2026-01-01T10:00:00Z")).build(),
                SpecialtyNote.builder().specialty(specialties.getById(3)).note("dentistry note")
                        .updatedAt(Instant.parse("2026-01-01T10:00:00Z")).build());
        notes.upsert(pending);
        notes.upsert(pending.stream()
                .map(note -> note.toBuilder().note("%s updated".formatted(note.note())).build()).toList());
        assertEquals("surgery note updated", notes.getById(specialties.getById(2)).note());
        assertEquals("dentistry note updated", notes.getById(specialties.getById(3)).note());
    }

    @Test
    public void testUpsertCompoundForeignKeyAsPrimaryKey() {
        var notes = PreparedStatementTemplate.ORM(dataSource).entity(VetSpecialtyNote.class);
        var vetSpecialty = new VetSpecialty(new VetSpecialtyPK(2, 1));
        notes.upsert(VetSpecialtyNote.builder().vetSpecialty(vetSpecialty).note("first").build());
        assertEquals("first", notes.getById(vetSpecialty).note());
        notes.upsert(VetSpecialtyNote.builder().vetSpecialty(vetSpecialty).note("second").build());
        assertEquals("second", notes.getById(vetSpecialty).note());
    }

    @Test
    public void testCrudNestedCompoundKeyChain() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var notes = orm.entity(VetSpecialtyNote.class);
        var vetSpecialty = new VetSpecialty(new VetSpecialtyPK(3, 2));
        notes.upsert(VetSpecialtyNote.builder().vetSpecialty(vetSpecialty).note("base note").build());
        var note = notes.getById(vetSpecialty);
        var audits = orm.entity(VetSpecialtyNoteAudit.class);
        audits.insert(VetSpecialtyNoteAudit.builder().note(note).remark("created").build());
        var stored = audits.getById(note);
        assertEquals("created", stored.remark());
        assertEquals(vetSpecialty.id(), stored.note().vetSpecialty().id());
        audits.update(stored.toBuilder().remark("updated").build());
        assertEquals("updated", audits.getById(note).remark());
        audits.remove(stored.toBuilder().remark("updated").build());
        assertTrue(audits.findById(note).isEmpty());
    }

    @Test
    public void testUpsertNestedCompoundKeyChain() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var notes = orm.entity(VetSpecialtyNote.class);
        var vetSpecialty = new VetSpecialty(new VetSpecialtyPK(4, 2));
        notes.upsert(VetSpecialtyNote.builder().vetSpecialty(vetSpecialty).note("base note").build());
        var note = notes.getById(vetSpecialty);
        var audits = orm.entity(VetSpecialtyNoteAudit.class);
        audits.upsert(VetSpecialtyNoteAudit.builder().note(note).remark("created").build());
        assertEquals("created", audits.getById(note).remark());
        audits.upsert(VetSpecialtyNoteAudit.builder().note(note).remark("revised").build());
        assertEquals("revised", audits.getById(note).remark());
    }

    @Test
    public void testUpsertNestedSingleColumnKeyChain() {
        var orm = PreparedStatementTemplate.ORM(dataSource);
        var specialty = orm.entity(Specialty.class).getById(3);
        var notes = orm.entity(SpecialtyNote.class);
        notes.upsert(SpecialtyNote.builder()
                .specialty(specialty)
                .note("dentistry note")
                .updatedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .build());
        var note = notes.getById(specialty);
        var history = orm.entity(SpecialtyNoteHistory.class);
        history.upsert(SpecialtyNoteHistory.builder().note(note).remark("created").build());
        assertEquals("created", history.getById(note).remark());
        history.upsert(SpecialtyNoteHistory.builder().note(note).remark("revised").build());
        assertEquals("revised", history.getById(note).remark());
    }

    @Test
    public void testCircularKeyChainFailsFast() {
        // A key chain that references itself cannot be flattened; the model must say so rather than loop.
        assertThrows(PersistenceException.class,
                () -> PreparedStatementTemplate.ORM(dataSource).entity(CycleA.class).findAll());
    }

    @Test
    public void testUpsertAndFetchWithSequenceExisting() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var inserted = pets.insertAndFetch(Pet.builder()
                .name("Buddy")
                .birthDate(LocalDate.of(2020, 1, 1))
                .type(PetType.builder().id(1).build())
                .owner(Owner.builder().id(1).build())
                .build());
        assertNotNull(inserted.id());
        // The key is no longer the sequence default, so the upsert has to route to an update.
        var updated = pets.upsertAndFetch(inserted.toBuilder().name("Max").build());
        assertEquals(inserted.id(), updated.id());
        assertEquals("Max", updated.name());
    }

    @Test
    public void testUpsertAndFetchWithSequenceExistingBatch() {
        assumeTrue(supportsSequences() && supportsBatchUpsertAndFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var insertedIds = pets.insertAndFetchIds(List.of(
                Pet.builder().name("Buddy").birthDate(LocalDate.of(2020, 1, 1))
                        .type(PetType.builder().id(1).build()).owner(Owner.builder().id(1).build()).build(),
                Pet.builder().name("Rex").birthDate(LocalDate.of(2020, 2, 1))
                        .type(PetType.builder().id(1).build()).owner(Owner.builder().id(1).build()).build()));
        assertEquals(2, insertedIds.size());
        var updated = pets.upsertAndFetch(List.of(
                        Pet.builder().id(insertedIds.get(0)).name("Max").birthDate(LocalDate.of(2020, 1, 1))
                                .type(PetType.builder().id(1).build()).owner(Owner.builder().id(1).build()).build(),
                        Pet.builder().id(insertedIds.get(1)).name("Bella").birthDate(LocalDate.of(2020, 2, 1))
                                .type(PetType.builder().id(1).build()).owner(Owner.builder().id(1).build()).build()))
                .stream().sorted(Comparator.comparingInt(Pet::id)).toList();
        assertEquals(2, updated.size());
        assertEquals("Max", updated.get(0).name());
        assertEquals("Bella", updated.get(1).name());
    }

    @Test
    public void testInsertAndFetchWithSequenceRefusedWhenUnsupported() {
        assumeTrue(supportsSequences() && !supportsFetchWithSequences());
        var pets = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var exception = assertThrows(PersistenceException.class, () -> pets.insertAndFetch(Pet.builder()
                .name("Buddy")
                .birthDate(LocalDate.of(2020, 1, 1))
                .type(PetType.builder().id(1).build())
                .owner(Owner.builder().id(1).build())
                .build()));
        assertNull(exception.getCause(), "Exception must be raised by storm.");
    }

    @Test
    public void testUpsertUniqueKey() {
        var petTypes = PreparedStatementTemplate.ORM(dataSource).entity(PetType.class);
        var sql = assertStatement(Statement.UPSERT_UNIQUE_KEY,
                () -> petTypes.upsert(PetType.builder().name("dragon").description("description").build()));
        assertFalse(sql.versionAware());
        assertEquals("dragon", sql.parameters().get(0).dbValue());
        assertEquals("description", sql.parameters().get(1).dbValue());
        var stored = petTypes.select()
                .where(Metamodel.of(PetType.class, "name"), EQUALS, "dragon").getSingleResult();
        assertEquals("description", stored.description());
        if (upsertMatchesAnyUniqueKey()) {
            petTypes.upsert(PetType.builder().name("dragon").description(null).build());
            var updated = petTypes.select()
                    .where(Metamodel.of(PetType.class, "name"), EQUALS, "dragon").getSingleResult();
            assertNull(updated.description());
        } else {
            var exception = assertThrows(PersistenceException.class, () -> petTypes.upsert(
                    PetType.builder().name("dragon").description("description").build()));
            // The database rejected it rather than Storm, which is what distinguishes this from a refusal.
            assertNotNull(exception.getCause());
        }
    }

    @Test
    public void testInsertAndFetchIdWithSequence() {
        assumeTrue(supportsSequences());
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        if (supportsFetchWithSequences()) {
            var id = entities.insertAndFetchId(SeqEntity.builder().name("Gamma").version(0).build());
            assertNotNull(id);
            assertTrue(id > 0);
        } else {
            // The dialect cannot read back a key the sequence produced, and says so itself.
            var exception = assertThrows(PersistenceException.class,
                    () -> entities.insertAndFetchId(SeqEntity.builder().name("Gamma").version(0).build()));
            assertNull(exception.getCause(), "Exception must be raised by storm.");
        }
    }

    @Test
    public void testInsertAndFetchIdsWithSequence() {
        assumeTrue(supportsSequences());
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var pending = List.of(
                SeqEntity.builder().name("Delta").version(0).build(),
                SeqEntity.builder().name("Epsilon").version(0).build());
        if (supportsFetchWithSequences()) {
            var ids = entities.insertAndFetchIds(pending);
            assertEquals(2, ids.size());
            assertTrue(ids.get(0) > 0);
            assertTrue(ids.get(1) > ids.get(0));
        } else {
            var exception = assertThrows(PersistenceException.class, () -> entities.insertAndFetchIds(pending));
            assertNull(exception.getCause(), "Exception must be raised by storm.");
        }
    }

    @Test
    public void testUpsertAndFetchIdsWithSequenceNew() {
        assumeTrue(supportsSequences());
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var pending = List.of(
                SeqEntity.builder().name("Eta").version(0).build(),
                SeqEntity.builder().name("Theta").version(0).build());
        if (supportsBatchUpsertAndFetchWithSequences()) {
            var ids = entities.upsertAndFetchIds(pending);
            assertEquals(2, ids.size());
            assertTrue(ids.get(0) > 0);
            assertTrue(ids.get(1) > 0);
        } else {
            var exception = assertThrows(PersistenceException.class, () -> entities.upsertAndFetchIds(pending));
            assertNull(exception.getCause(), "Exception must be raised by storm.");
        }
    }

    @Test
    public void testUpsertAndFetchIdsWithSequenceExisting() {
        assumeTrue(supportsSequences() && supportsBatchUpsertAndFetchWithSequences());
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var existing = entities.findAll();
        var ids = entities.upsertAndFetchIds(existing.stream()
                .map(entity -> entity.toBuilder().name(entity.name() + " Updated").build()).toList());
        assertEquals(existing.size(), ids.size());
        for (int index = 0; index < ids.size(); index++) {
            assertEquals(existing.get(index).id(), ids.get(index));
        }
    }

    @Test
    public void testUpsertAndFetchIdsWithSequenceMixed() {
        assumeTrue(supportsSequences() && supportsBatchUpsertAndFetchWithSequences());
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var existing = entities.getById(1);
        var ids = entities.upsertAndFetchIds(List.of(
                SeqEntity.builder().name("Iota").version(0).build(),
                existing.toBuilder().name("Alpha Updated").build()));
        assertEquals(2, ids.size());
        assertEquals(existing.id(), ids.get(1));
    }

    @Test
    public void testUpsertNewEntityRoutesToInsert() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(VersionLongEntity.class);
        entities.upsert(VersionLongEntity.builder().name("New Entity").version(0L).build());
        assertTrue(entities.findAll().stream().anyMatch(entity -> "New Entity".equals(entity.name())));
    }

    @Test
    public void testSequenceInsertAndFetchIdFiresCallbacksWithGeneratedKey() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var observed = new ArrayList<SeqEntity>();
        var orm = PreparedStatementTemplate.ORM(dataSource).withEntityCallback(new EntityCallback<SeqEntity>() {
            @Override
            public SeqEntity beforeInsert(SeqEntity entity) {
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }

            @Override
            public void afterInsert(SeqEntity entity) {
                observed.add(entity);
            }
        });
        var entities = orm.entity(SeqEntity.class);
        // Reading the key back is dialect-specific, and must still run the callbacks with the key it produced.
        var id = entities.insertAndFetchId(SeqEntity.builder().name("callback seq").version(0).build());
        assertEquals("CALLBACK SEQ", entities.getById(id).name());
        assertEquals(1, observed.size());
        assertEquals(id, observed.getFirst().id());
        assertEquals("CALLBACK SEQ", observed.getFirst().name());
    }

    @Test
    public void testUpsertNonAutoGeneratedPk() {
        var entities = PreparedStatementTemplate.ORM(dataSource).entity(NonAutoGenEntity.class);
        entities.upsert(NonAutoGenEntity.builder().id(1).name("First Updated").version(0).build());
        assertEquals("First Updated", entities.getById(1).name());
        entities.upsert(NonAutoGenEntity.builder().id(3).name("Third").version(0).build());
        assertEquals("Third", entities.getById(3).name());
    }

    @Test
    public void testSequenceBatchInsertAndFetchIdsFiresCallbacksWithGeneratedKeys() {
        assumeTrue(supportsSequences() && supportsFetchWithSequences());
        var observed = new ArrayList<SeqEntity>();
        var orm = PreparedStatementTemplate.ORM(dataSource).withEntityCallback(new EntityCallback<SeqEntity>() {
            @Override
            public SeqEntity beforeInsert(SeqEntity entity) {
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }

            @Override
            public void afterInsert(SeqEntity entity) {
                observed.add(entity);
            }
        });
        var entities = orm.entity(SeqEntity.class);
        var ids = entities.insertAndFetchIds(List.of(
                SeqEntity.builder().name("cb batch one").version(0).build(),
                SeqEntity.builder().name("cb batch two").version(0).build()));
        assertEquals(2, ids.size());
        assertEquals("CB BATCH ONE", entities.getById(ids.get(0)).name());
        assertEquals(ids, observed.stream().map(SeqEntity::id).toList());
        assertEquals(List.of("CB BATCH ONE", "CB BATCH TWO"), observed.stream().map(SeqEntity::name).toList());
    }

    @Test
    public void testUpsertAndFetchIdsReportsGeneratedKeysToCallbacks() {
        assumeTrue(supportsSequences() && supportsBatchUpsertAndFetchWithSequences());
        var observed = new ArrayList<SeqEntity>();
        var orm = PreparedStatementTemplate.ORM(dataSource).withEntityCallback(new EntityCallback<SeqEntity>() {
            @Override
            public void afterUpsert(SeqEntity entity) {
                observed.add(entity);
            }
        });
        var ids = orm.entity(SeqEntity.class).upsertAndFetchIds(List.of(
                SeqEntity.builder().name("upsert callback one").version(0).build(),
                SeqEntity.builder().name("upsert callback two").version(0).build()));
        assertEquals(2, ids.size());
        assertEquals(ids, observed.stream().map(SeqEntity::id).toList());
    }
}
