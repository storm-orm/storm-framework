package st.orm.core;

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
import st.orm.DbColumn;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.spi.MiscountedValue;
import st.orm.core.template.ORMTemplate;

/**
 * A converter returns one value per column it declares; a query comparing a converted field refuses a converter that
 * returns another number of values.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ConverterValueCountIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @DbTable("owner")
    public record OwnerWithMiscountedTelephone(
            @PK Integer id,
            String firstName,
            String lastName,
            @DbColumn("telephone") MiscountedValue telephone
    ) implements Entity<Integer> {}

    @Test
    void aConverterReturningMoreValuesThanItsColumnsIsRefused() {
        var owners = ORMTemplate.of(dataSource).entity(OwnerWithMiscountedTelephone.class);
        Metamodel<OwnerWithMiscountedTelephone, MiscountedValue> telephone = Metamodel.of(OwnerWithMiscountedTelephone.class, "telephone");
        var exception = assertThrows(PersistenceException.class,
                () -> owners.select().where(telephone, EQUALS, new MiscountedValue("6085551023")).getResultList());
        assertTrue(exception.getMessage().contains("Converter for 'telephone' returned 2 value(s), expected 1."), exception.getMessage());
    }
}
