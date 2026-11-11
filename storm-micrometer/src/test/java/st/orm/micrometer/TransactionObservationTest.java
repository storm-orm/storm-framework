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
package st.orm.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.TransactionScope;

/**
 * Tests for the {@code storm.transaction} observation: physical transactions are observed with their
 * outcome, joined blocks are not, {@code REQUIRES_NEW} opens its own observation, and the
 * {@link StormTransactionObservationConvention} drives the naming and key values.
 */
public class TransactionObservationTest {

    private static TransactionOptions options(TransactionPropagation propagation) {
        return new TransactionOptions(propagation, null, null, null);
    }

    private ORMTemplate template(TestObservationRegistry registry, String database) {
        return ORMTemplate.builder(new SimpleDataSource("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1"))
                .queryObserver(new MicrometerQueryObserver(registry))
                .build();
    }

    private List<io.micrometer.observation.Observation.Context> transactionContexts(TestObservationRegistry registry) {
        var contexts = new java.util.ArrayList<io.micrometer.observation.Observation.Context>();
        io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
                .hasHandledContextsThatSatisfy(handled -> handled.stream()
                        .filter(context -> "storm.transaction".equals(context.getName()))
                        .forEach(contexts::add));
        return contexts;
    }

    @Test
    public void committedTransactionIsObservedWithOutcome() {
        var registry = TestObservationRegistry.create();
        var orm = template(registry, "txobserved");
        var scope = TransactionScope.open(options(null), false);
        try {
            orm.query("SELECT 1").getSingleResult(Integer.class);
            scope.complete(false);
        } finally {
            scope.close();
        }
        var contexts = transactionContexts(registry);
        assertEquals(1, contexts.size());
        assertEquals("committed", keyValue(contexts.get(0), "storm.tx.outcome"));
        assertEquals("REQUIRED", keyValue(contexts.get(0), "storm.tx.propagation"));
    }

    @Test
    public void rolledBackTransactionCarriesTheOutcome() {
        var registry = TestObservationRegistry.create();
        var orm = template(registry, "txrollback");
        var scope = TransactionScope.open(options(null), false);
        try {
            orm.query("SELECT 1").getSingleResult(Integer.class);
            scope.complete(true);
        } finally {
            scope.close();
        }
        var contexts = transactionContexts(registry);
        assertEquals(1, contexts.size());
        assertEquals("rolled_back", keyValue(contexts.get(0), "storm.tx.outcome"));
    }

    @Test
    public void joinedBlocksAreNotObservedButRequiresNewIs() {
        var registry = TestObservationRegistry.create();
        var orm = template(registry, "txnesting");
        var outer = TransactionScope.open(options(null), false);
        try {
            orm.query("SELECT 1").getSingleResult(Integer.class);
            // Joined inner block: same physical transaction, no observation.
            var joined = TransactionScope.open(options(TransactionPropagation.REQUIRED), false);
            try {
                orm.query("SELECT 2").getSingleResult(Integer.class);
                joined.complete(false);
            } finally {
                joined.close();
            }
            // REQUIRES_NEW: its own physical transaction, its own observation.
            var independent = TransactionScope.open(options(TransactionPropagation.REQUIRES_NEW), false);
            try {
                orm.query("SELECT 3").getSingleResult(Integer.class);
                independent.complete(false);
            } finally {
                independent.close();
            }
            outer.complete(false);
        } finally {
            outer.close();
        }
        var contexts = transactionContexts(registry);
        assertEquals(2, contexts.size());
        var propagations = contexts.stream()
                .map(context -> keyValue(context, "storm.tx.propagation"))
                .sorted()
                .toList();
        assertEquals(List.of("REQUIRED", "REQUIRES_NEW"), propagations);
    }

    @Test
    public void extraKeyValuesAreAppendedToTransactionObservations() {
        var registry = TestObservationRegistry.create();
        var observer = new MicrometerQueryObserver(registry, KeyValues.of("storm.database", "vets"));
        var observation = observer.onTransaction(options(null));
        observation.close(false);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("storm.transaction")
                .that()
                .hasContextualNameEqualTo("transaction")
                .hasLowCardinalityKeyValue("storm.database", "vets")
                .hasLowCardinalityKeyValue("storm.tx.outcome", "committed")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    public void customTransactionConventionOverridesNamingAndKeyValues() {
        var registry = TestObservationRegistry.create();
        var transactionConvention = new StormTransactionObservationConvention() {
            @Override
            public String getName() {
                return "db.tx";
            }

            @Override
            public KeyValues getLowCardinalityKeyValues(StormTransactionObservationContext context) {
                return super.getLowCardinalityKeyValues(context).and("db.system.name", "h2database");
            }
        };
        var observer = new MicrometerQueryObserver(
                registry, new StormQueryObservationConvention(), transactionConvention, KeyValues.empty());
        var observation = observer.onTransaction(options(TransactionPropagation.REQUIRES_NEW));
        observation.close(true);
        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo("db.tx")
                .that()
                .hasLowCardinalityKeyValue("db.system.name", "h2database")
                .hasLowCardinalityKeyValue("storm.tx.propagation", "REQUIRES_NEW")
                .hasLowCardinalityKeyValue("storm.tx.outcome", "rolled_back");
    }

    private static String keyValue(io.micrometer.observation.Observation.Context context, String key) {
        var keyValue = context.getLowCardinalityKeyValue(key);
        return keyValue != null ? keyValue.getValue() : null;
    }
}
