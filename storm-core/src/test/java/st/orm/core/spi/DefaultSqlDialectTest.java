package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import st.orm.PersistenceException;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;

/**
 * Tests for {@link DefaultSqlDialect}.
 */
public class DefaultSqlDialectTest {

    @Test
    public void testNameNoAnsiEscaping() {
        var config = StormConfig.of(java.util.Map.of("storm.ansi_escaping", "false"));
        var dialect = new DefaultSqlDialect(config);
        assertEquals("Default", dialect.name());
    }

    @Test
    public void testNameAnsiEscaping() {
        var config = StormConfig.of(java.util.Map.of("storm.ansi_escaping", "true"));
        var dialect = new DefaultSqlDialect(config);
        assertEquals("Default[ansi]", dialect.name());
    }

    @Test
    public void testSupportsDeleteAlias() {
        var dialect = new DefaultSqlDialect();
        assertFalse(dialect.supportsDeleteAlias());
    }

    @Test
    public void testSupportsMultiValueTuples() {
        var dialect = new DefaultSqlDialect();
        assertFalse(dialect.supportsMultiValueTuples());
    }

    @Test
    public void testIsKeyword() {
        var dialect = new DefaultSqlDialect();
        assertTrue(dialect.isKeyword("SELECT"));
        assertTrue(dialect.isKeyword("select"));
        assertFalse(dialect.isKeyword("mycolumn"));
    }

    @Test
    public void testEscapeNoAnsi() {
        var config = StormConfig.of(java.util.Map.of("storm.ansi_escaping", "false"));
        var dialect = new DefaultSqlDialect(config);
        assertEquals("myTable", dialect.escape("myTable"));
    }

    @Test
    public void testEscapeAnsi() {
        var config = StormConfig.of(java.util.Map.of("storm.ansi_escaping", "true"));
        var dialect = new DefaultSqlDialect(config);
        assertEquals("\"myTable\"", dialect.escape("myTable"));
    }

    @Test
    public void testEscapeAnsiWithQuotes() {
        var config = StormConfig.of(java.util.Map.of("storm.ansi_escaping", "true"));
        var dialect = new DefaultSqlDialect(config);
        assertEquals("\"my\"\"Table\"", dialect.escape("my\"Table"));
    }

    @Test
    public void testGetSafeIdentifierRegular() {
        var dialect = new DefaultSqlDialect();
        assertEquals("myColumn", dialect.getSafeIdentifier("myColumn"));
    }

    @Test
    public void testGetSafeIdentifierKeyword() {
        var dialect = new DefaultSqlDialect();
        // "SELECT" is a keyword, so it gets escaped.
        String result = dialect.getSafeIdentifier("SELECT");
        assertNotNull(result);
    }

    @Test
    public void testGetValidIdentifierPattern() {
        var dialect = new DefaultSqlDialect();
        var pattern = dialect.getValidIdentifierPattern();
        assertTrue(pattern.matcher("myColumn").matches());
        assertFalse(pattern.matcher("123abc").matches());
    }

    @Test
    public void testSingleLineCommentPattern() {
        var dialect = new DefaultSqlDialect();
        assertNotNull(dialect.getSingleLineCommentPattern());
    }

    @Test
    public void testMultiLineCommentPattern() {
        var dialect = new DefaultSqlDialect();
        assertNotNull(dialect.getMultiLineCommentPattern());
    }

    @Test
    public void testIdentifierPattern() {
        var dialect = new DefaultSqlDialect();
        assertNotNull(dialect.getIdentifierPattern());
    }

    @Test
    public void testQuoteLiteralPattern() {
        var dialect = new DefaultSqlDialect();
        assertNotNull(dialect.getQuoteLiteralPattern());
    }

    @Test
    public void testLimitOnly() {
        var dialect = new DefaultSqlDialect();
        assertEquals("LIMIT 10", dialect.limit(10));
    }

    @Test
    public void testOffsetOnly() {
        var dialect = new DefaultSqlDialect();
        assertEquals("OFFSET 5", dialect.offset(5));
    }

    @Test
    public void testLimitAndOffset() {
        var dialect = new DefaultSqlDialect();
        assertEquals("LIMIT 10 OFFSET 5", dialect.limit(5, 10));
    }

    @Test
    public void testApplyLimitAfterSelect() {
        var dialect = new DefaultSqlDialect();
        assertFalse(dialect.applyLimitAfterSelect());
    }

    @Test
    public void testApplyLockHintAfterFrom() {
        var dialect = new DefaultSqlDialect();
        assertFalse(dialect.applyLockHintAfterFrom());
    }

    @Test
    public void testForShareLockHint() {
        var dialect = new DefaultSqlDialect();
        assertEquals("FOR SHARE", dialect.forShareLockHint());
    }

    @Test
    public void testForUpdateLockHint() {
        var dialect = new DefaultSqlDialect();
        assertEquals("FOR UPDATE", dialect.forUpdateLockHint());
    }

    @Test
    public void testSequenceNextValThrows() {
        var dialect = new DefaultSqlDialect();
        assertThrows(PersistenceException.class, () -> dialect.sequenceNextVal("my_seq"));
    }

    @Test
    public void testMultiValueInSingleRow() throws SqlTemplateException {
        var dialect = new DefaultSqlDialect();
        SequencedMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        String result = dialect.multiValueIn(List.of(row), v -> "?");
        assertTrue(result.contains("id"));
    }

    @Test
    public void testMultiValueInMultipleRows() throws SqlTemplateException {
        var dialect = new DefaultSqlDialect();
        SequencedMap<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", 1);
        SequencedMap<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 2);
        String result = dialect.multiValueIn(List.of(row1, row2), v -> "?");
        assertTrue(result.contains("OR"));
    }

    @Test
    void unorderedOffsetNeedsNoOrdering() {
        assertNull(new DefaultSqlDialect().orderByForOffset());
    }
}
