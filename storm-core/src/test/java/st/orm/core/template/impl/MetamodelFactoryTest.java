package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Inline;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.QueryColumn;
import st.orm.Ref;

/**
 * Tests for {@link MetamodelFactory} covering root model creation, path-based model resolution,
 * capitalize utility, and flatten behavior.
 */
class MetamodelFactoryTest {

    // Test model types

    public record SimpleEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record InlineAddress(String street, String zipCode) {}

    @QueryColumn(field = "nameFolded", type = String.class)
    public record EntityWithQueryColumn(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    public record EntityWithInline(
            @PK Integer id,
            @Inline InlineAddress address
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
            @FK Ref<ReferencedEntity> ref
    ) implements Entity<Integer> {}

    // capitalize tests

    @Test
    void testCapitalizeNormal() {
        assertEquals("Name", MetamodelFactory.capitalize("name"));
    }

    @Test
    void testCapitalizeAlreadyCapitalized() {
        assertEquals("Name", MetamodelFactory.capitalize("Name"));
    }

    @Test
    void testCapitalizeSingleChar() {
        assertEquals("A", MetamodelFactory.capitalize("a"));
    }

    @Test
    void testCapitalizeEmpty() {
        assertEquals("", MetamodelFactory.capitalize(""));
    }

    @Test
    void testCapitalizeNull() {
        assertEquals(null, MetamodelFactory.capitalize(null));
    }

    // root() tests

    @Test
    void testRootReturnsMetamodel() {
        Metamodel<SimpleEntity, SimpleEntity> root = MetamodelFactory.root(SimpleEntity.class);
        assertNotNull(root);
        assertEquals(SimpleEntity.class, root.fieldType());
    }

    @Test
    void testRootCachesSameInstance() {
        Metamodel<SimpleEntity, SimpleEntity> root1 = MetamodelFactory.root(SimpleEntity.class);
        Metamodel<SimpleEntity, SimpleEntity> root2 = MetamodelFactory.root(SimpleEntity.class);
        assertSame(root1, root2);
    }

    @Test
    void testRootGetValueReturnsSameRecord() {
        Metamodel<SimpleEntity, SimpleEntity> root = MetamodelFactory.root(SimpleEntity.class);
        SimpleEntity entity = new SimpleEntity(1, "test");
        assertEquals(entity, root.getValue(entity));
    }

    @Test
    void testRootIsIdenticalReturnsTrueForSameInstance() {
        Metamodel<SimpleEntity, SimpleEntity> root = MetamodelFactory.root(SimpleEntity.class);
        SimpleEntity entity = new SimpleEntity(1, "test");
        assertTrue(root.isIdentical(entity, entity));
    }

    @Test
    void testRootIsIdenticalReturnsFalseForDifferentInstances() {
        Metamodel<SimpleEntity, SimpleEntity> root = MetamodelFactory.root(SimpleEntity.class);
        SimpleEntity entityA = new SimpleEntity(1, "test");
        SimpleEntity entityB = new SimpleEntity(1, "test");
        assertFalse(root.isIdentical(entityA, entityB));
    }

    @Test
    void testRootIsSameReturnsTrueForSamePk() {
        Metamodel<SimpleEntity, SimpleEntity> root = MetamodelFactory.root(SimpleEntity.class);
        SimpleEntity entityA = new SimpleEntity(1, "a");
        SimpleEntity entityB = new SimpleEntity(1, "b");
        assertTrue(root.isSame(entityA, entityB));
    }

    @Test
    void testRootIsSameReturnsFalseForDifferentPk() {
        Metamodel<SimpleEntity, SimpleEntity> root = MetamodelFactory.root(SimpleEntity.class);
        SimpleEntity entityA = new SimpleEntity(1, "a");
        SimpleEntity entityB = new SimpleEntity(2, "a");
        assertFalse(root.isSame(entityA, entityB));
    }

    // of() tests (path-based metamodel)

    @Test
    void testOfSimpleField() {
        Metamodel<SimpleEntity, ?> model = MetamodelFactory.of(SimpleEntity.class, "name");
        assertNotNull(model);
        assertEquals(String.class, model.fieldType());
        assertEquals("name", model.field());
        assertEquals("", model.path());
        assertTrue(model.isColumn());
    }

    @Test
    void testOfPkField() {
        Metamodel<SimpleEntity, ?> model = MetamodelFactory.of(SimpleEntity.class, "id");
        assertNotNull(model);
        assertEquals(Integer.class, model.fieldType());
        assertEquals("id", model.field());
        assertTrue(model.isColumn());
    }

    @Test
    void testOfFkField() {
        Metamodel<EntityWithFk, ?> model = MetamodelFactory.of(EntityWithFk.class, "ref");
        assertNotNull(model);
        assertEquals(ReferencedEntity.class, model.fieldType());
        assertEquals("ref", model.field());
        assertTrue(model.isColumn());
    }

    @Test
    void testOfRefFkField() {
        Metamodel<EntityWithRefFk, ?> model = MetamodelFactory.of(EntityWithRefFk.class, "ref");
        assertNotNull(model);
        assertEquals(ReferencedEntity.class, model.fieldType());
        assertEquals("ref", model.field());
        assertTrue(model.isColumn());
    }

    @Test
    void testOfNestedFkField() {
        Metamodel<EntityWithFk, ?> model = MetamodelFactory.of(EntityWithFk.class, "ref.name");
        assertNotNull(model);
        assertEquals(String.class, model.fieldType());
        assertEquals("name", model.field());
        assertEquals("ref", model.path());
    }

    @Test
    void testOfInlineField() {
        Metamodel<EntityWithInline, ?> model = MetamodelFactory.of(EntityWithInline.class, "address");
        assertNotNull(model);
        assertEquals(InlineAddress.class, model.fieldType());
        assertTrue(model.isInline());
        assertFalse(model.isColumn());
    }

    @Test
    void testOfInlineNestedField() {
        Metamodel<EntityWithInline, ?> model = MetamodelFactory.of(EntityWithInline.class, "address.street");
        assertNotNull(model);
        assertEquals(String.class, model.fieldType());
        assertEquals("address.street", model.field());
        assertTrue(model.isColumn());
    }

    @Test
    void testOfCachesSameInstance() {
        Metamodel<SimpleEntity, ?> model1 = MetamodelFactory.of(SimpleEntity.class, "name");
        Metamodel<SimpleEntity, ?> model2 = MetamodelFactory.of(SimpleEntity.class, "name");
        assertSame(model1, model2);
    }

    // flatten() tests

    @Test
    void testFlattenNonInlineReturnsSingleton() {
        Metamodel<SimpleEntity, ?> model = MetamodelFactory.of(SimpleEntity.class, "name");
        List<Metamodel<SimpleEntity, ?>> flattened = MetamodelFactory.flatten(model);
        assertEquals(1, flattened.size());
        assertSame(model, flattened.get(0));
    }

    @Test
    void testFlattenInlineReturnsLeafFields() {
        Metamodel<EntityWithInline, ?> model = MetamodelFactory.of(EntityWithInline.class, "address");
        assertTrue(model.isInline());
        List<Metamodel<EntityWithInline, ?>> flattened = MetamodelFactory.flatten(model);
        assertEquals(2, flattened.size());
        // Should contain street and zipCode.
        assertTrue(flattened.stream().anyMatch(m -> m.field().endsWith("street")));
        assertTrue(flattened.stream().anyMatch(m -> m.field().endsWith("zipCode")));
    }

    // getValue() tests

    @Test
    void testOfGetValueSimpleField() {
        Metamodel<SimpleEntity, Object> model = MetamodelFactory.of(SimpleEntity.class, "name");
        SimpleEntity entity = new SimpleEntity(1, "hello");
        assertEquals("hello", model.getValue(entity));
    }

    @Test
    void testOfGetValuePkField() {
        Metamodel<SimpleEntity, Object> model = MetamodelFactory.of(SimpleEntity.class, "id");
        SimpleEntity entity = new SimpleEntity(42, "hello");
        assertEquals(42, model.getValue(entity));
    }

    @Test
    void testOfGetValueFkField() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref");
        ReferencedEntity referenced = new ReferencedEntity(10, "referenced");
        EntityWithFk entity = new EntityWithFk(1, referenced);
        assertEquals(referenced, model.getValue(entity));
    }

    @Test
    void testOfGetValueNestedPath() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref.name");
        ReferencedEntity referenced = new ReferencedEntity(10, "nested_name");
        EntityWithFk entity = new EntityWithFk(1, referenced);
        assertEquals("nested_name", model.getValue(entity));
    }

    @Test
    void testOfGetValueNullIntermediateReturnsNull() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref.name");
        EntityWithFk entity = new EntityWithFk(1, null);
        // Null-safe getter should return null when intermediate is null.
        assertEquals(null, model.getValue(entity));
    }

    // isSame / isIdentical tests

    @Test
    void testOfIsSameForScalarField() {
        Metamodel<SimpleEntity, Object> model = MetamodelFactory.of(SimpleEntity.class, "name");
        SimpleEntity entityA = new SimpleEntity(1, "same");
        SimpleEntity entityB = new SimpleEntity(2, "same");
        assertTrue(model.isSame(entityA, entityB));
    }

    @Test
    void testOfIsSameForDifferentScalarField() {
        Metamodel<SimpleEntity, Object> model = MetamodelFactory.of(SimpleEntity.class, "name");
        SimpleEntity entityA = new SimpleEntity(1, "a");
        SimpleEntity entityB = new SimpleEntity(1, "b");
        assertFalse(model.isSame(entityA, entityB));
    }

    @Test
    void testOfIsIdenticalForSameReference() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref");
        ReferencedEntity shared = new ReferencedEntity(10, "shared");
        EntityWithFk entityA = new EntityWithFk(1, shared);
        EntityWithFk entityB = new EntityWithFk(2, shared);
        assertTrue(model.isIdentical(entityA, entityB));
    }

    @Test
    void testOfIsIdenticalForDifferentReference() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref");
        EntityWithFk entityA = new EntityWithFk(1, new ReferencedEntity(10, "a"));
        EntityWithFk entityB = new EntityWithFk(2, new ReferencedEntity(10, "a"));
        assertFalse(model.isIdentical(entityA, entityB));
    }

    @Test
    void testOfIsSameForFkFieldComparesById() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref");
        EntityWithFk entityA = new EntityWithFk(1, new ReferencedEntity(10, "a"));
        EntityWithFk entityB = new EntityWithFk(2, new ReferencedEntity(10, "b"));
        // isSame for Data fields compares by PK, so both have ref.id=10 -> same.
        assertTrue(model.isSame(entityA, entityB));
    }

    @Test
    void testOfIsSameForFkFieldDifferentPk() {
        Metamodel<EntityWithFk, Object> model = MetamodelFactory.of(EntityWithFk.class, "ref");
        EntityWithFk entityA = new EntityWithFk(1, new ReferencedEntity(10, "a"));
        EntityWithFk entityB = new EntityWithFk(2, new ReferencedEntity(20, "a"));
        assertFalse(model.isSame(entityA, entityB));
    }

    // Query columns

    @Test
    void testQueryColumnResolvesWithoutAGeneratedMetamodel() {
        Metamodel<EntityWithQueryColumn, String> metamodel = MetamodelFactory.of(EntityWithQueryColumn.class, "nameFolded");

        assertEquals(EntityWithQueryColumn.class, metamodel.root());
        assertEquals(EntityWithQueryColumn.class, metamodel.table().fieldType());
        assertEquals("", metamodel.path());
        assertEquals("nameFolded", metamodel.field());
        assertEquals(String.class, metamodel.fieldType());
        assertTrue(metamodel.isColumn());
        assertFalse(MetamodelFactory.isNullable(metamodel));
    }

    @Test
    void testQueryColumnHoldsNoValue() {
        Metamodel<EntityWithQueryColumn, String> metamodel = MetamodelFactory.of(EntityWithQueryColumn.class, "nameFolded");
        var a = new EntityWithQueryColumn(1, "a");
        var b = new EntityWithQueryColumn(2, "b");

        var exception = assertThrows(UnsupportedOperationException.class, () -> metamodel.getValue(a));
        assertTrue(exception.getMessage().contains("query column"), exception.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> metamodel.isSame(a, b));
        assertThrows(UnsupportedOperationException.class, () -> metamodel.isIdentical(a, b));
    }

    @Test
    void testQueryColumnBehindAnUnknownPathIsRefused() {
        var exception = assertThrows(PersistenceException.class,
                () -> MetamodelFactory.of(EntityWithQueryColumn.class, "unknown.nameFolded"));
        assertTrue(exception.getMessage().contains("unknown.nameFolded"), exception.getMessage());
    }
}
