package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.Operator.NOT_IN;
import static st.orm.core.template.TemplateString.raw;

import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.Scrollable;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Specialty;
import st.orm.core.model.Vet;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.Vet_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.TemplateBuilder;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class BuilderPreparedStatementIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testBuilderWithJoin() {
        // 3 visits on 2023-01-08: visit ids 7, 8, 9 for pets 4, 1, 2 respectively.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(Visit.class).on(raw("\0.id = \0.pet_id", Pet.class, Visit.class))
                .where(raw("\0.visit_date = \0", Visit.class, LocalDate.of(2023, 1, 8)))
                .getResultList();
         assertEquals(3, list.size());
    }

    @Test
    public void testBuilderWithJoinTemplateFunction() {
        // Same join as testBuilderWithJoin but using TemplateBuilder.create for the ON clause.
        // 3 visits on 2023-01-08.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(Visit.class).on(TemplateBuilder.create(it -> "%s.id = %s.pet_id".formatted(it.interpolate(Pet.class), it.interpolate(Visit.class))))
                .where(raw("\0.visit_date = \0", Visit.class, LocalDate.of(2023, 1, 8)))
                .getResultList();
         assertEquals(3, list.size());
    }

    @Test
    public void testBuilderWithJoinParameter() {
        // ON clause matches pet id=1 (Leo) for all visits. WHERE filters to 3 visits on 2023-01-08.
        // Since the join condition is "p.id = 1", all 3 visits produce the same pet (Leo).
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(Visit.class).on(raw("\0.id = \0", Pet.class, 1))
                .where(raw("\0.visit_date = \0", Visit.class, LocalDate.of(2023, 1, 8)))
                .getResultList();
         assertEquals(3, list.size());
    }

    @Test
    public void testBuilderWithJoinTemplateFunctionParameter() {
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(Visit.class).on(TemplateBuilder.create(it -> "%s.id = %d".formatted(it.interpolate(Pet.class), 1)))
                .where(raw("\0.visit_date = \0", Visit.class, LocalDate.of(2023, 1, 8)))
                .getResultList();
         assertEquals(3, list.size());
    }

    @Test
    public void testBuilderWithJoinTemplateFunctionParameterMetamodel() {
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(Visit.class).on(TemplateBuilder.create(it -> "%s.id = %d".formatted(it.interpolate(Pet.class), 1)))
                .where(raw("\0 = \0", Visit_.visitDate, LocalDate.of(2023, 1, 8)))
                .getResultList();
         assertEquals(3, list.size());
    }

    @Test
    public void testBuilderWithAutoJoin() {
        // Visit id=1 references pet_id=7 (Samantha). Auto-join infers FK between Visit and Pet.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(Visit.class).on(Pet.class)
                .where(it -> it.whereAny(Visit.builder().id(1).build()))
                .getResultList();
         assertEquals(1, list.size());
         assertEquals(7, list.getFirst().id());
    }

    @Test
    public void testBuilderWithAutoJoinInvalidType() {
        // Vet has no FK relationship to Visit; whereAny(Vet) should throw SqlTemplateException.
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Pet.class)
                    .innerJoin(Visit.class).on(Pet.class)
                    .where(it -> it.whereAny(Vet.builder().id(1).build()))
                    .getResultStream()
                    .count();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testBuilderWithCustomSelect() {
        // 8 distinct pets have visits (pets 1-8). Total visits across all pets = 14.
        record Result(Pet pet, int visitCount) {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Result.class, raw("\0, COUNT(*)", Pet.class))
                .innerJoin(Visit.class).on(Pet.class)
                .groupBy(Pet_.id)
                .getResultList();
        assertEquals(8, list.size());
        assertEquals(14, list.stream().mapToInt(Result::visitCount).sum());
    }

    @Test
    public void testBuilderWithCompoundPkJoin() {
        // data.sql inserts 5 vet_specialty rows. Inner join returns one row per vet-specialty pair.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .innerJoin(VetSpecialty.class).on(Vet.class)
                .getResultList();
        assertEquals(5, list.size());
    }

    @Test
    public void testBuilderWithDoubleCompoundPkJoin() {
        // Same 5 vet_specialty rows, now also joined to Specialty. Still 5 rows.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .innerJoin(VetSpecialty.class).on(Vet.class)
                .innerJoin(Specialty.class).on(VetSpecialty.class)
                .getResultList();
        assertEquals(5, list.size());
    }

    @Test
    public void testBuilderWithMultipleWhere() {
        // Multiple .where() calls should be AND-combined. ID 1 AND ID 2 can't both match,
        // so the result should be empty.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .typed(Integer.class)
                .where(1)
                .where(2)
                .getResultList();
        assertEquals(0, list.size());
    }

    @Test
    public void testBuilderWithMultipleWhereMatching() {
        // Using two .where() calls that don't conflict: filter by first name AND last name.
        // Vet with ID=1 is "James Carter".
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(Vet_.firstName, EQUALS, "James")
                .where(Vet_.lastName, EQUALS, "Carter")
                .getResultList();
        assertEquals(1, list.size());
    }

    @Test
    public void testBuilderWithWhere() {
        // Vet ids 1 and 2 both exist in data.sql. OR predicate should match exactly 2 vets.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .typed(Integer.class)
                .where(it -> it.whereId(1).or(it.whereId(2)))
                .getResultList();
        assertEquals(2, list.size());
    }

    @Test
    public void testBuilderWithWhereEmpty() {
        // Filtering by an empty entity list should return 0 results (no match criteria).
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(List.of())
                .getResultList();
        assertEquals(0, list.size());
    }

    @Test
    public void testBuilderWithWhereEmptyEquals() {
        // EQUALS with an empty list is not supported; should throw SqlTemplateException.
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .where(Vet_.id, EQUALS, List.of())
                    .getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testBuilderWithWhereEmptyIn() {
        // IN with an empty list is valid and matches nothing; 0 results expected.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(Vet_.id, IN, List.of())
                .getResultList();
        assertEquals(0, list.size());
    }

    @Test
    public void testBuilderWithWhereEmptyNotEquals() {
        // NOT_EQUALS with an empty list is not supported; should throw SqlTemplateException.
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .where(Vet_.id, NOT_EQUALS, List.of())
                    .getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testBuilderWithWhereEmptyNotIn() {
        // NOT IN with an empty list matches all rows. data.sql has 6 vets.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(Vet_.id, NOT_IN, List.of())
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testBuilderWithWhereTemplate() {
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .typed(Integer.class)
                .where(it -> it.whereId(1).or(it.where(raw("\0.id = 2", Vet.class))))
                .getResultList();
        assertEquals(2, list.size());
    }

    @Test
    public void testBuilderWithWhereTemplateFunction() {
        // "1 = 1" matches all rows. data.sql has 6 vets.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(it -> it.where(TemplateBuilder.create(ignore -> "1 = 1")))
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testBuilderWithWhereTemplateFunctionAfterOr() {
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .typed(Integer.class)
                .where(it -> it.whereId(1).or(
                        it.where(TemplateBuilder.create(i -> "%s.id = %s".formatted(i.interpolate(Vet.class), i.interpolate(2))))))
                .getResultList();
        assertEquals(2, list.size());
    }

    // Composable ORDER BY tests.

    @Test
    public void testBuilderWithMultipleOrderBy() {
        // Multiple orderBy calls should produce a single ORDER BY with comma-separated columns.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .orderBy(Vet_.lastName)
                .orderBy(Vet_.firstName)
                .getResultList();
        assertEquals(6, list.size());
        // Verify the list is sorted by lastName, then firstName.
        for (int i = 1; i < list.size(); i++) {
            int cmp = list.get(i - 1).lastName().compareTo(list.get(i).lastName());
            if (cmp == 0) {
                assertTrue(list.get(i - 1).firstName().compareTo(list.get(i).firstName()) <= 0);
            } else {
                assertTrue(cmp < 0);
            }
        }
    }

    // Scroll pagination tests.

    @Test
    public void testScrollBasic() {
        // There are 6 vets. A window of 3 should have hasNext=true.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .orderBy(Vet_.id)
                .scroll(3);
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollLastPage() {
        // There are 6 vets. A window of 10 should have hasNext=false.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .orderBy(Vet_.id)
                .scroll(10);
        assertEquals(6, window.content().size());
        assertFalse(window.hasNext());
    }

    @Test
    public void testScrollWithKey() {
        // First page of 3 vets ordered by id.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3));
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollAfter() {
        // Get vets after id=3, ascending. Should get vets 4, 5, 6.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3, 10));
        assertEquals(3, window.content().size());
        assertFalse(window.hasNext());
        assertTrue(window.content().stream().allMatch(v -> v.id() > 3));
    }

    @Test
    public void testScrollBefore() {
        // Get vets before id=4, descending. Should get vets 3, 2, 1.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 4, 10).backward());
        assertEquals(3, window.content().size());
        assertFalse(window.hasNext());
        assertTrue(window.content().stream().allMatch(v -> v.id() < 4));
    }

    @Test
    public void testScrollAfterWithExistingWhere() {
        // Composable: WHERE firstName = 'James' AND id > 0.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(Vet_.firstName, EQUALS, "James")
                .scroll(Scrollable.of(Vet_.id, 0, 10));
        // There's one vet named James (James Carter, id=1).
        assertEquals(1, window.content().size());
        assertFalse(window.hasNext());
    }

    @Test
    public void testScrollAfterThrowsWithExplicitOrderBy() {
        // scrollAfter should throw if orderBy was already called.
        assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .orderBy(Vet_.lastName)
                    .scroll(Scrollable.of(Vet_.id, 0, 10));
        });
    }

    @Test
    public void testScrollBeforeThrowsWithExplicitOrderBy() {
        // scrollBefore should throw if orderBy was already called.
        assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .orderBy(Vet_.lastName)
                    .scroll(Scrollable.of(Vet_.id, 10, 10).backward());
        });
    }

    // Cursorless descending keyset pagination tests.

    @Test
    public void testScrollBeforeCursorless() {
        // First page of 3 vets ordered by id DESC (cursorless scrollBefore).
        // There are 6 vets, so hasNext=true. Expected ids: 6, 5, 4.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3).backward());
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
        assertEquals(6, window.content().get(0).id());
        assertEquals(5, window.content().get(1).id());
        assertEquals(4, window.content().get(2).id());
    }

    @Test
    public void testScrollBeforeCursorlessAllResults() {
        // Request more than available: all 6 vets in descending order.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 10).backward());
        assertEquals(6, window.content().size());
        assertFalse(window.hasNext());
        assertEquals(6, window.content().get(0).id());
        assertEquals(1, window.content().get(5).id());
    }

    @Test
    public void testScrollBeforeCursorlessThrowsWithExplicitOrderBy() {
        assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .orderBy(Vet_.lastName)
                    .scroll(Scrollable.of(Vet_.id, 10).backward());
        });
    }

    @Test
    public void testScrollBeforeCursorlessComposite() {
        // First page of 3 vets ordered by lastName DESC, id DESC.
        // Expected order: Stevens(5), Ortega(4), Leary(2), Jenkins(6), Douglas(3), Carter(1).
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, Vet_.lastName, 3).backward());
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
        assertEquals("Stevens", window.content().get(0).lastName());
        assertEquals("Ortega", window.content().get(1).lastName());
        assertEquals("Leary", window.content().get(2).lastName());
    }

    @Test
    public void testScrollBeforeCursorlessCompositeAllResults() {
        // Request more than available: all 6 vets in descending composite order.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, Vet_.lastName, 10).backward());
        assertEquals(6, window.content().size());
        assertFalse(window.hasNext());
        assertEquals("Stevens", window.content().get(0).lastName());
        assertEquals("Carter", window.content().get(5).lastName());
    }

    @Test
    public void testScrollBeforeCursorlessCompositeThrowsWithExplicitOrderBy() {
        assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .orderBy(Vet_.lastName)
                    .scroll(Scrollable.of(Vet_.id, Vet_.lastName, 10).backward());
        });
    }

    // Composite keyset pagination tests.

    @Test
    public void testCompositeWindowFirstPage() {
        // First page of 3 vets ordered by lastName ASC, id ASC.
        // Expected order: Carter(1), Douglas(3), Jenkins(6), Leary(2), Ortega(4), Stevens(5).
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, Vet_.lastName, 3));
        assertEquals(3, window.content().size());
        assertTrue(window.hasNext());
        assertEquals("Carter", window.content().get(0).lastName());
        assertEquals("Douglas", window.content().get(1).lastName());
        assertEquals("Jenkins", window.content().get(2).lastName());
    }

    @Test
    public void testCompositeWindowAfter() {
        // Get vets after (lastName="Douglas", id=3), ascending.
        // Should get: Jenkins(6), Leary(2), Ortega(4), Stevens(5).
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3, Vet_.lastName, "Douglas", 10));
        assertEquals(4, window.content().size());
        assertFalse(window.hasNext());
        assertEquals("Jenkins", window.content().get(0).lastName());
        assertEquals("Leary", window.content().get(1).lastName());
        assertEquals("Ortega", window.content().get(2).lastName());
        assertEquals("Stevens", window.content().get(3).lastName());
    }

    @Test
    public void testCompositeWindowBefore() {
        // Get vets before (lastName="Leary", id=2), descending.
        // Should get: Jenkins(6), Douglas(3), Carter(1).
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 2, Vet_.lastName, "Leary", 10).backward());
        assertEquals(3, window.content().size());
        assertFalse(window.hasNext());
        // Results are in descending order.
        assertEquals("Jenkins", window.content().get(0).lastName());
        assertEquals("Douglas", window.content().get(1).lastName());
        assertEquals("Carter", window.content().get(2).lastName());
    }

    @Test
    public void testCompositeWindowAfterWithExistingWhere() {
        // Composable: WHERE firstName starts with a consonant AND composite cursor.
        // Filter vets to those with lastName > 'D' using a where clause, then scroll after (lastName="Jenkins", id=6).
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .where(Vet_.lastName, GREATER_THAN, "D")
                .scroll(Scrollable.of(Vet_.id, 6, Vet_.lastName, "Jenkins", 10));
        // Remaining after "Jenkins": Leary(2), Ortega(4), Stevens(5).
        assertEquals(3, window.content().size());
        assertFalse(window.hasNext());
        assertEquals("Leary", window.content().get(0).lastName());
    }

    @Test
    public void testCompositeWindowAfterThrowsWithExplicitOrderBy() {
        assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .orderBy(Vet_.firstName)
                    .scroll(Scrollable.of(Vet_.id, 1, Vet_.lastName, "Carter", 10));
        });
    }

    @Test
    public void testCompositeWindowBeforeThrowsWithExplicitOrderBy() {
        assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Vet.class)
                    .orderBy(Vet_.firstName)
                    .scroll(Scrollable.of(Vet_.id, 5, Vet_.lastName, "Stevens", 10).backward());
        });
    }

    @Test
    public void testCompositeWindowAfterPagination() {
        // Page through all vets in pages of 2, ordered by lastName ASC, id ASC.
        // Page 1: Carter(1), Douglas(3).
        var page1 = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, Vet_.lastName, 2));
        assertEquals(2, page1.content().size());
        assertTrue(page1.hasNext());
        assertEquals("Carter", page1.content().get(0).lastName());
        assertEquals("Douglas", page1.content().get(1).lastName());

        // Page 2: Jenkins(6), Leary(2).
        Vet lastPage1 = page1.content().get(page1.content().size() - 1);
        var page2 = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, lastPage1.id(), Vet_.lastName, lastPage1.lastName(), 2));
        assertEquals(2, page2.content().size());
        assertTrue(page2.hasNext());
        assertEquals("Jenkins", page2.content().get(0).lastName());
        assertEquals("Leary", page2.content().get(1).lastName());

        // Page 3: Ortega(4), Stevens(5).
        Vet lastPage2 = page2.content().get(page2.content().size() - 1);
        var page3 = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, lastPage2.id(), Vet_.lastName, lastPage2.lastName(), 2));
        assertEquals(2, page3.content().size());
        assertFalse(page3.hasNext());
        assertEquals("Ortega", page3.content().get(0).lastName());
        assertEquals("Stevens", page3.content().get(1).lastName());
    }

    // Scroll navigation tests.

    @Test
    public void testScrollNavigationForwardThenBackward() {
        // Scroll forward through vets using Window navigation tokens, then scroll back.
        // 6 vets ordered by id ASC: 1, 2, 3, 4, 5, 6.

        // First window: vets 1, 2, 3.
        var firstWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3));
        assertEquals(3, firstWindow.content().size());
        assertTrue(firstWindow.hasNext());
        assertEquals(1, firstWindow.content().get(0).id());
        assertEquals(2, firstWindow.content().get(1).id());
        assertEquals(3, firstWindow.content().get(2).id());

        // Navigate forward: vets 4, 5, 6.
        var nextWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(firstWindow.next());
        assertEquals(3, nextWindow.content().size());
        assertFalse(nextWindow.hasNext());
        assertEquals(4, nextWindow.content().get(0).id());
        assertEquals(5, nextWindow.content().get(1).id());
        assertEquals(6, nextWindow.content().get(2).id());

        // Navigate backward: should return the same vets as the first window, but in descending order (3, 2, 1).
        var backWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(nextWindow.previous());
        assertEquals(3, backWindow.content().size());
        assertEquals(3, backWindow.content().get(0).id());
        assertEquals(2, backWindow.content().get(1).id());
        assertEquals(1, backWindow.content().get(2).id());
    }

    @Test
    public void testScrollWindowNextCursorRoundTrip() {
        // Test that cursor string serialization and deserialization produce the same results.
        // 6 vets ordered by id ASC.

        // First window: vets 1, 2, 3.
        var firstWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3));
        assertEquals(3, firstWindow.content().size());
        assertTrue(firstWindow.hasNext());

        // Get the next cursor string; should be non-null since hasNext is true.
        String cursor = firstWindow.nextCursor();
        assertFalse(cursor == null || cursor.isEmpty());

        // Reconstruct a scrollable from the cursor string.
        var reconstructed = Scrollable.fromCursor(Vet_.id, cursor);

        // Scroll using the reconstructed scrollable.
        var cursorWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(reconstructed);

        // Scroll using the navigation token for comparison.
        var tokenWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(firstWindow.next());

        // Both approaches should yield the same results.
        assertEquals(tokenWindow.content().size(), cursorWindow.content().size());
        for (int i = 0; i < tokenWindow.content().size(); i++) {
            assertEquals(tokenWindow.content().get(i).id(), cursorWindow.content().get(i).id());
        }
    }

    @Test
    public void testScrollBackwardNavigation() {
        // Scroll backward from the end, then navigate further back.
        // 6 vets ordered by id DESC: 6, 5, 4, 3, 2, 1.

        // First backward window: vets 6, 5, 4.
        var firstWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(Scrollable.of(Vet_.id, 3).backward());
        assertEquals(3, firstWindow.content().size());
        assertTrue(firstWindow.hasNext());
        assertEquals(6, firstWindow.content().get(0).id());
        assertEquals(5, firstWindow.content().get(1).id());
        assertEquals(4, firstWindow.content().get(2).id());

        // Navigate further back using next(): vets 3, 2, 1.
        var previousWindow = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .scroll(firstWindow.next());
        assertEquals(3, previousWindow.content().size());
        assertFalse(previousWindow.hasNext());
        assertEquals(3, previousWindow.content().get(0).id());
        assertEquals(2, previousWindow.content().get(1).id());
        assertEquals(1, previousWindow.content().get(2).id());
    }
}
