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
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.Operator.NOT_IN;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;

import java.util.ArrayList;
import java.util.Arrays;
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
import st.orm.DbTable;
import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.StormConfig;
import st.orm.core.model.Owner;
import st.orm.core.model.OwnerPrimaryPet;
import st.orm.core.model.OwnerPrimaryPet_;
import st.orm.core.model.Pet;
import st.orm.core.model.PetPair;
import st.orm.core.model.PetPair_;
import st.orm.core.model.Pet_;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.VetSpecialtyPK;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.model.polymorphic.Animal;
import st.orm.core.model.polymorphic.Cat;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Sql;

/**
 * The root metamodel names the row itself, which its primary key identifies: a comparison on it compares the primary
 * key, as a comparison on a foreign key path compares the foreign key.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class RootMetamodelIntegrationTest {

    private static final Metamodel<Pet, Pet> PET = Metamodel.root(Pet.class);

    /** The pet table read without its primary key. */
    @DbTable("pet")
    public record PetName(String name) implements Data {}

    @Autowired
    private DataSource dataSource;

    private final List<Sql> statements = new ArrayList<>();

    private ORMTemplate orm() {
        return ORMTemplate.of(dataSource);
    }

    private <T> T capture(Supplier<T> query) {
        statements.clear();
        var result = new ArrayList<T>(1);
        observe(statements::add, () -> result.add(query.get()));
        return result.getFirst();
    }

    private String statement() {
        return statements.getLast().statement().replaceAll("\\s+", " ");
    }

    private List<Object> values() {
        return statements.getLast().parameters().stream().map(parameter -> (Object) parameter.dbValue()).toList();
    }

    private Pet pet(int id) {
        return orm().entity(Pet.class).getById(id);
    }

    private static List<Integer> ids(List<Pet> pets) {
        return pets.stream().map(Pet::id).sorted().toList();
    }

    @Test
    public void equalsComparesThePrimaryKey() {
        var pets = capture(() -> orm().entity(Pet.class).select().where(PET, EQUALS, pet(3)).getResultList());
        assertEquals(List.of(3), ids(pets));
        assertTrue(statement().contains("WHERE p.id = ?"), statement());
        assertEquals(List.of(3), values());
    }

    @Test
    public void notEqualsComparesThePrimaryKey() {
        var pets = capture(() -> orm().entity(Pet.class).select().where(PET, NOT_EQUALS, pet(3)).getResultList());
        assertEquals(12, pets.size());
        assertFalse(ids(pets).contains(3));
        assertTrue(statement().contains("WHERE p.id <> ?"), statement());
    }

    @Test
    public void aRecordComparesThePrimaryKey() {
        var pets = orm().entity(Pet.class).select().where(PET, pet(4)).getResultList();
        assertEquals(List.of(4), ids(pets));
    }

    @Test
    public void inComparesThePrimaryKeys() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .where(PET, IN, List.of(pet(1), pet(2), pet(5))).getResultList());
        assertEquals(List.of(1, 2, 5), ids(pets));
        assertTrue(statement().contains("WHERE p.id IN (?, ?, ?)"), statement());
        assertEquals(List.of(1, 2, 5), values());
    }

    @Test
    public void notInLeavesOutTheRows() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .where(PET, NOT_IN, List.of(pet(1), pet(2))).getResultList());
        assertEquals(11, pets.size());
        assertFalse(ids(pets).contains(1));
        assertFalse(ids(pets).contains(2));
        assertTrue(statement().contains("WHERE p.id NOT IN (?, ?)"), statement());
    }

    @Test
    public void aSingleElementList() {
        assertEquals(List.of(6), ids(orm().entity(Pet.class).select().where(PET, IN, List.of(pet(6))).getResultList()));
        assertEquals(12, orm().entity(Pet.class).select().where(PET, NOT_IN, List.of(pet(6))).getResultCount());
    }

    @Test
    public void anEmptyListMatchesNoRowAndItsNegationEveryRow() {
        assertEquals(0, orm().entity(Pet.class).select().where(PET, IN, List.<Pet>of()).getResultCount());
        assertEquals(13, orm().entity(Pet.class).select().where(PET, NOT_IN, List.<Pet>of()).getResultCount());
    }

    @Test
    public void aNullAmongTheRowsMatchesNoRow() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .where(PET, IN, Arrays.asList(pet(1), null)).getResultList());
        assertEquals(List.of(1), ids(pets));
        assertTrue(statement().contains("WHERE p.id IN (?, ?)"), statement());
    }

    @Test
    public void refsCompareThePrimaryKey() {
        var pets = orm().entity(Pet.class).select()
                .whereRef(PET, List.of(Ref.of(Pet.class, 2), Ref.of(Pet.class, 7))).getResultList();
        assertEquals(List.of(2, 7), ids(pets));
        assertEquals(List.of(9), ids(orm().entity(Pet.class).select().where(PET, Ref.of(Pet.class, 9)).getResultList()));
    }

    @Test
    public void combinesWithAForeignKeyPath() {
        var owners = List.of(orm().entity(Owner.class).getById(3), orm().entity(Owner.class).getById(6));
        var pets = capture(() -> orm().entity(Pet.class).select()
                .where(it -> it.where(Pet_.owner, IN, owners).and(it.where(PET, NOT_IN, List.of(pet(3), pet(8)))))
                .getResultList());
        assertEquals(List.of(4, 7), ids(pets));
        assertTrue(statement().contains("p.owner_id IN (?, ?)") && statement().contains("p.id NOT IN (?, ?)"), statement());
    }

    @Test
    public void combinesByOr() {
        var pets = orm().entity(Pet.class).select()
                .where(it -> it.where(PET, EQUALS, pet(1)).or(it.where(PET, EQUALS, pet(2))))
                .getResultList();
        assertEquals(List.of(1, 2), ids(pets));
    }

    @Test
    public void aPaddedListRepeatsItsLastRow() {
        var orm = ORMTemplate.builder(dataSource).config(StormConfig.of(Map.of(StormConfig.IN_LIST, "padded"))).build();
        var pets = capture(() -> orm.entity(Pet.class).select()
                .where(PET, IN, List.of(pet(1), pet(2), pet(3))).getResultList());
        assertEquals(List.of(1, 2, 3), ids(pets));
        assertTrue(statement().contains("WHERE p.id IN (?, ?, ?, ?)"), statement());
        assertEquals(List.of(1, 2, 3, 3), values());
    }

    @Test
    public void anInlinedListRendersTheKeysAsLiterals() {
        var captured = new ArrayList<Sql>();
        observe(sqlTemplate -> sqlTemplate.withInlineParameters(true), captured::add,
                () -> orm().entity(Pet.class).select().where(PET, NOT_IN, List.of(pet(1), pet(2))).getResultList());
        var sql = captured.getLast().statement().replaceAll("\\s+", " ");
        assertTrue(sql.contains("WHERE p.id NOT IN (1, 2)"), sql);
    }

    @Test
    public void aCompoundPrimaryKeyComparesComponentWise() {
        var root = Metamodel.root(VetSpecialty.class);
        var specialties = orm().entity(VetSpecialty.class);
        var first = specialties.getById(new VetSpecialtyPK(2, 1));
        var second = specialties.getById(new VetSpecialtyPK(3, 2));
        var in = capture(() -> specialties.select().where(root, IN, List.of(first, second)).getResultList());
        assertEquals(List.of(first.id(), second.id()), in.stream().map(VetSpecialty::id).toList());
        assertTrue(statement().contains("vet_id") && statement().contains("specialty_id"), statement());
        assertEquals(3, specialties.select().where(root, NOT_IN, List.of(first, second)).getResultCount());
        assertEquals(List.of(first.id()), specialties.select().where(root, EQUALS, first).getResultList()
                .stream().map(VetSpecialty::id).toList());
        assertEquals(4, specialties.select().where(root, NOT_EQUALS, first).getResultCount());
    }

    @Test
    public void theRowComparesWithTheRowOfTheEnclosingQuery() {
        // The pets that share their owner with another pet.
        var pets = capture(() -> orm().entity(Pet.class).select()
                .whereExists(orm().selectFrom(Pet.class).where(it -> it.where(Pet_.owner, EQUALS, outer(Pet_.owner))
                        .and(it.where(PET, NOT_EQUALS, outer(PET)))))
                .getResultList());
        assertEquals(List.of(3, 4, 7, 8), ids(pets));
        assertTrue(statement().contains(".id <> p.id"), statement());
    }

    @Test
    public void aForeignKeyComparesWithTheRowItReferences() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .where(Pet_.owner, EQUALS, Metamodel.root(Owner.class))
                .getResultList());
        assertEquals(12, pets.size());
        assertTrue(statement().contains("p.owner_id = o.id"), statement());
    }

    @Test
    public void aJoinPredicateOnTheRowJoinsByItsKey() {
        var owners = orm().entity(Owner.class);
        orm().entity(OwnerPrimaryPet.class).insert(List.of(
                new OwnerPrimaryPet(owners.getById(1), pet(1)),
                new OwnerPrimaryPet(owners.getById(3), pet(4))));
        var pets = capture(() -> orm().entity(Pet.class).select()
                .innerJoin(OwnerPrimaryPet.class).on(it -> it.where(OwnerPrimaryPet_.pet, EQUALS, PET))
                .getResultList());
        assertEquals(List.of(1, 4), ids(pets));
        assertTrue(statement().contains("INNER JOIN owner_primary_pet opp ON opp.pet_id = p.id"), statement());
    }

    @Test
    public void aFilterJoinOnTheRowRelatesByItsKey() {
        var owners = orm().entity(Owner.class);
        orm().entity(OwnerPrimaryPet.class).insert(List.of(
                new OwnerPrimaryPet(owners.getById(1), pet(1)),
                new OwnerPrimaryPet(owners.getById(3), pet(4))));
        var pets = capture(() -> orm().entity(Pet.class).select()
                .semiJoin(OwnerPrimaryPet.class).on(it -> it.where(PET, EQUALS, OwnerPrimaryPet_.pet))
                .getResultList());
        assertEquals(List.of(1, 4), ids(pets));
        assertTrue(statement().contains("EXISTS") && statement().contains(".pet_id = p.id"), statement());
    }

    @Test
    public void aFilterJoinOnARowWithoutAPrimaryKeyRelatesByNoKey() {
        var refused = assertThrows(PersistenceException.class, () -> orm().entity(Pet.class).select()
                .semiJoin(PetName.class).on(it -> it.where(Metamodel.root(PetName.class), EQUALS, Metamodel.root(PetName.class)))
                .getResultList());
        assertTrue(refused.getMessage().contains("equates no key of PetName"), refused.getMessage());
    }

    @Test
    public void theRowOfAJoinedTableResolvesAtItsJoin() {
        var leo = pet(1);
        var visits = capture(() -> orm().entity(Visit.class).select()
                .where(Visit_.pet, EQUALS, leo)
                .getResultList());
        var byRoot = capture(() -> orm().entity(Visit.class).select()
                .innerJoin(Pet.class).on(Visit.class)
                .where(it -> it.where(Visit_.pet, EQUALS, PET).and(it.where(Visit_.pet, leo)))
                .getResultList());
        assertEquals(visits, byRoot);
    }

    @Test
    public void aTableReachedTwiceIsAmbiguous() {
        var refused = assertThrows(PersistenceException.class, () -> orm().entity(PetPair.class).select()
                .where(PetPair_.first, EQUALS, Metamodel.root(Pet.class))
                .getResultList());
        assertTrue(refused.getMessage().contains("Multiple paths found for Pet"), refused.getMessage());
    }

    @Test
    public void theRootOfASingleTableHierarchyComparesThePrimaryKey() {
        var animals = orm().entity(Animal.class);
        var whiskers = animals.getById(1);
        var others = capture(() -> animals.select().where(Metamodel.root(Animal.class), NOT_IN, List.of(whiskers))
                .getResultList());
        assertEquals(3, others.size());
        assertTrue(statement().contains("NOT IN (?)"), statement());
        var cat = animals.select().where(Metamodel.root(Cat.class), EQUALS, (Cat) whiskers).getResultList();
        assertEquals(List.of(whiskers), cat);
    }

    @Test
    public void theRootOfAJoinedHierarchyComparesThePrimaryKey() {
        var animals = orm().entity(JoinedAnimal.class);
        var whiskers = animals.getById(1);
        assertEquals(2, animals.select().where(Metamodel.root(JoinedAnimal.class), NOT_IN, List.of(whiskers))
                .getResultCount());
        assertEquals(List.of(whiskers), animals.select()
                .where(Metamodel.root(JoinedCat.class), EQUALS, (JoinedCat) whiskers).getResultList());
    }

    @Test
    public void orderByTheRootOrdersByThePrimaryKey() {
        var pets = capture(() -> orm().entity(Pet.class).select().orderByDescending(PET).getResultList());
        assertEquals(13, pets.getFirst().id());
        assertTrue(statement().contains("ORDER BY p.id DESC"), statement());
    }

    @Test
    public void groupByTheRootGroupsByThePrimaryKey() {
        var counts = capture(() -> orm().selectFrom(Pet.class, Long.class, raw("COUNT(*)"))
                .groupBy(PET)
                .getResultList());
        assertEquals(13, counts.size());
        assertTrue(statement().contains("GROUP BY p.id"), statement());
    }

    @Test
    public void deleteFiltersOnTheRow() {
        var visits = orm().entity(Visit.class);
        var first = visits.getById(1);
        int deleted = visits.delete().where(Metamodel.root(Visit.class), IN, List.of(first)).executeUpdate();
        assertEquals(1, deleted);
        assertTrue(visits.findById(1).isEmpty());
    }

    @Test
    public void updateFiltersOnTheRow() {
        var pets = orm().entity(Pet.class);
        int updated = pets.update().set(Pet_.name, "Leonard").where(PET, EQUALS, pet(1)).executeUpdate();
        assertEquals(1, updated);
        assertEquals("Leonard", pet(1).name());
        assertEquals("Basil", pet(2).name());
    }

    @Test
    public void aTableWithoutAPrimaryKeyIsRefusedByName() {
        var refused = assertThrows(PersistenceException.class, () -> orm().selectFrom(PetName.class)
                .where(Metamodel.root(PetName.class), IN, List.of(new PetName("Leo")))
                .getResultList());
        assertTrue(refused.getMessage().contains("Cannot compare PetName itself: it declares no primary key"),
                refused.getMessage());
    }
}
