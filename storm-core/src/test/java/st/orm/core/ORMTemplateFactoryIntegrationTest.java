package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.TableNameResolver;
import st.orm.mapping.TemplateDecorator;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ORMTemplateFactoryIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // 1. ORMTemplate.of(Connection)

    @Test
    public void testOfConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var orm = ORMTemplate.of(connection);
            var cities = orm.entity(City.class).select().getResultList();
            assertEquals(6, cities.size());
        }
    }

    // 2. ORMTemplate.of(Connection, UnaryOperator<TemplateDecorator>) - identity

    @Test
    public void testOfConnectionWithIdentityDecorator() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var orm = ORMTemplate.of(connection, t -> t);
            var cities = orm.entity(City.class).select().getResultList();
            assertEquals(6, cities.size());
        }
    }

    // 3. ORMTemplate.of(Connection, UnaryOperator<TemplateDecorator>) - bad decorator

    @Test
    public void testOfConnectionWithBadDecoratorThrows() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThrows(PersistenceException.class, () ->
                    ORMTemplate.of(connection, t -> new StubTemplateDecorator())
            );
        }
    }

    // 4. ORMTemplate.of(DataSource, UnaryOperator<TemplateDecorator>) - identity

    @Test
    public void testOfDataSourceWithIdentityDecorator() {
        var orm = ORMTemplate.of(dataSource, t -> t);
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    // 5. ORMTemplate.of(DataSource, UnaryOperator<TemplateDecorator>) - bad decorator

    @Test
    public void testOfDataSourceWithBadDecoratorThrows() {
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource, t -> new StubTemplateDecorator())
        );
    }

    // 6. ORMTemplate.of(DataSource, StormConfig)

    @Test
    public void testOfDataSourceWithConfig() {
        var config = StormConfig.defaults();
        var orm = ORMTemplate.of(dataSource, config);
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
        assertNotNull(orm.config());
    }

    @Test
    public void testOfDataSourceWithCustomConfig() {
        var config = StormConfig.of(Map.of());
        var orm = ORMTemplate.of(dataSource, config);
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    // 7. ORMTemplate.of(DataSource, StormConfig, UnaryOperator<TemplateDecorator>)

    @Test
    public void testOfDataSourceWithConfigAndIdentityDecorator() {
        var config = StormConfig.defaults();
        var orm = ORMTemplate.of(dataSource, config, t -> t);
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    @Test
    public void testOfDataSourceWithConfigAndBadDecoratorThrows() {
        var config = StormConfig.defaults();
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource, config, t -> new StubTemplateDecorator())
        );
    }

    // 8. ORMTemplate.of(Connection, StormConfig)

    @Test
    public void testOfConnectionWithConfig() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var config = StormConfig.defaults();
            var orm = ORMTemplate.of(connection, config);
            var cities = orm.entity(City.class).select().getResultList();
            assertEquals(6, cities.size());
        }
    }

    // 9. ORMTemplate.of(Connection, StormConfig, UnaryOperator<TemplateDecorator>)

    @Test
    public void testOfConnectionWithConfigAndIdentityDecorator() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var config = StormConfig.defaults();
            var orm = ORMTemplate.of(connection, config, t -> t);
            var cities = orm.entity(City.class).select().getResultList();
            assertEquals(6, cities.size());
        }
    }

    @Test
    public void testOfConnectionWithConfigAndBadDecoratorThrows() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var config = StormConfig.defaults();
            assertThrows(PersistenceException.class, () ->
                    ORMTemplate.of(connection, config, t -> new StubTemplateDecorator())
            );
        }
    }

    // 10. entityCallbacks() default

    // 11. withEntityCallback()

    @Test
    public void testWithEntityCallbackAddsCallback() {
        var callback = new EntityCallback<City>() {
            @Override
            public City beforeInsert(City entity) {
                return entity;
            }
        };
        var orm = ORMTemplate.of(dataSource).withEntityCallback(callback);
        assertEquals(1, orm.entityCallbacks().size());
    }

    @Test
    public void testWithMultipleEntityCallbacks() {
        var callback1 = new EntityCallback<City>() {
            @Override
            public City beforeInsert(City entity) {
                return entity;
            }
        };
        var callback2 = new EntityCallback<City>() {
            @Override
            public void afterInsert(City entity) {}
        };
        var orm = ORMTemplate.of(dataSource)
                .withEntityCallback(callback1)
                .withEntityCallback(callback2);
        assertEquals(2, orm.entityCallbacks().size());
    }

    @Test
    public void testWithEntityCallbackTemplateStillWorks() {
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(City entity) {
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }
        });
        // Verify query still works after adding callback.
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    // Additional: verify of(DataSource) produces a working template

    @Test
    public void testOfDataSourceBasic() {
        var orm = ORMTemplate.of(dataSource);
        assertNotNull(orm);
        var count = orm.entity(City.class).select().getResultCount();
        assertEquals(6, count);
    }

    // Additional: Connection-based template with config and custom config map

    @Test
    public void testOfConnectionWithCustomConfigMap() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            var config = StormConfig.of(Map.of("storm.test.key", "value"));
            var orm = ORMTemplate.of(connection, config);
            var cities = orm.entity(City.class).select().getResultList();
            assertEquals(6, cities.size());
            assertEquals("value", orm.config().getProperty("storm.test.key"));
        }
    }

    // Stub TemplateDecorator that is not a PreparedStatementTemplateImpl

    /**
     * A minimal TemplateDecorator implementation that is NOT a PreparedStatementTemplateImpl,
     * used to trigger the PersistenceException in factory methods that validate the decorator
     * returns the correct type.
     */
    private static class StubTemplateDecorator implements TemplateDecorator {
        @Override
        public TemplateDecorator withTableNameResolver(TableNameResolver tableNameResolver) {
            return this;
        }

        @Override
        public TemplateDecorator withSchemaResolver(st.orm.mapping.SchemaResolver schemaResolver) {
            return this;
        }

        @Override
        public TemplateDecorator withColumnNameResolver(ColumnNameResolver columnNameResolver) {
            return this;
        }

        @Override
        public TemplateDecorator withForeignKeyResolver(ForeignKeyResolver foreignKeyResolver) {
            return this;
        }
    }
}
