package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;
import static st.orm.Operator.LIKE;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.model.polymorphic.Adoption;
import st.orm.core.model.polymorphic.Animal;
import st.orm.core.model.polymorphic.Animal_;
import st.orm.core.model.polymorphic.Cat;
import st.orm.core.model.polymorphic.CharDiscAnimal;
import st.orm.core.model.polymorphic.CharDiscCat;
import st.orm.core.model.polymorphic.CharDiscDog;
import st.orm.core.model.polymorphic.Comment;
import st.orm.core.model.polymorphic.Commentable;
import st.orm.core.model.polymorphic.Dog;
import st.orm.core.model.polymorphic.IntDiscAnimal;
import st.orm.core.model.polymorphic.IntDiscCat;
import st.orm.core.model.polymorphic.IntDiscDog;
import st.orm.core.model.polymorphic.JoinedAdoption;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.model.polymorphic.JoinedDog;
import st.orm.core.model.polymorphic.NodscAnimal;
import st.orm.core.model.polymorphic.NodscAnimal_;
import st.orm.core.model.polymorphic.NodscBird;
import st.orm.core.model.polymorphic.NodscCat;
import st.orm.core.model.polymorphic.NodscDog;
import st.orm.core.model.polymorphic.Photo;
import st.orm.core.model.polymorphic.Post;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests for polymorphic sealed type hierarchies.
 * Tests Single-Table, Polymorphic FK, and Joined Table inheritance strategies.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class PolymorphicIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Single-Table Inheritance Tests (Animal/Cat/Dog)

    @Test
    public void testSelectAllAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var result = animals.select().getResultList();
        assertEquals(4, result.size());
        // First two are Cats, last two are Dogs (based on test data).
        assertTrue(result.get(0) instanceof Cat);
        assertTrue(result.get(1) instanceof Cat);
        assertTrue(result.get(2) instanceof Dog);
        assertTrue(result.get(3) instanceof Dog);
        assertEquals("Whiskers", ((Cat) result.get(0)).name());
        assertTrue(((Cat) result.get(0)).indoor());
        assertEquals("Rex", ((Dog) result.get(2)).name());
        assertEquals(30, ((Dog) result.get(2)).weight());
    }

    @Test
    public void testInsertCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        long before = animals.count();
        animals.insert(new Cat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        // Verify the inserted cat is returned correctly.
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof Cat);
        assertEquals("Bella", ((Cat) last).name());
        assertTrue(((Cat) last).indoor());
    }

    @Test
    public void testInsertDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        long before = animals.count();
        animals.insert(new Dog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        // Verify the inserted dog is returned correctly.
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof Dog);
        assertEquals("Buddy", ((Dog) last).name());
        assertEquals(25, ((Dog) last).weight());
    }

    @Test
    public void testUpdateCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        // Select the first cat (Whiskers, indoor) and update it.
        var result = animals.select().getResultList();
        var whiskers = (Cat) result.get(0);
        assertEquals("Whiskers", whiskers.name());
        animals.update(new Cat(whiskers.id(), "Sir Whiskers", true));
        // Verify the update.
        var updated = animals.select().getResultList();
        assertEquals("Sir Whiskers", ((Cat) updated.get(0)).name());
    }

    @Test
    public void testRemoveAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        // Insert a new animal and then delete it (avoids FK constraint from adoption table).
        animals.insert(new Cat(null, "Temp", false));
        long before = animals.count();
        var result = animals.select().getResultList();
        var last = result.getLast();
        animals.remove(last);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testSelectAnimalCount() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        assertEquals(4, animals.count());
    }

    @Test
    public void testInsertAndFetchCatId() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var id = animals.insertAndFetchId(new Cat(null, "Mittens", true));
        assertNotNull(id);
        assertTrue(id > 0);
    }

    @Test
    public void testSelectAdoptionWithAnimalRef() {
        var orm = ORMTemplate.of(dataSource);
        var adoptions = orm.entity(Adoption.class);
        var result = adoptions.select().getResultList();
        assertEquals(2, result.size());
        // Adoption 1 references animal 1 (a Cat), Adoption 2 references animal 3 (a Dog).
        assertNotNull(result.get(0).animal());
        assertNotNull(result.get(1).animal());
    }

    // Polymorphic FK Tests (Post/Photo/Comment)
    // These use independent entity types, so they work with the existing ORM infrastructure.

    @Test
    public void testSelectPost() {
        var orm = ORMTemplate.of(dataSource);
        var posts = orm.entity(Post.class);
        var result = posts.select().getResultList();
        assertEquals(2, result.size());
        assertEquals("Hello World", result.get(0).title());
        assertEquals("Second Post", result.get(1).title());
    }

    @Test
    public void testSelectPhoto() {
        var orm = ORMTemplate.of(dataSource);
        var photos = orm.entity(Photo.class);
        var result = photos.select().getResultList();
        assertEquals(2, result.size());
        assertEquals("photo1.jpg", result.get(0).url());
    }

    @Test
    public void testInsertPost() {
        var orm = ORMTemplate.of(dataSource);
        var posts = orm.entity(Post.class);
        long before = posts.count();
        posts.insert(new Post(null, "New Post"));
        assertEquals(before + 1, posts.count());
    }

    @Test
    public void testInsertPhoto() {
        var orm = ORMTemplate.of(dataSource);
        var photos = orm.entity(Photo.class);
        long before = photos.count();
        photos.insert(new Photo(null, "new_photo.png"));
        assertEquals(before + 1, photos.count());
    }

    // Joined Table Inheritance Tests (JoinedAnimal/JoinedCat/JoinedDog)

    @Test
    public void testSelectAllJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var result = animals.select().getResultList();
        assertEquals(3, result.size());
        // First two are JoinedCat, last one is JoinedDog (based on test data).
        assertTrue(result.get(0) instanceof JoinedCat);
        assertTrue(result.get(1) instanceof JoinedCat);
        assertTrue(result.get(2) instanceof JoinedDog);
        assertEquals("Whiskers", ((JoinedCat) result.get(0)).name());
        assertTrue(((JoinedCat) result.get(0)).indoor());
        assertEquals("Luna", ((JoinedCat) result.get(1)).name());
        assertFalse(((JoinedCat) result.get(1)).indoor());
        assertEquals("Rex", ((JoinedDog) result.get(2)).name());
        assertEquals(30, ((JoinedDog) result.get(2)).weight());
    }

    @Test
    public void testSelectJoinedAnimalCount() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        assertEquals(3, animals.count());
    }

    @Test
    public void testInsertJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long before = animals.count();
        animals.insert(new JoinedCat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        // Verify the inserted cat is returned correctly.
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof JoinedCat);
        assertEquals("Bella", ((JoinedCat) last).name());
        assertTrue(((JoinedCat) last).indoor());
    }

    @Test
    public void testInsertJoinedDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long before = animals.count();
        animals.insert(new JoinedDog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof JoinedDog);
        assertEquals("Buddy", ((JoinedDog) last).name());
        assertEquals(25, ((JoinedDog) last).weight());
    }

    @Test
    public void testInsertAndFetchIdJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var id = animals.insertAndFetchId(new JoinedCat(null, "Mittens", true));
        assertNotNull(id);
        assertTrue(id > 0);
    }

    @Test
    public void testUpdateJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Select the first cat (Whiskers, indoor) and update it.
        var result = animals.select().getResultList();
        var whiskers = (JoinedCat) result.get(0);
        assertEquals("Whiskers", whiskers.name());
        animals.update(new JoinedCat(whiskers.id(), "Sir Whiskers", true));
        // Verify the update.
        var updated = animals.select().getResultList();
        assertEquals("Sir Whiskers", ((JoinedCat) updated.get(0)).name());
    }

    @Test
    public void testUpdateJoinedCatToDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a cat, then update it to a dog (type change).
        var id = animals.insertAndFetchId(new JoinedCat(null, "Morpheus", true));
        animals.update(new JoinedDog(id, "Morpheus", 42));
        // Verify the entity is now a dog.
        var result = animals.select().getResultList();
        var morpheus = result.stream()
                .filter(a -> a.id().equals(id))
                .findFirst().orElseThrow();
        assertTrue(morpheus instanceof JoinedDog);
        assertEquals("Morpheus", ((JoinedDog) morpheus).name());
        assertEquals(42, ((JoinedDog) morpheus).weight());
    }

    @Test
    public void testUpdateJoinedDogToCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a dog, then update it to a cat (type change).
        var id = animals.insertAndFetchId(new JoinedDog(null, "Shifter", 20));
        animals.update(new JoinedCat(id, "Shifter", false));
        // Verify the entity is now a cat.
        var result = animals.select().getResultList();
        var shifter = result.stream()
                .filter(a -> a.id().equals(id))
                .findFirst().orElseThrow();
        assertTrue(shifter instanceof JoinedCat);
        assertEquals("Shifter", ((JoinedCat) shifter).name());
        assertFalse(((JoinedCat) shifter).indoor());
    }

    @Test
    public void testRemoveJoinedAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a new animal and then delete it (avoids FK constraint from joined_adoption table).
        animals.insert(new JoinedCat(null, "Temp", false));
        long before = animals.count();
        var result = animals.select().getResultList();
        var last = result.getLast();
        animals.remove(last);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testRemoveByIdJoinedAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a new animal and then delete by ID.
        var id = animals.insertAndFetchId(new JoinedDog(null, "TempDog", 10));
        long before = animals.count();
        animals.removeById(id);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testSelectJoinedAdoptionWithAnimalRef() {
        var orm = ORMTemplate.of(dataSource);
        var adoptions = orm.entity(JoinedAdoption.class);
        var result = adoptions.select().getResultList();
        assertEquals(2, result.size());
        // Adoption 1 references joined_animal 1 (a JoinedCat), Adoption 2 references joined_animal 3 (a JoinedDog).
        assertNotNull(result.get(0).animal());
        assertNotNull(result.get(1).animal());
    }

    // Joined Table Inheritance without @Discriminator (NodscAnimal)

    @Test
    public void testSelectAllNodscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().getResultList();
        assertEquals(4, result.size());
        // First two are NodscCat, third is NodscDog, fourth is NodscBird.
        assertTrue(result.get(0) instanceof NodscCat);
        assertTrue(result.get(1) instanceof NodscCat);
        assertTrue(result.get(2) instanceof NodscDog);
        assertTrue(result.get(3) instanceof NodscBird);
        assertEquals("Whiskers", ((NodscCat) result.get(0)).name());
        assertTrue(((NodscCat) result.get(0)).indoor());
        assertEquals("Rex", ((NodscDog) result.get(2)).name());
        assertEquals(30, ((NodscDog) result.get(2)).weight());
        assertEquals("Tweety", ((NodscBird) result.get(3)).name());
    }

    @Test
    public void testNodscAnimalCount() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        assertEquals(4, animals.count());
    }

    @Test
    public void testInsertNodscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        long before = animals.count();
        animals.insert(new NodscCat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof NodscCat);
        assertEquals("Bella", ((NodscCat) last).name());
        assertTrue(((NodscCat) last).indoor());
    }

    @Test
    public void testInsertNodscDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        long before = animals.count();
        animals.insert(new NodscDog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof NodscDog);
        assertEquals("Buddy", ((NodscDog) last).name());
        assertEquals(25, ((NodscDog) last).weight());
    }

    @Test
    public void testInsertNodscBird() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        long before = animals.count();
        animals.insert(new NodscBird(null, "Polly"));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof NodscBird);
        assertEquals("Polly", ((NodscBird) last).name());
    }

    @Test
    public void testUpdateNodscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().getResultList();
        var whiskers = (NodscCat) result.get(0);
        assertEquals("Whiskers", whiskers.name());
        animals.update(new NodscCat(whiskers.id(), "Sir Whiskers", true));
        var updated = animals.select().getResultList();
        assertEquals("Sir Whiskers", ((NodscCat) updated.get(0)).name());
    }

    @Test
    public void testUpdateNodscBird() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().getResultList();
        var tweety = (NodscBird) result.get(3);
        assertEquals("Tweety", tweety.name());
        // Update the bird (PK-only extension table, no extension fields).
        // Only the base table name field should be updated.
        animals.update(new NodscBird(tweety.id(), "Tweety Bird"));
        var updated = animals.select().getResultList();
        assertEquals("Tweety Bird", ((NodscBird) updated.get(3)).name());
    }

    @Test
    public void testUpdateNodscCatToDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert a cat, then update it to a dog (type change, both have extension fields).
        var id = animals.insertAndFetchId(new NodscCat(null, "Morpheus", true));
        animals.update(new NodscDog(id, "Morpheus", 42));
        // Verify the entity is now a dog.
        var result = animals.select().getResultList();
        var morpheus = result.stream()
                .filter(a -> a.id().equals(id))
                .findFirst().orElseThrow();
        assertTrue(morpheus instanceof NodscDog);
        assertEquals("Morpheus", ((NodscDog) morpheus).name());
        assertEquals(42, ((NodscDog) morpheus).weight());
    }

    @Test
    public void testUpdateNodscCatToBird() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert a cat, then update it to a bird (extension fields -> PK-only).
        var id = animals.insertAndFetchId(new NodscCat(null, "Shifter", true));
        animals.update(new NodscBird(id, "Shifter"));
        // Verify the entity is now a bird.
        var result = animals.select().getResultList();
        var shifter = result.stream()
                .filter(a -> a.id().equals(id))
                .findFirst().orElseThrow();
        assertTrue(shifter instanceof NodscBird);
        assertEquals("Shifter", ((NodscBird) shifter).name());
    }

    @Test
    public void testUpdateNodscBirdToCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert a bird, then update it to a cat (PK-only -> extension fields).
        var id = animals.insertAndFetchId(new NodscBird(null, "Transformer"));
        animals.update(new NodscCat(id, "Transformer", false));
        // Verify the entity is now a cat.
        var result = animals.select().getResultList();
        var transformer = result.stream()
                .filter(a -> a.id().equals(id))
                .findFirst().orElseThrow();
        assertTrue(transformer instanceof NodscCat);
        assertEquals("Transformer", ((NodscCat) transformer).name());
        assertFalse(((NodscCat) transformer).indoor());
    }

    @Test
    public void testRemoveNodscAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert a new animal and then delete it.
        animals.insert(new NodscCat(null, "Temp", false));
        long before = animals.count();
        var result = animals.select().getResultList();
        var last = result.getLast();
        animals.remove(last);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testRemoveNodscBird() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert and delete a bird (PK-only extension table).
        animals.insert(new NodscBird(null, "TempBird"));
        long before = animals.count();
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof NodscBird);
        animals.remove(last);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testRemoveByIdNodscAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var id = animals.insertAndFetchId(new NodscDog(null, "TempDog", 10));
        long before = animals.count();
        animals.removeById(id);
        assertEquals(before - 1, animals.count());
    }

    // Batch DML Tests for Joined Table Inheritance

    @Test
    public void testBatchInsertJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long before = animals.count();
        animals.insert(List.of(
                new JoinedCat(null, "Bella", true),
                new JoinedDog(null, "Max", 20),
                new JoinedCat(null, "Cleo", false)
        ));
        assertEquals(before + 3, animals.count());
        var result = animals.select().getResultList();
        // Verify the last three are the inserted ones.
        var bella = result.get(result.size() - 3);
        var max = result.get(result.size() - 2);
        var cleo = result.get(result.size() - 1);
        assertTrue(bella instanceof JoinedCat);
        assertEquals("Bella", ((JoinedCat) bella).name());
        assertTrue(((JoinedCat) bella).indoor());
        assertTrue(max instanceof JoinedDog);
        assertEquals("Max", ((JoinedDog) max).name());
        assertEquals(20, ((JoinedDog) max).weight());
        assertTrue(cleo instanceof JoinedCat);
        assertEquals("Cleo", ((JoinedCat) cleo).name());
        assertFalse(((JoinedCat) cleo).indoor());
    }

    @Test
    public void testBatchInsertAndFetchIdsJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var ids = animals.insertAndFetchIds(List.of(
                new JoinedCat(null, "Sid", true),
                new JoinedDog(null, "Bud", 15)
        ));
        assertEquals(2, ids.size());
        ids.forEach(id -> {
            assertNotNull(id);
            assertTrue(id > 0);
        });
    }

    @Test
    public void testBatchUpdateJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert entities to update.
        animals.insert(List.of(
                new JoinedCat(null, "UpdCat", true),
                new JoinedDog(null, "UpdDog", 10)
        ));
        var result = animals.select().getResultList();
        var cat = (JoinedCat) result.get(result.size() - 2);
        var dog = (JoinedDog) result.get(result.size() - 1);
        // Batch update.
        animals.update(List.of(
                new JoinedCat(cat.id(), "UpdatedCat", false),
                new JoinedDog(dog.id(), "UpdatedDog", 99)
        ));
        var updated = animals.select().getResultList();
        var updCat = (JoinedCat) updated.get(updated.size() - 2);
        var updDog = (JoinedDog) updated.get(updated.size() - 1);
        assertEquals("UpdatedCat", updCat.name());
        assertFalse(updCat.indoor());
        assertEquals("UpdatedDog", updDog.name());
        assertEquals(99, updDog.weight());
    }

    @Test
    public void testBatchUpdateJoinedAnimalsWithTypeChange() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a cat and a dog, then swap their types in a batch update.
        var catId = animals.insertAndFetchId(new JoinedCat(null, "SwapCat", true));
        var dogId = animals.insertAndFetchId(new JoinedDog(null, "SwapDog", 15));
        animals.update(List.of(
                new JoinedDog(catId, "SwapCat", 33),
                new JoinedCat(dogId, "SwapDog", false)
        ));
        var result = animals.select().getResultList();
        var formerCat = result.stream()
                .filter(a -> a.id().equals(catId))
                .findFirst().orElseThrow();
        var formerDog = result.stream()
                .filter(a -> a.id().equals(dogId))
                .findFirst().orElseThrow();
        assertTrue(formerCat instanceof JoinedDog);
        assertEquals("SwapCat", ((JoinedDog) formerCat).name());
        assertEquals(33, ((JoinedDog) formerCat).weight());
        assertTrue(formerDog instanceof JoinedCat);
        assertEquals("SwapDog", ((JoinedCat) formerDog).name());
        assertFalse(((JoinedCat) formerDog).indoor());
    }

    @Test
    public void testBatchRemoveJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert entities to delete.
        animals.insert(List.of(
                new JoinedCat(null, "DelCat", true),
                new JoinedDog(null, "DelDog", 5)
        ));
        long before = animals.count();
        var result = animals.select().getResultList();
        var cat = result.get(result.size() - 2);
        var dog = result.get(result.size() - 1);
        animals.remove(List.of(cat, dog));
        assertEquals(before - 2, animals.count());
    }

    @Test
    public void testBatchRemoveByRefJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert entities to delete by ref.
        var ids = animals.insertAndFetchIds(List.of(
                new JoinedCat(null, "RefCat", true),
                new JoinedDog(null, "RefDog", 7)
        ));
        long before = animals.count();
        // deleteByRef uses Ref without knowing the concrete type.
        List<Ref<JoinedAnimal>> refs = ids.stream()
                .map(id -> Ref.of(JoinedAnimal.class, id))
                .toList();
        animals.removeByRef(refs);
        assertEquals(before - 2, animals.count());
    }

    // Batch DML Tests for Joined Table Inheritance without @Discriminator

    @Test
    public void testBatchInsertNodscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        long before = animals.count();
        animals.insert(List.of(
                new NodscCat(null, "BatchCat", true),
                new NodscDog(null, "BatchDog", 22),
                new NodscBird(null, "BatchBird")
        ));
        assertEquals(before + 3, animals.count());
        var result = animals.select().getResultList();
        var cat = result.get(result.size() - 3);
        var dog = result.get(result.size() - 2);
        var bird = result.get(result.size() - 1);
        assertTrue(cat instanceof NodscCat);
        assertEquals("BatchCat", ((NodscCat) cat).name());
        assertTrue(dog instanceof NodscDog);
        assertEquals("BatchDog", ((NodscDog) dog).name());
        assertEquals(22, ((NodscDog) dog).weight());
        assertTrue(bird instanceof NodscBird);
        assertEquals("BatchBird", ((NodscBird) bird).name());
    }

    @Test
    public void testBatchUpdateNodscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert entities to update (mixed subtypes including PK-only extension table).
        animals.insert(List.of(
                new NodscCat(null, "UpdCat", true),
                new NodscBird(null, "UpdBird")
        ));
        var result = animals.select().getResultList();
        var cat = (NodscCat) result.get(result.size() - 2);
        var bird = (NodscBird) result.get(result.size() - 1);
        // Batch update.
        animals.update(List.of(
                new NodscCat(cat.id(), "UpdatedCat", false),
                new NodscBird(bird.id(), "UpdatedBird")
        ));
        var updated = animals.select().getResultList();
        var updCat = (NodscCat) updated.get(updated.size() - 2);
        var updBird = (NodscBird) updated.get(updated.size() - 1);
        assertEquals("UpdatedCat", updCat.name());
        assertFalse(updCat.indoor());
        assertEquals("UpdatedBird", updBird.name());
    }

    @Test
    public void testBatchUpdateNodscAnimalsWithTypeChange() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert a cat and a bird, then swap: cat -> bird (extension -> PK-only),
        // bird -> dog (PK-only -> extension).
        var catId = animals.insertAndFetchId(new NodscCat(null, "SwapCat", true));
        var birdId = animals.insertAndFetchId(new NodscBird(null, "SwapBird"));
        animals.update(List.of(
                new NodscBird(catId, "SwapCat"),
                new NodscDog(birdId, "SwapBird", 18)
        ));
        var result = animals.select().getResultList();
        var formerCat = result.stream()
                .filter(a -> a.id().equals(catId))
                .findFirst().orElseThrow();
        var formerBird = result.stream()
                .filter(a -> a.id().equals(birdId))
                .findFirst().orElseThrow();
        assertTrue(formerCat instanceof NodscBird);
        assertEquals("SwapCat", ((NodscBird) formerCat).name());
        assertTrue(formerBird instanceof NodscDog);
        assertEquals("SwapBird", ((NodscDog) formerBird).name());
        assertEquals(18, ((NodscDog) formerBird).weight());
    }

    @Test
    public void testBatchRemoveNodscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // Insert entities to delete (mixed subtypes).
        animals.insert(List.of(
                new NodscCat(null, "DelCat", false),
                new NodscDog(null, "DelDog", 3),
                new NodscBird(null, "DelBird")
        ));
        long before = animals.count();
        var result = animals.select().getResultList();
        var cat = result.get(result.size() - 3);
        var dog = result.get(result.size() - 2);
        var bird = result.get(result.size() - 1);
        animals.remove(List.of(cat, dog, bird));
        assertEquals(before - 3, animals.count());
    }

    // INTEGER Discriminator Type Tests (D1)

    @Test
    public void testSelectAllIntDiscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(IntDiscAnimal.class);
        var result = animals.select().getResultList();
        assertEquals(4, result.size());
        assertTrue(result.get(0) instanceof IntDiscCat);
        assertTrue(result.get(1) instanceof IntDiscCat);
        assertTrue(result.get(2) instanceof IntDiscDog);
        assertTrue(result.get(3) instanceof IntDiscDog);
        assertEquals("Whiskers", ((IntDiscCat) result.get(0)).name());
        assertTrue(((IntDiscCat) result.get(0)).indoor());
        assertEquals("Rex", ((IntDiscDog) result.get(2)).name());
        assertEquals(30, ((IntDiscDog) result.get(2)).weight());
    }

    @Test
    public void testInsertIntDiscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(IntDiscAnimal.class);
        long before = animals.count();
        animals.insert(new IntDiscCat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof IntDiscCat);
        assertEquals("Bella", ((IntDiscCat) last).name());
    }

    @Test
    public void testInsertIntDiscDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(IntDiscAnimal.class);
        long before = animals.count();
        animals.insert(new IntDiscDog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof IntDiscDog);
        assertEquals("Buddy", ((IntDiscDog) last).name());
        assertEquals(25, ((IntDiscDog) last).weight());
    }

    @Test
    public void testUpdateIntDiscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(IntDiscAnimal.class);
        var result = animals.select().getResultList();
        var whiskers = (IntDiscCat) result.get(0);
        animals.update(new IntDiscCat(whiskers.id(), "Sir Whiskers", true));
        var updated = animals.select().getResultList();
        assertEquals("Sir Whiskers", ((IntDiscCat) updated.get(0)).name());
    }

    @Test
    public void testRemoveIntDiscAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(IntDiscAnimal.class);
        animals.insert(new IntDiscCat(null, "Temp", false));
        long before = animals.count();
        var result = animals.select().getResultList();
        animals.remove(result.getLast());
        assertEquals(before - 1, animals.count());
    }

    // CHAR Discriminator Type Tests (D1)

    @Test
    public void testSelectAllCharDiscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(CharDiscAnimal.class);
        var result = animals.select().getResultList();
        assertEquals(4, result.size());
        assertTrue(result.get(0) instanceof CharDiscCat);
        assertTrue(result.get(1) instanceof CharDiscCat);
        assertTrue(result.get(2) instanceof CharDiscDog);
        assertTrue(result.get(3) instanceof CharDiscDog);
        assertEquals("Whiskers", ((CharDiscCat) result.get(0)).name());
        assertTrue(((CharDiscCat) result.get(0)).indoor());
        assertEquals("Rex", ((CharDiscDog) result.get(2)).name());
        assertEquals(30, ((CharDiscDog) result.get(2)).weight());
    }

    @Test
    public void testInsertCharDiscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(CharDiscAnimal.class);
        long before = animals.count();
        animals.insert(new CharDiscCat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof CharDiscCat);
        assertEquals("Bella", ((CharDiscCat) last).name());
    }

    @Test
    public void testInsertCharDiscDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(CharDiscAnimal.class);
        long before = animals.count();
        animals.insert(new CharDiscDog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var last = result.getLast();
        assertTrue(last instanceof CharDiscDog);
        assertEquals("Buddy", ((CharDiscDog) last).name());
    }

    @Test
    public void testUpdateCharDiscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(CharDiscAnimal.class);
        var result = animals.select().getResultList();
        var whiskers = (CharDiscCat) result.get(0);
        animals.update(new CharDiscCat(whiskers.id(), "Sir Whiskers", true));
        var updated = animals.select().getResultList();
        assertEquals("Sir Whiskers", ((CharDiscCat) updated.get(0)).name());
    }

    @Test
    public void testRemoveCharDiscAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(CharDiscAnimal.class);
        animals.insert(new CharDiscDog(null, "Temp", 5));
        long before = animals.count();
        var result = animals.select().getResultList();
        animals.remove(result.getLast());
        assertEquals(before - 1, animals.count());
    }

    // Comment Entity (Polymorphic FK) CRUD Tests (D2)

    @SuppressWarnings("unchecked")
    private static <T extends Commentable> Ref<Commentable> commentableRef(Class<T> type, Object id) {
        return (Ref<Commentable>) (Ref<?>) Ref.of(type, id);
    }

    @Test
    public void testSelectAllComments() {
        var orm = ORMTemplate.of(dataSource);
        var comments = orm.entity(Comment.class);
        var result = comments.select().getResultList();
        assertEquals(3, result.size());
        // Comment 1: "Nice post!" -> post 1.
        assertEquals("Nice post!", result.get(0).text());
        assertNotNull(result.get(0).target());
        assertEquals(1, result.get(0).target().id());
        assertTrue(result.get(0).target() instanceof Ref);
        // Comment 2: "Great photo!" -> photo 1.
        assertEquals("Great photo!", result.get(1).text());
        assertNotNull(result.get(1).target());
        assertEquals(1, result.get(1).target().id());
        // Comment 3: "Love it!" -> post 2.
        assertEquals("Love it!", result.get(2).text());
        assertNotNull(result.get(2).target());
        assertEquals(2, result.get(2).target().id());
    }

    @Test
    public void testInsertComment() {
        var orm = ORMTemplate.of(dataSource);
        var comments = orm.entity(Comment.class);
        long before = comments.count();
        comments.insert(new Comment(null, "New comment", commentableRef(Post.class, 1)));
        assertEquals(before + 1, comments.count());
        // Verify the inserted comment is returned correctly via SELECT.
        var result = comments.select().getResultList();
        var last = result.getLast();
        assertEquals("New comment", last.text());
        assertNotNull(last.target());
        assertEquals(1, last.target().id());
    }

    @Test
    public void testSelectCommentCount() {
        var orm = ORMTemplate.of(dataSource);
        var comments = orm.entity(Comment.class);
        assertEquals(3, comments.count());
    }

    @Test
    public void testUpdateComment() {
        var orm = ORMTemplate.of(dataSource);
        var comments = orm.entity(Comment.class);
        // Update the first comment (id=1) to target photo 2 instead of post 1.
        comments.update(new Comment(1, "Updated!", commentableRef(Photo.class, 2)));
        // Verify the update via SELECT.
        var result = comments.select().getResultList();
        var updated = result.stream().filter(c -> c.id() == 1).findFirst().orElseThrow();
        assertEquals("Updated!", updated.text());
        assertEquals(2, updated.target().id());
    }

    @Test
    public void testRemoveComment() {
        var orm = ORMTemplate.of(dataSource);
        var comments = orm.entity(Comment.class);
        var insertedId = comments.insertAndFetchId(new Comment(null, "To be deleted", commentableRef(Post.class, 1)));
        long before = comments.count();
        comments.removeById(insertedId);
        assertEquals(before - 1, comments.count());
    }

    @Test
    public void testFindCommentById() {
        var orm = ORMTemplate.of(dataSource);
        var comments = orm.entity(Comment.class);
        Optional<Comment> found = comments.findById(1);
        assertTrue(found.isPresent());
        assertEquals("Nice post!", found.get().text());
        assertNotNull(found.get().target());
        assertEquals(1, found.get().target().id());
    }

    // findById / getById Tests (D3)

    @Test
    public void testFindByIdSingleTable() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        Optional<Animal> found = animals.findById(1);
        assertTrue(found.isPresent());
        assertTrue(found.get() instanceof Cat);
        assertEquals("Whiskers", ((Cat) found.get()).name());
    }

    @Test
    public void testFindByIdJoinedTable() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        Optional<JoinedAnimal> found = animals.findById(1);
        assertTrue(found.isPresent());
        assertTrue(found.get() instanceof JoinedCat);
        assertEquals("Whiskers", ((JoinedCat) found.get()).name());
    }

    @Test
    public void testFindByIdNodsc() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        Optional<NodscAnimal> found = animals.findById(4);
        assertTrue(found.isPresent());
        assertTrue(found.get() instanceof NodscBird);
        assertEquals("Tweety", ((NodscBird) found.get()).name());
    }

    @Test
    public void testFindByIdNonExistent() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        Optional<Animal> found = animals.findById(999);
        assertTrue(found.isEmpty());
    }

    // STI Batch Operations (D4)

    @Test
    public void testBatchInsertSingleTableAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        long before = animals.count();
        animals.insert(List.of(
                new Cat(null, "BatchCat1", true),
                new Dog(null, "BatchDog1", 10),
                new Cat(null, "BatchCat2", false)
        ));
        assertEquals(before + 3, animals.count());
    }

    @Test
    public void testBatchUpdateSingleTableAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        animals.insert(List.of(
                new Cat(null, "UpdBatchCat", true),
                new Dog(null, "UpdBatchDog", 10)
        ));
        var result = animals.select().getResultList();
        var cat = (Cat) result.get(result.size() - 2);
        var dog = (Dog) result.get(result.size() - 1);
        animals.update(List.of(
                new Cat(cat.id(), "UpdatedBatchCat", false),
                new Dog(dog.id(), "UpdatedBatchDog", 99)
        ));
        var updated = animals.select().getResultList();
        var updatedCat = (Cat) updated.get(updated.size() - 2);
        var updatedDog = (Dog) updated.get(updated.size() - 1);
        assertEquals("UpdatedBatchCat", updatedCat.name());
        assertFalse(updatedCat.indoor());
        assertEquals("UpdatedBatchDog", updatedDog.name());
        assertEquals(99, updatedDog.weight());
    }

    @Test
    public void testBatchRemoveSingleTableAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        animals.insert(List.of(
                new Cat(null, "DelBatchCat", true),
                new Dog(null, "DelBatchDog", 5)
        ));
        long before = animals.count();
        var result = animals.select().getResultList();
        var cat = result.get(result.size() - 2);
        var dog = result.get(result.size() - 1);
        animals.remove(List.of(cat, dog));
        assertEquals(before - 2, animals.count());
    }

    // Where Clause Tests (D5)

    @Test
    public void testWhereClauseByIdOnPolymorphicEntity() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        // Filter by ID using findById.
        Optional<Animal> found = animals.findById(1);
        assertTrue(found.isPresent());
        assertTrue(found.get() instanceof Cat);
        assertEquals("Whiskers", ((Cat) found.get()).name());
    }

    @Test
    public void testSelectAnimalByNameUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var result = animals.select().where(Animal_.name, EQUALS, "Whiskers").getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(Cat.class, result.getFirst());
        assertEquals("Whiskers", result.getFirst().name());
    }

    @Test
    public void testSelectAnimalByIdUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var result = animals.select().where(Animal_.id, EQUALS, 3).getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(Dog.class, result.getFirst());
        assertEquals("Rex", result.getFirst().name());
    }

    @Test
    public void testSelectAnimalByNameLikeUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        // Both "Rex" and "Max" end with 'x'.
        var result = animals.select().where(Animal_.name, LIKE, "%x").getResultList();
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(a -> a instanceof Dog));
    }

    @Test
    public void testSelectAnimalByNameInUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var result = animals.select().where(Animal_.name, IN, List.of("Luna", "Max")).getResultList();
        assertEquals(2, result.size());
        assertInstanceOf(Cat.class, result.get(0));
        assertInstanceOf(Dog.class, result.get(1));
        assertEquals("Luna", result.get(0).name());
        assertEquals("Max", result.get(1).name());
    }

    @Test
    public void testCountAnimalByNameUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        assertEquals(1, animals.select().where(Animal_.name, EQUALS, "Rex").getResultCount());
    }

    // NodscAnimal Metamodel Tests (generated, joined without @Discriminator)

    @Test
    public void testSelectNodscAnimalByNameUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().where(NodscAnimal_.name, EQUALS, "Whiskers").getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(NodscCat.class, result.getFirst());
        assertEquals("Whiskers", result.getFirst().name());
    }

    @Test
    public void testSelectNodscAnimalByIdUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().where(NodscAnimal_.id, EQUALS, 4).getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(NodscBird.class, result.getFirst());
        assertEquals("Tweety", result.getFirst().name());
    }

    @Test
    public void testSelectNodscAnimalByNameLikeUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        // "Whiskers", "Rex", and "Tweety" all contain 'e'.
        var result = animals.select().where(NodscAnimal_.name, LIKE, "%e%").getResultList();
        assertEquals(3, result.size());
        assertInstanceOf(NodscCat.class, result.get(0));
        assertInstanceOf(NodscDog.class, result.get(1));
        assertInstanceOf(NodscBird.class, result.get(2));
    }

    @Test
    public void testSelectNodscAnimalByNameInUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().where(NodscAnimal_.name, IN, List.of("Luna", "Tweety")).getResultList();
        assertEquals(2, result.size());
        assertInstanceOf(NodscCat.class, result.get(0));
        assertInstanceOf(NodscBird.class, result.get(1));
    }

    @Test
    public void testCountNodscAnimalByNameUsingMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        assertEquals(1, animals.select().where(NodscAnimal_.name, EQUALS, "Rex").getResultCount());
    }

    // NodscAnimal MetamodelFactory Tests (non-generated, joined without @Discriminator)

    @Test
    public void testSelectNodscAnimalByNameUsingMetamodelFactory() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        Metamodel<NodscAnimal, String> name = Metamodel.of(NodscAnimal.class, "name");
        var result = animals.select().where(name, EQUALS, "Whiskers").getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(NodscCat.class, result.getFirst());
        assertEquals("Whiskers", result.getFirst().name());
    }

    @Test
    public void testSelectNodscAnimalByIdUsingMetamodelFactory() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        Metamodel<NodscAnimal, Integer> id = Metamodel.of(NodscAnimal.class, "id");
        var result = animals.select().where(id, EQUALS, 4).getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(NodscBird.class, result.getFirst());
        assertEquals("Tweety", result.getFirst().name());
    }

    @Test
    public void testSelectNodscAnimalByNameLikeUsingMetamodelFactory() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        Metamodel<NodscAnimal, String> name = Metamodel.of(NodscAnimal.class, "name");
        // "Whiskers", "Rex", and "Tweety" all contain 'e'.
        var result = animals.select().where(name, LIKE, "%e%").getResultList();
        assertEquals(3, result.size());
        assertInstanceOf(NodscCat.class, result.get(0));
        assertInstanceOf(NodscDog.class, result.get(1));
        assertInstanceOf(NodscBird.class, result.get(2));
    }

    @Test
    public void testCountNodscAnimalByNameUsingMetamodelFactory() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        Metamodel<NodscAnimal, String> name = Metamodel.of(NodscAnimal.class, "name");
        assertEquals(1, animals.select().where(name, EQUALS, "Rex").getResultCount());
    }

    // Animal MetamodelFactory Tests (non-generated, single table with @Discriminator)

    @Test
    public void testSelectAnimalByNameUsingMetamodelFactory() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        Metamodel<Animal, String> name = Metamodel.of(Animal.class, "name");
        var result = animals.select().where(name, EQUALS, "Whiskers").getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(Cat.class, result.getFirst());
        assertEquals("Whiskers", result.getFirst().name());
    }

    @Test
    public void testSelectAnimalByIdUsingMetamodelFactory() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        Metamodel<Animal, Integer> id = Metamodel.of(Animal.class, "id");
        var result = animals.select().where(id, EQUALS, 3).getResultList();
        assertEquals(1, result.size());
        assertInstanceOf(Dog.class, result.getFirst());
        assertEquals("Rex", result.getFirst().name());
    }

    // Type Change Tests for STI (D6)

    @Test
    public void testTypeChangeSingleTable() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        // Insert a cat, then update it to a dog (discriminator column update + field swap).
        animals.insert(new Cat(null, "Morph", true));
        var result = animals.select().getResultList();
        var cat = (Cat) result.getLast();
        animals.update(new Dog(cat.id(), "Morph", 42));
        var updated = animals.select().getResultList();
        var morphed = updated.stream()
                .filter(a -> a.id().equals(cat.id()))
                .findFirst().orElseThrow();
        assertTrue(morphed instanceof Dog);
        assertEquals("Morph", ((Dog) morphed).name());
        assertEquals(42, ((Dog) morphed).weight());
    }

    // insertAndFetch / updateAndFetch for JTI (D7)

    @Test
    public void testInsertAndFetchJoinedCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var fetched = animals.insertAndFetch(new JoinedCat(null, "FetchMe", true));
        assertNotNull(fetched);
        assertTrue(fetched instanceof JoinedCat);
        assertEquals("FetchMe", ((JoinedCat) fetched).name());
        assertTrue(((JoinedCat) fetched).indoor());
        assertNotNull(fetched.id());
    }

    @Test
    public void testUpdateAndFetchJoinedDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var id = animals.insertAndFetchId(new JoinedDog(null, "BeforeUpdate", 10));
        var fetched = animals.updateAndFetch(new JoinedDog(id, "AfterUpdate", 99));
        assertNotNull(fetched);
        assertTrue(fetched instanceof JoinedDog);
        assertEquals("AfterUpdate", ((JoinedDog) fetched).name());
        assertEquals(99, ((JoinedDog) fetched).weight());
    }

    // Ref Concrete Type Verification (D8)

    @Test
    public void testAdoptionRefConcreteType() {
        var orm = ORMTemplate.of(dataSource);
        var adoptions = orm.entity(Adoption.class);
        var result = adoptions.select().getResultList();
        // Adoption 1 references animal 1 (a Cat).
        Ref<Animal> catRef = result.get(0).animal();
        assertNotNull(catRef);
        assertEquals(1, catRef.id());
        // Adoption 2 references animal 3 (a Dog).
        Ref<Animal> dogRef = result.get(1).animal();
        assertNotNull(dogRef);
        assertEquals(3, dogRef.id());
    }

    // Invalid Discriminator Value Test (D9)

    @Test
    public void testInvalidDiscriminatorValue() throws Exception {
        // Insert a row with an unknown discriminator value via raw JDBC.
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO animal (dtype, name) VALUES ('Fish', 'Nemo')");
        }
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        assertThrows(PersistenceException.class, () -> {
            animals.select().getResultList();
        });
        // Clean up: remove the invalid row to avoid polluting other tests.
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM animal WHERE dtype = 'Fish'");
        }
    }

    // Missing Extension Row Test (B2)

    @Test
    public void testMissingExtensionRowThrowsError() throws Exception {
        // Insert a base table row with discriminator 'JoinedCat' but NO corresponding joined_cat row.
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Ghost')");
        }
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var exception = assertThrows(PersistenceException.class, () -> {
            animals.select().getResultList();
        });
        assertTrue(exception.getMessage().contains("no matching extension row was found"),
                "Expected error about missing extension row, got: " + exception.getMessage());
        // Clean up.
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM joined_animal WHERE name = 'Ghost'");
        }
    }
}
