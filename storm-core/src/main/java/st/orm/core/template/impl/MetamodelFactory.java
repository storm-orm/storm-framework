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

import static java.lang.invoke.MethodType.methodType;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getRecordField;
import static st.orm.core.template.impl.RecordReflection.getRecordFields;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isRecord;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import st.orm.AbstractKeyMetamodel;
import st.orm.AbstractMetamodel;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.UK;
import st.orm.mapping.RecordField;

/**
 * Implementation that is used by the generated models.
 *
 * @since 1.4
 */
public final class MetamodelFactory {

    private MetamodelFactory() {
        // Prevent instantiation.
    }

    /**
     * Root metamodels per record type. {@link ClassValue} ties each entry to the lifetime of the record type, so
     * cached metamodels never pin the type or its class loader.
     */
    private static final ClassValue<Metamodel<?, ?>> ROOT_METAMODEL_CACHE = new ClassValue<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected Metamodel<?, ?> computeValue(Class<?> table) {
            return getRootModel((Class<? extends Data>) table);
        }
    };

    /** Metamodels per root table, keyed by path; entries die with the root table. */
    private static final ClassValue<ConcurrentMap<String, Metamodel<?, ?>>> METAMODEL_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentMap<String, Metamodel<?, ?>> computeValue(Class<?> table) {
            return new ConcurrentHashMap<>();
        }
    };

    /**
     * Creates a new metamodel for the given record type.
     */
    public static <T extends Data> Metamodel<T, T> root(Class<T> table) {
        //noinspection unchecked
        return (Metamodel<T, T>) ROOT_METAMODEL_CACHE.get(table);
    }

    /**
     * Creates a new metamodel for the given record type.
     */
    private static <T extends Data> Metamodel<T, T> getRootModel(Class<T> table) {
        ValueComparator<T> wrapped = null;
        if (Data.class.isAssignableFrom(table)) {
            var pkField = findPkField(table).orElse(null);
            if (pkField != null) {
                var pkHandle = buildGetterHandle(table, pkField.name());
                var s = EqualitySupport.compileIsSame(pkHandle);
                wrapped = s::isSame;
            }
        }
        ValueComparator<T> same = wrapped == null ? Objects::equals : wrapped;
        return new AbstractMetamodel<>(table) {
            @Override
            public T getValue(T record) {
                return record;
            }

            @Override
            public boolean isIdentical(T a, @Nullable T b) {
                return a == b;
            }

            @Override
            public boolean isSame(T a, @Nullable T b) {
                try {
                    return same.isSame(a, b);
                } catch (PersistenceException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new PersistenceException("Failed to evaluate isSame for root metamodel of type %s.".formatted(table.getName()), e);
                }
            }
        };
    }

    /**
     * Creates a new metamodel for the given root table and path.
     */
    public static <T extends Data, E extends @Nullable Object> Metamodel<T, E> of(Class<T> rootTable, String path) {
        //noinspection unchecked
        return (Metamodel<T, E>) METAMODEL_CACHE.get(rootTable)
                .computeIfAbsent(path, ignore -> getModel(rootTable, path));
    }

    /**
     * Returns the metamodel column resolution should use for the given metamodel. A path that names the primary key
     * of a table reached through a foreign key resolves to the foreign key field itself: the key is the foreign key
     * column(s) on the referencing table, so the path means the same thing whether the relationship is declared as
     * a reference or as an entity. Reference paths are rewritten this way when they are built; generated entity
     * metamodels are plain path carriers, so the equivalence is applied here instead. Every other metamodel is
     * returned unchanged, and so is any metamodel whose path cannot be inspected reflectively.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Metamodel<?, ?> canonical(Metamodel<?, ?> metamodel) {
        try {
            Class<? extends Data> rootTable = metamodel.root();
            String foreignKeyPath = primaryKeyThroughForeignKeyPath(fieldResolutionClass(rootTable), metamodel.fieldPath());
            if (foreignKeyPath == null) {
                return metamodel;
            }
            return of((Class) rootTable, foreignKeyPath);
        } catch (RuntimeException ignore) {
            return metamodel;
        }
    }

    /**
     * Returns a flat list of leaf metamodels for the given metamodel. If the metamodel is not an inline record, it
     * returns a singleton list containing the metamodel. If it is an inline record, it recursively expands all nested
     * inline records and returns the individual column metamodels.
     */
    public static <T extends Data> List<Metamodel<T, ?>> flatten(Navigable<T, ?> metamodel) {
        if (!metamodel.isInline()) {
            if (metamodel instanceof Metamodel<T, ?> full) {
                return List.of(full);
            }
            // A navigation-only node (beyond a reference) is not a value metamodel; rebuild a resolvable metamodel
            // for its path so it can still be expanded for ORDER BY / GROUP BY.
            return List.of(of(metamodel.root(), metamodel.fieldPath()));
        }
        List<RecordField> fields = getRecordFields(metamodel.fieldType());
        List<Metamodel<T, ?>> result = new ArrayList<>();
        for (RecordField field : fields) {
            String childPath = metamodel.fieldPath() + "." + field.name();
            Metamodel<T, ?> child = of(metamodel.root(), childPath);
            result.addAll(child.flatten());
        }
        return List.copyOf(result);
    }

    /**
     * Returns whether the field designated by the given metamodel allows NULL values, following the semantics of
     * {@link Metamodel.Key#isNullable()}. A metamodel that carries the {@link Metamodel.Key} marker answers for
     * itself. For any other metamodel the record field at the metamodel's path decides: a unique field applies its
     * {@code nullsDistinct} setting, an inline record derives its nullability from its constituent fields, and a
     * plain field is as nullable as the field itself, because no unique constraint restricts its NULL values.
     *
     * <p>This backs {@link Metamodel#key(Metamodel)} delegates, which wrap metamodels that do not carry the key
     * marker themselves.</p>
     */
    public static boolean isNullable(Metamodel<?, ?> metamodel) {
        if (metamodel instanceof Metamodel.Key<?, ?> key) {
            return key.isNullable();
        }
        String path = metamodel.fieldPath();
        if (path.isEmpty()) {
            // The root metamodel designates the row itself rather than a column.
            return false;
        }
        try {
            RecordField field = getRecordField(fieldResolutionClass(metamodel.root()), path);
            boolean nullsDistinct = getNullsDistinct(field);
            boolean inline = !Ref.class.isAssignableFrom(field.type())
                    && getORMConverter(field).isEmpty()
                    && isRecord(field.type())
                    && !Data.class.isAssignableFrom(field.type());
            if (inline) {
                // Mirrors SimpleKeyMetamodel: an inline record is nullable when its unique constraint treats NULLs
                // as distinct and any of its constituent fields is nullable.
                boolean fieldIsUnique = field.isAnnotationPresent(UK.class) || field.isAnnotationPresent(PK.class);
                if (!fieldIsUnique || !nullsDistinct) {
                    return false;
                }
                for (var leaf : metamodel.flatten()) {
                    if (leaf instanceof Metamodel.Key<?, ?> leafKey && leafKey.isNullable()) {
                        return true;
                    }
                }
                return false;
            }
            return field.nullable() && nullsDistinct;
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to resolve nullability for metamodel field at path '%s' on type %s.".formatted(path, metamodel.root().getName()), e);
        }
    }

    /**
     * Returns the class that field resolution should inspect for the given root table. For sealed entity interfaces,
     * resolution is delegated to the first permitted subclass: the sealed interface itself declares accessor methods
     * but is not a record, so {@code getRecordField()} cannot inspect it directly. This mirrors the delegation
     * pattern used by {@code findPkField()}.
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Data> fieldResolutionClass(Class<? extends Data> rootTable) {
        if (rootTable.isSealed() && isSealedEntity(rootTable)) {
            Class<?>[] permitted = rootTable.getPermittedSubclasses();
            if (permitted != null && permitted.length > 0) {
                return (Class<? extends Data>) permitted[0];
            }
        }
        return rootTable;
    }

    /**
     * Returns the path of the foreign key field when {@code path} names the primary key of a table reached through
     * a foreign key, or {@code null} otherwise. Mirrors the analysis in {@link #getModel(Class, String)}: inline
     * parents fold into the field, and the node above must be a foreign key declared as a reference or as an entity
     * whose primary key the folded field names.
     */
    @Nullable
    private static String primaryKeyThroughForeignKeyPath(Class<? extends Data> fieldResolutionClass,
                                                          String path) {
        if (path.isEmpty()) return null;
        try {
            StringBuilder effectiveField = new StringBuilder(getRecordField(fieldResolutionClass, path).name());
            String effectivePath = stripLast(path);
            while (!effectivePath.isEmpty()) {
                RecordField parent = getRecordField(fieldResolutionClass, effectivePath);
                if (Data.class.isAssignableFrom(parent.type()) || Ref.class.isAssignableFrom(parent.type())) {
                    break;
                }
                effectiveField.insert(0, parent.name() + ".");
                effectivePath = stripLast(effectivePath);
            }
            if (effectivePath.isEmpty()) return null;
            RecordField referenceField = getRecordField(fieldResolutionClass, effectivePath);
            Class<?> referencedType = Ref.class.isAssignableFrom(referenceField.type())
                    ? getRefDataType(referenceField)
                    : Data.class.isAssignableFrom(referenceField.type()) ? referenceField.type() : null;
            return referencedType != null && isPrimaryKeyName(referencedType, effectiveField.toString())
                    ? effectivePath
                    : null;
        } catch (SqlTemplateException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the metamodel naming the key of the table the given path refers to, or {@code null} when the path is
     * not a to-one foreign key.
     *
     * <p>This is the inverse of the collapse {@link #canonical(Metamodel)} performs, and it is stated on the
     * relationship rather than on how the path was written: {@code Pet_.owner} and {@code Pet_.owner.id} name the
     * same thing, the first directly and the second through a key that collapses onto it, so both answer with the
     * referenced table's key. A reference answers too, since the table it refers to is joined once a query element
     * reaches beyond it.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static @Nullable Metamodel<?, ?> referencedKey(Metamodel<?, ?> metamodel) {
        try {
            String path = metamodel.fieldPath();
            if (path.isEmpty()) {
                return null;
            }
            Class<? extends Data> rootTable = metamodel.root();
            RecordField field = getRecordField(fieldResolutionClass(rootTable), path);
            Class<?> referencedType = Ref.class.isAssignableFrom(field.type())
                    ? getRefDataType(field)
                    : Data.class.isAssignableFrom(field.type()) ? field.type() : null;
            if (referencedType == null) {
                return null;
            }
            RecordField pk = findPkField(referencedType).orElse(null);
            if (pk == null) {
                return null;
            }
            String keyPath = path + "." + pk.name();
            Metamodel<?, ?> key = of((Class) rootTable, keyPath);
            if (key.fieldPath().equals(keyPath)) {
                return key;
            }
            // A reference rewrites the key beyond it onto the reference itself as it is built, which is the
            // equivalence this method exists to undo, so the path from the query root cannot express it. Rooting the
            // metamodel at the referenced table names the same column: the table is joined once a query element
            // reaches beyond the reference, and the key is resolved against that occurrence.
            return of((Class) referencedType, pk.name());
        } catch (SqlTemplateException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the path of the table occurrence the given path identifies, or {@code null} when it names a value
     * rather than an identity.
     *
     * <p>A grouping states what one row stands for, and three spellings state the same thing: the to-one
     * relationship ({@code Pet_.owner}), the key beyond it ({@code Pet_.owner.id}), and the query root's own key
     * ({@code Pet_.id}), which identifies the occurrence at the empty path. Anything else names a value, and a
     * grouping on it means one row per distinct value.</p>
     */
    public static @Nullable String identityPath(Metamodel<?, ?> metamodel) {
        try {
            if (referencedKey(metamodel) != null) {
                return metamodel.fieldPath();
            }
            var canonical = canonical(metamodel);
            if (!canonical.equals(metamodel)) {
                return canonical.fieldPath();
            }
            String path = metamodel.fieldPath();
            if (path.isEmpty()) {
                return null;
            }
            String parent = stripLast(path);
            // A key beyond a foreign key would have collapsed above, so the only identity left is the root's own.
            return parent.isEmpty() && isPrimaryKeyName(metamodel.root(), path) ? "" : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns whether {@code fieldName} names the primary key of {@code table}.
     */
    private static boolean isPrimaryKeyName(Class<?> table, String fieldName) {
        try {
            return findPkField(table).map(pk -> pk.name().equals(fieldName)).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Creates a new metamodel for the given root table and path.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Data, E extends @Nullable Object> Metamodel<T, E> getModel(Class<T> rootTable, String path) {
        Metamodel<T, ?> generated = lookupGeneratedMetamodel(rootTable, path);
        if (generated != null) {
            return (Metamodel<T, E>) generated;
        }
        if (path.isEmpty()) {
            return (Metamodel<T, E>) root(rootTable);
        }
        Class<? extends Data> fieldResolutionClass = fieldResolutionClass(rootTable);
        Class<E> fieldType;
        String effectivePath;
        StringBuilder effectiveField;
        boolean inline = false;
        boolean isColumn = false;
        boolean primaryKeyThroughReference = false;
        Class<?> declaringType;
        boolean fieldNullable;
        boolean fieldIsUnique;
        boolean nullsDistinct;
        try {
            RecordField field = getRecordField(fieldResolutionClass, path);
            declaringType = field.declaringType();
            fieldNullable = field.nullable();
            fieldIsUnique = field.isAnnotationPresent(UK.class) || field.isAnnotationPresent(PK.class);
            nullsDistinct = getNullsDistinct(field);
            effectiveField = new StringBuilder(field.name());
            if (Ref.class.isAssignableFrom(field.type())) {
                fieldType = (Class<E>) getRefDataType(field);
                isColumn = true;
                effectivePath = stripLast(path);
            } else {
                fieldType = (Class<E>) field.type();
                effectivePath = stripLast(path);
                if (getORMConverter(field).isPresent()) {
                    // A converted field is stored as its own column (or columns) rather than as the parts of the
                    // record it holds, exactly as the model builds it. Without this the record would be mistaken for
                    // an inline record and the path would resolve to columns that the table does not have.
                    isColumn = true;
                } else if (isRecord(fieldType)) {
                    if (Data.class.isAssignableFrom(fieldType)) {
                        isColumn = true;
                    } else {
                        inline = true;
                    }
                } else {
                    isColumn = true;
                }
            }
            // Walk up until we hit the table boundary; everything below becomes part of field(), everything above
            // (including the FK field) becomes path(). A Ref foreign key is a table boundary too: the referenced table
            // is joined and queried beyond the reference, so the reference is where field() begins, even when the
            // referenced table is itself reached beyond another reference (chained or self-referential references).
            while (!effectivePath.isEmpty()) {
                RecordField parent = getRecordField(fieldResolutionClass, effectivePath);
                if (Data.class.isAssignableFrom(parent.type()) || Ref.class.isAssignableFrom(parent.type())) {
                    break;
                }
                effectiveField.insert(0, parent.name() + ".");
                effectivePath = stripLast(effectivePath);
            }
            // A reference carries the target's primary key: Ref.id() reads it without fetching the target, because the
            // key is the foreign key column on the row itself. The metamodel mirrors that, so the target's primary key
            // reached through a reference resolves to that column, while any other column of the target resolves in
            // the referenced table. This is also the column an entity foreign key resolves its primary key to, so a
            // path means the same thing whether the relationship is declared as an entity or as a reference; for
            // entity foreign keys the equivalence is applied by {@link #canonical(Metamodel)} at column resolution.
            // Matching on the key therefore does not require the referenced row to exist; join the referenced table
            // explicitly to require that.
            //
            // Only the column the query resolves to is rewritten. Value extraction keeps following the requested path,
            // which still crosses the reference, so reading a value beyond a reference stays rejected for every path
            // alike instead of handing back the reference itself.
            if (!effectivePath.isEmpty()) {
                RecordField referenceField = getRecordField(fieldResolutionClass, effectivePath);
                if (Ref.class.isAssignableFrom(referenceField.type())
                        && isPrimaryKeyName(getRefDataType(referenceField), effectiveField.toString())) {
                    effectiveField = new StringBuilder(referenceField.name());
                    effectivePath = stripLast(effectivePath);
                    primaryKeyThroughReference = true;
                }
            }
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to resolve metamodel field at path '%s' on type %s.".formatted(path, rootTable.getName()), e);
        }
        Metamodel<T, ? extends Data> rootModel = root(rootTable);
        String tablePath = getTablePath(fieldResolutionClass, effectivePath);
        String tableField = "";
        if (!tablePath.isEmpty()
                && effectivePath.length() > tablePath.length()
                && effectivePath.startsWith(tablePath + ".")) {
            tableField = effectivePath.substring(tablePath.length() + 1);
        }
        Metamodel<T, ? extends Data> tableModel;
        if (effectivePath.isEmpty()) {
            tableModel = rootModel;
        } else {
            String tableModelPath = tablePath.isEmpty() ? "" : tablePath;
            String tableModelField = tablePath.isEmpty() ? effectivePath : tableField;
            Class<? extends Data> tableType = resolveDataTypeAtPath(fieldResolutionClass, effectivePath);
            MethodHandle tableHandle = buildGetterHandle(rootTable, effectivePath);
            tableModel = new SimpleMetamodel<>(
                    rootTable,
                    tableModelPath,
                    (Class) tableType,
                    tableModelField,
                    inline,
                    false,
                    rootModel,
                    tableHandle
            );
        }
        String fullPath = effectivePath.isEmpty()
                ? effectiveField.toString()
                : effectivePath + "." + effectiveField;
        // The requested path is used for value extraction so that a path crossing a reference keeps its query-only
        // behavior, even when the column it resolves to was rewritten to the foreign key.
        MethodHandle handle = buildGetterHandle(rootTable, primaryKeyThroughReference ? path : fullPath);
        // Determine if this field should be a Key metamodel with isNullable() support.
        boolean useKey = false;
        boolean keyNullable = false;
        if (inline && !Data.class.isAssignableFrom(fieldType)) {
            // Inline non-Data record (compound key or non-key inline).
            useKey = true;
            keyNullable = fieldIsUnique && nullsDistinct;
        } else if (!Data.class.isAssignableFrom(declaringType) && isRecord(declaringType)
                && !Data.class.isAssignableFrom(fieldType)) {
            // Scalar field inside a non-Data record (leaf of compound key).
            useKey = true;
            keyNullable = fieldNullable;
        } else if (fieldIsUnique) {
            // Scalar @UK/@PK field on a Data record.
            useKey = true;
            keyNullable = fieldNullable && nullsDistinct;
        }
        if (useKey) {
            return new SimpleKeyMetamodel<>(
                    rootTable, effectivePath, fieldType, effectiveField.toString(),
                    inline, isColumn, tableModel, handle, keyNullable);
        }
        return new SimpleMetamodel<>(
                rootTable,
                effectivePath,
                fieldType,
                effectiveField.toString(),
                inline,
                isColumn,
                tableModel,
                handle
        );
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T extends Data> Metamodel<T, ?> lookupGeneratedMetamodel(
            Class<T> rootTable,
            String path
    ) {
        try {
            Metamodel<T, ?> current = (Metamodel<T, ?>) Class.forName(
                            rootTable.getName() + "Metamodel", true, rootTable.getClassLoader())
                    .getMethod("instance")
                    .invoke(null);
            for (String segment : path.split("\\.")) {
                current = (Metamodel<T, ?>) readSegment(current, segment);
            }
            return current;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readSegment(Object instance, String name) throws Exception {
        Class<?> c = instance.getClass();
        // Public field (Java style, or Kotlin @JvmField).
        try {
            return c.getField(name).get(instance);
        } catch (NoSuchFieldException ignored) { }
        String cap = capitalize(name);
        // Kotlin/JavaBean getter: getX()
        try {
            Method m = c.getMethod("get" + cap);
            return m.invoke(instance);
        } catch (NoSuchMethodException ignored) { }
        // Boolean getter: isX()
        try {
            Method m = c.getMethod("is" + cap);
            return m.invoke(instance);
        } catch (NoSuchMethodException ignored) { }
        // Record-like accessor: x()
        try {
            Method m = c.getMethod(name);
            return m.invoke(instance);
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchFieldException("No metamodel member '%s' on %s.".formatted(name, c.getName()));
    }

    private static String stripLast(String p) {
        int idx = p.lastIndexOf('.');
        return idx == -1 ? "" : p.substring(0, idx);
    }

    private static String getTablePath(Class<? extends Data> rootTable,
                                       String path) {
        if (path.isEmpty()) {
            return "";
        }
        try {
            String tablePath = "";
            String candidate = "";
            String[] segments = path.split("\\.");
            for (int i = 0; i < segments.length - 1; i++) {
                candidate = candidate.isEmpty()
                        ? segments[i]
                        : candidate + "." + segments[i];
                RecordField field = getRecordField(rootTable, candidate);
                // A Ref<X> foreign key is a table boundary too: the referenced table can be joined and queried
                // beyond the reference, even though the reference itself is selected as its foreign key column.
                if (Data.class.isAssignableFrom(field.type()) || Ref.class.isAssignableFrom(field.type())) {
                    tablePath = candidate;
                }
            }
            return tablePath;
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to resolve table path for type %s at path '%s'.".formatted(rootTable.getName(), path), e);
        }
    }

    /**
     * Resolves the Data type at the end of {@code fullPath} (unwraps Ref<T>).
     */
    @SuppressWarnings("unchecked")
    private static Class<? extends Data> resolveDataTypeAtPath(Class<? extends Data> rootTable,
                                                               String fullPath) {
        try {
            RecordField f = getRecordField(rootTable, fullPath);
            if (Ref.class.isAssignableFrom(f.type())) {
                return getRefDataType(f);
            }
            return (Class<? extends Data>) f.type();
        } catch (SqlTemplateException e) {
            throw new PersistenceException("Failed to resolve Data type at path '%s' on type %s.".formatted(fullPath, rootTable.getName()), e);
        }
    }

    static String capitalize(String property) {
        if (property == null || property.isEmpty()) {
            return property;
        }
        char first = property.charAt(0);
        char upper = Character.toUpperCase(first);
        if (upper == first) {
            return property;
        }
        return upper + property.substring(1);
    }

    private static Method findAccessor(Class<?> type, String property) throws NoSuchMethodException {
        String name = capitalize(property);
        try {
            // Kotlin style: getId().
            return type.getMethod("get" + name);
        } catch (NoSuchMethodException ignored) { }
        try {
            // Boolean style: isActive().
            return type.getMethod("is" + name);
        } catch (NoSuchMethodException ignored) { }
        // Java record style: id().
        return type.getMethod(property);
    }

    /**
     * Builds a null-safe getter handle for a dotted path.
     *
     * <p>
     * Semantics:
     * - If any intermediate accessor returns null, the handle returns null.
     * - This matches metamodel getValue() semantics where parents in the hierarchy may be nullable.
     * </p>
     */
    private static MethodHandle buildGetterHandle(Class<?> rootType, @Nullable String fullPath) {
        try {
            if (fullPath == null || fullPath.isEmpty()) {
                return MethodHandles.identity(rootType);
            }
            MethodHandles.Lookup base = MethodHandles.lookup();
            Class<?> currentType = rootType;
            MethodHandle handle = null;
            for (String part : fullPath.split("\\.")) {
                if (Ref.class.isAssignableFrom(currentType)) {
                    // The path navigates beyond a Ref boundary. The referenced entity is not loaded in memory, so its
                    // columns cannot be read from an in-memory record; such metamodels are query-only (filter, join,
                    // order, select). Defer the failure to invocation so constructing the metamodel stays valid.
                    return refBoundaryHandle(rootType, fullPath);
                }
                Method m = findAccessor(currentType, part);
                MethodHandle getter = unreflect(base, currentType, m);
                if (handle == null) {
                    handle = getter;
                } else {
                    // Compose null-safe: if previous result is null -> return null, else call getter on it.
                    handle = nullSafeFilterReturnValue(handle, getter);
                }
                currentType = m.getReturnType();
            }
            return handle;
        } catch (Throwable e) {
            throw new PersistenceException(
                    new SqlTemplateException("Failed to create accessor handle for path: " + fullPath, e)
            );
        }
    }

    /**
     * Builds a getter handle that throws {@link UnsupportedOperationException} when invoked, for paths that navigate
     * beyond a Ref boundary. The referenced entity is not loaded in memory, so its columns cannot be read from a
     * record; the metamodel remains usable for query construction (filter, join, order, select).
     */
    private static MethodHandle refBoundaryHandle(Class<?> rootType, String fullPath) {
        MethodHandle thrower = MethodHandles.throwException(Object.class, UnsupportedOperationException.class);
        MethodHandle withException = MethodHandles.insertArguments(thrower, 0,
                new UnsupportedOperationException(
                        "Cannot extract a value across a Ref boundary for path '%s'. Reference-crossing metamodels are query-only (filter, join, order, select)."
                                .formatted(fullPath)));
        return MethodHandles.dropArguments(withException, 0, rootType);
    }

    /**
     * Equivalent to filterReturnValue(prev, next), but returns null when prev returns null.
     *
     * <p>
     * Types:
     * - prev: (A) -> R
     * - next: (R) -> S
     * Result:
     * - (A) -> S (or null when R is null). For primitive S this cannot be null, so we only apply
     *   the null-guard when S is a reference type. For primitive S, the best we can do is call next.
     * </p>
     */
    private static MethodHandle nullSafeFilterReturnValue(MethodHandle prev, MethodHandle next) throws Throwable {
        Class<?> rType = prev.type().returnType();
        Class<?> sType = next.type().returnType();
        // If prev can never return null (primitive), normal composition is fine.
        if (rType.isPrimitive()) {
            return MethodHandles.filterReturnValue(prev, next);
        }
        // If next returns a primitive, we cannot return null from the composed handle.
        if (sType.isPrimitive()) {
            return MethodHandles.filterReturnValue(prev, next);
        }
        MethodHandles.Lookup lk = MethodHandles.lookup();
        MethodHandle test = lk.findStatic(
                Objects.class,
                "isNull",
                methodType(boolean.class, Object.class)
        ).asType(methodType(boolean.class, rType));
        MethodHandle target = MethodHandles.dropArguments(
                MethodHandles.constant(sType, null),
                0,
                rType
        );
        MethodHandle guarded = MethodHandles.guardWithTest(test, target, next); // Fallback.
        return MethodHandles.filterReturnValue(prev, guarded);
    }

    private static MethodHandle unreflect(MethodHandles.Lookup base, Class<?> owner, Method m) throws Throwable {
        try {
            return MethodHandles.publicLookup().unreflect(m);
        } catch (IllegalAccessException ignored) { }
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, base);
            return lookup.unreflect(m);
        } catch (IllegalAccessException ignored) { }
        // Last resort: reflection.
        m.setAccessible(true);
        return base.unreflect(m);
    }

    private static boolean getNullsDistinct(RecordField field) {
        UK uk = field.getAnnotation(UK.class);
        if (uk != null) return uk.nullsDistinct();
        if (field.isAnnotationPresent(PK.class)) {
            UK metamodelUk = PK.class.getAnnotation(UK.class);
            return metamodelUk == null || metamodelUk.nullsDistinct();
        }
        return true;
    }

    private static final class SimpleKeyMetamodel<T extends Data, E extends @Nullable Object>
            extends AbstractKeyMetamodel<T, E, Object> {

        private final Class<T> root;
        private final Metamodel<T, ? extends Data> table;
        private final MethodHandle handle;
        private final IdentityComparator<T> identical;
        private final ValueComparator<T> same;

        SimpleKeyMetamodel(Class<T> root,
                           String path,
                           Class<E> fieldType,
                           String field,
                           boolean inline,
                           boolean isColumn,
                           Metamodel<T, ? extends Data> table,
                           MethodHandle handle,
                           boolean nullable) {
            super(fieldType, path, field, inline, null, isColumn, nullable);
            this.root = root;
            this.table = table;
            this.handle = handle;
            this.identical = EqualitySupport.compileIsIdentical(handle);
            ValueComparator<T> wrapped = null;
            if (Data.class.isAssignableFrom(fieldType)) {
                var pkField = findPkField(fieldType).orElse(null);
                if (pkField != null) {
                    var pkHandle = buildGetterHandle(fieldType, pkField.name());
                    var s = EqualitySupport.compileIsSame(pkHandle);
                    wrapped = (a, b) -> {
                        Object dataA = handle.invoke(a);
                        Object dataB = handle.invoke(b);
                        if (dataA == null || dataB == null) {
                            return dataA == dataB;
                        }
                        return s.isSame(dataA, dataB);
                    };
                }
            }
            this.same = wrapped == null ? EqualitySupport.compileIsSame(handle) : wrapped;
        }

        @Override
        public Class<T> root() {
            return root;
        }

        @Override
        public Metamodel<T, ? extends Data> table() {
            return table;
        }

        @Override
        public Object getValue(T record) {
            try {
                return handle.invoke(record);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("Failed to get value for metamodel field '%s' on type %s.".formatted(field(), root.getName()), e);
            }
        }

        @Override
        public boolean isIdentical(T a, T b) {
            try {
                return identical.isIdentical(a, b);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("Failed to evaluate isIdentical for metamodel field '%s' on type %s.".formatted(field(), root.getName()), e);
            }
        }

        @Override
        public boolean isSame(T a, T b) {
            try {
                return same.isSame(a, b);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("Failed to evaluate isSame for metamodel field '%s' on type %s.".formatted(field(), root.getName()), e);
            }
        }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean isNullable() {
            if (!isInline()) {
                return super.isNullable();
            }
            // Compound key (inline record): derive from flattened leaves.
            if (!super.isNullable()) {
                return false;
            }
            for (var leaf : flatten()) {
                if (leaf instanceof Metamodel.Key key && key.isNullable()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class SimpleMetamodel<T extends Data, E extends @Nullable Object>
            extends AbstractMetamodel<T, E, Object> {

        private final Class<T> root;
        private final Metamodel<T, ? extends Data> table;
        private final MethodHandle handle;
        private final IdentityComparator<T> identical;
        private final ValueComparator<T> same;

        SimpleMetamodel(Class<T> root,
                        String path,
                        Class<E> fieldType,
                        String field,
                        boolean inline,
                        boolean isColumn,
                        Metamodel<T, ? extends Data> table,
                        MethodHandle handle) {
            super(fieldType, path, field, inline, null, isColumn);
            this.root = root;
            this.table = table;
            this.handle = handle;
            this.identical = EqualitySupport.compileIsIdentical(handle);
            ValueComparator<T> wrapped = null;
            if (Data.class.isAssignableFrom(fieldType)) {
                var pkField = findPkField(fieldType).orElse(null);
                if (pkField != null) {
                    var pkHandle = buildGetterHandle(fieldType, pkField.name());
                    var s = EqualitySupport.compileIsSame(pkHandle);
                    wrapped = (a, b) -> {
                        Object dataA = handle.invoke(a);
                        Object dataB = handle.invoke(b);
                        if (dataA == null || dataB == null) return dataA == dataB;
                        return s.isSame(dataA, dataB);
                    };
                }
            }
            this.same = wrapped == null ? EqualitySupport.compileIsSame(handle) : wrapped;
        }

        @Override
        public Class<T> root() {
            return root;
        }

        @Override
        public Metamodel<T, ? extends Data> table() {
            return table;
        }

        @Override
        public Object getValue(T record) {
            try {
                return handle.invoke(record);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("Failed to get value for metamodel field '%s' on type %s.".formatted(field(), root.getName()), e);
            }
        }

        @Override
        public boolean isIdentical(T a, T b) {
            try {
                return identical.isIdentical(a, b);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("Failed to evaluate isIdentical for metamodel field '%s' on type %s.".formatted(field(), root.getName()), e);
            }
        }

        @Override
        public boolean isSame(T a, T b) {
            try {
                return same.isSame(a, b);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("Failed to evaluate isSame for metamodel field '%s' on type %s.".formatted(field(), root.getName()), e);
            }
        }
    }
}
