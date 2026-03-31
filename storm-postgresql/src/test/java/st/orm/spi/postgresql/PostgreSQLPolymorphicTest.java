package st.orm.spi.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Polymorphic.Strategy.JOINED;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.PK;
import st.orm.Polymorphic;
import st.orm.Ref;
import st.orm.core.template.ORMTemplate;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(showSql = false)
@Testcontainers
public class PostgreSQLPolymorphicTest {

    @SuppressWarnings("resource")
    @Container
    public static PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    // Joined Table Inheritance models
    @Discriminator @Polymorphic(JOINED) @DbTable("joined_animal")
    public sealed interface JoinedAnimal extends Entity<Integer> permits JoinedCat, JoinedDog {}

    @DbTable("joined_cat")
    public record JoinedCat(@PK Integer id, String name, boolean indoor) implements JoinedAnimal {}

    public record JoinedDog(@PK Integer id, String name, int weight) implements JoinedAnimal {}

    // No-Discriminator Joined Table Inheritance models
    @Polymorphic(JOINED) @DbTable("nodsc_animal")
    public sealed interface NodscAnimal extends Entity<Integer> permits NodscCat, NodscDog, NodscBird {}

    @DbTable("nodsc_cat")
    public record NodscCat(@PK Integer id, String name, boolean indoor) implements NodscAnimal {}

    @DbTable("nodsc_dog")
    public record NodscDog(@PK Integer id, String name, int weight) implements NodscAnimal {}

    @DbTable("nodsc_bird")
    public record NodscBird(@PK Integer id, String name) implements NodscAnimal {}

    // Single-Table Inheritance models
    @Discriminator @DbTable("animal")
    public sealed interface Animal extends Entity<Integer> permits Cat, Dog {}

    @Discriminator("Cat")
    public record Cat(@PK Integer id, String name, boolean indoor) implements Animal {}

    @Discriminator("Dog")
    public record Dog(@PK Integer id, String name, int weight) implements Animal {}

    // Single-Table Inheritance Tests

    @Test
    public void testSelectAllAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var result = animals.select().getResultList();
        assertEquals(4, result.size());
        assertEquals(2, result.stream().filter(a -> a instanceof Cat).count());
        assertEquals(2, result.stream().filter(a -> a instanceof Dog).count());
    }

    @Test
    public void testInsertAnimalCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        long before = animals.count();
        animals.insert(new Cat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var bella = result.stream()
                .filter(a -> a instanceof Cat c && c.name().equals("Bella"))
                .findFirst().orElseThrow();
        assertTrue(((Cat) bella).indoor());
    }

    @Test
    public void testInsertAnimalDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        long before = animals.count();
        animals.insert(new Dog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var buddy = result.stream()
                .filter(a -> a instanceof Dog d && d.name().equals("Buddy"))
                .findFirst().orElseThrow();
        assertEquals(25, ((Dog) buddy).weight());
    }

    @Test
    public void testUpdateAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        var result = animals.select().getResultList();
        var whiskers = (Cat) result.stream()
                .filter(a -> a instanceof Cat c && c.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        animals.update(new Cat(whiskers.id(), "Sir Whiskers", true));
        var updated = animals.select().getResultList();
        assertTrue(updated.stream().anyMatch(a -> a instanceof Cat c && c.name().equals("Sir Whiskers")));
    }

    @Test
    public void testDeleteAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        animals.insert(new Cat(null, "Temp", false));
        long before = animals.count();
        var result = animals.select().getResultList();
        var last = result.getLast();
        animals.remove(last);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testBatchInsertAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(Animal.class);
        long before = animals.count();
        animals.insert(List.of(
                new Cat(null, "BatchCat", true),
                new Dog(null, "BatchDog", 20),
                new Cat(null, "BatchCat2", false)
        ));
        assertEquals(before + 3, animals.count());
    }

    // Joined Table Inheritance Tests
    // Batch inserts for joined table inheritance group records by subtype, which may assign
    // IDs in a different order than the original list. Since result ordering without ORDER BY
    // is not guaranteed, these tests use stream-based lookups by name instead of positional
    // access.

    @Test
    public void testSelectAllJoinedAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var result = animals.select().getResultList();
        assertEquals(3, result.size());
        assertEquals(2, result.stream().filter(a -> a instanceof JoinedCat).count());
        assertEquals(1, result.stream().filter(a -> a instanceof JoinedDog).count());
        var whiskers = (JoinedCat) result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        assertTrue(whiskers.indoor());
        var luna = (JoinedCat) result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Luna"))
                .findFirst().orElseThrow();
        assertFalse(luna.indoor());
        var rex = (JoinedDog) result.stream()
                .filter(a -> a instanceof JoinedDog d && d.name().equals("Rex"))
                .findFirst().orElseThrow();
        assertEquals(30, rex.weight());
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
        var bella = result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Bella"))
                .findFirst().orElseThrow();
        assertTrue(((JoinedCat) bella).indoor());
    }

    @Test
    public void testInsertJoinedDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        long before = animals.count();
        animals.insert(new JoinedDog(null, "Buddy", 25));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var buddy = result.stream()
                .filter(a -> a instanceof JoinedDog d && d.name().equals("Buddy"))
                .findFirst().orElseThrow();
        assertEquals(25, ((JoinedDog) buddy).weight());
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
        var whiskers = (JoinedCat) result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        animals.update(new JoinedCat(whiskers.id(), "Sir Whiskers", true));
        // Verify the update.
        var updated = animals.select().getResultList();
        assertTrue(updated.stream().anyMatch(a -> a instanceof JoinedCat c && c.name().equals("Sir Whiskers")));
    }

    @Test
    public void testDeleteJoinedAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a new animal and then delete it.
        animals.insert(new JoinedCat(null, "Temp", false));
        long before = animals.count();
        var result = animals.select().getResultList();
        var last = result.getLast();
        animals.remove(last);
        assertEquals(before - 1, animals.count());
    }

    @Test
    public void testDeleteByIdJoinedAnimal() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        // Insert a new animal and then delete by ID.
        var id = animals.insertAndFetchId(new JoinedDog(null, "TempDog", 10));
        long before = animals.count();
        animals.removeById(id);
        assertEquals(before - 1, animals.count());
    }

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
        var bella = result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Bella"))
                .findFirst().orElseThrow();
        assertTrue(((JoinedCat) bella).indoor());
        var max = result.stream()
                .filter(a -> a instanceof JoinedDog d && d.name().equals("Max"))
                .findFirst().orElseThrow();
        assertEquals(20, ((JoinedDog) max).weight());
        var cleo = result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Cleo"))
                .findFirst().orElseThrow();
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
        var cat = (JoinedCat) result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("UpdCat"))
                .findFirst().orElseThrow();
        var dog = (JoinedDog) result.stream()
                .filter(a -> a instanceof JoinedDog d && d.name().equals("UpdDog"))
                .findFirst().orElseThrow();
        // Batch update.
        animals.update(List.of(
                new JoinedCat(cat.id(), "UpdatedCat", false),
                new JoinedDog(dog.id(), "UpdatedDog", 99)
        ));
        var updated = animals.select().getResultList();
        var updatedCat = (JoinedCat) updated.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("UpdatedCat"))
                .findFirst().orElseThrow();
        var updatedDog = (JoinedDog) updated.stream()
                .filter(a -> a instanceof JoinedDog d && d.name().equals("UpdatedDog"))
                .findFirst().orElseThrow();
        assertEquals("UpdatedCat", updatedCat.name());
        assertFalse(updatedCat.indoor());
        assertEquals("UpdatedDog", updatedDog.name());
        assertEquals(99, updatedDog.weight());
    }

    @Test
    public void testBatchDeleteJoinedAnimals() {
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
    public void testBatchDeleteByRefJoinedAnimals() {
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

    // No-Discriminator Joined Table Inheritance Tests

    @Test
    public void testSelectAllNodscAnimals() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().getResultList();
        assertEquals(4, result.size());
        assertEquals(2, result.stream().filter(a -> a instanceof NodscCat).count());
        assertEquals(1, result.stream().filter(a -> a instanceof NodscDog).count());
        assertEquals(1, result.stream().filter(a -> a instanceof NodscBird).count());
        var whiskers = (NodscCat) result.stream()
                .filter(a -> a instanceof NodscCat c && c.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        assertTrue(whiskers.indoor());
        var rex = (NodscDog) result.stream()
                .filter(a -> a instanceof NodscDog d && d.name().equals("Rex"))
                .findFirst().orElseThrow();
        assertEquals(30, rex.weight());
        result.stream()
                .filter(a -> a instanceof NodscBird b && b.name().equals("Tweety"))
                .findFirst().orElseThrow();
    }

    @Test
    public void testInsertNodscCat() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        long before = animals.count();
        animals.insert(new NodscCat(null, "Bella", true));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        var bella = result.stream()
                .filter(a -> a instanceof NodscCat c && c.name().equals("Bella"))
                .findFirst().orElseThrow();
        assertTrue(((NodscCat) bella).indoor());
    }

    @Test
    public void testInsertNodscBird() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        long before = animals.count();
        animals.insert(new NodscBird(null, "Polly"));
        assertEquals(before + 1, animals.count());
        var result = animals.select().getResultList();
        result.stream()
                .filter(a -> a instanceof NodscBird b && b.name().equals("Polly"))
                .findFirst().orElseThrow();
    }

    @Test
    public void testUpdateNodscBird() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().getResultList();
        var tweety = (NodscBird) result.stream()
                .filter(a -> a instanceof NodscBird b && b.name().equals("Tweety"))
                .findFirst().orElseThrow();
        // Update the bird (PK-only extension table, no extension fields).
        // Only the base table name field should be updated.
        animals.update(new NodscBird(tweety.id(), "Tweety Bird"));
        var updated = animals.select().getResultList();
        assertTrue(updated.stream().anyMatch(a -> a instanceof NodscBird b && b.name().equals("Tweety Bird")));
    }

    @Test
    public void testDeleteNodscAnimal() {
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
        result.stream()
                .filter(a -> a instanceof NodscCat c && c.name().equals("BatchCat"))
                .findFirst().orElseThrow();
        var dog = result.stream()
                .filter(a -> a instanceof NodscDog d && d.name().equals("BatchDog"))
                .findFirst().orElseThrow();
        assertEquals(22, ((NodscDog) dog).weight());
        result.stream()
                .filter(a -> a instanceof NodscBird b && b.name().equals("BatchBird"))
                .findFirst().orElseThrow();
    }

    @Test
    public void testBatchDeleteNodscAnimals() {
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

    // JTI Type Change Tests

    @Test
    public void testUpdateJoinedCatToDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(JoinedAnimal.class);
        var result = animals.select().getResultList();
        var whiskers = (JoinedCat) result.stream()
                .filter(a -> a instanceof JoinedCat c && c.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        // Convert cat to dog.
        animals.update(new JoinedDog(whiskers.id(), "Whiskers", 12));
        var updated = animals.select().getResultList();
        var converted = updated.stream()
                .filter(a -> a instanceof JoinedDog d && d.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        assertEquals(12, ((JoinedDog) converted).weight());
    }

    @Test
    public void testUpdateNodscCatToDog() {
        var orm = ORMTemplate.of(dataSource);
        var animals = orm.entity(NodscAnimal.class);
        var result = animals.select().getResultList();
        var whiskers = (NodscCat) result.stream()
                .filter(a -> a instanceof NodscCat c && c.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        // Convert cat to dog.
        animals.update(new NodscDog(whiskers.id(), "Whiskers", 12));
        var updated = animals.select().getResultList();
        var converted = updated.stream()
                .filter(a -> a instanceof NodscDog d && d.name().equals("Whiskers"))
                .findFirst().orElseThrow();
        assertEquals(12, ((NodscDog) converted).weight());
    }
}
