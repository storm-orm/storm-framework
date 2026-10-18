package st.orm.template;

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.template.model.City;
import st.orm.template.model.City_;
import st.orm.template.model.Owner;
import st.orm.template.model.Owner_;
import st.orm.template.model.Pet;
import st.orm.template.model.Pet_;

/**
 * The UPDATE builder through the Java API: values, refs, null and expressions, a condition through a related table,
 * and the refusals.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class UpdateBuilderTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ORMTemplate orm;

    @Test
    public void setsColumnsOnTheRowsTheConditionSelects() {
        var owners = orm.entity(Owner.class);
        var before = owners.getById(1);
        int updated = owners.update()
                .set(Owner_.firstName, "Bette")
                .setNull(Owner_.telephone)
                .where(Owner_.id, EQUALS, 1)
                .executeUpdate();
        assertEquals(1, updated);
        var after = owners.getById(1);
        assertEquals("Bette", after.firstName());
        assertNull(after.telephone());
        assertEquals(before.version() + 1, after.version());
        assertEquals("George", owners.getById(2).firstName());
    }

    @Test
    public void setsTheRowsAPathToARelatedTableSelects() {
        var owners = orm.entity(Owner.class);
        int updated = owners.update()
                .set(Owner_.telephone, "5552222")
                .where(Owner_.address.city.name, EQUALS, "Madison")
                .executeUpdate();
        assertEquals(4, updated);
        assertEquals(4, owners.select().where(Owner_.telephone, EQUALS, "5552222").getResultCount());
    }

    @Test
    public void computesTheValueFromTheRow() {
        var cities = orm.entity(City.class);
        cities.update()
                .set(City_.name, RAW."UPPER(\{City_.name})")
                .where(City_.id, EQUALS, 2)
                .executeUpdate();
        assertEquals("MADISON", cities.getById(2).name());
        assertEquals("Sun Paririe", cities.getById(1).name());
    }

    @Test
    public void setsAForeignKeyToARecordToARefAndToNull() {
        var pets = orm.entity(Pet.class);
        pets.update().set(Pet_.owner, orm.entity(Owner.class).getById(2)).where(Pet_.id, EQUALS, 1).executeUpdate();
        assertEquals(2, pets.getById(1).owner().id());
        pets.update().set(Pet_.owner, Ref.of(Owner.class, 3)).where(Pet_.id, EQUALS, 1).executeUpdate();
        assertEquals(3, pets.getById(1).owner().id());
        pets.update().setNull(Pet_.owner).where(Pet_.id, EQUALS, 1).executeUpdate();
        assertNull(pets.getById(1).owner());
    }

    @Test
    public void updatesThroughTheTemplate() {
        assertEquals(1, orm.updateFrom(City.class).set(City_.name, "Template").where(City_.id, EQUALS, 4).executeUpdate());
        assertEquals("Template", orm.entity(City.class).getById(4).name());
    }

    @Test
    public void refusesFieldsAnUpdateDoesNotSet() {
        var owners = orm.entity(Owner.class);
        assertTrue(assertThrows(PersistenceException.class, () -> owners.update().set(Owner_.id, 9))
                .getMessage().contains("primary key"));
        assertTrue(assertThrows(PersistenceException.class, () -> owners.update().set(Owner_.version, 9))
                .getMessage().contains("version"));
        assertTrue(assertThrows(PersistenceException.class, () -> owners.update().setNull(Owner_.firstName))
                .getMessage().contains("not nullable"));
        assertTrue(assertThrows(PersistenceException.class, () -> orm.entity(Pet.class).update().setNull(Pet_.birthDate))
                .getMessage().contains("not updatable"));
        assertTrue(assertThrows(PersistenceException.class, () -> orm.entity(Pet.class).update().set(Pet_.owner.firstName, "x"))
                .getMessage().contains("related table"));
    }

    @Test
    public void refusesAnUpdateWithoutAConditionUnlessUnsafe() {
        var cities = orm.entity(City.class);
        assertThrows(PersistenceException.class, () -> cities.update().set(City_.name, "Everywhere").executeUpdate());
        assertEquals(6, cities.update().set(City_.name, "Everywhere").unsafe().executeUpdate());
    }

    @Test
    public void refusesSetOnASelect() {
        assertThrows(PersistenceException.class, () -> orm.entity(City.class).select().set(City_.name, "x"));
    }
}
