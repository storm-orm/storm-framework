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
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.ResolveScope.INNER;
import static st.orm.ResolveScope.OUTER;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.Templates.column;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Data;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.model.Address;
import st.orm.core.model.Owner;
import st.orm.core.model.OwnerPrimaryPet;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.PetExtension;
import st.orm.core.model.PetExtension_;
import st.orm.core.model.PetPair;
import st.orm.core.model.PetPair_;
import st.orm.core.model.PetType;
import st.orm.core.model.PetType_;
import st.orm.core.model.PetVisitCount;
import st.orm.core.model.Pet_;
import st.orm.core.model.Tenant;
import st.orm.core.model.Tenant_;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.Visit;
import st.orm.core.model.VisitWithCompoundFK;
import st.orm.core.model.VisitWithCompoundFK_;
import st.orm.core.model.Visit_;
import st.orm.core.spi.NoInOverUnionSqlDialectProviderImpl;
import st.orm.core.spi.SqlDialectProvider;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.PreparedStatementTemplate;
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
        return PredicateBuilderFactory.suggest(input, Set.of(), paths);
    }

    private List<String> advisories(Supplier<?> query) {
        return AdviceCapture.advisories(query);
    }

    @SafeVarargs
    private static PredicateBuilder<Data, Owner, Integer> search(String input, st.orm.Metamodel<? extends Data, String>... paths) {
        return PredicateBuilderFactory.search(input, paths);
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
    public void suggestMatchesAWordAtTheStartOfAWordOnEveryDialect() {
        // The short word matches pet Leo at the start of a word, on the rows "davis" found, where search matched
        // Coleman anywhere; a dialect with a substring index matches the same rows.
        var owners = capture(() -> ownersWithPets()
                .where(it -> suggest("davis le", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty"), firstNames(owners));
        assertTrue(statement().contains("EXISTS"), statement());
        assertEquals(List.of("Betty"), firstNames(ownersWithPets(trigramOrm())
                .where(it -> suggest("davis le", Owner_.lastName, Pet_.name))
                .getResultList()));
        assertEquals(List.of("Betty"), firstNames(ownersWithPets(trigramOrm())
                .where(it -> suggest("le", Owner_.lastName, Pet_.name))
                .getResultList()));
        assertEquals(List.of("Betty", "Jean"), firstNames(ownersWithPets(trigramOrm())
                .where(it -> search("le", Owner_.lastName, Pet_.name))
                .getResultList()));
    }

    @Test
    public void suggestOfAOneCharacterWordMatchesNoRows() {
        var owners = capture(() -> ownersWithPets()
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

    /** Pets filtered by two semi-joined tables: their visits and their notes. */
    private QueryBuilder<Data, Pet, Integer> petsWithVisitsAndNotes() {
        return petsWithVisitsAndNotes(orm());
    }

    private static QueryBuilder<Data, Pet, Integer> petsWithVisitsAndNotes(ORMTemplate orm) {
        return orm.entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetExtension.class).on(Pet.class);
    }

    @Test
    public void searchMatchesEachWordOnEitherOfTwoSemiJoinedTables() {
        // data.sql: Rosy (pet 3) was spayed and is noted as a friendly dog; Leo (pet 1) is noted as a good cat.
        var pets = capture(() -> petsWithVisitsAndNotes()
                .where(it -> PredicateBuilderFactory.<Data, Pet, Integer>search("spayed friendly",
                        Pet_.name, Visit_.description, PetExtension_.notes))
                .getResultList());
        assertEquals(List.of("Rosy"), petNames(pets));
        // Each word is one UNION with a branch for the pet's name, its visits and its notes.
        assertEquals(2, statement().split(" IN \\(SELECT ").length - 1, statement());
        assertEquals(4, statement().split(" UNION ").length - 1, statement());
        assertFalse(statement().contains("EXISTS"), statement());
        assertEquals(List.of("Leo"), petNames(petsWithVisitsAndNotes()
                .where(it -> PredicateBuilderFactory.<Data, Pet, Integer>search("leo good",
                        Pet_.name, Visit_.description, PetExtension_.notes))
                .getResultList()));
    }

    @Test
    public void orAcrossTwoSemiJoinedTablesHoldsWhereEitherArmHolds() {
        // data.sql: Basil, Jewel and Max were neutered; Samantha is noted as an indoor cat.
        var pets = capture(() -> petsWithVisitsAndNotes()
                .where(it -> it.where(Visit_.description, EQUALS, "neutered")
                        .or(it.where(PetExtension_.notes, EQUALS, "Indoor cat")))
                .getResultList());
        assertEquals(List.of("Basil", "Jewel", "Max", "Samantha"), petNames(pets));
        // Each table is a branch of one UNION, so only the keys the branches find meet the pets.
        assertTrue(statement().contains("WHERE p.id IN (SELECT v.pet_id FROM visit v WHERE v.description = ? UNION "
                + "SELECT pe.pet_id FROM pet_extension pe WHERE pe.notes = ?)"), statement());
        assertFalse(statement().contains("EXISTS"), statement());
    }

    @Test
    public void orAcrossSemiJoinedTablesRendersAnExistsPerTableWhereTheDatabaseRunsAUnionPerRow() {
        var pets = capture(() -> petsWithVisitsAndNotes(noInOverUnionOrm())
                .where(it -> it.where(Visit_.description, EQUALS, "neutered")
                        .or(it.where(PetExtension_.notes, EQUALS, "Indoor cat")))
                .getResultList());
        assertEquals(List.of("Basil", "Jewel", "Max", "Samantha"), petNames(pets));
        // Each table is an EXISTS of its own, correlated through its foreign key, which the database tests per row.
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?) "
                + "OR EXISTS (SELECT 1 FROM pet_extension pe WHERE pe.pet_id = p.id AND pe.notes = ?)"), statement());
        assertFalse(statement().contains(" UNION "), statement());
    }

    @Test
    public void searchAcrossSemiJoinedTablesRendersAnExistsPerTableWhereTheDatabaseRunsAUnionPerRow() {
        var owners = capture(() -> ownersWithPets(noInOverUnionOrm())
                .where(it -> search("davis leo", Owner_.lastName, Pet_.name))
                .getResultList());
        assertEquals(List.of("Betty"), firstNames(owners));
        assertFalse(statement().contains(" UNION "), statement());
        // The owner's own column stays outside the subquery, which reads the pets of each owner alone.
        assertTrue(statement().contains("OR EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = o.id AND"), statement());
    }

    /** A template whose dialect's database runs an {@code IN} over a {@code UNION} once per row of the outer query. */
    private ORMTemplate noInOverUnionOrm() {
        // The filter applies to every kind of provider, so only the dialect providers are narrowed.
        return ORMTemplate.of(dataSource, template -> ((PreparedStatementTemplate) template)
                .withProviderFilter(provider -> !(provider instanceof SqlDialectProvider)
                        || provider instanceof NoInOverUnionSqlDialectProviderImpl));
    }

    @Test
    public void anArmJoinedByAndAcrossTwoSemiJoinedTablesHoldsEachOnItsOwnTable() {
        // Rosy was spayed and is noted as a friendly dog; Iggy matches by name.
        var pets = petsWithVisitsAndNotes()
                .where(it -> it.where(Visit_.description, EQUALS, "spayed")
                        .and(it.where(PetExtension_.notes, EQUALS, "Friendly dog"))
                        .or(it.where(Pet_.name, EQUALS, "Iggy")))
                .getResultList();
        assertEquals(List.of("Iggy", "Rosy"), petNames(pets));
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
    public void semiJoinOnATableSharingAForeignKeyWithTheSemiJoinedTableMatchesOnThatKey() {
        // data.sql: pets 1, 2, 3, 4, 6 and 7 have a 'spayed' visit; pets 1 (Leo), 3 (Rosy) and 7 (Samantha) have an extension.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetExtension.class).on(Visit.class)
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList());
        assertEquals(List.of("Leo", "Rosy", "Samantha"), petNames(pets));
        // Neither table references the other: the extension relates to the visit through the pet both reference.
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ? AND EXISTS (SELECT 1 FROM pet_extension pe WHERE pe.pet_id = v.pet_id))"), statement());
        assertEquals(List.of("Leo"), petNames(orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetExtension.class).on(Visit.class)
                .where(it -> it.where(Visit_.description, EQUALS, "spayed").and(it.where(PetExtension_.notes, EQUALS, "Good cat")))
                .getResultList()));
        assertEquals(List.of("Basil", "George", "Jewel"), petNames(orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .antiJoin(PetExtension.class).on(Visit.class)
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList()));
    }

    @Test
    public void semiJoinSharingAForeignKeyFiltersTheBranchOfTheSemiJoinedTableAlone() {
        // The pets named Max, or with a spayed visit and an extension: Max has no extension and is still found by name.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetExtension.class).on(Visit.class)
                .where(it -> it.where(Pet_.name, EQUALS, "Max").or(it.where(Visit_.description, EQUALS, "spayed")))
                .getResultList());
        assertEquals(List.of("Leo", "Max", "Rosy", "Samantha"), petNames(pets));
        assertTrue(statement().contains("UNION SELECT v.pet_id FROM visit v WHERE v.description = ? AND EXISTS (SELECT 1 FROM pet_extension pe WHERE pe.pet_id = v.pet_id))"), statement());
        // A search word matching a visit counts for a pet with an extension; a word matching the pet's own name for any pet.
        assertEquals(List.of("Leo", "Rosy", "Samantha"), petNames(orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetExtension.class).on(Visit.class)
                .where(it -> PredicateBuilderFactory.<Data, Pet, Integer>search("spayed", Pet_.name, Visit_.description))
                .getResultList()));
        assertEquals(List.of("Max"), petNames(orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetExtension.class).on(Visit.class)
                .where(it -> PredicateBuilderFactory.<Data, Pet, Integer>search("Max", Pet_.name, Visit_.description))
                .getResultList()));
    }

    @Test
    public void semiJoinOnTheQueryTableSharingAForeignKeyMatchesOnThatKey() {
        // data.sql: the tenant Alpha has the owner Betty, whose pet Leo was spayed.
        var tenants = capture(() -> orm().entity(Tenant.class).select()
                .semiJoin(Pet.class).on(Tenant.class)
                .where(it -> it.where(Pet_.name, EQUALS, "Leo"))
                .getResultList());
        assertEquals(1, tenants.size());
        assertTrue(statement().contains("EXISTS (SELECT 1 FROM pet p WHERE p.owner_id = t.owner_id AND p.name = ?)"), statement());
    }

    @Test
    public void semiJoinOnTheQueryTableSharingAForeignKeyBranchesAsAUnion() {
        // data.sql: the tenant Alpha has the owner Betty, whose pet is Leo, and Beta the owner George.
        var tenants = orm().entity(Tenant.class);
        var found = capture(() -> tenants.select()
                .semiJoin(Pet.class).on(Tenant.class)
                .where(it -> it.where(Tenant_.name, EQUALS, "Beta").or(it.where(Pet_.name, EQUALS, "Leo")))
                .getResultList());
        assertEquals(List.of("Alpha", "Beta"), found.stream().map(Tenant::name).sorted().toList());
        // The pets found by name give owner keys, through which the tenants are read; the branches meet on the tenant's key.
        assertTrue(statement().contains("WHERE t.id IN (SELECT t.id FROM tenant t WHERE t.name = ? UNION SELECT t.id FROM tenant t WHERE t.owner_id IN (SELECT p.owner_id FROM pet p WHERE p.name = ?))"), statement());
        assertEquals(List.of("Alpha"), capture(() -> tenants.select()
                .semiJoin(Pet.class).on(Tenant.class)
                .where(it -> PredicateBuilderFactory.<Data, Tenant, Integer>search("Leo", Tenant_.name, Pet_.name))
                .getResultList()).stream().map(Tenant::name).toList());
        assertTrue(statement().contains(" UNION "), statement());
    }

    @Test
    public void semiJoinSharingMoreThanOneForeignKeyIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetPair.class).on(Visit.class));
        assertTrue(e.getMessage().contains("semiJoin(PetPair).on(Visit): PetPair and Visit reference the same table through more than one pair of foreign keys (PetPair.first and Visit.pet, PetPair.second and Visit.pet)"), e.getMessage());
    }

    @Test
    public void semiJoinOnANamedKeyFollowsThatKey() {
        // data.sql: the pairs are (1, 2), (3, 4) and (5, 6).
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, Pet_.id))
                .getResultList());
        assertEquals(List.of("Iggy", "Leo", "Rosy"), petNames(pets));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM pet_pair pp WHERE pp.first_id = p.id)"), statement());
        assertEquals(List.of("Basil", "George", "Jewel"), petNames(orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(Pet_.id, EQUALS, PetPair_.second.id))
                .getResultList()));
        // A key of the table joined on, referencing the filtered table, with a condition beside the key.
        var owned = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Owner.class).on(it -> it.where(Pet_.owner.id, EQUALS, Owner_.id).and(it.where(Owner_.lastName, EQUALS, "Davis")))
                .getResultList());
        assertEquals(List.of("Iggy", "Leo"), petNames(owned));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM owner o1 WHERE o1.id = p.owner_id AND o1.last_name = ?)"), statement());
    }

    @Test
    public void semiJoinOnTwoNamedKeysMatchesThemWithEachOther() {
        // The pairs whose second member is the pet of a spayed visit: Basil (2), Jewel (4) and George (6).
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.second, EQUALS, Visit_.pet))
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList());
        assertEquals(List.of("Basil", "George", "Jewel"), petNames(pets));
        assertTrue(statement().contains("AND EXISTS (SELECT 1 FROM pet_pair pp WHERE pp.second_id = v.pet_id))"), statement());
        assertEquals(List.of("Leo", "Rosy", "Samantha"), petNames(orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .antiJoin(PetPair.class).on(it -> it.where(Visit_.pet, EQUALS, PetPair_.second))
                .where(it -> it.where(Visit_.description, EQUALS, "spayed"))
                .getResultList()));
    }

    @Test
    public void filterJoinOnATableHoldingTwoKeysToTheAnchorNamesTheKeyForm() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .antiJoin(PetPair.class).on(Pet.class));
        assertTrue(e.getMessage().contains("antiJoin: PetPair references Pet through more than one foreign key (first, second), so the key to follow is ambiguous. Name it: on(PetPair_.first.id eq Pet_.id), with one antiJoin per key to follow each of them."), e.getMessage());
    }

    @Test
    public void antiJoinPerKeyKeepsTheRowsNoKeyRefersTo() {
        // data.sql: the pairs are (1, 2), (3, 4) and (5, 6), so the pets from 7 on are in no pair.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .antiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, Pet_.id))
                .antiJoin(PetPair.class).on(it -> it.where(PetPair_.second.id, EQUALS, Pet_.id))
                .getResultList());
        assertEquals(List.of("Freddy", "Lucky", "Lucky", "Max", "Mulligan", "Samantha", "Sly"), petNames(pets));
        assertTrue(statement().contains("WHERE NOT EXISTS (SELECT 1 FROM pet_pair pp WHERE pp.first_id = p.id) AND NOT EXISTS (SELECT 1 FROM pet_pair pp WHERE pp.second_id = p.id)"), statement());
        // A semi-join per key keeps the rows both keys refer to, which no pair does.
        assertEquals(List.of(), petNames(orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, Pet_.id))
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.second.id, EQUALS, Pet_.id))
                .getResultList()));
    }

    @Test
    public void correlatedSubqueryNamesTheQueryTableThroughTheOuterScope() {
        // The rows either key refers to take a subquery that names the pet of the query, which the subquery's own
        // pets would otherwise make ambiguous, through the outer scope.
        var pets = orm().entity(Pet.class).select()
                .whereExists(orm().entity(PetPair.class).select()
                        .where(raw("\0 = \0 OR \0 = \0", column(PetPair_.first, INNER), column(Pet_.id, OUTER),
                                column(PetPair_.second, INNER), column(Pet_.id, OUTER))))
                .getResultList();
        assertEquals(List.of("Basil", "George", "Iggy", "Jewel", "Leo", "Rosy"), petNames(pets));
    }

    @Test
    public void semiJoinOnTheQueryTableOnANamedKey() {
        // The pairs sharing their first member with another pair: each pair matches itself.
        var pairs = capture(() -> orm().entity(PetPair.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first, EQUALS, PetPair_.first))
                .getResultList());
        assertEquals(3, pairs.size());
        assertTrue(statement().contains(".first_id = pp.first_id)"), statement());
    }

    @Test
    public void semiJoinOnAPredicateWithoutAKeyIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, Pet_.owner.id)));
        assertTrue(e.getMessage().contains("semiJoin(PetPair): the predicate equates no key of PetPair with a key of another table, so it relates PetPair to no row of the query. State the relation as a key comparison: on(PetPair_.<key>.id eq <Table>_.id), or on(PetPair_.<key> eq <Table>_.<key>) where both keys reference one table."), e.getMessage());
        // The primary keys of two tables relate them through no key.
        var primaryKeys = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.id, EQUALS, Pet_.id)));
        assertTrue(primaryKeys.getMessage().contains("semiJoin(PetPair): the predicate equates no key"), primaryKeys.getMessage());
        // A predicate naming the joined table alone holds for every row of the query.
        var alone = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .antiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, PetPair_.second.id)));
        assertTrue(alone.getMessage().contains("antiJoin(PetPair): the predicate equates no key"), alone.getMessage());
        // Either key refers to the row: an OR holds no key the subquery could correlate on.
        var either = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, Pet_.id).or(it.where(PetPair_.second.id, EQUALS, Pet_.id))));
        assertTrue(either.getMessage().contains("semiJoin(PetPair): the predicate equates no key"), either.getMessage());
    }

    @Test
    public void semiJoinOnAPredicateTakesTheKeyFromAnyTerm() {
        // The key match need not come first: the other terms are the condition on the related rows.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(it -> it.where(Visit_.description, EQUALS, "spayed").and(it.where(Visit_.pet.id, EQUALS, Pet_.id)))
                .getResultList());
        assertEquals(List.of("Basil", "George", "Jewel", "Leo", "Rosy", "Samantha"), petNames(pets));
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ?)"), statement());
    }

    @Test
    public void semiJoinOnAPredicateSkipsTheTermsEquatingNoKey() {
        // An equality of two columns that are no keys, or of a column beyond a key, is a condition like any other.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(it -> it.where(Visit_.description, EQUALS, Pet_.name)
                        .and(it.where(Visit_.pet.id, EQUALS, Pet_.id)))
                .getResultList());
        assertEquals(List.of(), petNames(pets));
        // The subquery joins the visit's pet, so a type reference to Pet names that occurrence, the same row as p.
        assertTrue(statement().contains("INNER JOIN pet p1 ON v.pet_id = p1.id WHERE v.pet_id = p.id AND v.description = p1.name)"), statement());
        // A column beyond the key names the row the key reaches.
        var beyond = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(it -> it.where(Visit_.pet.name, EQUALS, Visit_.description)
                        .and(it.where(Visit_.pet.id, EQUALS, Pet_.id)))
                .getResultList());
        assertEquals(List.of(), petNames(beyond));
        assertTrue(statement().contains("INNER JOIN pet p1 ON v.pet_id = p1.id WHERE v.pet_id = p.id AND p1.name = v.description)"), statement());
    }

    @Test
    public void semiJoinOnAPredicateRefusesKeysThatDoNotRelateTheTables() {
        // Neither side belongs to the joined table.
        var neither = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(Visit_.pet.id, EQUALS, Pet_.id)));
        assertTrue(neither.getMessage().contains("semiJoin(PetPair): the predicate equates no key"), neither.getMessage());
        // A key of the joined table equated with the primary key of a table it does not reference.
        var otherTable = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first.id, EQUALS, Owner_.id)));
        assertTrue(otherTable.getMessage().contains("semiJoin(PetPair): the predicate equates no key"), otherTable.getMessage());
        // The primary key of the joined table equated with a key referencing another table.
        var otherKey = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(Owner.class).on(it -> it.where(Owner_.id, EQUALS, Pet_.type.id)));
        assertTrue(otherKey.getMessage().contains("semiJoin(Owner): the predicate equates no key"), otherKey.getMessage());
        // On the query's own table, two different keys of the table relate no row to the query's row.
        var twoKeys = assertThrows(PersistenceException.class, () -> orm().entity(PetPair.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.first, EQUALS, PetPair_.second)));
        assertTrue(twoKeys.getMessage().contains("semiJoin(PetPair): the predicate equates no key"), twoKeys.getMessage());
    }

    @Test
    public void semiJoinOnTheQueryTableRefusesAKeyOfAnotherTable() {
        record PairNote(@PK Integer id, @FK PetPair pair) implements Entity<Integer> {}
        var pairId = Metamodel.<PairNote, Integer>of(PairNote.class, "pair.id");
        var e = assertThrows(PersistenceException.class, () -> orm().entity(PetPair.class).select()
                .semiJoin(PetPair.class).on(it -> it.where(PetPair_.id, EQUALS, pairId)));
        assertTrue(e.getMessage().contains("semiJoin(PetPair).on(PairNote_.pair eq PetPair_.id): a semiJoin on the query's own table finds the PetPair rows that reference the same row as the query's row, which takes a key of PetPair equated with itself: on(PetPair_.<key> eq PetPair_.<key>)."), e.getMessage());
    }

    @Test
    public void semiJoinOnTheQueryTableHoldingTwoKeysNamesTheSelfForm() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(PetPair.class).select()
                .semiJoin(PetPair.class).on(Pet.class));
        assertTrue(e.getMessage().contains("Name it: on(PetPair_.first eq PetPair_.first), with one semiJoin per key"), e.getMessage());
    }

    @Test
    public void semiJoinOnAPredicateOverARefKey() {
        // A key held as a Ref compares through the key beyond it as an entity key does.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(PetType.class).on(it -> it.where(Pet_.type.id, EQUALS, PetType_.id).and(it.where(PetType_.name, EQUALS, "dog")))
                .getResultList());
        assertEquals(4, pets.size());
        assertTrue(statement().contains("WHERE EXISTS (SELECT 1 FROM pet_type pt WHERE pt.id = p.type_id AND pt.name = ?)"), statement());
    }

    @Test
    public void filterJoinSharingTwoPairsOfKeysNamesThePairForm() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(PetPair.class).on(Visit.class));
        assertTrue(e.getMessage().contains("Name the pair: on(PetPair_.<key> eq Visit_.<key>)"), e.getMessage());
    }

    @Test
    public void semiJoinSharingNoForeignKeyIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(Visit.class).on(Pet.class)
                .semiJoin(Tenant.class).on(Visit.class));
        assertTrue(e.getMessage().contains("semiJoin(Tenant).on(Visit): no foreign key relates Tenant and Visit"), e.getMessage());
    }

    @Test
    public void filterJoinConditionNamesTheQueryTable() {
        var visitCount = orm().entity(Visit.class).select().getResultCount();
        var visits = capture(() -> orm().entity(Visit.class).select()
                .semiJoin(Pet.class).on(Visit.class, it -> it.where(Pet_.name, NOT_EQUALS, Visit_.description))
                .getResultList());
        assertEquals(visitCount, visits.size());
        assertTrue(statement().contains("WHERE p1.id = v.pet_id AND p1.name <> v.description"), statement());
        var named = orm().entity(Visit.class).select()
                .semiJoin(Pet.class).on(Visit.class, it -> it.where(Pet_.name, EQUALS, Visit_.description))
                .getResultCount();
        assertEquals(0, named);
        var unnamed = orm().entity(Visit.class).select()
                .antiJoin(Pet.class).on(Visit.class, it -> it.where(Pet_.name, EQUALS, Visit_.description))
                .getResultCount();
        assertEquals(visitCount, unnamed);
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
