package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.model.polymorphic.JoinedDog;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests for CRUD operations on joined table inheritance entities.
 * These tests exercise the JoinedEntityHelper methods: insertJoined, updateJoined,
 * deleteJoined, and their batch variants, which have significant uncovered branches.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class JoinedEntityCrudIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Insert JoinedCat

    @Test
    public void testInsertJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long countBefore = animals.count();
        animals.insert(new JoinedCat(null, "NewJoinedCat", true));
        assertEquals(countBefore + 1, animals.count());
        // Verify the last inserted record is a JoinedCat.
        var result = animals.select().getResultList();
        JoinedAnimal lastAnimal = result.getLast();
        assertInstanceOf(JoinedCat.class, lastAnimal);
        assertEquals("NewJoinedCat", ((JoinedCat) lastAnimal).name());
        assertTrue(((JoinedCat) lastAnimal).indoor());
    }

    // Insert JoinedDog

    @Test
    public void testInsertJoinedDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long countBefore = animals.count();
        animals.insert(new JoinedDog(null, "NewJoinedDog", 40));
        assertEquals(countBefore + 1, animals.count());
        var result = animals.select().getResultList();
        JoinedAnimal lastAnimal = result.getLast();
        assertInstanceOf(JoinedDog.class, lastAnimal);
        assertEquals("NewJoinedDog", ((JoinedDog) lastAnimal).name());
        assertEquals(40, ((JoinedDog) lastAnimal).weight());
    }

    // InsertAndFetchId for joined entity

    @Test
    public void testInsertAndFetchIdJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer generatedId = animals.insertAndFetchId(new JoinedCat(null, "FetchIdCat", false));
        assertNotNull(generatedId);
        assertTrue(generatedId > 0);
        JoinedAnimal fetched = animals.getById(generatedId);
        assertInstanceOf(JoinedCat.class, fetched);
        assertEquals("FetchIdCat", ((JoinedCat) fetched).name());
    }

    // Batch insert joined entities

    @Test
    public void testBatchInsertJoinedEntities() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long countBefore = animals.count();
        animals.insert(List.of(
                new JoinedCat(null, "BatchCat1", true),
                new JoinedCat(null, "BatchCat2", false),
                new JoinedDog(null, "BatchDog1", 25)
        ));
        assertEquals(countBefore + 3, animals.count());
    }

    // Batch insertAndFetchIds for joined entities

    @Test
    public void testBatchInsertAndFetchIdsJoinedEntities() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        List<Integer> ids = animals.insertAndFetchIds(List.of(
                new JoinedCat(null, "BatchFetchCat1", true),
                new JoinedDog(null, "BatchFetchDog1", 20)
        ));
        assertEquals(2, ids.size());
        assertInstanceOf(JoinedCat.class, animals.getById(ids.get(0)));
        assertInstanceOf(JoinedDog.class, animals.getById(ids.get(1)));
    }

    // Update joined entity

    @Test
    public void testUpdateJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Whiskers is id=1, a JoinedCat
        JoinedAnimal animal = animals.getById(1);
        assertInstanceOf(JoinedCat.class, animal);
        JoinedCat updatedCat = new JoinedCat(1, "UpdatedWhiskers", false);
        animals.update(updatedCat);
        JoinedAnimal fetched = animals.getById(1);
        assertInstanceOf(JoinedCat.class, fetched);
        assertEquals("UpdatedWhiskers", ((JoinedCat) fetched).name());
        assertEquals(false, ((JoinedCat) fetched).indoor());
    }

    @Test
    public void testUpdateJoinedDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Rex is id=3, a JoinedDog
        JoinedAnimal animal = animals.getById(3);
        assertInstanceOf(JoinedDog.class, animal);
        JoinedDog updatedDog = new JoinedDog(3, "UpdatedRex", 35);
        animals.update(updatedDog);
        JoinedAnimal fetched = animals.getById(3);
        assertInstanceOf(JoinedDog.class, fetched);
        assertEquals("UpdatedRex", ((JoinedDog) fetched).name());
        assertEquals(35, ((JoinedDog) fetched).weight());
    }

    // Batch update joined entities

    @Test
    public void testBatchUpdateJoinedEntities() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        animals.update(List.of(
                new JoinedCat(1, "BatchUpdWhiskers", false),
                new JoinedCat(2, "BatchUpdLuna", true)
        ));
        JoinedAnimal whiskers = animals.getById(1);
        assertEquals("BatchUpdWhiskers", ((JoinedCat) whiskers).name());
        JoinedAnimal luna = animals.getById(2);
        assertEquals("BatchUpdLuna", ((JoinedCat) luna).name());
    }

    // Delete joined entity

    @Test
    public void testRemoveJoinedEntity() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a new entity to delete (avoid FK constraint issues with seed data).
        Integer insertedId = animals.insertAndFetchId(new JoinedCat(null, "ToDelete", true));
        long countBefore = animals.count();
        animals.remove(new JoinedCat(insertedId, "ToDelete", true));
        assertEquals(countBefore - 1, animals.count());
        assertTrue(animals.findById(insertedId).isEmpty());
    }

    // Batch delete joined entities

    @Test
    public void testBatchRemoveJoinedEntities() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer id1 = animals.insertAndFetchId(new JoinedCat(null, "BatchDel1", true));
        Integer id2 = animals.insertAndFetchId(new JoinedDog(null, "BatchDel2", 15));
        long countBefore = animals.count();
        animals.remove(List.of(
                new JoinedCat(id1, "BatchDel1", true),
                new JoinedDog(id2, "BatchDel2", 15)
        ));
        assertEquals(countBefore - 2, animals.count());
    }

    // Select all joined animals verifies correct type mapping

    @Test
    public void testSelectAllJoinedAnimalsTyped() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var result = animals.select().getResultList();
        // Seed data: 2 JoinedCats (id=1,2) and 1 JoinedDog (id=3)
        assertEquals(3, result.size());
        assertInstanceOf(JoinedCat.class, result.get(0));
        assertInstanceOf(JoinedCat.class, result.get(1));
        assertInstanceOf(JoinedDog.class, result.get(2));
    }

    // GetById for joined entity

    @Test
    public void testGetByIdJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        JoinedAnimal animal = animals.getById(1);
        assertInstanceOf(JoinedCat.class, animal);
        JoinedCat cat = (JoinedCat) animal;
        assertEquals("Whiskers", cat.name());
        assertTrue(cat.indoor());
    }

    @Test
    public void testGetByIdJoinedDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        JoinedAnimal animal = animals.getById(3);
        assertInstanceOf(JoinedDog.class, animal);
        JoinedDog dog = (JoinedDog) animal;
        assertEquals("Rex", dog.name());
        assertEquals(30, dog.weight());
    }

    // Delete joined entity by id (using delete(entity))

    @Test
    public void testRemoveJoinedDogByEntity() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Integer insertedId = animals.insertAndFetchId(new JoinedDog(null, "DeleteDog", 18));
        long countBefore = animals.count();
        JoinedAnimal toDelete = animals.getById(insertedId);
        animals.remove(toDelete);
        assertEquals(countBefore - 1, animals.count());
    }

    // Count after multiple inserts

    @Test
    public void testJoinedEntityCountAfterMultipleInserts() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long countBefore = animals.count();
        animals.insert(new JoinedCat(null, "Count1", true));
        animals.insert(new JoinedDog(null, "Count2", 5));
        assertEquals(countBefore + 2, animals.count());
    }
}
