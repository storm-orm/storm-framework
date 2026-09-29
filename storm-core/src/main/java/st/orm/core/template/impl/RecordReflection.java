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

import static java.util.Optional.empty;
import static java.util.function.Predicate.not;
import static st.orm.core.spi.Providers.getORMConverter;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.DbColumn;
import st.orm.DbColumns;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Discriminator.DiscriminatorType;
import st.orm.Entity;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Polymorphic;
import st.orm.ProjectionQuery;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.Version;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;

/**
 * Helper class for record reflection.
 */
@SuppressWarnings("ALL")
final class RecordReflection {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private RecordReflection() {
    }

    /**
     * Checks whether the specified type is a record type.
     *
     * @param type the type to check.
     * @return {@code true} if the specified type is a record type, {@code false} otherwise.
     */
    public static boolean isRecord(Class<?> type) {
        return REFLECTION.findRecordType(type).isPresent();
    }

    /**
     * Returns the record type for the specified type.
     *
     * @param type the type to obtain the record type for.
     * @return the record type for the specified type.
     * @throws PersistenceException if the specified type is not a record type.
     */
    public static RecordType getRecordType(Class<?> type) {
        return REFLECTION.getRecordType(type);
    }

    /**
     * Returns the record components for the specified record type. The result is cached to avoid repeated expensive
     * reflection lookups.
     *
     * @param recordType the record type to obtain the record components for.
     * @return the record components for the specified record type.
     * @throws PersistenceException if the record type is not a record.
     */
    public static List<RecordField> getRecordFields(Class<?> recordType) {
        return REFLECTION.getRecordType(recordType).fields();
    }

    /**
     * Looks up the record field in the given table, taking the {@code field} path into account.
     */
    public static RecordField getRecordField(Class<?> table,
                                             String path) throws SqlTemplateException {
        if (path.isEmpty()) {
            throw new SqlTemplateException("Empty component path specified.");
        }
        // Split on '.' to handle nested components (e.g., "x.y.z").
        String[] parts = path.split("\\.");
        RecordType type = REFLECTION.getRecordType(table);
        RecordField foundField = null;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            // Get record components for the current record class.
            foundField = null;
            for (RecordField field : type.fields()) {
                if (field.name().equals(part)) {
                    foundField = field;
                    break;
                }
            }
            if (foundField == null) {
                throw new SqlTemplateException("No field named '%s' found in record %s.".formatted(part, type.type().getName()));
            }
            // If there's still a next part to search, update type if possible.
            boolean hasNextPart = (i < parts.length - 1);
            if (hasNextPart) {
                // Unwrap Ref<X> foreign keys so a path can traverse beyond a reference boundary. The referenced
                // type resolves deeper components for querying (filter, join, order, select); the reference itself
                // is still selected as its foreign key column, so the entity graph stays optimized.
                Class<?> nextType = Ref.class.isAssignableFrom(foundField.type())
                        ? getFkTargetType(foundField)
                        : foundField.type();
                // The type of the found field must be another record type to continue drilling down.
                var fieldType = REFLECTION.findRecordType(nextType).orElse(null);
                if (fieldType != null) {
                    type = fieldType;
                } else {
                    throw new SqlTemplateException("Component '%s' in record %s is not a record, but further components were specified: '%s'.".formatted(part, type.type().getName(), path));
                }
            }
        }
        return foundField;
    }

    /**
     * Returns the primary key field for the specified table. If the table has a compound primary key, represented
     * by a record, the primary key field is that record itself.
     *
     * <p><strong>Note:</strong> PKs must always be present at the top-level of the record. They would not be recognized
     * if they're part of inlined records.</p>
     *
     * @param table the table to obtain the primary key field for.
     * @return the primary key field for the specified table.
     */
    public static Optional<RecordField> findPkField(Class<?> table) {
        // For sealed entity interfaces, delegate to the first permitted subclass.
        if (table.isSealed() && isSealedEntity(table)) {
            Class<?>[] permitted = table.getPermittedSubclasses();
            if (permitted != null && permitted.length > 0) {
                return findPkField(permitted[0]);
            }
            return Optional.empty();
        }
        // Polymorphic FK interfaces (sealed Data, not Entity) have no PK of their own.
        if (table.isSealed() && isPolymorphicData(table)) {
            return Optional.empty();
        }
        return REFLECTION.getRecordType(table).fields().stream()
                .filter(field -> field.isAnnotationPresent(PK.class))
                .findFirst();
    }

    /**
     * Returns the primary key components for the specified table. If the primary key is a foreign key (a record with a
     * primary key), that record component is returned. If the primary key is a compound primary key (an inline record)
     * the primary key components are the record components of that inline record.
     *
     * <p><strong>Note:</strong> PKs must always be present at the top-level of the record. They would not be recognized
     * if they're part of inlined records.</p>
     *
     * @param table the table to obtain the primary key components for.
     */
    /**
     * A terminal field of a primary key, together with the accessor path that navigates to it from a value of
     * the key's declaring table.
     *
     * <p>The path follows the key chain: a primary key that is a foreign key to another entity steps through
     * that entity's primary key, and so on; plain record keys contribute their components. The path therefore
     * ends at a field that is bound to a single database column.</p>
     *
     * @param path the accessor path from the declaring table's key to the terminal field.
     */
    record KeyLeaf(List<RecordField> path) {
        RecordField field() {
            return path.getLast();
        }
    }

    /**
     * Flattens the primary key of the given table into its terminal fields, in declaration order — one leaf
     * per database column of the key.
     *
     * @param table the table whose primary key to flatten.
     * @return the terminal fields of the key with their accessor paths.
     * @throws SqlTemplateException if the key chain is circular or a referenced entity lacks a primary key.
     */
    static List<KeyLeaf> getPkLeaves(Class<?> table) throws SqlTemplateException {
        var leaves = new ArrayList<KeyLeaf>();
        flattenPk(table, new ArrayList<>(), new HashSet<>(), leaves);
        return leaves;
    }

    /**
     * Returns the flattened key leaves for the target of the given foreign key field, or {@code null} when the
     * target's key cannot be flattened — polymorphic foreign keys bind a discriminator and an identifier
     * instead of the target's key columns.
     */
    @Nullable
    static List<KeyLeaf> getFkLeaves(RecordField field) throws SqlTemplateException {
        Class<?> target = getFkTargetType(field);
        if (isPolymorphicData(target)) {
            return null;
        }
        return getPkLeaves(target);
    }

    /**
     * Resolves the entity type a foreign key field refers to, unwrapping {@link Ref} fields and selecting the
     * first permitted subclass for sealed entity hierarchies.
     */
    static Class<?> getFkTargetType(RecordField field) throws SqlTemplateException {
        Class<?> fkType = Ref.class.isAssignableFrom(field.type())
                ? getRefDataType(field)
                : field.type();
        if (fkType.isSealed() && isSealedEntity(fkType)) {
            Class<?>[] permitted = fkType.getPermittedSubclasses();
            if (permitted != null && permitted.length > 0) {
                fkType = permitted[0];
            }
        }
        return fkType;
    }

    /**
     * Resolves the class the given type supplies for the single type parameter of the given generic interface,
     * walking the interface and superclass hierarchy and substituting type variables along the way.
     *
     * @param type the type whose declaration to inspect.
     * @param genericInterface the generic interface whose type parameter to resolve.
     * @return the supplied class, or empty when the interface is implemented raw or the argument does not resolve
     *         to a class.
     */
    static Optional<Class<?>> findTypeArgument(Class<?> type, Class<?> genericInterface) {
        Type argument = resolveTypeArgument(type, genericInterface, 0, Map.of());
        return argument instanceof Class<?> cls ? Optional.of(cls) : empty();
    }

    /**
     * Resolves the type argument the given type supplies at the given index of the given generic interface,
     * walking the interface and superclass hierarchy and substituting type variables along the way.
     *
     * @param type the type whose declaration to inspect.
     * @param genericInterface the generic interface whose type parameter to resolve.
     * @param index the index of the interface's type parameter to resolve.
     * @return the supplied type argument, or empty when the type does not implement the interface, implements it
     *         raw, the index is out of bounds, or the argument is an unbound type variable.
     */
    static Optional<Type> findTypeArgument(Class<?> type, Class<?> genericInterface, int index) {
        return Optional.ofNullable(resolveTypeArgument(type, genericInterface, index, Map.of()));
    }

    @Nullable
    private static Type resolveTypeArgument(Class<?> type,
                                            Class<?> genericInterface,
                                            int index,
                                            Map<TypeVariable<?>, Type> bindings) {
        for (Type supertype : directSupertypes(type)) {
            Class<?> raw = rawType(supertype);
            if (raw == null || !genericInterface.isAssignableFrom(raw)) {
                continue;
            }
            if (raw == genericInterface) {
                if (supertype instanceof ParameterizedType parameterized) {
                    Type[] arguments = parameterized.getActualTypeArguments();
                    if (index < 0 || index >= arguments.length) {
                        return null;
                    }
                    return substitute(arguments[index], bindings);
                }
                return null;    // Raw declaration.
            }
            Type resolved = resolveTypeArgument(raw, genericInterface, index, bindingsFor(supertype, raw, bindings));
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static List<Type> directSupertypes(Class<?> type) {
        List<Type> supertypes = new ArrayList<>(Arrays.asList(type.getGenericInterfaces()));
        Type superclass = type.getGenericSuperclass();
        if (superclass != null) {
            supertypes.add(superclass);
        }
        return supertypes;
    }

    @Nullable
    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return null;
    }

    /**
     * Maps the type parameters of {@code raw} to the arguments {@code supertype} supplies for them, resolved
     * against the caller's bindings.
     */
    private static Map<TypeVariable<?>, Type> bindingsFor(Type supertype,
                                                          Class<?> raw,
                                                          Map<TypeVariable<?>, Type> bindings) {
        if (!(supertype instanceof ParameterizedType parameterized)) {
            return Map.of();
        }
        TypeVariable<?>[] parameters = raw.getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        Map<TypeVariable<?>, Type> result = new HashMap<>();
        for (int i = 0; i < parameters.length; i++) {
            Type argument = substitute(arguments[i], bindings);
            if (argument != null) {
                result.put(parameters[i], argument);
            }
        }
        return result;
    }

    @Nullable
    private static Type substitute(Type argument, Map<TypeVariable<?>, Type> bindings) {
        return argument instanceof TypeVariable<?> variable ? bindings.get(variable) : argument;
    }

    private static void flattenPk(Class<?> table,
                                  List<RecordField> path,
                                  Set<Class<?>> visited,
                                  List<KeyLeaf> leaves) throws SqlTemplateException {
        if (!visited.add(table)) {
            throw new SqlTemplateException(
                    "Circular key chain detected at %s. A primary key must not reference itself through its foreign keys."
                            .formatted(table.getSimpleName()));
        }
        var pkField = findPkField(table).orElseThrow(() ->
                new SqlTemplateException("No primary key found for type: %s.".formatted(table.getSimpleName())));
        path.add(pkField);
        flattenKeyField(pkField, path, visited, leaves);
        path.removeLast();
        visited.remove(table);
    }

    private static void flattenKeyField(RecordField field,
                                        List<RecordField> path,
                                        Set<Class<?>> visited,
                                        List<KeyLeaf> leaves) throws SqlTemplateException {
        if (field.isAnnotationPresent(FK.class)) {
            flattenPk(getFkTargetType(field), path, visited, leaves);
            return;
        }
        var recordType = REFLECTION.findRecordType(field.type()).orElse(null);
        if (recordType == null) {
            leaves.add(new KeyLeaf(List.copyOf(path)));
            return;
        }
        for (var component : recordType.fields()) {
            path.add(component);
            flattenKeyField(component, path, visited, leaves);
            path.removeLast();
        }
    }

    @SuppressWarnings("unchecked")
    static Stream<RecordField> getFkFields(Class<?> table) {
        if (table.isSealed()) {
            return Stream.empty();  // Sealed interfaces have no own FK fields.
        }
        return REFLECTION.getRecordType(table).fields().stream()
                .flatMap(field -> {
                    if (field.isAnnotationPresent(FK.class)) {
                        return Stream.of(field);
                    }
                    if (REFLECTION.findRecordType(field.type()).isPresent() && getORMConverter(field).isEmpty()) {
                        return getFkFields(field.type());
                    }
                    return Stream.empty();
                });
    }

    /**
     * Returns the version field for the specified table. The version field is a record field that is
     * annotated with the {@link Version} annotation.
     *
     * @param table the table to obtain the version field for.
     * @return optional with the field that specified the Version annotation, or an empty if none found.
     */
    static Optional<RecordField> getVersionField(Class<?> table) {
        if (table.isSealed()) {
            return Optional.empty();  // Sealed interfaces have no own version fields.
        }
        for (var field : REFLECTION.getRecordType(table).fields()) {
            if (field.isAnnotationPresent(Version.class)) {
                return Optional.of(field);
            }
            if (REFLECTION.findRecordType(field.type()).isPresent()
                    && !field.isAnnotationPresent(FK.class)
                    && getORMConverter(field).isEmpty()) {
                var versionComponent = getVersionField(field.type());
                if (versionComponent.isPresent()) {
                    return versionComponent;
                }
            }
        }
        return Optional.empty();
    }

    static GenerationStrategy getGenerationStrategy(RecordField field) {
        PK pk = field.getAnnotation(PK.class);
        if (pk != null) {
            if (!REFLECTION.findRecordType(field.type()).isPresent() && !field.isAnnotationPresent(FK.class)) {
                return pk.generation();
            }
        }
        return GenerationStrategy.NONE;
    }

    static String getSequence(RecordField field) {
        PK pk = field.getAnnotation(PK.class);
        if (pk != null) {
            return pk.sequence();
        }
        return "";
    }

    static boolean isTypePresent(Class<?> source,
                                 Class<?> target) throws SqlTemplateException {
        if (target.equals(source)) {
            return true;
        }
        if (source.isSealed()) {
            return false;  // Sealed interfaces have no own fields; auto-join not applicable.
        }
        return findRecordField(getRecordFields(source), target).isPresent();
    }

    /**
     * Returns whether a SELECT of {@code source} contributes columns of {@code target} to its select list.
     *
     * <p>The select list is the hydrated graph: an entity foreign key is joined and its columns selected, and an
     * inline record contributes its component columns. A {@link Ref} foreign key contributes its own column only, so
     * the table it refers to is not selected and traversal stops there. That also bounds the walk, because a cycle of
     * foreign keys has to cross a reference to be loadable at all; the visited set guards a model that does not.</p>
     *
     * <p>This answers a different question than {@link #isTypePresent(Class, Class)}, which asks whether a table can
     * be reached at all and so follows references too.</p>
     */
    static boolean isTypeSelected(Class<?> source, Class<?> target) throws SqlTemplateException {
        return isTypeSelected(source, target, new HashSet<>());
    }

    /**
     * Returns every table occurrence a select of {@code source} contributes columns from, keyed by field path
     * relative to {@code source}, with the empty path naming {@code source} itself.
     *
     * <p>Traversal follows the same graph as {@link #isTypeSelected(Class, Class)}: entity foreign keys and inline
     * records, stopping at references. Every edge is to-one, so an occurrence is determined by any occurrence its
     * path extends — one owner is one city — which is what lets a grouping cover more than the table it names.</p>
     */
    static SequencedMap<String, Class<? extends Data>> selectedOccurrences(Class<?> source)
            throws SqlTemplateException {
        var occurrences = new LinkedHashMap<String, Class<? extends Data>>();
        collectSelectedOccurrences(source, "", occurrences);
        return occurrences;
    }

    @SuppressWarnings("unchecked")
    private static void collectSelectedOccurrences(Class<?> source, String path,
                                                   Map<String, Class<? extends Data>> occurrences)
            throws SqlTemplateException {
        if (Data.class.isAssignableFrom(source)) {
            occurrences.put(path, (Class<? extends Data>) source);
        }
        if (source.isSealed() || !isRecord(source)) {
            return;
        }
        for (var field : getRecordFields(source)) {
            if (Ref.class.isAssignableFrom(field.type()) || !isRecord(field.type())) {
                continue;
            }
            if (getORMConverter(field).isPresent()) {
                continue;
            }
            collectSelectedOccurrences(field.type(), path.isEmpty() ? field.name() : path + "." + field.name(),
                    occurrences);
        }
    }

    private static boolean isTypeSelected(Class<?> source, Class<?> target, Set<Class<?>> visited)
            throws SqlTemplateException {
        if (target.equals(source)) {
            return true;
        }
        if (source.isSealed() || !isRecord(source) || !visited.add(source)) {
            return false;
        }
        for (var field : getRecordFields(source)) {
            if (Ref.class.isAssignableFrom(field.type()) || !isRecord(field.type())) {
                continue;
            }
            if (getORMConverter(field).isPresent()) {
                continue;   // Converted to a single column, so it contributes no table of its own.
            }
            if (isTypeSelected(field.type(), target, visited)) {
                return true;
            }
        }
        return false;
    }

    static Optional<RecordField> findRecordField(List<RecordField> fields,
                                                 Class<?> table) throws SqlTemplateException {
        return findRecordFields(fields, table).stream().findFirst();
    }

    /**
     * Returns the fields whose declared type matches the specified type, either directly or as
     * the data type of a Ref.
     *
     * @param fields the candidate (foreign key) fields.
     * @param table the type the fields are matched against.
     * @return the matching fields; empty when none match.
     */
    static List<RecordField> findRecordFields(List<RecordField> fields,
                                              Class<?> table) throws SqlTemplateException {
        var matches = new ArrayList<RecordField>();
        for (var field : fields) {
            if (field.type() == table
                    || (Ref.class.isAssignableFrom(field.type()) && getRefDataType(field).equals(table))) {
                matches.add(field);
            }
        }
        return matches;
    }

    /**
     * Returns whether the specified type can serve as the target of a table-based join: a
     * table-backed Data type with a primary key to join on. Query-backed projections have no
     * table, and types without a primary key expose no key column to join.
     *
     * @param type the type to check.
     * @return {@code true} if the type is a table-based join candidate, {@code false} otherwise.
     */
    static boolean isTableJoinCandidate(Class<? extends Data> type) {
        if (findPkField(type).isEmpty()) {
            return false;
        }
        if (type.isSealed() && isSealedEntity(type)) {
            return true;    // Sealed entity interfaces are not records and carry no ProjectionQuery.
        }
        return !getRecordType(type).isAnnotationPresent(ProjectionQuery.class);
    }

    /**
     * Returns the foreign key fields whose referenced type maps to the same table as the
     * specified type. This is the fallback used for table-based joins: a table-backed type, such
     * as a projection of a table or an alternative entity mapping it, can be joined by any
     * foreign key that references that table, even though the Java types differ.
     *
     * @param fields the candidate (foreign key) fields.
     * @param table the type whose table name the referenced types are matched against.
     * @param tableNameResolver the resolver used to derive table names.
     * @return the matching fields; empty when none match.
     */
    static List<RecordField> findRecordFieldsByTable(List<RecordField> fields,
                                                     Class<? extends Data> table,
                                                     TableNameResolver tableNameResolver) throws SqlTemplateException {
        var tableName = getTableName(table, tableNameResolver);
        var matches = new ArrayList<RecordField>();
        for (var field : fields) {
            Class<?> targetType = Ref.class.isAssignableFrom(field.type()) ? getRefDataType(field) : field.type();
            if (!Data.class.isAssignableFrom(targetType)) {
                continue;
            }
            //noinspection unchecked
            if (tableName.equals(getTableName((Class<? extends Data>) targetType, tableNameResolver))) {
                matches.add(field);
            }
        }
        return matches;
    }

    /**
     * Ref primary key types per declaring record class, keyed by field name. {@link ClassValue} ties each entry to
     * the lifetime of the declaring class, so cached types never pin the class or its class loader.
     */
    private static final ClassValue<ConcurrentMap<String, Class<?>>> REF_PK_TYPE_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentMap<String, Class<?>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @SuppressWarnings("unchecked")
    static Class<?> getRefPkType(RecordField field) throws SqlTemplateException {
        try {
            return REF_PK_TYPE_CACHE.get(field.declaringType()).computeIfAbsent(field.name(), ignore -> {
                try {
                    var type = field.genericType();
                    if (type instanceof ParameterizedType parameterizedType) {
                        Type supplied = parameterizedType.getActualTypeArguments()[0];
                        if (supplied instanceof Class<?> c) {
                            // For sealed types, resolve PK from the first permitted subclass.
                            if (c.isSealed() && Data.class.isAssignableFrom(c)) {
                                Class<?>[] permitted = c.getPermittedSubclasses();
                                if (permitted != null && permitted.length > 0) {
                                    return RecordReflection.findPkField(permitted[0])
                                            .map(RecordField::type)
                                            .orElseThrow(() -> new SqlTemplateException(
                                                    "Primary key not found for permitted subclass: %s."
                                                            .formatted(permitted[0].getSimpleName())));
                                }
                            }
                            if (REFLECTION.findRecordType(c).isPresent()) {
                                return RecordReflection.findPkField(c)
                                        .map(RecordField::type)
                                        .orElseThrow(() -> new SqlTemplateException("Primary key not found for entity: %s.".formatted(c.getSimpleName())));
                            }
                        }
                    }
                    throw new SqlTemplateException("Ref component must specify a Data type: %s. The generic type parameter of Ref<T> must be a type that implements the Data interface (Entity, Projection, or Inline record).".formatted(field.type().getSimpleName()));
                } catch (SqlTemplateException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw (SqlTemplateException) e.getCause();
        }
    }

    /** Ref data types per declaring record class, keyed by field name; entries die with the declaring class. */
    private static final ClassValue<ConcurrentMap<String, Class<? extends Data>>> REF_RECORD_TYPE_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentMap<String, Class<? extends Data>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @SuppressWarnings("unchecked")
    static Class<? extends Data> getRefDataType(RecordField field) throws SqlTemplateException {
        try {
            return REF_RECORD_TYPE_CACHE.get(field.declaringType()).computeIfAbsent(field.name(), ignore -> {
                try {
                    Class<?> recordType = null;
                    var type = field.genericType();
                    if (type instanceof ParameterizedType parameterizedType) {
                        Type supplied = parameterizedType.getActualTypeArguments()[0];
                        if (supplied instanceof Class<?> c) {
                            recordType = (Class<?>) c;
                        }
                    }
                    if (recordType == null) {
                        throw new SqlTemplateException("Ref must specify a Data type: %s. The generic type parameter of Ref<T> must be a type that implements the Data interface (Entity, Projection, or Inline record).".formatted(field.type().getSimpleName()));
                    }
                    if (!Data.class.isAssignableFrom(recordType)) {
                        throw new SqlTemplateException("Ref must specify a Data type: %s. The generic type parameter of Ref<T> must be a type that implements the Data interface (Entity, Projection, or Inline record).".formatted(field.type().getSimpleName()));
                    }
                    // Accept sealed interfaces (they are not records themselves but their subtypes are).
                    var finalRecordType = recordType;
                    if (recordType.isSealed() && detectSealedPattern(recordType).isPresent()) {
                        // Sealed hierarchy type is valid even though the interface itself is not a record.
                        return (Class<? extends Data>) recordType;
                    }
                    REFLECTION.findRecordType(recordType)
                            .orElseThrow(() -> new SqlTemplateException("Ref must specify a record type: %s.".formatted(finalRecordType.getSimpleName())));
                    return (Class<? extends Data>) recordType;
                } catch (SqlTemplateException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            throw (SqlTemplateException) e.getCause();
        }
    }

    /**
     * Returns the table name for the specified record type taking the table name resolver and the schema resolver
     * into account. This is the name a statement addresses; the declared name of
     * {@link #getTableName(Class, TableNameResolver)} serves to compare tables with each other.
     *
     * @param table the record type to obtain the table name for.
     * @param tableNameResolver the table name resolver.
     * @param schemaResolver the schema resolver.
     * @return the table name for the specified record type, in the schema the resolver addresses.
     * @since 1.15
     */
    static TableName getTableName(Class<? extends Data> table,
                                  TableNameResolver tableNameResolver,
                                  SchemaResolver schemaResolver) throws SqlTemplateException {
        var tableName = getTableName(table, tableNameResolver);
        var schema = schemaResolver.resolveSchema(table, tableName.schema());
        if (schema == null) {
            throw new SqlTemplateException("Schema resolver returned null for %s; return an empty string to address the table without a schema.".formatted(table.getSimpleName()));
        }
        return schema.equals(tableName.schema())
                ? tableName
                : new TableName(tableName.table(), schema, tableName.escape());
    }

    /**
     * Returns the table name for the specified record type taking the table name resolver into account, if present.
     *
     * @param table the record type to obtain the table name for.
     * @param tableNameResolver the table name resolver.
     * @return the table name for the specified record type.
     */
    static TableName getTableName(Class<? extends Data> table,
                                  TableNameResolver tableNameResolver) throws SqlTemplateException {
        // For sealed entity interfaces, resolve table name from the @DbTable annotation on the
        // interface itself, or fall back to camelCase-to-snake_case conversion (sealed interfaces
        // aren't records and don't have a RecordType).
        if (table.isSealed() && isSealedEntity(table)) {
            DbTable dbTable = table.getAnnotation(DbTable.class);
            if (dbTable != null) {
                var tableNames = Stream.of(dbTable.name(), dbTable.value())
                        .filter(not(String::isEmpty))
                        .distinct()
                        .toList();
                if (tableNames.size() > 1) {
                    throw new PersistenceException("Multiple table names found for %s.".formatted(table.getSimpleName()));
                }
                String tableName = tableNames.isEmpty()
                        ? camelCaseToSnakeCase(table.getSimpleName())
                        : tableNames.getFirst();
                if (!dbTable.schema().isEmpty()) {
                    return new TableName(tableName, dbTable.schema(), dbTable.escape());
                }
                return new TableName(tableName, "", dbTable.escape());
            }
            // No @DbTable: use camelCase-to-snake_case conversion.
            return new TableName(camelCaseToSnakeCase(table.getSimpleName()), "", false);
        }
        RecordType type = REFLECTION.getRecordType(table);
        String tableName = null;
        DbTable dbTable = type.getAnnotation(DbTable.class);
        if (dbTable != null) {
            var tableNames = Stream.of(dbTable.name(), dbTable.value())
                    .filter(not(String::isEmpty))
                    .distinct()
                    .toList();
            if (tableNames.size() > 1) {
                throw new PersistenceException("Multiple table names found for %s.".formatted(table.getSimpleName()));
            }
            if (!tableNames.isEmpty()) {
                tableName = tableNames.getFirst();
            }
        }
        if (tableName == null) {
            tableName = tableNameResolver.resolveTableName(type);
        }
        if (dbTable != null) {
            if (!dbTable.schema().isEmpty()) {
                return new TableName(tableName, dbTable.schema(), dbTable.escape());
            }
            return new TableName(tableName, "", dbTable.escape());
        }
        return new TableName(tableName, "", false);
    }

    /**
     * Combines the specified column value and name into a stream of lists. If the value is empty, only the name is
     * returned. If the name is empty, only the value is returned. If both are non-empty, an exception is thrown.
     *
     * @param value the column value.“
     * @param name the column name.
     * @return a stream of lists containing the column value and/or name.
     * @throws IllegalArgumentException if the name is different from the value.
     */
    private static Optional<String> combine(String value, String name) {
        if (!value.isEmpty()) {
            if (!name.isEmpty() && !name.equals(value)) {
                throw new IllegalArgumentException("Column name '%s' cannot be different from the column value '%s'.".formatted(name, value));
            }
            return Optional.of(value);
        }
        if (!name.isEmpty()) {
            return Optional.of(name);
        }
        return empty();
    }

    /**
     * Returns the column name(s) for the specified {@link DbColumn} or {@link DbColumns} annotation.
     *
     * @param dbColumns the {@link DbColumn} or {@link DbColumns} annotation to obtain the column name(s) for.
     * @return the column name(s) for the specified {@link DbColumn} or {@link DbColumns} annotation.
     * @throws IllegalArgumentException if any of the annotations is invalid.
     */
    private static Stream<List<ColumnName>> columnNames(DbColumn[] dbColumns) {
        return Stream.of(Arrays.stream(dbColumns)
                .map(dbColumn -> combine(dbColumn.name(), dbColumn.value())
                        .map(name -> new ColumnName(name, dbColumn.escape()))
                        .orElseThrow(() -> new IllegalArgumentException("Column name cannot be empty."))).toList());
    }

    private static final List<Class<? extends Annotation>> COLUMN_ANNOTATIONS = List.of(PK.class, FK.class, DbColumn.class);

    /**
     * Returns the column name for the specified record field taking the column name resolver into account,
     * if present.
     *
     * @param field the record field to obtain the column name for.
     * @param columnNameResolver the column name resolver.
     * @return the column name for the specified record field.
     */
    static ColumnName getColumnName(RecordField field,
                                    ColumnNameResolver columnNameResolver) throws SqlTemplateException {
        List<ColumnName> names = getColumnNames(field, COLUMN_ANNOTATIONS);
        if (names.size() == 1) {
            return names.getFirst();
        }
        if (names.size() > 1) {
            throw new SqlTemplateException("Multiple column names found for %s.%s: %s.".formatted(field.type().getSimpleName(), field.name(), names));
        }
        DbColumn dbColumn = field.getAnnotation(DbColumn.class);
        return new ColumnName(columnNameResolver.resolveColumnName(field), dbColumn != null && dbColumn.escape());
    }

    /**
     * Returns the column name(s) for the specified record component using the component's annotations.
     *
     * @param field the record field to obtain the column name(s) for.
     * @param annotationTypes the column name annotations to consider.
     * @return the column name(s) for the specified record component.
     * @throws SqlTemplateException if zero, or multiple names are found for the component.
     */
    private static List<ColumnName> getColumnNames(RecordField field,
                                                   List<Class<? extends Annotation>> annotationTypes)
            throws SqlTemplateException {
        try {
            var columNameLists = annotationTypes.stream()
                    .map(annotationType -> field.getAnnotation(annotationType))
                    .filter(Objects::nonNull)
                    .flatMap(RecordReflection::getColumnNames)
                    .distinct()
                    .toList();
            if (columNameLists.isEmpty()) {
                return List.of();
            }
            if (columNameLists.size() > 1) {
                throw new SqlTemplateException("Multiple column names found for %s.%s: %s."
                        .formatted(field.type().getSimpleName(), field.name(), columNameLists));
            }
            return columNameLists.getFirst();
        } catch (IllegalArgumentException e) {
            throw new SqlTemplateException(e);
        }
    }

    /**
     * Returns the column name(s) for as specified by the {@code annotation}.
     *
     * @param annotation annotation to obtain the column name(s) for.
     * @return the column name(s) for as specified by the {@code annotation}.
     * @throws IllegalArgumentException if the annotation is invalid.
     */
    private static Stream<List<ColumnName>> getColumnNames(Annotation annotation) {
        return switch (annotation) {
            case PK pk -> combine(pk.value(), pk.name()).map(ColumnName::new).stream().map(name -> List.of(name));
            case FK fk -> combine(fk.value(), fk.name()).map(ColumnName::new).stream().map(name -> List.of(name));
            case DbColumn dbColumn -> columnNames(new DbColumn[]{dbColumn});
            case DbColumns dbColumns -> columnNames(dbColumns.value());
            default -> throw new IllegalArgumentException("Unsupported annotation: %s.".formatted(annotation));
        };
    }

    private static final List<Class<? extends Annotation>> PK_COLUMN_ANNOTATIONS = List.of(PK.class, DbColumn.class, DbColumns.class);

    /**
     * Returns the column name(s) for the specified primary key field.
     *
     * @param field the record field to obtain the primary key column name(s) for.
     * @return the column name for the specified record component(s).
     */
    static List<ColumnName> getPrimaryKeys(RecordField field,
                                           ForeignKeyResolver foreignKeyResolver,
                                           ColumnNameResolver columnNameResolver) throws SqlTemplateException {
        var columnNames = getColumnNames(field, PK_COLUMN_ANNOTATIONS);
        if (!columnNames.isEmpty()) {
            return columnNames;
        }
        if (field.isAnnotationPresent(FK.class)) {
            // If the primary key component is also a foreign key, return the foreign key column names.
            return getForeignKeys(field, foreignKeyResolver, columnNameResolver);
        }
        DbColumn[] dbColumns = field.getAnnotations(DbColumn.class);
        RecordType fieldType = REFLECTION.findRecordType(field.type()).orElse(null);
        if (fieldType != null) {
            var leaves = new ArrayList<KeyLeaf>();
            flattenKeyField(field, new ArrayList<>(), new HashSet<>(), leaves);
            columnNames = new ArrayList<>(leaves.size());
            for (int i = 0; i < leaves.size(); i++) {
                var leafField = leaves.get(i).field();
                DbColumn nestedDbColumn = i < dbColumns.length
                        ? dbColumns[i]
                        : leafField.getAnnotation(DbColumn.class);    // Top level is prioritized over nested.
                String name = columnNameResolver.resolveColumnName(leafField);
                columnNames.add(new ColumnName(name, nestedDbColumn != null && nestedDbColumn.escape()));
            }
        } else {
            DbColumn dbColumn = dbColumns.length > 0 ? dbColumns[0] : null;
            String name = columnNameResolver.resolveColumnName(field);
            columnNames = List.of(new ColumnName(name, dbColumn != null && dbColumn.escape()));
        }
        return columnNames;
    }

    private static final List<Class<? extends Annotation>> FK_COLUMN_ANNOTATIONS = List.of(FK.class, DbColumn.class, DbColumns.class);

    /**
     * Returns the column name(s) for the specified foreign key field taking the column name resolver into account,
     * if present.
     *
     * @param field the record field to obtain the foreign key column name(s) for.
     * @param foreignKeyResolver the foreign key resolver.
     * @return the column name for the specified record component(s).
     */
    @SuppressWarnings("unchecked")
    static List<ColumnName> getForeignKeys(RecordField field,
                                           ForeignKeyResolver foreignKeyResolver,
                                           ColumnNameResolver columnNameResolver) throws SqlTemplateException {
        var columnNames = getColumnNames(field, FK_COLUMN_ANNOTATIONS);
        if (!columnNames.isEmpty()) {
            return columnNames;
        }
        Class<?> fkType = Ref.class.isAssignableFrom(field.type())
                ? getRefDataType(field)
                : field.type();
        // Handle Polymorphic FK: sealed Data interface with separate entity subtypes.
        if (isPolymorphicData(fkType)) {
            // Emit two columns: discriminator + FK value.
            String discriminatorCol = getPolymorphicDiscriminatorColumn(field);
            String fkCol = field.name() + "_id";
            return List.of(
                    new ColumnName(discriminatorCol, false),
                    new ColumnName(fkCol, false)
            );
        }
        // For sealed entity types (Single-Table/Joined), resolve PK from first permitted subclass.
        if (fkType.isSealed() && isSealedEntity(fkType)) {
            Class<?>[] permitted = fkType.getPermittedSubclasses();
            if (permitted != null && permitted.length > 0) {
                fkType = permitted[0];
            }
        }
        DbColumn[] dbColumns = field.getAnnotations(DbColumn.class);
        List<KeyLeaf> leaves = getPkLeaves(fkType);
        if (leaves.size() == 1) {
            // If the key resolves to a single column, use the column name of the FK component.
            DbColumn dbColumn = dbColumns.length > 0
                    ? dbColumns[0]
                    : leaves.getFirst().field().getAnnotation(DbColumn.class);
            String name = foreignKeyResolver.resolveColumnName(field, REFLECTION.getRecordType(fkType));
            return List.of(new ColumnName(name, dbColumn != null && dbColumn.escape()));
        }
        columnNames = new ArrayList<>(leaves.size());
        for (int i = 0; i < leaves.size(); i++) {
            var leafField = leaves.get(i).field();
            DbColumn nestedDbColumn = i < dbColumns.length
                    ? dbColumns[i]
                    : leafField.getAnnotation(DbColumn.class); // Top-level prioritized.
            String name = columnNameResolver.resolveColumnName(leafField);
            columnNames.add(new ColumnName(name, nestedDbColumn != null && nestedDbColumn.escape()));
        }
        return columnNames;
    }

    // ---- Sealed type hierarchy support ----

    /**
     * Enumeration of the polymorphic patterns supported for sealed type hierarchies.
     */
    enum SealedPattern {
        /** All subtypes in one table with discriminator column. Sealed interface is an Entity with @DbTable,
         *  subtypes do NOT have @DbTable. */
        SINGLE_TABLE,
        /** Each subtype in its own extension table (common fields + discriminator in base table).
         *  Sealed interface is an Entity with @DbTable, subtypes also have @DbTable. */
        JOINED,
        /** Sealed interface is a Data marker (not Entity). Each subtype independently has @DbTable and
         *  implements Entity. FK produces discriminator + value columns. */
        POLYMORPHIC_FK
    }

    /**
     * Sealed pattern detection results per type; entries die with the type.
     */
    private static final ClassValue<Optional<SealedPattern>> SEALED_PATTERN_CACHE = new ClassValue<>() {
        @Override
        protected Optional<SealedPattern> computeValue(Class<?> t) {
            if (!t.isSealed()) {
                return Optional.empty();
            }
            Class<?>[] permitted = t.getPermittedSubclasses();
            if (permitted == null || permitted.length == 0) {
                return Optional.empty();
            }
            boolean isEntity = Entity.class.isAssignableFrom(t);
            if (isEntity) {
                // Sealed Entity: use @Polymorphic to determine strategy.
                // Default is SINGLE_TABLE; @Polymorphic(JOINED) opts into Joined Table.
                // @Polymorphic(SINGLE_TABLE) is also accepted for self-documenting code.
                Polymorphic polymorphic = t.getAnnotation(Polymorphic.class);
                if (polymorphic != null && polymorphic.value() == Polymorphic.Strategy.JOINED) {
                    return Optional.of(SealedPattern.JOINED);
                }
                return Optional.of(SealedPattern.SINGLE_TABLE);
            }
            if (Data.class.isAssignableFrom(t)) {
                // Polymorphic FK: sealed Data interface, subtypes are independent entities.
                // Subtypes must implement Entity; @DbTable is optional (table name resolver
                // is used when not present).
                boolean allSubtypesAreEntities = true;
                for (Class<?> sub : permitted) {
                    if (!Entity.class.isAssignableFrom(sub)) {
                        allSubtypesAreEntities = false;
                        break;
                    }
                }
                if (allSubtypesAreEntities) {
                    return Optional.of(SealedPattern.POLYMORPHIC_FK);
                }
            }
            return Optional.empty();
        }
    };

    /**
     * Detects the polymorphic pattern for the given sealed type, if any.
     *
     * @param type the type to inspect.
     * @return an Optional containing the detected SealedPattern, or empty if the type is not a sealed hierarchy.
     */
    static Optional<SealedPattern> detectSealedPattern(Class<?> type) {
        return SEALED_PATTERN_CACHE.get(type);
    }

    /**
     * Returns true if the given type is a sealed entity (Single-Table or Joined pattern).
     */
    static boolean isSealedEntity(Class<?> type) {
        return detectSealedPattern(type)
                .map(p -> p == SealedPattern.SINGLE_TABLE || p == SealedPattern.JOINED)
                .orElse(false);
    }

    /**
     * Returns true if the given type uses single-table inheritance.
     */
    static boolean isSingleTableEntity(Class<?> type) {
        return detectSealedPattern(type)
                .map(p -> p == SealedPattern.SINGLE_TABLE)
                .orElse(false);
    }

    /**
     * Returns true if the given type uses joined table inheritance.
     */
    static boolean isJoinedEntity(Class<?> type) {
        return detectSealedPattern(type)
                .map(p -> p == SealedPattern.JOINED)
                .orElse(false);
    }

    /**
     * Returns true if the given type is a polymorphic Data interface (Polymorphic FK).
     */
    static boolean isPolymorphicData(Class<?> type) {
        return detectSealedPattern(type)
                .map(p -> p == SealedPattern.POLYMORPHIC_FK)
                .orElse(false);
    }

    /**
     * Returns true if the given sealed type has a discriminator column.
     *
     * <p>For SINGLE_TABLE inheritance, a discriminator is always required. For JOINED inheritance,
     * the {@code @Discriminator} annotation is optional; when absent, type resolution is performed
     * via a CASE expression that checks which extension table has a matching row.</p>
     *
     * @param sealedType the sealed type to check.
     * @return {@code true} if the type has a discriminator column.
     */
    static boolean hasDiscriminator(Class<?> sealedType) {
        var pattern = detectSealedPattern(sealedType).orElse(null);
        if (pattern == SealedPattern.SINGLE_TABLE) {
            return true;
        }
        if (pattern == SealedPattern.JOINED) {
            return sealedType.isAnnotationPresent(Discriminator.class);
        }
        return false;
    }

    /**
     * Returns the discriminator column name for a sealed type hierarchy.
     *
     * <p>If the sealed interface is annotated with {@link Discriminator @Discriminator}, the configured column name
     * is returned (defaulting to {@code "dtype"} if no column is specified). For JOINED inheritance without
     * {@code @Discriminator}, the default {@code "dtype"} is returned as an internal key (it is never projected
     * as a column in this case).</p>
     *
     * @param sealedType the sealed interface.
     * @return the discriminator column name.
     * @throws SqlTemplateException if a SINGLE_TABLE entity lacks {@code @Discriminator}.
     */
    static String getDiscriminatorColumn(Class<?> sealedType) throws SqlTemplateException {
        Discriminator discriminator = sealedType.getAnnotation(Discriminator.class);
        if (discriminator == null) {
            // For JOINED without @Discriminator, return default internal key.
            if (isJoinedEntity(sealedType)) {
                return "dtype";
            }
            throw new SqlTemplateException(
                    "Sealed type %s must be annotated with @Discriminator to specify the discriminator column. Add @Discriminator(column = \"...\") to the sealed type to specify which column distinguishes between subtypes."
                            .formatted(sealedType.getSimpleName()));
        }
        if (!discriminator.column().isEmpty()) {
            return discriminator.column();
        }
        return "dtype";
    }

    /**
     * Returns the discriminator column name for a polymorphic FK field.
     *
     * @param field the FK field targeting a sealed Data type.
     * @return the discriminator column name.
     */
    static String getPolymorphicDiscriminatorColumn(RecordField field) {
        Discriminator discriminator = field.getAnnotation(Discriminator.class);
        if (discriminator != null && !discriminator.column().isEmpty()) {
            return discriminator.column();
        }
        return field.name() + "_type";
    }

    /**
     * Converts a camelCase name to snake_case. Matches the default name resolver behavior.
     */
    private static String camelCaseToSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toLowerCase(name.charAt(0)));
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else if (Character.isDigit(c)
                    && i >= 2
                    && Character.isLowerCase(name.charAt(i - 1))
                    && Character.isLowerCase(name.charAt(i - 2))) {
                sb.append('_').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Returns the discriminator type for a sealed type hierarchy.
     *
     * <p>The type is read from the {@code @Discriminator} annotation on the sealed interface.
     * Defaults to {@link DiscriminatorType#STRING} if not specified.</p>
     *
     * @param sealedType the sealed interface.
     * @return the discriminator type.
     */
    static DiscriminatorType getDiscriminatorType(Class<?> sealedType) {
        Discriminator discriminator = sealedType.getAnnotation(Discriminator.class);
        if (discriminator != null) {
            return discriminator.type();
        }
        return DiscriminatorType.STRING;
    }

    /**
     * Returns the Java type corresponding to the discriminator column for a sealed type hierarchy.
     *
     * @param sealedType the sealed interface.
     * @return {@code String.class}, {@code Integer.class}, or {@code Character.class}.
     */
    static Class<?> getDiscriminatorColumnJavaType(Class<?> sealedType) {
        return switch (getDiscriminatorType(sealedType)) {
            case STRING -> String.class;
            case INTEGER -> Integer.class;
            case CHAR -> Character.class;
        };
    }

    /**
     * Returns the discriminator value for a concrete subtype within a sealed hierarchy.
     *
     * <p>The return type depends on the discriminator type of the sealed interface:
     * {@code String} for STRING, {@code Integer} for INTEGER, {@code Character} for CHAR.</p>
     *
     * @param concreteType the concrete subtype (a permitted subclass).
     * @param sealedType the sealed interface.
     * @return the discriminator value.
     */
    static Object getDiscriminatorValue(Class<?> concreteType, Class<?> sealedType) {
        Discriminator discriminator = concreteType.getAnnotation(Discriminator.class);
        String rawValue;
        if (discriminator != null && !discriminator.value().isEmpty()) {
            rawValue = discriminator.value();
        } else if (isPolymorphicData(sealedType)) {
            // For POLYMORPHIC_FK, use @DbTable name if present, otherwise use the default table name
            // resolver to derive the discriminator value from the class name.
            DbTable dbTable = concreteType.getAnnotation(DbTable.class);
            if (dbTable != null) {
                String name = dbTable.value().isEmpty() ? dbTable.name() : dbTable.value();
                if (!name.isEmpty()) {
                    rawValue = name;
                } else {
                    rawValue = camelCaseToSnakeCase(concreteType.getSimpleName());
                }
            } else {
                // Use camelCase to snake_case conversion (matches default table name resolver).
                rawValue = camelCaseToSnakeCase(concreteType.getSimpleName());
            }
        } else {
            // Default: simple class name.
            rawValue = concreteType.getSimpleName();
        }
        return convertDiscriminatorValue(rawValue, getDiscriminatorType(sealedType));
    }

    /**
     * Converts a raw string discriminator value to the appropriate Java type based on the discriminator type.
     */
    private static Object convertDiscriminatorValue(String rawValue, DiscriminatorType type) {
        return switch (type) {
            case STRING -> rawValue;
            case INTEGER -> Integer.parseInt(rawValue);
            case CHAR -> rawValue.charAt(0);
        };
    }

    /**
     * Discriminator value to concrete type mappings per sealed type; entries die with the sealed type.
     */
    private static final ClassValue<Map<Object, Class<?>>> DISCRIMINATOR_MAP_CACHE = new ClassValue<>() {
        @Override
        protected Map<Object, Class<?>> computeValue(Class<?> t) {
            Map<Object, Class<?>> m = new ConcurrentHashMap<>();
            Class<?>[] permitted = t.getPermittedSubclasses();
            if (permitted != null) {
                for (Class<?> sub : permitted) {
                    Object value = getDiscriminatorValue(sub, t);
                    m.put(value, sub);
                }
            }
            return m;
        }
    };

    /**
     * Resolves a discriminator value to a concrete subtype for the given sealed type.
     *
     * @param sealedType the sealed interface.
     * @param discriminatorValue the discriminator value from the database.
     * @return the concrete subtype class.
     * @throws SqlTemplateException if the discriminator value does not match any permitted subtype.
     */
    static Class<?> resolveConcreteType(Class<?> sealedType,
                                        Object discriminatorValue) throws SqlTemplateException {
        Map<Object, Class<?>> map = DISCRIMINATOR_MAP_CACHE.get(sealedType);
        Class<?> resolved = map.get(discriminatorValue);
        if (resolved == null) {
            throw new SqlTemplateException("Unknown discriminator value '%s' for sealed type %s. Known values: %s."
                    .formatted(discriminatorValue, sealedType.getSimpleName(), map.keySet()));
        }
        return resolved;
    }

    /**
     * Normalizes a raw discriminator value from the database to the appropriate Java type
     * based on the discriminator type of the sealed interface.
     *
     * @param raw the raw value from JDBC.
     * @param discriminatorType the discriminator type of the sealed hierarchy.
     * @return the normalized value (String, Integer, or Character).
     */
    static Object normalizeDiscriminatorValue(Object raw, DiscriminatorType discriminatorType) {
        return switch (discriminatorType) {
            case STRING -> raw.toString();
            case INTEGER -> raw instanceof Number number ? number.intValue() : Integer.parseInt(raw.toString());
            case CHAR -> raw instanceof String string && string.length() == 1
                    ? string.charAt(0)
                    : (raw instanceof Character ? raw : raw.toString().charAt(0));
        };
    }

    /**
     * Returns the fields that are common to ALL permitted subclasses of a joined sealed entity.
     * These fields belong to the base table. PK is always a base field.
     *
     * <p>A field is "common" if it appears in every permitted subclass with the same name AND type.</p>
     *
     * @param sealedType the sealed entity interface.
     * @return the list of field names that belong to the base table.
     */
    static List<String> getBaseFieldNames(Class<?> sealedType) {
        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            return List.of();
        }
        // Start with all fields from the first subtype.
        RecordType firstType = REFLECTION.getRecordType(permitted[0]);
        List<String> candidates = new ArrayList<>();
        for (RecordField field : firstType.fields()) {
            candidates.add(field.name());
        }
        // Intersect with fields from all other subtypes.
        for (int i = 1; i < permitted.length; i++) {
            RecordType subType = REFLECTION.getRecordType(permitted[i]);
            List<String> subNames = subType.fields().stream().map(RecordField::name).toList();
            // Also check that types match.
            candidates.removeIf(name -> {
                if (!subNames.contains(name)) {
                    return true;
                }
                // Verify type matches.
                RecordField firstField = firstType.fields().stream()
                        .filter(f -> f.name().equals(name)).findFirst().orElse(null);
                RecordField subField = subType.fields().stream()
                        .filter(f -> f.name().equals(name)).findFirst().orElse(null);
                return firstField == null || subField == null || !firstField.type().equals(subField.type());
            });
        }
        return List.copyOf(candidates);
    }

    /**
     * Returns the field names unique to a specific concrete subtype (extension table fields).
     * These are fields NOT in the base field set.
     *
     * @param concreteType the concrete subtype.
     * @param sealedType the sealed entity interface.
     * @return the list of extension field names.
     */
    static List<String> getExtensionFieldNames(Class<?> concreteType,
                                               Class<?> sealedType) {
        List<String> baseFields = getBaseFieldNames(sealedType);
        RecordType type = REFLECTION.getRecordType(concreteType);
        List<String> extensionFields = new ArrayList<>();
        for (RecordField field : type.fields()) {
            if (!baseFields.contains(field.name())) {
                extensionFields.add(field.name());
            }
        }
        return List.copyOf(extensionFields);
    }

    /**
     * Validates a sealed entity hierarchy. Should be called during startup validation.
     *
     * @param sealedType the sealed interface to validate.
     * @return an error message, or empty string if valid.
     */
    static String validateSealedHierarchy(Class<?> sealedType) {
        Optional<SealedPattern> patternOpt = detectSealedPattern(sealedType);
        if (patternOpt.isEmpty()) {
            return "";
        }
        SealedPattern pattern = patternOpt.get();
        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            return "Sealed type %s has no permitted subclasses.".formatted(sealedType.getSimpleName());
        }
        // All permitted subclasses must be records.
        for (Class<?> sub : permitted) {
            if (REFLECTION.findRecordType(sub).isEmpty()) {
                return "Permitted subclass %s of sealed type %s must be a record."
                        .formatted(sub.getSimpleName(), sealedType.getSimpleName());
            }
        }
        if (pattern == SealedPattern.SINGLE_TABLE || pattern == SealedPattern.JOINED) {
            // @Discriminator is required for SINGLE_TABLE, optional for JOINED.
            if (pattern == SealedPattern.SINGLE_TABLE && !sealedType.isAnnotationPresent(Discriminator.class)) {
                return "Sealed entity %s must be annotated with @Discriminator to specify the discriminator column."
                        .formatted(sealedType.getSimpleName());
            }
            // Check for misused @Discriminator value attribute on sealed interface.
            Discriminator sealedDiscriminator = sealedType.getAnnotation(Discriminator.class);
            if (sealedDiscriminator != null && !sealedDiscriminator.value().isEmpty()) {
                return "@Discriminator on sealed entity %s specifies a value attribute '%s'. "
                        .formatted(sealedType.getSimpleName(), sealedDiscriminator.value())
                        + "Use the column attribute to set the discriminator column name, or omit it for the default column name 'dtype'.";
            }
            // All subtypes must have the same @PK type and generation strategy.
            Class<?> firstPkType = null;
            GenerationStrategy firstGenStrategy = null;
            for (Class<?> sub : permitted) {
                Optional<RecordField> pkField = findPkField(sub);
                if (pkField.isEmpty()) {
                    return "Permitted subclass %s of sealed entity %s must have a @PK field."
                            .formatted(sub.getSimpleName(), sealedType.getSimpleName());
                }
                Class<?> pkType = pkField.get().type();
                GenerationStrategy genStrategy = getGenerationStrategy(pkField.get());
                if (firstPkType == null) {
                    firstPkType = pkType;
                    firstGenStrategy = genStrategy;
                } else {
                    if (!firstPkType.equals(pkType)) {
                        return "All permitted subclasses of sealed entity %s must have the same @PK type. Found %s and %s."
                                .formatted(sealedType.getSimpleName(), firstPkType.getSimpleName(), pkType.getSimpleName());
                    }
                    if (firstGenStrategy != genStrategy) {
                        return "All permitted subclasses of sealed entity %s must have the same @PK generation strategy. Found %s and %s."
                                .formatted(sealedType.getSimpleName(), firstGenStrategy, genStrategy);
                    }
                }
            }
        }
        if (pattern == SealedPattern.JOINED) {
            // Shared fields must match in name and type.
            List<String> baseFields = getBaseFieldNames(sealedType);
            if (baseFields.isEmpty()) {
                return "Joined sealed entity %s has no common fields across subtypes."
                        .formatted(sealedType.getSimpleName());
            }
        }
        // Check for near-miss fields: same name but different types across subtypes.
        if (pattern == SealedPattern.SINGLE_TABLE || pattern == SealedPattern.JOINED) {
            for (int i = 0; i < permitted.length; i++) {
                RecordType subType1 = REFLECTION.getRecordType(permitted[i]);
                for (RecordField field1 : subType1.fields()) {
                    for (int j = i + 1; j < permitted.length; j++) {
                        RecordType subType2 = REFLECTION.getRecordType(permitted[j]);
                        for (RecordField field2 : subType2.fields()) {
                            if (field1.name().equals(field2.name()) && !field1.type().equals(field2.type())) {
                                return "Field '%s' has different types in subtypes %s (%s) and %s (%s) of sealed type %s. "
                                        .formatted(field1.name(),
                                                permitted[i].getSimpleName(), field1.type().getSimpleName(),
                                                permitted[j].getSimpleName(), field2.type().getSimpleName(),
                                                sealedType.getSimpleName())
                                        + "Fields shared across subtypes must have the same type.";
                            }
                        }
                    }
                }
            }
        }
        if (pattern == SealedPattern.POLYMORPHIC_FK) {
            // Sealed interface must NOT have @DbTable.
            if (sealedType.isAnnotationPresent(DbTable.class)) {
                return "Polymorphic data type %s must not have @DbTable.".formatted(sealedType.getSimpleName());
            }
            // Sealed interface must NOT have @Discriminator (discriminator is on the FK field).
            if (sealedType.isAnnotationPresent(Discriminator.class)) {
                return "Polymorphic data type %s must not have @Discriminator. "
                        .formatted(sealedType.getSimpleName())
                        + "The discriminator column is specified on the @FK field that references this type.";
            }
            // Sealed interface must NOT have @Polymorphic (it's for sealed Entity types only).
            if (sealedType.isAnnotationPresent(Polymorphic.class)) {
                return "Polymorphic data type %s must not have @Polymorphic. "
                        .formatted(sealedType.getSimpleName())
                        + "@Polymorphic is only used on sealed Entity interfaces.";
            }
            // All subtypes must implement Entity.
            Class<?> firstPkType = null;
            GenerationStrategy firstGenStrategy = null;
            for (Class<?> sub : permitted) {
                if (!Entity.class.isAssignableFrom(sub)) {
                    return "Permitted subclass %s of polymorphic data type %s must implement Entity."
                            .formatted(sub.getSimpleName(), sealedType.getSimpleName());
                }
                Optional<RecordField> pkField = findPkField(sub);
                if (pkField.isEmpty()) {
                    return "Permitted subclass %s of polymorphic data type %s must have a @PK field."
                            .formatted(sub.getSimpleName(), sealedType.getSimpleName());
                }
                Class<?> pkType = pkField.get().type();
                GenerationStrategy genStrategy = getGenerationStrategy(pkField.get());
                if (firstPkType == null) {
                    firstPkType = pkType;
                    firstGenStrategy = genStrategy;
                } else {
                    if (!firstPkType.equals(pkType)) {
                        return "All permitted subclasses of polymorphic data type %s must have the same @PK column type. Found %s and %s."
                                .formatted(sealedType.getSimpleName(), firstPkType.getSimpleName(), pkType.getSimpleName());
                    }
                    if (firstGenStrategy != genStrategy) {
                        return "All permitted subclasses of polymorphic data type %s must have the same @PK generation strategy. Found %s and %s."
                                .formatted(sealedType.getSimpleName(), firstGenStrategy, genStrategy);
                    }
                }
            }
        }
        // Validate discriminator value uniqueness.
        Set<Object> seenValues = new HashSet<>();
        for (Class<?> sub : permitted) {
            Object value = getDiscriminatorValue(sub, sealedType);
            if (!seenValues.add(value)) {
                return "Duplicate discriminator value '%s' in sealed type %s."
                        .formatted(value, sealedType.getSimpleName());
            }
        }
        // Check for misplaced @Discriminator(column) on subtypes.
        for (Class<?> sub : permitted) {
            Discriminator subDiscriminator = sub.getAnnotation(Discriminator.class);
            if (subDiscriminator != null && !subDiscriminator.column().isEmpty()) {
                return "@Discriminator on permitted subclass %s of sealed type %s specifies a column attribute. "
                        .formatted(sub.getSimpleName(), sealedType.getSimpleName())
                        + "The discriminator column is specified on the sealed interface, not on subtypes.";
            }
        }
        // Check for @DbTable on single-table subtypes.
        if (pattern == SealedPattern.SINGLE_TABLE) {
            for (Class<?> sub : permitted) {
                if (sub.isAnnotationPresent(DbTable.class)) {
                    return "Permitted subclass %s of single-table sealed entity %s must not have @DbTable. "
                            .formatted(sub.getSimpleName(), sealedType.getSimpleName())
                            + "All subtypes share the sealed interface's table.";
                }
            }
        }
        return "";
    }

    /**
     * Returns the joined sealed parent of the given type, if any.
     *
     * <p>This method checks whether the given concrete type is a permitted subclass of a sealed entity
     * that uses joined table inheritance. If so, it returns the sealed parent interface.</p>
     *
     * @param type the type to inspect.
     * @return an Optional containing the joined sealed parent, or empty if the type is not a joined subtype.
     */
    static Optional<Class<?>> findJoinedSealedParent(Class<?> type) {
        if (type.isSealed() || type.isInterface()) {
            return Optional.empty();
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (isJoinedEntity(iface)) {
                return Optional.of(iface);
            }
        }
        return Optional.empty();
    }

    // ---- End sealed type hierarchy support ----

    static void mapForeignKeys(TableMapper tableMapper,
                               String alias,
                               Class<? extends Data> rootTable,
                               Class<? extends Data> table,
                               @Nullable String path)
            throws SqlTemplateException {
        if (table.isSealed() && isSealedEntity(table)) {
            return; // Sealed entity interfaces have no FK fields; subtypes are handled separately.
        }
        for (var field : RecordReflection.getRecordFields(table)) {
            if (field.isAnnotationPresent(FK.class)) {
                if (Ref.class.isAssignableFrom(field.type())) {
                    tableMapper.mapForeignKey(table, getRefDataType(field), alias, field, rootTable, path);
                } else {
                    Class<?> recordType = field.type();
                    REFLECTION.findRecordType(recordType)
                            .orElseThrow(() -> new SqlTemplateException("FK annotation is only allowed on record types: %s.".formatted(field.type().getSimpleName())));
                    if (!Data.class.isAssignableFrom(recordType)) {
                        throw new SqlTemplateException("@FK annotation is only allowed on Data types: %s. Foreign key fields must reference types that implement the Data interface (Entity or Inline record). Remove the @FK annotation or change the field type.".formatted(field.type().getSimpleName()));
                    }
                    tableMapper.mapForeignKey(table, (Class<? extends Data>) recordType, alias, field, rootTable, path);
                }
            }
        }
    }
}
