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

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Returns a lazily initialized value. Once the value has been resolved, the supplier is released for garbage
 * collection.
 *
 * @param <T> the type of results supplied by this supplier.
 */
public final class LazySupplier<T> implements Supplier<T> {

    /**
     * Returns a supplier which lazy initializes the value.
     *
     * @param supplier the supplier that provides the value.
     * @return a supplier which lazy initializes the value.
     * @param <T> the type of results supplied by this supplier.
     */
    public static <T> Supplier<T> lazy(Supplier<T> supplier) {
        return new LazySupplier<>(supplier);
    }

    private volatile Supplier<T> supplier;
    private final AtomicReference<T> reference;

    /**
     * Creates a lazy supplier that will invoke the given supplier on the first call to {@link #get()}.
     *
     * @param supplier the supplier that provides the value.
     */
    public LazySupplier(Supplier<T> supplier) {
        this.supplier = requireNonNull(supplier);
        this.reference = new AtomicReference<>();
    }

    /**
     * Creates a lazy supplier with a pre-loaded initial value. The supplier is not retained and {@link #get()} will
     * return the initial value without invoking any supplier.
     *
     * @param initialValue the pre-loaded value.
     */
    public LazySupplier(T initialValue) {
        this.supplier = null;
        this.reference = new AtomicReference<>(requireNonNull(initialValue));
    }

    /**
     * Gets a result. On the first invocation, the supplier is called to produce the value. The supplier is then
     * released for garbage collection. A resolved value is returned with a single volatile read, so shared
     * suppliers on hot paths stay free of write contention.
     *
     * @return a result.
     */
    @Override
    public T get() {
        T result = reference.get();
        if (result != null) {
            return result;
        }
        // Read once: another thread that resolves the value clears the field, and the update below may run after
        // it did. The field is cleared only once the value is set, so a cleared field means the value is there.
        var pending = supplier;
        if (pending == null) {
            return reference.get();
        }
        result = reference.updateAndGet(value -> requireNonNullElseGet(value, pending));
        supplier = null;    // Release the supplier (and its captured context) for GC.
        return result;
    }

    /**
     * Returns the value if it has already been lazily loaded, otherwise an empty optional is returned.
     *
     * @return the value if it has already been lazily loaded, otherwise an empty optional.
     */
    public Optional<T> value() {
        return ofNullable(reference.get());
    }

    /**
     * Returns the first argument if it is non-{@code null} and otherwise returns the non-{@code null} value of
     * {@code supplier.get()}.
     *
     * @param obj an object.
     * @param supplier of a non-{@code null} object to return if the first argument is {@code null}.
     * @param <T> the type of the first argument and return type.
     * @return the first argument if it is non-{@code null} and otherwise the value from {@code supplier.get()} if it is
     * non-{@code null}.
     * @throws NullPointerException if both {@code obj} is null and either the {@code supplier} is {@code null} or
     * the {@code supplier.get()} value is {@code null}.
     */
    static <T> T requireNonNullElseGet(T obj, Supplier<? extends T> supplier) {
        return (obj != null) ? obj
                : requireNonNull(requireNonNull(supplier, "supplier").get(), "supplier.get()");
    }
}
