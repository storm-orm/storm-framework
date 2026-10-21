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
package st.orm.core.template.impl;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;
import st.orm.core.template.TemplateString;

/**
 * A slow delete names the foreign key of another table that references the table it deletes from without an index to
 * serve the database's check of it. H2 indexes every foreign key it enforces, so these tests declare no constraints
 * and report the foreign keys through a discovery query, as a database that leaves the index to the schema lists them.
 */
public class UnindexedForeignKeyAdviceTest {

    public record Customer(@PK Integer id, String lastName) implements Entity<Integer> {
    }

    private static final Metamodel<Customer, Integer> ID = Metamodel.of(Customer.class, "id");

    /** A foreign key column as the discovery query reports it; a {@code null} name for a database that omits it. */
    private record ForeignKey(String table, @Nullable String name, String column, String pkTable, String pkColumn) {
    }

    private DriverManagerDataSource dataSource;

    @BeforeEach
    public void database() throws SQLException {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:unindexed_foreign_key_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, last_name VARCHAR(255) NOT NULL)");
            statement.execute("""
                    CREATE TABLE purchase (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL,
                        created_by INTEGER, updated_by INTEGER)""");
            statement.execute("CREATE TABLE orders (id INTEGER NOT NULL, version INTEGER NOT NULL, PRIMARY KEY (id, version))");
            statement.execute("CREATE TABLE line_item (id INTEGER PRIMARY KEY, order_id INTEGER, order_version INTEGER)");
            statement.execute("INSERT INTO customer (id, last_name) VALUES (1, 'Davis'), (2, 'Franklin'), (3, 'Rodriquez')");
        }
        SlowStatementLogTestSupport.reset();
    }

    @AfterEach
    public void noThreshold() {
        SlowStatementLog.threshold(null);
        SlowStatementLogTestSupport.reset();
    }

    /**
     * Returns the catalog of the database with the given foreign keys, which the discovery query reports in place of
     * the database's own.
     */
    private IndexCatalog catalog(ForeignKey... foreignKeys) throws SQLException {
        return catalog(foreignKeyQuery(foreignKeys));
    }

    private IndexCatalog catalog(String foreignKeyQuery) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    new DatabaseSchema.Discovery(
                            SequenceDiscoveryStrategy.INFORMATION_SCHEMA,
                            ConstraintDiscoveryStrategy.INFORMATION_SCHEMA,
                            null, foreignKeyQuery, null, null));
            return IndexCatalog.of(Map.of("", schema));
        }
    }

    private static String foreignKeyQuery(ForeignKey... foreignKeys) {
        if (foreignKeys.length == 0) {
            return """
                    SELECT 'x' AS FK_TABLE, 'x' AS FK_NAME, 'x' AS FK_COLUMN, 'x' AS PK_TABLE, 'x' AS PK_COLUMN
                    WHERE CAST(? AS VARCHAR) IS NULL""";
        }
        return """
                SELECT * FROM (VALUES %s) AS fk(FK_TABLE, FK_NAME, FK_COLUMN, PK_TABLE, PK_COLUMN)
                WHERE CAST(? AS VARCHAR) IS NOT NULL""".formatted(Arrays.stream(foreignKeys)
                .map(key -> "('%s', %s, '%s', '%s', '%s')".formatted(key.table(),
                        key.name() == null ? "CAST(NULL AS VARCHAR)" : "'" + key.name() + "'",
                        key.column(), key.pkTable(), key.pkColumn()))
                .collect(joining(", ")));
    }

    private static final ForeignKey PURCHASE_CUSTOMER =
            new ForeignKey("PURCHASE", "FK_PURCHASE_CUSTOMER", "CUSTOMER_ID", "CUSTOMER", "ID");

    private static List<String> deleteAdvice(String table, IndexCatalog catalog) {
        var builder = new IndexDemands.Builder();
        builder.deleteFrom("", table);
        var demands = builder.build();
        assert demands != null;
        return demands.advise(catalog);
    }

    @Test
    public void aDeleteFromAReferencedTableNamesTheIndexTheCheckLacks() throws SQLException {
        var advice = deleteAdvice("customer", catalog(PURCHASE_CUSTOMER));
        assertEquals(List.of(Advice.UNINDEXED_FOREIGN_KEY.format("customer", "PURCHASE", "CUSTOMER_ID", "CUSTOMER_ID")),
                advice);
        assertTrue(advice.getFirst().startsWith("deleting from customer checks the foreign key PURCHASE(CUSTOMER_ID) "
                + "that references it, and no index on PURCHASE starts with CUSTOMER_ID, so the database reads "
                + "PURCHASE in full for every row it deletes; an index on PURCHASE(CUSTOMER_ID) serves the check."),
                advice.getFirst());
    }

    @Test
    public void aForeignKeyAnIndexStartsWithIsServed() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX purchase_customer ON purchase (customer_id, id)");
        }
        assertEquals(List.of(), deleteAdvice("customer", catalog(PURCHASE_CUSTOMER)));
    }

    @Test
    public void aTableNoForeignKeyReferencesIsNotAdvised() throws SQLException {
        assertEquals(List.of(), deleteAdvice("purchase", catalog(PURCHASE_CUSTOMER)));
        assertEquals(List.of(), deleteAdvice("customer", catalog()));
    }

    @Test
    public void aCompoundForeignKeyIsServedByAnIndexOnAnyOfItsColumns() throws SQLException {
        var orderId = new ForeignKey("LINE_ITEM", "FK_LINE_ITEM_ORDER", "ORDER_ID", "ORDERS", "ID");
        var orderVersion = new ForeignKey("LINE_ITEM", "FK_LINE_ITEM_ORDER", "ORDER_VERSION", "ORDERS", "VERSION");
        var advice = deleteAdvice("orders", catalog(orderId, orderVersion));
        assertEquals(1, advice.size(), advice::toString);
        assertTrue(advice.getFirst().contains("the foreign key LINE_ITEM(ORDER_ID, ORDER_VERSION) that references "
                + "it, and no index on LINE_ITEM starts with ORDER_ID or ORDER_VERSION"), advice.getFirst());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX line_item_version ON line_item (order_version)");
        }
        assertEquals(List.of(), deleteAdvice("orders", catalog(orderId, orderVersion)));
    }

    @Test
    public void eachNamedForeignKeyIsCheckedOnItsOwn() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX purchase_created_by ON purchase (created_by)");
        }
        var advice = deleteAdvice("customer", catalog(
                new ForeignKey("PURCHASE", "FK_PURCHASE_CREATED_BY", "CREATED_BY", "CUSTOMER", "ID"),
                new ForeignKey("PURCHASE", "FK_PURCHASE_UPDATED_BY", "UPDATED_BY", "CUSTOMER", "ID")));
        assertEquals(1, advice.size(), advice::toString);
        assertTrue(advice.getFirst().contains("PURCHASE(UPDATED_BY)"), advice.getFirst());
    }

    @Test
    public void unnamedForeignKeysToOneTableAreServedByAnIndexOnAnyOfThem() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX purchase_created_by ON purchase (created_by)");
        }
        // Without names the columns cannot be told apart from a compound key, so an index on one serves them all.
        assertEquals(List.of(), deleteAdvice("customer", catalog(
                new ForeignKey("PURCHASE", null, "CREATED_BY", "CUSTOMER", "ID"),
                new ForeignKey("PURCHASE", null, "UPDATED_BY", "CUSTOMER", "ID"))));
    }

    @Test
    public void foreignKeysThatWereNotReadAreNotAdvised() throws SQLException {
        // A query the database cannot answer leaves the foreign keys unknown.
        var catalog = catalog("SELECT * FROM no_such_catalog WHERE schema_name = ?");
        assertEquals(List.of(), deleteAdvice("customer", catalog));
    }

    @Test
    public void aTableTheCatalogDoesNotKnowIsNotAdvised() throws SQLException {
        assertEquals(List.of(), deleteAdvice("created_after_the_load", catalog(PURCHASE_CUSTOMER)));
    }

    /** Returns the unindexed-foreign-key advice on the slow lines the action logs. */
    private static List<String> slowAdvice(Supplier<?> action) {
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
        SlowStatementLog.threshold(Duration.ofNanos(1));
        try {
            action.get();
        } finally {
            SlowStatementLog.threshold(null);
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.list.stream()
                .flatMap(event -> event.getFormattedMessage().lines())
                .filter(line -> line.contains("#unindexed-foreign-key"))
                .toList();
    }

    @Test
    public void everyDeleteStormBuildsFromATableCarriesTheAdvice() throws SQLException {
        IndexCatalog.publish(dataSource, catalog(PURCHASE_CUSTOMER));
        var orm = ORMTemplate.of(dataSource);
        var customers = orm.entity(Customer.class);
        assertEquals(1, slowAdvice(() -> {
            customers.remove(new Customer(1, "Davis"));
            return null;
        }).size());
        assertEquals(1, slowAdvice(() -> customers.delete().where(ID, EQUALS, 2).executeUpdate()).size());
        assertEquals(1, slowAdvice(() -> orm.query(TemplateString.raw("DELETE FROM \0 WHERE id = 3", Customer.class))
                .executeUpdate()).size());
    }

    @Test
    public void aDeleteWrittenAsTextNamesNoTable() throws SQLException {
        IndexCatalog.publish(dataSource, catalog(PURCHASE_CUSTOMER));
        var orm = ORMTemplate.of(dataSource);
        assertEquals(List.of(), slowAdvice(() -> orm.query("DELETE FROM customer WHERE id = 1").executeUpdate()));
    }

    @Test
    public void aSelectOfAReferencedTableIsNotAdvised() throws SQLException {
        IndexCatalog.publish(dataSource, catalog(PURCHASE_CUSTOMER));
        var orm = ORMTemplate.of(dataSource);
        assertEquals(List.of(), slowAdvice(() -> orm.entity(Customer.class).select().getResultList()));
    }
}
