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
package st.orm.core.model;

import lombok.Builder;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;

/**
 * Two foreign keys to one table, both declared as entities. A path through either, such as {@code first.id},
 * must resolve to that key's own column, also where the keyset predicate of a scroll compiles it as a value.
 */
@Builder(toBuilder = true)
public record PetPair(
        @PK Integer id,
        @FK Pet first,
        @FK Pet second
) implements Entity<Integer> {}
