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

import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isRecord;

import java.util.LinkedHashSet;
import java.util.Set;
import st.orm.FK;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.mapping.RecordType;

/**
 * The types a data type references through its foreign keys, its inline records included: what a write of the
 * type depends on having been written before it.
 *
 * @since 1.16
 */
final class ForeignKeyTargets {

    private ForeignKeyTargets() {
    }

    private static final ClassValue<Set<Class<?>>> TARGETS = new ClassValue<>() {
        @Override
        protected Set<Class<?>> computeValue(Class<?> type) {
            var targets = new LinkedHashSet<Class<?>>();
            if (isRecord(type)) {
                collect(getRecordType(type), targets);
            }
            return Set.copyOf(targets);
        }
    };

    /**
     * Returns the types the type's foreign keys reference, through its inline records as well.
     *
     * @param type the data type.
     * @return the referenced types; empty for a type without foreign keys.
     */
    static Set<Class<?>> of(Class<?> type) {
        return TARGETS.get(type);
    }

    private static void collect(RecordType recordType, Set<Class<?>> targets) {
        for (var field : recordType.fields()) {
            if (field.isAnnotationPresent(FK.class)) {
                if (Ref.class.isAssignableFrom(field.type())) {
                    try {
                        targets.add(getRefDataType(field));
                    } catch (SqlTemplateException ignore) {
                        // A reference whose type cannot be resolved names no target; the model refuses it elsewhere.
                    }
                } else {
                    targets.add(field.type());
                }
            } else if (isRecord(field.type())) {
                // An inline record is columns on the same row, foreign keys among them.
                collect(getRecordType(field.type()), targets);
            }
        }
    }
}
