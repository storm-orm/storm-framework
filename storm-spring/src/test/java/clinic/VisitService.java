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

import st.orm.core.template.ORMTemplate;

/**
 * A service outside Storm's packages, so that the call site walker treats the proxy Spring generates around it as
 * application code: the frame it names is the generated class's, which has no source file.
 */
public class VisitService {
    private final ORMTemplate orm;

    public VisitService(ORMTemplate orm) {
        this.orm = orm;
    }

    public void closeVisit() {
        orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
