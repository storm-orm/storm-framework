package st.orm.spi.h2;

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
import st.orm.StormConfig;
import st.orm.core.template.SqlTemplateException;

class H2SqlDialectTest {

    private final H2SqlDialect dialect = new H2SqlDialect();

    // Identifier validation

    @Test
    void identifierPatternShouldRejectUnderscorePrefixedNames() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("_temp").matches());
    }

    @Test
    void identifierPatternShouldRejectNumericLeadingCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("123start").matches());
    }

    // Escape: H2 uses double-quote escaping (ANSI standard)

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
    void getSafeIdentifierShouldEscapeH2SpecificKeywords() {
        assertEquals("\"ILIKE\"", dialect.getSafeIdentifier("ILIKE"));
        assertEquals("\"AUTOINCREMENT\"", dialect.getSafeIdentifier("AUTOINCREMENT"));
        assertEquals("\"CACHED\"", dialect.getSafeIdentifier("CACHED"));
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

    // H2-specific limit/offset syntax

    @Test
    void limitShouldGenerateLimitClause() {
        assertEquals("LIMIT 10", dialect.limit(10));
        assertEquals("LIMIT 0", dialect.limit(0));
    }

    @Test
    void offsetShouldGeneratePlainOffsetWithoutRowsKeyword() {
        assertEquals("OFFSET 5", dialect.offset(5));
        assertEquals("OFFSET 0", dialect.offset(0));
    }

    @Test
    void limitWithOffsetShouldPutOffsetBeforeLimit() {
        assertEquals("LIMIT 20 OFFSET 10", dialect.limit(10, 20));
    }

    // H2-specific lock hints

    @Test
    void forShareLockShouldReturnEmptyString() {
        assertEquals("", dialect.forShareLockHint());
    }

    // Sequence SQL: H2 NEXT VALUE FOR syntax

    @Test
    void sequenceNextValShouldGenerateNextValueForSyntax() {
        assertEquals("NEXT VALUE FOR my_seq", dialect.sequenceNextVal("my_seq"));
    }

    @Test
    void sequenceNextValShouldEscapeKeywordSequenceName() {
        var result = dialect.sequenceNextVal("SELECT");
        assertTrue(result.startsWith("NEXT VALUE FOR "));
        assertTrue(result.contains("SELECT"));
    }

    // Provider filter

    @Test
    void providerFilterShouldAcceptNonSqlDialectProviders() {
        assertTrue(H2ProviderFilter.INSTANCE.test(new st.orm.core.spi.Provider() {}));
    }

    @Test
    void providerFilterShouldRejectForeignSqlDialectProviders() {
        assertFalse(H2ProviderFilter.INSTANCE.test(new st.orm.core.spi.SqlDialectProvider() {
            @Override public st.orm.core.template.SqlDialect getSqlDialect(StormConfig config) { return null; }
        }));
    }

    @Test
    void providerFilterShouldAcceptH2EntityRepositoryProvider() {
        assertTrue(H2ProviderFilter.INSTANCE.test(new H2EntityRepositoryProviderImpl()));
    }

    // SqlDialectProvider

    @Test
    void sqlDialectProviderShouldReturnDialectWithH2SpecificBehavior() {
        var provider = new H2SqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertEquals("\"col\"", sqlDialect.escape("col"));
        assertEquals("LIMIT 5", sqlDialect.limit(5));
    }

    @Test
    void nameShouldReturnH2() {
        assertEquals("H2", dialect.name());
    }

    @Test
    void configConstructorShouldCreateDialectWithSameBehavior() {
        var configDialect = new H2SqlDialect(StormConfig.of(Map.of()));
        assertEquals("H2", configDialect.name());
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
    void forShareLockHintShouldReturnEmptyString() {
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
        assertNotNull(new H2EntityRepositoryProviderImpl());
    }

    @Test
    void sqlDialectProviderShouldCreateDialectWithAllH2SpecificBehavior() {
        var provider = new H2SqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertNotNull(sqlDialect);
        assertEquals("H2", sqlDialect.name());
        assertFalse(sqlDialect.supportsDeleteAlias());
        assertTrue(sqlDialect.supportsMultiValueTuples());
        assertEquals("FOR UPDATE", sqlDialect.forUpdateLockHint());
        assertEquals("", sqlDialect.forShareLockHint());
        assertEquals("OFFSET 5", sqlDialect.offset(5));
    }

    @Test
    void isKeywordShouldRecognizeH2SpecificKeywords() {
        assertTrue(dialect.isKeyword("AUTOINCREMENT"));
        assertTrue(dialect.isKeyword("CACHED"));
        assertTrue(dialect.isKeyword("EXPLAIN"));
        assertTrue(dialect.isKeyword("ILIKE"));
        assertTrue(dialect.isKeyword("INDEX"));
        assertTrue(dialect.isKeyword("LIMIT"));
        assertTrue(dialect.isKeyword("MEMORY"));
        assertTrue(dialect.isKeyword("MINUS"));
        assertTrue(dialect.isKeyword("OFFSET"));
        assertTrue(dialect.isKeyword("QUALIFY"));
        assertTrue(dialect.isKeyword("REGEXP"));
        assertTrue(dialect.isKeyword("ROWNUM"));
        assertTrue(dialect.isKeyword("TODAY"));
        assertTrue(dialect.isKeyword("TOP"));
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
    void multiColumnExpressionShouldUseTupleSyntaxForEquals() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.EQUALS, values, v -> "?");
        assertEquals("(a, b) = (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForIn() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.IN, values, v -> "?");
        assertEquals("(a, b) IN ((?, ?), (?, ?))", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForNotEquals() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.NOT_EQUALS, values, v -> "?");
        assertEquals("(a, b) <> (?, ?)", result);
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
