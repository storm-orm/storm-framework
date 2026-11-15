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
package st.orm.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static st.orm.Metamodel.outer;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.Operator.NOT_IN;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Metamodel;
import st.orm.Ref;
import st.orm.template.model.Owner;
import st.orm.template.model.Pet;
import st.orm.template.model.Pet_;

/**
 * The root metamodel names the row itself, which its primary key identifies, in each form the where builder takes.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class RootMetamodelTest {

    private static final Metamodel<Pet, Pet> PET = Metamodel.root(Pet.class);

    @Autowired
    private ORMTemplate orm;

    private List<Pet> pets(Integer... ids) {
        return orm.entity(Pet.class).findAllById(List.of(ids));
    }

    private static List<Integer> ids(List<Pet> pets) {
        return pets.stream().map(Pet::id).sorted().toList();
    }

    @Test
    public void eachOperatorComparesThePrimaryKey() {
        var pets = orm.entity(Pet.class);
        var rosy = pets.getById(3);
        assertEquals(List.of(3), ids(pets.select().where(PET, EQUALS, rosy).getResultList()));
        assertFalse(ids(pets.select().where(PET, NOT_EQUALS, rosy).getResultList()).contains(3));
        assertEquals(List.of(1, 2), ids(pets.select().where(PET, IN, pets(1, 2)).getResultList()));
        assertEquals(11, pets.select().where(PET, NOT_IN, pets(1, 2)).getResultCount());
    }

    @Test
    public void recordsAndRefsCompareThePrimaryKey() {
        var pets = orm.entity(Pet.class);
        assertEquals(List.of(4), ids(pets.select().where(PET, pets.getById(4)).getResultList()));
        assertEquals(List.of(5, 6), ids(pets.select().where(PET, pets(5, 6)).getResultList()));
        assertEquals(List.of(9), ids(pets.select().where(PET, Ref.of(Pet.class, 9)).getResultList()));
        assertEquals(List.of(2, 7), ids(pets.select()
                .whereRef(PET, List.of(Ref.of(Pet.class, 2), Ref.of(Pet.class, 7))).getResultList()));
    }

    @Test
    public void theRowCombinesWithAForeignKeyPath() {
        var owners = orm.entity(Owner.class).findAllById(List.of(3, 6));
        var result = orm.entity(Pet.class).select()
                .where(it -> it.where(Pet_.owner, IN, owners).and(it.where(PET, NOT_IN, pets(3, 8))))
                .getResultList();
        assertEquals(List.of(4, 7), ids(result));
    }

    @Test
    public void theRowComparesWithTheRowOfTheEnclosingQuery() {
        var result = orm.entity(Pet.class).select()
                .whereExists(orm.selectFrom(Pet.class).where(it -> it.where(Pet_.owner, EQUALS, outer(Pet_.owner))
                        .and(it.where(PET, NOT_EQUALS, outer(PET)))))
                .getResultList();
        assertEquals(List.of(3, 4, 7, 8), ids(result));
    }
}
