package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests for schema validation methods of ORMTemplate, entityCallbacks, config, and
 * entity/projection repository access.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ORMTemplateSchemaValidationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @ExtendWith(QuietValidationLog.class)
    public void testValidateSchemaReturnsResults() {
        var orm = ORMTemplate.of(dataSource);
        List<String> results = orm.validateSchema();
        assertNotNull(results);
    }

    @Test
    public void testValidateSchemaWithSpecificTypes() {
        var orm = ORMTemplate.of(dataSource);
        List<String> results = orm.validateSchema(List.of(City.class));
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Expected no validation errors for City, got: " + results);
    }

    @Test
    public void testEntityCallbacksDefaultsToEmpty() {
        var orm = ORMTemplate.of(dataSource);
        assertNotNull(orm.entityCallbacks());
        assertTrue(orm.entityCallbacks().isEmpty());
    }

    @Test
    public void testWithEntityCallbacksPreservesQueryBehavior() {
        var orm = ORMTemplate.of(dataSource);
        var ormWithCallbacks = orm.withEntityCallbacks(List.of());
        // The new template should still be functional and return the same data.
        assertEquals(orm.entity(City.class).count(), ormWithCallbacks.entity(City.class).count());
    }

    @Test
    public void testConfigFallsBackToSystemProperties() {
        var orm = ORMTemplate.of(dataSource);
        var config = orm.config();
        // Default config reads from system properties; requesting an unset key returns null.
        assertNull(config.getProperty("storm.nonexistent.test.key"));
        // getProperty with default should return the default for unset keys.
        assertEquals("fallback", config.getProperty("storm.nonexistent.test.key", "fallback"));
    }

    @Test
    public void testEntityRepository() {
        var orm = ORMTemplate.of(dataSource);
        var cityRepo = orm.entity(City.class);
        assertNotNull(cityRepo);
        assertTrue(cityRepo.count() > 0);
    }

    @Test
    public void testEntityRepositoryCaching() {
        var orm = ORMTemplate.of(dataSource);
        var cityRepo1 = orm.entity(City.class);
        var cityRepo2 = orm.entity(City.class);
        // Same instance should be returned (cached).
        assertTrue(cityRepo1 == cityRepo2);
    }

    @Test
    public void testMultipleEntityRepositories() {
        var orm = ORMTemplate.of(dataSource);
        var cityRepo = orm.entity(City.class);
        var ownerRepo = orm.entity(Owner.class);
        assertNotNull(cityRepo);
        assertNotNull(ownerRepo);
        assertTrue(cityRepo.count() > 0);
        assertTrue(ownerRepo.count() > 0);
    }

    @Test
    public void testValidateSchemaWithMultipleTypesReturnsNoErrors() {
        var orm = ORMTemplate.of(dataSource);
        // Non-strict validateSchema should report no errors for valid, existing tables.
        List<String> results = orm.validateSchema(List.of(City.class, Owner.class, Pet.class));
        assertTrue(results.isEmpty(),
                "Expected no validation errors for valid types, got: " + results);
    }

}
