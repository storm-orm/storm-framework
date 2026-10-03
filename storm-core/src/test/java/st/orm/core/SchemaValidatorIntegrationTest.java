package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Data;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.GenerationStrategy;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.PetType;
import st.orm.core.model.Visit;
import st.orm.core.model.polymorphic.Animal;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.SchemaValidationError;
import st.orm.core.template.impl.SchemaValidationException;
import st.orm.core.template.impl.SchemaValidator;

/**
 * Integration tests for {@link SchemaValidator} covering table existence, column validation,
 * primary key matching, unique key validation, foreign key validation, and error reporting.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
@ExtendWith(QuietValidationLog.class)
public class SchemaValidatorIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Valid types

    @Test
    public void testValidateValidTypes() {
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(City.class));
        assertNotNull(errors);
        // Filter out warnings (e.g., NULLABILITY_MISMATCH) to check for hard errors only.
        List<SchemaValidationError> hardErrors = errors.stream()
                .filter(e -> !e.kind().warning())
                .toList();
        assertTrue(hardErrors.isEmpty(), "Expected no hard errors for City, got: " + hardErrors);
    }

    @Test
    public void testValidateMultipleValidTypesHasNoHardErrors() {
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(City.class, Owner.class, Pet.class));
        // Filter out warnings like NULLABILITY_MISMATCH; only check for structural errors.
        List<SchemaValidationError> hardErrors = errors.stream()
                .filter(e -> !e.kind().warning())
                .toList();
        assertTrue(hardErrors.isEmpty(),
                "Expected no hard errors for valid types, got: " + hardErrors);
    }

    // Views

    @DbTable("owner_view")
    public record OwnerViewNames(
            @PK Integer id,
            String firstName,
            String lastName
    ) implements Projection<Integer> {}

    @Test
    public void testViewColumnsAreNotReportedAsNullable() {
        // A view cannot declare its columns NOT NULL, so the metadata reports them as nullable whatever the
        // query beneath returns; the projection's non-null fields have nothing to be checked against.
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(OwnerViewNames.class));
        assertTrue(errors.isEmpty(), "Expected no findings for a projection over a view, got: " + errors);
    }

    // Missing table

    @DbTable("nonexistent_table")
    public record NonexistentTableEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @Test
    public void testValidateDetectsMissingTable() {
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(NonexistentTableEntity.class));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == SchemaValidationError.ErrorKind.TABLE_NOT_FOUND));
    }

    // Missing column

    @DbTable("city")
    public record CityWithExtraColumn(
            @PK Integer id,
            String name,
            String nonexistentColumn
    ) implements Entity<Integer> {}

    @Test
    public void testValidateDetectsMissingColumn() {
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(CityWithExtraColumn.class));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == SchemaValidationError.ErrorKind.COLUMN_NOT_FOUND));
    }

    // Type incompatible

    @DbTable("city")
    public record CityWithIncompatibleType(
            @PK String id,
            String name
    ) implements Entity<String> {}

    @Test
    public void testValidateDetectsTypeIncompatibility() {
        var validator = SchemaValidator.of(dataSource);
        // City.id is an INTEGER in the database, but mapped as String in the entity.
        List<SchemaValidationError> errors = validator.validate(List.of(CityWithIncompatibleType.class));
        List<SchemaValidationError> hardErrors = errors.stream()
                .filter(e -> !e.kind().warning())
                .toList();
        assertFalse(hardErrors.isEmpty(),
                "Expected hard errors for String-mapped INTEGER column, got: " + errors);
    }

    // validateOrThrow throws for invalid type

    @Test
    public void testValidateOrThrowThrowsForInvalidType() {
        var validator = SchemaValidator.of(dataSource);
        assertThrows(SchemaValidationException.class,
                () -> validator.validateOrThrow(List.of(NonexistentTableEntity.class)));
    }

    // validateAndReport

    @Test
    public void testValidateAndReportNonStrictIgnoresNullabilityWarnings() {
        var validator = SchemaValidator.of(dataSource);
        List<String> errors = validator.validateAndReport(List.of(City.class), false);
        // Non-strict mode should exclude NULLABILITY_MISMATCH warnings from the error list.
        assertTrue(errors.isEmpty(), "Expected no errors in non-strict mode for City, got: " + errors);
    }

    /** Requires the owner's telephone, which the schema leaves nullable. */
    @DbTable("owner")
    public record OwnerRequiredTelephone(
            @PK Integer id,
            String telephone
    ) implements Projection<Integer> {}

    @Test
    public void testValidateAndReportStrictIncludesNullabilityWarnings() {
        var validator = SchemaValidator.of(dataSource);
        List<String> errors = validator.validateAndReport(List.of(OwnerRequiredTelephone.class), true);
        // Strict mode should treat NULLABILITY_MISMATCH as an error.
        assertFalse(errors.isEmpty(),
                "Expected strict mode to report nullability warnings as errors for OwnerRequiredTelephone");
        assertTrue(errors.stream().anyMatch(e -> e.contains("NULLABILITY_MISMATCH")),
                "Expected NULLABILITY_MISMATCH in strict error messages, got: " + errors);
    }

    @Test
    public void testValidateAndReportForMissingTableIncludesTableName() {
        var validator = SchemaValidator.of(dataSource);
        List<String> errors = validator.validateAndReport(List.of(NonexistentTableEntity.class), true);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("nonexistent_table")),
                "Error message should reference the missing table name, got: " + errors);
    }

    // Empty type list

    @Test
    public void testValidateEmptyTypeList() {
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of());
        assertNotNull(errors);
        assertTrue(errors.isEmpty());
    }

    // Sequence validation (testing with a type that references a nonexistent sequence)

    @DbTable("city")
    public record CityWithSequence(
            @PK(generation = GenerationStrategy.SEQUENCE, sequence = "nonexistent_seq") Integer id,
            String name
    ) implements Entity<Integer> {}

    @Test
    public void testValidateDetectsMissingSequence() {
        var validator = SchemaValidator.of(dataSource);
        List<SchemaValidationError> errors = validator.validate(List.of(CityWithSequence.class));
        // Should detect that the sequence does not exist.
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == SchemaValidationError.ErrorKind.SEQUENCE_NOT_FOUND));
    }

    @DbTable("non_existent_table")
    record NonExistentEntity(@PK Integer id, String name) implements Entity<Integer> {}

    @DbTable("city")
    record CityWithExtraColumnForOrm(@PK Integer id, String name, String extraColumn) implements Entity<Integer> {}

    @Test
    public void testValidateSchemaForCityReturnsEmpty() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(City.class));
        assertTrue(errors.isEmpty(), "City schema should validate without errors: " + errors);
    }

    @Test
    public void testValidateSchemaForOwnerReturnsEmpty() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(Owner.class));
        assertTrue(errors.isEmpty(), "Owner schema should validate without errors: " + errors);
    }

    @Test
    public void testValidateSchemaForPetReturnsEmpty() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(Pet.class));
        assertTrue(errors.isEmpty(), "Pet schema should validate without errors: " + errors);
    }

    @Test
    public void testValidateSchemaForMultipleTypesReturnsEmpty() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(City.class, Owner.class, PetType.class));
        assertTrue(errors.isEmpty(), "Multiple types should validate without errors: " + errors);
    }

    @Test
    public void testValidateSchemaOrThrowForValidTypesDoesNotThrow() {
        var orm = ORMTemplate.of(dataSource);
        assertDoesNotThrow(() -> orm.validateSchemaOrThrow(List.of(City.class, Owner.class)));
    }

    @Test
    public void testValidateSchemaForSingleTableAnimal() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(Animal.class));
        assertTrue(errors.isEmpty(), "Animal (single-table) schema should validate without errors: " + errors);
    }

    @Test
    public void testValidateSchemaForJoinedAnimal() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(JoinedAnimal.class));
        assertTrue(errors.isEmpty(), "JoinedAnimal schema should validate without errors: " + errors);
    }

    @Test
    public void testValidateSchemaForNonExistentTableReportsError() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.<Class<? extends Data>>of(NonExistentEntity.class));
        assertFalse(errors.isEmpty(), "Non-existent table should produce validation errors");
    }

    @Test
    public void testValidateSchemaOrThrowForNonExistentTableThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.validateSchemaOrThrow(List.<Class<? extends Data>>of(NonExistentEntity.class)));
    }

    @Test
    public void testValidateSchemaForExtraColumnReportsError() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.<Class<? extends Data>>of(CityWithExtraColumnForOrm.class));
        assertFalse(errors.isEmpty(), "Entity with extra column should produce validation errors");
    }

    @Test
    public void testValidateSchemaForVisitReturnsEmpty() {
        var orm = ORMTemplate.of(dataSource);
        List<String> errors = orm.validateSchema(List.of(Visit.class));
        assertTrue(errors.isEmpty(), "Visit schema should validate without errors: " + errors);
    }
}
