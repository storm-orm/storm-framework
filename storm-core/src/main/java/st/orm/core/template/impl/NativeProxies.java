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

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.stream.BaseStream;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * The JDK proxy shapes Storm creates for itself, which a native image must know at build time: the statement a lease
 * hands out where closing it returns a connection, and the queries and streams a monitored resource wraps. A proxy
 * class is keyed by its ordered interface list, so each shape lists its interfaces in the order Storm requests them.
 *
 * @since 1.16
 */
public final class NativeProxies {

    private NativeProxies() {
    }

    /**
     * Returns the proxy shapes, each the ordered interfaces of one proxy class.
     *
     * @return the shapes.
     */
    public static List<Class<?>[]> shapes() {
        return List.of(
                new Class<?>[] {PreparedStatement.class},
                MonitoredResource.proxyInterfaces(PreparedQueryImpl.class),
                new Class<?>[] {BaseStream.class, Stream.class},
                new Class<?>[] {Serializable.class, BaseStream.class, Stream.class},
                new Class<?>[] {BaseStream.class, IntStream.class},
                new Class<?>[] {BaseStream.class, LongStream.class},
                new Class<?>[] {BaseStream.class, DoubleStream.class});
    }
}
