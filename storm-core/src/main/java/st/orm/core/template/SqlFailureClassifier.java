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

import java.sql.SQLException;
import java.util.Optional;
import st.orm.IntegrityViolationException;
import st.orm.TransientPersistenceException;

/**
 * Reads what a database's errors report: a failure that running the transaction again may overcome, and a value the
 * database refused for a rule of its table. Storm throws the first as a {@link TransientPersistenceException} and the
 * second as an {@link IntegrityViolationException}, each through the exception mapper with the driver's exception as
 * its cause.
 *
 * <p>A classifier belongs to a database, and a dialect returns the one for its database through
 * {@link SqlDialect#sqlFailureClassifier()}. The methods default to the SQL states the standard assigns, and a
 * classifier overrides them with its database's own codes and names. Storm asks about every {@code SQLException} in
 * the chain of a statement's failure, or of a commit's failure: the causes as well as the next exceptions a driver
 * chains to a batch failure.</p>
 *
 * @since 1.16
 */
public interface SqlFailureClassifier {

    /**
     * The classifier of the SQL states the standard assigns, for a database with no classifier of its own.
     */
    SqlFailureClassifier STANDARD = new SqlFailureClassifier() {};

    /**
     * Returns whether the error reports a failure that stems from concurrent activity, on which the database gave up
     * the transaction's work: a deadlock or a serialization failure. Running the whole transaction again may succeed.
     *
     * <p>One recognised error in the chain classifies the failure. A database that detects a serialization conflict
     * only when the transaction commits reports it from the commit, and the transaction did not commit. A chain that
     * reports a lost connection, under SQL state class {@code 08}, is never transient, a connection lost during the
     * commit included: whether the transaction committed is unknown.</p>
     *
     * <p>The default recognises SQL state {@code 40001}, serialization failure. A classifier adds the codes its
     * database reports for a deadlock or a serialization failure. It leaves out the errors after which the outcome is
     * unknown, such as SQL states {@code 40002} and {@code 40003}, and a lock wait timeout, which some databases
     * answer by rolling back only the statement.</p>
     *
     * @param exception one error of the failure's chain.
     * @return {@code true} if the error reports a deadlock or a serialization failure, {@code false} otherwise.
     */
    default boolean isTransient(SQLException exception) {
        return "40001".equals(exception.getSQLState());
    }

    /**
     * Returns what the error reports when the database refused a value for a rule of its table: a duplicate under a
     * unique key, a reference to a row that does not exist, a row other rows still reference, a null in a column that
     * refuses it, a value a check constraint rejects, or a value too large for its column. Storm resolves the names
     * the failure carries to the entity's fields. The first error classified in the chain decides.
     *
     * <p>The default recognises the SQL states the standard assigns: {@code 23505} unique violation, {@code 23503}
     * foreign key violation, {@code 23502} not null violation, {@code 23514} check violation, {@code 22001} string
     * data right truncation and {@code 22003} numeric value out of range, and names nothing, so a violation it
     * classifies resolves to no field.</p>
     *
     * <p>A classifier decides the kind from the error code or SQL state, which a database keeps stable across its
     * versions, and reads the constraint, table and column names from the error's structured fields where its
     * database has them, and otherwise from the identifiers its message quotes and the SQL terms it carries, which a
     * server configured for another language keeps while it translates the words around them. It never infers a
     * column the error does not name, and never matches the prose of a message. A message a later version reshapes
     * costs the names and the fields they resolve to, not the kind; a classifier whose database reports several kinds
     * under one code and tells them apart by a SQL term in the message states that in its own documentation. A
     * classifier that cannot tell a reference to a missing row from a row still referenced reports
     * {@link IntegrityFailure.Kind#FOREIGN_KEY}; Storm tells them apart from the schema and the statement. The
     * conformance suite runs every kind against every database, so a change in a database's errors fails a build
     * rather than a caller.</p>
     *
     * @param exception one error of the failure's chain.
     * @return the failure the error reports, or empty when it reports none.
     */
    default Optional<IntegrityFailure> integrityFailure(SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState == null) {
            return Optional.empty();
        }
        return switch (sqlState) {
            case "23505" -> Optional.of(IntegrityFailure.of(IntegrityFailure.Kind.UNIQUE));
            case "23503" -> Optional.of(IntegrityFailure.of(IntegrityFailure.Kind.FOREIGN_KEY));
            case "23502" -> Optional.of(IntegrityFailure.of(IntegrityFailure.Kind.NOT_NULL));
            case "23514" -> Optional.of(IntegrityFailure.of(IntegrityFailure.Kind.CHECK));
            case "22001" -> Optional.of(IntegrityFailure.of(IntegrityFailure.Kind.LENGTH));
            case "22003" -> Optional.of(IntegrityFailure.of(IntegrityFailure.Kind.NUMERIC_RANGE));
            default -> Optional.empty();
        };
    }
}
