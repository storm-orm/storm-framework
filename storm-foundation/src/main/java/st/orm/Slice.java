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

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A slice of query results: the shape a {@link Page}, a {@link Window} and a plain slice share.
 *
 * <p>A slice holds its content in the order it was read and says whether rows existed after and before it at query
 * time. It iterates over its content, so a loop reads {@code for (user : slice)}. A page adds a total count and
 * navigates by page number, a window navigates by keyset, and the plain slice that {@code slice(pageable)} returns
 * is a page without the count query, navigated through its {@link Pageable}.</p>
 *
 * @param <R> the type of the results.
 * @since 1.10
 */
public interface Slice<R> extends Iterable<R> {

    /**
     * Returns the results in this slice, in the order they were read.
     *
     * @return the content; never contains {@code null} elements.
     */
    List<R> content();

    /**
     * Returns {@code true} if rows existed after this slice at query time.
     *
     * @return {@code true} if a next slice exists.
     */
    boolean hasNext();

    /**
     * Returns {@code true} if rows existed before this slice at query time.
     *
     * @return {@code true} if a previous slice exists.
     */
    boolean hasPrevious();

    /**
     * Returns the number of results in this slice.
     *
     * @return the number of results.
     */
    default int size() {
        return content().size();
    }

    /**
     * Returns {@code true} if this slice holds no results.
     *
     * @return {@code true} if the slice is empty.
     */
    default boolean isEmpty() {
        return content().isEmpty();
    }

    @Override
    default Iterator<R> iterator() {
        return content().iterator();
    }

    /**
     * Returns the results as a stream.
     *
     * @return a stream over the content.
     */
    default Stream<R> stream() {
        return content().stream();
    }

    /**
     * Creates a plain slice with the given content and flags.
     *
     * @param content the results.
     * @param hasNext {@code true} if rows existed after the slice.
     * @param hasPrevious {@code true} if rows existed before the slice.
     * @param <R> the type of the results.
     * @return the slice.
     * @since 1.14
     */
    static <R> Slice<R> of(List<R> content, boolean hasNext, boolean hasPrevious) {
        return new SliceImpl<>(content, hasNext, hasPrevious);
    }
}
