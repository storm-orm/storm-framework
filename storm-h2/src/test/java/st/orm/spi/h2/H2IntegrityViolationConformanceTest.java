package st.orm.spi.h2;

import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against H2. The suite commits, so it runs without the per-test
 * rollback.
 */
@StormTest(rollback = false)
public class H2IntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {

    /**
     * H2 measures a character column in UTF-16 units, so five supplementary characters do not fit a column of five.
     */
    @Override
    protected boolean storesSupplementaryCharactersByCount() {
        return false;
    }

    /**
     * H2 names the column of a number with more digits than its column holds.
     */
    @Override
    protected boolean namesColumnOfRangeViolation() {
        return true;
    }
}
