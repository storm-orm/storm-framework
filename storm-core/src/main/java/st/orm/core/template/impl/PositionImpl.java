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

import static java.util.List.copyOf;

import java.util.List;
import st.orm.Position;

/**
 * The position the engine builds for a scroll request: the values of the sort fields and the key, in that order,
 * and the side of the row the request continues on. Applications see it as an opaque {@link Position}.
 *
 * @param values the values of the sort fields and the key, in that order; never contain {@code null}.
 * @param after {@code true} to continue after the row, {@code false} to continue before it.
 */
public record PositionImpl(List<Object> values, boolean after) implements Position {

    public PositionImpl {
        values = copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("A position needs at least the key value.");
        }
    }

    /**
     * Returns the engine's view of a position.
     *
     * @param position the position.
     * @return the position with its values.
     * @throws IllegalArgumentException if the position was not built by the engine.
     */
    public static PositionImpl of(Position position) {
        if (position instanceof PositionImpl impl) {
            return impl;
        }
        throw new IllegalArgumentException(
                "A position comes from Scrollable.after, Scrollable.before or Scrollable.from; got "
                        + position.getClass().getName() + ".");
    }

    @Override
    public String toString() {
        return (after ? "after " : "before ") + values;
    }
}
