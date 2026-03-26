package st.orm.template;

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.template.Templates.alias;
import static st.orm.template.Templates.insert;
import static st.orm.template.Templates.param;
import static st.orm.template.Templates.select;
import static st.orm.template.Templates.set;
import static st.orm.template.Templates.table;
import static st.orm.template.Templates.update;
import static st.orm.template.Templates.values;
import static st.orm.template.Templates.where;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.repository.EntityRepository;
import st.orm.repository.ProjectionRepository;
import st.orm.template.model.City;
import st.orm.template.model.OwnerView;
import st.orm.template.model.PetType;
import st.orm.template.model.Visit;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class ORMTemplateTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ORMTemplate orm;

    // ORMTemplate factory methods

    @Test
    public void testFactoryOfDataSource() {
        ORMTemplate template = ORMTemplate.of(dataSource);
        assertNotNull(template);
    }

    @Test
    public void testFactoryOfConnection() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ORMTemplate template = ORMTemplate.of(connection);
            assertNotNull(template);
        }
    }

    @Test
    public void testFactoryOfDataSourceViaTemplates() {
        ORMTemplate template = ORMTemplate.of(dataSource);
        assertNotNull(template);
        // Verify it can perform operations
        assertEquals(6, template.entity(City.class).count());
    }

    @Test
    public void testFactoryOfConnectionViaTemplates() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ORMTemplate template = ORMTemplate.of(connection);
            assertNotNull(template);
        }
    }

    // ORMTemplate.withEntityCallback / withEntityCallbacks

    @Test
    public void testWithEntityCallback() {
        EntityCallback<City> callback = new EntityCallback<>() {};
        ORMTemplate withCallback = orm.withEntityCallback(callback);
        assertNotNull(withCallback);
    }

    @Test
    public void testWithEntityCallbacks() {
        EntityCallback<City> callback = new EntityCallback<>() {};
        ORMTemplate withCallbacks = orm.withEntityCallbacks(List.of(callback));
        assertNotNull(withCallbacks);
    }

    // Entity repository

    @Test
    public void testEntityRepositoryAccess() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        assertNotNull(cities);
    }

    @Test
    public void testEntityFindAll() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<City> allCities = cities.findAll();
        assertEquals(6, allCities.size());
    }

    @Test
    public void testEntityFindById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Optional<City> city = cities.findById(1);
        assertTrue(city.isPresent());
        assertEquals("Sun Paririe", city.get().name());
    }

    @Test
    public void testEntityFindByIdNotFound() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Optional<City> city = cities.findById(999);
        assertFalse(city.isPresent());
    }

    @Test
    public void testEntityGetById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city = cities.getById(1);
        assertEquals("Sun Paririe", city.name());
    }

    @Test
    public void testEntityGetByIdNotFound() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        assertThrows(NoResultException.class, () -> cities.getById(999));
    }

    @Test
    public void testEntityFindAllById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<City> found = cities.findAllById(List.of(1, 2, 3));
        assertEquals(3, found.size());
    }

    @Test
    public void testEntityInsert() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long countBefore = cities.count();
        cities.insert(new City(null, "NewCity"));
        long countAfter = cities.count();
        assertEquals(countBefore + 1, countAfter);
    }

    @Test
    public void testEntityInsertAndFetch() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "InsertedCity"));
        assertNotNull(inserted);
        assertNotNull(inserted.id());
        assertEquals("InsertedCity", inserted.name());
    }

    @Test
    public void testEntityInsertAndFetchId() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Integer id = cities.insertAndFetchId(new City(null, "FetchIdCity"));
        assertNotNull(id);
        assertTrue(id > 0);
    }

    @Test
    public void testEntityUpdate() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "ToUpdate"));
        cities.update(new City(inserted.id(), "Updated"));
        City updated = cities.getById(inserted.id());
        assertEquals("Updated", updated.name());
    }

    @Test
    public void testEntityUpdateAndFetch() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "UpdAndFetch"));
        City updated = cities.updateAndFetch(new City(inserted.id(), "UpdAndFetched"));
        assertEquals("UpdAndFetched", updated.name());
    }

    @Test
    public void testEntityDelete() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "ToDelete"));
        long countBefore = cities.count();
        cities.delete(inserted);
        long countAfter = cities.count();
        assertEquals(countBefore - 1, countAfter);
    }

    @Test
    public void testEntityDeleteById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "DeleteById"));
        cities.deleteById(inserted.id());
        assertFalse(cities.findById(inserted.id()).isPresent());
    }

    @Test
    public void testEntityDeleteAll() {
        // Visit has no incoming FK constraints, so we can safely deleteAll
        var localOrm = ORMTemplate.of(dataSource);
        EntityRepository<Visit, Integer> visits = localOrm.entity(Visit.class);
        assertTrue(visits.count() > 0);
        visits.deleteAll();
        assertEquals(0, visits.count());
    }

    @Test
    public void testEntityCount() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        assertEquals(6, cities.count());
    }

    @Test
    public void testEntityExists() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        assertTrue(cities.exists());
    }

    @Test
    public void testEntityExistsById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        assertTrue(cities.existsById(1));
        assertFalse(cities.existsById(999));
    }

    @Test
    public void testEntityExistsByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Ref<City> ref = cities.ref(1);
        assertTrue(cities.existsByRef(ref));
    }

    @Test
    public void testEntityRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Ref<City> ref = cities.ref(1);
        assertNotNull(ref);
        assertEquals(1, ref.id());
    }

    @Test
    public void testEntityRefFromEntity() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city = cities.getById(1);
        Ref<City> ref = cities.ref(city);
        assertNotNull(ref);
    }

    @Test
    public void testEntityUnload() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city = cities.getById(1);
        Ref<City> ref = cities.unload(city);
        assertNotNull(ref);
    }

    @Test
    public void testEntityFindByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Ref<City> ref = cities.ref(1);
        Optional<City> result = cities.findByRef(ref);
        assertTrue(result.isPresent());
    }

    @Test
    public void testEntityGetByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Ref<City> ref = cities.ref(1);
        City city = cities.getByRef(ref);
        assertNotNull(city);
    }

    @Test
    public void testEntityFindAllByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<Ref<City>> refs = List.of(cities.ref(1), cities.ref(2));
        List<City> found = cities.findAllByRef(refs);
        assertEquals(2, found.size());
    }

    @Test
    public void testEntityFindAllRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<Ref<City>> allRefs = cities.findAllRef();
        assertEquals(6, allRefs.size());
    }

    @Test
    public void testEntitySelectAll() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        try (Stream<City> stream = cities.select().getResultStream()) {
            assertEquals(6, stream.count());
        }
    }

    @Test
    public void testEntitySelectAllRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        try (Stream<Ref<City>> stream = cities.selectRef().getResultStream()) {
            assertEquals(6, stream.count());
        }
    }

    @Test
    public void testEntitySelectById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        try (Stream<City> stream = cities.selectById(Stream.of(1, 2, 3))) {
            assertEquals(3, stream.count());
        }
    }

    @Test
    public void testEntitySelectByIdWithChunkSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        try (Stream<City> stream = cities.selectById(Stream.of(1, 2, 3, 4), 2)) {
            assertEquals(4, stream.count());
        }
    }

    @Test
    public void testEntitySelectByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        try (Stream<City> stream = cities.selectByRef(Stream.of(cities.ref(1), cities.ref(2)))) {
            assertEquals(2, stream.count());
        }
    }

    @Test
    public void testEntitySelectByRefWithChunkSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        try (Stream<City> stream = cities.selectByRef(Stream.of(cities.ref(1), cities.ref(2)), 1)) {
            assertEquals(2, stream.count());
        }
    }

    @Test
    public void testEntityCountById() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long count = cities.countById(Stream.of(1, 2, 3));
        assertEquals(3, count);
    }

    @Test
    public void testEntityCountByIdWithChunkSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long count = cities.countById(Stream.of(1, 2, 3, 4), 2);
        assertEquals(4, count);
    }

    @Test
    public void testEntityCountByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long count = cities.countByRef(Stream.of(cities.ref(1), cities.ref(2)));
        assertEquals(2, count);
    }

    @Test
    public void testEntityCountByRefWithChunkSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long count = cities.countByRef(Stream.of(cities.ref(1), cities.ref(2)), 1);
        assertEquals(2, count);
    }

    @Test
    public void testEntityInsertIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long countBefore = cities.count();
        cities.insert(List.of(new City(null, "Batch1"), new City(null, "Batch2")));
        assertEquals(countBefore + 2, cities.count());
    }

    @Test
    public void testEntityInsertAndFetchIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<City> inserted = cities.insertAndFetch(List.of(new City(null, "FB1"), new City(null, "FB2")));
        assertEquals(2, inserted.size());
        assertNotNull(inserted.get(0).id());
    }

    @Test
    public void testEntityInsertAndFetchIdsIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<Integer> ids = cities.insertAndFetchIds(List.of(new City(null, "ID1"), new City(null, "ID2")));
        assertEquals(2, ids.size());
        assertTrue(ids.get(0) > 0);
    }

    @Test
    public void testEntityUpdateIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UpdIter1"));
        City city2 = cities.insertAndFetch(new City(null, "UpdIter2"));
        cities.update(List.of(new City(city1.id(), "UpdIter1X"), new City(city2.id(), "UpdIter2X")));
        assertEquals("UpdIter1X", cities.getById(city1.id()).name());
    }

    @Test
    public void testEntityUpdateAndFetchIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UAFIter1"));
        City city2 = cities.insertAndFetch(new City(null, "UAFIter2"));
        List<City> updated = cities.updateAndFetch(List.of(
                new City(city1.id(), "UAFIter1X"),
                new City(city2.id(), "UAFIter2X")));
        assertEquals(2, updated.size());
    }

    @Test
    public void testEntityDeleteIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "DelIter1"));
        City city2 = cities.insertAndFetch(new City(null, "DelIter2"));
        long countBefore = cities.count();
        cities.delete(List.of(city1, city2));
        assertEquals(countBefore - 2, cities.count());
    }

    @Test
    public void testEntityDeleteByRefIterable() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "DelRef1"));
        City city2 = cities.insertAndFetch(new City(null, "DelRef2"));
        long countBefore = cities.count();
        cities.deleteByRef(List.of(cities.ref(city1.id()), cities.ref(city2.id())));
        assertEquals(countBefore - 2, cities.count());
    }

    @Test
    public void testEntityDeleteByRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "DelByRef"));
        cities.deleteByRef(cities.ref(inserted.id()));
        assertFalse(cities.findById(inserted.id()).isPresent());
    }

    @Test
    public void testEntityInsertStream() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long countBefore = cities.count();
        cities.insert(Stream.of(new City(null, "StreamA"), new City(null, "StreamB")));
        assertEquals(countBefore + 2, cities.count());
    }

    @Test
    public void testEntityInsertStreamWithBatchSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long countBefore = cities.count();
        cities.insert(Stream.of(new City(null, "BatchA"), new City(null, "BatchB")), 1);
        assertEquals(countBefore + 2, cities.count());
    }

    @Test
    public void testEntityUpdateStream() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UpdStream1"));
        City city2 = cities.insertAndFetch(new City(null, "UpdStream2"));
        cities.update(Stream.of(new City(city1.id(), "UpdStream1X"), new City(city2.id(), "UpdStream2X")));
        assertEquals("UpdStream1X", cities.getById(city1.id()).name());
    }

    @Test
    public void testEntityUpdateStreamWithBatchSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UpdSB1"));
        cities.update(Stream.of(new City(city1.id(), "UpdSB1X")), 1);
        assertEquals("UpdSB1X", cities.getById(city1.id()).name());
    }

    @Test
    public void testEntityDeleteStream() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "DelStream1"));
        City city2 = cities.insertAndFetch(new City(null, "DelStream2"));
        long countBefore = cities.count();
        cities.delete(Stream.of(city1, city2));
        assertEquals(countBefore - 2, cities.count());
    }

    @Test
    public void testEntityDeleteStreamWithBatchSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "DelSB1"));
        long countBefore = cities.count();
        cities.delete(Stream.of(city1), 1);
        assertEquals(countBefore - 1, cities.count());
    }

    @Test
    public void testEntityDeleteByRefStream() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "DelRefStream1"));
        long countBefore = cities.count();
        cities.deleteByRef(Stream.of(cities.ref(city1.id())));
        assertEquals(countBefore - 1, cities.count());
    }

    @Test
    public void testEntityDeleteByRefStreamWithBatchSize() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "DelRefSB1"));
        long countBefore = cities.count();
        cities.deleteByRef(Stream.of(cities.ref(city1.id())), 1);
        assertEquals(countBefore - 1, cities.count());
    }

    @Test
    public void testEntityRepositoryOrm() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        ORMTemplate repoOrm = cities.orm();
        assertNotNull(repoOrm);
    }

    @Test
    public void testEntityModel() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        Model<City, Integer> model = cities.model();
        assertNotNull(model);
        assertEquals("city", model.name());
        assertEquals(City.class, model.type());
        assertEquals(Integer.class, model.primaryKeyType());
    }

    @Test
    public void testEntityInsertWithIgnoreAutoGenerate() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        cities.insert(new City(9999, "ManualId"), true);
        City found = cities.getById(9999);
        assertEquals("ManualId", found.name());
    }

    @Test
    public void testEntityInsertIterableWithIgnoreAutoGenerate() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        cities.insert(List.of(new City(9997, "ManualBatch1"), new City(9998, "ManualBatch2")), true);
        assertEquals("ManualBatch1", cities.getById(9997).name());
    }

    @Test
    public void testEntityInsertStreamIgnoreAutoGenerate() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        cities.insert(Stream.of(new City(9995, "StreamManual1")), true);
        assertEquals("StreamManual1", cities.getById(9995).name());
    }

    @Test
    public void testEntityInsertStreamWithBatchSizeIgnoreAutoGenerate() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        cities.insert(Stream.of(new City(9993, "SBManual1")), 1, true);
        assertEquals("SBManual1", cities.getById(9993).name());
    }

    // Projection repository

    @Test
    public void testProjectionRepositoryAccess() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        assertNotNull(views);
    }

    @Test
    public void testProjectionFindAll() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<OwnerView> all = views.findAll();
        assertEquals(10, all.size());
    }

    @Test
    public void testProjectionFindById() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        Optional<OwnerView> view = views.findById(1);
        assertTrue(view.isPresent());
        assertEquals("Betty", view.get().firstName());
    }

    @Test
    public void testProjectionGetById() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        OwnerView view = views.getById(1);
        assertEquals("Betty", view.firstName());
    }

    @Test
    public void testProjectionCount() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        assertEquals(10, views.count());
    }

    @Test
    public void testProjectionExists() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        assertTrue(views.exists());
    }

    @Test
    public void testProjectionExistsById() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        assertTrue(views.existsById(1));
        assertFalse(views.existsById(999));
    }

    @Test
    public void testProjectionExistsByRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        Ref<OwnerView> ref = views.ref(1);
        assertTrue(views.existsByRef(ref));
    }

    @Test
    public void testProjectionFindByRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        Ref<OwnerView> ref = views.ref(1);
        Optional<OwnerView> result = views.findByRef(ref);
        assertTrue(result.isPresent());
    }

    @Test
    public void testProjectionGetByRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        Ref<OwnerView> ref = views.ref(1);
        OwnerView view = views.getByRef(ref);
        assertNotNull(view);
    }

    @Test
    public void testProjectionFindAllById() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<OwnerView> found = views.findAllById(List.of(1, 2, 3));
        assertEquals(3, found.size());
    }

    @Test
    public void testProjectionFindAllByRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<Ref<OwnerView>> refs = List.of(views.ref(1), views.ref(2));
        List<OwnerView> found = views.findAllByRef(refs);
        assertEquals(2, found.size());
    }

    @Test
    public void testProjectionSelectAll() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        try (Stream<OwnerView> stream = views.select().getResultStream()) {
            assertEquals(10, stream.count());
        }
    }

    @Test
    public void testProjectionSelectById() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        try (Stream<OwnerView> stream = views.selectById(Stream.of(1, 2))) {
            assertEquals(2, stream.count());
        }
    }

    @Test
    public void testProjectionSelectByIdWithBatchSize() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        try (Stream<OwnerView> stream = views.selectById(Stream.of(1, 2, 3), 2)) {
            assertEquals(3, stream.count());
        }
    }

    @Test
    public void testProjectionSelectByRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        try (Stream<OwnerView> stream = views.selectByRef(Stream.of(views.ref(1)))) {
            assertEquals(1, stream.count());
        }
    }

    @Test
    public void testProjectionSelectByRefWithBatchSize() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        try (Stream<OwnerView> stream = views.selectByRef(Stream.of(views.ref(1), views.ref(2)), 1)) {
            assertEquals(2, stream.count());
        }
    }

    @Test
    public void testProjectionCountById() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        long count = views.countById(Stream.of(1, 2));
        assertEquals(2, count);
    }

    @Test
    public void testProjectionCountByIdWithBatchSize() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        long count = views.countById(Stream.of(1, 2, 3), 2);
        assertEquals(3, count);
    }

    @Test
    public void testProjectionCountByRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        long count = views.countByRef(Stream.of(views.ref(1)));
        assertEquals(1, count);
    }

    @Test
    public void testProjectionCountByRefWithBatchSize() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        long count = views.countByRef(Stream.of(views.ref(1), views.ref(2)), 1);
        assertEquals(2, count);
    }

    @Test
    public void testProjectionModel() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        Model<OwnerView, Integer> model = views.model();
        assertNotNull(model);
        assertEquals("owner_view", model.name());
        assertEquals(OwnerView.class, model.type());
    }

    @Test
    public void testProjectionRepositoryOrm() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        ORMTemplate repoOrm = views.orm();
        assertNotNull(repoOrm);
    }

    @Test
    public void testProjectionRefWithProjection() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        OwnerView view = views.getById(1);
        Ref<OwnerView> ref = views.ref(view, 1);
        assertNotNull(ref);
    }

    // QueryTemplate methods

    @Test
    public void testDialect() {
        assertNotNull(orm.dialect());
    }

    @Test
    public void testCreateBindVars() {
        assertNotNull(orm.createBindVars());
    }

    @Test
    public void testRefByTypeAndId() {
        Ref<City> ref = orm.ref(City.class, 1);
        assertNotNull(ref);
        assertEquals(1, ref.id());
    }

    @Test
    public void testRefByRecordAndId() {
        City city = orm.entity(City.class).getById(1);
        Ref<City> ref = orm.ref(city, 1);
        assertNotNull(ref);
    }

    @Test
    public void testModelByType() {
        Model<City, ?> model = orm.model(City.class);
        assertNotNull(model);
        assertEquals("city", model.name());
    }

    @Test
    public void testModelByTypeWithPrimaryKeyRequirement() {
        Model<City, ?> model = orm.model(City.class, true);
        assertNotNull(model);
    }

    @Test
    public void testModelColumns() {
        Model<City, ?> model = orm.model(City.class);
        List<Column> columns = model.columns();
        assertFalse(columns.isEmpty());
    }

    @Test
    public void testModelDeclaredColumns() {
        Model<City, ?> model = orm.model(City.class);
        List<Column> declaredColumns = model.declaredColumns();
        assertFalse(declaredColumns.isEmpty());
    }

    @Test
    public void testModelSchema() {
        Model<City, ?> model = orm.model(City.class);
        assertNotNull(model.schema());
    }

    @Test
    public void testModelIsDefaultPrimaryKey() {
        Model<City, Integer> model = (Model<City, Integer>) (Model<?, ?>) orm.model(City.class);
        assertTrue(model.isDefaultPrimaryKey(0));
        assertTrue(model.isDefaultPrimaryKey(null));
        assertFalse(model.isDefaultPrimaryKey(1));
    }

    @Test
    public void testModelForEachValue() throws Exception {
        Model<City, Integer> model = (Model<City, Integer>) (Model<?, ?>) orm.model(City.class);
        City city = new City(1, "TestCity");
        model.forEachValue(model.columns(), city, (column, value) -> {
            assertNotNull(column);
        });
    }

    @Test
    public void testModelValues() throws Exception {
        Model<City, Integer> model = (Model<City, Integer>) (Model<?, ?>) orm.model(City.class);
        City city = new City(1, "TestCity");
        var values = model.values(city);
        assertFalse(values.isEmpty());
    }

    @Test
    public void testModelValuesWithColumnList() throws Exception {
        Model<City, Integer> model = (Model<City, Integer>) (Model<?, ?>) orm.model(City.class);
        City city = new City(1, "TestCity");
        var values = model.values(model.columns(), city);
        assertFalse(values.isEmpty());
    }

    @Test
    public void testColumnProperties() {
        Model<City, ?> model = orm.model(City.class);
        Column idColumn = model.columns().stream().filter(Column::primaryKey).findFirst().orElseThrow();
        assertNotNull(idColumn.name());
        assertNotNull(idColumn.type());
        assertTrue(idColumn.primaryKey());
        assertNotNull(idColumn.generation());
        assertNotNull(idColumn.sequence());
        assertTrue(idColumn.index() > 0);
    }

    // Query with String Templates

    @Test
    public void testQuerySelectAll() {
        List<City> cities = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultList(City.class);
        assertEquals(6, cities.size());
    }

    @Test
    public void testQuerySelectWithParam() {
        List<City> cities = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.name = \{"Madison"}")
                .getResultList(City.class);
        assertEquals(1, cities.size());
        assertEquals("Madison", cities.get(0).name());
    }

    @Test
    public void testQueryGetSingleResult() {
        City city = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{1}")
                .getSingleResult(City.class);
        assertEquals("Sun Paririe", city.name());
    }

    @Test
    public void testQueryGetOptionalResult() {
        Optional<City> city = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{999}")
                .getOptionalResult(City.class);
        assertFalse(city.isPresent());
    }

    @Test
    public void testQueryGetOptionalResultPresent() {
        Optional<City> city = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{1}")
                .getOptionalResult(City.class);
        assertTrue(city.isPresent());
    }

    @Test
    public void testQueryGetResultStream() {
        try (Stream<City> stream = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultStream(City.class)) {
            assertEquals(6, stream.count());
        }
    }

    @Test
    public void testQueryGetResultStreamRaw() {
        try (Stream<Object[]> stream = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultStream()) {
            assertEquals(6, stream.count());
        }
    }

    @Test
    public void testQueryGetSingleResultRaw() {
        Object[] row = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{1}")
                .getSingleResult();
        assertNotNull(row);
    }

    @Test
    public void testQueryGetOptionalResultRaw() {
        Optional<Object[]> row = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{999}")
                .getOptionalResult();
        assertFalse(row.isPresent());
    }

    @Test
    public void testQueryGetResultList() {
        List<Object[]> rows = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultList();
        assertEquals(6, rows.size());
    }

    @Test
    public void testQueryGetResultCount() {
        long count = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultCount();
        assertEquals(6, count);
    }

    @Test
    public void testQueryIsVersionAware() {
        Query query = orm.query(RAW."SELECT \{City.class} FROM \{City.class}");
        // Simple query without version tracking should be false
        assertFalse(query.isVersionAware());
    }

    @Test
    public void testQueryUnsafe() {
        Query query = orm.query(RAW."DELETE FROM \{City.class}");
        Query unsafeQuery = query.unsafe();
        assertNotNull(unsafeQuery);
    }

    @Test
    public void testQueryWithString() {
        Query query = orm.query("SELECT 1");
        assertNotNull(query);
    }

    @Test
    public void testQuerySingleResultThrowsNoResultException() {
        assertThrows(NoResultException.class, () ->
                orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{999}")
                        .getSingleResult(City.class));
    }

    @Test
    public void testQuerySingleResultThrowsNonUniqueResultException() {
        assertThrows(NonUniqueResultException.class, () ->
                orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                        .getSingleResult(City.class));
    }

    @Test
    public void testQueryInsert() {
        long countBefore = orm.entity(City.class).count();
        orm.query(RAW."INSERT INTO \{table(City.class)} (name) VALUES (\{"QueryInserted"})")
                .executeUpdate();
        long countAfter = orm.entity(City.class).count();
        assertEquals(countBefore + 1, countAfter);
    }

    @Test
    public void testQueryGetRefStream() {
        try (Stream<Ref<City>> stream = orm.query(RAW."SELECT \{City.class}.id FROM \{City.class}")
                .getRefStream(City.class, Integer.class)) {
            assertEquals(6, stream.count());
        }
    }

    @Test
    public void testQueryGetRefList() {
        List<Ref<City>> refs = orm.query(RAW."SELECT \{City.class}.id FROM \{City.class}")
                .getRefList(City.class, Integer.class);
        assertEquals(6, refs.size());
    }

    // Query with Templates helper methods

    @Test
    public void testTemplatesSelect() {
        var selectElement = select(City.class);
        assertNotNull(selectElement);
    }

    @Test
    public void testTemplatesFrom() {
        var fromElement = Templates.from(City.class, true);
        assertNotNull(fromElement);
    }

    @Test
    public void testTemplatesAlias() {
        var aliasElement = alias(City.class);
        assertNotNull(aliasElement);
    }

    @Test
    public void testTemplatesTable() {
        var tableElement = table(City.class);
        assertNotNull(tableElement);
    }

    @Test
    public void testTemplatesParam() {
        var paramElement = param("test");
        assertNotNull(paramElement);
    }

    @Test
    public void testTemplatesInsert() {
        var insertElement = insert(City.class);
        assertNotNull(insertElement);
    }

    @Test
    public void testTemplatesUpdate() {
        var updateElement = update(City.class);
        assertNotNull(updateElement);
    }

    @Test
    public void testTemplatesValues() {
        var valuesElement = values(new City(null, "Test"));
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesSet() {
        var setElement = set(new City(1, "Test"));
        assertNotNull(setElement);
    }

    @Test
    public void testTemplatesWhere() {
        var whereElement = where(new City(1, "Test"));
        assertNotNull(whereElement);
    }

    // SelectFrom (QueryBuilder) via QueryTemplate

    @Test
    public void testSelectFrom() {
        List<City> cities = orm.selectFrom(City.class)
                .getResultList();
        assertEquals(6, cities.size());
    }

    @Test
    public void testSelectFromWithType() {
        List<City> cities = orm.selectFrom(City.class, City.class)
                .getResultList();
        assertEquals(6, cities.size());
    }

    @Test
    public void testDeleteFrom() {
        // Visit has no incoming FK constraints, so we can safely deleteFrom
        var localOrm = ORMTemplate.of(dataSource);
        int deleted = localOrm.deleteFrom(Visit.class).unsafe().executeUpdate();
        assertTrue(deleted > 0);
    }

    // Subquery via QueryTemplate

    @Test
    public void testSubquery() {
        var subqueryBuilder = orm.subquery(City.class);
        assertNotNull(subqueryBuilder);
    }

    @Test
    public void testSubqueryWithSelectType() {
        var subqueryBuilder = orm.subquery(City.class, City.class);
        assertNotNull(subqueryBuilder);
    }

    // PreparedQuery

    @Test
    public void testPreparedQueryInsertAndGetGeneratedKeys() {
        var bindVars = orm.createBindVars();
        try (PreparedQuery preparedQuery = orm.query(RAW."INSERT INTO \{City.class} VALUES \{values(bindVars)}").prepare()) {
            preparedQuery.addBatch(new City(null, "PrepCity1"));
            preparedQuery.addBatch(new City(null, "PrepCity2"));
            preparedQuery.executeBatch();
            try (Stream<Integer> keys = preparedQuery.getGeneratedKeys(Integer.class)) {
                List<Integer> keysList = keys.toList();
                assertEquals(2, keysList.size());
            }
        }
    }

    @Test
    public void testPreparedQueryExecuteUpdate() {
        var bindVars = orm.createBindVars();
        try (PreparedQuery preparedQuery = orm.query(RAW."INSERT INTO \{City.class} VALUES \{values(bindVars)}").prepare()) {
            preparedQuery.addBatch(new City(null, "PrepUpd"));
            int result = preparedQuery.executeUpdate();
            assertTrue(result >= 0);
        }
    }

    // EntityRepository select/selectCount/delete builders

    @Test
    public void testEntitySelect() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<City> result = cities.select().getResultList();
        assertEquals(6, result.size());
    }

    @Test
    public void testEntitySelectCount() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long count = cities.selectCount().getSingleResult();
        assertEquals(6, count);
    }

    @Test
    public void testEntitySelectWithType() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<City> result = cities.select(City.class).getResultList();
        assertEquals(6, result.size());
    }

    @Test
    public void testEntitySelectRef() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<Ref<City>> refs = cities.selectRef().getResultList();
        assertEquals(6, refs.size());
    }

    @Test
    public void testEntityDeleteBuilder() {
        // Visit has no incoming FK constraints, so we can safely delete all
        var localOrm = ORMTemplate.of(dataSource);
        EntityRepository<Visit, Integer> visits = localOrm.entity(Visit.class);
        int deleted = visits.delete().unsafe().executeUpdate();
        assertTrue(deleted > 0);
    }

    // ProjectionRepository select/selectCount builders

    @Test
    public void testProjectionSelect() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<OwnerView> result = views.select().getResultList();
        assertEquals(10, result.size());
    }

    @Test
    public void testProjectionSelectCount() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        long count = views.selectCount().getSingleResult();
        assertEquals(10, count);
    }

    @Test
    public void testProjectionSelectWithType() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<OwnerView> result = views.select(OwnerView.class).getResultList();
        assertEquals(10, result.size());
    }

    @Test
    public void testProjectionSelectRef() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<Ref<OwnerView>> refs = views.selectRef().getResultList();
        assertEquals(10, refs.size());
    }

    // Upsert operations

    @Test
    public void testEntityUpsertWithAutoGeneratedKey() {
        // City has auto-generated PK. When ID is non-default, upsert routes to update.
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "UpsertCity"));
        // Upsert with existing ID should route to update
        cities.upsert(new City(inserted.id(), "UpsertCityUpdated"));
        assertEquals("UpsertCityUpdated", cities.getById(inserted.id()).name());
    }

    @Test
    public void testEntityUpsertWithDefaultIdThrowsWithoutDialect() {
        // City has auto-generated PK. When ID is default (null), upsert falls through to
        // doUpsert() which requires dialect-specific MERGE support.
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        assertThrows(PersistenceException.class, () -> cities.upsert(new City(null, "UpsertNewCity")));
    }

    @Test
    public void testEntityUpsertAndFetchIdWithAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "UpsertFetchIdCity"));
        Integer id = cities.upsertAndFetchId(new City(inserted.id(), "UpsertFetchIdCityUpdated"));
        assertEquals(inserted.id(), id);
    }

    @Test
    public void testEntityUpsertAndFetchWithAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "UpsertFetchCity"));
        City result = cities.upsertAndFetch(new City(inserted.id(), "UpsertFetchCityUpdated"));
        assertNotNull(result);
        assertEquals("UpsertFetchCityUpdated", result.name());
    }

    @Test
    public void testEntityUpsertNotAvailableWithoutDialect() {
        // PetType has non-auto-generated PK, so upsert requires dialect-specific MERGE support.
        // Without a dialect module, upsert should throw PersistenceException.
        EntityRepository<PetType, Integer> types = orm.entity(PetType.class);
        assertThrows(PersistenceException.class, () -> types.upsert(new PetType(100, "UpsertType")));
    }

    @Test
    public void testEntityUpsertIterableWithAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UBatch1"));
        City city2 = cities.insertAndFetch(new City(null, "UBatch2"));
        cities.upsert(List.of(new City(city1.id(), "UBatch1X"), new City(city2.id(), "UBatch2X")));
        assertEquals("UBatch1X", cities.getById(city1.id()).name());
    }

    @Test
    public void testEntityUpsertAndFetchIdsIterableWithAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UAFI1"));
        City city2 = cities.insertAndFetch(new City(null, "UAFI2"));
        List<Integer> ids = cities.upsertAndFetchIds(List.of(new City(city1.id(), "UAFI1X"), new City(city2.id(), "UAFI2X")));
        assertEquals(2, ids.size());
    }

    @Test
    public void testEntityUpsertAndFetchIterableWithAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City city1 = cities.insertAndFetch(new City(null, "UAF1"));
        City city2 = cities.insertAndFetch(new City(null, "UAF2"));
        List<City> results = cities.upsertAndFetch(List.of(new City(city1.id(), "UAF1X"), new City(city2.id(), "UAF2X")));
        assertEquals(2, results.size());
    }

    @Test
    public void testEntityUpsertStreamWithAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "US1"));
        cities.upsert(Stream.of(new City(inserted.id(), "US1X")));
        assertEquals("US1X", cities.getById(inserted.id()).name());
    }

    @Test
    public void testEntityUpsertStreamWithBatchSizeAndAutoGeneratedKey() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        City inserted = cities.insertAndFetch(new City(null, "USB1"));
        cities.upsert(Stream.of(new City(inserted.id(), "USB1X")), 1);
        assertEquals("USB1X", cities.getById(inserted.id()).name());
    }

    // SelectRef with specific type

    @Test
    public void testEntitySelectRefWithType() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<Ref<City>> refs = cities.selectRef(City.class).getResultList();
        assertEquals(6, refs.size());
    }

    @Test
    public void testProjectionSelectRefWithType() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<Ref<OwnerView>> refs = views.selectRef(OwnerView.class).getResultList();
        assertEquals(10, refs.size());
    }

    // Select with template

    @Test
    public void testEntitySelectWithTemplate() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<String> names = cities.select(String.class, RAW."\{City.class}.name").getResultList();
        assertEquals(6, names.size());
    }

    @Test
    public void testProjectionSelectWithTemplate() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<String> names = views.select(String.class, RAW."\{OwnerView.class}.first_name").getResultList();
        assertEquals(10, names.size());
    }

    // SelectFrom with template

    @Test
    public void testSelectFromWithTemplate() {
        List<String> names = orm.selectFrom(City.class, String.class, RAW."\{City.class}.name")
                .getResultList();
        assertEquals(6, names.size());
    }

    // ValidateSchema

    @Test
    public void testValidateSchema() {
        List<String> errors = orm.validateSchema(List.of(City.class));
        assertNotNull(errors);
    }

    @Test
    public void testValidateSchemaOrThrow() {
        assertDoesNotThrow(() -> orm.validateSchemaOrThrow(List.of(City.class)));
    }

    // Additional Templates helper methods coverage

    @Test
    public void testTemplatesFromWithAlias() {
        var fromElement = Templates.from(City.class, "c", true);
        assertNotNull(fromElement);
    }

    @Test
    public void testTemplatesFromWithTemplate() {
        var fromElement = Templates.from(RAW."city", "c");
        assertNotNull(fromElement);
    }

    @Test
    public void testTemplatesInsertIgnoreAutoGenerate() {
        var insertElement = insert(City.class, true);
        assertNotNull(insertElement);
    }

    @Test
    public void testTemplatesValuesIgnoreAutoGenerate() {
        var valuesElement = values(new City(1, "Test"), true);
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesValuesVarargs() {
        var valuesElement = values(new City(1, "A"), new City(2, "B"));
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesValuesIterable() {
        var valuesElement = values(List.of(new City(1, "A"), new City(2, "B")));
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesValuesIterableIgnoreAutoGenerate() {
        var valuesElement = values(List.of(new City(1, "A")), true);
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesValuesBindVars() {
        var bindVars = orm.createBindVars();
        var valuesElement = values(bindVars);
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesValuesBindVarsIgnoreAutoGenerate() {
        var bindVars = orm.createBindVars();
        var valuesElement = values(bindVars, true);
        assertNotNull(valuesElement);
    }

    @Test
    public void testTemplatesUpdateWithAlias() {
        var updateElement = update(City.class, "c");
        assertNotNull(updateElement);
    }

    @Test
    public void testTemplatesSetBindVars() {
        var bindVars = orm.createBindVars();
        var setElement = Templates.set(bindVars);
        assertNotNull(setElement);
    }

    @Test
    public void testTemplatesWhereIterable() {
        var city1 = new City(1, "A");
        var city2 = new City(2, "B");
        var whereElement = where(List.of(city1, city2));
        assertNotNull(whereElement);
    }

    @Test
    public void testTemplatesWhereBindVars() {
        var bindVars = orm.createBindVars();
        var whereElement = Templates.where(bindVars);
        assertNotNull(whereElement);
    }

    @Test
    public void testTemplatesDelete() {
        var deleteElement = Templates.delete(City.class);
        assertNotNull(deleteElement);
    }

    @Test
    public void testTemplatesDeleteWithAlias() {
        var deleteElement = Templates.delete(City.class, "c");
        assertNotNull(deleteElement);
    }

    @Test
    public void testTemplatesTableWithAlias() {
        var tableElement = table(City.class, "c");
        assertNotNull(tableElement);
    }

    @Test
    public void testTemplatesParamWithName() {
        var paramElement = param("name", "test");
        assertNotNull(paramElement);
    }

    @Test
    public void testTemplatesParamWithConverter() {
        var paramElement = Templates.param("test", (String s) -> s.toUpperCase());
        assertNotNull(paramElement);
    }

    @Test
    public void testTemplatesParamWithNameAndConverter() {
        var paramElement = Templates.param("name", "test", (String s) -> s.toUpperCase());
        assertNotNull(paramElement);
    }

    @Test
    public void testTemplatesUnsafe() {
        var unsafeElement = Templates.unsafe("raw sql");
        assertNotNull(unsafeElement);
    }

    @Test
    public void testTemplatesSubqueryFromBuilder() {
        var builder = orm.selectFrom(City.class);
        var subqueryElement = Templates.subquery(builder, false);
        assertNotNull(subqueryElement);
    }

    @Test
    public void testTemplatesSubqueryFromTemplate() {
        var subqueryElement = Templates.subquery(RAW."SELECT 1 FROM \{City.class}", false);
        assertNotNull(subqueryElement);
    }

    // ORMTemplate factory overloads

    @Test
    public void testFactoryOfDataSourceWithDecorator() {
        ORMTemplate template = ORMTemplate.of(dataSource, d -> d);
        assertNotNull(template);
        assertEquals(6, template.entity(City.class).count());
    }

    @Test
    public void testFactoryOfConnectionWithDecorator() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ORMTemplate template = ORMTemplate.of(connection, d -> d);
            assertNotNull(template);
        }
    }

    // SubqueryTemplate

    @Test
    public void testSubqueryWithTemplate() {
        var subquery = orm.subquery(City.class, RAW."\{City.class}.id");
        assertNotNull(subquery);
    }

    // Query - executeBatch

    @Test
    public void testQueryExecuteBatch() {
        // executeBatch via Query (wraps a single-statement batch)
        var bindVars = orm.createBindVars();
        Query query = orm.query(RAW."INSERT INTO \{City.class} VALUES \{values(bindVars)}");
        try (PreparedQuery prepared = query.prepare()) {
            prepared.addBatch(new City(null, "BatchExec1"));
            int[] results = prepared.executeBatch();
            assertTrue(results.length > 0);
        }
    }

    // Query - getResultCount from raw SQL

    @Test
    public void testQueryGetResultCountFromTable() {
        // getResultCount counts result rows; a COUNT(*) query returns 1 row
        long rowCount = orm.query(RAW."SELECT COUNT(*) FROM \{table(City.class)}")
                .getResultCount();
        assertEquals(1, rowCount);
    }

    // ORMTemplate - validateSchema (no args)

    @Test
    public void testValidateSchemaNoArgs() {
        List<String> errors = orm.validateSchema();
        assertNotNull(errors);
    }

    @Test
    public void testValidateSchemaOrThrowNoArgs() {
        assertDoesNotThrow(() -> orm.validateSchemaOrThrow());
    }

    // ORMTemplate factory overloads with StormConfig

    @Test
    public void testFactoryOfDataSourceWithStormConfig() {
        var config = st.orm.StormConfig.defaults();
        ORMTemplate template = ORMTemplate.of(dataSource, config);
        assertNotNull(template);
        assertEquals(6, template.entity(City.class).count());
    }

    @Test
    public void testFactoryOfConnectionWithStormConfig() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var config = st.orm.StormConfig.defaults();
            ORMTemplate template = ORMTemplate.of(connection, config);
            assertNotNull(template);
        }
    }

    @Test
    public void testFactoryOfDataSourceWithStormConfigAndDecorator() {
        var config = st.orm.StormConfig.defaults();
        ORMTemplate template = ORMTemplate.of(dataSource, config, decorator -> decorator);
        assertNotNull(template);
        assertEquals(6, template.entity(City.class).count());
    }

    @Test
    public void testFactoryOfConnectionWithStormConfigAndDecorator() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var config = st.orm.StormConfig.defaults();
            ORMTemplate template = ORMTemplate.of(connection, config, decorator -> decorator);
            assertNotNull(template);
        }
    }

    // Templates - additional static helper methods

    @Test
    public void testTemplatesSelectWithMode() {
        var element = select(City.class, st.orm.SelectMode.DECLARED);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesSetWithFieldsCollection() {
        var city = new City(1, "Test");
        var element = set(city, List.of());
        assertNotNull(element);
    }

    @Test
    public void testTemplatesSetBindVarsWithFieldsCollection() {
        var bindVars = orm.createBindVars();
        var element = set(bindVars, List.of());
        assertNotNull(element);
    }

    @Test
    public void testTemplatesAliasWithResolveScope() {
        var element = Templates.alias(City.class, st.orm.ResolveScope.CASCADE);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesColumn() {
        var element = Templates.column(st.orm.template.model.City_.name);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesColumnWithResolveScope() {
        var element = Templates.column(st.orm.template.model.City_.name, st.orm.ResolveScope.CASCADE);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesBindVar() {
        var bindVars = orm.createBindVars();
        var element = Templates.bindVar(bindVars, city -> ((City) city).name());
        assertNotNull(element);
    }

    @Test
    public void testTemplatesParamNamedString() {
        var element = param("myParam", "value");
        assertNotNull(element);
    }

    @Test
    public void testTemplatesParamDateWithDateType() {
        var element = param(new java.util.Date(), st.orm.TemporalType.DATE);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesParamCalendarWithDateType() {
        var element = param(java.util.Calendar.getInstance(), st.orm.TemporalType.DATE);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesParamNamedDateWithTemporalType() {
        var element = Templates.param("myDate", new java.util.Date(), st.orm.TemporalType.TIMESTAMP);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesParamNamedCalendarWithTemporalType() {
        var element = Templates.param("myCal", java.util.Calendar.getInstance(), st.orm.TemporalType.TIMESTAMP);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesSubqueryWithCorrelation() {
        var subquery = orm.subquery(City.class, RAW."1");
        var element = Templates.subquery(subquery, true);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesWhereWithRecordInstance() {
        var city = new City(1, "Test");
        var element = where(city);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesWhereWithBindVarsInstance() {
        var bindVars = orm.createBindVars();
        var element = where(bindVars);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesWhereWithIterableOfRecords() {
        var element = where(List.of(new City(1, "Test"), new City(2, "Test2")));
        assertNotNull(element);
    }

    @Test
    public void testTemplatesValuesWithIgnoreAutoGenerate() {
        var element = values(new City(1, "Test"), true);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesValuesIterableWithIgnoreAutoGenerate() {
        var element = values(List.of(new City(1, "Test")), true);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesValuesBindVarsWithIgnoreAutoGenerate() {
        var bindVars = orm.createBindVars();
        var element = values(bindVars, true);
        assertNotNull(element);
    }

    @Test
    public void testTemplatesInsertWithIgnoreAutoGenerate() {
        var element = insert(City.class, true);
        assertNotNull(element);
    }

    // ORMTemplate - SubqueryTemplate default methods on ORMTemplate

    @Test
    public void testORMSubqueryDefaultSingleArg() {
        var subquery = orm.subquery(City.class);
        assertNotNull(subquery);
    }

    @Test
    public void testORMSubqueryDefaultTwoArgs() {
        var subquery = orm.subquery(City.class, City.class);
        assertNotNull(subquery);
    }

    // validateSchema with specific types

    @Test
    public void testValidateSchemaWithTypes() {
        List<String> errors = orm.validateSchema(List.of(City.class));
        assertNotNull(errors);
    }

    @Test
    public void testValidateSchemaOrThrowWithTypes() {
        assertDoesNotThrow(() -> orm.validateSchemaOrThrow(List.of(City.class)));
    }

    // Query - typed getResult methods

    @Test
    public void testQueryGetResultStreamWithType() {
        try (Stream<City> stream = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultStream(City.class)) {
            assertEquals(6, stream.count());
        }
    }

    @Test
    public void testQueryGetResultListWithType() {
        List<City> cities = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultList(City.class);
        assertEquals(6, cities.size());
    }

    @Test
    public void testQueryGetSingleResultWithType() {
        City city = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{1}")
                .getSingleResult(City.class);
        assertNotNull(city);
        assertEquals(1, city.id());
    }

    @Test
    public void testQueryGetOptionalResultWithType() {
        Optional<City> city = orm.query(RAW."SELECT \{City.class} FROM \{City.class} WHERE \{City.class}.id = \{1}")
                .getOptionalResult(City.class);
        assertTrue(city.isPresent());
    }

    @Test
    public void testQueryGetResultCountFromAllRows() {
        long count = orm.query(RAW."SELECT \{City.class} FROM \{City.class}")
                .getResultCount();
        assertEquals(6, count);
    }
}
