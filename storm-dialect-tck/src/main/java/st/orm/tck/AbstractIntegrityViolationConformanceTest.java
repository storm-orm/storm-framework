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
package st.orm.tck;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.TransactionPropagation.REQUIRED;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolationException;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.TransactionOptions;
import st.orm.TransientPersistenceException;
import st.orm.UK;
import st.orm.core.repository.EntityRepository;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * Integrity violation conformance: a write the database refuses for a constraint reaches the caller as an
 * {@link IntegrityViolationException} naming the kind of rule, the constraint as the database reports it, and the
 * entity's fields where the error identifies the column or the constraint; and {@code validate} finds every value
 * that does not fit its column before the write.
 *
 * <p>The suite creates its own tables, {@value #PARENT_TABLE} and {@value #CHILD_TABLE}, with a named primary key,
 * unique key, check constraint and foreign key, and {@value #DEFERRED_TABLE} on a database with deferred constraints
 * ({@link #deferredForeignKeyDdl()}). What a database names in its errors differs; a dialect states it through the
 * hooks, and the suite asserts what the database states and nothing it does not. The suite commits, so it runs
 * without the per-test rollback.</p>
 */
@Timeout(value = 5, unit = MINUTES)
public abstract class AbstractIntegrityViolationConformanceTest {

    protected static final String PARENT_TABLE = "iv_parent";
    protected static final String CHILD_TABLE = "iv_child";
    protected static final String DEFERRED_TABLE = "iv_deferred";

    private static final int TIMEOUT_SECONDS = 60;

    /** A row with a unique code of at most five characters and an amount of at most three digits before the point. */
    @DbTable(PARENT_TABLE)
    public record Parent(@PK(generation = NONE) Integer id, @UK String code, @Nullable BigDecimal amount)
            implements Entity<Integer> {}

    /** A row that references a parent. */
    @DbTable(CHILD_TABLE)
    public record Child(@PK(generation = NONE) Integer id, @FK Ref<Parent> parent) implements Entity<Integer> {}

    /** A row that references a parent through a constraint the database checks at commit. */
    @DbTable(DEFERRED_TABLE)
    public record Deferred(@PK(generation = NONE) Integer id, @FK Ref<Parent> parent) implements Entity<Integer> {}

    protected DataSource dataSource;
    protected ORMTemplate orm;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly.
     */
    @BeforeEach
    final void createTables(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        this.orm = ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (var table : List.of(DEFERRED_TABLE, CHILD_TABLE, PARENT_TABLE)) {
                try {
                    statement.execute("DROP TABLE " + table);
                } catch (SQLException absent) {
                    // The table is left from an earlier run only when that run was interrupted.
                }
            }
            for (var ddl : ddl()) {
                statement.execute(ddl);
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
        awaitTables();
    }

    @AfterEach
    final void dropTables() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (var table : List.of(DEFERRED_TABLE, CHILD_TABLE, PARENT_TABLE)) {
                try {
                    statement.execute("DROP TABLE " + table);
                } catch (SQLException absent) {
                    // The deferred table exists on a database with deferred constraints alone.
                }
            }
        }
    }

    /**
     * The statements that create the suite's tables: the parent with a named primary key, unique key and check
     * constraint, the child with a named foreign key, and the deferred child where {@link #deferredForeignKeyDdl()}
     * gives one. A database that spells a type differently overrides this.
     */
    protected List<String> ddl() {
        var statements = new ArrayList<>(List.of(
                "CREATE TABLE " + PARENT_TABLE + " (id INTEGER NOT NULL, code VARCHAR(5) NOT NULL, amount DECIMAL(5, 2), "
                        + "CONSTRAINT iv_parent_pk PRIMARY KEY (id), "
                        + "CONSTRAINT iv_parent_code_uk UNIQUE (code), "
                        + "CONSTRAINT iv_parent_amount_ck CHECK (amount >= 0))",
                "CREATE TABLE " + CHILD_TABLE + " (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, "
                        + "CONSTRAINT iv_child_pk PRIMARY KEY (id), "
                        + "CONSTRAINT iv_child_parent_fk FOREIGN KEY (parent_id) REFERENCES " + PARENT_TABLE + " (id))"));
        var deferred = deferredForeignKeyDdl();
        if (deferred != null) {
            statements.add(deferred);
        }
        return statements;
    }

    /**
     * The statement that creates {@value #DEFERRED_TABLE} with an integer primary key {@code id} and a
     * {@code parent_id} whose foreign key {@code iv_deferred_parent_fk} to {@value #PARENT_TABLE} the database checks
     * at commit; {@code null} for a database without deferred constraints, which skips the case.
     */
    protected @Nullable String deferredForeignKeyDdl() {
        return null;
    }

    /**
     * Waits until a transaction that starts now reads the tables the suite just created. Returns at once by default;
     * a database whose snapshots refuse a table younger than they are waits here.
     */
    protected void awaitTables() throws Exception {
    }

    /**
     * Whether the database names the violated constraint of a duplicate. A database that names the columns instead
     * returns {@code false}; the fields resolve either way.
     */
    protected boolean reportsUniqueConstraintName() {
        return true;
    }

    /**
     * Whether the database names the violated foreign key constraint. Without the name, a remove of a referenced row
     * cannot name the referencing table.
     */
    protected boolean reportsForeignKeyConstraintName() {
        return true;
    }

    /**
     * Whether the error for a value too long for its column names the column.
     */
    protected boolean namesColumnOfLengthViolation() {
        return true;
    }

    /**
     * Whether the error for a value out of range for its column names the column.
     */
    protected boolean namesColumnOfRangeViolation() {
        return false;
    }

    /**
     * Whether a character column of five characters holds five characters outside the basic multilingual plane,
     * each of two UTF-16 units. A database that counts units, or bytes, or stores such a column in a character set
     * without them returns {@code false}; the check of {@code validate} counts code points either way, so it is never
     * stricter than the database.
     */
    protected boolean storesSupplementaryCharactersByCount() {
        return true;
    }

    /**
     * Whether an upsert resolves on any unique key rather than the primary key alone, so a conflict on another
     * unique key updates the row instead of failing.
     */
    protected boolean upsertResolvesOnUniqueKeys() {
        return false;
    }

    private EntityRepository<Parent, Integer> parents() {
        return orm.entity(Parent.class);
    }

    private EntityRepository<Child, Integer> children() {
        return orm.entity(Child.class);
    }

    private static Metamodel<Parent, ?> field(String path) {
        return Metamodel.of(Parent.class, path);
    }

    @Test
    public void aDuplicateUnderAUniqueKeyNamesTheField() {
        parents().insert(new Parent(1, "A", null));
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents().insert(new Parent(2, "A", null)));
        var violation = single(thrown, IntegrityViolation.Unique.class);
        assertEquals(List.of("code"), paths(violation));
        assertTrue(violation.on(field("code")), "the violation is on the code field");
        assertEquals(Parent.class, violation.type());
        assertTable(violation, PARENT_TABLE);
        if (reportsUniqueConstraintName()) {
            assertConstraint(violation, "iv_parent_code_uk");
        }
        assertCause(thrown);
        assertEquals(1, thrown.violations(field("code")).size());
        assertTrue(thrown.violations(field("amount")).isEmpty());
    }

    @Test
    public void aDuplicatePrimaryKeyNamesTheKey() {
        parents().insert(new Parent(1, "A", null));
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents().insert(new Parent(1, "B", null)));
        var violation = single(thrown, IntegrityViolation.Unique.class);
        assertEquals(List.of("id"), paths(violation));
        assertCause(thrown);
    }

    @Test
    public void aReferenceToAMissingRowNamesTheForeignKey() {
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> children().insert(new Child(1, orm.ref(Parent.class, 999))));
        var violation = single(thrown, IntegrityViolation.ForeignKey.class);
        assertEquals(Child.class, violation.type());
        if (reportsForeignKeyConstraintName()) {
            assertConstraint(violation, "iv_child_parent_fk");
            assertEquals(List.of("parent"), paths(violation));
            assertTable(violation, CHILD_TABLE);
        }
        assertCause(thrown);
    }

    @Test
    public void aRemoveOfAReferencedRowNamesTheReferencingTable() {
        parents().insert(new Parent(1, "A", null));
        children().insert(new Child(1, orm.ref(Parent.class, 1)));
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents().removeById(1));
        var violation = single(thrown, IntegrityViolation.Referenced.class);
        assertEquals(Parent.class, violation.type(), "the type of the removed row");
        assertTrue(violation.fields().isEmpty(), "a referencing row names no field of the removed row");
        if (reportsForeignKeyConstraintName()) {
            assertConstraint(violation, "iv_child_parent_fk");
            assertTable(violation, CHILD_TABLE);
        }
        assertCause(thrown);
    }

    @Test
    public void aNullInANonNullableFieldIsRefusedBeforeTheStatement() {
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents().insert(new Parent(1, null, null)));
        var violation = single(thrown, IntegrityViolation.NotNull.class);
        assertEquals(List.of("code"), paths(violation));
        assertEquals(Parent.class, violation.type());
        assertTable(violation, PARENT_TABLE);
        assertNull(sqlException(thrown), "no statement ran");
        assertEquals(0, parents().count());
    }

    @Test
    public void aNullTheDatabaseRefusesIsReported() {
        var thrown = assertThrows(IntegrityViolationException.class, () -> orm.query(TemplateString.of(
                "INSERT INTO " + PARENT_TABLE + " (id, code) VALUES (1, NULL)")).executeUpdate());
        single(thrown, IntegrityViolation.NotNull.class);
        assertCause(thrown);
    }

    @Test
    public void aValueACheckConstraintRejectsNamesTheConstraint() {
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents().insert(new Parent(1, "A", new BigDecimal("-1"))));
        var violation = single(thrown, IntegrityViolation.Check.class);
        assertConstraint(violation, "iv_parent_amount_ck");
        assertTable(violation, PARENT_TABLE);
        assertCause(thrown);
    }

    @Test
    public void aValueTooLongIsReported() {
        assumeTrue(orm.dialect().enforcesColumnSizes(), "The database stores any value whatever the declared size.");
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents().insert(new Parent(1, "ABCDEF", null)));
        var violation = single(thrown, IntegrityViolation.Length.class);
        assertEquals(namesColumnOfLengthViolation() ? List.of("code") : List.of(), paths(violation));
        assertNull(violation.maxLength(), "a database-reported violation carries no limit");
        assertCause(thrown);
    }

    @Test
    public void aValueOutOfRangeIsReported() {
        assumeTrue(orm.dialect().enforcesColumnSizes(), "The database stores any value whatever the declared size.");
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents().insert(new Parent(1, "A", new BigDecimal("1234.5"))));
        var violation = single(thrown, IntegrityViolation.NumericRange.class);
        assertEquals(namesColumnOfRangeViolation() ? List.of("amount") : List.of(), paths(violation));
        assertCause(thrown);
    }

    @Test
    public void aBatchViolationIsReported() {
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents().insert(List.of(new Parent(1, "A", null), new Parent(2, "A", null))));
        single(thrown, IntegrityViolation.Unique.class);
        assertCause(thrown);
    }

    @Test
    public void anUpsertConflictOnItsTargetDoesNotThrow() {
        parents().insert(new Parent(1, "A", null));
        assertDoesNotThrow(() -> parents().upsert(new Parent(1, "B", null)));
        assertEquals("B", parents().getById(1).code());
    }

    @Test
    public void anUpsertConflictOnAnotherUniqueKeyIsReported() {
        assumeTrue(!upsertResolvesOnUniqueKeys(), "The database resolves an upsert on any unique key.");
        parents().insert(new Parent(1, "A", null));
        var thrown = assertThrows(IntegrityViolationException.class, () -> parents().upsert(new Parent(2, "A", null)));
        var violation = single(thrown, IntegrityViolation.Unique.class);
        assertEquals(List.of("code"), paths(violation), () -> violation + " reported as " + thrown.getMessage());
    }

    @Test
    public void aDeferredConstraintIsReportedAtCommit() {
        assumeTrue(deferredForeignKeyDdl() != null, "The database has no deferred constraints.");
        var deferred = orm.entity(Deferred.class);
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(
                        new TransactionOptions(REQUIRED, null, TIMEOUT_SECONDS, false),
                        transaction -> {
                            deferred.insert(new Deferred(1, orm.ref(Parent.class, 999)));
                            return null;
                        }));
        var violation = single(thrown, IntegrityViolation.ForeignKey.class);
        assertTrue(violation.fields().isEmpty(), "no statement names the entity the commit refused");
        if (reportsForeignKeyConstraintName()) {
            assertConstraint(violation, "iv_deferred_parent_fk");
        }
        assertCause(thrown);
        assertEquals(0, deferred.count());
    }

    @Test
    public void aViolationIsNotTransient() {
        parents().insert(new Parent(1, "A", null));
        PersistenceException thrown = assertThrows(PersistenceException.class,
                () -> parents().insert(new Parent(2, "A", null)));
        assertFalse(thrown instanceof TransientPersistenceException, () -> "not transient: " + thrown);
        assertInstanceOf(IntegrityViolationException.class, thrown);
    }

    @Test
    public void validateReportsEveryViolationOfAnEntity() {
        boolean sizes = orm.dialect().enforcesColumnSizes();
        var violations = parents().validate(new Parent(1, null, new BigDecimal("1234.5")));
        assertEquals(sizes ? 2 : 1, violations.size(), () -> "every violation: " + violations);
        var nullValue = assertInstanceOf(IntegrityViolation.NotNull.class, violations.getFirst());
        assertEquals(List.of("code"), paths(nullValue));
        assertEquals(Parent.class, nullValue.type());
        assertTable(nullValue, PARENT_TABLE);
        if (sizes) {
            var range = assertInstanceOf(IntegrityViolation.NumericRange.class, violations.get(1));
            assertEquals(List.of("amount"), paths(range));
            assertEquals(5, range.precision());
            assertEquals(2, range.scale());
            var length = parents().validate(new Parent(1, "ABCDEF", null));
            assertEquals(1, length.size(), () -> "the length violation alone: " + length);
            var tooLong = assertInstanceOf(IntegrityViolation.Length.class, length.getFirst());
            assertEquals(List.of("code"), paths(tooLong));
            assertEquals(5, tooLong.maxLength());
        }
        assertEquals(0, parents().count(), "validate writes nothing");
    }

    @Test
    public void validateAcceptsValuesAtTheLimitAndTheInsertSucceeds() {
        var parent = new Parent(1, "ABCDE", new BigDecimal("999.99"));
        assertEquals(List.of(), parents().validate(parent));
        parents().insert(parent);
        assertEquals(0, parents().validate(new Parent(2, "A", new BigDecimal("0.5"))).size());
        assertEquals(0, parents().validate(new Parent(3, "B", BigDecimal.ZERO)).size());
        assertEquals(1, parents().count());
    }

    @Test
    public void validateCountsCodePoints() {
        var parent = new Parent(1, "😀".repeat(5), null);
        assertEquals(10, parent.code().length(), "five characters of two units each");
        assertEquals(List.of(), parents().validate(parent), "five code points fit a column of five characters");
        if (storesSupplementaryCharactersByCount()) {
            parents().insert(parent);
            assertEquals(1, parents().count());
        }
    }

    @Test
    public void validateOrThrowThrowsEveryViolation() {
        boolean sizes = orm.dialect().enforcesColumnSizes();
        var thrown = assertThrows(IntegrityViolationException.class,
                () -> parents().validateOrThrow(new Parent(1, null, new BigDecimal("1234.5"))));
        assertEquals(sizes ? 2 : 1, thrown.violations().size());
        assertInstanceOf(IntegrityViolation.NotNull.class, thrown.violations().getFirst());
        assertNull(sqlException(thrown), "no statement ran");
        assertDoesNotThrow(() -> parents().validateOrThrow(new Parent(1, "A", null)));
        assertEquals(0, parents().count());
    }

    private static <V extends IntegrityViolation> V single(IntegrityViolationException thrown, Class<V> kind) {
        assertEquals(1, thrown.violations().size(), () -> "one violation: " + thrown.violations());
        var violation = thrown.violations().getFirst();
        return assertInstanceOf(kind, violation, () -> "the kind of " + violation + " reported as " + thrown.getMessage());
    }

    private static List<String> paths(IntegrityViolation violation) {
        return violation.fields().stream().map(Metamodel::fieldPath).toList();
    }

    private static void assertConstraint(IntegrityViolation violation, String name) {
        var constraint = violation.constraint();
        assertNotNull(constraint, () -> "the constraint of " + violation);
        // A database may report the index that enforces a constraint, named after the constraint.
        assertTrue(constraint.regionMatches(true, 0, name, 0, name.length()),
                () -> "the constraint " + constraint + " is " + name);
    }

    private static void assertTable(IntegrityViolation violation, String name) {
        var table = violation.table();
        assertNotNull(table, () -> "the table of " + violation);
        assertTrue(name.equalsIgnoreCase(table), () -> "the table " + table + " is " + name);
    }

    private static void assertCause(IntegrityViolationException thrown) {
        assertNotNull(sqlException(thrown), () -> "the database's error is the cause of " + thrown);
    }

    private static @Nullable SQLException sqlException(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }
}
