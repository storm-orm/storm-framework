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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Operator;
import st.orm.core.template.impl.IndexDemands.Kind;

/**
 * The missing-index decision of a statement shape is made once per catalog: a shape that is slow again reuses it, and
 * a catalog published by a later schema load is decided anew.
 */
public class IndexDemandsTest {

    private DriverManagerDataSource dataSource;

    @BeforeEach
    public void database() throws SQLException {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:index_demands_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, last_name VARCHAR(255), email VARCHAR(255))");
            statement.execute("CREATE INDEX customer_email ON customer (email)");
        }
    }

    private IndexCatalog catalog() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return IndexCatalog.of(Map.of("", DatabaseSchema.read(connection)));
        }
    }

    private static IndexDemands demands(String column, Kind kind) {
        var builder = new IndexDemands.Builder();
        builder.add("", "customer", column, kind);
        var demands = builder.build();
        assert demands != null;
        return demands;
    }

    @Test
    public void aRepeatedDecisionReusesTheFirst() throws SQLException {
        var demands = demands("last_name", Kind.EQUALITY);
        var catalog = catalog();
        var first = demands.advise(catalog);
        assertEquals(1, first.size(), first.toString());
        assertSame(first, demands.advise(catalog));
    }

    @Test
    public void aNewCatalogIsDecidedAnew() throws SQLException {
        var demands = demands("last_name", Kind.EQUALITY);
        var first = demands.advise(catalog());
        var second = demands.advise(catalog());
        assertNotSame(first, second);
        assertEquals(first, second);
    }

    @Test
    public void withoutACatalogNothingIsAdvised() {
        assertEquals(List.of(), demands("last_name", Kind.EQUALITY).advise(null));
    }

    @Test
    public void aColumnAnIndexStartsWithIsServed() throws SQLException {
        var catalog = catalog();
        assertEquals(List.of(), demands("email", Kind.EQUALITY).advise(catalog));
        assertEquals(List.of(), demands("ID", Kind.RANGE).advise(catalog));
    }

    @Test
    public void anUnknownTableIsNotAdvised() throws SQLException {
        var builder = new IndexDemands.Builder();
        builder.add("", "created_after_the_load", "name", Kind.EQUALITY);
        var demands = builder.build();
        assert demands != null;
        assertEquals(List.of(), demands.advise(catalog()));
    }

    @Test
    public void aStatementWithoutDemandsHasNone() {
        assertNull(new IndexDemands.Builder().build());
    }

    @Test
    public void onlyComparisonsAnIndexServesAreDemands() {
        assertEquals(Kind.EQUALITY, IndexDemands.kindOf(Operator.EQUALS));
        assertEquals(Kind.EQUALITY, IndexDemands.kindOf(Operator.IN));
        assertEquals(Kind.RANGE, IndexDemands.kindOf(Operator.BETWEEN));
        assertEquals(Kind.RANGE, IndexDemands.kindOf(Operator.LESS_THAN_OR_EQUAL));
        for (var operator : List.of(Operator.NOT_EQUALS, Operator.NOT_IN, Operator.LIKE, Operator.CONTAINS,
                Operator.EQUALS_IGNORE_CASE, Operator.IS_NULL, Operator.IS_TRUE)) {
            assertNull(IndexDemands.kindOf(operator), operator.toString());
        }
    }

    @Test
    public void theSuggestedIndexPutsEqualityFirst() throws SQLException {
        var builder = new IndexDemands.Builder();
        builder.add("", "customer", "last_name", Kind.RANGE);
        builder.add("", "customer", "id_card", Kind.EQUALITY);
        var demands = builder.build();
        assert demands != null;
        var advice = demands.advise(catalog());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("customer(id_card, last_name)"), advice.getFirst());
    }

    @Test
    public void aRangeOnAnEqualityColumnAddsNoSecondColumn() throws SQLException {
        var builder = new IndexDemands.Builder();
        builder.add("", "customer", "last_name", Kind.EQUALITY);
        builder.add("", "customer", "LAST_NAME", Kind.EQUALITY);
        builder.add("", "customer", "last_name", Kind.RANGE);
        var demands = builder.build();
        assert demands != null;
        var advice = demands.advise(catalog());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("starts with last_name, which"), advice.getFirst());
        assertTrue(advice.getFirst().contains("customer(last_name) finds"), advice.getFirst());
    }

    @Test
    public void aRankingComparisonStaysOne() {
        var expression = new Elements.ObjectExpression(null, Operator.EQUALS, 1);
        assertTrue(expression.indexable());
        var ranking = expression.ranking();
        assertFalse(ranking.indexable());
        assertSame(ranking, ranking.ranking());
    }

    @Test
    public void aTableWhoseIndexesWereNotReadIsUnknown() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            // A query the database cannot answer leaves the indexes unknown.
            var schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    new DatabaseSchema.Discovery(
                            st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy.INFORMATION_SCHEMA,
                            st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy.INFORMATION_SCHEMA,
                            null, null, null, "SELECT * FROM no_such_catalog WHERE schema_name = ?"));
            assertNull(IndexCatalog.of(Map.of("", schema)).find("", "customer"));
        }
    }
}
