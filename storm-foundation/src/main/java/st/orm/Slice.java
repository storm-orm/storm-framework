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

import jakarta.annotation.Nonnull;
import java.util.List;

/**
 * Represents a slice of query results — a chunk of data with informational navigation flags.
 *
 * <p>A {@code Slice} is the common base for both {@link Window} (cursor-based scrolling) and {@link Page}
 * (offset-based pagination). It provides access to the result content and flags indicating whether adjacent
 * results exist, without prescribing a specific navigation mechanism.</p>
 *
 * @param <R> the result type of the slice content.
 * @since 1.11
 */
public interface Slice<R> {

    /**
     * Returns the list of results in this slice. The list is immutable and never contains {@code null} elements.
     *
     * @return the results.
     */
    @Nonnull
    List<R> content();

    /**
     * Returns {@code true} if more results exist beyond this slice in the forward direction.
     *
     * @return whether more results exist.
     */
    boolean hasNext();

    /**
     * Returns {@code true} if results exist before this slice.
     *
     * @return whether previous results exist.
     */
    boolean hasPrevious();
}
