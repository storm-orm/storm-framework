package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LazySupplier}.
 */
public class LazySupplierTest {

    @Test
    public void testLazyInitialization() {
        AtomicInteger callCount = new AtomicInteger(0);
        LazySupplier<String> lazy = new LazySupplier<>(() -> {
            callCount.incrementAndGet();
            return "hello";
        });

        assertEquals(0, callCount.get(), "Supplier should not be called until get()");
        assertEquals("hello", lazy.get());
        assertEquals(1, callCount.get(), "Supplier should be called exactly once");
        assertEquals("hello", lazy.get());
        assertEquals(1, callCount.get(), "Subsequent get() should not invoke supplier again");
    }

    @Test
    public void testLazyStaticFactoryMethod() {
        AtomicInteger callCount = new AtomicInteger(0);
        var lazy = LazySupplier.lazy(() -> {
            callCount.incrementAndGet();
            return 42;
        });

        assertEquals(42, lazy.get());
        assertEquals(1, callCount.get());
        assertEquals(42, lazy.get());
        assertEquals(1, callCount.get(), "Static factory lazy supplier should also only invoke once");
    }

    @Test
    public void testConstructorWithInitialValue() {
        LazySupplier<String> lazy = new LazySupplier<>("initial");

        assertEquals("initial", lazy.get());
        assertTrue(lazy.value().isPresent());
        assertEquals("initial", lazy.value().get());
    }

    @Test
    public void testValueReturnsEmptyBeforeGet() {
        LazySupplier<String> lazy = new LazySupplier<>(() -> "hello");
        assertTrue(lazy.value().isEmpty(), "Value should be empty before get() is called");
    }

    @Test
    public void testValueReturnsPresentAfterGet() {
        LazySupplier<String> lazy = new LazySupplier<>(() -> "hello");
        lazy.get();
        assertTrue(lazy.value().isPresent(), "Value should be present after get()");
        assertEquals("hello", lazy.value().get());
    }

    @Test
    public void testValueReturnsPresentWithInitialValue() {
        LazySupplier<String> lazy = new LazySupplier<>("initial");
        assertTrue(lazy.value().isPresent(), "Value should be present when initial value is provided");
        assertEquals("initial", lazy.value().get());
    }

    @Test
    public void testSupplierReleasedAfterGet() {
        AtomicInteger callCount = new AtomicInteger(0);
        LazySupplier<String> lazy = new LazySupplier<>(() -> {
            callCount.incrementAndGet();
            return "hello";
        });

        assertEquals("hello", lazy.get());
        assertEquals(1, callCount.get(), "Supplier should be called exactly once");

        // After get(), the supplier should be released. Calling get() again returns the cached value.
        assertEquals("hello", lazy.get());
        assertEquals(1, callCount.get(), "Supplier should not be called again after first get()");
    }

    @Test
    public void testRequireNonNullElseGet() {
        assertEquals("existing", LazySupplier.requireNonNullElseGet("existing", () -> "fallback"));
        assertEquals("fallback", LazySupplier.requireNonNullElseGet(null, () -> "fallback"));
    }

    @Test
    public void testRequireNonNullElseGetThrowsOnNullSupplier() {
        assertThrows(NullPointerException.class, () -> LazySupplier.requireNonNullElseGet(null, null));
    }

    @Test
    public void testRequireNonNullElseGetThrowsWhenSupplierReturnsNull() {
        assertThrows(NullPointerException.class, () -> LazySupplier.requireNonNullElseGet(null, () -> null));
    }

    @Test
    void concurrentFirstReadsAllSeeTheValue() throws Exception {
        // Threads that find the value unresolved race the one that resolves it and clears the supplier; each must
        // still see the value, never a cleared supplier.
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 2_000; round++) {
                var lazy = new LazySupplier<>(() -> "value");
                var start = new java.util.concurrent.CountDownLatch(1);
                var reads = new java.util.ArrayList<java.util.concurrent.Future<String>>();
                for (int i = 0; i < threads; i++) {
                    reads.add(pool.submit(() -> {
                        start.await();
                        return lazy.get();
                    }));
                }
                start.countDown();
                for (var read : reads) {
                    assertEquals("value", read.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
