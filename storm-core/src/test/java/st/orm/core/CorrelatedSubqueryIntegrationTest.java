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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Metamodel.outer;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.ResolveScope.INNER;
import static st.orm.ResolveScope.OUTER;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.Templates.column;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.Scrollable;
import st.orm.SqlFunction;
import st.orm.core.model.Address;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.PredicateBuilderFactory;

/**
 * A subquery correlates with the enclosing query through {@code column(..., OUTER)}, which names the outer query's
 * table and never the subquery's own, and an existence test selects a constant.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class CorrelatedSubqueryIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private final List<String> statements = new ArrayList<>();

    private ORMTemplate orm() {
        return ORMTemplate.of(dataSource);
    }

    private <T> T capture(Supplier<T> query) {
        statements.clear();
        var result = new ArrayList<T>(1);
        observe(sql -> statements.add(sql.statement()), () -> result.add(query.get()));
        return result.getFirst();
    }

    private String statement() {
        return statements.getFirst().replaceAll("\\s+", " ");
    }

    private static TemplateString petOfOuterRow() {
        return raw("\0 = \0", column(Pet_.id, INNER), column(Pet_.id, OUTER));
    }

    @Test
    public void outerJoinsTheOuterTableItNamesWhenTheSelectDoesNotReadIt() {
        // The visits of pets that had a rabies shot, counted: the outer query reads no pet column of its own.
        var count = capture(() -> orm().selectFrom(Visit.class, Long.class, raw("COUNT(*)"))
                .where(it -> it.exists(it.subquery(Visit.class)
                        .where(Visit_.description, EQUALS, "rabies shot").where(petOfOuterRow())))
                .getSingleResult());
        assertEquals(7L, count);
        assertTrue(statement().contains("INNER JOIN pet p ON v.pet_id = p.id"), statement());
    }

    @Test
    public void outerNeverResolvesToTheSubqueryOwnAlias() {
        // The visits of pets named Leo.
        var visits = capture(() -> orm().entity(Visit.class).select()
                .where(it -> it.exists(it.subquery(Pet.class)
                        .where(Pet_.name, EQUALS, "Leo").where(petOfOuterRow())))
                .getResultList());
        assertEquals(2, visits.size());
        assertEquals(Set.of("Leo"), visits.stream().map(visit -> visit.pet().name()).collect(Collectors.toSet()));
        assertTrue(statement().contains("p1.id = p.id"), statement());
        assertFalse(statement().contains("p1.id = p1.id"), statement());
    }

    @Test
    public void whereExistsSelectsAConstant() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(raw("\0 = \0", column(Pet_.owner, INNER), column(Owner_.id, OUTER))))
                .getResultList());
        assertEquals(10, owners.size());
        assertTrue(statement().contains("WHERE EXISTS ( SELECT 1 FROM pet p WHERE p.owner_id = o.id )"), statement());
    }

    @Test
    public void whereNotExistsSelectsAConstant() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereNotExists(orm().selectFrom(Pet.class).where(raw("\0 = \0", column(Pet_.owner, INNER), column(Owner_.id, OUTER))))
                .getResultList());
        assertEquals(0, owners.size());
        assertTrue(statement().contains("WHERE NOT EXISTS ( SELECT 1 FROM pet p WHERE p.owner_id = o.id )"), statement());
    }

    @Test
    public void havingExistsSelectsAConstant() {
        var counts = capture(() -> orm().selectFrom(Owner.class, Long.class, raw("COUNT(*)"))
                .groupBy(Owner_.id)
                .havingExists(orm().selectFrom(Pet.class).where(raw("\0 = \0", column(Pet_.owner, INNER), column(Owner_.id, OUTER))))
                .getResultList());
        assertEquals(10, counts.size());
        assertTrue(statement().contains("HAVING EXISTS ( SELECT 1 FROM pet p WHERE p.owner_id = o.id )"), statement());
    }

    @Test
    public void subqueryWithASelectListOfItsOwnKeepsIt() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class, Integer.class, raw("\0", Pet_.id))
                        .where(raw("\0 = \0", column(Pet_.owner, INNER), column(Owner_.id, OUTER))))
                .getResultList());
        assertEquals(10, owners.size());
        assertTrue(statement().contains("WHERE EXISTS ( SELECT p.id FROM pet p WHERE p.owner_id = o.id )"), statement());
    }

    @Test
    public void comparisonWithoutOuterNamesTheSubqueryOwnTables() {
        // The subquery joins the visit's pet, which the path names: every pet has a visit by this reading.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Visit.class).where(it -> it.where(Visit_.pet.id, EQUALS, Pet_.id)))
                .getResultList());
        assertEquals(13, pets.size());
        assertTrue(statement().contains("WHERE v.pet_id = p1.id"), statement());
    }

    @Test
    public void outerNamesTheColumnOfTheEnclosingQueryInAComparison() {
        // data.sql: pets 1 to 8 have visits.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Visit.class).where(it -> it.where(Visit_.pet.id, EQUALS, outer(Pet_.id))))
                .getResultList());
        assertEquals(8, pets.size());
        assertTrue(statement().contains("WHERE EXISTS ( SELECT 1 FROM visit v WHERE v.pet_id = p.id )"), statement());
    }

    @Test
    public void outerNamesTheEnclosingRowOfTheTableTheSubqueryIsRootedAt() {
        // The pets whose owner keeps another pet: Rosy and Jewel, Samantha and Max.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(it -> it.where(Pet_.owner.id, EQUALS, outer(Pet_.owner.id))
                        .and(it.where(Pet_.id, NOT_EQUALS, outer(Pet_.id)))))
                .getResultList());
        assertEquals(Set.of("Rosy", "Jewel", "Samantha", "Max"), pets.stream().map(Pet::name).collect(Collectors.toSet()));
        assertTrue(statement().contains("FROM pet p1 WHERE p1.owner_id = p.owner_id AND p1.id <> p.id"), statement());
    }

    @Test
    public void outerOnTheLeftOfAComparisonNamesTheEnclosingQuery() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(it -> it.where(outer(Pet_.owner.id), EQUALS, Pet_.owner.id)
                        .and(it.where(Pet_.id, NOT_EQUALS, outer(Pet_.id)))))
                .getResultList());
        assertEquals(4, pets.size());
        assertTrue(statement().contains("FROM pet p1 WHERE p.owner_id = p1.owner_id AND p1.id <> p.id"), statement());
    }

    @Test
    public void outerReachesATableTheEnclosingQueryJoins() {
        // The visits for which another pet's owner shares the first name of the visited pet's owner: none does, so
        // the subquery finds the visited pet's own row.
        var visits = capture(() -> orm().entity(Visit.class).select()
                .whereExists(orm().selectFrom(Pet.class)
                        .where(it -> it.where(Pet_.owner.firstName, EQUALS, outer(Visit_.pet.owner.firstName))))
                .getResultList());
        assertEquals(14, visits.size());
        assertTrue(statement().contains("FROM pet p1 LEFT JOIN owner o1 ON p1.owner_id = o1.id WHERE o1.first_name = o.first_name"), statement());
    }

    @Test
    public void outerInATemplateRendersTheColumnOfTheEnclosingQuery() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(raw("\0 = \0", column(Pet_.owner, INNER), outer(Owner_.id))))
                .getResultList());
        assertEquals(10, owners.size());
        assertTrue(statement().contains("WHERE EXISTS ( SELECT 1 FROM pet p WHERE p.owner_id = o.id )"), statement());
    }

    @Test
    public void outerCountsTheRelatedRowsOfTheEnclosingRow() {
        // data.sql: pets 1, 2, 4, 6, 7 and 8 had two visits, the other visited pets one.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Visit.class)
                        .where(it -> it.where(Visit_.pet.id, EQUALS, outer(Pet_.id)))
                        .having(raw("COUNT(*) >= \0", 2)))
                .getResultList());
        assertEquals(Set.of("Leo", "Basil", "Jewel", "George", "Samantha", "Max"), pets.stream().map(Pet::name).collect(Collectors.toSet()));
        assertTrue(statement().contains("FROM visit v WHERE v.pet_id = p.id HAVING COUNT(*) >= ?"), statement());
    }

    @Test
    public void outerIsRefusedWhereTheQueryResolvesItsOwnColumns() {
        // Every use other than a comparison of two columns resolves the path as a column of the query, and that
        // resolution refuses it, whatever the use: a value comparison, through a foreign key and a function call as
        // well, ordering, grouping, a search, a scroll and a column to set. A fetch resolves no columns and refuses it
        // where it is given.
        var upper = SqlFunction.<String>of("upper");
        List<Executable> uses = List.of(
                () -> orm().selectFrom(Pet.class).where(it -> it.where(outer(Pet_.name), EQUALS, "Leo")).getResultList(),
                () -> orm().selectFrom(Pet.class).where(it -> it.where(outer(Pet_.owner.id), EQUALS, 1)).getResultList(),
                () -> orm().selectFrom(Pet.class).where(it -> it.where(upper.apply(outer(Pet_.name)), EQUALS, "LEO")).getResultList(),
                () -> orm().selectFrom(Pet.class).orderBy(outer(Pet_.name)).getResultList(),
                () -> orm().selectFrom(Pet.class).groupBy(outer(Pet_.name)).getResultList(),
                () -> orm().selectFrom(Pet.class).where(it -> PredicateBuilderFactory.search("leo", outer(Pet_.name))).getResultList(),
                () -> orm().entity(Pet.class).select().scroll(Scrollable.of(Metamodel.key(outer(Pet_.id)), 10)),
                () -> orm().entity(Owner.class).update().set(outer(Owner_.firstName), "Leo").where(Owner_.id, EQUALS, 1).executeUpdate(),
                () -> orm().entity(Pet.class).select().fetch(outer(Pet_.type)));
        for (var use : uses) {
            var e = assertThrows(PersistenceException.class, use);
            assertTrue(e.getMessage().contains("is a column of the enclosing query; only a comparison of two columns takes it"), e.getMessage());
        }
    }

    @Test
    public void outerIsNoKeyOfAJoin() {
        // A semi-join relates its table to a table of the query through a key of each; a column of the enclosing
        // query is neither.
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(it -> it.where(Visit_.pet.id, EQUALS, outer(Pet_.id))));
        assertTrue(e.getMessage().contains("the predicate equates no key of Visit"), e.getMessage());
    }

    @Test
    public void outerInASubqueryWithASemiJoinStaysTheEnclosingQuerys() {
        // The owners who keep a visited pet other than the pet the enclosing row is: the subquery's semi-join places
        // its own conditions, and the comparison with the enclosing row stays with the subquery's rows.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Pet.class)
                        .semiJoin(Visit.class).on(Pet.class)
                        .where(it -> it.where(Pet_.owner.id, EQUALS, outer(Pet_.owner.id))
                                .and(it.where(Pet_.id, NOT_EQUALS, outer(Pet_.id)))))
                .getResultList());
        assertEquals(Set.of("Rosy", "Jewel", "Samantha", "Max"), pets.stream().map(Pet::name).collect(Collectors.toSet()));
        assertTrue(statement().contains("p1.owner_id = p.owner_id AND p1.id <> p.id AND EXISTS ( SELECT 1 FROM visit v WHERE v.pet_id = p1.id )"), statement());
    }

    @Test
    public void outerInATemplateNamesAColumn() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(raw("\0 IS NOT NULL", outer(Metamodel.root(Owner.class)))))
                .getResultList());
        assertTrue(e.getMessage().contains("Path does not reference a column"), e.getMessage());
    }

    @Test
    public void outerInATemplatePredicateRendersTheColumnOfTheEnclosingQuery() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class)
                        .where(it -> it.where(raw("\0 = \0", column(Pet_.owner, INNER), outer(Owner_.id)))
                                .and(it.where(Pet_.name, NOT_EQUALS, "Leo"))))
                .getResultList());
        // data.sql: Betty keeps Leo alone.
        assertEquals(9, owners.size());
        assertTrue(statement().contains("FROM pet p WHERE (p.owner_id = o.id) AND p.name <> ?"), statement());
    }

    @Test
    public void outerInATemplatePredicateNamesAColumn() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class)
                        .where(it -> it.where(raw("\0 IS NOT NULL", outer(Metamodel.root(Owner.class))))
                                .and(it.where(Pet_.name, NOT_EQUALS, "Leo"))))
                .getResultList());
        assertTrue(e.getMessage().contains("Path does not reference a column"), e.getMessage());
    }

    @Test
    public void outerInAColumnElementNamesTheEnclosingQuery() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(raw("\0 = \0", column(Pet_.owner, INNER), column(outer(Owner_.id)))))
                .getResultList());
        assertEquals(10, owners.size());
        assertTrue(statement().contains("WHERE EXISTS ( SELECT 1 FROM pet p WHERE p.owner_id = o.id )"), statement());
    }

    @Test
    public void outerOutsideASubqueryFindsNoEnclosingQuery() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .where(it -> it.where(Pet_.owner.id, EQUALS, outer(Owner_.id)))
                .getResultList());
        assertTrue(e.getMessage().contains("Owner is not part of this query"), e.getMessage());
    }

    @Test
    public void functionAppliesToAnOuterColumn() {
        // The pets named as their owner, whatever the case: none in data.sql, so the statement is what counts.
        var upper = SqlFunction.<String>of("upper");
        var owners = capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(it -> it.where(Pet_.owner.id, EQUALS, outer(Owner_.id))
                        .and(it.where(upper.apply(Pet_.name), EQUALS, upper.apply(outer(Owner_.firstName))))))
                .getResultList());
        assertEquals(0, owners.size());
        assertTrue(statement().contains("FROM pet p WHERE p.owner_id = o.id AND upper(p.name) = upper(o.first_name)"), statement());
        // The call may be named in the enclosing query as a whole, and in a template.
        capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class)
                        .where(it -> it.where(Pet_.name, EQUALS, outer(upper.apply(Owner_.firstName)))))
                .getResultList());
        assertTrue(statement().contains("FROM pet p WHERE p.name = upper(o.first_name)"), statement());
        capture(() -> orm().entity(Owner.class).select()
                .whereExists(orm().selectFrom(Pet.class)
                        .where(raw("\0 = \0", Pet_.name, upper.apply(outer(Owner_.firstName)))))
                .getResultList());
        assertTrue(statement().contains("FROM pet p WHERE p.name = upper(o.first_name)"), statement());
    }

    @Test
    public void functionAppliesToEitherSideOfAColumnComparison() {
        var upper = SqlFunction.<String>of("upper");
        var owners = capture(() -> orm().entity(Owner.class).select()
                .where(it -> it.where(upper.apply(Owner_.firstName), EQUALS, upper.apply(Owner_.lastName)))
                .getResultList());
        assertEquals(0, owners.size());
        assertTrue(statement().contains("WHERE upper(o.first_name) = upper(o.last_name)"), statement());
        // A function takes one column, and an address spans two.
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .where(it -> it.where(Owner_.address, EQUALS, SqlFunction.<Address>of("upper").apply(Owner_.address)))
                .getResultList());
        assertTrue(e.getMessage().contains("applies a SQL function"), e.getMessage());
    }
}
