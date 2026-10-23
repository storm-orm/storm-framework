package st.orm.spi.h2;

import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class H2SchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {
    @Override
    protected boolean indexesForeignKeys() {
        return true;
    }

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_folded VARCHAR(50) GENERATED ALWAYS AS (LOWER(name)) NOT NULL)";
    }
}
