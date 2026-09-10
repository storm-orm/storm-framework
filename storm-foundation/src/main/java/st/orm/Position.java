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

/**
 * The row a scroll request continues from, and on which side of it.
 *
 * <p>A position is opaque, like the cursor string that carries it between requests. It comes from
 * {@link Scrollable#after(Object...)}, {@link Scrollable#before(Object...)} or {@link Scrollable#from(String)} and
 * travels with the {@link Scrollable} it belongs to; the engine builds it and reads the row it names when it builds
 * the window. A position {@code after} a row asks for the rows that follow it in sort order; a position
 * {@code before} it asks for the rows that precede it. Either way the window comes back in sort order.</p>
 *
 * @since 1.14
 */
public interface Position {

    /**
     * Returns {@code true} if the request continues after the row, {@code false} if it continues before it.
     *
     * @return {@code true} to continue after the row, {@code false} to continue before it.
     */
    boolean after();
}
