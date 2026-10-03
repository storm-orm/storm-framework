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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import st.orm.Data;
import st.orm.DbIgnore;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.PK;
import st.orm.Persist;
import st.orm.ProjectionQuery;
import st.orm.Ref;
import st.orm.UK;
import st.orm.core.QuietValidationLog;
import st.orm.core.template.impl.SchemaValidationError;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;
import st.orm.core.template.impl.SchemaValidationException;
import st.orm.core.template.impl.SchemaValidator;

/**
 * Tests for {@link SchemaValidator} using H2 in-memory databases with intentional schema mismatches.
 */
@ExtendWith(QuietValidationLog.class)
class SchemaValidatorTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private String jdbcUrl;
    private DataSource dataSource;

    // Test entity definitions

    public record ValidCity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record MissingTableEntity(
            @PK Integer id,
            String value
    ) implements Entity<Integer> {}

    public record MissingColumnEntity(
            @PK Integer id,
            String name,
            String nonExistentColumn
    ) implements Entity<Integer> {}

    public record TypeMismatchEntity(
            @PK Integer id,
            LocalDate name  // name is VARCHAR in DB, but LocalDate in entity
    ) implements Entity<Integer> {}

    public record TypeNarrowingEntity(
            @PK Integer id,
            Integer score  // score is FLOAT in DB, but Integer in entity
    ) implements Entity<Integer> {}

    public record NullabilityMismatchEntity(
            @PK Integer id,
            String name,      // non-nullable in entity
            String description // non-nullable in entity, but nullable in DB
    ) implements Entity<Integer> {}

    public record PrimaryKeyMismatchEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record SequenceEntity(
            @PK(generation = GenerationStrategy.SEQUENCE, sequence = "nonexistent_seq") Integer id,
            String name
    ) implements Entity<Integer> {}

    public record InlinedAddress(
            @Nullable String street,
            @Nullable String zipCode
    ) {}

    public record EntityWithInline(
            @PK Integer id,
            String name,
            InlinedAddress address
    ) implements Entity<Integer> {}

    public record ForeignKeyRef(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record EntityWithFk(
            @PK Integer id,
            String name,
            @Nullable @FK Ref<ForeignKeyRef> foreignKeyRef
    ) implements Entity<Integer> {}

    @DbTable(schema = "custom_schema")
    public record SchemaEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @DbTable(schema = "missing_schema")
    public record MissingSchemaEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @DbIgnore
    public record IgnoredEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record FieldIgnoredEntity(
            @PK Integer id,
            String name,
            @DbIgnore LocalDate description  // type mismatch, but ignored
    ) implements Entity<Integer> {}

    // Constraint flag test entities

    public record PrimaryKeyMissingEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record PrimaryKeyMissingNoConstraintEntity(
            @PK(constraint = false) Integer id,
            String name
    ) implements Entity<Integer> {}

    public record ForeignKeyMismatchTarget(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record WrongForeignKeyTarget(
            @PK Integer id,
            String label
    ) implements Entity<Integer> {}

    public record ForeignKeyMismatchEntity(
            @PK Integer id,
            String name,
            @Nullable @FK Ref<ForeignKeyMismatchTarget> foreignKeyMismatchTarget
    ) implements Entity<Integer> {}

    public record ForeignKeyNoConstraintEntity(
            @PK Integer id,
            String name,
            @Nullable @FK(constraint = false) Ref<ForeignKeyTarget> foreignKeyTarget
    ) implements Entity<Integer> {}

    public record ForeignKeyMismatchNoConstraintEntity(
            @PK Integer id,
            String name,
            @Nullable @FK(constraint = false) Ref<ForeignKeyMismatchTarget> foreignKeyMismatchTarget
    ) implements Entity<Integer> {}

    public record UniqueKeyNoConstraintEntity(
            @PK Integer id,
            @UK(constraint = false) String email,
            String name
    ) implements Entity<Integer> {}

    // UK/FK validation test entities

    public record UniqueKeyEntity(
            @PK Integer id,
            @UK String email,
            String name
    ) implements Entity<Integer> {}

    public record CompoundUniqueKeyFields(int userId, String email) {}

    public record CompoundUniqueKeyEntity(
            @PK Integer id,
            String name,
            int userId,
            String email,
            @UK @Persist(insertable = false, updatable = false) CompoundUniqueKeyFields uniqueKey
    ) implements Entity<Integer> {}

    public record ForeignKeyTarget(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record ForeignKeySourceEntity(
            @PK Integer id,
            String name,
            @Nullable @FK Ref<ForeignKeyTarget> foreignKeyTarget
    ) implements Entity<Integer> {}

    public record UniqueKeyIgnoredEntity(
            @PK Integer id,
            String name,
            @DbIgnore @UK String email
    ) implements Entity<Integer> {}

    public record ForeignKeyIgnoredEntity(
            @PK Integer id,
            String name,
            @DbIgnore @Nullable @FK Ref<ForeignKeyTarget> foreignKeyTarget
    ) implements Entity<Integer> {}

    @BeforeEach
    void setUp() {
        // Each test gets a fresh in-memory database to avoid table name collisions.
        // DB_CLOSE_DELAY=-1 keeps the database alive across connections within the same test.
        jdbcUrl = "jdbc:h2:mem:schema_validator_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SimpleDataSource(jdbcUrl);
    }

    @Test
    void testValidEntityPasses() throws SQLException {
        execute("CREATE TABLE valid_city (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ValidCity.class));

        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void testTableNotFound() {
        // No table created for MissingTableEntity.
        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(MissingTableEntity.class));

        assertEquals(1, errors.size());
        assertEquals(ErrorKind.TABLE_NOT_FOUND, errors.getFirst().kind());
        assertTrue(errors.getFirst().message().contains("missing_table_entity"));
    }

    @Test
    void testColumnNotFound() throws SQLException {
        execute("CREATE TABLE missing_column_entity (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(MissingColumnEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.COLUMN_NOT_FOUND
                        && error.message().contains("non_existent_column")));
    }

    @Test
    void testTypeIncompatible() throws SQLException {
        execute("CREATE TABLE type_mismatch_entity (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(TypeMismatchEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.TYPE_INCOMPATIBLE
                        && error.message().contains("name")));
    }

    @Test
    void testTypeNarrowing() throws SQLException {
        execute("CREATE TABLE type_narrowing_entity (id INTEGER AUTO_INCREMENT, score FLOAT NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(TypeNarrowingEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.TYPE_NARROWING
                        && error.message().contains("score")));
        // Should NOT produce a TYPE_INCOMPATIBLE error.
        assertFalse(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.TYPE_INCOMPATIBLE));
    }

    @Test
    void testNullabilityMismatch() throws SQLException {
        execute("CREATE TABLE nullability_mismatch_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "description VARCHAR(255), "  // nullable in DB
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(NullabilityMismatchEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.NULLABILITY_MISMATCH
                        && error.message().contains("description")));
    }

    @Test
    void testPrimaryKeyMismatch() throws SQLException {
        // Create a table with a compound PK instead of a single PK.
        execute("CREATE TABLE primary_key_mismatch_entity ("
                + "id INTEGER NOT NULL, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id, name))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(PrimaryKeyMismatchEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.PRIMARY_KEY_MISMATCH));
    }

    @Test
    void testSequenceNotFound() throws SQLException {
        execute("CREATE TABLE sequence_entity (id INTEGER, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(SequenceEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.SEQUENCE_NOT_FOUND
                        && error.message().contains("nonexistent_seq")));
    }

    @Test
    void testSequenceExists() throws SQLException {
        execute("CREATE TABLE sequence_entity (id INTEGER, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE SEQUENCE nonexistent_seq START WITH 1");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(SequenceEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.SEQUENCE_NOT_FOUND),
                "Expected no SEQUENCE_NOT_FOUND error when sequence exists.");
    }

    @Test
    void testInlineRecordExpansion() throws SQLException {
        execute("CREATE TABLE entity_with_inline ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "street VARCHAR(255), "
                + "zip_code VARCHAR(255), "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(EntityWithInline.class));

        assertTrue(errors.isEmpty(), "Expected no errors for inline record but got: " + errors);
    }

    @Test
    void testForeignKeyRef() throws SQLException {
        execute("CREATE TABLE foreign_key_ref (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE entity_with_fk ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_ref_id INTEGER, "
                + "PRIMARY KEY (id), "
                + "FOREIGN KEY (foreign_key_ref_id) REFERENCES foreign_key_ref(id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(EntityWithFk.class));

        assertTrue(errors.isEmpty(), "Expected no errors for FK ref but got: " + errors);
    }

    @Test
    void testValidateOrThrow() {
        // No table created.
        SchemaValidator validator = SchemaValidator.of(dataSource);

        assertThrows(SchemaValidationException.class,
                () -> validator.validateOrThrow(List.of(MissingTableEntity.class)));
    }

    @Test
    void testValidateOrThrowWithNoErrors() throws SQLException {
        execute("CREATE TABLE valid_city (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        SchemaValidator validator = SchemaValidator.of(dataSource);
        // Should not throw.
        List<SchemaValidationError> errors = validator.validate(List.of(ValidCity.class));
        assertTrue(errors.isEmpty());
    }

    @Test
    void testMultipleErrors() throws SQLException {
        // Create a table that will cause column not found for MissingColumnEntity.
        execute("CREATE TABLE missing_column_entity (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(MissingColumnEntity.class, MissingTableEntity.class));

        // MissingColumnEntity should have COLUMN_NOT_FOUND, MissingTableEntity should have TABLE_NOT_FOUND.
        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.COLUMN_NOT_FOUND));
        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.TABLE_NOT_FOUND));
    }

    @Test
    void testSchemaValidationExceptionMessage() {
        SchemaValidator validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(MissingTableEntity.class));

        SchemaValidationException exception = new SchemaValidationException(errors);
        assertTrue(exception.getMessage().contains("Schema validation failed"));
        assertEquals(errors, exception.getErrors());
    }

    @Test
    void testEntityWithCustomSchema() throws SQLException {
        execute("CREATE SCHEMA IF NOT EXISTS custom_schema");
        execute("CREATE TABLE custom_schema.schema_entity (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(SchemaEntity.class));

        assertTrue(errors.isEmpty(), "Expected no errors for entity in custom schema but got: " + errors);
    }

    @Test
    void testEntityWithCustomSchemaTableNotFound() throws SQLException {
        // Create the schema but not the table.
        execute("CREATE SCHEMA IF NOT EXISTS missing_schema");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(MissingSchemaEntity.class));

        assertEquals(1, errors.size());
        assertEquals(ErrorKind.TABLE_NOT_FOUND, errors.getFirst().kind());
    }

    @Test
    void testEntityWithCustomSchemaNotFoundInDefaultSchema() throws SQLException {
        // Create the table in the default schema, not in custom_schema.
        execute("CREATE TABLE schema_entity (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(SchemaEntity.class));

        // Should fail because the table exists in the default schema, not in custom_schema.
        assertEquals(1, errors.size());
        assertEquals(ErrorKind.TABLE_NOT_FOUND, errors.getFirst().kind());
    }

    @Test
    void testDbIgnoreOnType() {
        // No table created, but @DbIgnore on the type should skip validation entirely.
        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(IgnoredEntity.class));

        assertTrue(errors.isEmpty(), "Expected no errors for @DbIgnore entity but got: " + errors);
    }

    @Test
    void testDbIgnoreOnField() throws SQLException {
        execute("CREATE TABLE field_ignored_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "description VARCHAR(255) NOT NULL, "  // VARCHAR in DB, LocalDate in entity (type mismatch)
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(FieldIgnoredEntity.class));

        // The description field has a type mismatch, but @DbIgnore suppresses it.
        assertTrue(errors.isEmpty(), "Expected no errors for @DbIgnore field but got: " + errors);
    }

    // UK validation tests

    @Test
    void testUniqueKeyWithMatchingConstraint() throws SQLException {
        execute("CREATE TABLE unique_key_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "email VARCHAR(255) NOT NULL, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id), "
                + "UNIQUE (email))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(UniqueKeyEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                "Expected no UNIQUE_KEY_MISSING when unique constraint exists, but got: " + errors);
    }

    @Test
    void testUniqueKeyMissing() throws SQLException {
        execute("CREATE TABLE unique_key_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "email VARCHAR(255) NOT NULL, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(UniqueKeyEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING
                        && error.message().contains("email")),
                "Expected UNIQUE_KEY_MISSING for email column, but got: " + errors);
        // Should be a warning, not an error.
        assertTrue(errors.stream()
                .filter(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING)
                .allMatch(error -> error.kind().warning()));
    }

    @Test
    void testCompoundUniqueKeyWithMatchingConstraint() throws SQLException {
        execute("CREATE TABLE compound_unique_key_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "user_id INTEGER NOT NULL, "
                + "email VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id), "
                + "UNIQUE (user_id, email))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(CompoundUniqueKeyEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                "Expected no UNIQUE_KEY_MISSING for compound unique key, but got: " + errors);
    }

    @Test
    void testCompoundUniqueKeyMissing() throws SQLException {
        execute("CREATE TABLE compound_unique_key_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "user_id INTEGER NOT NULL, "
                + "email VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(CompoundUniqueKeyEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING
                        && error.message().contains("uniqueKey")));
    }

    @Test
    void testUniqueKeyIgnoredByDbIgnore() throws SQLException {
        execute("CREATE TABLE unique_key_ignored_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "email VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(UniqueKeyIgnoredEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                "Expected no UNIQUE_KEY_MISSING when @DbIgnore suppresses it, but got: " + errors);
    }

    // FK validation tests

    @Test
    void testForeignKeyWithMatchingConstraint() throws SQLException {
        execute("CREATE TABLE foreign_key_target ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_source_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_target_id INTEGER, "
                + "PRIMARY KEY (id), "
                + "FOREIGN KEY (foreign_key_target_id) REFERENCES foreign_key_target(id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ForeignKeySourceEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING),
                "Expected no FOREIGN_KEY_MISSING when FK constraint exists, but got: " + errors);
    }

    @Test
    void testForeignKeyMissing() throws SQLException {
        execute("CREATE TABLE foreign_key_target ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_source_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_target_id INTEGER, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ForeignKeySourceEntity.class));

        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING
                        && error.message().contains("foreign_key_target_id")),
                "Expected FOREIGN_KEY_MISSING for FK column, but got: " + errors);
        // Should be a warning, not an error.
        assertTrue(errors.stream()
                .filter(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING)
                .allMatch(error -> error.kind().warning()));
    }

    @Test
    void testForeignKeyIgnoredByDbIgnore() throws SQLException {
        execute("CREATE TABLE foreign_key_target ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_ignored_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_target_id INTEGER, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ForeignKeyIgnoredEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING),
                "Expected no FOREIGN_KEY_MISSING when @DbIgnore suppresses it, but got: " + errors);
    }

    // Polymorphic FK validation tests

    // Polymorphic FK: sealed Data interface with independent entity subtypes in separate tables.
    sealed interface Commentable extends Data permits CommentablePost, CommentablePhoto {}
    record CommentablePost(@PK Integer id, String title) implements Commentable, Entity<Integer> {}
    record CommentablePhoto(@PK Integer id, String url) implements Commentable, Entity<Integer> {}

    public record EntityWithPolymorphicFk(
            @PK Integer id,
            String name,
            @Nullable @FK Ref<Commentable> commentable
    ) implements Entity<Integer> {}

    @Test
    void testPolymorphicFkSkipsValidation() throws SQLException {
        execute("CREATE TABLE commentable_post (id INTEGER AUTO_INCREMENT, title VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE commentable_photo (id INTEGER AUTO_INCREMENT, url VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE entity_with_polymorphic_fk ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "commentable_type VARCHAR(255), "
                + "commentable_id INTEGER, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(EntityWithPolymorphicFk.class));

        // Polymorphic FK should skip FK constraint validation (no FOREIGN_KEY_MISSING error).
        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING),
                "Expected no FOREIGN_KEY_MISSING for polymorphic FK, but got: " + errors);
    }

    // Primary key missing tests

    @Test
    void testPrimaryKeyMissing() throws SQLException {
        // Table has no PRIMARY KEY constraint.
        execute("CREATE TABLE primary_key_missing_entity (id INTEGER NOT NULL, name VARCHAR(255) NOT NULL)");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(PrimaryKeyMissingEntity.class));

        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.PRIMARY_KEY_MISSING),
                "Expected PRIMARY_KEY_MISSING when DB has no PK constraint, but got: " + errors);
        // Should be a warning, not an error.
        assertTrue(errors.stream()
                .filter(error -> error.kind() == ErrorKind.PRIMARY_KEY_MISSING)
                .allMatch(error -> error.kind().warning()));
    }

    @Test
    void testPrimaryKeyMissingSuppressedByConstraintFalse() throws SQLException {
        // Table has no PRIMARY KEY constraint.
        execute("CREATE TABLE primary_key_missing_no_constraint_entity (id INTEGER NOT NULL, name VARCHAR(255) NOT NULL)");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(PrimaryKeyMissingNoConstraintEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.PRIMARY_KEY_MISSING),
                "Expected no PRIMARY_KEY_MISSING with @PK(constraint = false), but got: " + errors);
    }

    // Foreign key mismatch tests

    @Test
    void testForeignKeyMismatch() throws SQLException {
        // Create the wrong target table and the entity's expected target.
        execute("CREATE TABLE wrong_foreign_key_target (id INTEGER AUTO_INCREMENT, label VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_mismatch_target (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        // FK constraint references wrong_foreign_key_target instead of foreign_key_mismatch_target.
        execute("CREATE TABLE foreign_key_mismatch_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_mismatch_target_id INTEGER, "
                + "PRIMARY KEY (id), "
                + "FOREIGN KEY (foreign_key_mismatch_target_id) REFERENCES wrong_foreign_key_target(id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ForeignKeyMismatchEntity.class));

        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISMATCH),
                "Expected FOREIGN_KEY_MISMATCH when FK points to wrong table, but got: " + errors);
        // Should be a hard error, not a warning.
        assertFalse(errors.stream()
                .filter(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISMATCH)
                .anyMatch(error -> error.kind().warning()));
    }

    @Test
    void testForeignKeyMismatchNotSuppressedByConstraintFalse() throws SQLException {
        // FK mismatch should still be reported even with @FK(constraint = false).
        execute("CREATE TABLE wrong_foreign_key_target (id INTEGER AUTO_INCREMENT, label VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_mismatch_target (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_mismatch_no_constraint_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_mismatch_target_id INTEGER, "
                + "PRIMARY KEY (id), "
                + "FOREIGN KEY (foreign_key_mismatch_target_id) REFERENCES wrong_foreign_key_target(id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ForeignKeyMismatchNoConstraintEntity.class));

        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISMATCH),
                "Expected FOREIGN_KEY_MISMATCH even with @FK(constraint = false), but got: " + errors);
    }

    // Constraint flag suppression tests

    @Test
    void testForeignKeyMissingSuppressedByConstraintFalse() throws SQLException {
        execute("CREATE TABLE foreign_key_target (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");
        execute("CREATE TABLE foreign_key_no_constraint_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "name VARCHAR(255) NOT NULL, "
                + "foreign_key_target_id INTEGER, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(ForeignKeyNoConstraintEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING),
                "Expected no FOREIGN_KEY_MISSING with @FK(constraint = false), but got: " + errors);
    }

    @Test
    void testUniqueKeyMissingSuppressedByConstraintFalse() throws SQLException {
        execute("CREATE TABLE unique_key_no_constraint_entity ("
                + "id INTEGER AUTO_INCREMENT, "
                + "email VARCHAR(255) NOT NULL, "
                + "name VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (id))");

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(UniqueKeyNoConstraintEntity.class));

        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                "Expected no UNIQUE_KEY_MISSING with @UK(constraint = false), but got: " + errors);
    }

    // Helpers

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Simple DataSource that creates new connections to an H2 in-memory database.
     */
    private static final class SimpleDataSource implements DataSource {
        private final String url;

        SimpleDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() { return null; }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() { return 0; }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper.");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @DbTable("nonexistent_table")
    public record MissingEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @ProjectionQuery("SELECT id, name FROM valid_city")
    public record ProjectionQueryType(
            @PK Integer id,
            String name
    ) implements st.orm.Projection<Integer> {}

    @Test
    void testValidateAndReportNonStrictExcludesWarnings() throws SQLException {
        execute("CREATE TABLE valid_city (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");

        SchemaValidator validator = SchemaValidator.of(dataSource);
        // City name is nullable in DB but non-null in entity, causing NULLABILITY_MISMATCH (a warning).
        List<String> errors = validator.validateAndReport(List.of(ValidCity.class), false);
        // Non-strict mode should exclude warnings from the returned list.
        assertTrue(errors.isEmpty(), "Expected no errors in non-strict mode, got: " + errors);
    }

    @Test
    void testValidateAndReportStrictIncludesWarnings() throws SQLException {
        execute("CREATE TABLE valid_city (id INTEGER AUTO_INCREMENT, name VARCHAR(255), PRIMARY KEY (id))");

        SchemaValidator validator = SchemaValidator.of(dataSource);
        List<String> errors = validator.validateAndReport(List.of(ValidCity.class), true);
        // Strict mode should include NULLABILITY_MISMATCH as an error.
        assertFalse(errors.isEmpty(), "Expected strict errors for nullable column, got: " + errors);
    }

    @Test
    void testValidateAndReportWithMissingTable() {
        SchemaValidator validator = SchemaValidator.of(dataSource);
        List<String> errors = validator.validateAndReport(List.of(MissingEntity.class), true);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("nonexistent_table")));
    }

    @Test
    void testValidateEmptyList() {
        SchemaValidator validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of());
        assertNotNull(errors);
        assertTrue(errors.isEmpty());
    }

    @Test
    void testValidateOrThrowThrowsForMissingTable() {
        SchemaValidator validator = SchemaValidator.of(dataSource);
        assertThrows(SchemaValidationException.class,
                () -> validator.validateOrThrow(List.of(MissingEntity.class)));
    }

    @Test
    void testValidateOrThrowPassesForValidTable() throws SQLException {
        execute("CREATE TABLE valid_city (id INTEGER AUTO_INCREMENT, name VARCHAR(255) NOT NULL, PRIMARY KEY (id))");

        SchemaValidator validator = SchemaValidator.of(dataSource);
        // Should not throw for a valid entity.
        validator.validateOrThrow(List.of(ValidCity.class));
    }

    @Test
    void testSchemaValidationExceptionErrors() {
        List<SchemaValidationError> errors = List.of(
                new SchemaValidationError(ValidCity.class, ErrorKind.TABLE_NOT_FOUND, "Table not found."),
                new SchemaValidationError(ValidCity.class, ErrorKind.COLUMN_NOT_FOUND, "Column not found.")
        );
        SchemaValidationException exception = new SchemaValidationException(errors);
        assertEquals(2, exception.getErrors().size());
        assertTrue(exception.getMessage().contains("Schema validation failed"));
    }

    @Test
    void testProjectionQueryTypeSkippedDuringValidation() {
        // ProjectionQuery types should be skipped, so no TABLE_NOT_FOUND even though no table exists.
        SchemaValidator validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(ProjectionQueryType.class));
        assertTrue(errors.isEmpty(), "Expected ProjectionQuery to be skipped, but got: " + errors);
    }
}
