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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbIgnore;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.UK;
import st.orm.core.model.polymorphic.JoinedAdoption;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlInterceptor;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.SchemaValidationError;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;
import st.orm.core.template.impl.SchemaValidator;
import st.orm.core.template.impl.SlowStatementLog;
import st.orm.core.template.impl.SlowStatementLogTestSupport;

/**
 * A slow statement names the index it lacks: a table none of whose indexes starts with a column the statement filters
 * or sorts it by, checked against the indexes the database reported when its schema was loaded. Each test runs on a
 * database of its own, since the loaded schema belongs to the data source.
 */
public class MissingIndexAdviceIntegrationTest {

    public record Customer(@PK Integer id, String lastName, @UK String email, LocalDate joinedOn)
            implements Entity<Integer> {
    }

    public record Purchase(@PK Integer id, @FK Customer customer, int amount) implements Entity<Integer> {
    }

    /** A type without a primary key, which no entity model can be built for. */
    public record Unmodeled(Integer id) implements Entity<Integer> {
    }

    @DbIgnore
    public record Ignored(@PK Integer id) implements Entity<Integer> {
    }

    @DbTable(value = "customer_archive", schema = "sales")
    public record ArchivedCustomer(@PK Integer id, String lastName) implements Entity<Integer> {
    }

    private static final Metamodel<ArchivedCustomer, String> ARCHIVED_LAST_NAME =
            Metamodel.of(ArchivedCustomer.class, "lastName");

    private static final Metamodel<Customer, String> LAST_NAME = Metamodel.of(Customer.class, "lastName");
    private static final Metamodel<Customer, String> EMAIL = Metamodel.of(Customer.class, "email");
    private static final Metamodel<Customer, LocalDate> JOINED_ON = Metamodel.of(Customer.class, "joinedOn");
    private static final Metamodel<Customer, Integer> ID = Metamodel.of(Customer.class, "id");

    private DataSource dataSource;

    @BeforeEach
    public void database() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:missing_index_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE customer (id INTEGER AUTO_INCREMENT PRIMARY KEY, last_name VARCHAR(255) NOT NULL,
                        email VARCHAR(255) NOT NULL, joined_on DATE NOT NULL)""");
            // A unique index, not a unique constraint, which unique key validation accepts as well.
            statement.execute("CREATE UNIQUE INDEX customer_email ON customer (email)");
            // No foreign key constraint, which would index the column on H2.
            statement.execute("""
                    CREATE TABLE purchase (id INTEGER AUTO_INCREMENT PRIMARY KEY, customer_id INTEGER NOT NULL,
                        amount INTEGER NOT NULL)""");
            statement.execute("INSERT INTO customer (last_name, email, joined_on) VALUES ('Davis', 'davis@example.com', DATE '2024-01-01')");
            statement.execute("INSERT INTO purchase (customer_id, amount) VALUES (1, 10)");
            // A view has no indexes of its own, so the loaded schema leaves it out.
            statement.execute("CREATE VIEW customer_view AS SELECT id, last_name FROM customer");
            statement.execute("CREATE SCHEMA sales");
            statement.execute("CREATE TABLE sales.customer_archive (id INTEGER PRIMARY KEY, last_name VARCHAR(255) NOT NULL)");
            // A joined inheritance hierarchy, whose columns spread over several tables.
            statement.execute("CREATE TABLE joined_animal (id INTEGER AUTO_INCREMENT PRIMARY KEY, dtype VARCHAR(50) NOT NULL, name VARCHAR(255))");
            statement.execute("CREATE TABLE joined_cat (id INTEGER NOT NULL PRIMARY KEY, indoor BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE joined_dog (id INTEGER NOT NULL PRIMARY KEY, weight INTEGER NOT NULL)");
            statement.execute("CREATE TABLE joined_adoption (id INTEGER AUTO_INCREMENT PRIMARY KEY, animal_id INTEGER NOT NULL)");
        }
        dataSource = h2;
        SlowStatementLogTestSupport.reset();
        SlowStatementLog.threshold(Duration.ofNanos(1));
    }

    @AfterEach
    public void noThreshold() {
        SlowStatementLog.threshold(null);
        SlowStatementLogTestSupport.reset();
    }

    /** Returns the missing-index advice on the slow lines the action logs. */
    private static List<String> missingIndexAdvice(Supplier<?> action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        try {
            action.get();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previous);
            appender.stop();
        }
        return AdviceCapture.advisoriesIn(appender.list.stream()
                        .map(ILoggingEvent::getFormattedMessage).toList()).stream()
                .filter(advisory -> advisory.contains("#missing-index"))
                .toList();
    }

    @Test
    public void aFilterOnAColumnNoIndexStartsWithIsAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(LAST_NAME, EQUALS, "Davis").getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("no index on customer starts with last_name, so the database reads the "
                + "table in full to find the matching rows.\nindex: customer(last_name) finds them directly where the "
                + "condition selects few of them."),
                advice.getFirst());
    }

    @Test
    public void withoutALoadedSchemaNothingIsAdvised() {
        var orm = ORMTemplate.of(dataSource);
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(LAST_NAME, EQUALS, "Davis").getResultList()));
    }

    @Test
    public void aFilterAnIndexServesIsNotAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(EMAIL, EQUALS, "davis@example.com").getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).getById(1)));
        // One served column is a path that avoids reading the table in full.
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(EMAIL, EQUALS, "davis@example.com")
                .where(LAST_NAME, EQUALS, "Davis").getResultList()));
    }

    @Test
    public void aToManyJoinIsAdvisedTheIndexOnTheJoinedTablesKey() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select().distinct()
                .innerJoin(Purchase.class).on(Customer.class).getResultList());
        assertTrue(advice.stream().anyMatch(line -> line.contains("no index on purchase starts with customer_id, so the "
                + "database reads the table in full to find the joined rows")), advice.toString());
    }

    @Test
    public void aSelectNamedThroughTheMetamodelIsHeldByTheGroupedIndex() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.selectFrom(Customer.class, String.class,
                        TemplateString.raw("MAX(\0)", EMAIL))
                .where(JOINED_ON, EQUALS, LocalDate.of(2024, 1, 1)).groupBy(LAST_NAME).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("customer(joined_on, last_name) finds the matching rows and reads them "
                + "already grouped, without a temporary table or sort\ncovering: with email appended it reads the index "
                + "alone, since it then holds every column the select reads"), advice.getFirst());
    }

    @Test
    public void aSelectWithAColumnStormDoesNotSeeHedgesOnWhatItReads() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.selectFrom(Customer.class, String.class,
                        TemplateString.raw("COALESCE(MAX(\0), MAX(c.email))", LAST_NAME))
                .where(JOINED_ON, EQUALS, LocalDate.of(2024, 1, 1)).groupBy(LAST_NAME).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("customer(joined_on, last_name) finds the matching rows and reads them "
                + "already grouped, without a temporary table or sort\ncovering: it reads the index alone where the "
                + "select reads no other columns"), advice.getFirst());
    }

    @Test
    public void aCountReadsNoColumnSoTheGroupedIndexHoldsEverythingTheSelectReads() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).selectCount()
                .where(JOINED_ON, EQUALS, LocalDate.of(2024, 1, 1)).groupBy(LAST_NAME).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("customer(joined_on, last_name) finds the matching rows and reads them "
                + "already grouped, without a temporary table or sort\ncovering: it reads the index alone, since it "
                + "holds every column the select reads"), advice.getFirst());
    }

    @Test
    public void anIndexedFilterIsAdvisedTheIndexThatContinuesItWithTheOrderOfALimitedResult() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX customer_last_name ON customer (last_name)");
        }
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select().where(LAST_NAME, EQUALS, "Davis")
                .orderBy(JOINED_ON, EMAIL).limit(10).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("no index on customer continues last_name with joined_on, email"),
                advice.getFirst());
        assertTrue(advice.getFirst().contains("\nindex: customer(last_name, joined_on, email), extending "
                + "CUSTOMER_LAST_NAME, reads the matching rows in order and stops at the limit"), advice.getFirst());
        // An order in mixed directions is left alone: an index serves it only when declared in the same directions.
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(LAST_NAME, EQUALS, "Davis").orderBy(JOINED_ON).orderByDescending(EMAIL).limit(10)
                .getResultList()));
    }

    @Test
    public void aGroupedReadIsAdvisedTheIndexThatReadsItInGroupOrder() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).selectCount()
                .where(JOINED_ON, EQUALS, LocalDate.of(2024, 1, 1)).groupBy(LAST_NAME).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("so the database reads the table in full and sorts the matching rows into "
                + "groups"), advice.getFirst());
        assertTrue(advice.getFirst().contains("\nindex: customer(joined_on, last_name) finds the matching rows and reads them "
                + "already grouped"), advice.getFirst());
    }

    @Test
    public void aRangeAndTheOrderOfALimitedResultSuggestAnIndexOnTheColumn() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(JOINED_ON, GREATER_THAN, LocalDate.of(2020, 1, 1))
                .orderBy(JOINED_ON).limit(10).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("\nindex: customer(joined_on)"), advice.getFirst());
        // An index serves a descending order as it does an ascending one.
        assertEquals(1, missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderByDescending(JOINED_ON).limit(10).getResultList()).size());
        var sorted = missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderBy(JOINED_ON).limit(10).getResultList());
        assertEquals(1, sorted.size(), sorted.toString());
        assertTrue(sorted.getFirst().contains("no index on customer starts with joined_on, so the database sorts every "
                + "row before it returns the first ones.\nindex: customer(joined_on) reads the rows in order and stops "
                + "at the limit."), sorted.getFirst());
    }

    @Test
    public void anOrderWithoutALimitIsNotAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        // Every row is read whatever the order, so no index would spare a read.
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderBy(JOINED_ON).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderBy(ID).limit(10).getResultList()));
    }

    @Test
    public void equalityAndRangeSuggestTheEqualityColumnFirst() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(JOINED_ON, GREATER_THAN, LocalDate.of(2020, 1, 1))
                .where(LAST_NAME, EQUALS, "Davis").getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("starts with last_name or joined_on"), advice.getFirst());
        assertTrue(advice.getFirst().contains("\nindex: customer(last_name, joined_on)"), advice.getFirst());
    }

    @Test
    public void theForeignKeyASemiJoinCorrelatesOnIsAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .semiJoin(Purchase.class).on(Customer.class)
                .where(LAST_NAME, EQUALS, "Davis").getResultList());
        assertTrue(advice.stream().anyMatch(line -> line.contains("\nindex: purchase(customer_id)")),
                advice.toString());
    }

    @Test
    public void aRepeatedSlowShapeIsAdvisedTheSame() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        Supplier<?> query = () -> orm.entity(Customer.class).select().where(LAST_NAME, EQUALS, "Davis").getResultList();
        var first = missingIndexAdvice(query);
        var second = missingIndexAdvice(query);
        assertEquals(first, second);
        assertEquals(1, second.size(), second.toString());
    }

    @Test
    public void validationLoadsTheSchemaAndAcceptsAUniqueIndexForAUniqueKey() {
        List<SchemaValidationError> errors = SchemaValidator.of(dataSource)
                .validate(List.of(Customer.class, Purchase.class));
        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING), errors.toString());
        var orm = ORMTemplate.of(dataSource);
        var advice = missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(LAST_NAME, EQUALS, "Davis").getResultList());
        assertEquals(1, advice.size(), advice.toString());
    }

    @Test
    public void anIndexAddedAfterTheLoadIsSeenOnTheNextLoad() throws SQLException {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        Supplier<?> query = () -> orm.entity(Customer.class).select().where(LAST_NAME, EQUALS, "Davis").getResultList();
        assertEquals(1, missingIndexAdvice(query).size());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX customer_last_name ON customer (last_name)");
        }
        orm.loadSchema();
        assertEquals(List.of(), missingIndexAdvice(query));
    }

    @Test
    public void aFilteredLoadKnowsTheDefaultSchema() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema(type -> type == Customer.class);
        assertEquals(1, missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(LAST_NAME, EQUALS, "Davis").getResultList()).size());
    }

    @Test
    public void aTemplateWithoutADataSourceCannotLoadTheSchema() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var orm = ORMTemplate.of(connection);
            var exception = assertThrows(PersistenceException.class,
                    orm::loadSchema);
            assertTrue(exception.getMessage().startsWith("Loading the schema requires a DataSource-backed template."),
                    exception.getMessage());
        }
    }

    @Test
    public void anOrderNoIndexCanServeIsNotAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        // A raw ordering names no column, and a ranking by a condition sorts by no column either.
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderBy(TemplateString.of("joined_on")).limit(10).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderFirst(it -> it.where(LAST_NAME, EQUALS, "Davis")).limit(10).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderFirst(it -> it.where(TemplateString.of("last_name = 'Davis'"))).limit(10).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .orderFirst(it -> it.where(TemplateString.raw("\0 = 'Davis'", LAST_NAME))).limit(10).getResultList()));
        // Distinct and grouped rows are all read before the limit applies.
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select().distinct()
                .orderBy(JOINED_ON).limit(10).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .groupBy(ID, LAST_NAME, EMAIL, JOINED_ON).orderBy(JOINED_ON).limit(10).getResultList()));
        // An order on a joined table's column cannot be read from the query's own table in order.
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Purchase.class).select()
                .orderBy(Metamodel.of(Purchase.class, "customer.joinedOn")).limit(10).getResultList()));
    }

    @Test
    public void aJoinedInheritanceHierarchyIsNotAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(JoinedAnimal.class).select()
                .where(Metamodel.of(JoinedAnimal.class, "id"), EQUALS, 1).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(JoinedAnimal.class).select()
                .orderBy(Metamodel.of(JoinedAnimal.class, "id")).limit(10).getResultList()));
        assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(JoinedAdoption.class).select()
                .semiJoin(JoinedAnimal.class).on(JoinedAdoption.class).getResultList()));
    }

    @Test
    public void aTableInASchemaOfItsOwnIsNamedWithItsSchema() {
        var orm = ORMTemplate.of(dataSource);
        // A schema the load did not read is unknown, so nothing is advised on its tables.
        orm.loadSchema();
        Supplier<?> query = () -> orm.entity(ArchivedCustomer.class).select()
                .where(ARCHIVED_LAST_NAME, EQUALS, "Davis").getResultList();
        assertEquals(List.of(), missingIndexAdvice(query));
        SchemaValidator.of(dataSource).load(List.of(ArchivedCustomer.class));
        var advice = missingIndexAdvice(query);
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("\nindex: sales.customer_archive(last_name)"), advice.getFirst());
    }

    @Test
    public void aTemplateWithoutADataSourceIsNotAdvised() throws SQLException {
        ORMTemplate.of(dataSource).loadSchema();
        try (var connection = dataSource.getConnection()) {
            var orm = ORMTemplate.of(connection);
            assertEquals(List.of(), missingIndexAdvice(() -> orm.entity(Customer.class).select()
                    .where(LAST_NAME, EQUALS, "Davis").getResultList()));
        }
    }

    @Test
    public void aStatementAnInterceptorReplacesIsNotAdvised() {
        var orm = ORMTemplate.of(dataSource);
        orm.loadSchema();
        // An interceptor may return a statement of its own making, which carries no demands.
        UnaryOperator<Sql> foreign = sql -> (Sql) Proxy.newProxyInstance(Sql.class.getClassLoader(),
                new Class<?>[] {Sql.class}, (proxy, method, args) -> {
                    try {
                        return method.invoke(sql, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var advice = missingIndexAdvice(() -> SqlInterceptor.intercept(foreign, () -> orm.entity(Customer.class)
                .select().where(LAST_NAME, EQUALS, "Davis").getResultList()));
        assertEquals(List.of(), advice);
    }

    @Test
    public void aSchemaThatCannotBeReadFailsTheLoad() {
        var unreachable = new DriverManagerDataSource("jdbc:h2:mem:missing_index_absent_" + UUID.randomUUID()
                + ";IFEXISTS=TRUE");
        var load = assertThrows(PersistenceException.class,
                () -> SchemaValidator.of(unreachable).load());
        assertEquals("Failed to load the database schema.", load.getMessage());
        var validate = assertThrows(PersistenceException.class,
                () -> SchemaValidator.of(unreachable).validate(List.of(Customer.class)));
        assertEquals("Failed to read database schema for validation.", validate.getMessage());
    }

    @Test
    public void aLoadSkipsTheTypesWithoutATableOfTheirOwn() {
        // A sealed hierarchy, a type that cannot be modeled, and an ignored type name no schema to read.
        SchemaValidator.of(dataSource).load(List.of(JoinedAnimal.class, Unmodeled.class, Ignored.class));
        var orm = ORMTemplate.of(dataSource);
        assertEquals(1, missingIndexAdvice(() -> orm.entity(Customer.class).select()
                .where(LAST_NAME, EQUALS, "Davis").getResultList()).size());
    }
}
