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

import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.impl.RecordReflection.getExtensionFieldNames;
import static st.orm.core.template.impl.RecordReflection.hasDiscriminator;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.Entity;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.SqlTemplateException;

/**
 * Utility for handling multi-table DML operations for joined sealed entity types (Joined Table inheritance).
 *
 * <p>In joined table inheritance, the base table stores common fields and a discriminator, while each permitted
 * subclass has its own extension table with subtype-specific fields. DML operations must target multiple tables:</p>
 * <ul>
 *   <li><b>INSERT</b>: insert into the base table first (to get the generated PK), then into the extension table.</li>
 *   <li><b>UPDATE</b>: update the base table and extension table separately.</li>
 *   <li><b>DELETE</b>: delete from the extension table first (FK constraint), then from the base table.</li>
 * </ul>
 *
 * <p>DML operations use the template engine with partition-aware models. The sealed model (base table) has
 * extension-specific columns marked as non-insertable/non-updatable, so INSERT/UPDATE via the sealed model
 * automatically touches only base fields and the discriminator. The concrete subtype model (extension table)
 * has base non-PK fields marked as non-insertable/non-updatable and PK generation set to NONE, so INSERT/UPDATE
 * via the concrete model touches only PK and extension fields.</p>
 *
 * @since 1.9
 */
public final class JoinedEntityHelper {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private JoinedEntityHelper() {
    }

    /**
     * Inserts a joined entity into both the base and extension tables.
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entity the entity to insert.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the insert fails.
     */
    public static <E extends Entity<ID>, ID> void insert(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull E entity
    ) {
        try {
            insertJoined(queryTemplate, model, entity);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct insert statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Inserts a joined entity and returns the generated primary key.
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entity the entity to insert.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @return the generated primary key.
     * @throws PersistenceException if the insert fails.
     */
    public static <E extends Entity<ID>, ID> ID insertAndFetchId(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull E entity
    ) {
        try {
            return insertJoined(queryTemplate, model, entity);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct insert-and-fetch-id statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Updates a joined entity in both the base and extension tables.
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entity the entity to update.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the update fails.
     */
    public static <E extends Entity<ID>, ID> void update(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull E entity
    ) {
        try {
            updateJoined(queryTemplate, model, entity);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct update statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Removes a joined entity from both the extension and base tables.
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entity the entity to remove.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the remove fails.
     */
    public static <E extends Entity<ID>, ID> void remove(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull E entity
    ) {
        try {
            deleteJoined(queryTemplate, model, entity.getClass(), entity.id());
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct remove statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Removes a joined entity by primary key from all extension and base tables.
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param id the primary key of the entity to remove.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the remove fails.
     */
    public static <E extends Entity<ID>, ID> void removeById(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull ID id
    ) {
        try {
            deleteJoined(queryTemplate, model, null, id);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct remove-by-id statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    // ---- Batch operations ----

    /**
     * Inserts a batch of joined entities into both the base and extension tables.
     *
     * <p>Phase 1 inserts all entities into the base table (sealed type), collecting generated keys if applicable.
     * Phase 2 partitions entities by concrete subtype and inserts into the corresponding extension tables.</p>
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entities the entities to insert (already validated and transformed by callbacks).
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @return the list of generated (or provided) primary keys, one per entity.
     * @throws PersistenceException if the insert fails.
     * @since 1.9
     */
    public static <E extends Entity<ID>, ID> List<ID> insertBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities
    ) {
        if (entities.isEmpty()) {
            return List.of();
        }
        try {
            return insertJoinedBatch(queryTemplate, model, entities);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct batch insert statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Inserts extension table rows for a batch of joined entities. This method performs Phase 2 of the joined
     * entity batch insert: it partitions the entities by concrete subtype and inserts into the corresponding
     * extension tables.
     *
     * <p>This method is intended for use by dialect-specific implementations that need to perform the base table
     * insert (Phase 1) using a different mechanism (e.g., SQL Server's {@code OUTPUT INSERTED} clause) while
     * reusing the standard extension table insert logic.</p>
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entities the entities to insert (already validated and transformed by callbacks).
     * @param ids the primary keys for the entities, in the same order as the entities list.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the insert fails.
     * @since 1.9
     */
    public static <E extends Entity<ID>, ID> void insertExtensionTables(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities,
            @Nonnull List<ID> ids
    ) {
        if (entities.isEmpty()) {
            return;
        }
        boolean autoGeneratedPrimaryKey = model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .anyMatch(c -> c.generation() != st.orm.GenerationStrategy.NONE);
        try {
            insertExtensionTablesBatch(queryTemplate, model, entities, ids, autoGeneratedPrimaryKey);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct extension table batch insert statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Updates a batch of joined entities in both the base and extension tables.
     *
     * <p>Phase 1 updates all entities in the base table (sealed type). Phase 2 partitions entities by concrete
     * subtype and updates the corresponding extension tables (only for subtypes with extension fields).</p>
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entities the entities to update (already validated and transformed by callbacks).
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the update fails.
     * @since 1.9
     */
    public static <E extends Entity<ID>, ID> void updateBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities
    ) {
        if (entities.isEmpty()) {
            return;
        }
        try {
            updateJoinedBatch(queryTemplate, model, entities);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct batch update statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Removes a batch of joined entities from both extension and base tables.
     *
     * <p>Phase 1 deletes from extension tables first (FK constraints), partitioned by concrete subtype.
     * Phase 2 deletes all entities from the base table.</p>
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param entities the entities to remove (already validated).
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the remove fails.
     * @since 1.9
     */
    public static <E extends Entity<ID>, ID> void removeBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities
    ) {
        if (entities.isEmpty()) {
            return;
        }
        try {
            deleteJoinedBatch(queryTemplate, model, entities);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct batch remove statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    /**
     * Removes a batch of joined entities by reference from all extension and base tables.
     *
     * <p>When the concrete type is unknown, attempts DELETE from all extension tables for all IDs
     * (at most one will match per entity), then deletes from the base table.</p>
     *
     * @param queryTemplate the query template for executing SQL.
     * @param model the model describing the sealed entity.
     * @param refs the entity references to remove.
     * @param <E> the entity type (sealed interface).
     * @param <ID> the primary key type.
     * @throws PersistenceException if the remove fails.
     * @since 1.9
     */
    public static <E extends Entity<ID>, ID> void removeBatchByRef(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<Ref<E>> refs
    ) {
        if (refs.isEmpty()) {
            return;
        }
        try {
            deleteJoinedBatchByRef(queryTemplate, model, refs);
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to construct batch remove-by-ref statement for joined entity %s.".formatted(model.type().getSimpleName()), e);
        }
    }

    // ---- Internal implementation ----

    private static boolean hasExtensionFields(@Nonnull Class<?> concreteType, @Nonnull Class<?> sealedType) {
        return !getExtensionFieldNames(concreteType, sealedType).isEmpty();
    }

    /**
     * Deletes extension table rows from all subtypes except the specified concrete type. Used during update to
     * clean up old extension rows when an entity's type has changed.
     *
     * @return the total number of rows deleted across all extension tables.
     */
    private static <ID> int deleteOtherExtensionRows(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Class<?> sealedType,
            @Nonnull Class<?> currentConcreteType,
            @Nonnull ID id
    ) throws SqlTemplateException {
        int totalDeleted = 0;
        List<? extends Class<?>> permitted = REFLECTION.getPermittedSubclasses(sealedType);
        for (Class<?> subtype : permitted) {
            if (subtype == currentConcreteType) {
                continue;
            }
            // Skip subtypes without extension fields only when a discriminator exists.
            // When no discriminator, every subtype has an extension table (even PK-only) as type marker.
            if (!hasExtensionFields(subtype, sealedType) && hasDiscriminator(sealedType)) {
                continue;
            }
            totalDeleted += queryTemplate.query(raw("""
                    DELETE FROM \0
                    WHERE \0""", subtype, id))
                    .managed().executeUpdate();
        }
        return totalDeleted;
    }

    @SuppressWarnings("unchecked")
    private static <E> E reconstructWithPk(@Nonnull E entity, @Nonnull Object generatedPk) {
        var recordType = REFLECTION.getRecordType(entity.getClass());
        var fields = recordType.fields();
        Object[] args = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            args[i] = fields.get(i).isAnnotationPresent(PK.class)
                    ? generatedPk
                    : REFLECTION.getRecordValue(entity, i);
        }
        try {
            var constructor = recordType.constructor();
            constructor.setAccessible(true);
            return (E) constructor.newInstance(args);
        } catch (Exception e) {
            throw new PersistenceException("Failed to reconstruct entity %s with generated key.".formatted(entity.getClass().getSimpleName()), e);
        }
    }

    private static <E extends Entity<ID>, ID> ID insertJoined(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull E entity
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        Class<? extends Data> concreteType = (Class<? extends Data>) entity.getClass();
        boolean autoGeneratedPk = model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .anyMatch(c -> c.generation() != st.orm.GenerationStrategy.NONE);
        boolean extensionFields = hasExtensionFields(concreteType, sealedType);
        boolean needsExtensionInsert = extensionFields || !hasDiscriminator(sealedType);
        // Step 1: Base table INSERT (sealed model handles discriminator, filters to base columns).
        ID id;
        if (autoGeneratedPk) {
            try (var query = queryTemplate.query(raw("""
                    INSERT INTO \0
                    VALUES \0""", sealedType, entity))
                    .managed().prepare()) {
                if (query.executeUpdate() != 1) {
                    throw new PersistenceException("Insert into base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
                }
                try (Stream<ID> keys = query.getGeneratedKeys(model.primaryKeyType())) {
                    id = keys.findFirst().orElseThrow(
                            () -> new PersistenceException("Failed to retrieve generated key."));
                }
            }
        } else {
            if (queryTemplate.query(raw("""
                    INSERT INTO \0
                    VALUES \0""", sealedType, entity))
                    .managed().executeUpdate() != 1) {
                throw new PersistenceException("Insert into base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
            }
            id = entity.id();
        }
        // Step 2: Extension table INSERT (concrete model filters to PK + extension columns).
        // Always insert into extension table when no discriminator (even if only PK),
        // because the extension table row serves as the type marker.
        if (needsExtensionInsert) {
            E entityWithPk = autoGeneratedPk ? reconstructWithPk(entity, id) : entity;
            if (queryTemplate.query(raw("""
                    INSERT INTO \0
                    VALUES \0""", concreteType, entityWithPk))
                    .managed().executeUpdate() != 1) {
                throw new PersistenceException("Insert into extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), concreteType.getSimpleName()));
            }
        }
        return id;
    }

    private static <E extends Entity<ID>, ID> void updateJoined(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull E entity
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        Class<? extends Data> concreteType = (Class<? extends Data>) entity.getClass();
        boolean extensionFields = hasExtensionFields(concreteType, sealedType);
        boolean needsExtensionTable = extensionFields || !hasDiscriminator(sealedType);
        // Step 1: Base table UPDATE (sealed model: only base non-PK fields in SET).
        if (queryTemplate.query(raw("""
                UPDATE \0
                SET \0
                WHERE \0""", sealedType, entity, entity))
                .managed().executeUpdate() != 1) {
            throw new PersistenceException("Update of base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
        }
        // Step 2: Extension table handling.
        // The extension row may or may not exist independently of the base row: it exists when
        // the entity's subtype hasn't changed, but is absent when the entity's type has changed
        // (e.g., Cat -> Dog). Try UPDATE first; if the row doesn't exist, clean up old extension
        // rows and INSERT into the correct extension table.
        if (needsExtensionTable) {
            boolean extensionRowExists = false;
            if (extensionFields) {
                extensionRowExists = queryTemplate.query(raw("""
                        UPDATE \0
                        SET \0
                        WHERE \0""", concreteType, entity, entity))
                        .managed().executeUpdate() == 1;
            }
            if (!extensionRowExists) {
                // Extension row doesn't exist for the current subtype. Clean up any old extension
                // table rows from other subtypes (handles type changes).
                int deleted = deleteOtherExtensionRows(queryTemplate, sealedType, concreteType, entity.id());
                // Insert into the current extension table if there are extension fields (the UPDATE
                // returned 0, so the row doesn't exist) or if the type changed (old rows were
                // deleted from other extension tables, so a new type marker is needed).
                if (extensionFields || deleted > 0) {
                    if (queryTemplate.query(raw("""
                            INSERT INTO \0
                            VALUES \0""", concreteType, entity))
                            .managed().executeUpdate() != 1) {
                        throw new PersistenceException("Insert into extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), concreteType.getSimpleName()));
                    }
                }
            }
        }
    }

    private static <E extends Entity<ID>, ID> void deleteJoined(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nullable Class<?> concreteTypeOrNull,
            @Nonnull ID id
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        // Step 1: DELETE from extension table(s) first (FK constraint).
        List<? extends Class<?>> permitted = REFLECTION.getPermittedSubclasses(sealedType);
        for (Class<?> subtype : permitted) {
            if (concreteTypeOrNull != null && concreteTypeOrNull != subtype) {
                continue;
            }
            // Skip subtypes without extension fields only when a discriminator exists.
            // When no discriminator, every subtype has an extension table (even PK-only) as type marker.
            if (!hasExtensionFields(subtype, sealedType) && hasDiscriminator(sealedType)) {
                continue;
            }
            if (concreteTypeOrNull != null) {
                // Known concrete type: expect exactly one row deleted.
                if (queryTemplate.query(raw("""
                        DELETE FROM \0
                        WHERE \0""", subtype, id))
                        .managed().executeUpdate() != 1) {
                    throw new PersistenceException("Delete from extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), subtype.getSimpleName()));
                }
            } else {
                // Unknown concrete type: delete from all extension tables (at most one will match).
                queryTemplate.query(raw("""
                        DELETE FROM \0
                        WHERE \0""", subtype, id))
                        .managed().executeUpdate();
            }
        }
        // Step 2: DELETE from base table.
        int baseResult = queryTemplate.query(raw("""
                DELETE FROM \0
                WHERE \0""", sealedType, id))
                .managed().executeUpdate();
        if (concreteTypeOrNull != null && baseResult != 1) {
            throw new PersistenceException("Delete from base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
        }
    }

    private static <E extends Entity<ID>, ID> List<ID> insertJoinedBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        boolean autoGeneratedPrimaryKey = model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .anyMatch(c -> c.generation() != st.orm.GenerationStrategy.NONE);
        // Phase 1: Base table INSERT.
        List<ID> ids;
        var baseBindVars = queryTemplate.createBindVars();
        try (var baseQuery = queryTemplate.query(raw("""
                INSERT INTO \0
                VALUES \0""", sealedType, baseBindVars))
                .managed().prepare()) {
            entities.forEach(baseQuery::addBatch);
            int[] result = baseQuery.executeBatch();
            if (IntStream.of(result).anyMatch(r -> r != 1)) {
                throw new PersistenceException("Batch insert into base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
            }
            if (autoGeneratedPrimaryKey) {
                try (Stream<ID> keys = baseQuery.getGeneratedKeys(model.primaryKeyType())) {
                    ids = keys.toList();
                }
            } else {
                ids = entities.stream().map(Entity::id).toList();
            }
        }
        // Phase 2: Extension table INSERTs, partitioned by concrete subtype.
        insertExtensionTablesBatch(queryTemplate, model, entities, ids, autoGeneratedPrimaryKey);
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Entity<ID>, ID> void insertExtensionTablesBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities,
            @Nonnull List<ID> ids,
            boolean autoGeneratedPrimaryKey
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        Map<Class<?>, List<Integer>> subtypeIndices = new LinkedHashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            subtypeIndices.computeIfAbsent(entities.get(i).getClass(), k -> new ArrayList<>()).add(i);
        }
        for (var entry : subtypeIndices.entrySet()) {
            Class<? extends Data> concreteType = (Class<? extends Data>) entry.getKey();
            boolean extensionFields = hasExtensionFields(concreteType, sealedType);
            boolean needsExtensionInsert = extensionFields || !hasDiscriminator(sealedType);
            if (!needsExtensionInsert) {
                continue;
            }
            var extensionBindVars = queryTemplate.createBindVars();
            try (var extensionQuery = queryTemplate.query(raw("""
                    INSERT INTO \0
                    VALUES \0""", concreteType, extensionBindVars))
                    .managed().prepare()) {
                for (int index : entry.getValue()) {
                    E entity = entities.get(index);
                    E entityWithPrimaryKey = autoGeneratedPrimaryKey
                            ? reconstructWithPk(entity, ids.get(index)) : entity;
                    extensionQuery.addBatch(entityWithPrimaryKey);
                }
                int[] extensionResult = extensionQuery.executeBatch();
                if (IntStream.of(extensionResult).anyMatch(r -> r != 1)) {
                    throw new PersistenceException("Batch insert into extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), concreteType.getSimpleName()));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Entity<ID>, ID> void updateJoinedBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        // Phase 1: Base table UPDATE.
        var baseBindVars = queryTemplate.createBindVars();
        try (var baseQuery = queryTemplate.query(raw("""
                UPDATE \0
                SET \0
                WHERE \0""", sealedType, baseBindVars, baseBindVars))
                .managed().prepare()) {
            entities.forEach(baseQuery::addBatch);
            int[] result = baseQuery.executeBatch();
            if (IntStream.of(result).anyMatch(r -> r != 1)) {
                throw new PersistenceException("Batch update of base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
            }
        }
        // Phase 2: Extension table handling, partitioned by concrete subtype.
        Map<Class<?>, List<E>> subtypeMap = new LinkedHashMap<>();
        for (E entity : entities) {
            subtypeMap.computeIfAbsent(entity.getClass(), k -> new ArrayList<>()).add(entity);
        }
        for (var entry : subtypeMap.entrySet()) {
            Class<? extends Data> concreteType = (Class<? extends Data>) entry.getKey();
            boolean extensionFields = hasExtensionFields(concreteType, sealedType);
            boolean needsExtensionTable = extensionFields || !hasDiscriminator(sealedType);
            if (!needsExtensionTable) {
                continue;
            }
            List<E> subtypeEntities = entry.getValue();
            List<E> needInsert;
            if (extensionFields) {
                // Try batch UPDATE for entities with extension fields.
                var extensionBindVars = queryTemplate.createBindVars();
                try (var extQuery = queryTemplate.query(raw("""
                        UPDATE \0
                        SET \0
                        WHERE \0""", concreteType, extensionBindVars, extensionBindVars))
                        .managed().prepare()) {
                    subtypeEntities.forEach(extQuery::addBatch);
                    int[] extResult = extQuery.executeBatch();
                    needInsert = new ArrayList<>();
                    for (int i = 0; i < extResult.length; i++) {
                        if (extResult[i] == 0) {
                            needInsert.add(subtypeEntities.get(i));
                        } else if (extResult[i] != 1) {
                            throw new PersistenceException("Batch update of extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), concreteType.getSimpleName()));
                        }
                    }
                }
            } else {
                // PK-only extension table (no discriminator). Check if type changed by deleting
                // from other extension tables.
                needInsert = new ArrayList<>();
                for (E entity : subtypeEntities) {
                    int deleted = deleteOtherExtensionRows(queryTemplate, sealedType, concreteType, entity.id());
                    if (deleted > 0) {
                        needInsert.add(entity);
                    }
                }
            }
            // Insert extension rows for entities where the UPDATE returned 0 (type changed).
            if (!needInsert.isEmpty()) {
                if (extensionFields) {
                    for (E entity : needInsert) {
                        deleteOtherExtensionRows(queryTemplate, sealedType, concreteType, entity.id());
                    }
                }
                var insertBindVars = queryTemplate.createBindVars();
                try (var insertQuery = queryTemplate.query(raw("""
                        INSERT INTO \0
                        VALUES \0""", concreteType, insertBindVars))
                        .managed().prepare()) {
                    needInsert.forEach(insertQuery::addBatch);
                    int[] insertResult = insertQuery.executeBatch();
                    if (IntStream.of(insertResult).anyMatch(r -> r != 1)) {
                        throw new PersistenceException("Batch insert into extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), concreteType.getSimpleName()));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Entity<ID>, ID> void deleteJoinedBatch(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<E> entities
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        // Phase 1: DELETE from extension tables first (FK constraint).
        Map<Class<?>, List<E>> subtypeMap = new LinkedHashMap<>();
        for (E entity : entities) {
            subtypeMap.computeIfAbsent(entity.getClass(), k -> new ArrayList<>()).add(entity);
        }
        for (var entry : subtypeMap.entrySet()) {
            Class<? extends Data> concreteType = (Class<? extends Data>) entry.getKey();
            if (!hasExtensionFields(concreteType, sealedType) && hasDiscriminator(sealedType)) {
                continue;
            }
            var extBindVars = queryTemplate.createBindVars();
            try (var extQuery = queryTemplate.query(raw("""
                    DELETE FROM \0
                    WHERE \0""", concreteType, extBindVars))
                    .managed().prepare()) {
                entry.getValue().forEach(extQuery::addBatch);
                int[] extResult = extQuery.executeBatch();
                if (IntStream.of(extResult).anyMatch(r -> r != 1)) {
                    throw new PersistenceException("Batch delete from extension table failed for entity %s (subtype %s). This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(sealedType.getSimpleName(), concreteType.getSimpleName()));
                }
            }
        }
        // Phase 2: DELETE from base table.
        var baseBindVars = queryTemplate.createBindVars();
        try (var baseQuery = queryTemplate.query(raw("""
                DELETE FROM \0
                WHERE \0""", sealedType, baseBindVars))
                .managed().prepare()) {
            entities.forEach(baseQuery::addBatch);
            int[] result = baseQuery.executeBatch();
            if (IntStream.of(result).anyMatch(r -> r != 1)) {
                throw new PersistenceException("Batch delete from base table '%s' failed for entity %s. This may indicate a constraint violation, trigger interference, or that the target row does not exist.".formatted(model.name(), sealedType.getSimpleName()));
            }
        }
    }

    private static <E extends Entity<ID>, ID> void deleteJoinedBatchByRef(
            @Nonnull QueryTemplate queryTemplate,
            @Nonnull Model<E, ID> model,
            @Nonnull List<Ref<E>> refs
    ) throws SqlTemplateException {
        Class<E> sealedType = model.type();
        List<? extends Class<?>> permitted = REFLECTION.getPermittedSubclasses(sealedType);
        // Phase 1: DELETE from all extension tables (unknown concrete type).
        for (Class<?> subtype : permitted) {
            if (!hasExtensionFields(subtype, sealedType) && hasDiscriminator(sealedType)) {
                continue;
            }
            queryTemplate.query(raw("""
                    DELETE FROM \0
                    WHERE \0""", subtype, refs))
                    .managed().executeUpdate();
        }
        // Phase 2: DELETE from base table.
        queryTemplate.query(raw("""
                DELETE FROM \0
                WHERE \0""", sealedType, refs))
                .managed().executeUpdate();
    }
}
