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
package st.orm.spi.h2;

import jakarta.annotation.Nonnull;
import st.orm.Entity;
import st.orm.core.repository.EntityRepository;
import st.orm.core.spi.EntityRepositoryProvider;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;

/**
 * Implementation of {@link EntityRepositoryProvider} for H2.
 */
public class H2EntityRepositoryProviderImpl implements EntityRepositoryProvider {

    @Override
    public <ID, E extends Entity<ID>> EntityRepository<E, ID> getEntityRepository(
            @Nonnull ORMTemplate ormTemplate,
            @Nonnull Model<E, ID> model) {
        return new H2EntityRepositoryImpl<>(ormTemplate, model);
    }
}
