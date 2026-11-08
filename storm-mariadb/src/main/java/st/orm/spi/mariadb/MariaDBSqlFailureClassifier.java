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
package st.orm.spi.mariadb;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.spi.mysql.MySQLSqlFailureClassifier;

/**
 * Reads the errors MariaDB reports for a constraint as MySQL's are read, and error {@code 4025}, the code MariaDB
 * reports a violated check constraint under, whose message names the constraint and the table.
 *
 * @since 1.16
 */
public final class MariaDBSqlFailureClassifier extends MySQLSqlFailureClassifier {

    /** {@code CONSTRAINT `owner_age_ck` failed for `db`.`owner`}. */
    private static final Pattern CHECK_CONSTRAINT = Pattern.compile("CONSTRAINT `([^`]+)` failed for `[^`]*`\\.`([^`]+)`");

    /**
     * Recognises the deadlock MySQL reports, error {@code 1213}, and error {@code 1020}, {@code ER_CHECKREAD}: with
     * {@code innodb_snapshot_isolation}, on by default since MariaDB 11.6, a transaction that writes a row another
     * transaction changed after its snapshot fails with it, and MariaDB rolls the transaction back. A lock wait
     * timeout, error {@code 1205}, is not transient.
     *
     * @param exception one error of the failure's chain.
     * @return {@code true} if the error reports a deadlock or a write conflict with the transaction's snapshot.
     * @since 1.16
     */
    @Override
    public boolean isTransient(SQLException exception) {
        return super.isTransient(exception) || exception.getErrorCode() == 1020;
    }

    @Override
    public Optional<IntegrityFailure> integrityFailure(SQLException exception) {
        if (exception.getErrorCode() == 4025) {
            var failure = IntegrityFailure.of(Kind.CHECK);
            var matcher = CHECK_CONSTRAINT.matcher(exception.getMessage() == null ? "" : exception.getMessage());
            if (matcher.find()) {
                failure = failure.withConstraint(matcher.group(1)).withTable(matcher.group(2));
            }
            return Optional.of(failure);
        }
        return super.integrityFailure(exception);
    }
}
