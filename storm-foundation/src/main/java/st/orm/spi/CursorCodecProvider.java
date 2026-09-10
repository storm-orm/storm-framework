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

import java.util.List;

/**
 * Service provider interface for registering custom cursor codecs.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and used to extend the set of types that
 * {@link st.orm.Scrollable#toCursor()} and {@link st.orm.Scrollable#from(String)} can serialize. To register a custom
 * codec, implement this interface and add the fully qualified class name to
 * {@code META-INF/services/st.orm.spi.CursorCodecProvider}.</p>
 *
 * <pre>{@code
 * public class MyCursorCodecProvider implements CursorCodecProvider {
 *     @Override
 *     public List<CursorCodecEntry<?>> codecs() {
 *         return List.of(
 *             new CursorCodecEntry<>(64, UserId.class, new CursorCodec<UserId>() {
 *                 public void write(DataOutputStream out, UserId value) throws IOException {
 *                     out.writeLong(value.value());
 *                 }
 *                 public UserId read(DataInputStream in) throws IOException {
 *                     return new UserId(in.readLong());
 *                 }
 *             })
 *         );
 *     }
 * }
 * }</pre>
 *
 * <p>Custom codecs must use tags in the range [64, 255]. Tags 0-63 are reserved for built-in types.</p>
 *
 * @since 1.11
 */
public interface CursorCodecProvider {

    /**
     * Returns the list of custom cursor codec entries to register.
     *
     * @return codec entries (must not be null or contain null elements).
     */
    List<CursorCodecEntry<?>> codecs();
}
