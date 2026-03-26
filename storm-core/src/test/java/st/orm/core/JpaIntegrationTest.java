package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static st.orm.core.template.JpaTemplate.ORM;
import static st.orm.core.template.TemplateString.raw;

import jakarta.annotation.Nonnull;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.StormConfig;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.PetTypeEnum;
import st.orm.core.model.Vet;
import st.orm.core.template.JpaTemplate;
import st.orm.core.template.ORMTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class JpaIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    public void testSelectVet() {
        // data.sql inserts exactly 6 vets (ids 1-6).
        try (var query = ORM(entityManager).query("SELECT * FROM vet").prepare()) {
            assertEquals(6, query.getResultCount());
        }
    }

    @Test
    public void testSelectPet() {
        // INNER JOIN on owner excludes pet 13 (Sly, no owner). 12 of 13 pets have owners.
        try (var query = ORM(entityManager).query("""
            SELECT p.id, p.name, p.birth_date, pt.*, o.*
            FROM pet p
              INNER JOIN pet_type pt ON p.type_id = pt.id
              INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
            assertEquals(12, query.getResultCount());
        }
    }

    @Test
    public void testSelectPetTyped() {
        // JPA does not support wildcard (*) column expansion for typed result mapping.
        // Using "pt.*" and "o.*" in the SELECT causes a PersistenceException from the JPA provider.
        assertThrows(PersistenceException.class, () -> {
            try (var query = ORM(entityManager).query("""
                     SELECT p.id, p.name, p.birth_date, pt.*, o.*
                     FROM pet p
                       INNER JOIN pet_type pt ON p.type_id = pt.id
                       INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
                var stream = query.getResultStream(Pet.class);
                assertEquals(10, stream
                        .map(Pet::owner)
                        .filter(Objects::nonNull)
                        .map(Owner::firstName)
                        .distinct()
                        .count());
            }
        });
    }

    @Test
    public void testSelectPetTypedWithFilter() {
        // JPA does not support wildcard (*) column expansion; throws PersistenceException.
        assertThrows(PersistenceException.class, () -> {
            String nameFilter = "%y%";
            try (var query = ORM(entityManager).query(raw("""
                SELECT p.id, p.name, p.birth_date, pt.*, o.*
                FROM pet p
                  INNER JOIN pet_type pt ON p.type_id = pt.id
                  INNER JOIN owner o ON p.owner_id = o.id
                WHERE p.name LIKE \0""", nameFilter)).prepare()) {
                var stream = query.getResultStream(Pet.class);
                assertEquals(5, stream
                        .map(Pet::owner)
                        .filter(Objects::nonNull)
                        .map(Owner::firstName)
                        .distinct()
                        .count());
            }
        });
    }

    @Test
    public void testSelectPetTypedWithEnum() {
        // 12 owned pets span all 6 pet types (cat, dog, lizard, snake, bird, hamster).
        try (var query = ORM(entityManager).query("""
            SELECT UPPER(pt.name) pet_type
            FROM pet p
              INNER JOIN pet_type pt ON p.type_id = pt.id
              INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
            var stream = query.getResultStream(PetTypeEnum.class);
            assertEquals(6, stream.distinct().count());
        }
    }

    @Test
    public void testSelectPetTypedWithLocalRecordAndEnum() {
        // JPA does not support wildcard (*) column expansion for Owner; throws PersistenceException.
        assertThrows(PersistenceException.class, () -> {
            record Pet(int id, String name, LocalDate birthDate, PetTypeEnum type, Owner owner) {
            }
            try (var query = ORM(entityManager).query("""
                    SELECT p.id, p.name, p.birth_date, UPPER(pt.name) pet_type, o.*
                    FROM pet p
                      INNER JOIN pet_type pt ON p.type_id = pt.id
                      INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
                var stream = query.getResultStream(Pet.class);
                assertEquals(6, stream.map(Pet::type).distinct().count());
            }
        });
    }

    @Test
    public void testSelectPetTypedWithLocalRecordAndEnumNull() {
        // JPA does not support wildcard (*) column expansion for Owner; throws PersistenceException.
        assertThrows(PersistenceException.class, () -> {
            record Pet(int id, String name, LocalDate birthDate, PetTypeEnum type, Owner owner) {}
            try (var query = ORM(entityManager).query("""
                    SELECT p.id, p.name, p.birth_date, NULL pet_type, o.*
                    FROM pet p
                      INNER JOIN pet_type pt ON p.type_id = pt.id
                      INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
                var stream = query.getResultStream(Pet.class);
                stream.toList();
            }
        });
    }

    @Test
    public void testSelectPetTypedWithLocalRecordAndNonnullEnumNull() {
        // JPA does not support wildcard (*) column expansion for Owner; throws PersistenceException.
        assertThrows(PersistenceException.class, () -> {
            record Pet(int id, String name, LocalDate birthDate, @Nonnull PetTypeEnum type, Owner owner) {}
            try (var query = ORM(entityManager).query("""
                    SELECT p.id, p.name, p.birth_date, NULL pet_type, o.*
                    FROM pet p
                      INNER JOIN pet_type pt ON p.type_id = pt.id
                      INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
                var stream = query.getResultStream(Pet.class);
                stream.toList();
            }
        });
    }

    @Test
    public void testSelectPetTypedWithLocalRecordAndEnumNotExists() {
        // JPA does not support wildcard (*) column expansion for Owner; throws PersistenceException.
        assertThrows(PersistenceException.class, () -> {
            record Pet(int id, String name, LocalDate birthDate, PetTypeEnum type, Owner owner) {}
            try (var query = ORM(entityManager).query("""
                    SELECT p.id, p.name, p.birth_date, 'fail' pet_type, o.*
                    FROM pet p
                      INNER JOIN pet_type pt ON p.type_id = pt.id
                      INNER JOIN owner o ON p.owner_id = o.id""").prepare()) {
                var stream = query.getResultStream(Pet.class);
                stream.toList();
            }
        });
    }

    @Test
    public void testSelectPetWithoutType() {
        // LEFT OUTER JOIN with "1 <> 1" ensures pet_type columns are always NULL.
        // JPA does not support wildcard (*) column expansion; throws PersistenceException.
        assertThrows(PersistenceException.class, ()-> {
            try (var query = ORM(entityManager).query("""
                SELECT p.id, p.name, p.birth_date, pt.*, o.*
                FROM pet p
                  INNER JOIN owner o ON p.owner_id = o.id
                  LEFT OUTER JOIN pet_type pt ON p.type_id = pt.id AND 1 <> 1""").prepare();
                 var stream = query.getResultStream(Pet.class)) {
                stream.toList();
            }
        });
    }

    @Test
    public void testSelectPetWithoutOwner() {
        // LEFT OUTER JOIN with "1 <> 1" ensures owner columns are always NULL.
        // JPA does not support wildcard (*) column expansion; throws PersistenceException.
        assertThrows(PersistenceException.class, ()-> {
            try (var query = ORM(entityManager).query("""
                SELECT p.id, p.name, p.birth_date, pt.*, o.*
                FROM pet p
                  INNER JOIN pet_type pt ON p.type_id = pt.id
                  LEFT OUTER JOIN owner o ON p.owner_id = o.id AND 1 <> 1""").prepare()) {
                var stream = query.getResultStream(Pet.class);
                stream.toList();
            }
        });
    }

    @Test
    public void testSelectVetRecord() {
        // Template expansion of \0 for Vet selects and joins correctly via JPA. data.sql has 6 vets.
        try (var query = ORM(entityManager).query(raw("SELECT \0 FROM \0", Vet.class, Vet.class)).prepare()) {
            assertEquals(6, query.getResultCount());
        }
    }

    @Test
    void jpaTemplate_of_entityManager() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        assertNotNull(jpa);
        // Execute a basic query to ensure the template is functional.
        var result = jpa.query(raw("SELECT COUNT(*) FROM vet"));
        assertNotNull(result);
    }

    @Test
    void jpaTemplate_of_entityManager_withConfig() {
        StormConfig config = StormConfig.of(Map.of());
        JpaTemplate jpa = JpaTemplate.of(entityManager, config);
        assertNotNull(jpa);
        var result = jpa.query(raw("SELECT COUNT(*) FROM city"));
        assertNotNull(result);
    }

    @Test
    void jpaTemplate_toORM() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        ORMTemplate orm = jpa.toORM();
        assertNotNull(orm);
        assertNotNull(orm.config());
    }

    @Test
    void ormTemplate_of_entityManager_basic() {
        ORMTemplate orm = JpaTemplate.ORM(entityManager);
        assertNotNull(orm);
    }

    @Test
    void ormTemplate_of_entityManager_withConfig() {
        StormConfig config = StormConfig.of(Map.of());
        ORMTemplate orm = JpaTemplate.ORM(entityManager, config);
        assertNotNull(orm);
        assertEquals(config, orm.config());
    }

    @Test
    void ormTemplate_of_entityManager_withDecorator() {
        ORMTemplate orm = JpaTemplate.ORM(entityManager, t -> t);
        assertNotNull(orm);
    }

    @Test
    void ormTemplate_of_entityManager_withDecorator_customTableNameResolver() {
        ORMTemplate orm = JpaTemplate.ORM(entityManager, t -> t.withTableNameResolver(null));
        assertNotNull(orm);
    }

    @Test
    void ormTemplate_of_entityManager_withConfigAndDecorator() {
        StormConfig config = StormConfig.of(Map.of());
        ORMTemplate orm = JpaTemplate.ORM(entityManager, config, t -> t);
        assertNotNull(orm);
        assertEquals(config, orm.config());
    }

    @Test
    void ormTemplate_of_entityManager_decoratorMustReturnSameType() {
        // Decorator that returns a non-JpaTemplateImpl should throw.
        assertThrows(st.orm.PersistenceException.class, () ->
                JpaTemplate.ORM(entityManager, t -> new TemplateDecoratorStub()));
    }

    @Test
    void ormTemplate_of_entityManager_configAndDecorator_decoratorMustReturnSameType() {
        StormConfig config = StormConfig.of(Map.of());
        assertThrows(st.orm.PersistenceException.class, () ->
                JpaTemplate.ORM(entityManager, config, t -> new TemplateDecoratorStub()));
    }

    @Test
    void jpaTemplate_ORM_static() {
        ORMTemplate orm = ORM(entityManager);
        assertNotNull(orm);
    }

    @Test
    void jpaTemplate_ORM_static_withConfig() {
        StormConfig config = StormConfig.of(Map.of());
        ORMTemplate orm = JpaTemplate.ORM(entityManager, config);
        assertNotNull(orm);
        assertEquals(config, orm.config());
    }

    @Test
    void jpaOrm_countCities() {
        try (var q = ORM(entityManager).query(raw("SELECT COUNT(*) FROM city")).prepare()) {
            assertEquals(6, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_countOwners() {
        try (var q = ORM(entityManager).query(raw("SELECT COUNT(*) FROM owner")).prepare()) {
            assertEquals(10, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_countPets() {
        // pet table has 13 rows (12 with owners + 1 without).
        try (var q = ORM(entityManager).query(raw("SELECT COUNT(*) FROM pet")).prepare()) {
            assertEquals(13, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_countVisits() {
        try (var q = ORM(entityManager).query(raw("SELECT COUNT(*) FROM visit")).prepare()) {
            assertEquals(14, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_selectStringColumn() {
        try (var q = ORM(entityManager).query(raw("SELECT name FROM city ORDER BY id")).prepare()) {
            var names = q.getResultStream(String.class).toList();
            assertEquals(6, names.size());
            assertEquals("Sun Paririe", names.get(0));
            assertEquals("Waunakee", names.get(5));
        }
    }

    @Test
    void jpaOrm_selectIntColumn() {
        try (var q = ORM(entityManager).query(raw("SELECT id FROM city ORDER BY id")).prepare()) {
            var ids = q.getResultStream(int.class).toList();
            assertEquals(6, ids.size());
            assertEquals(1, ids.get(0));
            assertEquals(6, ids.get(5));
        }
    }

    @Test
    void jpaOrm_queryWithParameter() {
        try (var q = ORM(entityManager).query(raw("SELECT COUNT(*) FROM owner WHERE city_id = \0", 2)).prepare()) {
            assertEquals(4, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_queryWithStringParameter() {
        try (var q = ORM(entityManager).query(raw("SELECT id FROM city WHERE name = \0", "Madison")).prepare()) {
            assertEquals(2, (int) q.getResultStream(int.class).findFirst().orElse(0));
        }
    }

    @Test
    void jpaOrm_getResultCount_vets() {
        try (var q = ORM(entityManager).query("SELECT * FROM vet").prepare()) {
            assertEquals(6, q.getResultCount());
        }
    }

    @Test
    void jpaOrm_getResultCount_cities() {
        try (var q = ORM(entityManager).query("SELECT * FROM city").prepare()) {
            assertEquals(6, q.getResultCount());
        }
    }

    @Test
    void jpaOrm_selectVetRecord() {
        try (var q = ORM(entityManager).query(raw("SELECT \0 FROM \0", Vet.class, Vet.class)).prepare()) {
            assertEquals(6, q.getResultCount());
        }
    }

    @Test
    void jpaOrm_executeUpdate_insertCity() {
        try (var q = ORM(entityManager).query(raw("INSERT INTO city (name) VALUES (\0)", "TestCity")).prepare()) {
            int count = q.executeUpdate();
            assertEquals(1, count);
        }

        // Verify the city was inserted.
        try (var q = ORM(entityManager).query(raw("SELECT COUNT(*) FROM city")).prepare()) {
            assertEquals(7, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_executeUpdate_updateCity() {
        try (var q = ORM(entityManager).query(raw("UPDATE city SET name = \0 WHERE id = \0", "Renamed", 1)).prepare()) {
            int count = q.executeUpdate();
            assertEquals(1, count);
        }

        try (var q = ORM(entityManager).query(raw("SELECT name FROM city WHERE id = \0", 1)).prepare()) {
            assertEquals("Renamed", q.getResultStream(String.class).findFirst().orElse(null));
        }
    }

    @Test
    void jpaOrm_executeUpdate_deleteCityNoFk() {
        // Insert a city with no FK references, then delete it.
        try (var q = ORM(entityManager).query(raw("INSERT INTO city (name) VALUES (\0)", "ToDelete")).prepare()) {
            q.executeUpdate();
        }
        try (var q = ORM(entityManager).query(raw("DELETE FROM city WHERE name = \0", "ToDelete")).prepare()) {
            int count = q.executeUpdate();
            assertEquals(1, count);
        }
    }

    @Test
    void jpaOrm_unsafeAndManaged_noOps() {
        var orm = ORM(entityManager);
        // unsafe() and managed() return Query, so call them before prepare().
        try (var q = orm.query(raw("SELECT COUNT(*) FROM vet")).unsafe().managed().prepare()) {
            assertEquals(6, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    @Test
    void jpaOrm_createBindVars_throwsPersistenceException() {
        var orm = ORM(entityManager);
        assertThrows(PersistenceException.class, orm::createBindVars);
    }

    @Test
    void jpaTemplate_withTableNameResolver() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        JpaTemplate customized = jpa.withTableNameResolver(null);
        assertNotNull(customized);
        // Ensure it still works.
        var result = customized.query(raw("SELECT COUNT(*) FROM vet"));
        assertNotNull(result);
    }

    @Test
    void jpaTemplate_withColumnNameResolver() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        JpaTemplate customized = jpa.withColumnNameResolver(null);
        assertNotNull(customized);
    }

    @Test
    void jpaTemplate_withForeignKeyResolver() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        JpaTemplate customized = jpa.withForeignKeyResolver(null);
        assertNotNull(customized);
    }

    @Test
    void jpaTemplate_withTableAliasResolver() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        JpaTemplate customized = jpa.withTableAliasResolver(
                st.orm.core.template.TableAliasResolver.DEFAULT);
        assertNotNull(customized);
    }

    @Test
    void jpaTemplate_withProviderFilter() {
        JpaTemplate jpa = JpaTemplate.of(entityManager);
        JpaTemplate customized = jpa.withProviderFilter(p -> true);
        assertNotNull(customized);
    }

    @Test
    void jpaOrm_getResultStream_objectArray() {
        try (var q = ORM(entityManager).query(raw("SELECT id, name FROM city ORDER BY id")).prepare()) {
            var rows = q.getResultStream().toList();
            assertEquals(6, rows.size());
            // Each row should be an Object array.
            assertEquals(2, rows.get(0).length);
        }
    }

    @Test
    void jpaOrm_getResultStream_singleColumn_wrappedAsArray() {
        try (var q = ORM(entityManager).query(raw("SELECT name FROM city WHERE id = \0", 1)).prepare()) {
            var rows = q.getResultStream().toList();
            assertEquals(1, rows.size());
            // Single column results are wrapped in an Object[1].
            assertEquals(1, rows.get(0).length);
            assertEquals("Sun Paririe", rows.get(0)[0]);
        }
    }

    @Test
    void jpaOrm_queryWithTypeExpansion_selectColumns() {
        try (var q = ORM(entityManager).query(raw("SELECT \0 FROM \0", Vet.class, Vet.class)).prepare()) {
            var vets = q.getResultStream().toList();
            assertEquals(6, vets.size());
        }
    }

    @Test
    void jpaOrm_badSql_throwsPersistenceException() {
        assertThrows(PersistenceException.class, () -> {
            try (var q = ORM(entityManager).query(raw("SELECT * FROM nonexistent_table")).prepare()) {
                q.getResultStream().toList();
            }
        });
    }

    @Test
    void jpaOrm_multipleQueriesOnSameTemplate() {
        var orm = ORM(entityManager);

        try (var q = orm.query(raw("SELECT COUNT(*) FROM city")).prepare()) {
            assertEquals(6, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }

        try (var q = orm.query(raw("SELECT COUNT(*) FROM vet")).prepare()) {
            assertEquals(6, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }

        try (var q = orm.query(raw("SELECT COUNT(*) FROM owner")).prepare()) {
            assertEquals(10, (long) q.getResultStream(long.class).findFirst().orElse(0L));
        }
    }

    private static class TemplateDecoratorStub implements st.orm.mapping.TemplateDecorator {
        @Override
        public st.orm.mapping.TemplateDecorator withTableNameResolver(st.orm.mapping.TableNameResolver r) {
            return this;
        }

        @Override
        public st.orm.mapping.TemplateDecorator withColumnNameResolver(st.orm.mapping.ColumnNameResolver r) {
            return this;
        }

        @Override
        public st.orm.mapping.TemplateDecorator withForeignKeyResolver(st.orm.mapping.ForeignKeyResolver r) {
            return this;
        }
    }
}
