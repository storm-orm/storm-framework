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
package st.orm.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.core.template.SqlInterceptor.observe;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;

/**
 * Delete builder conformance: a DELETE built through the query builder removes the rows its conditions select, on
 * every database, whether the conditions name the deleted table alone or reach other tables through a path, an
 * explicit join, a semi-join or an anti-join.
 *
 * <p>The dialect decides how the statement reaches other tables: joined into the DELETE on MySQL, MariaDB and SQL
 * Server, and through a subquery matching the primary key elsewhere. A DELETE without a condition refuses to run on
 * every database unless the builder or the query is marked unsafe, including where the subquery form carries a WHERE
 * clause of its own.</p>
 *
 * <p>The suite creates and drops its own tables, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractDeleteBuilderConformanceTest {

    @DbTable("delete_city")
    public record City(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    @DbTable("delete_owner")
    public record Owner(@PK(generation = NONE) Integer id,
                        String lastName,
                        @Nullable @FK City city) implements Entity<Integer> {}

    @DbTable("delete_pet")
    public record Pet(@PK(generation = NONE) Integer id,
                      String name,
                      @Nullable @FK Owner owner,
                      int visitCount) implements Entity<Integer> {}

    private static final Metamodel<Owner, String> OWNER_LAST_NAME = Metamodel.of(Owner.class, "lastName");
    private static final Metamodel<Pet, String> PET_NAME = Metamodel.of(Pet.class, "name");
    private static final Metamodel<Pet, Integer> PET_VISIT_COUNT = Metamodel.of(Pet.class, "visitCount");
    private static final Metamodel<Pet, String> PET_CITY_NAME = Metamodel.of(Pet.class, "owner.city.name");

    private static final List<String> TABLES = List.of("delete_pet", "delete_owner", "delete_city");

    private DataSource dataSource;
    private ORMTemplate orm;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly. The drop guards against tables a previous run left behind.
     */
    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dropTables();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE delete_city (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE delete_owner (id INT PRIMARY KEY, last_name VARCHAR(100) NOT NULL, city_id INT)");
            statement.execute("CREATE TABLE delete_pet (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, owner_id INT, visit_count INT NOT NULL)");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
        var madison = new City(1, "Madison");
        var monona = new City(2, "Monona");
        orm.entity(City.class).insert(List.of(madison, monona));
        var davis = new Owner(1, "Davis", madison);
        var franklin = new Owner(2, "Franklin", monona);
        var black = new Owner(3, "Black", null);
        orm.entity(Owner.class).insert(List.of(davis, franklin, black));
        orm.entity(Pet.class).insert(List.of(
                new Pet(1, "Leo", davis, 12),
                new Pet(2, "Basil", davis, 3),
                new Pet(3, "Rosy", franklin, 9),
                new Pet(4, "Jewel", franklin, 2),
                new Pet(5, "Stray", null, 1)));
    }

    @AfterEach
    final void dropTables() {
        // Not every dialect spells the drop conditionally: Oracle has no DROP TABLE IF EXISTS.
        for (var table : TABLES) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
            } catch (SQLException ignored) {
                // The table was not there to drop.
            }
        }
    }

    private <X> List<String> statements(Supplier<X> action) {
        var statements = new ArrayList<String>();
        observe(sql -> statements.add(sql.statement()), action);
        return statements;
    }

    private List<String> petNames() {
        return orm.entity(Pet.class).select().getResultList().stream().map(Pet::name).sorted().toList();
    }

    private List<String> ownerLastNames() {
        return orm.entity(Owner.class).select().getResultList().stream().map(Owner::lastName).sorted().toList();
    }

    @Test
    public void deletesTheRowsTheConditionSelects() {
        int deleted = orm.entity(Pet.class).delete()
                .where(PET_VISIT_COUNT, GREATER_THAN, 5)
                .executeUpdate();
        assertEquals(2, deleted);
        assertEquals(List.of("Basil", "Jewel", "Stray"), petNames());
    }

    @Test
    public void deletesTheRowsAPathToARelatedTableSelects() {
        var statements = statements(() -> orm.entity(Pet.class).delete()
                .where(PET_CITY_NAME, EQUALS, "Madison")
                .executeUpdate());
        assertEquals(1, statements.size(), statements.toString());
        assertEquals(List.of("Jewel", "Rosy", "Stray"), petNames());
    }

    @Test
    public void deletesTheRowsAnExplicitJoinSelects() {
        // Davis and Franklin each have two pets the condition matches, so the join meets each of their rows twice;
        // each row is deleted once.
        int deleted = orm.entity(Owner.class).delete()
                .innerJoin(Pet.class).on(Owner.class)
                .where(PET_VISIT_COUNT, GREATER_THAN, 1)
                .executeUpdate();
        assertEquals(2, deleted);
        assertEquals(List.of("Black"), ownerLastNames());
    }

    @Test
    public void deletesTheRowsASemiJoinSelects() {
        int deleted = orm.entity(Owner.class).delete()
                .semiJoin(Pet.class).on(Owner.class)
                .where(PET_NAME, EQUALS, "Rosy")
                .executeUpdate();
        assertEquals(1, deleted);
        assertEquals(List.of("Black", "Davis"), ownerLastNames());
    }

    @Test
    public void deletesTheRowsAnAntiJoinSelects() {
        int deleted = orm.entity(Owner.class).delete()
                .antiJoin(Pet.class).on(Owner.class)
                .executeUpdate();
        assertEquals(1, deleted);
        assertEquals(List.of("Davis", "Franklin"), ownerLastNames());
    }

    @Test
    public void matchesTheConditionOnTheDeletedTableItself() {
        int deleted = orm.entity(Owner.class).delete()
                .where(OWNER_LAST_NAME, EQUALS, "Davis")
                .executeUpdate();
        assertEquals(1, deleted);
        assertEquals(List.of("Black", "Franklin"), ownerLastNames());
    }

    @Test
    public void namesTheDeletedTableSoItsCachedEntitiesAreForgotten() {
        // The statement names the table it deletes from whichever form it takes, which is what evicts the entities of
        // that type a transaction holds once it runs.
        var affected = new ArrayList<Object>();
        observe(sql -> affected.add(sql.affectedType().orElse(null)), () -> orm.entity(Pet.class).delete()
                .where(PET_CITY_NAME, EQUALS, "Madison")
                .executeUpdate());
        assertEquals(List.of(Pet.class), affected);
    }

    @Test
    public void refusesADeleteWithoutACondition() {
        var exception = assertThrows(PersistenceException.class, () -> orm.entity(Pet.class).delete()
                .executeUpdate());
        assertTrue(exception.getMessage().contains("WHERE"), exception.getMessage());
        assertEquals(5, petNames().size());
        assertEquals(5, orm.entity(Pet.class).delete()
                .unsafe()
                .executeUpdate());
        assertEquals(List.of(), petNames());
    }

    @Test
    public void runsADeleteWithoutAConditionWhenTheQueryIsMarkedUnsafe() {
        assertEquals(5, orm.entity(Pet.class).delete()
                .build()
                .unsafe()
                .executeUpdate());
        assertEquals(List.of(), petNames());
    }

    @Test
    public void leavesTheConditionCheckOfAPlanToItsQueries() {
        var plan = orm.entity(Pet.class).delete().plan();
        var exception = assertThrows(PersistenceException.class, () -> plan.query().executeUpdate());
        assertTrue(exception.getMessage().contains("WHERE"), exception.getMessage());
        assertEquals(5, petNames().size());
        assertEquals(5, plan.query().unsafe().executeUpdate());
        assertEquals(List.of(), petNames());
    }
}
