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
        return orm().entity(Owner.class).select().semiJoin(Pet.class).on(Owner.class);
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
    public void semiJoinWithoutConditionIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> ownersWithPets().getResultList());
        assertTrue(e.getMessage().contains("semiJoin(Pet) filters nothing"), e.getMessage());
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
