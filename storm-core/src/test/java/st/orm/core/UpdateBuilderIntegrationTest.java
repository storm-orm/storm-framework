package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;
import static st.orm.core.template.SqlInterceptor.observe;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.core.model.Address;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.VetSpecialty_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.model.polymorphic.JoinedCat_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * Integration tests for the UPDATE builder: the columns it sets, the version it increments, the fields it refuses,
 * the conditions it requires, and the clauses an UPDATE cannot carry.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class UpdateBuilderIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private ORMTemplate orm() {
        return ORMTemplate.of(dataSource);
    }

    private List<String> statements(Runnable action) {
        var statements = new ArrayList<String>();
        observe(sql -> statements.add(sql.statement()), action);
        return statements;
    }

    // Values.

    @Test
    public void setsAColumnOnTheRowsTheConditionSelectsAndIncrementsTheVersion() {
        var owners = orm().entity(Owner.class);
        var before = owners.getById(1);
        var other = owners.getById(2);
        int updated = owners.update()
                .set(Owner_.telephone, "5550000")
                .where(Owner_.id, EQUALS, 1)
                .executeUpdate();
        assertEquals(1, updated);
        var after = owners.getById(1);
        assertEquals("5550000", after.telephone());
        assertEquals(before.firstName(), after.firstName());
        assertEquals(before.version() + 1, after.version());
        assertEquals(other, owners.getById(2));
    }

    @Test
    public void setsSeveralColumnsInOneStatement() {
        var owners = orm().entity(Owner.class);
        var statements = statements(() -> owners.update()
                .set(Owner_.firstName, "Bette")
                .set(Owner_.telephone, "5551111")
                .where(Owner_.id, EQUALS, 1)
                .executeUpdate());
        assertEquals(1, statements.size());
        var owner = owners.getById(1);
        assertEquals("Bette", owner.firstName());
        assertEquals("5551111", owner.telephone());
    }

    @Test
    public void setsANullableFieldToNull() {
        var owners = orm().entity(Owner.class);
        owners.update().set(Owner_.telephone, (String) null).where(Owner_.id, EQUALS, 1).executeUpdate();
        assertNull(owners.getById(1).telephone());
    }

    @Test
    public void refusesNullForAFieldThatIsNotNullable() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(Owner.class).update().set(Owner_.firstName, (String) null));
        assertTrue(exception.getMessage().contains("Owner.firstName"), exception.getMessage());
        assertTrue(exception.getMessage().contains("not nullable"), exception.getMessage());
    }

    @Test
    public void setsAForeignKeyToARecordToARefAndToNull() {
        var pets = orm().entity(Pet.class);
        var owner = orm().entity(Owner.class).getById(2);
        pets.update().set(Pet_.owner, owner).where(Pet_.id, EQUALS, 1).executeUpdate();
        assertEquals(2, pets.getById(1).owner().id());
        pets.update().set(Pet_.owner, Ref.of(Owner.class, 3)).where(Pet_.id, EQUALS, 1).executeUpdate();
        assertEquals(3, pets.getById(1).owner().id());
        pets.update().set(Pet_.owner, (Owner) null).where(Pet_.id, EQUALS, 1).executeUpdate();
        assertNull(pets.getById(1).owner());
    }

    @Test
    public void setsAnInlineRecordThroughEachOfItsColumns() {
        var owners = orm().entity(Owner.class);
        var city = orm().entity(City.class).getById(5);
        owners.update().set(Owner_.address, new Address("1 Lake Rd.", city)).where(Owner_.id, EQUALS, 1).executeUpdate();
        var owner = owners.getById(1);
        assertEquals("1 Lake Rd.", owner.address().address());
        assertEquals(5, owner.address().city().id());
    }

    @Test
    public void setsAColumnOfAnInlineRecord() {
        var owners = orm().entity(Owner.class);
        var before = owners.getById(1);
        owners.update().set(Owner_.address.city, Ref.of(City.class, 4)).where(Owner_.id, EQUALS, 1).executeUpdate();
        var owner = owners.getById(1);
        assertEquals(4, owner.address().city().id());
        assertEquals(before.address().address(), owner.address().address());
    }

    @Test
    public void setsAValueTheExpressionComputesFromTheRow() {
        var cities = orm().entity(City.class);
        var before = cities.getById(2);
        cities.update().set(City_.name, TemplateString.raw("UPPER(\0)", City_.name)).where(City_.id, EQUALS, 2).executeUpdate();
        assertEquals(before.name().toUpperCase(), cities.getById(2).name());
        assertEquals("Sun Paririe", cities.getById(1).name());
    }

    @Test
    public void refusesAnExpressionForAFieldOfSeveralColumns() {
        var exception = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).update()
                .set(Owner_.address, TemplateString.of("NULL")));
        assertTrue(exception.getMessage().contains("Owner.address"), exception.getMessage());
    }

    @Test
    public void refreshesATimestampVersion() {
        var visits = orm().entity(Visit.class);
        var before = visits.getById(1);
        visits.update().set(Visit_.description, "checked").where(Visit_.id, EQUALS, 1).executeUpdate();
        var after = visits.getById(1);
        assertEquals("checked", after.description());
        assertNotEquals(before.timestamp(), after.timestamp());
        assertTrue(after.timestamp().isAfter(Instant.parse("2000-01-01T00:00:00Z")));
    }

    // Fields an UPDATE does not set.

    @Test
    public void refusesThePrimaryKey() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(City.class).update().set(City_.id, 99));
        assertTrue(exception.getMessage().contains("City.id"), exception.getMessage());
        assertTrue(exception.getMessage().contains("primary key"), exception.getMessage());
    }

    @Test
    public void refusesAPartOfACompoundPrimaryKey() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(VetSpecialty.class).update().set(VetSpecialty_.id.vetId, 2));
        assertTrue(exception.getMessage().contains("primary key"), exception.getMessage());
    }

    @Test
    public void refusesAFieldThatIsNotUpdatable() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(Pet.class).update().set(Pet_.birthDate, (LocalDate) null));
        assertTrue(exception.getMessage().contains("Pet.birthDate"), exception.getMessage());
        assertTrue(exception.getMessage().contains("not updatable"), exception.getMessage());
        assertThrows(PersistenceException.class,
                () -> orm().entity(Pet.class).update().set(Pet_.type, Ref.of(st.orm.core.model.PetType.class, 1)));
    }

    @Test
    public void refusesTheVersionField() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(Owner.class).update().set(Owner_.version, 7));
        assertTrue(exception.getMessage().contains("version"), exception.getMessage());
    }

    @Test
    public void refusesAPathIntoARelatedTable() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(Pet.class).update().set(Pet_.owner.firstName, "Leo"));
        assertTrue(exception.getMessage().contains("Pet.owner.firstName"), exception.getMessage());
        assertTrue(exception.getMessage().contains("related table"), exception.getMessage());
        assertThrows(PersistenceException.class,
                () -> orm().entity(Owner.class).update().set(Owner_.address.city.name, "Madison"));
    }

    @Test
    public void refusesAFieldSetTwice() {
        assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).update()
                .set(Owner_.telephone, "1")
                .set(Owner_.telephone, "2"));
        // An inline record and one of its columns overlap.
        assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).update()
                .set(Owner_.address.city, (City) null)
                .set(Owner_.address, new Address("x", null)));
    }

    @Test
    public void refusesAnUpdateThatSetsNothing() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(Owner.class).update().where(Owner_.id, EQUALS, 1).executeUpdate());
        assertTrue(exception.getMessage().contains("set"), exception.getMessage());
    }

    @Test
    public void refusesATypeOfAJoinedInheritanceHierarchy() {
        // Its rows span the base table and an extension table, and an UPDATE writes the columns of one table.
        // The base type has no metamodel of its own fields, so its refusal comes when the statement is built.
        var base = assertThrows(PersistenceException.class, () -> orm().entity(JoinedAnimal.class).update()
                .unsafe()
                .executeUpdate());
        assertTrue(base.getMessage().contains("joined inheritance"), base.getMessage());
        var subtype = assertThrows(PersistenceException.class, () -> orm().entity(JoinedCat.class).update()
                .set(JoinedCat_.indoor, false));
        assertTrue(subtype.getMessage().contains("joined inheritance"), subtype.getMessage());
    }

    @Test
    public void refusesSetOnASelectOrDelete() {
        assertThrows(PersistenceException.class, () -> orm().entity(City.class).select().set(City_.name, "x"));
        assertThrows(PersistenceException.class, () -> orm().entity(City.class).delete().set(City_.name, "x"));
    }

    // Conditions.

    @Test
    public void refusesAnUpdateWithoutAConditionUnlessUnsafe() {
        var cities = orm().entity(City.class);
        var exception = assertThrows(PersistenceException.class,
                () -> cities.update().set(City_.name, "Everywhere").executeUpdate());
        assertTrue(exception.getMessage().contains("unsafe()"), exception.getMessage());
        // A plan leaves the check to its queries, which refuse to run unless they are marked unsafe. A plan binds no
        // fixed values, so it sets a value computed from the row.
        var plan = cities.update().set(City_.name, TemplateString.raw("UPPER(\0)", City_.name)).plan();
        var planned = assertThrows(PersistenceException.class, () -> plan.query().executeUpdate());
        assertTrue(planned.getMessage().contains("unsafe()"), planned.getMessage());
        assertFalse(cities.select().getResultList().stream().anyMatch(city -> city.name().equals("Everywhere")));
        int updated = cities.update().set(City_.name, "Everywhere").unsafe().executeUpdate();
        assertEquals(cities.count(), updated);
        assertTrue(cities.select().getResultList().stream().allMatch(city -> city.name().equals("Everywhere")));
    }

    @Test
    public void setsTheRowsAPathToARelatedTableSelects() {
        var owners = orm().entity(Owner.class);
        int updated = owners.update()
                .set(Owner_.telephone, "5552222")
                .where(Owner_.address.city.name, EQUALS, "Madison")
                .executeUpdate();
        assertEquals(4, updated);
        var inMadison = owners.select().where(Owner_.address.city.name, EQUALS, "Madison").getResultList();
        assertTrue(inMadison.stream().allMatch(owner -> owner.telephone().equals("5552222")));
        assertEquals(4, owners.select().where(Owner_.telephone, EQUALS, "5552222").getResultCount());
    }

    @Test
    public void setsTheRowsAnExplicitJoinSelects() {
        var owners = orm().entity(Owner.class);
        int updated = owners.update()
                .set(Owner_.telephone, "5553333")
                .innerJoin(Pet.class).on(Owner.class)
                .where(Pet_.name, IN, "Leo", "Basil")
                .executeUpdate();
        assertEquals(2, updated);
        assertEquals("5553333", owners.getById(1).telephone());
        assertEquals("5553333", owners.getById(2).telephone());
    }

    @Test
    public void setsTheRowsASemiJoinSelects() {
        var owners = orm().entity(Owner.class);
        int updated = owners.update()
                .set(Owner_.telephone, "5554444")
                .semiJoin(Pet.class).on(Owner.class)
                .where(Pet_.name, EQUALS, "Leo")
                .executeUpdate();
        assertEquals(1, updated);
        assertEquals("5554444", owners.getById(1).telephone());
    }

    @Test
    public void setsInAnyOrderWithTheConditions() {
        var owners = orm().entity(Owner.class);
        owners.update().where(Owner_.id, EQUALS, 3).set(Owner_.telephone, "5555555").executeUpdate();
        assertEquals("5555555", owners.getById(3).telephone());
    }

    @Test
    public void updatesThroughTheTemplate() {
        orm().updateFrom(City.class).set(City_.name, "Renamed").where(City_.id, EQUALS, 3).executeUpdate();
        assertEquals("Renamed", orm().entity(City.class).getById(3).name());
    }

    @Test
    public void runsThroughAPlan() {
        // A plan binds no fixed values, so the plan sets the value the expression computes.
        var plan = orm().entity(City.class).update().set(City_.name, TemplateString.raw("UPPER(\0)", City_.name)).unsafe().plan();
        assertEquals(orm().entity(City.class).count(), plan.query().executeUpdate());
        assertEquals("MADISON", orm().entity(City.class).getById(2).name());
    }

    @Test
    public void leavesAHintOutWhereTheDatabaseHasNoHintSyntax() {
        var statements = statements(() -> orm().entity(City.class).update().hint("NO_MERGE(x)")
                .set(City_.name, "Hinted").where(City_.id, EQUALS, 1).executeUpdate());
        assertFalse(statements.getFirst().contains("NO_MERGE"), statements.getFirst());
        assertEquals("Hinted", orm().entity(City.class).getById(1).name());
    }

    // Callbacks.

    @Test
    public void firesNoEntityCallbacks() {
        var calls = new ArrayList<String>();
        var orm = orm().withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeUpdate(City entity) {
                calls.add("beforeUpdate");
                return entity;
            }

            @Override
            public void afterUpdate(City entity) {
                calls.add("afterUpdate");
            }
        });
        orm.entity(City.class).update().set(City_.name, "Quiet").where(City_.id, EQUALS, 1).executeUpdate();
        assertEquals("Quiet", orm.entity(City.class).getById(1).name());
        assertEquals(List.of(), calls);
    }

    // Clauses an UPDATE does not carry.

    @Test
    public void refusesTheClausesOfASelect() {
        var cities = orm().entity(City.class);
        assertThrows(PersistenceException.class, () -> cities.update().distinct());
        assertThrows(PersistenceException.class, () -> cities.update().limit(1));
        assertThrows(PersistenceException.class, () -> cities.update().offset(1));
        assertThrows(PersistenceException.class, () -> cities.update().forShare());
        assertThrows(PersistenceException.class, () -> cities.update().forUpdate());
        assertThrows(PersistenceException.class, () -> cities.update().forLock(TemplateString.of("FOR UPDATE")));
        assertThrows(PersistenceException.class,
                () -> cities.update().set(City_.name, "x").unsafe().getResultStream());
        assertThrows(PersistenceException.class,
                () -> cities.update().set(City_.name, "x").where(City_.id, EQUALS, 1).scroll(Scrollable.of(City_.id, 10)));
        assertThrows(PersistenceException.class,
                () -> cities.select().whereExists(cities.update().set(City_.name, "x").where(City_.id, EQUALS, 1)).getResultList());
    }
}
