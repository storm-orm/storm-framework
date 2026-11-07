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
package st.orm.spi.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static st.orm.Polymorphic.Strategy.JOINED;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.PK;
import st.orm.Polymorphic;
import st.orm.core.repository.EntityRepository;
import st.orm.core.template.ORMTemplate;

/**
 * A batch of joined entities with generated keys inserts its base rows as multi-row statements, each taking at most
 * the rows whose values fit the bind parameter limit of 999, for a batch whose values exceed it.
 */
class SQLiteJoinedInsertBindLimitTest {

    @Discriminator
    @Polymorphic(JOINED)
    @DbTable("limit_animal")
    public sealed interface LimitAnimal extends Entity<Integer> permits LimitCat, LimitDog {}

    @DbTable("limit_cat")
    public record LimitCat(@PK Integer id, String name, boolean indoor) implements LimitAnimal {}

    @DbTable("limit_dog")
    public record LimitDog(@PK Integer id, String name, int weight) implements LimitAnimal {}

    private static final int ROWS = 1500;

    private EntityRepository<LimitAnimal, Integer> animals;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:target/joined_limit.db");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS limit_cat");
            statement.execute("DROP TABLE IF EXISTS limit_dog");
            statement.execute("DROP TABLE IF EXISTS limit_animal");
            statement.execute("CREATE TABLE limit_animal (id INTEGER PRIMARY KEY AUTOINCREMENT, dtype VARCHAR(50) NOT NULL, name VARCHAR(50))");
            statement.execute("CREATE TABLE limit_cat (id INTEGER PRIMARY KEY REFERENCES limit_animal (id), indoor BOOLEAN NOT NULL)");
            statement.execute("CREATE TABLE limit_dog (id INTEGER PRIMARY KEY REFERENCES limit_animal (id), weight INTEGER NOT NULL)");
        }
        animals = ORMTemplate.of(dataSource).entity(LimitAnimal.class);
    }

    private static List<LimitAnimal> newAnimals() {
        return IntStream.rangeClosed(1, ROWS)
                .<LimitAnimal>mapToObj(index -> index % 2 == 0
                        ? new LimitCat(null, "cat" + index, true)
                        : new LimitDog(null, "dog" + index, index))
                .toList();
    }

    @Test
    void batchWhoseValuesExceedTheBindLimitInserts() {
        var ids = animals.insertAndFetchIds(newAnimals().stream(), ROWS);
        assertEquals(ROWS, new HashSet<>(ids).size());
        assertEquals(ROWS, animals.count());
    }
}
