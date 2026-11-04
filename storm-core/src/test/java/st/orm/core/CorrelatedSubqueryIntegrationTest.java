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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Visit;
import st.orm.core.model.Visit_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

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
}
