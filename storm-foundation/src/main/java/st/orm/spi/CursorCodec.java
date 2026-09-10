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
package st.orm.spi;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encodes and decodes a single scalar value for cursor serialization.
 *
 * <p>A {@code CursorCodec} handles the binary representation of one Java type within an opaque cursor string
 * (produced by {@link st.orm.Scrollable#toCursor()} and consumed by {@link st.orm.Scrollable#from(String)}). Storm
 * provides built-in codecs for common scalar types (primitives, {@code String}, {@code UUID}, date/time types,
 * {@code BigDecimal}). Additional codecs can be registered via {@link CursorCodecProvider}.</p>
 *
 * <p>A type is eligible for cursor serialization if it is a scalar mapped value that is orderable in SQL and
 * uniquely reconstructable from its encoded representation. Entity types, collections, JSON objects, and other
 * composite types are not eligible.</p>
 *
 * @param <T> the Java type this codec handles.
 * @since 1.11
 */
public interface CursorCodec<T> {

    /**
     * Writes a non-null value to the output stream.
     *
     * @param out the data output stream.
     * @param value the value to write (never null).
     * @throws IOException if an I/O error occurs.
     */
    void write(DataOutputStream out, T value) throws IOException;

    /**
     * Reads a value from the input stream.
     *
     * @param in the data input stream.
     * @return the deserialized value (never null).
     * @throws IOException if an I/O error occurs or the data is malformed.
     */
    T read(DataInputStream in) throws IOException;
}
