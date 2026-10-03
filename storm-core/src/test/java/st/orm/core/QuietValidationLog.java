package st.orm.core;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * Keeps schema validation findings out of the build log while a test runs.
 *
 * <p>Tests that validate types the schema does not hold provoke findings on purpose, and validation logs each one.
 * The tests read the findings from the returned results instead.</p>
 */
public final class QuietValidationLog implements BeforeEachCallback, AfterEachCallback {

    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger("st.orm.validation");

    @Override
    public void beforeEach(ExtensionContext context) {
        LOGGER.setAdditive(false);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        LOGGER.setAdditive(true);
    }
}
