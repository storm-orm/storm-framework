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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static st.orm.TransactionPropagation.NOT_SUPPORTED;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;
import static st.orm.core.spi.FetchSizeSqlDialectProviderImpl.READ_ONLY_TRANSACTION_STATEMENT;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import st.orm.TransactionOptions;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * The dialect's read-only statement is the first statement of every read-only transaction Storm's own JDBC
 * transaction handling opens, and of nothing else. The test dialect returns a user variable assignment H2 accepts,
 * and a recording data source lists every statement with the connection it ran on.
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
    private ORMTemplate orm;

    @BeforeEach
    void prepare() throws SQLException {
        var h2 = DataSourceBuilder.create()
                .url("jdbc:h2:mem:readOnlyTransactionStatement;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS read_only_statement (id INTEGER PRIMARY KEY)");
            statement.execute("DELETE FROM read_only_statement");
        }
        recording = new RecordingDataSource(h2);
        // Storm's own providers, whatever the module's test classpath registers for its other tests.
        orm = ORMTemplate.builder(recording)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        // The template opens a connection of its own once, to detect the dialect; that one is not part of any
        // transaction's sequence.
        read();
        recording.reset();
    }

    private void read() {
        orm.query(TemplateString.of(READ)).getSingleResult(Long.class);
    }

    private void write() {
        orm.query(TemplateString.of(WRITE)).executeUpdate();
    }

    private static void transaction(TransactionOptions options, Runnable block) {
        TransactionRunner.<Void, RuntimeException>execute(options, transaction -> {
            block.run();
            return null;
        });
    }

    @Test
    void theStatementOpensAReadOnlyTransactionAndRunsOnce() {
        transaction(READ_ONLY, () -> {
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
        transaction(READ_WRITE, this::write);
        assertEquals(List.of("1:prepare(" + WRITE + ")"), recording.calls);
    }

    @Test
    void aRequiresNewFrameInsideAReadOnlyOneOpensReadWriteOnItsOwnConnection() {
        transaction(READ_ONLY, () -> {
            read();
            transaction(READ_WRITE_NEW, this::write);
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
        transaction(READ_ONLY, () -> {
            read();
            transaction(READ_WRITE, this::read);
        });
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "1:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aNotSupportedFrameRunsOutsideAnyTransaction() {
        transaction(READ_ONLY, () -> {
            read();
            transaction(READ_WRITE_NOT_SUPPORTED, this::write);
        });
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "2:prepare(" + WRITE + ")"), recording.calls);
    }

    @Test
    void everyReadOnlyTransactionOpensItsOwn() {
        transaction(READ_ONLY, this::read);
        transaction(READ_ONLY, this::read);
        assertEquals(List.of(
                "1:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "1:prepare(" + READ + ")",
                "2:execute(" + READ_ONLY_TRANSACTION_STATEMENT + ")",
                "2:prepare(" + READ + ")"), recording.calls);
    }

    @Test
    void aStatementOutsideAnyTransactionOpensNothing() {
        read();
        assertEquals(List.of("1:prepare(" + READ + ")"), recording.calls);
    }
}
