package st.orm.spi.oracle;

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
 * Unit tests for {@link OracleSqlDialect} verifying Oracle-specific SQL generation behavior.
 */
class OracleSqlDialectTest {

    private final OracleSqlDialect dialect = new OracleSqlDialect();

    // Escape: Oracle uses double-quote escaping

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
    void getSafeIdentifierShouldEscapeOracleSpecificKeywords() {
        // ROWNUM, ROWID, SYNONYM, VARCHAR2 are Oracle-specific keywords.
        assertEquals("\"ROWNUM\"", dialect.getSafeIdentifier("ROWNUM"));
        assertEquals("\"ROWID\"", dialect.getSafeIdentifier("ROWID"));
        assertEquals("\"VARCHAR2\"", dialect.getSafeIdentifier("VARCHAR2"));
    }

    @Test
    void getSafeIdentifierShouldNotEscapeNormalIdentifiers() {
        assertEquals("myTable", dialect.getSafeIdentifier("myTable"));
    }

    @Test
    void getSafeIdentifierShouldNotEscapeWordsOracleAccepts() {
        // Oracle folds an unquoted name to upper case, so quoting one of these would make it case-sensitive and stop
        // it matching a column created without quotes.
        for (String name : List.of("position", "value", "year", "month", "time", "timestamp", "result", "language")) {
            assertEquals(name, dialect.getSafeIdentifier(name));
        }
    }

    @Test
    void getSafeIdentifierShouldEscapeIdentifiersWithInvalidCharacters() {
        assertEquals("\"my table\"", dialect.getSafeIdentifier("my table"));
        assertEquals("\"my-col\"", dialect.getSafeIdentifier("my-col"));
    }

    @Test
    void isKeywordShouldBeCaseInsensitive() {
        assertTrue(dialect.isKeyword("rownum"));
        assertTrue(dialect.isKeyword("ROWNUM"));
        assertFalse(dialect.isKeyword("myColumn"));
    }

    // Identifier pattern

    @Test
    void identifierPatternShouldExtractDoubleQuotedIdentifiers() {
        var matcher = dialect.getIdentifierPattern().matcher("SELECT \"my col\" FROM t");
        assertTrue(matcher.find());
        assertEquals("\"my col\"", matcher.group());
    }

    // Quote literal pattern

    @Test
    void quoteLiteralPatternShouldMatchStringWithEscapedQuotes() {
        var matcher = dialect.getQuoteLiteralPattern().matcher("'it''s a test'");
        assertTrue(matcher.find());
        assertEquals("'it''s a test'", matcher.group());
    }

    // Oracle-specific limit/offset: FETCH FIRST / OFFSET ROWS syntax

    @Test
    void limitShouldUseFetchFirstSyntax() {
        // Oracle uses FETCH FIRST N ROWS ONLY, not LIMIT N.
        assertEquals("FETCH FIRST 10 ROWS ONLY", dialect.limit(10));
        assertEquals("FETCH FIRST 1 ROWS ONLY", dialect.limit(1));
        assertEquals("FETCH FIRST 0 ROWS ONLY", dialect.limit(0));
    }

    @Test
    void offsetShouldUseOffsetRowsSyntax() {
        assertEquals("OFFSET 5 ROWS", dialect.offset(5));
        assertEquals("OFFSET 0 ROWS", dialect.offset(0));
    }

    @Test
    void limitWithOffsetShouldUseFetchNextSyntax() {
        assertEquals("OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY", dialect.limit(10, 20));
        assertEquals("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY", dialect.limit(0, 1));
    }

    // Oracle-specific lock hints: no shared lock, FOR UPDATE

    @Test
    void forShareLockShouldReturnEmptyBecauseOracleDoesNotSupportSharedLocks() {
        // Oracle does not support a shared lock hint, so it returns empty string.
        assertEquals("", dialect.forShareLockHint());
    }

    // Sequence SQL: Oracle-specific .NEXTVAL syntax

    @Test
    void sequenceNextValShouldGenerateDotNextvalSyntax() {
        assertEquals("my_seq.NEXTVAL", dialect.sequenceNextVal("my_seq"));
    }

    @Test
    void sequenceNextValShouldEscapeKeywordSequenceName() {
        var result = dialect.sequenceNextVal("SELECT");
        assertEquals("\"SELECT\".NEXTVAL", result);
    }

    // Provider filter

    @Test
    void providerFilterShouldAcceptNonSqlDialectProviders() {
        assertTrue(OracleProviderFilter.INSTANCE.test(new st.orm.core.spi.Provider() {}));
    }

    @Test
    void providerFilterShouldRejectForeignSqlDialectProviders() {
        assertFalse(OracleProviderFilter.INSTANCE.test(new st.orm.core.spi.SqlDialectProvider() {
            @Override public st.orm.core.template.SqlDialect getSqlDialect(StormConfig config) { return null; }
        }));
    }

    @Test
    void providerFilterShouldAcceptOracleEntityRepositoryProvider() {
        assertTrue(OracleProviderFilter.INSTANCE.test(new OracleEntityRepositoryProviderImpl()));
    }

    // SqlDialectProvider

    @Test
    void sqlDialectProviderShouldReturnDialectWithOracleSpecificBehavior() {
        var provider = new OracleSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        // Oracle-specific: double-quote escaping and FETCH FIRST syntax.
        assertEquals("\"col\"", sqlDialect.escape("col"));
        assertEquals("FETCH FIRST 5 ROWS ONLY", sqlDialect.limit(5));
    }

    @Test
    void nameShouldReturnOracle() {
        assertEquals("Oracle", dialect.name());
    }

    @Test
    void configConstructorShouldCreateDialectWithSameBehavior() {
        var configDialect = new OracleSqlDialect(StormConfig.of(Map.of()));
        assertEquals("Oracle", configDialect.name());
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
    void sequenceDiscoveryStrategyShouldReturnAllSequences() {
        assertEquals(
                st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy.ALL_SEQUENCES,
                dialect.sequenceDiscoveryStrategy());
    }

    @Test
    void constraintDiscoveryStrategyShouldReturnAllConstraints() {
        assertEquals(
                st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy.ALL_CONSTRAINTS,
                dialect.constraintDiscoveryStrategy());
    }

    @Test
    void validIdentifierPatternShouldAcceptAlphanumericWithUnderscore() {
        assertTrue(dialect.getValidIdentifierPattern().matcher("myTable123").matches());
        assertTrue(dialect.getValidIdentifierPattern().matcher("my_table").matches());
    }

    @Test
    void validIdentifierPatternShouldRejectUnderscorePrefix() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("_temp").matches());
    }

    @Test
    void validIdentifierPatternShouldRejectSpecialCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("my-table").matches());
        assertFalse(dialect.getValidIdentifierPattern().matcher("my table").matches());
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
    void entityRepositoryProviderShouldNotBeNull() {
        assertNotNull(new OracleEntityRepositoryProviderImpl());
    }

    @Test
    void sqlDialectProviderShouldCreateDialectWithAllOracleSpecificBehavior() {
        var provider = new OracleSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertNotNull(sqlDialect);
        assertEquals("Oracle", sqlDialect.name());
        assertFalse(sqlDialect.supportsDeleteAlias());
        assertTrue(sqlDialect.supportsMultiValueTuples());
        assertEquals("FOR UPDATE", sqlDialect.forUpdateLockHint());
        assertEquals("", sqlDialect.forShareLockHint());
    }

    @Test
    void isKeywordShouldRecognizeAnsiKeywords() {
        assertTrue(dialect.isKeyword("SELECT"));
        assertTrue(dialect.isKeyword("INSERT"));
        assertTrue(dialect.isKeyword("UPDATE"));
        assertTrue(dialect.isKeyword("DELETE"));
    }

    @Test
    void isKeywordShouldRecognizeOracleSpecificKeywords() {
        assertTrue(dialect.isKeyword("ACCESS"));
        assertTrue(dialect.isKeyword("AUDIT"));
        assertTrue(dialect.isKeyword("CLUSTER"));
        assertTrue(dialect.isKeyword("COMMENT"));
        assertTrue(dialect.isKeyword("COMPRESS"));
        assertTrue(dialect.isKeyword("EXCLUSIVE"));
        assertTrue(dialect.isKeyword("FILE"));
        assertTrue(dialect.isKeyword("IDENTIFIED"));
        assertTrue(dialect.isKeyword("INCREMENT"));
        assertTrue(dialect.isKeyword("INDEX"));
        assertTrue(dialect.isKeyword("INITIAL"));
        assertTrue(dialect.isKeyword("LOCK"));
        assertTrue(dialect.isKeyword("LONG"));
        assertTrue(dialect.isKeyword("MAXEXTENTS"));
        assertTrue(dialect.isKeyword("MLSLABEL"));
        assertTrue(dialect.isKeyword("MODE"));
        assertTrue(dialect.isKeyword("MODIFY"));
        assertTrue(dialect.isKeyword("NOWAIT"));
        assertTrue(dialect.isKeyword("OFFLINE"));
        assertTrue(dialect.isKeyword("ONLINE"));
        assertTrue(dialect.isKeyword("PCTFREE"));
        assertTrue(dialect.isKeyword("RAW"));
        assertTrue(dialect.isKeyword("SESSION"));
        assertTrue(dialect.isKeyword("SHARE"));
        assertTrue(dialect.isKeyword("SUCCESSFUL"));
        assertTrue(dialect.isKeyword("UID"));
        assertTrue(dialect.isKeyword("VALIDATE"));
        assertTrue(dialect.isKeyword("VIEW"));
    }

    @Test
    void isKeywordShouldRecognizeReservedWordsThatAreCommonColumnNames() {
        assertTrue(dialect.isKeyword("LEVEL"));
        assertTrue(dialect.isKeyword("NUMBER"));
        assertTrue(dialect.isKeyword("OPTION"));
        assertTrue(dialect.isKeyword("PUBLIC"));
        assertTrue(dialect.isKeyword("RESOURCE"));
        assertTrue(dialect.isKeyword("SIZE"));
        assertTrue(dialect.isKeyword("SYSDATE"));
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
    void multiColumnExpressionShouldUseTupleSyntaxForBetween() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.BETWEEN, values, v -> "?");
        assertEquals("(a, b) BETWEEN (?, ?) AND (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForLessThan() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.LESS_THAN, values, v -> "?");
        assertEquals("(a, b) < (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldUseTupleSyntaxForGreaterThanOrEqual() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.GREATER_THAN_OR_EQUAL, values, v -> "?");
        assertEquals("(a, b) >= (?, ?)", result);
    }

    @Test
    void multiColumnExpressionShouldFallBackForUnsupportedOperator() {
        var values = List.of(row("a", 1, "b", 2));
        assertThrows(SqlTemplateException.class,
                () -> dialect.multiColumnExpression(Operator.LIKE, values, v -> "?"));
    }
}
