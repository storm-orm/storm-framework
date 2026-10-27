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
package st.orm.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import st.orm.Data;

/**
 * An implementation of the context that does not know what the advice asks says what keeps the advice from resting
 * on a guess: transactional, in a transaction of no known identity, reading records, and writing by condition.
 */
class QueryContextDefaultsTest {

    private static final QueryContext CONTEXT = new QueryContext() {
        @Override
        public SqlOperation operation() {
            return SqlOperation.SELECT;
        }

        @Override
        public Optional<Class<? extends Data>> dataType() {
            return Optional.empty();
        }

        @Override
        public ExecutionKind kind() {
            return ExecutionKind.QUERY;
        }

        @Override
        public OptionalInt batchSize() {
            return OptionalInt.empty();
        }

        @Override
        public Optional<String> statement() {
            return Optional.empty();
        }
    };

    @Test
    void anImplementationThatDoesNotKnowSaysWhatKeepsTheAdviceFromAGuess() {
        assertTrue(CONTEXT.transactional());
        assertEquals(0L, CONTEXT.transactionId());
        assertTrue(CONTEXT.readsRecords());
        assertFalse(CONTEXT.repositoryWrite());
        assertEquals(StatementOrigin.DIRECT, CONTEXT.origin());
        assertEquals(0L, CONTEXT.shapeId());
    }
}
