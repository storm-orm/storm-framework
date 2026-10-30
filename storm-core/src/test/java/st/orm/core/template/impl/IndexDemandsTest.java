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
import st.orm.core.template.SqlDialect.ConstraintDiscoveryStrategy;
import st.orm.core.template.SqlDialect.SequenceDiscoveryStrategy;
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
            statement.execute("CREATE TABLE owner (id INTEGER PRIMARY KEY, source ENUM('Online', 'Referral', 'Walk-in'), "
                    + "active BOOLEAN, city VARCHAR(50), last_name VARCHAR(50), first_name VARCHAR(50))");
            statement.execute("CREATE TABLE pet (id INTEGER PRIMARY KEY, type_id INTEGER, birth_date DATE, "
                    + "name VARCHAR(50))");
            statement.execute("CREATE TABLE note (id INTEGER PRIMARY KEY, pet_id INTEGER, title VARCHAR(2000), "
                    + "body VARCHAR(2000))");
        }
    }

    private IndexCatalog catalog() throws SQLException {
        return catalog(0);
    }

    /** The catalog of a database whose index keys may hold the given bytes at most. */
    private IndexCatalog catalog(int maxKeyBytes) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            return IndexCatalog.of(Map.of("", DatabaseSchema.read(connection)), maxKeyBytes);
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
        assertTrue(advice.getFirst().contains("starts with last_name, so"), advice.getFirst());
        assertTrue(advice.getFirst().contains("\nindex: customer(last_name) finds"), advice.getFirst());
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
                            SequenceDiscoveryStrategy.INFORMATION_SCHEMA,
                            ConstraintDiscoveryStrategy.INFORMATION_SCHEMA,
                            null, null, null, "SELECT * FROM no_such_catalog WHERE schema_name = ?"));
            assertNull(IndexCatalog.of(Map.of("", schema)).find("", "customer"));
        }
    }

    /** Demands on one table, each a column and how the statement uses it. */
    private static IndexDemands demandsOn(String table, Object... columnsAndKinds) {
        var builder = new IndexDemands.Builder();
        for (int i = 0; i < columnsAndKinds.length; i += 2) {
            builder.add("", table, (String) columnsAndKinds[i], (Kind) columnsAndKinds[i + 1]);
        }
        var demands = builder.build();
        assert demands != null;
        return demands;
    }

    private String adviceOf(IndexDemands demands) throws SQLException {
        var advice = demands.advise(catalog());
        assertEquals(1, advice.size(), advice.toString());
        return advice.getFirst();
    }

    @Test
    public void aGroupedReadOnAnEnumIsAdvisedTheIndexThatReadsInGroupOrderFirst() throws SQLException {
        var advice = adviceOf(demandsOn("owner", "source", Kind.EQUALITY, "city", Kind.GROUP, "last_name",
                Kind.GROUP));
        assertEquals("no index on owner starts with source, so the database reads the table in full and sorts the "
                + "matching rows into groups.\n"
                + "index: owner(source, city, last_name) finds the matching rows and reads them already grouped, "
                + "without a temporary table or sort\n"
                + "covering: it reads the index alone when it also holds the columns the select reads\n"
                + "or: owner(source) finds them directly where the condition selects few of them; source is an ENUM, "
                + "with few distinct values, so an index on it alone is often not chosen.\nsee: "
                + "https://orm.st/docs/advice#missing-index", advice);
    }

    @Test
    public void aSelectThatNamesEveryColumnItReadsIsSaidToBeHeldByTheGroupedIndex() throws SQLException {
        // A count reads no column: the grouped index holds everything the select reads.
        var advice = adviceOf(demandsOn("owner", "source", Kind.EQUALITY, "city", Kind.GROUP, "", Kind.WHOLE_SELECT));
        assertTrue(advice.contains("owner(source, city) finds the matching rows and reads them already grouped, without "
                + "a temporary table or sort\ncovering: it reads the index alone, since it holds every column the "
                + "select reads\nor: owner(source)"), advice);
        // A select of columns past the index names them apart, as an appendix the reader weighs.
        advice = adviceOf(demandsOn("owner", "source", Kind.EQUALITY, "city", Kind.GROUP, "first_name",
                Kind.WHOLE_SELECT, "last_name", Kind.WHOLE_SELECT));
        assertTrue(advice.contains("owner(source, city) finds the matching rows and reads them already grouped, without "
                + "a temporary table or sort\ncovering: with first_name, last_name appended it reads the index alone, "
                + "since it then holds every column the select reads\nor: owner(source)"), advice);
    }

    @Test
    public void theCoveringColumnsAreNamedAsWhatWouldPassTheKeyLimit() throws SQLException {
        var demands = demandsOn("note", "pet_id", Kind.EQUALITY, "title", Kind.GROUP, "body", Kind.WHOLE_SELECT);
        var unlimited = demands.advise(catalog(0)).getFirst();
        assertTrue(unlimited.contains("note(pet_id, title) finds the matching rows and reads them already grouped, "
                + "without a temporary table or sort\ncovering: with body appended it reads the index alone, since it "
                + "then holds every column the select reads\nor: note(pet_id)"), unlimited);
        var limited = demands.advise(catalog(3_072)).getFirst();
        assertTrue(limited.matches("(?s).*note\\(pet_id, title\\) finds the matching rows and reads them already "
                + "grouped, without a temporary table or sort\ncovering: with body appended it would read the index "
                + "alone, but a key of about \\d+ bytes passes the 3072 bytes the database allows a key\n"
                + "or: note\\(pet_id\\).*"), limited);
    }

    @Test
    public void anIndexWhoseKeyPassesTheLimitIsSaidSo() throws SQLException {
        var demands = demandsOn("note", "pet_id", Kind.EQUALITY, "title", Kind.GROUP, "body", Kind.GROUP);
        var limited = demands.advise(catalog(3_072)).getFirst();
        assertTrue(limited.matches("(?s).*note\\(pet_id, title, body\\) finds the matching rows and reads them already "
                + "grouped, without a temporary table or sort, though its key of about \\d+ bytes passes the 3072 bytes "
                + "the database allows a key, so a column must be shortened or left out\nor: note\\(pet_id\\).*"), limited);
        assertFalse(demands.advise(catalog(0)).getFirst().contains("passes"));
    }

    @Test
    public void aGroupedReadOnItsEqualityColumnsAloneIsAdvisedOneIndex() throws SQLException {
        var advice = adviceOf(demandsOn("owner", "city", Kind.EQUALITY, "city", Kind.GROUP));
        assertTrue(advice.contains("\nindex: owner(city) finds them directly"), advice);
    }

    @Test
    public void aGroupedReadWithoutAFilterIsAdvisedTheIndexThatReadsInGroupOrder() throws SQLException {
        var advice = adviceOf(demandsOn("owner", "city", Kind.GROUP, "last_name", Kind.GROUP));
        assertTrue(advice.startsWith("no index on owner starts with city or last_name, so the database sorts every row "
                + "into groups.\nindex: owner(city, last_name) reads the rows already grouped, without a temporary "
                + "table or sort"), advice);
    }

    @Test
    public void aRangeAndALimitedOrderOnAnotherColumnAreAdvisedBothIndexesWithWhenEachWins() throws SQLException {
        var advice = adviceOf(demandsOn("pet", "type_id", Kind.EQUALITY, "birth_date", Kind.RANGE, "name",
                Kind.ORDER));
        assertTrue(advice.contains("\nindex: pet(type_id, birth_date) finds the matching rows directly, then sorts "
                + "them, which is best where the range selects few of them\nor: pet(type_id, name) reads the rows in "
                + "name order, "
                + "checks the range on each and stops at the limit, which is best where the range selects many of "
                + "them.\nsee:"), advice);
    }

    @Test
    public void aBooleanConditionAloneSaysAnIndexOnItIsOftenNotChosen() throws SQLException {
        var advice = adviceOf(demandsOn("owner", "active", Kind.EQUALITY));
        assertTrue(advice.contains("\nindex: owner(active) finds them directly where the condition selects few of "
                + "them; active is a BOOLEAN, with few distinct values, so an index on it alone is often not chosen."),
                advice);
    }

    @Test
    public void anIndexLeadingWithAGroupColumnServesTheGroupedRead() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX owner_city ON owner (city)");
        }
        assertEquals(List.of(), demandsOn("owner", "city", Kind.GROUP).advise(catalog()));
    }

    private void index(String ddl) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    @Test
    public void anIndexedFilterWhoseIndexStopsBeforeTheGroupsIsAdvisedTheIndexThatContinuesIt() throws SQLException {
        index("CREATE INDEX owner_source ON owner (source)");
        var advice = adviceOf(demandsOn("owner", "source", Kind.EQUALITY, "city", Kind.GROUP, "last_name",
                Kind.GROUP));
        assertEquals("no index on owner continues source with city, last_name, so the database sorts the matching rows "
                + "into groups.\nindex: owner(source, city, last_name), extending OWNER_SOURCE, finds the matching rows "
                + "and reads them already grouped, without a temporary table or sort\ncovering: it reads the index "
                + "alone when it also holds the columns the select reads.\nsee: "
                + "https://orm.st/docs/advice#missing-index", advice);
    }

    @Test
    public void anIndexThatContinuesTheFilterWithTheGroupsInAnyOrderServesTheGroupedRead() throws SQLException {
        index("CREATE INDEX owner_source_groups ON owner (source, last_name, city)");
        assertEquals(List.of(), demandsOn("owner", "source", Kind.EQUALITY, "city", Kind.GROUP, "last_name",
                Kind.GROUP).advise(catalog()));
    }

    @Test
    public void aLimitedOrderOnSeveralColumnsIsServedOnlyInItsOwnOrder() throws SQLException {
        index("CREATE INDEX pet_type ON pet (type_id)");
        var advice = adviceOf(demandsOn("pet", "type_id", Kind.EQUALITY, "name", Kind.ORDER, "birth_date",
                Kind.ORDER));
        assertTrue(advice.startsWith("no index on pet continues type_id with name, birth_date, so the database sorts "
                + "every matching row before it returns the first ones.\nindex: pet(type_id, name, birth_date), "
                + "extending PET_TYPE, reads the matching rows in order and stops at the limit."), advice);
        index("CREATE INDEX pet_type_reversed ON pet (type_id, birth_date, name)");
        assertEquals(1, demandsOn("pet", "type_id", Kind.EQUALITY, "name", Kind.ORDER, "birth_date", Kind.ORDER)
                .advise(catalog()).size());
        index("CREATE INDEX pet_type_ordered ON pet (type_id, name, birth_date)");
        assertEquals(List.of(), demandsOn("pet", "type_id", Kind.EQUALITY, "name", Kind.ORDER, "birth_date",
                Kind.ORDER).advise(catalog()));
    }

    @Test
    public void aGroupThatHoldsThePrimaryKeyOrAnEqualityOnAUniqueKeyNeedsNoIndex() throws SQLException {
        assertEquals(List.of(), demandsOn("owner", "id", Kind.GROUP, "city", Kind.GROUP).advise(catalog()));
        index("CREATE UNIQUE INDEX pet_name ON pet (name)");
        assertEquals(List.of(), demandsOn("pet", "name", Kind.EQUALITY, "birth_date", Kind.ORDER).advise(catalog()));
    }

    @Test
    public void aJoinOnTheTableIsNamedAsOne() throws SQLException {
        var advice = adviceOf(demandsOn("pet", "type_id", Kind.JOIN));
        assertTrue(advice.startsWith("no index on pet starts with type_id, so the database reads the table in full to "
                + "find the joined rows.\nindex: pet(type_id)"), advice);
        var both = adviceOf(demandsOn("pet", "type_id", Kind.JOIN, "name", Kind.EQUALITY));
        assertTrue(both.contains("so the database reads the table in full to find the matching rows"), both);
    }

    @Test
    public void theColumnsTheSelectNamesAreHeldByTheGroupedIndexAndAnOrderOnTheGroupsIsServedByIt()
            throws SQLException {
        var advice = adviceOf(demandsOn("owner", "source", Kind.EQUALITY, "city", Kind.GROUP, "city", Kind.ORDER,
                "first_name", Kind.SELECT));
        assertTrue(advice.contains("owner(source, city) finds the matching rows and reads them already grouped and in "
                + "the order the ORDER BY names, without a temporary table or sort\ncovering: with first_name appended "
                + "it reads the index alone where the select reads no other columns"), advice);
    }
}
