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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import st.orm.Metamodel;
import st.orm.core.template.TemplateString;

/**
 * What a builder asserts about the statement it builds, beside the template: facts the compiler cannot read from the
 * SQL and that the compiled statement carries. They are given to the compile with the template, keyed with it, so a
 * statement shape is compiled once with its facts applied, and cost a build nothing where it asserts none.
 *
 * <p>A subquery's facts are its own statement's, which never runs as one: a subquery embedded in a statement asserts
 * nothing to the statement that embeds it.</p>
 *
 * @param advisories the advice the builder decided, reported with the statement.
 * @param sortedBy the columns a limited result is sorted by, which an index read in their order serves; recorded as
 *                 an index demand.
 * @param select the select list built from a template, whose columns an index can hold, or {@code null} for a
 *               select of an entity; left out of equality, as the statement's own key covers the select clause.
 * @param unconditional whether an UPDATE or DELETE holds no condition, whatever WHERE clause it renders.
 * @since 1.16
 */
public record StatementFacts(List<String> advisories,
                             List<Metamodel<?, ?>> sortedBy,
                             @Nullable TemplateString select,
                             boolean unconditional) {

    /** A statement that asserts nothing. */
    public static final StatementFacts NONE = new StatementFacts(List.of(), List.of(), null, false);

    public StatementFacts {
        advisories = List.copyOf(advisories);
        sortedBy = List.copyOf(sortedBy);
    }

    public boolean isEmpty() {
        return this == NONE;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StatementFacts(
            List<String> advisories_, List<Metamodel<?, ?>> by, TemplateString select_, boolean unconditional_
        )
                && advisories.equals(advisories_)
                && sortedBy.equals(by)
                && (select == null) == (select_ == null)
                && unconditional == unconditional_;
    }

    @Override
    public int hashCode() {
        return Objects.hash(advisories, sortedBy, select != null, unconditional);
    }

    /**
     * Collects the facts of one build. A build that asserts nothing yields {@link #NONE} without allocating.
     */
    static final class Collector {
        private @Nullable List<String> advisories;
        private List<Metamodel<?, ?>> sortedBy = List.of();
        private @Nullable TemplateString select;
        private boolean unconditional;

        void advise(String advisory) {
            if (advisories == null) {
                advisories = new ArrayList<>(2);
            }
            advisories.add(advisory);
        }

        void sortedBy(List<Metamodel<?, ?>> columns) {
            sortedBy = columns;
        }

        void select(TemplateString select) {
            this.select = select;
        }

        void unconditional() {
            unconditional = true;
        }

        StatementFacts build() {
            if (advisories == null && sortedBy.isEmpty() && select == null && !unconditional) {
                return NONE;
            }
            return new StatementFacts(advisories == null ? List.of() : advisories, sortedBy, select, unconditional);
        }
    }
}
