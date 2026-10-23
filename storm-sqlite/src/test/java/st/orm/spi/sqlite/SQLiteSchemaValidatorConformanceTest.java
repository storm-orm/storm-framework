package st.orm.spi.sqlite;

import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.test.StormTest;

@StormTest(url = "jdbc:sqlite:target/conformance.db", scripts = "/data.sql")
public class SQLiteSchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {

    @Override
    protected boolean supportsSequences() {
        return false;
    }

    @Override
    protected String expressionIndexDdl() {
        return "CREATE UNIQUE INDEX index_probe_lower ON index_probe (lower(a))";
    }

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, name_folded TEXT GENERATED ALWAYS AS (LOWER(name)) STORED NOT NULL)";
    }
}
