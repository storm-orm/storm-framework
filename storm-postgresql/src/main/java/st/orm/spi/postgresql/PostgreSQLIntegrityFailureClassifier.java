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
package st.orm.spi.postgresql;

import java.sql.SQLException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailureClassifier;

/**
 * Reads the SQL states PostgreSQL reports for a constraint, as the standard assigns them, and the constraint, table
 * and column from the error's fields: PostgreSQL reports them apart from the message, so they are read whatever
 * language the server writes its messages in. The driver exposes the fields on its own exception type, read here by
 * reflection, as the module does not depend on the driver.
 *
 * <p>A foreign key violation names the table that holds the foreign key, for an insert whose parent row is absent
 * and for a delete of a row the table still references alike; Storm tells the two apart from the statement and the
 * entity's table.</p>
 *
 * @since 1.16
 */
public final class PostgreSQLIntegrityFailureClassifier implements IntegrityFailureClassifier {

    @Override
    public Optional<IntegrityFailure> classify(SQLException exception) {
        return STANDARD.classify(exception).map(failure -> {
            var fields = serverErrorFields(exception);
            if (fields == null) {
                return failure;
            }
            return failure.withConstraint(fields.constraint()).withTable(fields.table()).withColumn(fields.column());
        });
    }

    private record ServerErrorFields(@Nullable String constraint, @Nullable String table, @Nullable String column) {}

    /**
     * Reads the constraint, table and column fields of the server's error, or {@code null} when the exception is not
     * the driver's or carries no server error.
     */
    private static @Nullable ServerErrorFields serverErrorFields(SQLException exception) {
        try {
            var serverError = exception.getClass().getMethod("getServerErrorMessage").invoke(exception);
            if (serverError == null) {
                return null;
            }
            return new ServerErrorFields(
                    field(serverError, "getConstraint"),
                    field(serverError, "getTable"),
                    field(serverError, "getColumn"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String field(Object serverError, String accessor) throws ReflectiveOperationException {
        return (String) serverError.getClass().getMethod(accessor).invoke(serverError);
    }
}
