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

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolation.Length;
import st.orm.IntegrityViolation.NotNull;
import st.orm.IntegrityViolation.NumericRange;
import st.orm.PersistenceException;
import st.orm.SqlTemplateException;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.impl.DatabaseSchema.DbColumn;

/**
 * Checks an entity against its columns before any statement runs: a null in a column that refuses it, a string longer
 * than its character column, a decimal with more digits before the decimal point than its column holds. Every
 * violation of the entity is reported, as the {@link IntegrityViolation} records a database's own refusal of a write
 * reports, so one handler serves both.
 *
 * <p>The column limits come from the database schema, read on first use over the template's data source through
 * {@link SchemaCache} and kept for the data source's lifetime. The checks are never stricter than the database: a
 * string's length counts code points, a database that stores any value whatever the declared size
 * ({@link SqlDialect#enforcesColumnSizes()}) is not checked against it, and a column the schema does not describe is
 * not checked. Without a data source, or where the schema cannot be read, the null check alone runs. Rules that depend
 * on other rows, a unique key or a foreign key's target, are the database's to check.</p>
 *
 * @since 1.16
 */
public final class EntityValidator {

    private EntityValidator() {}

    /**
     * Checks an entity against its columns over the data source of the template, which the template's query factory
     * holds for a template that runs on one.
     *
     * @param model the entity's model.
     * @param entity the entity to check.
     * @param template the template the entity's repository belongs to.
     * @return the violations, empty when every value fits its column.
     * @param <E> the entity type.
     */
    public static <E extends Data> List<IntegrityViolation> validate(Model<E, ?> model, E entity, QueryTemplate template) {
        var dataSource = template instanceof QueryTemplateImpl impl ? impl.queryFactory.dataSource() : null;
        return validate(model, entity, dataSource, template.dialect());
    }

    /**
     * Checks an entity against its columns.
     *
     * @param model the entity's model.
     * @param entity the entity to check.
     * @param dataSource the data source the schema is read over, or {@code null} for a template without one.
     * @param dialect the dialect, which states whether the database enforces the declared sizes of its columns.
     * @return the violations, empty when every value fits its column.
     * @param <E> the entity type.
     */
    public static <E extends Data> List<IntegrityViolation> validate(Model<E, ?> model,
                                                                      E entity,
                                                                      @Nullable DataSource dataSource,
                                                                      SqlDialect dialect) {
        requireNonNull(model, "model");
        requireNonNull(entity, "entity");
        var values = nullableValues(model, entity);
        var schema = dialect.enforcesColumnSizes() && dataSource != null
                ? SchemaCache.readOrNull(dataSource, dialect, model.schema())
                : null;
        List<IntegrityViolation> violations = new ArrayList<>();
        for (var entry : values.entrySet()) {
            var column = entry.getKey();
            var value = entry.getValue();
            if (value == null) {
                if (!column.nullable() && !column.primaryKey()) {
                    violations.add(new NotNull(model.name(), model.type(), List.of(column.metamodel())));
                }
                continue;
            }
            if (schema == null) {
                continue;
            }
            schema.getColumn(model.name(), column.name())
                    .flatMap(dbColumn -> sizeViolation(model, column, dbColumn, value))
                    .ifPresent(violations::add);
        }
        return List.copyOf(violations);
    }

    /**
     * Returns the message of the exception {@code validateOrThrow} throws for the violations of an entity.
     *
     * @param type the entity type.
     * @param violations the violations, at least one.
     * @return the message.
     */
    public static String message(Class<? extends Data> type, List<IntegrityViolation> violations) {
        return "%s does not fit its table: %s".formatted(type.getSimpleName(),
                violations.stream().map(IntegrityViolations::describe).collect(Collectors.joining(" ")));
    }

    /**
     * Returns the values of every column of the entity, a non-nullable column that holds a null included, which
     * the model's own value extraction refuses as a write does.
     */
    private static <E extends Data> SequencedMap<Column, @Nullable Object> nullableValues(Model<E, ?> model,
                                                                                           E entity) {
        if (!(model instanceof ModelImpl<E, ?> impl)) {
            throw new PersistenceException("Cannot validate %s: the model is not Storm's own.".formatted(model.type().getSimpleName()));
        }
        try {
            return impl.nullableValues(entity);
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    private static Optional<IntegrityViolation> sizeViolation(Model<?, ?> model, Column column, DbColumn dbColumn,
                                                              Object value) {
        switch (dbColumn.dataType()) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> {
                // A database counts characters; a string of as many UTF-16 units as the column holds can never hold
                // more code points, so the count runs for a string near the limit alone.
                if (value instanceof CharSequence text
                        && dbColumn.columnSize() > 0
                        && text.length() > dbColumn.columnSize()
                        && Character.codePointCount(text, 0, text.length()) > dbColumn.columnSize()) {
                    return Optional.of(new Length(model.name(), model.type(), List.of(column.metamodel()),
                            dbColumn.columnSize()));
                }
            }
            case Types.DECIMAL, Types.NUMERIC -> {
                // Extra decimals round where the database accepts them; the digits before the decimal point do not.
                if (value instanceof BigDecimal decimal && decimal.signum() != 0 && dbColumn.columnSize() > 0
                        && decimal.precision() - decimal.scale() > dbColumn.columnSize() - dbColumn.decimalDigits()) {
                    return Optional.of(new NumericRange(model.name(), model.type(), List.of(column.metamodel()),
                            dbColumn.columnSize(), dbColumn.decimalDigits()));
                }
            }
            default -> { }
        }
        return Optional.empty();
    }
}
