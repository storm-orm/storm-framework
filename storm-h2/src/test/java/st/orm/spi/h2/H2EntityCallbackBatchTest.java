package st.orm.spi.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static st.orm.GenerationStrategy.NONE;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.test.StormTest;

/**
 * Covers where a SQL-level upsert batch reaches a callback, which needs a dialect with native upsert support; the H2
 * dialect maps upsert to {@code MERGE}, and a key the database does not generate keeps the upsert at SQL level.
 */
@StormTest(scripts = "/data.sql")
public class H2EntityCallbackBatchTest {

    private DataSource dataSource;

    @BeforeEach
    void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @DbTable("specialty")
    public record Specialty(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    private static List<Specialty> specialties() {
        return List.of(new Specialty(901, "Cardiology"), new Specialty(902, "Oncology"), new Specialty(903, "Ophthalmology"));
    }

    @Test
    public void testUpsertBatchReachesTheUpsertListForm() {
        List<List<Specialty>> batches = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Specialty>() {
            @Override
            public void afterUpsert(List<Specialty> entities) {
                batches.add(List.copyOf(entities));
            }
        });
        orm.entity(Specialty.class).upsert(specialties());
        assertEquals(List.of(3), batches.stream().map(List::size).toList());
    }

    @Test
    public void testUpsertBatchReachesTheInsertListFormWhenNoUpsertFormIsOverridden() {
        List<List<Specialty>> batches = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Specialty>() {
            @Override
            public void afterInsert(List<Specialty> entities) {
                batches.add(List.copyOf(entities));
            }
        });
        orm.entity(Specialty.class).upsert(specialties());
        assertEquals(List.of(3), batches.stream().map(List::size).toList());
    }

    @Test
    public void testUpsertBatchReachesTheSingleUpsertFormForEachEntity() {
        List<String> observed = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Specialty>() {
            @Override
            public void afterUpsert(Specialty entity) {
                observed.add("upsert:" + entity.name());
            }

            @Override
            public void afterInsert(List<Specialty> entities) {
                observed.add("insert list");
            }
        });
        orm.entity(Specialty.class).upsert(specialties());
        // A callback that overrides the single upsert form keeps receiving each upserted entity through it.
        assertEquals(List.of("upsert:Cardiology", "upsert:Oncology", "upsert:Ophthalmology"), observed);
    }

    @Test
    public void testUpsertBatchReachesTheSingleInsertFormForEachEntity() {
        List<String> observed = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Specialty>() {
            @Override
            public void afterInsert(Specialty entity) {
                observed.add(entity.name());
            }
        });
        orm.entity(Specialty.class).upsert(specialties());
        assertEquals(List.of("Cardiology", "Oncology", "Ophthalmology"), observed);
    }

    @Test
    public void testSingleUpsertIsListOfOne() {
        List<List<Specialty>> batches = new ArrayList<>();
        var orm = ORMTemplate.of(dataSource).withEntityCallback(new EntityCallback<Specialty>() {
            @Override
            public void afterUpsert(List<Specialty> entities) {
                batches.add(List.copyOf(entities));
            }
        });
        orm.entity(Specialty.class).upsert(new Specialty(904, "Dermatology"));
        assertEquals(List.of(1), batches.stream().map(List::size).toList());
    }
}
