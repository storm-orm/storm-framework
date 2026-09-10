package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.Scrollable;
import st.orm.core.model.Vet;
import st.orm.core.model.Vet_;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests for {@link st.orm.core.template.QueryBuilder} scroll methods,
 * covering edge cases not tested elsewhere: scroll with explicit orderBy,
 * composite scroll with explicit orderBy, and orderByDescendingAny with empty array.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class QueryBuilderScrollIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Scrollable.of(key, size) with explicit orderBy should throw

    @Test
    public void scrollWithKeyThrowsWhenExplicitOrderByIsPresent() {
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(Vet.class)
                        .orderBy(Vet_.lastName)
                        .scroll(Scrollable.of(Vet_.id, 3)));
    }

    // Scrollable.of(key, size).descending() - cursorless descending

    @Test
    public void scrollBeforeCursorlessWithKeyThrowsWhenExplicitOrderByIsPresent() {
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(Vet.class)
                        .orderBy(Vet_.firstName)
                        .scroll(Scrollable.of(Vet_.id, 3).descending()));
    }

    // Scrollable.of(key, size).sortBy(sort) with explicit orderBy should throw

    @Test
    public void scrollCompositeWithKeyAndSortThrowsWhenExplicitOrderByIsPresent() {
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(Vet.class)
                        .orderBy(Vet_.firstName)
                        .scroll(Scrollable.of(Vet_.id, 3).sortBy(Vet_.lastName)));
    }

    // orderByDescendingAny with empty array should throw

    @Test
    public void orderByDescendingAnyWithEmptyArrayThrows() {
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(Vet.class)
                        .orderByDescending());
    }

    // Verify basic keyset pagination behavior with edge case sizes

    @Test
    public void scrollWithKeySizeExactlyMatchingDataShouldHaveNoNext() {
        // There are 6 vets. Requesting exactly 6 should return all with hasNext=false.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 6));
        assertEquals(6, window.content().size());
        assertFalse(window.hasNext());
    }

    @Test
    public void scrollWithKeySizeOneLessThanDataShouldHaveNext() {
        // There are 6 vets. Requesting 5 should return 5 with hasNext=true.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 5));
        assertEquals(5, window.content().size());
        assertTrue(window.hasNext());
    }

    // Composite keyset: full forward pagination

    @Test
    public void compositeScrollAfterReturnsCorrectNextPageAndHasNext() {
        // First page of 2 vets ordered by lastName, id.
        var firstPage = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 2).sortBy(Vet_.lastName));
        assertEquals(2, firstPage.content().size());
        assertTrue(firstPage.hasNext());

        // Second page after the last item of first page.
        Vet lastOfFirstPage = firstPage.content().getLast();
        var secondPage = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 2).sortBy(Vet_.lastName).after(lastOfFirstPage.lastName(), lastOfFirstPage.id()));
        assertEquals(2, secondPage.content().size());
        assertTrue(secondPage.hasNext());

        // Third (last) page.
        Vet lastOfSecondPage = secondPage.content().getLast();
        var thirdPage = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 2).sortBy(Vet_.lastName).after(lastOfSecondPage.lastName(), lastOfSecondPage.id()));
        assertEquals(2, thirdPage.content().size());
        assertFalse(thirdPage.hasNext());
    }

    // Composite keyset: full backward pagination

    @Test
    public void compositeScrollBeforeReturnsCorrectPreviousPage() {
        // Start from the end: first page descending.
        var lastPage = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 2).sortByDescending(Vet_.lastName).descending());
        assertEquals(2, lastPage.content().size());
        assertTrue(lastPage.hasNext());

        // Previous page before the first item of last page.
        Vet firstOfLastPage = lastPage.content().getLast();
        var previousPage = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 2).sortBy(Vet_.lastName).before(firstOfLastPage.lastName(), firstOfLastPage.id()));
        assertEquals(2, previousPage.content().size());
        assertTrue(previousPage.hasNext());
    }

    // scrollAfter with value cursor: verify WHERE condition is applied correctly

    @Test
    public void scrollAfterWithValueCursorExcludesCursorValue() {
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 10).after(3));
        // Only vets with id > 3 should be returned (4, 5, 6).
        assertEquals(3, window.content().size());
        assertTrue(window.content().stream().allMatch(vet -> vet.id() > 3));
    }

    // scrollBefore with value cursor: verify WHERE condition is applied correctly

    @Test
    public void scrollBeforeWithValueCursorExcludesCursorValue() {
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 10).before(4));
        // Only vets with id < 4 should be returned (1, 2, 3), in sort order.
        assertEquals(3, window.content().size());
        assertTrue(window.content().stream().allMatch(vet -> vet.id() < 4));
        assertTrue(window.content().get(0).id() < window.content().get(1).id());
        assertTrue(window.hasNext());
    }

    // scroll with non-positive size should throw

    @Test
    public void scrollWithZeroSizeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(Vet.class)
                        .orderBy(Vet_.id)
                        .slice(0, 0));
    }

    @Test
    public void scrollWithNegativeSizeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(Vet.class)
                        .orderBy(Vet_.id)
                        .slice(0, -1));
    }
}
