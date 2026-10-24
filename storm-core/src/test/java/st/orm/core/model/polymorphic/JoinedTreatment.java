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

import static st.orm.Polymorphic.Strategy.JOINED;

import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.Polymorphic;
import st.orm.core.model.Pet;

/**
 * A joined hierarchy whose base table holds a foreign key and whose surgery extension table holds another.
 */
@Discriminator
@Polymorphic(JOINED)
@DbTable("joined_treatment")
public sealed interface JoinedTreatment extends Entity<Integer> permits JoinedCheckup, JoinedSurgery {
    @Override
    Integer id();
    Pet pet();
}
