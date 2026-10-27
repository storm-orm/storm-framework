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
package clinic;

import st.orm.core.repository.EntityRepository;

/**
 * A repository whose query lives in a default method, the way an application writes one: the line inside it runs
 * for every caller, so a call site names the caller through it.
 */
public interface CustomerRepository extends EntityRepository<Customer, Integer> {

    default Customer lookUp(int id) {
        return getById(id);
    }
}
