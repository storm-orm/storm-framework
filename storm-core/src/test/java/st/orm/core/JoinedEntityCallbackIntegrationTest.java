package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.GenerationStrategy;
import st.orm.NoResultException;
import st.orm.PK;
import st.orm.Ref;
import st.orm.core.model.City;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.model.polymorphic.JoinedDog;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests targeting uncovered branches in JoinedEntityHelper
 * (callback-aware joined entity CRUD) and EntityRepositoryImpl
 * (findByRef, getByRef, re-entrant callbacks, callback type resolution).
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class JoinedEntityCallbackIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Joined entity INSERT with callbacks
    // Exercises insertJoined + beforeInsert/afterInsert callback paths

    @Test
    public void testJoinedEntityInsertWithBeforeAndAfterCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public JoinedAnimal beforeInsert(@Nonnull JoinedAnimal entity) {
                log.add("beforeInsert:" + entity.getClass().getSimpleName());
                return entity;
            }

            @Override
            public void afterInsert(@Nonnull JoinedAnimal entity) {
                log.add("afterInsert:" + entity.getClass().getSimpleName());
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        Integer catId = animals.insertAndFetchId(new JoinedCat(null, "CbCat", true));
        assertNotNull(catId);
        assertTrue(log.contains("beforeInsert:JoinedCat"));
        assertTrue(log.contains("afterInsert:JoinedCat"));
    }

    @Test
    public void testJoinedEntityInsertTransformViaCallback() {
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public JoinedAnimal beforeInsert(@Nonnull JoinedAnimal entity) {
                if (entity instanceof JoinedCat cat) {
                    return new JoinedCat(cat.id(), cat.name().toUpperCase(), cat.indoor());
                }
                return entity;
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        Integer insertedId = animals.insertAndFetchId(new JoinedCat(null, "lowercase", false));
        JoinedAnimal fetched = animals.getById(insertedId);
        assertTrue(fetched instanceof JoinedCat);
        assertEquals("LOWERCASE", ((JoinedCat) fetched).name());
    }

    // Joined entity batch INSERT with callbacks
    // Exercises insertJoinedBatch + callback paths for multiple entities

    @Test
    public void testJoinedEntityBatchInsertWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public JoinedAnimal beforeInsert(@Nonnull JoinedAnimal entity) {
                log.add("beforeInsert");
                return entity;
            }

            @Override
            public void afterInsert(@Nonnull JoinedAnimal entity) {
                log.add("afterInsert");
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        List<Integer> ids = animals.insertAndFetchIds(List.of(
                new JoinedCat(null, "BatchCbCat1", true),
                new JoinedDog(null, "BatchCbDog1", 20)
        ));
        assertEquals(2, ids.size());
        // 2 beforeInsert + 2 afterInsert
        assertEquals(4, log.size());
    }

    // Joined entity UPDATE with callbacks
    // Exercises updateJoined + beforeUpdate/afterUpdate callback paths

    @Test
    public void testJoinedEntityUpdateWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public JoinedAnimal beforeUpdate(@Nonnull JoinedAnimal entity) {
                log.add("beforeUpdate:" + entity.getClass().getSimpleName());
                return entity;
            }

            @Override
            public void afterUpdate(@Nonnull JoinedAnimal entity) {
                log.add("afterUpdate:" + entity.getClass().getSimpleName());
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        animals.update(new JoinedCat(1, "UpdatedCbWhiskers", false));
        assertTrue(log.contains("beforeUpdate:JoinedCat"));
        assertTrue(log.contains("afterUpdate:JoinedCat"));
    }

    @Test
    public void testJoinedEntityBatchUpdateWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public JoinedAnimal beforeUpdate(@Nonnull JoinedAnimal entity) {
                log.add("beforeUpdate");
                return entity;
            }

            @Override
            public void afterUpdate(@Nonnull JoinedAnimal entity) {
                log.add("afterUpdate");
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        animals.update(List.of(
                new JoinedCat(1, "BatchCbWhiskers", true),
                new JoinedDog(3, "BatchCbRex", 35)
        ));
        assertEquals(4, log.size()); // 2 beforeUpdate + 2 afterUpdate
    }

    // Joined entity DELETE with callbacks
    // Exercises deleteJoined + beforeDelete/afterDelete callback paths

    @Test
    public void testJoinedEntityRemoveWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public void beforeDelete(@Nonnull JoinedAnimal entity) {
                log.add("beforeDelete");
            }

            @Override
            public void afterDelete(@Nonnull JoinedAnimal entity) {
                log.add("afterDelete");
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        // First insert a new entity so we can safely delete it.
        Integer newId = animals.insertAndFetchId(new JoinedCat(null, "TempCatDel", false));
        JoinedAnimal toDelete = animals.getById(newId);
        animals.remove(toDelete);
        assertTrue(log.contains("beforeDelete"));
        assertTrue(log.contains("afterDelete"));
    }

    @Test
    public void testJoinedEntityBatchRemoveWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public void beforeDelete(@Nonnull JoinedAnimal entity) {
                log.add("beforeDelete");
            }

            @Override
            public void afterDelete(@Nonnull JoinedAnimal entity) {
                log.add("afterDelete");
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        Integer id1 = animals.insertAndFetchId(new JoinedCat(null, "DelBatch1", true));
        Integer id2 = animals.insertAndFetchId(new JoinedDog(null, "DelBatch2", 10));
        JoinedAnimal cat = animals.getById(id1);
        JoinedAnimal dog = animals.getById(id2);
        animals.remove(List.of(cat, dog));
        assertEquals(4, log.size()); // 2 beforeDelete + 2 afterDelete
    }

    // Joined entity deleteById (exercises deleteJoined with null concreteType)

    @Test
    public void testJoinedEntityRemoveById() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer newId = animals.insertAndFetchId(new JoinedDog(null, "TempDogDel", 5));
        long countBefore = animals.count();
        animals.removeById(newId);
        assertEquals(countBefore - 1, animals.count());
    }

    // Joined entity batch deleteByRef

    @Test
    public void testJoinedEntityBatchRemoveByRef() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer id1 = animals.insertAndFetchId(new JoinedCat(null, "RefDel1", true));
        Integer id2 = animals.insertAndFetchId(new JoinedDog(null, "RefDel2", 15));
        long countBefore = animals.count();
        animals.removeByRef(List.of(
                Ref.of(JoinedAnimal.class, id1),
                Ref.of(JoinedAnimal.class, id2)
        ));
        assertEquals(countBefore - 2, animals.count());
    }

    // Joined entity batch deleteById

    @Test
    public void testJoinedEntityBatchRemoveByIdList() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer id1 = animals.insertAndFetchId(new JoinedCat(null, "IdDel1", false));
        Integer id2 = animals.insertAndFetchId(new JoinedDog(null, "IdDel2", 8));
        long countBefore = animals.count();
        animals.removeById(id1);
        animals.removeById(id2);
        assertEquals(countBefore - 2, animals.count());
    }

    // Joined entity stream-based batch insert

    @Test
    public void testJoinedEntityStreamInsert() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long countBefore = animals.count();
        animals.insert(Stream.of(
                new JoinedCat(null, "StreamCat1", true),
                new JoinedDog(null, "StreamDog1", 12),
                new JoinedCat(null, "StreamCat2", false)
        ), 2); // Small batch size.
        assertEquals(countBefore + 3, animals.count());
    }

    // Joined entity stream-based batch update

    @Test
    public void testJoinedEntityStreamUpdate() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        animals.update(Stream.of(
                new JoinedCat(1, "StreamUpdWhiskers", false),
                new JoinedDog(3, "StreamUpdRex", 40)
        ), 1); // Batch size 1.
        assertEquals("StreamUpdWhiskers", ((JoinedCat) animals.getById(1)).name());
        assertEquals("StreamUpdRex", ((JoinedDog) animals.getById(3)).name());
    }

    // Joined entity stream-based batch delete

    @Test
    public void testJoinedEntityStreamRemove() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer id1 = animals.insertAndFetchId(new JoinedCat(null, "StreamDel1", true));
        Integer id2 = animals.insertAndFetchId(new JoinedDog(null, "StreamDel2", 7));
        JoinedAnimal cat = animals.getById(id1);
        JoinedAnimal dog = animals.getById(id2);
        long countBefore = animals.count();
        animals.remove(Stream.of(cat, dog), 1);
        assertEquals(countBefore - 2, animals.count());
    }

    // EntityRepositoryImpl: findById returns Optional

    @Test
    public void testFindByIdReturnsEmptyForNonExistent() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        Optional<City> result = cities.findById(99999);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testFindByIdReturnsPresentForExistent() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        Optional<City> result = cities.findById(1);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().id());
    }

    // EntityRepositoryImpl: findByRef returns Optional

    @Test
    public void testFindByRefReturnsEmptyForNonExistent() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        Optional<City> result = cities.findByRef(Ref.of(City.class, 99999));
        assertTrue(result.isEmpty());
    }

    @Test
    public void testFindByRefReturnsPresentForExistent() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        Optional<City> result = cities.findByRef(Ref.of(City.class, 1));
        assertTrue(result.isPresent());
    }

    // EntityRepositoryImpl: getByRef

    @Test
    public void testGetByRefReturnsEntity() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        City city = cities.getByRef(Ref.of(City.class, 1));
        assertNotNull(city);
        assertEquals(1, city.id());
    }

    @Test
    public void testGetByRefNonExistentThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertThrows(NoResultException.class,
                () -> cities.getByRef(Ref.of(City.class, 99999)));
    }

    // EntityRepositoryImpl: re-entrant callback (callback triggers insert)
    // This exercises the CALLBACK_ACTIVE ThreadLocal path.

    @Test
    public void testCallbackReEntrancyInsertWithinCallback() {
        List<String> log = new ArrayList<>();
        ORMTemplate baseOrm = ORMTemplate.of(dataSource);
        ORMTemplate orm = baseOrm.withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                log.add("before:" + entity.name());
                return entity;
            }

            @Override
            public void afterInsert(@Nonnull City entity) {
                log.add("after:" + entity.name());
                // Attempt a re-entrant insert within afterInsert.
                // Due to CALLBACK_ACTIVE guard, callbacks should NOT fire for this nested insert.
                if (entity.name().equals("Primary")) {
                    baseOrm.entity(City.class).insert(City.builder().name("Nested").build());
                }
            }
        });
        orm.entity(City.class).insert(City.builder().name("Primary").build());
        // "before:Primary" and "after:Primary" should fire.
        assertTrue(log.contains("before:Primary"));
        assertTrue(log.contains("after:Primary"));
    }

    // EntityRepositoryImpl: callback type resolution with subtype specificity
    // Tests resolveCallbackEntityType with a callback for a specific subtype.

    @Test
    public void testCallbackForSpecificSubtypeUsesCityCallback() {
        // Test that a callback for a specific entity type only fires for that type.
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource)
                .withEntityCallback(new EntityCallback<City>() {
                    @Override
                    public City beforeInsert(@Nonnull City entity) {
                        log.add("cityCallback:" + entity.name());
                        return entity;
                    }
                });
        // City callback should fire for City inserts.
        orm.entity(City.class).insert(City.builder().name("CbSpecific").build());
        assertTrue(log.contains("cityCallback:CbSpecific"));
    }

    // EntityRepositoryImpl: callback inheritance with generic Entity type

    @Test
    public void testCallbackForGenericEntityType() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource)
                .withEntityCallback(new EntityCallback<>() {
                    @Override
                    public Entity beforeInsert(@Nonnull Entity entity) {
                        log.add("genericCallback");
                        return entity;
                    }
                });
        orm.entity(City.class).insert(City.builder().name("GenericCb").build());
        assertTrue(log.contains("genericCallback"));
    }

    // Sequence-based entities not testable on H2's default dialect (sequences not supported).

    // NONE generation strategy entity (exercises ValuesProcessor NONE path)
    // Define a custom entity with explicit NONE generation.

    @DbTable("pet_type")
    public record PetTypeNone(
            @PK(generation = GenerationStrategy.NONE) Integer id,
            String name
    ) implements Entity<Integer> {}

    @Test
    public void testNoneGenerationStrategyInsert() {
        var orm = ORMTemplate.of(dataSource);
        var petTypes = orm.entity(PetTypeNone.class);
        long countBefore = petTypes.count();
        petTypes.insert(new PetTypeNone(99, "test_type"));
        assertEquals(countBefore + 1, petTypes.count());
    }

    // Joined entity: updateAndFetch with callbacks

    @Test
    public void testJoinedEntityUpdateAndFetchWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public JoinedAnimal beforeUpdate(@Nonnull JoinedAnimal entity) {
                log.add("beforeUpdate");
                return entity;
            }

            @Override
            public void afterUpdate(@Nonnull JoinedAnimal entity) {
                log.add("afterUpdate");
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        JoinedAnimal updated = animals.updateAndFetch(new JoinedCat(1, "CbFetchWhiskers", true));
        assertNotNull(updated);
        assertTrue(log.contains("beforeUpdate"));
        assertTrue(log.contains("afterUpdate"));
    }

    // Joined entity: batch updateAndFetch

    @Test
    public void testJoinedEntityBatchUpdateAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        List<JoinedAnimal> fetched = animals.updateAndFetch(List.of(
                new JoinedCat(1, "BatchFetchWhiskers", false),
                new JoinedDog(3, "BatchFetchRex", 42)
        ));
        assertEquals(2, fetched.size());
    }

    // Joined entity: insertAndFetch batch

    @Test
    public void testJoinedEntityBatchInsertAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        List<JoinedAnimal> fetched = animals.insertAndFetch(List.of(
                new JoinedCat(null, "InsertFetch1", true),
                new JoinedDog(null, "InsertFetch2", 22)
        ));
        assertEquals(2, fetched.size());
        for (JoinedAnimal animal : fetched) {
            assertNotNull(animal.id());
        }
    }

    // Joined entity: delete with callback transforms nothing (void)

    @Test
    public void testJoinedEntityRemoveByIdWithCallbacks() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<JoinedAnimal>() {
            @Override
            public void beforeDelete(@Nonnull JoinedAnimal entity) {
                log.add("beforeDelete:" + entity.id());
            }
        });
        var animals = orm.entity(JoinedAnimal.class);
        Integer newId = animals.insertAndFetchId(new JoinedCat(null, "DelByIdCb", true));
        // deleteById does NOT fire entity callbacks (entity not loaded).
        animals.removeById(newId);
        // No callback should fire for deleteById.
        assertTrue(log.isEmpty(), "deleteById should not fire entity callbacks");
    }

    // Joined entity: select operations

    @Test
    public void testJoinedEntitySelectAll() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        List<JoinedAnimal> all = animals.select().getResultList();
        assertTrue(all.size() >= 3);
        // Should contain both cats and dogs.
        assertTrue(all.stream().anyMatch(a -> a instanceof JoinedCat));
        assertTrue(all.stream().anyMatch(a -> a instanceof JoinedDog));
    }

    // EntityRepositoryImpl: various edge cases

    @Test
    public void testExistsById() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertTrue(cities.existsById(1));
        assertFalse(cities.existsById(99999));
    }

    @Test
    public void testExistsByRef() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertTrue(cities.existsByRef(Ref.of(City.class, 1)));
        assertFalse(cities.existsByRef(Ref.of(City.class, 99999)));
    }
}
