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
package st.orm.core.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Entity;
import st.orm.core.template.SqlLog.Unrecorded.Group;
import st.orm.spi.QueryContext;
import st.orm.spi.SqlOperation;

/**
 * The statements past a scope's recording limit are counted per operation and type, up to a bound: past it, the
 * executions of further types count with the other types of their operation, and statements without a type keep a
 * group of their own.
 */
class SqlLogUnrecordedGroupsTest {

    record Owner(Integer id) implements Entity<Integer> {}

    record Pet(Integer id) implements Entity<Integer> {}

    record Visit(Integer id) implements Entity<Integer> {}

    @AfterEach
    void restore() {
        SqlLog.Recorder.maxGroups = 1_024;
    }

    private static QueryContext context(SqlOperation operation, @Nullable Class<? extends Data> type) {
        return new QueryContext() {
            @Override
            public SqlOperation operation() {
                return operation;
            }

            @Override
            public Optional<Class<? extends Data>> dataType() {
                return Optional.ofNullable(type);
            }

            @Override
            public ExecutionKind kind() {
                return ExecutionKind.UPDATE;
            }

            @Override
            public OptionalInt batchSize() {
                return OptionalInt.empty();
            }

            @Override
            public Optional<String> statement() {
                return Optional.of("SQL");
            }
        };
    }

    private static void execute(SqlLog.Recorder recorder, SqlOperation operation, @Nullable Class<? extends Data> type) {
        var handle = recorder.onExecute(context(operation, type), List.of());
        handle.executed();
        handle.close(1, true);
    }

    @Test
    void typesPastTheBoundCountWithTheOtherTypesOfTheirOperation() {
        SqlLog.Recorder.maxGroups = 2;
        // Nothing is recorded in the table, so every execution is past its limit.
        var recorder = SqlLog.recorder(0);
        execute(recorder, SqlOperation.UPDATE, Owner.class);
        execute(recorder, SqlOperation.UPDATE, Pet.class);
        execute(recorder, SqlOperation.UPDATE, Visit.class);
        execute(recorder, SqlOperation.UPDATE, Visit.class);
        execute(recorder, SqlOperation.UPDATE, null);
        var groups = SqlLog.summary("task", recorder, 0).unrecorded().groups();
        assertEquals(4, groups.size(), groups.toString());
        assertTrue(groups.stream().anyMatch(group -> "Owner".equals(group.type()) && group.executions() == 1),
                groups.toString());
        assertTrue(groups.stream().anyMatch(group -> "Pet".equals(group.type())), groups.toString());
        assertTrue(groups.stream().anyMatch(group -> group.otherTypes() && group.type() == null
                && group.executions() == 2), groups.toString());
        assertTrue(groups.stream().anyMatch(group -> !group.otherTypes() && group.type() == null
                && group.executions() == 1), groups.toString());
        assertEquals(5, groups.stream().mapToInt(Group::executions).sum());
    }
}
