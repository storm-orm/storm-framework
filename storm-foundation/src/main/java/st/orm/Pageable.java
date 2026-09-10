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

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Represents a pagination request with a zero-based page number, page size, and optional sort orders.
 *
 * <p>Use {@link #ofSize(int)} to create a request for the first page, or {@link #of(int, int)} for a specific page.
 * Add sort orders with {@link #sortBy(Metamodel)} and {@link #sortByDescending(Metamodel)}. Navigation methods
 * {@link #next()} and {@link #previous()} return new instances for adjacent pages, preserving sort orders.</p>
 *
 * @param pageNumber the zero-based page index (must not be negative).
 * @param pageSize the maximum number of elements per page (must be positive).
 * @param orders the sort orders to apply (may be empty).
 * @since 1.10
 */
public record Pageable(int pageNumber, int pageSize, List<Order> orders) {

    public Pageable {
        orders = copyOf(orders);
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must not be negative.");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive.");
        }
    }

    /**
     * Creates a pageable request for the given page number and page size without sort orders.
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of elements per page.
     */
    public Pageable(int pageNumber, int pageSize) {
        this(pageNumber, pageSize, List.of());
    }

    /**
     * Creates a pageable request for the given page number and page size without sort orders.
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of elements per page.
     * @return a pageable request.
     */
    public static Pageable of(int pageNumber, int pageSize) {
        return new Pageable(pageNumber, pageSize);
    }

    /**
     * Creates a pageable request for the first page with the given page size.
     *
     * @param pageSize the maximum number of elements per page.
     * @return a pageable request for page 0.
     */
    public static Pageable ofSize(int pageSize) {
        return new Pageable(0, pageSize);
    }

    /**
     * Returns a new pageable with an ascending sort order appended for the given field.
     *
     * @param field the metamodel field to sort by.
     * @return a new pageable with the sort order added.
     */
    public Pageable sortBy(Metamodel<?, ?> field) {
        var newOrders = new ArrayList<>(orders);
        newOrders.add(new Order(field, false));
        return new Pageable(pageNumber, pageSize, newOrders);
    }

    /**
     * Returns a new pageable with a descending sort order appended for the given field.
     *
     * @param field the metamodel field to sort by in descending order.
     * @return a new pageable with the sort order added.
     */
    public Pageable sortByDescending(Metamodel<?, ?> field) {
        var newOrders = new ArrayList<>(orders);
        newOrders.add(new Order(field, true));
        return new Pageable(pageNumber, pageSize, newOrders);
    }

    /**
     * Returns a pageable request for the next page, preserving sort orders.
     *
     * @return a pageable for the next page.
     */
    public Pageable next() {
        return new Pageable(pageNumber + 1, pageSize, orders);
    }

    /**
     * Returns a pageable request for the previous page, preserving sort orders, or {@code null} on page 0, where
     * nothing precedes. A request that answered with itself would let a loop step backwards forever.
     *
     * @return a pageable for the previous page, or {@code null} on the first page.
     */
    @Nullable
    public Pageable previous() {
        return pageNumber > 0 ? new Pageable(pageNumber - 1, pageSize, orders) : null;
    }

    /**
     * Returns the offset to use for this page request.
     *
     * @return the offset as a long value.
     */
    public long offset() {
        return (long) pageNumber * pageSize;
    }
}
