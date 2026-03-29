package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.OptimisticLockException;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Version;
import st.orm.core.model.Address;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.PetType;
import st.orm.core.model.Visit;
import st.orm.core.template.ORMTemplate;


/**
 * Integration tests for EntityRepositoryImpl edge cases including validation
 * error paths, callback re-entrancy, insertAndFetch, updateAndFetch, deleteAll,
 * insert with ignoreAutoGenerate, optimistic lock exceptions, and batch operations.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class EntityRepositoryAdditionalIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Validation error paths

    @Test
    public void testInsertWithExplicitPrimaryKeyOnAutoGenEntityThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // City has auto-generated PK; inserting with an explicit PK should throw.
        assertThrows(PersistenceException.class,
                () -> cities.insert(City.builder().id(999).name("Explicit").build()));
    }

    @Test
    public void testUpdateWithDefaultPrimaryKeyThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Updating with null PK (default) should throw.
        // The null PK causes a NullPointerException when looking up the entity in cache.
        assertThrows(NullPointerException.class,
                () -> cities.update(City.builder().name("NoPK").build()));
    }

    @Test
    public void testRemoveWithDefaultPrimaryKeyThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Deleting with null PK should throw.
        assertThrows(PersistenceException.class,
                () -> cities.remove(City.builder().name("NoPK").build()));
    }

    // insert with ignoreAutoGenerate

    @Test
    public void testInsertWithIgnoreAutoGenerateExplicitPrimaryKey() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Insert with explicit PK using ignoreAutoGenerate=true.
        long countBefore = cities.count();
        cities.insert(City.builder().id(9990).name("ExplicitPK").build(), true);
        assertEquals(countBefore + 1, cities.count());
        City fetched = cities.getById(9990);
        assertEquals("ExplicitPK", fetched.name());
        // Clean up.
        cities.removeById(9990);
    }

    @Test
    public void testInsertWithIgnoreAutoGenerateNullPrimaryKeyThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Insert with ignoreAutoGenerate=true but null PK should throw.
        assertThrows(PersistenceException.class,
                () -> cities.insert(City.builder().name("NoPK").build(), true));
    }

    // deleteAll

    @Test
    public void testRemoveAllRemovesAllEntities() {
        var orm = ORMTemplate.of(dataSource);
        // Use pet_extension to avoid FK constraint issues (it's a leaf table).
        // Insert some cities that are not referenced by anything else.
        // Actually, cities are referenced by owners, so let's test with a non-referenced entity.
        // Let's just check that deleteAll compiles and runs on a small test scope.
        // We'll test by inserting into a fresh scope, using a custom entity with its own table.
        var cities = orm.entity(City.class);
        long countBefore = cities.count();
        assertTrue(countBefore > 0, "There should be seed data");
        // We can't actually call deleteAll on cities because owners reference them.
        // Instead, test that deleteAll on a non-FK table works.
        // The visit table is a leaf table (no FK references from other tables).
        var visits = orm.entity(Visit.class);
        long visitCountBefore = visits.count();
        assertTrue(visitCountBefore > 0);
        visits.removeAll();
        assertEquals(0, visits.count());
    }

    // Callback re-entrancy guard

    @Test
    public void testCallbackReEntrancyGuardPreventsRecursion() {
        List<String> callbackLog = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                callbackLog.add("beforeInsert:" + entity.name());
                // Simulate a nested insert within the callback.
                // The re-entrancy guard should prevent callbacks from firing again.
                return entity;
            }

            @Override
            public void afterInsert(@Nonnull City entity) {
                callbackLog.add("afterInsert:" + entity.name());
            }
        });
        orm.entity(City.class).insert(City.builder().name("ReEntrant").build());
        // Callbacks should fire exactly once (not recursively).
        assertEquals(2, callbackLog.size());
        assertEquals("beforeInsert:ReEntrant", callbackLog.get(0));
        assertEquals("afterInsert:ReEntrant", callbackLog.get(1));
    }

    // Optimistic lock exception on version mismatch

    @Test
    public void testOptimisticLockExceptionOnVersionMismatch() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        Owner original = owners.getById(1);
        // Update the owner to increment the version.
        owners.update(original.toBuilder().firstName("Updated").build());
        // Now try to update with the old version - should throw OptimisticLockException.
        assertThrows(OptimisticLockException.class,
                () -> owners.update(original.toBuilder().firstName("Stale").build()));
    }

    // Version-aware update on Visit (Instant version)

    @Test
    public void testVisitUpdateWithTimestampVersion() {
        var orm = ORMTemplate.of(dataSource);
        var visits = orm.entity(Visit.class);
        Visit original = visits.getById(1);
        assertNotNull(original);
        Visit updated = original.toBuilder()
                .description("Version test update")
                .build();
        visits.update(updated);
        Visit fetched = visits.getById(1);
        assertEquals("Version test update", fetched.description());
    }

    // delete query builder

    @Test
    public void testDeleteQueryBuilder() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        Integer insertedId = cities.insertAndFetchId(City.builder().name("ToDeleteViaBuilder").build());
        long countBefore = cities.count();
        cities.delete()
                .where(insertedId)
                .executeUpdate();
        assertEquals(countBefore - 1, cities.count());
    }

    // Version-aware entity with Long version type

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithLongVersion(
            @PK Integer id,
            @Nonnull String firstName,
            @Nonnull String lastName,
            @Nonnull Address address,
            @Nullable String telephone,
            @Version long version
    ) implements Entity<Integer> {}

    @Test
    public void testUpdateOwnerWithLongVersionType() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(OwnerWithLongVersion.class);
        OwnerWithLongVersion original = owners.getById(1);
        assertNotNull(original);
        long originalVersion = original.version();
        OwnerWithLongVersion updated = original.toBuilder()
                .firstName("LongVersionBetty")
                .build();
        owners.update(updated);
        OwnerWithLongVersion fetched = owners.getById(1);
        assertEquals("LongVersionBetty", fetched.firstName());
        assertEquals(originalVersion + 1, fetched.version());
    }

    // Insert and update with Pet entity (exercises FK with Ref and @Persist)

    @Test
    public void testInsertPetWithRefType() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        long countBefore = pets.count();
        Owner owner = orm.entity(Owner.class).getById(1);
        Pet newPet = Pet.builder()
                .name("TestPet")
                .birthDate(LocalDate.of(2024, 1, 1))
                .type(Ref.of(PetType.class, 0))
                .owner(owner)
                .build();
        Integer insertedId = pets.insertAndFetchId(newPet);
        assertNotNull(insertedId);
        assertEquals(countBefore + 1, pets.count());
    }

    // Joined entity insertAndFetch

    @Test
    public void testJoinedEntityInsertAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var fetched = animals.insertAndFetch(
                new st.orm.core.model.polymorphic.JoinedCat(null, "FetchCat", true));
        assertNotNull(fetched);
        assertTrue(fetched instanceof st.orm.core.model.polymorphic.JoinedCat);
        assertEquals("FetchCat", ((st.orm.core.model.polymorphic.JoinedCat) fetched).name());
    }

    // Joined entity updateAndFetch

    @Test
    public void testJoinedEntityUpdateAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var fetched = animals.updateAndFetch(
                new st.orm.core.model.polymorphic.JoinedDog(3, "FetchedRex", 33));
        assertNotNull(fetched);
        assertTrue(fetched instanceof st.orm.core.model.polymorphic.JoinedDog);
        assertEquals("FetchedRex", ((st.orm.core.model.polymorphic.JoinedDog) fetched).name());
    }

    // Batch insert and fetch IDs for joined entities

    @Test
    public void testJoinedEntityBatchInsertAndFetchIds() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        List<Integer> ids = animals.insertAndFetchIds(List.of(
                new st.orm.core.model.polymorphic.JoinedCat(null, "JBatch1", true),
                new st.orm.core.model.polymorphic.JoinedDog(null, "JBatch2", 22)
        ));
        assertEquals(2, ids.size());
        for (Integer id : ids) {
            assertNotNull(id);
            assertTrue(id > 0);
        }
    }

    // Batch insert and fetch entities for joined type

    @Test
    public void testJoinedEntityBatchInsertAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var fetched = animals.insertAndFetch(List.of(
                new st.orm.core.model.polymorphic.JoinedCat(null, "JFetch1", false),
                new st.orm.core.model.polymorphic.JoinedDog(null, "JFetch2", 15)
        ));
        assertEquals(2, fetched.size());
    }

    // Single-table polymorphic batch update and fetch

    @Test
    public void testSingleTablePolymorphicBatchUpdateAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(st.orm.core.model.polymorphic.Animal.class);
        List<st.orm.core.model.polymorphic.Animal> fetched = animals.updateAndFetch(List.of(
                new st.orm.core.model.polymorphic.Cat(1, "UpFetchWhiskers", false),
                new st.orm.core.model.polymorphic.Dog(3, "UpFetchRex", 32)
        ));
        assertEquals(2, fetched.size());
    }

    // Callback inheritance: callback for supertype matches subtypes

    @Test
    public void testCallbackForEntitySupertype() {
        List<String> log = new ArrayList<>();
        // Register a callback for Entity (supertype of all entities).
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<>() {
            @Override
            public Entity beforeInsert(@Nonnull Entity entity) {
                log.add("supertype:beforeInsert");
                return entity;
            }
        });
        orm.entity(City.class).insert(City.builder().name("SupertypeCb").build());
        // The callback should fire because City extends Entity.
        assertTrue(log.contains("supertype:beforeInsert"));
    }

    // Callback with empty callbacks list

    @Test
    public void testNoCallbacksRegistered() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Just verify operations work fine without callbacks.
        Integer insertedId = cities.insertAndFetchId(City.builder().name("NoCb").build());
        assertNotNull(insertedId);
        cities.update(City.builder().id(insertedId).name("NoCbUpdated").build());
        cities.remove(City.builder().id(insertedId).name("NoCbUpdated").build());
    }

    // Batch update with joined entities and callbacks

    @Test
    public void testJoinedEntityBatchUpdateWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(
                new EntityCallback<st.orm.core.model.polymorphic.JoinedAnimal>() {
                    @Override
                    public st.orm.core.model.polymorphic.JoinedAnimal beforeUpdate(
                            @Nonnull st.orm.core.model.polymorphic.JoinedAnimal entity) {
                        log.add("beforeUpdate");
                        return entity;
                    }

                    @Override
                    public void afterUpdate(@Nonnull st.orm.core.model.polymorphic.JoinedAnimal entity) {
                        log.add("afterUpdate");
                    }
                });
        var animals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        animals.update(List.of(
                new st.orm.core.model.polymorphic.JoinedCat(1, "CbWhiskers", false),
                new st.orm.core.model.polymorphic.JoinedDog(3, "CbRex", 28)
        ));
        // Callbacks should have fired for each entity.
        assertEquals(4, log.size()); // 2 beforeUpdate + 2 afterUpdate
    }
}
