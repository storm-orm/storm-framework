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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.TemplateString.raw;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Data;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.Order;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.PetType;
import st.orm.core.model.Pet_;
import st.orm.core.model.Vet;
import st.orm.core.model.VetView;
import st.orm.core.model.VetView_;
import st.orm.core.model.Vet_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlLog;

/**
 * The scroll request states an ordering and a position; every window comes back in that ordering with the tokens
 * read from the row, whatever the result type.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class KeysetScrollIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private static final Comparator<Owner> BY_LAST_THEN_FIRST_THEN_ID = Comparator.comparing(Owner::lastName)
            .thenComparing(Owner::firstName)
            .thenComparing(Owner::id);

    @Test
    public void sortFieldsOrderBeforeTheKeyEachInItsOwnDirection() {
        var orm = ORMTemplate.of(dataSource);
        var expected = orm.entity(Owner.class).select().getResultList().stream()
                .sorted(Comparator.comparing(Owner::lastName)
                        .thenComparing(Owner::firstName, Comparator.reverseOrder())
                        .thenComparing(Owner::id))
                .map(Owner::id)
                .toList();
        var window = orm.entity(Owner.class)
                .scroll(Scrollable.of(Owner_.id, 10).sortBy(Owner_.lastName).sortByDescending(Owner_.firstName));
        assertEquals(expected, window.content().stream().map(Owner::id).toList());
        assertFalse(window.hasNext());
        assertFalse(window.hasPrevious());
    }

    @Test
    public void descendingKeyOrdersEqualSortValuesByKeyDescending() {
        // Two owners share the last name Davis: with the key descending the higher id comes first.
        var window = ORMTemplate.of(dataSource).entity(Owner.class)
                .scroll(Scrollable.of(Owner_.id, 10).sortBy(Owner_.lastName).descending());
        var davis = window.content().stream().filter(owner -> owner.lastName().equals("Davis")).map(Owner::id).toList();
        assertEquals(2, davis.size());
        assertTrue(davis.get(0) > davis.get(1));
    }

    @Test
    public void windowsWithSortFieldsContinueWhereTheLastOneEnded() {
        var orm = ORMTemplate.of(dataSource);
        var expected = orm.entity(Owner.class).select().getResultList().stream()
                .sorted(BY_LAST_THEN_FIRST_THEN_ID).map(Owner::id).toList();
        var request = Scrollable.of(Owner_.id, 3).sortBy(Owner_.lastName).sortBy(Owner_.firstName);
        var seen = new ArrayList<Integer>();
        var window = orm.entity(Owner.class).scroll(request);
        while (true) {
            window.content().forEach(owner -> seen.add(owner.id()));
            if (!window.hasNext()) {
                break;
            }
            window = orm.entity(Owner.class).scroll(window.next());
        }
        assertEquals(expected, seen);
    }

    @Test
    public void previousWindowComesBackInSortOrderWithItsFlags() {
        var orm = ORMTemplate.of(dataSource);
        var first = orm.entity(Owner.class).scroll(Scrollable.of(Owner_.id, 4).sortBy(Owner_.lastName).sortBy(Owner_.firstName));
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());
        var second = orm.entity(Owner.class).scroll(first.next());
        assertTrue(second.hasPrevious());
        assertTrue(second.hasNext());
        var back = orm.entity(Owner.class).scroll(second.previous());
        assertEquals(first.content(), back.content());
        assertTrue(back.hasNext());
        assertFalse(back.hasPrevious());
        var forwardAgain = orm.entity(Owner.class).scroll(back.next());
        assertEquals(second.content(), forwardAgain.content());
    }

    @Test
    public void beforeTheFirstRowIsEmptyAndTheAnchorFollows() {
        var window = ORMTemplate.of(dataSource).entity(Vet.class).scroll(Scrollable.of(Vet_.id, 3).before(1));
        assertTrue(window.isEmpty());
        assertTrue(window.hasNext());
        assertFalse(window.hasPrevious());
        assertNull(window.<Vet>next());
    }

    @Test
    public void refsCarryTokensReadFromTheRow() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class);
        var request = Scrollable.of(Owner_.id, 4).sortBy(Owner_.lastName);
        var first = owners.selectRef().scroll(request);
        assertEquals(4, first.size());
        assertNotNull(first.<Owner>next());
        var second = owners.scrollRef(first.next());
        assertEquals(4, second.size());
        var expected = owners.select().getResultList().stream()
                .sorted(Comparator.comparing(Owner::lastName).thenComparing(Owner::id))
                .map(Owner::id)
                .toList();
        var seen = new ArrayList<>(first.content().stream().map(Ref::id).toList());
        seen.addAll(second.content().stream().map(Ref::id).toList());
        assertEquals(expected.subList(0, 8), seen);
        var back = owners.scrollRef(second.previous());
        assertEquals(first.content(), back.content());
    }

    record TypeCount(@FK Ref<PetType> type, long count) implements Data {}

    @Test
    public void customSelectTypeCarriesTokensReadFromTheRow() {
        var orm = ORMTemplate.of(dataSource);
        var key = Metamodel.key(Pet_.type);
        var first = orm.selectFrom(Pet.class, TypeCount.class, raw("\0, COUNT(*)", Pet_.type))
                .groupBy(Pet_.type)
                .scroll(Scrollable.of(key, 2));
        assertEquals(2, first.size());
        assertTrue(first.hasNext());
        assertNotNull(first.<Pet>next());
        var second = orm.selectFrom(Pet.class, TypeCount.class, raw("\0, COUNT(*)", Pet_.type))
                .groupBy(Pet_.type)
                .scroll(first.next());
        assertFalse(second.isEmpty());
        var firstTypes = first.content().stream().map(count -> count.type().id()).toList();
        var secondTypes = second.content().stream().map(count -> count.type().id()).toList();
        assertTrue(secondTypes.stream().noneMatch(firstTypes::contains));
        // The cursor string round-trips the reference's key value.
        var fromCursor = orm.selectFrom(Pet.class, TypeCount.class, raw("\0, COUNT(*)", Pet_.type))
                .groupBy(Pet_.type)
                .scroll(Scrollable.of(key, 2).from(first.nextCursor()));
        assertEquals(second.content(), fromCursor.content());
    }

    @Test
    public void referenceSortFieldSortsByTheReferencedKey() {
        var orm = ORMTemplate.of(dataSource);
        var window = orm.entity(Pet.class).scroll(Scrollable.of(Pet_.id, 20).sortBy(Pet_.type));
        var expected = orm.entity(Pet.class).select().getResultList().stream()
                .sorted(Comparator.comparing((Pet pet) -> (Integer) pet.type().id()).thenComparing(Pet::id))
                .map(Pet::id)
                .toList();
        assertEquals(expected, window.content().stream().map(Pet::id).toList());
    }

    @Test
    public void nullableSortFieldIsRefused() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Owner.class)
                .scroll(Scrollable.of(Owner_.id, 5).sortBy(Owner_.telephone)));
        assertTrue(exception.getMessage().contains("NULL"), exception.getMessage());
    }

    @Test
    public void windowsRefuseAStartBeforeARow() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Vet.class)
                .windows(Scrollable.of(Vet_.id, 2).before(4)));
        assertTrue(exception.getMessage().contains("before"), exception.getMessage());
    }

    @Test
    public void positionMustMatchTheOrderingWhenScrolled() {
        var orm = ORMTemplate.of(dataSource);
        // A request built through after() checks the count itself; the canonical constructor does not, so the
        // engine checks it when the request is scrolled.
        var position = Scrollable.of(Metamodel.key(Vet_.id), 4).after(3).position();
        var mismatched = new Scrollable<>(Metamodel.key(Vet_.id), false, List.of(Order.asc(Vet_.lastName)), 4, position);
        var exception = assertThrows(IllegalArgumentException.class, () -> orm.entity(Vet.class).scroll(mismatched));
        assertTrue(exception.getMessage().contains("expected 2 values, got 1"), exception.getMessage());
    }

    @Test
    public void sliceFollowsTheQueryOrderingAndThePageNumber() {
        var orm = ORMTemplate.of(dataSource);
        var first = orm.selectFrom(Vet.class).orderBy(Vet_.id).slice(0, 4);
        assertEquals(List.of(1, 2, 3, 4), first.stream().map(Vet::id).toList());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());
        var rest = orm.selectFrom(Vet.class).orderBy(Vet_.id).slice(Pageable.of(0, 4).next());
        assertEquals(List.of(5, 6), rest.stream().map(Vet::id).toList());
        assertFalse(rest.hasNext());
        assertTrue(rest.hasPrevious());
    }

    @Test
    public void inlineRecordSortFieldIsRefused() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Owner.class)
                .scroll(Scrollable.of(Owner_.id, 5).sortBy(Owner_.address)));
        assertTrue(exception.getMessage().contains("inline record"), exception.getMessage());
    }

    @Test
    public void inlineRecordKeyNeedsTheEntityAsResult() {
        // An inline key is read from the mapped record, which a ref does not carry.
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Owner.class)
                .selectRef()
                .scroll(Scrollable.of(Metamodel.key(Owner_.address), 5)));
        assertTrue(exception.getMessage().contains("result type to be Owner"), exception.getMessage());
    }

    @Test
    public void inlineRecordKeyScrollsTheEntityFromTheRecord() {
        var orm = ORMTemplate.of(dataSource);
        var first = orm.entity(Owner.class).scroll(Scrollable.of(Metamodel.key(Owner_.address), 4));
        assertEquals(4, first.size());
        assertNotNull(first.<Owner>next());
        var second = orm.entity(Owner.class).scroll(first.next());
        assertFalse(second.isEmpty());
        assertTrue(second.hasPrevious());
        var ids = new ArrayList<>(first.content().stream().map(Owner::id).toList());
        ids.addAll(second.content().stream().map(Owner::id).toList());
        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    @Test
    public void deleteQueryCannotBeScrolled() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource).entity(Vet.class)
                .delete()
                .scroll(Scrollable.of(Vet_.id, 2)));
        assertTrue(exception.getMessage().toLowerCase().contains("delete"), exception.getMessage());
    }

    @Test
    public void projectionRepositoryScrollsRefs() {
        var vetViews = ORMTemplate.of(dataSource).projection(VetView.class);
        var first = vetViews.scrollRef(Scrollable.of(VetView_.id, 4));
        assertEquals(List.of(1, 2, 3, 4), first.content().stream().map(ref -> (Integer) ref.id()).toList());
        var second = vetViews.scrollRef(first.next());
        assertEquals(List.of(5, 6), second.content().stream().map(ref -> (Integer) ref.id()).toList());
        assertEquals(first.content(), vetViews.scrollRef(second.previous()).content());
    }

    @Test
    public void scrollReportsItsRowsToTheSqlLog() {
        var orm = ORMTemplate.of(dataSource);
        var scope = SqlLog.open("scroll");
        try {
            orm.entity(Vet.class).scroll(Scrollable.of(Vet_.id, 4));
        } finally {
            scope.close();
        }
        var summary = scope.summary();
        assertEquals(1, summary.statementCount());
        var statement = summary.statements().getFirst();
        // The window reads size + 1 rows to decide hasNext, and reports them exactly.
        assertTrue(statement.exactRows(), statement.toString());
        assertEquals(5, statement.rows(), statement.toString());
    }

    @Test
    public void windowIteratesOverItsContent() {
        var window = ORMTemplate.of(dataSource).entity(Vet.class).scroll(Scrollable.of(Vet_.id, 4));
        var ids = new ArrayList<Integer>();
        for (Vet vet : window) {
            ids.add(vet.id());
        }
        assertEquals(List.of(1, 2, 3, 4), ids);
        assertEquals(4, window.size());
        assertFalse(window.isEmpty());
    }
}
