package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.CONTAINS;
import static st.orm.Operator.CONTAINS_IGNORE_CASE;
import static st.orm.Operator.ENDS_WITH;
import static st.orm.Operator.ENDS_WITH_IGNORE_CASE;
import static st.orm.Operator.EQUALS_IGNORE_CASE;
import static st.orm.Operator.NOT_CONTAINS;
import static st.orm.Operator.NOT_CONTAINS_IGNORE_CASE;
import static st.orm.Operator.NOT_EQUALS_IGNORE_CASE;
import static st.orm.Operator.STARTS_WITH;
import static st.orm.Operator.STARTS_WITH_IGNORE_CASE;
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate.PositionalParameter;

/**
 * The text-matching operators compare a column against literal text: the statement renders {@code LIKE ? ESCAPE '!'},
 * the bound pattern is the escaped text with the operator's wildcards, and a value that is not text, or a path that
 * spans several columns, is refused with a message naming the path. The operators that ignore case lower both sides
 * in the statement and bind the same value, and refuse what the others refuse.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class TextMatchIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private List<String> cityNames(st.orm.Operator operator, String text) {
        return ORMTemplate.of(dataSource).entity(City.class).select()
                .where(City_.name, operator, text)
                .getResultList().stream()
                .map(City::name)
                .sorted()
                .toList();
    }

    @Test
    public void matchesTheTextAtTheAnchorTheOperatorNames() {
        assertEquals(List.of("Madison", "McFarland", "Monona"), cityNames(STARTS_WITH, "M"));
        assertEquals(List.of("Madison"), cityNames(ENDS_WITH, "son"));
        assertEquals(List.of("McFarland"), cityNames(CONTAINS, "Far"));
    }

    @Test
    public void rendersTheEscapeClauseAndBindsTheEscapedPattern() {
        var captured = new AtomicReference<Sql>();
        observe(captured::set, () -> ORMTemplate.of(dataSource).entity(City.class).select()
                .where(City_.name, NOT_CONTAINS, "5!_%")
                .getResultList());
        var sql = captured.get();
        assertTrue(sql.statement().contains("NOT LIKE ? ESCAPE '!'"), sql.statement());
        assertEquals("%5!!!_!%%", ((PositionalParameter) sql.parameters().getFirst()).dbValue());
    }

    @Test
    public void refusesAValueThatIsNotText() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(City.class)
                .select()
                .where(City_.id, CONTAINS, 1)
                .getResultList());
        assertTrue(exception.getMessage().contains("CONTAINS compares id against text, but was given Integer"),
                exception.getMessage());
    }

    @Test
    public void refusesAPathThatSpansSeveralColumns() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).getById(1);
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(Owner_.address, CONTAINS, owner.address())
                .getResultList());
        assertTrue(exception.getMessage().contains("CONTAINS compares a single column against text, but address spans several columns"),
                exception.getMessage());
    }

    @Test
    public void theOperatorsIgnoringCaseMatchTheTextInAnyCase() {
        assertEquals(List.of("Madison", "McFarland", "Monona"), cityNames(STARTS_WITH_IGNORE_CASE, "m"));
        assertEquals(List.of("Madison"), cityNames(ENDS_WITH_IGNORE_CASE, "SON"));
        assertEquals(List.of("McFarland"), cityNames(CONTAINS_IGNORE_CASE, "FAR"));
        assertEquals(List.of("Madison"), cityNames(EQUALS_IGNORE_CASE, "mADISON"));
        assertEquals(List.of(), cityNames(EQUALS_IGNORE_CASE, "mADIS"));
        assertTrue(cityNames(NOT_EQUALS_IGNORE_CASE, "mADISON").stream().noneMatch("Madison"::equals));
    }

    @Test
    public void ignoringCaseLowersBothSidesAndBindsTheEscapedPattern() {
        var captured = new AtomicReference<Sql>();
        observe(captured::set, () -> ORMTemplate.of(dataSource).entity(City.class).select()
                .where(City_.name, NOT_CONTAINS_IGNORE_CASE, "5!_%")
                .getResultList());
        var sql = captured.get();
        assertTrue(sql.statement().contains("LOWER(c.name) NOT LIKE LOWER(?) ESCAPE '!'"), sql.statement());
        assertEquals("%5!!!_!%%", ((PositionalParameter) sql.parameters().getFirst()).dbValue());
    }

    @Test
    public void equalsIgnoreCaseLowersBothSidesAndBindsTheTextAsItIs() {
        var captured = new AtomicReference<Sql>();
        observe(captured::set, () -> ORMTemplate.of(dataSource).entity(City.class).select()
                .where(City_.name, EQUALS_IGNORE_CASE, "50%_!")
                .getResultList());
        var sql = captured.get();
        assertTrue(sql.statement().contains("LOWER(c.name) = LOWER(?)"), sql.statement());
        assertEquals("50%_!", ((PositionalParameter) sql.parameters().getFirst()).dbValue());
    }

    @Test
    public void equalsIgnoreCaseRefusesAValueThatIsNotText() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(City.class)
                .select()
                .where(City_.id, EQUALS_IGNORE_CASE, 1)
                .getResultList());
        assertTrue(exception.getMessage().contains("EQUALS_IGNORE_CASE compares id against text, but was given Integer"),
                exception.getMessage());
    }

    @Test
    public void equalsIgnoreCaseRefusesAPathThatSpansSeveralColumns() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).getById(1);
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(Owner_.address, EQUALS_IGNORE_CASE, owner.address())
                .getResultList());
        assertTrue(exception.getMessage().contains("EQUALS_IGNORE_CASE compares a single column against text, but address spans several columns"),
                exception.getMessage());
    }

    @Test
    public void theDefaultDialectEscapesTheStandardWildcardsAndTheEscapeCharacter() {
        SqlDialect dialect = ORMTemplate.of(dataSource).dialect();
        assertEquals("50!% !_ a!!b [x] \\", dialect.escapeLike("50% _ a!b [x] \\"));
    }
}
