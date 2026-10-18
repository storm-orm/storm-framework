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
package st.orm.template.impl;

import static java.util.Objects.requireNonNull;
import static st.orm.template.impl.StringTemplates.convert;

import st.orm.BindVars;
import st.orm.Data;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.template.Model;
import st.orm.template.Query;
import st.orm.template.QueryBuilder;
import st.orm.template.QueryTemplate;


public class QueryTemplateImpl implements QueryTemplate {
    private final st.orm.core.template.QueryTemplate core;

    public QueryTemplateImpl(st.orm.core.template.QueryTemplate core) {
        this.core = requireNonNull(core, "core");
    }

    @Override
    public BindVars createBindVars() {
        return core.createBindVars();
    }

    @Override
    public <T extends Data, ID> Ref<T> ref(Class<T> type, ID id) {
        return core.ref(type, id);
    }

    @Override
    public <T extends Data, ID> Ref<T> ref(T record, ID id) {
        return core.ref(record, id);
    }

    @Override
    public <T extends Data, ID> Model<T, ID> model(Class<T> type) {
        //noinspection unchecked
        return new ModelImpl<>((st.orm.core.template.impl.ModelImpl<T, ID>) core.model(type));
    }

    @Override
    public <T extends Data, ID> Model<T, ID> model(Class<T> type, boolean requirePrimaryKey) {
        //noinspection unchecked
        return new ModelImpl<>((st.orm.core.template.impl.ModelImpl<T, ID>) core.model(type, requirePrimaryKey));
    }

    @Override
    public <T extends Data, R> QueryBuilder<T, R, Object> selectFrom(Class<T> fromType, Class<R> selectType, StringTemplate template) {
        return new QueryBuilderImpl<>(core.selectFrom(fromType, selectType, convert(template)));
    }

    @Override
    public <T extends Data> QueryBuilder<T, ?, ?> deleteFrom(Class<T> fromType) {
        return new QueryBuilderImpl<>(core.deleteFrom(fromType));
    }

    @Override
    public <T extends Data> QueryBuilder<T, ?, ?> updateFrom(Class<T> fromType) {
        return new QueryBuilderImpl<>(core.updateFrom(fromType));
    }

    @Override
    public Query query(String query) {
        return new QueryImpl(core.query(query));
    }

    @Override
    public Query query(StringTemplate template) throws PersistenceException {
        return new QueryImpl(core.query(convert(template)));
    }

    @Override
    public <T extends Data> QueryBuilder<T, ?, ?> subquery(Class<T> fromType) {
        return new QueryBuilderImpl<>(core.subquery(fromType));
    }

    @Override
    public <T extends Data, R extends Data> QueryBuilder<T, ?, ?> subquery(Class<T> fromType, Class<R> selectType) {
        return new QueryBuilderImpl<>(core.subquery(fromType, selectType));
    }

    @Override
    public <T extends Data> QueryBuilder<T, ?, ?> subquery(Class<T> fromType, StringTemplate template) {
        return new QueryBuilderImpl<>(core.subquery(fromType, convert(template)));
    }
}
