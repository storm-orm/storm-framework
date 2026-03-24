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
package st.orm.core.repository.impl;

import static st.orm.DirtyCheck.INSTANCE;
import static st.orm.DirtyCheck.VALUE;
import static st.orm.UpdateMode.ENTITY;
import static st.orm.UpdateMode.FIELD;
import static st.orm.UpdateMode.OFF;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.Data;
import st.orm.DirtyCheck;
import st.orm.DynamicUpdate;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.StormConfig;
import st.orm.UpdateMode;
import st.orm.core.spi.EntityCache;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.mapping.RecordType;

/**
 * Determines which fields of an entity are considered dirty for update purposes.
 *
 * <p>This class compares the current entity instance with the state that was observed when the entity was read within
 * the same transaction.</p>
 *
 * <p>The result of this comparison is interpreted according to {@link UpdateMode}:</p>
 * <ul>
 *   <li>{@link UpdateMode#ENTITY}: column-level dirty checking is used only to decide whether any change exists; if so,
 *       a full-row UPDATE is generated.</li>
 *   <li>{@link UpdateMode#FIELD}: column-level dirty checking is used to select which columns are included in the
 *       UPDATE.</li>
 * </ul>
 *
 * <p>The default update mode can be configured via the {@code storm.update.default_mode} property
 * (see {@link StormConfig}).</p>
 *
 * <p>When dynamic updates are enabled, different UPDATE statement shapes may be generated depending on which fields are
 * dirty. The {@code storm.update.max_shapes} property limits the number of distinct shapes that may be generated.
 * Once the limit is exceeded, a full-entity update is used to preserve batching efficiency.</p>
 *
 * <p>Dirty checking is performed for all <strong>updatable columns</strong> of the entity. This includes any record
 * components that map to columns that can be updated (as defined by {@link Column#updatable()}). Non-updatable columns
 * are ignored.</p>
 *
 * <p>Dirty checks are performed via the entity {@link Metamodel} for each updatable column. If a generated metamodel
 * implementation is available (Java annotation processor / Kotlin KSP), the generated, type-specific equality checks
 * are used to avoid reflection and prevent boxing for primitive fields. If no generated implementation is available,
 * reflective accessors are used and type-appropriate equality checks for primitive fields are applied where possible.</p>
 *
 * <p>By default, dirty checking is based on <em>identity</em>. A field is considered dirty as soon as its extracted
 * value is no longer identical. Value-based dirty checking can be enabled by setting the
 * {@code storm.update.dirty_check} property to {@code value} (see {@link StormConfig}). In this mode, values are
 * compared by semantic equality after a fast identity
 * check when applicable.</p>
 *
 * <p>The dirty checking strategy can also be configured per entity using {@link DynamicUpdate#dirtyCheck()}.</p>
 *
 * <p>When performing dynamic updates, the version field is always included if present.</p>
 *
 * @param <E> the entity type
 * @param <ID> the entity identifier type
 * @since 1.7
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class DirtySupport<E extends Entity<ID>, ID> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirtySupport.class);
    private static final DirtyCheckMetrics dirtyCheckMetrics = DirtyCheckMetrics.getInstance();

    // Implementation note: dirty field checks can be performed efficiently due to the immutable record types.

    /**
     * Marker for a clean entity (no dirty fields).
     */
    private static final Optional<Set<Metamodel<?, ?>>> CLEAN = Optional.empty();

    /**
     * Marker indicating that at least one field is dirty, without enumerating specific fields.
     *
     * <p>This marker is used for {@link UpdateMode#OFF} and {@link UpdateMode#ENTITY} (where the existence of a change
     * is sufficient), and as a fallback when field enumeration is not available.</p>
     */
    private static final Optional<Set<Metamodel<?, ?>>> DIRTY = Optional.of(Set.of());

    private final Model<E, ID> model;
    private final UpdateMode updateMode;
    private final DirtyCheck dirtyCheck;
    private final Column versionColumn;
    private final ConcurrentMap<BitSet, Set<Metamodel<?, ?>>> dirtyFieldsCache = new ConcurrentHashMap<>();
    private final UpdateMode defaultUpdateMode;
    private final DirtyCheck defaultDirtyCheck;
    private final int maxShapes;

    DirtySupport(@Nonnull Model<E, ID> model, @Nonnull StormConfig config) {
        this.model = model;
        this.defaultUpdateMode = UpdateMode.valueOf(
                config.getProperty(StormConfig.UPDATE_DEFAULT_MODE, "ENTITY").trim().toUpperCase());
        this.defaultDirtyCheck = DirtyCheck.valueOf(
                config.getProperty(StormConfig.UPDATE_DIRTY_CHECK, "INSTANCE").trim().toUpperCase());
        this.maxShapes = Math.max(1, Integer.parseInt(
                config.getProperty(StormConfig.UPDATE_MAX_SHAPES, "5")));
        RecordType recordType = model.recordType();
        this.updateMode = getUpdateMode(recordType);
        this.dirtyCheck = getDirtyCheck(recordType);
        this.versionColumn = model.declaredColumns().stream().filter(Column::version).findAny().orElse(null);
        dirtyCheckMetrics.registerEntity(model.type().getName(), updateMode.name(), dirtyCheck.name(), maxShapes);
        LOGGER.debug("{}: updateMode={}, dirtyCheck={}, maxShapes={}", model.type().getSimpleName(), updateMode, dirtyCheck, maxShapes);
    }

    /**
     * Returns the effective {@link UpdateMode} for the given record type using the given config for defaults.
     *
     * <p>If the record type is annotated with {@link DynamicUpdate}, the annotation value is used. Otherwise, the
     * configured default update mode applies.</p>
     *
     * @param recordType the entity record type.
     * @param config the Storm configuration to use for the default update mode.
     * @return the effective update mode for the given record type.
     */
    public static UpdateMode getUpdateMode(@Nonnull RecordType recordType, @Nonnull StormConfig config) {
        DynamicUpdate dynamicUpdate = recordType.getAnnotation(DynamicUpdate.class);
        if (dynamicUpdate != null) {
            return dynamicUpdate.value();
        }
        return UpdateMode.valueOf(config.getProperty(StormConfig.UPDATE_DEFAULT_MODE, "ENTITY").trim().toUpperCase());
    }

    /**
     * Returns the effective {@link UpdateMode} for the given record type.
     *
     * <p>If the record type is annotated with {@link DynamicUpdate}, the annotation value is used. Otherwise, the
     * globally configured default update mode applies.</p>
     *
     * <p>The default update mode can be configured via the {@code storm.update.default_mode} property
     * (see {@link StormConfig}). If the property is not set, the default is {@link UpdateMode#ENTITY}.</p>
     *
     * @param recordType the entity record type.
     * @return the effective update mode for the given record type.
     */
    public UpdateMode getUpdateMode(@Nonnull RecordType recordType) {
        DynamicUpdate dynamicUpdate = recordType.getAnnotation(DynamicUpdate.class);
        return dynamicUpdate == null ? defaultUpdateMode : dynamicUpdate.value();
    }

    /**
     * Returns the effective {@link DirtyCheck} strategy for the given record type.
     *
     * <p>If the record type is annotated with {@link DynamicUpdate} and the {@link DynamicUpdate#dirtyCheck()}
     * attribute is set to a value other than {@link DirtyCheck#DEFAULT}, that value is used.</p>
     *
     * <p>Otherwise, the globally configured dirty check strategy applies.</p>
     *
     * <p>The default dirty check strategy can be configured via the {@code storm.update.dirty_check} property
     * (see {@link StormConfig}). If the property is not set, the default strategy is
     * {@link DirtyCheck#INSTANCE}.</p>
     *
     * @param recordType the entity record type.
     * @return the effective dirty check strategy for the given record type.
     */
    public DirtyCheck getDirtyCheck(@Nonnull RecordType recordType) {
        DynamicUpdate dynamicUpdate = recordType.getAnnotation(DynamicUpdate.class);
        if (dynamicUpdate == null) {
            return defaultDirtyCheck;
        }
        DirtyCheck configured = dynamicUpdate.dirtyCheck();
        return configured == DirtyCheck.DEFAULT ? defaultDirtyCheck : configured;
    }

    /**
     * Returns the maximum number of distinct update shapes that may be generated when dynamic updates are enabled.
     *
     * <p>This value is configured via the {@code storm.update.max_shapes} property (see {@link StormConfig}). It limits the number of
     * different column combinations that UPDATE statements may be generated for.</p>
     *
     * <p>If the limit is exceeded, a full-entity update is used to avoid excessive statement fan-out and preserve
     * batching efficiency.</p>
     *
     * @return the maximum number of allowed update shapes
     */
    public int getMaxShapes() {
        return maxShapes;
    }

    private boolean fieldsEqual(@Nonnull Metamodel<Data, ?> metamodel, E a, E b) {
        return switch (dirtyCheck) {
            case INSTANCE -> metamodel.isIdentical(a, b);
            case VALUE -> metamodel.isSame(a, b);
            case DEFAULT -> switch (defaultDirtyCheck) {
                case INSTANCE -> metamodel.isIdentical(a, b);
                case VALUE -> metamodel.isSame(a, b);
                case DEFAULT -> throw new IllegalStateException("Invalid default dirty check mode.");
            };
        };
    }

    /**
     * Returns the set of fields that are considered dirty for this entity instance.
     *
     * <p>The comparison is performed against the cached state of the same entity within the current transaction. Only
     * fields that map to updatable columns are evaluated.</p>
     *
     * <p>Each field is compared using its associated {@link Metamodel}, and a field is marked dirty as soon as its
     * extracted value differs according to the configured {@link DirtyCheck} strategy.</p>
     *
     * <p>For fields backed by nested record components, the comparison is performed at the level of the top-level field
     * that maps to the column. Nested records are treated as a single logical unit.</p>
     *
     * <p>The returned optional is interpreted as follows:</p>
     * <ul>
     *   <li>An empty optional indicates that no fields are dirty.</li>
     *   <li>A non-empty optional containing an empty set indicates that at least one field is dirty, but the specific
     *       fields are not enumerated (for example in {@link UpdateMode#OFF} or {@link UpdateMode#ENTITY}).</li>
     *   <li>A non-empty optional containing a non-empty set enumerates the dirty fields (used for
     *       {@link UpdateMode#FIELD}). When present, the version field is included as well.</li>
     * </ul>
     *
     * @param entity the entity to check
     * @param cache the entity cache holding the previously observed state
     * @return an optional describing the dirty state, or an empty optional if no fields are dirty
     */
    Optional<Set<Metamodel<?, ?>>> getDirty(@Nonnull E entity,
                                            @Nullable EntityCache<E, ID> cache) {
        if (updateMode == OFF) {
            // Update mode is OFF, treat all fields as dirty.
            dirtyCheckMetrics.recordDirty();
            return DIRTY;
        }
        if (cache == null) {
            // No cache available, assume all fields are dirty.
            dirtyCheckMetrics.recordDirtyCacheMiss();
            return DIRTY;
        }
        var cached = cache.get(entity.id()).orElse(null);
        if (cached == null) {
            // Not cached, assume all fields are dirty.
            dirtyCheckMetrics.recordDirtyCacheMiss();
            return DIRTY;
        }
        if (cached == entity) {
            // Cached, no fields are dirty.
            dirtyCheckMetrics.recordCleanIdentityMatch();
            return CLEAN;
        }
        // Record update mode and dirty check strategy for checks that reach field comparison.
        recordModeAndStrategy();
        BitSet dirtyFields = updateMode == ENTITY ? null : new BitSet();
        for (Column column : model.declaredColumns()) {
            if (!column.updatable()) {
                continue;
            }
            if (fieldsEqual(column.metamodel(), entity, cached)) {
                dirtyCheckMetrics.recordFieldClean();
                continue;
            }
            dirtyCheckMetrics.recordFieldDirty();
            if (dirtyFields == null) {
                dirtyCheckMetrics.recordDirty();
                return DIRTY;
            }
            dirtyFields.set(column.index() - 1);
        }
        if (dirtyFields == null || dirtyFields.isEmpty()) {
            dirtyCheckMetrics.recordClean();
            return CLEAN;
        }
        dirtyCheckMetrics.recordDirty();
        BitSet key = (BitSet) dirtyFields.clone(); // Defensive copy.
        int sizeBefore = dirtyFieldsCache.size();
        var result = Optional.of(dirtyFieldsCache.computeIfAbsent(key, bits -> {
            Set<Metamodel<?, ?>> set = new HashSet<>();
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                set.add(model.columns().get(i).metamodel());
            }
            if (versionColumn != null) {
                set.add(versionColumn.metamodel());
            }
            return Set.copyOf(set);
        }));
        if (dirtyFieldsCache.size() > sizeBefore) {
            dirtyCheckMetrics.recordNewShape(model.type().getSimpleName());
        }
        return result;
    }

    /**
     * Records the update mode and dirty check strategy for this entity type.
     */
    private void recordModeAndStrategy() {
        if (updateMode == ENTITY) {
            dirtyCheckMetrics.recordEntityModeCheck();
        } else if (updateMode == FIELD) {
            dirtyCheckMetrics.recordFieldModeCheck();
        }
        DirtyCheck effectiveStrategy = dirtyCheck == DirtyCheck.DEFAULT ? defaultDirtyCheck : dirtyCheck;
        if (effectiveStrategy == INSTANCE) {
            dirtyCheckMetrics.recordInstanceStrategyCheck();
        } else if (effectiveStrategy == VALUE) {
            dirtyCheckMetrics.recordValueStrategyCheck();
        }
    }
}
