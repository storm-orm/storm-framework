package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.StormConfig.VALIDATION_RECORD_MODE;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Entity;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.Inline;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.ProjectionQuery;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.Version;
import st.orm.core.template.SqlTemplate.NamedParameter;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;

/**
 * Tests for {@link RecordValidation} to cover data type validation, parameter validation, and
 * various edge cases in record structure validation.
 */
class RecordValidationTest {

    // Valid entity types

    public record SimpleEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record LongPkEntity(
            @PK Long id,
            String name
    ) implements Entity<Long> {}

    public record StringPkEntity(
            @PK String id,
            String name
    ) implements Entity<String> {}

    // Invalid entity types

    public record NoPkEntity(
            String name
    ) implements Entity<Void> {}

    // We need a separate class to test multiple PKs
    public record MultiplePkEntity(
            @PK Integer id,
            @PK Integer id2,
            String name
    ) implements Entity<Integer> {}

    // Inline record validation

    public record InlineData(String street, String zipCode) {}

    public record EntityWithInline(
            @PK Integer id,
            @Inline InlineData address
    ) implements Entity<Integer> {}

    // Invalid PK type

    public record FloatPkEntity(
            @PK Float id,
            String name
    ) implements Entity<Float> {}

    public record DoublePkTypeEntity(
            @PK Double id,
            String name
    ) implements Entity<Double> {}

    // Temporal PK types

    public record LocalDatePkEntity(
            @PK LocalDate day,
            String name
    ) implements Entity<LocalDate> {}

    public record LocalDateTimePkEntity(
            @PK LocalDateTime at,
            String name
    ) implements Entity<LocalDateTime> {}

    public record InstantPkEntity(
            @PK Instant at,
            String name
    ) implements Entity<Instant> {}

    public record SnapshotPk(int accountId, LocalDate snapshotDate) {}

    public record SnapshotEntity(
            @PK SnapshotPk id,
            String name
    ) implements Entity<SnapshotPk> {}

    public record OffsetDateTimePkEntity(
            @PK OffsetDateTime at,
            String name
    ) implements Entity<OffsetDateTime> {}

    public record ZonedDateTimePkEntity(
            @PK ZonedDateTime at,
            String name
    ) implements Entity<ZonedDateTime> {}

    public record LocalTimePkEntity(
            @PK LocalTime at,
            String name
    ) implements Entity<LocalTime> {}

    public record DatePkEntity(
            @PK Date at,
            String name
    ) implements Entity<Date> {}

    public record OffsetPk(int accountId, OffsetDateTime at) {}

    public record OffsetCompoundPkEntity(
            @PK OffsetPk id,
            String name
    ) implements Entity<OffsetPk> {}

    // FK validation

    public record ReferencedEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record EntityWithFk(
            @PK Integer id,
            @FK ReferencedEntity ref
    ) implements Entity<Integer> {}

    public record EntityWithRefFk(
            @PK Integer id,
            @Nullable @FK Ref<ReferencedEntity> ref
    ) implements Entity<Integer> {}

    // FK inlined (invalid)

    public record InlinedFkEntity(
            @PK Integer id,
            @FK @Inline InlineData address
    ) implements Entity<Integer> {}

    // Version validation

    public record EntityWithVersion(
            @PK Integer id,
            String name,
            @Version int version
    ) implements Entity<Integer> {}

    public record MultipleVersionEntity(
            @PK Integer id,
            @Version int version1,
            @Version int version2
    ) implements Entity<Integer> {}

    // Ref without FK

    public record RefWithoutFk(
            @PK Integer id,
            Ref<ReferencedEntity> ref
    ) implements Entity<Integer> {}

    // Entity inside entity without FK

    public record EntityInsideEntity(
            @PK Integer id,
            ReferencedEntity nested
    ) implements Entity<Integer> {}

    // Inline with PK (invalid)

    public record InlineWithPk(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record EntityWithInlineHavingPk(
            @PK Integer id,
            InlineWithPk nested
    ) implements Entity<Integer> {}

    // Projection tests

    public record SimpleProjection(
            @PK Integer id,
            String name
    ) implements Projection<Integer> {}

    public record ProjectionInsideProjection(
            @PK Integer id,
            SimpleProjection nested
    ) implements Projection<Integer> {}

    public record EntityInsideProjection(
            @PK Integer id,
            ReferencedEntity nested
    ) implements Projection<Integer> {}

    // Projection id type argument

    public record VoidProjectionWithoutPk(
            String name
    ) implements Projection<Void> {}

    public record VoidProjectionWithPk(
            @PK Integer id,
            String name
    ) implements Projection<Void> {}

    public record UnmappedIdProjection(
            String name
    ) implements Projection<Integer> {}

    public record MismatchedIdProjection(
            @PK Long id,
            String name
    ) implements Projection<Integer> {}

    public record PrimitivePkProjection(
            @PK int id,
            String name
    ) implements Projection<Integer> {}

    public record FkPkProjection(
            @PK @FK ReferencedEntity ref,
            String name
    ) implements Projection<Integer> {}

    public record MismatchedFkPkProjection(
            @PK @FK ReferencedEntity ref,
            String name
    ) implements Projection<ReferencedEntity> {}

    public record RefPkProjection(
            @PK @FK Ref<ReferencedEntity> ref,
            String name
    ) implements Projection<Integer> {}

    public record CompoundKey(int userId, int roleId) {}

    public record CompoundPkProjection(
            @PK CompoundKey key,
            String name
    ) implements Projection<CompoundKey> {}

    public record MismatchedCompoundPkProjection(
            @PK CompoundKey key,
            String name
    ) implements Projection<Integer> {}

    public interface GenericView<T> extends Projection<T> {}

    public record IndirectIdProjection(
            @PK Integer id,
            String name
    ) implements GenericView<Integer> {}

    public record MismatchedIndirectIdProjection(
            @PK Long id,
            String name
    ) implements GenericView<Integer> {}

    @SuppressWarnings("rawtypes")
    public record RawProjection(
            @PK Integer id,
            String name
    ) implements Projection {}

    // ProjectionQuery

    @ProjectionQuery("SELECT id, name FROM simple_entity")
    public record ValidProjectionQuery(
            @PK Integer id,
            String name
    ) implements Projection<Integer> {}

    @ProjectionQuery("")
    public record EmptyProjectionQuery(
            @PK Integer id,
            String name
    ) implements Projection<Integer> {}

    @ProjectionQuery("SELECT id, name FROM simple_entity")
    public record ProjectionQueryOnEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    // Data class wrapping entities

    public record DataWrappingEntity(
            ReferencedEntity entity
    ) implements Data {}

    // Non-Data type

    public record NotData(Integer id) {}

    // FK that's not a Data or Ref

    public record FkWithInvalidType(
            @PK Integer id,
            @FK String invalid
    ) implements Entity<Integer> {}

    // Inline non-record

    public record InlineNonRecord(
            @PK Integer id,
            @Inline String invalid
    ) implements Entity<Integer> {}

    // Test methods

    @Test
    void testValidSimpleEntity() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(SimpleEntity.class));
    }

    @Test
    void testValidLongPkEntity() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(LongPkEntity.class));
    }

    @Test
    void testValidStringPkEntity() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(StringPkEntity.class));
    }

    @Test
    void testEntityWithoutPkFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(NoPkEntity.class));
    }

    @Test
    void testEntityWithoutPkButNotRequired() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(NoPkEntity.class, false));
    }

    @Test
    void testMultiplePksFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(MultiplePkEntity.class));
    }

    @Test
    void testInvalidPkTypeFloat() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(FloatPkEntity.class));
    }

    @Test
    void testInvalidPkTypeDouble() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(DoublePkTypeEntity.class));
    }

    @Test
    void testLocalDatePk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(LocalDatePkEntity.class));
    }

    @Test
    void testLocalDateTimePk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(LocalDateTimePkEntity.class));
    }

    @Test
    void testInstantPk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(InstantPkEntity.class));
    }

    @Test
    void testCompoundPkWithLocalDate() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(SnapshotEntity.class));
    }

    @Test
    void testInvalidPkTypeOffsetDateTime() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(OffsetDateTimePkEntity.class));
    }

    @Test
    void testInvalidPkTypeZonedDateTime() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(ZonedDateTimePkEntity.class));
    }

    @Test
    void testInvalidPkTypeLocalTime() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(LocalTimePkEntity.class));
    }

    @Test
    void testInvalidPkTypeDate() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(DatePkEntity.class));
    }

    @Test
    void testInvalidCompoundPkComponentOffsetDateTime() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(OffsetCompoundPkEntity.class));
    }

    @Test
    void testEntityWithFk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(EntityWithFk.class));
    }

    @Test
    void testEntityWithRefFk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(EntityWithRefFk.class));
    }

    @Test
    void testFkWithInlineFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(InlinedFkEntity.class));
    }

    @Test
    void testEntityWithVersion() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(EntityWithVersion.class));
    }

    @Test
    void testMultipleVersionsFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(MultipleVersionEntity.class));
    }

    @Test
    void testRefWithoutFkFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(RefWithoutFk.class));
    }

    @Test
    void testEntityInsideEntityWithoutFkFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(EntityInsideEntity.class));
    }

    @Test
    void testEntityWithInlineHavingPkFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(EntityWithInlineHavingPk.class));
    }

    @Test
    void testValidInlineEntity() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(EntityWithInline.class));
    }

    @Test
    void testProjectionInsideProjectionFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(ProjectionInsideProjection.class));
    }

    @Test
    void testEntityInsideProjectionAllowedWithoutFk() {
        // Entity inside projection without @FK is allowed because the containing type check
        // in RecordValidation only catches Entity-inside-Entity and Projection-inside-Projection.
        assertDoesNotThrow(() -> RecordValidation.validateDataType(EntityInsideProjection.class));
    }

    @Test
    void testEmptyProjectionQueryFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(EmptyProjectionQuery.class));
    }

    @Test
    void testProjectionQueryOnEntityFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(ProjectionQueryOnEntity.class));
    }

    @Test
    void testValidProjectionQuery() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(ValidProjectionQuery.class));
    }

    @Test
    void testDataWrappingEntity() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(DataWrappingEntity.class, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNonDataTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordValidation.validateDataType((Class<? extends Data>) (Class<?>) NotData.class));
    }

    @Test
    void testFkWithInvalidTypeFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(FkWithInvalidType.class));
    }

    @Test
    void testInlineNonRecordFails() {
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateDataType(InlineNonRecord.class));
    }

    // Parameter validation tests

    @Test
    void testValidPositionalParameters() {
        List<Parameter> params = List.of(
                new PositionalParameter(1, "hello"),
                new PositionalParameter(2, 42)
        );
        assertDoesNotThrow(() -> RecordValidation.validateParameters(params, 2));
    }

    @Test
    void testNoPositionalParameters() {
        List<Parameter> params = List.of();
        assertDoesNotThrow(() -> RecordValidation.validateParameters(params, 0));
    }

    @Test
    void testMismatchedPositionalParameterCount() {
        List<Parameter> params = List.of(
                new PositionalParameter(1, "hello")
        );
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateParameters(params, 2));
    }

    @Test
    void testPositionalParameterNotStartingAtOne() {
        List<Parameter> params = List.of(
                new PositionalParameter(2, "hello")
        );
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateParameters(params, 1));
    }

    @Test
    void testPositionalParameterGap() {
        List<Parameter> params = List.of(
                new PositionalParameter(1, "hello"),
                new PositionalParameter(3, 42)
        );
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateParameters(params, 2));
    }

    @Test
    void testNamedParametersWithSameValueOk() {
        List<Parameter> params = List.of(
                new NamedParameter("name", "hello"),
                new NamedParameter("name", "hello")
        );
        assertDoesNotThrow(() -> RecordValidation.validateParameters(params, 0));
    }

    @Test
    void testNamedParametersWithDifferentValuesFails() {
        List<Parameter> params = List.of(
                new NamedParameter("name", "hello"),
                new NamedParameter("name", "world")
        );
        assertThrows(SqlTemplateException.class, () -> RecordValidation.validateParameters(params, 0));
    }

    @Test
    void testValidSimpleProjection() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(SimpleProjection.class));
    }

    @Test
    void testTooManyPositionalParametersFails() {
        List<Parameter> params = List.of(
                new PositionalParameter(1, "hello"),
                new PositionalParameter(2, "world"),
                new PositionalParameter(3, "extra")
        );
        // Three positional parameters when only 2 are expected should fail.
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateParameters(params, 2));
    }

    public record FkTarget(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record FkWithAutoGeneratedPk(
            @PK(generation = GenerationStrategy.IDENTITY) @FK Ref<FkTarget> id,
            String name
    ) implements Entity<Ref<FkTarget>> {}

    @Test
    void testFkWithAutoGeneratedPkFails() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(FkWithAutoGeneratedPk.class));
    }

    public record EntityWithProjectionInside(
            @PK Integer id,
            SimpleProjection nested
    ) implements Entity<Integer> {}

    @Test
    void testProjectionInsideEntityWithoutFkFails() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(EntityWithProjectionInside.class));
    }

    public record TargetEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record ProjectionWithEntityInside(
            @PK Integer id,
            TargetEntity nested
    ) implements Projection<Integer> {}

    @Test
    void testEntityInsideProjectionIsAllowedWhenNotMarkedFk() {
        // Entity inside projection without @FK is allowed because the containing type check
        // in RecordValidation only catches Entity-inside-Entity and Projection-inside-Projection.
        assertDoesNotThrow(() -> RecordValidation.validateDataType(ProjectionWithEntityInside.class));
    }

    public record InlineWithPrimaryKey(
            @PK Integer id,
            String value
    ) implements Entity<Integer> {}

    public record EntityWithInlinedPk(
            @PK Integer id,
            InlineWithPrimaryKey nested
    ) implements Entity<Integer> {}

    @Test
    void testInlinedFieldWithPkInsideEntityFails() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(EntityWithInlinedPk.class));
    }

    public record ExtEntityInsideEntity(
            @PK Integer id,
            FkTarget nested
    ) implements Entity<Integer> {}

    @Test
    void testEntityInsideEntityWithoutFkFailsExtended() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(ExtEntityInsideEntity.class));
    }

    public record CompoundPkWithFloat(float x, float y) {}

    public record EntityWithInvalidCompoundPk(
            @PK CompoundPkWithFloat pk,
            String name
    ) implements Entity<CompoundPkWithFloat> {}

    @Test
    void testCompoundPkWithInvalidNestedTypeFails() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(EntityWithInvalidCompoundPk.class));
    }

    public record InvalidFkTarget(
            String name
    ) implements Entity<Void> {}

    public record EntityWithInvalidFkTarget(
            @PK Integer id,
            @FK InvalidFkTarget fkTarget
    ) implements Entity<Integer> {}

    @Test
    void testFkTargetValidationErrorPropagates() {
        // The FK target (InvalidFkTarget) has no primary key, which should cause a validation error.
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(EntityWithInvalidFkTarget.class));
    }

    public record ValidRefFkEntity(
            @PK Integer id,
            @Nullable @FK Ref<FkTarget> reference
    ) implements Entity<Integer> {}

    @Test
    void testValidRefFk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(ValidRefFkEntity.class));
    }

    public record ExtDataWrappingEntity(
            FkTarget entity
    ) implements Data {}

    @Test
    void testDataWrappingEntityValid() {
        // Data classes are allowed to wrap entities without @FK.
        assertDoesNotThrow(() -> RecordValidation.validateDataType(ExtDataWrappingEntity.class, false));
    }

    public record OuterProjection(
            @PK Integer id,
            SimpleProjection nested
    ) implements Projection<Integer> {}

    @Test
    void testProjectionInsideProjectionFailsExtended() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(OuterProjection.class));
    }

    @Test
    void testFkWithAutoGeneratedPkErrorMessage() {
        SqlTemplateException exception = assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(FkWithAutoGeneratedPk.class));
        assertTrue(exception.getMessage().contains("auto-generated"),
                "Expected error message to mention 'auto-generated', got: " + exception.getMessage());
    }

    // Projection id type argument

    @Test
    void testProjectionVoidWithoutPk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(VoidProjectionWithoutPk.class));
    }

    @Test
    void testProjectionVoidWithPkFails() {
        SqlTemplateException exception = assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(VoidProjectionWithPk.class));
        assertTrue(exception.getMessage().contains("Projection<Void>"),
                "Expected error message to mention 'Projection<Void>', got: " + exception.getMessage());
    }

    @Test
    void testProjectionWithUnmappedIdAllowed() {
        // A projection may declare a row identity that is not among its mapped columns; this supports detached
        // refs while the id-based repository operations require the mapped key.
        assertDoesNotThrow(() -> RecordValidation.validateDataType(UnmappedIdProjection.class));
    }

    @Test
    void testProjectionIdTypeMismatchFails() {
        SqlTemplateException exception = assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(MismatchedIdProjection.class));
        assertTrue(exception.getMessage().contains("identifies rows by"),
                "Expected error message to name the row identity type, got: " + exception.getMessage());
    }

    @Test
    void testProjectionPrimitivePkMatchesWrapper() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(PrimitivePkProjection.class));
    }

    @Test
    void testProjectionFkPkIdentifiedByReferencedKey() {
        // A foreign key typed primary key identifies rows by the referenced table's key.
        assertDoesNotThrow(() -> RecordValidation.validateDataType(FkPkProjection.class));
    }

    @Test
    void testProjectionFkPkComponentTypeFails() {
        // The component type is not the row identity; the referenced table's key is.
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(MismatchedFkPkProjection.class));
    }

    @Test
    void testProjectionRefPkIdentifiedByReferencedKey() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(RefPkProjection.class));
    }

    @Test
    void testProjectionCompoundPk() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(CompoundPkProjection.class));
    }

    @Test
    void testProjectionCompoundPkMismatchFails() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(MismatchedCompoundPkProjection.class));
    }

    @Test
    void testProjectionIdTypeThroughGenericInterface() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(IndirectIdProjection.class));
    }

    @Test
    void testProjectionIdTypeMismatchThroughGenericInterfaceFails() {
        assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(MismatchedIndirectIdProjection.class));
    }

    @Test
    void testRawProjectionNotChecked() {
        assertDoesNotThrow(() -> RecordValidation.validateDataType(RawProjection.class));
    }

    @Test
    void testProjectionInsideEntityErrorMessage() {
        SqlTemplateException exception = assertThrows(SqlTemplateException.class,
                () -> RecordValidation.validateDataType(EntityWithProjectionInside.class));
        assertTrue(exception.getMessage().contains("@FK") || exception.getMessage().contains("@Inline"),
                "Expected error message to mention @FK or @Inline, got: " + exception.getMessage());
    }

    // Record validation mode resolution

    @Test
    void testRecordModeDefaultsToFail() {
        assertEquals("fail", RecordValidation.resolveRecordMode(StormConfig.defaults()));
    }

    @Test
    void testRecordModeBlankFallsBackToFail() {
        var config = StormConfig.of(Map.of(VALIDATION_RECORD_MODE, "  "));
        assertEquals("fail", RecordValidation.resolveRecordMode(config));
    }

    @Test
    void testRecordModeMatchesCaseInsensitivelyAfterTrimming() {
        var config = StormConfig.of(Map.of(VALIDATION_RECORD_MODE, "  WARN  "));
        assertEquals("warn", RecordValidation.resolveRecordMode(config));
    }

    @Test
    void testRecordModeUnknownValueFailsFast() {
        var config = StormConfig.of(Map.of(VALIDATION_RECORD_MODE, "fial"));
        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> RecordValidation.resolveRecordMode(config));
        assertEquals("Invalid storm.validation.record_mode: 'fial'. Valid values are: none, warn, fail.",
                exception.getMessage());
    }

}
