package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.PK;
import st.orm.Ref;

/**
 * Tests for {@link WeakInterner}.
 */
public class WeakInternerTest {

    // Simple entity for testing.
    record TestEntity(@PK Integer id, String name) implements Entity<Integer> {}

    // A non-entity record for testing.
    record SimpleData(String value) {}

    @Test
    public void testInternNonEntityReturnsCanonicalInstance() {
        WeakInterner interner = new WeakInterner();
        SimpleData data1 = new SimpleData("hello");
        SimpleData data2 = new SimpleData("hello");

        SimpleData interned1 = interner.intern(data1);
        SimpleData interned2 = interner.intern(data2);
        assertSame(interned1, interned2, "Should return the same canonical instance for equal objects");
    }

    @Test
    public void testInternEntityReturnsCanonicalInstance() {
        WeakInterner interner = new WeakInterner();
        TestEntity entity1 = new TestEntity(1, "Alice");
        TestEntity entity2 = new TestEntity(1, "Alice");

        TestEntity interned1 = interner.intern(entity1);
        TestEntity interned2 = interner.intern(entity2);
        assertSame(interned1, interned2, "Should return the same canonical instance for equal entities");
    }

    @Test
    public void testInternEntityDifferentPrimaryKeyReturnsDifferentInstances() {
        WeakInterner interner = new WeakInterner();
        TestEntity entity1 = new TestEntity(1, "Alice");
        TestEntity entity2 = new TestEntity(2, "Bob");

        TestEntity interned1 = interner.intern(entity1);
        TestEntity interned2 = interner.intern(entity2);
        assertSame(entity1, interned1);
        assertSame(entity2, interned2);
    }

    @Test
    public void testInternEntitySamePkReturnsCachedInstance() {
        WeakInterner interner = new WeakInterner();
        TestEntity entity1 = new TestEntity(1, "Alice");
        TestEntity entity2 = new TestEntity(1, "Updated Alice");

        TestEntity interned1 = interner.intern(entity1);
        assertSame(entity1, interned1);
        // entity2 has the same PK, so the interner returns the existing cached instance.
        TestEntity interned2 = interner.intern(entity2);
        assertSame(entity1, interned2, "Should return cached entity for same PK");
    }

    @Test
    public void testInternNullThrowsException() {
        WeakInterner interner = new WeakInterner();
        assertThrows(NullPointerException.class, () -> interner.intern(null));
    }

    @Test
    public void testGetEntityByTypeAndPk() {
        WeakInterner interner = new WeakInterner();
        TestEntity entity = new TestEntity(1, "Alice");
        interner.intern(entity);

        TestEntity cached = interner.get(TestEntity.class, 1);
        assertNotNull(cached, "Should find cached entity");
        assertSame(entity, cached);
    }

    @Test
    public void testGetEntityReturnsNullWhenNotCached() {
        WeakInterner interner = new WeakInterner();
        TestEntity cached = interner.get(TestEntity.class, 999);
        assertNull(cached, "Should return null when entity is not cached");
    }

    @Test
    public void testInternDifferentNonEntityValues() {
        WeakInterner interner = new WeakInterner();
        SimpleData data1 = new SimpleData("hello");
        SimpleData data2 = new SimpleData("world");

        SimpleData interned1 = interner.intern(data1);
        SimpleData interned2 = interner.intern(data2);
        assertSame(data1, interned1);
        assertSame(data2, interned2);
    }

    @Test
    public void testInternStringReturnsCanonicalInstance() {
        WeakInterner interner = new WeakInterner();
        // Use new String() to avoid the JVM string pool.
        String string1 = new String("test");
        String string2 = new String("test");

        String interned1 = interner.intern(string1);
        String interned2 = interner.intern(string2);
        assertSame(interned1, interned2, "Should intern equal strings to the same instance");
    }

    @Test
    public void testInternMultipleEntitiesDifferentTypes() {
        // Test entity of another type.
        record OtherEntity(@PK Integer id, String label) implements Entity<Integer> {}

        WeakInterner interner = new WeakInterner();
        TestEntity testEntity = new TestEntity(1, "Alice");
        OtherEntity otherEntity = new OtherEntity(1, "Other");

        TestEntity internedTest = interner.intern(testEntity);
        OtherEntity internedOther = interner.intern(otherEntity);
        assertSame(testEntity, internedTest);
        assertSame(otherEntity, internedOther);
    }

    @Test
    public void testGetEntityForDifferentTypeReturnsNull() {
        record OtherEntity(@PK Integer id, String label) implements Entity<Integer> {}

        WeakInterner interner = new WeakInterner();
        TestEntity entity = new TestEntity(1, "Alice");
        interner.intern(entity);

        // Looking up with a different entity type but same PK should return null
        // because the Ref includes the entity type.
        OtherEntity result = interner.get(OtherEntity.class, 1);
        assertNull(result, "Should return null when looking up different entity type");
    }

    @Test
    public void testGetEntityAfterInternReturnsCorrectInstance() {
        WeakInterner interner = new WeakInterner();
        TestEntity entity1 = new TestEntity(1, "Alice");
        TestEntity entity2 = new TestEntity(2, "Bob");
        interner.intern(entity1);
        interner.intern(entity2);

        assertSame(entity1, interner.get(TestEntity.class, 1));
        assertSame(entity2, interner.get(TestEntity.class, 2));
        assertNull(interner.get(TestEntity.class, 3));
    }

    @Test
    public void testLoadedRefMeetingAnUnloadedRefBecomesTheCanonicalInstance() {
        WeakInterner interner = new WeakInterner();
        Ref<TestEntity> unloaded = Ref.of(TestEntity.class, 1);
        Ref<TestEntity> loaded = Ref.of(new TestEntity(1, "Alice"));
        assertSame(unloaded, interner.intern(unloaded));

        // The loaded reference is handed back, not the unloaded one interned a moment earlier.
        assertSame(loaded, interner.intern(loaded));
        // The loaded reference is the canonical instance from then on: an unloaded reference to the same record
        // resolves to it.
        Ref<TestEntity> later = Ref.of(TestEntity.class, 1);
        assertSame(loaded, interner.intern(later));
        assertTrue(interner.intern(later).isLoaded());
    }

    @Test
    public void testUnloadedRefMeetingALoadedRefReceivesTheLoadedInstance() {
        WeakInterner interner = new WeakInterner();
        Ref<TestEntity> loaded = Ref.of(new TestEntity(1, "Alice"));
        Ref<TestEntity> unloaded = Ref.of(TestEntity.class, 1);
        assertSame(loaded, interner.intern(loaded));
        assertSame(loaded, interner.intern(unloaded));
    }

    @Test
    public void testTwoUnloadedRefsKeepTheFirst() {
        WeakInterner interner = new WeakInterner();
        Ref<TestEntity> first = Ref.of(TestEntity.class, 1);
        Ref<TestEntity> second = Ref.of(TestEntity.class, 1);
        assertSame(first, interner.intern(first));
        assertSame(first, interner.intern(second));
        assertFalse(interner.intern(second).isLoaded());
    }

    @Test
    public void testTwoLoadedRefsKeepTheFirst() {
        WeakInterner interner = new WeakInterner();
        Ref<TestEntity> first = Ref.of(new TestEntity(1, "Alice"));
        Ref<TestEntity> second = Ref.of(new TestEntity(1, "Alice"));
        assertSame(first, interner.intern(first));
        assertSame(first, interner.intern(second));
    }
}
