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
package st.orm.core.template.impl;

import jakarta.annotation.Nonnull;

/**
 * Describes a single mismatch between an entity definition and the database schema.
 *
 * @param entityType the entity or projection class where the mismatch was detected.
 * @param kind       the category of the validation error.
 * @param message    a human-readable description of the mismatch.
 * @since 1.9
 */
public record SchemaValidationError(
        @Nonnull Class<?> entityType,
        @Nonnull ErrorKind kind,
        @Nonnull String message
) {

    /**
     * The category of a schema validation error.
     */
    public enum ErrorKind {
        /** The mapped table does not exist in the database. */
        TABLE_NOT_FOUND,
        /** A mapped column does not exist in the database table. */
        COLUMN_NOT_FOUND,
        /** The Java type is not compatible with the SQL column type. */
        TYPE_INCOMPATIBLE,
        /** The Java type is numerically convertible but with potential precision or range differences. */
        TYPE_NARROWING(true),
        /** The nullability of the entity field does not match the database column. */
        NULLABILITY_MISMATCH(true),
        /** The primary key columns in the entity do not match the database primary key. */
        PRIMARY_KEY_MISMATCH,
        /** The entity declares a {@code @PK} but the database table has no primary key constraint. @since 1.10 */
        PRIMARY_KEY_MISSING(true),
        /** A sequence referenced by the entity does not exist in the database. */
        SEQUENCE_NOT_FOUND,
        /** A {@code @UK} field does not have a matching unique constraint in the database. */
        UNIQUE_KEY_MISSING(true),
        /** A {@code @FK} field has a foreign key constraint that references a different table than expected. @since 1.10 */
        FOREIGN_KEY_MISMATCH,
        /** A {@code @FK} field does not have a matching foreign key constraint in the database. */
        FOREIGN_KEY_MISSING(true),
        /** A database column is NOT NULL without a default value, but is not mapped in the entity. @since 1.10 */
        UNMAPPED_COLUMN(true);

        private final boolean warning;

        ErrorKind() {
            this(false);
        }

        ErrorKind(boolean warning) {
            this.warning = warning;
        }

        /**
         * Returns whether this error kind is a warning rather than a hard error.
         *
         * <p>Warnings are logged but do not cause {@code validateSchemaOrThrow()} to fail.</p>
         *
         * @return {@code true} if this is a warning.
         */
        public boolean warning() {
            return warning;
        }
    }

    @Override
    public String toString() {
        return "%s [%s]: %s".formatted(entityType.getSimpleName(), kind, message);
    }
}
