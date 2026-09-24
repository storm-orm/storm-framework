package st.orm.spi.postgresql;

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
import st.orm.SqlTemplateException;
import st.orm.StormConfig;

/**
 * Unit tests for {@link PostgreSQLSqlDialect} verifying PostgreSQL-specific SQL generation behavior.
 */
class PostgreSQLSqlDialectTest {

    private final PostgreSQLSqlDialect dialect = new PostgreSQLSqlDialect();

    // Identifier validation

    @Test
    void identifierPatternShouldRejectUnderscorePrefixedNames() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("_temp").matches());
    }

    @Test
    void identifierPatternShouldRejectNumericLeadingCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("123start").matches());
    }

    // Escape: PostgreSQL uses double-quote escaping

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
    void getSafeIdentifierShouldEscapePostgreSQLSpecificKeywords() {
        assertEquals("\"ILIKE\"", dialect.getSafeIdentifier("ILIKE"));
        assertEquals("\"RETURNING\"", dialect.getSafeIdentifier("RETURNING"));
        assertEquals("\"desc\"", dialect.getSafeIdentifier("desc"));
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
        assertTrue(dialect.isKeyword("ilike"));
        assertTrue(dialect.isKeyword("ILIKE"));
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

    // PostgreSQL-specific limit/offset syntax

    @Test
    void limitShouldGenerateLimitClause() {
        assertEquals("LIMIT 10", dialect.limit(10));
        assertEquals("LIMIT 0", dialect.limit(0));
    }

    @Test
    void offsetShouldGeneratePlainOffsetWithoutRowsKeyword() {
        // PostgreSQL uses OFFSET N (not OFFSET N ROWS like Oracle/MSSQL).
        assertEquals("OFFSET 5", dialect.offset(5));
        assertEquals("OFFSET 0", dialect.offset(0));
    }

    @Test
    void limitWithOffsetShouldPutOffsetBeforeLimit() {
        assertEquals("OFFSET 10 LIMIT 20", dialect.limit(10, 20));
    }

    // PostgreSQL-specific lock hints

    @Test
    void forShareLockShouldUseForKeyShare() {
        // PostgreSQL uses FOR KEY SHARE (weaker than FOR SHARE) to allow concurrent inserts.
        assertEquals("FOR KEY SHARE", dialect.forShareLockHint());
    }

    // Sequence SQL: PostgreSQL nextval('name')

    @Test
    void sequenceNextValShouldGenerateNextvalFunction() {
        assertEquals("nextval('my_seq')", dialect.sequenceNextVal("my_seq"));
    }

    @Test
    void sequenceNextValShouldEscapeKeywordSequenceName() {
        var result = dialect.sequenceNextVal("SELECT");
        assertTrue(result.startsWith("nextval('"));
        assertTrue(result.contains("SELECT"));
    }

    // Provider filter

    @Test
    void providerFilterShouldAcceptNonSqlDialectProviders() {
        assertTrue(PostgreSQLProviderFilter.INSTANCE.test(new st.orm.core.spi.Provider() {}));
    }

    @Test
    void providerFilterShouldRejectForeignSqlDialectProviders() {
        assertFalse(PostgreSQLProviderFilter.INSTANCE.test(new st.orm.core.spi.SqlDialectProvider() {
            @Override public st.orm.core.template.SqlDialect getSqlDialect(StormConfig config) { return null; }
        }));
    }

    @Test
    void providerFilterShouldAcceptPostgreSQLEntityRepositoryProvider() {
        assertTrue(PostgreSQLProviderFilter.INSTANCE.test(new PostgreSQLEntityRepositoryProviderImpl()));
    }

    // SqlDialectProvider

    @Test
    void sqlDialectProviderShouldReturnDialectWithPostgreSQLSpecificBehavior() {
        var provider = new PostgreSQLSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertEquals("\"col\"", sqlDialect.escape("col"));
        assertEquals("LIMIT 5", sqlDialect.limit(5));
    }

    @Test
    void nameShouldReturnPostgreSQL() {
        assertEquals("PostgreSQL", dialect.name());
    }

    @Test
    void configConstructorShouldCreateDialectWithSameBehavior() {
        var configDialect = new PostgreSQLSqlDialect(StormConfig.of(Map.of()));
        assertEquals("PostgreSQL", configDialect.name());
        assertEquals("\"col\"", configDialect.escape("col"));
    }

    @Test
    void supportsDeleteAliasShouldReturnFalse() {
        assertFalse(dialect.supportsDeleteAlias());
    }

    @Test
    void supportsMultiValueTuplesShouldReturnTrue() {
        assertTrue(dialect.supportsMultiValueTuples());
    }

    @Test
    void forUpdateLockHintShouldReturnForUpdate() {
        assertEquals("FOR UPDATE", dialect.forUpdateLockHint());
    }

    @Test
    void forShareLockHintShouldReturnForKeyShare() {
        assertEquals("FOR KEY SHARE", dialect.forShareLockHint());
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
        assertNotNull(new PostgreSQLEntityRepositoryProviderImpl());
    }

    @Test
    void sqlDialectProviderShouldCreateDialectWithAllPostgreSQLSpecificBehavior() {
        var provider = new PostgreSQLSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertNotNull(sqlDialect);
        assertEquals("PostgreSQL", sqlDialect.name());
        assertFalse(sqlDialect.supportsDeleteAlias());
        assertTrue(sqlDialect.supportsMultiValueTuples());
        assertEquals("FOR UPDATE", sqlDialect.forUpdateLockHint());
        assertEquals("FOR KEY SHARE", sqlDialect.forShareLockHint());
        assertEquals("OFFSET 5", sqlDialect.offset(5));
    }

    @Test
    void isKeywordShouldRecognizePostgreSQLSpecificKeywords() {
        assertTrue(dialect.isKeyword("ANALYSE"));
        assertTrue(dialect.isKeyword("ANALYZE"));
        assertTrue(dialect.isKeyword("ASC"));
        assertTrue(dialect.isKeyword("COLLATION"));
        assertTrue(dialect.isKeyword("CONCURRENTLY"));
        assertTrue(dialect.isKeyword("DEFERRABLE"));
        assertTrue(dialect.isKeyword("DESC"));
        assertTrue(dialect.isKeyword("FREEZE"));
        assertTrue(dialect.isKeyword("ILIKE"));
        assertTrue(dialect.isKeyword("INITIALLY"));
        assertTrue(dialect.isKeyword("ISNULL"));
        assertTrue(dialect.isKeyword("LIMIT"));
        assertTrue(dialect.isKeyword("NOTNULL"));
        assertTrue(dialect.isKeyword("PLACING"));
        assertTrue(dialect.isKeyword("RETURNING"));
        assertTrue(dialect.isKeyword("VARIADIC"));
        assertTrue(dialect.isKeyword("VERBOSE"));
    }

    @Test
    void escapeShouldHandleEmptyName() {
        assertEquals("\"\"", dialect.escape(""));
    }

    // multiColumnExpression: tuple syntax for supported operators

    private SequencedMap<String, Object> row(String column1, Object value1, String column2, Object value2) {
        var map = new LinkedHashMap<String, Object>();
        map.put(column1, value1);
        map.put(column2, value2);
        return map;
    }

    @Test
    void multiColumnExpressionShouldExpandEquals() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.EQUALS, values, v -> "?");
        assertEquals("a = ? AND b = ?", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForIn() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.IN, values, v -> "?");
        assertEquals("(a, b) IN ((?, ?), (?, ?))", result);
    }

    @Test
    void multiColumnExpressionShouldExpandNotEquals() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.NOT_EQUALS, values, v -> "?");
        assertEquals("NOT (a = ? AND b = ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForGreaterThan() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.GREATER_THAN, values, v -> "?");
        assertEquals("(a, b) > (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForLessThanOrEqual() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.LESS_THAN_OR_EQUAL, values, v -> "?");
        assertEquals("(a, b) <= (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForBetween() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.BETWEEN, values, v -> "?");
        assertEquals("(a, b) BETWEEN (?, ?) AND (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldFallBackForUnsupportedOperator() {
        var values = List.of(row("a", 1, "b", 2));
        // LIKE is not in the tuple-supported list, so it falls back to the default which throws.
        assertThrows(SqlTemplateException.class,
                () -> dialect.multiColumnExpression(Operator.LIKE, values, v -> "?"));
    }
}
