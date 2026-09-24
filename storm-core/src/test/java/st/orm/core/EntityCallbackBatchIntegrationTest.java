package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.Ref;
import st.orm.core.model.Address;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.PetType;
import st.orm.core.template.ORMTemplate;

/**
 * Covers the list form of the "after" callbacks: a batch write reaches a callback as one list per batch, a
 * single-entity write as a list of one, and a {@code *AndFetch} call or a write set as one list per type once the rows
 * have been read back.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class EntityCallbackBatchIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /** Records every list the insert callback receives. */
    private ORMTemplate ormRecordingInserts(List<List<City>> batches) {
        return ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterInsert(List<City> entities) {
                batches.add(List.copyOf(entities));
            }
        });
    }

    private static City city(String name) {
        return City.builder().name(name).build();
    }

    @Test
    public void testBatchInsertIsOneList() {
        List<List<City>> batches = new ArrayList<>();
        ormRecordingInserts(batches).entity(City.class).insert(List.of(city("Batch one"), city("Batch two")));
        assertEquals(1, batches.size());
        assertEquals(List.of("Batch one", "Batch two"), batches.getFirst().stream().map(City::name).toList());
    }

    @Test
    public void testSingleInsertIsListOfOne() {
        List<List<City>> batches = new ArrayList<>();
        ormRecordingInserts(batches).entity(City.class).insert(city("Single"));
        assertEquals(1, batches.size());
        assertEquals(1, batches.getFirst().size());
    }

    @Test
    public void testBatchInsertAndFetchIsOneListOfFetchedRows() {
        List<List<City>> batches = new ArrayList<>();
        var inserted = ormRecordingInserts(batches).entity(City.class)
                .insertAndFetch(List.of(city("Fetched one"), city("Fetched two")));
        assertEquals(1, batches.size());
        assertEquals(inserted.stream().map(City::id).sorted().toList(),
                batches.getFirst().stream().map(City::id).sorted().toList());
    }

    @Test
    public void testStreamInsertIsOneListPerBatch() {
        List<List<City>> batches = new ArrayList<>();
        ormRecordingInserts(batches).entity(City.class).insert(
                Stream.of(city("Stream 1"), city("Stream 2"), city("Stream 3"), city("Stream 4"), city("Stream 5")),
                2);
        assertEquals(List.of(2, 2, 1), batches.stream().map(List::size).toList());
    }

    @Test
    public void testSingleEntityFormStillSeesEveryEntity() {
        List<City> observed = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterInsert(City entity) {
                observed.add(entity);
            }
        });
        orm.entity(City.class).insert(List.of(city("Each one"), city("Each two"), city("Each three")));
        assertEquals(List.of("Each one", "Each two", "Each three"), observed.stream().map(City::name).toList());
    }

    @Test
    public void testBatchUpdateIsOneList() {
        List<List<City>> batches = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterUpdate(List<City> entities) {
                batches.add(List.copyOf(entities));
            }
        });
        var cities = orm.entity(City.class);
        var inserted = cities.insertAndFetch(List.of(city("Update one"), city("Update two")));
        cities.update(inserted.stream().map(c -> c.toBuilder().name(c.name() + " updated").build()).toList());
        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().stream().allMatch(c -> c.name().endsWith(" updated")));
    }

    @Test
    public void testBatchRemoveIsOneList() {
        List<List<City>> batches = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterRemove(List<City> entities) {
                batches.add(List.copyOf(entities));
            }
        });
        var cities = orm.entity(City.class);
        var inserted = cities.insertAndFetch(List.of(city("Remove one"), city("Remove two")));
        cities.remove(inserted);
        assertEquals(1, batches.size());
        assertEquals(2, batches.getFirst().size());
    }

    @Test
    public void testWriteSetIsOneListPerType() {
        List<List<Owner>> batches = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Owner>() {
            @Override
            public void afterInsert(List<Owner> entities) {
                batches.add(List.copyOf(entities));
            }
        });
        var pets = List.of(newPet("First", newOwner("First")), newPet("Second", newOwner("Second")));
        // The owners join by discovery; each owner row is observed once, both in one list, after the rows are read.
        orm.writeSet().insertAndFetch(pets);
        assertEquals(1, batches.size());
        assertEquals(2, batches.getFirst().size());
        assertTrue(batches.getFirst().stream().allMatch(owner -> owner.id() != null));
    }

    @Test
    public void testEachCallbackReceivesTheWholeListBeforeTheNext() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource)
                .withEntityCallback(new EntityCallback<City>() {
                    @Override
                    public void afterInsert(City entity) {
                        log.add("first:" + entity.name());
                    }
                })
                .withEntityCallback(new EntityCallback<City>() {
                    @Override
                    public void afterInsert(City entity) {
                        log.add("second:" + entity.name());
                    }
                });
        orm.entity(City.class).insert(List.of(city("A"), city("B")));
        assertEquals(List.of("first:A", "first:B", "second:A", "second:B"), log);
    }

    @Test
    public void testDatabaseWorkInsideListCallbackFiresNoCallbacks() {
        List<List<City>> batches = new ArrayList<>();
        ORMTemplate[] recording = new ORMTemplate[1];
        recording[0] = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterInsert(List<City> entities) {
                batches.add(List.copyOf(entities));
                // One statement for the whole batch, through the same template, which must not re-enter this callback.
                recording[0].entity(City.class).insert(entities.stream().map(c -> city(c.name() + " copy")).toList());
            }
        });
        recording[0].entity(City.class).insert(List.of(city("Guarded one"), city("Guarded two")));
        assertEquals(1, batches.size());
        assertNotNull(recording[0].entity(City.class).select().getResultList().stream()
                .filter(c -> c.name().equals("Guarded two copy")).findFirst().orElse(null));
    }

    private Owner newOwner(String firstName) {
        return Owner.builder()
                .firstName(firstName)
                .lastName("Batch")
                .address(Address.builder()
                        .address("110 W. Liberty St.")
                        .city(City.builder().id(1).name("Sun Paririe").build())
                        .build())
                .telephone("6085551023")
                .build();
    }

    private Pet newPet(String name, Owner owner) {
        return Pet.builder()
                .name(name)
                .birthDate(LocalDate.of(2023, 5, 5))
                .type(Ref.of(PetType.class, 1))
                .owner(owner)
                .build();
    }
}
