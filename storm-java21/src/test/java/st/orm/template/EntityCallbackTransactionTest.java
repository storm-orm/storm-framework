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
package st.orm.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static st.orm.template.Transactions.transaction;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.template.model.City;

/**
 * Tests the transactional context entity callbacks run in: a callback sees the transaction the write runs in, and
 * sees none where the write runs in none.
 *
 * <p>The asymmetry is the point. Inside a transaction the callback reads and writes through the same connection, so
 * it observes the uncommitted write and a rollback takes its own work back along with it. Outside a transaction the
 * write has already committed by the time the callback runs, so a callback that throws leaves it in place.</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@Sql("/data.sql")
public class EntityCallbackTransactionTest {

    @Autowired
    ORMTemplate orm;

    /** Thrown from a callback to fail the operation that triggered it. */
    private static final class CallbackFailure extends RuntimeException {
        CallbackFailure() {
            super("Simulated callback failure.");
        }
    }

    /** Writes a row of its own for every city inserted, standing in for an audit trail. */
    private final class AuditingCallback implements EntityCallback<City> {
        private final List<String> observed = new ArrayList<>();

        @Override
        public void afterInsert(City entity) {
            observed.add(entity.name());
            orm.entity(City.class).insert(new City(null, entity.name() + " (audit)"));
        }
    }

    /** Reads the inserted row back from inside the callback, before the operation returns. */
    private final class ReadingCallback implements EntityCallback<City> {
        private Boolean sawOwnWrite;

        @Override
        public void afterInsert(City entity) {
            sawOwnWrite = orm.entity(City.class).findById(entity.id()).isPresent();
        }
    }

    /** Fails after the write has been issued. */
    private static final class FailingCallback implements EntityCallback<City> {

        @Override
        public void afterInsert(City entity) {
            throw new CallbackFailure();
        }
    }

    private long countCities(String name) {
        return orm.entity(City.class).findAll().stream()
                .filter(city -> city.name().equals(name))
                .count();
    }

    @Test
    public void rollbackTakesBackTheCallbacksOwnWrite() {
        var callback = new AuditingCallback();
        var cities = orm.withEntityCallback(callback).entity(City.class);
        assertThrows(IllegalStateException.class, () ->
                transaction(tx -> {
                    cities.insert(new City(null, "Springfield"));
                    throw new IllegalStateException("Simulated failure.");
                }));
        assertEquals(List.of("Springfield"), callback.observed,
                "The callback must have fired before the rollback.");
        assertEquals(0, countCities("Springfield"), "The write must be rolled back.");
        assertEquals(0, countCities("Springfield (audit)"),
                "The callback's own write must be rolled back with it.");
    }

    @Test
    public void callbackWorkCommitsWithTheWrite() {
        var callback = new AuditingCallback();
        var cities = orm.withEntityCallback(callback).entity(City.class);
        transaction(tx -> {
            cities.insert(new City(null, "Springfield"));
            return null;
        });
        assertEquals(1, countCities("Springfield"));
        assertEquals(1, countCities("Springfield (audit)"));
    }

    @Test
    public void callbackReadsThroughTheTransactionItRunsIn() {
        var callback = new ReadingCallback();
        var cities = orm.withEntityCallback(callback).entity(City.class);
        assertThrows(IllegalStateException.class, () ->
                transaction(tx -> {
                    cities.insertAndFetchId(new City(null, "Shelbyville"));
                    throw new IllegalStateException("Simulated failure.");
                }));
        assertEquals(Boolean.TRUE, callback.sawOwnWrite,
                "The callback shares the transaction's connection, so it must see the uncommitted row.");
        assertEquals(0, countCities("Shelbyville"), "The write must be rolled back.");
    }

    @Test
    public void throwingCallbackRollsBackTheWriteInsideATransaction() {
        var cities = orm.withEntityCallback(new FailingCallback()).entity(City.class);
        assertThrows(CallbackFailure.class, () ->
                transaction(tx -> {
                    cities.insert(new City(null, "Ogdenville"));
                    return null;
                }));
        assertEquals(0, countCities("Ogdenville"),
                "A callback that throws inside a transaction takes the write down with it.");
    }

    @Test
    public void throwingCallbackCannotTakeBackAWriteOutsideATransaction() {
        var cities = orm.withEntityCallback(new FailingCallback()).entity(City.class);
        assertThrows(CallbackFailure.class, () -> cities.insert(new City(null, "North Haverbrook")));
        assertEquals(1, countCities("North Haverbrook"),
                "Without a transaction the statement has already committed when the callback runs.");
    }
}
