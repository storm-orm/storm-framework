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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Bridges cursor serialization to the codec registry in storm-core, so the foundation carries the types and the
 * engine carries the codecs.
 */
final class CursorHelper {

    private static final Method TO_CURSOR_METHOD;
    private static final Method FROM_CURSOR_METHOD;

    static {
        try {
            Class<?> factoryClass = Class.forName("st.orm.core.spi.CursorFactory");
            TO_CURSOR_METHOD = factoryClass.getMethod("toCursor", int.class, Position.class);
            FROM_CURSOR_METHOD = factoryClass.getMethod("fromCursor", int.class, String.class, Class[].class);
        } catch (ReflectiveOperationException e) {
            var ex = new ExceptionInInitializerError(
                    "Failed to initialize cursor serialization. "
                            + "Please ensure that storm-core is present in the classpath.");
            ex.initCause(e);
            throw ex;
        }
    }

    private CursorHelper() {}

    /**
     * Serializes a position into a Base64 URL-safe string under the fingerprint of its ordering.
     */
    static String toCursor(int orderingFingerprint, Position position) {
        return (String) invoke(TO_CURSOR_METHOD, orderingFingerprint, position);
    }

    /**
     * Deserializes a cursor string issued under the given ordering fingerprint into a position, checking each value
     * against the declared field type where that type is a plain value type.
     */
    static Position fromCursor(int orderingFingerprint, String cursor, Class<?>[] valueTypes) {
        return (Position) invoke(FROM_CURSOR_METHOD, orderingFingerprint, cursor, valueTypes);
    }

    private static Object invoke(Method method, Object... arguments) {
        try {
            try {
                return method.invoke(null, arguments);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } catch (ReflectiveOperationException e) {
                throw new PersistenceException("Reflection invocation failed for CursorFactory." + method.getName() + ".", e);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }
}
