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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.core.model.DesignModel.Chain0;
import st.orm.core.model.DesignModel.Chain10;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.Visit;

/**
 * An entity's graph counts what its read joins and selects, and names the fields a {@code Ref} could cut.
 */
public class EntityGraphTest {

    @Test
    public void aReadJoinsEveryInlineEntityAndStopsAtARef() {
        var graph = EntityGraph.of(Visit.class);
        // Visit's own row: id, visit_date, description, timestamp; the pet's columns take the foreign key's place.
        assertEquals(4, graph.ownColumns());
        // Pet: id, name, birth_date, type_id (a Ref); Owner: six columns, its address inline; City: two.
        assertEquals(3, graph.joins());
        assertEquals(12, graph.columns());
        // The city an inline address carries joins from the owner's row.
        assertEquals("Visit(Pet(Owner(City)))", graph.graph());
        assertFalse(graph.overBudget());
        assertFalse(graph.wide());
    }

    @Test
    public void aCutRemovesTheSubtreeAndLeavesItsForeignKey() {
        var cuts = EntityGraph.of(Visit.class).cuts();
        // City has no foreign keys of its own, so it can never grow and is never a candidate.
        assertEquals(List.of("Pet.owner", "Visit.pet"), cuts.stream().map(EntityGraph.Cut::field).sorted().toList());
        var owner = cuts.stream().filter(cut -> cut.field().equals("Pet.owner")).findFirst().orElseThrow();
        assertEquals(2, owner.depth());
        assertEquals(2, owner.joins());
        assertEquals(7, owner.columns());
        assertEquals("a Ref for Pet.owner removes 2 joins and 7 columns", owner.describe());
    }

    @Test
    public void aGraphPastTheBudgetIsCutAtTheDeepestEdgeThatBringsItBack() {
        var graph = EntityGraph.of(Chain0.class);
        assertEquals(11, graph.joins());
        assertTrue(graph.overBudget());
        // Chain10 has no foreign keys of its own; the deepest edge that cuts anything is the one into it.
        assertEquals(List.of("Chain9.next"), graph.withinBudget().stream().map(EntityGraph.Cut::field).toList());
        var advice = graph.budgetAdvice();
        assertTrue(advice.startsWith("every read of Chain0 joins 11 tables and selects 22 columns beyond its own, "
                + "past the design budget of 10 joins and 96 columns; a Ref for Chain9.next removes 2 joins and 3 "
                + "columns."), advice);
        assertTrue(advice.endsWith(" See https://orm.st/docs/advice#entity-budget"), advice);
    }

    @Test
    public void aWideReadCarriesAdviceAndANarrowOneDoesNot() {
        var advice = EntityGraph.wideReadAdvice(Chain0.class);
        assertNotNull(advice);
        // Below half the budget takes seven joins off; the deepest edge that does is the one into Chain5.
        assertTrue(advice.contains("where that width is what makes the read slow, a Ref for Chain4.next removes 7 "
                + "joins and 13 columns."), advice);
        assertNull(EntityGraph.wideReadAdvice(Visit.class));
        assertNull(EntityGraph.wideReadAdvice(Pet.class));
        // Within budget and without a field to cut, a read carries nothing.
        assertNull(EntityGraph.wideReadAdvice(Chain10.class));
        assertNull(EntityGraph.wideReadAdvice(Owner.class));
    }

    @Test
    public void theBudgetTheApplicationSetsDecidesWhatIsPastItAndWhatIsWide() {
        try {
            AdviceLog.entityBudget(12, null);
            assertFalse(EntityGraph.of(Chain0.class).overBudget());
            AdviceLog.entityBudget(6, 20);
            var graph = EntityGraph.of(Chain0.class);
            assertTrue(graph.overBudget());
            assertTrue(graph.budgetAdvice().contains("past the design budget of 6 joins and 20 columns;"),
                    graph.budgetAdvice());
            // Half of six joins is three, so a read of Visit is wide now, and its advice is worked out again.
            assertNotNull(EntityGraph.wideReadAdvice(Visit.class));
        } finally {
            AdviceLog.reset();
        }
        assertEquals(10, AdviceLog.entityBudgetJoins());
        assertEquals(96, AdviceLog.entityBudgetColumns());
        assertNull(EntityGraph.wideReadAdvice(Visit.class));
    }

    @Test
    public void aBudgetSetOnOneSideKeepsTheOther() {
        try {
            AdviceLog.entityBudget(null, 120);
            assertEquals(10, AdviceLog.entityBudgetJoins());
            assertEquals(120, AdviceLog.entityBudgetColumns());
        } finally {
            AdviceLog.reset();
        }
    }

    @Test
    public void aBudgetBelowOneIsRefused() {
        var e = assertThrows(IllegalArgumentException.class, () -> AdviceLog.entityBudget(0, null));
        assertTrue(e.getMessage().contains("at least one join and one column"), e.getMessage());
        assertEquals(10, AdviceLog.entityBudgetJoins());
    }
}
