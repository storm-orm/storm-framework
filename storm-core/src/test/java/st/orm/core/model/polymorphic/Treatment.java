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
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.core.model.Pet;

/**
 * A single-table hierarchy whose subtypes declare foreign keys: every treatment references the pet, a checkup may
 * reference the vet, and a surgery references the surgeon.
 */
@Discriminator(column = "kind")
@DbTable("treatment")
public sealed interface Treatment extends Entity<Integer> permits Checkup, Surgery {
    @Override
    Integer id();
    Pet pet();
    LocalDate treatmentDate();
}
