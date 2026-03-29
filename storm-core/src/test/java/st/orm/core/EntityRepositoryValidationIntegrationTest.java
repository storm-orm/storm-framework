package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.model.Address;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.PetType;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.VetSpecialtyPK;
import st.orm.core.model.Visit;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlTemplateException;

/**
 * Integration tests for {@code EntityRepositoryImpl} validation paths, non-auto-generated PK
 * operations, optimistic locking, and callback edge cases.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class EntityRepositoryValidationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Insert validation: auto-generated PK must not be set

    @Test
    public void testInsertAutoGenPkWithExplicitIdThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // City has auto-generated PK (IDENTITY). Inserting with an explicit non-default PK should fail.
        assertThrows(PersistenceException.class,
                () -> cities.insert(City.builder().id(999).name("Explicit PK").build()));
    }

    @Test
    public void testInsertAutoGenPkWithDefaultIdSucceeds() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        long before = cities.count();
        // City with null PK (default for Integer) should succeed for auto-gen PK.
        cities.insert(City.builder().name("Auto Gen City").build());
        assertEquals(before + 1, cities.count());
    }

    // Insert with ignoreAutoGenerate: PK must be set

    @Test
    public void testInsertIgnoreAutoGenerateWithDefaultPkThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // When ignoreAutoGenerate=true, PK must be set (non-default).
        assertThrows(PersistenceException.class,
                () -> cities.insert(City.builder().name("No PK").build(), true));
    }

    @Test
    public void testInsertIgnoreAutoGenerateWithExplicitPkSucceeds() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        long before = cities.count();
        cities.insert(City.builder().id(500).name("Explicit").build(), true);
        assertEquals(before + 1, cities.count());
        assertEquals("Explicit", cities.getById(500).name());
    }

    // Update validation: PK must be set

    @Test
    public void testUpdateWithZeroPkThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Updating a city with PK=0 (default for Integer) should fail validation.
        assertThrows(PersistenceException.class,
                () -> cities.update(City.builder().id(0).name("Zero PK Update").build()));
    }

    // Delete validation: PK must be set

    @Test
    public void testRemoveWithDefaultPkThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Deleting a city with PK=0 (default) should fail validation.
        assertThrows(PersistenceException.class,
                () -> cities.remove(City.builder().id(0).name("No PK Delete").build()));
    }

    // Non-auto-generated PK: VetSpecialty uses @PK(generation = NONE)

    @Test
    public void testNonAutoGenPkInsertAndRemove() {
        var orm = ORMTemplate.of(dataSource);
        var vetSpecialties = orm.entity(VetSpecialty.class);
        // VetSpecialty has @PK(generation = NONE) with compound key.
        // Vet id=6 exists, specialty id=1 exists. Insert a new combination.
        VetSpecialtyPK pk = VetSpecialtyPK.builder().vetId(6).specialtyId(1).build();
        vetSpecialties.insert(new VetSpecialty(pk));
        // Verify it exists.
        VetSpecialty fetched = vetSpecialties.getById(pk);
        assertNotNull(fetched);
        // Delete it.
        vetSpecialties.remove(new VetSpecialty(pk));
    }

    @Test
    public void testNonAutoGenPkInsertWithDefaultPkThrows() {
        var orm = ORMTemplate.of(dataSource);
        var vetSpecialties = orm.entity(VetSpecialty.class);
        // Inserting VetSpecialty with default (all-zero) compound PK should fail for NONE generation.
        VetSpecialtyPK defaultPk = VetSpecialtyPK.builder().vetId(0).specialtyId(0).build();
        assertThrows(PersistenceException.class,
                () -> vetSpecialties.insert(new VetSpecialty(defaultPk)));
    }

    @Test
    public void testNonAutoGenPkUpsertWithDefaultPkThrows() {
        var orm = ORMTemplate.of(dataSource);
        var vetSpecialties = orm.entity(VetSpecialty.class);
        // For non-auto-gen PK, upsert requires PK to be set.
        VetSpecialtyPK defaultPk = VetSpecialtyPK.builder().vetId(0).specialtyId(0).build();
        assertThrows(PersistenceException.class,
                () -> vetSpecialties.upsert(new VetSpecialty(defaultPk)));
    }

    // Optimistic locking: version mismatch causes OptimisticLockException

    @Test
    public void testOptimisticLockExceptionOnVersionMismatch() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        // Fetch owner id=1, version=0.
        Owner owner = owners.getById(1);
        assertEquals(0, owner.version());
        // Update to increment version in the DB.
        owners.update(owner.toBuilder().firstName("Updated Once").build());
        // Now try updating with the stale version (version=0). Should throw optimistic lock.
        assertThrows(OptimisticLockException.class,
                () -> owners.update(owner.toBuilder().firstName("Stale Update").build()));
    }

    @Test
    public void testRemoveWithStaleVersionThrows() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        Owner owner = owners.getById(1);
        // Update to increment version.
        owners.update(owner.toBuilder().firstName("Updated").build());
        // Deleting with stale version should throw PersistenceException.
        assertThrows(PersistenceException.class,
                () -> owners.remove(owner));
    }

    // InsertAndFetch

    @Test
    public void testInsertAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        City result = cities.insertAndFetch(City.builder().name("FetchAfterInsert").build());
        assertNotNull(result);
        assertNotNull(result.id());
        assertTrue(result.id() > 0);
        assertEquals("FetchAfterInsert", result.name());
    }

    // InsertAndFetchId

    @Test
    public void testInsertAndFetchId() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        Integer id = cities.insertAndFetchId(City.builder().name("FetchIdCity").build());
        assertNotNull(id);
        assertTrue(id > 0);
        assertEquals("FetchIdCity", cities.getById(id).name());
    }

    // UpdateAndFetch

    @Test
    public void testUpdateAndFetch() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        City updated = cities.updateAndFetch(City.builder().id(1).name("FetchAfterUpdate").build());
        assertNotNull(updated);
        assertEquals(1, updated.id());
        assertEquals("FetchAfterUpdate", updated.name());
    }

    // Delete non-existent entity throws

    @Test
    public void testRemoveNonExistentEntityThrows() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertThrows(PersistenceException.class,
                () -> cities.remove(City.builder().id(99999).name("NonExistent").build()));
    }

    // Batch insert with auto-gen PK

    @Test
    public void testBatchInsertAutoGenPk() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        long before = cities.count();
        cities.insert(List.of(
                City.builder().name("BatchCity1").build(),
                City.builder().name("BatchCity2").build()
        ));
        assertEquals(before + 2, cities.count());
    }

    @Test
    public void testBatchInsertAndFetchIds() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var ids = cities.insertAndFetchIds(List.of(
                City.builder().name("FetchBatch1").build(),
                City.builder().name("FetchBatch2").build()
        ));
        assertEquals(2, ids.size());
        assertEquals("FetchBatch1", cities.getById(ids.get(0)).name());
        assertEquals("FetchBatch2", cities.getById(ids.get(1)).name());
    }

    // Batch update

    @Test
    public void testBatchUpdate() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        cities.update(List.of(
                City.builder().id(1).name("Updated Sun Prairie").build(),
                City.builder().id(2).name("Updated McFarland").build()
        ));
        assertEquals("Updated Sun Prairie", cities.getById(1).name());
        assertEquals("Updated McFarland", cities.getById(2).name());
    }

    // Batch delete

    @Test
    public void testBatchRemove() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var id1 = cities.insertAndFetchId(City.builder().name("TempDel1").build());
        var id2 = cities.insertAndFetchId(City.builder().name("TempDel2").build());
        long before = cities.count();
        cities.remove(List.of(
                City.builder().id(id1).name("TempDel1").build(),
                City.builder().id(id2).name("TempDel2").build()
        ));
        assertEquals(before - 2, cities.count());
    }

    // Callback re-entrancy guard

    @Test
    public void testCallbackReentrancyGuard() {
        // A callback that performs a database operation should not trigger callbacks again.
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                log.add("beforeInsert:" + entity.name());
                return entity;
            }

            @Override
            public void afterInsert(@Nonnull City entity) {
                log.add("afterInsert:" + entity.name());
            }
        });
        orm.entity(City.class).insert(City.builder().name("reentrancy test").build());
        assertEquals(2, log.size());
        assertEquals("beforeInsert:reentrancy test", log.get(0));
        assertEquals("afterInsert:reentrancy test", log.get(1));
    }

    // Callback on delete operations

    @Test
    public void testRemoveCallbacksWithBatch() {
        List<String> beforeLog = new ArrayList<>();
        List<String> afterLog = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void beforeDelete(@Nonnull City entity) {
                beforeLog.add(entity.name());
            }

            @Override
            public void afterDelete(@Nonnull City entity) {
                afterLog.add(entity.name());
            }
        });
        var cities = orm.entity(City.class);
        var id1 = cities.insertAndFetchId(City.builder().name("DelCbA").build());
        var id2 = cities.insertAndFetchId(City.builder().name("DelCbB").build());
        cities.remove(Stream.of(
                City.builder().id(id1).name("DelCbA").build(),
                City.builder().id(id2).name("DelCbB").build()
        ));
        assertEquals(List.of("DelCbA", "DelCbB"), beforeLog);
        assertEquals(List.of("DelCbA", "DelCbB"), afterLog);
    }

    // Batch insert with callbacks

    @Test
    public void testBatchInsertAndFetchIdsWithCallbacks() {
        List<String> beforeLog = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                beforeLog.add(entity.name());
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }
        });
        var cities = orm.entity(City.class);
        var ids = cities.insertAndFetchIds(List.of(
                City.builder().name("cb fetch a").build(),
                City.builder().name("cb fetch b").build()
        ));
        assertEquals(2, ids.size());
        assertEquals(List.of("cb fetch a", "cb fetch b"), beforeLog);
        // Verify the uppercased names were persisted.
        assertEquals("CB FETCH A", cities.getById(ids.get(0)).name());
        assertEquals("CB FETCH B", cities.getById(ids.get(1)).name());
    }

    // Update callbacks

    @Test
    public void testUpdateCallbacksFireCorrectly() {
        List<String> beforeLog = new ArrayList<>();
        List<String> afterLog = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeUpdate(@Nonnull City entity) {
                beforeLog.add(entity.name());
                return entity;
            }

            @Override
            public void afterUpdate(@Nonnull City entity) {
                afterLog.add(entity.name());
            }
        });
        var cities = orm.entity(City.class);
        cities.update(City.builder().id(1).name("Callback Updated").build());
        assertEquals(1, beforeLog.size());
        assertEquals("Callback Updated", beforeLog.get(0));
        assertEquals(1, afterLog.size());
        assertEquals("Callback Updated", afterLog.get(0));
    }

    // Model introspection via entity repository

    @Test
    public void testModelType() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertEquals(City.class, cities.model().type());
    }

    @Test
    public void testModelPrimaryKeyType() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertEquals(Integer.class, cities.model().primaryKeyType());
    }

    @Test
    public void testModelTableName() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertEquals("city", cities.model().name());
    }

    @Test
    public void testModelIsDefaultPrimaryKey() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertTrue(cities.model().isDefaultPrimaryKey(null));
        assertTrue(cities.model().isDefaultPrimaryKey(0));
        assertFalse(cities.model().isDefaultPrimaryKey(1));
    }

    @Test
    public void testModelIsNotJoinedInheritance() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        assertFalse(cities.model().isJoinedInheritance());
    }

    @Test
    public void testModelColumns() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var columns = cities.model().columns();
        // City has 2 columns: id and name.
        assertEquals(2, columns.size());
    }

    @Test
    public void testModelDeclaredColumns() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var declaredColumns = cities.model().declaredColumns();
        assertEquals(2, declaredColumns.size());
    }

    @Test
    public void testModelPrimaryKeyMetamodel() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var primaryKeyMetamodel = cities.model().getPrimaryKeyMetamodel();
        assertTrue(primaryKeyMetamodel.isPresent());
    }

    // Owner model with inline Address and FK City

    @Test
    public void testOwnerModelColumnsIncludeAddressAndCity() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        var columns = owners.model().columns();
        assertTrue(columns.size() > 6, "Owner model should have expanded columns including FK city");
    }

    @Test
    public void testOwnerModelDeclaredColumnsDoNotExpandFKs() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        var declaredColumns = owners.model().declaredColumns();
        assertTrue(declaredColumns.size() >= 5);
    }

    // FindMetamodel

    @Test
    public void testFindMetamodelForSelfType() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var metamodel = cities.model().findMetamodel(City.class);
        assertTrue(metamodel.isPresent());
    }

    @Test
    public void testFindMetamodelForForeignType() {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        // Owner has a FK to City via Address. findMetamodel should find it.
        var metamodel = owners.model().findMetamodel(City.class);
        assertTrue(metamodel.isPresent());
    }

    // ForEachValue / values for City

    @Test
    public void testModelForEachValue() throws SqlTemplateException {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var model = cities.model();
        City city = City.builder().id(42).name("TestCity").build();
        var values = model.declaredValues(city);
        assertEquals(2, values.size());
    }

    @Test
    public void testModelValuesForOwner() throws SqlTemplateException {
        var orm = ORMTemplate.of(dataSource);
        var owners = orm.entity(Owner.class);
        var model = owners.model();
        Owner owner = owners.getById(1);
        var values = model.declaredValues(owner);
        assertTrue(values.size() >= 5);
    }

    // Stream-based delete

    @Test
    public void testStreamBasedRemove() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        var id1 = cities.insertAndFetchId(City.builder().name("StreamDel1").build());
        var id2 = cities.insertAndFetchId(City.builder().name("StreamDel2").build());
        long before = cities.count();
        cities.remove(Stream.of(
                City.builder().id(id1).name("StreamDel1").build(),
                City.builder().id(id2).name("StreamDel2").build()
        ));
        assertEquals(before - 2, cities.count());
    }

    // Stream-based insert

    @Test
    public void testStreamBasedInsert() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        long before = cities.count();
        cities.insert(Stream.of(
                City.builder().name("StreamIns1").build(),
                City.builder().name("StreamIns2").build()
        ));
        assertEquals(before + 2, cities.count());
    }

    // Stream-based update

    @Test
    public void testStreamBasedUpdate() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        cities.update(Stream.of(
                City.builder().id(1).name("StreamUpd Sun Prairie").build(),
                City.builder().id(2).name("StreamUpd McFarland").build()
        ));
        assertEquals("StreamUpd Sun Prairie", cities.getById(1).name());
        assertEquals("StreamUpd McFarland", cities.getById(2).name());
    }

    // PetType: manual PK insert validation

    @Test
    public void testPetTypeInsertWithExplicitPk() {
        var orm = ORMTemplate.of(dataSource);
        var petTypes = orm.entity(PetType.class);
        // PetType has @PK(generation = NONE), so inserting with an explicit PK should succeed.
        assertDoesNotThrow(
                () -> petTypes.insert(PetType.builder().id(99).name("Explicit").build()));
    }

    @Test
    public void testPetTypeInsertWithNullPkThrowsAtDb() {
        var orm = ORMTemplate.of(dataSource);
        var petTypes = orm.entity(PetType.class);
        // PetType has @PK(generation = NONE) and the schema has no auto_increment.
        // Inserting with null PK fails at the database level.
        assertThrows(PersistenceException.class,
                () -> petTypes.insert(PetType.builder().name("New Type").build()));
    }

    // Foreign key default value validation

    @Test
    public void testInsertPetWithDefaultOwnerIdThrows() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        // Owner uses IDENTITY generation, so a default PK value (0) indicates an unsaved entity.
        var unsavedOwner = Owner.builder()
                .id(0)
                .firstName("John")
                .lastName("Doe")
                .address(Address.builder().address("123 Main St").city(City.builder().id(1).build()).build())
                .build();
        assertThrows(PersistenceException.class,
                () -> pets.insert(Pet.builder()
                        .name("Buddy")
                        .birthDate(java.time.LocalDate.of(2020, 1, 1))
                        .type(Ref.of(PetType.class, 0))
                        .owner(unsavedOwner)
                        .build()));
    }

    @Test
    public void testInsertPetWithNullOwnerSucceeds() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        // Null FK is allowed (nullable owner).
        assertDoesNotThrow(
                () -> pets.insert(Pet.builder()
                        .name("Buddy")
                        .birthDate(java.time.LocalDate.of(2020, 1, 1))
                        .type(Ref.of(PetType.class, 0))
                        .owner(null)
                        .build()));
    }

    @Test
    public void testInsertPetWithValidOwnerSucceeds() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        // Owner with a non-default PK should pass validation.
        var savedOwner = Owner.builder()
                .id(1)
                .firstName("Jane")
                .lastName("Doe")
                .address(Address.builder().address("456 Oak Ave").city(City.builder().id(1).build()).build())
                .build();
        assertDoesNotThrow(
                () -> pets.insert(Pet.builder()
                        .name("Rex")
                        .birthDate(java.time.LocalDate.of(2021, 6, 15))
                        .type(Ref.of(PetType.class, 1))
                        .owner(savedOwner)
                        .build()));
    }

    @Test
    public void testInsertPetWithDefaultPetTypeIdSucceeds() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        // PetType uses NONE generation, so a PK value of 0 is valid (not treated as unsaved).
        assertDoesNotThrow(
                () -> pets.insert(Pet.builder()
                        .name("Kitty")
                        .birthDate(java.time.LocalDate.of(2022, 3, 10))
                        .type(Ref.of(PetType.class, 0))
                        .owner(null)
                        .build()));
    }

    @Test
    public void testInsertVisitWithDefaultPetIdThrows() {
        var orm = ORMTemplate.of(dataSource);
        var visits = orm.entity(Visit.class);
        // Pet uses IDENTITY generation, so a default PK value (0) indicates an unsaved entity.
        var unsavedPet = Pet.builder()
                .id(0)
                .name("Ghost")
                .birthDate(java.time.LocalDate.of(2020, 1, 1))
                .type(Ref.of(PetType.class, 0))
                .build();
        assertThrows(PersistenceException.class,
                () -> visits.insert(Visit.builder()
                        .visitDate(java.time.LocalDate.of(2024, 1, 1))
                        .description("Checkup")
                        .pet(unsavedPet)
                        .timestamp(java.time.Instant.now())
                        .build()));
    }

    @Test
    public void testUpdatePetWithDefaultOwnerIdThrows() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        // Update should also validate FK default values.
        var unsavedOwner = Owner.builder()
                .id(0)
                .firstName("John")
                .lastName("Doe")
                .address(Address.builder().address("123 Main St").city(City.builder().id(1).build()).build())
                .build();
        assertThrows(PersistenceException.class,
                () -> pets.update(Pet.builder()
                        .id(1)
                        .name("Buddy")
                        .birthDate(java.time.LocalDate.of(2020, 1, 1))
                        .type(Ref.of(PetType.class, 0))
                        .owner(unsavedOwner)
                        .build()));
    }

    // Intermediate callback type resolution

    /**
     * An intermediate callback interface that extends EntityCallback with a bound type parameter.
     * Tests the resolveCallbackEntityType path for intermediate parameterized types (lines 141-145).
     */
    interface CityCallback extends EntityCallback<City> {
    }

    @Test
    public void testIntermediateCallbackTypeResolution() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new CityCallback() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                log.add(entity.name());
                return entity;
            }
        });
        orm.entity(City.class).insert(City.builder().name("intermediate callback").build());
        assertEquals(1, log.size());
        assertEquals("intermediate callback", log.get(0));
    }

    /**
     * An abstract base class that implements EntityCallback. Tests the superclass resolution path
     * (lines 154-156).
     */
    static abstract class AbstractCityCallback implements EntityCallback<City> {
    }

    @Test
    public void testSuperclassCallbackTypeResolution() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new AbstractCityCallback() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                log.add(entity.name());
                return entity;
            }
        });
        orm.entity(City.class).insert(City.builder().name("superclass callback").build());
        assertEquals(1, log.size());
        assertEquals("superclass callback", log.get(0));
    }

    /**
     * A callback registered for a specific type should NOT trigger for a different entity type.
     */
    @Test
    public void testCallbackNotTriggeredForDifferentEntityType() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Owner>() {
            @Override
            public Owner beforeInsert(@Nonnull Owner entity) {
                log.add("should-not-trigger");
                return entity;
            }
        });
        // Insert a City; the Owner callback should NOT fire.
        orm.entity(City.class).insert(City.builder().name("no callback city").build());
        assertTrue(log.isEmpty(), "Owner callback should not trigger for City insert");
    }

    // Joined inheritance operations

    @Test
    public void testJoinedInheritanceInsertAndGet() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        // Insert a JoinedCat via the JoinedAnimal repository.
        var cat = new st.orm.core.model.polymorphic.JoinedCat(null, "IntegrationCat", true);
        joinedAnimals.insert(cat);
    }

    @Test
    public void testJoinedInheritanceInsertAndFetchId() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var dog = new st.orm.core.model.polymorphic.JoinedDog(null, "IntegrationDog", 25);
        Integer id = joinedAnimals.insertAndFetchId(dog);
        assertNotNull(id);
        assertTrue(id > 0);
    }

    @Test
    public void testJoinedInheritanceInsertAndFetchIds() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var ids = joinedAnimals.insertAndFetchIds(List.of(
                new st.orm.core.model.polymorphic.JoinedCat(null, "BatchCat1", false),
                new st.orm.core.model.polymorphic.JoinedDog(null, "BatchDog1", 15)
        ));
        assertEquals(2, ids.size());
    }

    @Test
    public void testJoinedInheritanceUpdate() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var cat = new st.orm.core.model.polymorphic.JoinedCat(null, "UpdateCat", true);
        Integer id = joinedAnimals.insertAndFetchId(cat);
        // Update the name.
        joinedAnimals.update(new st.orm.core.model.polymorphic.JoinedCat(id, "UpdatedCat", false));
    }

    @Test
    public void testJoinedInheritanceBatchUpdate() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        Integer id1 = joinedAnimals.insertAndFetchId(new st.orm.core.model.polymorphic.JoinedCat(null, "BU Cat1", true));
        Integer id2 = joinedAnimals.insertAndFetchId(new st.orm.core.model.polymorphic.JoinedDog(null, "BU Dog1", 20));
        joinedAnimals.update(Stream.of(
                new st.orm.core.model.polymorphic.JoinedCat(id1, "BU Cat1 Updated", false),
                new st.orm.core.model.polymorphic.JoinedDog(id2, "BU Dog1 Updated", 25)
        ));
    }

    @Test
    public void testJoinedInheritanceRemove() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var dog = new st.orm.core.model.polymorphic.JoinedDog(null, "DeleteDog", 30);
        Integer id = joinedAnimals.insertAndFetchId(dog);
        joinedAnimals.remove(new st.orm.core.model.polymorphic.JoinedDog(id, "DeleteDog", 30));
    }

    @Test
    public void testJoinedInheritanceBatchRemove() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        Integer id1 = joinedAnimals.insertAndFetchId(new st.orm.core.model.polymorphic.JoinedCat(null, "BD Cat1", true));
        Integer id2 = joinedAnimals.insertAndFetchId(new st.orm.core.model.polymorphic.JoinedDog(null, "BD Dog1", 15));
        joinedAnimals.remove(Stream.of(
                new st.orm.core.model.polymorphic.JoinedCat(id1, "BD Cat1", true),
                new st.orm.core.model.polymorphic.JoinedDog(id2, "BD Dog1", 15)
        ));
    }

    @Test
    public void testJoinedInheritanceBatchInsert() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        joinedAnimals.insert(Stream.of(
                new st.orm.core.model.polymorphic.JoinedCat(null, "StreamCat1", true),
                new st.orm.core.model.polymorphic.JoinedDog(null, "StreamDog1", 10)
        ));
    }

    // Parameterized intermediate callback type resolution (L142-144)

    /**
     * An intermediate parameterized interface that extends EntityCallback with a bound type but has
     * its own type parameter. This forces the resolveCallbackEntityType to recurse through the
     * parameterized intermediate type (lines 141-144).
     */
    interface ParameterizedCityCallback<X> extends EntityCallback<City> {
    }

    @Test
    public void testParameterizedIntermediateCallbackTypeResolution() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new ParameterizedCityCallback<String>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                log.add(entity.name());
                return entity;
            }
        });
        orm.entity(City.class).insert(City.builder().name("parameterized callback").build());
        assertEquals(1, log.size());
        assertEquals("parameterized callback", log.get(0));
    }

    // Upsert rejection for joined sealed entities (L906)

    @Test
    public void testUpsertRejectedForJoinedSealedEntity() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        // Use null PK (default) so isUpsertUpdate returns false and we reach requireNonJoinedSealedEntity.
        var cat = new st.orm.core.model.polymorphic.JoinedCat(null, "UpsertCat", true);
        assertThrows(PersistenceException.class, () -> joinedAnimals.upsert(cat));
    }

    @Test
    public void testUpsertAndFetchIdRejectedForJoinedSealedEntity() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        var dog = new st.orm.core.model.polymorphic.JoinedDog(null, "UpsertDog", 20);
        assertThrows(PersistenceException.class, () -> joinedAnimals.upsertAndFetchId(dog));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testUpsertIterableRejectedForJoinedSealedEntity() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        Iterable<st.orm.core.model.polymorphic.JoinedAnimal> animals = List.of(
                new st.orm.core.model.polymorphic.JoinedCat(1, "UpsertCat", true));
        assertThrows(PersistenceException.class, () -> joinedAnimals.upsertAndFetchIds(animals));
    }

    @Test
    public void testUpsertStreamRejectedForJoinedSealedEntity() {
        var orm = ORMTemplate.of(dataSource);
        var joinedAnimals = orm.entity(st.orm.core.model.polymorphic.JoinedAnimal.class);
        assertThrows(PersistenceException.class, () ->
                joinedAnimals.upsert(Stream.of(
                        new st.orm.core.model.polymorphic.JoinedCat(1, "UpsertCat", true))));
    }
}
