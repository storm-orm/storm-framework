package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.core.template.IntegrityFailure.Kind.CHECK;
import static st.orm.core.template.IntegrityFailure.Kind.FOREIGN_KEY;
import static st.orm.core.template.IntegrityFailure.Kind.LENGTH;
import static st.orm.core.template.IntegrityFailure.Kind.NOT_NULL;
import static st.orm.core.template.IntegrityFailure.Kind.NUMERIC_RANGE;
import static st.orm.core.template.IntegrityFailure.Kind.REFERENCED;
import static st.orm.core.template.IntegrityFailure.Kind.UNIQUE;
import static st.orm.spi.SqlOperation.DELETE;
import static st.orm.spi.SqlOperation.INSERT;
import static st.orm.spi.SqlOperation.UNDEFINED;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Data;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolationException;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.UK;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailureClassifier;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.TemplateString;
import st.orm.spi.SqlOperation;

/**
 * The resolution of a database's integrity violation to the entity's fields, and the checks of {@code validate},
 * against an in-memory database. The statements run through the default dialect, which classifies the kind from the
 * SQL state; the resolution of a name to a field is driven through a dialect that reads the names from the
 * database's messages, as a vendor dialect does.
 */
class IntegrityViolationsTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @DbTable("iv_parent")
    public record Parent(@PK(generation = NONE) Integer id, @UK String code, @Nullable BigDecimal amount,
                         @Nullable String note) implements Entity<Integer> {}

    @DbTable("iv_child")
    public record Child(@PK(generation = NONE) Integer id, @FK Ref<Parent> parent) implements Entity<Integer> {}

    /**
     * A record whose constructor refuses a blank code, so a stored row that holds one fails to load.
     */
    @DbTable("iv_parent")
    public record StrictParent(@PK(generation = NONE) Integer id, String code) implements Entity<Integer> {
        public StrictParent {
            if (code.isBlank()) {
                throw new IllegalArgumentException("Code must not be blank.");
            }
        }
    }

    /**
     * A dialect that reads the names from the database's messages, as a vendor dialect does.
     */
    static class NamingDialect extends DefaultSqlDialect {
        @Override
        public IntegrityFailureClassifier integrityFailureClassifier() {
            return NamingDialect::classify;
        }

        private static Optional<IntegrityFailure> classify(SQLException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            return switch (exception.getErrorCode()) {
                case 23505 -> Optional.of(message.contains("PRIMARY KEY")
                        ? IntegrityFailure.of(UNIQUE).ofPrimaryKey()
                        : IntegrityFailure.of(UNIQUE).withConstraint(between(message, "\"PUBLIC.", " ON")));
                case 23506 -> Optional.of(IntegrityFailure.of(FOREIGN_KEY).withConstraint(between(message, "\"", ":")));
                case 23503 -> Optional.of(IntegrityFailure.of(REFERENCED).withConstraint(between(message, "\"", ":")));
                case 23502 -> Optional.of(IntegrityFailure.of(NOT_NULL).withColumn(between(message, "column \"", "\"")));
                case 23513 -> Optional.of(IntegrityFailure.of(CHECK).withConstraint(between(message, "\"", ":")));
                case 22001 -> Optional.of(IntegrityFailure.of(LENGTH).withColumn(between(message, "column \"", " ")));
                case 22003 -> Optional.of(IntegrityFailure.of(NUMERIC_RANGE));
                default -> Optional.empty();
            };
        }

        private static @Nullable String between(String message, String start, String end) {
            int from = message.indexOf(start);
            if (from < 0) {
                return null;
            }
            from += start.length();
            int to = message.indexOf(end, from);
            return to < 0 ? null : message.substring(from, to);
        }
    }

    /**
     * A data source over the driver manager, as the test needs no pool, that counts the connections it hands out.
     */
    private static final class UrlDataSource extends DriverManagerDataSource {
        private final AtomicInteger connections = new AtomicInteger();

        UrlDataSource(String url) {
            super(url);
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            return super.getConnection();
        }

        AtomicInteger connections() {
            return connections;
        }
    }

    private DataSource dataSource;
    private ORMTemplate orm;
    private IntegrityViolations naming;

    @BeforeEach
    void createTables() throws SQLException {
        dataSource = new UrlDataSource("jdbc:h2:mem:integrity_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE iv_parent (id INTEGER NOT NULL, code VARCHAR(5) NOT NULL, amount DECIMAL(5, 2), "
                    + "note VARCHAR(10), CONSTRAINT iv_parent_pk PRIMARY KEY (id), "
                    + "CONSTRAINT iv_parent_code_uk UNIQUE (code), CONSTRAINT iv_parent_amount_ck CHECK (amount >= 0))");
            statement.execute("CREATE TABLE iv_child (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, "
                    + "CONSTRAINT iv_child_pk PRIMARY KEY (id), "
                    + "CONSTRAINT iv_child_parent_fk FOREIGN KEY (parent_id) REFERENCES iv_parent (id))");
        }
        orm = ORMTemplate.of(dataSource);
        orm.loadSchema(type -> false);
        naming = new IntegrityViolations(new NamingDialect(), dataSource, ModelBuilder.newInstance());
    }

    /**
     * Runs the statement and returns the error the database reports for it.
     */
    private SQLException error(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("The statement was not refused: " + sql);
        } catch (SQLException e) {
            return e;
        }
    }

    private IntegrityViolation resolve(String sql, SqlOperation operation, Class<? extends Data> type)
            throws SQLException {
        var error = error(sql);
        var translated = assertInstanceOf(IntegrityViolationException.class, naming.translate(error, operation, type));
        assertSame(error, translated.getCause());
        return translated.violations().getFirst();
    }

    private static List<String> paths(IntegrityViolation violation) {
        return violation.fields().stream().map(Metamodel::fieldPath).toList();
    }

    @Test
    void aNamedUniqueConstraintResolvesToItsField() throws SQLException {
        orm.entity(Parent.class).insert(new Parent(1, "A", null, null));
        var violation = resolve("INSERT INTO iv_parent (id, code) VALUES (2, 'A')", INSERT, Parent.class);
        assertInstanceOf(IntegrityViolation.Unique.class, violation);
        assertEquals(List.of("code"), paths(violation));
        assertTrue(violation.on(Metamodel.of(Parent.class, "code")));
        assertEquals(Parent.class, violation.type());
        assertEquals("iv_parent", violation.table().toLowerCase());
        assertTrue(violation.constraint().toLowerCase().startsWith("iv_parent_code_uk"));
    }

    @Test
    void aPrimaryKeyViolationResolvesToTheKey() throws SQLException {
        orm.entity(Parent.class).insert(new Parent(1, "A", null, null));
        var violation = resolve("INSERT INTO iv_parent (id, code) VALUES (1, 'B')", INSERT, Parent.class);
        assertInstanceOf(IntegrityViolation.Unique.class, violation);
        assertEquals(List.of("id"), paths(violation));
    }

    @Test
    void aNamedForeignKeyResolvesToTheReferencingField() throws SQLException {
        var violation = resolve("INSERT INTO iv_child (id, parent_id) VALUES (1, 999)", INSERT, Child.class);
        assertInstanceOf(IntegrityViolation.ForeignKey.class, violation);
        assertEquals(List.of("parent"), paths(violation));
        assertEquals("iv_child", violation.table().toLowerCase());
        assertTrue(violation.constraint().equalsIgnoreCase("iv_child_parent_fk"));
    }

    @Test
    void aReferencedRowResolvesToTheReferencingTable() throws SQLException {
        orm.entity(Parent.class).insert(new Parent(1, "A", null, null));
        orm.entity(Child.class).insert(new Child(1, orm.ref(Parent.class, 1)));
        var violation = resolve("DELETE FROM iv_parent WHERE id = 1", DELETE, Parent.class);
        assertInstanceOf(IntegrityViolation.Referenced.class, violation);
        assertEquals(Parent.class, violation.type());
        assertEquals("iv_child", violation.table().toLowerCase());
        assertTrue(violation.fields().isEmpty());
    }

    @Test
    void aNamedColumnResolvesToItsField() throws SQLException {
        var nullValue = resolve("INSERT INTO iv_parent (id, code) VALUES (1, NULL)", INSERT, Parent.class);
        assertInstanceOf(IntegrityViolation.NotNull.class, nullValue);
        assertEquals(List.of("code"), paths(nullValue));
        var tooLong = assertInstanceOf(IntegrityViolation.Length.class,
                resolve("INSERT INTO iv_parent (id, code) VALUES (1, 'ABCDEF')", INSERT, Parent.class));
        assertEquals(List.of("code"), paths(tooLong));
        assertNull(tooLong.maxLength(), "a database-reported violation carries no limit");
    }

    @Test
    void aNamedCheckConstraintIsReportedWithoutFields() throws SQLException {
        var violation = resolve("INSERT INTO iv_parent (id, code, amount) VALUES (1, 'A', -1)", INSERT, Parent.class);
        assertInstanceOf(IntegrityViolation.Check.class, violation);
        assertTrue(violation.constraint().equalsIgnoreCase("iv_parent_amount_ck"));
        assertTrue(violation.fields().isEmpty());
        assertEquals("iv_parent", violation.table().toLowerCase());
    }

    @Test
    void aForeignKeyViolationNamingAnotherTableIsAReferencedRow() throws SQLException {
        // A database that names the referencing table on a delete, under the kind of a foreign key violation.
        var namingTheChild = new IntegrityViolations(new DefaultSqlDialect() {
            @Override
            public IntegrityFailureClassifier integrityFailureClassifier() {
                return exception -> Optional.of(IntegrityFailure.of(FOREIGN_KEY).withTable("iv_child"));
            }
        }, dataSource, ModelBuilder.newInstance());
        orm.entity(Parent.class).insert(new Parent(1, "A", null, null));
        orm.entity(Child.class).insert(new Child(1, orm.ref(Parent.class, 1)));
        var error = error("DELETE FROM iv_parent WHERE id = 1");
        var violation = assertInstanceOf(IntegrityViolationException.class, namingTheChild.translate(error, UNDEFINED, Parent.class))
                .violations().getFirst();
        assertInstanceOf(IntegrityViolation.Referenced.class, violation);
        assertEquals("iv_child", violation.table());
        assertEquals(Parent.class, violation.type());
    }

    /** A type the model builder refuses: no record, no sealed entity. */
    interface NotAnEntity extends Data {}

    @Test
    void aTypeWithoutAModelResolvesNoField() {
        var error = new SQLException("duplicate", "23505", 23505);
        var violation = assertInstanceOf(IntegrityViolationException.class, naming.translate(error, INSERT, NotAnEntity.class))
                .violations().getFirst();
        assertInstanceOf(IntegrityViolation.Unique.class, violation);
        assertEquals(NotAnEntity.class, violation.type());
        assertTrue(violation.fields().isEmpty());
        assertNull(violation.table());
    }

    @Test
    void aFailureCarriesWhatTheErrorNames() {
        var failure = IntegrityFailure.of(UNIQUE).withConstraint("uk").withTable("t").withColumn("c");
        assertEquals("uk", failure.constraint());
        assertEquals("t", failure.table());
        assertEquals(List.of("c"), failure.columns());
        assertFalse(failure.primaryKey());
        assertTrue(failure.withColumn(null).columns().isEmpty());
        assertTrue(failure.ofPrimaryKey().primaryKey());
    }

    @Test
    void aConstraintTheSchemaDoesNotKnowKeepsItsNameAndResolvesNoField() {
        var error = new SQLException("Unique index or primary key violation: \"PUBLIC.NOPE ON PUBLIC.IV_PARENT(CODE)\"", "23505", 23505);
        var violation = assertInstanceOf(IntegrityViolationException.class, naming.translate(error, INSERT, Parent.class))
                .violations().getFirst();
        assertInstanceOf(IntegrityViolation.Unique.class, violation);
        assertEquals("NOPE", violation.constraint());
        assertTrue(violation.fields().isEmpty());
        assertEquals("iv_parent", violation.table());
    }

    @Test
    void aViolationWithoutAnEntityKeepsTheNamesTheErrorCarries() throws SQLException {
        var error = error("INSERT INTO iv_parent (id, code) VALUES (1, NULL)");
        var translated = assertInstanceOf(IntegrityViolationException.class, naming.translate(error, UNDEFINED, null));
        var violation = translated.violations().getFirst();
        assertInstanceOf(IntegrityViolation.NotNull.class, violation);
        assertNull(violation.type());
        assertTrue(violation.fields().isEmpty());
        assertNull(violation.table());
        assertTrue(translated.getMessage().startsWith("Write refused by the database: Null value."), translated.getMessage());
    }

    @Test
    void aViolationWithoutADataSourceResolvesNoField() throws SQLException {
        orm.entity(Parent.class).insert(new Parent(1, "A", null, null));
        var error = error("INSERT INTO iv_parent (id, code) VALUES (2, 'A')");
        var withoutDataSource = new IntegrityViolations(new NamingDialect(), null, ModelBuilder.newInstance());
        var violation = assertInstanceOf(IntegrityViolationException.class, withoutDataSource.translate(error, INSERT, Parent.class))
                .violations().getFirst();
        assertInstanceOf(IntegrityViolation.Unique.class, violation);
        assertNotNull(violation.constraint(), "the name the error carries");
        assertTrue(violation.fields().isEmpty(), "no data source to read the schema over");
        assertEquals("iv_parent", violation.table());
    }

    @Test
    void theCommitResolutionClassifiesWithoutAnEntity() {
        var violations = IntegrityViolations.of(new NamingDialect());
        var error = new SQLException("Referential integrity constraint violation: \"IV_CHILD_PARENT_FK: PUBLIC.IV_CHILD FOREIGN KEY(PARENT_ID) REFERENCES PUBLIC.IV_PARENT(ID)\"", "23506", 23506);
        var translated = assertInstanceOf(IntegrityViolationException.class, violations.translate(error, UNDEFINED, null));
        var violation = translated.violations().getFirst();
        assertInstanceOf(IntegrityViolation.ForeignKey.class, violation);
        assertEquals("IV_CHILD_PARENT_FK", violation.constraint());
        assertTrue(violation.fields().isEmpty());
        assertSame(error, translated.getCause());
        var other = new SQLException("syntax", "42000", 42000);
        assertSame(other, violations.translate(other, UNDEFINED, null));
        assertSame(translated, violations.translate(translated, UNDEFINED, null));
        var wrapped = new PersistenceException(new IllegalStateException(error));
        assertInstanceOf(IntegrityViolationException.class, violations.translate(wrapped, UNDEFINED, null),
                "the chain is walked through the causes");
    }

    @Test
    void aFailingClassificationNeverMasksTheError() {
        var failing = new IntegrityViolations(new DefaultSqlDialect() {
            @Override
            public IntegrityFailureClassifier integrityFailureClassifier() {
                return exception -> {
                    throw new IllegalStateException("classification failed");
                };
            }
        }, dataSource, ModelBuilder.newInstance());
        var error = new SQLException("duplicate", "23505", 23505);
        assertSame(error, failing.translate(error, INSERT, Parent.class));
        assertEquals(1, error.getSuppressed().length);
        assertEquals("classification failed", error.getSuppressed()[0].getMessage());
    }

    @Test
    void aStatementTheDefaultDialectClassifiesIsAnIntegrityViolation() {
        var parents = orm.entity(Parent.class);
        parents.insert(new Parent(1, "A", null, null));
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents.insert(new Parent(2, "A", null, null)));
        var violation = thrown.violations().getFirst();
        assertInstanceOf(IntegrityViolation.Unique.class, violation);
        assertTrue(violation.fields().isEmpty(), "the default dialect names nothing");
        assertEquals("iv_parent", violation.table());
        assertEquals(Parent.class, violation.type());
        assertInstanceOf(SQLException.class, thrown.getCause());
        assertTrue(thrown.getMessage().startsWith("Insert refused by the database: Duplicate value of Parent in iv_parent."),
                thrown.getMessage());
        assertEquals(0, thrown.violations(Metamodel.of(Parent.class, "code")).size());
    }

    @Test
    void aForeignKeyViolationOnADeleteIsAReferencedRowWhereTheDialectCannotTell() {
        orm.entity(Parent.class).insert(new Parent(1, "A", null, null));
        orm.entity(Child.class).insert(new Child(1, orm.ref(Parent.class, 1)));
        var thrown = assertThrows(IntegrityViolationException.class, () -> orm.entity(Parent.class).removeById(1));
        var violation = thrown.violations().getFirst();
        assertInstanceOf(IntegrityViolation.Referenced.class, violation);
        assertNull(violation.constraint());
        assertNull(violation.table());
        assertTrue(thrown.getMessage().startsWith("Delete refused by the database: Row still referenced"), thrown.getMessage());
    }

    @Test
    void aNullInANonNullableFieldIsRefusedBeforeTheStatement() {
        var parents = orm.entity(Parent.class);
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents.insert(new Parent(1, null, null, null)));
        var violation = thrown.violations().getFirst();
        assertInstanceOf(IntegrityViolation.NotNull.class, violation);
        assertEquals(List.of("code"), paths(violation));
        assertEquals("iv_parent", violation.table());
        assertNull(thrown.getCause());
        assertTrue(thrown.getMessage().startsWith("Cannot write NULL to non-nullable column 'code' of Parent."), thrown.getMessage());
        assertEquals(0, parents.count());
    }

    @Test
    void aNullInANonNullableFieldOfABatchIsRefused() {
        var parents = orm.entity(Parent.class);
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents.insert(List.of(new Parent(1, "A", null, null), new Parent(2, null, null, null))));
        assertInstanceOf(IntegrityViolation.NotNull.class, thrown.violations().getFirst());
    }

    @Test
    void aNullInANonNullableFieldOfAnUpdateIsRefused() {
        var parents = orm.entity(Parent.class);
        parents.insert(new Parent(1, "A", null, null));
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents.update(new Parent(1, null, null, null)));
        assertInstanceOf(IntegrityViolation.NotNull.class, thrown.violations().getFirst());
        assertEquals("A", parents.getById(1).code());
    }

    @Test
    void aViolationPassesThroughTheExceptionMapper() {
        var mapped = ORMTemplate.builder(dataSource)
                .exceptionMapper((cause, context) -> new IllegalStateException("mapped " + context.operation(), cause))
                .build();
        var parents = mapped.entity(Parent.class);
        parents.insert(new Parent(1, "A", null, null));
        var duplicate = assertThrows(IllegalStateException.class, () -> parents.insert(new Parent(2, "A", null, null)));
        assertEquals("mapped INSERT", duplicate.getMessage());
        assertInstanceOf(IntegrityViolationException.class, duplicate.getCause());
        var nullValue = assertThrows(IllegalStateException.class, () -> parents.insert(new Parent(2, null, null, null)));
        assertInstanceOf(IntegrityViolationException.class, nullValue.getCause());
        var batch = assertThrows(IllegalStateException.class,
                () -> parents.insert(List.of(new Parent(2, "B", null, null), new Parent(3, null, null, null))));
        assertInstanceOf(IntegrityViolationException.class, batch.getCause());
        var checked = assertThrows(IllegalStateException.class, () -> parents.validateOrThrow(new Parent(2, null, null, null)));
        assertInstanceOf(IntegrityViolationException.class, checked.getCause());
    }

    @Test
    void aStatementWithoutAnEntityIsAnIntegrityViolation() {
        var thrown = assertThrows(IntegrityViolationException.class, () -> orm.query(TemplateString.of(
                "INSERT INTO iv_parent (id, code) VALUES (1, NULL)")).executeUpdate());
        var violation = thrown.violations().getFirst();
        assertInstanceOf(IntegrityViolation.NotNull.class, violation);
        assertNull(violation.type());
        assertTrue(violation.fields().isEmpty());
    }

    @Test
    void validateReportsEveryViolation() {
        var parents = orm.entity(Parent.class);
        var violations = parents.validate(new Parent(1, null, new BigDecimal("1234.5"), "0123456789A"));
        assertEquals(3, violations.size(), violations::toString);
        assertInstanceOf(IntegrityViolation.NotNull.class, violations.get(0));
        assertEquals(List.of("code"), paths(violations.get(0)));
        var range = assertInstanceOf(IntegrityViolation.NumericRange.class, violations.get(1));
        assertEquals(List.of("amount"), paths(range));
        assertEquals(5, range.precision());
        assertEquals(2, range.scale());
        var tooLong = assertInstanceOf(IntegrityViolation.Length.class, violations.get(2));
        assertEquals(List.of("note"), paths(tooLong));
        assertEquals(10, tooLong.maxLength());
        assertNull(tooLong.constraint());
        assertEquals("iv_parent", violations.get(2).table());
        assertEquals(0, parents.count());
    }

    @Test
    void validateAcceptsWhatFits() {
        var parents = orm.entity(Parent.class);
        assertEquals(List.of(), parents.validate(new Parent(1, "ABCDE", new BigDecimal("999.99"), "0123456789")));
        assertEquals(List.of(), parents.validate(new Parent(1, "A", new BigDecimal("0.005"), null)), "extra decimals round");
        assertEquals(List.of(), parents.validate(new Parent(1, "A", BigDecimal.ZERO, null)));
        assertEquals(List.of(), parents.validate(new Parent(1, "A", new BigDecimal("-999.99"), null)), "the check is the database's");
        assertEquals(List.of(), parents.validate(new Parent(1, "😀".repeat(5), null, null)), "five code points");
        assertEquals(1, parents.validate(new Parent(1, "😀".repeat(6), null, null)).size());
    }

    @Test
    void validateRunsTheNullCheckAloneWhereTheDatabaseStoresAnyValue() {
        var lenient = new DefaultSqlDialect() {
            @Override
            public boolean enforcesColumnSizes() {
                return false;
            }
        };
        var violations = EntityValidator.validate(orm.model(Parent.class),
                new Parent(1, null, new BigDecimal("1234.5"), "0123456789A"), dataSource, lenient);
        assertEquals(List.of(IntegrityViolation.NotNull.class), violations.stream().map(Object::getClass).toList());
    }

    @Test
    void validateRefusesWithoutALoadedSchema() {
        var unloaded = new UrlDataSource("jdbc:h2:mem:unloaded_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        var entity = new Parent(1, null, null, "0123456789A");
        for (var source : Arrays.asList(unloaded, null)) {
            var thrown = assertThrows(PersistenceException.class,
                    () -> EntityValidator.validate(orm.model(Parent.class), entity, source, new DefaultSqlDialect()));
            assertFalse(thrown instanceof IntegrityViolationException);
            assertTrue(thrown.getMessage().startsWith("Cannot validate Parent: the database schema is not loaded."), thrown.getMessage());
        }
        assertEquals(0, unloaded.connections().get(), "a check reads no metadata");
    }

    @Test
    void onlyALoadPublishesTheSchema() throws SQLException {
        var source = new UrlDataSource("jdbc:h2:mem:loaded_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE iv_parent (id INTEGER PRIMARY KEY, code VARCHAR(5) NOT NULL, amount DECIMAL(5, 2), note VARCHAR(10), CONSTRAINT iv_parent_code_uk UNIQUE (code))");
        }
        int connections = source.connections().get();
        var resolver = new IntegrityViolations(new NamingDialect(), source, ModelBuilder.newInstance());
        var error = new SQLException("Unique index or primary key violation: \"PUBLIC.IV_PARENT_CODE_UK ON PUBLIC.IV_PARENT(CODE)\"", "23505", 23505);
        var unresolved = assertInstanceOf(IntegrityViolationException.class, resolver.translate(error, INSERT, Parent.class));
        assertTrue(unresolved.violations().getFirst().fields().isEmpty(), "no field without a loaded schema");
        assertEquals("IV_PARENT_CODE_UK", unresolved.violations().getFirst().constraint());
        assertEquals(connections, source.connections().get(), "a failed write reads no metadata");
        assertNull(SchemaCache.cached(source, ""));

        SchemaValidator.of(source).validate(List.of(Parent.class));
        var loaded = SchemaCache.cached(source, "");
        assertNotNull(loaded, "schema validation publishes what it read");
        assertEquals(2, loaded.getColumn("iv_parent", "amount").orElseThrow().decimalDigits());
        connections = source.connections().get();
        var resolved = assertInstanceOf(IntegrityViolationException.class, resolver.translate(error, INSERT, Parent.class));
        assertEquals(List.of("code"), paths(resolved.violations().getFirst()));
        assertEquals(connections, source.connections().get(), "resolving reads what the load kept");
    }

    @Test
    void onlyAConstraintTheLoadedSchemaDoesNotKnowIsLogged() throws SQLException {
        var logger = (Logger) LoggerFactory.getLogger("st.orm.validation");
        var appender = new ListAppender<ILoggingEvent>();
        var level = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            resolve("INSERT INTO iv_parent (id, code, amount) VALUES (1, 'A', -1)", INSERT, Parent.class);
            assertEquals(List.of(), appender.list, "a check constraint names no field, by its kind");
            var unknown = new SQLException("Unique index or primary key violation: \"PUBLIC.NOPE ON PUBLIC.IV_PARENT(CODE)\"", "23505", 23505);
            naming.translate(unknown, INSERT, Parent.class);
            assertEquals(1, appender.list.size());
            assertEquals(Level.DEBUG, appender.list.getFirst().getLevel());
            assertTrue(appender.list.getFirst().getFormattedMessage().contains("NOPE"), appender.list.toString());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }
    }

    @Test
    void theStandardClassifierReadsTheStatesTheStandardAssigns() {
        var kinds = Map.of("23505", UNIQUE, "23503", FOREIGN_KEY, "23502", NOT_NULL, "23514", CHECK, "22001", LENGTH,
                "22003", NUMERIC_RANGE);
        kinds.forEach((state, kind) -> assertEquals(kind,
                IntegrityFailureClassifier.STANDARD.classify(new SQLException("refused", state)).orElseThrow().kind()));
        assertTrue(IntegrityFailureClassifier.STANDARD.classify(new SQLException("syntax", "42000")).isEmpty());
        assertTrue(IntegrityFailureClassifier.STANDARD.classify(new SQLException("no state")).isEmpty());
    }

    @Test
    void aValueOutOfRangeTheDatabaseReportsCarriesNoLimit() {
        var error = new SQLException("Numeric value out of range", "22003", 22003);
        var violation = assertInstanceOf(IntegrityViolation.NumericRange.class,
                assertInstanceOf(IntegrityViolationException.class, naming.translate(error, INSERT, Parent.class))
                        .violations().getFirst());
        assertNull(violation.precision());
        assertNull(violation.scale());
    }

    @Test
    void validateRunsOnATemplateThatHoldsNoDataSource() {
        var lenient = new DefaultSqlDialect() {
            @Override
            public boolean enforcesColumnSizes() {
                return false;
            }
        };
        var template = (QueryTemplate) Proxy.newProxyInstance(QueryTemplate.class.getClassLoader(),
                new Class<?>[]{QueryTemplate.class}, (proxy, method, arguments) -> lenient);
        var violations = EntityValidator.validate(orm.model(Parent.class), new Parent(1, null, null, null), template);
        assertEquals(List.of(IntegrityViolation.NotNull.class), violations.stream().map(Object::getClass).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateRefusesAModelThatIsNotStormsOwn() {
        var model = (Model<Parent, Integer>) Proxy.newProxyInstance(Model.class.getClassLoader(),
                new Class<?>[]{Model.class}, (proxy, method, arguments) -> Parent.class);
        var thrown = assertThrows(PersistenceException.class, () -> EntityValidator.validate(model,
                new Parent(1, "A", null, null), dataSource, new DefaultSqlDialect()));
        assertEquals("Cannot validate Parent: the model is not Storm's own.", thrown.getMessage());
    }

    @Test
    void validateOrThrowThrowsEveryViolationAndReturnsForAFit() {
        var parents = orm.entity(Parent.class);
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents.validateOrThrow(new Parent(1, "ABCDEF", new BigDecimal("1234.5"), null)));
        assertEquals(2, thrown.violations().size());
        assertNull(thrown.getCause());
        assertTrue(thrown.getMessage().startsWith("Parent does not fit its table: Value too long for code of Parent in iv_parent: at most 5 characters."),
                thrown.getMessage());
        parents.validateOrThrow(new Parent(1, "A", null, null));
    }

    @Test
    void aRowAConstructorRejectsNamesItsPrimaryKey() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO iv_parent (id, code) VALUES (42, ' ')");
        }
        var thrown = assertThrows(PersistenceException.class, () -> orm.entity(StrictParent.class).getById(42));
        var failure = assertInstanceOf(SqlTemplateException.class, thrown.getCause());
        assertEquals("Failed to create a new instance of StrictParent for primary key 42.", failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void aViolationDescribesItself() {
        var violation = new IntegrityViolation.Length("iv_parent", Parent.class,
                List.of(Metamodel.of(Parent.class, "code")), 5);
        assertEquals("Value too long for code of Parent in iv_parent: at most 5 characters.", IntegrityViolations.describe(violation));
        var range = new IntegrityViolation.NumericRange("iv_parent", Parent.class,
                List.of(Metamodel.of(Parent.class, "amount")), 5, 2);
        assertEquals("Value out of range for amount of Parent in iv_parent: at most 3 digits before the decimal point.", IntegrityViolations.describe(range));
        var referenced = new IntegrityViolation.Referenced("iv_child_parent_fk", "iv_child", Parent.class);
        assertEquals("Row still referenced of Parent by iv_child (iv_child_parent_fk).", IntegrityViolations.describe(referenced));
        assertEquals("Duplicate value.", IntegrityViolations.describe(new IntegrityViolation.Unique(null, null, null, List.of())));
        assertEquals("Reference to a missing row.", IntegrityViolations.describe(new IntegrityViolation.ForeignKey(null, null, null, List.of())));
        assertEquals("Value rejected by a check.", IntegrityViolations.describe(new IntegrityViolation.Check(null, null, null)));
        assertEquals("Value out of range: at most 5 digits before the decimal point.", IntegrityViolations.describe(
                new IntegrityViolation.NumericRange(null, null, List.of(), 5, null)));
        assertEquals("Null value.", IntegrityViolations.describe(new IntegrityViolation.NotNull(null, null, List.of())));
        assertTrue(violation.on(Metamodel.of(Parent.class, "code")));
        assertEquals(violation, new IntegrityViolation.Length("iv_parent", Parent.class,
                List.of(Metamodel.of(Parent.class, "code")), 5));
        assertThrows(IllegalArgumentException.class, () -> new IntegrityViolationException("none", List.of()));
    }
}
