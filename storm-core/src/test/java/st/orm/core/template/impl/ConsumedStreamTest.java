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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.IntegrationConfig;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;

/**
 * A stream or prepared query handed to the caller is watched for a missing close; one Storm consumes and closes in
 * the same call is not, and reads the same rows.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ConsumedStreamTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void aStreamHandedToTheCallerIsWatched() {
        var cities = ORMTemplate.of(dataSource).entity(City.class);
        try (var stream = cities.select().getResultStream()) {
            assertTrue(Proxy.isProxyClass(stream.getClass()), stream.getClass().getName());
        }
        try (var stream = cities.select().build().getResultStream(City.class)) {
            assertTrue(Proxy.isProxyClass(stream.getClass()), stream.getClass().getName());
        }
        try (var stream = cities.selectRef().getResultStream()) {
            assertTrue(Proxy.isProxyClass(stream.getClass()), stream.getClass().getName());
        }
    }

    @Test
    public void aStreamAnEagerTerminalConsumesIsNotWatchedAndReadsTheSameRows() {
        var cities = ORMTemplate.of(dataSource).entity(City.class);
        var query = (QueryImpl) cities.select().build();
        try (var stream = query.consumedResultStream(City.class)) {
            assertFalse(Proxy.isProxyClass(stream.getClass()), stream.getClass().getName());
        }
        try (var watched = cities.select().getResultStream()) {
            assertEquals(watched.toList(), cities.select().getResultList());
        }
        try (var watched = cities.select().build().getResultStream(City.class)) {
            assertEquals(watched.toList(), cities.select().build().getResultList(City.class));
        }
        try (var watched = cities.selectRef().getResultStream()) {
            assertEquals(watched.toList(), cities.selectRef().getResultList());
        }
    }

    @Test
    public void aQueryPreparedForTheApplicationIsWatchedAndOneStormConsumesIsNot() {
        var cities = ORMTemplate.of(dataSource).entity(City.class);
        try (var prepared = cities.select().build().prepare()) {
            assertTrue(Proxy.isProxyClass(prepared.getClass()), prepared.getClass().getName());
        }
        try (var prepared = ConsumedQueries.prepare(cities.select().build())) {
            assertFalse(Proxy.isProxyClass(prepared.getClass()), prepared.getClass().getName());
            assertEquals(cities.select().getResultList(), prepared.getResultList(City.class));
        }
    }
}
