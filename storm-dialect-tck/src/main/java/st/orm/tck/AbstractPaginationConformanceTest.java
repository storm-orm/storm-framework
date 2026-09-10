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
package st.orm.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Metamodel;
import st.orm.Pageable;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;
import st.orm.tck.model.Owner;
import st.orm.tck.model.Pet;
import st.orm.tck.model.Vet;

/**
 * Read-in-parts conformance: every dialect renders the keyset predicate, the ordering and the window limit, every
 * window comes back in the request's sort order with its tokens read from the row, and the offset reads, page and
 * slice, render their offset, limit and count.
 *
 * <p>The suite relies on the shared seed data: six vets with ids 1 to 6, ten owners of whom two share the last name
 * Davis, and thirteen pets over six types. Names are capitalised words, so binary and case-insensitive collations
 * agree on their order.</p>
 */
@SuppressWarnings("ALL")
public abstract class AbstractPaginationConformanceTest {

    protected DataSource dataSource;

    @BeforeEach
    final void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static final Metamodel.Key<Vet, Object> VET_ID = Metamodel.key(Metamodel.of(Vet.class, "id"));
    private static final Metamodel.Key<Owner, Object> OWNER_ID = Metamodel.key(Metamodel.of(Owner.class, "id"));
    private static final Metamodel<Owner, Object> OWNER_LAST_NAME = Metamodel.of(Owner.class, "lastName");
    private static final Metamodel<Owner, Object> OWNER_FIRST_NAME = Metamodel.of(Owner.class, "firstName");
    private static final Metamodel.Key<Pet, Object> PET_ID = Metamodel.key(Metamodel.of(Pet.class, "id"));
    private static final Metamodel<Pet, Object> PET_TYPE = Metamodel.of(Pet.class, "type");

    private static final Comparator<Owner> BY_LAST_THEN_FIRST_THEN_ID = Comparator.comparing(Owner::lastName)
            .thenComparing(Owner::firstName)
            .thenComparing(Owner::id);

    private static List<Integer> ids(List<Window<Vet>> windows) {
        return windows.stream().flatMap(window -> window.content().stream()).map(Vet::id).toList();
    }

    @Test
    public void windowsByKeyCoverEveryRowInKeyOrder() {
        var windows = ORMTemplate.of(dataSource).entity(Vet.class).windows(Scrollable.of(VET_ID, 4)).toList();
        assertEquals(2, windows.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), ids(windows));
        assertTrue(windows.get(0).hasNext());
        assertFalse(windows.get(1).hasNext());
    }

    @Test
    public void descendingKeyReversesTheSequence() {
        var windows = ORMTemplate.of(dataSource).entity(Vet.class)
                .windows(Scrollable.of(VET_ID, 4).descending())
                .toList();
        assertEquals(List.of(6, 5, 4, 3, 2, 1), ids(windows));
    }

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
                .scroll(Scrollable.of(OWNER_ID, 10).sortBy(OWNER_LAST_NAME).sortByDescending(OWNER_FIRST_NAME));
        assertEquals(expected, window.content().stream().map(Owner::id).toList());
        assertFalse(window.hasNext());
        assertFalse(window.hasPrevious());
    }

    @Test
    public void windowsWithSortFieldsContinueWhereTheLastOneEnded() {
        var orm = ORMTemplate.of(dataSource);
        var expected = orm.entity(Owner.class).select().getResultList().stream()
                .sorted(BY_LAST_THEN_FIRST_THEN_ID).map(Owner::id).toList();
        var seen = new ArrayList<Integer>();
        var window = orm.entity(Owner.class)
                .scroll(Scrollable.of(OWNER_ID, 3).sortBy(OWNER_LAST_NAME).sortBy(OWNER_FIRST_NAME));
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
        var request = Scrollable.of(OWNER_ID, 4).sortBy(OWNER_LAST_NAME).sortBy(OWNER_FIRST_NAME);
        var first = orm.entity(Owner.class).scroll(request);
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
        var window = ORMTemplate.of(dataSource).entity(Vet.class).scroll(Scrollable.of(VET_ID, 3).before(1));
        assertTrue(window.isEmpty());
        assertTrue(window.hasNext());
        assertFalse(window.hasPrevious());
        assertNull(window.<Vet>next());
    }

    @Test
    public void refsCarryTokensReadFromTheRow() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class);
        var first = owners.selectRef().scroll(Scrollable.of(OWNER_ID, 4).sortBy(OWNER_LAST_NAME));
        assertEquals(4, first.size());
        assertNotNull(first.<Owner>next());
        var second = owners.scrollRef(first.next());
        var expected = owners.select().getResultList().stream()
                .sorted(Comparator.comparing(Owner::lastName).thenComparing(Owner::id))
                .map(Owner::id)
                .toList();
        var seen = new ArrayList<Object>(first.content().stream().map(Ref::id).toList());
        seen.addAll(second.content().stream().map(Ref::id).toList());
        assertEquals(expected.subList(0, 8), seen);
        assertEquals(first.content(), owners.scrollRef(second.previous()).content());
    }

    @Test
    public void referenceSortFieldSortsByTheReferencedKey() {
        var orm = ORMTemplate.of(dataSource);
        // The reference's column carries the referenced key, so a pet sorts by its type id. Read as refs, so the
        // comparison stays on ids and no dialect has to parse the pet's date column.
        var rows = orm.query(TemplateString.of("SELECT id, type_id FROM pet")).getResultList();
        var expected = rows.stream()
                .map(row -> new int[] {((Number) row[0]).intValue(), ((Number) row[1]).intValue()})
                .sorted(Comparator.<int[]>comparingInt(row -> row[1]).thenComparingInt(row -> row[0]))
                .map(row -> row[0])
                .toList();
        var window = orm.entity(Pet.class).selectRef().scroll(Scrollable.of(PET_ID, 20).sortBy(PET_TYPE));
        assertEquals(expected, window.content().stream().map(ref -> ((Number) ref.id()).intValue()).toList());
        assertNotNull(window.<Pet>next());
    }

    @Test
    public void cursorStringContinuesAtTheSamePosition() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class);
        var request = Scrollable.of(OWNER_ID, 4).sortBy(OWNER_LAST_NAME);
        var first = owners.scroll(request);
        var fromToken = owners.scroll(first.next());
        var fromCursor = owners.scroll(request.from(first.nextCursor()));
        assertEquals(fromToken.content(), fromCursor.content());
        assertEquals(first.content(), owners.scroll(request.from(fromCursor.previousCursor())).content());
    }

    @Test
    public void sliceFollowsTheRequestOrderingAndThePageNumber() {
        var orm = ORMTemplate.of(dataSource);
        var vets = orm.entity(Vet.class);
        var pageable = Pageable.ofSize(4).sortBy(Metamodel.of(Vet.class, "id"));
        var first = vets.slice(pageable);
        assertEquals(List.of(1, 2, 3, 4), first.stream().map(Vet::id).toList());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());
        var rest = vets.slice(pageable.next());
        assertEquals(List.of(5, 6), rest.stream().map(Vet::id).toList());
        assertFalse(rest.hasNext());
        assertTrue(rest.hasPrevious());
        // The query's own ordering serves a request without sort orders.
        var ordered = vets.select().orderBy(Metamodel.of(Vet.class, "id")).slice(1, 4);
        assertEquals(rest.content(), ordered.content());
        var refs = vets.sliceRef(Pageable.ofSize(4).sortBy(Metamodel.of(Vet.class, "id")));
        assertEquals(List.of(1, 2, 3, 4), refs.stream().map(ref -> ref.id()).toList());
        assertTrue(refs.hasNext());
    }

    @Test
    public void pageCountsAFullPageAndDerivesTheTotalFromAShortOne() {
        var vets = ORMTemplate.of(dataSource).entity(Vet.class);
        var pageable = Pageable.ofSize(4).sortByDescending(Metamodel.of(Vet.class, "id"));
        var first = vets.page(pageable);
        assertEquals(List.of(6, 5, 4, 3), first.stream().map(Vet::id).toList());
        assertEquals(6, first.totalCount());
        assertEquals(2, first.totalPages());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());
        assertNull(first.previous());
        var last = vets.page(first.next());
        assertEquals(List.of(2, 1), last.stream().map(Vet::id).toList());
        assertEquals(6, last.totalCount());
        assertFalse(last.hasNext());
        assertTrue(last.hasPrevious());
        assertEquals(first.content(), vets.page(last.previous()).content());
        // A page beyond the end is empty and still reports the total.
        var beyond = vets.page(Pageable.of(5, 4).sortByDescending(Metamodel.of(Vet.class, "id")));
        assertTrue(beyond.isEmpty());
        assertEquals(6, beyond.totalCount());
        assertTrue(beyond.hasPrevious());
    }

    @Test
    public void pageRefReadsRefsWithTheSameTotal() {
        var vets = ORMTemplate.of(dataSource).entity(Vet.class);
        var id = Metamodel.of(Vet.class, "id");
        var refs = vets.pageRef(Pageable.ofSize(4).sortBy(id));
        assertEquals(List.of(1, 2, 3, 4), refs.stream().map(ref -> ref.id()).toList());
        assertEquals(6, refs.totalCount());
        assertTrue(refs.hasNext());
        // The query builder pages with its own ordering, and a total the application holds skips the count query.
        var counted = vets.select().orderBy(id).page(Pageable.of(1, 4), 6);
        assertEquals(List.of(5, 6), counted.stream().map(Vet::id).toList());
        assertEquals(6, counted.totalCount());
        assertFalse(counted.hasNext());
    }

    @Test
    public void offsetReadsNeedNoOrderingOfTheirOwn() {
        // An unordered offset read is the documented first page; its order is the database's choice, its shape is
        // not, and every dialect renders it, with a constant ordering where the grammar demands one.
        var vets = ORMTemplate.of(dataSource).entity(Vet.class);
        var page = vets.page(0, 4);
        assertEquals(4, page.size());
        assertEquals(6, page.totalCount());
        assertTrue(page.hasNext());
        var slice = vets.slice(0, 4);
        assertEquals(4, slice.size());
        assertTrue(slice.hasNext());
        var rest = vets.sliceRef(1, 4);
        assertEquals(2, rest.size());
        assertFalse(rest.hasNext());
        assertEquals(2, vets.select().offset(2).limit(2).getResultList().size());
        assertEquals(4, vets.select().offset(2).getResultList().size());
    }
}
