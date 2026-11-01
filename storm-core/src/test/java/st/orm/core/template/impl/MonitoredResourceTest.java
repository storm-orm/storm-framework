package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.BaseStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MonitoredResource}.
 *
 * <p>JDK proxy classes are keyed by the ordered interface list, and the proxy shapes below are registered
 * for GraalVM native images through {@link NativeProxies}. These tests pin the exact shapes so a change in interface
 * order fails here instead of at runtime in a native image.</p>
 */
public class MonitoredResourceTest {

    @Test
    public void testStreamProxyShapeIsDeterministic() {
        try (Stream<String> stream = MonitoredResource.wrap(Stream.of("a", "b"))) {
            assertArrayEquals(new Class<?>[] { BaseStream.class, Stream.class },
                    stream.getClass().getInterfaces(),
                    "Stream proxy must implement [BaseStream, Stream] in that order");
        }
    }

    @Test
    public void testDerivedIntStreamProxyShapeIsDeterministic() {
        try (Stream<String> stream = MonitoredResource.wrap(Stream.of("a", "b"))) {
            try (IntStream mapped = stream.mapToInt(String::length)) {
                assertArrayEquals(new Class<?>[] { BaseStream.class, IntStream.class },
                        mapped.getClass().getInterfaces(),
                        "Derived IntStream proxy must implement [BaseStream, IntStream] in that order");
            }
        }
    }

    @Test
    public void everyProxyShapeAMonitoredResourceCreatesIsRegisteredForNativeImages() {
        var registered = NativeProxies.shapes().stream().map(List::of).toList();
        try (Stream<String> stream = MonitoredResource.wrap(Stream.of("a", "b"));
             IntStream mapped = MonitoredResource.wrap(Stream.of("a")).mapToInt(String::length)) {
            for (var proxy : List.<Object>of(stream, mapped)) {
                assertTrue(registered.contains(List.of(proxy.getClass().getInterfaces())),
                        List.of(proxy.getClass().getInterfaces()).toString());
            }
        }
        assertTrue(registered.contains(List.of(MonitoredResource.proxyInterfaces(PreparedQueryImpl.class))));
    }

    @Test
    public void testProxyDelegatesStreamOperations() {
        try (Stream<String> stream = MonitoredResource.wrap(Stream.of("a", "b"))) {
            assertEquals(List.of("A", "B"), stream.map(String::toUpperCase).toList());
        }
    }
}
