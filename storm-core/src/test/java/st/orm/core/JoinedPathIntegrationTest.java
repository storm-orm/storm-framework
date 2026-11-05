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
import static st.orm.core.template.TemplateString.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.template.ORMTemplate;

/**
 * A path from a table the query joins explicitly joins the table it reaches on demand, as a path from the root does.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class JoinedPathIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private final List<String> statements = new ArrayList<>();

    public record LastNameAndType(String lastName, String type) {}

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

    @Test
    public void pathFromJoinedTableFiltersThroughTheTableItReaches() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .innerJoin(Pet.class).on(Owner.class)
                .where(Pet_.type.name, EQUALS, "dog")
                .getResultList());
        assertEquals(4, owners.size());
        assertEquals(Set.of(3, 8, 10), owners.stream().map(Owner::id).collect(Collectors.toSet()));
        assertTrue(statement().contains("INNER JOIN pet p ON p.owner_id = o.id INNER JOIN pet_type pt ON p.type_id = pt.id"), statement());
        assertTrue(statement().contains("WHERE pt.name = ?"), statement());
    }

    @Test
    public void pathFromJoinedTableSelectsThroughTheTableItReaches() {
        var rows = orm().selectFrom(Owner.class, LastNameAndType.class, raw("\0, \0", Owner_.lastName, Pet_.type.name))
                .innerJoin(Pet.class).on(Owner.class)
                .getResultList();
        assertEquals(12, rows.size());
        assertEquals(List.of("cat", "lizard"), rows.stream()
                .filter(row -> row.lastName().equals("Davis"))
                .map(LastNameAndType::type)
                .sorted()
                .toList());
    }

    @Test
    public void pathFromJoinedTableGroupsThroughTheTableItReaches() {
        var counts = orm().selectFrom(Owner.class, Long.class, raw("COUNT(*)"))
                .innerJoin(Pet.class).on(Owner.class)
                .groupBy(Pet_.type.name)
                .getResultList();
        assertEquals(6, counts.size());
        assertEquals(12L, counts.stream().mapToLong(Long::longValue).sum());
    }

    @Test
    public void pathFromJoinedTableOrdersThroughTheTableItReaches() {
        var rows = orm().selectFrom(Owner.class, LastNameAndType.class, raw("\0, \0", Owner_.lastName, Pet_.type.name))
                .innerJoin(Pet.class).on(Owner.class)
                .orderBy(Pet_.type.name)
                .getResultList();
        var types = rows.stream().map(LastNameAndType::type).toList();
        assertEquals(types.stream().sorted().toList(), types);
    }

    @Test
    public void pathFromLeftJoinedTableJoinsTheTableItReachesWithALeftJoin() {
        var owners = capture(() -> orm().entity(Owner.class).select()
                .leftJoin(Pet.class).on(Owner.class)
                .where(Pet_.type.name, EQUALS, "dog")
                .getResultList());
        assertEquals(4, owners.size());
        assertTrue(statement().contains("LEFT JOIN pet p ON p.owner_id = o.id LEFT JOIN pet_type pt ON p.type_id = pt.id"), statement());
    }

    @Test
    public void selectedJoinedEntityIsHydratedWithItsEagerForeignKeys() {
        var pets = capture(() -> orm().entity(Owner.class).select(Pet.class)
                .innerJoin(Pet.class).on(Owner.class)
                .getResultList());
        assertEquals(12, pets.size());
        assertTrue(pets.stream().allMatch(pet -> pet.owner() != null && pet.owner().lastName() != null));
        assertTrue(statement().contains("o.last_name, o.address, o.city_id, c.name, o.telephone, o.version FROM owner o LEFT JOIN city c ON o.city_id = c.id INNER JOIN pet p ON p.owner_id = o.id"), statement());
        assertFalse(statement().contains("o1"), statement());
    }

    @Test
    public void keyEquatedToTheQueryTableByAPredicateIsNotJoinedAgain() {
        var pets = capture(() -> orm().entity(Owner.class).select(Pet.class)
                .innerJoin(Pet.class).on(it -> it.where(Pet_.owner.id, EQUALS, Owner_.id))
                .where(Pet_.owner.lastName, EQUALS, "Davis")
                .getResultList());
        assertEquals(2, pets.size());
        assertTrue(statement().contains("INNER JOIN pet p ON p.owner_id = o.id"), statement());
        assertTrue(statement().contains("WHERE o.last_name = ?"), statement());
        assertFalse(statement().contains("o1"), statement());
    }

    @Test
    public void pathFromJoinedTableReachesTwoLevelsDeep() {
        var count = capture(() -> orm().selectFrom(Owner.class, Long.class, raw("COUNT(*)"))
                .innerJoin(Pet.class).on(Owner.class)
                .innerJoin(Visit.class).on(Pet.class)
                .where(Visit_.pet.type.name, EQUALS, "cat")
                .getSingleResult());
        assertEquals(6L, count);
        assertTrue(statement().contains("INNER JOIN pet p1 ON v.pet_id = p1.id INNER JOIN pet_type pt ON p1.type_id = pt.id"), statement());
    }

    @Test
    public void pathFromATableJoinedTwiceNamesTheJoins() {
        var e = assertThrows(PersistenceException.class, () -> orm().entity(Owner.class).select()
                .innerJoin(Pet.class).on(Owner.class)
                .innerJoin(Pet.class).on(Owner.class)
                .where(Pet_.type.name, EQUALS, "dog")
                .getResultList());
        var message = e.getCause().getMessage();
        assertTrue(message.contains("Multiple joins found for Pet: p, p1"), message);
    }
}
