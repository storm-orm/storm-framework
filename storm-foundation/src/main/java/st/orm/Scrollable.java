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

import static java.util.List.copyOf;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A scroll request: an ordering, a window size, and the position to continue from.
 *
 * <p>A {@code Scrollable} is the scrolling counterpart of {@link Pageable}. Where a {@code Pageable} navigates by
 * page number, a {@code Scrollable} navigates by keyset: it names the sort fields, a unique key that breaks ties
 * and makes every row addressable, and optionally the row to continue after or before. The ordering and the size
 * belong to the request; the position is what a {@link Window} hands back as {@link Window#next()} and
 * {@link Window#previous()}, and what a cursor string carries across a network boundary.</p>
 *
 * <pre>{@code
 * // Newest first, tiebreak on id, twenty per window
 * var latest = Scrollable.of(Post_.id, 20).sortByDescending(Post_.createdAt);
 *
 * // Last name, then first name, then id
 * var byName = Scrollable.of(User_.id, 20).sortBy(User_.lastName).sortBy(User_.firstName);
 *
 * // The next request from a client's cursor string, with the size the client asks for
 * var next = Scrollable.of(User_.id, size).sortBy(User_.lastName).from(cursor);
 * }</pre>
 *
 * <p>The serialized cursor is opaque and URL-safe, but it is not tamper-proof. If the cursor is exposed to
 * untrusted clients, sign or wrap it at a higher layer.</p>
 *
 * @param key the unique key that breaks ties and addresses a row; it orders last.
 * @param keyDescending {@code true} to order the key descending.
 * @param sort the sort fields that order before the key, in precedence order; each carries its own direction.
 * @param size the maximum number of results per window (must be positive).
 * @param position the row to continue after or before, or {@code null} to start at the beginning.
 * @since 1.11
 */
public record Scrollable<T extends Data>(
        Metamodel.Key<T, ?> key,
        boolean keyDescending,
        List<Order> sort,
        int size,
        @Nullable Position position) {

    public Scrollable {
        requireNonNull(key, "key must not be null.");
        sort = copyOf(sort);
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive.");
        }
    }

    /**
     * Creates a request for the first window, ordered by the key ascending.
     *
     * @param key the unique key field.
     * @param size the maximum number of results per window.
     * @param <T> the entity type.
     * @return the request.
     */
    public static <T extends Data> Scrollable<T> of(Metamodel.Key<T, ?> key, int size) {
        return new Scrollable<>(key, false, List.of(), size, null);
    }

    /**
     * Returns this request with the key ordered descending. Sort fields keep their own direction.
     *
     * <pre>{@code
     * // Newest ids first
     * var latest = users.scroll(Scrollable.of(User_.id, 20).descending());
     * }</pre>
     *
     * @return the request with the key descending.
     * @since 1.14
     */
    public Scrollable<T> descending() {
        return new Scrollable<>(key, true, sort, size, position);
    }

    /**
     * Returns this request with an ascending sort field appended before the key.
     *
     * @param field the field to sort by; must not allow NULL values.
     * @return the request with the sort field added.
     * @since 1.14
     */
    public Scrollable<T> sortBy(Metamodel<T, ?> field) {
        return withSort(Order.asc(field));
    }

    /**
     * Returns this request with a descending sort field appended before the key.
     *
     * @param field the field to sort by; must not allow NULL values.
     * @return the request with the sort field added.
     * @since 1.14
     */
    public Scrollable<T> sortByDescending(Metamodel<T, ?> field) {
        return withSort(Order.desc(field));
    }

    private Scrollable<T> withSort(Order order) {
        if (position != null) {
            throw new IllegalStateException("Add sort fields before the position; the position names their values.");
        }
        var orders = new ArrayList<>(sort);
        orders.add(order);
        return new Scrollable<>(key, keyDescending, orders, size, null);
    }

    /**
     * Returns this request with a different window size.
     *
     * @param size the maximum number of results per window.
     * @return the request with the size.
     * @since 1.14
     */
    public Scrollable<T> size(int size) {
        return new Scrollable<>(key, keyDescending, sort, size, position);
    }

    /**
     * Returns this request continuing after the row with the given values.
     *
     * @param values one value per sort field in sort order, then the key value.
     * @return the request positioned after that row.
     * @since 1.14
     */
    public Scrollable<T> after(Object... values) {
        return at(position(values, true));
    }

    /**
     * Returns this request continuing before the row with the given values. The window comes back in sort order,
     * the same as a window reached by {@link #after(Object...)}.
     *
     * @param values one value per sort field in sort order, then the key value.
     * @return the request positioned before that row.
     * @since 1.14
     */
    public Scrollable<T> before(Object... values) {
        return at(position(values, false));
    }

    private Position position(Object[] values, boolean after) {
        if (values.length != sort.size() + 1) {
            throw new IllegalArgumentException(
                    "A position carries one value per sort field and one for the key: expected %d values, got %d."
                            .formatted(sort.size() + 1, values.length));
        }
        return PositionHelper.position(List.of(values), after);
    }

    private Scrollable<T> at(Position position) {
        return new Scrollable<>(key, keyDescending, sort, size, position);
    }

    /**
     * Returns this request at the position a cursor string carries, as produced by {@link #toCursor()},
     * {@link Window#nextCursor()} or {@link Window#previousCursor()}.
     *
     * <p>The cursor was issued for one ordering, and this request must state the same key, sort fields and
     * directions; a cursor from another ordering is refused.</p>
     *
     * @param cursor the cursor string.
     * @return the request at the cursor's position.
     * @throws InvalidCursorException if the cursor is malformed, from an earlier format, issued for another ordering
     *                                or codec registry, or carries a value of the wrong type.
     * @since 1.14
     */
    public Scrollable<T> from(String cursor) {
        requireNonNull(cursor, "cursor must not be null.");
        return at(CursorHelper.fromCursor(fingerprint(), cursor, valueTypes()));
    }

    /**
     * Returns the complete ordering: the sort fields, then the key with its direction.
     *
     * @return the orders, in precedence.
     * @since 1.14
     */
    public List<Order> orders() {
        var orders = new ArrayList<>(sort);
        orders.add(new Order(key, keyDescending));
        return List.copyOf(orders);
    }

    /**
     * Serializes the position of this request into an opaque, URL-safe string. The cursor carries the position
     * only: a fingerprint of the ordering, whether to continue after or before the row, and the row's values.
     * The size stays with the request, so a client may ask for another size on the next request.
     *
     * @return a URL-safe Base64-encoded cursor string.
     * @throws IllegalStateException if this request has no position, or a value type is unsupported.
     * @since 1.11
     */
    public String toCursor() {
        if (position == null) {
            throw new IllegalStateException("A request for the first window has no position to serialize.");
        }
        return CursorHelper.toCursor(fingerprint(), position);
    }

    private Class<?>[] valueTypes() {
        var types = new Class<?>[sort.size() + 1];
        for (int i = 0; i < sort.size(); i++) {
            types[i] = sort.get(i).field().fieldType();
        }
        types[sort.size()] = key.fieldType();
        return types;
    }

    /**
     * A stable fingerprint of the ordering: the key and sort paths with their directions.
     */
    private int fingerprint() {
        var parts = new ArrayList<Object>();
        for (var order : sort) {
            parts.add(order.field().fieldPath());
            parts.add(order.descending());
        }
        parts.add(key.fieldPath());
        parts.add(keyDescending);
        return Objects.hash(parts.toArray());
    }
}
