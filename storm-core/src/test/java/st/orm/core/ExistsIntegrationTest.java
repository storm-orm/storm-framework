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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Visit;
import st.orm.core.template.ORMTemplate;

/**
 * An existence check stops at the first matching row instead of counting every row that matches.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ExistsIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private List<String> observeStatements(Runnable runnable) {
        List<String> statements = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), runnable);
        return statements;
    }

    private String single(Runnable runnable) {
        var statements = observeStatements(runnable);
        assertEquals(1, statements.size(), statements::toString);
        return statements.getFirst();
    }

    @Test
    public void testAPlainQuerySelectsAConstantLimitedToOneRow() {
        var orm = ORMTemplate.of(dataSource);
        String statement = single(() -> assertTrue(orm.selectFrom(Pet.class).exists()));
        assertTrue(statement.startsWith("SELECT 1\nFROM pet"), statement);
        assertTrue(statement.endsWith("LIMIT 1"), statement);
        assertFalse(statement.contains("COUNT"), statement);
        assertFalse(statement.contains("FROM ("), statement);
    }

    @Test
    public void testAQueryWithoutAMatchDoesNotExist() {
        var orm = ORMTemplate.of(dataSource);
        assertTrue(orm.selectFrom(Pet.class).where(Pet_.name, EQUALS, "Leo").exists());
        assertFalse(orm.selectFrom(Pet.class).where(Pet_.name, EQUALS, "No such pet").exists());
    }

    @Test
    public void testDistinctAndOrderByAreLeftOut() {
        var orm = ORMTemplate.of(dataSource);
        String statement = single(() -> assertTrue(orm.selectFrom(Pet.class)
                .distinct()
                .innerJoin(Visit.class).on(Pet.class)
                .orderBy(Pet_.name)
                .exists()));
        assertFalse(statement.contains("DISTINCT"), statement);
        assertFalse(statement.contains("ORDER BY"), statement);
        assertFalse(statement.contains("FROM ("), statement);
    }

    @Test
    public void testLimitAndOffsetDecideWhetherARowComesBack() {
        var orm = ORMTemplate.of(dataSource);
        int total = (int) orm.selectFrom(Visit.class).getResultCount();
        String statement = single(() -> assertTrue(orm.selectFrom(Visit.class).offset(total - 1).exists()));
        assertTrue(statement.contains("FROM ("), statement);
        assertFalse(orm.selectFrom(Visit.class).offset(total).exists());
        assertFalse(orm.selectFrom(Visit.class).limit(0).exists());
        assertTrue(orm.selectFrom(Visit.class).offset(total - 2).limit(5).exists());
    }

    @Test
    public void testHavingDecidesWhetherAGroupComesBack() {
        var orm = ORMTemplate.of(dataSource);
        assertTrue(orm.selectFrom(Pet.class).groupBy(Pet_.owner).having(raw("COUNT(*) > 1")).exists());
        String statement = single(() -> assertFalse(orm.selectFrom(Pet.class)
                .groupBy(Pet_.owner).having(raw("COUNT(*) > 100")).exists()));
        assertTrue(statement.contains("FROM ("), statement);
    }

    @Test
    public void testHavingWithoutGroupByDecidesWhetherARowComesBack() {
        var orm = ORMTemplate.of(dataSource);
        // The whole table is one group, which HAVING keeps or drops.
        assertTrue(orm.selectFrom(Pet.class).having(raw("COUNT(*) > 1")).exists());
        String statement = single(() -> assertFalse(orm.selectFrom(Pet.class).having(raw("COUNT(*) > 100")).exists()));
        assertTrue(statement.contains("FROM ("), statement);
    }

    @Test
    public void testASubqueryIsRefused() {
        var orm = ORMTemplate.of(dataSource);
        var failure = assertThrows(PersistenceException.class, () -> orm.subquery(Visit.class).exists());
        assertEquals("Cannot build a query from a subquery.", failure.getMessage());
    }

    @Test
    public void testACustomSelectIsTestedAsADerivedTable() {
        var orm = ORMTemplate.of(dataSource);
        // An aggregate without GROUP BY returns a row whether or not any row matches.
        var total = orm.entity(Pet.class).select(Long.class, raw("COUNT(*)")).where(Pet_.name, EQUALS, "No such pet");
        String statement = single(() -> assertTrue(total.exists()));
        assertTrue(statement.contains("FROM ("), statement);
        assertFalse(orm.entity(City.class).select(String.class, raw("\0.name", City.class))
                .where(raw("1 = 0")).exists());
    }

    @Test
    public void testASemiJoinIsTestedDirectly() {
        var orm = ORMTemplate.of(dataSource);
        String statement = single(() -> assertTrue(orm.selectFrom(Pet.class)
                .semiJoin(Visit.class).on(Pet.class)
                .exists()));
        assertTrue(statement.startsWith("SELECT 1\n"), statement);
    }

    @Test
    public void testALockingQueryReadsItsFirstRow() {
        var orm = ORMTemplate.of(dataSource);
        String statement = single(() -> assertTrue(orm.selectFrom(Pet.class).forUpdate().exists()));
        assertTrue(statement.contains("FOR UPDATE"), statement);
    }

    @Test
    public void testRepositoryExistenceChecksStopAtTheFirstRow() {
        var orm = ORMTemplate.of(dataSource);
        var pets = orm.entity(Pet.class);
        for (var statement : observeStatements(() -> {
            assertTrue(pets.exists());
            assertTrue(pets.existsById(1));
            assertFalse(pets.existsById(-1));
            assertTrue(pets.existsByRef(Ref.of(Pet.class, 1)));
            assertTrue(orm.entity(Owner.class).existsById(1));
        })) {
            assertTrue(statement.startsWith("SELECT 1\n"), statement);
            assertFalse(statement.contains("COUNT"), statement);
        }
    }
}
