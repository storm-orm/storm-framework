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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionPropagation.REQUIRED;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.AcceptAdvice;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.TransactionOptions;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlLog;

/**
 * A unit of work that inserts an owner and then its pet outside any transaction is advised in its summary; the
 * same writes in a transaction block, a block that opens at its first statement included, are not.
 */
class WritesWithoutTransactionTest {

    public record Owner(@PK @Nullable Integer id, String name) implements Entity<Integer> {}

    public record Pet(@PK @Nullable Integer id, String name, @FK Owner owner) implements Entity<Integer> {}

    private ORMTemplate orm;

    @BeforeEach
    void database() throws SQLException {
        DataSource h2 = new DriverManagerDataSource("jdbc:h2:mem:writes_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owner (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute("CREATE TABLE pet (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL, "
                    + "owner_id INTEGER NOT NULL REFERENCES owner(id))");
        }
        // Storm's own providers, whatever the module's test classpath registers for its other tests.
        orm = ORMTemplate.builder(h2)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        AdviceLog.reset();
    }

    private void ownerThenPet() {
        var owner = orm.entity(Owner.class).insertAndFetch(new Owner(null, "Betty"));
        orm.entity(Pet.class).insert(new Pet(null, "Basil", owner));
    }

    /** Runs the work in a recorded unit of work and returns the summary's advice. */
    private static String summary(Runnable work) {
        var scope = SqlLog.open("work");
        try {
            work.run();
        } finally {
            scope.close();
        }
        return SqlLogRenderer.render(scope.summary());
    }

    @Test
    void anOwnerAndThenItsPetOutsideATransactionAreAdvised() {
        var summary = summary(this::ownerThenPet);
        assertTrue(summary.contains("\tadvice: the unit of work wrote Owner and then Pet, which references Owner, "
                + "each committing on its own, so when a later write fails, the earlier ones stay."
                + System.lineSeparator() + "\t\tfix: run them in one transaction." + System.lineSeparator()
                + "\t\tsee: https://orm.st/docs/advice#writes-without-transaction"), summary);
        var summary2 = summary(this::ownerThenPet);
        assertEquals(1, summary2.lines().filter(line -> line.contains("#writes-without-transaction")).count(),
                summary2);
    }

    @Test
    void theSameWritesInATransactionAreNot() {
        var summary = summary(() -> TransactionRunner.<Void, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, null, null), transaction -> {
                    ownerThenPet();
                    return null;
                }));
        assertFalse(summary.contains("advice:"), summary);
    }

    @Test
    @AcceptAdvice("writes-without-transaction")
    void acceptedOnTheMethodRunningTheUnitOfWorkTheyAreNot() {
        var summary = summary(this::ownerThenPet);
        assertFalse(summary.contains("advice:"), summary);
    }
}
