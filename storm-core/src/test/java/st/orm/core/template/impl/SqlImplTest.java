package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlTemplate;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Tests for {@link SqlImpl}.
 */
public class SqlImplTest {

    private SqlImpl createBasicSql() {
        return new SqlImpl(
                SqlOperation.SELECT,
                "SELECT * FROM users",
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                false,
                Optional.empty(), StatementOrigin.DIRECT, 0L
        );
    }

    @Test
    public void testBasicConstruction() {
        SqlImpl sql = createBasicSql();
        assertEquals(SqlOperation.SELECT, sql.operation());
        assertEquals("SELECT * FROM users", sql.statement());
        assertTrue(sql.parameters().isEmpty());
        assertTrue(sql.bindVariables().isEmpty());
        assertTrue(sql.generatedKeys().isEmpty());
        assertTrue(sql.affectedType().isEmpty());
        assertTrue(sql.dataType().isEmpty());
        assertFalse(sql.versionAware());
        assertTrue(sql.unsafeWarning().isEmpty());
    }

    @Test
    public void testOperationReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.operation(SqlOperation.INSERT);
        assertEquals(SqlOperation.INSERT, updated.operation());
        assertEquals("SELECT * FROM users", updated.statement());
    }

    @Test
    public void testStatementReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.statement("INSERT INTO users VALUES (?)");
        assertEquals("INSERT INTO users VALUES (?)", updated.statement());
        assertEquals(SqlOperation.SELECT, updated.operation());
    }

    @Test
    public void testParametersReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.parameters(List.of());
        assertNotNull(updated);
        assertTrue(updated.parameters().isEmpty());
    }

    @Test
    public void testBindVariablesReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.bindVariables(null);
        assertTrue(updated.bindVariables().isEmpty());
    }

    @Test
    public void testGeneratedKeysReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.generatedKeys(List.of("id", "created_at"));
        assertEquals(List.of("id", "created_at"), updated.generatedKeys());
    }

    @Test
    public void testAffectedTypeReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.affectedType(null);
        assertTrue(updated.affectedType().isEmpty());
    }

    @Test
    public void testVersionAwareReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.versionAware(true);
        assertTrue(updated.versionAware());
        assertFalse(sql.versionAware());
    }

    @Test
    public void testUnsafeWarningReturnsNewInstance() {
        SqlImpl sql = createBasicSql();
        Sql updated = sql.unsafeWarning("This query is unsafe");
        assertTrue(updated.unsafeWarning().isPresent());
        assertEquals("This query is unsafe", updated.unsafeWarning().get());
    }

    @Test
    public void testUnsafeWarningNullClearsWarning() {
        SqlImpl sql = new SqlImpl(
                SqlOperation.SELECT,
                "SELECT * FROM users",
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                false,
                Optional.of("existing warning"), StatementOrigin.DIRECT, 0L
        );
        Sql updated = sql.unsafeWarning(null);
        assertTrue(updated.unsafeWarning().isEmpty());
    }

    @Test
    public void testNullOperationThrows() {
        assertThrows(NullPointerException.class, () -> new SqlImpl(
                null,
                "SELECT 1",
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                false,
                Optional.empty(),
                StatementOrigin.DIRECT, 0L
        ));
    }

    @Test
    public void testNullStatementThrows() {
        assertThrows(NullPointerException.class, () -> new SqlImpl(
                SqlOperation.SELECT,
                null,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                false,
                Optional.empty(),
                StatementOrigin.DIRECT, 0L
        ));
    }

    @Test
    public void testAllOperationTypes() {
        for (SqlOperation operation : SqlOperation.values()) {
            SqlImpl sql = new SqlImpl(
                    operation,
                    "SQL",
                    List.of(),
                    Optional.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    List.of(),
                    false,
                    Optional.empty(), StatementOrigin.DIRECT, 0L
            );
            assertEquals(operation, sql.operation());
        }
    }

    @Test
    public void testParametersAreDefensivelyCopied() {
        List<SqlTemplate.Parameter> mutableParameters = new ArrayList<>();
        mutableParameters.add(new SqlTemplate.PositionalParameter(1, "original"));
        // SqlImpl's compact constructor calls copyOf on parameters, so later modifications of the list handed in
        // must not affect the constructed statement.
        SqlImpl sql = new SqlImpl(
                SqlOperation.SELECT,
                "SELECT 1",
                mutableParameters,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                false,
                Optional.empty(), StatementOrigin.DIRECT, 0L
        );
        mutableParameters.add(new SqlTemplate.PositionalParameter(2, "added later"));
        assertEquals(List.of(new SqlTemplate.PositionalParameter(1, "original")), sql.parameters());
    }

    @Test
    public void testGeneratedKeysDefensivelyCopied() {
        SqlImpl sql = new SqlImpl(
                SqlOperation.INSERT,
                "INSERT INTO t (x) VALUES (?)",
                List.of(),
                Optional.empty(),
                List.of("id"),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                false,
                Optional.empty(), StatementOrigin.DIRECT, 0L
        );
        assertEquals(1, sql.generatedKeys().size());
        assertEquals("id", sql.generatedKeys().getFirst());
    }
}
