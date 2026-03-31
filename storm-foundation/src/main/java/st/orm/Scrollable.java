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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Represents a scroll request that captures the cursor state needed to fetch a window of results.
 *
 * <p>A {@code Scrollable} is the scrolling counterpart of {@link Pageable}. While a {@code Pageable} navigates by
 * page number, a {@code Scrollable} navigates by cursor position. Scrollable instances are typically obtained from
 * {@link Window#next()} or {@link Window#previous()}, but can also be created directly using
 * the factory methods.</p>
 *
 * <p>The serialized cursor is opaque and URL-safe, but it is not tamper-proof. If the cursor is exposed to
 * untrusted clients, sign or wrap it at a higher layer.</p>
 *
 * @param key the unique key field used for cursor positioning and ordering.
 * @param keyCursor the cursor value for the key field, or {@code null} for the first page.
 * @param sort the non-unique sort field, or {@code null} for single-key scrolling.
 * @param sortCursor the cursor value for the sort field, or {@code null} for single-key scrolling.
 * @param size the maximum number of results per window (must be positive).
 * @param isForward {@code true} for forward scrolling (ascending key order), {@code false} for backward scrolling
 *                  (descending key order).
 * @since 1.11
 */
public record Scrollable<T extends Data>(
        @Nonnull Metamodel.Key<T, ?> key,
        @Nullable Object keyCursor,
        @Nullable Metamodel<T, ?> sort,
        @Nullable Object sortCursor,
        int size,
        boolean isForward) {

    /**
     * Framework-level upper bound for a window size carried inside a cursor. Configurable via the
     * {@code st.orm.scrollable.maxSize} system property. Defaults to 1000. Repository or API layers may choose
     * stricter limits.
     */
    private static final int MAX_SIZE = Integer.getInteger("st.orm.scrollable.maxSize", 1_000);

    public Scrollable {
        requireNonNull(key, "key must not be null.");
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive.");
        }
        if (sort == null && sortCursor != null) {
            throw new IllegalArgumentException("sortCursor requires a sort field.");
        }
        if (sort != null && ((keyCursor == null) != (sortCursor == null))) {
            throw new IllegalArgumentException(
                    "Composite scrolling requires both keyCursor and sortCursor, or neither.");
        }
        // Cursor value types are validated lazily in toCursor(), not here. This allows inline records and other
        // complex types to be used as cursor values for in-memory scrolling (via Window navigation tokens), even
        // though they cannot be serialized to cursor strings.
    }

    /**
     * Creates a scrollable request for the first page in ascending key order.
     *
     * @param key the unique key field.
     * @param size the maximum number of results per window.
     * @param <T> the entity type.
     * @param <E> the key field type.
     * @return a scrollable for the first page.
     */
    public static <T extends Data, E> Scrollable<T> of(@Nonnull Metamodel.Key<T, E> key, int size) {
        return new Scrollable<>(key, null, null, null, size, true);
    }

    /**
     * Creates a scrollable request starting after the given cursor value, in ascending key order.
     *
     * @param key the unique key field.
     * @param keyCursor the cursor value to start after.
     * @param size the maximum number of results per window.
     * @param <T> the entity type.
     * @param <E> the key field type.
     * @return a scrollable starting after the cursor.
     */
    public static <T extends Data, E> Scrollable<T> of(
            @Nonnull Metamodel.Key<T, E> key, @Nonnull E keyCursor, int size) {
        return new Scrollable<>(key, keyCursor, null, null, size, true);
    }

    /**
     * Creates a scrollable request for the first page in ascending key order, sorted by the given field.
     *
     * @param key the unique key field (tiebreaker).
     * @param sort the non-unique sort field.
     * @param size the maximum number of results per window.
     * @param <T> the entity type.
     * @param <E> the key field type.
     * @param <S> the sort field type.
     * @return a scrollable for the first page.
     */
    public static <T extends Data, E, S> Scrollable<T> of(
            @Nonnull Metamodel.Key<T, E> key, @Nonnull Metamodel<T, S> sort, int size) {
        requireNonNull(sort, "sort must not be null.");
        return new Scrollable<>(key, null, sort, null, size, true);
    }

    /**
     * Creates a scrollable request starting after the given cursor values, in ascending key order, sorted by the
     * given field.
     *
     * @param key the unique key field (tiebreaker).
     * @param keyCursor the cursor value for the key field.
     * @param sort the non-unique sort field.
     * @param sortCursor the cursor value for the sort field.
     * @param size the maximum number of results per window.
     * @param <T> the entity type.
     * @param <E> the key field type.
     * @param <S> the sort field type.
     * @return a scrollable starting after the cursor values.
     */
    public static <T extends Data, E, S> Scrollable<T> of(
            @Nonnull Metamodel.Key<T, E> key, @Nonnull E keyCursor,
            @Nonnull Metamodel<T, S> sort, @Nonnull S sortCursor, int size) {
        requireNonNull(sort, "sort must not be null.");
        return new Scrollable<>(key, keyCursor, sort, sortCursor, size, true);
    }

    /**
     * Returns a new scrollable with forward direction. Returns {@code this} if already forward.
     *
     * @return a scrollable with forward direction.
     */
    public Scrollable<T> forward() {
        if (isForward) {
            return this;
        }
        return new Scrollable<>(key, keyCursor, sort, sortCursor, size, true);
    }

    /**
     * Returns a new scrollable with backward direction. Returns {@code this} if already backward.
     *
     * <pre>{@code
     * // First page from the end (descending)
     * var window = repo.scroll(Scrollable.of(User_.id, 20).backward());
     * }</pre>
     *
     * @return a scrollable with backward direction.
     */
    public Scrollable<T> backward() {
        if (!isForward) {
            return this;
        }
        return new Scrollable<>(key, keyCursor, sort, sortCursor, size, false);
    }

    /**
     * Returns a new scrollable with the direction reversed.
     *
     * @return a new scrollable with the opposite direction.
     */
    public Scrollable<T> reverse() {
        return new Scrollable<>(key, keyCursor, sort, sortCursor, size, !isForward);
    }

    /**
     * Returns {@code true} if this scrollable has a cursor position (i.e., is not a first-page request).
     *
     * @return {@code true} if a cursor is set.
     */
    public boolean hasCursor() {
        return keyCursor != null;
    }

    /**
     * Returns {@code true} if this scrollable uses a composite cursor with a separate sort field.
     *
     * @return {@code true} if a sort field is set.
     */
    public boolean isComposite() {
        return sort != null;
    }

    /**
     * Serializes the cursor state of this scrollable into an opaque, URL-safe string. The cursor encodes the cursor
     * values, size, direction, a metamodel fingerprint, and a registry fingerprint.
     *
     * <p>This is useful for REST APIs where the cursor is passed as a query parameter:</p>
     *
     * <pre>{@code
     * // Server: include cursor in response
     * String cursor = window.nextCursor();
     *
     * // Client sends cursor back as query parameter
     * // Server: reconstruct scrollable
     * var scrollable = Scrollable.fromCursor(User_.id, cursor);
     * var next = repo.scroll(scrollable);
     * }</pre>
     *
     * @return a URL-safe Base64-encoded cursor string.
     * @throws IllegalStateException if a cursor value type is unsupported or serialization fails.
     * @since 1.11
     */
    public String toCursor() {
        return CursorHelper.toCursor(metamodelFingerprint(key, sort), isForward, size, keyCursor, sortCursor);
    }

    /**
     * Deserializes a cursor string (produced by {@link #toCursor()}) into a {@code Scrollable} for single-key
     * scrolling.
     *
     * @param key the unique key field (must match the key used when the cursor was created).
     * @param cursor the cursor string.
     * @param <T> the entity type.
     * @param <E> the key field type.
     * @return a scrollable reconstructed from the cursor.
     * @throws IllegalArgumentException if the cursor string is invalid.
     * @since 1.11
     */
    public static <T extends Data, E> Scrollable<T> fromCursor(
            @Nonnull Metamodel.Key<T, E> key, @Nonnull String cursor) {
        return fromCursor(key, null, cursor);
    }

    /**
     * Deserializes a cursor string (produced by {@link #toCursor()}) into a {@code Scrollable} for composite
     * scrolling with a sort field.
     *
     * @param key the unique key field (must match the key used when the cursor was created).
     * @param sort the sort field, or {@code null} for single-key scrolling.
     * @param cursor the cursor string.
     * @param <T> the entity type.
     * @param <E> the key field type.
     * @param <S> the sort field type.
     * @return a scrollable reconstructed from the cursor.
     * @throws IllegalArgumentException if the cursor string is invalid.
     * @since 1.11
     */
    public static <T extends Data, E, S> Scrollable<T> fromCursor(
            @Nonnull Metamodel.Key<T, E> key,
            @Nullable Metamodel<T, S> sort,
            @Nonnull String cursor) {
        requireNonNull(key, "key must not be null.");
        requireNonNull(cursor, "cursor must not be null.");
        int metamodelFingerprint = metamodelFingerprint(key, sort);
        Class<?> keyFieldType = key.fieldType();
        Class<?> sortFieldType = sort != null ? sort.fieldType() : null;
        Object[] result = CursorHelper.fromCursor(metamodelFingerprint, cursor, keyFieldType, sortFieldType);
        boolean isForward = (boolean) result[0];
        int size = (int) result[1];
        validateCursorSize(size);
        Object keyCursor = result[2];
        Object sortCursor = result[3];
        if (sort == null && sortCursor != null) {
            throw new IllegalArgumentException("Invalid cursor: sortCursor present but no sort field provided.");
        }
        return new Scrollable<>(key, keyCursor, sort, sortCursor, size, isForward);
    }

    /**
     * Validates the size extracted from a deserialized cursor. This limit only applies to cursors from untrusted
     * clients, not to programmatic usage via {@link #of}.
     */
    private static void validateCursorSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Invalid cursor: size must be positive.");
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("Invalid cursor: size must not exceed " + MAX_SIZE + ".");
        }
    }

    /**
     * Produces a stable metamodel fingerprint from the key and sort paths using the Metamodel API.
     */
    private static int metamodelFingerprint(@Nonnull Metamodel.Key<?, ?> key, @Nullable Metamodel<?, ?> sort) {
        if (sort == null) {
            return key.fieldPath().hashCode();
        }
        return Objects.hash(key.fieldPath(), sort.fieldPath());
    }
}
