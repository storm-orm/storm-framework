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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Represents a window of query results from a scrolling operation with {@link Scrollable} navigation tokens.
 *
 * <p>A {@code Window} implements {@link Slice} and provides cursor-based navigation for sequential traversal
 * through large result sets. Use {@link #next()} and {@link #previous()} for typed programmatic navigation,
 * or {@link #nextCursor()} and {@link #previousCursor()} for serialized cursor strings suitable for REST APIs.</p>
 *
 * <pre>{@code
 * Window<User> window = userRepository.scroll(Scrollable.of(User_.id, 20));
 * if (window.hasNext()) {
 *     Window<User> next = userRepository.scroll(window.next());
 * }
 * }</pre>
 *
 * <p>The {@link #next()} and {@link #previous()} navigation tokens are always provided when the window
 * has content, regardless of whether {@code hasNext} or {@code hasPrevious} is {@code true}. This allows developers
 * to follow the cursor even when no more results were detected at query time, which is useful for polling scenarios
 * where new data may appear after the initial query. The {@code hasNext} and {@code hasPrevious} flags are
 * informational: they indicate whether more results existed at the time of the query, but the decision to follow
 * the cursor is left to the developer.</p>
 *
 * @param content the list of results in this window; never contains {@code null} elements.
 * @param hasNext {@code true} if more results existed beyond this window in the scroll direction at query time.
 * @param hasPrevious {@code true} if this window was fetched with a cursor position (i.e., not the first page).
 * @param nextScrollable the scrollable to fetch the next window, or {@code null} if the window is empty.
 * @param previousScrollable the scrollable to fetch the previous window, or {@code null} if the window is empty.
 * @param <R> the result type (e.g., {@code User} for entity queries, {@code Ref<User>} for ref queries).
 * @since 1.11
 */
public record Window<R>(
        @Nonnull List<R> content,
        boolean hasNext,
        boolean hasPrevious,
        @Nullable Scrollable<?> nextScrollable,
        @Nullable Scrollable<?> previousScrollable
) implements Slice<R> {

    public Window {
        content = copyOf(content);
    }

    /**
     * Returns an empty window with no content and no navigation tokens.
     *
     * @param <R> the result type.
     * @return an empty window.
     */
    public static <R> Window<R> empty() {
        return new Window<>(List.of(), false, false, null, null);
    }

    /**
     * Returns a typed scrollable for fetching the next window, or {@code null} if the window is empty.
     *
     * <p>The type parameter {@code T} is inferred from the call-site context, typically from the
     * {@code scroll(Scrollable<T>)} method parameter:</p>
     *
     * <pre>{@code
     * Window<User> next = userRepository.scroll(window.next());
     * }</pre>
     *
     * @param <T> the data type, inferred from context.
     * @return the scrollable for the next window, or {@code null}.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends Data> Scrollable<T> next() {
        return (Scrollable<T>) nextScrollable;
    }

    /**
     * Returns a typed scrollable for fetching the previous window, or {@code null} if the window is empty.
     *
     * <p>The type parameter {@code T} is inferred from the call-site context, typically from the
     * {@code scroll(Scrollable<T>)} method parameter:</p>
     *
     * <pre>{@code
     * Window<User> prev = userRepository.scroll(window.previous());
     * }</pre>
     *
     * @param <T> the data type, inferred from context.
     * @return the scrollable for the previous window, or {@code null}.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends Data> Scrollable<T> previous() {
        return (Scrollable<T>) previousScrollable;
    }

    /**
     * Returns an opaque cursor string for fetching the next window, or {@code null} if there is no next window
     * according to {@link #hasNext()}.
     *
     * <p>This method is a convenience for REST APIs that want to include a cursor only when more results were
     * detected. For polling or streaming use cases where you want to follow the cursor regardless, use
     * {@link #next()} directly.</p>
     *
     * @return the cursor string, or {@code null}.
     * @see Scrollable#toCursor()
     * @see Scrollable#fromCursor(Metamodel.Key, String)
     */
    @Nullable
    public String nextCursor() {
        return hasNext && nextScrollable != null ? nextScrollable.toCursor() : null;
    }

    /**
     * Returns an opaque cursor string for fetching the previous window, or {@code null} if this is the first window
     * according to {@link #hasPrevious()}.
     *
     * <p>This method is a convenience for REST APIs that want to include a cursor only when previous results exist.
     * For use cases where you want to follow the cursor regardless, use {@link #previous()} directly.</p>
     *
     * @return the cursor string, or {@code null}.
     * @see Scrollable#toCursor()
     * @see Scrollable#fromCursor(Metamodel.Key, String)
     */
    @Nullable
    public String previousCursor() {
        return hasPrevious && previousScrollable != null ? previousScrollable.toCursor() : null;
    }
}
