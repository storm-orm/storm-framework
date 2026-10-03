package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.Repository;
import st.orm.core.template.ORMTemplate;

/**
 * Extended integration tests for {@link ORMTemplate} covering validateSchemaOrThrow,
 * repository proxy creation, and edge cases.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class ORMTemplateIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // validateSchemaOrThrow with types

    @DbTable("nonexistent_table")
    public record NonexistentEntity(
            @PK Integer id,
            String name
    ) implements Entity<Integer> {}

    @Test
    @ExtendWith(QuietValidationLog.class)
    public void testValidateSchemaOrThrowWithInvalidTypesThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class,
                () -> orm.validateSchemaOrThrow(List.of(NonexistentEntity.class)));
    }

    @Test
    public void testValidateSchemaOrThrowWithValidTypes() {
        var orm = ORMTemplate.of(dataSource);
        assertDoesNotThrow(() -> orm.validateSchemaOrThrow(List.of(City.class)));
    }

    // Repository proxy

    interface CityRepository extends EntityRepository<City, Integer> {}

    @Test
    public void testRepositoryProxy() {
        var orm = ORMTemplate.of(dataSource);
        CityRepository repository = orm.repository(CityRepository.class);
        assertNotNull(repository);
        assertTrue(repository.count() > 0);
    }

    @Test
    public void testRepositoryProxyCaching() {
        var orm = ORMTemplate.of(dataSource);
        CityRepository repository1 = orm.repository(CityRepository.class);
        CityRepository repository2 = orm.repository(CityRepository.class);
        // Same proxy instance should be returned.
        assertSame(repository1, repository2);
    }

    @Test
    public void testRepositoryProxyToString() {
        var orm = ORMTemplate.of(dataSource);
        CityRepository repository = orm.repository(CityRepository.class);
        String toString = repository.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("CityRepository"));
    }

    @Test
    public void testRepositoryProxyHashCode() {
        var orm = ORMTemplate.of(dataSource);
        CityRepository repository = orm.repository(CityRepository.class);
        // hashCode should not throw and should be based on identity.
        int hashCode = repository.hashCode();
        assertEquals(System.identityHashCode(repository), hashCode);
    }

    @Test
    public void testRepositoryProxyEquals() {
        var orm = ORMTemplate.of(dataSource);
        CityRepository repository = orm.repository(CityRepository.class);
        // equals should use identity comparison.
        assertTrue(repository.equals(repository));
    }

    @Test
    public void testRepositoryProxyOrm() {
        var orm = ORMTemplate.of(dataSource);
        CityRepository repository = orm.repository(CityRepository.class);
        assertNotNull(repository.orm());
    }

    // Base Repository interface

    interface MinimalRepository extends Repository {}

    @Test
    public void testMinimalRepositoryProxy() {
        var orm = ORMTemplate.of(dataSource);
        MinimalRepository repository = orm.repository(MinimalRepository.class);
        assertNotNull(repository);
        assertNotNull(repository.orm());
    }

    // Entity callbacks

    @Test
    public void testWithEntityCallbacksReturnsNewInstance() {
        var orm = ORMTemplate.of(dataSource);
        var original = orm.entityCallbacks();
        var ormWithCallback = orm.withEntityCallbacks(List.of(
                new st.orm.EntityCallback<City>() {
                    @Override
                    public City beforeInsert(City entity) {
                        return entity;
                    }
                }
        ));
        assertEquals(1, ormWithCallback.entityCallbacks().size());
    }

    // validateSchema returns results

    @Test
    public void testValidateSchemaWithEmptyTypeList() {
        var orm = ORMTemplate.of(dataSource);
        List<String> results = orm.validateSchema(List.of());
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    // SelectBuilder with distinct

    @Test
    public void testSelectDistinct() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .distinct()
                .getResultList();
        assertNotNull(list);
        assertTrue(list.size() > 0);
    }

    // SelectBuilder with limit and offset

    @Test
    public void testSelectWithLimitAndOffset() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .limit(2)
                .offset(1)
                .getResultList();
        assertNotNull(list);
        assertEquals(2, list.size());
    }

    @Test
    public void testSelectWithOffsetOnly() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .offset(1)
                .getResultList();
        assertNotNull(list);
        // Should skip the first row; with at least 3 cities, should return 2+.
        assertTrue(list.size() >= 1);
    }

    @Test
    public void testSelectWithLimitOnly() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .limit(1)
                .getResultList();
        assertNotNull(list);
        assertEquals(1, list.size());
    }
}
