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
package st.orm.core.model.polymorphic;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import st.orm.FK;
import st.orm.PK;
import st.orm.QueryColumn;
import st.orm.Ref;
import st.orm.core.model.Pet;
import st.orm.core.model.Vet;

@QueryColumn(field = "keywords", type = String.class, nullable = true)
public record Checkup(
        @PK Integer id,
        @FK Pet pet,
        LocalDate treatmentDate,
        @Nullable @FK Ref<Vet> vet,
        String notes
) implements Treatment {
}
