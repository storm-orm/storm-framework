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
package st.orm;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when a value breaks a rule of its table: a write the database refuses for a constraint, a null in a
 * non-nullable field at write time, or a value that {@code validateOrThrow} finds too large for its column.
 *
 * <p>The database refuses a write for one constraint at a time, so a violation it reports comes alone;
 * {@code validateOrThrow} reports every violation of the entity at once. Each {@link IntegrityViolation} names the
 * kind of rule and the fields it resolves to, so a caller matches on a metamodel key:</p>
 *
 * <pre>{@code
 * try {
 *     users.insert(user);
 * } catch (IntegrityViolationException e) {
 *     if (e.violations(User_.email).stream().anyMatch(v -> v instanceof IntegrityViolation.Unique)) {
 *         // The email is taken.
 *     }
 *     throw e;
 * }
 * }</pre>
 *
 * <p>A violation the database reported passes through the template's exception mapper like any failure the
 * database reported, with the driver's exception as its cause, so a mapper translates it to its own type.</p>
 *
 * @see IntegrityViolation
 * @since 1.16
 */
public class IntegrityViolationException extends PersistenceException {

    private final List<IntegrityViolation> violations;

    /**
     * Constructs a new exception with the specified detail message and violations.
     *
     * @param message the detail message.
     * @param violations the violations, at least one.
     */
    public IntegrityViolationException(String message, List<IntegrityViolation> violations) {
        this(message, violations, null);
    }

    /**
     * Constructs a new exception with the specified detail message, violations and cause.
     *
     * @param message the detail message.
     * @param violations the violations, at least one.
     * @param cause the failure the database reported, or {@code null} when Storm found the violations itself.
     */
    public IntegrityViolationException(String message, List<IntegrityViolation> violations, @Nullable Throwable cause) {
        super(message, cause);
        this.violations = List.copyOf(requireNonNull(violations, "violations"));
        if (this.violations.isEmpty()) {
            throw new IllegalArgumentException("At least one violation is required.");
        }
    }

    /**
     * Returns the violations. A violation the database reported comes alone; {@code validateOrThrow} reports every
     * violation of the entity.
     *
     * @return the violations, at least one.
     */
    public List<IntegrityViolation> violations() {
        return violations;
    }

    /**
     * Returns the violations that resolve to the given field.
     *
     * @param field the field, as a metamodel key.
     * @return the violations on the field; empty when none resolves to it.
     */
    public List<IntegrityViolation> violations(Metamodel<?, ?> field) {
        requireNonNull(field, "field");
        return violations.stream().filter(violation -> violation.on(field)).toList();
    }
}
