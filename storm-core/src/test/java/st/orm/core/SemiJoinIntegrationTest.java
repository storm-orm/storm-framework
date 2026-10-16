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
import static st.orm.Operator.EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Data;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.model.Address;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.Visit;
import st.orm.core.model.VisitWithCompoundFK;
import st.orm.core.model.VisitWithCompoundFK_;
import st.orm.core.model.Visit_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.PredicateBuilderFactory;

/**
 * A semi-join keeps the rows that have a matching related row and an anti-join the rows that have none, each row once,
 * with the conditions on the related table placed in subqueries.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SemiJoinIntegrationTest {

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
        return statements.getFirst().replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static List<String> firstNames(List<Owner> owners) {
        return owners.stream().map(Owner::firstName).sorted().toList();
    }

    private static List<String> petNames(List<Pet> pets) {
        return pets.stream().map(Pet::name).sorted().toList();
    }

    private QueryBuilder<Data, Owner, Integer> ownersWithPets() {
        return ownersWithPets(orm());
    }

    private static QueryBuilder<Data, Owner, Integer> ownersWithPets(ORMTemplate orm) {
        return orm.entity(Owner.class).select().semiJoin(Pet.class).on(Owner.class);
    }

    /** A template whose dialect's substring index serves words of three characters, as a trigram index does. */
    private ORMTemplate trigramOrm() {
        return ORMTemplate.of(dataSource, StormConfig.of(Map.of(StormConfig.SEARCH_MIN_TERM_LENGTH, "3")));
    }

    @SafeVarargs
    private static PredicateBuilder<Data, Owner, Integer> suggest(String input, st.orm.Metamodel<? extends Data, String>... paths) {
        return PredicateBuilderFactory.suggest(input, List.of(paths));
    }

    private List<String> advisories(Supplier<?> query) {
        var advisories = new ArrayList<String>();
        observe(sql -> advisories.addAll(sql.advisories()), query::get);
        return advisories;
    }

    @SafeVarargs
    private static PredicateBuilder<Data, Owner, Integer> search(String input, st.orm.Metamodel<? extends Data, String>... paths) {
        return PredicateBuilderFactory.search(input, List.of(paths));
    }

    @Test
    public void semiJoinKeepsTheRowsWithAMatchingRelatedRowOnce() {
        var owners = capture(() -> ownersWithPets().where(it -> it.where(Pet_.name, EQUALS, "Lucky")).getResultList());
        assertEquals(List.of("Carlos", "Jeff"), firstNames(owners));
        assertTrue(statement().contains("EXISTS (SELECT 1 FROM pet"), statement());
        assertFalse(statement().contains("JOIN pet"), statement());
    }

    @Test
    public void conditionsJoinedByAndHoldForTheSameRelatedRow() {
        var owners = ownersWithPets()
                .where(it -> it.where(Pet_.name, EQUALS, "Samantha").and(it.where(Pet_.name, EQUALS, "Max")))
                .getResultList();
        assertEquals(List.of(), firstNames(owners));
    }

    @Test
    public void searchMatchesEachWordOnItsOwn() {
        var owners = capture(() -> ownersWithPets()
                .where(it -> search("samantha max", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Jean"), firstNames(owners));
        assertEquals(2, statement().split(" UNION ").length - 1, statement());
    }

    @Test
    public void searchMatchesAcrossTheRowAndItsRelatedRows() {
        var owners = capture(() -> ownersWithPets()
                .where(it -> search("davis leo", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty"), firstNames(owners));
        assertTrue(statement().contains(" IN (SELECT "), statement());
    }

    @Test
    public void shortWordIsTestedOnTheRowsTheLongerWordsFound() {
        var owners = capture(() -> ownersWithPets(trigramOrm())
                .where(it -> search("davis le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty"), firstNames(owners));
        // The long word reads each table through its own indexes; the short one is an EXISTS on the rows it found.
        assertEquals(1, statement().split(" UNION ").length - 1, statement());
        assertTrue(statement().contains("EXISTS (SELECT 1 FROM pet"), statement());
        // The owner's own column stays outside the subquery, which reads the pets of each owner alone.
        assertTrue(statement().contains("OR EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND"), statement());
    }

    @Test
    public void everyWordIsReadThroughItsIndexesWhereTheDialectTreatsWordsAlike() {
        var owners = capture(() -> ownersWithPets()
                .where(it -> search("davis le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty"), firstNames(owners));
        assertEquals(2, statement().split(" UNION ").length - 1, statement());
        assertFalse(statement().contains("EXISTS"), statement());
    }

    @Test
    public void searchOfShortWordsAloneStillMatchesEveryWord() {
        var owners = capture(() -> ownersWithPets(trigramOrm())
                .where(it -> search("le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty", "Jean"), firstNames(owners));
        // Nothing narrows the rows for it, so it is read as a long word is.
        assertTrue(statement().contains(" UNION "), statement());
    }

    @Test
    public void joinConditionLimitsTheRelatedRowsEachWordMatches() {
        // data.sql: owner 6 (Jean Coleman) has pets Samantha and Max.
        var maxOnly = orm().entity(Owner.class).select()
                .semiJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Max"));
        assertEquals(List.of(), firstNames(capture(() -> maxOnly
                .where(it -> search("samantha", Owner_.lastName, Pet_.name)).getResultList())));
        assertTrue(statement().contains(" UNION "), statement());
        assertEquals(List.of("Jean"), firstNames(maxOnly
                .where(it -> search("max", Owner_.lastName, Pet_.name)).getResultList()));
        // A word on the owner's own columns needs no related row.
        assertEquals(List.of("Jean"), firstNames(maxOnly
                .where(it -> search("coleman", Owner_.lastName, Pet_.name)).getResultList()));
        // The short word is tested on the rows the long one found, with the same limit.
        var trigramMaxOnly = trigramOrm().entity(Owner.class).select()
                .semiJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Max"));
        assertEquals(List.of(), firstNames(capture(() -> trigramMaxOnly
                .where(it -> search("coleman sa", Owner_.lastName, Pet_.name)).getResultList())));
        assertTrue(statement().contains("OR EXISTS (SELECT 1 FROM pet"), statement());
        assertEquals(List.of("Jean"), firstNames(trigramMaxOnly
                .where(it -> search("coleman ma", Owner_.lastName, Pet_.name)).getResultList()));
    }

    @Test
    public void suggestIgnoresShortWords() {
        var owners = capture(() -> ownersWithPets(trigramOrm())
                .where(it -> suggest("davis le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty", "Harold"), firstNames(owners));
        assertFalse(statement().contains("EXISTS"), statement());
    }

    @Test
    public void suggestOfShortWordsAloneMatchesNoRows() {
        var owners = capture(() -> ownersWithPets(trigramOrm())
                .where(it -> suggest("le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of(), firstNames(owners));
        assertTrue(statement().contains("1 <> 1"), statement());
        assertFalse(statement().contains("LIKE"), statement());
    }

    @Test
    public void suggestOfAnEmptyInputMatchesNoRows() {
        assertEquals(List.of(), firstNames(ownersWithPets(trigramOrm())
                .where(it -> suggest("  ", Owner_.lastName, Pet_.name))
                .getResultList()));
        assertEquals(List.of(), firstNames(ownersWithPets()
                .where(it -> suggest("", Owner_.lastName, Pet_.name))
                .getResultList()));
    }

    @Test
    public void suggestMatchesAsSearchWhereTheDialectTreatsWordsAlike() {
        assertEquals(List.of("Betty", "Jean"), firstNames(ownersWithPets()
                .where(it -> suggest("le", Owner_.lastName, Pet_.name))
                .getResultList()));
    }

    @Test
    public void searchOfShortWordsAloneCarriesAdvice() {
        var orm = trigramOrm();
        var advice = advisories(() -> ownersWithPets(orm)
                .where(it -> search("le", Owner_.lastName, Pet_.name)).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("suggest"), advice.getFirst());
        assertEquals(List.of(), advisories(() -> ownersWithPets(orm)
                .where(it -> search("davis le", Owner_.lastName, Pet_.name)).getResultList()));
        assertEquals(List.of(), advisories(() -> ownersWithPets(orm)
                .where(it -> suggest("le", Owner_.lastName, Pet_.name)).getResultList()));
        assertEquals(List.of(), advisories(() -> ownersWithPets()
                .where(it -> search("le", Owner_.lastName, Pet_.name)).getResultList()));
    }

    @Test
    public void rowWithoutRelatedRowsMatchesThroughItsOwnColumns() {
        var owners = orm().entity(Owner.class);
        owners.insert(new Owner(0, "Petra", "Lonely", new Address("1 Main St.", null), null, 0));
        assertEquals(List.of("Petra"), firstNames(ownersWithPets()
                .where(it -> search("lonely", Owner_.lastName, Pet_.name))
                .getResultList()));
        assertEquals(List.of("Petra"), firstNames(ownersWithPets()
                .where(it -> it.where(Owner_.lastName, EQUALS, "Lonely").or(it.where(Pet_.name, EQUALS, "Nobody")))
                .getResultList()));
    }

    @Test
    public void semiJoinCombinesWithConditionsOnTheRoot() {
        var owners = ownersWithPets()
                .where(it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .where(it -> it.where(Owner_.lastName, EQUALS, "Black"))
                .getResultList();
        assertEquals(List.of("Jeff"), firstNames(owners));
    }

    @Test
    public void semiJoinCountsEachRowOnce() {
        assertEquals(2, ownersWithPets().where(it -> it.where(Pet_.name, EQUALS, "Lucky")).getResultCount());
    }

    @Test
    public void semiJoinRelatesACompositeKey() {
        var vetSpecialties = capture(() -> orm().entity(VetSpecialty.class).select()
                .semiJoin(VisitWithCompoundFK.class).on(VetSpecialty.class)
                .where(it -> it.where(VisitWithCompoundFK_.description, EQUALS, "neutered"))
                .getResultList());
        assertEquals(1, vetSpecialties.size());
        assertEquals(3, vetSpecialties.getFirst().id().vetId());
        assertEquals(2, vetSpecialties.getFirst().id().specialtyId());
    }

    @Test
    public void semiJoinOnTheReferencedSide() {
        // Pet holds the key to Owner: the pets whose owner is named Davis.
        var pets = orm().entity(Pet.class).select()
                .semiJoin(Owner.class).on(Pet.class)
                .where(it -> it.where(Owner_.lastName, EQUALS, "Davis"))
                .getResultList();
        assertEquals(List.of("Iggy", "Leo"), petNames(pets));
    }

    @Test
    public void antiJoinKeepsTheRowsWithoutRelatedRows() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .antiJoin(Visit.class).on(Pet.class)
                .getResultList());
        assertEquals(List.of("Freddy", "Lucky", "Lucky", "Mulligan", "Sly"), petNames(pets));
        assertTrue(statement().contains("NOT EXISTS (SELECT 1 FROM visit"), statement());
    }

    @Test
    public void antiJoinConditionDecidesWhichRelatedRowsExclude() {
        var pets = orm().entity(Pet.class).select()
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .where(it -> it.where(Pet_.name, EQUALS, "Max").or(it.where(Pet_.name, EQUALS, "Leo")))
                .getResultList();
        assertEquals(List.of("Max"), petNames(pets));
    }

    @Test
    public void leftJoinConditionKeepsTheRowsWithoutAMatch() {
        var owners = orm().entity(Owner.class).select()
                .leftJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .getResultList();
        assertEquals(10, owners.size());
    }

    @Test
    public void innerJoinConditionFiltersTheJoinedRows() {
        var owners = orm().entity(Owner.class).select()
                .innerJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .getResultList();
        assertEquals(List.of("Carlos", "Jeff"), firstNames(owners));
    }

    @Test
    public void semiJoinedColumnsCannotOrderTheQuery() {
        var e = assertThrows(PersistenceException.class, () -> ownersWithPets()
                .where(it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .orderBy(Pet_.name)
                .getResultList());
        assertTrue(e.getMessage().contains("Pet is semi-joined"), e.getMessage());
    }

    @Test
    public void antiJoinedTableCannotBeNamedInWhere() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .antiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList());
        assertTrue(e.getMessage().contains("antiJoin(Visit).on(Pet, condition)"), e.getMessage());
    }

    @Test
    public void semiJoinWithoutConditionKeepsTheRowsWithARelatedRow() {
        var owners = orm().entity(Owner.class);
        owners.insert(new Owner(0, "Petra", "Lonely", new Address("1 Main St.", null), null, 0));
        var withPets = capture(() -> ownersWithPets().getResultList());
        assertEquals(10, withPets.size());
        assertFalse(firstNames(withPets).contains("Petra"));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id)"), statement());
        var withoutPets = orm().entity(Owner.class).select().antiJoin(Pet.class).on(Owner.class).getResultList();
        assertEquals(List.of("Petra"), firstNames(withoutPets));
    }

    @Test
    public void semiJoinAndAntiJoinWithTheSameConditionAreComplements() {
        var named = firstNames(orm().entity(Owner.class).select()
                .semiJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .getResultList());
        var others = firstNames(orm().entity(Owner.class).select()
                .antiJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .getResultList());
        assertEquals(List.of("Carlos", "Jeff"), named);
        assertEquals(8, others.size());
        assertTrue(others.stream().noneMatch(named::contains));
    }

    @Test
    public void semiJoinRefusesATemplateCondition() {
        assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(Pet.class).on(TemplateString.of("1 = 1")));
    }

    @Test
    public void semiJoinRefusesATableTheQueryJoins() {
        assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .innerJoin(Pet.class).on(Owner.class)
                .semiJoin(Pet.class).on(Owner.class));
    }

    @Test
    public void semiJoinRefusesTheQueryTable() {
        assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(Owner.class).on(Pet.class));
    }
}
