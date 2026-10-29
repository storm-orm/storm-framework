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

import static java.util.function.Predicate.not;
import static st.orm.GenerationStrategy.IDENTITY;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.core.template.SqlInterceptor.intercept;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.impl.StringTemplates.flatten;
import static st.orm.spi.SqlOperation.INSERT;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
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
 * Implementation of {@link EntityRepository} for MySQL.
 */
public class MySQLEntityRepositoryImpl<E extends Entity<ID>, ID>
        extends EntityRepositoryImpl<E, ID> {

    public MySQLEntityRepositoryImpl(ORMTemplate ormTemplate, Model<E, ID> model) {
        super(ormTemplate, model);
    }

    private String getVersionString(Column column) {
        String columnName = column.qualifiedName(ormTemplate.dialect());
        String updateExpression = switch (column.type()) {
            case Class<?> c when Integer.TYPE.isAssignableFrom(c)
                        || Long.TYPE.isAssignableFrom(c)
                        || Integer.class.isAssignableFrom(c)
                        || Long.class.isAssignableFrom(c)
                        || BigInteger.class.isAssignableFrom(c) -> "%s + 1".formatted(columnName);
            case Class<?> c when Instant.class.isAssignableFrom(c)
                        || Date.class.isAssignableFrom(c)
                        || Calendar.class.isAssignableFrom(c)
                        || Timestamp.class.isAssignableFrom(c) -> "CURRENT_TIMESTAMP";
            default ->
                    throw new PersistenceException("Unsupported version type: %s.".formatted(column.type().getSimpleName()));
        };
        return "%s = %s".formatted(columnName, updateExpression);
    }

    protected TemplateString onDuplicateKey(AtomicBoolean versionAware) {
        var dialect = ormTemplate.dialect();
        var values = new ArrayList<String>();
        var duplicates = new HashSet<>();   // CompoundPks may also have their columns included as stand-alone fields. Only include them once.
        // LAST_INSERT_ID() is used to get the last auto-generated primary key value in case no fields are updated.
        model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .filter(column -> duplicates.add(column.name()))
                .map(column -> {
                    String columnName = column.qualifiedName(dialect);
                    if (column.generation() == IDENTITY || (column.generation() == SEQUENCE && column.sequence().isEmpty())) {
                        return "%s = LAST_INSERT_ID(%s)".formatted(columnName, columnName);
                    }
                    return "%s = VALUES(%s)".formatted(columnName, columnName);
                })
                .forEach(values::add);
        model.declaredColumns().stream()
                .filter(not(Column::primaryKey))
                .filter(Column::updatable)
                .filter(column -> duplicates.add(column.name()))
                .map(column -> {
                    if (column.version()) {
                        versionAware.setPlain(true);
                        return getVersionString(column);
                    }
                    String columnName = column.qualifiedName(dialect);
                    return "%s = VALUES(%s)".formatted(columnName, columnName);
                })
                .forEach(values::add);
        if (values.isEmpty()) {
            return TemplateString.EMPTY;
        }
        return TemplateString.of("\nON DUPLICATE KEY UPDATE %s".formatted(String.join(", ", values)));
    }

    @Override
    protected void doUpsert(E entity) {
        validateUpsert(entity);
        entityCache().ifPresent(cache -> {
            if (model.isDefaultPrimaryKey(entity.id())) {
                // MySQL can update a record with the same unique key so we need to clear the cache
                // as we cannot predict which record is updated.
                cache.clear();
            } else {
                cache.remove(entity.id());
            }
        });
        var versionAware = new AtomicBoolean();
        intercept(sql -> sql.versionAware(versionAware.getPlain()), () -> {
             var query = ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0""", model.type(), entity, onDuplicateKey(versionAware)))).managed();
            query.executeUpdate();
        });
    }

    @Override
    protected ID doUpsertAndFetchId(E entity) {
        if (generationStrategy == SEQUENCE) {
            throw new PersistenceException("MySQL does not support sequence-based generation.");
        }
        validateUpsert(entity);
        entityCache().ifPresent(cache -> {
            if (model.isDefaultPrimaryKey(entity.id())) {
                // MySQL can update a record with the same unique key so we need to clear the cache
                // as we cannot predict which record is updated.
                cache.clear();
            } else {
                cache.remove(entity.id());
            }
        });
        var versionAware = new AtomicBoolean();
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () -> {
            try (var query = ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0""", model.type(), entity, onDuplicateKey(versionAware)))).managed().prepare()) {
                query.executeUpdate();
                if (isAutoGeneratedPrimaryKey()) {
                    try (var stream = query.getGeneratedKeys(model.primaryKeyType())) {
                        return stream
                                .reduce((ignore1, ignore2) -> {
                                    throw failure(INSERT, new NonUniqueResultException("Expected single result, but found more than one."));
                                }).orElseThrow(() -> failure(INSERT, new NoResultException("Expected single result, but found none.")));
                    }
                }
                return entity.id();
            }
        });
    }

    @Override
    public List<ID> upsertAndFetchIds(Iterable<E> entities) {
        if (generationStrategy == SEQUENCE) {
            throw new PersistenceException("MySQL does not support sequence-based generation.");
        }
        return super.upsertAndFetchIds(entities);
    }

    @Override
    protected PreparedQuery prepareUpsertQuery() {
        var bindVars = ormTemplate.createBindVars();
        var versionAware = new AtomicBoolean();
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () ->
                ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0""", model.type(), bindVars, onDuplicateKey(versionAware)))).managed()
                        .prepare());
    }

    @Override
    protected void doUpsertBatch(List<E> batch, PreparedQuery query,
                                 @Nullable EntityCache<E, ID> cache) {
        if (batch.isEmpty()) {
            return;
        }
        batch.stream().map(this::validateUpsert).forEach(query::addBatch);
        if (cache != null) {
            if (batch.stream().anyMatch(e -> model.isDefaultPrimaryKey(e.id()))) {
                // MySQL can update a record with the same unique key so we need to clear the cache
                // as we cannot predict which record is updated.
                cache.clear();
            } else {
                batch.forEach(e -> cache.remove(e.id()));
            }
        }
        int[] result = query.executeBatch();
        if (IntStream.of(result).anyMatch(r -> r != 1 && r != 2)) {
            throw new PersistenceException(upsertFailureMessage(batch.size()));
        }
    }

    @Override
    protected List<ID> doUpsertAndFetchIdsBatch(List<E> batch, PreparedQuery query,
                                                @Nullable EntityCache<E, ID> cache) {
        if (batch.isEmpty()) {
            return List.of();
        }
        batch.stream().map(this::validateUpsert).forEach(query::addBatch);
        if (cache != null) {
            if (batch.stream().anyMatch(e -> model.isDefaultPrimaryKey(e.id()))) {
                // MySQL can update a record with the same unique key so we need to clear the cache
                // as we cannot predict which record is updated.
                cache.clear();
            } else {
                batch.forEach(e -> cache.remove(e.id()));
            }
        }
        int[] result = query.executeBatch();
        if (IntStream.of(result).anyMatch(r -> r != 1 && r != 2)) {
            throw new PersistenceException(upsertFailureMessage(batch.size()));
        }
        if (isAutoGeneratedPrimaryKey()) {
            try (var generatedKeys = query.getGeneratedKeys(model.primaryKeyType())) {
                return generatedKeys.toList();
            }
        }
        return batch.stream().map(Entity::id).toList();
    }

    @Override
    public ID insertAndFetchId(E entity) {
        if (generationStrategy != SEQUENCE) {
            return super.insertAndFetchId(entity);
        }
        throw new PersistenceException("MySQL does not support sequence-based generation.");
    }

    @Override
    public List<ID> insertAndFetchIds(Iterable<E> entities) {
        if (generationStrategy != SEQUENCE) {
            return super.insertAndFetchIds(entities);
        }
        throw new PersistenceException("MySQL does not support sequence-based generation.");
    }
}
