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

import static java.lang.System.arraycopy;
import static java.util.Collections.addAll;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static st.orm.EnumType.NAME;
import static st.orm.UpdateMode.OFF;
import static st.orm.core.repository.impl.DirtySupport.getUpdateMode;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.ObjectMapperFactory.nullableHint;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorColumnJavaType;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorType;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.getRefPkType;
import static st.orm.core.template.impl.RecordReflection.isJoinedEntity;
import static st.orm.core.template.impl.RecordReflection.isPolymorphicData;
import static st.orm.core.template.impl.RecordReflection.isRecord;
import static st.orm.core.template.impl.RecordReflection.normalizeDiscriminatorValue;
import static st.orm.core.template.impl.RecordReflection.resolveConcreteType;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.DbEnum;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.EnumType;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.core.spi.EntityCache;
import st.orm.core.spi.RefFactory;
import st.orm.core.spi.StormConfigHelper;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.WeakInterner;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Factory for creating {@link ObjectMapper} instances that construct Java records from JDBC result set columns.
 *
 * <p>This class handles the complex mapping from flat JDBC column arrays to nested record structures, including:</p>
 * <ul>
 *   <li>Recursive expansion of nested records</li>
 *   <li>Custom type converters via {@code @Convert} annotation</li>
 *   <li>Enum mapping (by name or ordinal)</li>
 *   <li>{@link Ref} creation for entity references</li>
 *   <li>Nullable field handling</li>
 * </ul>
 *
 * <h2>Compilation and Caching</h2>
 * <p>Record mapping plans are compiled once per record type and cached globally. The compilation produces:</p>
 * <ul>
 *   <li>An {@link ArgumentPlan} containing {@link Step} instances for each constructor parameter</li>
 *   <li>Expanded parameter types reflecting the flattened JDBC column structure</li>
 * </ul>
 *
 * <h2>Interning and Caching</h2>
 * <p>To ensure object identity consistency and reduce memory usage, constructed records are interned:</p>
 * <ul>
 *   <li><b>Entities within a transaction</b>: Interned via {@link EntityCache} (transaction-scoped)</li>
 *   <li><b>Other records and entities</b>: Interned via {@link WeakInterner} (query-scoped)</li>
 * </ul>
 *
 * <h3>Entity Cache Scoping</h3>
 * <p>The entity cache is transaction-scoped. Its behavior depends on the transaction isolation level:</p>
 * <ul>
 *   <li>At {@code REPEATABLE_READ} or higher: Cached instances are returned, providing object identity consistency</li>
 *   <li>At {@code READ_COMMITTED} or lower: Fresh data is fetched from the database on each read</li>
 * </ul>
 *
 * <p>The entity cache serves two purposes:</p>
 * <ul>
 *   <li><b>Dirty tracking</b>: The cached state serves as the baseline for detecting changes when updating entities
 *       (see {@link st.orm.DynamicUpdate}). Cache writes occur at all isolation levels when dirty tracking is enabled.</li>
 *   <li><b>Identity preservation</b>: At {@code REPEATABLE_READ}+, entities already in cache are returned directly,
 *       ensuring the same database row returns the same object instance within a transaction.</li>
 * </ul>
 *
 * <p>The entity cache is <em>not</em> available when there is no active transaction (e.g., {@code NOT_SUPPORTED}
 * propagation).</p>
 *
 * <h2>Early Cache Lookup Optimization</h2>
 * <p>For both top-level and nested entities, the mapper extracts the primary key directly from the flat column array
 * <em>before</em> constructing the entity or its nested objects. If a cached entity with that PK exists, construction
 * is skipped entirely, improving performance for queries that return duplicate entity references.</p>
 *
 * @see ObjectMapper
 * @see EntityCache
 * @see WeakInterner
 */
final class RecordMapper {

    private RecordMapper() {
    }

    /**
     * Returns a factory for creating instances of the specified record type.
     *
     * @param columnCount the number of columns to use as constructor arguments.
     * @param type the record type of the instance to create.
     * @param refFactory the factory for creating ref instances for entities and projections.
     * @param fetchPlan the references the statement resolved as part of its select list, whose targets the mapper
     *                  consumes in place of their foreign key column.
     * @return a factory for creating instances of the specified type.
     * @param <T> the type of the instance to create.
     * @throws SqlTemplateException if an error occurred while creating the factory.
     */
    static <T> Optional<ObjectMapper<T>> getFactory(int columnCount,
                                                    RecordType type,
                                                    RefFactory refFactory,
                                                    @Nullable TransactionContext transactionContext,
                                                    FetchPlan fetchPlan) throws SqlTemplateException {
        // The compiled plan already holds the flat column count (its parameterTypes length), cached per type and
        // fetch plan. Reuse it instead of re-walking the record structure on every query, as getParameterCount would.
        if (compiledFor(type, refFactory, fetchPlan).parameterTypes().length == columnCount) {
            return Optional.of(wrapConstructor(type, refFactory, transactionContext, fetchPlan));
        }
        return empty();
    }

    /**
     * Returns a factory for creating instances from a sealed entity type (single-table or joined).
     * The factory reads a discriminator column to determine which concrete subtype to instantiate.
     *
     * @param columnCount the number of columns (including discriminator).
     * @param sealedType the sealed entity interface class.
     * @param refFactory the factory for creating ref instances.
     * @param transactionContext the current transaction context.
     * @return a factory for creating instances of the concrete subtypes.
     * @param <T> the sealed entity interface type.
     * @throws SqlTemplateException if compilation fails.
     */
    @SuppressWarnings("unchecked")
    static <T> Optional<ObjectMapper<T>> getSealedFactory(int columnCount,
                                                          Class<T> sealedType,
                                                          RefFactory refFactory,
                                                          @Nullable TransactionContext transactionContext) throws SqlTemplateException {
        SealedCompiled sealedCompiled = sealedCompiledFor(sealedType, refFactory);
        if (sealedCompiled.totalColumnCount() != columnCount) {
            return empty();
        }
        // Create per-subtype ObjectMappers with a shared interner so that nested records
        // (e.g., a City referenced by both Car and Truck) are deduplicated across subtypes.
        var interner = new WeakInterner();
        Map<Object, ObjectMapper<?>> subtypeMappers = new HashMap<>();
        for (var entry : sealedCompiled.subtypeInfo().entrySet()) {
            Object discriminatorValue = entry.getKey();
            SubtypeInfo info = entry.getValue();
            subtypeMappers.put(discriminatorValue, wrapConstructor(info.recordType(), refFactory, transactionContext, interner));
        }
        var discriminatorType = getDiscriminatorType(sealedType);
        return Optional.of(new ObjectMapper<>() {
            @Override
            public Class<?>[] getParameterTypes() {
                return sealedCompiled.parameterTypes();
            }

            @Override
            public T newInstance(Object[] args) throws SqlTemplateException {
                // First column is always the discriminator.
                Object discriminatorRaw = args[sealedCompiled.discriminatorOffset()];
                if (discriminatorRaw == null) {
                    throw new SqlTemplateException("Discriminator column is null for sealed type %s."
                            .formatted(sealedType.getSimpleName()));
                }
                Object discriminatorValue = normalizeDiscriminatorValue(discriminatorRaw, discriminatorType);
                SubtypeInfo info = sealedCompiled.subtypeInfo().get(discriminatorValue);
                if (info == null) {
                    throw new SqlTemplateException("Unknown discriminator value '%s' for sealed type %s."
                            .formatted(discriminatorValue, sealedType.getSimpleName()));
                }
                ObjectMapper<?> mapper = subtypeMappers.get(discriminatorValue);
                // Extract subtype-specific args from the union args.
                Object[] subtypeArgs = new Object[info.columnCount()];
                for (int i = 0; i < info.columnOffsets().length; i++) {
                    subtypeArgs[i] = args[info.columnOffsets()[i]];
                }
                // For joined-table inheritance, verify that extension-specific columns are not all
                // null. If they are, the extension table row is likely missing (data corruption).
                if (sealedCompiled.joined() && info.extensionColumnIndices().length > 0) {
                    boolean allExtensionColumnsNull = true;
                    for (int idx : info.extensionColumnIndices()) {
                        if (subtypeArgs[idx] != null) {
                            allExtensionColumnsNull = false;
                            break;
                        }
                    }
                    if (allExtensionColumnsNull) {
                        throw new SqlTemplateException(
                                ("Discriminator indicates type '%s' for sealed type %s, but no matching extension row" +
                                    " was found. This may indicate data corruption (missing extension table row).")
                                        .formatted(discriminatorValue, sealedType.getSimpleName()));
                    }
                }
                return (T) mapper.newInstance(subtypeArgs);
            }
        });
    }

    /**
     * Holds compiled information for a sealed entity hierarchy.
     */
    private record SealedCompiled(
            Class<?>[] parameterTypes,
            int totalColumnCount,
            int discriminatorOffset,
            boolean joined,
            Map<Object, SubtypeInfo> subtypeInfo
    ) {}

    /**
     * Information about a single concrete subtype in a sealed hierarchy.
     */
    private record SubtypeInfo(
            RecordType recordType,
            Object discriminatorValue,
            int columnCount,
            int[] columnOffsets,  // Maps subtype column index -> union column index
            int[] extensionColumnIndices  // Subtype column indices that are extension-specific (not common)
    ) {}

    /**
     * Sealed entity compiled plans, held per sealed interface class. {@link ClassValue} ties each plan to the
     * lifetime of the sealed class, so cached plans never pin the class or its class loader. The holder starts
     * empty because the plan is compiled with a {@link RefFactory}, which is not available to
     * {@link ClassValue#computeValue}.
     */
    private static final ClassValue<AtomicReference<SealedCompiled>> SEALED_COMPILED = new ClassValue<>() {
        @Override
        protected AtomicReference<SealedCompiled> computeValue(Class<?> type) {
            return new AtomicReference<>();
        }
    };

    /**
     * Returns the compiled sealed entity information, creating and caching it if necessary.
     */
    private static SealedCompiled sealedCompiledFor(Class<?> sealedType,
                                                     RefFactory refFactory) throws SqlTemplateException {
        AtomicReference<SealedCompiled> holder = SEALED_COMPILED.get(sealedType);
        SealedCompiled compiled = holder.get();
        if (compiled == null) {
            // Concurrent first calls may compile the plan more than once; the first published plan wins and the
            // compilation is a pure function of the sealed type, so every candidate is equivalent.
            compiled = compileSealedPlan(sealedType, refFactory);
            if (!holder.compareAndSet(null, compiled)) {
                compiled = holder.get();
            }
        }
        return compiled;
    }

    /**
     * Compiles a plan for a sealed entity hierarchy. Determines the union of columns across all subtypes,
     * including the discriminator column.
     */
    private static SealedCompiled compileSealedPlan(Class<?> sealedType,
                                                     RefFactory refFactory) throws SqlTemplateException {
        Class<?>[] permittedArray = sealedType.getPermittedSubclasses();
        if (permittedArray == null) {
            throw new SqlTemplateException("Sealed type %s has no permitted subclasses.".formatted(sealedType.getSimpleName()));
        }
        List<Class<?>> subtypes = List.of(permittedArray);
        if (subtypes.isEmpty()) {
            throw new SqlTemplateException("Sealed type %s has no permitted subclasses.".formatted(sealedType.getSimpleName()));
        }
        // Build the union column list. For single-table, the union is:
        // [discriminator] + [union of all subtype fields]
        // For each subtype, compute its columns and their offsets in the union.
        // First, build a map of fieldName -> union offset. Fields shared by multiple subtypes occupy the same offset.
        List<String> unionFieldNames = new ArrayList<>();
        List<Class<?>> unionFieldTypes = new ArrayList<>();
        // Add discriminator column first.
        unionFieldNames.add(RecordReflection.getDiscriminatorColumn(sealedType));
        unionFieldTypes.add(getDiscriminatorColumnJavaType(sealedType));
        int discriminatorOffset = 0;
        // Track field name to union index mapping.
        Map<String, Integer> fieldToUnionIndex = new HashMap<>();
        for (Class<?> subtype : subtypes) {
            RecordType subRecordType = getRecordType(subtype);
            Class<?>[] subtypeParamTypes = expandParameterTypes(subRecordType, refFactory, FetchPlan.NONE);
            List<RecordField> fields = subRecordType.fields();
            int flatIndex = 0;
            for (RecordField field : fields) {
                int fieldColumnCount = getFieldColumnCount(field, FetchPlan.NONE);
                for (int col = 0; col < fieldColumnCount; col++) {
                    String key = field.name() + (fieldColumnCount > 1 ? "." + col : "");
                    if (!fieldToUnionIndex.containsKey(key)) {
                        fieldToUnionIndex.put(key, unionFieldNames.size());
                        unionFieldNames.add(key);
                        unionFieldTypes.add(subtypeParamTypes[flatIndex]);
                    }
                    flatIndex++;
                }
            }
        }
        // Compute common field names (fields present in ALL subtypes) for extension row validation.
        boolean joined = isJoinedEntity(sealedType);
        Map<String, Integer> fieldOccurrenceCount = new HashMap<>();
        for (Class<?> subtype : subtypes) {
            RecordType subRecordType = getRecordType(subtype);
            for (var field : subRecordType.fields()) {
                fieldOccurrenceCount.merge(field.name(), 1, Integer::sum);
            }
        }
        Set<String> commonFieldNames = new HashSet<>();
        for (var entry : fieldOccurrenceCount.entrySet()) {
            if (entry.getValue() == subtypes.size()) {
                commonFieldNames.add(entry.getKey());
            }
        }
        // Now build SubtypeInfo for each subtype.
        Map<Object, SubtypeInfo> subtypeInfoMap = new HashMap<>();
        for (Class<?> subtype : subtypes) {
            RecordType subRecordType = getRecordType(subtype);
            Object discriminatorValue = RecordReflection.getDiscriminatorValue(subtype, sealedType);
            Class<?>[] subtypeParamTypes = expandParameterTypes(subRecordType, refFactory, FetchPlan.NONE);
            List<RecordField> fields = subRecordType.fields();
            int[] offsets = new int[subtypeParamTypes.length];
            List<Integer> extensionIndices = new ArrayList<>();
            int flatIndex = 0;
            for (RecordField field : fields) {
                int fieldColumnCount = getFieldColumnCount(field, FetchPlan.NONE);
                boolean isExtension = !commonFieldNames.contains(field.name());
                for (int col = 0; col < fieldColumnCount; col++) {
                    String key = field.name() + (fieldColumnCount > 1 ? "." + col : "");
                    offsets[flatIndex] = fieldToUnionIndex.get(key);
                    if (isExtension) {
                        extensionIndices.add(flatIndex);
                    }
                    flatIndex++;
                }
            }
            subtypeInfoMap.put(discriminatorValue, new SubtypeInfo(
                    subRecordType, discriminatorValue, subtypeParamTypes.length, offsets,
                    extensionIndices.stream().mapToInt(Integer::intValue).toArray()));
        }
        return new SealedCompiled(
                unionFieldTypes.toArray(new Class<?>[0]),
                unionFieldNames.size(),
                discriminatorOffset,
                joined,
                Map.copyOf(subtypeInfoMap));
    }

    /**
     * Holds the compiled mapping plan and expanded parameter types for a record type.
     *
     * @param plan the compiled argument plan for adapting flat JDBC args to constructor args.
     * @param parameterTypes the expanded JDBC column types (flattened from nested records).
     * @param skipRegions the column regions of nested entities eligible for skipping decode on cache hits.
     */
    private record Compiled(ArgumentPlan plan,
                            Class<?>[] parameterTypes,
                            PkInfo pkInfo,
                            List<ColumnSkipper.SkipRegion> skipRegions) {}

    /**
     * Compiled plans per record class, keyed by the references the statement resolves. A resolved reference
     * consumes the referenced table's columns rather than its foreign key column alone, so a plan compiled for one
     * set of resolved references cannot read a row shaped by another. {@link ClassValue} ties the plans to the
     * lifetime of the record class, so they never pin the class or its class loader.
     */
    private static final ClassValue<ConcurrentMap<FetchPlan, Compiled>> COMPILED = new ClassValue<>() {
        @Override
        protected ConcurrentMap<FetchPlan, Compiled> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /**
     * Returns the compiled plan for the given record type, creating and caching it if necessary.
     *
     * @param type the record type to compile.
     * @param refFactory the factory for resolving Ref parameter types.
     * @param fetchPlan the references the statement resolves as part of its select list.
     * @return the compiled plan.
     * @throws SqlTemplateException if compilation fails.
     */
    private static Compiled compiledFor(RecordType type,
                                        RefFactory refFactory,
                                        FetchPlan fetchPlan) throws SqlTemplateException {
        try {
            return COMPILED.get(type.type()).computeIfAbsent(fetchPlan, t -> {
                try {
                    PkInfo pkInfo = Entity.class.isAssignableFrom(type.type())
                            ? calculatePkInfo(type, fetchPlan)
                            : PkInfo.NONE;
                    List<ColumnSkipper.SkipRegion> skipRegions = new ArrayList<>();
                    collectSkipRegions(type, 0, skipRegions, fetchPlan);
                    return new Compiled(compilePlan(type, fetchPlan),
                            expandParameterTypes(type, refFactory, fetchPlan), pkInfo,
                            List.copyOf(skipRegions));
                } catch (SqlTemplateException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof SqlTemplateException ste) throw ste;
            throw e;
        }
    }

    /**
     * Returns the number of parameters for the specified record type. This method takes into account its components
     * recursively.
     *
     * @param type the record type to calculate the number of parameters for.
     * @return the number of parameters for the specified record type.
     */
    private static int getParameterCount(RecordType type, FetchPlan fetchPlan) throws SqlTemplateException {
        int count = 0;
        for (RecordField field : type.fields()) {
            var converter = getORMConverter(field);
            if (converter.isPresent()) {
                count += converter.get().getParameterCount();
            } else {
                if (isRecord(field.type())) {
                    // Recursion for nested records.
                    count += getParameterCount(getRecordType(field.type()), fetchPlan.descend(field.name()));
                } else if (Ref.class.isAssignableFrom(field.type()) && fetchPlan.fetches(field.name())) {
                    // A resolved reference consumes the referenced record's columns, exactly as an entity foreign key.
                    count += getParameterCount(getRecordType(getRefDataType(field)), fetchPlan.descend(field.name()));
                } else if (Ref.class.isAssignableFrom(field.type()) && isPolymorphicData(getRefDataType(field))) {
                    // Polymorphic FK: discriminator + PK columns.
                    count += 2;
                } else {
                    count += 1; // Component of the record, count as one.
                }
            }
        }
        return count;
    }

    /**
     * Wraps the specified constructor in a factory.
     *
     * @param type the type holding the constructor to wrap.
     * @param refFactory the bridge for creating supplier instances for records.
     * @return a factory for creating instances using the specified constructor.
     * @param <T> the type of the instance to create.
     */
    private static <T> ObjectMapper<T> wrapConstructor(RecordType type,
                                                       RefFactory refFactory,
                                                       @Nullable TransactionContext transactionContext,
                                                       FetchPlan fetchPlan) throws SqlTemplateException {
        return wrapConstructor(type, refFactory, transactionContext, new WeakInterner(), fetchPlan);
    }

    /**
     * Resolves the transaction-scoped entity cache for the top-level entity type. Only called when the type is an
     * entity within a transaction and caching is required.
     */
    @SuppressWarnings("unchecked")
    private static EntityCache<Entity<?>, ?> resolveEntityCache(TransactionContext transactionContext,
                                                                RecordType type) {
        return (EntityCache<Entity<?>, ?>) transactionContext.entityCache(
                (Class<? extends Entity<?>>) type.type(), StormConfigHelper.cacheRetention(StormConfig.defaults()));
    }

    private static <T> ObjectMapper<T> wrapConstructor(RecordType type,
                                                       RefFactory refFactory,
                                                       @Nullable TransactionContext transactionContext,
                                                       WeakInterner interner) throws SqlTemplateException {
        return wrapConstructor(type, refFactory, transactionContext, interner, FetchPlan.NONE);
    }

    private static <T> ObjectMapper<T> wrapConstructor(RecordType type,
                                                       RefFactory refFactory,
                                                       @Nullable TransactionContext transactionContext,
                                                       WeakInterner interner,
                                                       FetchPlan fetchPlan) throws SqlTemplateException {
        Compiled compiled = compiledFor(type, refFactory, fetchPlan);
        boolean isEntity = Entity.class.isAssignableFrom(type.type());
        // Determine cache read/write policy.
        // Cache read: return cached instances (identity preservation) - only at REPEATABLE_READ+
        // Cache write: store for dirty tracking OR for identity preservation.
        // Only entities in a transaction can be cached; the leading conditions short-circuit the dirty-tracking
        // lookup so it is never computed on a non-transactional read.
        boolean cacheReadEnabled = transactionContext != null && transactionContext.isRepeatableRead();
        EntityCache<Entity<?>, ?> entityCache =
                transactionContext != null && isEntity
                        && (cacheReadEnabled || getUpdateMode(type, StormConfig.defaults()) != OFF)
                        ? resolveEntityCache(transactionContext, type)
                        : null;
        PkInfo pkInfo = compiled.pkInfo();
        ColumnSkipper columnSkipper = createColumnSkipper(type, compiled, cacheReadEnabled, entityCache,
                interner, transactionContext);
        return new ObjectMapper<>() {
            @Override
            public Class<?>[] getParameterTypes() {
                return compiled.parameterTypes();
            }

            @Override
            public ColumnSkipper columnSkipper() {
                return columnSkipper;
            }

            @SuppressWarnings("unchecked")
            @Override
            public T newInstance(Object[] args) throws SqlTemplateException {
                // Early cache lookup optimization for top-level entities.
                // If we can extract the PK early, check the cache before constructing nested objects.
                // Only perform cache lookup if cache read is enabled (identity preservation).
                if (entityCache != null && cacheReadEnabled && pkInfo.offset >= 0) {
                    Object pk = extractPk(args, pkInfo);
                    if (pk != null) {
                        //noinspection unchecked,rawtypes
                        Optional<Entity<?>> cached = ((EntityCache) entityCache).get(pk);
                        if (cached.isPresent()) {
                            // Cache hit - skip construction entirely.
                            return (T) cached.get();
                        }
                    }
                }
                Object[] adaptedArgs = compiled.plan()
                        .adapt(args, 0, false, refFactory, interner, transactionContext)
                        .constructorArgs();
                // Don't intern top level records.
                var record = ObjectMapperFactory.construct((Constructor<T>) type.constructor(), adaptedArgs, 0);
                if (entityCache != null) {
                    // Intern for dirty tracking and/or identity preservation.
                    Entity<?> interned = entityCache.intern((Entity<?>) record);
                    // Only return cached instance if cache read is enabled.
                    if (cacheReadEnabled) {
                        return (T) interned;
                    }
                }
                return record;
            }

            /**
             * Extracts the primary key from args at the configured offset.
             *
             * @param args the flat argument array.
             * @param pkInfo the PK offset and column count information.
             * @return the PK value, or null if any PK column is null or PK cannot be extracted.
             */
            private Object extractPk(Object[] args, PkInfo pkInfo) throws SqlTemplateException {
                int pkStart = pkInfo.offset;
                int pkColumnCount = pkInfo.columnCount;
                if (pkColumnCount == 1) {
                    // Simple PK - just return the value.
                    return args[pkStart];
                }
                // Composite PK - construct from columns.
                if (pkInfo.constructor == null) {
                    // Cannot construct composite PK without constructor.
                    return null;
                }
                Object[] pkArgs = new Object[pkColumnCount];
                for (int i = 0; i < pkColumnCount; i++) {
                    Object arg = args[pkStart + i];
                    if (arg == null) {
                        return null;  // Null in composite PK means no valid PK.
                    }
                    pkArgs[i] = arg;
                }
                return ObjectMapperFactory.construct(pkInfo.constructor, pkArgs, pkStart);
            }
        };
    }

    /**
     * Creates the column skipper for the given compiled plan, or {@code null} when no column region can ever be
     * skipped for the mapped type.
     *
     * <p>The top-level region mirrors the early cache lookup in the mapper's {@code newInstance}: it only applies
     * when cache reads are enabled, as top-level records are never interned.</p>
     *
     * @param type the mapped record type.
     * @param compiled the compiled plan for the mapped type.
     * @param cacheReadEnabled whether transaction-scoped cache reads are enabled.
     * @param entityCache the entity cache for the top-level record, or null if not applicable.
     * @param interner the query-scoped interner, shared with the mapper.
     * @param transactionContext the transaction context, or null if not in a transaction.
     * @return the column skipper, or {@code null} if no region is eligible.
     */
    @Nullable
    private static ColumnSkipper createColumnSkipper(RecordType type,
                                                     Compiled compiled,
                                                     boolean cacheReadEnabled,
                                                     @Nullable EntityCache<Entity<?>, ?> entityCache,
                                                     WeakInterner interner,
                                                     @Nullable TransactionContext transactionContext) {
        PkInfo pkInfo = compiled.pkInfo();
        boolean topLevel = entityCache != null && cacheReadEnabled && pkInfo.offset() >= 0
                && (pkInfo.columnCount() == 1 || pkInfo.constructor() != null);
        List<ColumnSkipper.SkipRegion> skipRegions = compiled.skipRegions();
        if (!topLevel && skipRegions.isEmpty()) {
            return null;
        }
        List<ColumnSkipper.SkipRegion> regions;
        if (topLevel) {
            regions = new ArrayList<>(skipRegions.size() + 1);
            //noinspection unchecked
            regions.add(new ColumnSkipper.SkipRegion(0, compiled.parameterTypes().length, pkInfo.offset(),
                    pkInfo.columnCount(), pkInfo.constructor(), (Class<? extends Entity<?>>) type.type(), true));
            regions.addAll(skipRegions);
        } else {
            regions = skipRegions;
        }
        return new ColumnSkipper(regions, interner, transactionContext, entityCache, cacheReadEnabled);
    }

    /**
     * Expands the specified parameter types to include the types of record components.
     *
     * @param type the record type that holds the constructor to expand the parameter types for.
     * @return the expanded parameter types.
     * @throws SqlTemplateException if an error occurred while expanding the parameter types.
     */
    private static Class<?>[] expandParameterTypes(RecordType type,
                                                   RefFactory refFactory,
                                                   FetchPlan fetchPlan) throws SqlTemplateException {
        List<Class<?>> expandedTypes = new ArrayList<>();
        var fields = type.fields();
        var parameterTypes = type.constructor().getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            var field = fields.get(i);
            var converter = getORMConverter(field);
            if (converter.isPresent()) {
                expandedTypes.addAll(converter.get().getParameterTypes());
                continue;
            }
            if (isRecord(parameterTypes[i])) {
                // Recursively expand record components.
                addAll(expandedTypes, expandParameterTypes(getRecordType(parameterTypes[i]), refFactory,
                        fetchPlan.descend(field.name())));
            } else if (Ref.class.isAssignableFrom(parameterTypes[i]) && fetchPlan.fetches(field.name())) {
                // A resolved reference occupies the referenced record's columns rather than a single key column.
                addAll(expandedTypes, expandParameterTypes(getRecordType(getRefDataType(field)), refFactory,
                        fetchPlan.descend(field.name())));
            } else if (Ref.class.isAssignableFrom(parameterTypes[i])) {
                Class<? extends Data> refDataType = getRefDataType(fields.get(i));
                if (isPolymorphicData(refDataType)) {
                    // Polymorphic FK: discriminator (String) + PK type.
                    expandedTypes.add(String.class);
                    expandedTypes.add(getRefPkType(fields.get(i)));
                } else {
                    // Regular Ref: just the PK type.
                    expandedTypes.add(getRefPkType(fields.get(i)));
                }
            } else {
                // Non-record type, add directly.
                expandedTypes.add(parameterTypes[i]);
            }
        }
        return expandedTypes.toArray(new Class<?>[0]);
    }

    /** Pattern for validating ordinal enum values. */
    private static final Pattern INT_PATTERN = Pattern.compile("\\d+");

    private static boolean isArgNull(@Nullable Object arg) {
        return arg == null;
    }

    /**
     * A compiled, reusable plan for adapting flat JDBC column values into constructor arguments.
     *
     * <p>An argument plan is compiled once per record type and cached. It transforms a flat array of JDBC
     * column values (in declaration order) into properly nested constructor arguments, handling type
     * conversion, nullable fields, and recursive record construction.</p>
     */
    private interface ArgumentPlan {

        /**
         * Adapts flat JDBC column values into constructor arguments for a record type.
         *
         * @param flatArgs the flat array of JDBC column values.
         * @param offset the starting offset into flatArgs.
         * @param parentNullable whether the parent context allows null values.
         * @param refFactory factory for creating {@link Ref} instances.
         * @param interner interner for deduplicating records and entities.
         * @param tx the transaction context, or null if not in a transaction.
         * @return the result containing constructor args and updated offset.
         * @throws SqlTemplateException if adaptation fails due to null constraint violations.
         */
        Result adapt(Object[] flatArgs,
                     int offset,
                     boolean parentNullable,
                     RefFactory refFactory,
                     WeakInterner interner,
                     @Nullable TransactionContext tx) throws SqlTemplateException;

        /**
         * The result of adapting flat args.
         *
         * @param constructorArgs the constructor arguments ready for record instantiation.
         * @param offset the updated offset into flatArgs after consuming this record's columns.
         */
        record Result(Object[] constructorArgs, int offset) {}
    }

    /**
     * A single step in the argument adaptation process, responsible for processing one constructor parameter.
     *
     * <p>Steps are composed into an {@link ArgumentPlan}. Each step type handles a specific kind of
     * constructor parameter:</p>
     * <ul>
     *   <li>{@link PlainStep}: Simple pass-through for primitive/simple types</li>
     *   <li>{@link ConverterStep}: Custom type conversion via {@code @Convert}</li>
     *   <li>{@link EnumStep}: Enum mapping by name or ordinal</li>
     *   <li>{@link RefStep}: Creates {@link Ref} instances for entity references</li>
     *   <li>{@link RecordStep}: Recursive construction of nested records/entities</li>
     * </ul>
     */
    private interface Step {

        /**
         * Applies this step to extract and transform a value from the flat args array.
         *
         * @param flatArgs the flat array of JDBC column values.
         * @param offset mutable offset tracker into flatArgs.
         * @param parentNullable whether the parent context allows null values.
         * @param refFactory factory for creating {@link Ref} instances.
         * @param interner interner for deduplicating records and entities.
         * @param tx the transaction context, or null if not in a transaction.
         * @return the processed value for this constructor parameter.
         * @throws SqlTemplateException if processing fails.
         */
        Object apply(Object[] flatArgs,
                     Offset offset,
                     boolean parentNullable,
                     RefFactory refFactory,
                     WeakInterner interner,
                     @Nullable TransactionContext tx) throws SqlTemplateException;

        /**
         * Mutable offset holder to track position in flatArgs across steps.
         *
         * <p>Using a mutable holder avoids allocating result pairs for each step.</p>
         */
        final class Offset {
            int i;
            Offset(int i) { this.i = i; }
        }
    }

    /**
     * Default implementation of {@link ArgumentPlan} that applies a sequence of steps.
     *
     * <p>Each step corresponds to one constructor parameter of the target record type.</p>
     */
    private static final class CompiledArgumentPlan implements ArgumentPlan {
        private final RecordType type;
        private final Step[] steps;
        /** True when every step is a one-column pass-through, so the flat args line up with the constructor params. */
        private final boolean trivial;

        private CompiledArgumentPlan(RecordType type, Step[] steps) {
            this.type = type;
            this.steps = steps;
            boolean allPlain = true;
            for (Step step : steps) {
                if (!(step instanceof PlainStep)) {
                    allPlain = false;
                    break;
                }
            }
            this.trivial = allPlain;
        }

        @Override
        public Result adapt(Object[] flatArgs,
                            int offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext tx) throws SqlTemplateException {
            if (trivial && offset == 0 && flatArgs.length == steps.length) {
                // Every step is a one-column pass-through and the flat args already line up one-to-one with the
                // constructor parameters, so validate non-null components in place and reuse the array directly
                // instead of allocating and copying into a second one.
                for (int p = 0; p < steps.length; p++) {
                    RecordField field = type.fields().get(p);
                    if (!(parentNullable || field.nullable()) && isArgNull(flatArgs[p])) {
                        throw new SqlTemplateException(
                                "Database returned NULL for non-nullable component '%s.%s'. Either %s, or ensure the corresponding column is NOT NULL in the database."
                                        .formatted(type.type().getSimpleName(), field.name(), nullableHint(type.type()))
                        );
                    }
                }
                return new Result(flatArgs, offset + steps.length);
            }
            Object[] constructorArgs = new Object[steps.length];
            Step.Offset stepOffset = new Step.Offset(offset);
            for (int p = 0; p < steps.length; p++) {
                Object v = steps[p].apply(flatArgs, stepOffset, parentNullable, refFactory, interner, tx);
                RecordField field = type.fields().get(p);
                boolean nullable = parentNullable || field.nullable();
                if (!nullable && isArgNull(v)) {
                    throw new SqlTemplateException(
                            "Database returned NULL for non-nullable component '%s.%s'. Either %s, or ensure the corresponding column is NOT NULL in the database."
                                    .formatted(type.type().getSimpleName(), field.name(), nullableHint(type.type()))
                    );
                }
                constructorArgs[p] = v;
            }
            return new Result(constructorArgs, stepOffset.i);
        }
    }

    /**
     * Step that passes a single column value through unchanged.
     *
     * <p>Used for simple types (primitives, strings, etc.) that don't require conversion.</p>
     */
    private static final class PlainStep implements Step {
        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext context) {
            return flatArgs[offset.i++];
        }
    }

    /**
     * Step that applies a custom type converter to one or more columns.
     *
     * <p>Used for fields annotated with {@code @Convert}. The converter may consume multiple
     * columns (e.g., for composite types) as specified by its parameter count.</p>
     */
    private static final class ConverterStep implements Step {
        private final Object converter;
        private final int paramCount;

        private ConverterStep(Object converter, int paramCount) {
            this.converter = converter;
            this.paramCount = paramCount;
        }

        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext tx) throws SqlTemplateException {
            Object[] slice = new Object[paramCount];
            arraycopy(flatArgs, offset.i, slice, 0, paramCount);
            offset.i += paramCount;
            return ((ConverterInvoker) converter).fromDatabase(slice, refFactory);
        }

        /**
         * Functional interface for invoking converters without reflection at runtime.
         *
         * <p>The actual converter is wrapped in this interface during compilation.</p>
         */
        @FunctionalInterface
        interface ConverterInvoker {
            @Nullable Object fromDatabase(@Nullable Object[] args, RefFactory refFactory) throws SqlTemplateException;
        }
    }

    /**
     * Step that maps a column value to an enum constant.
     *
     * <p>Supports two mapping strategies via {@link DbEnum} annotation:</p>
     * <ul>
     *   <li>{@link EnumType#NAME}: Maps string values to enum constants by name (default)</li>
     *   <li>{@link EnumType#ORDINAL}: Maps integer values to enum constants by ordinal</li>
     * </ul>
     */
    private static final class EnumStep implements Step {
        private final EnumType mapping;
        private final String ownerSimpleName;
        private final String fieldName;
        private final ObjectMapper<?> enumMapper;

        private EnumStep(Class<?> enumType, EnumType mapping, String ownerSimpleName, String fieldName) {
            this.mapping = mapping;
            this.ownerSimpleName = ownerSimpleName;
            this.fieldName = fieldName;
            // Resolved once at plan compilation; the factory lookup is too costly to repeat per row.
            this.enumMapper = EnumMapper.getFactory(1, enumType).orElseThrow();
        }

        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext context) throws SqlTemplateException {
            Object raw = flatArgs[offset.i++];
            Object v = switch (mapping) {
                case NAME -> raw;
                case ORDINAL -> {
                    if (raw == null) {
                        yield null;
                    }
                    if (raw instanceof String s && INT_PATTERN.matcher(s).matches()) {
                        yield Integer.parseInt(s);
                    }
                    throw new SqlTemplateException(
                            "Cannot map value '%s' to ordinal enum field '%s.%s'. Expected a numeric string representing the enum ordinal."
                                    .formatted(raw, ownerSimpleName, fieldName)
                    );
                }
            };
            return enumMapper.newInstance(new Object[]{v});
        }
    }

    /**
     * Step that creates a {@link Ref} instance from a primary key column.
     *
     * <p>Refs are lazy references to entities or projections. The actual entity is not loaded
     * until the ref is dereferenced. This step consumes a single PK column and delegates to
     * {@link RefFactory} for ref creation and interning.</p>
     */
    private static final class RefStep implements Step {
        private final Class<? extends Data> dataType;

        private RefStep(Class<?> dataType) {
            @SuppressWarnings("unchecked")
            Class<? extends Data> dt = (Class<? extends Data>) dataType;
            this.dataType = dt;
        }

        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext context) {
            Object pk = flatArgs[offset.i++];
            if (pk == null) {
                return null;
            }
            return interner.intern(refFactory.create(dataType, pk));
        }
    }

    /**
     * Step that creates a {@link Ref} instance for a polymorphic foreign key.
     *
     * <p>Consumes two columns: a discriminator value and a primary key value.
     * Uses the discriminator to determine the concrete entity type, then creates a Ref
     * pointing to that type.</p>
     */
    private static final class PolymorphicRefStep implements Step {
        private final Class<?> sealedType;
        private final Discriminator.DiscriminatorType discriminatorType;

        private PolymorphicRefStep(Class<?> sealedType) {
            this.sealedType = sealedType;
            this.discriminatorType = getDiscriminatorType(sealedType);
        }

        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext context) throws SqlTemplateException {
            Object discriminatorRaw = flatArgs[offset.i++];
            Object pk = flatArgs[offset.i++];
            if (discriminatorRaw == null || pk == null) {
                return null;
            }
            Object discriminatorValue = normalizeDiscriminatorValue(discriminatorRaw, discriminatorType);
            @SuppressWarnings("unchecked")
            Class<? extends Data> concreteType = (Class<? extends Data>) resolveConcreteType(sealedType, discriminatorValue);
            return interner.intern(refFactory.create(concreteType, pk));
        }
    }

    /**
     * Step that recursively constructs a nested record or entity from multiple columns.
     *
     * <p>This step handles the most complex case: nested record types that may themselves contain
     * further nested records. It delegates to a sub-{@link ArgumentPlan} for recursive construction.</p>
     *
     * <h2>Early Cache Lookup Optimization</h2>
     * <p>For entity types, this step can extract the primary key directly from the flat column array
     * <em>before</em> constructing the entity and its nested objects. If a cached entity with that PK
     * exists (in {@link EntityCache} or {@link WeakInterner}), construction is skipped entirely.</p>
     *
     * <p>This optimization is particularly valuable for queries that return duplicate entity references
     * (e.g., joins that repeat the same entity across multiple rows).</p>
     *
     * <h2>Interning</h2>
     * <p>After construction, entities are interned to ensure identity consistency:</p>
     * <ul>
     *   <li>Entities with dirty tracking: via {@link EntityCache} (transaction-scoped)</li>
     *   <li>Other entities and records: via {@link WeakInterner} (query-scoped)</li>
     * </ul>
     */
    private static final class RecordStep implements Step {
        private final RecordField field;
        private final RecordType subType;
        private final ArgumentPlan subPlan;
        private final boolean subIsEntity;
        private final boolean subNeedsCache;

        // Fields for early PK cache lookup optimization.

        /** Offset within this record's flatArgs where PK starts (-1 if not applicable). */
        private final int pkFlatOffset;
        /** Number of columns the PK spans. */
        private final int pkColumnCount;
        /** Total columns this record consumes (for skipping on cache hit). */
        private final int totalColumnCount;
        /** Constructor for composite PKs (null for simple single-column PKs). */
        private final Constructor<?> pkConstructor;

        private RecordStep(RecordField field,
                           RecordType subType,
                           ArgumentPlan subPlan,
                           int pkFlatOffset,
                           int pkColumnCount,
                           int totalColumnCount,
                           @Nullable Constructor<?> pkConstructor) {
            this.field = field;
            this.subType = subType;
            this.subPlan = subPlan;
            this.subIsEntity = Entity.class.isAssignableFrom(subType.type());
            this.subNeedsCache = getUpdateMode(subType, StormConfig.defaults()) != OFF;
            this.pkFlatOffset = pkFlatOffset;
            this.pkColumnCount = pkColumnCount;
            this.totalColumnCount = totalColumnCount;
            this.pkConstructor = pkConstructor;
        }

        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext context) throws SqlTemplateException {
            boolean nullableHere = parentNullable || field.nullable();
            int start = offset.i;
            // Determine cache read/write policy for nested entities.
            // Cache read: return cached instances (identity preservation) - only at REPEATABLE_READ+
            // Cache write: store for dirty tracking OR for identity preservation
            boolean cacheReadEnabled = context != null && context.isRepeatableRead();
            boolean cacheWriteEnabled = cacheReadEnabled || subNeedsCache;
            EntityCache<Entity<?>, ?> entityCache = null;
            if (context != null && subIsEntity && cacheWriteEnabled) {
                //noinspection unchecked
                entityCache = (EntityCache<Entity<?>, ?>) context.entityCache(
                        (Class<? extends Entity<?>>) subType.type(), StormConfigHelper.cacheRetention(StormConfig.defaults()));
            }
            if (subIsEntity && pkFlatOffset >= 0) {
                Object pk = extractPk(flatArgs, start + pkFlatOffset);
                if (pk != null) {
                    if (entityCache != null && cacheReadEnabled) {
                        // Cache read enabled: use EntityCache for transaction-scoped identity.
                        //noinspection unchecked,rawtypes
                        Optional<Entity<?>> cached = ((EntityCache) entityCache).get(pk);
                        if (cached.isPresent()) {
                            // Cache hit - skip construction entirely.
                            offset.i = start + totalColumnCount;
                            return cached.get();
                        }
                    } else {
                        // Cache read disabled or no entity cache: use WeakInterner for query-scoped identity.
                        //noinspection unchecked
                        Entity<?> cached = interner.get((Class<Entity<?>>) subType.type(), pk);
                        if (cached != null) {
                            // Cache hit - skip construction entirely.
                            offset.i = start + totalColumnCount;
                            return cached;
                        }
                    }
                }
            }
            ArgumentPlan.Result r = subPlan.adapt(flatArgs, offset.i, nullableHere, refFactory, interner, context);
            offset.i = r.offset();
            if (field.nullable()) {
                boolean allNull = true;
                for (int i = start; i < offset.i; i++) {
                    if (flatArgs[i] != null) {
                        allNull = false;
                        break;
                    }
                }
                if (allNull) {
                    return null;
                }
            }
            // Validate nested non-nullable components.
            Object[] childArgs = r.constructorArgs();
            var subFields = subType.fields();
            RecordField nullViolation = null;
            for (int j = 0; j < childArgs.length; j++) {
                if (isArgNull(childArgs[j]) && !subFields.get(j).nullable()) {
                    nullViolation = subFields.get(j);
                    break;
                }
            }
            if (nullViolation != null) {
                if (!nullableHere) {
                    throw new SqlTemplateException(
                            "Database returned NULL for non-nullable component '%s.%s'. Either %s, or ensure the corresponding column is NOT NULL in the database."
                                    .formatted(subType.type().getSimpleName(), nullViolation.name(), nullableHint(subType.type()))
                    );
                }
                return null;
            }
            // Construct nested record.
            Object record = ObjectMapperFactory.construct(subType.constructor(), childArgs, start);
            if (entityCache != null) {
                // Intern for dirty tracking and/or identity preservation.
                Entity<?> interned = entityCache.intern((Entity<?>) record);
                if (cacheReadEnabled) {
                    // Return cached instance for transaction-scoped identity.
                    return interned;
                }
                // Cache read disabled: use WeakInterner for query-scoped identity only.
                // The entity was already stored in entityCache for dirty tracking.
                return interner.intern(record);
            }
            return interner.intern(record);
        }

        /**
         * Extracts the primary key from flatArgs at the given offset.
         *
         * @param flatArgs the flat argument array.
         * @param pkStart the starting offset for PK columns.
         * @return the PK value, or null if any PK column is null or PK cannot be extracted.
         */
        private Object extractPk(Object[] flatArgs, int pkStart) throws SqlTemplateException {
            if (pkColumnCount == 1) {
                // Simple PK - just return the value.
                return flatArgs[pkStart];
            }
            // Composite PK - construct from columns.
            if (pkConstructor == null) {
                // Cannot construct composite PK without constructor.
                return null;
            }
            Object[] pkArgs = new Object[pkColumnCount];
            for (int i = 0; i < pkColumnCount; i++) {
                Object arg = flatArgs[pkStart + i];
                if (arg == null) {
                    return null;  // Null in composite PK means no valid PK.
                }
                pkArgs[i] = arg;
            }
            return ObjectMapperFactory.construct(pkConstructor, pkArgs, pkStart);
        }
    }

    /**
     * Compiles an argument plan for the given record type.
     *
     * <p>This method analyzes the record's constructor parameters and creates an appropriate
     * {@link Step} for each one. The resulting plan can be reused across multiple row mappings.</p>
     *
     * <p>Step selection is based on parameter type:</p>
     * <ul>
     *   <li>Fields with {@code @Convert}: {@link ConverterStep}</li>
     *   <li>Nested records: {@link RecordStep} (recursive)</li>
     *   <li>Enums: {@link EnumStep}</li>
     *   <li>{@link Ref} types: {@link RefStep}</li>
     *   <li>All other types: {@link PlainStep}</li>
     * </ul>
     *
     * @param type the record type to compile a plan for.
     * @return the compiled argument plan.
     * @throws SqlTemplateException if compilation fails.
     */
    private static ArgumentPlan compilePlan(RecordType type, FetchPlan fetchPlan) throws SqlTemplateException {
        Class<?>[] paramTypes = type.constructor().getParameterTypes();
        Step[] steps = new Step[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            RecordField field = type.fields().get(i);
            var converterOpt = getORMConverter(field);
            if (converterOpt.isPresent()) {
                var converter = converterOpt.get();
                int parameterCount = converter.getParameterCount();
                // Wrap converter call once to keep ConverterStep fast at runtime.
                ConverterStep.ConverterInvoker invoker = converter::fromDatabase;
                steps[i] = new ConverterStep(invoker, parameterCount);
                continue;
            }
            Class<?> p = paramTypes[i];
            if (isRecord(p)) {
                steps[i] = recordStep(field, getRecordType(p), fetchPlan.descend(field.name()));
            } else if (p.isEnum()) {
                EnumType enumType = ofNullable(field.getAnnotation(DbEnum.class)).map(DbEnum::value).orElse(NAME);
                steps[i] = new EnumStep(p, enumType, type.type().getSimpleName(), field.name());
            } else if (Ref.class.isAssignableFrom(p)) {
                Class<? extends Data> refDataType = getRefDataType(field);
                if (fetchPlan.fetches(field.name())) {
                    // The statement carries the referenced record, laid out as an entity foreign key. Build it through
                    // the nested-record step, which already handles the outer join's all-null row, the entity cache and
                    // interning, then wrap it in a loaded reference.
                    FetchPlan subPlan = fetchPlan.descend(field.name());
                    RecordType refRecordType = getRecordType(refDataType);
                    steps[i] = new FetchedRefStep(recordStep(field, refRecordType, subPlan), refDataType,
                            keyReader(refRecordType, subPlan));
                } else if (isPolymorphicData(refDataType)) {
                    // Polymorphic FK: two columns (discriminator + PK).
                    steps[i] = new PolymorphicRefStep(refDataType);
                } else {
                    steps[i] = new RefStep(refDataType);
                }
            } else {
                steps[i] = new PlainStep();
            }
        }
        return new CompiledArgumentPlan(type, steps);
    }

    /**
     * Builds the step that constructs the nested record held by the given field.
     */
    private static RecordStep recordStep(RecordField field,
                                         RecordType sub,
                                         FetchPlan fetchPlan) throws SqlTemplateException {
        ArgumentPlan subPlan = compilePlan(sub, fetchPlan);
        // Calculate PK information for early cache lookup optimization.
        PkInfo pkInfo = calculatePkInfo(sub, fetchPlan);
        int totalColumnCount = getParameterCount(sub, fetchPlan);
        return new RecordStep(field, sub, subPlan, pkInfo.offset, pkInfo.columnCount, totalColumnCount,
                pkInfo.constructor);
    }

    /**
     * Locates the primary key of a resolved reference's target within the row, so the reference can be created with
     * the identity it is compared by.
     *
     * <p>The key is read from the flat columns rather than from the constructed record, because a projection carries
     * no accessor for it. Entities do, but reading both alike keeps one path.</p>
     *
     * @param type the record type the reference points at.
     * @param fetchPlan the plan in effect for that type.
     * @return the reader for the target's primary key.
     * @throws SqlTemplateException if the type declares no primary key.
     */
    /**
     * Returns the flat column offset of the primary key field: the sum of the column counts of all fields
     * preceding it.
     */
    private static int pkColumnOffset(RecordType type, RecordField pkField, FetchPlan fetchPlan)
            throws SqlTemplateException {
        int offset = 0;
        for (RecordField field : type.fields()) {
            if (field.name().equals(pkField.name())) {
                break;
            }
            offset += getFieldColumnCount(field, fetchPlan);
        }
        return offset;
    }

    private static KeyReader keyReader(RecordType type, FetchPlan fetchPlan) throws SqlTemplateException {
        RecordField pkField = findPkField(type.type()).orElseThrow(() -> new SqlTemplateException(
                "Cannot resolve a reference to %s: the type declares no primary key.".formatted(type.type().getSimpleName())));
        int offset = pkColumnOffset(type, pkField, fetchPlan);
        int columnCount = getFieldColumnCount(pkField, fetchPlan);
        Constructor<?> keyConstructor = null;
        if (columnCount > 1) {
            if (!isRecord(pkField.type())) {
                throw new SqlTemplateException("Cannot resolve a reference to %s: its primary key spans %d columns but is not a record."
                        .formatted(type.type().getSimpleName(), columnCount));
            }
            var keyRecordType = getRecordType(pkField.type());
            if (keyRecordType.constructor().getParameterCount() != columnCount) {
                throw new SqlTemplateException("Cannot resolve a reference to %s: its primary key spans nested records, which cannot be read back from the row."
                        .formatted(type.type().getSimpleName()));
            }
            keyConstructor = keyRecordType.constructor();
        }
        return new KeyReader(offset, columnCount, keyConstructor);
    }

    /**
     * Reads a record's primary key out of the flat columns of the row it occupies.
     *
     * @param offset the offset of the key columns, relative to the start of the record.
     * @param columnCount the number of columns the key spans.
     * @param constructor the constructor for a compound key, or {@code null} for a single-column key.
     */
    private record KeyReader(int offset, int columnCount, @Nullable Constructor<?> constructor) {

        @Nullable
        Object read(Object[] flatArgs, int start) throws SqlTemplateException {
            if (columnCount == 1) {
                return flatArgs[start + offset];
            }
            Object[] keyArgs = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                Object arg = flatArgs[start + offset + i];
                if (arg == null) {
                    return null;
                }
                keyArgs[i] = arg;
            }
            assert constructor != null : "Compound key without a constructor.";
            return ObjectMapperFactory.construct(constructor, keyArgs, start + offset);
        }
    }

    /**
     * Step that creates a loaded {@link Ref} from the referenced record's own columns.
     *
     * <p>A reference normally consumes a single key column and defers the record to {@link Ref#fetch()}. When the
     * statement resolves the reference, its target is selected alongside the row that holds it, so the record is built
     * here and the reference is handed back already loaded: {@code fetch()} returns it without querying.</p>
     */
    private static final class FetchedRefStep implements Step {
        private final RecordStep recordStep;
        private final Class<? extends Data> dataType;
        private final KeyReader keyReader;

        private FetchedRefStep(RecordStep recordStep,
                               Class<? extends Data> dataType,
                               KeyReader keyReader) {
            this.recordStep = recordStep;
            this.dataType = dataType;
            this.keyReader = keyReader;
        }

        @Override
        public Object apply(Object[] flatArgs,
                            Offset offset,
                            boolean parentNullable,
                            RefFactory refFactory,
                            WeakInterner interner,
                            @Nullable TransactionContext context) throws SqlTemplateException {
            int start = offset.i;
            Object record = recordStep.apply(flatArgs, offset, parentNullable, refFactory, interner, context);
            if (record == null) {
                return null;
            }
            Object pk = keyReader.read(flatArgs, start);
            if (pk == null) {
                throw new SqlTemplateException("Database returned NULL for the primary key of %s, which the query resolves as a reference. A reference is identified by that key, so it cannot be null while the referenced row is present."
                        .formatted(dataType.getSimpleName()));
            }
            return interner.intern(refFactory.create((Data) record, pk));
        }
    }

    /**
     * Holds primary key location and construction information for an entity type.
     *
     * <p>This information enables the early cache lookup optimization in {@link RecordStep}.</p>
     *
     * @param offset the offset into flatArgs where the PK columns start (-1 if not applicable).
     * @param columnCount the number of columns the PK spans.
     * @param constructor the constructor for composite PKs (null for simple single-column PKs).
     */
    private record PkInfo(int offset, int columnCount, @Nullable Constructor<?> constructor) {
        /** Sentinel value indicating no PK information is available (non-entity types). */
        static final PkInfo NONE = new PkInfo(-1, 0, null);
    }

    /**
     * Calculates the primary key offset, column count, and constructor for the given record type.
     *
     * <p>This information enables early cache lookups by extracting the PK directly from flatArgs
     * before constructing nested objects.</p>
     *
     * @param type the record type to analyze.
     * @return PkInfo containing offset, column count, and constructor (for composite PKs).
     */
    private static PkInfo calculatePkInfo(RecordType type, FetchPlan fetchPlan) throws SqlTemplateException {
        // Only entities have PKs.
        if (!Entity.class.isAssignableFrom(type.type())) {
            return PkInfo.NONE;
        }
        // Find the PK field.
        Optional<RecordField> pkFieldOpt = findPkField(type.type());
        if (pkFieldOpt.isEmpty()) {
            return PkInfo.NONE;
        }
        RecordField pkField = pkFieldOpt.get();
        int offset = pkColumnOffset(type, pkField, fetchPlan);
        // Calculate how many columns the PK spans.
        int pkColumnCount = getFieldColumnCount(pkField, fetchPlan);
        // For composite PKs (record types), we need the constructor.
        Constructor<?> pkConstructor = null;
        if (isRecord(pkField.type()) && pkColumnCount > 1) {
            var pkRecordType = getRecordType(pkField.type());
            // The shortcut only applies to flat key records. When the key spans nested records or entities
            // (key chains), its flat column count exceeds the constructor arity and the key must be built
            // through the regular argument plan; the early cache lookup is skipped in that case.
            if (pkRecordType.constructor().getParameterCount() == pkColumnCount) {
                pkConstructor = pkRecordType.constructor();
            }
        }
        return new PkInfo(offset, pkColumnCount, pkConstructor);
    }

    /**
     * Collects the flat column regions of nested entities that are eligible for the early cache lookup, in
     * ascending column order with outer regions preceding the regions they contain.
     *
     * <p>These regions allow the row reader to skip decoding the non-key columns of an entity that is already
     * cached; see {@link ColumnSkipper}. A region is only eligible when the primary key can be extracted directly
     * from the flat columns, mirroring the conditions of the early cache lookup in {@link RecordStep}.</p>
     *
     * @param type the record type to analyze.
     * @param base the absolute column offset at which the record type starts.
     * @param out the list to add the regions to.
     */
    private static void collectSkipRegions(RecordType type,
                                           int base,
                                           List<ColumnSkipper.SkipRegion> out,
                                           FetchPlan fetchPlan) throws SqlTemplateException {
        int cursor = base;
        for (RecordField field : type.fields()) {
            var converter = getORMConverter(field);
            if (converter.isPresent()) {
                cursor += converter.get().getParameterCount();
                continue;
            }
            boolean fetchedRef = Ref.class.isAssignableFrom(field.type()) && fetchPlan.fetches(field.name());
            if (isRecord(field.type()) || fetchedRef) {
                RecordType sub = getRecordType(fetchedRef ? getRefDataType(field) : field.type());
                FetchPlan subPlan = fetchPlan.descend(field.name());
                int totalColumnCount = getParameterCount(sub, subPlan);
                if (Entity.class.isAssignableFrom(sub.type())) {
                    PkInfo pkInfo = calculatePkInfo(sub, subPlan);
                    if (pkInfo.offset() >= 0 && (pkInfo.columnCount() == 1 || pkInfo.constructor() != null)) {
                        //noinspection unchecked
                        out.add(new ColumnSkipper.SkipRegion(cursor, cursor + totalColumnCount,
                                cursor + pkInfo.offset(), pkInfo.columnCount(), pkInfo.constructor(),
                                (Class<? extends Entity<?>>) sub.type(), false));
                    }
                }
                collectSkipRegions(sub, cursor, out, subPlan);
                cursor += totalColumnCount;
            } else if (Ref.class.isAssignableFrom(field.type()) && isPolymorphicData(getRefDataType(field))) {
                // Polymorphic FK: discriminator + PK columns.
                cursor += 2;
            } else {
                cursor += 1;
            }
        }
    }

    /**
     * Returns the number of JDBC columns a field consumes in the flat args array.
     *
     * <p>This accounts for:</p>
     * <ul>
     *   <li>Custom converters that may consume multiple columns</li>
     *   <li>Nested records that expand to multiple columns recursively</li>
     *   <li>Simple fields that consume exactly one column</li>
     * </ul>
     *
     * @param field the field to calculate column count for.
     * @return the number of columns the field consumes.
     * @throws SqlTemplateException if the field type cannot be analyzed.
     */
    private static int getFieldColumnCount(RecordField field, FetchPlan fetchPlan) throws SqlTemplateException {
        var converter = getORMConverter(field);
        if (converter.isPresent()) {
            return converter.get().getParameterCount();
        }
        if (isRecord(field.type())) {
            return getParameterCount(getRecordType(field.type()), fetchPlan.descend(field.name()));
        }
        if (Ref.class.isAssignableFrom(field.type()) && fetchPlan.fetches(field.name())) {
            return getParameterCount(getRecordType(getRefDataType(field)), fetchPlan.descend(field.name()));
        }
        if (Ref.class.isAssignableFrom(field.type()) && isPolymorphicData(getRefDataType(field))) {
            // Polymorphic FK: discriminator + PK columns.
            return 2;
        }
        return 1;
    }
}
