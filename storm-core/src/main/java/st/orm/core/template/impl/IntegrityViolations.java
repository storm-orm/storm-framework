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

import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;
import static st.orm.core.template.IntegrityFailure.Kind.CHECK;
import static st.orm.core.template.IntegrityFailure.Kind.FOREIGN_KEY;
import static st.orm.core.template.IntegrityFailure.Kind.REFERENCED;
import static st.orm.core.template.impl.ObjectMapperFactory.nullableHint;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.Data;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolation.Check;
import st.orm.IntegrityViolation.ForeignKey;
import st.orm.IntegrityViolation.Length;
import st.orm.IntegrityViolation.NotNull;
import st.orm.IntegrityViolation.NumericRange;
import st.orm.IntegrityViolation.Referenced;
import st.orm.IntegrityViolation.Unique;
import st.orm.IntegrityViolationException;
import st.orm.Metamodel;
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.core.template.IntegrityFailureClassifier;
import st.orm.core.template.Model;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.impl.DatabaseSchema.DbForeignKey;
import st.orm.core.template.impl.DatabaseSchema.DbIndex;
import st.orm.core.template.impl.DatabaseSchema.DbUniqueKey;
import st.orm.spi.SqlOperation;

/**
 * Reports a value that breaks a rule of its table in terms of the entity.
 *
 * <p>A failure the database reported is classified by the dialect's {@link IntegrityFailureClassifier}, which reads
 * the kind of rule and the names the error carries, and resolved here to the entity's fields: a column the error
 * names is looked up in the model, a constraint it names in the schema loaded at startup. No metadata is read here.
 * Where the schema is not loaded, or the template runs on a connection rather than a data source, a violation keeps
 * the names the error carries, and a constraint resolves to no field.</p>
 *
 * @since 1.16
 */
public final class IntegrityViolations {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.validation");

    private final IntegrityFailureClassifier classifier;
    private final @Nullable DataSource dataSource;
    private final @Nullable ModelBuilder modelBuilder;

    /**
     * Creates the resolution for a template.
     *
     * @param dialect the dialect whose classifier reads the failures.
     * @param dataSource the data source whose loaded schema resolves a constraint to its fields, or {@code null} for
     *                   a template without one.
     * @param modelBuilder the models of the entities, or {@code null} when no entity is known.
     */
    public IntegrityViolations(SqlDialect dialect, @Nullable DataSource dataSource, @Nullable ModelBuilder modelBuilder) {
        this.classifier = requireNonNull(dialect.integrityFailureClassifier(), "integrityFailureClassifier");
        this.dataSource = dataSource;
        this.modelBuilder = modelBuilder;
    }

    /**
     * Creates the resolution for a failure that names no entity, such as the failure of a commit: the violation
     * carries the kind of rule and the names the error carries, and no field.
     *
     * @param dialect the dialect whose classifier reads the failures.
     * @return the resolution.
     */
    public static IntegrityViolations of(SqlDialect dialect) {
        return new IntegrityViolations(dialect, null, null);
    }

    /**
     * Returns the failure as an {@link IntegrityViolationException} when the classifier recognises an error in its
     * chain as an integrity violation, and the failure itself otherwise. The chain is walked through the causes and,
     * for each {@code SQLException}, the next exceptions a driver chains to it; the first error classified decides.
     *
     * @param failure the failure a statement or a commit reported.
     * @param operation the operation the failure belongs to.
     * @param type the entity type the statement writes, or {@code null} when it writes none.
     * @return the integrity violation, with the failure as its cause, or the failure.
     */
    public Throwable translate(Throwable failure, SqlOperation operation, @Nullable Class<? extends Data> type) {
        if (failure instanceof IntegrityViolationException) {
            return failure;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                for (SQLException next = sqlException; next != null; next = next.getNextException()) {
                    if (next != sqlException && !visited.add(next)) {
                        break;
                    }
                    Optional<IntegrityFailure> classified;
                    try {
                        classified = classifier.classify(next);
                    } catch (RuntimeException classificationFailure) {
                        // Never let classification mask the original exception; it travels along with it.
                        failure.addSuppressed(classificationFailure);
                        return failure;
                    }
                    if (classified.isPresent()) {
                        var violation = resolve(classified.get(), operation, type);
                        return new IntegrityViolationException(
                                "%s refused by the database: %s".formatted(describe(operation), describe(violation)),
                                List.of(violation), failure);
                    }
                }
            }
        }
        return failure;
    }

    private static String describe(SqlOperation operation) {
        return switch (operation) {
            case INSERT -> "Insert";
            case UPDATE -> "Update";
            case DELETE -> "Delete";
            default -> "Write";
        };
    }

    /**
     * Resolves the names a failure carries to the entity's fields.
     */
    private IntegrityViolation resolve(IntegrityFailure failure, SqlOperation operation,
                                       @Nullable Class<? extends Data> type) {
        Model<?, ?> model = model(type);
        if (model == null) {
            return violation(failure.kind(), failure.constraint(), failure.table(), type, List.of());
        }
        Kind kind = failure.kind();
        String table = failure.table();
        String constraint = failure.constraint();
        List<Metamodel<?, ?>> fields = List.of();
        if (!failure.columns().isEmpty()) {
            fields = fieldsOf(model, failure.columns());
        } else if (failure.primaryKey()) {
            fields = model.columns().stream().filter(Column::primaryKey).map(IntegrityViolations::field).toList();
        } else if (constraint != null && kind != CHECK) {
            var schema = schema(model.schema());
            if (schema != null) {
                var uniqueColumns = schema.getUniqueKeys(model.name()).stream()
                        .filter(key -> constraint.equalsIgnoreCase(key.indexName()))
                        .sorted(comparingInt(DbUniqueKey::ordinalPosition))
                        .map(DbUniqueKey::columnName)
                        .toList();
                if (uniqueColumns.isEmpty()) {
                    // A unique index that is no constraint, and the primary key on a database that reports it under
                    // the name of its index.
                    uniqueColumns = schema.getIndexes(model.name()).stream()
                            .filter(index -> index.unique() && constraint.equalsIgnoreCase(index.indexName())
                                    && index.columnName() != null)
                            .sorted(comparingInt(DbIndex::ordinalPosition))
                            .map(DbIndex::columnName)
                            .toList();
                }
                if (!uniqueColumns.isEmpty()) {
                    fields = fieldsOf(model, uniqueColumns);
                } else {
                    var ownForeignKey = schema.getForeignKeys(model.name()).stream()
                            .filter(key -> constraint.equalsIgnoreCase(key.constraintName()))
                            .map(DbForeignKey::fkColumnName)
                            .toList();
                    if (!ownForeignKey.isEmpty() && kind == FOREIGN_KEY) {
                        fields = fieldsOf(model, ownForeignKey);
                    } else if (kind == FOREIGN_KEY || kind == REFERENCED) {
                        // The constraint belongs to a table that references this one: the row is still referenced.
                        var referencing = schema.tableNames().stream()
                                .filter(name -> schema.getForeignKeys(name).stream()
                                        .anyMatch(key -> constraint.equalsIgnoreCase(key.constraintName())
                                                && model.name().equalsIgnoreCase(key.pkTableName())))
                                .findFirst();
                        if (referencing.isPresent()) {
                            kind = REFERENCED;
                            table = referencing.get();
                        }
                    }
                }
            }
        }
        if (constraint != null && fields.isEmpty() && kind != REFERENCED && kind != CHECK
                && schema(model.schema()) != null) {
            // The database named a constraint the loaded schema does not know: a misread name, or a constraint added
            // since the load. Either way the violation resolves to no field, which is worth a word to a developer.
            LOGGER.debug("Constraint {} reported for {} is not known for table {}; the violation resolves to no field.",
                    constraint, type.getSimpleName(), model.name());
        }
        if (kind == FOREIGN_KEY && fields.isEmpty()
                && (operation == SqlOperation.DELETE || (table != null && !table.equalsIgnoreCase(model.name())))) {
            // A delete, or an error that names another table, violates a reference to this row rather than one
            // this row holds.
            kind = REFERENCED;
        }
        if (table == null && kind != REFERENCED) {
            table = model.name();
        }
        return violation(kind, constraint, table, type, fields);
    }

    /**
     * Returns the violation of the given kind, without a limit: a database reports none. A kind that names no
     * constraint or no field leaves out what the failure carried for it.
     */
    private static IntegrityViolation violation(Kind kind,
                                                @Nullable String constraint,
                                                @Nullable String table,
                                                @Nullable Class<? extends Data> type,
                                                List<Metamodel<?, ?>> fields) {
        return switch (kind) {
            case UNIQUE -> new Unique(constraint, table, type, fields);
            case FOREIGN_KEY -> new ForeignKey(constraint, table, type, fields);
            case REFERENCED -> new Referenced(constraint, table, type);
            case NOT_NULL -> new NotNull(table, type, fields);
            case CHECK -> new Check(constraint, table, type);
            case LENGTH -> new Length(table, type, fields, null);
            case NUMERIC_RANGE -> new NumericRange(table, type, fields, null, null);
        };
    }

    private static List<Metamodel<?, ?>> fieldsOf(Model<?, ?> model, List<String> columnNames) {
        List<Metamodel<?, ?>> fields = new ArrayList<>(columnNames.size());
        for (var columnName : columnNames) {
            model.columns().stream()
                    .filter(column -> column.name().equalsIgnoreCase(columnName))
                    .findFirst()
                    .map(IntegrityViolations::field)
                    .ifPresent(fields::add);
        }
        return List.copyOf(fields);
    }

    private static Metamodel<?, ?> field(Column column) {
        return column.metamodel();
    }

    private @Nullable Model<?, ?> model(@Nullable Class<? extends Data> type) {
        if (type == null || modelBuilder == null) {
            return null;
        }
        try {
            return modelBuilder.build(type, false);
        } catch (SqlTemplateException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the schema the entities of the template live in, or {@code null} when the template has no data source
     * or the schema is not loaded.
     */
    private @Nullable DatabaseSchema schema(String entitySchema) {
        return dataSource == null ? null : SchemaCache.cached(dataSource, entitySchema);
    }

    /**
     * Returns the exception for a null in a field whose column refuses it, found while the values of a write are
     * read from the entity.
     *
     * @param type the entity type.
     * @param table the entity's table.
     * @param column the column that refuses the null.
     * @return the exception.
     */
    static IntegrityViolationException nullValue(Class<? extends Data> type, String table, Column column) {
        var violation = new NotNull(table, type, List.of(column.metamodel()));
        return new IntegrityViolationException(
                "Cannot write NULL to non-nullable column '%s' of %s. Ensure the entity field has a value before inserting or updating, or %s if NULL is intended."
                        .formatted(column.name(), type.getSimpleName(), nullableHint(type)),
                List.of(violation));
    }

    /**
     * Returns the violation as a sentence naming the kind, the fields, the type, the table, the constraint and the
     * limit, for the message of the exception that carries it.
     *
     * @param violation the violation.
     * @return the description.
     */
    public static String describe(IntegrityViolation violation) {
        var description = new StringBuilder(switch (violation) {
            case Unique ignored -> "Duplicate value";
            case ForeignKey ignored -> "Reference to a missing row";
            case Referenced ignored -> "Row still referenced";
            case NotNull ignored -> "Null value";
            case Check ignored -> "Value rejected by a check";
            case Length ignored -> "Value too long";
            case NumericRange ignored -> "Value out of range";
        });
        if (!violation.fields().isEmpty()) {
            description.append(" for ")
                    .append(violation.fields().stream().map(Metamodel::fieldPath).collect(Collectors.joining(", ")));
        }
        if (violation.type() != null) {
            description.append(" of ").append(violation.type().getSimpleName());
        }
        if (violation.table() != null) {
            description.append(violation instanceof Referenced ? " by " : " in ").append(violation.table());
        }
        if (violation.constraint() != null) {
            description.append(" (").append(violation.constraint()).append(')');
        }
        if (violation instanceof Length length && length.maxLength() != null) {
            description.append(": at most ").append(length.maxLength()).append(" characters");
        }
        if (violation instanceof NumericRange range && range.precision() != null) {
            description.append(": at most ").append(range.precision() - (range.scale() == null ? 0 : range.scale()))
                    .append(" digits before the decimal point");
        }
        return description.append('.').toString();
    }
}
