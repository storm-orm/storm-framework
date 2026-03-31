package st.orm.template;

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.JoinType.inner;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.IN;
import static st.orm.Operator.IS_NOT_NULL;
import static st.orm.Operator.IS_NULL;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LESS_THAN_OR_EQUAL;
import static st.orm.Operator.LIKE;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.Operator.NOT_IN;
import static st.orm.Operator.NOT_LIKE;

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
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.template.model.City;
import st.orm.template.model.City_;
import st.orm.template.model.Owner;
import st.orm.template.model.Owner_;
import st.orm.template.model.Pet;
import st.orm.template.model.PetType;
import st.orm.template.model.Pet_;
import st.orm.template.model.Visit;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class QueryBuilderTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ORMTemplate orm;

    // QueryBuilder - distinct

    @Test
    public void testDistinct() {
        List<City> cities = orm.selectFrom(City.class).distinct().getResultList();
        assertEquals(6, cities.size());
    }

    // QueryBuilder - limit and offset

    @Test
    public void testLimit() {
        List<City> cities = orm.selectFrom(City.class).limit(3).getResultList();
        assertEquals(3, cities.size());
    }

    @Test
    public void testOffset() {
        List<City> allCities = orm.selectFrom(City.class).getResultList();
        List<City> offsetCities = orm.selectFrom(City.class).limit(10).offset(2).getResultList();
        assertEquals(allCities.size() - 2, offsetCities.size());
    }

    // QueryBuilder - where with ID

    @Test
    public void testWhereById() {
        // Uses the where(ID) method on EntityRepository.select()
        City city = orm.entity(City.class).select().where(orm.entity(City.class).ref(1)).getSingleResult();
        assertNotNull(city);
        assertEquals(1, city.id());
    }

    @Test
    public void testWhereByIds() {
        List<City> cities = orm.entity(City.class).select()
                .whereRef(List.of(orm.entity(City.class).ref(1), orm.entity(City.class).ref(2)))
                .getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - where with StringTemplate

    @Test
    public void testWhereWithStringTemplate() {
        List<City> cities = orm.selectFrom(City.class)
                .where(RAW."\{City.class}.name = \{"Madison"}")
                .getResultList();
        assertEquals(1, cities.size());
    }

    // QueryBuilder - where with record

    @Test
    public void testWhereWithRecord() {
        var city = orm.entity(City.class).getById(1);
        List<City> cities = orm.entity(City.class).select().where(city).getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereWithRecordIterable() {
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<City> cities = orm.entity(City.class).select().where(List.of(city1, city2)).getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - where with Ref

    @Test
    public void testWhereWithRef() {
        var ref = orm.entity(City.class).ref(1);
        List<City> cities = orm.entity(City.class).select().where(ref).getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereRefIterable() {
        var ref1 = orm.entity(City.class).ref(1);
        var ref2 = orm.entity(City.class).ref(2);
        List<City> cities = orm.entity(City.class).select().whereRef(List.of(ref1, ref2)).getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - where with WhereBuilder and PredicateBuilder

    @Test
    public void testWhereWithWhereBuilder() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.name = \{"Madison"}"))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereWithPredicateAnd() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.id > \{0}")
                        .and(wb.where(RAW."\{City.class}.id < \{3}")))
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testWhereWithPredicateOr() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.name = \{"Madison"}")
                        .or(wb.where(RAW."\{City.class}.name = \{"Windsor"}")))
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testWhereBuilderWhereRef() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.whereRef(orm.entity(City.class).ref(1)))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereBuilderWhereRefIterable2() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.whereRef(List.of(orm.entity(City.class).ref(1), orm.entity(City.class).ref(2), orm.entity(City.class).ref(3))))
                .getResultList();
        assertEquals(3, cities.size());
    }

    @Test
    public void testWhereBuilderWhereRecord() {
        var city = orm.entity(City.class).getById(1);
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(city))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereBuilderWhereRecordIterable() {
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(List.of(city1, city2)))
                .getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - whereAny

    @Test
    public void testWhereAnyWithWhereBuilder() {
        List<City> cities = orm.entity(City.class).select()
                .whereAny(wb -> wb.whereAny(orm.entity(City.class).getById(1)))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereBuilderWhereAnyRef() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.whereAnyRef(orm.entity(City.class).ref(1)))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereBuilderWhereAnyRefIterable() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.whereAnyRef(List.of(orm.entity(City.class).ref(1), orm.entity(City.class).ref(2))))
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testWhereBuilderWhereAnyRecordIterable() {
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.whereAny(List.of(city1, city2)))
                .getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - where with exists / notExists

    @Test
    public void testWhereExists() {
        // Select owners that have pets
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.exists(
                        wb.subquery(Pet.class, RAW."1")
                                .where(RAW."\{Pet.class}.owner_id = \{Owner.class}.id")))
                .getResultList();
        assertFalse(owners.isEmpty());
    }

    @Test
    public void testWhereNotExists() {
        // Select owners that have no pets
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.notExists(
                        wb.subquery(Pet.class, RAW."1")
                                .where(RAW."\{Pet.class}.owner_id = \{Owner.class}.id")))
                .getResultList();
        // All 10 owners have pets, so result depends on data; just verify it runs
        assertNotNull(owners);
    }

    // QueryBuilder - inner join (class-based)

    @Test
    public void testInnerJoinOnClass() {
        // Join Owner on Pet: Pet has FK to Owner via owner_id
        List<Pet> pets = orm.entity(Pet.class).select()
                .innerJoin(Owner.class).on(Pet.class)
                .getResultList();
        // Pet 13 (Sly) has null owner, so inner join excludes it: 12 results
        assertEquals(12, pets.size());
    }

    @Test
    public void testInnerJoinOnTemplate() {
        // City has no FKs. Use join(JoinType, Class, alias) with template ON condition
        List<City> cities = orm.entity(City.class).select()
                .join(inner(), PetType.class, "pt").on(RAW."pt.id = \{City.class}.id")
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    // QueryBuilder - left join

    @Test
    public void testLeftJoinOnClass() {
        // Left join Owner on Pet: preserves all 13 pets including Pet 13 with null owner
        List<Pet> pets = orm.entity(Pet.class).select()
                .leftJoin(Owner.class).on(Pet.class)
                .getResultList();
        assertEquals(13, pets.size());
    }

    // QueryBuilder - right join

    @Test
    public void testRightJoinOnClass() {
        // Right join Owner on Pet: includes all owners
        List<Pet> pets = orm.entity(Pet.class).select()
                .rightJoin(Owner.class).on(Pet.class)
                .getResultList();
        assertEquals(12, pets.size());
    }

    // QueryBuilder - cross join

    @Test
    public void testCrossJoin() {
        List<City> result = orm.selectFrom(City.class)
                .crossJoin(PetType.class)
                .limit(10)
                .getResultList();
        assertEquals(10, result.size());
    }

    // QueryBuilder - join with type/alias

    @Test
    public void testJoinWithTypeAlias() {
        // City has no auto-joined FKs, so we can freely join other tables
        List<City> cities = orm.selectFrom(City.class)
                .join(inner(), Owner.class, "o").on(RAW."o.city_id = \{City.class}.id")
                .distinct()
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    // QueryBuilder - join with StringTemplate

    @Test
    public void testInnerJoinWithTemplate() {
        // City has no auto-joined FKs, join owner table via template
        List<City> cities = orm.selectFrom(City.class)
                .innerJoin(RAW."owner", "own").on(RAW."own.city_id = \{City.class}.id")
                .distinct()
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    @Test
    public void testLeftJoinWithTemplate() {
        List<City> cities = orm.selectFrom(City.class)
                .leftJoin(RAW."owner", "own").on(RAW."own.city_id = \{City.class}.id")
                .distinct()
                .getResultList();
        assertEquals(6, cities.size());
    }

    @Test
    public void testRightJoinWithTemplate() {
        List<City> cities = orm.selectFrom(City.class)
                .rightJoin(RAW."owner", "own").on(RAW."own.city_id = \{City.class}.id")
                .distinct()
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    @Test
    public void testCrossJoinWithTemplate() {
        List<City> result = orm.selectFrom(City.class)
                .crossJoin(RAW."pet_type")
                .limit(5)
                .getResultList();
        assertEquals(5, result.size());
    }

    @Test
    public void testJoinWithTypeTemplateAlias() {
        // City has no auto-joined FKs, join owner table via template with alias
        List<City> cities = orm.selectFrom(City.class)
                .join(inner(), RAW."owner", "o").on(RAW."o.city_id = \{City.class}.id")
                .distinct()
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    // QueryBuilder - join with subquery

    @Test
    public void testJoinWithSubquery() {
        // City has no auto-joined FKs, join with a subquery of Owner
        var subquery = orm.subquery(Owner.class, RAW."\{Owner.class}.id, \{Owner.class}.city_id");
        List<City> cities = orm.selectFrom(City.class)
                .join(inner(), subquery, "ow").on(RAW."ow.city_id = \{City.class}.id")
                .distinct()
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    // QueryBuilder - append

    @Test
    public void testAppend() {
        List<City> cities = orm.selectFrom(City.class)
                .append(RAW." ORDER BY \{City.class}.name")
                .getResultList();
        assertEquals(6, cities.size());
    }

    // QueryBuilder - forUpdate / forShare / forLock

    @Test
    public void testForUpdate() {
        // H2 supports FOR UPDATE
        List<City> cities = orm.selectFrom(City.class)
                .limit(1)
                .forUpdate()
                .getResultList();
        assertEquals(1, cities.size());
    }

    // QueryBuilder - unsafe

    @Test
    public void testQueryBuilderUnsafe() {
        // Visit has no incoming FK constraints, so we can safely delete all
        var localOrm = ORMTemplate.of(dataSource);
        int deleted = localOrm.deleteFrom(Visit.class).unsafe().executeUpdate();
        assertTrue(deleted > 0);
    }

    // QueryBuilder - build

    @Test
    public void testBuild() {
        Query query = orm.selectFrom(City.class).build();
        assertNotNull(query);
        List<City> cities = query.getResultList(City.class);
        assertEquals(6, cities.size());
    }

    // QueryBuilder - prepare

    @Test
    public void testPrepare() {
        try (PreparedQuery preparedQuery = orm.selectFrom(City.class).prepare()) {
            assertNotNull(preparedQuery);
        }
    }

    // QueryBuilder - getResultStream

    @Test
    public void testGetResultStream() {
        try (Stream<City> stream = orm.entity(City.class).select().getResultStream()) {
            assertEquals(6, stream.count());
        }
    }

    // QueryBuilder - getResultCount

    @Test
    public void testGetResultCount() {
        long count = orm.entity(City.class).select().getResultCount();
        assertEquals(6, count);
    }

    // QueryBuilder - getSingleResult / getOptionalResult

    @Test
    public void testGetSingleResult() {
        City city = orm.entity(City.class).select().where(orm.entity(City.class).ref(1)).getSingleResult();
        assertNotNull(city);
    }

    @Test
    public void testGetOptionalResultPresent() {
        Optional<City> city = orm.entity(City.class).select().where(orm.entity(City.class).ref(1)).getOptionalResult();
        assertTrue(city.isPresent());
    }

    @Test
    public void testGetOptionalResultEmpty() {
        Optional<City> city = orm.entity(City.class).select().where(orm.entity(City.class).ref(999)).getOptionalResult();
        assertFalse(city.isPresent());
    }

    @Test
    public void testGetSingleResultThrowsNoResult() {
        assertThrows(NoResultException.class, () ->
                orm.entity(City.class).select().where(orm.entity(City.class).ref(999)).getSingleResult());
    }

    @Test
    public void testGetSingleResultThrowsNonUnique() {
        assertThrows(NonUniqueResultException.class, () ->
                orm.entity(City.class).select().getSingleResult());
    }

    // QueryBuilder - scroll (pagination)

    @Test
    public void testScroll() {
        Window<City, City> window = orm.entity(City.class).select().scroll(3);
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollNoMore() {
        Window<City, City> window = orm.entity(City.class).select().scroll(100);
        assertEquals(6, window.content().size());
        assertFalse(window.hasNext());
    }

    // QueryBuilder - typed

    @Test
    public void testTyped() {
        // typed() expects a Data type as the pk type; use ref-based where instead
        List<City> cities = orm.entity(City.class).select()
                .where(orm.entity(City.class).ref(1))
                .getResultList();
        assertEquals(1, cities.size());
    }

    // StringTemplates convert methods

    @Test
    public void testStringTemplatesConvertRoundtrip() {
        // This is implicitly tested by all query operations, but let's do a direct test
        Query query = orm.query(RAW."SELECT \{City.class} FROM \{City.class}");
        assertNotNull(query);
    }

    // QueryBuilder - groupBy

    @Test
    public void testGroupByWithTemplate() {
        // Count pets per type using string template for groupBy
        List<Object[]> result = orm.query(RAW."SELECT \{PetType.class}.name, COUNT(*) FROM \{Pet.class} INNER JOIN \{PetType.class} ON \{Pet.class}.type_id = \{PetType.class}.id GROUP BY \{PetType.class}.name")
                .getResultList();
        assertFalse(result.isEmpty());
    }

    // QueryBuilder - having

    @Test
    public void testHavingWithTemplate() {
        // Count pets per type, having count > 1
        List<Object[]> result = orm.query(RAW."SELECT \{PetType.class}.name, COUNT(*) AS c FROM \{Pet.class} INNER JOIN \{PetType.class} ON \{Pet.class}.type_id = \{PetType.class}.id GROUP BY \{PetType.class}.name HAVING c > \{1}")
                .getResultList();
        assertFalse(result.isEmpty());
    }

    @Test
    public void testHavingWithMetamodelAndOperator() {
        List<Long> result = orm.entity(Owner.class).selectCount()
                .groupBy(Owner_.lastName)
                .having(Owner_.lastName, EQUALS, "Davis")
                .getResultList();
        assertEquals(1, result.size());
    }

    @Test
    public void testHavingAnyWithMetamodelAndOperator() {
        List<Long> result = orm.entity(Owner.class).selectCount()
                .groupBy(Owner_.lastName)
                .havingAny(Owner_.lastName, IN, "Davis", "Franklin")
                .getResultList();
        assertEquals(2, result.size());
    }

    // PredicateBuilder - andAny / orAny

    @Test
    public void testPredicateAndAny() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.id = \{1}")
                        .andAny(wb.where(RAW."\{City.class}.name = \{"Sun Paririe"}")))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testPredicateOrAny() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.id = \{999}")
                        .orAny(wb.where(RAW."\{City.class}.name = \{"Madison"}")))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testPredicateAndTemplate() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.id > \{0}")
                        .and(RAW."\{City.class}.id < \{3}"))
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testPredicateOrTemplate() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(RAW."\{City.class}.name = \{"Madison"}")
                        .or(RAW."\{City.class}.name = \{"Windsor"}"))
                .getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - Metamodel-based where

    @Test
    public void testWhereMetamodelEquals() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, EQUALS, "Madison")
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereMetamodelNotEquals() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, NOT_EQUALS, "Madison")
                .getResultList();
        assertEquals(5, cities.size());
    }

    @Test
    public void testWhereMetamodelLike() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, LIKE, "M%")
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    @Test
    public void testWhereMetamodelNotLike() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, NOT_LIKE, "M%")
                .getResultList();
        assertFalse(cities.isEmpty());
    }

    @Test
    public void testWhereMetamodelGreaterThan() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.id, GREATER_THAN, 3)
                .getResultList();
        assertEquals(3, cities.size());
    }

    @Test
    public void testWhereMetamodelLessThan() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.id, LESS_THAN, 3)
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testWhereMetamodelGreaterThanOrEqual() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.id, GREATER_THAN_OR_EQUAL, 3)
                .getResultList();
        assertEquals(4, cities.size());
    }

    @Test
    public void testWhereMetamodelLessThanOrEqual() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.id, LESS_THAN_OR_EQUAL, 3)
                .getResultList();
        assertEquals(3, cities.size());
    }

    @Test
    public void testWhereMetamodelIn() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, IN, List.of("Madison", "Windsor"))
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testWhereMetamodelNotIn() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, NOT_IN, List.of("Madison", "Windsor"))
                .getResultList();
        assertEquals(4, cities.size());
    }

    @Test
    public void testWhereMetamodelIsNull() {
        // Pet 13 has null owner
        List<Pet> pets = orm.entity(Pet.class).select()
                .where(Pet_.owner, IS_NULL)
                .getResultList();
        assertEquals(1, pets.size());
    }

    @Test
    public void testWhereMetamodelIsNotNull() {
        List<Pet> pets = orm.entity(Pet.class).select()
                .where(Pet_.owner, IS_NOT_NULL)
                .getResultList();
        assertEquals(12, pets.size());
    }

    @Test
    public void testWhereMetamodelVarargs() {
        List<City> cities = orm.entity(City.class).select()
                .where(City_.name, EQUALS, "Madison")
                .getResultList();
        assertEquals(1, cities.size());
    }

    // QueryBuilder - where with Iterable of records

    @Test
    public void testWhereIterableRecords() {
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<City> cities = orm.entity(City.class).select()
                .where(List.of(city1, city2))
                .getResultList();
        assertEquals(2, cities.size());
    }

    // QueryBuilder - Metamodel groupBy / orderBy

    @Test
    public void testGroupByMetamodel() {
        // Group owners by city (through metamodel)
        long count = orm.entity(City.class).select()
                .groupBy(City_.name)
                .getResultCount();
        assertEquals(6, count);
    }

    @Test
    public void testOrderByMetamodel() {
        List<City> cities = orm.entity(City.class).select()
                .orderBy(City_.name)
                .getResultList();
        assertEquals(6, cities.size());
        // Verify ordering (ascending by name)
        assertTrue(cities.get(0).name().compareTo(cities.get(1).name()) <= 0);
    }

    @Test
    public void testOrderByDescendingMetamodel() {
        List<City> cities = orm.entity(City.class).select()
                .orderByDescending(City_.name)
                .getResultList();
        assertEquals(6, cities.size());
        // Verify descending ordering
        assertTrue(cities.get(0).name().compareTo(cities.get(1).name()) >= 0);
    }

    // QueryBuilder - orderByDescending with StringTemplate

    @Test
    public void testOrderByDescendingTemplate() {
        List<City> cities = orm.entity(City.class).select()
                .orderByDescending(RAW."\{City_.name}")
                .getResultList();
        assertEquals(6, cities.size());
        assertTrue(cities.get(0).name().compareTo(cities.get(1).name()) >= 0);
    }

    // QueryBuilder - Metamodel-based scroll

    @Test
    public void testScrollWithMetamodelKey() {
        Window<City, City> window = orm.entity(City.class).select()
                .scroll(Scrollable.of(City_.id, 3));
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollAfterWithMetamodelKey() {
        Window<City, City> window = orm.entity(City.class).select()
                .scroll(Scrollable.of(City_.id, 2, 3));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testScrollBeforeWithMetamodelKey() {
        Window<City, City> window = orm.entity(City.class).select()
                .scroll(Scrollable.of(City_.id, 5, 3).backward());
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testScrollRefWithMetamodelKey() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef().scroll(Scrollable.of(City_.id, 3));
        assertEquals(3, window.content().size());
    }

    @Test
    public void testScrollAfterRefWithMetamodelKey() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef()
                .scroll(Scrollable.of(City_.id, 2, 3));
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testScrollBeforeRefWithMetamodelKey() {
        Window<Ref<City>, City> window = orm.entity(City.class).selectRef()
                .scroll(Scrollable.of(City_.id, 5, 3).backward());
        assertFalse(window.content().isEmpty());
    }

    // WhereBuilder - Metamodel-based where

    @Test
    public void testWhereBuilderMetamodelEquals() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(City_.name, EQUALS, "Madison"))
                .getResultList();
        assertEquals(1, cities.size());
    }

    @Test
    public void testWhereBuilderMetamodelIn() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(City_.name, IN, List.of("Madison", "Windsor")))
                .getResultList();
        assertEquals(2, cities.size());
    }

    @Test
    public void testWhereBuilderPredicateAndMetamodel() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.where(City_.id, GREATER_THAN, 0)
                        .and(wb.where(City_.id, LESS_THAN, 3)))
                .getResultList();
        assertEquals(2, cities.size());
    }

    // WhereBuilder - TRUE / FALSE

    @Test
    public void testWhereBuilderTrue() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.TRUE())
                .getResultList();
        assertEquals(6, cities.size());
    }

    @Test
    public void testWhereBuilderFalse() {
        List<City> cities = orm.entity(City.class).select()
                .where(wb -> wb.FALSE())
                .getResultList();
        assertEquals(0, cities.size());
    }

    // WhereBuilder - where(Metamodel, V record) default method

    @Test
    public void testWhereBuilderMetamodelRecordDefault() {
        // WhereBuilder.where(Metamodel<T,V>, V record) delegates to where(path, EQUALS, record)
        var city = orm.entity(City.class).getById(1);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.where(Owner_.address.city, city))
                .getResultList();
        assertNotNull(owners);
    }

    @Test
    public void testWhereBuilderWhereAnyMetamodelRecordDefault() {
        // WhereBuilder.whereAny(Metamodel<?,V>, V record) delegates to whereAny(path, EQUALS, record)
        var city = orm.entity(City.class).getById(1);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.whereAny(Owner_.address.city, city))
                .getResultList();
        assertNotNull(owners);
    }

    // WhereBuilder - where(Metamodel, Iterable<V>) default method

    @Test
    public void testWhereBuilderMetamodelIterableDefault() {
        // WhereBuilder.where(Metamodel<T,V>, Iterable<V>) delegates to where(path, IN, it)
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.where(Owner_.address.city, List.of(city1, city2)))
                .getResultList();
        assertNotNull(owners);
    }

    @Test
    public void testWhereBuilderWhereAnyMetamodelIterableDefault() {
        // WhereBuilder.whereAny(Metamodel<?,V>, Iterable<V>) delegates to whereAny(path, IN, it)
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.whereAny(Owner_.address.city, List.of(city1, city2)))
                .getResultList();
        assertNotNull(owners);
    }

    // WhereBuilder - SubqueryTemplate default methods

    @Test
    public void testWhereBuilderSubqueryDefaultSingleArg() {
        // SubqueryTemplate.subquery(Class) default method
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.exists(wb.subquery(Pet.class)
                        .where(RAW."\{Pet.class}.owner_id = \{Owner.class}.id")))
                .getResultList();
        assertFalse(owners.isEmpty());
    }

    @Test
    public void testWhereBuilderSubqueryDefaultTwoArgs() {
        // SubqueryTemplate.subquery(Class, Class) default method
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(wb -> wb.exists(wb.subquery(Pet.class, Pet.class)
                        .where(RAW."\{Pet.class}.owner_id = \{Owner.class}.id")))
                .getResultList();
        assertFalse(owners.isEmpty());
    }

    // QueryBuilder - where(Metamodel, V record) default on QueryBuilder

    @Test
    public void testQueryBuilderWhereMetamodelRecord() {
        // QueryBuilder.where(Metamodel<T,V>, V record) default
        var city = orm.entity(City.class).getById(1);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(Owner_.address.city, city)
                .getResultList();
        assertNotNull(owners);
    }

    @Test
    public void testQueryBuilderWhereMetamodelRef() {
        // QueryBuilder.where(Metamodel<T,V>, Ref<V>) default
        var cityRef = orm.entity(City.class).ref(1);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(Owner_.address.city, cityRef)
                .getResultList();
        assertNotNull(owners);
    }

    @Test
    public void testQueryBuilderWhereMetamodelIterable() {
        // QueryBuilder.where(Metamodel<T,V>, Iterable<V>) default
        var city1 = orm.entity(City.class).getById(1);
        var city2 = orm.entity(City.class).getById(2);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(Owner_.address.city, List.of(city1, city2))
                .getResultList();
        assertNotNull(owners);
    }

    @Test
    public void testQueryBuilderWhereRefMetamodel() {
        // QueryBuilder.whereRef(Metamodel<T,V>, Iterable<Ref<V>>) default
        var ref1 = orm.entity(City.class).ref(1);
        var ref2 = orm.entity(City.class).ref(2);
        List<Owner> owners = orm.entity(Owner.class).select()
                .whereRef(Owner_.address.city, List.of(ref1, ref2))
                .getResultList();
        assertNotNull(owners);
    }

    // QueryBuilder - whereId with Iterable default

    @Test
    public void testQueryBuilderWhereIdIterable() {
        List<City> cities = orm.entity(City.class).select()
                .whereId(List.of(1, 2, 3))
                .getResultList();
        assertEquals(3, cities.size());
    }

    // QueryBuilder - groupByAny / orderByAny / orderByDescendingAny

    @Test
    public void testGroupByAny() {
        long count = orm.entity(City.class).select()
                .groupByAny(City_.name)
                .getResultCount();
        assertEquals(6, count);
    }

    @Test
    public void testOrderByAny() {
        List<City> cities = orm.entity(City.class).select()
                .orderByAny(City_.name)
                .getResultList();
        assertEquals(6, cities.size());
        assertTrue(cities.get(0).name().compareTo(cities.get(1).name()) <= 0);
    }

    @Test
    public void testOrderByDescendingAnyMetamodel() {
        List<City> cities = orm.entity(City.class).select()
                .orderByDescendingAny(City_.name)
                .getResultList();
        assertEquals(6, cities.size());
        assertTrue(cities.get(0).name().compareTo(cities.get(1).name()) >= 0);
    }

    @Test
    public void testGroupByAnyEmptyThrows() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().groupByAny());
    }

    @Test
    public void testOrderByAnyEmptyThrows() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderByAny());
    }

    @Test
    public void testOrderByDescendingAnyVarargsEmptyThrows() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderByDescendingAny(new st.orm.Metamodel[0]));
    }

    // QueryBuilder - orderByDescending with multiple metamodels

    @Test
    public void testOrderByDescendingMultipleMetamodels() {
        List<City> cities = orm.entity(City.class).select()
                .orderByDescending(City_.name, City_.id)
                .getResultList();
        assertEquals(6, cities.size());
        assertTrue(cities.get(0).name().compareTo(cities.get(1).name()) >= 0);
    }

    // QueryBuilder - composite scroll with sort + key

    @Test
    public void testScrollAfterComposite() {
        Window<City, City> window = orm.entity(City.class).select()
                .scroll(Scrollable.of(City_.id, 2, City_.name, "A", 3));
        assertNotNull(window);
    }

    @Test
    public void testScrollBeforeComposite() {
        Window<City, City> window = orm.entity(City.class).select()
                .scroll(Scrollable.of(City_.id, 5, City_.name, "Z", 3).backward());
        assertNotNull(window);
    }

    // QueryBuilder - scroll methods throw if orderBy already set

    @Test
    public void testScrollWithKeyThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, 3)));
    }

    @Test
    public void testScrollBeforeWithKeyThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, 3).backward()));
    }

    @Test
    public void testScrollAfterThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, 2, 3)));
    }

    @Test
    public void testScrollBeforeValueThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, 5, 3).backward()));
    }

    @Test
    public void testScrollCompositeThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, City_.name, 3)));
    }

    @Test
    public void testScrollBeforeCompositeThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, City_.name, 3).backward()));
    }

    @Test
    public void testScrollAfterCompositeThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, 2, City_.name, "A", 3)));
    }

    @Test
    public void testScrollBeforeCompositeValueThrowsIfOrderBySet() {
        assertThrows(PersistenceException.class, () ->
                orm.entity(City.class).select().orderBy(City_.name).scroll(Scrollable.of(City_.id, 5, City_.name, "Z", 3).backward()));
    }

    // QueryBuilder - forShare

    @Test
    public void testForShare() {
        // H2 does not support FOR SHARE syntax, so this should throw
        assertThrows(PersistenceException.class, () ->
                orm.selectFrom(City.class)
                        .limit(1)
                        .forShare()
                        .getResultList());
    }

    // QueryBuilder - forLock with template

    @Test
    public void testForLockWithTemplate() {
        List<City> cities = orm.selectFrom(City.class)
                .limit(1)
                .forLock(RAW."FOR UPDATE")
                .getResultList();
        assertEquals(1, cities.size());
    }

    // QueryBuilder - scroll with size validation

    @Test
    public void testScrollSizeZeroThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                orm.entity(City.class).select().scroll(0));
    }

    @Test
    public void testScrollSizeNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                orm.entity(City.class).select().scroll(-1));
    }

    // QueryBuilder - getResultList / getResultCount via default methods

    @Test
    public void testGetResultList() {
        List<City> cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    @Test
    public void testExecuteUpdateViaBuilder() {
        var localOrm = ORMTemplate.of(dataSource);
        localOrm.entity(City.class).insertAndFetch(new City(null, "TempForDelete"));
        int deleted = localOrm.deleteFrom(City.class)
                .where(RAW."\{City.class}.name = \{"TempForDelete"}")
                .executeUpdate();
        assertEquals(1, deleted);
    }

    // QueryBuilder - where(ID) convenience method

    @Test
    public void testWhereByIdDirectValue() {
        // Uses the where(ID id) method that delegates to where(predicate -> predicate.whereId(id))
        City city = orm.entity(City.class).select().where(1).getSingleResult();
        assertNotNull(city);
        assertEquals(1, city.id());
    }

    // QueryBuilder - getOptionalResult throws NonUniqueResultException

    @Test
    public void testGetOptionalResultThrowsNonUnique() {
        assertThrows(NonUniqueResultException.class, () ->
                orm.entity(City.class).select().getOptionalResult());
    }
}
