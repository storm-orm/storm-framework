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
package st.orm.spi.postgresql;

import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.core.template.SqlInterceptor.intercept;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.impl.StringTemplates.flatten;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import st.orm.Entity;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.impl.OnConflictEntityRepositoryImpl;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Query;

/**
 * Implementation of {@link EntityRepository} for PostgreSQL.
 */
public class PostgreSQLEntityRepositoryImpl<E extends Entity<ID>, ID>
        extends OnConflictEntityRepositoryImpl<E, ID> {

    public PostgreSQLEntityRepositoryImpl(ORMTemplate ormTemplate, Model<E, ID> model) {
        super(ormTemplate, model);
    }

    @Override
    protected ID doUpsertAndFetchId(E entity) {
        if (generationStrategy != SEQUENCE) {
            return super.doUpsertAndFetchId(entity);
        }
        validateUpsert(entity);
        entityCache().ifPresent(cache -> {
            if (!model.isDefaultPrimaryKey(entity.id())) {
                cache.remove(entity.id());
            }
        });
        var versionAware = new AtomicBoolean();
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String pkName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () -> {
            var query = ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0
                    RETURNING %s""".formatted(pkName), model.type(), entity, onConflictClause(versionAware)))).managed()
                    ;
            return query.getSingleResult(model.primaryKeyType());
        });
    }

    /**
     * Overrides to use SEQUENCE-specific RETURNING clause for batch fetch IDs when applicable.
     */
    @Override
    public List<ID> upsertAndFetchIds(Iterable<E> entities) {
        if (generationStrategy != SEQUENCE) {
            return super.upsertAndFetchIds(entities);
        }
        // SEQUENCE path: use a single query with RETURNING clause instead of batched prepared statements.
        return upsertAndFetchIdsPartitioned(entities,
                (chunk, entityCache) -> upsertPartitionAndFetchIds(chunk, entityCache, this::getUpsertQuery));
    }

    private Query getUpsertQuery(Iterable<E> entities) {
        var versionAware = new AtomicBoolean();
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String pkName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        return intercept(sql -> sql.versionAware(versionAware.getPlain()), () ->
                ormTemplate.query(flatten(raw("""
                    INSERT INTO \0
                    VALUES \0\0
                    RETURNING %s""".formatted(pkName), model.type(), entities, onConflictClause(versionAware)))).managed()
                        );
    }

    @Override
    public ID insertAndFetchId(E entity) {
        if (generationStrategy != SEQUENCE) {
            return super.insertAndFetchId(entity);
        }
        return insertAndFetchIdReturning(entity);
    }
}
