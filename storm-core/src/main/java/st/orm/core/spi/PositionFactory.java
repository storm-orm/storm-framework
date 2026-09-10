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
package st.orm.core.spi;

import java.util.List;
import st.orm.Position;
import st.orm.core.template.impl.PositionImpl;

/**
 * Builds the position of a scroll request. The foundation reaches this class through {@code PositionHelper}, so
 * {@link st.orm.Scrollable} can state a position while the engine keeps the values it names.
 */
public final class PositionFactory {

    private PositionFactory() {}

    /**
     * Builds the position of a scroll request.
     *
     * @param values the values of the sort fields and the key, in that order; must not contain {@code null}.
     * @param after {@code true} to continue after the row, {@code false} to continue before it.
     * @return the position.
     */
    public static Position position(List<Object> values, boolean after) {
        return new PositionImpl(values, after);
    }
}
