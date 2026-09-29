package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.ArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;

/**
 * A hint is the database's own text, refused only where it could end the comment that carries it or the statement,
 * and left out on a database without hint syntax, as H2 is. Where each dialect renders it is covered by the
 * Technology Compatibility Kit.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class OptimizerHintIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void refusesABlankHint() {
        var exception = assertThrows(PersistenceException.class,
                () -> ORMTemplate.of(dataSource).entity(City.class).select().hint(" "));
        assertEquals("An optimizer hint cannot be blank.", exception.getMessage());
    }

    @Test
    public void refusesAHintThatWouldEndItsComment() {
        var exception = assertThrows(PersistenceException.class,
                () -> ORMTemplate.of(dataSource).entity(City.class).select().hint("NO_MERGE(x) */ DROP"));
        assertTrue(exception.getMessage().contains("comment terminator"), exception.getMessage());
    }

    @Test
    public void refusesAHintThatWouldEndTheStatement() {
        var exception = assertThrows(PersistenceException.class,
                () -> ORMTemplate.of(dataSource).entity(City.class).delete().hint("RECOMPILE; DELETE"));
        assertTrue(exception.getMessage().contains("semicolon"), exception.getMessage());
    }

    @Test
    public void aDatabaseWithoutHintSyntaxLeavesTheHintOut() {
        var statements = new ArrayList<String>();
        var cities = observe(sql -> statements.add(sql.statement()), () -> ORMTemplate.of(dataSource)
                .entity(City.class).select().hint("NO_MERGE(recent)").getResultList());
        assertEquals(ORMTemplate.of(dataSource).entity(City.class).select().getResultList(), cities);
        assertFalse(statements.getFirst().contains("NO_MERGE"), statements.getFirst());
    }
}
