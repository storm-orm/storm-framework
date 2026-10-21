package st.orm.spi.h2;

import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class H2SchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {
    @Override
    protected boolean indexesForeignKeys() {
        return true;
    }
}
