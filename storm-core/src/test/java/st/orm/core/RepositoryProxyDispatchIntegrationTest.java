package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.model.OwnerView;
import st.orm.core.model.PetView;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.repository.Repository;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests for ORMTemplateImpl.dispatch method and repository proxy behaviors.
 * These tests cover dispatch branches for EntityRepository, ProjectionRepository,
 * and Repository base interface methods, plus default method invocation and
 * unsupported operation handling.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class RepositoryProxyDispatchIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // Repository interfaces

    interface CityEntityRepository extends EntityRepository<City, Integer> {}

    interface PetViewProjectionRepository extends ProjectionRepository<PetView, Integer> {}

    interface OwnerViewProjectionRepository extends ProjectionRepository<OwnerView, Integer> {}

    // Interface extending Repository only (no entity, no projection).
    interface MinimalRepository extends Repository {}

    // Interface with a custom default method.
    interface RepositoryWithDefaultMethod extends EntityRepository<City, Integer> {
        default String customGreeting() {
            return "Hello from " + orm().getClass().getSimpleName();
        }

        default long doubleCount() {
            return count() * 2;
        }
    }

    // EntityRepository dispatch

    @Test
    public void testEntityRepositoryProxyCount() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository = orm.repository(CityEntityRepository.class);
        assertTrue(repository.count() > 0);
    }

    @Test
    public void testEntityRepositoryProxyGetById() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository = orm.repository(CityEntityRepository.class);
        City city = repository.getById(1);
        assertNotNull(city);
        assertEquals("Sun Paririe", city.name());
    }

    @Test
    public void testEntityRepositoryProxyFindById() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository = orm.repository(CityEntityRepository.class);
        Optional<City> city = repository.findById(9999);
        assertTrue(city.isEmpty());
    }

    @Test
    public void testEntityRepositoryProxyInsertAndRemove() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository = orm.repository(CityEntityRepository.class);
        long countBefore = repository.count();
        Integer insertedId = repository.insertAndFetchId(City.builder().name("ProxyCity").build());
        assertNotNull(insertedId);
        assertEquals(countBefore + 1, repository.count());
        repository.removeById(insertedId);
        assertEquals(countBefore, repository.count());
    }

    // ProjectionRepository dispatch

    @Test
    public void testProjectionRepositoryProxyCount() {
        var orm = ORMTemplate.of(dataSource);
        PetViewProjectionRepository repository = orm.repository(PetViewProjectionRepository.class);
        assertTrue(repository.count() > 0);
    }

    @Test
    public void testProjectionRepositoryProxySelect() {
        var orm = ORMTemplate.of(dataSource);
        PetViewProjectionRepository repository = orm.repository(PetViewProjectionRepository.class);
        List<PetView> pets = repository.select().getResultList();
        assertNotNull(pets);
        assertFalse(pets.isEmpty());
    }

    @Test
    public void testProjectionRepositoryProxyGetById() {
        var orm = ORMTemplate.of(dataSource);
        PetViewProjectionRepository repository = orm.repository(PetViewProjectionRepository.class);
        PetView petView = repository.getById(1);
        assertNotNull(petView);
        assertEquals("Leo", petView.name());
    }

    @Test
    public void testOwnerViewProjectionRepositorySelect() {
        var orm = ORMTemplate.of(dataSource);
        OwnerViewProjectionRepository repository = orm.repository(OwnerViewProjectionRepository.class);
        List<OwnerView> owners = repository.select().getResultList();
        assertNotNull(owners);
        assertFalse(owners.isEmpty());
        assertEquals("Betty", owners.getFirst().firstName());
    }

    // Repository base interface dispatch

    @Test
    public void testMinimalRepositoryProxyOrm() {
        var orm = ORMTemplate.of(dataSource);
        MinimalRepository repository = orm.repository(MinimalRepository.class);
        assertNotNull(repository.orm());
    }

    // Default method dispatch

    @Test
    public void testDefaultMethodInvocation() {
        var orm = ORMTemplate.of(dataSource);
        RepositoryWithDefaultMethod repository = orm.repository(RepositoryWithDefaultMethod.class);
        String greeting = repository.customGreeting();
        assertNotNull(greeting);
        assertTrue(greeting.startsWith("Hello from"));
    }

    @Test
    public void testDefaultMethodCallingRepositoryMethod() {
        var orm = ORMTemplate.of(dataSource);
        RepositoryWithDefaultMethod repository = orm.repository(RepositoryWithDefaultMethod.class);
        long count = repository.count();
        long doubleCount = repository.doubleCount();
        assertEquals(count * 2, doubleCount);
    }

    // Proxy identity methods

    @Test
    public void testProxyHashCodeIsIdentityBased() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository = orm.repository(CityEntityRepository.class);
        assertEquals(System.identityHashCode(repository), repository.hashCode());
    }

    @Test
    public void testProxyEqualsIsIdentityBased() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository1 = orm.repository(CityEntityRepository.class);
        CityEntityRepository repository2 = orm.repository(CityEntityRepository.class);
        // Same proxy instance from cache.
        assertTrue(repository1.equals(repository2));
        assertTrue(repository1.equals(repository1));
        assertFalse(repository1.equals(new Object()));
    }

    @Test
    public void testProxyToStringContainsInterfaceName() {
        var orm = ORMTemplate.of(dataSource);
        CityEntityRepository repository = orm.repository(CityEntityRepository.class);
        String toString = repository.toString();
        assertTrue(toString.contains("CityEntityRepository"));
        assertTrue(toString.contains("@proxy"));
    }

    // Projection repository: projection() method on ORMTemplate

    @Test
    public void testProjectionMethodDirectly() {
        var orm = ORMTemplate.of(dataSource);
        var projectionRepository = orm.projection(PetView.class);
        assertNotNull(projectionRepository);
        assertTrue(projectionRepository.count() > 0);
    }

    @Test
    public void testProjectionMethodFindById() {
        var orm = ORMTemplate.of(dataSource);
        var projectionRepository = orm.projection(PetView.class);
        Optional<PetView> petView = projectionRepository.findById(1);
        assertTrue(petView.isPresent());
        assertEquals("Leo", petView.get().name());
    }

    @Test
    public void testProjectionMethodGetByIdNonExistentThrows() {
        var orm = ORMTemplate.of(dataSource);
        var projectionRepository = orm.projection(PetView.class);
        assertThrows(PersistenceException.class, () -> projectionRepository.getById(99999));
    }

    // OwnerView projection with version and inline Address

    @Test
    public void testOwnerViewProjectionWithVersionAndAddress() {
        var orm = ORMTemplate.of(dataSource);
        var projectionRepository = orm.projection(OwnerView.class);
        OwnerView owner = projectionRepository.getById(1);
        assertNotNull(owner);
        assertEquals("Betty", owner.firstName());
        assertEquals("Davis", owner.lastName());
        assertNotNull(owner.address());
        assertTrue(owner.version() >= 0);
    }

    // SelectFrom query builder

    @Test
    public void testSelectFromCityWithFilter() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.selectFrom(City.class)
                .getResultList();
        assertNotNull(cities);
        assertTrue(cities.size() >= 6);
    }
}
