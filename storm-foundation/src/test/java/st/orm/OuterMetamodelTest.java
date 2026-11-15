package st.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import st.orm.AbstractMetamodelTest.FieldMetamodel;
import st.orm.AbstractMetamodelTest.TestData;
import st.orm.AbstractMetamodelTest.TestMetamodel;

/**
 * A path named in the enclosing query stands for the column it wraps there: it is no path of the query it is used
 * in, and is never equal to the path it wraps.
 */
class OuterMetamodelTest {

    private final TestMetamodel table = new TestMetamodel(TestData.class);
    private final FieldMetamodel id = new FieldMetamodel("", "id", false, table);

    @Test
    void outerDescribesTheColumnItWraps() {
        Metamodel<TestData, Integer> outer = Metamodel.outer(id);
        assertEquals(id.root(), outer.root());
        assertEquals(id.table(), outer.table());
        assertEquals(id.path(), outer.path());
        assertEquals(id.field(), outer.field());
        assertEquals(id.fieldType(), outer.fieldType());
        assertEquals(id.isColumn(), outer.isColumn());
        assertEquals(id.isInline(), outer.isInline());
        assertEquals(List.of(outer), outer.flatten());
        assertEquals("outer(TestData_.id)", outer.toString());
        assertTrue(Metamodel.outer(SqlFunction.<Integer>of("abs").apply(id)).toString().startsWith("outer(abs("));
    }

    @Test
    void aRecordHoldsNoValueForAnOuterColumn() {
        Metamodel<TestData, Integer> outer = Metamodel.outer(id);
        var record = new TestData(7);
        List<Executable> uses = List.of(() -> outer.getValue(record), () -> outer.isIdentical(record, record),
                () -> outer.isSame(record, record));
        for (var use : uses) {
            var e = assertThrows(UnsupportedOperationException.class, use);
            assertTrue(e.getMessage().contains("outer(TestData_.id) is a column of the enclosing query"), e.getMessage());
        }
    }

    @Test
    void outerIsRecognisedThroughAFunctionCallAndAKeyView() {
        Metamodel<TestData, Integer> outer = Metamodel.outer(id);
        assertTrue(Metamodel.isOuter(outer));
        assertTrue(Metamodel.isOuter(SqlFunction.<Integer>of("abs").apply(outer)));
        assertTrue(Metamodel.isOuter(Metamodel.key(outer)));
        assertFalse(Metamodel.isOuter(id));
        assertFalse(Metamodel.isOuter(SqlFunction.<Integer>of("abs").apply(id)));
    }

    @Test
    void aScrollTakesNoOuterColumn() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> new Scrollable<>(Metamodel.key(Metamodel.outer(id)), false, List.of(), 10, null));
        assertTrue(e.getMessage().contains("a column of the enclosing query"), e.getMessage());
        var sorted = assertThrows(IllegalArgumentException.class,
                () -> Scrollable.of(Metamodel.key(id), 10).sortBy(Metamodel.outer(id)));
        assertTrue(sorted.getMessage().contains("a column of the enclosing query"), sorted.getMessage());
    }

    @Test
    void outerNeverEqualsTheColumnItWraps() {
        Metamodel<TestData, Integer> outer = Metamodel.outer(id);
        assertNotEquals(id, outer);
        assertNotEquals(outer, id);
        assertEquals(Metamodel.outer(id), outer);
        // A key view of the outer path is no more equal to the column than the outer path is.
        assertNotEquals(id, Metamodel.key(outer));
        assertEquals(id, Metamodel.key(id));
        // Neither is a function call on the column, nor a value of another kind.
        assertNotEquals(id, SqlFunction.<Integer>of("abs").apply(id));
        assertFalse(id.equals("id"));
    }

    @Test
    void outerOfAnOuterPathIsThatPath() {
        Metamodel<TestData, Integer> outer = Metamodel.outer(id);
        assertSame(outer, Metamodel.outer(outer));
    }
}
