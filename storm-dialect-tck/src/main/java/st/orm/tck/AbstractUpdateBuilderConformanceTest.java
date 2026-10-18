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
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
import st.orm.Ref;
import st.orm.Version;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.TemplateString;

/**
 * Update builder conformance: an UPDATE built through the query builder sets the columns it names on the rows its
 * conditions select, on every database, whether the conditions name the updated table alone or reach other tables
 * through a path, an explicit join, a semi-join or an anti-join.
 *
 * <p>The dialect decides how the statement reaches other tables: joined into the UPDATE before the SET clause on MySQL
 * and MariaDB, which refuse a subquery that reads the table being updated, in a FROM clause after it on SQL Server,
 * and through a subquery matching the primary key elsewhere. The suite covers a value, a value computed from the row,
 * a foreign key set to a ref and to null, the version increment, and the refusal of an UPDATE without a
 * condition.</p>
 *
 * <p>The suite creates and drops its own tables, so a dialect module runs it with {@code rollback = false}.</p>
 */
public abstract class AbstractUpdateBuilderConformanceTest {

    @DbTable("update_city")
    public record City(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    @DbTable("update_owner")
    public record Owner(@PK(generation = NONE) Integer id,
                        String lastName,
                        @Nullable @FK City city,
                        @Nullable String telephone,
                        @Version int version) implements Entity<Integer> {}

    @DbTable("update_pet")
    public record Pet(@PK(generation = NONE) Integer id,
                      String name,
                      @Nullable @FK Owner owner,
                      int visitCount) implements Entity<Integer> {}

    private static final Metamodel<Owner, String> OWNER_LAST_NAME = Metamodel.of(Owner.class, "lastName");
    private static final Metamodel<Owner, String> OWNER_TELEPHONE = Metamodel.of(Owner.class, "telephone");
    private static final Metamodel<Owner, String> OWNER_CITY_NAME = Metamodel.of(Owner.class, "city.name");
    private static final Metamodel<Pet, Integer> PET_ID = Metamodel.of(Pet.class, "id");
    private static final Metamodel<Pet, Integer> PET_VISIT_COUNT = Metamodel.of(Pet.class, "visitCount");
    private static final Metamodel<Pet, Owner> PET_OWNER = Metamodel.of(Pet.class, "owner");
    private static final Metamodel<Pet, String> PET_CITY_NAME = Metamodel.of(Pet.class, "owner.city.name");

    private static final List<String> TABLES = List.of("update_pet", "update_owner", "update_city");

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
            statement.execute("CREATE TABLE update_city (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE update_owner (id INT PRIMARY KEY, last_name VARCHAR(100) NOT NULL, city_id INT, telephone VARCHAR(100), version INT NOT NULL)");
            statement.execute("CREATE TABLE update_pet (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, owner_id INT, visit_count INT NOT NULL)");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
        var madison = new City(1, "Madison");
        var monona = new City(2, "Monona");
        orm.entity(City.class).insert(List.of(madison, monona));
        var davis = new Owner(1, "Davis", madison, null, 0);
        var franklin = new Owner(2, "Franklin", monona, null, 0);
        var black = new Owner(3, "Black", null, null, 0);
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

    private Map<String, Integer> visitCountsByName() {
        return orm.entity(Pet.class).select().getResultList().stream()
                .collect(Collectors.toMap(Pet::name, Pet::visitCount));
    }

    private Map<String, String> telephonesByLastName() {
        return orm.entity(Owner.class).select().getResultList().stream()
                .collect(Collectors.toMap(Owner::lastName, owner -> String.valueOf(owner.telephone())));
    }

    @Test
    public void setsAColumnOnTheRowsTheConditionSelects() {
        int updated = orm.entity(Pet.class).update()
                .set(PET_VISIT_COUNT, 0)
                .where(PET_VISIT_COUNT, GREATER_THAN, 5)
                .executeUpdate();
        assertEquals(2, updated);
        assertEquals(Map.of("Leo", 0, "Basil", 3, "Rosy", 0, "Jewel", 2,
                "Stray", 1), visitCountsByName());
    }

    @Test
    public void setsTheRowsAPathToARelatedTableSelects() {
        var statements = statements(() -> orm.entity(Pet.class).update()
                .set(PET_VISIT_COUNT, 1)
                .where(PET_CITY_NAME, EQUALS, "Madison")
                .executeUpdate());
        assertEquals(1, statements.size(), statements.toString());
        assertEquals(Map.of("Leo", 1, "Basil", 1, "Rosy", 9, "Jewel", 2,
                "Stray", 1), visitCountsByName());
    }

    @Test
    public void setsTheRowsAnExplicitJoinSelects() {
        // Davis has two pets, so the join meets his row twice; the row is updated once.
        int updated = orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55121")
                .innerJoin(Pet.class).on(Owner.class)
                .where(PET_VISIT_COUNT, GREATER_THAN, 5)
                .executeUpdate();
        assertEquals(2, updated);
        assertEquals(Map.of("Davis", "55121", "Franklin", "55121", "Black", "null"), telephonesByLastName());
    }

    @Test
    public void setsTheRowsASemiJoinSelects() {
        int updated = orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55122")
                .semiJoin(Pet.class).on(Owner.class)
                .executeUpdate();
        assertEquals(2, updated);
        assertEquals(Map.of("Davis", "55122", "Franklin", "55122", "Black", "null"), telephonesByLastName());
    }

    @Test
    public void setsTheRowsAnAntiJoinSelects() {
        int updated = orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55123")
                .antiJoin(Pet.class).on(Owner.class)
                .executeUpdate();
        assertEquals(1, updated);
        assertEquals(Map.of("Davis", "null", "Franklin", "null", "Black", "55123"), telephonesByLastName());
    }

    @Test
    public void computesTheValueFromTheRow() {
        orm.entity(Pet.class).update()
                .set(PET_VISIT_COUNT, TemplateString.raw("\0 + 1", PET_VISIT_COUNT))
                .where(PET_VISIT_COUNT, GREATER_THAN, 5)
                .executeUpdate();
        assertEquals(Map.of("Leo", 13, "Basil", 3, "Rosy", 10, "Jewel", 2,
                "Stray", 1), visitCountsByName());
    }

    @Test
    public void computesTheValueFromTheRowThroughAPath() {
        var statements = statements(() -> orm.entity(Pet.class).update()
                .set(PET_VISIT_COUNT, TemplateString.raw("\0 * 2", PET_VISIT_COUNT))
                .where(PET_CITY_NAME, EQUALS, "Monona")
                .executeUpdate());
        assertEquals(1, statements.size(), statements.toString());
        assertEquals(Map.of("Leo", 12, "Basil", 3, "Rosy", 18, "Jewel", 4,
                "Stray", 1), visitCountsByName());
    }

    @Test
    public void setsAForeignKeyToARefAndToNull() {
        orm.entity(Pet.class).update()
                .set(PET_OWNER, Ref.of(Owner.class, 3))
                .where(PET_ID, EQUALS, 5)
                .executeUpdate();
        assertEquals("Black", orm.entity(Pet.class).getById(5).owner().lastName());
        orm.entity(Pet.class).update()
                .set(PET_OWNER, (Owner) null)
                .where(PET_CITY_NAME, EQUALS, "Monona")
                .executeUpdate();
        assertNull(orm.entity(Pet.class).getById(3).owner());
        assertNull(orm.entity(Pet.class).getById(4).owner());
    }

    @Test
    public void incrementsTheVersionOfEachRowItUpdates() {
        orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55124")
                .where(OWNER_CITY_NAME, EQUALS, "Madison")
                .executeUpdate();
        var versions = orm.entity(Owner.class).select().getResultList().stream()
                .collect(Collectors.toMap(Owner::lastName, Owner::version));
        assertEquals(Map.of("Davis", 1, "Franklin", 0, "Black", 0), versions);
    }

    @Test
    public void namesTheUpdatedTableSoItsCachedEntitiesAreForgotten() {
        // The statement names the table it writes whichever form it takes, which is what evicts the entities of that
        // type a transaction holds once it runs.
        var affected = new ArrayList<Object>();
        observe(sql -> affected.add(sql.affectedType().orElse(null)), () -> orm.entity(Pet.class).update()
                .set(PET_VISIT_COUNT, 2)
                .where(PET_CITY_NAME, EQUALS, "Madison")
                .executeUpdate());
        assertEquals(List.of(Pet.class), affected);
    }

    @Test
    public void refusesAnUpdateWithoutACondition() {
        var exception = assertThrows(PersistenceException.class, () -> orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55125")
                .executeUpdate());
        assertTrue(exception.getMessage().contains("WHERE"), exception.getMessage());
        assertEquals(Map.of("Davis", "null", "Franklin", "null", "Black", "null"), telephonesByLastName());
        assertEquals(3, orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55125")
                .unsafe()
                .executeUpdate());
    }

    @Test
    public void matchesTheConditionOnTheUpdatedTableItself() {
        int updated = orm.entity(Owner.class).update()
                .set(OWNER_TELEPHONE, "55126")
                .where(OWNER_LAST_NAME, EQUALS, "Davis")
                .executeUpdate();
        assertEquals(1, updated);
        assertEquals("55126", orm.entity(Owner.class).getById(1).telephone());
    }
}
