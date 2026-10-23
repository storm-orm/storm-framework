package st.orm.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.QueryColumn;
import st.orm.StormConfig;
import st.orm.UK;
import st.orm.core.spi.Providers;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.DatabaseSchema;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;
import st.orm.core.template.impl.SchemaValidator;
import st.orm.core.template.impl.SlowStatementLog;

/**
 * The schema mismatches every dialect is expected to report.
 *
 * <p>None of these assertions depends on the SQL a dialect generates, so unlike
 * {@link AbstractEntityRepositoryConformanceTest} this suite pins no statements. A dialect runs it by extending it and
 * annotating the subclass with {@code @StormTest}.
 *
 * <p>The entities are declared here rather than reused from {@code st.orm.tck.model} because they exist to be wrong in
 * specific ways: a table that is absent, a column that is absent, a type that cannot hold the column, a non-null
 * component over a nullable column, a key that does not match. Reusing the model would make them right.
 */
public abstract class AbstractSchemaValidatorConformanceTest {

    protected DataSource dataSource;

    @BeforeEach
    final void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The statement that creates a unique index on an expression, {@code lower(a)}, on {@code index_probe}, named
     * {@code index_probe_lower}; {@code null} for a database without expression indexes.
     */
    protected @Nullable String expressionIndexDdl() {
        return null;
    }

    /**
     * Whether the database creates an index for every foreign key it enforces, so that a delete never checks a
     * referencing table without one.
     */
    protected boolean indexesForeignKeys() {
        return false;
    }

    /** Whether the dialect has sequences, and so reports {@code SEQUENCE_NOT_FOUND} at all. */
    protected boolean supportsSequences() {
        return true;
    }

    /**
     * The statement that creates {@code generated_probe} with an integer primary key {@code id}, a {@code NOT NULL}
     * text column {@code name}, and a {@code NOT NULL} text column {@code name_folded} the database generates from
     * {@code name}; {@code null} for a database without generated columns.
     */
    protected @Nullable String generatedColumnProbeDdl() {
        return null;
    }

    /** {@code generated_probe} with its generated column declared as a query column. */
    @DbTable("generated_probe")
    @QueryColumn(field = "nameFolded", type = String.class)
    public record GeneratedProbe(@PK Integer id, String name) implements Entity<Integer> {}

    /** {@code generated_probe} with its generated column left unmapped. */
    @DbTable("generated_probe")
    public record GeneratedProbeUnmapped(@PK Integer id, String name) implements Entity<Integer> {}

    public record Vet(
            @PK Integer id,
            @Nullable String firstName,
            @Nullable String lastName
    ) implements Entity<Integer> {}

    public record Address(
            @Nullable String address,
            @Nullable String city
    ) {}

    public record Owner(
            @PK Integer id,
            @Nullable String firstName,
            @Nullable String lastName,
            Address address,
            @Nullable String telephone,
            @Nullable Integer version
    ) implements Entity<Integer> {}

    /** A table that {@code fk_probe_child.parent_id} references. */
    @DbTable("fk_probe_parent")
    public record FkProbeParent(@PK(generation = NONE) Integer id) implements Entity<Integer> {}

    /** A unique key on {@code c}, which a unique index enforces. */
    @DbTable("index_probe")
    public record IndexProbeUniqueC(
            @PK Integer id,
            @Nullable String a,
            @Nullable String b,
            @UK @Nullable Integer c
    ) implements Entity<Integer> {}

    /** A unique key on {@code a}, which a unique index on {@code lower(a)} does not enforce. */
    @DbTable("index_probe")
    public record IndexProbeUniqueA(
            @PK Integer id,
            @UK @Nullable String a,
            @Nullable String b,
            @Nullable Integer c
    ) implements Entity<Integer> {}

    public record MissingTableEntity(
            @PK Integer id,
            String value
    ) implements Entity<Integer> {}

    @DbTable("vet")
    public record MissingColumnEntity(
            @PK Integer id,
            String firstName,
            String nonExistentColumn
    ) implements Entity<Integer> {}

    @DbTable("vet")
    public record TypeMismatchEntity(
            @PK Integer id,
            LocalDate firstName
    ) implements Entity<Integer> {}

    @DbTable("pet_type")
    public record NullabilityMismatchEntity(
            @PK Integer id,
            String name,
            String description
    ) implements Entity<Integer> {}

    @DbTable("vet_specialty")
    public record PrimaryKeyMismatchEntity(
            @PK(generation = NONE) Integer vetId,
            Integer specialtyId
    ) implements Entity<Integer> {}

    @DbTable("vet")
    public record SequenceExistsEntity(
            @PK(generation = SEQUENCE, sequence = "pet_id_seq") Integer id,
            String firstName,
            String lastName
    ) implements Entity<Integer> {}

    @DbTable("vet")
    public record SequenceNotFoundEntity(
            @PK(generation = SEQUENCE, sequence = "nonexistent_seq") Integer id,
            String firstName,
            String lastName
    ) implements Entity<Integer> {}

    @Test
    public void testValidEntitiesPass() {
        var errors = SchemaValidator.of(dataSource).validate(List.of(Vet.class, Owner.class));
        assertTrue(errors.isEmpty(), () -> "Expected no errors but got: " + errors);
    }

    @Test
    public void testTableNotFound() {
        var errors = SchemaValidator.of(dataSource).validate(List.of(MissingTableEntity.class));
        assertEquals(1, errors.size());
        assertEquals(ErrorKind.TABLE_NOT_FOUND, errors.getFirst().kind());
        assertTrue(errors.getFirst().message().contains("missing_table_entity"));
    }

    @Test
    public void testColumnNotFound() {
        var errors = SchemaValidator.of(dataSource).validate(List.of(MissingColumnEntity.class));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.COLUMN_NOT_FOUND
                        && error.message().contains("non_existent_column")));
    }

    @Test
    public void testTypeIncompatible() {
        var errors = SchemaValidator.of(dataSource).validate(List.of(TypeMismatchEntity.class));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.TYPE_INCOMPATIBLE
                        && error.message().contains("first_name")));
    }

    @Test
    public void testNullabilityMismatch() {
        var errors = SchemaValidator.of(dataSource).validate(List.of(NullabilityMismatchEntity.class));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.NULLABILITY_MISMATCH
                        && error.message().contains("description")));
    }

    @Test
    public void testPrimaryKeyMismatch() {
        var errors = SchemaValidator.of(dataSource).validate(List.of(PrimaryKeyMismatchEntity.class));
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.PRIMARY_KEY_MISMATCH));
    }

    @Test
    public void testSequenceExists() {
        assumeTrue(supportsSequences());
        var errors = SchemaValidator.of(dataSource).validate(List.of(SequenceExistsEntity.class));
        assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.SEQUENCE_NOT_FOUND),
                "Expected no SEQUENCE_NOT_FOUND when pet_id_seq exists.");
    }

    @Test
    public void testSequenceNotFound() {
        assumeTrue(supportsSequences());
        var errors = SchemaValidator.of(dataSource).validate(List.of(SequenceNotFoundEntity.class));
        assertTrue(errors.stream().anyMatch(
                error -> error.kind() == ErrorKind.SEQUENCE_NOT_FOUND
                        && error.message().contains("nonexistent_seq")));
    }

    @Test
    public void testIndexesAreDiscovered() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE index_probe (id INTEGER NOT NULL PRIMARY KEY, a VARCHAR(50), b VARCHAR(50), c INTEGER)");
            try {
                statement.execute("CREATE INDEX index_probe_ab ON index_probe (a, b)");
                statement.execute("CREATE UNIQUE INDEX index_probe_c ON index_probe (c)");
                var dialect = Providers.getSqlDialect(connection, StormConfig.defaults());
                var schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(dialect));
                assertTrue(schema.isDiscovered("index_probe", ConstraintKind.INDEX));
                var indexes = schema.getIndexes("index_probe");
                var composite = indexes.stream()
                        .filter(index -> index.indexName().equalsIgnoreCase("index_probe_ab"))
                        .sorted(Comparator.comparingInt(DbIndex::ordinalPosition))
                        .toList();
                assertEquals(2, composite.size(), indexes::toString);
                assertTrue(composite.get(0).columnName().equalsIgnoreCase("a"), indexes::toString);
                assertEquals(1, composite.get(0).ordinalPosition());
                assertTrue(composite.get(1).columnName().equalsIgnoreCase("b"), indexes::toString);
                assertEquals(2, composite.get(1).ordinalPosition());
                assertFalse(composite.get(0).unique());
                var unique = indexes.stream()
                        .filter(index -> index.indexName().equalsIgnoreCase("index_probe_c"))
                        .toList();
                assertEquals(1, unique.size(), indexes::toString);
                assertTrue(unique.getFirst().columnName().equalsIgnoreCase("c"), indexes::toString);
                assertTrue(unique.getFirst().unique());
                assertFalse(unique.getFirst().partial());
                // A unique index makes the column unique as a unique constraint does.
                var errors = SchemaValidator.of(dataSource).validate(List.of(IndexProbeUniqueC.class));
                assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                        errors::toString);
            } finally {
                statement.execute("DROP TABLE index_probe");
            }
        }
    }

    @Test
    public void testAGeneratedColumnIsFilledByTheDatabase() throws SQLException {
        var ddl = generatedColumnProbeDdl();
        assumeTrue(ddl != null);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(ddl);
            try {
                var dialect = Providers.getSqlDialect(connection, StormConfig.defaults());
                var schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(dialect));
                var column = schema.getColumn("generated_probe", "name_folded").orElseThrow();
                // The driver reports the column as generated or with its expression as default; either way the
                // database fills it, so neither a query column nor an unmapped column over it fails an insert.
                assertTrue(column.generated() || column.hasDefault(), column::toString);
                var errors = SchemaValidator.of(dataSource).validate(List.of(GeneratedProbe.class, GeneratedProbeUnmapped.class));
                assertFalse(errors.stream().anyMatch(error -> error.kind() == ErrorKind.QUERY_COLUMN_NOT_FILLED
                        || error.kind() == ErrorKind.UNMAPPED_COLUMN || error.kind() == ErrorKind.COLUMN_NOT_FOUND
                        || error.kind() == ErrorKind.TYPE_INCOMPATIBLE), errors::toString);
            } finally {
                statement.execute("DROP TABLE generated_probe");
            }
        }
    }

    @Test
    public void testAnExpressionIndexHasNoColumn() throws SQLException {
        var ddl = expressionIndexDdl();
        assumeTrue(ddl != null);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE index_probe (id INTEGER NOT NULL PRIMARY KEY, a VARCHAR(50), b VARCHAR(50), c INTEGER)");
            try {
                statement.execute(ddl);
                // The load publishes the indexes for the data source, which reads the expression as unknown.
                SchemaValidator.of(dataSource).load(List.of());
                var dialect = Providers.getSqlDialect(connection, StormConfig.defaults());
                var schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(dialect));
                var expression = schema.getIndexes("index_probe").stream()
                        .filter(index -> index.indexName().equalsIgnoreCase("index_probe_lower"))
                        .toList();
                assertEquals(1, expression.size(), () -> schema.getIndexes("index_probe").toString());
                assertEquals(null, expression.getFirst().columnName());
                // A unique index on lower(a) does not make a itself unique.
                var errors = SchemaValidator.of(dataSource).validate(List.of(IndexProbeUniqueA.class));
                assertTrue(errors.stream().anyMatch(error -> error.kind() == ErrorKind.UNIQUE_KEY_MISSING),
                        errors::toString);
            } finally {
                statement.execute("DROP TABLE index_probe");
            }
        }
    }

    @Test
    public void testASlowDeleteNamesTheIndexAReferencingForeignKeyLacks() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fk_probe_parent (id INTEGER NOT NULL PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE fk_probe_child (id INTEGER NOT NULL PRIMARY KEY, parent_id INTEGER NOT NULL,
                        CONSTRAINT fk_probe_child_parent FOREIGN KEY (parent_id) REFERENCES fk_probe_parent (id))""");
            try {
                statement.execute("INSERT INTO fk_probe_parent (id) VALUES (1)");
                var orm = ORMTemplate.of(dataSource);
                orm.loadSchema();
                var advice = slowAdvice(() -> {
                    orm.entity(FkProbeParent.class).removeById(1);
                    return null;
                });
                if (indexesForeignKeys()) {
                    assertEquals(List.of(), advice);
                } else {
                    assertEquals(1, advice.size(), advice::toString);
                    assertTrue(advice.getFirst().toLowerCase().contains("checks the foreign key "
                            + "fk_probe_child(parent_id) that references it"), advice.getFirst());
                }
            } finally {
                statement.execute("DROP TABLE fk_probe_child");
                statement.execute("DROP TABLE fk_probe_parent");
            }
        }
    }

    /** Returns the unindexed-foreign-key advice on the slow lines the action logs, every execution counting as slow. */
    private static List<String> slowAdvice(Supplier<?> action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previousLevel = logger.getLevel();
        Duration previousThreshold = SlowStatementLog.threshold();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        SlowStatementLog.threshold(Duration.ofNanos(1));
        try {
            action.get();
        } finally {
            SlowStatementLog.threshold(previousThreshold);
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.list.stream()
                .flatMap(event -> event.getFormattedMessage().lines())
                .filter(line -> line.contains("#unindexed-foreign-key"))
                .toList();
    }
}
