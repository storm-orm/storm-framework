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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_IN;
import static st.orm.core.template.TemplateString.wrap;

import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.core.model.Owner;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.PredicateNode.Term;

/**
 * A predicate that holds for every row or for none, whatever the row holds, ranks no row before another: a list
 * without values, its negation, and a conjunction or disjunction one of them decides. Any other predicate depends on
 * the row.
 */
public class PredicateNodeTest {

    private static final Metamodel<Owner, Integer> ID = Metamodel.of(Owner.class, "id");

    private static PredicateNode comparison(Operator operator, Object values) {
        return new Term(wrap(new ObjectExpression(ID, operator, values)), true);
    }

    @Test
    public void aListWithoutValuesHoldsForNoRowAndItsNegationForEveryRow() {
        assertEquals(Boolean.FALSE, PredicateNode.constant(comparison(IN, List.of())));
        assertEquals(Boolean.TRUE, PredicateNode.constant(comparison(NOT_IN, List.of())));
        assertEquals(Boolean.FALSE, PredicateNode.constant(comparison(IN, new Object[0])));
        assertEquals(Boolean.TRUE, PredicateNode.constant(comparison(NOT_IN, new Object[0])));
    }

    @Test
    public void aComparisonWithAValueDependsOnTheRow() {
        assertNull(PredicateNode.constant(comparison(IN, List.of(1))));
        assertNull(PredicateNode.constant(comparison(NOT_IN, new Object[] {1})));
        // A single value is neither an array nor an iterable.
        assertNull(PredicateNode.constant(comparison(IN, 1)));
        // Only a list comparison is without values; any other operator compares what it is given.
        assertNull(PredicateNode.constant(comparison(EQUALS, List.of())));
    }

    @Test
    public void aTermThatIsNotASingleComparisonDependsOnTheRow() {
        assertNull(PredicateNode.constant(new Term(TemplateString.of("o.id = 1"), true)));
        assertNull(PredicateNode.constant(new Term(wrap(ID), true)));
        assertNull(PredicateNode.constant(new Term(TemplateString.raw("\0 = \0", ID, 1), true)));
    }

    @Test
    public void noRowsDecidesAConjunctionAndEveryRowADisjunction() {
        var noRows = comparison(IN, List.of());
        var everyRow = comparison(NOT_IN, List.of());
        var byRow = comparison(EQUALS, 1);
        assertEquals(Boolean.FALSE, PredicateNode.constant(PredicateNode.and(List.of(byRow, noRows))));
        assertEquals(Boolean.TRUE, PredicateNode.constant(PredicateNode.or(List.of(byRow, everyRow))));
        assertEquals(Boolean.TRUE, PredicateNode.constant(PredicateNode.and(List.of(everyRow, everyRow))));
        assertEquals(Boolean.FALSE, PredicateNode.constant(PredicateNode.or(List.of(noRows, noRows))));
        // The comparison decides where the constant leaves the outcome open.
        assertNull(PredicateNode.constant(PredicateNode.and(List.of(byRow, everyRow))));
        assertNull(PredicateNode.constant(PredicateNode.or(List.of(byRow, noRows))));
    }
}
