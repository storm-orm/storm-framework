package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.GenerationStrategy;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;

/**
 * Two templates over one database, each addressing an entity's declared schema under a schema of its own.
 */
class SchemaResolverIntegrationTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @DbTable(schema = "archive")
    public record Archive(@PK(generation = GenerationStrategy.NONE) Integer id, String name) implements Entity<Integer> {}

    public record Plain(@PK(generation = GenerationStrategy.NONE) Integer id, String name) implements Entity<Integer> {}

    @Discriminator
    public sealed interface Shape extends Entity<Integer> permits Circle, Square {
        @Override
        Integer id();
    }

    public record Circle(@PK(generation = GenerationStrategy.NONE) Integer id, int radius) implements Shape {}

    public record Square(@PK(generation = GenerationStrategy.NONE) Integer id, int side) implements Shape {}

    private DataSource dataSource;
    private ORMTemplate tenantA;
    private ORMTemplate tenantB;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new SimpleDataSource("jdbc:h2:mem:schema_resolver_" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        for (String schema : new String[] {"tenant_a", "tenant_b"}) {
            execute("CREATE SCHEMA " + schema);
            execute("CREATE TABLE " + schema + ".archive (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL)");
            execute("CREATE TABLE " + schema + ".plain (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL)");
            execute("CREATE TABLE " + schema + ".shape (id INTEGER PRIMARY KEY, dtype VARCHAR(50) NOT NULL, radius INTEGER, side INTEGER)");
            execute("CREATE TABLE " + schema + ".geo_shape (id INTEGER PRIMARY KEY, dtype VARCHAR(50) NOT NULL, radius INTEGER, side INTEGER)");
        }
        tenantA = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver(tenant("tenant_a")));
        tenantB = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver(tenant("tenant_b")));
    }

    private static SchemaResolver tenant(String schema) {
        var archive = SchemaResolver.mapping(Map.of("archive", schema));
        return (type, declared) -> declared.isEmpty() ? schema : archive.resolveSchema(type, declared);
    }

    @Test
    void writesAndReadsLandInTheSchemaOfEachTemplate() throws SQLException {
        tenantA.entity(Archive.class).insert(new Archive(1, "a"));
        tenantB.entity(Archive.class).insert(new Archive(2, "b"));
        tenantB.entity(Archive.class).insert(new Archive(3, "b"));
        assertEquals(1, countRows("tenant_a.archive"));
        assertEquals(2, countRows("tenant_b.archive"));
        assertEquals(1, tenantA.entity(Archive.class).count());
        assertEquals(2, tenantB.entity(Archive.class).count());
        assertEquals("a", tenantA.entity(Archive.class).getById(1).name());
        assertTrue(tenantB.entity(Archive.class).findById(1).isEmpty());
    }

    @Test
    void updatesAndDeletesStayInTheSchemaOfTheTemplate() throws SQLException {
        tenantA.entity(Archive.class).insert(new Archive(1, "a"));
        tenantB.entity(Archive.class).insert(new Archive(1, "b"));
        tenantA.entity(Archive.class).update(new Archive(1, "a2"));
        tenantB.entity(Archive.class).remove(new Archive(1, "b"));
        assertEquals("a2", tenantA.entity(Archive.class).getById(1).name());
        assertEquals(0, countRows("tenant_b.archive"));
    }

    @Test
    void anUndeclaredSchemaResolvesThroughTheResolver() throws SQLException {
        tenantA.entity(Plain.class).insert(new Plain(1, "a"));
        assertEquals(1, countRows("tenant_a.plain"));
        assertEquals(0, countRows("tenant_b.plain"));
        assertEquals(0, tenantB.entity(Plain.class).count());
    }

    @Test
    void aSealedEntityAndItsSubtypesResolveThroughTheResolver() throws SQLException {
        tenantA.entity(Shape.class).insert(new Circle(1, 10));
        tenantA.entity(Square.class).insert(new Square(2, 20));
        tenantB.entity(Circle.class).insert(new Circle(1, 30));
        assertEquals(2, countRows("tenant_a.shape"));
        assertEquals(1, countRows("tenant_b.shape"));
        assertEquals(new Square(2, 20), tenantA.entity(Shape.class).getById(2));
        assertEquals(1, tenantA.entity(Circle.class).count());
        assertEquals(0, tenantB.entity(Square.class).count());
    }

    @Test
    void theSchemaOfASubtypeIsResolvedForItsSealedEntity() {
        Set<Class<?>> resolved = new HashSet<>();
        var recording = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver((type, schema) -> {
            resolved.add(type);
            return "tenant_a";
        }));
        recording.entity(Circle.class).count();
        recording.entity(Shape.class).count();
        assertEquals(Set.of(Shape.class), resolved);
    }

    @Test
    void aSealedEntityAndItsSubtypesTakeTheNameOfTheTableNameResolver() throws SQLException {
        var prefixed = ORMTemplate.of(dataSource, decorator -> decorator
                .withSchemaResolver(tenant("tenant_a"))
                .withTableNameResolver(type -> "geo_" + TableNameResolver.DEFAULT.resolveTableName(type)));
        prefixed.entity(Shape.class).insert(new Circle(1, 10));
        prefixed.entity(Square.class).insert(new Square(2, 20));
        assertEquals(2, countRows("tenant_a.geo_shape"));
        assertEquals(0, countRows("tenant_a.shape"));
        assertEquals(2, prefixed.entity(Shape.class).count());
        assertEquals(new Circle(1, 10), prefixed.entity(Circle.class).getById(1));
    }

    @Test
    void theTableNameResolverSeesASealedEntityAsItsInterface() {
        List<RecordType> resolved = new ArrayList<>();
        var recording = ORMTemplate.of(dataSource, decorator -> decorator
                .withSchemaResolver(tenant("tenant_a"))
                .withTableNameResolver(type -> {
                    resolved.add(type);
                    return "shape";
                }));
        recording.entity(Circle.class).count();
        var type = resolved.getFirst();
        assertEquals(Shape.class, type.type());
        assertNull(type.constructor());
        assertTrue(type.isAnnotationPresent(Discriminator.class));
        assertEquals(List.of("id", "radius", "side"), type.fields().stream().map(RecordField::name).toList());
    }

    @Test
    @ExtendWith(QuietValidationLog.class)
    void schemaValidationReadsTheResolvedSchema() {
        assertTrue(tenantA.validateSchema(List.of(Archive.class, Plain.class)).isEmpty());
        var missing = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver(SchemaResolver.mapping(Map.of("archive", "tenant_c"))));
        assertFalse(missing.validateSchema(List.of(Archive.class)).isEmpty());
    }

    @Test
    void templatesWithEqualMappingsShareTheirModels() {
        var first = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver(SchemaResolver.mapping(Map.of("archive", "tenant_a"))));
        var second = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver(SchemaResolver.mapping(Map.of("archive", "tenant_a"))));
        assertEquals(SchemaResolver.mapping(Map.of("archive", "tenant_a")), SchemaResolver.mapping(Map.of("archive", "tenant_a")));
        assertSame(first.model(Archive.class), second.model(Archive.class));
    }

    @Test
    void aResolverReturningNullIsRefused() {
        var broken = ORMTemplate.of(dataSource, decorator -> decorator.withSchemaResolver((type, schema) -> null));
        var exception = assertThrows(PersistenceException.class, () -> broken.entity(Archive.class).count());
        Throwable cause = exception;
        while (cause.getCause() != null && !cause.getMessage().contains("Schema resolver returned null")) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("Schema resolver returned null for Archive"), exception.getMessage());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long countRows(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static final class SimpleDataSource implements DataSource {
        private final String url;

        SimpleDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("Not a wrapper.");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
