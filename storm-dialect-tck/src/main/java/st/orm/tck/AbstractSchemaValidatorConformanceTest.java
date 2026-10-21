package st.orm.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.StormConfig;
import st.orm.UK;
import st.orm.core.spi.Providers;
import st.orm.core.template.impl.DatabaseSchema;
import st.orm.core.template.impl.DatabaseSchema.ConstraintKind;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.SchemaValidationError.ErrorKind;
import st.orm.core.template.impl.SchemaValidator;

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

    /** Whether the dialect has sequences, and so reports {@code SEQUENCE_NOT_FOUND} at all. */
    protected boolean supportsSequences() {
        return true;
    }

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
                        dialect.sequenceDiscoveryStrategy(), dialect.constraintDiscoveryStrategy(),
                        dialect.indexDiscoveryStrategy());
                assertTrue(schema.isDiscovered("index_probe", ConstraintKind.INDEX));
                var indexes = schema.getIndexes("index_probe");
                var composite = indexes.stream()
                        .filter(index -> index.indexName().equalsIgnoreCase("index_probe_ab"))
                        .sorted(java.util.Comparator.comparingInt(DbIndex::ordinalPosition))
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
                        dialect.sequenceDiscoveryStrategy(), dialect.constraintDiscoveryStrategy(),
                        dialect.indexDiscoveryStrategy());
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
}
