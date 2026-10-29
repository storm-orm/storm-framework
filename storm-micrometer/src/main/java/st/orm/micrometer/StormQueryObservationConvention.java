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

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import java.util.Locale;

/**
 * Default {@link ObservationConvention} for Storm query observations.
 *
 * <p>Observations are named {@code storm.query} with a contextual name of the form {@code "select pet"}. The
 * contextual name doubles as the span name and is all lowercase, since tracing handlers rewrite capitalized
 * names into hyphenated form. The low-cardinality key values are suitable as metric tags:</p>
 *
 * <ul>
 *   <li>{@code storm.operation} — {@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE} or
 *   {@code UNDEFINED}</li>
 *   <li>{@code storm.execution} — {@code QUERY}, {@code UPDATE} or {@code BATCH}</li>
 *   <li>{@code storm.data_type} — the simple name of the targeted entity or projection, or {@code none}</li>
 *   <li>{@code storm.origin} — {@code DIRECT}, or {@code FETCH} for a statement resolving a
 *   reference; the rate of the latter is what resolving references costs</li>
 *   <li>{@code storm.shape} — the identity of the statement's shape, hex-encoded, or {@code none} when unknown.
 *   Statements generated from one template share a shape whatever their parameters expand to, so grouping by
 *   shape treats them as one statement where the text would split them. The cardinality is bounded by the number
 *   of distinct query templates in the application</li>
 * </ul>
 *
 * <p>The SQL statement is exposed as the high-cardinality key value {@code db.statement}; tracing handlers turn it
 * into a span attribute, and it is never used as a metric tag.</p>
 *
 * <p>Extend this class or supply your own {@link ObservationConvention} to the
 * {@link MicrometerQueryObserver} to customize naming and key values.</p>
 *
 * @since 1.13
 */
public class StormQueryObservationConvention implements ObservationConvention<StormQueryObservationContext> {

    /**
     * The observation name for Storm query executions.
     */
    public static final String OBSERVATION_NAME = "storm.query";

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof StormQueryObservationContext;
    }

    @Override
    public String getName() {
        return OBSERVATION_NAME;
    }

    @Override
    public String getContextualName(StormQueryObservationContext context) {
        var queryContext = context.queryContext();
        var dataType = queryContext.dataType().map(Class::getSimpleName).orElse("query");
        // All lowercase: tracing handlers use the contextual name as the span name and rewrite capitalized
        // names into hyphenated form ("SELECT Pet" would surface as "s-e-l-e-c-t -pet").
        return (queryContext.operation().name() + " " + dataType).toLowerCase(Locale.ROOT);
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(StormQueryObservationContext context) {
        var queryContext = context.queryContext();
        var shapeId = queryContext.shapeId();
        return KeyValues.of(
                        "storm.operation", queryContext.operation().name(),
                        "storm.execution", queryContext.kind().name(),
                        "storm.data_type", queryContext.dataType().map(Class::getSimpleName).orElse("none"),
                        "storm.origin", queryContext.origin().name(),
                        "storm.shape", shapeId != 0 ? "%016x".formatted(shapeId) : "none")
                .and(context.extraLowCardinalityKeyValues());
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(StormQueryObservationContext context) {
        return context.queryContext().statement()
                .map(statement -> KeyValues.of("db.statement", statement))
                .orElseGet(KeyValues::empty);
    }
}
