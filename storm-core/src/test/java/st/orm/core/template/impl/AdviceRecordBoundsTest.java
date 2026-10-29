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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import st.orm.Entity;
import st.orm.spi.QueryContext.ExecutionKind;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * What the advice log remembers about transactions is bounded: the blocks it reported per call site, and the repeats
 * the transactions advised on, each at {@link AdviceLog#MAX_REPORTED} entries, after which the record starts over.
 * Starting over may repeat a line, never change what one says.
 */
class AdviceRecordBoundsTest {

    record Owner(Integer id) implements Entity<Integer> {}

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previous;

    @BeforeEach
    void reportPerformanceAdvice() {
        AdviceLog.reset();
        AdviceLog.categories(List.of("correctness", "performance", "design"));
        AdviceLog.repeatFloor(null);
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("st.orm.sql.advice");
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        logger.setAdditive(false);
    }

    @AfterEach
    void restore() {
        logger.detachAppender(appender);
        logger.setAdditive(true);
        logger.setLevel(previous);
        appender.stop();
        AdviceLog.reset();
    }

    @Test
    void theBlocksReportedPerCallSiteStartOverWhenTheRecordIsFull() {
        var advisory = new Repeats.Advisory(Advice.REPEATED_SELECT, new Repeats.ShapeAtSite(7L, "Loop.kt:3"), 7, 1,
                "the same SELECT of Owner ran 10 times");
        for (int site = 1; site <= AdviceLog.MAX_REPORTED; site++) {
            AdviceLog.onTransaction(List.of(advisory), null, "Service.kt:" + site);
        }
        // Reported at the first site already, the advice stays quiet there while the record holds it.
        AdviceLog.onTransaction(List.of(advisory), null, "Service.kt:1");
        assertEquals(AdviceLog.MAX_REPORTED, appender.list.size());
        // One site more fills the record, which starts over, so the first site reports its advice again.
        AdviceLog.onTransaction(List.of(advisory), null, "Service.kt:" + (AdviceLog.MAX_REPORTED + 1));
        AdviceLog.onTransaction(List.of(advisory), null, "Service.kt:1");
        assertEquals(AdviceLog.MAX_REPORTED + 2, appender.list.size());
    }

    /** A transaction that read an owner ten times one row at a time, and advised on it as it completed. */
    private static TransactionRecord advisingTransaction() {
        var record = TransactionRecord.take(false);
        assertNotNull(record);
        var context = new QueryImpl.QueryContextImpl(SqlOperation.SELECT, false, Owner.class, ExecutionKind.QUERY,
                "SELECT o.id FROM owner o WHERE o.id = ?", StatementOrigin.DIRECT, 7, true, null, true, false, false);
        for (int i = 0; i < Advice.REPEAT_THRESHOLD; i++) {
            record.statementClosed(context, 0, 1_000, 1_000, 1, true);
        }
        assertEquals(1, record.advisories().size());
        return record;
    }

    @Test
    void theRepeatsTransactionsAdvisedOnStartOverWhenTheRecordIsFull() {
        var first = advisingTransaction();
        var key = first.advisories().getFirst().key();
        assertTrue(TransactionRecord.advised(first.id(), key));
        for (int i = 1; i < AdviceLog.MAX_REPORTED; i++) {
            advisingTransaction();
        }
        assertTrue(TransactionRecord.advised(first.id(), key));
        // One transaction more fills the record, which starts over: a summary then counts the first transaction's
        // executions as its own, which repeats advice but never drops it.
        advisingTransaction();
        assertFalse(TransactionRecord.advised(first.id(), key));
    }
}
