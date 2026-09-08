package st.orm.template;

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.template.Transactions.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.repository.EntityRepository;
import st.orm.repository.ProjectionRepository;
import st.orm.template.model.Address;
import st.orm.template.model.City;
import st.orm.template.model.City_;
import st.orm.template.model.Owner;
import st.orm.template.model.OwnerView;
import st.orm.template.model.OwnerView_;
import st.orm.template.model.Pet;
import st.orm.template.model.PetType;
import st.orm.template.model.Pet_;
import st.orm.template.model.Visit;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class RepositoryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ORMTemplate orm;

    // Repository proxy

    interface CityRepository extends EntityRepository<City, Integer> {
    }

    interface OwnerViewRepository extends ProjectionRepository<OwnerView, Integer> {
    }

    @SuppressWarnings("rawtypes")
    interface RawEntityRepository extends EntityRepository {
    }

    @SuppressWarnings("rawtypes")
    interface RawProjectionRepository extends ProjectionRepository {
    }

    @Test
    public void testRepositoryProxy() {
        CityRepository cityRepo = orm.repository(CityRepository.class);
        assertNotNull(cityRepo);
        List<City> cities = cityRepo.findAll();
        assertEquals(6, cities.size());
    }

    @Test
    public void testRepositoryProxyEquals() {
        CityRepository repo1 = orm.repository(CityRepository.class);
        CityRepository repo2 = orm.repository(CityRepository.class);
        // Proxy equals is identity-based
        assertFalse(repo1.equals(repo2));
        assertTrue(repo1.equals(repo1));
    }

    @Test
    public void testRepositoryProxyHashCode() {
        CityRepository repo = orm.repository(CityRepository.class);
        // hashCode should return the System.identityHashCode for proxy instances.
        int hash = repo.hashCode();
        assertEquals(System.identityHashCode(repo), hash);
    }

    @Test
    public void testRepositoryProxyToString() {
        CityRepository repo = orm.repository(CityRepository.class);
        String str = repo.toString();
        assertNotNull(str);
        assertTrue(str.contains("CityRepository"));
    }

    @Test
    public void testRepositoryProxyOrm() {
        CityRepository repo = orm.repository(CityRepository.class);
        ORMTemplate repoOrm = repo.orm();
        assertNotNull(repoOrm);
    }

    @Test
    public void testProjectionRepositoryProxy() {
        OwnerViewRepository repo = orm.repository(OwnerViewRepository.class);
        assertNotNull(repo);
        List<OwnerView> views = repo.findAll();
        assertEquals(10, views.size());
    }

    // Complex entity operations with FK relationships

    @Test
    public void testOwnerWithAddress() {
        EntityRepository<Owner, Integer> owners = orm.entity(Owner.class);
        List<Owner> allOwners = owners.findAll();
        assertEquals(10, allOwners.size());
        // Verify FK resolution
        Owner first = allOwners.get(0);
        assertNotNull(first.address());
        assertNotNull(first.address().city());
        assertNotNull(first.address().city().name());
    }

    @Test
    public void testPetWithForeignKeys() {
        EntityRepository<Pet, Integer> pets = orm.entity(Pet.class);
        List<Pet> allPets = pets.findAll();
        assertEquals(13, allPets.size());
        // Verify FK resolution
        Pet first = allPets.get(0);
        assertNotNull(first.type());
        assertNotNull(first.type().name());
    }

    @Test
    public void testVisitWithNestedForeignKeys() {
        EntityRepository<Visit, Integer> visits = orm.entity(Visit.class);
        List<Visit> allVisits = visits.findAll();
        assertEquals(14, allVisits.size());
        // Verify nested FK resolution
        Visit first = allVisits.get(0);
        assertNotNull(first.pet());
        assertNotNull(first.pet().type());
    }

    // OwnerView - Projection operations

    @Test
    public void testOwnerViewFindAllVerifyContent() {
        ProjectionRepository<OwnerView, Integer> views = orm.projection(OwnerView.class);
        List<OwnerView> all = views.findAll();
        // Verify data integrity
        OwnerView betty = all.stream().filter(v -> v.firstName().equals("Betty")).findFirst().orElseThrow();
        assertEquals("Davis", betty.lastName());
        assertNotNull(betty.address());
    }

    // Scroll methods

    @Test
    public void testScrollBasic() {
        Window<City> window = orm.entity(City.class).select().scroll(2);
        assertEquals(2, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollLastPage() {
        Window<City> window = orm.entity(City.class).select().scroll(100);
        assertFalse(window.hasNext());
    }

    @Test
    public void testScrollInvalidSize() {
        assertThrows(IllegalArgumentException.class, () ->
                orm.entity(City.class).select().scroll(0));
        assertThrows(IllegalArgumentException.class, () ->
                orm.entity(City.class).select().scroll(-1));
    }

    // EntityRepository - select with custom select type

    // StringTemplates helper methods coverage

    @Test
    public void testSelectTemplateHelper() {
        List<City> cities = orm.query(RAW."SELECT \{Templates.select(City.class)} FROM \{Templates.from(City.class, true)}")
                .getResultList(City.class);
        assertEquals(6, cities.size());
    }

    @Test
    public void testTableTemplateHelper() {
        var tableElement = Templates.table(City.class);
        assertNotNull(tableElement);
    }

    @Test
    public void testAliasTemplateHelper() {
        var aliasElement = Templates.alias(City.class);
        assertNotNull(aliasElement);
    }

    @Test
    public void testUnsafeTemplateHelper() {
        var unsafeElement = Templates.unsafe("some raw SQL");
        assertNotNull(unsafeElement);
    }

    @Test
    public void testSubqueryTemplateHelper() {
        var subquery = orm.subquery(City.class, RAW."1");
        var subqueryElement = Templates.subquery(subquery, false);
        assertNotNull(subqueryElement);
    }

    @Test
    public void testParamWithTemporalType() {
        var dateParam = Templates.param(new java.util.Date(), st.orm.TemporalType.TIMESTAMP);
        assertNotNull(dateParam);
    }

    @Test
    public void testParamWithCalendar() {
        var calendarParam = Templates.param(java.util.Calendar.getInstance(), st.orm.TemporalType.TIMESTAMP);
        assertNotNull(calendarParam);
    }

    // Coverage for Model and Column

    @Test
    public void testOwnerModelColumns() {
        Model<Owner, ?> model = orm.model(Owner.class);
        List<Column> columns = model.columns();
        // Owner has: id, firstName, lastName, address (which expands to address + cityId), telephone, version
        assertFalse(columns.isEmpty());
        assertTrue(columns.size() >= 5);
    }

    @Test
    public void testOwnerModelDeclaredColumns() {
        Model<Owner, ?> model = orm.model(Owner.class);
        List<Column> declaredColumns = model.declaredColumns();
        // Declared columns: id, firstName, lastName, address (composite), telephone, version
        assertFalse(declaredColumns.isEmpty());
    }

    @Test
    public void testColumnAttributes() {
        Model<Owner, ?> model = orm.model(Owner.class);
        for (Column column : model.columns()) {
            assertNotNull(column.name());
            assertNotNull(column.type());
            assertNotNull(column.generation());
            assertNotNull(column.sequence());
            // Just exercise all getters
            column.primaryKey();
            column.foreignKey();
            column.nullable();
            column.insertable();
            column.updatable();
            column.version();
            column.ref();
            column.index();
            column.metamodel();
        }
    }

    @Test
    public void testOwnerModelForEachValue() throws Exception {
        Model<Owner, Integer> model = (Model<Owner, Integer>) (Model<?, ?>) orm.model(Owner.class);
        Owner owner = orm.entity(Owner.class).getById(1);
        model.forEachValue(model.columns(), owner, (column, value) -> {
            assertNotNull(column.name());
        });
    }

    @Test
    public void testOwnerModelValues() throws Exception {
        Model<Owner, Integer> model = (Model<Owner, Integer>) (Model<?, ?>) orm.model(Owner.class);
        Owner owner = orm.entity(Owner.class).getById(1);
        var values = model.values(owner);
        assertFalse(values.isEmpty());
    }

    // QueryBuilder - executeUpdate (delete)

    @Test
    public void testQueryBuilderExecuteUpdate() {
        var localOrm = ORMTemplate.of(dataSource);
        EntityRepository<City, Integer> cities = localOrm.entity(City.class);
        cities.insertAndFetch(new City(null, "ToDeleteViaBuilder"));
        int deleted = localOrm.deleteFrom(City.class)
                .where(RAW."\{City.class}.name = \{"ToDeleteViaBuilder"}")
                .executeUpdate();
        assertEquals(1, deleted);
    }

    // SubqueryTemplate

    // WhereBuilder - subquery

    // EntityRepository - Metamodel.Key scroll default methods

    @Test
    public void testEntityScrollByKey() {
        Window<City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, 3));
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testEntityWindows() {
        var cities = orm.entity(City.class);
        List<Window<City>> windows = cities.windows(4).toList();
        assertEquals(cities.count(), windows.stream().mapToLong(window -> window.content().size()).sum());
        assertTrue(windows.stream().allMatch(window -> window.content().size() <= 4));
        assertFalse(windows.getLast().hasNext());
        var ids = windows.stream().flatMap(window -> window.content().stream()).map(City::id).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    public void testEntityWindowsResumeFromToken() {
        var cities = orm.entity(City.class);
        Window<City> first = cities.windows(2).findFirst().orElseThrow();
        var rest = cities.windows(first.next()).toList();
        var restIds = rest.stream().flatMap(window -> window.content().stream()).map(City::id).toList();
        assertEquals(cities.count() - 2, restIds.size());
        assertTrue(restIds.stream().allMatch(id -> id > first.content().getLast().id()));
    }

    @Test
    public void testStatementWhileStreamIsOpenInTransactionIsRefused() {
        transaction(transaction -> {
            var cities = orm.entity(City.class);
            try (var stream = cities.select().getResultStream()) {
                var exception = assertThrows(PersistenceException.class, () -> stream.forEach(city -> cities.count()));
                assertTrue(exception.getMessage().contains("result stream is still open"), exception.getMessage());
            }
            // The stream is closed, so the connection is free again.
            assertTrue(cities.count() > 0);
            return null;
        });
    }

    @Test
    public void testWindowsInTransactionAllowStatementsPerWindow() {
        transaction(transaction -> {
            var cities = orm.entity(City.class);
            // A query and a batched write per window, on the transaction's connection.
            cities.windows(2).forEach(window -> {
                assertTrue(cities.count() > 0);
                cities.update(window.content().stream()
                        .map(city -> new City(city.id(), city.name() + " (windowed)"))
                        .toList());
            });
            assertTrue(cities.select().getResultList().stream().allMatch(city -> city.name().endsWith(" (windowed)")));
            transaction.setRollbackOnly();
            return null;
        });
        assertTrue(orm.entity(City.class).select().getResultList().stream().noneMatch(city -> city.name().endsWith(" (windowed)")));
    }

    @Test
    public void testEntityScrollBeforeByKey() {
        Window<City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollBeforeRefByKey() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollAfterByKey() {
        Window<City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 2, 3));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollBeforeByKeyAndValue() {
        Window<City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 5, 3).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollAfterRefByKey() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 2, 3));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollBeforeRefByKeyAndValue() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 5, 3).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollByKeyAndSort() {
        Window<City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, City_.name, 3));
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollBeforeByKeyAndSort() {
        Window<City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, City_.name, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollBeforeRefByKeyAndSort() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, City_.name, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollRefByKeyAndSort() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, City_.name, 3));
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollAfterByKeyAndSort() {
        Window<City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 2, City_.name, "A", 3));
        assertNotNull(window);
    }

    @Test
    public void testEntityScrollBeforeByKeyAndSortAndValue() {
        Window<City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 5, City_.name, "Z", 3).backward());
        assertNotNull(window);
    }

    @Test
    public void testEntityScrollAfterRefByKeyAndSort() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 2, City_.name, "A", 3));
        assertNotNull(window);
    }

    @Test
    public void testEntityScrollBeforeRefByKeyAndSortAndValue() {
        Window<Ref<City>> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 5, City_.name, "Z", 3).backward());
        assertNotNull(window);
    }

    // ProjectionRepository - Metamodel.Key scroll default methods

    @Test
    public void testProjectionScrollByKey() {
        Window<OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, 5));
        assertEquals(5, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testProjectionWindows() {
        var ownerViews = orm.projection(OwnerView.class);
        List<Window<OwnerView>> windows = ownerViews.windows(5).toList();
        assertEquals(2, windows.size());
        assertEquals(ownerViews.count(), windows.stream().mapToLong(window -> window.content().size()).sum());
        assertFalse(windows.getLast().hasNext());
    }

    @Test
    public void testProjectionScrollBeforeByKey() {
        Window<OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, 5).backward());
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollRefByKey() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 5));
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollAfterByKey() {
        var window = orm.projection(OwnerView.class).select().scroll(Scrollable.of(OwnerView_.id, 3, 5));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testProjectionScrollBeforeByKeyAndValue() {
        var window = orm.projection(OwnerView.class).select().scroll(Scrollable.of(OwnerView_.id, 8, 5).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testProjectionScrollAfterRefByKey() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 3, 5));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyAndValue() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 8, 5).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyInitial() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 5).backward());
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollByKeyAndSort() {
        Window<OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5));
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollRefByKeyAndSort() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5));
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollBeforeByKeyAndSort() {
        Window<OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5).backward());
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyAndSort() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5).backward());
        assertEquals(5, window.content().size());
    }

    // ProjectionRepository - composite scrolling with sort

    @Test
    public void testProjectionScrollAfterByKeyAndSort() {
        var window = orm.projection(OwnerView.class).select().scroll(Scrollable.of(OwnerView_.id, 3, OwnerView_.firstName, "A", 5));
        assertNotNull(window);
    }

    @Test
    public void testProjectionScrollBeforeByKeyAndSortAndValue() {
        var window = orm.projection(OwnerView.class).select().scroll(Scrollable.of(OwnerView_.id, 8, OwnerView_.firstName, "Z", 5).backward());
        assertNotNull(window);
    }

    @Test
    public void testProjectionScrollAfterRefByKeyAndSort() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 3, OwnerView_.firstName, "A", 5));
        assertNotNull(window);
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyAndSortAndValue() {
        Window<Ref<OwnerView>> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 8, OwnerView_.firstName, "Z", 5).backward());
        assertNotNull(window);
    }

    // Page pagination

    @Test
    public void testEntityPageFirstPage() {
        Page<City> firstPage = orm.entity(City.class).page(0, 3);
        assertEquals(3, firstPage.content().size());
        assertEquals(6, firstPage.totalCount());
        assertEquals(2, firstPage.totalPages());
        assertTrue(firstPage.hasNext());
        assertFalse(firstPage.hasPrevious());
    }

    @Test
    public void testEntityPageSecondPage() {
        Page<City> secondPage = orm.entity(City.class).page(1, 3);
        assertEquals(3, secondPage.content().size());
        assertEquals(6, secondPage.totalCount());
        assertFalse(secondPage.hasNext());
        assertTrue(secondPage.hasPrevious());
    }

    @Test
    public void testEntityPageWithPageable() {
        Pageable pageable = Pageable.ofSize(2);
        Page<City> firstPage = orm.entity(City.class).page(pageable);
        assertEquals(2, firstPage.content().size());
        assertEquals(6, firstPage.totalCount());
        assertEquals(0, firstPage.pageNumber());
        assertTrue(firstPage.hasNext());

        Page<City> secondPage = orm.entity(City.class).page(firstPage.nextPageable());
        assertEquals(2, secondPage.content().size());
        assertEquals(1, secondPage.pageNumber());
        assertTrue(secondPage.hasNext());
        assertTrue(secondPage.hasPrevious());
    }

    @Test
    public void testEntityPageWithSortOrder() {
        Pageable pageable = Pageable.ofSize(3).sortBy(City_.name);
        Page<City> firstPage = orm.entity(City.class).page(pageable);
        assertEquals(3, firstPage.content().size());
        String firstName = firstPage.content().getFirst().name();
        for (City city : firstPage.content()) {
            assertTrue(firstName.compareTo(city.name()) <= 0);
        }
    }

    @Test
    public void testEntityPageBeyondLastPage() {
        Page<City> emptyPage = orm.entity(City.class).page(100, 3);
        assertEquals(0, emptyPage.content().size());
        assertEquals(6, emptyPage.totalCount());
    }

    @Test
    public void testEntityPageRef() {
        Page<Ref<City>> refPage = orm.entity(City.class).pageRef(0, 3);
        assertEquals(3, refPage.content().size());
        assertEquals(6, refPage.totalCount());
    }

    @Test
    public void testEntityPageRefWithPageable() {
        Pageable pageable = Pageable.ofSize(3);
        Page<Ref<City>> refPage = orm.entity(City.class).pageRef(pageable);
        assertEquals(3, refPage.content().size());
        assertEquals(6, refPage.totalCount());
        assertTrue(refPage.hasNext());
    }

    @Test
    public void testProjectionPageFirstPage() {
        Page<OwnerView> firstPage = orm.projection(OwnerView.class).page(0, 5);
        assertEquals(5, firstPage.content().size());
        assertEquals(10, firstPage.totalCount());
        assertTrue(firstPage.hasNext());
    }

    @Test
    public void testProjectionPageWithPageable() {
        Pageable pageable = Pageable.ofSize(5);
        Page<OwnerView> firstPage = orm.projection(OwnerView.class).page(pageable);
        assertEquals(5, firstPage.content().size());
        assertEquals(10, firstPage.totalCount());
        assertEquals(0, firstPage.pageNumber());
        assertTrue(firstPage.hasNext());
    }

    @Test
    public void testProjectionPageRef() {
        Page<Ref<OwnerView>> refPage = orm.projection(OwnerView.class).pageRef(0, 5);
        assertEquals(5, refPage.content().size());
        assertEquals(10, refPage.totalCount());
    }

    @Test
    public void testProjectionFindAllRef() {
        List<Ref<OwnerView>> allRefs = orm.projection(OwnerView.class).findAllRef();
        assertEquals(10, allRefs.size());
    }

    // EntityRepository - additional default methods for completeness

    @Test
    public void testEntityFindByRef() {
        var ref = orm.entity(City.class).ref(1);
        Optional<City> city = orm.entity(City.class).findByRef(ref);
        assertTrue(city.isPresent());
    }

    @Test
    public void testEntityGetByRef() {
        var ref = orm.entity(City.class).ref(1);
        City city = orm.entity(City.class).getByRef(ref);
        assertNotNull(city);
        assertEquals(1, city.id());
    }

    @Test
    public void testEntityFindAllByRef() {
        var ref1 = orm.entity(City.class).ref(1);
        var ref2 = orm.entity(City.class).ref(2);
        List<City> cities = orm.entity(City.class).findAllByRef(List.of(ref1, ref2));
        assertEquals(2, cities.size());
    }

    @Test
    public void testEntityRemove() {
        var localOrm = ORMTemplate.of(dataSource);
        var repo = localOrm.entity(City.class);
        var inserted = repo.insertAndFetch(new City(null, "ToDeleteEntity"));
        repo.remove(inserted);
        assertFalse(repo.findById(inserted.id()).isPresent());
    }

    @Test
    public void testEntityRemoveByRef() {
        var localOrm = ORMTemplate.of(dataSource);
        var repo = localOrm.entity(City.class);
        var inserted = repo.insertAndFetch(new City(null, "ToDeleteByRef"));
        repo.removeByRef(repo.ref(inserted.id()));
        assertFalse(repo.findById(inserted.id()).isPresent());
    }

    @Test
    public void testEntityRemoveByRefIterable() {
        var localOrm = ORMTemplate.of(dataSource);
        var repo = localOrm.entity(City.class);
        var inserted1 = repo.insertAndFetch(new City(null, "ToDeleteRef1"));
        var inserted2 = repo.insertAndFetch(new City(null, "ToDeleteRef2"));
        repo.removeByRef(List.of(repo.ref(inserted1.id()), repo.ref(inserted2.id())));
        assertFalse(repo.findById(inserted1.id()).isPresent());
        assertFalse(repo.findById(inserted2.id()).isPresent());
    }

    // ProjectionRepository - additional default methods

    @Test
    public void testProjectionFindByRef() {
        var ref = orm.projection(OwnerView.class).ref(1);
        Optional<OwnerView> view = orm.projection(OwnerView.class).findByRef(ref);
        assertTrue(view.isPresent());
    }

    @Test
    public void testProjectionGetByRef() {
        var ref = orm.projection(OwnerView.class).ref(1);
        OwnerView view = orm.projection(OwnerView.class).getByRef(ref);
        assertNotNull(view);
    }

    @Test
    public void testProjectionFindAllByRef() {
        var ref1 = orm.projection(OwnerView.class).ref(1);
        var ref2 = orm.projection(OwnerView.class).ref(2);
        List<OwnerView> views = orm.projection(OwnerView.class).findAllByRef(List.of(ref1, ref2));
        assertEquals(2, views.size());
    }

    @Test
    public void testProjectionFindById() {
        Optional<OwnerView> view = orm.projection(OwnerView.class).findById(1);
        assertTrue(view.isPresent());
    }

    @Test
    public void testProjectionGetById() {
        OwnerView view = orm.projection(OwnerView.class).getById(1);
        assertNotNull(view);
    }

    @Test
    public void testProjectionFindAllById() {
        List<OwnerView> views = orm.projection(OwnerView.class).findAllById(List.of(1, 2, 3));
        assertEquals(3, views.size());
    }

    @Test
    public void testProjectionCount() {
        long count = orm.projection(OwnerView.class).count();
        assertEquals(10, count);
    }

    @Test
    public void testProjectionSelectCount() {
        long count = orm.projection(OwnerView.class).selectCount().getSingleResult();
        assertEquals(10, count);
    }

    @Test
    public void testProjectionSelectWithTemplate() {
        List<String> names = orm.projection(OwnerView.class).select(String.class, RAW."\{OwnerView.class}.first_name")
                .getResultList();
        assertEquals(10, names.size());
    }

    // Repository proxy - dispatch EntityRepository method with parameters (L187-189 toShortSignature)

    @Test
    public void testRepositoryProxyFindById() {
        CityRepository cityRepo = orm.repository(CityRepository.class);
        // findById has 1 parameter, exercising toShortSignature loop body (L187-189).
        Optional<City> city = cityRepo.findById(1);
        assertTrue(city.isPresent());
        assertEquals(1, city.get().id());
    }

    @Test
    public void testRepositoryProxyMultiParamMethod() {
        CityRepository cityRepo = orm.repository(CityRepository.class);
        // select(Class, StringTemplate) has 2 parameters, covering toShortSignature separator (L188).
        List<String> names = cityRepo.select(String.class, RAW."\{City.class}.name").getResultList();
        assertEquals(6, names.size());
    }

    @Test
    public void testRawEntityRepositoryThrows() {
        // Raw (non-parameterized) EntityRepository triggers L214: "Could not determine entity class".
        assertThrows(IllegalArgumentException.class, () -> orm.repository(RawEntityRepository.class));
    }

    @Test
    public void testRawProjectionRepositoryThrows() {
        // Raw (non-parameterized) ProjectionRepository triggers L241: "Could not determine projection class".
        assertThrows(IllegalArgumentException.class, () -> orm.repository(RawProjectionRepository.class));
    }

    // Repository proxy - projection proxy with findById (dispatch through ProjectionRepository)

    @Test
    public void testProjectionProxyFindById() {
        OwnerViewRepository viewRepo = orm.repository(OwnerViewRepository.class);
        Optional<OwnerView> view = viewRepo.findById(1);
        assertTrue(view.isPresent());
    }

    // Field-based finder methods (default methods on EntityRepository/ProjectionRepository).

    @Test
    public void testFindByField() {
        var cities = orm.entity(City.class);
        Optional<City> city = cities.findBy(City_.name, "Madison");
        assertTrue(city.isPresent());
        assertEquals("Madison", city.get().name());
        assertTrue(cities.findBy(City_.name, "Nonexistent").isEmpty());
    }

    @Test
    public void testGetByField() {
        var cities = orm.entity(City.class);
        City city = cities.getBy(City_.name, "Madison");
        assertEquals("Madison", city.name());
    }

    @Test
    public void testFindAllByField() {
        var cities = orm.entity(City.class);
        List<City> matches = cities.findAllBy(City_.name, "Madison");
        assertEquals(1, matches.size());
    }

    @Test
    public void testFindAllByFieldMultipleValues() {
        var cities = orm.entity(City.class);
        List<City> matches = cities.findAllBy(City_.name, List.of("Madison", "Monona"));
        assertEquals(2, matches.size());
    }

    @Test
    public void testFindAllByFieldRef() {
        var pets = orm.entity(Pet.class);
        List<Pet> petsOfOwner = pets.findAllBy(Pet_.owner, Ref.of(Owner.class, 1));
        assertFalse(petsOfOwner.isEmpty());
        assertTrue(petsOfOwner.stream().allMatch(pet -> pet.owner().id() == 1));
    }

    @Test
    public void testFindAllByRefField() {
        var pets = orm.entity(Pet.class);
        List<Pet> matches = pets.findAllByRef(Pet_.owner,
                List.of(Ref.of(Owner.class, 1), Ref.of(Owner.class, 6)));
        assertEquals(3, matches.size());
    }

    @Test
    public void testFindRefByField() {
        var cities = orm.entity(City.class);
        Optional<Ref<City>> ref = cities.findRefBy(City_.name, "Madison");
        assertTrue(ref.isPresent());
        assertEquals("Madison", ref.get().fetch().name());
    }

    @Test
    public void testFindAllRefByField() {
        var pets = orm.entity(Pet.class);
        List<Ref<Pet>> refs = pets.findAllRefBy(Pet_.owner, Ref.of(Owner.class, 6));
        assertEquals(2, refs.size());
    }

    @Test
    public void testGetRefByField() {
        var cities = orm.entity(City.class);
        Ref<City> ref = cities.getRefBy(City_.name, "Madison");
        assertNotNull(ref);
        assertEquals("Madison", ref.fetch().name());
    }

    @Test
    public void testCountByField() {
        var pets = orm.entity(Pet.class);
        assertEquals(2, pets.countBy(Pet_.owner, Ref.of(Owner.class, 6)));
        var cities = orm.entity(City.class);
        assertEquals(1, cities.countBy(City_.name, "Madison"));
    }

    @Test
    public void testExistsByField() {
        var cities = orm.entity(City.class);
        assertTrue(cities.existsBy(City_.name, "Madison"));
        assertFalse(cities.existsBy(City_.name, "Nonexistent"));
    }

    @Test
    public void testRemoveAllByField() {
        var cities = orm.entity(City.class);
        cities.insertAndFetch(new City(null, "ToRemoveByField"));
        int removed = cities.removeAllBy(City_.name, "ToRemoveByField");
        assertEquals(1, removed);
        assertFalse(cities.existsBy(City_.name, "ToRemoveByField"));
    }

    @Test
    public void testFieldFinderThroughRepositoryProxy() {
        // Default interface methods must dispatch correctly through the repository proxy.
        CityRepository cityRepo = orm.repository(CityRepository.class);
        Optional<City> city = cityRepo.findBy(City_.name, "Madison");
        assertTrue(city.isPresent());
        assertEquals(1, cityRepo.countBy(City_.name, "Madison"));
        assertTrue(cityRepo.existsBy(City_.name, "Madison"));
    }

    @Test
    public void testProjectionFieldFinder() {
        var ownerViews = orm.projection(OwnerView.class);
        List<OwnerView> views = ownerViews.findAllBy(OwnerView_.firstName, "George");
        assertFalse(views.isEmpty());
        assertTrue(views.stream().allMatch(view -> view.firstName().equals("George")));
        assertTrue(ownerViews.existsBy(OwnerView_.firstName, "George"));
    }

    // Field-based finder methods matching a referenced value (the Ref overloads on EntityRepository).

    @Test
    public void testFindByRefValue() {
        // Owner 1 has exactly one pet (Leo), so the single-result finder resolves; owner 3 has two, so the same
        // finder reports the ambiguity instead of picking one.
        var pets = orm.entity(Pet.class);
        Optional<Pet> pet = pets.findBy(Pet_.owner, Ref.of(Owner.class, 1));
        assertTrue(pet.isPresent());
        assertEquals("Leo", pet.get().name());
        assertTrue(pets.findBy(Pet_.owner, Ref.of(Owner.class, 999)).isEmpty());
        assertThrows(NonUniqueResultException.class, () -> pets.findBy(Pet_.owner, Ref.of(Owner.class, 3)));
    }

    @Test
    public void testGetByRefValue() {
        var pets = orm.entity(Pet.class);
        Pet pet = pets.getBy(Pet_.owner, Ref.of(Owner.class, 1));
        assertEquals("Leo", pet.name());
        assertThrows(NoResultException.class, () -> pets.getBy(Pet_.owner, Ref.of(Owner.class, 999)));
    }

    @Test
    public void testFindRefByRefValue() {
        var pets = orm.entity(Pet.class);
        Optional<Ref<Pet>> ref = pets.findRefBy(Pet_.owner, Ref.of(Owner.class, 1));
        assertTrue(ref.isPresent());
        assertEquals("Leo", ref.get().fetch().name());
        assertTrue(pets.findRefBy(Pet_.owner, Ref.of(Owner.class, 999)).isEmpty());
    }

    @Test
    public void testGetRefByRefValue() {
        var pets = orm.entity(Pet.class);
        Ref<Pet> ref = pets.getRefBy(Pet_.owner, Ref.of(Owner.class, 1));
        assertEquals("Leo", ref.fetch().name());
        assertThrows(NoResultException.class, () -> pets.getRefBy(Pet_.owner, Ref.of(Owner.class, 999)));
    }

    @Test
    public void testFindAllRefByFieldMultipleValues() {
        var cities = orm.entity(City.class);
        List<Ref<City>> refs = cities.findAllRefBy(City_.name, List.of("Madison", "Monona"));
        assertEquals(2, refs.size());
        assertTrue(refs.stream().map(Ref::fetch).map(City::name).toList().containsAll(List.of("Madison", "Monona")));
        assertTrue(cities.findAllRefBy(City_.name, List.of()).isEmpty());
    }

    @Test
    public void testFindAllRefByRefField() {
        // Owners 1 and 6 own one and two pets: three refs, and only refs to those pets.
        var pets = orm.entity(Pet.class);
        List<Ref<Pet>> refs = pets.findAllRefByRef(Pet_.owner, List.of(Ref.of(Owner.class, 1), Ref.of(Owner.class, 6)));
        assertEquals(3, refs.size());
        assertTrue(refs.stream().map(Ref::fetch).allMatch(pet -> List.of(1, 6).contains(pet.owner().id())));
    }

    @Test
    public void testExistsByRefValue() {
        var pets = orm.entity(Pet.class);
        assertTrue(pets.existsBy(Pet_.owner, Ref.of(Owner.class, 1)));
        assertFalse(pets.existsBy(Pet_.owner, Ref.of(Owner.class, 999)));
    }

    @Test
    public void testRemoveAllByRefValue() {
        var pets = orm.entity(Pet.class);
        var owners = orm.entity(Owner.class);
        City city = orm.entity(City.class).getById(1);
        Owner owner = owners.insertAndFetch(new Owner(null, "Removable", "Owner", new Address("1 Main St.", city), null, 0));
        // Pet type 1, not 0: an identity-generated key of 0 is the value Storm reads as "not yet persisted", so a
        // reference to it is rejected before it reaches the database.
        PetType petType = orm.entity(PetType.class).getById(1);
        pets.insert(new Pet(null, "Removable", LocalDate.of(2024, 1, 1), petType, owner));
        pets.insert(new Pet(null, "Removable Too", LocalDate.of(2024, 1, 2), petType, owner));
        int removed = pets.removeAllBy(Pet_.owner, Ref.of(Owner.class, owner.id()));
        assertEquals(2, removed);
        assertFalse(pets.existsBy(Pet_.owner, Ref.of(Owner.class, owner.id())));
        // Nothing left to remove; the count reports that rather than failing.
        assertEquals(0, pets.removeAllBy(Pet_.owner, Ref.of(Owner.class, owner.id())));
    }

    @Test
    public void testRemoveAllByFieldMultipleValues() {
        var cities = orm.entity(City.class);
        cities.insert(new City(null, "RemoveMultiA"));
        cities.insert(new City(null, "RemoveMultiB"));
        int removed = cities.removeAllBy(City_.name, List.of("RemoveMultiA", "RemoveMultiB", "RemoveMultiMissing"));
        assertEquals(2, removed);
        assertFalse(cities.existsBy(City_.name, "RemoveMultiA"));
        assertFalse(cities.existsBy(City_.name, "RemoveMultiB"));
    }

    @Test
    public void testRemoveAllByRefField() {
        var pets = orm.entity(Pet.class);
        var owners = orm.entity(Owner.class);
        City city = orm.entity(City.class).getById(1);
        PetType petType = orm.entity(PetType.class).getById(1);
        Owner first = owners.insertAndFetch(new Owner(null, "First", "Removable", new Address("1 Main St.", city), null, 0));
        Owner second = owners.insertAndFetch(new Owner(null, "Second", "Removable", new Address("2 Main St.", city), null, 0));
        pets.insert(new Pet(null, "Of First", LocalDate.of(2024, 1, 1), petType, first));
        pets.insert(new Pet(null, "Of Second", LocalDate.of(2024, 1, 2), petType, second));
        int removed = pets.removeAllByRef(Pet_.owner, List.of(Ref.of(Owner.class, first.id()), Ref.of(Owner.class, second.id())));
        assertEquals(2, removed);
        assertFalse(pets.existsBy(Pet_.owner, Ref.of(Owner.class, first.id())));
        assertFalse(pets.existsBy(Pet_.owner, Ref.of(Owner.class, second.id())));
    }

    // Field-based finder methods on ProjectionRepository; the owner view's address carries the city reference.

    @Test
    public void testProjectionFindByField() {
        var ownerViews = orm.projection(OwnerView.class);
        Optional<OwnerView> view = ownerViews.findBy(OwnerView_.lastName, "Franklin");
        assertTrue(view.isPresent());
        assertEquals("George", view.get().firstName());
        assertTrue(ownerViews.findBy(OwnerView_.lastName, "Nobody").isEmpty());
        // Two owners share the last name Davis; a single-result finder must not pick one silently.
        assertThrows(NonUniqueResultException.class, () -> ownerViews.findBy(OwnerView_.lastName, "Davis"));
    }

    @Test
    public void testProjectionFindByRefValue() {
        // Sun Prairie (city 1) hosts owner 1 only; Madison (city 2) hosts four owners.
        var ownerViews = orm.projection(OwnerView.class);
        Optional<OwnerView> view = ownerViews.findBy(OwnerView_.address.city, Ref.of(City.class, 1));
        assertTrue(view.isPresent());
        assertEquals(1, view.get().id());
        assertTrue(ownerViews.findBy(OwnerView_.address.city, Ref.of(City.class, 999)).isEmpty());
        assertThrows(NonUniqueResultException.class,
                () -> ownerViews.findBy(OwnerView_.address.city, Ref.of(City.class, 2)));
    }

    @Test
    public void testProjectionGetByField() {
        var ownerViews = orm.projection(OwnerView.class);
        assertEquals("George", ownerViews.getBy(OwnerView_.lastName, "Franklin").firstName());
        assertThrows(NoResultException.class, () -> ownerViews.getBy(OwnerView_.lastName, "Nobody"));
        assertEquals(1, ownerViews.getBy(OwnerView_.address.city, Ref.of(City.class, 1)).id());
        assertThrows(NoResultException.class, () -> ownerViews.getBy(OwnerView_.address.city, Ref.of(City.class, 999)));
    }

    @Test
    public void testProjectionFindAllByRefValue() {
        var ownerViews = orm.projection(OwnerView.class);
        List<OwnerView> inMadison = ownerViews.findAllBy(OwnerView_.address.city, Ref.of(City.class, 2));
        assertEquals(4, inMadison.size());
        assertTrue(inMadison.stream().allMatch(view -> view.address().city().id() == 2));
        assertTrue(ownerViews.findAllBy(OwnerView_.address.city, Ref.of(City.class, 999)).isEmpty());
    }

    @Test
    public void testProjectionFindAllByFieldMultipleValues() {
        var ownerViews = orm.projection(OwnerView.class);
        List<OwnerView> views = ownerViews.findAllBy(OwnerView_.lastName, List.of("Franklin", "Davis"));
        assertEquals(3, views.size());
        assertTrue(ownerViews.findAllBy(OwnerView_.lastName, List.of()).isEmpty());
    }

    @Test
    public void testProjectionFindAllByRefField() {
        // Cities 1 and 6 host one owner each.
        var ownerViews = orm.projection(OwnerView.class);
        List<OwnerView> views = ownerViews.findAllByRef(OwnerView_.address.city,
                List.of(Ref.of(City.class, 1), Ref.of(City.class, 6)));
        assertEquals(2, views.size());
        assertTrue(views.stream().allMatch(view -> List.of(1, 6).contains(view.address().city().id())));
    }

    @Test
    public void testProjectionFindRefByField() {
        var ownerViews = orm.projection(OwnerView.class);
        Optional<Ref<OwnerView>> ref = ownerViews.findRefBy(OwnerView_.lastName, "Franklin");
        assertTrue(ref.isPresent());
        assertEquals("George", ref.get().fetch().firstName());
        assertTrue(ownerViews.findRefBy(OwnerView_.lastName, "Nobody").isEmpty());
        Optional<Ref<OwnerView>> byCity = ownerViews.findRefBy(OwnerView_.address.city, Ref.of(City.class, 1));
        assertTrue(byCity.isPresent());
        assertEquals(1, byCity.get().fetch().id());
    }

    @Test
    public void testProjectionGetRefByField() {
        var ownerViews = orm.projection(OwnerView.class);
        assertEquals("George", ownerViews.getRefBy(OwnerView_.lastName, "Franklin").fetch().firstName());
        assertThrows(NoResultException.class, () -> ownerViews.getRefBy(OwnerView_.lastName, "Nobody"));
        assertEquals(1, ownerViews.getRefBy(OwnerView_.address.city, Ref.of(City.class, 1)).fetch().id());
        assertThrows(NoResultException.class,
                () -> ownerViews.getRefBy(OwnerView_.address.city, Ref.of(City.class, 999)));
    }

    @Test
    public void testProjectionFindAllRefByField() {
        var ownerViews = orm.projection(OwnerView.class);
        assertEquals(2, ownerViews.findAllRefBy(OwnerView_.lastName, "Davis").size());
        assertEquals(4, ownerViews.findAllRefBy(OwnerView_.address.city, Ref.of(City.class, 2)).size());
        List<Ref<OwnerView>> byNames = ownerViews.findAllRefBy(OwnerView_.lastName, List.of("Franklin", "Davis"));
        assertEquals(3, byNames.size());
        List<Ref<OwnerView>> byCities = ownerViews.findAllRefByRef(OwnerView_.address.city,
                List.of(Ref.of(City.class, 1), Ref.of(City.class, 6)));
        assertEquals(2, byCities.size());
        assertTrue(byCities.stream().map(Ref::fetch).allMatch(view -> List.of(1, 6).contains(view.address().city().id())));
    }

    @Test
    public void testProjectionCountAndExistsByRefValue() {
        var ownerViews = orm.projection(OwnerView.class);
        assertEquals(4, ownerViews.countBy(OwnerView_.address.city, Ref.of(City.class, 2)));
        assertEquals(0, ownerViews.countBy(OwnerView_.address.city, Ref.of(City.class, 999)));
        assertTrue(ownerViews.existsBy(OwnerView_.address.city, Ref.of(City.class, 2)));
        assertFalse(ownerViews.existsBy(OwnerView_.address.city, Ref.of(City.class, 999)));
    }
}
