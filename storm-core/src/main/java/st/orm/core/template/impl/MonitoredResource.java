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

import static java.util.Comparator.comparing;

import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.PersistenceException;

/**
 * Monitors whether streams are closed.
 */
final class MonitoredResource {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.resource");
    private static final Cleaner CLEANER = Cleaner.create();

    static <T extends AutoCloseable> T wrap(T resource) {
        return wrap(resource, new AtomicInteger());
    }

    private static <T extends AutoCloseable> T wrap(T resource, AtomicInteger openCount) {
        // Capturing the creation stack trace is expensive; only do so when debug logging is enabled.
        Exception createStackTrace = LOGGER.isDebugEnabled() ? new Exception("Create stack trace") : null;
        openCount.getAndIncrement();
        var cleanable = new AtomicReference<Cleanable>();
        //noinspection unchecked
        T proxy = (T) Proxy.newProxyInstance(resource.getClass().getClassLoader(),
                INTERFACES.get(resource.getClass()), (p, method, args) -> {
                    if (method.getName().equals("close")) {
                        // We can safely use plain mode here.
                        openCount.setPlain(-1);
                        cleanable.getPlain().clean();    // Invokes the cleanup method and deregisters the cleanable.
                        return null;
                    }
                    try {
                        Object result = method.invoke(resource, args);
                        if (result == resource) {
                            return p;   // Ensure monitored resource is returned.
                        }
                        if (result instanceof AutoCloseable c) {
                            return MonitoredResource.wrap(c, openCount);
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
        cleanable.setPlain(CLEANER.register(proxy, () -> {
            // This callback will be invoked when the Cleanable is explicitly cleaned, or when the Cleaner is
            // invoked by the garbage collector. It will be invoked at most once.
            int count = openCount.decrementAndGet();
            if (count == 0) {
                if (createStackTrace != null) {
                    LOGGER.warn("Resource was not closed properly.", createStackTrace);
                } else {
                    LOGGER.warn("Resource was not closed properly. Enable debug logging for 'st.orm.resource' to capture creation stack traces.");
                }
            }
            if (count <= 0) {
                try {
                    // Close the resource, also when this call is triggered from the Cleaner.
                    resource.close();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PersistenceException(e);
                }
            }
        }));
        return proxy;
    }

    /**
     * Returns the interfaces the proxy wrapping an instance of the given type implements, in proxy order.
     *
     * @param type the type of the wrapped resource.
     * @return the proxy's interfaces.
     */
    static Class<?>[] proxyInterfaces(Class<?> type) {
        return INTERFACES.get(type).clone();
    }

    /**
     * Interface arrays per class; the walk is only performed once per resource type. The interfaces are
     * sorted by name because JDK proxy classes are keyed by the ordered interface list: a stable order
     * ensures every run requests the same proxy shape, which allows the shape to be registered as
     * GraalVM native-image reachability metadata.
     */
    private static final ClassValue<Class<?>[]> INTERFACES = new ClassValue<>() {
        @Override
        protected Class<?>[] computeValue(Class<?> clazz) {
            Set<Class<?>> allInterfaces = new HashSet<>();
            Class<?> current = clazz;
            while (current != null) {
                allInterfaces.addAll(Arrays.asList(current.getInterfaces()));
                current = current.getSuperclass();
            }
            Class<?>[] interfaces = allInterfaces.toArray(new Class<?>[0]);
            Arrays.sort(interfaces, comparing(Class::getName));
            return interfaces;
        }
    };
}
