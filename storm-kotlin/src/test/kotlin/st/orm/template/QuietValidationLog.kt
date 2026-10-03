package st.orm.template

import ch.qos.logback.classic.Logger
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory

/**
 * Keeps schema validation findings out of the build log while a test runs.
 *
 * Tests that validate types the schema does not hold provoke findings on purpose, and validation logs each one. The
 * tests read the findings from the returned results instead.
 */
internal class QuietValidationLog :
    BeforeEachCallback,
    AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        logger.isAdditive = false
    }

    override fun afterEach(context: ExtensionContext) {
        logger.isAdditive = true
    }

    private companion object {
        val logger = LoggerFactory.getLogger("st.orm.validation") as Logger
    }
}
