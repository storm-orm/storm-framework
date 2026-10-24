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
import static java.util.Locale.ROOT;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
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
import static st.orm.core.template.impl.RecordReflection.getQueryColumnField;
import static st.orm.core.template.impl.RecordReflection.getQueryColumns;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.getSealedFields;
import static st.orm.core.template.impl.RecordReflection.getSequence;
import static st.orm.core.template.impl.RecordReflection.getTableName;
import static st.orm.core.template.impl.RecordReflection.hasDiscriminator;
import static st.orm.core.template.impl.RecordReflection.isRecord;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;
import static st.orm.core.template.impl.RecordValidation.validateDataType;

import java.util.ArrayList;
import java.util.HashSet;
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
import st.orm.QueryColumn;
import st.orm.Ref;
import st.orm.SqlFunction;
import st.orm.SqlTemplateException;
import st.orm.Version;
import st.orm.core.spi.Name;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.impl.RecordReflection.SealedField;
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
     * The number of builders whose models a record type keeps. Builders are equal when their resolvers are, and a
     * resolver created per template, such as a lambda, equals no other, so an application that creates templates on
     * demand would otherwise add models for as long as the record type lives.
     */
    private static final int BUILDERS_PER_TYPE = 64;

    /**
     * The cached models per record type, keyed by the builder and the references the statement resolves. The
     * builder's resolvers name the table, its schema and its columns, and the plan changes the column list, so a
     * model built for one template or plan cannot serve another. The builders are held in a bounded LRU, so the
     * models of templates that are no longer used are evicted. {@link ClassValue} ties the models to the lifetime of
     * the record type, so they never pin the type or its class loader.
     */
    private static final ClassValue<SegmentedLruCache<ModelBuilderImpl, ConcurrentMap<FetchPlan, Model<?, ?>>>> MODEL_CACHE = new ClassValue<>() {
        @Override
        protected SegmentedLruCache<ModelBuilderImpl, ConcurrentMap<FetchPlan, Model<?, ?>>> computeValue(Class<?> type) {
            return new SegmentedLruCache<>(BUILDERS_PER_TYPE);
        }
    };

    private ModelFactory() {
    }

    static <T extends Data, ID> Model<T, ID> getModel(ModelBuilderImpl builder, Class<T> type, boolean requirePrimaryKey) throws SqlTemplateException {
        return getModel(builder, type, requirePrimaryKey, FetchPlan.NONE);
    }

    static <T extends Data, ID> Model<T, ID> getModel(ModelBuilderImpl builder, Class<T> type, boolean requirePrimaryKey, FetchPlan fetchPlan) throws SqlTemplateException {
        try {
            validateDataType(type, requirePrimaryKey);
            //noinspection unchecked
            return (Model<T, ID>) MODEL_CACHE.get(type)
                    .getOrCompute(builder, ConcurrentHashMap::new)
                    .computeIfAbsent(fetchPlan, ignore -> {
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
        List<Column> queryColumns = new ArrayList<>();
        try {
            BuildContext ctx = new BuildContext(builder, columns, fields, queryColumns, Metamodel.root(type), new AtomicInteger(1), fetchPlan);
            for (var field : recordType.fields()) {
                createColumns(ctx, ctx.rootMetamodel(), field, false, null, KeyScope.none(), PkContext.none(), null, false);
            }
            createQueryColumns(ctx, ctx.rootMetamodel(), type, false);
            var tableName = getTableName(type, builder.tableNameResolver(), builder.schemaResolver());
            // For permitted subclasses of a JOINED sealed entity, adjust columns so that
            // base non-PK fields are not insertable/updatable (they belong to the base table),
            // and the PK generation is NONE (PK is provided from the base table INSERT).
            List<Column> finalColumns = columns;
            var joinedParent = findJoinedSealedParent(type);
            if (joinedParent.isPresent()) {
                finalColumns = adjustColumnsForJoinedSubtype(columns, joinedParent.get());
            }
            // The first model of an entity is the moment it is first used, where no startup check saw it.
            DesignCheck.check(type);
            return new ModelImpl<>(recordType, tableName, fields, finalColumns, queryColumns);
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
                            ci.insertable(), ci.updatable(), ci.version(), ci.ref(), ci.function(),
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
                                false, false, ci.version(), ci.ref(), ci.function(),
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
     * <p>The model holds a discriminator column followed by the union of the subtypes' fields, each built as the
     * subtype that first declares it builds it: a foreign key resolves its column through the foreign key resolver
     * and is joined, an inline record contributes its components, and a converted field its converter's columns.
     * Fields shared across subtypes occupy one position. A field some subtypes lack holds NULL in the rows of the
     * others, so its columns are nullable and the join through it is an outer join.</p>
     *
     * <p>For single-table inheritance every column lives in the sealed type's table. For joined inheritance the
     * model covers the base table: the columns of a field only some subtypes declare belong to that subtype's
     * extension table, which is joined at query time, and are not written through this model.</p>
     *
     * <p>The row mapper lays the union out the same way, so each subtype is built from its own positions.</p>
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
        List<Column> queryColumns = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(1);
        RecordField pkFieldOfFirst = findPkField(permitted[0]).orElseThrow(
                () -> new SqlTemplateException("No PK field in %s.".formatted(permitted[0].getSimpleName())));
        // Emit discriminator column. It maps no field, so it carries the table's own metamodel.
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
                "",
                (Metamodel<Data, ?>) Metamodel.root(firstSubtype),
                null
        ));
        fields.add(pkFieldOfFirst); // Placeholder field for discriminator.
        try {
            for (SealedField sealedField : getSealedFields(sealedType)) {
                RecordField field = sealedField.field();
                var ctx = new BuildContext(builder, columns, fields, queryColumns, Metamodel.root(sealedField.owner()), index, FetchPlan.NONE);
                int start = columns.size();
                createColumns(ctx, ctx.rootMetamodel(), field, sealedField.nullable(), null, KeyScope.none(), PkContext.none(), null, false);
                // The columns of a joined extension field are written to the extension table through the subtype's
                // own model, not through the base table's.
                if (pattern == SealedPattern.JOINED && !sealedField.common() && !field.isAnnotationPresent(PK.class)) {
                    for (int i = start; i < columns.size(); i++) {
                        ColumnImpl ci = (ColumnImpl) columns.get(i);
                        columns.set(i, new ColumnImpl(
                                ci.columnName(), ci.index(), ci.type(), ci.persistedType(), ci.primaryKey(),
                                ci.generation(), ci.sequence(),
                                ci.foreignKey(), ci.foreignKeyGeneration(), ci.keyIndex(), ci.nullable(),
                                false, false, ci.version(), ci.ref(), ci.function(),
                                ci.metamodel(), ci.secondaryMetamodel()
                        ));
                    }
                }
            }
            // A query column a subtype declares is a column of the shared table; one declared by several subtypes is
            // one column.
            var queryColumnNames = new HashSet<String>();
            for (Class<?> subtype : permitted) {
                var subtypeQueryColumns = new ArrayList<Column>();
                var ctx = new BuildContext(builder, columns, fields, subtypeQueryColumns, Metamodel.root((Class<? extends Data>) subtype), index, FetchPlan.NONE);
                createQueryColumns(ctx, ctx.rootMetamodel(), (Class<? extends Data>) subtype, false);
                for (var queryColumn : subtypeQueryColumns) {
                    if (queryColumnNames.add(queryColumn.name().toLowerCase(ROOT))) {
                        queryColumns.add(queryColumn);
                    }
                }
            }
        } catch (UncheckedSqlTemplateException e) {
            throw e.getCause();
        }
        var tableName = getTableName(sealedType, builder.tableNameResolver(), builder.schemaResolver());
        // Override the type to the sealed interface so that Model.type() returns the sealed interface
        // (e.g., Vehicle.class) rather than the first permitted subclass (e.g., Car.class).
        return new ModelImpl<>(firstRecordType, sealedType, tableName, fields, columns, queryColumns);
    }

    /**
     * The state of a model under construction. {@code columns} and {@code fields} are aligned, one record field per
     * column; {@code queryColumns} holds the columns the query side knows and the record does not carry, which have
     * no field.
     */
    record BuildContext(ModelBuilder builder, List<Column> columns, List<RecordField> fields, List<Column> queryColumns, Metamodel<?, ?> rootMetamodel, AtomicInteger index, FetchPlan fetchPlan) {
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
        createQueryColumns(ctx, foreignMetamodel, targetType, nullableDueToJoin);
    }

    /**
     * Adds the query columns the type declares, under the parent's path, so that a path through a relation reaches
     * them as it reaches the components. A query column is never selected, inserted or updated, so it takes no
     * position among the model's columns and no record field.
     */
    private static void createQueryColumns(BuildContext ctx,
                                           Metamodel<?, ?> parentMetamodel,
                                           Class<? extends Data> type,
                                           boolean parentNullable) {
        List<QueryColumn> queryColumns = getQueryColumns(type);
        if (queryColumns.isEmpty()) {
            return;
        }
        try {
            var componentNames = getRecordType(type).fields().stream().map(RecordField::name).collect(toSet());
            // The columns the fields of this table map, as emitted: a query column cannot name one of them.
            var tablePath = parentMetamodel.fieldPath();
            var fieldColumns = ctx.columns().stream()
                    .filter(column -> column.metamodel().table().fieldPath().equals(tablePath))
                    .collect(toMap(column -> column.name().toLowerCase(ROOT), column -> column.metamodel().field(), (first, second) -> first));
            var declared = new HashSet<String>();
            var declaredColumns = new HashSet<String>();
            for (QueryColumn queryColumn : queryColumns) {
                String fieldName = queryColumn.field();
                if (fieldName.isEmpty()) {
                    throw new SqlTemplateException("A query column of %s names no field.".formatted(type.getSimpleName()));
                }
                if (componentNames.contains(fieldName)) {
                    throw new SqlTemplateException("Query column '%s' of %s has the name of a record component; a query column is a column the record does not carry.".formatted(fieldName, type.getSimpleName()));
                }
                if (!declared.add(fieldName)) {
                    throw new SqlTemplateException("Query column '%s' of %s is declared twice.".formatted(fieldName, type.getSimpleName()));
                }
                RecordField field = getQueryColumnField(type, queryColumn);
                @SuppressWarnings("unchecked")
                Metamodel<Data, ?> metamodel = (Metamodel<Data, ?>) Metamodel.of(
                        (Class<? extends Data>) parentMetamodel.root(),
                        parentMetamodel.fieldPath().isEmpty() ? fieldName : parentMetamodel.fieldPath() + "." + fieldName);
                ColumnName columnName = queryColumn.column().isEmpty()
                        ? new ColumnName(ctx.builder().columnNameResolver().resolveColumnName(field))
                        : new ColumnName(queryColumn.column());
                if (!queryColumn.function().isEmpty()) {
                    try {
                        SqlFunction.of(queryColumn.function());
                    } catch (IllegalArgumentException e) {
                        throw new SqlTemplateException("Query column '%s' of %s names function '%s', which is not a SQL identifier.".formatted(fieldName, type.getSimpleName(), queryColumn.function()), e);
                    }
                }
                String mappedBy = fieldColumns.get(columnName.name().toLowerCase(ROOT));
                if (mappedBy != null) {
                    throw new SqlTemplateException("Query column '%s' of %s names column '%s', which field '%s' maps; a query column is a column the record does not carry.".formatted(fieldName, type.getSimpleName(), columnName.name(), mappedBy));
                }
                if (!declaredColumns.add(columnName.name().toLowerCase(ROOT))) {
                    throw new SqlTemplateException("Query column '%s' of %s names column '%s', which another query column of %s names as well.".formatted(fieldName, type.getSimpleName(), columnName.name(), type.getSimpleName()));
                }
                ctx.queryColumns().add(new ColumnImpl(
                        columnName,
                        0,
                        queryColumn.type(),
                        queryColumn.type(),
                        false,
                        GenerationStrategy.NONE,
                        "",
                        false,
                        GenerationStrategy.NONE,
                        -1,
                        parentNullable || queryColumn.nullable(),
                        false,
                        false,
                        false,
                        false,
                        queryColumn.function(),
                        metamodel,
                        null
                ));
            }
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
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
                    "",
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
