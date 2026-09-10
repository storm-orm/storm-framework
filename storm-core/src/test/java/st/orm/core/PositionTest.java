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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Metamodel;
import st.orm.Position;
import st.orm.Scrollable;
import st.orm.core.model.Vet;
import st.orm.core.model.Vet_;
import st.orm.core.template.impl.PositionImpl;

class PositionTest {

    private static Scrollable<Vet> request() {
        return Scrollable.of(Metamodel.key(Vet_.id), 20).sortBy(Vet_.lastName);
    }

    @Test
    void equalsOnValuesAndSide() {
        var position = request().after("Carter", 3).position();
        assertEquals(request().after("Carter", 3).position(), position);
        assertEquals(request().after("Carter", 3).position().hashCode(), position.hashCode());
        assertNotEquals(request().before("Carter", 3).position(), position);
        assertNotEquals(request().after("Carter", 4).position(), position);
    }

    @Test
    void namesTheSideAndTheValues() {
        assertEquals("after [Carter, 3]", request().after("Carter", 3).position().toString());
        assertEquals("before [Carter, 3]", request().before("Carter", 3).position().toString());
    }

    @Test
    void engineReadsTheValues() {
        assertEquals(List.of("Carter", 3), PositionImpl.of(request().after("Carter", 3).position()).values());
        assertTrue(request().after("Carter", 3).position().after());
    }

    @Test
    void engineRefusesAPositionItDidNotBuild() {
        Position foreign = () -> true;
        var exception = assertThrows(IllegalArgumentException.class, () -> PositionImpl.of(foreign));
        assertTrue(exception.getMessage().contains("Scrollable.after"), exception.getMessage());
    }
}
