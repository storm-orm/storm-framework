/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.spi.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.spi.Providers;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.impl.PreparedStatementTemplateImpl;
import st.orm.core.template.impl.SchemaValidationError;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;
import st.orm.core.template.impl.SchemaValidator;

/**
 * Tests that a dialect is chosen for the database in front of it, not for the modules that happen to be on the
 * classpath.
 *
 * <p>This module puts the MySQL dialect provider on the test classpath, and these tests connect to H2, which no
 * provider here claims. Resolution must therefore land on the default dialect. Picking the first registered
 * provider instead lands on MySQL, whose constraint discovery reads a {@code KEY_COLUMN_USAGE} column H2 does not
 * have; that query fails, the foreign keys are never read, and validation loses the ability to say anything about
 * them.</p>
 *
 * <p>Every module has at most one dialect, so this mismatch is the only configuration in which the reactor can
 * catch a resolution that ignores the database. {@link #theProductBlindLookupWouldPickThisModulesDialect()} asserts
 * that the mismatch is still there, so that adding the H2 dialect to this module's test scope fails loudly rather
 * than quietly disarming these tests.</p>
 */
class MySQLDialectResolutionTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @DbTable("parent")
    record Parent(@PK Integer id) implements Entity<Integer> {}

    @DbTable("child")
    record Child(@PK Integer id, @FK Parent parent) implements Entity<Integer> {}

    private static final String PARENT_TABLE = "CREATE TABLE parent (id INTEGER PRIMARY KEY)";

    private static final String CHILD_WITH_FOREIGN_KEY =
            "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parent (id))";

    private static final String CHILD_WITHOUT_FOREIGN_KEY =
            "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL)";

    private static final String OTHER_TABLE = "CREATE TABLE other (id INTEGER PRIMARY KEY)";

    /** The column carries a foreign key, but to a table the entity does not name. */
    private static final String CHILD_WITH_WRONG_FOREIGN_KEY =
            "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES other (id))";

    /**
     * An H2 database, which the MySQL dialect provider does not claim.
     */
    private static DataSource h2(String childTable) throws SQLException {
        var dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:mysql_dialect_resolution_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(PARENT_TABLE);
            statement.execute(OTHER_TABLE);
            statement.execute(childTable);
        }
        return dataSource;
    }

    @Test
    void theProductBlindLookupWouldPickThisModulesDialect() {
        // Guards the premise of the tests below: without consulting the database, resolution lands on MySQL.
        assertEquals(MySQLSqlDialect.class, Providers.getSqlDialect(StormConfig.defaults()).getClass(),
                "These tests only mean something while this module's dialect is the one a product-blind lookup "
                        + "returns. Adding another dialect to this module's test scope disarms them.");
    }

    @Test
    void resolvingForAnUnclaimedDatabaseFallsBackToTheDefaultDialect() throws SQLException {
        var dataSource = h2(CHILD_WITH_FOREIGN_KEY);

        assertEquals(DefaultSqlDialect.class,
                Providers.getSqlDialect(dataSource, StormConfig.defaults()).getClass());
    }

    /**
     * The dialect a template generates SQL with is the one resolved for its database, not the one the shared
     * {@link SqlTemplate#PS} carries.
     *
     * <p>The shared template resolves a dialect once, with no database in view. Building on it unchanged writes
     * MySQL syntax for a database that is not MySQL, while the statements are executed with the dialect the
     * template did resolve for itself, so the two halves of one query disagree about the database.</p>
     */
    @Test
    void sqlIsGeneratedWithTheDialectOfTheDatabaseTheTemplateIsBoundTo() throws SQLException {
        // The dialects share a hierarchy, so only the exact class distinguishes them.
        assertEquals(MySQLSqlDialect.class, SqlTemplate.PS.dialect().getClass(),
                "The shared template resolves this module's dialect, which is the state this test needs");

        var template = new PreparedStatementTemplateImpl(h2(CHILD_WITH_FOREIGN_KEY));

        assertEquals(DefaultSqlDialect.class, template.sqlTemplate().dialect().getClass());
    }

    @Test
    void validatorReadsForeignKeysOfAnUnclaimedDatabase() throws SQLException {
        var dataSource = h2(CHILD_WITH_FOREIGN_KEY);

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(Parent.class, Child.class));

        assertEquals(List.of(), errors);
    }

    /**
     * The discriminating case. A missing foreign key can only be reported by a dialect whose constraint discovery
     * works against this database; one that cannot read them has nothing to report.
     */
    @Test
    void validatorStillReportsAMissingForeignKeyOnAnUnclaimedDatabase() throws SQLException {
        var dataSource = h2(CHILD_WITHOUT_FOREIGN_KEY);

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(Parent.class, Child.class));

        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISSING),
                "Expected the absent foreign key to be reported, got: " + errors);
    }

    @Test
    void templateValidationReadsForeignKeysOfAnUnclaimedDatabase() throws SQLException {
        var orm = ORMTemplate.of(h2(CHILD_WITH_FOREIGN_KEY));

        assertEquals(List.of(), orm.validateSchema(List.of(Parent.class, Child.class)));
    }

    /**
     * The same discriminating case through {@link ORMTemplate#validateSchema}, which resolves its own dialect
     * rather than going through the {@link SchemaValidator} factories.
     *
     * <p>A misdirected foreign key rather than an absent one, because a foreign key that is merely missing is a
     * warning, and warnings are logged but kept out of the returned list unless validation is strict. A foreign key
     * pointing somewhere else is a hard error, and spotting it requires having read the constraint.</p>
     */
    @Test
    void templateValidationStillReportsAMisdirectedForeignKeyOnAnUnclaimedDatabase() throws SQLException {
        var orm = ORMTemplate.of(h2(CHILD_WITH_WRONG_FOREIGN_KEY));
        // The mismatch is provoked on purpose and read from the result; validation's log of it stays out of the
        // build log.
        var validationLogger = (Logger) LoggerFactory.getLogger("st.orm.validation");
        validationLogger.setAdditive(false);
        List<String> errors;
        try {
            errors = orm.validateSchema(List.of(Parent.class, Child.class));
        } finally {
            validationLogger.setAdditive(true);
        }

        assertFalse(errors.isEmpty(), "Expected the foreign key to 'other' to be reported as a mismatch");
    }

    /**
     * The {@link SchemaValidator} factory sees the same misdirected foreign key.
     */
    @Test
    void validatorReportsAMisdirectedForeignKeyOnAnUnclaimedDatabase() throws SQLException {
        var dataSource = h2(CHILD_WITH_WRONG_FOREIGN_KEY);

        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(Parent.class, Child.class));

        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.FOREIGN_KEY_MISMATCH),
                "Expected the foreign key to 'other' to be reported as a mismatch, got: " + errors);
    }
}
