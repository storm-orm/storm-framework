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
package st.orm.core.template.impl;

import st.orm.core.template.PreparedQuery;
import st.orm.core.template.Query;

/**
 * Prepares queries for Storm's own callers that use and close them in the same call, as a repository does with the
 * statements it prepares for its writes. A query prepared for the application is watched for a missing close, which
 * costs a proxy around it and a registration with the cleaner; a caller that closes what it prepares in the same
 * method gains nothing from the watch.
 *
 * @since 1.16
 */
public final class ConsumedQueries {

    private ConsumedQueries() {
    }

    /**
     * Prepares the query for a caller that uses and closes it in the same call. A query Storm did not build is
     * prepared as {@link Query#prepare()} prepares it.
     *
     * @param query the query to prepare.
     * @return the prepared query, to be closed by the caller in the call that prepared it.
     */
    public static PreparedQuery prepare(Query query) {
        return query instanceof QueryImpl impl ? impl.prepareConsumed() : query.prepare();
    }
}
