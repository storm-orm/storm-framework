package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Ref;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * Integration tests for EntityRepository batch operations, update operations,
 * and various query builder patterns.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class EntityRepositoryBatchIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // The seed data contains exactly 6 cities:
    // id=1: "Sun Paririe", id=2: "Madison", id=3: "McFarland",
    // id=4: "Windsor", id=5: "Monona", id=6: "Waunakee"
    private static final int SEED_CITY_COUNT = 6;

    // Batch insert

    @Test
    public void testBatchInsertIncreasesCityCount() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        long countBefore = cities.count();

        cities.insert(List.of(
                City.builder().name("BatchCity1").build(),
                City.builder().name("BatchCity2").build(),
                City.builder().name("BatchCity3").build()
        ));
        assertEquals(countBefore + 3, cities.count());
    }

    @Test
    public void testBatchInsertAndFetchIdsReturnsValidIds() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        List<Integer> ids = cities.insertAndFetchIds(List.of(
                City.builder().name("FetchId1").build(),
                City.builder().name("FetchId2").build()
        ));
        assertEquals(2, ids.size());
        // Verify each returned ID corresponds to the correct inserted city.
        assertEquals("FetchId1", cities.getById(ids.get(0)).name());
        assertEquals("FetchId2", cities.getById(ids.get(1)).name());
    }

    // Update

    @Test
    public void testUpdateEntityPersistsNewName() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var insertedId = cities.insertAndFetchId(City.builder().name("BeforeUpdate").build());
        cities.update(City.builder().id(insertedId).name("AfterUpdate").build());

        assertEquals("AfterUpdate", cities.getById(insertedId).name());
    }

    @Test
    public void testBatchUpdatePersistsAllChanges() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var id1 = cities.insertAndFetchId(City.builder().name("BatchUpdate1").build());
        var id2 = cities.insertAndFetchId(City.builder().name("BatchUpdate2").build());

        cities.update(List.of(
                City.builder().id(id1).name("Updated1").build(),
                City.builder().id(id2).name("Updated2").build()
        ));

        assertEquals("Updated1", cities.getById(id1).name());
        assertEquals("Updated2", cities.getById(id2).name());
    }

    // Delete

    @Test
    public void testRemoveEntityRemovesIt() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var insertedId = cities.insertAndFetchId(City.builder().name("ToDelete").build());
        long countBefore = cities.count();

        cities.remove(City.builder().id(insertedId).name("ToDelete").build());
        assertEquals(countBefore - 1, cities.count());
        // Verify the deleted city is actually gone.
        assertFalse(cities.findById(insertedId).isPresent());
    }

    @Test
    public void testRemoveByRefRemovesEntity() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var insertedId = cities.insertAndFetchId(City.builder().name("RefDelete").build());
        cities.removeByRef(Ref.of(City.class, insertedId));

        assertFalse(cities.findById(insertedId).isPresent());
    }

    @Test
    public void testBatchRemoveRemovesAllSpecified() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var id1 = cities.insertAndFetchId(City.builder().name("BatchDel1").build());
        var id2 = cities.insertAndFetchId(City.builder().name("BatchDel2").build());
        long countBefore = cities.count();

        cities.remove(List.of(
                City.builder().id(id1).name("BatchDel1").build(),
                City.builder().id(id2).name("BatchDel2").build()
        ));
        assertEquals(countBefore - 2, cities.count());
        assertFalse(cities.findById(id1).isPresent());
        assertFalse(cities.findById(id2).isPresent());
    }

    @Test
    public void testBatchRemoveByRefRemovesAllSpecified() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var id1 = cities.insertAndFetchId(City.builder().name("RefBatchDel1").build());
        var id2 = cities.insertAndFetchId(City.builder().name("RefBatchDel2").build());

        cities.removeByRef(List.of(
                Ref.of(City.class, id1),
                Ref.of(City.class, id2)
        ));
        assertFalse(cities.findById(id1).isPresent());
        assertFalse(cities.findById(id2).isPresent());
    }

    // Query builder: WHERE

    @Test
    public void testWhereEqualsReturnsOnlyMatchingCity() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var result = cities.select()
                .where(City_.name, EQUALS, "Madison")
                .getResultList();
        assertEquals(1, result.size());
        assertEquals("Madison", result.get(0).name());
    }

    @Test
    public void testWhereInReturnsOnlyRequestedCities() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var result = cities.select()
                .where(City_.name, IN, List.of("Madison", "Monona"))
                .getResultList();
        assertEquals(2, result.size());
        Set<String> names = result.stream().map(City::name).collect(Collectors.toSet());
        assertEquals(Set.of("Madison", "Monona"), names);
    }

    // Count and existence

    @Test
    public void testCountMatchesSeedData() {
        var orm = ORMTemplate.of(dataSource);
        assertEquals(SEED_CITY_COUNT, orm.entity(City.class).count());
    }

    @Test
    public void testExistsByIdReturnsTrueForSeedCity() {
        var orm = ORMTemplate.of(dataSource);
        // City id=2 is "Madison" from seed data.
        assertTrue(orm.entity(City.class).existsById(2));
    }

    @Test
    public void testExistsByIdReturnsFalseForAbsentId() {
        var orm = ORMTemplate.of(dataSource);
        assertFalse(orm.entity(City.class).existsById(99999));
    }

    // Get/Find by ID and Ref

    @Test
    public void testGetByIdReturnsCorrectCity() {
        var orm = ORMTemplate.of(dataSource);
        City city = orm.entity(City.class).getById(2);
        assertEquals(2, city.id());
        assertEquals("Madison", city.name());
    }

    @Test
    public void testFindByIdReturnsPresentForExistingCity() {
        var orm = ORMTemplate.of(dataSource);
        Optional<City> city = orm.entity(City.class).findById(2);
        assertTrue(city.isPresent());
        assertEquals("Madison", city.get().name());
    }

    @Test
    public void testFindByIdReturnsEmptyForAbsentId() {
        var orm = ORMTemplate.of(dataSource);
        assertFalse(orm.entity(City.class).findById(99999).isPresent());
    }

    @Test
    public void testGetByRefReturnsCorrectCity() {
        var orm = ORMTemplate.of(dataSource);
        City city = orm.entity(City.class).getByRef(Ref.of(City.class, 3));
        assertEquals(3, city.id());
        assertEquals("McFarland", city.name());
    }

    // InsertAndFetch

    @Test
    public void testInsertAndFetchReturnsCompleteEntity() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        City fetched = cities.insertAndFetch(City.builder().name("InsertAndFetch").build());
        assertNotNull(fetched.id());
        assertTrue(fetched.id() > SEED_CITY_COUNT);
        assertEquals("InsertAndFetch", fetched.name());
    }

    // UpdateAndFetch

    @Test
    public void testUpdateAndFetchReturnsUpdatedEntity() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);

        var insertedId = cities.insertAndFetchId(City.builder().name("BeforeUAF").build());
        City updated = cities.updateAndFetch(
                City.builder().id(insertedId).name("AfterUAF").build());
        assertEquals("AfterUAF", updated.name());
        assertEquals(insertedId, updated.id());
    }

    // Query stream

    @Test
    public void testStreamCountMatchesRepositoryCount() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        try (var stream = cities.select().getResultStream()) {
            long streamCount = stream.count();
            assertEquals(cities.count(), streamCount);
        }
    }

    // OrderBy

    @Test
    public void testOrderByAscendingReturnsSortedResults() {
        var orm = ORMTemplate.of(dataSource);
        var result = orm.entity(City.class).select()
                .orderBy(City_.name)
                .getResultList();
        assertEquals(SEED_CITY_COUNT, result.size());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).name().compareTo(result.get(i).name()) <= 0,
                    "Expected '%s' <= '%s'".formatted(result.get(i - 1).name(), result.get(i).name()));
        }
    }

    @Test
    public void testOrderByDescendingReturnsSortedResults() {
        var orm = ORMTemplate.of(dataSource);
        var result = orm.entity(City.class).select()
                .orderByDescending(City_.name)
                .getResultList();
        assertEquals(SEED_CITY_COUNT, result.size());
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).name().compareTo(result.get(i).name()) >= 0,
                    "Expected '%s' >= '%s'".formatted(result.get(i - 1).name(), result.get(i).name()));
        }
    }

    // Distinct

    @Test
    public void testDistinctOnNameProjectionCollapsesDuplicates() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Insert two cities with the same name to create duplicate names.
        cities.insert(City.builder().name("DuplicateName").build());
        cities.insert(City.builder().name("DuplicateName").build());

        // Select all names (including duplicates).
        List<String> allNames = cities.select(String.class, TemplateString.of("name"))
                .getResultList();
        // Select distinct names only.
        List<String> distinctNames = cities.select(String.class, TemplateString.of("name"))
                .distinct()
                .getResultList();

        // There should be 2 more total names than distinct names (the 2 duplicate "DuplicateName" entries
        // collapse to 1 in the distinct query).
        assertEquals(allNames.size() - 1, distinctNames.size(),
                "Distinct should collapse the duplicate 'DuplicateName' entries into one");
        // Verify "DuplicateName" appears exactly once in distinct results.
        assertEquals(1, distinctNames.stream().filter("DuplicateName"::equals).count());
    }

    // Limit and Offset

    @Test
    public void testLimitRestrictsResultCount() {
        var orm = ORMTemplate.of(dataSource);
        var result = orm.entity(City.class).select()
                .limit(2)
                .getResultList();
        assertEquals(2, result.size());
    }

    @Test
    public void testOffsetSkipsRows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var allCities = cities.select().orderBy(City_.id).getResultList();

        var offsetResult = cities.select()
                .orderBy(City_.id)
                .offset(2)
                .limit(2)
                .getResultList();
        assertEquals(2, offsetResult.size());
        // The offset results should match the 3rd and 4th cities from the full list.
        assertEquals(allCities.get(2).id(), offsetResult.get(0).id());
        assertEquals(allCities.get(3).id(), offsetResult.get(1).id());
    }
}
