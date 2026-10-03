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

import static java.util.Locale.ROOT;
import static java.util.Optional.empty;
import static st.orm.StormConfig.VALIDATION_RECORD_MODE;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.getRefPkType;
import static st.orm.core.template.impl.RecordReflection.isRecord;

import java.lang.invoke.MethodType;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.Data;
import st.orm.Entity;
import st.orm.FK;
import st.orm.GenerationStrategy;
import st.orm.Inline;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.ProjectionQuery;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.Version;
import st.orm.core.spi.TypeDiscovery;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Helper class for validating record types and named parameters.
 */
@SuppressWarnings("ALL")
final class RecordValidation {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.validation");

    private RecordValidation() {
    }

    /**
     * Validation messages per data type, keyed by the require-primary-key flag; an empty message marks a valid type.
     * {@link ClassValue} ties each entry to the lifetime of the validated type, so the cache never pins the type or
     * its class loader.
     */
    private static final ClassValue<ConcurrentMap<Boolean, String>> VALIDATE_RECORD_TYPE_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentMap<Boolean, String> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static volatile boolean validationCompleted = false;

    static void validate() {
        validate(StormConfig.defaults());
    }

    static void validate(StormConfig config) {
        if (validationCompleted) {
            return;
        }
        String recordMode = resolveRecordMode(config);
        synchronized (RecordValidation.class) {
            if (validationCompleted) {
                return;
            }
            if ("none".equals(recordMode)) {
                LOGGER.info("Skipping Data type validation. Set storm.validation.record_mode=fail to enable validation.");
                validationCompleted = true;
                return;
            }
            boolean warningsOnly = "warn".equals(recordMode);
            LOGGER.debug("Validating Data types for correctness.");
            var dataTypes = TypeDiscovery.getDataTypes();
            var validationErrors = new AtomicReference<>(0);
            var firstError = new AtomicReference<String>();
            dataTypes.forEach(
                    dataType -> {
                        try {
                            validateDataType(dataType, Entity.class.isAssignableFrom(dataType));
                        } catch (SqlTemplateException e) {
                            validationErrors.setPlain(validationErrors.getPlain() + 1);
                            if (firstError.getPlain() == null) {
                                firstError.setPlain(e.getMessage());
                            }
                            LOGGER.warn("Validation failed for %s: %s"
                                    .formatted(dataType.getSimpleName(), e.getMessage()));
                        }
                    }
            );
            if (!warningsOnly && firstError.getPlain() != null) {
                throw new PersistenceException(firstError.getPlain());
            }
            if (validationErrors.getPlain() > 0) {
                LOGGER.warn("Entity validation found %d issues. Set storm.validation.record_mode=fail to fail on startup."
                        .formatted(validationErrors.getPlain()));
            } else if (dataTypes.isEmpty()) {
                LOGGER.debug("No Data types to validate for correctness.");
            } else {
                LOGGER.info("Successfully validated %s Data types for correctness.".formatted(dataTypes.size()));
            }
            validationCompleted = true;
        }
    }

    /**
     * Resolves the record validation mode from the given configuration.
     *
     * <p>The value is trimmed and matched case-insensitively; an absent or blank value means the {@code fail}
     * default. Any other value is a configuration error and fails fast, so a typo cannot silently change the
     * validation semantics.</p>
     *
     * @param config the Storm configuration.
     * @return the resolved record validation mode: {@code "none"}, {@code "warn"}, or {@code "fail"}.
     * @throws PersistenceException if the configured value is not a valid mode.
     */
    static String resolveRecordMode(StormConfig config) {
        String recordMode = config.getProperty(VALIDATION_RECORD_MODE, null);
        if (recordMode == null || recordMode.isBlank()) {
            return "fail";
        }
        String mode = recordMode.trim().toLowerCase(ROOT);
        if (!"none".equals(mode) && !"warn".equals(mode) && !"fail".equals(mode)) {
            throw new PersistenceException("Invalid %s: '%s'. Valid values are: none, warn, fail."
                    .formatted(VALIDATION_RECORD_MODE, recordMode.trim()));
        }
        return mode;
    }

    /**
     * Checks if the provided type is a valid primary key type.
     *
     * <p><strong>Note:</strong> Floating point types are prohibited as primary keys.</p>
     *
     * @param type the type to check.
     * @return true if the type is a valid primary key type, false otherwise.
     */
    private static boolean isValidPrimaryKeyType(Class<?> type) {
        if (!(type == boolean.class || type == Boolean.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == String.class
                || type == UUID.class
                || type == BigInteger.class
                || type.isEnum()
                || type == Ref.class)
        ) {
            return false;
        }
        return true;
    }

    /**
     * Validates the provided record type for ORM mapping.
     *
     * @param dataType the record type to validate.
     * @param requirePrimaryKey true if a primary key is required, false otherwise.
     * @param duplicates a set to track duplicate record types to ensure no cycles.
     * @return an empty string if the record type is valid, otherwise an error message.
     */
    private static String validate(Class<? extends Data> dataType, boolean requirePrimaryKey, Set<Class<?>> duplicates) {
        if (!duplicates.add(dataType)) {
            return "";
        }
        boolean pkFound = false;
        boolean versionFound = false;
        RecordType type = getRecordType(dataType);
        var dataGraph = validateDataGraph(dataType);
        if (dataGraph.isPresent()) {
            return dataGraph.get();
        }
        for (var field : type.fields()) {
            if (getORMConverter(field).isPresent()) {
                for (var annotation : List.of(PK.class, FK.class, Inline.class)) {
                    if (field.isAnnotationPresent(annotation)) {
                        return "Converted field must not be @%s: %s.%s.".formatted(annotation.getSimpleName(), dataType.getSimpleName(), field.name());
                    }
                }
                continue;
            }
            PK pk = field.getAnnotation(PK.class);
            FK fk = field.getAnnotation(FK.class);
            Inline inline = field.getAnnotation(Inline.class);
            Version version = field.getAnnotation(Version.class);
            if (pk != null) {
                if (pkFound) {
                    return "Multiple primary keys found: %s.".formatted(dataType.getSimpleName());
                }
                pkFound = true;
                // Generation only applies to inserted types; projections and plain data classes are never
                // inserted, so a declared strategy is meaningless rather than wrong there.
                if (fk != null && Entity.class.isAssignableFrom(dataType) && pk.generation() != GenerationStrategy.NONE) {
                    return "Foreign key must not be an auto-generated primary key: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                }
                if (isRecord(field.type())) {
                    if (fk == null) {
                        for (var nestedField : getRecordType(field.type()).fields()) {
                            if (!isValidPrimaryKeyType(nestedField.type())) {
                                return "Invalid primary key type %s.%s.%s.".formatted(dataType.getSimpleName(), field.name(), nestedField.name());
                            }
                        }
                    }
                } else if (!isValidPrimaryKeyType(field.type())) {
                    return "Invalid primary key type: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                }
            }
            if (fk != null) {
                if (inline != null) {
                    return "Foreign key must not be inlined: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                }
                Class<? extends Data> fkType;
                if (Data.class.isAssignableFrom(field.type())) {
                    fkType = (Class<? extends Data>) field.type();
                } else if (Ref.class.isAssignableFrom(field.type())) {
                    try {
                        fkType = getRefDataType(field);
                        getRefPkType(field);    // Validate Ref's PK type.
                    } catch (SqlTemplateException e) {
                        return e.getMessage();
                    }
                } else {
                    return "Foreign key must either be a Data type or a Ref: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                }
                // Sealed types (polymorphic FK, single-table, joined) are validated separately.
                if (fkType.isSealed() && RecordReflection.detectSealedPattern(fkType).isPresent()) {
                    String fkMessage = doValidateDataType(fkType, RecordReflection.isSealedEntity(fkType));
                    if (!fkMessage.isEmpty()) {
                        return fkMessage;
                    }
                } else {
                    String message = validate(fkType, true, duplicates);
                    if (!message.isEmpty()) {
                        return message + " Should %s.%s be marked as @FK?".formatted(field.type().getSimpleName(), field.name());
                    }
                }
            }
            if (inline != null) {
                if (!isRecord(field.type())) {
                    return "Inlined component must be a record type: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                }
            }
            if (version != null) {
                if (versionFound) {
                    return "Multiple @Version annotations found: %s.".formatted(dataType.getSimpleName());
                }
                versionFound = true;
            }
            if (Ref.class.isAssignableFrom(field.type()) && !field.isAnnotationPresent(FK.class) && !field.isAnnotationPresent(PK.class)) {
                return "Ref fields must be marked as @FK: %s.%s.".formatted(dataType.getSimpleName(), field.name());
            }
            if (isRecord(field.type())) {
                if (!field.isAnnotationPresent(FK.class)) {
                    // Data classes are allowed to wrap entities and projections (without @FK), but Entities and Projections must refer to them as @FK or @Inline.
                    if (Entity.class.isAssignableFrom(dataType)) {
                        if (Entity.class.isAssignableFrom(field.type())) {
                            return "Entities inside entities must be marked as @FK or @Inline: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                        }
                        if (Projection.class.isAssignableFrom(field.type())) {
                            return "Projections inside entities must be marked as @FK or @Inline: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                        }
                        if (findPkField(field.type()).isPresent()) {
                            return "Inlined field must not have a primary key: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                        }
                    } else if (Projection.class.isAssignableFrom(field.type())) {
                        if (Entity.class.isAssignableFrom(field.type())) {
                            return "Entities inside projections must be marked as @FK or @Inline: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                        }
                        if (Projection.class.isAssignableFrom(field.type())) {
                            return "Projections inside projections must be marked as @FK or @Inline: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                        }
                        if (findPkField(field.type()).isPresent()) {
                            return "Inlined field must not have a primary key: %s.%s.".formatted(dataType.getSimpleName(), field.name());
                        }
                    }
                }
            }
        }
        if (requirePrimaryKey && !pkFound) {
            return "No primary key found for %s.".formatted(dataType.getSimpleName());
        }
        if (Projection.class.isAssignableFrom(dataType)) {
            String idMessage = validateProjectionIdType(dataType, type);
            if (!idMessage.isEmpty()) {
                return idMessage;
            }
        }
        ProjectionQuery projectionQuery = type.getAnnotation(ProjectionQuery.class);
        if (projectionQuery != null) {
            if (!Projection.class.isAssignableFrom(dataType)) {
                return "ProjectionQuery must only be used on records implementing Projection: %s".formatted(dataType.getSimpleName());
            }
            if (projectionQuery.value().isEmpty()) {
                return "ProjectionQuery must specify a query: %s".formatted(dataType.getSimpleName());
            }
        }
        return "";
    }

    /**
     * Validates the type argument a projection supplies for {@code Projection<ID>} against the record's primary
     * key. {@code ID} is the projection's row identity type: the type the id-based repository operations bind and
     * refs carry for the projection. The declaration is only checked where it contradicts the mapped key. A
     * projection without a {@code @PK} field may supply any argument: its row identity can exist in the database
     * without being among the mapped columns, which still supports detached refs, while the id-based repository
     * operations require the mapped key and fail at query time.
     */
    private static String validateProjectionIdType(Class<? extends Data> dataType, RecordType type) {
        Class<?> declaredIdType = RecordReflection.findTypeArgument(dataType, Projection.class).orElse(null);
        if (declaredIdType == null) {
            return "";  // Raw or unresolved declarations cannot be checked.
        }
        RecordField pkField = type.fields().stream()
                .filter(field -> field.isAnnotationPresent(PK.class))
                .findFirst()
                .orElse(null);
        if (pkField == null) {
            return "";
        }
        if (declaredIdType == Void.class) {
            return "Projection %s declares Projection<Void> but has a primary key: %s."
                    .formatted(dataType.getSimpleName(), pkField.name());
        }
        Class<?> rowIdentityType = rowIdentityType(pkField, new HashSet<>());
        if (rowIdentityType == null) {
            return "";  // The key chain itself is rejected by other validations.
        }
        if (declaredIdType != boxed(rowIdentityType)) {
            return "Projection %s declares id type %s but its primary key %s identifies rows by %s."
                    .formatted(dataType.getSimpleName(), declaredIdType.getSimpleName(), pkField.name(),
                            rowIdentityType.getSimpleName());
        }
        return "";
    }

    /**
     * Returns the row identity type of a primary key field: the type the id-based operations bind for the key. A
     * key that is a foreign key identifies rows by the referenced table's key, applied recursively; a plain key
     * identifies rows by its own type. Returns {@code null} when the chain cannot be resolved; the key itself is
     * rejected by other validations in that case.
     */
    private static Class<?> rowIdentityType(RecordField field, Set<Class<?>> visited) {
        if (field.isAnnotationPresent(FK.class) || Ref.class.isAssignableFrom(field.type())) {
            Class<?> target;
            try {
                target = RecordReflection.getFkTargetType(field);
            } catch (SqlTemplateException e) {
                return null;
            }
            if (!visited.add(target)) {
                return null;    // Circular key chain.
            }
            return findPkField(target)
                    .map(targetPkField -> rowIdentityType(targetPkField, visited))
                    .orElse(null);
        }
        return field.type();
    }

    /**
     * Returns the wrapper class for a primitive type; any other type is returned unchanged. Declared type
     * arguments are always reference types, so primitive key fields compare by their wrapper.
     */
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return MethodType.methodType(type).wrap().returnType();
    }

    /**
     * Validates whether the specified record type is valid for ORM mapping.
     *
     * <p>The results of this validation are cached.</p>
     *
     * @param dataType the record type to validate.
     * @param requirePrimaryKey true if a primary key is required, false otherwise.
     * @throws SqlTemplateException if the record type is invalid for ORM mapping.
     */
    static void validateDataType(Class<? extends Data> dataType)
            throws SqlTemplateException {
        validateDataType(dataType, Entity.class.isAssignableFrom(dataType));
    }

    /**
     * Validates whether the specified record type is valid for ORM mapping.
     *
     * <p>The results of this validation are cached.</p>
     *
     * @param dataType the record type to validate.
     * @param requirePrimaryKey true if a primary key is required, false otherwise.
     * @throws SqlTemplateException if the record type is invalid for ORM mapping.
     */
    static void validateDataType(Class<? extends Data> dataType, boolean requirePrimaryKey)
            throws SqlTemplateException {
        if (!Data.class.isAssignableFrom(dataType)) {
            throw new IllegalArgumentException("Not a data type: %s".formatted(dataType.getSimpleName()));
        }
        String message = VALIDATE_RECORD_TYPE_CACHE.get(dataType).computeIfAbsent(
                requirePrimaryKey,
                ignore -> doValidateDataType(dataType, requirePrimaryKey));
        if (!message.isEmpty()) {
            throw new SqlTemplateException(message);
        }
    }

    /**
     * Performs the actual validation without caching. All internal recursive calls go through this method
     * to avoid {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} recursive update issues.
     */
    @SuppressWarnings("unchecked")
    private static String doValidateDataType(Class<? extends Data> dataType, boolean requirePrimaryKey) {
        // Sealed entity interfaces (Single-Table/Joined) are valid even though they are not records.
        // Their subtypes are validated individually.
        if (dataType.isSealed() && RecordReflection.isSealedEntity(dataType)) {
            String hierarchyMessage = RecordReflection.validateSealedHierarchy(dataType);
            if (!hierarchyMessage.isEmpty()) {
                return hierarchyMessage;
            }
            Class<?>[] permitted = dataType.getPermittedSubclasses();
            if (permitted != null) {
                for (Class<?> sub : permitted) {
                    if (Data.class.isAssignableFrom(sub)) {
                        String subMessage = doValidateDataType((Class<? extends Data>) sub, requirePrimaryKey);
                        if (!subMessage.isEmpty()) {
                            return subMessage;
                        }
                    }
                }
            }
            return "";
        }
        // Polymorphic Data interfaces (Polymorphic FK) are valid even though they are not records.
        if (dataType.isSealed() && RecordReflection.isPolymorphicData(dataType)) {
            return RecordReflection.validateSealedHierarchy(dataType);
        }
        return validate(dataType, requirePrimaryKey, new HashSet<>());
    }

    /**
     * Validates that the provided record type does not contain cyclic dependencies. Specifically, it ensures that no
     * record type appears multiple times along any path from the specified {@code recordType}.
     *
     * <p>For example:
     * <ul>
     *     <li>Record A(B, C) and Record B(C) is valid.</li>
     *     <li>Record A(B, C) and Record B(A) is invalid due to a cycle A → B → A.</li>
     * </ul>
     *
     * @param dataType The root Data class to validate. Must not be null.
     * @throws SqlTemplateException if a cycle is detected in the Record graph.
     */
    private static Optional<String> validateDataGraph(Class<? extends Data> dataType) {
        // Initialize an empty set to keep track of the current traversal path.
        Set<RecordType> currentPath = new LinkedHashSet<>();
        // Start the recursive validation with the root record type.
        return validateRecordGraph(getRecordType(dataType), currentPath);
    }

    /**
     * Recursively validates the record graph to detect cycles.
     *
     * Note that this implementation traverses the record graph, which means it includes Record instances, not just
     * Data instances in the graph.
     *
     * @param recordType  The current Record class being validated.
     * @param currentPath The set of Record classes in the current traversal path.
     * @return an empty optional if the record graph is valid, otherwise an optional containing an error message.
     */
    private static Optional<String> validateRecordGraph(RecordType recordType,
                                                        Set<RecordType> currentPath) {
        // Check if the current record type is already in the path (cycle detected).
        if (currentPath.contains(recordType)) {
            String cycle = buildCyclePath(recordType, currentPath);
            if (Data.class.isAssignableFrom(recordType.type())) {
                return Optional.of(("Cycle of non-Ref foreign keys: %s. "
                        + "A foreign key cycle must cross a Ref boundary to be loadable. "
                        + "Mark one of the foreign keys as Ref (for example Ref<%s>) to break the cycle.")
                        .formatted(cycle, recordType.type().getSimpleName()));
            }
            return Optional.of(("Cycle of inline records: %s. "
                    + "An inline record embeds its fields in the enclosing table, so a cycle cannot be modeled.")
                    .formatted(cycle));
        }
        currentPath.add(recordType);
        for (RecordField field : recordType.fields()) {
            if (field.mutable()) {
                return Optional.of("Mutable fields are not allowed: %s.%s.".formatted(recordType.type().getSimpleName(), field.name()));
            }
            if (isRecord(field.type())) {
                // Recursively validate the component record type.
                var path = validateRecordGraph(getRecordType(field.type()), currentPath);
                if (path.isPresent()) {
                    return path;
                }
            }
        }
        // Remove the current record type from the path after processing.
        currentPath.remove(recordType);
        return empty();
    }

    /**
     * Builds a string representation of the cycle for error messaging: the tail of the traversal path from the type
     * the cycle re-enters, closed by naming that type again.
     *
     * @param currentType the record type where the cycle was detected.
     * @param path        the current traversal path leading up to the cycle.
     * @return a string describing the cycle.
     */
    private static String buildCyclePath(RecordType currentType,
                                                  Set<RecordType> path) {
        StringBuilder cyclePath = new StringBuilder();
        boolean inCycle = false;
        for (RecordType type : path) {
            inCycle = inCycle || type.equals(currentType);
            if (inCycle) {
                cyclePath.append(type.type().getSimpleName()).append(" -> ");
            }
        }
        cyclePath.append(currentType.type().getSimpleName());
        return cyclePath.toString();
    }

    /**
     * Validates the parameters of a SQL template.
     *
     * @param parameters the parameters to validate.
     * @throws SqlTemplateException if the parameters are invalid.
     */
    static void validateParameters(List<Parameter> parameters, int expectedPositionalParameters) throws SqlTemplateException {
        validatePositionalParameters(parameters, expectedPositionalParameters);
        validateNamedParameters(parameters);
    }

    /**
     * Validates that the compiled SQL exposes a placeholder for every positional parameter it binds.
     *
     * <p>A value interpolated inside a string literal renders as the literal text {@code '?'}. The driver reads
     * that as a quoted question mark rather than a placeholder, while the value is still bound, so every parameter
     * after it binds one position early. Some drivers reject the statement outright; others run it against
     * silently wrong arguments, which is the worse outcome and the reason this is checked here.</p>
     *
     * @param sql the compiled SQL.
     * @param expectedPositionalParameters the number of positional parameters compilation produced.
     * @param dialect the SQL dialect, used to recognise literals, quoted identifiers and comments.
     * @throws SqlTemplateException if the SQL exposes fewer placeholders than the statement binds.
     */
    static void validatePlaceholders(String sql, int expectedPositionalParameters, SqlDialect dialect)
            throws SqlTemplateException {
        if (expectedPositionalParameters == 0) {
            return;
        }
        // One pass over the compiled SQL counts the placeholders and notes whether a string literal could be
        // hiding one. Only a literal hides a placeholder silently: interpolating into a quoted identifier yields
        // a column named "?", which the database rejects on its own. Generated SQL carries identifier quotes
        // routinely and literals almost never, so this settles the common case without allocating.
        int placeholders = 0;
        boolean literal = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?') {
                placeholders++;
            } else if (c == '\'') {
                literal = true;
            }
        }
        if (literal) {
            String cleared = SqlParser.clearStringLiterals(
                    SqlParser.clearQuotedIdentifiers(SqlParser.removeComments(sql, dialect), dialect), dialect);
            placeholders = 0;
            for (int i = 0; i < cleared.length(); i++) {
                if (cleared.charAt(i) == '?') {
                    placeholders++;
                }
            }
        }
        // More placeholders than parameters is the bind-vars case, where the values arrive per batch.
        if (placeholders >= expectedPositionalParameters) {
            return;
        }
        throw new SqlTemplateException(("The statement binds %d positional parameters but exposes %d placeholders. " +
                "A value interpolated inside a string literal renders as '?', which the driver reads as text rather " +
                "than a placeholder, so the values after it bind one position early. Interpolate the value without " +
                "the quotes around it, as in DATE_FORMAT(date, \\0) rather than DATE_FORMAT(date, '\\0'). SQL: %s")
                .formatted(expectedPositionalParameters, placeholders, sql));
    }

    /**
     * Validates that positional parameters cover all the positions from 1 to n without gaps.
     *
     * @param parameters the parameters to validate.
     * @throws SqlTemplateException if a positional parameter is missing or if there are gaps in the positions.
     */
    private static void validatePositionalParameters(List<Parameter> parameters, int expectedPositionalParameters) throws SqlTemplateException {
        // Positions are small dense integers generated by the compiler (1..n), so a bit set suffices. Non-positive
        // positions cannot occur in compiled statements; they fall back to the boxed path for faithful error
        // reporting.
        java.util.BitSet positions = null;
        int distinct = 0;
        int minPosition = Integer.MAX_VALUE;
        int maxPosition = 0;
        for (Parameter param : parameters) {
            if (param instanceof PositionalParameter pp) {
                int position = pp.position();
                if (position < 1) {
                    validatePositionalParametersBoxed(parameters, expectedPositionalParameters);
                    return;
                }
                if (positions == null) {
                    positions = new java.util.BitSet();
                }
                if (!positions.get(position)) {
                    positions.set(position);
                    distinct++;
                }
                if (position < minPosition) {
                    minPosition = position;
                }
                if (position > maxPosition) {
                    maxPosition = position;
                }
            }
        }
        if (distinct != expectedPositionalParameters) {
            throw new SqlTemplateException("Expected %d positional parameters, but found %d instead.".formatted(expectedPositionalParameters, distinct));
        }
        if (distinct == 0) {
            return;
        }
        if (minPosition != 1) {
            throw new SqlTemplateException("Positional parameters must start at 1, but found %d instead.".formatted(minPosition));
        }
        // Check for consecutive coverage from 1 through maxPosition.
        int missing = positions.nextClearBit(1);
        if (missing <= maxPosition) {
            throw new SqlTemplateException("Missing positional parameter at position %d".formatted(missing));
        }
    }

    /** Fallback for non-positive positions, reporting errors from a fully sorted view of the positions. */
    private static void validatePositionalParametersBoxed(List<Parameter> parameters, int expectedPositionalParameters) throws SqlTemplateException {
        SortedSet<Integer> positionSet = new TreeSet<>();
        for (Parameter param : parameters) {
            if (param instanceof PositionalParameter pp) {
                positionSet.add(pp.position());
            }
        }
        if (positionSet.size() != expectedPositionalParameters) {
            throw new SqlTemplateException("Expected %d positional parameters, but found %d instead.".formatted(expectedPositionalParameters, positionSet.size()));
        }
        if (positionSet.isEmpty()) {
            return;
        }
        int minPos = positionSet.first();
        if (minPos != 1) {
            throw new SqlTemplateException("Positional parameters must start at 1, but found %d instead.".formatted(minPos));
        }
        // Check for consecutive coverage from 1 through maxPos
        int expected = 1;
        for (int pos : positionSet) {
            if (pos != expected) {
                throw new SqlTemplateException("Missing positional parameter at position %d".formatted(expected));
            }
            expected++;
        }
    }

    /**
     * Validates that named parameters are not being used multiple times with varying values.
     *
     * @param parameters the parameters to validate.
     * @throws SqlTemplateException if a named parameter is being used multiple times with varying values.
     */
    private static void validateNamedParameters(List<Parameter> parameters) throws SqlTemplateException {
        // Most statements carry only positional parameters, so the map is created only when the first named
        // parameter appears. A name mapped to null is skipped when comparing occurrences.
        Map<String, Object> seenValues = null;
        for (Parameter parameter : parameters) {
            if (parameter instanceof SqlTemplate.NamedParameter named) {
                if (seenValues == null) {
                    seenValues = new HashMap<>();
                }
                Object dbValue = named.dbValue();
                Object previous = seenValues.putIfAbsent(named.name(), dbValue);
                if (previous != null && !previous.equals(dbValue)) {
                    throw new SqlTemplateException("Named parameter '%s' is being used multiple times with varying values.".formatted(named.name()));
                }
            }
        }
    }

    /**
     * Validates that the given field path names a reference the query can resolve as part of the statement.
     *
     * <p>The path is walked segment by segment so a mistake is reported against the segment that caused it. A path
     * that crosses no reference is rejected: everything it names is already part of the entity graph, so resolving it
     * would be a no-op that reads as a fetch.</p>
     *
     * @param rootType the type the path is relative to.
     * @param path the field path to validate.
     * @throws PersistenceException if the path cannot be resolved as a reference.
     */
    static void validateFetchPath(Class<? extends Data> rootType, String path) {
        if (path.isEmpty()) {
            throw new PersistenceException("Cannot resolve an empty path for %s.".formatted(rootType.getSimpleName()));
        }
        Class<?> current = rootType;
        boolean crossedReference = false;
        StringBuilder walked = new StringBuilder();
        for (String segment : path.split("\\.", -1)) {
            if (!walked.isEmpty()) {
                walked.append('.');
            }
            walked.append(segment);
            if (!isRecord(current)) {
                throw new PersistenceException("Cannot resolve '%s' on %s: '%s' does not hold a record."
                        .formatted(path, rootType.getSimpleName(), walked));
            }
            RecordField field;
            try {
                field = RecordReflection.getRecordField(current, segment);
            } catch (SqlTemplateException e) {
                throw new PersistenceException("Cannot resolve '%s' on %s: no field at '%s'."
                        .formatted(path, rootType.getSimpleName(), walked), e);
            }
            if (Ref.class.isAssignableFrom(field.type())) {
                Class<? extends Data> target;
                try {
                    target = getRefDataType(field);
                } catch (SqlTemplateException e) {
                    throw new PersistenceException("Cannot resolve '%s' on %s.".formatted(path, rootType.getSimpleName()), e);
                }
                if (target.isSealed()) {
                    // A sealed target picks its concrete record per row from a discriminator, so the referenced
                    // columns have no fixed layout to expand the reference into. A polymorphic target has no single
                    // table to join in the first place.
                    throw new PersistenceException("Cannot resolve '%s' on %s: it references the sealed type %s, whose concrete record is chosen per row rather than by a fixed column layout. Fetch it on demand instead."
                            .formatted(path, rootType.getSimpleName(), target.getSimpleName()));
                }
                crossedReference = true;
                current = target;
            } else {
                current = field.type();
            }
        }
        if (!crossedReference) {
            throw new PersistenceException("Cannot resolve '%s' on %s: it crosses no reference, so it is already part of the record the query selects."
                    .formatted(path, rootType.getSimpleName()));
        }
    }

}
