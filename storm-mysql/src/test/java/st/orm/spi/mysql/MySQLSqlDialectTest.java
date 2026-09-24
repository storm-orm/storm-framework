package st.orm.spi.mysql;

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
import st.orm.SqlTemplateException;
import st.orm.StormConfig;

/**
 * Unit tests for {@link MySQLSqlDialect} verifying MySQL-specific SQL generation behavior.
 */
class MySQLSqlDialectTest {

    private final MySQLSqlDialect dialect = new MySQLSqlDialect();

    // Identifier validation: MySQL allows underscore prefix

    @Test
    void identifierPatternShouldAcceptUnderscorePrefixedNames() {
        // MySQL (unlike ANSI/Oracle/PostgreSQL) allows _ as first character.
        assertTrue(dialect.getValidIdentifierPattern().matcher("_temp").matches());
    }

    @Test
    void identifierPatternShouldRejectNumericLeadingCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("123start").matches());
    }

    @Test
    void identifierPatternShouldRejectEmptyString() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("").matches());
    }

    // Escape: MySQL uses backtick escaping

    @Test
    void escapeShouldWrapInBackticks() {
        assertEquals("`myColumn`", dialect.escape("myColumn"));
    }

    @Test
    void escapeShouldDoubleEmbeddedBackticks() {
        assertEquals("`my``Column`", dialect.escape("my`Column"));
    }

    @Test
    void escapeShouldHandleMultipleEmbeddedBackticks() {
        assertEquals("`a``b``c`", dialect.escape("a`b`c"));
    }

    // getSafeIdentifier: keyword + escape integration

    @Test
    void getSafeIdentifierShouldEscapeMySQLSpecificKeywords() {
        // FULLTEXT, UNSIGNED, ZEROFILL are MySQL-specific keywords.
        assertEquals("`FULLTEXT`", dialect.getSafeIdentifier("FULLTEXT"));
        assertEquals("`UNSIGNED`", dialect.getSafeIdentifier("UNSIGNED"));
        assertEquals("`ZEROFILL`", dialect.getSafeIdentifier("ZEROFILL"));
    }

    @Test
    void getSafeIdentifierShouldNotEscapeNormalIdentifiers() {
        assertEquals("myTable", dialect.getSafeIdentifier("myTable"));
        assertEquals("_temp", dialect.getSafeIdentifier("_temp"));
    }

    @Test
    void getSafeIdentifierShouldEscapeIdentifiersWithSpaces() {
        assertEquals("`my table`", dialect.getSafeIdentifier("my table"));
    }

    @Test
    void isKeywordShouldBeCaseInsensitive() {
        assertTrue(dialect.isKeyword("fulltext"));
        assertTrue(dialect.isKeyword("FULLTEXT"));
        assertFalse(dialect.isKeyword("myColumn"));
    }

    // Identifier pattern: matches backtick and double-quote identifiers

    @Test
    void identifierPatternShouldExtractBacktickIdentifiers() {
        var matcher = dialect.getIdentifierPattern().matcher("SELECT `my col` FROM t");
        assertTrue(matcher.find());
        assertEquals("`my col`", matcher.group());
    }

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

    // MySQL-specific limit/offset with unusual offset workaround

    @Test
    void limitShouldGenerateLimitClause() {
        assertEquals("LIMIT 10", dialect.limit(10));
        assertEquals("LIMIT 0", dialect.limit(0));
    }

    @Test
    void offsetShouldUseLimitMaxBigintWorkaround() {
        // MySQL does not support standalone OFFSET; uses LIMIT MAX_BIGINT OFFSET N.
        assertEquals("LIMIT 18446744073709551615 OFFSET 5", dialect.offset(5));
        assertEquals("LIMIT 18446744073709551615 OFFSET 0", dialect.offset(0));
    }

    @Test
    void limitWithOffsetShouldCombineLimitAndOffset() {
        assertEquals("LIMIT 20 OFFSET 10", dialect.limit(10, 20));
    }

    // MySQL sequence: not supported, should throw

    @Test
    void sequenceNextValShouldThrowPersistenceException() {
        assertThrows(PersistenceException.class, () -> dialect.sequenceNextVal("my_seq"));
    }

    @Test
    void sequenceNextValExceptionShouldContainMeaningfulMessage() {
        var exception = assertThrows(PersistenceException.class, () -> dialect.sequenceNextVal("my_seq"));
        assertTrue(exception.getMessage().contains("MySQL"));
        assertTrue(exception.getMessage().contains("sequence"));
    }

    // Provider filter

    @Test
    void providerFilterShouldAcceptNonSqlDialectProviders() {
        assertTrue(MySQLProviderFilter.INSTANCE.test(new st.orm.core.spi.Provider() {}));
    }

    @Test
    void providerFilterShouldRejectForeignSqlDialectProviders() {
        assertFalse(MySQLProviderFilter.INSTANCE.test(new st.orm.core.spi.SqlDialectProvider() {
            @Override public st.orm.core.template.SqlDialect getSqlDialect(StormConfig config) { return null; }
        }));
    }

    @Test
    void providerFilterShouldAcceptMySQLEntityRepositoryProvider() {
        assertTrue(MySQLProviderFilter.INSTANCE.test(new MySQLEntityRepositoryProviderImpl()));
    }

    // SqlDialectProvider

    @Test
    void sqlDialectProviderShouldReturnDialectWithMySQLSpecificBehavior() {
        var provider = new MySQLSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        // MySQL-specific: backtick escaping and LIMIT syntax.
        assertEquals("`col`", sqlDialect.escape("col"));
        assertEquals("LIMIT 5", sqlDialect.limit(5));
    }

    @Test
    void nameShouldReturnMySQL() {
        assertEquals("MySQL", dialect.name());
    }

    @Test
    void configConstructorShouldCreateDialectWithSameBehavior() {
        var configDialect = new MySQLSqlDialect(StormConfig.of(Map.of()));
        assertEquals("MySQL", configDialect.name());
        assertEquals("`col`", configDialect.escape("col"));
    }

    @Test
    void supportsDeleteAliasShouldReturnTrue() {
        assertTrue(dialect.supportsDeleteAlias());
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
    void forShareLockHintShouldReturnForShare() {
        assertEquals("FOR SHARE", dialect.forShareLockHint());
    }

    @Test
    void validIdentifierPatternShouldAcceptAlphanumericWithUnderscore() {
        assertTrue(dialect.getValidIdentifierPattern().matcher("my_table_123").matches());
    }

    @Test
    void validIdentifierPatternShouldRejectSpecialCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("my-table").matches());
        assertFalse(dialect.getValidIdentifierPattern().matcher("my table").matches());
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
        assertNotNull(new MySQLEntityRepositoryProviderImpl());
    }

    @Test
    void sqlDialectProviderShouldCreateDialectWithAllMySQLSpecificBehavior() {
        var provider = new MySQLSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertNotNull(sqlDialect);
        assertEquals("MySQL", sqlDialect.name());
        assertTrue(sqlDialect.supportsDeleteAlias());
        assertTrue(sqlDialect.supportsMultiValueTuples());
        assertEquals("FOR UPDATE", sqlDialect.forUpdateLockHint());
        assertEquals("FOR SHARE", sqlDialect.forShareLockHint());
    }

    @Test
    void isKeywordShouldRecognizeMySQLSpecificKeywords() {
        assertTrue(dialect.isKeyword("ACCESSIBLE"));
        assertTrue(dialect.isKeyword("ANALYZE"));
        assertTrue(dialect.isKeyword("CHANGE"));
        assertTrue(dialect.isKeyword("DATABASE"));
        assertTrue(dialect.isKeyword("DELAYED"));
        assertTrue(dialect.isKeyword("ENCLOSED"));
        assertTrue(dialect.isKeyword("ESCAPED"));
        assertTrue(dialect.isKeyword("FORCE"));
        assertTrue(dialect.isKeyword("GENERATED"));
        assertTrue(dialect.isKeyword("HIGH_PRIORITY"));
        assertTrue(dialect.isKeyword("INT1"));
        assertTrue(dialect.isKeyword("INT2"));
        assertTrue(dialect.isKeyword("INT3"));
        assertTrue(dialect.isKeyword("INT4"));
        assertTrue(dialect.isKeyword("INT8"));
        assertTrue(dialect.isKeyword("KEYS"));
        assertTrue(dialect.isKeyword("LINES"));
        assertTrue(dialect.isKeyword("LOAD"));
        assertTrue(dialect.isKeyword("LOW_PRIORITY"));
        assertTrue(dialect.isKeyword("MEDIUMINT"));
        assertTrue(dialect.isKeyword("MIDDLEINT"));
        assertTrue(dialect.isKeyword("OPTIMIZE"));
        assertTrue(dialect.isKeyword("OPTIONALLY"));
        assertTrue(dialect.isKeyword("OUTFILE"));
        assertTrue(dialect.isKeyword("PURGE"));
        assertTrue(dialect.isKeyword("REQUIRE"));
        assertTrue(dialect.isKeyword("SCHEMAS"));
        assertTrue(dialect.isKeyword("SHOW"));
        assertTrue(dialect.isKeyword("SQL_BIG_RESULT"));
        assertTrue(dialect.isKeyword("SQL_CALC_FOUND_ROWS"));
        assertTrue(dialect.isKeyword("SQL_SMALL_RESULT"));
        assertTrue(dialect.isKeyword("STRAIGHT_JOIN"));
        assertTrue(dialect.isKeyword("TERMINATED"));
        assertTrue(dialect.isKeyword("TINYINT"));
        assertTrue(dialect.isKeyword("UNSIGNED"));
        assertTrue(dialect.isKeyword("UTC_DATE"));
        assertTrue(dialect.isKeyword("UTC_TIME"));
        assertTrue(dialect.isKeyword("UTC_TIMESTAMP"));
        assertTrue(dialect.isKeyword("VIRTUAL"));
        assertTrue(dialect.isKeyword("XOR"));
        assertTrue(dialect.isKeyword("ZEROFILL"));
    }

    @Test
    void isKeywordShouldRecognizeWordsMySQLReservesForWindowsAndLocking() {
        assertTrue(dialect.isKeyword("DENSE_RANK"));
        assertTrue(dialect.isKeyword("GROUPS"));
        assertTrue(dialect.isKeyword("LIMIT"));
        assertTrue(dialect.isKeyword("LOCK"));
        assertTrue(dialect.isKeyword("LONG"));
        assertTrue(dialect.isKeyword("RANK"));
        assertTrue(dialect.isKeyword("READ"));
        assertTrue(dialect.isKeyword("ROW_NUMBER"));
        assertTrue(dialect.isKeyword("SCHEMA"));
        assertTrue(dialect.isKeyword("USAGE"));
    }

    @Test
    void isKeywordShouldRecognizeWordsMySQL9Reserves() {
        assertTrue(dialect.isKeyword("LIBRARY"));
        assertTrue(dialect.isKeyword("QUALIFY"));
    }

    @Test
    void sequenceDiscoveryStrategyShouldReturnNone() {
        assertEquals(
                st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy.NONE,
                dialect.sequenceDiscoveryStrategy());
    }

    @Test
    void useCatalogAsSchemaShouldReturnTrue() {
        assertTrue(dialect.useCatalogAsSchema());
    }

    @Test
    void constraintDiscoveryStrategyShouldReturnInformationSchemaReferencing() {
        assertEquals(
                st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy.INFORMATION_SCHEMA_REFERENCING,
                dialect.constraintDiscoveryStrategy());
    }

    @Test
    void escapeShouldHandleEmptyName() {
        assertEquals("``", dialect.escape(""));
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
    void multiColumnExpressionShouldUseTupleSyntaxForNotIn() throws Exception {
        var values = List.of(row("a", 1, "b", 2), row("a", 3, "b", 4));
        var result = dialect.multiColumnExpression(Operator.NOT_IN, values, v -> "?");
        assertEquals("(a, b) NOT IN ((?, ?), (?, ?))", result);
    }

    @Test
    void multiColumnExpressionShouldExpandGreaterThanOrEqual() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.GREATER_THAN_OR_EQUAL, values, v -> "?");
        assertEquals("(a > ? OR (a = ? AND b >= ?))", result);
    }

    @Test
    void multiColumnExpressionShouldExpandLessThan() throws Exception {
        var values = List.of(row("a", 1, "b", 2));
        var result = dialect.multiColumnExpression(Operator.LESS_THAN, values, v -> "?");
        assertEquals("(a < ? OR (a = ? AND b < ?))", result);
    }

    @Test
    void multiColumnExpressionShouldFallBackForUnsupportedOperator() {
        var values = List.of(row("a", 1, "b", 2));
        assertThrows(SqlTemplateException.class,
                () -> dialect.multiColumnExpression(Operator.LIKE, values, v -> "?"));
    }
}
