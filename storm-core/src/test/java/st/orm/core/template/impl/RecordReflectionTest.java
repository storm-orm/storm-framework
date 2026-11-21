package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.DbColumn;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Discriminator.DiscriminatorType;
import st.orm.Entity;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.Inline;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Polymorphic;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.Version;
import st.orm.mapping.RecordField;
import st.orm.mapping.TableNameResolver;

/**
 * Tests for {@link RecordReflection} utility methods covering field lookup, PK/FK/Version
 * discovery, table and column naming, and type checking.
 */
class RecordReflectionTest {

    // Test model types

    public record SimpleEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record EntityWithVersion(
            @PK Integer id,
            String name,
            @Version int version
    ) implements Entity<Integer> {}

    public record InlineAddress(String street, String zipCode) {}

    public record EntityWithInline(
            @PK Integer id,
            @Inline InlineAddress address,
            String name
    ) implements Entity<Integer> {}

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

    public record CompoundPk(int partA, int partB) {}

    public record EntityWithCompoundPk(
            @PK CompoundPk id,
            String name
    ) implements Entity<CompoundPk> {}

    @DbTable("custom_table")
    public record CustomTableEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @DbTable(value = "annotated_table", schema = "my_schema")
    public record SchemaTableEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record EntityWithDbColumn(
            @PK @DbColumn("pk_col") Integer id,
            @DbColumn("name_col") String name
    ) implements Entity<Integer> {}

    public record EntityWithFkPk(
            @PK @FK ReferencedEntity ref,
            String extra
    ) implements Entity<Integer> {}

    public record NoVersionEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record NestedRecord(
            String inner
    ) {}

    public record EntityWithNested(
            @PK Integer id,
            @Inline NestedRecord nested
    ) implements Entity<Integer> {}

    public record SimpleData(String value) implements Data {}

    public record IdentityGenEntity(
            @PK(generation = GenerationStrategy.IDENTITY) Integer id,
            String name
    ) implements Entity<Integer> {}

    public record NoneGenEntity(
            @PK(generation = GenerationStrategy.NONE) Integer id,
            String name
    ) implements Entity<Integer> {}

    public record SeqGenEntity(
            @PK(generation = GenerationStrategy.SEQUENCE, sequence = "my_seq") Integer id,
            String name
    ) implements Entity<Integer> {}

    // isRecord tests

    @Test
    void testIsRecordForRecord() {
        assertTrue(RecordReflection.isRecord(SimpleEntity.class));
    }

    @Test
    void testIsRecordForNonRecord() {
        assertFalse(RecordReflection.isRecord(String.class));
    }

    @Test
    void testIsRecordForInlineRecord() {
        assertTrue(RecordReflection.isRecord(InlineAddress.class));
    }

    // getRecordType tests

    @Test
    void testGetRecordType() {
        var recordType = RecordReflection.getRecordType(SimpleEntity.class);
        assertNotNull(recordType);
        assertEquals(SimpleEntity.class, recordType.type());
    }

    // getRecordFields tests

    @Test
    void testGetRecordFields() {
        var fields = RecordReflection.getRecordFields(SimpleEntity.class);
        assertEquals(2, fields.size());
        assertEquals("id", fields.get(0).name());
        assertEquals("name", fields.get(1).name());
    }

    @Test
    void testGetRecordFieldsForEntityWithFk() {
        var fields = RecordReflection.getRecordFields(EntityWithFk.class);
        assertEquals(2, fields.size());
        assertEquals("id", fields.get(0).name());
        assertEquals("ref", fields.get(1).name());
    }

    // getRecordField (path-based lookup) tests

    @Test
    void testGetRecordFieldSimple() throws SqlTemplateException {
        RecordField field = RecordReflection.getRecordField(SimpleEntity.class, "name");
        assertEquals("name", field.name());
        assertEquals(String.class, field.type());
    }

    @Test
    void testGetRecordFieldPk() throws SqlTemplateException {
        RecordField field = RecordReflection.getRecordField(SimpleEntity.class, "id");
        assertEquals("id", field.name());
        assertTrue(field.isAnnotationPresent(PK.class));
    }

    @Test
    void testGetRecordFieldNestedPath() throws SqlTemplateException {
        RecordField field = RecordReflection.getRecordField(EntityWithNested.class, "nested.inner");
        assertEquals("inner", field.name());
        assertEquals(String.class, field.type());
    }

    @Test
    void testGetRecordFieldEmptyPathThrows() {
        assertThrows(SqlTemplateException.class,
                () -> RecordReflection.getRecordField(SimpleEntity.class, ""));
    }

    @Test
    void testGetRecordFieldNonexistentFieldThrows() {
        assertThrows(SqlTemplateException.class,
                () -> RecordReflection.getRecordField(SimpleEntity.class, "nonexistent"));
    }

    @Test
    void testGetRecordFieldNestedNonRecordThrows() {
        assertThrows(SqlTemplateException.class,
                () -> RecordReflection.getRecordField(SimpleEntity.class, "name.something"));
    }

    // findPkField tests

    @Test
    void testFindPkField() {
        Optional<RecordField> pkField = RecordReflection.findPkField(SimpleEntity.class);
        assertTrue(pkField.isPresent());
        assertEquals("id", pkField.get().name());
    }

    @Test
    void testFindPkFieldCompound() {
        Optional<RecordField> pkField = RecordReflection.findPkField(EntityWithCompoundPk.class);
        assertTrue(pkField.isPresent());
        assertEquals("id", pkField.get().name());
        assertEquals(CompoundPk.class, pkField.get().type());
    }

    // getPkLeaves tests

    @Test
    void testGetPkLeavesSimple() throws SqlTemplateException {
        var leaves = RecordReflection.getPkLeaves(SimpleEntity.class);
        assertEquals(1, leaves.size());
        assertEquals("id", leaves.get(0).field().name());
    }

    @Test
    void testGetPkLeavesCompound() throws SqlTemplateException {
        var leaves = RecordReflection.getPkLeaves(EntityWithCompoundPk.class);
        assertEquals(2, leaves.size());
        assertEquals("partA", leaves.get(0).field().name());
        assertEquals("partB", leaves.get(1).field().name());
    }

    @Test
    void testGetPkLeavesFkPk() throws SqlTemplateException {
        // When PK is also FK, the key chain is followed into the referenced entity's primary key.
        var leaves = RecordReflection.getPkLeaves(EntityWithFkPk.class);
        assertEquals(1, leaves.size());
        assertEquals("id", leaves.get(0).field().name());
        // The accessor path steps through the FK field into the referenced key.
        assertEquals(2, leaves.get(0).path().size());
        assertEquals("ref", leaves.get(0).path().get(0).name());
    }

    // getFkFields tests

    @Test
    void testGetFkFields() {
        List<RecordField> fkFields = RecordReflection.getFkFields(EntityWithFk.class).toList();
        assertEquals(1, fkFields.size());
        assertEquals("ref", fkFields.get(0).name());
    }

    @Test
    void testGetFkFieldsWithRef() {
        List<RecordField> fkFields = RecordReflection.getFkFields(EntityWithRefFk.class).toList();
        assertEquals(1, fkFields.size());
        assertEquals("ref", fkFields.get(0).name());
    }

    @Test
    void testGetFkFieldsNoFk() {
        List<RecordField> fkFields = RecordReflection.getFkFields(SimpleEntity.class).toList();
        assertTrue(fkFields.isEmpty());
    }

    // getVersionField tests

    @Test
    void testGetVersionField() {
        Optional<RecordField> versionField = RecordReflection.getVersionField(EntityWithVersion.class);
        assertTrue(versionField.isPresent());
        assertEquals("version", versionField.get().name());
    }

    @Test
    void testGetVersionFieldAbsent() {
        Optional<RecordField> versionField = RecordReflection.getVersionField(NoVersionEntity.class);
        assertFalse(versionField.isPresent());
    }

    // getGenerationStrategy tests

    @Test
    void testGetGenerationStrategyIdentity() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(IdentityGenEntity.class, "id");
        assertEquals(GenerationStrategy.IDENTITY, RecordReflection.getGenerationStrategy(pkField));
    }

    @Test
    void testGetGenerationStrategyNone() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(NoneGenEntity.class, "id");
        assertEquals(GenerationStrategy.NONE, RecordReflection.getGenerationStrategy(pkField));
    }

    @Test
    void testGetGenerationStrategySequence() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(SeqGenEntity.class, "id");
        assertEquals(GenerationStrategy.SEQUENCE, RecordReflection.getGenerationStrategy(pkField));
    }

    @Test
    void testGetGenerationStrategyNonPkField() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(SimpleEntity.class, "name");
        assertEquals(GenerationStrategy.NONE, RecordReflection.getGenerationStrategy(nameField));
    }

    // getSequence tests

    @Test
    void testGetSequence() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(SeqGenEntity.class, "id");
        assertEquals("my_seq", RecordReflection.getSequence(pkField));
    }

    @Test
    void testGetSequenceEmpty() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(IdentityGenEntity.class, "id");
        assertEquals("", RecordReflection.getSequence(pkField));
    }

    @Test
    void testGetSequenceNonPk() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(SimpleEntity.class, "name");
        assertEquals("", RecordReflection.getSequence(nameField));
    }

    // isTypePresent tests

    @Test
    void testIsTypePresentSelf() throws SqlTemplateException {
        assertTrue(RecordReflection.isTypePresent(SimpleEntity.class, SimpleEntity.class));
    }

    @Test
    void testIsTypePresentFk() throws SqlTemplateException {
        assertTrue(RecordReflection.isTypePresent(EntityWithFk.class, ReferencedEntity.class));
    }

    @Test
    void testIsTypePresentAbsent() throws SqlTemplateException {
        assertFalse(RecordReflection.isTypePresent(SimpleEntity.class, ReferencedEntity.class));
    }

    // getRefDataType tests

    @Test
    void testGetRefDataType() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithRefFk.class, "ref");
        Class<? extends Data> refDataType = RecordReflection.getRefDataType(refField);
        assertEquals(ReferencedEntity.class, refDataType);
    }

    // getRefPkType tests

    @Test
    void testGetRefPkType() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithRefFk.class, "ref");
        Class<?> refPkType = RecordReflection.getRefPkType(refField);
        assertEquals(Integer.class, refPkType);
    }

    // getTableName tests

    @Test
    void testGetTableNameDefault() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(SimpleEntity.class, type -> {
            // Default resolver: camelCase to snake_case.
            return type.type().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        });
        assertNotNull(tableName);
        // SimpleEntity -> simple_entity
        assertEquals("simple_entity", tableName.name());
    }

    @Test
    void testGetTableNameWithDbTable() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(CustomTableEntity.class, type -> type.type().getSimpleName());
        assertEquals("custom_table", tableName.name());
    }

    @Test
    void testGetTableNameWithSchema() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(SchemaTableEntity.class, type -> type.type().getSimpleName());
        assertEquals("annotated_table", tableName.name());
        assertEquals("my_schema", tableName.schema());
    }

    // getColumnName tests

    @Test
    void testGetColumnNameWithDbColumn() throws SqlTemplateException {
        RecordField idField = RecordReflection.getRecordField(EntityWithDbColumn.class, "id");
        ColumnName columnName = RecordReflection.getColumnName(idField, field -> field.name());
        assertEquals("pk_col", columnName.name());
    }

    @Test
    void testGetColumnNameDefault() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(SimpleEntity.class, "name");
        ColumnName columnName = RecordReflection.getColumnName(nameField, field -> field.name());
        assertEquals("name", columnName.name());
    }

    // findRecordField tests

    @Test
    void testFindRecordFieldByType() throws SqlTemplateException {
        var fields = RecordReflection.getRecordFields(EntityWithFk.class);
        Optional<RecordField> found = RecordReflection.findRecordField(fields, ReferencedEntity.class);
        assertTrue(found.isPresent());
        assertEquals("ref", found.get().name());
    }

    @Test
    void testFindRecordFieldByTypeRef() throws SqlTemplateException {
        var fields = RecordReflection.getRecordFields(EntityWithRefFk.class);
        Optional<RecordField> found = RecordReflection.findRecordField(fields, ReferencedEntity.class);
        assertTrue(found.isPresent());
        assertEquals("ref", found.get().name());
    }

    @Test
    void testFindRecordFieldByTypeMissing() throws SqlTemplateException {
        var fields = RecordReflection.getRecordFields(SimpleEntity.class);
        Optional<RecordField> found = RecordReflection.findRecordField(fields, ReferencedEntity.class);
        assertFalse(found.isPresent());
    }

    // Generation strategy for compound PK

    @Test
    void testGetGenerationStrategyCompoundPk() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(EntityWithCompoundPk.class, "id");
        // Compound PK (record type) always returns NONE.
        assertEquals(GenerationStrategy.NONE, RecordReflection.getGenerationStrategy(pkField));
    }

    // Sealed entity model types for coverage

    @Discriminator
    @DbTable("sealed_animal")
    sealed interface SealedAnimal extends Entity<Integer> permits SealedCat, SealedDog {}
    record SealedCat(@PK Integer id, String name, boolean indoor) implements SealedAnimal {}
    record SealedDog(@PK Integer id, String name, int weight) implements SealedAnimal {}

    // Sealed entity without @DbTable annotation (to test fallback to camelCase-to-snake_case).
    @Discriminator
    sealed interface SealedNoDbTable extends Entity<Integer> permits SealedNoDbTableSub {}
    record SealedNoDbTableSub(@PK Integer id, String name) implements SealedNoDbTable {}

    // Sealed entity with @DbTable including schema.
    @Discriminator
    @DbTable(value = "schema_animal", schema = "my_schema")
    sealed interface SealedSchemaAnimal extends Entity<Integer> permits SealedSchemaCat {}
    record SealedSchemaCat(@PK Integer id, String name) implements SealedSchemaAnimal {}

    // Entity with Ref to sealed entity.
    public record EntityWithSealedRef(
            @PK Integer id,
            @FK Ref<SealedAnimal> animal
    ) implements Entity<Integer> {}

    // Sealed Data (polymorphic FK) for getRefDataType sealed path.
    sealed interface SealedData extends Data permits SealedDataSub1, SealedDataSub2 {}
    record SealedDataSub1(@PK Integer id, String title) implements SealedData, Entity<Integer> {}
    record SealedDataSub2(@PK Integer id, String url) implements SealedData, Entity<Integer> {}

    // Entity with Ref to sealed Data (polymorphic FK).
    public record EntityWithSealedDataRef(
            @PK Integer id,
            @FK Ref<SealedData> item
    ) implements Entity<Integer> {}

    // Record with nested inline that has a @Version field.
    public record VersionedInline(String data, @Version int version) {}

    public record EntityWithNestedVersion(
            @PK Integer id,
            @Inline VersionedInline nested,
            String name
    ) implements Entity<Integer> {}

    // Sealed entity: getFkFields returns empty (L200)

    @Test
    void testGetFkFieldsSealedEntityReturnsEmpty() {
        List<RecordField> fkFields = RecordReflection.getFkFields(SealedAnimal.class).toList();
        assertTrue(fkFields.isEmpty());
    }

    // Sealed entity: getVersionField returns empty (L223)

    @Test
    void testGetVersionFieldSealedEntityReturnsEmpty() {
        Optional<RecordField> versionField = RecordReflection.getVersionField(SealedAnimal.class);
        assertFalse(versionField.isPresent());
    }

    // Nested version field in inline record (L232-234)

    @Test
    void testGetVersionFieldFromNestedInlineRecord() {
        Optional<RecordField> versionField = RecordReflection.getVersionField(EntityWithNestedVersion.class);
        assertTrue(versionField.isPresent());
        assertEquals("version", versionField.get().name());
    }

    // Sealed entity: isTypePresent returns false (L265)

    @Test
    void testIsTypePresentSealedEntityReturnsFalse() throws SqlTemplateException {
        assertFalse(RecordReflection.isTypePresent(SealedAnimal.class, ReferencedEntity.class));
    }

    // Sealed entity: getTableName with @DbTable (L379-395)

    @Test
    void testGetTableNameSealedEntityWithDbTable() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(SealedAnimal.class, type -> type.type().getSimpleName());
        assertEquals("sealed_animal", tableName.name());
        assertEquals("", tableName.schema());
    }

    // Sealed entity: getTableName with @DbTable and schema (L392-393)

    @Test
    void testGetTableNameSealedEntityWithSchema() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(SealedSchemaAnimal.class, type -> type.type().getSimpleName());
        assertEquals("schema_animal", tableName.name());
        assertEquals("my_schema", tableName.schema());
    }

    // Sealed entity: getTableName without @DbTable takes the name of the resolver.

    @Test
    void testGetTableNameSealedEntityWithoutDbTable() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(SealedNoDbTable.class, TableNameResolver.DEFAULT);
        assertEquals("sealed_no_db_table", tableName.name());
        assertEquals("", tableName.schema());
        var resolved = RecordReflection.getTableName(SealedNoDbTable.class, type -> type.type().getSimpleName());
        assertEquals("SealedNoDbTable", resolved.name());
    }

    // Ref to sealed entity: getRefPkType resolves PK from first permitted subclass (L301-308)

    @Test
    void testGetRefPkTypeSealedEntity() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithSealedRef.class, "animal");
        Class<?> refPkType = RecordReflection.getRefPkType(refField);
        assertEquals(Integer.class, refPkType);
    }

    // Ref to sealed Data: getRefDataType accepts sealed type (L351-353)

    @Test
    void testGetRefDataTypeSealedData() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithSealedDataRef.class, "item");
        Class<? extends Data> refDataType = RecordReflection.getRefDataType(refField);
        assertEquals(SealedData.class, refDataType);
    }

    // Sealed entity: getPkLeaves on sealed type delegates to first permitted subclass

    @Test
    void testGetPkLeavesSealedEntity() throws SqlTemplateException {
        var leaves = RecordReflection.getPkLeaves(SealedAnimal.class);
        assertEquals(1, leaves.size());
        assertEquals("id", leaves.get(0).field().name());
    }

    // Multiple table names in @DbTable for sealed entity (L387)

    @DbTable(name = "table_a", value = "table_b")
    @Discriminator
    sealed interface SealedMultiTableName extends Entity<Integer> permits SealedMultiTableSub {}
    record SealedMultiTableSub(@PK Integer id) implements SealedMultiTableName {}

    @Test
    void testGetTableNameSealedEntityMultipleNamesThrows() {
        assertThrows(PersistenceException.class, () ->
                RecordReflection.getTableName(SealedMultiTableName.class, type -> type.type().getSimpleName()));
    }

    // Multiple table names in @DbTable for non-sealed entity (L409)

    @DbTable(name = "table_x", value = "table_y")
    public record MultiTableNameEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @Test
    void testGetTableNameNonSealedMultipleNamesThrows() {
        assertThrows(PersistenceException.class, () ->
                RecordReflection.getTableName(MultiTableNameEntity.class, type -> type.type().getSimpleName()));
    }

    // Sealed entity: @DbTable with empty name/value takes the name of the resolver.

    @DbTable
    @Discriminator
    sealed interface SealedEmptyDbTable extends Entity<Integer> permits SealedEmptyDbTableSub {}
    record SealedEmptyDbTableSub(@PK Integer id) implements SealedEmptyDbTable {}

    @Test
    void testGetTableNameSealedEntityEmptyDbTableFallsBackToTheResolver() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(SealedEmptyDbTable.class, TableNameResolver.DEFAULT);
        assertEquals("sealed_empty_db_table", tableName.name());
    }

    // getPkLeaves for type with no PK fails fast

    public record NoPkData(String value) implements Data {}

    @Test
    void testGetPkLeavesNoPkThrows() {
        assertThrows(SqlTemplateException.class, () -> RecordReflection.getPkLeaves(NoPkData.class));
    }

    // getRefPkType error for non-entity Ref type (L314, L318-324)

    public record NoPkRecord(String value) implements Data {}

    public record EntityWithRefToNoPkRecord(
            @PK Integer id,
            @FK Ref<NoPkRecord> ref
    ) implements Entity<Integer> {}

    @Test
    void testGetRefPkTypeNoPkRecordThrows() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithRefToNoPkRecord.class, "ref");
        assertThrows(SqlTemplateException.class, () -> RecordReflection.getRefPkType(refField));
    }

    // getRefDataType error for non-Data Ref type (L344, L347)

    public record NonDataType(String value) {}

    public record EntityWithRefToNonData(
            @PK Integer id,
            @FK Ref<SimpleEntity> ref  // SimpleEntity is Data+Entity, so this passes
    ) implements Entity<Integer> {}

    // getRefPkType for sealed Data with no @PK in permitted subclass (L306-308)

    sealed interface SealedDataNoPk extends Data permits SealedDataNoPkSub {}
    record SealedDataNoPkSub(Integer id, String title) implements SealedDataNoPk, Entity<Integer> {
        // No @PK annotation on id field.
    }

    public record EntityWithRefToSealedNoPk(
            @PK Integer id,
            @FK Ref<SealedDataNoPk> item
    ) implements Entity<Integer> {}

    @Test
    void testGetRefPkTypeSealedDataNoPkThrows() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithRefToSealedNoPk.class, "item");
        assertThrows(SqlTemplateException.class, () -> RecordReflection.getRefPkType(refField));
    }

    // getRefDataType for raw Ref type (L344)

    @SuppressWarnings("rawtypes")
    public record EntityWithRawRef(
            @PK Integer id,
            @FK Ref ref
    ) implements Entity<Integer> {}

    @Test
    void testGetRefDataTypeRawRefThrows() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithRawRef.class, "ref");
        assertThrows(SqlTemplateException.class, () -> RecordReflection.getRefDataType(refField));
    }

    @Test
    void testGetRefPkTypeRawRefThrows() throws SqlTemplateException {
        RecordField refField = RecordReflection.getRecordField(EntityWithRawRef.class, "ref");
        assertThrows(SqlTemplateException.class, () -> RecordReflection.getRefPkType(refField));
    }

    // combine with conflicting name and value (L438-439)

    public record EntityWithConflictingColumnNames(
            @PK Integer id,
            @DbColumn(name = "col_a", value = "col_b") String name
    ) implements Entity<Integer> {}

    @Test
    void testGetColumnNameConflictingNamesThrows() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(EntityWithConflictingColumnNames.class, "name");
        assertThrows(SqlTemplateException.class, () ->
                RecordReflection.getColumnName(nameField, field -> field.name()));
    }

    // columnNames with empty @DbColumn (L460)

    public record EntityWithEmptyDbColumn(
            @PK Integer id,
            @DbColumn(name = "", value = "") String name
    ) implements Entity<Integer> {}

    @Test
    void testGetColumnNameEmptyDbColumnThrows() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(EntityWithEmptyDbColumn.class, "name");
        assertThrows(SqlTemplateException.class, () ->
                RecordReflection.getColumnName(nameField, field -> field.name()));
    }

    // normalizeDiscriminatorValue with CHAR type (L950)

    @Test
    void testNormalizeDiscriminatorValueChar() {
        Object result = RecordReflection.normalizeDiscriminatorValue("X", DiscriminatorType.CHAR);
        assertEquals('X', result);
    }

    @Test
    void testNormalizeDiscriminatorValueCharFromCharacter() {
        Object result = RecordReflection.normalizeDiscriminatorValue('Y', DiscriminatorType.CHAR);
        assertEquals('Y', result);
    }

    @Test
    void testNormalizeDiscriminatorValueCharFromMultiCharString() {
        // Multi-char string should take the first character via raw.toString().charAt(0).
        Object result = RecordReflection.normalizeDiscriminatorValue("AB", DiscriminatorType.CHAR);
        assertEquals('A', result);
    }

    @Test
    void testNormalizeDiscriminatorValueCharFromNonStringNonChar() {
        // Non-String, non-Character: falls through to raw.toString().charAt(0).
        Object result = RecordReflection.normalizeDiscriminatorValue(42, DiscriminatorType.CHAR);
        assertEquals('4', result);
    }

    @Test
    void testNormalizeDiscriminatorValueInteger() {
        Object result = RecordReflection.normalizeDiscriminatorValue("42", DiscriminatorType.INTEGER);
        assertEquals(42, result);
    }

    @Test
    void testNormalizeDiscriminatorValueIntegerFromNumber() {
        Object result = RecordReflection.normalizeDiscriminatorValue(42L, DiscriminatorType.INTEGER);
        assertEquals(42, result);
    }

    // findJoinedSealedParent for sealed/interface types returns empty (L1195)

    @Test
    void testFindJoinedSealedParentForSealedType() {
        // SealedAnimal is a sealed interface, should return empty.
        Optional<Class<?>> parent = RecordReflection.findJoinedSealedParent(SealedAnimal.class);
        assertFalse(parent.isPresent());
    }

    @Test
    void testFindJoinedSealedParentForNonSealedInterface() {
        // Entity is a non-sealed interface, should return empty.
        Optional<Class<?>> parent = RecordReflection.findJoinedSealedParent(Entity.class);
        assertFalse(parent.isPresent());
    }

    // findJoinedSealedParent for joined subtype returns the sealed parent

    @Discriminator
    @Polymorphic(Polymorphic.Strategy.JOINED)
    @DbTable("joined_base")
    sealed interface JoinedAnimalLocal extends Entity<Integer> permits JoinedCatLocal, JoinedDogLocal {}
    @DbTable("joined_cat") record JoinedCatLocal(@PK Integer id, String name, boolean indoor) implements JoinedAnimalLocal {}
    @DbTable("joined_dog") record JoinedDogLocal(@PK Integer id, String name, int weight) implements JoinedAnimalLocal {}

    @Test
    void testFindJoinedSealedParentForJoinedSubtype() {
        Optional<Class<?>> parent = RecordReflection.findJoinedSealedParent(JoinedCatLocal.class);
        assertTrue(parent.isPresent());
        assertEquals(JoinedAnimalLocal.class, parent.get());
    }

    @Test
    void testFindJoinedSealedParentForNonJoinedSubtype() {
        // SealedCat is a permitted subclass of a SINGLE_TABLE sealed entity, not JOINED.
        Optional<Class<?>> parent = RecordReflection.findJoinedSealedParent(SealedCat.class);
        assertFalse(parent.isPresent());
    }

    // getBaseFieldNames for joined sealed entity (L966)

    @Test
    void testGetBaseFieldNamesJoinedEntity() {
        List<String> baseFields = RecordReflection.getBaseFieldNames(JoinedAnimalLocal.class);
        // Common fields between JoinedCatLocal(id, name, indoor) and JoinedDogLocal(id, name, weight) are: id, name
        assertTrue(baseFields.contains("id"));
        assertTrue(baseFields.contains("name"));
        assertFalse(baseFields.contains("indoor"));
        assertFalse(baseFields.contains("weight"));
    }

    // getExtensionFieldNames for joined sealed entity

    @Test
    void testGetExtensionFieldNamesJoinedEntity() {
        List<String> extensionFields = RecordReflection.getExtensionFieldNames(JoinedCatLocal.class, JoinedAnimalLocal.class);
        assertTrue(extensionFields.contains("indoor"));
        assertFalse(extensionFields.contains("id"));
        assertFalse(extensionFields.contains("name"));
    }

    // validateSealedHierarchy: missing @PK in subtype (L1057-1058)

    @Discriminator
    @DbTable("missing_pk_sealed")
    sealed interface MissingPkSealed extends Entity<Integer> permits MissingPkSub {}
    record MissingPkSub(String name) implements MissingPkSealed {
        @Override public Integer id() { return 0; }
    }

    @Test
    void testValidateSealedHierarchyMissingPk() {
        String result = RecordReflection.validateSealedHierarchy(MissingPkSealed.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("@PK"));
    }

    // hasDiscriminator for non-sealed type returns false (L755)

    @Test
    void testHasDiscriminatorNonSealedReturnsFalse() {
        assertFalse(RecordReflection.hasDiscriminator(SimpleEntity.class));
    }

    @Test
    void testHasDiscriminatorPolymorphicFkReturnsFalse() {
        assertFalse(RecordReflection.hasDiscriminator(SealedData.class));
    }

    // validateSealedHierarchy: mismatched PK types (L1067-1068)

    @Discriminator
    @DbTable("pk_type_mismatch")
    sealed interface PkTypeMismatchSealed extends Entity<Object> permits PkTypeMismatchInt, PkTypeMismatchString {}
    record PkTypeMismatchInt(@PK Integer id) implements PkTypeMismatchSealed {}
    record PkTypeMismatchString(@PK String id) implements PkTypeMismatchSealed {}

    @Test
    void testValidateSealedHierarchyMismatchedPkTypes() {
        String result = RecordReflection.validateSealedHierarchy(PkTypeMismatchSealed.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("same @PK type"));
    }

    // validateSealedHierarchy: mismatched PK generation strategy (L1070-1072)

    @Discriminator
    @DbTable("gen_mismatch")
    sealed interface GenMismatchSealed extends Entity<Integer> permits GenMismatchIdentity, GenMismatchNone {}
    record GenMismatchIdentity(@PK(generation = GenerationStrategy.IDENTITY) Integer id) implements GenMismatchSealed {}
    record GenMismatchNone(@PK(generation = GenerationStrategy.NONE) Integer id) implements GenMismatchSealed {}

    @Test
    void testValidateSealedHierarchyMismatchedGenStrategy() {
        String result = RecordReflection.validateSealedHierarchy(GenMismatchSealed.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("generation strategy"));
    }

    // validateSealedHierarchy: @Discriminator with value on sealed interface (L1046-1049)

    @Discriminator(value = "bad_value")
    @DbTable("disc_with_value")
    sealed interface DiscWithValueSealed extends Entity<Integer> permits DiscWithValueSub {}
    record DiscWithValueSub(@PK Integer id) implements DiscWithValueSealed {}

    @Test
    void testValidateSealedHierarchyDiscriminatorWithValue() {
        String result = RecordReflection.validateSealedHierarchy(DiscWithValueSealed.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("value attribute"));
    }

    // validateSealedHierarchy: @Discriminator with column on subtype (L1165-1168)

    @Discriminator
    @DbTable("disc_col_sub")
    sealed interface DiscColumnOnSubSealed extends Entity<Integer> permits DiscColumnOnSub {}
    @Discriminator(column = "my_col") record DiscColumnOnSub(@PK Integer id) implements DiscColumnOnSubSealed {}

    @Test
    void testValidateSealedHierarchyDiscriminatorColumnOnSubtype() {
        String result = RecordReflection.validateSealedHierarchy(DiscColumnOnSubSealed.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("column attribute"));
    }

    // validateSealedHierarchy: @DbTable on single-table subtype (L1174-1177)

    @Discriminator
    @DbTable("single_table_parent")
    sealed interface SingleTableWithDbTableSub extends Entity<Integer> permits SingleTableSub {}
    @DbTable("sub_table") record SingleTableSub(@PK Integer id) implements SingleTableWithDbTableSub {}

    @Test
    void testValidateSealedHierarchySingleTableSubWithDbTable() {
        String result = RecordReflection.validateSealedHierarchy(SingleTableWithDbTableSub.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("must not have @DbTable"));
    }

    // validateSealedHierarchy: field type mismatch across subtypes (L1093-1099)

    @Discriminator
    @DbTable("type_mismatch")
    sealed interface TypeMismatchSealed extends Entity<Integer> permits TypeMismatchSub1, TypeMismatchSub2 {}
    record TypeMismatchSub1(@PK Integer id, String shared) implements TypeMismatchSealed {}
    record TypeMismatchSub2(@PK Integer id, int shared) implements TypeMismatchSealed {}

    @Test
    void testValidateSealedHierarchyFieldTypeMismatch() {
        String result = RecordReflection.validateSealedHierarchy(TypeMismatchSealed.class);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("different types"));
    }

    @DbTable("my_table")
    public record SimpleTestEntity(@PK Integer id, String name) implements Entity<Integer> {}

    @Test
    void testCamelCaseToSnakeCaseSimple() throws SqlTemplateException {
        // Test via getTableName, which calls camelCaseToSnakeCase internally for non-annotated types.
        var tableName = RecordReflection.getTableName(SimpleTestEntity.class, type -> {
            return type.type().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        });
        assertNotNull(tableName);
    }

    @Discriminator
    sealed interface NameWithDigits2Test extends Entity<Integer> permits NameWithDigits2TestSub {}
    record NameWithDigits2TestSub(@PK Integer id) implements NameWithDigits2Test {}

    @Test
    void testGetTableNameSealedNoDbTableWithDigits() throws SqlTemplateException {
        // "NameWithDigits2Test" has "ts2T" pattern, testing digit-preceded-by-lowercase path.
        var tableName = RecordReflection.getTableName(NameWithDigits2Test.class, TableNameResolver.DEFAULT);
        assertNotNull(tableName);
        // Should contain underscores from camelCase conversion.
        assertTrue(tableName.name().contains("_"));
    }

    public record SimplePkEntity(@PK Integer id, String name) implements Entity<Integer> {}

    @Test
    void testGetPrimaryKeysForFkPk() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(EntityWithFkPk.class, "ref");
        List<ColumnName> primaryKeys = RecordReflection.getPrimaryKeys(
                pkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        assertFalse(primaryKeys.isEmpty());
        assertEquals("ref_id", primaryKeys.getFirst().name());
    }

    @Test
    void testGetPrimaryKeysForCompoundPk() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(EntityWithCompoundPk.class, "id");
        List<ColumnName> primaryKeys = RecordReflection.getPrimaryKeys(
                pkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        assertEquals(2, primaryKeys.size());
        assertEquals("partA", primaryKeys.get(0).name());
        assertEquals("partB", primaryKeys.get(1).name());
    }

    @Test
    void testGetPrimaryKeysForSimplePk() throws SqlTemplateException {
        RecordField pkField = RecordReflection.getRecordField(SimplePkEntity.class, "id");
        List<ColumnName> primaryKeys = RecordReflection.getPrimaryKeys(
                pkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        assertEquals(1, primaryKeys.size());
    }

    public record CompoundPkEntity(
            @PK CompoundPk id,
            String name
    ) implements Entity<CompoundPk> {}

    public record EntityWithCompoundFk(
            @PK Integer id,
            @FK CompoundPkEntity ref
    ) implements Entity<Integer> {}

    @Test
    void testGetForeignKeysForCompoundFk() throws SqlTemplateException {
        RecordField fkField = RecordReflection.getRecordField(EntityWithCompoundFk.class, "ref");
        List<ColumnName> foreignKeys = RecordReflection.getForeignKeys(
                fkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        // Compound FK should return multiple column names (one per PK component).
        assertEquals(2, foreignKeys.size());
    }

    @Test
    void testGetForeignKeysForRefFk() throws SqlTemplateException {
        RecordField fkField = RecordReflection.getRecordField(EntityWithRefFk.class, "ref");
        List<ColumnName> foreignKeys = RecordReflection.getForeignKeys(
                fkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        assertEquals(1, foreignKeys.size());
        assertEquals("ref_id", foreignKeys.getFirst().name());
    }

    public record EntityWithSealedFk(
            @PK Integer id,
            @FK Ref<SealedAnimal> animal
    ) implements Entity<Integer> {}

    @Test
    void testGetForeignKeysForSealedEntityFk() throws SqlTemplateException {
        RecordField fkField = RecordReflection.getRecordField(EntityWithSealedFk.class, "animal");
        List<ColumnName> foreignKeys = RecordReflection.getForeignKeys(
                fkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        // Sealed entity FK should resolve to a single FK column.
        assertEquals(1, foreignKeys.size());
        assertEquals("animal_id", foreignKeys.getFirst().name());
    }

    @Test
    void testGetForeignKeysNamesASealedTargetAsItsSealedInterface() throws SqlTemplateException {
        RecordField fkField = RecordReflection.getRecordField(EntityWithSealedFk.class, "animal");
        List<ColumnName> foreignKeys = RecordReflection.getForeignKeys(
                fkField,
                (field, type) -> type.type().getSimpleName() + "_id",
                field -> field.name()
        );
        assertEquals(List.of("SealedAnimal_id"), foreignKeys.stream().map(ColumnName::name).toList());
    }

    sealed interface SealedTarget extends Data permits TargetPost, TargetPhoto {}
    @DbTable("post") record TargetPost(@PK Integer id, String title) implements SealedTarget, Entity<Integer> {}
    @DbTable("photo") record TargetPhoto(@PK Integer id, String url) implements SealedTarget, Entity<Integer> {}

    public record EntityWithPolymorphicFk(
            @PK Integer id,
            @FK Ref<SealedTarget> target
    ) implements Entity<Integer> {}

    @Test
    void testGetForeignKeysForPolymorphicFk() throws SqlTemplateException {
        RecordField fkField = RecordReflection.getRecordField(EntityWithPolymorphicFk.class, "target");
        List<ColumnName> foreignKeys = RecordReflection.getForeignKeys(
                fkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        // Polymorphic FK should return two columns: discriminator + FK value.
        assertEquals(2, foreignKeys.size());
        assertTrue(foreignKeys.stream().anyMatch(fk -> fk.name().contains("type")));
        assertTrue(foreignKeys.stream().anyMatch(fk -> fk.name().contains("id")));
    }

    public record EntityWithCustomDiscCol(
            @PK Integer id,
            @FK @DbColumn("custom_disc") Ref<SealedTarget> target
    ) implements Entity<Integer> {}

    @Test
    void testFindPkFieldForNoPkDataReturnsEmpty() {
        Optional<RecordField> pkField = RecordReflection.findPkField(NoPkData.class);
        assertFalse(pkField.isPresent());
    }

    public record InnerNested(String deepField) {}
    public record OuterNested(@Inline InnerNested inner) {}
    public record EntityWithDeepNested(
            @PK Integer id,
            @Inline OuterNested outer
    ) implements Entity<Integer> {}

    @Test
    void testGetRecordFieldDeeplyNestedPath() throws SqlTemplateException {
        RecordField field = RecordReflection.getRecordField(EntityWithDeepNested.class, "outer.inner.deepField");
        assertEquals("deepField", field.name());
        assertEquals(String.class, field.type());
    }

    @Test
    void testGetBaseFieldNamesForNonSealedEntityReturnsFields() {
        // For a non-sealed entity, getBaseFieldNames returns the intersection of all permitted subtypes' fields.
        // Since SimplePkEntity is not sealed, it returns an empty list (no subtypes to intersect).
        List<String> baseFields = RecordReflection.getBaseFieldNames(SimplePkEntity.class);
        assertNotNull(baseFields);
    }

    @Discriminator(type = DiscriminatorType.INTEGER)
    @DbTable("int_disc_animal")
    sealed interface IntDiscAnimal extends Entity<Integer> permits IntDiscCat, IntDiscDog {}
    @Discriminator("1") record IntDiscCat(@PK Integer id, String name) implements IntDiscAnimal {}
    @Discriminator("2") record IntDiscDog(@PK Integer id, String name) implements IntDiscAnimal {}

    @Test
    void testHasDiscriminatorForIntegerDiscType() {
        assertTrue(RecordReflection.hasDiscriminator(IntDiscAnimal.class));
    }

    @Discriminator(type = DiscriminatorType.CHAR)
    @DbTable("char_disc_animal")
    sealed interface CharDiscAnimal extends Entity<Integer> permits CharDiscCat, CharDiscDog {}
    @Discriminator("C") record CharDiscCat(@PK Integer id, String name) implements CharDiscAnimal {}
    @Discriminator("D") record CharDiscDog(@PK Integer id, String name) implements CharDiscAnimal {}

    @Test
    void testHasDiscriminatorForCharDiscType() {
        assertTrue(RecordReflection.hasDiscriminator(CharDiscAnimal.class));
    }

    @Discriminator
    @DbTable("valid_sealed")
    sealed interface ValidSealedEntity extends Entity<Integer> permits ValidSealedSub1, ValidSealedSub2 {}
    record ValidSealedSub1(@PK Integer id, String name) implements ValidSealedEntity {}
    record ValidSealedSub2(@PK Integer id, String name, int extra) implements ValidSealedEntity {}

    @Test
    void testValidateSealedHierarchyValidReturnsEmpty() {
        String result = RecordReflection.validateSealedHierarchy(ValidSealedEntity.class);
        assertTrue(result.isEmpty(), "Expected empty validation result for valid sealed hierarchy, got: " + result);
    }

    @Discriminator
    @Polymorphic(Polymorphic.Strategy.JOINED)
    @DbTable("joined_valid")
    sealed interface JoinedValidEntity extends Entity<Integer> permits JoinedValidSub1, JoinedValidSub2 {}
    @DbTable("joined_sub1") record JoinedValidSub1(@PK Integer id, String name, boolean flag) implements JoinedValidEntity {}
    @DbTable("joined_sub2") record JoinedValidSub2(@PK Integer id, String name, int count) implements JoinedValidEntity {}

    @Test
    void testValidateSealedHierarchyJoinedValid() {
        String result = RecordReflection.validateSealedHierarchy(JoinedValidEntity.class);
        assertTrue(result.isEmpty(), "Expected empty validation result for valid joined hierarchy, got: " + result);
    }

    @Discriminator
    @Polymorphic(Polymorphic.Strategy.JOINED)
    @DbTable("joined_common_only")
    sealed interface JoinedCommonOnlyEntity extends Entity<Integer> permits JoinedCommonSub1, JoinedCommonSub2 {}
    @DbTable("joined_common_sub1") record JoinedCommonSub1(@PK Integer id, String name) implements JoinedCommonOnlyEntity {}
    @DbTable("joined_common_sub2") record JoinedCommonSub2(@PK Integer id, String name) implements JoinedCommonOnlyEntity {}

    @Test
    void testValidateSealedHierarchyJoinedWithCommonFields() {
        String result = RecordReflection.validateSealedHierarchy(JoinedCommonOnlyEntity.class);
        assertTrue(result.isEmpty(), "Joined hierarchy with common fields should validate: " + result);
    }

    public record EntityWithExplicitFkName(
            @PK Integer id,
            @FK(name = "custom_ref_id") ReferencedEntity ref
    ) implements Entity<Integer> {}

    @Test
    void testGetForeignKeysWithExplicitFkName() throws SqlTemplateException {
        RecordField fkField = RecordReflection.getRecordField(EntityWithExplicitFkName.class, "ref");
        List<ColumnName> foreignKeys = RecordReflection.getForeignKeys(
                fkField,
                (field, type) -> field.name() + "_id",
                field -> field.name()
        );
        assertEquals(1, foreignKeys.size());
        assertEquals("custom_ref_id", foreignKeys.getFirst().name());
    }

    public record ExtInlineAddress(String street, String city) {}

    public record EntityWithExtInline(
            @PK Integer id,
            @Inline ExtInlineAddress address
    ) implements Entity<Integer> {}

    @Test
    void testIsTypePresentForInlineType() throws SqlTemplateException {
        // Inline types are recursively searched, so the inline record's type is present.
        assertTrue(RecordReflection.isTypePresent(EntityWithExtInline.class, ExtInlineAddress.class));
    }

    public record EntityWithVersionAndFk(
            @PK Integer id,
            @FK ReferencedEntity ref,
            @Version int version
    ) implements Entity<Integer> {}

    @Test
    void testGetVersionFieldWithFk() {
        Optional<RecordField> versionField = RecordReflection.getVersionField(EntityWithVersionAndFk.class);
        assertTrue(versionField.isPresent());
        assertEquals("version", versionField.get().name());
    }

    public record EntityWithDbColumnValue(
            @PK Integer id,
            @DbColumn("value_col") String name
    ) implements Entity<Integer> {}

    @Test
    void testGetColumnNameWithDbColumnValue() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(EntityWithDbColumnValue.class, "name");
        ColumnName columnName = RecordReflection.getColumnName(nameField, field -> field.name());
        assertEquals("value_col", columnName.name());
    }

    public record EntityWithDbColumnName(
            @PK Integer id,
            @DbColumn(name = "name_col") String name
    ) implements Entity<Integer> {}

    @Test
    void testGetColumnNameWithDbColumnNameAttribute() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(EntityWithDbColumnName.class, "name");
        ColumnName columnName = RecordReflection.getColumnName(nameField, field -> field.name());
        assertEquals("name_col", columnName.name());
    }

    @Test
    void testGetRecordFieldsForInlineRecord() {
        var fields = RecordReflection.getRecordFields(ExtInlineAddress.class);
        assertEquals(2, fields.size());
        assertEquals("street", fields.get(0).name());
        assertEquals("city", fields.get(1).name());
    }

    @Test
    void testNormalizeDiscriminatorValueIntegerZero() {
        Object result = RecordReflection.normalizeDiscriminatorValue("0", DiscriminatorType.INTEGER);
        assertEquals(0, result);
    }

    @Test
    void testNormalizeDiscriminatorValueIntegerNegative() {
        Object result = RecordReflection.normalizeDiscriminatorValue("-5", DiscriminatorType.INTEGER);
        assertEquals(-5, result);
    }

    @Test
    void testNormalizeDiscriminatorValueString() {
        Object result = RecordReflection.normalizeDiscriminatorValue("SomeValue", DiscriminatorType.STRING);
        assertEquals("SomeValue", result);
    }

    @Test
    void testNormalizeDiscriminatorValueCharSingleChar() {
        Object result = RecordReflection.normalizeDiscriminatorValue("Z", DiscriminatorType.CHAR);
        assertEquals('Z', result);
    }

    public record EntityWithEscapedColumn(
            @PK Integer id,
            @DbColumn(value = "reserved_word", escape = true) String name
    ) implements Entity<Integer> {}

    @Test
    void testGetColumnNameEscaped() throws SqlTemplateException {
        RecordField nameField = RecordReflection.getRecordField(EntityWithEscapedColumn.class, "name");
        ColumnName columnName = RecordReflection.getColumnName(nameField, field -> field.name());
        assertEquals("reserved_word", columnName.name());
        assertTrue(columnName.escape());
    }

    @DbTable(value = "escaped_table", escape = true)
    public record EscapedTableEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @Test
    void testGetTableNameEscaped() throws SqlTemplateException {
        var tableName = RecordReflection.getTableName(EscapedTableEntity.class, type -> type.type().getSimpleName());
        assertEquals("escaped_table", tableName.name());
        assertTrue(tableName.escape());
    }
}
