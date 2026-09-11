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
package st.orm.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static st.orm.TransactionPropagation.NOT_SUPPORTED;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;
import static st.orm.spring.ReadOnlyStatementSqlDialectProviderImpl.READ_ONLY_TRANSACTION_STATEMENT;
import static st.orm.template.Transactions.transaction;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import st.orm.TransactionOptions;
import st.orm.template.ORMTemplate;

/**
 * The dialect's read-only statement is the first statement of every read-only transaction Storm opens through
 * Spring's transaction manager, and of nothing else: a Spring transaction that was already open when Storm's
 * statement ran is left to Spring's own {@code enforceReadOnly} setting. The module's test dialect returns a user
 * variable assignment H2 accepts, and a recording data source lists every statement with the connection it ran on.
 */
class ReadOnlyTransactionStatementTest {

    private static final TransactionOptions READ_ONLY = new TransactionOptions(REQUIRED, null, null, true);
    private static final TransactionOptions READ_WRITE = new TransactionOptions(REQUIRED, null, null, false);
    private static final TransactionOptions READ_WRITE_NEW = new TransactionOptions(REQUIRES_NEW, null, null, false);
    private static final TransactionOptions READ_WRITE_NOT_SUPPORTED =
            new TransactionOptions(NOT_SUPPORTED, null, null, false);

    private static final String READ = "SELECT COUNT(*) FROM read_only_statement";
    private static final String WRITE = "INSERT INTO read_only_statement (id) VALUES (1)";

    private RecordingDataSource recording;
    private DataSourceTransactionManager transactionManager;
    private ORMTemplate orm;

    @BeforeEach
    void prepare() throws SQLException {
        var h2 = DataSourceBuilder.create()
                .url("jdbc:h2:mem:springReadOnlyTransactionStatement;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS read_only_statement (id INTEGER PRIMARY KEY)");
            statement.execute("DELETE FROM read_only_statement");
        }
        recording = new RecordingDataSource(h2);
        transactionManager = new DataSourceTransactionManager(recording);
        orm = SpringOrmTemplate.of(recording, () -> List.of(transactionManager));
        // The template opens a connection of its own once, to detect the dialect; that one is not part of any
        // transaction's sequence.
        read();
        recording.reset();
    }

    private void read() {
        orm.query(READ).getSingleResult(Long.class);
    }

    private void write() {
        orm.query(WRITE).executeUpdate();
    }

    private static void run(TransactionOptions options, Runnable block) {
        transaction(options, transaction -> {
            block.run();
            return null;
        });
    }

    private TransactionTemplate springTransaction(boolean readOnly) {
        var template = new TransactionTemplate(transactionManager);
        template.setReadOnly(readOnly);
        return template;
    }

    @Test
    void theStatementOpensAReadOnlyTransactionAndRunsOnce() {
        run(READ_ONLY, () -> {
            read();
            read();
        });
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "1:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aReadWriteTransactionSendsNoStatement() {
        run(READ_WRITE, this::write);
        assertEquals(List.of("1:prepare(" + WRITE + ")"), recording.calls);
    }

    @Test
    void aRequiresNewFrameInsideAReadOnlyOneOpensReadWriteOnItsOwnConnection() {
        run(READ_ONLY, () -> {
            read();
            run(READ_WRITE_NEW, this::write);
            read();
        });
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "2:prepare(" + WRITE + ")",
                "1:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aJoinedFrameRunsInTheTransactionAlreadyOpened() {
        run(READ_ONLY, () -> {
            read();
            run(READ_WRITE, this::read);
        });
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "1:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aNotSupportedFrameRunsOutsideAnyTransaction() {
        run(READ_ONLY, () -> {
            read();
            run(READ_WRITE_NOT_SUPPORTED, this::write);
        });
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "2:prepare(" + WRITE + ")"), recording.calls);
    }

    @Test
    void aSpringTransactionStormDidNotOpenIsLeftToSpring() {
        springTransaction(true).executeWithoutResult(status -> read());
        assertEquals(List.of("1:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aStormBlockJoiningASpringTransactionOpensNothing() {
        springTransaction(true).executeWithoutResult(status -> run(READ_ONLY, this::read));
        assertEquals(List.of("1:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aRequiresNewBlockInsideASpringTransactionOpensItsOwn() {
        springTransaction(false).executeWithoutResult(status -> {
            read();
            run(new TransactionOptions(REQUIRES_NEW, null, null, true), this::read);
        });
        assertEquals(List.of(
                "1:prepare(" + READ + ")",
                "2:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "2:prepare(" + READ + ")"), recording.calls);
    }
}
