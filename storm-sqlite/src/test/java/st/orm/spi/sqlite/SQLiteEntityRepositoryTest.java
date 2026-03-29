package st.orm.spi.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.util.AssertionErrors.assertNull;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.core.template.SqlInterceptor.observe;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
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
import st.orm.Version;
import st.orm.core.template.PreparedStatementTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(showSql = false)
public class SQLiteEntityRepositoryTest {

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
                LIMIT -1 OFFSET 1""";
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
                VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)
                RETURNING id""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertFalse(sql.versionAware());
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
                VALUES (?, ?), (?, ?)
                RETURNING id""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertFalse(sql.versionAware());
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
                WHERE id = ? AND version = ?""";
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

    @Test
    public void testUpsert() {
        String expectedSql = """
                INSERT INTO vet (first_name, last_name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        observe(sql -> {
            assertEquals(expectedSql, sql.statement());
            assertEquals(sql.generatedKeys(), List.of("id"));
            assertFalse(sql.versionAware());
            assertEquals("John", sql.parameters().get(0).dbValue());
            assertEquals("Doe", sql.parameters().get(1).dbValue());
        }, () -> repo.upsert(Vet.builder().firstName("John").lastName("Doe").build()));
        var entity = repo.select().where(Metamodel.of(Vet.class, "firstName"), EQUALS, "John").getSingleResult();
        repo.upsert(entity.toBuilder().lastName("Smith").build());
        var updated = repo.select().where(Metamodel.of(Vet.class, "firstName"), EQUALS, "John").getSingleResult();
        assertEquals(entity.id(), updated.id());
        assertEquals("John", updated.firstName());
        assertEquals("Smith", updated.lastName());
    }

    @Test
    public void testUpsertBatch() {
        String expectedSql = """
                INSERT INTO vet (first_name, last_name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Vet.class);
        observe(sql -> {
            assertEquals(expectedSql, sql.statement());
            assertEquals(sql.generatedKeys(), List.of("id"));
            assertFalse(sql.versionAware());
            assertTrue(sql.bindVariables().isPresent());
        }, () -> repo.upsert(List.of(
                Vet.builder().firstName("John").lastName("Doe").build(),
                Vet.builder().firstName("Jane").lastName("Doe").build())));
        var entities = repo.select().where(Metamodel.of(Vet.class, "lastName"), EQUALS, "Doe").getResultList();
        repo.upsert(entities.stream().map(entity -> entity.toBuilder().lastName("Smith").build()).toList());
        var updated = repo.select().where(Metamodel.of(Vet.class, "lastName"), EQUALS, "Smith").getResultList();
        var none = repo.select().where(Metamodel.of(Vet.class, "lastName"), EQUALS, "Doe").getResultCount();
        assertEquals(2, updated.size());
        assertTrue(updated.stream().allMatch(entity -> entity.lastName().equals("Smith")));
        assertEquals(0, none);
    }

    @Test
    public void testUpsertInlineVersion() {
        String expectedSql = """
                UPDATE owner
                SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                WHERE id = ? AND version = ?""";
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
            repo.upsert(entity.toBuilder().lastName("Smith").build());
            var update = repo.getById(1);
            assertEquals("Betty", update.firstName());
            assertEquals("Smith", update.lastName());
            assertEquals("638 Cardinal Ave.", update.address().address());
            assertEquals("Sun Prairie", update.address().city());
            assertEquals("6085551749", update.telephone());
            assertEquals(1, update.version());
        });
    }

    @Test
    public void testUpsertAndFetchInlineVersion() {
        String expectedSql = """
                UPDATE owner
                SET first_name = ?, last_name = ?, address = ?, city = ?, telephone = ?, version = version + 1
                WHERE id = ? AND version = ?""";
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
            var update = repo.upsertAndFetch(entity.toBuilder().lastName("Smith").build());
            assertEquals("Betty", update.firstName());
            assertEquals("Smith", update.lastName());
            assertEquals("638 Cardinal Ave.", update.address().address());
            assertEquals("Sun Prairie", update.address().city());
            assertEquals("6085551749", update.telephone());
            assertEquals(1, update.version());
        });
    }

    @Test
    public void testUpsertAndFetchInlineVersionInsert() {
        String expectedSql = """
                INSERT INTO owner (first_name, last_name, address, city, telephone, version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name, address = EXCLUDED.address, city = EXCLUDED.city, telephone = EXCLUDED.telephone, version = owner.version + 1""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Owner.class);
        var entity = repo.getById(1);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of("id"));
                assertTrue(sql.versionAware());
                assertEquals("Betty", sql.parameters().get(0).dbValue());
                assertEquals("Smith", sql.parameters().get(1).dbValue());
                assertEquals("638 Cardinal Ave.", sql.parameters().get(2).dbValue());
                assertEquals("Sun Prairie", sql.parameters().get(3).dbValue());
                assertEquals("6085551749", sql.parameters().get(4).dbValue());
            }
        }, () -> {
            var insert = repo.upsertAndFetch(entity.toBuilder()
                    .id(0)  // Default value.
                    .lastName("Smith").build());
            assertTrue(insert.id() != 1);
            assertEquals("Betty", insert.firstName());
            assertEquals("Smith", insert.lastName());
            assertEquals("638 Cardinal Ave.", insert.address().address());
            assertEquals("Sun Prairie", insert.address().city());
            assertEquals("6085551749", insert.telephone());
            assertEquals(0, insert.version());
        });
    }

    @Test
    public void testUpsertInlineVersionBatch() {
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
            repo.upsert(
                    entities.stream().map(entity -> entity.toBuilder().lastName("Smith").build()).toList()
            );
            var updates = repo.findAllById(List.of(1, 2)).stream().sorted(Comparator.comparingInt(Entity::id)).toList();
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

    @Builder(toBuilder = true)
    public record PetType(
            @PK Integer id,
            @Nonnull String name,
            @Nullable String description
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    public record Specialty(
            @PK(generation = NONE) Integer id,
            @Nonnull String name
    ) implements Entity<Integer> {}

    @Test
    public void testUpsertNonAutoGenerated() {
        String expectedSql = """
                INSERT INTO specialty (id, name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        observe(sql -> {
            assertEquals(expectedSql, sql.statement());
            assertEquals(sql.generatedKeys(), List.of());
            assertFalse(sql.versionAware());
            assertEquals(4, sql.parameters().get(0).dbValue());
            assertEquals("anaesthetics", sql.parameters().get(1).dbValue());
        }, () -> repo.upsert(Specialty.builder().id(4).name("anaesthetics").build()));
        var entity = repo.select().where(Metamodel.of(Specialty.class, "name"), EQUALS, "anaesthetics").getSingleResult();
        repo.upsert(entity.toBuilder().name("anaesthetist").build());
        var updated = repo.select().where(Metamodel.of(Specialty.class, "name"), EQUALS, "anaesthetist").getSingleResult();
        assertEquals(entity.id(), updated.id());
        assertEquals("anaesthetist", updated.name());
    }

    @Test
    public void testUpsertAndFetchNonAutoGenerated() {
        String expectedSql = """
                INSERT INTO specialty (id, name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
                assertEquals(sql.generatedKeys(), List.of());
                assertFalse(sql.versionAware());
                assertEquals(4, sql.parameters().get(0).dbValue());
                assertEquals("anaesthetics", sql.parameters().get(1).dbValue());
            }
        }, () -> {
            var entity = repo.upsertAndFetch(Specialty.builder().id(4).name("anaesthetics").build());
            var updated = repo.upsertAndFetch(entity.toBuilder().name("anaesthetist").build());
            assertEquals(entity.id(), updated.id());
            assertEquals("anaesthetist", updated.name());
        });
    }

    @Test
    public void testUpsertNonAutoGeneratedBatch() {
        String expectedSql = """
                INSERT INTO specialty (id, name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
        observe(sql -> {
            assertEquals(expectedSql, sql.statement());
            assertEquals(sql.generatedKeys(), List.of());
            assertFalse(sql.versionAware());
            assertTrue(sql.bindVariables().isPresent());
        }, () -> repo.upsert(List.of(
                Specialty.builder().id(4).name("anaesthetics").build(),
                Specialty.builder().id(5).name("nurse").build())));
        var entities = repo.select().where(Metamodel.of(Specialty.class, "id"), GREATER_THAN_OR_EQUAL, 4).getResultList();
        repo.upsert(entities.stream().map(e -> e.toBuilder().name("%ss".formatted(e.name())).build()).toList());
        var updated = repo.select().where(Metamodel.of(Specialty.class, "id"), GREATER_THAN_OR_EQUAL, 4).getResultList();
        assertEquals(2, updated.size());
        assertTrue(updated.stream().allMatch(entity -> entity.name().endsWith("s")));
    }

    @Test
    public void testUpsertAndFetchNonAutoGeneratedBatch() {
        String expectedSql = """
                INSERT INTO specialty (id, name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(Specialty.class);
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
                    Specialty.builder().id(4).name("anaesthetics").build(),
                    Specialty.builder().id(5).name("nurse").build()));
            var updated = repo.upsertAndFetch(entities.stream().map(e -> e.toBuilder().name("%ss".formatted(e.name())).build()).toList());
            assertEquals(2, updated.size());
            assertTrue(updated.stream().allMatch(entity -> entity.name().endsWith("s")));
        });
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
    public void testUuidDelete() {
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(ApiKey.class);
        long before = repo.count();
        repo.remove(repo.getById(DEFAULT_KEY_ID));
        assertEquals(before - 1, repo.count());
    }

    @BeforeEach
    void setUpBranchTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    DROP TABLE IF EXISTS version_long_entity;
                    """);
            statement.execute("""
                    CREATE TABLE version_long_entity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name VARCHAR(255),
                        version INTEGER DEFAULT 0
                    );
                    """);
            statement.execute("""
                    INSERT INTO version_long_entity (name) VALUES ('Alice');
                    """);
            statement.execute("""
                    INSERT INTO version_long_entity (name) VALUES ('Bob');
                    """);
            statement.execute("""
                    DROP TABLE IF EXISTS version_instant_entity;
                    """);
            statement.execute("""
                    CREATE TABLE version_instant_entity (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name VARCHAR(255),
                        version TIMESTAMP DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
                    );
                    """);
            statement.execute("""
                    INSERT INTO version_instant_entity (name) VALUES ('Alice');
                    """);
            statement.execute("""
                    INSERT INTO version_instant_entity (name) VALUES ('Bob');
                    """);
            statement.execute("""
                    DROP TABLE IF EXISTS pk_only_entity;
                    """);
            statement.execute("""
                    CREATE TABLE pk_only_entity (
                        id INTEGER PRIMARY KEY
                    );
                    """);
            statement.execute("""
                    INSERT INTO pk_only_entity (id) VALUES (1);
                    """);
            statement.execute("""
                    INSERT INTO pk_only_entity (id) VALUES (2);
                    """);
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
            assertTrue(sql.statement().contains("strftime('%Y-%m-%d %H:%M:%f', 'now')"));
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
    public void testUpsertPkOnlyEntity() {
        String expectedSql = """
                INSERT INTO pk_only_entity (id)
                VALUES (?)
                ON CONFLICT (id) DO NOTHING""";
        var repo = PreparedStatementTemplate.ORM(dataSource).entity(PkOnlyEntity.class);
        var first = new AtomicBoolean(false);
        observe(sql -> {
            if (!first.getAndSet(true)) {
                assertEquals(expectedSql, sql.statement());
            }
        }, () -> {
            repo.upsert(PkOnlyEntity.builder().id(1).build());
            repo.upsert(PkOnlyEntity.builder().id(3).build());
        });
        assertEquals(3, repo.findAll().size());
    }

    @Test
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
}
