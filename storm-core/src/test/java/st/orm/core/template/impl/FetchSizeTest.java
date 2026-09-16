package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.TemplateString.raw;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.IntegrationConfig;
import st.orm.core.model.City;
import st.orm.core.spi.RefFactory;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;

/**
 * Tests for fetch size logic in QueryImpl and PreparedStatementTemplateImpl.
 *
 * <p>A test SPI provider ({@code FetchSizeSqlDialectProviderImpl}) registers a dialect with
 * {@code defaultFetchSize=100} and {@code streamOnlyFetchSize=false}. This ensures the setFetchSize
 * paths in PreparedStatementTemplateImpl and the applyFetchSize path in QueryImpl are exercised by
 * any query execution through the normal ORM path.</p>
 *
 * <p>Additionally, direct QueryImpl construction with {@code streamOnlyFetchSize=true} tests the
 * withoutFetchSize delegation for non-streaming methods.</p>
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class FetchSizeTest {

    @Autowired
    private DataSource dataSource;

    private static final RefFactory DETACHED_REF_FACTORY = new RefFactory() {
        @Override
        public <T extends st.orm.Data, ID> Ref<T> create(Class<T> type, ID pk) {
            return Ref.of(type, pk);
        }

        @Override
        public <T extends st.orm.Data, ID> Ref<T> create(T record, ID pk) {
            throw new UnsupportedOperationException();
        }
    };

    /**
     * Creates a QueryImpl with custom fetch size settings, backed by a real H2 connection.
     * Each call to the statement function prepares a fresh PreparedStatement.
     */
    private QueryImpl createQueryWithFetchSize(Connection connection,
                                               String sql,
                                               int defaultFetchSize,
                                               boolean streamOnlyFetchSize) {
        return createQueryWithFetchSize(connection, sql, defaultFetchSize, streamOnlyFetchSize, false);
    }

    private QueryImpl createQueryWithFetchSize(Connection connection,
                                               String sql,
                                               int defaultFetchSize,
                                               boolean streamOnlyFetchSize,
                                               boolean streamingRequiresTransaction) {
        var environment = new QueryImpl.Environment(
                DETACHED_REF_FACTORY,
                st.orm.core.spi.Providers.getTransactionTemplateProvider(),
                st.orm.spi.QueryObserver.noop(),
                e -> e instanceof PersistenceException persistenceException
                        ? persistenceException
                        : new PersistenceException(e),
                st.orm.spi.SqlOperation.SELECT,
                null,
                FetchPlan.NONE,
                List.of(),
                sql,
                st.orm.spi.StatementOrigin.DIRECT,
                0L,
                java.util.List.of(),
                null);
        return new QueryImpl(
                environment,
                unsafe -> {
                    try {
                        return connection.prepareStatement(sql);
                    } catch (SQLException e) {
                        throw new PersistenceException(e);
                    }
                },
                null,
                null,
                false,
                false,
                false,
                defaultFetchSize,
                streamOnlyFetchSize,
                streamingRequiresTransaction
        );
    }

    @Test
    public void testFetchSizeAppliedViaDataSourceProcessor() {
        var orm = ORMTemplate.of(dataSource);
        try (Stream<Object[]> stream = orm.query(raw("SELECT id FROM city WHERE id = 1")).getResultStream()) {
            Object[] row = stream.findFirst().orElseThrow();
            assertNotNull(row[0]);
        }
    }

    @Test
    public void testFetchSizeAppliedViaConnectionProcessor() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatementTemplate template = new PreparedStatementTemplateImpl(connection);
            var orm = template.toORM();
            try (Stream<Object[]> stream = orm.query(raw("SELECT id FROM city WHERE id = 1")).getResultStream()) {
                Object[] row = stream.findFirst().orElseThrow();
                assertNotNull(row[0]);
            }
        }
    }

    @Test
    public void testApplyFetchSizeOnGetResultStream() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, false);
            try (Stream<Object[]> stream = query.getResultStream()) {
                Object[] row = stream.findFirst().orElseThrow();
                assertNotNull(row[0]);
            }
        }
    }

    record CityId(int id) {}

    @Test
    public void testApplyFetchSizeOnGetResultStreamTyped() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, false);
            try (Stream<CityId> stream = query.getResultStream(CityId.class)) {
                CityId result = stream.findFirst().orElseThrow();
                assertEquals(1, result.id());
            }
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getSingleResult() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, true);
            Object[] result = query.getSingleResult();
            assertNotNull(result);
            assertEquals(1, result.length);
        }
    }

    record CityName(String name) {}

    @Test
    public void testStreamOnlyFetchSize_getSingleResultTyped() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT name FROM city WHERE id = 1", 100, true);
            CityName result = query.getSingleResult(CityName.class);
            assertNotNull(result);
            assertEquals("Sun Paririe", result.name());
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getOptionalResult() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, true);
            var result = query.getOptionalResult();
            assertTrue(result.isPresent());
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getOptionalResultTyped() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT name FROM city WHERE id = 1", 100, true);
            var result = query.getOptionalResult(CityName.class);
            assertTrue(result.isPresent());
            assertEquals("Sun Paririe", result.get().name());
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getResultList() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city ORDER BY id", 100, true);
            List<Object[]> results = query.getResultList();
            assertEquals(6, results.size());
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getResultListTyped() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT name FROM city ORDER BY id", 100, true);
            List<CityName> results = query.getResultList(CityName.class);
            assertEquals(6, results.size());
            assertEquals("Sun Paririe", results.getFirst().name());
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getResultCount() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city", 100, true);
            long count = query.getResultCount();
            assertEquals(6, count);
        }
    }

    @Test
    public void testStreamOnlyFetchSize_getRefList() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city ORDER BY id", 100, true);
            List<Ref<City>> refs = query.getRefList(City.class, Integer.class);
            assertEquals(6, refs.size());
            for (Ref<City> ref : refs) {
                assertNotNull(ref);
                assertNotNull(ref.id());
            }
        }
    }

    @Test
    public void testStreamOnlyFetchSize_stillAppliesOnStream() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city ORDER BY id", 100, true);
            try (Stream<Object[]> stream = query.getResultStream()) {
                long count = stream.count();
                assertEquals(6, count);
            }
        }
    }

    @Test
    public void testStreamOnlyFetchSize_stillAppliesOnStreamTyped() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city ORDER BY id", 100, true);
            try (Stream<CityId> stream = query.getResultStream(CityId.class)) {
                long count = stream.count();
                assertEquals(6, count);
            }
        }
    }

    @Test
    public void testPreparePassesFetchSizeToPreparedQuery() throws Exception {
        var orm = ORMTemplate.of(dataSource);
        try (var preparedQuery = orm.selectFrom(City.class).build().prepare();
             var stream = preparedQuery.getResultStream(City.class)) {
            long count = stream.count();
            assertEquals(6, count);
        }
    }

    @Test
    public void testStreamingRequiresTransaction_disablesAutoCommitDuringStream() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, false, true);
            try (Stream<Object[]> stream = query.getResultStream()) {
                Object[] row = stream.findFirst().orElseThrow();
                assertNotNull(row[0]);
            }
            // Auto-commit should be restored after the stream is closed.
            assertTrue(connection.getAutoCommit(), "Auto-commit should be restored after stream close");
        }
    }

    @Test
    public void testStreamingRequiresTransaction_noChangeWhenAlreadyInTransaction() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, false, true);
            try (Stream<Object[]> stream = query.getResultStream()) {
                Object[] row = stream.findFirst().orElseThrow();
                assertNotNull(row[0]);
            }
            // Auto-commit should remain false (unchanged).
            assertTrue(!connection.getAutoCommit(), "Auto-commit should remain false when already in a transaction");
            connection.rollback();
        }
    }

    @Test
    public void testStreamingRequiresTransaction_noChangeWhenFlagDisabled() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getAutoCommit());
            QueryImpl query = createQueryWithFetchSize(connection, "SELECT id FROM city WHERE id = 1", 100, false, false);
            try (Stream<Object[]> stream = query.getResultStream()) {
                Object[] row = stream.findFirst().orElseThrow();
                assertNotNull(row[0]);
            }
            // Auto-commit should remain true (unchanged).
            assertTrue(connection.getAutoCommit(), "Auto-commit should remain true when flag is disabled");
        }
    }
}
