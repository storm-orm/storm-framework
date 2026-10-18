package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.Owner;
import st.orm.core.model.OwnerPrimaryPet;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.model.polymorphic.JoinedCat_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * Integration tests for DeleteBuilder covering distinct, offset, limit, forShare, forUpdate,
 * forLock, subquery, and getResultStream restrictions, the condition check, and joins.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class DeleteBuilderIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testDeleteDistinctThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).distinct());
    }

    @Test
    public void testDeleteOffsetThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).offset(1));
    }

    @Test
    public void testDeleteLimitThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).limit(1));
    }

    @Test
    public void testDeleteForShareThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).forShare());
    }

    @Test
    public void testDeleteForUpdateThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).forUpdate());
    }

    @Test
    public void testDeleteForLockThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).forLock(TemplateString.of("FOR LOCK")));
    }

    @Test
    public void testDeleteGetResultStreamThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).unsafe().getResultStream());
    }

    @Test
    public void testDeleteWithWhereClause() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Insert a city we can delete.
        var insertedId = cities.insertAndFetchId(City.builder().name("DeleteMe").build());
        long countBefore = cities.count();

        orm.deleteFrom(City.class)
                .where(City_.id, EQUALS, insertedId)
                .executeUpdate();

        assertEquals(countBefore - 1, cities.count());
    }

    @Test
    public void testDeleteWithUnsafe() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Insert a standalone city.
        cities.insertAndFetchId(City.builder().name("UnsafeDelete").build());

        // Unsafe delete without WHERE should attempt to delete all.
        // This will fail due to FK constraints on existing cities, which is expected.
        assertThrows(PersistenceException.class,
                () -> orm.deleteFrom(City.class).unsafe().executeUpdate());
    }

    @Test
    public void refusesADeleteWithoutAConditionUnlessUnsafe() {
        var visits = ORMTemplate.of(dataSource).entity(Visit.class);
        long count = visits.count();
        var exception = assertThrows(PersistenceException.class, () -> visits.delete().executeUpdate());
        assertTrue(exception.getMessage().contains("unsafe()"), exception.getMessage());
        assertEquals(count, visits.count());
        assertEquals(count, visits.delete().unsafe().executeUpdate());
        assertEquals(0, visits.count());
    }

    @Test
    public void runsADeleteWithoutAConditionWhenTheQueryIsMarkedUnsafe() {
        var visits = ORMTemplate.of(dataSource).entity(Visit.class);
        long count = visits.count();
        assertEquals(count, visits.delete().build().unsafe().executeUpdate());
        assertEquals(0, visits.count());
    }

    @Test
    public void leavesTheConditionCheckOfAPlanToItsQueries() {
        var visits = ORMTemplate.of(dataSource).entity(Visit.class);
        long count = visits.count();
        var plan = visits.delete().plan();
        var exception = assertThrows(PersistenceException.class, () -> plan.query().executeUpdate());
        assertTrue(exception.getMessage().contains("unsafe()"), exception.getMessage());
        assertEquals(count, visits.count());
        assertEquals(count, plan.query().unsafe().executeUpdate());
        assertEquals(0, visits.count());
    }

    @Test
    public void refusesSet() {
        var exception = assertThrows(PersistenceException.class,
                () -> ORMTemplate.of(dataSource).entity(City.class).delete().set(City_.name, "x"));
        assertTrue(exception.getMessage().contains("SET"), exception.getMessage());
    }

    @Test
    public void deletesTheRowsAnExplicitJoinSelects() {
        var visits = ORMTemplate.of(dataSource).entity(Visit.class);
        long count = visits.count();
        long ofLeo = visits.select().where(Visit_.pet.name, EQUALS, "Leo").getResultCount();
        assertTrue(ofLeo > 0);
        int deleted = visits.delete()
                .innerJoin(Pet.class).on(Visit.class)
                .where(Pet_.name, EQUALS, "Leo")
                .executeUpdate();
        assertEquals(ofLeo, deleted);
        assertEquals(count - ofLeo, visits.count());
    }

    @Test
    public void deletesTheRowsAPathToARelatedTableSelects() {
        var visits = ORMTemplate.of(dataSource).entity(Visit.class);
        long count = visits.count();
        long ofLeo = visits.select().where(Visit_.pet.name, EQUALS, "Leo").getResultCount();
        int deleted = visits.delete().where(Visit_.pet.name, EQUALS, "Leo").executeUpdate();
        assertEquals(ofLeo, deleted);
        assertEquals(count - ofLeo, visits.count());
    }

    @Test
    public void deletesTheRowsANestedSemiJoinSelects() {
        // The visits of the pets that are an owner's primary pet: Leo's.
        var orm = ORMTemplate.of(dataSource);
        orm.entity(OwnerPrimaryPet.class).insert(new OwnerPrimaryPet(orm.entity(Owner.class).getById(1), orm.entity(Pet.class).getById(1)));
        var visits = orm.entity(Visit.class);
        long count = visits.count();
        long ofLeo = visits.select().where(Visit_.pet.name, EQUALS, "Leo").getResultCount();
        assertTrue(ofLeo > 0);
        int deleted = visits.delete()
                .semiJoin(Pet.class).on(Visit.class)
                .semiJoin(OwnerPrimaryPet.class).on(Pet.class)
                .executeUpdate();
        assertEquals(ofLeo, deleted);
        assertEquals(count - ofLeo, visits.count());
    }

    @Test
    public void refusesATypeOfAJoinedInheritanceHierarchy() {
        // Its rows span the base table and an extension table, and a DELETE removes the rows of one table.
        var orm = ORMTemplate.of(dataSource);
        long animals = orm.entity(JoinedAnimal.class).count();
        var base = assertThrows(PersistenceException.class, () -> orm.entity(JoinedAnimal.class).delete()
                .where(Metamodel.of(JoinedAnimal.class, "id"), EQUALS, 1)
                .executeUpdate());
        assertTrue(base.getMessage().contains("joined inheritance"), base.getMessage());
        var subtype = assertThrows(PersistenceException.class, () -> orm.entity(JoinedCat.class).delete()
                .where(JoinedCat_.id, EQUALS, 1)
                .executeUpdate());
        assertTrue(subtype.getMessage().contains("joined inheritance"), subtype.getMessage());
        assertEquals(animals, orm.entity(JoinedAnimal.class).count());
    }
}
