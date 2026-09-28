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

import static java.util.Collections.nCopies;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.RecordReflection.detectSealedPattern;
import static st.orm.core.template.impl.RecordReflection.findJoinedSealedParent;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getBaseFieldNames;
import static st.orm.core.template.impl.RecordReflection.getColumnName;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorColumn;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorColumnJavaType;
import static st.orm.core.template.impl.RecordReflection.getFkLeaves;
import static st.orm.core.template.impl.RecordReflection.getForeignKeys;
import static st.orm.core.template.impl.RecordReflection.getGenerationStrategy;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.getSequence;
import static st.orm.core.template.impl.RecordReflection.getTableName;
import static st.orm.core.template.impl.RecordReflection.hasDiscriminator;
import static st.orm.core.template.impl.RecordReflection.isRecord;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;
import static st.orm.core.template.impl.RecordValidation.validateDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.DbColumn;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.Persist;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.Version;
import st.orm.core.spi.Name;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.impl.RecordReflection.SealedPattern;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Factory for creating models.
 *
 * @since 1.2
 */
final class ModelFactory {

    /**
     * The cached models per record type, keyed by the builder and the references the statement resolves. The
     * builder's resolvers name the table, its schema and its columns, and the plan changes the column list, so a
     * model built for one template or plan cannot serve another. {@link ClassValue} ties the models to the lifetime
     * of the record type, so they never pin the type or its class loader.
     */
    private static final ClassValue<ConcurrentMap<ModelKey, Model<?, ?>>> MODEL_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentMap<ModelKey, Model<?, ?>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /**
     * The key of a cached model: the builder whose resolvers named it and the references it resolves.
     */
    private record ModelKey(ModelBuilderImpl builder, FetchPlan fetchPlan) {}

    private ModelFactory() {
    }

    static <T extends Data, ID> Model<T, ID> getModel(ModelBuilderImpl builder, Class<T> type, boolean requirePrimaryKey) throws SqlTemplateException {
        return getModel(builder, type, requirePrimaryKey, FetchPlan.NONE);
    }

    static <T extends Data, ID> Model<T, ID> getModel(ModelBuilderImpl builder, Class<T> type, boolean requirePrimaryKey, FetchPlan fetchPlan) throws SqlTemplateException {
        try {
            validateDataType(type, requirePrimaryKey);
            //noinspection unchecked
            return (Model<T, ID>) MODEL_CACHE.get(type).computeIfAbsent(new ModelKey(builder, fetchPlan), ignore -> {
                try {
                    return createModel(builder, type, requirePrimaryKey, fetchPlan);
                } catch (SqlTemplateException e) {
                    throw new UncheckedSqlTemplateException(e);
                }
            });
        } catch (UncheckedSqlTemplateException e) {
            throw e.getCause();
        }
    }

    private static <T extends Data, ID> Model<T, ID> createModel(ModelBuilder builder, Class<T> type, boolean requirePrimaryKey, FetchPlan fetchPlan) throws SqlTemplateException {
        validateDataType(type, requirePrimaryKey);
        // Handle sealed entity types (single-table and joined).
        if (type.isSealed() && isSealedEntity(type)) {
            if (!fetchPlan.isEmpty()) {
                throw new SqlTemplateException("Cannot resolve references for %s: a sealed entity selects the union of its subtypes' columns, which has no single position to expand a reference into. Select a concrete subtype to resolve %s."
                        .formatted(type.getSimpleName(), String.join(", ", fetchPlan.toList())));
            }
            return createSealedModel(builder, type);
        }
        RecordType recordType = getRecordType(type);
        List<Column> columns = new ArrayList<>();
        List<RecordField> fields = new ArrayList<>();
        try {
            BuildContext ctx = new BuildContext(builder, columns, fields, Metamodel.root(type), new AtomicInteger(1), fetchPlan);
            for (var field : recordType.fields()) {
                createColumns(ctx, ctx.rootMetamodel(), field, false, null, KeyScope.none(), PkContext.none(), null, false);
            }
            var tableName = getTableName(type, builder.tableNameResolver(), builder.schemaResolver());
            // For permitted subclasses of a JOINED sealed entity, adjust columns so that
            // base non-PK fields are not insertable/updatable (they belong to the base table),
            // and the PK generation is NONE (PK is provided from the base table INSERT).
            List<Column> finalColumns = columns;
            var joinedParent = findJoinedSealedParent(type);
            if (joinedParent.isPresent()) {
                finalColumns = adjustColumnsForJoinedSubtype(columns, joinedParent.get());
            }
            return new ModelImpl<>(recordType, tableName, fields, finalColumns);
        } catch (UncheckedSqlTemplateException e) {
            throw e.getCause();
        }
    }

    /**
     * Adjusts columns for a concrete subtype of a JOINED sealed entity.
     *
     * <p>Base non-PK fields are marked as non-insertable/non-updatable (they belong to the base table).
     * PK generation is overridden to NONE (the PK value is provided from the base table INSERT,
     * not auto-generated).</p>
     */
    private static List<Column> adjustColumnsForJoinedSubtype(List<Column> columns,
                                                               Class<?> sealedParent) {
        List<String> baseFieldNames = getBaseFieldNames(sealedParent);
        List<Column> adjusted = new ArrayList<>(columns.size());
        for (Column col : columns) {
            if (col instanceof ColumnImpl ci) {
                if (ci.primaryKey()) {
                    // PK: override generation to NONE (PK is provided from base INSERT).
                    adjusted.add(new ColumnImpl(
                            ci.columnName(), ci.index(), ci.type(), ci.persistedType(), true,
                            GenerationStrategy.NONE, "",
                            ci.foreignKey(), ci.foreignKeyGeneration(), ci.keyIndex(), ci.nullable(),
                            ci.insertable(), ci.updatable(), ci.version(), ci.ref(),
                            ci.metamodel(), ci.secondaryMetamodel()
                    ));
                } else {
                    // Determine if this is a base field by checking the top-level field name
                    // from the metamodel path.
                    String fieldPath = ci.metamodel().fieldPath();
                    String topLevelField = fieldPath.contains(".")
                            ? fieldPath.substring(0, fieldPath.indexOf('.'))
                            : fieldPath;
                    boolean isBaseField = baseFieldNames.contains(topLevelField);
                    if (isBaseField) {
                        // Base non-PK field: not insertable or updatable in extension table.
                        adjusted.add(new ColumnImpl(
                                ci.columnName(), ci.index(), ci.type(), ci.persistedType(), false,
                                ci.generation(), ci.sequence(),
                                ci.foreignKey(), ci.foreignKeyGeneration(), ci.keyIndex(), ci.nullable(),
                                false, false, ci.version(), ci.ref(),
                                ci.metamodel(), ci.secondaryMetamodel()
                        ));
                    } else {
                        // Extension field: keep as-is.
                        adjusted.add(ci);
                    }
                }
            } else {
                adjusted.add(col);
            }
        }
        return adjusted;
    }

    /**
     * Creates a model for a sealed entity type (single-table or joined inheritance).
     *
     * <p>For single-table inheritance, the model includes a discriminator column followed by the
     * union of all subtype fields. Fields shared across subtypes occupy the same column position.
     * Subtype-specific columns are nullable (they hold NULL for rows of other subtypes).</p>
     *
     * <p>For joined inheritance, the model covers the base table's columns. Extension table columns
     * are handled via JOINs at query time.</p>
     *
     * <p>Unlike normal models, sealed entity models build columns directly rather than using
     * {@link #createColumns}, because sealed interfaces are not records and cannot participate
     * in metamodel path resolution. The RecordMapper handles the actual mapping from result set
     * to concrete subtype records via discriminator-based dispatch.</p>
     */
    @SuppressWarnings("unchecked")
    private static <T extends Data, ID> Model<T, ID> createSealedModel(ModelBuilder builder,
                                                                        Class<T> sealedType) throws SqlTemplateException {
        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new SqlTemplateException("Sealed type %s has no permitted subclasses.".formatted(sealedType.getSimpleName()));
        }
        Class<? extends Data> firstSubtype = (Class<? extends Data>) permitted[0];
        RecordType firstRecordType = getRecordType(firstSubtype);
        var pattern = detectSealedPattern(sealedType).orElseThrow();
        List<Column> columns = new ArrayList<>();
        List<RecordField> fields = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(1);
        // Use the first subtype as the metamodel root since sealed interfaces aren't records.
        Metamodel<?, ?> rootMetamodel = Metamodel.root(firstSubtype);
        RecordField pkFieldOfFirst = findPkField(permitted[0]).orElseThrow(
                () -> new SqlTemplateException("No PK field in %s.".formatted(permitted[0].getSimpleName())));
        // Emit discriminator column.
        String discriminatorColumnName = getDiscriminatorColumn(sealedType);
        boolean discriminator = hasDiscriminator(sealedType);
        columns.add(new ColumnImpl(
                new ColumnName(discriminatorColumnName, false),
                index.getAndIncrement(),
                getDiscriminatorColumnJavaType(sealedType),
                getDiscriminatorColumnJavaType(sealedType),
                false,  // not primary key
                GenerationStrategy.NONE,
                "",
                false,  // not foreign key
                GenerationStrategy.NONE,
                -1,
                false,  // not nullable
                discriminator,   // insertable only when discriminator exists
                discriminator,   // updatable only when discriminator exists (supports type changes)
                false,  // not version
                false,  // not ref
                (Metamodel<Data, ?>) rootMetamodel,
                null
        ));
        fields.add(pkFieldOfFirst); // Placeholder field for discriminator.
        // Build union of all subtype fields. Use LinkedHashMap to preserve insertion order
        // and avoid duplicates (fields shared across subtypes keep the first occurrence).
        java.util.LinkedHashMap<String, RecordField> seenFields = new java.util.LinkedHashMap<>();
        for (Class<?> subtype : permitted) {
            RecordType subRecordType = getRecordType(subtype);
            for (RecordField field : subRecordType.fields()) {
                if (!seenFields.containsKey(field.name())) {
                    seenFields.put(field.name(), field);
                }
            }
        }
        // For each unique field, determine if it's common or subtype-specific.
        // For JOINED pattern, also identify which subtype owns each extension field.
        for (var entry : seenFields.entrySet()) {
            RecordField field = entry.getValue();
            boolean isCommon = true;
            Class<?> owningSubtype = null; // For JOINED: the subtype that owns this extension field.
            for (Class<?> subtype : permitted) {
                RecordType subRecordType = getRecordType(subtype);
                boolean found = subRecordType.fields().stream()
                        .anyMatch(f -> f.name().equals(field.name()) && f.type().equals(field.type()));
                if (!found) {
                    isCommon = false;
                } else if (owningSubtype == null) {
                    owningSubtype = subtype;
                }
            }
            boolean isPk = field.isAnnotationPresent(PK.class);
            boolean nullable;
            if (isCommon) {
                // For common fields, compute nullable as the disjunction (OR) of all subtypes' nullability.
                nullable = false;
                for (Class<?> subtype : permitted) {
                    RecordType subRecordType = getRecordType(subtype);
                    for (RecordField subField : subRecordType.fields()) {
                        if (subField.name().equals(field.name()) && subField.type().equals(field.type())) {
                            if (subField.nullable()) {
                                nullable = true;
                            }
                            break;
                        }
                    }
                }
            } else {
                // Subtype-specific fields are always nullable in the union.
                nullable = true;
            }
            ColumnName columnName = getColumnName(field, builder.columnNameResolver());
            // For JOINED pattern, extension-specific columns use a metamodel tied to their owning
            // subtype so that alias resolution maps them to the correct extension table alias.
            Metamodel<Data, ?> columnMetamodel;
            if (pattern == SealedPattern.JOINED && !isCommon && !isPk && owningSubtype != null) {
                //noinspection unchecked
                columnMetamodel = (Metamodel<Data, ?>) Metamodel.of(
                        (Class<? extends Data>) owningSubtype, field.name());
            } else {
                columnMetamodel = (Metamodel<Data, ?>) rootMetamodel;
            }
            // For JOINED pattern, extension-specific columns (not common across all subtypes,
            // not PK) should not be insertable or updatable via the sealed (base table) model.
            boolean extensionColumn = pattern == SealedPattern.JOINED && !isCommon && !isPk;
            // Resolve a secondary metamodel rooted at the sealed type so that runtime metamodels
            // (e.g., Metamodel.of(Animal.class, "name")) can be used in where clauses. This works
            // both with generated metamodels and with the MetamodelFactory runtime path.
            Metamodel<Data, ?> sealedFieldMetamodel = null;
            try {
                //noinspection unchecked
                sealedFieldMetamodel = (Metamodel<Data, ?>) Metamodel.of(
                        (Class<? extends Data>) sealedType, field.name());
            } catch (RuntimeException ignored) {
                // Field not declared on the sealed interface (e.g., extension-specific field).
            }
            columns.add(new ColumnImpl(
                    columnName,
                    index.getAndIncrement(),
                    field.type(),
                    field.type(),
                    isPk,
                    isPk ? getGenerationStrategy(field) : GenerationStrategy.NONE,
                    isPk ? getSequence(field) : "",
                    false,  // not foreign key
                    GenerationStrategy.NONE,
                    isPk ? 1 : -1,
                    nullable,
                    !extensionColumn,                       // insertable
                    !extensionColumn && !isPk,              // updatable
                    field.isAnnotationPresent(Version.class),
                    false,  // not ref
                    columnMetamodel,
                    sealedFieldMetamodel
            ));
            fields.add(field);
        }
        var tableName = getTableName(sealedType, builder.tableNameResolver(), builder.schemaResolver());
        // Override the type to the sealed interface so that Model.type() returns the sealed interface
        // (e.g., Vehicle.class) rather than the first permitted subclass (e.g., Car.class).
        return new ModelImpl<>(firstRecordType, sealedType, tableName, fields, columns);
    }

    record BuildContext(ModelBuilder builder, List<Column> columns, List<RecordField> fields, Metamodel<?, ?> rootMetamodel, AtomicInteger index, FetchPlan fetchPlan) {
    }

    record ColumnSpec(boolean primaryKey,
                      boolean foreignKey,
                      GenerationStrategy foreignKeyGeneration,
                      boolean nullable,
                      boolean insertable,
                      boolean updatable,
                      boolean version,
                      boolean ref,
                      Class<?> dataType,
                      GenerationStrategy generation,
                      String sequence) {
    }

    record PkContext(boolean active, GenerationStrategy generation, String sequence) {
        static final PkContext NONE = new PkContext(false, GenerationStrategy.NONE, "");
        static PkContext none() {
            return NONE;
        }
        static PkContext start(RecordField pkField) {
            return new PkContext(true, getGenerationStrategy(pkField), getSequence(pkField));
        }
    }

    private static void createColumns(BuildContext ctx,
                                      Metamodel<?, ?> parentMetamodel,
                                      RecordField field,
                                      boolean parentNullable,
                                      @Nullable Persist inheritedPersist,
                                      KeyScope inheritedKeyScope,
                                      PkContext inheritedPkContext,
                                      @Nullable Metamodel<Data, ?> keyMetamodel,
                                      boolean expandingRelation) {
        try {
            boolean pkAnnotation = field.isAnnotationPresent(PK.class);
            boolean fkAnnotation = field.isAnnotationPresent(FK.class);
            if (expandingRelation && pkAnnotation) {
                if (fkAnnotation && isRecord(field.type()) && Data.class.isAssignableFrom(field.type())) {
                    // The parent's FK column already provides this entity's PK value, but we still
                    // need to expand the referenced entity's non-PK fields via a join.
                    @SuppressWarnings("unchecked")
                    Metamodel<Data, ?> ownMetamodel = (Metamodel<Data, ?>) Metamodel.of(
                            (Class<? extends Data>) parentMetamodel.root(),
                            parentMetamodel.fieldPath().isEmpty() ? field.name() : parentMetamodel.fieldPath() + "." + field.name()
                    );
                    boolean nullable = parentNullable || field.nullable();
                    expandForeignRelation(ctx, ownMetamodel, field, nullable, field.type());
                }
                return;
            }
            PkContext pkContext = pkAnnotation ? PkContext.start(field) : inheritedPkContext;
            boolean effectivePrimaryKey = pkAnnotation || pkContext.active();
            KeyScope keyScope = inheritedKeyScope;
            if (pkAnnotation || fkAnnotation) {
                keyScope = KeyScope.start(true);
            }
            @SuppressWarnings("unchecked")
            Metamodel<Data, ?> ownMetamodel = (Metamodel<Data, ?>) Metamodel.of(
                    (Class<? extends Data>) parentMetamodel.root(),
                    parentMetamodel.fieldPath().isEmpty() ? field.name() : parentMetamodel.fieldPath() + "." + field.name()
            );
            Metamodel<Data, ?> columnMetamodel = keyMetamodel != null ? keyMetamodel : ownMetamodel;
            Metamodel<Data, ?> nextKeyMetamodel = keyMetamodel;
            if (pkAnnotation && isRecord(field.type()) && !fkAnnotation) {
                nextKeyMetamodel = ownMetamodel;
                columnMetamodel = ownMetamodel;
            }
            var converter = getORMConverter(field).orElse(null);
            if (converter != null) {
                var columnTypes = converter.getParameterTypes();
                int expected = converter.getParameterCount();
                if (columnTypes.size() != expected) {
                    throw new SqlTemplateException("Expected %s parameter types, but got %d.".formatted(expected, columnTypes.size()));
                }
                var columnNames = converter.getColumns(c -> getColumnName(c, ctx.builder().columnNameResolver()));
                if (columnTypes.size() != columnNames.size()) {
                    throw new SqlTemplateException("Column count does not match value count.");
                }
                ColumnSpec spec = buildSpec(field, effectivePrimaryKey, fkAnnotation, parentNullable, inheritedPersist, pkContext);
                emitColumns(ctx, field, columnMetamodel, null, spec, keyScope, columnNames, columnTypes, columnTypes);
                return;
            }
            if (isRecord(field.type()) && !fkAnnotation) {
                if (!effectivePrimaryKey && field.isAnnotationPresent(DbColumn.class)) {
                    throw new SqlTemplateException("DbColumn annotation is not allowed for @Inline records: %s.%s.".formatted(field.type().getSimpleName(), field.name()));
                }
                boolean nullable = parentNullable || field.nullable();
                Persist persist = field.getAnnotation(Persist.class);
                Persist effectivePersist = persist != null ? persist : inheritedPersist;
                var nested = getRecordType(field.type());
                for (var child : nested.fields()) {
                    createColumns(ctx,
                            ownMetamodel,
                            child,
                            nullable,
                            effectivePersist,
                            effectivePrimaryKey ? keyScope : inheritedKeyScope,
                            pkContext,
                            nextKeyMetamodel,
                            expandingRelation);
                }
                return;
            }
            ColumnSpec spec = buildSpec(field, effectivePrimaryKey, fkAnnotation, parentNullable, inheritedPersist, pkContext);
            if (fkAnnotation) {
                var fkNames = getForeignKeys(field, ctx.builder().foreignKeyResolver(), ctx.builder().columnNameResolver());
                Metamodel<Data, ?> secondaryMetamodel = null;
                if (!spec.ref()) {
                    var pkField = findPkField(field.type()).orElseThrow(
                            () -> new SqlTemplateException("No primary key found for type: %s".formatted(field.type().getSimpleName())));
                    //noinspection unchecked
                    secondaryMetamodel = (Metamodel<Data, ?>) Metamodel.of(
                            (Class<? extends Data>) ownMetamodel.root(),
                            ownMetamodel.fieldPath().isEmpty() ? field.name() : ownMetamodel.fieldPath() + "." + pkField.name());
                }
                var leaves = getFkLeaves(field);
                List<Class<?>> persistedTypes = leaves != null && leaves.size() == fkNames.size()
                        ? leaves.stream().<Class<?>>map(leaf -> leaf.field().type()).toList()
                        : nCopies(fkNames.size(), spec.dataType());
                emitColumns(ctx, field, ownMetamodel, secondaryMetamodel, spec, keyScope, fkNames, nCopies(fkNames.size(), spec.dataType()), persistedTypes);
                if (!spec.ref()) {
                    expandForeignRelation(ctx, ownMetamodel, field, spec.nullable(), field.type());
                } else if (ctx.fetchPlan().fetches(ownMetamodel.fieldPath())) {
                    // A resolved reference is laid out exactly as an entity foreign key: the foreign key column
                    // carries the target's primary key, and the target's remaining columns follow from its joined
                    // table. That symmetry is what lets the row mapper reuse its nested-record path here.
                    expandForeignRelation(ctx, ownMetamodel, field, spec.nullable(), getRefDataType(field));
                }
                return;
            }
            ColumnName columnName = getColumnName(field, ctx.builder().columnNameResolver());
            // Compound key components are registered under the key's group metamodel so that compound
            // lookups resolve to all key columns. The component's own metamodel is retained as the
            // secondary metamodel so that per-component metamodels, as exposed by generated metamodel
            // classes and Key.flatten(), resolve to their individual column as well.
            Metamodel<Data, ?> componentMetamodel = columnMetamodel != ownMetamodel ? ownMetamodel : null;
            emitColumns(ctx, field, columnMetamodel, componentMetamodel, spec, keyScope, List.of(columnName), List.of(spec.dataType()), List.of(spec.dataType()));
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    private static void expandForeignRelation(BuildContext ctx,
                                              Metamodel<Data, ?> foreignMetamodel,
                                              RecordField foreignField,
                                              boolean nullableDueToJoin,
                                              Class<?> relationType) {
        if (!Data.class.isAssignableFrom(relationType)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Class<? extends Data> targetType = (Class<? extends Data>) relationType;
        RecordType targetRecordType = getRecordType(targetType);
        for (var child : targetRecordType.fields()) {
            createColumns(ctx, foreignMetamodel, child, nullableDueToJoin, null, KeyScope.none(), PkContext.none(), null, true);
        }
    }

    private static ColumnSpec buildSpec(RecordField field,
                                        boolean effectivePrimaryKey,
                                        boolean foreignKey,
                                        boolean parentNullable,
                                        @Nullable Persist inheritedPersist,
                                        PkContext pkContext) throws SqlTemplateException {
        boolean nullable = parentNullable || field.nullable();
        Persist persist = field.getAnnotation(Persist.class);
        Persist effectivePersist = persist != null ? persist : inheritedPersist;
        boolean version = field.isAnnotationPresent(Version.class);
        boolean insertable = effectivePrimaryKey || effectivePersist == null || effectivePersist.insertable();
        boolean updatable = !effectivePrimaryKey && (effectivePersist == null || effectivePersist.updatable());
        boolean ref = Ref.class.isAssignableFrom(field.type());
        Class<?> dataType = field.type();
        if (ref) {
            dataType = getRefDataType(field);
        }
        GenerationStrategy generation = effectivePrimaryKey ? pkContext.generation() : GenerationStrategy.NONE;
        String sequence = effectivePrimaryKey ? pkContext.sequence() : "";
        GenerationStrategy fkGeneration = GenerationStrategy.NONE;
        if (foreignKey) {
            Class<?> fkTargetType = ref ? getRefDataType(field) : field.type();
            fkGeneration = findPkField(fkTargetType)
                    .map(pkField -> getGenerationStrategy(pkField))
                    .orElse(GenerationStrategy.NONE);
        }
        return new ColumnSpec(effectivePrimaryKey, foreignKey, fkGeneration, nullable, insertable, updatable, version, ref, dataType, generation, sequence);
    }

    private static void emitColumns(BuildContext ctx,
                                    RecordField field,
                                    Metamodel<Data, ?> metamodel,
                                    @Nullable Metamodel<Data, ?> secondaryMetamodel,
                                    ColumnSpec spec,
                                    KeyScope keyScope,
                                    List<? extends Name> names,
                                    List<Class<?>> types,
                                    List<Class<?>> persistedTypes) throws SqlTemplateException {
        if (names.size() != types.size() || names.size() != persistedTypes.size()) {
            throw new SqlTemplateException("Column count does not match type count.");
        }
        for (int i = 0; i < names.size(); i++) {
            int keyPos = keyScope.next();
            ctx.columns().add(new ColumnImpl(
                    names.get(i),
                    ctx.index().getAndIncrement(),
                    types.get(i),
                    persistedTypes.get(i),
                    spec.primaryKey(),
                    spec.generation(),
                    spec.sequence(),
                    spec.foreignKey(),
                    spec.foreignKeyGeneration(),
                    keyPos,
                    spec.nullable(),
                    spec.insertable(),
                    spec.updatable(),
                    spec.version(),
                    spec.ref(),
                    metamodel,
                    secondaryMetamodel
            ));
        }
        ctx.fields().addAll(nCopies(names.size(), field));
    }

    @SuppressWarnings("SameParameterValue")
    private static final class KeyScope {
        static final KeyScope NONE = new KeyScope(false, false);
        final boolean active;
        final boolean compound;
        final AtomicInteger pos;

        KeyScope(boolean active, boolean compound) {
            this.active = active;
            this.compound = compound;
            this.pos = active ? new AtomicInteger(1) : null;
        }

        static KeyScope none() {
            return NONE;
        }

        /**
         * Creates a new scope.
         *
         * @param compound whether the key is compound.
         */
        static KeyScope start(boolean compound) {
            return new KeyScope(true, compound);
        }

        int next() {
            return !active ? -1 : pos.getAndIncrement();
        }
    }
}
