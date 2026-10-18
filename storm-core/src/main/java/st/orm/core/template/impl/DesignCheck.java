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

import static st.orm.core.template.impl.RecordReflection.isRecord;

import st.orm.Data;
import st.orm.Entity;

/**
 * Checks entities against the design budget and reports the ones that exceed it to the advice log, once per entity.
 *
 * <p>What the model alone shows is checked in advance: a read that exceeds the budget does so however the
 * application uses the entity. Every entity the type index lists is checked with the rest of the model when the first
 * template is created; an entity it does not list is checked when its model is first built. Whether a read within
 * the budget is too wide shows only in real use, so the slow statement log advises on it instead.</p>
 *
 * @since 1.15
 */
final class DesignCheck {

    private DesignCheck() {
    }

    /**
     * Checks entities against the design budget, as the first template is created.
     *
     * @param types the data types to check; types other than entities are skipped.
     */
    static void check(Iterable<Class<? extends Data>> types) {
        for (var type : types) {
            check(type);
        }
    }

    /**
     * Checks an entity against the design budget, reporting it to the advice log once where it exceeds it. Does
     * nothing for a type other than an entity, or while the advice log does not report the advice.
     *
     * @param type the type.
     */
    static void check(Class<?> type) {
        if (!Entity.class.isAssignableFrom(type) || type.isInterface() || !AdviceLog.reports(Advice.ENTITY_BUDGET)) {
            return;
        }
        try {
            if (!isRecord(type)) {
                return;
            }
            var graph = EntityGraph.of(type);
            if (graph.overBudget()) {
                AdviceLog.onDesign(Advice.ENTITY_BUDGET, graph, graph.budgetAdvice());
            }
        } catch (RuntimeException e) {
            // Advice never stands in the way of the application; a model it cannot read fails where it is used.
        }
    }
}
