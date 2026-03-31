package st.orm.spi.h2;

import static java.util.Collections.nCopies;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.AssertionErrors.assertNull;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.core.template.SqlInterceptor.observe;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.Persist;
import st.orm.PersistenceException;
import st.orm.Version;
import st.orm.core.template.PreparedStatementTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(showSql = false)
public class H2EntityRepositoryTest {

    @Autowired
    private DataSource dataSource;

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
            @Nonnull String firstName,
            @Nonnull String lastName,
            @Nonnull Address address,
            @Nullable String telephone,
            @Version int version
    ) implements Entity<Integer> {}

    @Test
    public void testSelectLimit() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                LIMIT 2""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var entities = repo.select().limit(2).getResultList();
            assertEquals(2, entities.size());
            assertEquals("Betty", entities.getFirst().firstName());
            assertEquals("Davis", entities.getFirst().lastName());
            assertEquals("638 Cardinal Ave.", entities.getFirst().address().address());
            assertEquals("Sun Prairie", entities.getFirst().address().city());
            assertEquals("6085551749", entities.getFirst().telephone());
            assertEquals(0, entities.getFirst().version());
            assertEquals("George", entities.getLast().firstName());
            assertEquals("Franklin", entities.getLast().lastName());
            assertEquals("110 W. Liberty St.", entities.getLast().address().address());
            assertEquals("Madison", entities.getLast().address().city());
            assertEquals("6085551023", entities.getLast().telephone());
            assertEquals(0, entities.getLast().version());
        });
    }

    @Test
    public void testSelectLimitOffset() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                ORDER BY o.id
                LIMIT 2 OFFSET 1""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var entities = repo.select().orderBy(Metamodel.of(Owner.class, "id")).offset(1).limit(2).getResultList();
            assertEquals(2, entities.size());
            assertEquals("George", entities.getFirst().firstName());
            assertEquals("Franklin", entities.getFirst().lastName());
            assertEquals("110 W. Liberty St.", entities.getFirst().address().address());
            assertEquals("Madison", entities.getFirst().address().city());
            assertEquals("6085551023", entities.getFirst().telephone());
            assertEquals(0, entities.getFirst().version());
            assertEquals("Eduardo", entities.getLast().firstName());
            assertEquals("Rodriquez", entities.getLast().lastName());
            assertEquals("2693 Commerce St.", entities.getLast().address().address());
            assertEquals("McFarland", entities.getLast().address().city());
            assertEquals("6085558763", entities.getLast().telephone());
            assertEquals(0, entities.getLast().version());
        });
    }

    @Test
    public void testSelectOffset() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city, o.telephone, o.version
                FROM owner o
                ORDER BY o.id
                OFFSET 1""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var entities = repo.select().orderBy(Metamodel.of(Owner.class, "id")).offset(1).getResultList();
            assertEquals(9, entities.size());
            assertEquals("George", entities.getFirst().firstName());
            assertEquals("Franklin", entities.getFirst().lastName());
            assertEquals("110 W. Liberty St.", entities.getFirst().address().address());
            assertEquals("Madison", entities.getFirst().address().city());
            assertEquals("6085551023", entities.getFirst().telephone());
        });
    }

    @Test
    public void testInsertAndFetch() {
        String expectedSql = """
                INSERT INTO vet (first_name, last_name)
                VALUES (?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of("id"));
                assertFalse(sql.versionAware());
                assertEquals("John", sql.parameters().get(0).dbValue());
                assertEquals("Doe", sql.parameters().get(1).dbValue());
            }
        }, () -> {
            var entity = repo.insertAndFetch(Vet.builder().firstName("John").lastName("Doe").build());
            assertTrue(entity.id() > 0);
            assertEquals("John", entity.firstName());
            assertEquals("Doe", entity.lastName());
        });
    }

    @Test
    public void testInsertAndFetchInline() {
        String expectedSql = """
                INSERT INTO owner (first_name, last_name, address, city, telephone, version)
                VALUES (?, ?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of("id"));
                assertFalse(sql.versionAware());
                assertEquals("John", sql.parameters().get(0).dbValue());
                assertEquals("Doe", sql.parameters().get(1).dbValue());
            }
        }, () -> {
            var entity = repo.insertAndFetch(Owner.builder().firstName("John").lastName("Doe").address(Address.builder().address("243 Acalanes Dr").city("Sunnyvale").build()).build());
            assertTrue(entity.id() > 0);
            assertEquals("John", entity.firstName());
            assertEquals("Doe", entity.lastName());
            assertEquals("243 Acalanes Dr", entity.address().address());
            assertEquals("Sunnyvale", entity.address().city());
            assertNull("telephone", entity.telephone());
            assertEquals(0, entity.version());
        });
    }

    @Test
    public void testInsertAndFetchInlineBatch() {
        String expectedSql = """
                INSERT INTO owner (first_name, last_name, address, city, telephone, version)
                VALUES (?, ?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of("id"));
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var entities = repo.insertAndFetch(List.of(
                    Owner.builder().firstName("John").lastName("Doe").address(Address.builder().address("243 Acalanes Dr").city("Sunnyvale").build()).build(),
                    Owner.builder().firstName("Jane").lastName("Doe").address(Address.builder().address("243 Acalanes Dr").city("Sunnyvale").build()).build()
            )).stream().sorted(Comparator.comparingInt(Owner::id)).toList();
            assertEquals(2, entities.size());
            assertEquals("John", entities.getFirst().firstName());
            assertEquals("Doe", entities.getFirst().lastName());
            assertEquals("243 Acalanes Dr", entities.getFirst().address().address());
            assertEquals("Sunnyvale", entities.getFirst().address().city());
            assertNull("telephone", entities.getFirst().telephone());
            assertEquals(0, entities.getFirst().version());
            assertEquals("Jane", entities.getLast().firstName());
            assertEquals("Doe", entities.getLast().lastName());
            assertEquals("243 Acalanes Dr", entities.getLast().address().address());
            assertEquals("Sunnyvale", entities.getLast().address().city());
            assertNull("telephone", entities.getLast().telephone());
            assertEquals(0, entities.getLast().version());
        });
    }

    @Test
    public void testInsertAndFetchBatch() {
        String expectedSql = """
                INSERT INTO vet (first_name, last_name)
                VALUES (?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of("id"));
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var entities = repo.insertAndFetch(List.of(
                    Vet.builder().firstName("John").lastName("Doe").build(),
                    Vet.builder().firstName("Jane").lastName("Doe").build()
            )).stream().sorted(Comparator.comparingInt(Entity::id)).toList();
            assertEquals(2, entities.size());
            assertEquals("John", entities.getFirst().firstName());
            assertEquals("Doe", entities.getFirst().lastName());
            assertEquals("Jane", entities.getLast().firstName());
            assertEquals("Doe", entities.getLast().lastName());
        });
    }

    @Test
    public void testUpdateAndFetchInlineVersion() {
        String expectedSql = """
                UPDATE owner
                SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                WHERE (id, version) = (?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entity = repo.getById(1);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertTrue(sql.versionAware());
                assertEquals("Betty", sql.parameters().get(0).dbValue());
                assertEquals("Smith", sql.parameters().get(1).dbValue());
                assertEquals("638 Cardinal Ave.", sql.parameters().get(2).dbValue());
                assertEquals("Sun Prairie", sql.parameters().get(3).dbValue());
                assertEquals("6085551749", sql.parameters().get(4).dbValue());
                assertEquals(1, sql.parameters().get(5).dbValue());
                assertEquals(0, sql.parameters().get(6).dbValue());
            }
        }, () -> {
            var update = repo.updateAndFetch(entity.toBuilder().lastName("Smith").build());
            assertEquals("Betty", update.firstName());
            assertEquals("Smith", update.lastName());
            assertEquals("638 Cardinal Ave.", update.address().address());
            assertEquals("Sun Prairie", update.address().city());
            assertEquals("6085551749", update.telephone());
            assertEquals(1, update.version());
        });
    }

    @Test
    public void testUpdateAndFetchInlineVersionBatch() {
        String expectedSql = """
                UPDATE owner
                SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                WHERE id = ? AND version = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entities = repo.findAllById(List.of(1, 2));
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertTrue(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var updates = repo.updateAndFetch(
                    entities.stream().map(entity -> entity.toBuilder().lastName("Smith").build()).toList()
            ).stream().sorted(Comparator.comparingInt(Entity::id)).toList();
            assertEquals(2, updates.size());
            assertEquals("Betty", updates.getFirst().firstName());
            assertEquals("Smith", updates.getFirst().lastName());
            assertEquals("638 Cardinal Ave.", updates.getFirst().address().address());
            assertEquals("Sun Prairie", updates.getFirst().address().city());
            assertEquals("6085551749", updates.getFirst().telephone());
            assertEquals(1, updates.getFirst().version());
            assertEquals("George", updates.getLast().firstName());
            assertEquals("Smith", updates.getLast().lastName());
            assertEquals("110 W. Liberty St.", updates.getLast().address().address());
            assertEquals("Madison", updates.getLast().address().city());
            assertEquals("6085551023", updates.getLast().telephone());
            assertEquals(1, updates.getLast().version());
        });
    }

    @Test
    public void testUpsertAndFetchBatch() {
        String expectedSql = """
                UPDATE vet
                SET first_name = ?, last_name = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var entities = repo.upsertAndFetch(List.of(
                    Vet.builder().id(1).firstName("John").lastName("Doe").build(),
                    Vet.builder().id(2).firstName("Jane").lastName("Doe").build()
            )).stream().sorted(Comparator.comparingInt(Entity::id)).toList();
            assertEquals(2, entities.size());
            assertEquals("John", entities.getFirst().firstName());
            assertEquals("Doe", entities.getFirst().lastName());
            assertEquals("Jane", entities.getLast().firstName());
            assertEquals("Doe", entities.getLast().lastName());
        });
    }

    @Builder(toBuilder = true)
    public record Specialty(
            @PK(generation = NONE) Integer id,
            @Nonnull String name
    ) implements Entity<Integer> {}

    @Test
    @Disabled("H2 v2 MERGE fails with 'Unknown data type' when binding untyped parameters in SELECT ... AS")
    public void testUpsertNonAutoGenerated() {
        // H2 uses MERGE syntax for non-auto-generated PKs. Verify functional behavior.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        repo.upsert(Specialty.builder().id(4).name("anaesthetics").build());
        var entity = repo.select().where(Metamodel.of(Specialty.class, "name"), EQUALS, "anaesthetics").getSingleResult();
        repo.upsert(entity.toBuilder().name("anaesthetist").build());
        var updated = repo.select().where(Metamodel.of(Specialty.class, "name"), EQUALS, "anaesthetist").getSingleResult();
        assertEquals(entity.id(), updated.id());
        assertEquals("anaesthetist", updated.name());
    }

    @Test
    @Disabled("H2 v2 MERGE fails with 'Unknown data type' when binding untyped parameters in SELECT ... AS")
    public void testUpsertAndFetchNonAutoGenerated() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var entity = repo.upsertAndFetch(Specialty.builder().id(4).name("anaesthetics").build());
        var updated = repo.upsertAndFetch(entity.toBuilder().name("anaesthetist").build());
        assertEquals(entity.id(), updated.id());
        assertEquals("anaesthetist", updated.name());
    }

    @Test
    @Disabled("H2 v2 MERGE fails with 'Unknown data type' when binding untyped parameters in SELECT ... AS")
    public void testUpsertNonAutoGeneratedBatch() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        repo.upsert(List.of(
                Specialty.builder().id(4).name("anaesthetics").build(),
                Specialty.builder().id(5).name("nurse").build()));
        var entities = repo.select().where(Metamodel.of(Specialty.class, "id"), GREATER_THAN_OR_EQUAL, 4).getResultList();
        repo.upsert(entities.stream().map(e -> e.toBuilder().name("%ss".formatted(e.name())).build()).toList());
        var updated = repo.select().where(Metamodel.of(Specialty.class, "id"), GREATER_THAN_OR_EQUAL, 4).getResultList();
        assertEquals(2, updated.size());
        assertTrue(updated.stream().allMatch(entity -> entity.name().endsWith("s")));
    }

    @Test
    @Disabled("H2 v2 MERGE fails with 'Unknown data type' when binding untyped parameters in SELECT ... AS")
    public void testUpsertAndFetchNonAutoGeneratedBatch() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var entities = repo.upsertAndFetch(List.of(
                Specialty.builder().id(4).name("anaesthetics").build(),
                Specialty.builder().id(5).name("nurse").build()));
        var updated = repo.upsertAndFetch(entities.stream().map(e -> e.toBuilder().name("%ss".formatted(e.name())).build()).toList());
        assertEquals(2, updated.size());
        assertTrue(updated.stream().allMatch(entity -> entity.name().endsWith("s")));
    }

    @Builder(toBuilder = true)
    public record VetSpecialtyPK(
            int vetId,
            int specialtyId
    ) {}

    @Builder(toBuilder = true)
    public record VetSpecialty(
            @Nonnull @PK(generation = NONE) VetSpecialtyPK id,  // Implicitly @Inlined
            @Nonnull @Persist(insertable = false, updatable = false) @FK Vet vet,
            @Nonnull @Persist(insertable = false, updatable = false) @FK Specialty specialty) implements Entity<VetSpecialtyPK> {
        public VetSpecialty(@Nonnull VetSpecialtyPK pk) {
            //noinspection DataFlowIssue
            this(pk, null, null);
        }
    }

    @Test
    public void testInsertAndFetchCompoundPk() {
        String expectedSql = """
                INSERT INTO vet_specialty (vet_id, specialty_id)
                VALUES (?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VetSpecialty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertEquals(1, sql.parameters().get(0).dbValue());
                assertEquals(2, sql.parameters().get(1).dbValue());
            }
        }, () -> {
            var entity = repo.insertAndFetch(VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(1).specialtyId(2).build()).build());
            assertEquals(1, entity.id().vetId());
            assertEquals(2, entity.id().specialtyId());
        });
    }

    @Test
    public void testInsertAndFetchBatchCompoundPk() {
        String expectedSql = """
                INSERT INTO vet_specialty (vet_id, specialty_id)
                VALUES (?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VetSpecialty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var entities = repo.insertAndFetch(List.of(
                    VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(1).specialtyId(2).build()).build(),
                    VetSpecialty.builder().id(VetSpecialtyPK.builder().vetId(6).specialtyId(3).build()).build()
            )).stream().sorted(Comparator.comparingInt(a -> a.id().vetId())).toList();
            assertEquals(2, entities.size());
            assertEquals(1, entities.getFirst().id().vetId());
            assertEquals(2, entities.getFirst().id().specialtyId());
            assertEquals(6, entities.getLast().id().vetId());
            assertEquals(3, entities.getLast().id().specialtyId());
        });
    }

    @Builder(toBuilder = true)
    @DbTable("pet")
    public record Pet(
            @PK(generation = SEQUENCE, sequence = "pet_id_seq") Integer id,
            @Nonnull String name,
            @Nonnull LocalDate birthDate,
            @Nonnull @FK PetType type,
            @Nullable @FK Owner owner
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    public record PetType(
            @PK Integer id,
            @Nonnull String name,
            @Nullable String description
    ) implements Entity<Integer> {}

    @Test
    public void testInsertWithSequence() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (NEXT VALUE FOR pet_id_seq, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            repo.insert(Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = repo.findAll().stream().max(Comparator.comparingInt(Pet::id)).orElseThrow();
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
    }

    @Test
    public void testInsertAndFetchIdWithSequenceThrows() {
        // H2 does not support using sequence-based ID generation together with fetch mode.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        assertThrows(PersistenceException.class, () -> repo.insertAndFetchId(Pet.builder()
                .name("Buddy")
                .birthDate(LocalDate.of(2020, 1, 1))
                .type(PetType.builder().id(1).build())
                .owner(Owner.builder().id(1).build())
                .build()));
    }

    @Test
    public void testInsertAndFetchWithSequenceIgnoreAutoGenerate() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            repo.insert(Pet.builder()
                    .id(100)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build(), true);
            var entity = repo.getById(100);
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
    }

    @Test
    public void testInsertWithSequenceStream() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (NEXT VALUE FOR pet_id_seq, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            repo.insert(nCopies(2, Pet.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).stream());
            var entities = repo.findAll().stream().sorted(Comparator.comparingInt(Pet::id)).skip(13).toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
    }

    @Test
    public void testInsertWithSequenceIgnoreAutoGenerateBatch() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            repo.insert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList(), true);
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testInsertWithSequenceIgnoreAutoGenerateStream() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            repo.insert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()), true);
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testUpsertWithSequenceExisting() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            var id = 1;
            repo.upsert(Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = repo.getById(id);
            assertEquals(id, entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
    }

    @Test
    public void testUpsertWithSequenceExistingBatch() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(1, 2);
            repo.upsert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList());
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testUpsertWithSequenceExistingStream() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(1, 2);
            repo.upsert(ids.stream().map(id -> Pet.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()));
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testUpsertWithSequenceNew() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            var id = 100;
            var e = assertThrows(PersistenceException.class, () ->
                    repo.upsert(Pet.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()));
            assertNull("Exception must be raised by storm.", e.getCause());
        });
    }

    @Test
    public void testUpsertWithSequenceNewBatch() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    repo.upsert(ids.stream().map(id -> Pet.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()).toList()));
            assertNull("Exception must be raised by storm.", e.getCause());
        });
    }

    @Test
    public void testUpsertWithSequenceNewStream() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Pet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    repo.upsert(ids.stream().map(id -> Pet.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build())));
            assertNull("Exception must be raised by storm.", e.getCause());
        });
    }

    @Builder(toBuilder = true)
    @DbTable("pet")
    public record PetSequenceEmpty(
            @PK(generation = SEQUENCE) Integer id,
            @Nonnull String name,
            @Nonnull LocalDate birthDate,
            @Nonnull @FK PetType type,
            @Nullable @FK Owner owner
    ) implements Entity<Integer> {}

    @Test
    public void testInsertWithSequenceEmpty() {
        String expectedSql = """
                INSERT INTO pet (name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            repo.insert(PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = repo.findAll().stream().max(Comparator.comparingInt(PetSequenceEmpty::id)).orElseThrow();
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
    }

    @Test
    public void testInsertAndFetchIdWithSequenceEmptyThrows() {
        // H2 does not support using sequence-based ID generation together with fetch mode.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        assertThrows(PersistenceException.class, () -> repo.insertAndFetchId(PetSequenceEmpty.builder()
                .name("Buddy")
                .birthDate(LocalDate.of(2020, 1, 1))
                .type(PetType.builder().id(1).build())
                .owner(Owner.builder().id(1).build())
                .build()));
    }

    @Test
    public void testInsertWithSequenceEmptyIgnoreAutoGenerate() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            repo.insert(PetSequenceEmpty.builder()
                    .id(100)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build(), true);
            var entity = repo.getById(100);
            assertNotNull(entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
    }

    @Test
    public void testInsertWithSequenceEmptyStream() {
        String expectedSql = """
                INSERT INTO pet (name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            repo.insert(nCopies(2, PetSequenceEmpty.builder()
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).stream());
            var entities = repo.findAll().stream().sorted(Comparator.comparingInt(PetSequenceEmpty::id)).skip(13).toList();
            assertEquals(2, entities.size());
            for (var entity : entities) {
                assertNotNull(entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            }
        });
    }

    @Test
    public void testInsertWithSequenceEmptyIgnoreAutoGenerateBatch() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            repo.insert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList(), true);
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testInsertWithSequenceEmptyIgnoreAutoGenerateStream() {
        String expectedSql = """
                INSERT INTO pet (id, name, birth_date, type_id, owner_id)
                VALUES (?, ?, ?, ?, ?)""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            repo.insert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()), true);
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testUpsertWithSequenceEmptyExisting() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            var id = 1;
            repo.upsert(PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build());
            var entity = repo.getById(id);
            assertEquals(id, entity.id());
            assertEquals("Buddy", entity.name());
            assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
            assertEquals(1, entity.type().id());
            assertEquals(1, entity.owner().id());
        });
    }

    @Test
    public void testUpsertWithSequenceEmptyExistingBatch() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(1, 2);
            repo.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()).toList());
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testUpsertWithSequenceEmptyExistingStream() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(1, 2);
            repo.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                    .id(id)
                    .name("Buddy")
                    .birthDate(LocalDate.of(2020, 1, 1))
                    .type(PetType.builder().id(1).build())
                    .owner(Owner.builder().id(1).build())
                    .build()));
            ids.forEach(id -> {
                var entity = repo.getById(id);
                assertEquals(id, entity.id());
                assertEquals("Buddy", entity.name());
                assertEquals(LocalDate.of(2020, 1, 1), entity.birthDate());
                assertEquals(1, entity.type().id());
                assertEquals(1, entity.owner().id());
            });
        });
    }

    @Test
    public void testUpsertWithSequenceEmptyNew() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertFalse(sql.bindVariables().isPresent());
            }
        }, () -> {
            var id = 100;
            var e = assertThrows(PersistenceException.class, () ->
                    repo.upsert(PetSequenceEmpty.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()));
            assertNull("Exception must be raised by storm.", e.getCause());
        });
    }

    @Test
    public void testUpsertWithSequenceEmptyNewBatch() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    repo.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build()).toList()));
            assertNull("Exception must be raised by storm.", e.getCause());
        });
    }

    @Test
    public void testUpsertWithSequenceEmptyNewStream() {
        String expectedSql = """
                UPDATE pet
                SET name = ?, birth_date = ?, type_id = ?, owner_id = ?
                WHERE id = ?""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PetSequenceEmpty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertTrue(sql.bindVariables().isPresent());
            }
        }, () -> {
            var ids = List.of(100, 101);
            var e = assertThrows(PersistenceException.class, () ->
                    repo.upsert(ids.stream().map(id -> PetSequenceEmpty.builder()
                            .id(id)
                            .name("Buddy")
                            .birthDate(LocalDate.of(2020, 1, 1))
                            .type(PetType.builder().id(1).build())
                            .owner(Owner.builder().id(1).build())
                            .build())));
            assertNull("Exception must be raised by storm.", e.getCause());
        });
    }

    @BeforeEach
    void setUpBranchTables() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("""
                    DROP TABLE IF EXISTS version_long_entity CASCADE;
                    CREATE TABLE version_long_entity (
                        id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        name varchar(255),
                        version bigint DEFAULT 0
                    );
                    INSERT INTO version_long_entity (name) VALUES ('Alice');
                    INSERT INTO version_long_entity (name) VALUES ('Bob');
                    """);
                statement.execute("""
                    DROP TABLE IF EXISTS version_instant_entity CASCADE;
                    CREATE TABLE version_instant_entity (
                        id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        name varchar(255),
                        version timestamp DEFAULT CURRENT_TIMESTAMP
                    );
                    INSERT INTO version_instant_entity (name) VALUES ('Alice');
                    INSERT INTO version_instant_entity (name) VALUES ('Bob');
                    """);
                statement.execute("""
                    DROP TABLE IF EXISTS pk_only_entity CASCADE;
                    CREATE TABLE pk_only_entity (
                        id integer PRIMARY KEY
                    );
                    INSERT INTO pk_only_entity (id) VALUES (1);
                    INSERT INTO pk_only_entity (id) VALUES (2);
                    """);
                statement.execute("""
                    DROP TABLE IF EXISTS seq_entity CASCADE;
                    DROP SEQUENCE IF EXISTS seq_entity_id_seq;
                    CREATE SEQUENCE seq_entity_id_seq START WITH 1 INCREMENT BY 1;
                    CREATE TABLE seq_entity (
                        id integer DEFAULT NEXT VALUE FOR seq_entity_id_seq NOT NULL PRIMARY KEY,
                        name varchar(255),
                        version integer DEFAULT 0
                    );
                    INSERT INTO seq_entity (name) VALUES ('Alpha');
                    INSERT INTO seq_entity (name) VALUES ('Beta');
                    """);
            }
        }
    }

    @Builder(toBuilder = true)
    @DbTable("version_long_entity")
    public record VersionLongEntity(
            @PK Integer id,
            @Nonnull String name,
            @Version long version
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("version_instant_entity")
    public record VersionInstantEntity(
            @PK Integer id,
            @Nonnull String name,
            @Version @Nullable Instant version
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("pk_only_entity")
    public record PkOnlyEntity(
            @PK(generation = NONE) Integer id
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("seq_entity")
    public record SeqEntity(
            @PK(generation = SEQUENCE, sequence = "seq_entity_id_seq") Integer id,
            @Nonnull String name,
            @Version int version
    ) implements Entity<Integer> {}

    @Test
    public void testUpsertWithVersionLong() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VersionLongEntity.class);
        var entity = repo.getById(1);
        assertEquals("Alice", entity.name());
        assertEquals(0L, entity.version());

        observe(sql -> {
            assertTrue(sql.versionAware());
        }, () -> repo.upsert(entity.toBuilder().name("Alice Updated").build()));

        var updated = repo.getById(1);
        assertEquals("Alice Updated", updated.name());
        assertEquals(1L, updated.version());
    }

    @Test
    public void testUpsertBatchWithVersionLong() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VersionLongEntity.class);
        var entity1 = repo.getById(1);
        var entity2 = repo.getById(2);

        repo.upsert(List.of(
                entity1.toBuilder().name("Alice Batch").build(),
                entity2.toBuilder().name("Bob Batch").build()));

        var updated1 = repo.getById(1);
        var updated2 = repo.getById(2);
        assertEquals("Alice Batch", updated1.name());
        assertEquals(1L, updated1.version());
        assertEquals("Bob Batch", updated2.name());
        assertEquals(1L, updated2.version());
    }

    @Test
    public void testUpsertWithVersionInstant() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VersionInstantEntity.class);
        var entity = repo.getById(1);
        assertEquals("Alice", entity.name());
        assertNotNull(entity.version());

        Instant versionBefore = entity.version();

        observe(sql -> {
            assertTrue(sql.versionAware());
            assertTrue(sql.statement().contains("CURRENT_TIMESTAMP"));
        }, () -> repo.upsert(entity.toBuilder().name("Alice Instant").build()));

        var updated = repo.getById(1);
        assertEquals("Alice Instant", updated.name());
        assertNotNull(updated.version());
        assertTrue(updated.version().compareTo(versionBefore) >= 0);
    }

    @Test
    public void testUpsertBatchWithVersionInstant() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(VersionInstantEntity.class);
        var entity1 = repo.getById(1);
        var entity2 = repo.getById(2);

        repo.upsert(List.of(
                entity1.toBuilder().name("Alice Instant Batch").build(),
                entity2.toBuilder().name("Bob Instant Batch").build()));

        var updated1 = repo.getById(1);
        var updated2 = repo.getById(2);
        assertEquals("Alice Instant Batch", updated1.name());
        assertEquals("Bob Instant Batch", updated2.name());
    }

    @Test
    @Disabled("H2 v2 MERGE fails with 'Unknown data type' when binding untyped parameters in SELECT ... AS")
    public void testUpsertPkOnlyEntity() {
        // H2 uses MERGE syntax for PK-only entities with non-auto-generated PKs.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PkOnlyEntity.class);
        repo.upsert(PkOnlyEntity.builder().id(1).build());
        repo.upsert(PkOnlyEntity.builder().id(3).build());
        assertEquals(3, repo.findAll().size());
    }

    @Test
    @Disabled("H2 v2 MERGE fails with 'Unknown data type' when binding untyped parameters in SELECT ... AS")
    public void testUpsertBatchPkOnlyEntity() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PkOnlyEntity.class);
        repo.upsert(List.of(
                PkOnlyEntity.builder().id(1).build(),
                PkOnlyEntity.builder().id(2).build(),
                PkOnlyEntity.builder().id(4).build()));
        assertEquals(3, repo.findAll().stream()
                .filter(entity -> entity.id() >= 3 || entity.id() <= 2)
                .count());
    }

    @Test
    public void testInsertAndFetchIdWithSeqEntityThrows() {
        // H2 does not support using sequence-based ID generation together with fetch mode.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var entity = SeqEntity.builder()
                .name("Gamma")
                .version(0)
                .build();
        assertThrows(PersistenceException.class, () -> repo.insertAndFetchId(entity));
    }

    @Test
    public void testInsertAndFetchIdsWithSeqEntityThrows() {
        // H2 does not support using sequence-based ID generation together with fetch mode.
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(SeqEntity.class);
        var entities = List.of(
                SeqEntity.builder().name("Delta").version(0).build(),
                SeqEntity.builder().name("Epsilon").version(0).build());
        assertThrows(PersistenceException.class, () -> repo.insertAndFetchIds(entities));
    }

    // UUID support

    @Builder(toBuilder = true)
    @DbTable("api_key")
    public record ApiKey(
            @PK(generation = NONE) UUID id,
            @Nonnull String name,
            @Nullable UUID externalReference
    ) implements Entity<UUID> {}

    private static final UUID DEFAULT_KEY_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID SECONDARY_KEY_ID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private static final UUID DEFAULT_KEY_EXTERNAL_REF = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");

    @Test
    public void testUuidFindAll() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        List<ApiKey> all = repo.findAll();
        assertEquals(2, all.size());
    }

    @Test
    public void testUuidGetById() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        ApiKey key = repo.getById(DEFAULT_KEY_ID);
        assertNotNull(key);
        assertEquals("Default Key", key.name());
        assertEquals(DEFAULT_KEY_EXTERNAL_REF, key.externalReference());
    }

    @Test
    public void testUuidGetByIdWithNullExternalReference() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        ApiKey key = repo.getById(SECONDARY_KEY_ID);
        assertNotNull(key);
        assertEquals("Secondary Key", key.name());
        assertNull("externalReference should be null", key.externalReference());
    }

    @Test
    public void testUuidInsert() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        UUID newId = UUID.randomUUID();
        UUID newExtRef = UUID.randomUUID();
        repo.insert(new ApiKey(newId, "New Key", newExtRef));
        ApiKey inserted = repo.getById(newId);
        assertNotNull(inserted);
        assertEquals("New Key", inserted.name());
        assertEquals(newExtRef, inserted.externalReference());
        assertEquals(3, repo.count());
    }

    @Test
    public void testUuidUpdate() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        ApiKey key = repo.getById(DEFAULT_KEY_ID);
        UUID newExternalRef = UUID.randomUUID();
        ApiKey updated = key.toBuilder().name("Updated Key").externalReference(newExternalRef).build();
        repo.update(updated);
        ApiKey fetched = repo.getById(DEFAULT_KEY_ID);
        assertEquals("Updated Key", fetched.name());
        assertEquals(newExternalRef, fetched.externalReference());
    }

    @Test
    public void testUuidRemove() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        long before = repo.count();
        repo.remove(repo.getById(DEFAULT_KEY_ID));
        assertEquals(before - 1, repo.count());
    }
}
