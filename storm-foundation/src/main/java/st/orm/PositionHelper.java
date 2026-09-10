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
import java.util.List;

/**
 * Bridges the building of a position to storm-core, so the foundation carries the type and the engine carries the
 * values.
 */
final class PositionHelper {

    private static final Method POSITION_METHOD;

    static {
        try {
            Class<?> factoryClass = Class.forName("st.orm.core.spi.PositionFactory");
            POSITION_METHOD = factoryClass.getMethod("position", List.class, boolean.class);
        } catch (ReflectiveOperationException e) {
            var ex = new ExceptionInInitializerError(
                    "Failed to initialize position building. "
                            + "Please ensure that storm-core is present in the classpath.");
            ex.initCause(e);
            throw ex;
        }
    }

    private PositionHelper() {}

    /**
     * Builds a position from the values of the sort fields and the key, in that order.
     */
    static Position position(List<Object> values, boolean after) {
        try {
            try {
                return (Position) POSITION_METHOD.invoke(null, values, after);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } catch (ReflectiveOperationException e) {
                throw new PersistenceException("Reflection invocation failed for PositionFactory.position.", e);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new PersistenceException(t);
        }
    }
}
