package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.EntityCallback;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class EntityCallbackIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testBeforeInsertTransformsEntity() {
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }
        });
        var cities = orm.entity(City.class);
        cities.insert(City.builder().name("test city").build());
        // Verify the persisted city has the transformed (uppercased) name.
        var all = cities.select().getResultList();
        assertTrue(all.stream().anyMatch(c -> c.name().equals("TEST CITY")));
    }

    @Test
    public void testAfterInsertCalled() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterInsert(@Nonnull City entity) {
                log.add("inserted:" + entity.name());
            }
        });
        orm.entity(City.class).insert(City.builder().name("callback city").build());
        assertEquals(1, log.size());
        assertEquals("inserted:callback city", log.getFirst());
    }

    @Test
    public void testBeforeUpdateTransformsEntity() {
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeUpdate(@Nonnull City entity) {
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }
        });
        var cities = orm.entity(City.class);
        // City with id=1 exists as "Sun Paririe" in test data.
        cities.update(City.builder().id(1).name("updated city").build());
        var fetched = cities.getById(1);
        assertEquals("UPDATED CITY", fetched.name());
    }

    @Test
    public void testAfterUpdateCalled() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterUpdate(@Nonnull City entity) {
                log.add("updated:" + entity.name());
            }
        });
        orm.entity(City.class).update(City.builder().id(1).name("new name").build());
        assertEquals(1, log.size());
        assertEquals("updated:new name", log.getFirst());
    }

    @Test
    public void testBeforeDeleteCalled() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void beforeDelete(@Nonnull City entity) {
                log.add("before:" + entity.name());
            }
        });
        // Insert a city with no FK references, then delete it.
        var id = orm.entity(City.class).insertAndFetchId(City.builder().name("Deletable").build());
        orm.entity(City.class).remove(City.builder().id(id).name("Deletable").build());
        assertEquals(List.of("before:Deletable"), log);
    }

    @Test
    public void testAfterDeleteCalled() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public void afterDelete(@Nonnull City entity) {
                log.add("after:" + entity.name());
            }
        });
        // Insert a city with no FK references, then delete it.
        var id = orm.entity(City.class).insertAndFetchId(City.builder().name("Deletable").build());
        orm.entity(City.class).remove(City.builder().id(id).name("Deletable").build());
        assertEquals(List.of("after:Deletable"), log);
    }

    @Test
    public void testBatchInsertCallbacks() {
        List<String> beforeLog = new ArrayList<>();
        List<String> afterLog = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                beforeLog.add(entity.name());
                return entity.toBuilder().name(entity.name().toUpperCase()).build();
            }

            @Override
            public void afterInsert(@Nonnull City entity) {
                afterLog.add(entity.name());
            }
        });
        orm.entity(City.class).insert(List.of(
                City.builder().name("batch one").build(),
                City.builder().name("batch two").build()
        ));
        assertEquals(List.of("batch one", "batch two"), beforeLog);
        // afterInsert receives the original batch entities (before transformation)
        assertEquals(2, afterLog.size());
    }

    @Test
    public void testNoCallbackByDefault() {
        var orm = ORMTemplate.of(dataSource);
        assertTrue(orm.entityCallbacks().isEmpty());
    }

    @Test
    public void testInsertAndFetchIdWithCallback() {
        List<String> log = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<City>() {
            @Override
            public City beforeInsert(@Nonnull City entity) {
                log.add("before:" + entity.name());
                return entity.toBuilder().name(entity.name() + " [modified]").build();
            }

            @Override
            public void afterInsert(@Nonnull City entity) {
                log.add("after:" + entity.name());
            }
        });
        var id = orm.entity(City.class).insertAndFetchId(City.builder().name("fetch id city").build());
        assertEquals(2, log.size());
        assertEquals("before:fetch id city", log.get(0));
        assertTrue(log.get(1).startsWith("after:"));
        // Verify the entity was stored with the modified name.
        var fetched = orm.entity(City.class).getById(id);
        assertEquals("fetch id city [modified]", fetched.name());
    }
}
