package st.orm.spi.mariadb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import st.orm.PersistenceException;
import st.orm.StormConfig;

/**
 * Unit tests for {@link MariaDBSqlDialect} verifying MariaDB-specific behavior,
 * especially where it diverges from the MySQL base class.
 */
class MariaDBSqlDialectTest {

    private final MariaDBSqlDialect dialect = new MariaDBSqlDialect();

    // Escape: MariaDB inherits MySQL backtick escaping

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
    void getSafeIdentifierShouldEscapeMySQLKeywords() {
        // MariaDB reserves FULLTEXT and OPTIMIZE as MySQL does.
        assertEquals("`FULLTEXT`", dialect.getSafeIdentifier("FULLTEXT"));
        assertEquals("`OPTIMIZE`", dialect.getSafeIdentifier("OPTIMIZE"));
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

    @Test
    void isKeywordShouldRecognizeWordsMariaDBReserves() {
        assertTrue(dialect.isKeyword("CONVERSION"));
        assertTrue(dialect.isKeyword("RETURNING"));
        assertTrue(dialect.isKeyword("ROW_NUMBER"));
        assertTrue(dialect.isKeyword("TO_DATE"));
        assertTrue(dialect.isKeyword("VECTOR"));
    }

    @Test
    void isKeywordShouldNotRecognizeWordsOnlyMySQLReserves() {
        assertFalse(dialect.isKeyword("DENSE_RANK"));
        assertFalse(dialect.isKeyword("GROUPS"));
        assertFalse(dialect.isKeyword("LIBRARY"));
        assertFalse(dialect.isKeyword("RANK"));
    }

    // Identifier pattern: matches both backtick and double-quote

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

    // Quote literal pattern

    @Test
    void quoteLiteralPatternShouldMatchStringWithEscapedQuotes() {
        var matcher = dialect.getQuoteLiteralPattern().matcher("'it''s a test'");
        assertTrue(matcher.find());
        assertEquals("'it''s a test'", matcher.group());
    }

    // MySQL-inherited limit/offset with MySQL's unusual offset workaround

    @Test
    void offsetShouldUseLimitMaxWorkaround() {
        // MySQL/MariaDB do not support standalone OFFSET; they use LIMIT MAX_BIGINT OFFSET N.
        assertEquals("LIMIT 18446744073709551615 OFFSET 5", dialect.offset(5));
    }

    @Test
    void limitWithOffsetShouldCombineLimitAndOffset() {
        assertEquals("LIMIT 20 OFFSET 10", dialect.limit(10, 20));
    }

    // MariaDB's key behavioral override: sequence support

    @Test
    void sequenceNextValShouldReturnNextValueForSyntax() {
        // MariaDB overrides MySQL's throwing behavior to support sequences.
        assertEquals("NEXT VALUE FOR my_seq", dialect.sequenceNextVal("my_seq"));
    }

    @Test
    void sequenceNextValShouldEscapeKeywordSequenceName() {
        var result = dialect.sequenceNextVal("SELECT");
        assertEquals("NEXT VALUE FOR `SELECT`", result);
    }

    @Test
    void mariaDBSequenceSupportShouldDifferFromMySQL() {
        // This is the critical behavioral difference: MariaDB supports sequences, MySQL does not.
        var mariaResult = dialect.sequenceNextVal("test_seq");
        assertTrue(mariaResult.contains("NEXT VALUE FOR"));

        var mysqlDialect = new st.orm.spi.mysql.MySQLSqlDialect();
        assertThrows(PersistenceException.class, () -> mysqlDialect.sequenceNextVal("test_seq"));
    }

    @Test
    void sequenceDiscoveryStrategyShouldReturnInformationSchemaTables() {
        assertEquals(
                st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy.INFORMATION_SCHEMA_TABLES,
                dialect.sequenceDiscoveryStrategy());
    }

    // Provider filter

    @Test
    void providerFilterShouldAcceptNonSqlDialectProviders() {
        assertTrue(MariaDBProviderFilter.INSTANCE.test(new st.orm.core.spi.Provider() {}));
    }

    @Test
    void providerFilterShouldRejectForeignSqlDialectProviders() {
        assertFalse(MariaDBProviderFilter.INSTANCE.test(new st.orm.core.spi.SqlDialectProvider() {
            @Override public st.orm.core.template.SqlDialect getSqlDialect(StormConfig config) { return null; }
        }));
    }

    @Test
    void providerFilterShouldAcceptMariaDBEntityRepositoryProvider() {
        assertTrue(MariaDBProviderFilter.INSTANCE.test(new MariaDBEntityRepositoryProviderImpl()));
    }

    // SqlDialectProvider

    @Test
    void sqlDialectProviderShouldReturnDialectWithMariaDBSpecificBehavior() {
        var provider = new MariaDBSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        // MariaDB-specific: backtick escaping and LIMIT syntax (inherited from MySQL).
        assertEquals("`col`", sqlDialect.escape("col"));
        assertEquals("LIMIT 5", sqlDialect.limit(5));
        assertEquals("MariaDB", sqlDialect.name());
    }

    @Test
    void nameShouldReturnMariaDB() {
        assertEquals("MariaDB", dialect.name());
    }

    @Test
    void configConstructorShouldCreateDialectWithSameBehavior() {
        var configDialect = new MariaDBSqlDialect(StormConfig.of(Map.of()));
        assertEquals("MariaDB", configDialect.name());
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
    void limitShouldGenerateLimitClause() {
        assertEquals("LIMIT 10", dialect.limit(10));
        assertEquals("LIMIT 0", dialect.limit(0));
    }

    @Test
    void validIdentifierPatternShouldAcceptUnderscorePrefix() {
        assertTrue(dialect.getValidIdentifierPattern().matcher("_temp").matches());
    }

    @Test
    void validIdentifierPatternShouldRejectEmptyString() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("").matches());
    }

    @Test
    void validIdentifierPatternShouldRejectSpecialCharacters() {
        assertFalse(dialect.getValidIdentifierPattern().matcher("my-table").matches());
    }

    @Test
    void quoteLiteralPatternShouldMatchSimpleStringLiteral() {
        var matcher = dialect.getQuoteLiteralPattern().matcher("'hello'");
        assertTrue(matcher.find());
        assertEquals("'hello'", matcher.group());
    }

    @Test
    void entityRepositoryProviderShouldNotBeNull() {
        assertNotNull(new MariaDBEntityRepositoryProviderImpl());
    }

    @Test
    void sqlDialectProviderShouldCreateDialectWithAllMariaDBSpecificBehavior() {
        var provider = new MariaDBSqlDialectProviderImpl();
        var sqlDialect = provider.getSqlDialect(StormConfig.of(Map.of()));
        assertNotNull(sqlDialect);
        assertEquals("MariaDB", sqlDialect.name());
        assertTrue(sqlDialect.supportsDeleteAlias());
        assertTrue(sqlDialect.supportsMultiValueTuples());
        assertEquals("FOR UPDATE", sqlDialect.forUpdateLockHint());
        assertEquals("FOR SHARE", sqlDialect.forShareLockHint());
    }

    @Test
    void escapeShouldHandleEmptyName() {
        assertEquals("``", dialect.escape(""));
    }
}
