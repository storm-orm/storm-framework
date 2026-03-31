package st.orm.template;

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import st.orm.Page;
import st.orm.Pageable;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.repository.EntityRepository;
import st.orm.repository.ProjectionRepository;
import st.orm.template.model.City;
import st.orm.template.model.City_;
import st.orm.template.model.Owner;
import st.orm.template.model.OwnerView;
import st.orm.template.model.OwnerView_;
import st.orm.template.model.Pet;
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
        Window<City, City> window = orm.entity(City.class).select().scroll(2);
        assertEquals(2, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollLastPage() {
        Window<City, City> window = orm.entity(City.class).select().scroll(100);
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

    @Test
    public void testSelectWithLongResultType() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        long count = cities.selectCount().getSingleResult();
        assertEquals(6, count);
    }

    @Test
    public void testSelectCustomType() {
        EntityRepository<City, Integer> cities = orm.entity(City.class);
        List<String> names = cities.select(String.class, RAW."\{City.class}.name").getResultList();
        assertEquals(6, names.size());
    }

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
    public void testDeleteTemplateHelper() {
        var deleteElement = Templates.delete(City.class);
        assertNotNull(deleteElement);
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

    @Test
    public void testSubqueryFromType() {
        var subquery = orm.subquery(City.class);
        assertNotNull(subquery);
    }

    @Test
    public void testSubqueryFromTypeWithSelectType() {
        var subquery = orm.subquery(City.class, City.class);
        assertNotNull(subquery);
    }

    @Test
    public void testSubqueryFromTypeWithTemplate() {
        var subquery = orm.subquery(City.class, RAW."\{City.class}.id");
        assertNotNull(subquery);
    }

    // WhereBuilder - subquery

    @Test
    public void testWhereBuilderSubquery() {
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.exists(
                        wb.subquery(Pet.class, RAW."1")
                                .where(RAW."\{Pet.class}.owner_id = \{Owner.class}.id")))
                .getResultList();
        assertFalse(owners.isEmpty());
    }

    // EntityRepository - Metamodel.Key scroll default methods

    @Test
    public void testEntityScrollByKey() {
        Window<City, City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, 3));
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testEntityScrollBeforeByKey() {
        Window<City, City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollBeforeRefByKey() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollRefByKey() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 3));
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollAfterByKey() {
        Window<City, City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 2, 3));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollBeforeByKeyAndValue() {
        Window<City, City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 5, 3).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollAfterRefByKey() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 2, 3));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollBeforeRefByKeyAndValue() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 5, 3).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testEntityScrollByKeyAndSort() {
        Window<City, City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, City_.name, 3));
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollBeforeByKeyAndSort() {
        Window<City, City> window = orm.entity(City.class).scroll(Scrollable.of(City_.id, City_.name, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollBeforeRefByKeyAndSort() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, City_.name, 3).backward());
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollRefByKeyAndSort() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, City_.name, 3));
        assertEquals(3, window.content().size());
    }

    @Test
    public void testEntityScrollAfterByKeyAndSort() {
        Window<City, City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 2, City_.name, "A", 3));
        assertNotNull(window);
    }

    @Test
    public void testEntityScrollBeforeByKeyAndSortAndValue() {
        Window<City, City> window = orm.entity(City.class).select().scroll(Scrollable.of(City_.id, 5, City_.name, "Z", 3).backward());
        assertNotNull(window);
    }

    @Test
    public void testEntityScrollAfterRefByKeyAndSort() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 2, City_.name, "A", 3));
        assertNotNull(window);
    }

    @Test
    public void testEntityScrollBeforeRefByKeyAndSortAndValue() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 5, City_.name, "Z", 3).backward());
        assertNotNull(window);
    }

    // ProjectionRepository - Metamodel.Key scroll default methods

    @Test
    public void testProjectionScrollByKey() {
        Window<OwnerView, OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, 5));
        assertEquals(5, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testProjectionScrollBeforeByKey() {
        Window<OwnerView, OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, 5).backward());
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollRefByKey() {
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 5));
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
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 3, 5));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyAndValue() {
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 8, 5).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyInitial() {
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 5).backward());
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollByKeyAndSort() {
        Window<OwnerView, OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5));
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollRefByKeyAndSort() {
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5));
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollBeforeByKeyAndSort() {
        Window<OwnerView, OwnerView> window = orm.projection(OwnerView.class).scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5).backward());
        assertEquals(5, window.content().size());
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyAndSort() {
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, OwnerView_.firstName, 5).backward());
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
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 3, OwnerView_.firstName, "A", 5));
        assertNotNull(window);
    }

    @Test
    public void testProjectionScrollBeforeRefByKeyAndSortAndValue() {
        Window<Ref<OwnerView>, OwnerView> window = orm.projection(OwnerView.class).selectRef().scroll(Scrollable.of(OwnerView_.id, 8, OwnerView_.firstName, "Z", 5).backward());
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
}
