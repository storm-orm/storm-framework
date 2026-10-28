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
package st.orm.spi.mssqlserver;

import static st.orm.GenerationStrategy.IDENTITY;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.core.template.SqlInterceptor.intercept;
import static st.orm.core.template.TemplateString.combine;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.impl.StringTemplates.flatten;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import st.orm.Entity;
import st.orm.PersistenceException;
import st.orm.SqlTemplateException;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.impl.MergeEntityRepositoryImpl;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Query;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.JoinedEntityHelper;

/**
 * Implementation of {@link EntityRepository} for SQL Server.
 */
public class MSSQLServerEntityRepositoryImpl<E extends Entity<ID>, ID>
        extends MergeEntityRepositoryImpl<E, ID> {

    public MSSQLServerEntityRepositoryImpl(ORMTemplate ormTemplate, Model<E, ID> model) {
        super(ormTemplate, model);
    }

    /**
     * SQL Server requires MERGE statements to be terminated with a semicolon.
     */
    @Override
    protected String statementSuffix() {
        return ";";
    }

    /**
     * SQL Server increments the stored version rather than the incoming source value.
     */
    @Override
    protected String versionIncrementExpression(String qualifiedName) {
        return "t.%s + 1".formatted(qualifiedName);
    }

    /**
     * Builds a SELECT clause for the merge source based on entities.
     */
    private TemplateString mergeSelect(Iterable<E> entities) {
        assert generationStrategy == SEQUENCE;
        try {
            List<TemplateString> valuesTemplates = new ArrayList<>();
            for (E entity : entities) {
                var mapped = model.declaredValues(entity);
                var duplicates = new HashSet<>(); // Ensure each column appears only once.
                valuesTemplates.add(mapped.entrySet().stream()
                        .filter(entry -> duplicates.add(entry.getKey().name()))
                        .map(entry -> {
                            Column column = entry.getKey();
                            Object value = entry.getValue();
                            if (column.primaryKey()) {
                                //noinspection unchecked
                                if (model.isDefaultPrimaryKey((ID) value)) {
                                    value = null;   // Always pass NULL to force a mismatch.
                                }
                            }
                            return wrap(value);
                        })
                        .reduce((left, right) -> combine(left, TemplateString.of(", "), right))
                        .map(t -> combine(TemplateString.of("("), t, TemplateString.of(")")))
                        .orElseThrow());
            }
            return valuesTemplates.stream()
                    .reduce((left, right) -> combine(left, TemplateString.of(", "), right))
                    .map(t -> combine(TemplateString.of("VALUES "), t))
                    .orElseThrow();
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to map entity to SQL parameters.", e);
        }
    }

    /**
     * Builds a src clause for the merge source based on bind variables.
     */
    private TemplateString mergeSource() {
        var dialect = ormTemplate.dialect();
        var duplicates = new HashSet<>(); // Ensure each column appears only once.
        return model.declaredColumns().stream()
                .filter(column -> duplicates.add(column.name()))
                .map(entry -> TemplateString.of(entry.qualifiedName(dialect)))
                .reduce((left, right) -> combine(left, TemplateString.of(", "), right))
                .orElseThrow();
    }

    /**
     * Constructs the INSERT clause for the MERGE statement.
     */
    @Override
    protected TemplateString mergeInsert() {
        var dialect = ormTemplate.dialect();
        var insertDuplicates = new HashSet<>();
        var insertArgs = model.declaredColumns().stream()
                .filter(column -> !(column.generation() == IDENTITY || (column.generation() == SEQUENCE && column.sequence().isEmpty())))
                .map(Column::name)
                .filter(insertDuplicates::add)
                .toList();
        var valuesDuplicates = new HashSet<>();
        var valuesArgs = model.declaredColumns().stream()
                .filter(column -> valuesDuplicates.add(column.name()))
                .map(column -> {
                    if (column.generation() == IDENTITY || (column.generation() == SEQUENCE && column.sequence().isEmpty())) {
                        // For auto-generated primary keys, we do not insert a value.
                        return null;
                    }
                    if (!column.sequence().isEmpty()) {
                        return "NEXT VALUE FOR %s".formatted(dialect.getSafeIdentifier(column.sequence()));
                    }
                    return "src.%s".formatted(column.qualifiedName(dialect));
                })
                .filter(Objects::nonNull)
                .toList();
        if (insertArgs.isEmpty()) {
            return TemplateString.EMPTY;
        }
        String insertSql = String.join(", ", insertArgs);
        String valuesSql = String.join(", ", valuesArgs);
        String sql = "\n\tINSERT (%s)\n\tVALUES (%s)".formatted(insertSql, valuesSql);
        return TemplateString.of("\nWHEN NOT MATCHED THEN%s".formatted(sql));
    }

    /**
     * Overrides to use SEQUENCE-specific OUTPUT clause for batch fetch IDs when applicable.
     *
     * <p>For non-SEQUENCE generation strategies, delegates to the base class implementation which
     * handles routing via {@link #isUpsertInsert(Entity)} and {@link #isUpsertUpdate(Entity)}.
     * For SEQUENCE with a non-empty sequence name, throws because SQL Server does not support
     * NEXT VALUE FOR in a MERGE OUTPUT clause. For SEQUENCE with an empty sequence name, uses
     * the OUTPUT INSERTED clause on the MERGE statement.</p>
     */
    @Override
    public List<ID> upsertAndFetchIds(Iterable<E> entities) {
        if (generationStrategy != SEQUENCE) {
            return super.upsertAndFetchIds(entities);
        }
        if (!primaryKeyColumns.getFirst().sequence().isEmpty()) {
            //
            // The following SQL would be generated if the sequence is non-empty:
            //
            // MERGE INTO table t
            // USING (VALUES (?, ?), (?, ?)) AS src(id, name)
            // ON (t.id = src.id)
            // WHEN MATCHED THEN
            //   UPDATE SET t.name = src.name, t.owner_id = src.owner_id
            // WHEN NOT MATCHED THEN
            //   INSERT (id, name)
            //	  VALUES (NEXT VALUE FOR seq_id, src.name)
            // OUTPUT INSERTED.id;
            //
            // However, this would result in the following error:
            // NEXT VALUE FOR function can only be used with MERGE if it is defined within a default constraint on the target table for insert actions.
            //
            throw new PersistenceException("MSSQLServer does not support combining sequence-based ID generation with fetch mode. " +
                    "Use the column's DEFAULT constraint for sequence values instead.");
        }
        // SEQUENCE path with empty sequence: use a single query with OUTPUT clause instead of batched prepared statements.
        return upsertAndFetchIdsPartitioned(entities,
                (chunk, entityCache) -> upsertPartitionAndFetchIds(chunk, entityCache, this::getUpsertQuery));
    }

    private Query getUpsertQuery(Iterable<E> entities) {
        var versionAware = new AtomicBoolean();
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String pkName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () ->
                ormTemplate.query(flatten(raw("""
                    MERGE INTO \0 t
                    USING (\0) AS src(\0)
                    ON (\0)\0\0
                    OUTPUT INSERTED.%s;""".formatted(pkName), model.type(), mergeSelect(entities), mergeSource(), mergeOn(), mergeUpdate(versionAware), mergeInsert()))).managed()
                        );
    }

    /**
     * Overrides joined entity batch insert to use SQL Server's {@code OUTPUT INSERTED} clause instead of
     * {@code executeBatch()} followed by {@code getGeneratedKeys()}, which SQL Server does not support.
     *
     * <p>Phase 1 (base table insert) uses a multi-value INSERT with {@code OUTPUT INSERTED} to retrieve
     * generated keys. Phase 2 (extension table inserts) delegates to the standard
     * {@link JoinedEntityHelper#insertExtensionTables} logic, which uses batch execution without generated
     * keys and works correctly on SQL Server.</p>
     */
    @Override
    protected List<ID> insertJoinedBatch(List<E> entities) {
        if (generationStrategy == NONE) {
            return super.insertJoinedBatch(entities);
        }
        // SQL Server does not support getGeneratedKeys() after executeBatch().
        // Use OUTPUT INSERTED clause for the base table insert instead.
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String primaryKeyName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        // Phase 1: Base table INSERT with OUTPUT INSERTED.
        var query = ormTemplate.query(raw("""
            INSERT INTO \0
            OUTPUT INSERTED.%s
            VALUES \0""".formatted(primaryKeyName), model.type(), entities))
                .managed();
        List<ID> ids = query.getResultList(model.primaryKeyType());
        // Phase 2: Extension table INSERTs.
        JoinedEntityHelper.insertExtensionTables(ormTemplate, model, entities, ids);
        return ids;
    }

    @Override
    public ID insertAndFetchId(E entity) {
        if (generationStrategy != SEQUENCE) {
            return super.insertAndFetchId(entity);
        }
        entity = fireBeforeInsert(entity);
        validateInsert(entity);
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String pkName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        try (var query = ormTemplate.query(raw("""
                INSERT INTO \0
                OUTPUT INSERTED.%s
                VALUES \0""".formatted(pkName), model.type(), entity)).managed().prepare()) {
            ID id = query.getSingleResult(model.primaryKeyType());
            fireAfterInsert(entity, id);
            return id;
        }
    }

    @Override
    public List<ID> insertAndFetchIds(Iterable<E> entities) {
        if (generationStrategy == NONE) {
            return super.insertAndFetchIds(entities);
        }
        // Also use MSSQLServer specific logic for AUTO_INCREMENT as MSSQLServer does not support generated keys in batch mode.
        List<E> transformed = toStream(entities)
                .map(this::fireBeforeInsert)
                .map(this::validateInsert)
                .toList();
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String pkName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        var query = ormTemplate.query(raw("""
            INSERT INTO \0
            OUTPUT INSERTED.%s
            VALUES \0""".formatted(pkName), model.type(), transformed))
                .managed();
        List<ID> ids = query.getResultList(model.primaryKeyType());
        fireAfterInsert(transformed, ids);
        return ids;
    }
}
