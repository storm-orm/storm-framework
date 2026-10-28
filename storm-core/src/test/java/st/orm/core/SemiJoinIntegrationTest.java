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
import st.orm.core.model.OwnerPrimaryPet;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.PetType;
import st.orm.core.model.PetVisitCount;
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
        return AdviceCapture.advisories(query);
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
    public void suggestMatchesAShortWordAtTheStartOfAWord() {
        // The short word matches pet Leo at the start of a word, where search matched Coleman anywhere.
        var owners = capture(() -> ownersWithPets(trigramOrm())
                .where(it -> suggest("davis le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty"), firstNames(owners));
        assertFalse(statement().contains("EXISTS"), statement());
    }

    @Test
    public void suggestOfWordsTooShortForAWordStartMatchesNoRows() {
        var owners = capture(() -> ownersWithPets(trigramOrm())
                .where(it -> suggest("l", Owner_.lastName, Pet_.name))
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
    public void suggestMatchesAPrefixOfTheColumnWhereNoIndexServesASubstring() {
        // The prefix leaves the column bare, for an index over it; these columns compare case-sensitively, so the
        // lowered term finds nothing in them, which is why a suggestion column is one that compares ignoring case.
        var owners = capture(() -> ownersWithPets()
                .where(it -> suggest("le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of(), firstNames(owners));
        assertTrue(statement().contains("last_name LIKE ? ESCAPE '!'"), statement());
        assertFalse(statement().contains("LOWER(o.last_name)"), statement());
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
    public void twoFilterJoinsOnOneTableAreTwoSubqueries() {
        // data.sql: pets 1 to 8 have visits; pets 1, 2, 3, 4, 6 and 7 have a 'spayed' visit.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .semiJoin(Visit.class).on(Pet.class)
                .getResultList());
        assertEquals(List.of("Iggy", "Max"), petNames(pets));
        assertTrue(statement().contains("NOT EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?)"), statement());
        assertTrue(statement().contains(" AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id)"), statement());
        var none = orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .antiJoin(Visit.class).on(Pet.class)
                .getResultList();
        assertEquals(List.of(), petNames(none));
    }

    @Test
    public void twoSemiJoinsOnOneTableEachMatchARelatedRowOfTheirOwn() {
        // data.sql: pets 2 and 4 were spayed and neutered, on different visits.
        var both = orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList();
        assertEquals(List.of("Basil", "Jewel"), petNames(both));
        var oneVisit = orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed")
                        .and(it.where(Visit_.description, EQUALS, "neutered")))
                .getResultList();
        assertEquals(List.of(), petNames(oneVisit));
    }

    @Test
    public void conditionNamingATwiceFilteredTableIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "neutered"))
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList());
        assertTrue(e.getMessage().contains("filters by more than once (semiJoin(Visit).on(Pet), antiJoin(Visit).on(Pet))"), e.getMessage());
        assertTrue(e.getMessage().contains("semiJoin(Visit).on(Pet, condition)"), e.getMessage());
    }

    @Test
    public void joinOnATwiceFilteredTableIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(Pet.class).on(Owner.class)
                .antiJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Lucky"))
                .semiJoin(Visit.class).on(Pet.class));
        assertTrue(e.getMessage().contains("semiJoin(Visit).on(Pet): the query filters by Pet more than once"), e.getMessage());
    }

    @Test
    public void semiJoinOnASemiJoinedTableNestsInItsSubquery() {
        // data.sql: the neutered visits are of Basil (George), Jewel (Eduardo) and Max (Jean).
        var owners = capture(() -> ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList());
        assertEquals(List.of("Eduardo", "George", "Jean"), firstNames(owners));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?))"), statement());
        assertEquals(3, ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultCount());
    }

    @Test
    public void semiJoinOnASemiJoinedTableWithoutConditionKeepsTheRowsWithARelatedRowOfARelatedRow() {
        // data.sql: the pets of Jeff, Maria, David and Carlos have no visits.
        var owners = ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .getResultList();
        assertEquals(List.of("Betty", "Eduardo", "George", "Harold", "Jean", "Peter"), firstNames(owners));
    }

    @Test
    public void conditionsJoinedByAndAcrossLevelsHoldForTheSameOuterRelatedRow() {
        // Betty's Leo was spayed, and Basil, a pet of George, was neutered: no pet named Leo was neutered.
        assertEquals(List.of(), firstNames(ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Pet_.name, EQUALS, "Leo").and(it.where(Visit_.description, EQUALS, "neutered")))
                .getResultList()));
        var owners = capture(() -> ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Pet_.name, EQUALS, "Basil").and(it.where(Visit_.description, EQUALS, "neutered")))
                .getResultList());
        assertEquals(List.of("George"), firstNames(owners));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND p.name = ? AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?))"), statement());
    }

    @Test
    public void orAcrossLevelsTestsTheInnerTableOnTheRelatedRowsOfEachRow() {
        var owners = capture(() -> ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Pet_.name, EQUALS, "Leo").or(it.where(Visit_.description, EQUALS, "neutered")))
                .getResultList());
        assertEquals(List.of("Betty", "Eduardo", "George", "Jean"), firstNames(owners));
        // Inside the subquery of the pets, the visits are read for each pet alone.
        assertTrue(statement().contains("(p.name = ? OR EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?))"), statement());
        assertFalse(statement().contains(" UNION "), statement());
    }

    @Test
    public void conditionOnTheRootNextToANestedSemiJoinHoldsWithoutRelatedRows() {
        var owners = ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Owner_.lastName, EQUALS, "Black").or(it.where(Visit_.description, EQUALS, "neutered")))
                .getResultList();
        assertEquals(List.of("Eduardo", "George", "Jean", "Jeff"), firstNames(owners));
    }

    @Test
    public void antiJoinOnASemiJoinedTableNestsAsNotExists() {
        // data.sql: Lucky (Jeff and Carlos), Mulligan (Maria) and Freddy (David) have no visits.
        var owners = capture(() -> ownersWithPets()
                .antiJoin(Visit.class).on(Pet.class)
                .getResultList());
        assertEquals(List.of("Carlos", "David", "Jeff", "Maria"), firstNames(owners));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND NOT EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id))"), statement());
    }

    @Test
    public void antiJoinOnASemiJoinedTableTakesItsConditionOnTheJoin() {
        // The owners with a pet that was never spayed: Max of Jean, Iggy of Harold, and the pets without visits.
        var owners = ownersWithPets()
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList();
        assertEquals(List.of("Carlos", "David", "Harold", "Jean", "Jeff", "Maria"), firstNames(owners));
        var e = assertThrows(PersistenceException.class, () -> ownersWithPets()
                .antiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList());
        assertTrue(e.getMessage().contains("antiJoin(Visit).on(Pet, condition)"), e.getMessage());
    }

    @Test
    public void semiJoinOnAnAntiJoinedTableDecidesWhichRelatedRowsExclude() {
        // The owners without a pet that was neutered.
        var owners = capture(() -> orm().entity(Owner.class).select()
                .antiJoin(Pet.class).on(Owner.class)
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList());
        assertEquals(List.of("Betty", "Carlos", "David", "Harold", "Jeff", "Maria", "Peter"), firstNames(owners));
        assertTrue(statement().contains("WHERE NOT EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?))"), statement());
        // The condition decides which pets exclude an owner, so it belongs on the join.
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .antiJoin(Pet.class).on(Owner.class)
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList());
        assertTrue(e.getMessage().contains("semiJoin(Visit).on(Pet, condition)"), e.getMessage());
    }

    @Test
    public void semiJoinOnATableASemiJoinedTableReferencesCorrelatesOnTheForeignKey() {
        var primaryPets = orm().entity(OwnerPrimaryPet.class);
        var pets = orm().entity(Pet.class);
        var owners = orm().entity(Owner.class);
        // Leo (Betty) was spayed; Jewel (Eduardo) was neutered.
        primaryPets.insert(new OwnerPrimaryPet(owners.getById(1), pets.getById(1)));
        primaryPets.insert(new OwnerPrimaryPet(owners.getById(3), pets.getById(4)));
        var neutered = capture(() -> owners.select()
                .semiJoin(OwnerPrimaryPet.class).on(Owner.class)
                .semiJoin(Visit.class).on(Pet.class)
                .where(it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList());
        assertEquals(List.of("Eduardo"), firstNames(neutered));
        // The visit relates to the primary pet through the link's foreign key, without reading the pet table.
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM owner_primary_pet opp WHERE opp.owner_id = o.id AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = opp.pet_id AND v.description = ?))"), statement());
        assertFalse(statement().contains("FROM pet"), statement());
        assertEquals(List.of("Betty"), firstNames(owners.select()
                .semiJoin(OwnerPrimaryPet.class).on(Owner.class)
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList()));
    }

    @Test
    public void semiJoinOnATableThatReferencesTheSemiJoinedTablesTableIsRefused() {
        // Pet holds the key to its type, so a subquery of the link would have to read the pet to reach the type.
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(OwnerPrimaryPet.class).on(Owner.class)
                .semiJoin(PetType.class).on(Pet.class)
                .getResultList());
        assertTrue(e.getMessage().contains("semiJoin(Pet).on(OwnerPrimaryPet)"), e.getMessage());
    }

    @Test
    public void semiJoinOnATableOfTheQueryStaysOnTheQuery() {
        // Visit is filtered in the subquery of the pets; the owner the pet's own foreign key joins stays the query's.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(OwnerPrimaryPet.class).on(Owner.class)
                .getResultList());
        assertEquals(List.of(), petNames(pets));
        assertTrue(statement().contains("EXISTS (SELECT 1 FROM owner_primary_pet opp WHERE opp.owner_id = o.id)"), statement());
    }

    @Test
    public void semiJoinOnATableNoFilterReachesIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .getResultList());
        assertTrue(e.getMessage().contains("Pet is not part of this query"), e.getMessage());
    }

    @Test
    public void semiJoinOnTheQueryTableFindsTheRowsReferencingTheSameRow() {
        // data.sql: pets 2 and 4 were spayed and neutered; pet 8 was neutered and never spayed.
        var neutered = capture(() -> orm().entity(Visit.class).select()
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .where(Visit_.description, EQUALS, "neutered")
                .getResultList());
        assertEquals(List.of(2, 4), neutered.stream().map(visit -> visit.pet().id()).sorted().toList());
        // The condition on the join names the other visit; the condition in where names the query's visit.
        assertTrue(statement().contains("WHERE v.description = ? AND EXISTS (SELECT 1 FROM visit v1 WHERE v1.pet_id = v.pet_id AND v1.description = ?)"), statement());
        var spayedOnly = orm().entity(Visit.class).select()
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .where(Visit_.description, EQUALS, "neutered")
                .getResultList();
        assertEquals(List.of(8), spayedOnly.stream().map(visit -> visit.pet().id()).toList());
    }

    @Test
    public void semiJoinOnTheQueryTableGroupsByTheQueryTable() {
        // data.sql: the pets with a spayed visit are 1, 2, 3, 4, 6 and 7; pet 3 had one visit, the others two.
        var counts = orm().entity(Visit.class)
                .select(PetVisitCount.class, TemplateString.raw("\0, COUNT(*)", Visit_.pet))
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .groupBy(Visit_.pet)
                .orderBy(Visit_.pet)
                .getResultList();
        assertEquals(List.of(1, 2, 3, 4, 6, 7), counts.stream().map(PetVisitCount::petId).toList());
        assertEquals(List.of(2L, 2L, 1L, 2L, 2L, 2L), counts.stream().map(PetVisitCount::visits).toList());
    }

    @Test
    public void antiJoinOnTheQueryTableFiltersADelete() {
        // data.sql: pet 8's neutered visit is the one of a pet never spayed.
        var removed = capture(() -> orm().entity(Visit.class).delete()
                .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .where(Visit_.description, EQUALS, "neutered")
                .executeUpdate());
        assertEquals(1, removed);
        assertTrue(statement().contains("NOT EXISTS (SELECT 1 FROM visit v1 WHERE v1.pet_id = v.pet_id AND v1.description = ?)"), statement());
        assertEquals(2, orm().entity(Visit.class).select().where(Visit_.description, EQUALS, "neutered").getResultCount());
    }

    @Test
    public void semiJoinOnTheQueryTableNamesATableItReferences() {
        var self = assertThrows(PersistenceException.class, () -> orm().entity(Visit.class).select()
                .semiJoin(Visit.class).on(Visit.class));
        assertTrue(self.getMessage().contains("semiJoin(Visit).on(Visit)"), self.getMessage());
        var reversed = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(Owner.class).on(Pet.class));
        assertTrue(reversed.getMessage().contains("Pet references Owner instead"), reversed.getMessage());
    }

    @Test
    public void twoSemiJoinsOnOneTableInsideASubqueryEachMatchARelatedRowOfTheirOwn() {
        // data.sql: Basil (George) and Jewel (Eduardo) were spayed and neutered, on different visits.
        var owners = capture(() -> ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "neutered"))
                .getResultList());
        assertEquals(List.of("Eduardo", "George"), firstNames(owners));
        assertTrue(statement().contains("EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?) AND EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?))"), statement());
    }

    @Test
    public void conditionNamingATableFilteredTwiceInsideASubqueryIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> ownersWithPets()
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "spayed"))
                .semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "neutered"))
                .where(it -> it.where(Visit_.description, EQUALS, "rabies shot"))
                .getResultList());
        assertTrue(e.getMessage().contains("filters by more than once (semiJoin(Visit).on(Pet), semiJoin(Visit).on(Pet))"), e.getMessage());
    }

    @Test
    public void semiJoinReachedThroughATwiceFilteredTableIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .semiJoin(OwnerPrimaryPet.class).on(Owner.class)
                .antiJoin(OwnerPrimaryPet.class).on(Owner.class)
                .semiJoin(Visit.class).on(Pet.class)
                .getResultList());
        assertTrue(e.getMessage().contains("semiJoin(Visit).on(Pet): Pet is reached through the foreign key of OwnerPrimaryPet, which the query filters by more than once (semiJoin(OwnerPrimaryPet).on(Owner), antiJoin(OwnerPrimaryPet).on(Owner))"), e.getMessage());
    }

    @Test
    public void semiJoinOnAJoinedTableCorrelatesWithTheJoin() {
        // data.sql: pets 1 to 8 have visits, each with an owner.
        var owners = capture(() -> orm().entity(Owner.class).select()
                .innerJoin(Pet.class).on(Owner.class)
                .semiJoin(Visit.class).on(Pet.class)
                .getResultList());
        assertEquals(8, owners.size());
        assertTrue(statement().contains("INNER JOIN pet p ON"), statement());
        assertTrue(statement().contains("EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id)"), statement());
    }

    @Test
    public void antiJoinedColumnsCannotOrderTheQuery() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .antiJoin(Pet.class).on(Owner.class)
                .orderBy(Pet_.name)
                .getResultList());
        assertTrue(e.getMessage().contains("Pet is anti-joined"), e.getMessage());
    }

    @Test
    public void orderingByTheQueryTableNextToASemiJoin() {
        var owners = ownersWithPets().orderBy(Owner_.lastName).getResultList();
        assertEquals(10, owners.size());
        assertEquals(owners.stream().map(Owner::lastName).sorted().toList(), owners.stream().map(Owner::lastName).toList());
    }
}
