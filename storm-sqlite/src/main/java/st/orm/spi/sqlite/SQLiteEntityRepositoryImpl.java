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
package st.orm.spi.sqlite;

import static java.util.function.Predicate.not;
import static st.orm.core.template.SqlInterceptor.intercept;
import static st.orm.core.template.TemplateString.combine;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.impl.StringTemplates.flatten;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import st.orm.Entity;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.PersistenceException;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.impl.EntityRepositoryImpl;
import st.orm.core.spi.EntityCache;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedQuery;
import st.orm.core.template.TemplateString;

/**
 * Implementation of {@link EntityRepository} for SQLite.
 *
 * <p>SQLite supports upserts using {@code INSERT ... ON CONFLICT(pk) DO UPDATE SET ...} syntax
 * (available since SQLite 3.24).</p>
 */
public class SQLiteEntityRepositoryImpl<E extends Entity<ID>, ID>
        extends EntityRepositoryImpl<E, ID> {

    public SQLiteEntityRepositoryImpl(@Nonnull ORMTemplate ormTemplate, @Nonnull Model<E, ID> model) {
        super(ormTemplate, model);
    }

    private TemplateString getVersionString(@Nonnull Class<?> type, @Nonnull Column column) {
        TemplateString columnName = TemplateString.of(column.qualifiedName(ormTemplate.dialect()));
        TemplateString updateExpression = switch (column.type()) {
            case Class<?> c when Integer.TYPE.isAssignableFrom(c)
                    || Long.TYPE.isAssignableFrom(c)
                    || Integer.class.isAssignableFrom(c)
                    || Long.class.isAssignableFrom(c)
                    || BigInteger.class.isAssignableFrom(c) -> TemplateString.of("%s + 1".formatted(column.qualifiedName(ormTemplate.dialect())));
            case Class<?> c when Instant.class.isAssignableFrom(c)
                    || Date.class.isAssignableFrom(c)
                    || Calendar.class.isAssignableFrom(c)
                    || Timestamp.class.isAssignableFrom(c) -> TemplateString.of("CURRENT_TIMESTAMP");
            default ->
                    throw new PersistenceException("Unsupported version type: %s.".formatted(column.type().getSimpleName()));
        };
        return flatten(raw("\0 = \0", columnName, updateExpression));
    }

    /**
     * Constructs the SQLite conflict clause for an upsert.
     *
     * <p>This method builds an "ON CONFLICT (<primary_keys>) DO UPDATE SET ..." clause.
     * For non-primary key columns, it assigns the value from the EXCLUDED pseudo-table.
     * Version columns are updated using {@link #getVersionString(Class, Column)}.</p>
     *
     * @param versionAware a flag that will be set if a version column is encountered.
     * @return the conflict clause as a TemplateString.
     */
    private TemplateString onConflictClause(@Nonnull AtomicBoolean versionAware) {
        var dialect = ormTemplate.dialect();
        String conflictTarget = model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .map(c -> c.qualifiedName(dialect))
                .reduce("%s, %s"::formatted)
                .orElseThrow(() -> new PersistenceException("No primary key defined."));
        var assignments = model.declaredColumns().stream()
                .filter(not(Column::primaryKey))
                .filter(Column::updatable)
                .map(column -> {
                    if (column.version()) {
                        versionAware.setPlain(true);
                        return getVersionString(model.type(), column);
                    }
                    return TemplateString.of("%s = EXCLUDED.%s".formatted(column.qualifiedName(dialect), column.qualifiedName(dialect)));
                })
                .reduce((left, right) -> combine(left, TemplateString.of(", "), right))
                .map(st -> combine(TemplateString.of("DO UPDATE SET "), st))
                .orElse(TemplateString.of("DO NOTHING"));
        return flatten(combine(TemplateString.of("\nON CONFLICT ("), TemplateString.of(conflictTarget), raw(") \0", assignments)));
    }

    @Override
    protected void doUpsert(@Nonnull E entity) {
        validateUpsert(entity);
        entityCache().ifPresent(cache -> {
            if (!model.isDefaultPrimaryKey(entity.id())) {
                cache.remove(entity.id());
            }
        });
        var versionAware = new AtomicBoolean();
        intercept(sql -> sql.versionAware(versionAware.getPlain()), () -> {
            var query = ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0""", model.type(), entity, onConflictClause(versionAware)))).managed();
            query.executeUpdate();
        });
    }

    @Override
    protected ID doUpsertAndFetchId(@Nonnull E entity) {
        validateUpsert(entity);
        entityCache().ifPresent(cache -> {
            if (!model.isDefaultPrimaryKey(entity.id())) {
                cache.remove(entity.id());
            }
        });
        var versionAware = new AtomicBoolean();
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () -> {
            try (var query = ormTemplate.query(flatten(raw("""
                        INSERT INTO \0
                        VALUES \0\0""", model.type(), entity, onConflictClause(versionAware)))).managed().prepare()) {
                query.executeUpdate();
                if (isAutoGeneratedPrimaryKey()) {
                    try (var stream = query.getGeneratedKeys(model.primaryKeyType())) {
                        return stream.reduce((ignore1, ignore2) -> {
                            throw new NonUniqueResultException("Expected single result, but found more than one.");
                        }).orElseThrow(() -> new NoResultException("Expected single result, but found none."));
                    }
                }
                return entity.id();
            }
        });
    }

    @Override
    protected PreparedQuery prepareUpsertQuery() {
        var bindVars = ormTemplate.createBindVars();
        var versionAware = new AtomicBoolean();
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () ->
                ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0""", model.type(), bindVars, onConflictClause(versionAware))))
                        .managed().prepare());
    }

    @Override
    protected void doUpsertBatch(@Nonnull List<E> batch, @Nonnull PreparedQuery query,
                                 @Nullable EntityCache<E, ID> cache) {
        if (batch.isEmpty()) {
            return;
        }
        batch.stream().map(this::validateUpsert).forEach(query::addBatch);
        if (cache != null) {
            batch.stream()
                    .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                    .forEach(e -> cache.remove(e.id()));
        }
        int[] result = query.executeBatch();
        if (IntStream.of(result).anyMatch(r -> r != 0 && r != 1 && r != 2)) {
            throw new PersistenceException("Batch upsert failed.");
        }
    }

    @Override
    protected List<ID> doUpsertAndFetchIdsBatch(@Nonnull List<E> batch, @Nonnull PreparedQuery query,
                                                @Nullable EntityCache<E, ID> cache) {
        if (batch.isEmpty()) {
            return List.of();
        }
        batch.stream().map(this::validateUpsert).forEach(query::addBatch);
        if (cache != null) {
            batch.stream()
                    .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                    .forEach(e -> cache.remove(e.id()));
        }
        int[] result = query.executeBatch();
        if (IntStream.of(result).anyMatch(r -> r != 0 && r != 1 && r != 2)) {
            throw new PersistenceException("Batch upsert failed.");
        }
        if (isAutoGeneratedPrimaryKey()) {
            try (var generatedKeys = query.getGeneratedKeys(model.primaryKeyType())) {
                return generatedKeys.toList();
            }
        }
        return batch.stream().map(Entity::id).toList();
    }

    @Override
    public ID insertAndFetchId(@Nonnull E entity) {
        // SQLite does not support sequences.
        return super.insertAndFetchId(entity);
    }

    @Override
    public List<ID> insertAndFetchIds(@Nonnull Iterable<E> entities) {
        // SQLite does not support sequences.
        return super.insertAndFetchIds(entities);
    }
}
