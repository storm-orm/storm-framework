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
package st.orm.spring.boot;

import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.micrometer.QueryObservers;
import st.orm.micrometer.StormQueryObservationContext;
import st.orm.micrometer.StormTransactionObservationContext;
import st.orm.spi.QueryObserver;

/**
 * Composes the query observer the Spring Boot integration reports Storm executions with.
 *
 * <p>The observer follows the {@code storm.observations} properties: with
 * {@code storm.observations.semantic-conventions=otel} the OpenTelemetry database semantic conventions ride
 * along, with the database product resolved from the given data source's JDBC URL, so each observer describes
 * the database it actually observes. A {@code database} name is appended to every observation as the
 * low-cardinality {@code storm.database} key value, naming the template the observation came from, so each
 * template's queries chart under their own identity.</p>
 *
 * <p>{@link StormObservationAutoConfiguration} builds the auto-configured template's observer through this
 * class, and the starter's {@code OrmTemplateFactory} builds one per data source it composes a template for.</p>
 *
 * @since 1.14
 */
public final class StormQueryObservers {

    private StormQueryObservers() {
    }

    /**
     * Creates a query observer reporting to the given registry.
     *
     * @param observationRegistry the registry to report observations to.
     * @param properties the Storm configuration properties bound from {@code storm.*}.
     * @param dataSource the data source the observed template runs on, used to resolve the database product for
     *                   the OpenTelemetry semantic conventions; {@code null} when no single data source applies.
     * @param database the name reported as the {@code storm.database} key value, or {@code null} to report none.
     * @param queryConvention custom query observation convention, or {@code null} for the property-driven default.
     * @param transactionConvention custom transaction observation convention, or {@code null} for the default.
     * @return the composed query observer.
     * @throws st.orm.PersistenceException if {@code storm.observations.semantic-conventions} carries an unknown value.
     */
    public static QueryObserver create(
            ObservationRegistry observationRegistry,
            StormProperties properties,
            @Nullable DataSource dataSource,
            @Nullable String database,
            @Nullable ObservationConvention<StormQueryObservationContext> queryConvention,
            @Nullable ObservationConvention<StormTransactionObservationContext> transactionConvention) {
        var convention = queryConvention != null ? queryConvention : QueryObservers.semanticConventionFor(
                properties.getObservations().getSemanticConventions(),
                "storm.observations.semantic-conventions",
                dataSource);
        return QueryObservers.create(observationRegistry, convention, transactionConvention, database);
    }
}
