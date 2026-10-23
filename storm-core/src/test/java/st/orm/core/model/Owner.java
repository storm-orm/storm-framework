/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core.model;

import lombok.Builder;
import org.jspecify.annotations.Nullable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.QueryColumn;
import st.orm.Version;

/**
 * Simple domain object representing an owner.
 *
 */
@Builder(toBuilder = true)
@QueryColumn(field = "lastNameFolded", type = String.class)
@QueryColumn(field = "lastNameUpper", type = String.class, function = "upper")
public record Owner(
        @PK Integer id,
        String firstName,
        String lastName,
        Address address,
        @Nullable String telephone,
        @Version int version
) implements Person, Entity<Integer> {
}
