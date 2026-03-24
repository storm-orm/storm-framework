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
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.getRefPkType;
import static st.orm.core.template.impl.RecordReflection.isRecord;

import jakarta.annotation.Nonnull;
import java.math.BigInteger;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
import st.orm.StormConfig;
import st.orm.Version;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.spi.TypeDiscovery;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.SqlTemplateException;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * Helper class for validating record types and named parameters.
 */
@SuppressWarnings("ALL")
final class RecordValidation {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();
    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.validation");

    private RecordValidation() {
    }

    record TypeValidationKey(@Nonnull Class<? extends Data> type, boolean requirePrimaryKey) {}
    private static final Map<TypeValidationKey, String> VALIDATE_RECORD_TYPE_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean validationCompleted = false;

    static void validate() {
        validate(StormConfig.defaults());
    }

    static void validate(@Nonnull StormConfig config) {
        if (validationCompleted) {
            return;
        }
        String recordMode = resolveRecordMode(config);
        synchronized (RecordValidation.class) {
            if (validationCompleted) {
                return;
            }
            if ("none".equalsIgnoreCase(recordMode)) {
                LOGGER.info("Skipping Data type validation. Set storm.validation.record_mode=fail to enable validation.");
                validationCompleted = true;
                return;
            }
            boolean warningsOnly = "warn".equalsIgnoreCase(recordMode);
            LOGGER.info("Validating Data types for correctness.");
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
            } else {
                LOGGER.info("Successfully validated %s Data types for correctness.".formatted(dataTypes.size()));
            }
            validationCompleted = true;
        }
    }

    /**
     * Resolves the record validation mode from the given configuration.
     *
     * @param config the Storm configuration.
     * @return the resolved record validation mode: {@code "none"}, {@code "warn"}, or {@code "fail"}.
     */
    private static String resolveRecordMode(@Nonnull StormConfig config) {
        String recordMode = config.getProperty(StormConfig.VALIDATION_RECORD_MODE, null);
        if (recordMode != null) {
            return recordMode.trim();
        }
        return "fail";
    }

    /**
     * Checks if the provided type is a valid primary key type.
     *
     * <p><strong>Note:</strong> Floating point types are prohibited as primary keys.</p>
     *
     * @param type the type to check.
     * @return true if the type is a valid primary key type, false otherwise.
     */
    private static boolean isValidPrimaryKeyType(@Nonnull Class<?> type) {
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
    private static String validate(@Nonnull Class<? extends Data> dataType, boolean requirePrimaryKey, Set<Class<?>> duplicates) {
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
                if (fk != null && pk.generation() != GenerationStrategy.NONE) {
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
     * Validates whether the specified record type is valid for ORM mapping.
     *
     * <p>The results of this validation are cached.</p>
     *
     * @param dataType the record type to validate.
     * @param requirePrimaryKey true if a primary key is required, false otherwise.
     * @throws SqlTemplateException if the record type is invalid for ORM mapping.
     */
    static void validateDataType(@Nonnull Class<? extends Data> dataType)
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
    static void validateDataType(@Nonnull Class<? extends Data> dataType, boolean requirePrimaryKey)
            throws SqlTemplateException {
        if (!Data.class.isAssignableFrom(dataType)) {
            throw new IllegalArgumentException("Not a data type: %s".formatted(dataType.getSimpleName()));
        }
        String message = VALIDATE_RECORD_TYPE_CACHE.computeIfAbsent(
                new TypeValidationKey(dataType, requirePrimaryKey),
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
    private static String doValidateDataType(@Nonnull Class<? extends Data> dataType, boolean requirePrimaryKey) {
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

    record GraphValidationKey(@Nonnull Class<? extends Data> dataType) {}

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
    private static Optional<String> validateDataGraph(@Nonnull Class<? extends Data> dataType) {
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
    private static Optional<String> validateRecordGraph(@Nonnull RecordType recordType,
                                                        @Nonnull Set<RecordType> currentPath) {
        // Check if the current record type is already in the path (cycle detected).
        if (currentPath.contains(recordType)) {
            return Optional.of("Cyclic dependency detected: %s.".formatted(buildCyclePath(recordType, currentPath)));
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
     * Builds a string representation of the cycle path for error messaging.
     *
     * @param currentType the record type where the cycle was detected.
     * @param path        the current traversal path leading up to the cycle.
     * @return a string describing the cycle path.
     */
    private static String buildCyclePath(@Nonnull RecordType currentType,
                                                  @Nonnull Set<RecordType> path) {
        StringBuilder cyclePath = new StringBuilder();
        for (RecordType type : path) {
            cyclePath.append(type.type().getSimpleName()).append(" -> ");
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
    static void validateParameters(@Nonnull List<Parameter> parameters, int expectedPositionalParameters) throws SqlTemplateException {
        validatePositionalParameters(parameters, expectedPositionalParameters);
        validateNamedParameters(parameters);
    }

    /**
     * Validates that positional parameters cover all the positions from 1 to n without gaps.
     *
     * @param parameters the parameters to validate.
     * @throws SqlTemplateException if a positional parameter is missing or if there are gaps in the positions.
     */
    private static void validatePositionalParameters(@Nonnull List<Parameter> parameters, int expectedPositionalParameters) throws SqlTemplateException {
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
        var namedParameters = parameters.stream()
                .filter(SqlTemplate.NamedParameter.class::isInstance)
                .map(SqlTemplate.NamedParameter.class::cast)
                .collect(Collectors.<SqlTemplate.NamedParameter, String>groupingBy(SqlTemplate.NamedParameter::name));
        for (var entry : namedParameters.entrySet()) {
            var list = entry.getValue();
            if (list.size() > 1) {
                Object first = null;
                for (var value : list) {
                    var v = value.dbValue();
                    if (first == null) {
                        first = v;
                    } else {
                        if (!first.equals(v)) {
                            throw new SqlTemplateException("Named parameter '%s' is being used multiple times with varying values.".formatted(value.name()));
                        }
                    }
                }
            }
        }
    }

}
