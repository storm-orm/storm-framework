package st.orm.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.ORMTemplate.of;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.jackson.model.Owner;
import st.orm.test.StormTest;

/**
 * A converted column is stored as one type and held as another: the address lives in a single JSON column while the
 * record holds an {@code Address}. The metamodel has to address it as the column it is, not as the parts of the
 * record it holds.
 */
@StormTest(scripts = "/data.sql")
public class JsonMetamodelTest {

    private DataSource dataSource;

    @BeforeEach
    void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Test
    public void convertedFieldIsAColumnRatherThanAnInlineRecord() {
        var path = Metamodel.of(Owner.class, "address");
        assertTrue(path.isColumn(), "A converted field is stored as its own column.");
        assertFalse(path.isInline(), "A converted field is not the parts of the record it holds.");
    }

    @Test
    public void convertedColumnIsFilteredByItsStoredValue() {
        var orm = of(dataSource);
        List<Owner> owners = orm.entity(Owner.class).select()
                .where(Metamodel.of(Owner.class, "address"), Operator.EQUALS,
                        "{\"address\":\"638 Cardinal Ave.\",\"city\":\"Sun Prairie\"}")
                .getResultList();
        assertEquals(1, owners.size());
        assertEquals("Betty", owners.getFirst().firstName());
    }
}
