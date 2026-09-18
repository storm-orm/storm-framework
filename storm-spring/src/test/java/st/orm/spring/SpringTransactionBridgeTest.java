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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE;
import static st.orm.TransactionIsolation.READ_COMMITTED;
import static st.orm.TransactionIsolation.REPEATABLE_READ;
import static st.orm.TransactionIsolation.SERIALIZABLE;
import static st.orm.TransactionPropagation.MANDATORY;
import static st.orm.TransactionPropagation.REQUIRES_NEW;
import static st.orm.template.Transactions.transaction;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import st.orm.IllegalTransactionStateException;
import st.orm.PersistenceException;
import st.orm.ReadOnlyTransactionException;
import st.orm.TransactionOptions;
import st.orm.repository.EntityRepository;
import st.orm.spring.model.Pet;
import st.orm.spring.model.Visit;
import st.orm.template.ORMTemplate;
import st.orm.template.Transactions;
import st.orm.template.impl.BuilderImpl;

/**
 * Verifies that Storm's programmatic transaction API ({@link Transactions}) is bridged into Spring's
 * transaction managers when the template is composed with the Spring providers, and that Storm blocks
 * cooperate with Spring-managed transactions.
 */
class SpringTransactionBridgeTest {

    private DataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private ORMTemplate orm;
    private EntityRepository<Visit, Integer> visits;
    private Pet pet;

    @BeforeEach
    void setUp() {
        dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:bridgetest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        orm = SpringOrmTemplate.of(dataSource, () -> List.of(transactionManager));
        visits = orm.entity(Visit.class);
        pet = orm.entity(Pet.class).getById(1);
    }

    private void insertVisit(String description) {
        visits.insert(new Visit(null, LocalDate.now(), description, pet, Instant.now()));
    }

    private TransactionTemplate springTransaction(int isolationLevel) {
        var springTransaction = new TransactionTemplate(transactionManager);
        springTransaction.setIsolationLevel(isolationLevel);
        return springTransaction;
    }

    private static TransactionOptions isolation(st.orm.TransactionIsolation isolation) {
        return TransactionOptions.defaults().withIsolation(isolation);
    }

    @Test
    void programmaticTransactionCommitsThroughSpringManager() {
        long before = visits.count();
        transaction(tx -> {
            insertVisit("committed");
            return null;
        });
        assertEquals(before + 1, visits.count());
    }

    @Test
    void programmaticRollbackDiscardsWrites() {
        long before = visits.count();
        transaction(tx -> {
            insertVisit("discarded");
            assertEquals(before + 1, visits.count());
            tx.setRollbackOnly();
            return null;
        });
        assertEquals(before, visits.count());
    }

    @Test
    void checkedExceptionPropagatesTransparentlyAndRollsBack() {
        long before = visits.count();
        assertThrows(IOException.class, () ->
                transaction(tx -> {
                    insertVisit("failed");
                    throw new IOException("Simulated failure after the insert");
                }));
        assertEquals(before, visits.count());
    }

    @Test
    void requiresNewCommitsIndependentlyOfOuterRollback() {
        long before = visits.count();
        transaction(outer -> {
            insertVisit("outer, discarded");
            transaction(REQUIRES_NEW, inner -> {
                insertVisit("inner, committed");
                return null;
            });
            outer.setRollbackOnly();
            return null;
        });
        assertEquals(before + 1, visits.count());
    }

    @Test
    void mandatoryWithoutEnclosingTransactionThrows() {
        // MANDATORY is checked against the enclosing block, or for the outermost block against the Spring
        // transaction active on the thread, and fails the way it does under Storm's own transactions.
        assertThrows(PersistenceException.class, () ->
                transaction(MANDATORY, tx -> {
                    insertVisit("never");
                    return null;
                }));
    }

    @Test
    void mandatoryJoinsAnEnclosingSpringManagedTransaction() {
        long before = visits.count();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            // The outermost Storm block sees the Spring transaction the surrounding code opened.
            transaction(MANDATORY, tx -> {
                insertVisit("joined, discarded");
                return null;
            });
            status.setRollbackOnly();
        });
        assertEquals(before, visits.count());
    }

    @Test
    void stormBlockJoinsSpringManagedTransaction() {
        long before = visits.count();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            // A Storm programmatic block inside a Spring-managed transaction joins it (REQUIRED).
            transaction(tx -> {
                insertVisit("joined, discarded");
                return null;
            });
            assertEquals(before + 1, visits.count());
            status.setRollbackOnly();
        });
        // The Spring rollback discarded the write made by the joined Storm block.
        assertEquals(before, visits.count());
    }

    @Test
    void blockStatingAnIsolationInsideASpringTransactionAtTheDefaultIsRefused() {
        // The refused block fails as a joined block does, so the transaction it joined rolls back with it.
        IllegalTransactionStateException thrown = assertThrows(IllegalTransactionStateException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        transaction(isolation(REPEATABLE_READ), tx -> visits.count())));
        assertTrue(thrown.getMessage().startsWith("A REQUIRED block states REPEATABLE_READ isolation but joins a "
                + "transaction running at the database's default isolation level"), thrown.getMessage());
    }

    @Test
    void blockStatingAStricterIsolationThanTheSpringTransactionIsRefused() {
        IllegalTransactionStateException thrown = assertThrows(IllegalTransactionStateException.class, () ->
                springTransaction(ISOLATION_READ_COMMITTED).executeWithoutResult(status ->
                        transaction(isolation(SERIALIZABLE), tx -> visits.count())));
        assertTrue(thrown.getMessage().startsWith("A REQUIRED block states SERIALIZABLE isolation but joins a "
                + "READ_COMMITTED transaction"), thrown.getMessage());
    }

    @Test
    void joinedBlockStatingAStricterIsolationThanTheStormTransactionIsRefused() {
        IllegalTransactionStateException thrown = assertThrows(IllegalTransactionStateException.class, () ->
                transaction(isolation(READ_COMMITTED), outer -> {
                    visits.count();
                    return transaction(isolation(SERIALIZABLE), tx -> visits.count());
                }));
        assertTrue(thrown.getMessage().startsWith("A REQUIRED block states SERIALIZABLE isolation but joins a "
                + "READ_COMMITTED transaction"), thrown.getMessage());
    }

    @Test
    void joinedBlockStatingTheSameOrALowerIsolationJoins() {
        springTransaction(ISOLATION_SERIALIZABLE).executeWithoutResult(status -> {
            long count = visits.count();
            assertEquals(count, (long) transaction(isolation(SERIALIZABLE), tx -> visits.count()));
            assertEquals(count, (long) transaction(isolation(READ_COMMITTED), tx -> visits.count()));
        });
    }

    @Test
    void joinedBlockFollowsTheSpringTransactionsIsolationForTheEntityCache() {
        // The block states no level of its own, so the entity cache takes the level of the Spring transaction
        // the block joined.
        springTransaction(ISOLATION_REPEATABLE_READ).executeWithoutResult(status ->
                transaction(tx -> {
                    Visit first = visits.getById(1);
                    assertSame(first, visits.getById(1));
                    return null;
                }));
        springTransaction(ISOLATION_READ_COMMITTED).executeWithoutResult(status ->
                transaction(tx -> {
                    Visit first = visits.getById(1);
                    assertNotSame(first, visits.getById(1));
                    return null;
                }));
    }

    @Test
    void readOnlyBlockInsideAReadWriteSpringTransactionRefusesItsOwnWrites() {
        long before = visits.count();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            transaction(TransactionOptions.defaults().withReadOnly(true), tx -> {
                assertEquals(before, visits.count());
                assertThrows(ReadOnlyTransactionException.class, () -> insertVisit("refused"));
                return null;
            });
            insertVisit("written by the Spring transaction");
        });
        assertEquals(before + 1, visits.count());
    }

    @Test
    void blockJoiningAReadOnlyJoinedBlockIsRefusedWhateverItDeclares() {
        long before = visits.count();
        assertThrows(ReadOnlyTransactionException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        transaction(TransactionOptions.defaults().withReadOnly(true), tx ->
                                transaction(TransactionOptions.defaults().withReadOnly(false), inner -> {
                                    insertVisit("refused inside a read-only block");
                                    return null;
                                }))));
        assertEquals(before, visits.count());
    }

    @Test
    void aValidatingManagersRefusalIsReportedAgainstTheBlock() {
        transactionManager.setValidateExistingTransaction(true);
        IllegalTransactionStateException thrown = assertThrows(IllegalTransactionStateException.class, () ->
                springTransaction(ISOLATION_SERIALIZABLE).executeWithoutResult(status ->
                        transaction(isolation(READ_COMMITTED), tx -> visits.count())));
        assertTrue(thrown.getMessage().startsWith("Transaction manager "
                + DataSourceTransactionManager.class.getName()
                + " refused the REQUIRED block's participation in the surrounding transaction"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("REQUIRES_NEW"), thrown.getMessage());
    }

    @Test
    void joinedBlockDefersCallbacksToTheSpringCommit() {
        var events = new java.util.ArrayList<String>();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            transaction(tx -> {
                tx.onCommit(() -> events.add("commit"));
                tx.onRollback(() -> events.add("rollback"));
                insertVisit("joined");
                return null;
            });
            // The joined block has returned, but the physical Spring transaction is still open.
            assertEquals(List.of(), events, "callbacks must not fire while the physical transaction is open");
            status.setRollbackOnly();
        });
        assertEquals(List.of("rollback"), events);
    }

    @Test
    void joinedBlockCallbacksFireOnTheSpringCommit() {
        var events = new java.util.ArrayList<String>();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            transaction(tx -> {
                tx.onCommit(() -> events.add("commit"));
                tx.onRollback(() -> events.add("rollback"));
                insertVisit("joined, kept");
                return null;
            });
            assertEquals(List.of(), events, "callbacks must wait for the Spring commit");
        });
        assertEquals(List.of("commit"), events);
    }

    @Test
    void entityCallbackParticipatesInAStormManagedTransaction() {
        var events = new java.util.ArrayList<String>();
        // An entity callback wanting commit-time work participates by opening a joining block of its own. The
        // block joins the Storm-managed transaction the insert runs in, so the callback fires on its outcome.
        ORMTemplate withCallback = orm.withEntityCallback(new st.orm.EntityCallback<Visit>() {
            @Override
            public void afterInsert(Visit entity) {
                transaction(tx -> {
                    tx.onCommit(() -> events.add("inserted " + entity.description()));
                    return null;
                });
            }
        });
        transaction(tx -> {
            withCallback.entity(Visit.class).insert(new Visit(null, LocalDate.now(), "kept", pet, Instant.now()));
            assertEquals(List.of(), events, "the log must wait for the commit");
            return null;
        });
        assertEquals(List.of("inserted kept"), events);

        events.clear();
        assertThrows(IllegalStateException.class, () ->
                transaction(tx -> {
                    withCallback.entity(Visit.class).insert(new Visit(null, LocalDate.now(), "undone", pet, Instant.now()));
                    throw new IllegalStateException("Fail the transaction.");
                }));
        assertEquals(List.of(), events, "a rolled-back insert must not be logged as if it had been applied");
    }

    @Test
    void entityCallbackDefersItsLogToTheSpringCommit() {
        var events = new java.util.ArrayList<String>();
        // The same pattern as under Storm-managed transactions: the callback opens a joining block of its own.
        // The block runs no query, so the detected Spring transaction is what its onCommit waits for.
        ORMTemplate withCallback = orm.withEntityCallback(new st.orm.EntityCallback<Visit>() {
            @Override
            public void afterInsert(Visit entity) {
                transaction(tx -> {
                    tx.onCommit(() -> events.add("inserted " + entity.description()));
                    return null;
                });
            }
        });
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            withCallback.entity(Visit.class).insert(new Visit(null, LocalDate.now(), "kept", pet, Instant.now()));
            assertEquals(List.of(), events, "the log must wait for the commit");
        });
        assertEquals(List.of("inserted kept"), events);

        events.clear();
        springTransaction.executeWithoutResult(status -> {
            withCallback.entity(Visit.class).insert(new Visit(null, LocalDate.now(), "undone", pet, Instant.now()));
            status.setRollbackOnly();
        });
        assertEquals(List.of(), events, "a rolled-back insert must not be logged as if it had been applied");
    }

    @Test
    void registrationOnlyBlockDefersCallbacksToTheSpringCommit() {
        // The block performs no database work, so it never binds to a template; the detected Spring transaction
        // is what settles its callbacks.
        var events = new java.util.ArrayList<String>();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            transaction(tx -> {
                tx.onCommit(() -> events.add("commit"));
                tx.onRollback(() -> events.add("rollback"));
                return null;
            });
            assertEquals(List.of(), events, "callbacks must wait for the Spring commit");
        });
        assertEquals(List.of("commit"), events);
    }

    @Test
    void registrationOnlyBlockCallbacksFollowTheSpringRollback() {
        var events = new java.util.ArrayList<String>();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            transaction(tx -> {
                tx.onCommit(() -> events.add("commit"));
                tx.onRollback(() -> events.add("rollback"));
                return null;
            });
            status.setRollbackOnly();
        });
        assertEquals(List.of("rollback"), events);
    }

    @Test
    void registrationOnlyBlockPropagatesRollbackOnlyToSpring() {
        long before = visits.count();
        var springTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // Spring reports a transaction that was marked rollback-only from within, rather than rolling back
        // silently, which is how it treats every rollback-only mark it did not make itself.
        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class, () ->
                springTransaction.executeWithoutResult(status -> {
                    insertVisit("doomed by the inner mark");
                    transaction(tx -> {
                        tx.setRollbackOnly();
                        return null;
                    });
                }));
        assertEquals(before, visits.count());
    }

    @Test
    void registrationOnlyBlockWithoutAnyTransactionFiresAtBlockEnd() {
        // No transaction anywhere: block end is the completion, so the callbacks fire there.
        var events = new java.util.ArrayList<String>();
        transaction(tx -> {
            tx.onCommit(() -> events.add("commit"));
            return null;
        });
        assertEquals(List.of("commit"), events);
    }

    @Test
    void providerWithoutManagersRejectsStormInitiatedTransactions() {
        ORMTemplate unbridged = new BuilderImpl(st.orm.core.template.ORMTemplate.builder(dataSource)
                .connectionProvider(new SpringConnectionProvider())
                .transactionTemplateProvider(new SpringTransactionTemplateProvider()))
                .build();
        EntityRepository<Visit, Integer> unbridgedVisits = unbridged.entity(Visit.class);
        PersistenceException exception = assertThrows(PersistenceException.class, () ->
                transaction(tx -> {
                    unbridgedVisits.insert(new Visit(null, LocalDate.now(), "never", pet, Instant.now()));
                    return null;
                }));
        assertTrue(exception.getMessage().contains("PlatformTransactionManager supplier"));
    }
}
