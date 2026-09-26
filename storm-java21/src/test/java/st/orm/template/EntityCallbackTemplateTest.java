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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.template.Transactions.transaction;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.TransactionCallbackException;
import st.orm.template.model.City;

/**
 * An entity callback reaches the template of the operation that fired it through {@link ORMTemplate#current()},
 * rather than capturing one of its own.
 *
 * <p>Two H2 databases stand in for two data sources, so that a single callback instance registered on both
 * templates has no template it could have captured correctly.</p>
 */
class EntityCallbackTemplateTest {

    /** Writes an audit row through the template that fired it, capturing nothing. */
    private static final class AuditingCallback implements EntityCallback<City> {

        @Override
        public void afterInsert(List<City> entities) {
            ORMTemplate.current().writeSet().insert(entities.stream()
                    .map(city -> new City(null, city.name() + " (audit)"))
                    .toList());
        }
    }

    private ORMTemplate orders;
    private ORMTemplate audit;

    @BeforeEach
    void setUp() {
        var callback = new AuditingCallback();
        orders = ORMTemplate.of(database("callback-orders")).withEntityCallback(callback);
        audit = ORMTemplate.of(database("callback-audit")).withEntityCallback(callback);
    }

    private static DataSource database(String name) {
        DataSource dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);
        return dataSource;
    }

    private static long countCities(ORMTemplate template, String name) {
        return template.entity(City.class).findAll().stream()
                .filter(city -> city.name().equals(name))
                .count();
    }

    @Test
    void callbackWritesThroughTheTemplateThatFiredIt() {
        orders.entity(City.class).insert(new City(null, "Springfield"));
        assertEquals(1, countCities(orders, "Springfield (audit)"));
    }

    @Test
    void oneCallbackFollowsEachTemplateItIsRegisteredOn() {
        orders.entity(City.class).insert(new City(null, "Springfield"));
        audit.entity(City.class).insert(new City(null, "Shelbyville"));
        assertEquals(1, countCities(orders, "Springfield (audit)"),
                "The audit row belongs to the database the write went to.");
        assertEquals(0, countCities(orders, "Shelbyville (audit)"));
        assertEquals(1, countCities(audit, "Shelbyville (audit)"));
        assertEquals(0, countCities(audit, "Springfield (audit)"));
    }

    @Test
    void workThroughTheCurrentTemplateJoinsTheTransaction() {
        assertThrows(IllegalStateException.class, () ->
                transaction(tx -> {
                    orders.entity(City.class).insert(new City(null, "Ogdenville"));
                    throw new IllegalStateException("Simulated failure.");
                }));
        assertEquals(0, countCities(orders, "Ogdenville"), "The write must be rolled back.");
        assertEquals(0, countCities(orders, "Ogdenville (audit)"),
                "The callback wrote through the transaction's connection, so its row rolls back too.");
    }

    @Test
    void workThroughTheCurrentTemplateFiresNoCallbacks() {
        orders.entity(City.class).insert(new City(null, "Brockway"));
        assertEquals(1, countCities(orders, "Brockway (audit)"));
        assertEquals(0, countCities(orders, "Brockway (audit) (audit)"),
                "Callbacks never fire recursively, so the callback's own write fires none.");
    }

    @Test
    void templateCapturedInACallbackServesItsCommitCallback() {
        var publishing = ORMTemplate.of(database("callback-publish"))
                .withEntityCallback(new EntityCallback<City>() {
                    @Override
                    public void afterInsert(List<City> entities) {
                        var orm = ORMTemplate.current();
                        transaction(tx -> {
                            tx.onCommit(() -> orm.entity(City.class).insert(entities.stream()
                                    .map(city -> new City(null, city.name() + " (published)"))
                                    .toList()));
                            return null;
                        });
                    }
                });
        transaction(tx -> {
            publishing.entity(City.class).insert(new City(null, "Capital City"));
            return null;
        });
        assertEquals(1, countCities(publishing, "Capital City"));
        assertEquals(1, countCities(publishing, "Capital City (published)"),
                "The template captured while the callback ran serves the work registered for after the commit.");
    }

    @Test
    void currentIsRefusedInsideACommitCallback() {
        var refusing = ORMTemplate.of(database("callback-refuse"))
                .withEntityCallback(new EntityCallback<City>() {
                    @Override
                    public void afterInsert(List<City> entities) {
                        transaction(tx -> {
                            tx.onCommit(ORMTemplate::current);
                            return null;
                        });
                    }
                });
        var thrown = assertThrows(TransactionCallbackException.class, () ->
                transaction(tx -> {
                    refusing.entity(City.class).insert(new City(null, "North Haverbrook"));
                    return null;
                }));
        assertTrue(thrown.isCommitted(), "The commit callback runs once the transaction has committed.");
        assertTrue(thrown.getCause().getMessage().contains("entity callback"), thrown.getCause().getMessage());
    }

    @Test
    void currentIsRefusedOutsideACallback() {
        var thrown = assertThrows(PersistenceException.class, ORMTemplate::current);
        assertTrue(thrown.getMessage().contains("entity callback"), thrown.getMessage());
    }
}
