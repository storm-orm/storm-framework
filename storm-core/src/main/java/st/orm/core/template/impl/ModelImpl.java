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

import static java.util.List.copyOf;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static st.orm.EnumType.NAME;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.ObjectMapperFactory.nullableHint;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorValue;
import static st.orm.core.template.impl.RecordReflection.getFkLeaves;
import static st.orm.core.template.impl.RecordReflection.getRecordField;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isJoinedEntity;
import static st.orm.core.template.impl.RecordReflection.isPolymorphicData;
import static st.orm.core.template.impl.RecordReflection.isRecord;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.DbEnum;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.spi.ORMConverter;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.SqlDialect;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Default {@link Model} implementation backed by {@link RecordType} and precomputed column metadata.
 *
 * <p>This implementation optimizes template operations by precomputing column mappings, converters,
 * and lookup tables up front.</p>
 *
 * <p><strong>Column iteration contract:</strong> {@link #forEachValue(List, Data, BiConsumer)} expects
 * the provided {@code columns} list to be ordered by {@link Column#index()} (typically originating from
 * {@link #columns()} or {@link #declaredColumns()}).</p>
 *
 * <p><strong>Converter subsets:</strong> if a logical field expands into multiple physical columns via an
 * {@link ORMConverter}, callers may provide any subset of those physical columns. Missing physical columns
 * are allowed as long as the provided list remains ordered.</p>
 *
 * @param <E> the entity/projection type.
 * @param <ID> the primary key type, or {@code Void} for projections without a primary key.
 */
public final class ModelImpl<E extends Data, ID> implements Model<E, ID> {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private final RecordType recordType;
    private final Class<E> typeOverride;
    private final TableName tableName;
    private final List<RecordField> fields;
    private final List<Column> columns;
    private final RecordField primaryKeyField;
    private final List<Column> declaredColumns;
    private final List<Column> queryColumns;
    private final Map<Class<? extends Data>, List<Metamodel<E, ?>>> mappedMetamodels;
    private final Metamodel<E, ID> primaryKeyMetamodel;
    private final Map<Metamodel<?, ?>, List<Column>> columnMap;

    /**
     * For sealed entity models, the 1-based column index of the discriminator column; -1 for non-sealed models.
     */
    private final int discriminatorColumnIndex;

    /**
     * Maps 1-based column indices to the sealed Data type for polymorphic FK discriminator columns.
     *
     * <p>A polymorphic FK (e.g., {@code Ref<Commentable>}) expands to two physical columns:
     * a discriminator column (e.g., {@code target_type}) and an FK id column (e.g., {@code target_id}).
     * This map identifies the discriminator columns so that
     * {@link #forEachValueOrdered(List, Data, BiConsumer)} can extract the discriminator value from the
     * {@link Ref#type()} instead of the {@link Ref#id()}.</p>
     */
    private final Map<Integer, Class<?>> polymorphicFkDiscriminatorColumns;

    /**
     * The metamodel reading each column's value from a record, aligned to {@link #columns()} by column index
     * (1-based in {@link Column#index()}).
     */
    private final Metamodel<Data, ?>[] columnMetamodels;

    /**
     * Converter aligned to {@link #columns()} by column index (1-based in {@link Column#index()}); {@code null} for
     * a column without one.
     *
     * <p>If a logical field expands into multiple physical columns, the converter is registered for each
     * physical column that belongs to that expansion.</p>
     */
    private final ORMConverter[] converters;

    /**
     * Per-column accessor paths for foreign key columns, following the referenced key chain from the foreign
     * key field's value down to the terminal field bound to the column. Precomputed at model construction so
     * value extraction is a plain loop of accessor invocations; {@code null} for columns that do not use chain
     * extraction (non-foreign-key columns and polymorphic foreign keys).
     */
    private final RecordField[][] keyPaths;

    /**
     * Parent metamodel for each converter group, keyed by the group's 0-based start column index. A converter reads
     * its value by reflectively invoking the field accessor on the record passed to
     * {@link ORMConverter#toDatabase(Object)}, so that record must be the one that declares the field. This metamodel
     * navigates the root record down to the enclosing inline component, or resolves to the root record itself for a
     * top-level field. Precomputed so the per-row value loop is a single lookup plus {@code getValue}, with no
     * string/metamodel allocation on the write path.
     */
    private final Metamodel<Data, ?>[] converterParents;

    /**
     * How each permitted subtype of a sealed entity supplies the model's columns, keyed by subtype; empty for other
     * models. The columns of a sealed model are rooted at the subtype that first declares their field, so a record
     * of another subtype reads them through its own metamodels, precomputed here so the per-row value loop is the
     * same array walk as for any model.
     */
    private final Map<Class<?>, SubtypeAccess> subtypeAccess;

    /** The model's own accessors: the column metamodels, converters and converter parents as one. */
    private final SubtypeAccess access;

    /**
     * The accessors of one subtype of a sealed entity, aligned to {@link #columns()}: {@code null} where the subtype
     * does not declare the column's field, whose columns then hold NULL in its rows.
     */
    private record SubtypeAccess(Metamodel<Data, ?>[] metamodels,
                                 ORMConverter[] converters,
                                 Metamodel<Data, ?>[] converterParents) {}

    public ModelImpl(RecordType recordType,
                     TableName tableName,
                     List<RecordField> fields,
                     List<Column> columns,
                     List<Column> queryColumns) throws SqlTemplateException {
        this(recordType, null, tableName, fields, columns, queryColumns);
    }

    /**
     * Creates a model with an optional type override for sealed entity hierarchies.
     *
     * <p>When a sealed entity interface (e.g., {@code Vehicle}) uses single-table or joined inheritance,
     * the {@code recordType} is derived from the first permitted subclass (e.g., {@code Car}), but
     * {@link #type()} must return the sealed interface. The {@code typeOverride} provides this.</p>
     *
     * @param recordType the record type from which column metadata is derived.
     * @param typeOverride the type to return from {@link #type()}, or null to use the record type.
     * @param tableName the table name.
     * @param fields the record fields aligned with columns.
     * @param columns the column metadata.
     * @param queryColumns the columns a query names but the record does not carry, those of the type and those
     *                     reached through its expanded relations; they are resolved by metamodel and take no position
     *                     among {@code columns}.
     */
    public ModelImpl(RecordType recordType,
                     @Nullable Class<E> typeOverride,
                     TableName tableName,
                     List<RecordField> fields,
                     List<Column> columns,
                     List<Column> queryColumns) throws SqlTemplateException {
        assert fields.size() == columns.size() : "Columns and fields must have the same size";
        this.recordType = requireNonNull(recordType, "recordType");
        this.typeOverride = typeOverride;
        this.tableName = requireNonNull(tableName, "tableName");
        this.fields = copyOf(fields);
        this.columns = copyOf(columns);
        this.queryColumns = initDeclaredColumns(queryColumns);
        this.discriminatorColumnIndex = initDiscriminatorColumnIndex(typeOverride);
        this.columnMap = initColumnMap(this.columns, queryColumns, typeOverride, this.discriminatorColumnIndex);
        this.polymorphicFkDiscriminatorColumns = initPolymorphicFkDiscriminatorColumns(this.fields, this.columns);
        this.columnMetamodels = initColumnMetamodels(this.columns);
        this.converters = initConverters(this.fields, this.columns);
        this.converterParents = initConverterParents(this.converters, this.columns);
        this.access = new SubtypeAccess(this.columnMetamodels, this.converters, this.converterParents);
        this.subtypeAccess = initSubtypeAccess(typeOverride, this.discriminatorColumnIndex, this.columns, this.converters);
        this.keyPaths = initKeyPaths(this.fields, this.columns);
        this.primaryKeyField = initPrimaryKeyField(this.recordType);
        this.declaredColumns = initDeclaredColumns(this.columns);
        this.primaryKeyMetamodel = initPrimaryKeyMetamodel(this.declaredColumns);
        this.mappedMetamodels = initMappedMetamodels(this.fields, this.columns);
    }

    /**
     * Maps each column under its metamodels. A column of a sealed entity is rooted at the subtype that first declares
     * its field, and is registered as well under the sealed type and under every other subtype declaring the field,
     * so a path rooted at any of them resolves to it. The discriminator column maps no field and is not registered.
     */
    @SuppressWarnings("unchecked")
    private static Map<Metamodel<?, ?>, List<Column>> initColumnMap(List<Column> columns,
                                                                   List<Column> queryColumns,
                                                                   @Nullable Class<?> typeOverride,
                                                                   int discriminatorColumnIndex) {
        var map = new HashMap<Metamodel<?, ?>, List<Column>>();
        List<Class<? extends Data>> sealedRoots = discriminatorColumnIndex > 0
                ? sealedRoots((Class<? extends Data>) typeOverride)
                : List.of();
        for (var column : columns) {
            if (column.index() == discriminatorColumnIndex) {
                continue;
            }
            map.computeIfAbsent(column.metamodel().canonical(), ignore -> new ArrayList<>()).add(column);
            if (column.secondaryMetamodel() != null) {
                map.computeIfAbsent(column.secondaryMetamodel().canonical(), ignore -> new ArrayList<>()).add(column);
            }
            if (!sealedRoots.isEmpty() && column.metamodel().path().isEmpty()) {
                String fieldPath = column.metamodel().fieldPath();
                for (var root : sealedRoots) {
                    if (root != column.metamodel().root() && declaresField(root, fieldPath)) {
                        map.computeIfAbsent(Metamodel.of(root, fieldPath).canonical(), ignore -> new ArrayList<>()).add(column);
                    }
                }
            }
        }
        for (var column : queryColumns) {
            map.computeIfAbsent(column.metamodel().canonical(), ignore -> new ArrayList<>()).add(column);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(map);
    }

    /**
     * Returns the types a column of a sealed entity can be rooted at: the sealed type and its permitted subtypes.
     */
    @SuppressWarnings("unchecked")
    private static List<Class<? extends Data>> sealedRoots(Class<? extends Data> sealedType) {
        var roots = new ArrayList<Class<? extends Data>>();
        roots.add(sealedType);
        for (Class<?> subtype : sealedType.getPermittedSubclasses()) {
            roots.add((Class<? extends Data>) subtype);
        }
        return roots;
    }

    /**
     * Returns whether a path rooted at {@code root} resolves: its first segment names a field of a subtype, or an
     * accessor the sealed type declares, which is what a metamodel rooted at the sealed type reads the value through.
     */
    private static boolean declaresField(Class<? extends Data> root, String fieldPath) {
        int dot = fieldPath.indexOf('.');
        String topLevel = dot < 0 ? fieldPath : fieldPath.substring(0, dot);
        if (root.isSealed()) {
            return MetamodelFactory.hasAccessor(root, topLevel);
        }
        for (var field : REFLECTION.getRecordType(root).fields()) {
            if (field.name().equals(topLevel)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Metamodel<Data, ?>[] initColumnMetamodels(List<Column> columns) {
        var metamodels = new Metamodel[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            metamodels[i] = columns.get(i).metamodel();
        }
        return metamodels;
    }

    /**
     * Precomputes, per permitted subtype of a sealed entity, the metamodels and converters that read the model's
     * columns from a record of that subtype. A column's own metamodel serves the subtype it is rooted at; another
     * subtype declaring the field reads the same path rooted at itself, and a top-level converted field takes the
     * converter of the subtype's own field, since a converter reads its value through the declaring record.
     */
    @SuppressWarnings("unchecked")
    private static Map<Class<?>, SubtypeAccess> initSubtypeAccess(@Nullable Class<?> typeOverride,
                                                                 int discriminatorColumnIndex,
                                                                 List<Column> columns,
                                                                 ORMConverter[] converters) throws SqlTemplateException {
        if (discriminatorColumnIndex < 0) {
            return Map.of();
        }
        Map<Class<?>, SubtypeAccess> access = new HashMap<>();
        for (Class<?> subtype : typeOverride.getPermittedSubclasses()) {
            Set<String> fieldNames = REFLECTION.getRecordType(subtype).fields().stream()
                    .map(RecordField::name)
                    .collect(toSet());
            var metamodels = new Metamodel[columns.size()];
            var subtypeConverters = new ORMConverter[columns.size()];
            var parents = new Metamodel[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                if (i == discriminatorColumnIndex - 1) {
                    continue;
                }
                var metamodel = columns.get(i).metamodel();
                String fieldPath = metamodel.fieldPath();
                int dot = fieldPath.indexOf('.');
                String topLevel = dot < 0 ? fieldPath : fieldPath.substring(0, dot);
                if (!fieldNames.contains(topLevel)) {
                    continue;   // The subtype lacks the field, so the column holds NULL in its rows.
                }
                metamodels[i] = metamodel.root() == subtype
                        ? metamodel
                        : (Metamodel<Data, ?>) Metamodel.of((Class<? extends Data>) subtype, fieldPath);
                if (converters[i] != null) {
                    int lastDot = fieldPath.lastIndexOf('.');
                    String parentPath = lastDot < 0 ? "" : fieldPath.substring(0, lastDot);
                    subtypeConverters[i] = parentPath.isEmpty()
                            ? getORMConverter(getRecordField(subtype, topLevel)).orElseThrow()
                            : converters[i];
                    parents[i] = (Metamodel<Data, ?>) Metamodel.of((Class<? extends Data>) subtype, parentPath);
                }
            }
            access.put(subtype, new SubtypeAccess(metamodels, subtypeConverters, parents));
        }
        return Map.copyOf(access);
    }

    /**
     * For sealed entity models, returns the 1-based index of the discriminator column. The discriminator is always
     * the first column in a sealed entity model (index 1).
     */
    private static int initDiscriminatorColumnIndex(@Nullable Class<?> typeOverride) {
        return typeOverride != null && typeOverride.isSealed() && isSealedEntity(typeOverride) ? 1 : -1;
    }

    /**
     * Identifies polymorphic FK discriminator columns. For each polymorphic FK field (a {@code Ref} to a sealed
     * {@link Data} interface), the first of its two columns is the discriminator. This method maps those column
     * indices to the sealed type so that value extraction can produce the discriminator value instead of the FK id.
     */
    private static Map<Integer, Class<?>> initPolymorphicFkDiscriminatorColumns(
            List<RecordField> fields, List<Column> columns) {
        Map<Integer, Class<?>> map = new HashMap<>();
        RecordField previousField = null;
        for (int i = 0; i < fields.size(); i++) {
            RecordField field = fields.get(i);
            if (field.equals(previousField)) {
                // Second (or later) column for the same field; skip.
                previousField = field;
                continue;
            }
            previousField = field;
            if (!columns.get(i).foreignKey() || !Ref.class.isAssignableFrom(field.type())) {
                continue;
            }
            try {
                Class<? extends Data> refDataType = getRefDataType(field);
                if (isPolymorphicData(refDataType)) {
                    // First column of a polymorphic FK pair is the discriminator.
                    map.put(columns.get(i).index(), refDataType);
                }
            } catch (SqlTemplateException e) {
                // Should not happen for a valid model; skip gracefully.
            }
        }
        return map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    private static RecordField[][] initKeyPaths(List<RecordField> fields,
                                                List<Column> columns) throws SqlTemplateException {
        var paths = new RecordField[columns.size()][];
        Map<RecordField, Integer> columnsByField = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).foreignKey()) {
                columnsByField.merge(fields.get(i), 1, Integer::sum);
            }
        }
        Map<RecordField, Optional<List<RecordReflection.KeyLeaf>>> leavesByField = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            if (!column.foreignKey()) {
                continue;
            }
            var field = fields.get(i);
            var cached = leavesByField.get(field);
            if (cached == null) {
                cached = Optional.ofNullable(getFkLeaves(field));
                leavesByField.put(field, cached);
            }
            // Only use chain extraction when the flattened key matches the emitted columns one-to-one;
            // polymorphic foreign keys keep the legacy extraction.
            var leaves = cached.orElse(null);
            if (leaves == null || leaves.size() != columnsByField.get(field)) {
                continue;
            }
            int keyIndex = column.keyIndex();
            if (keyIndex >= 1 && keyIndex <= leaves.size()) {
                paths[i] = leaves.get(keyIndex - 1).path().toArray(new RecordField[0]);
            }
        }
        return paths;
    }

    /**
     * Extracts the value bound to a foreign key column by walking the column's key chain path. {@link Ref}
     * values substitute their identifier for the next step of the path.
     */
    @Nullable
    private Object extractKeyValue(@Nullable Object value,
                                   RecordField[] path,
                                   Column column) throws SqlTemplateException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Ref) && !(value instanceof Data)) {
            throw new SqlTemplateException("Invalid foreign key type for column '%s'. Expected a Ref or Data type, but got an unrecognized value type. Ensure the foreign key field is correctly typed.".formatted(column.name()));
        }
        for (int i = 0; i < path.length && value != null; i++) {
            value = value instanceof Ref<?> ref
                    ? ref.id()                          // The identifier is the result of the next accessor.
                    : REFLECTION.invoke(path[i], value);
        }
        return value;
    }

    private static ORMConverter[] initConverters(List<RecordField> fields, List<Column> columns) {
        var converters = new ORMConverter[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            converters[i] = getORMConverter(fields.get(i)).orElse(null);
        }
        return converters;
    }

    /**
     * Precomputes, for each converter-backed column, the metamodel that navigates the root record to the record
     * declaring the converted field — the enclosing inline component, or the root record itself for a top-level field.
     * Keyed by column index (0-based) so the value loop resolves a converter group's parent with a single lookup. A
     * converter group's start column is always converter-backed, so its key is always present.
     */
    @SuppressWarnings("unchecked")
    private static Metamodel<Data, ?>[] initConverterParents(ORMConverter[] converters,
                                                             List<Column> columns) {
        var parents = new Metamodel[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            if (converters[i] == null) {
                continue;
            }
            Metamodel<Data, ?> columnMetamodel = columns.get(i).metamodel();
            String fieldPath = columnMetamodel.fieldPath();
            int lastDot = fieldPath.lastIndexOf('.');
            // The parent path is the field path without its final segment; empty resolves to the root record itself.
            String parentPath = lastDot < 0 ? "" : fieldPath.substring(0, lastDot);
            parents[i] = Metamodel.of(columnMetamodel.root(), parentPath);
        }
        return parents;
    }

    private static RecordField initPrimaryKeyField(RecordType recordType) {
        for (var field : recordType.fields()) {
            if (field.isAnnotationPresent(PK.class)) {
                return field;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Data, ID> Metamodel<E, ID> initPrimaryKeyMetamodel(List<Column> declaredColumns) {
        var metamodels = declaredColumns.stream()
                .filter(Column::primaryKey)
                .map(Column::metamodel)
                .collect(toSet());
        assert metamodels.size() <= 1 : "More than one primary key metamodel found.";
        if (metamodels.isEmpty()) {
            return null;
        }
        return (Metamodel<E, ID>) metamodels.iterator().next();
    }

    private static List<Column> initDeclaredColumns(List<Column> columns) {
        List<Column> declared = new ArrayList<>();
        for (var column : columns) {
            if (column.metamodel().path().isEmpty()) {
                declared.add(column);
            }
        }
        return copyOf(declared);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Data> Map<Class<? extends Data>, List<Metamodel<E, ?>>> initMappedMetamodels(
            List<RecordField> fields,
            List<Column> columns
    ) throws SqlTemplateException {
        Map<Class<? extends Data>, List<Metamodel<E, ?>>> mapped = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            var field = fields.get(i);
            if (!field.isAnnotationPresent(FK.class)) {
                continue;
            }
            var type = field.type();
            if (Ref.class.isAssignableFrom(type)) {
                type = RecordReflection.getRefDataType(field);
            }
            var dataType = (Class<? extends Data>) type;
            var metamodel = (Metamodel<E, ?>) columns.get(i).metamodel();
            mapped.computeIfAbsent(dataType, ignore -> new ArrayList<>()).add(metamodel);
        }
        mapped.replaceAll((k, v) -> copyOf(v));
        return Map.copyOf(mapped);
    }

    @Override
    public Optional<Metamodel<E, ?>> findMetamodel(Class<? extends Data> type) {
        if (recordType.type().equals(type) || type().equals(type)) {
            return Optional.ofNullable(primaryKeyMetamodel);
        }
        var metamodels = mappedMetamodels.get(type);
        if (metamodels == null || metamodels.size() > 1) {
            return empty();
        }
        return Optional.of(metamodels.getFirst());
    }

    @Override
    public String schema() {
        return tableName.schema();
    }

    @Override
    public String name() {
        return tableName.name();
    }

    @Override
    public String qualifiedName(SqlDialect dialect) {
        return tableName.qualified(dialect);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<E> type() {
        return typeOverride != null ? typeOverride : (Class<E>) recordType.type();
    }

    @Override
    public boolean isJoinedInheritance() {
        return typeOverride != null && typeOverride.isSealed() && isJoinedEntity(typeOverride);
    }

    @Override
    public Class<ID> primaryKeyType() {
        //noinspection unchecked
        return primaryKeyField == null ? (Class<ID>) Void.class : (Class<ID>) primaryKeyField.type();
    }

    @Override
    public boolean isDefaultPrimaryKey(@Nullable ID pk) {
        return REFLECTION.isDefaultValue(pk);
    }

    @Override
    public Optional<Metamodel<E, ID>> getPrimaryKeyMetamodel() {
        return ofNullable(primaryKeyMetamodel);
    }

    @Override
    public List<Column> getColumns(Metamodel<?, ?> metamodel) throws SqlTemplateException {
        var columns = columnMap.get(metamodel.canonical());
        if (columns == null) {
            if (metamodel.isInline()) {
                return getInlineColumns(metamodel);
            }
            throw new SqlTemplateException("Column not found for metamodel: %s.".formatted(metamodel));
        }
        if (columns.size() > 1) {
            // Get fully qualified matches. A foreign key column is registered under the key it carries as well, so
            // a path naming that key ("movie.id") matches the column through its secondary metamodel, and each of
            // two foreign keys to one table resolves to its own column.
            String fieldPath = metamodel.fieldPath();
            var matches = columns.stream()
                    .filter(column -> column.metamodel().fieldPath().equals(fieldPath)
                            || (column.secondaryMetamodel() != null
                                && column.secondaryMetamodel().fieldPath().equals(fieldPath)))
                    .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            if (columns.stream().map(Column::metamodel).map(Metamodel::fieldPath).distinct().count() > 1) {
                throw new SqlTemplateException("Ambiguous column mapping for metamodel: %s.".formatted(metamodel));
            }
        }
        return columns;
    }

    /**
     * Resolves all declared columns that belong to the given inline record metamodel.
     *
     * <p>Inline records are not stored directly in the column map because they expand into their constituent
     * fields. This method collects all declared columns whose metamodel field path starts with the inline
     * record's field path prefix.</p>
     */
    private List<Column> getInlineColumns(Metamodel<?, ?> metamodel) throws SqlTemplateException {
        String prefix = metamodel.fieldPath() + ".";
        var inlineColumns = declaredColumns.stream()
                .filter(column -> column.metamodel().fieldPath().startsWith(prefix))
                .toList();
        if (inlineColumns.isEmpty()) {
            throw new SqlTemplateException("Column not found for inline metamodel: %s.".formatted(metamodel));
        }
        return inlineColumns;
    }

    @Override
    public void forEachValue(List<Column> columns,
                             E record,
                             BiConsumer<Column, Object> consumer) throws SqlTemplateException {
        requireNonNull(columns, "columns");
        requireNonNull(record, "record");
        requireNonNull(consumer, "consumer");
        forEachValueOrdered(columns, record, consumer);
    }

    @Override
    public void validateForeignKeys(List<Column> columns, E record) throws SqlTemplateException {
        requireNonNull(columns, "columns");
        requireNonNull(record, "record");
        var metamodels = accessors(record).metamodels();
        for (var column : columns) {
            if (!column.foreignKey() || column.foreignKeyGeneration() == GenerationStrategy.NONE) {
                continue;
            }
            var metamodel = metamodels[column.index() - 1];
            if (metamodel == null) {
                continue;   // The record's subtype lacks the field.
            }
            var value = getValue(metamodel, record);
            if (value == null) {
                continue;
            }
            Object foreignKeyId;
            if (value instanceof Ref<?> ref) {
                foreignKeyId = ref.id();
            } else if (value instanceof Data data) {
                foreignKeyId = REFLECTION.getId(data);
            } else {
                continue;
            }
            if (foreignKeyId != null && REFLECTION.isDefaultValue(foreignKeyId)) {
                throw new PersistenceException(
                        "Foreign key '%s.%s' has a default primary key value. "
                                .formatted(record.getClass().getSimpleName(), column.name())
                        + "This typically indicates an unsaved entity is being used as a reference. "
                        + "Ensure the referenced entity has been persisted before using it as a foreign key, "
                        + "or insert the whole graph in one operation via writeSet().");
            }
        }
    }

    @Override
    public void forEachValue(Metamodel<E, ?> metamodel,
                             Object object,
                             BiConsumer<Column, Object> consumer) throws SqlTemplateException {
        // For sealed entity models, columns originally share the same root metamodel, so
        // getColumns() could not distinguish between PK and non-PK columns. When a generated
        // metamodel is available (e.g., Animal_.name), its canonical form is registered via
        // a secondary metamodel on the column, enabling field-specific resolution.
        boolean resolvedViaInline = false;
        List<Column> columns;
        if (discriminatorColumnIndex > 0) {
            // The root of a sealed entity names the entity itself, which its primary key identifies.
            columns = metamodel.isColumn()
                    ? getColumns(metamodel)
                    : declaredColumns.stream().filter(Column::primaryKey).toList();
        } else if (metamodel.isInline() && columnMap.get(metamodel.canonical()) == null) {
            // Inline record not directly in column map (e.g., Address). Fall back to inline resolution.
            columns = getInlineColumns(metamodel);
            resolvedViaInline = true;
        } else {
            columns = getColumns(metamodel);
        }
        if (resolvedViaInline) {
            forEachInlineValue(metamodel, columns, object, consumer);
            return;
        }
        // Follow the key chain down to the value the column holds. The identifier of an entity-keyed entity is itself
        // an entity, and a reference-keyed entity holds its identifier in the reference, which carries the key without
        // fetching the target. A compound key record is not a key holder and terminates the chain, so it reaches the
        // component-wise branch below.
        Object value = object;
        while (true) {
            if (value instanceof Data data) {
                value = REFLECTION.getId(data);
            } else if (value instanceof Ref<?> ref) {
                value = ref.id();
            } else {
                break;
            }
        }
        // The chain terminates on null when the key it walks is absent, for instance an entity whose identifier is
        // not set. The columns then carry no value.
        if (value == null) {
            for (var column : columns) {
                consumer.accept(column, null);
            }
            return;
        }
        // We may check whether the value is compatible with the metamodel. Note that we need to check for primary-key
        // compatibility in the case of data classes. In this case we need to add the primary key type to the metamodel.
        if (isRecord(value.getClass())) {
            for (var column : columns) {
                consumer.accept(column, map(column, REFLECTION.getRecordValue(value, column.keyIndex() - 1)));
            }
        } else {
            if (columns.size() != 1) {
                throw new SqlTemplateException("Metamodel does not resolve to a single column: %s.".formatted(metamodel));
            }
            var column = columns.getFirst();
            consumer.accept(column, map(column, value));
        }
    }

    /**
     * Extracts values for an inline record metamodel.
     *
     * <p>Inline records expand into their constituent fields in the column model. This method maps each column
     * back to its corresponding record component using the column's field path relative to the inline record,
     * handling both plain fields and foreign key fields.</p>
     */
    private void forEachInlineValue(Metamodel<E, ?> metamodel,
                                    List<Column> columns,
                                    Object object,
                                    BiConsumer<Column, Object> consumer) throws SqlTemplateException {
        RecordType inlineRecordType = REFLECTION.getRecordType(object.getClass());
        List<? extends RecordField> inlineFields = inlineRecordType.fields();
        // Build a field-name to component-index map for the inline record.
        Map<String, Integer> fieldIndexMap = HashMap.newHashMap(inlineFields.size());
        for (int i = 0; i < inlineFields.size(); i++) {
            fieldIndexMap.put(inlineFields.get(i).name(), i);
        }
        String prefix = metamodel.fieldPath();
        for (var column : columns) {
            String columnFieldPath = column.metamodel().fieldPath();
            // Strip the inline prefix to get the relative field path within the inline record.
            String relativeField = columnFieldPath.substring(prefix.length() + 1);
            // The first segment of the relative field path is the component name.
            int dotIndex = relativeField.indexOf('.');
            String componentName = dotIndex >= 0 ? relativeField.substring(0, dotIndex) : relativeField;
            Integer componentIndex = fieldIndexMap.get(componentName);
            if (componentIndex == null) {
                throw new SqlTemplateException("No component '%s' found in inline record %s."
                        .formatted(componentName, object.getClass().getSimpleName()));
            }
            Object value = REFLECTION.getRecordValue(object, componentIndex);
            if (column.foreignKey()) {
                // Handle FK components: walk the key chain from the referenced Data object to the column value.
                var path = keyPaths[column.index() - 1];
                if (path != null) {
                    value = extractKeyValue(value, path, column);
                } else {
                    if (value instanceof Data data) {
                        value = REFLECTION.getId(data);
                    }
                    if (value != null && isRecord(value.getClass())) {
                        value = REFLECTION.getRecordValue(value, column.keyIndex() - 1);
                    }
                }
            } else if (value != null && column.primaryKey() && isRecord(value.getClass())) {
                // Handle compound primary keys within the inline record.
                value = REFLECTION.getRecordValue(value, column.keyIndex() - 1);
            }
            consumer.accept(column, map(column, value));
        }
    }

    /**
     * Extracts values for an ordered list of columns.
     *
     * <p>This method supports ORM converters that expand a single logical field into multiple physical columns.
     * Converter-backed physical columns are assumed to be sequential in the model column list.</p>
     *
     * <p>The input list may omit physical columns from a converter group. Missing columns are allowed.
     * The only structural requirement is that the input list is ordered by {@link Column#index()}.</p>
     */
    private void forEachValueOrdered(List<Column> view,
                                     E record,
                                     BiConsumer<Column, Object> consumer)
            throws SqlTemplateException {
        var accessors = accessors(record);
        var metamodels = accessors.metamodels();
        var converters = accessors.converters();
        var converterParents = accessors.converterParents();
        int lastIndex = -1;
        int cachedGroupStart = -1;
        int cachedParamCount = -1;
        List<?> cachedValues = null;
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0, size = view.size(); i < size; i++) {
            var column = view.get(i);
            int index = column.index();
            if (lastIndex != -1 && index <= lastIndex) {
                throw new SqlTemplateException(
                        "Columns must be strictly ordered by model index. Got %d after %d."
                                .formatted(index, lastIndex)
                );
            }
            lastIndex = index;
            if (index == discriminatorColumnIndex) {
                // The discriminator value follows from the record's concrete type.
                consumer.accept(column, getDiscriminatorValue(record.getClass(), typeOverride));
                continue;
            }
            var metamodel = metamodels[index - 1];
            if (metamodel == null) {
                // The column belongs to a field of another subtype of the sealed entity.
                consumer.accept(column, null);
                continue;
            }
            var converter = converters[index - 1];
            if (converter != null) {
                int parameterCount = converter.getParameterCount();
                int groupStart = findConverterGroupStart(converters, index - 1, parameterCount);
                int groupStartIndex = groupStart + 1;
                if (groupStart != cachedGroupStart) {
                    // Navigate the root record down to the record that declares the converted field so the converter
                    // reflects its accessor on the correct instance (the enclosing inline component, or the root).
                    cachedValues = converter.toDatabase(converterParents[groupStart].getValue(record));
                    cachedGroupStart = groupStart;
                    cachedParamCount = parameterCount;
                }
                assert cachedValues != null;
                if (cachedValues.size() != cachedParamCount) {
                    throw new SqlTemplateException(
                            "Converter for '%s' returned %d value(s), expected %d."
                                    .formatted(column.name(), cachedValues.size(), cachedParamCount)
                    );
                }
                int valueIndex = index - groupStartIndex;
                if (valueIndex < 0 || valueIndex >= cachedParamCount) {
                    throw new SqlTemplateException(
                            "Converter value index out of bounds for '%s': idx=%d start=%d count=%d."
                                    .formatted(column.name(), index, groupStartIndex, cachedParamCount)
                    );
                }
                consumer.accept(column, cachedValues.get(valueIndex));
                continue;
            }
            var value = getValue(metamodel, record);
            if (value == null && !column.nullable() && !column.primaryKey()) {
                throw new SqlTemplateException("Cannot write NULL to non-nullable column '%s'. Ensure the entity field has a value before inserting or updating, or %s if NULL is intended.".formatted(column.name(), nullableHint(type())));
            }
            if (column.foreignKey()) {
                if (value instanceof Ref<?> ref && polymorphicFkDiscriminatorColumns.containsKey(column.index())) {
                    // Polymorphic FK discriminator column: extract the discriminator value from the Ref's
                    // concrete type instead of the FK id.
                    value = getDiscriminatorValue(ref.type(), polymorphicFkDiscriminatorColumns.get(column.index()));
                } else {
                    var path = keyPaths[index - 1];
                    if (path != null) {
                        value = extractKeyValue(value, path, column);
                    } else {
                        if (value instanceof Ref<?> ref) {
                            value = ref.id();
                        } else if (value instanceof Data data) {
                            value = REFLECTION.getId(data);
                        } else if (value != null) {
                            throw new SqlTemplateException("Invalid foreign key type for column '%s'. Expected a Ref or Data type, but got an unrecognized value type. Ensure the foreign key field is correctly typed.".formatted(column.name()));
                        }
                        if (value != null && isRecord(value.getClass())) {
                            value = REFLECTION.getRecordValue(value, column.keyIndex() - 1);
                        }
                    }
                }
            } else if (value != null && column.primaryKey() && isRecord(value.getClass())) {
                value = REFLECTION.getRecordValue(value, column.keyIndex() - 1);
            }
            consumer.accept(column, map(column, value));
        }
    }

    /**
     * Returns the accessors reading the model's columns from the given record: the model's own for a record of its
     * type, or those of the record's subtype for a sealed entity.
     */
    private SubtypeAccess accessors(E record) throws SqlTemplateException {
        if (discriminatorColumnIndex < 0) {
            return access;
        }
        var subtypeAccessors = subtypeAccess.get(record.getClass());
        if (subtypeAccessors == null) {
            throw new SqlTemplateException("%s is not a permitted subtype of %s.".formatted(record.getClass().getSimpleName(), typeOverride.getSimpleName()));
        }
        return subtypeAccessors;
    }

    private Object getValue(Metamodel<Data, ?> metamodel, E record) throws SqlTemplateException {
        try {
            return metamodel.getValue(record);
        } catch (ClassCastException e) {
            throw new SqlTemplateException("Invalid data type: %s. Expected: %s."
                    .formatted(record.getClass().getSimpleName(), metamodel.root().getSimpleName()));
        }
    }

    /**
     * Determines the start index (0-based) of a converter group that contains {@code index}.
     *
     * <p>Converter-backed columns are sequential in the model. Given a column at {@code index} with parameter
     * count {@code n}, the group start must be within {@code [index - (n - 1), index]}.</p>
     *
     * <p>This method chooses the earliest possible start within that window that is still converter-backed.</p>
     */
    private static int findConverterGroupStart(ORMConverter[] converters, int index, int parameterCount) {
        int min = Math.max(0, index - (parameterCount - 1));
        int start = index;
        while (start > min && converters[start - 1] != null) {
            start--;
        }
        return start;
    }

    private Object map(Column column, @Nullable Object value) {
        return switch (value) {
            case Instant it -> Timestamp.from(it);
            case LocalDateTime it -> Timestamp.valueOf(it);
            case OffsetDateTime it -> Timestamp.from(it.toInstant());
            case LocalDate it -> Date.valueOf(it);
            case LocalTime it -> Time.valueOf(it);
            case Calendar it -> new Timestamp(it.getTimeInMillis());
            case java.util.Date it -> new Timestamp(it.getTime());
            case Enum<?> it -> switch (ofNullable(fields.get(column.index() - 1).getAnnotation(DbEnum.class))
                    .map(DbEnum::value)
                    .orElse(NAME)) {
                case NAME -> it.name();
                case ORDINAL -> it.ordinal();
            };
            case null, default -> value;
        };
    }

    @Override
    public RecordType recordType() {
        return recordType;
    }

    @Override
    public List<Column> columns() {
        return columns;
    }

    @Override
    public List<Column> declaredColumns() {
        return declaredColumns;
    }

    @Override
    public List<Column> queryColumns() {
        return queryColumns;
    }
}
