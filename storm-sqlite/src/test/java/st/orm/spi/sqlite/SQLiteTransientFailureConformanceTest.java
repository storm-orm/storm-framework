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
package st.orm.spi.sqlite;

import java.util.List;
import java.util.Optional;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against SQLite, in write-ahead-log mode, where a transaction that
 * writes after another connection committed past its snapshot fails with {@code SQLITE_BUSY_SNAPSHOT}. A busy
 * timeout of a second bounds the lock wait. The suite commits, so it runs without the per-test rollback.
 */
@StormTest(url = "jdbc:sqlite:target/transient-failure.db?journal_mode=WAL&busy_timeout=1000", rollback = false)
public class SQLiteTransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

    @Override
    protected Optional<String> deadlockUnsupportedReason() {
        return Optional.of("SQLite has one writer at a time: a second writer waits for the busy timeout instead of "
                + "holding a row the first one waits for, so two transactions never deadlock.");
    }

    /**
     * A transaction that read and then writes while another connection holds the write lock gets a plain
     * {@code SQLITE_BUSY} at once, the code of a busy timeout that ran out, since its snapshot can no longer become
     * the latest; once the other connection committed, the write fails with {@code SQLITE_BUSY_SNAPSHOT}.
     */
    @Override
    protected boolean singleWriter() {
        return true;
    }

    @Override
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
    }
}
