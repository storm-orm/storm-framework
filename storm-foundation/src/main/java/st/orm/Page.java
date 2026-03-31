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
import java.util.List;

/**
 * Represents a page of query results for offset-based pagination.
 *
 * <p>A page contains a list of results along with metadata about the total number of matching results, the current
 * page number, and the page size. This allows clients to compute total pages and navigate to arbitrary page
 * positions.</p>
 *
 * <p>Page numbers are zero-based: the first page is page 0.</p>
 *
 * @param content the list of results in this page; never contains {@code null} elements.
 * @param totalCount the total number of matching results across all pages.
 * @param pageable the pagination request that produced this page.
 * @param <R> the type of the results.
 * @since 1.10
 */
public record Page<R>(@Nonnull List<R> content, long totalCount, @Nonnull Pageable pageable) implements Slice<R> {
    public Page {
        content = copyOf(content);
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must not be negative.");
        }
    }

    /**
     * Creates a page with the specified content, total count, page number, and page size.
     *
     * @param content the list of results in this page.
     * @param totalCount the total number of matching results across all pages.
     * @param pageNumber the zero-based index of this page.
     * @param pageSize the maximum number of elements per page.
     */
    public Page(@Nonnull List<R> content, long totalCount, int pageNumber, int pageSize) {
        this(content, totalCount, Pageable.of(pageNumber, pageSize));
    }

    /**
     * Returns the zero-based index of this page.
     *
     * @return the page number.
     */
    public int pageNumber() {
        return pageable.pageNumber();
    }

    /**
     * Returns the maximum number of elements per page.
     *
     * @return the page size.
     */
    public int pageSize() {
        return pageable.pageSize();
    }

    /**
     * Returns the total number of pages.
     *
     * @return the total number of pages needed to hold all elements.
     */
    public int totalPages() {
        return totalCount == 0 ? 0 : (int) Math.ceilDiv(totalCount, pageSize());
    }

    /**
     * Returns {@code true} if there is a next page after this one.
     *
     * @return {@code true} if a next page exists, {@code false} otherwise.
     */
    public boolean hasNext() {
        return pageNumber() < totalPages() - 1;
    }

    /**
     * Returns {@code true} if there is a previous page before this one.
     *
     * @return {@code true} if a previous page exists, {@code false} otherwise.
     */
    public boolean hasPrevious() {
        return pageNumber() > 0;
    }

    /**
     * Returns the {@link Pageable} for the next page, preserving sort orders.
     *
     * @return the pageable for the next page.
     */
    public Pageable nextPageable() {
        return pageable.next();
    }

    /**
     * Returns the {@link Pageable} for the previous page, or the first page if already on page 0. Sort orders are
     * preserved.
     *
     * @return the pageable for the previous page.
     */
    public Pageable previousPageable() {
        return pageable.previous();
    }
}
