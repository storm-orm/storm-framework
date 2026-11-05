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
import static st.orm.Operator.IS_NOT_NULL;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;

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
import st.orm.PersistenceException;
import st.orm.core.model.Owner;
import st.orm.core.model.OwnerPrimaryPet;
import st.orm.core.model.OwnerPrimaryPet_;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.template.ORMTemplate;

/**
 * A predicate compares the column at a path with the column at another path of the query, without binding a value.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ColumnComparisonIntegrationTest {

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
        return statements.getLast().replaceAll("\\s+", " ");
    }

    /** Leo (pet 1) is the primary pet of its owner; Rosy (pet 3, of owner 3) is recorded as owner 2's primary pet. */
    private void recordPrimaryPets() {
        var owners = orm().entity(Owner.class);
        var pets = orm().entity(Pet.class);
        orm().entity(OwnerPrimaryPet.class).insert(List.of(
                new OwnerPrimaryPet(owners.getById(1), pets.getById(1)),
                new OwnerPrimaryPet(owners.getById(2), pets.getById(3))));
    }

    @Test
    public void columnsOfOneTableCompareWithoutAParameter() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .where(Owner_.firstName, NOT_EQUALS, Owner_.lastName)
                .getResultList());
        assertEquals(10, owners.size());
        assertTrue(statement().contains("WHERE o.first_name <> o.last_name"), statement());
        assertFalse(statement().contains("?"), statement());
    }

    @Test
    public void keyComparesWithAKeyToTheSameTable() {
        recordPrimaryPets();
        var pets = capture(() -> orm().entity(Pet.class).select()
                .innerJoin(OwnerPrimaryPet.class).on(Pet.class)
                .where(OwnerPrimaryPet_.owner, EQUALS, Pet_.owner)
                .getResultList());
        assertEquals(List.of("Leo"), pets.stream().map(Pet::name).toList());
        assertTrue(statement().contains(".owner_id = p.owner_id"), statement());
    }

    @Test
    public void keysCompareInAJoinCondition() {
        recordPrimaryPets();
        var pets = capture(() -> orm().entity(Pet.class).select()
                .innerJoin(OwnerPrimaryPet.class).on(Pet.class, it -> it.where(OwnerPrimaryPet_.owner, EQUALS, Pet_.owner))
                .getResultList());
        assertEquals(List.of("Leo"), pets.stream().map(Pet::name).toList());
        assertTrue(statement().contains(".pet_id = p.id AND (") && statement().contains(".owner_id = p.owner_id)"), statement());
    }

    @Test
    public void pathBeyondAKeyResolvesAtTheOccurrenceItReaches() {
        recordPrimaryPets();
        var visitsOfLeo = orm().entity(Visit.class).select()
                .where(Visit_.pet, orm().entity(Pet.class).getById(1))
                .getResultCount();
        var visits = capture(() -> orm().entity(Visit.class).select()
                .innerJoin(OwnerPrimaryPet.class).on(it -> it.where(OwnerPrimaryPet_.pet, EQUALS, Visit_.pet))
                .where(OwnerPrimaryPet_.owner, EQUALS, Visit_.pet.owner)
                .getResultList());
        assertEquals(visitsOfLeo, visits.size());
        assertTrue(visitsOfLeo > 0);
        assertTrue(statement().contains(".owner_id = p.owner_id"), statement());
    }

    @Test
    public void columnsCompareInHaving() {
        var counts = capture(() -> orm().selectFrom(Owner.class, Long.class, raw("COUNT(*)"))
                .groupBy(Owner_.firstName, Owner_.lastName)
                .having(Owner_.firstName, NOT_EQUALS, Owner_.lastName)
                .getResultList());
        assertEquals(10, counts.size());
        assertTrue(statement().contains("HAVING o.first_name <> o.last_name"), statement());
    }

    @Test
    public void keyBeyondAForeignKeyOfAJoinedTableIsItsKeyColumn() {
        var visitsOfOwnedPets = orm().entity(Visit.class).select()
                .where(Visit_.pet.owner.firstName, IS_NOT_NULL)
                .getResultCount();
        var visits = capture(() -> orm().entity(Owner.class).select()
                .crossJoin(Visit.class)
                .innerJoin(Pet.class).on(Visit.class, it -> it.where(Pet_.owner.id, EQUALS, Owner_.id))
                .getResultList());
        assertEquals(visitsOfOwnedPets, visits.size());
        assertTrue(visitsOfOwnedPets > 0);
        assertTrue(statement().contains("INNER JOIN pet p ON v.pet_id = p.id AND (p.owner_id = o.id)"), statement());
        var petsOfOwner = orm().entity(Pet.class).select()
                .where(Pet_.owner, orm().entity(Owner.class).getById(1))
                .getResultCount();
        var owners = orm().entity(Owner.class).select().getResultCount();
        var pairs = capture(() -> orm().entity(Owner.class).select()
                .crossJoin(Pet.class)
                .where(Pet_.owner.id, EQUALS, 1)
                .getResultList());
        assertEquals(owners * petsOfOwner, pairs.size());
        assertTrue(statement().contains("WHERE p.owner_id = ?"), statement());
        assertFalse(statement().contains("JOIN owner o1"), statement());
    }

    @Test
    public void columnBeyondAKeyOfAJoinedTableIsRefusedInAJoinCondition() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .crossJoin(Visit.class)
                .innerJoin(Pet.class).on(Visit.class, it -> it.where(Pet_.owner.firstName, EQUALS, Owner_.firstName))
                .getResultList());
        var message = e.getCause().getMessage();
        assertTrue(message.contains("Owner is not part of this query rooted at Owner"), message);
    }

    @Test
    public void columnOutsideTheQueryIsRefused() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .where(Owner_.firstName, EQUALS, Pet_.name)
                .getResultList());
        var message = e.getCause().getMessage();
        assertTrue(message.contains("Pet is not part of this query rooted at Owner"), message);
    }
}
