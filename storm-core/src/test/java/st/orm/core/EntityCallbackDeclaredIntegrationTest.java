package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.EntityCallbacks;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;

/**
 * Callbacks an entity declares with {@link EntityCallbacks} apply without the application registering anything.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class EntityCallbackDeclaredIntegrationTest {

    @Autowired
    private DataSource dataSource;

    static final List<String> ORDER = new ArrayList<>();
    static final AtomicInteger COUNTED = new AtomicInteger();

    @BeforeEach
    public void reset() {
        ORDER.clear();
        COUNTED.set(0);
    }

    @DbTable("city")
    @EntityCallbacks(UppercaseName.class)
    public record DeclaredCity(@PK Integer id, String name) implements Entity<Integer> {}

    public static class UppercaseName implements EntityCallback<DeclaredCity> {
        @Override
        public DeclaredCity beforeInsert(DeclaredCity entity) {
            ORDER.add("declared");
            return new DeclaredCity(entity.id(), entity.name().toUpperCase());
        }
    }

    @Test
    public void declaredCallbackAppliesWithoutRegistration() {
        var cities = ORMTemplate.of(dataSource).entity(DeclaredCity.class);
        cities.insert(new DeclaredCity(null, "declared city"));
        assertTrue(cities.select().getResultList().stream()
                .anyMatch(city -> city.name().equals("DECLARED CITY")));
        assertEquals(List.of("declared"), ORDER);
    }

    @DbTable("city")
    @EntityCallbacks(CountingCallback.class)
    public record CountedCity(@PK Integer id, String name) implements Entity<Integer> {}

    public static class CountingCallback implements EntityCallback<CountedCity> {
        @Override
        public void afterInsert(CountedCity entity) {
            COUNTED.incrementAndGet();
        }
    }

    @Test
    public void registeringAnInstanceOfADeclaredCallbackReplacesIt() {
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new CountingCallback());
        orm.entity(CountedCity.class).insert(new CountedCity(null, "counted city"));
        assertEquals(1, COUNTED.get());
    }

    @Test
    public void declaredCallbacksRunBeforeRegisteredOnes() {
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<DeclaredCity>() {
            @Override
            public DeclaredCity beforeInsert(DeclaredCity entity) {
                ORDER.add("registered");
                return entity;
            }
        });
        orm.entity(DeclaredCity.class).insert(new DeclaredCity(null, "ordered city"));
        assertEquals(List.of("declared", "registered"), ORDER);
    }

    @DbTable("city")
    @EntityCallbacks(NeedsCollaborator.class)
    public record UncreatableCity(@PK Integer id, String name) implements Entity<Integer> {}

    public static class NeedsCollaborator implements EntityCallback<UncreatableCity> {
        public NeedsCollaborator(String collaborator) {
        }
    }

    @Test
    public void aCallbackStormCannotCreateNamesTheWayToRegisterIt() {
        var orm = ORMTemplate.of(dataSource);
        var e = assertThrows(PersistenceException.class, () -> orm.entity(UncreatableCity.class));
        assertTrue(e.getMessage().contains(NeedsCollaborator.class.getName()));
        assertTrue(e.getMessage().contains("no-argument constructor"));
        assertTrue(e.getMessage().contains("withEntityCallback"));
    }

    @DbTable("city")
    @EntityCallbacks(ForAnotherEntity.class)
    public record MismatchedCity(@PK Integer id, String name) implements Entity<Integer> {}

    public static class ForAnotherEntity implements EntityCallback<City> {
    }

    @Test
    public void aCallbackDeclaredForAnotherEntityFailsFast() {
        var orm = ORMTemplate.of(dataSource);
        var e = assertThrows(PersistenceException.class, () -> orm.entity(MismatchedCity.class));
        assertTrue(e.getMessage().contains(ForAnotherEntity.class.getName()));
        assertTrue(e.getMessage().contains(City.class.getName()));
    }
}
