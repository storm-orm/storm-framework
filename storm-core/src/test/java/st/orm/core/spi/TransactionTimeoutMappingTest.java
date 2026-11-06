/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionPropagation.REQUIRED;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import st.orm.PersistenceException;
import st.orm.TransactionOptions;
import st.orm.TransactionTimedOutException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * A statement that fails once the transaction's deadline has passed timed out, whatever exception the driver reports:
 * a driver that cancels a timed-out statement as it cancels any other reports the cancellation, not a timeout.
 */
class TransactionTimeoutMappingTest {

    private static final TransactionOptions ONE_SECOND = new TransactionOptions(REQUIRED, null, 1, null);

    private ORMTemplate orm;

    @BeforeEach
    void prepare() {
        var h2 = DataSourceBuilder.create()
                .url("jdbc:h2:mem:timeoutMapping;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        orm = ORMTemplate.builder(h2)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
    }

    /** Binds a connection to the frame and lets the deadline pass, as a long statement does. */
    private void runPastTheDeadline() {
        orm.query(TemplateString.of("SELECT 1")).getSingleResult(Integer.class);
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aDatabaseFailurePastTheDeadlineIsATimeout() {
        var exception = assertThrows(TransactionTimedOutException.class, () ->
                TransactionRunner.<Object, RuntimeException>execute(ONE_SECOND, transaction -> {
                    runPastTheDeadline();
                    // How a driver without a timeout exception reports its cancelled statement.
                    throw new PersistenceException(new SQLException("canceling statement due to user request", "57014"));
                }));
        assertTrue(exception.getMessage().contains("timeout=1s"), exception.getMessage());
        assertTrue(exception.getCause() instanceof PersistenceException, String.valueOf(exception.getCause()));
    }

    @Test
    void aTimeoutExceptionAnywhereInTheCauseChainIsATimeout() {
        assertThrows(TransactionTimedOutException.class, () ->
                TransactionRunner.<Object, RuntimeException>execute(ONE_SECOND, transaction -> {
                    throw new PersistenceException(new IllegalStateException(new SQLTimeoutException("statement timeout")));
                }));
    }

    @Test
    void aFailureInACancelledTransactionIsNoTimeout() {
        assertThrows(PersistenceException.class, () ->
                TransactionRunner.<Object, RuntimeException>execute(ONE_SECOND, transaction -> {
                    runPastTheDeadline();
                    transaction.cancel();
                    throw new PersistenceException(new SQLException("canceling statement due to user request", "57014"));
                }));
    }

    @Test
    void aFailureOutsideTheDatabasePastTheDeadlineIsNoTimeout() {
        assertThrows(IllegalStateException.class, () ->
                TransactionRunner.<Object, RuntimeException>execute(ONE_SECOND, transaction -> {
                    runPastTheDeadline();
                    throw new IllegalStateException("not the database");
                }));
    }
}
