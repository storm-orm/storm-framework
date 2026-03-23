package st.orm.spi.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import st.orm.Operator;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.template.SqlTemplateException;

class SQLiteSqlDialectTest {

    private final SQLiteSqlDialect dialect = new SQLiteSqlDialect();

    // Identifier validation

    @Test
    void identifierPatternShouldAcceptUnderscorePrefixedNames() {
        assertTrue(dialect.getValidIdentifierPattern().matcher("_temp").matches());
    }

    @Test
    void identifierPatternShouldRejectNumericLeadingCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("123start").matches());
    }

    // Escape: SQLite uses double-quote escaping (ANSI standard)

    @Test
    void escapeShouldWrapInDoubleQuotes() {
        assertEquals("\"myColumn\"", dialect.escape("myColumn"));
    }

    @Test
    void escapeShouldDoubleEmbeddedDoubleQuotes() {
        assertEquals("\"my\"\"Column\"", dialect.escape("my\"Column"));
    }

    @Test
    void escapeShouldHandleMultipleEmbeddedDoubleQuotes() {
        assertEquals("\"a\"\"b\"\"c\"", dialect.escape("a\"b\"c"));
    }

    // getSafeIdentifier: keyword + escape integration

    @Test
    void getSafeIdentifierShouldEscapeSQLiteSpecificKeywords() {
        assertEquals("\"ABORT\"", dialect.getSafeIdentifier("ABORT"));
        assertEquals("\"AUTOINCREMENT\"", dialect.getSafeIdentifier("AUTOINCREMENT"));
        assertEquals("\"PRAGMA\"", dialect.getSafeIdentifier("PRAGMA"));
    }

    @Test
    void getSafeIdentifierShouldNotEscapeNormalIdentifiers() {
        assertEquals("myTable", dialect.getSafeIdentifier("myTable"));
    }

    @Test
    void getSafeIdentifierShouldEscapeIdentifiersWithSpaces() {
        assertEquals("\"my table\"", dialect.getSafeIdentifier("my table"));
    }

    @Test
    void isKeywordShouldBeCaseInsensitive() {
        assertTrue(dialect.isKeyword("abort"));
        assertTrue(dialect.isKeyword("ABORT"));
        assertFalse(dialect.isKeyword("myColumn"));
    }

    // Identifier pattern: extraction from SQL text

    @Test
    void identifierPatternShouldExtractDoubleQuotedIdentifiers() {
        var matcher = dialect.getIdentifierPattern().matcher("SELECT \"my col\" FROM t");
        assertTrue(matcher.find());
        assertEquals("\"my col\"", matcher.group());
    }

    @Test
    void identifierPatternShouldNotMatchUnquotedText() {
        assertFalse(dialect.getIdentifierPattern().matcher("SELECT myCol FROM t").find());
    }

    // Quote literal pattern

    @Test
    void quoteLiteralPatternShouldMatchStringWithEscapedQuotes() {
        var matcher = dialect.getQuoteLiteralPattern().matcher("'it''s a test'");
        assertTrue(matcher.find());
        assertEquals("'it''s a test'", matcher.group());
    }

    // SQLite-specific limit/offset syntax

    @Test
    void limitShouldGenerateLimitClause() {
        assertEquals("LIMIT 10", dialect.limit(10));
        assertEquals("LIMIT 0", dialect.limit(0));
    }

    @Test
    void offsetShouldGenerateLimitMinusOneOffset() {
        // SQLite requires a LIMIT clause before OFFSET.
        assertEquals("LIMIT -1 OFFSET 5", dialect.offset(5));
        assertEquals("LIMIT -1 OFFSET 0", dialect.offset(0));
    }

    @Test
    void limitWithOffsetShouldPutLimitBeforeOffset() {
        assertEquals("LIMIT 20 OFFSET 10", dialect.limit(10, 20));
    }

    // SQLite-specific lock hints (no row-level locking)

    @Test
    void forShareLockShouldReturnEmpty() {
        assertEquals("", dialect.forShareLockHint());
    }

    // Sequence SQL: SQLite does not support sequences

    @Test
    void sequenceNextValShouldThrowPersistenceException() {
        assertThrows(PersistenceException.class, () -> dialect.sequenceNextVal("my_seq"));
    }

    // Provider filter

    @Test
    void providerFilterShouldAcceptNonSqlDialectProviders() {
        assertTrue(SQLiteProviderFilter.INSTANCE.test(new st.orm.core.spi.Provider() {}));
    }

    @Test
    void providerFilterShouldRejectForeignSqlDialectProviders() {
        assertFalse(SQLiteProviderFilter.INSTANCE.test(new st.orm.core.spi.SqlDialectProvider() {
            @Override public st.orm.core.template.SqlDialect getSqlDialect(StormConfig config) { return null; }
        }));
    }

    @Test
    void providerFilterShouldAcceptSQLiteEntityRepositoryProvider() {
        assertTrue(SQLiteProviderFilter.INSTANCE.test(new SQLiteEntityRepositoryProviderImpl()));
    }

    // SqlDialectProvider

    @Test
    void sqlDialectProviderShouldReturnDialectWithSQLiteSpecificBehavior() {
        var provider = new SQLiteSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertEquals("\"col\"", sqlDialect.escape("col"));
        assertEquals("LIMIT 5", sqlDialect.limit(5));
    }

    @Test
    void nameShouldReturnSQLite() {
        assertEquals("SQLite", dialect.name());
    }

    @Test
    void configConstructorShouldCreateDialectWithSameBehavior() {
        var configDialect = new SQLiteSqlDialect(StormConfig.of(Map.of()));
        assertEquals("SQLite", configDialect.name());
        assertEquals("\"col\"", configDialect.escape("col"));
    }

    @Test
    void supportsDeleteAliasShouldReturnFalse() {
        assertFalse(dialect.supportsDeleteAlias());
    }

    @Test
    void supportsMultiValueTuplesShouldReturnFalse() {
        assertFalse(dialect.supportsMultiValueTuples());
    }

    @Test
    void forUpdateLockHintShouldReturnEmpty() {
        assertEquals("", dialect.forUpdateLockHint());
    }

    @Test
    void forShareLockHintShouldReturnEmpty() {
        assertEquals("", dialect.forShareLockHint());
    }

    @Test
    void validIdentifierPatternShouldAcceptAlphanumericWithUnderscore() {
        assertTrue(dialect.getValidIdentifierPattern().matcher("myTable123").matches());
        assertTrue(dialect.getValidIdentifierPattern().matcher("my_table").matches());
    }

    @Test
    void validIdentifierPatternShouldRejectEmptyString() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("").matches());
    }

    @Test
    void quoteLiteralPatternShouldMatchSimpleStringLiteral() {
        var matcher = dialect.getQuoteLiteralPattern().matcher("'hello'");
        assertTrue(matcher.find());
        assertEquals("'hello'", matcher.group());
    }

    @Test
    void quoteLiteralPatternShouldMatchEmptyStringLiteral() {
        var matcher = dialect.getQuoteLiteralPattern().matcher("''");
        assertTrue(matcher.find());
        assertEquals("''", matcher.group());
    }

    @Test
    void entityRepositoryProviderShouldNotBeNull() {
        assertNotNull(new SQLiteEntityRepositoryProviderImpl());
    }

    @Test
    void sqlDialectProviderShouldCreateDialectWithAllSQLiteSpecificBehavior() {
        var provider = new SQLiteSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertNotNull(sqlDialect);
        assertEquals("SQLite", sqlDialect.name());
        assertFalse(sqlDialect.supportsDeleteAlias());
        assertFalse(sqlDialect.supportsMultiValueTuples());
        assertEquals("", sqlDialect.forUpdateLockHint());
        assertEquals("", sqlDialect.forShareLockHint());
        assertEquals("LIMIT -1 OFFSET 5", sqlDialect.offset(5));
    }

    @Test
    void isKeywordShouldRecognizeSQLiteSpecificKeywords() {
        assertTrue(dialect.isKeyword("ABORT"));
        assertTrue(dialect.isKeyword("AUTOINCREMENT"));
        assertTrue(dialect.isKeyword("CONFLICT"));
        assertTrue(dialect.isKeyword("DATABASE"));
        assertTrue(dialect.isKeyword("DETACH"));
        assertTrue(dialect.isKeyword("EXPLAIN"));
        assertTrue(dialect.isKeyword("FAIL"));
        assertTrue(dialect.isKeyword("GLOB"));
        assertTrue(dialect.isKeyword("IF"));
        assertTrue(dialect.isKeyword("IGNORE"));
        assertTrue(dialect.isKeyword("INDEX"));
        assertTrue(dialect.isKeyword("INDEXED"));
        assertTrue(dialect.isKeyword("INSTEAD"));
        assertTrue(dialect.isKeyword("ISNULL"));
        assertTrue(dialect.isKeyword("KEY"));
        assertTrue(dialect.isKeyword("LIMIT"));
        assertTrue(dialect.isKeyword("NOTNULL"));
        assertTrue(dialect.isKeyword("OFFSET"));
        assertTrue(dialect.isKeyword("PLAN"));
        assertTrue(dialect.isKeyword("PRAGMA"));
        assertTrue(dialect.isKeyword("QUERY"));
        assertTrue(dialect.isKeyword("RAISE"));
        assertTrue(dialect.isKeyword("REGEXP"));
        assertTrue(dialect.isKeyword("REINDEX"));
        assertTrue(dialect.isKeyword("RENAME"));
        assertTrue(dialect.isKeyword("REPLACE"));
        assertTrue(dialect.isKeyword("VACUUM"));
        assertTrue(dialect.isKeyword("VIRTUAL"));
    }

    @Test
    void escapeShouldHandleEmptyName() {
        assertEquals("\"\"", dialect.escape(""));
    }

    @Test
    void sequenceDiscoveryStrategyShouldReturnNone() {
        assertEquals(SQLiteSqlDialect.SequenceDiscoveryStrategy.NONE, dialect.sequenceDiscoveryStrategy());
    }

    @Test
    void constraintDiscoveryStrategyShouldReturnJdbcMetadata() {
        assertEquals(SQLiteSqlDialect.ConstraintDiscoveryStrategy.JDBC_METADATA, dialect.constraintDiscoveryStrategy());
    }

    // multiColumnExpression: fallback to default (no tuple support)

    private SequencedMap<String, Object> row(String column1, Object value1, String column2, Object value2) {
        var map = new LinkedHashMap<String, Object>();
        map.put(column1, value1);
        map.put(column2, value2);
        return map;
    }

    @Test
    void multiColumnExpressionShouldUseAndSyntaxForEquals() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.EQUALS, values, v -> "?");
        assertEquals("a = ? AND b = ?", result);
    }

    @Test
    void multiColumnExpressionShouldUseOrOfAndSyntaxForIn() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.IN, values, v -> "?");
        assertEquals("(a = ? AND b = ?) OR (a = ? AND b = ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseNotSyntaxForNotEquals() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.NOT_EQUALS, values, v -> "?");
        assertEquals("NOT (a = ? AND b = ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseLexicographicExpansionForGreaterThan() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.GREATER_THAN, values, v -> "?");
        assertEquals("(a > ? OR (a = ? AND b > ?))", result);
    }

    @Test
    void multiColumnExpressionShouldUseLexicographicExpansionForLessThanOrEqual() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.LESS_THAN_OR_EQUAL, values, v -> "?");
        assertEquals("(a < ? OR (a = ? AND b <= ?))", result);
    }

    @Test
    void multiColumnExpressionShouldUseLexicographicExpansionForBetween() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.BETWEEN, values, v -> "?");
        assertEquals("((a > ? OR (a = ? AND b >= ?)) AND (a < ? OR (a = ? AND b <= ?)))", result);
    }

    @Test
    void multiColumnExpressionShouldFallBackForUnsupportedOperator() {
        var values = List.of(row("a", 1, "b", 2));
        // LIKE is not supported for multi-column, so it falls back to the default which throws.
        assertThrows(SqlTemplateException.class,
                () -> dialect.multiColumnExpression(Operator.LIKE, values, v -> "?"));
    }
}
