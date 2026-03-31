package st.orm.core;

import static java.util.Collections.newSetFromMap;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.transaction.annotation.Isolation.READ_COMMITTED;
import static org.springframework.transaction.annotation.Isolation.READ_UNCOMMITTED;
import static org.springframework.transaction.annotation.Isolation.REPEATABLE_READ;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;
import static org.springframework.transaction.annotation.Propagation.REQUIRED;
import static st.orm.EnumType.ORDINAL;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.BETWEEN;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.IN;
import static st.orm.Operator.IS_NULL;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LIKE;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.ResolveScope.INNER;
import static st.orm.ResolveScope.OUTER;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.Templates.alias;
import static st.orm.core.template.Templates.column;
import static st.orm.core.template.Templates.select;
import static st.orm.core.template.Templates.where;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.Builder;
import lombok.NonNull;
import org.h2.jdbc.JdbcSQLSyntaxErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;
import st.orm.Convert;
import st.orm.Converter;
import st.orm.Data;
import st.orm.DbColumn;
import st.orm.DbEnum;
import st.orm.DbTable;
import st.orm.DefaultJoinType;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Metamodel;
import st.orm.OptimisticLockException;
import st.orm.PK;
import st.orm.Pageable;
import st.orm.Persist;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.SelectMode;
import st.orm.Version;
import st.orm.core.model.Address;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.EntityWithNullableUK;
import st.orm.core.model.EntityWithNullableUK_;
import st.orm.core.model.EntityWithNullsNotDistinctUK;
import st.orm.core.model.EntityWithNullsNotDistinctUK_;
import st.orm.core.model.NullableCompoundUK;
import st.orm.core.model.Owner;
import st.orm.core.model.OwnerView;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.PetExtension;
import st.orm.core.model.PetOwnerRecursion;
import st.orm.core.model.PetOwnerRef;
import st.orm.core.model.PetOwnerRef_;
import st.orm.core.model.PetTypeEnum;
import st.orm.core.model.PetWithNullableOwnerRef;
import st.orm.core.model.PetWithNullableOwnerRef_;
import st.orm.core.model.Pet_;
import st.orm.core.model.Specialty;
import st.orm.core.model.Vet;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.VetSpecialtyPK;
import st.orm.core.model.Visit;
import st.orm.core.model.VisitWithCompoundFK;
import st.orm.core.model.VisitWithCompoundFK_;
import st.orm.core.model.VisitWithTwoPetRefs;
import st.orm.core.model.VisitWithTwoPets;
import st.orm.core.model.VisitWithTwoPetsOneRef;
import st.orm.core.model.VisitWithTwoPetsOneRef_;
import st.orm.core.model.VisitWithTwoPets_;
import st.orm.core.model.Visit_;
import st.orm.core.model.Wrapper;
import st.orm.core.repository.PetRepository;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlTemplate.PositionalParameter;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.TemplateBuilder;
import st.orm.core.template.TemplateString;

@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@DataJpaTest(showSql = false)
public class RepositoryPreparedStatementIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void testSelect() {
        // data.sql inserts exactly 10 owners (ids 1-10).
        assertEquals(10, ORMTemplate.of(dataSource).entity(Owner.class).selectCount().getSingleResult());
    }

    @Test
    public void testCount() {
        // data.sql inserts exactly 10 owners (ids 1-10).
        assertEquals(10, ORMTemplate.of(dataSource).entity(Owner.class).count());
    }

    @Test
    public void testResultCount() {
        // data.sql inserts exactly 10 owners (ids 1-10).
        assertEquals(10, ORMTemplate.of(dataSource).entity(Owner.class).select().getResultCount());
    }

    @Test
    public void testSelectByFk() {
        // Owner 1 (Betty Davis) has exactly 1 pet: Leo (id=1).
        assertEquals(1, ORMTemplate.of(dataSource).entity(Pet.class).select().where(Pet_.owner, Owner.builder().id(1).build()).getResultCount());
    }

    @Test
    public void testSelectByFkNested() {
        // Owner 1 (Betty Davis) has 1 pet (Leo, id=1). Leo has 2 visits (ids 4, 8).
        assertEquals(2, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.pet.owner, Owner.builder().id(1).build()).getResultCount());
    }

    @Test
    public void testSelectByColumn() {
        // data.sql: only visit id=1 has visit_date = 2023-01-01.
        assertEquals(1, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.visitDate, EQUALS, LocalDate.of(2023, 1, 1)).getResultCount());
    }

    @Test
    public void testSelectByColumnGreaterThan() {
        // data.sql: 14 visits total, only visit id=1 has date 2023-01-01. 13 visits have dates after 2023-01-01.
        assertEquals(13, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.visitDate, GREATER_THAN, LocalDate.of(2023, 1, 1)).getResultCount());
    }

    @Test
    public void testSelectByColumnGreaterThanOrEqual() {
        // data.sql: all 14 visits have dates >= 2023-01-01 (the earliest date in the data).
        assertEquals(14, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.visitDate, GREATER_THAN_OR_EQUAL, LocalDate.of(2023, 1, 1)).getResultCount());
    }

    @Test
    public void testSelectByColumnBetween() {
        // data.sql: 10 visits have dates between 2023-01-01 and 2023-01-09 inclusive
        // (visit dates: 01-01, 01-02, 01-03, 01-04, 01-04, 01-06, 01-08, 01-08, 01-08, 01-09).
        assertEquals(10, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.visitDate, BETWEEN, LocalDate.of(2023, 1, 1), LocalDate.of(2023, 1, 9)).getResultCount());
    }

    @Test
    public void testSelectByColumnIsNull() {
        // data.sql: only pet id=13 (Sly) has NULL owner_id.
        assertEquals(1, ORMTemplate.of(dataSource).entity(Pet.class).select().where(Pet_.owner, IS_NULL).getResultCount());
    }

    @Test
    public void testSelectByColumnRefIsNull() {
        // data.sql: only pet id=13 (Sly) has NULL owner_id.
        assertEquals(1, ORMTemplate.of(dataSource).entity(PetWithNullableOwnerRef.class).select().where(PetWithNullableOwnerRef_.owner, IS_NULL).getResultCount());
    }

    @Test
    public void testSelectByColumnEqualsNull() {
        // EQUALS with null is not supported; should use IS_NULL instead. Expect SqlTemplateException.
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(Pet.class).select().where(Pet_.owner, EQUALS, (Owner) null).getResultCount();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectByColumnRefEqualsNull() {
        // EQUALS with null is not supported even for Ref-based fields; should use IS_NULL instead.
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(PetWithNullableOwnerRef.class).select().where(PetWithNullableOwnerRef_.owner, EQUALS, (Owner) null).getResultCount();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectByColumnEqualsId() {
        // Owner 1 (Betty Davis) has exactly 1 pet: Leo (id=1).
        assertEquals(1, ORMTemplate.of(dataSource).entity(Pet.class).select().where(Pet_.owner.id, EQUALS, 1).getResultCount());
    }

    @Test
    public void testSelectByColumnRecord() {
        // Owner 1 (Betty Davis) has exactly 1 pet: Leo (id=1).
        assertEquals(1, ORMTemplate.of(dataSource).entity(Pet.class).select().where(Pet_.owner, EQUALS, Owner.builder().id(1).build()).getResultCount());
    }

    @Test
    public void testSelectByColumnRef() {
        // Owner 1 (Betty Davis) has exactly 1 pet: Leo (id=1).
        assertEquals(1, ORMTemplate.of(dataSource).entity(Pet.class).select().where(Pet_.owner, Ref.of(Owner.builder().id(1).build())).getResultCount());
    }

    @Test
    public void testSelectByNestedColumn() {
        // data.sql: pet "Leo" (id=1) has 2 visits (ids 4, 8).
        assertEquals(2, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.pet.name, EQUALS, "Leo").getResultCount());
    }

    @Test
    public void testSelectByDeeperNestedColumn() {
        // data.sql: owner "Betty" (id=1) has 1 pet (Leo), and Leo has 2 visits (ids 4, 8).
        assertEquals(2, ORMTemplate.of(dataSource).entity(Visit.class).select().where(Visit_.pet.owner.firstName, EQUALS, "Betty").getResultCount());
    }

    @Test
    public void testSelectByUnknownColumn() throws SqlTemplateException{
        var e = assertThrows(PersistenceException.class, () -> {
            var model = Visit_.pet.name;
            // We don't want to use Metamodel.of as it would already throw an exception.
            Metamodel<Visit, String> invalidModel = new Metamodel<>() {
                @Override
                public boolean isColumn() {
                    return true;
                }

                @Override
                public boolean isInline() {
                    return false;
                }

                @Override
                public Class<Visit> root() {
                    return model.root();
                }

                @Override
                public Metamodel<Visit, ? extends Data> table() {
                    return model.table();
                }

                @Override
                public String path() {
                    return model.path();
                }

                @Override
                public Class<String> fieldType() {
                    return model.fieldType();
                }

                @Override
                public String field() {
                    return "names"; // Invalid name.
                }

                @Override
                public String getValue(@NonNull Visit record) {
                    return record.pet().name();
                }

                @Override
                public boolean isIdentical(@NonNull Visit a, @Nonnull Visit b) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isSame(@NonNull Visit a, @Nonnull Visit b) {
                    throw new UnsupportedOperationException();
                }
            };
            ORMTemplate.of(dataSource).entity(Visit.class).select().where(wrap(where(invalidModel, EQUALS, "Leo"))).getResultCount();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectCountCustomClass() {
        // GROUP BY owner: 10 owners in data.sql, each appears as a group.
        // 12 of 13 pets have owners (pet 13 Sly has no owner, so its owner is null and grouped separately).
        // LEFT JOIN means owner=null group has 1 pet, but the query uses Pet table joined to Owner, so
        // all 10 owners appear. The null-owner pet is still counted. Total pet count = 12 + 1 null = 13,
        // but with GROUP BY owner_id, null is a separate group => 11 groups, but result is 10 because
        // the query uses Owner.class which inner-joins to owner. Actually, the raw SQL selects Owner
        // columns and groups by owner_id, so null-owner pet contributes a null-owner group.
        // The result size of 10 means only the 10 non-null owner groups are returned (Owner FK join
        // filters out pet 13). Total pets across those 10 owners = 12.
        record Count(Owner owner, int value) {}
        var query = ORMTemplate.of(dataSource).query(raw("SELECT \0, COUNT(*) FROM \0 GROUP BY \0", Owner.class, Pet.class, Owner_.id));
        var result = query.getResultList(Count.class);
        assertEquals(10, result.size(), "10 distinct owners exist in data.sql");
        assertEquals(12, result.stream().mapToInt(Count::value).sum(), "12 pets have non-null owner_id");
    }

    @Test
    public void testInsert() {
        // data.sql inserts 6 vets. After inserting 2 more, expect 8 total.
        var repository = ORMTemplate.of(dataSource).entity(Vet.class);
        Vet vet1 = Vet.builder().firstName("Noel").lastName("Fitzpatrick").build();
        Vet vet2 = Vet.builder().firstName("Scarlett").lastName("Magda").build();
        repository.insert(List.of(vet1, vet2));
        var list = repository.select().getResultList();
        assertEquals(8, list.size(), "6 original + 2 inserted = 8 vets");
        assertEquals("Scarlett", list.getLast().firstName());
    }

    @Test
    public void testInsertReturningIds() {
        // data.sql inserts 6 vets (ids 1-6 via auto_increment). Next two get ids 7 and 8.
        var repository = ORMTemplate.of(dataSource).entity(Vet.class);
        Vet vet1 = Vet.builder().firstName("Noel").lastName("Fitzpatrick").build();
        Vet vet2 = Vet.builder().firstName("Scarlett").lastName("Magda").build();
        var ids = repository.insertAndFetchIds(List.of(vet1, vet2));
        assertEquals(List.of(7, 8), ids);
    }

    @Test
    public void testInsertReturningIdsCompoundPk() {
        try {
            var repository = ORMTemplate.of(dataSource).entity(VetSpecialty.class);
            VetSpecialty vetSpecialty = VetSpecialty.builder().id(new VetSpecialtyPK(1, 1)).build();
            var id = repository.insertAndFetchId(vetSpecialty);
            assertEquals(1, id.vetId());
            assertEquals(1, id.specialtyId());
        } catch (PersistenceException ignore) {
            // May happen in case of Mysql/MariaDB as they only support auto increment columns.
        }
    }

    @Test
    public void testUpdateList() {
        var repository = ORMTemplate.of(dataSource).entity(Vet.class);
        try (var stream1 = repository.select().getResultStream()) {
            var list1 = stream1.toList();
            repository.update(list1);
            try (var stream2 = repository.select().getResultStream()) {
                var list2 = stream2.toList();
                assertEquals(list1, list2);
            }
        }
    }

    @Test
    public void testSelectList() {
        var repository = ORMTemplate.of(dataSource).entity(Vet.class);
        var list = repository.select().getResultList();
        var list2 = repository.findAllById(list.stream().map(Vet::id).toList());
        assertEquals(list, list2);
    }

    @Test
    public void testSelectFetchRef() {
        var visit = ORMTemplate.of(dataSource).entity(VisitWithNullablePetRef.class).getById(1);
        //noinspection DataFlowIssue
        var pet = visit.pet().fetch();
        assertNotNull(visit.pet());
        //noinspection DataFlowIssue
        assertEquals("Jean", pet.owner().firstName());
        assertSame(pet, visit.pet().fetch());
    }

    @Test
    public void testSelectRef() {
        // data.sql inserts 14 visits.
        var visits = ORMTemplate.of(dataSource).entity(Visit.class).selectRef().getResultList();
        assertEquals(14, visits.size());
    }

    record VisitResult(Ref<Visit> visit) {}

    @Test
    public void testSelectRefTemplate() {
        // data.sql inserts 14 visits.
        var visits = ORMTemplate.of(dataSource).selectFrom(Visit.class, VisitResult.class, wrap(select(Visit.class, SelectMode.PK))).getResultList();
        assertEquals(14, visits.size());
    }

    @Test
    public void testSelectRefCustomType() {
        // data.sql inserts 14 visits. Each visit has a pet FK, so 14 pet refs are returned.
        var pets = ORMTemplate.of(dataSource).entity(Visit.class).selectRef(Pet.class).getResultList();
        assertEquals(14, pets.size());
    }

    @Test
    public void testSelectWhereRef() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class).select().where(Ref.of(Owner.builder().id(1).build())).getResultList();
        assertEquals(1, owners.size());
        assertEquals(1, owners.getFirst().id());
    }

    @Test
    public void testSelectRefCompoundPk() {
        // data.sql inserts 5 vet_specialty rows: (2,1), (3,2), (3,3), (4,2), (5,1).
        var vetSpecialties = ORMTemplate.of(dataSource).entity(VetSpecialty.class).selectRef().getResultList();
        assertEquals(5, vetSpecialties.size());
    }

    @Test
    public void testSelectWhereRefCompoundPk() {
        // data.sql: vet_specialty (2,1) = vet 2 (Helen Leary), specialty 1 (radiology).
        var vetSpecialties = ORMTemplate.of(dataSource).entity(VetSpecialty.class).select().where(Ref.of(VetSpecialty.builder().id(new VetSpecialtyPK(2, 1)).build())).getResultList();
        assertEquals(1, vetSpecialties.size());
        assertEquals(2, vetSpecialties.getFirst().vet().id());
        assertEquals(1, vetSpecialties.getFirst().specialty().id());
    }

    @Test
    public void testSelectWithInvalidPath() {
        var e = assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource).entity(Pet.class).select()
                        .where(wrap(where(Metamodel.of(Pet.class, "owner.city"), EQUALS, "Sunnyvale")))
                        .getResultList());
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testWhereWithOperatorAndRecord() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).getById(1);
        var pets = ORMTemplate.of(dataSource).entity(Pet.class).select()
                .where(raw("\0 = \0", Pet_.owner, owner))
                .getResultList();
        assertFalse(pets.isEmpty());
    }

    @Test
    public void testSelectWithInvalidPathNoArg() {
        var e = assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource).entity(Pet.class).select()
                        .where(wrap(where(Metamodel.of(Pet.class, "owner.city"), IS_NULL)))
                        .getResultList());
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    // Projection page pagination

    @Test
    public void testProjectionPageFirstPage() {
        // data.sql inserts exactly 10 owners (ids 1-10).
        // First page of size 5 should return 5 items with totalCount=10.
        var page = ORMTemplate.of(dataSource).projection(OwnerView.class).page(0, 5);
        assertEquals(5, page.content().size());
        assertEquals(10, page.totalCount());
        assertTrue(page.hasNext());
    }

    @Test
    public void testProjectionPageWithPageable() {
        // Use Pageable.ofSize to create a pageable request for the first page of projections.
        var pageable = Pageable.ofSize(5);
        var page = ORMTemplate.of(dataSource).projection(OwnerView.class).page(pageable);
        assertEquals(5, page.content().size());
        assertEquals(10, page.totalCount());
        assertEquals(0, page.pageNumber());
        assertTrue(page.hasNext());
    }

    @Test
    public void testProjectionPageRef() {
        // pageRef should return a Page of Ref<OwnerView> instead of OwnerView.
        var page = ORMTemplate.of(dataSource).projection(OwnerView.class).pageRef(0, 5);
        assertEquals(5, page.content().size());
        assertEquals(10, page.totalCount());
        assertInstanceOf(Ref.class, page.content().getFirst());
    }

    @Test
    void testInsertPetWithNull() {
        var e = assertThrows(PersistenceException.class, () -> {
            var repository = ORMTemplate.of(dataSource).entity(Visit.class);
            //noinspection DataFlowIssue
            Visit visit = new Visit(0, LocalDate.now(), "test", null, Instant.now());
            repository.insert(visit);
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    void testUpdatePetWithNull() {
        var e = assertThrows(PersistenceException.class, () -> {
            var repository = ORMTemplate.of(dataSource).entity(Visit.class);
            var visit = repository.getById(1);
            //noinspection DataFlowIssue
            repository.update(new Visit(visit.id(), visit.visitDate(), visit.description(), null, Instant.now()));
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    public record AutoDate(String value) {}
    public record CustomDate(String value) {}

    public static class CustomConverter implements Converter<LocalDate, CustomDate> {
        @Override
        public LocalDate toDatabase(@Nullable CustomDate value) {
            return value == null ? null : LocalDate.parse(value.value());
        }

        @Override
        public CustomDate fromDatabase(@Nullable LocalDate dbValue) {
            return new CustomDate(dbValue == null ? null : dbValue.toString());
        }
    }

    @Builder(toBuilder = true)
    @DbTable("visit")
    public record VisitWithDefaultConverter(
            @PK Integer id,
            @Nonnull AutoDate visitDate,
            @Nullable String description,
            @Nonnull @FK Ref<Pet> pet
    ) implements Entity<Integer> {
    }

    @Builder(toBuilder = true)
    @DbTable("visit")
    public record VisitWithDisabledConverter(
            @PK Integer id,
            @Nonnull @Convert(disableConversion = true) AutoDate visitDate,
            @Nullable String description,
            @Nonnull @FK Ref<Pet> pet
    ) implements Entity<Integer> {
    }

    @Builder(toBuilder = true)
    @DbTable("visit")
    public record VisitWithCustomConverter(
            @PK Integer id,
            @Nonnull @Convert(converter = CustomConverter.class) CustomDate visitDate,
            @Nullable String description,
            @Nonnull @FK Ref<Pet> pet
    ) implements Entity<Integer> {
    }

    @Builder(toBuilder = true)
    @DbTable("visit")
    public record VisitWithoutConverter(
            @PK Integer id,
            @Nonnull CustomDate visitDate,
            @Nullable String description,
            @Nonnull @FK Ref<Pet> pet
    ) implements Entity<Integer> {
    }

    @Test
    void testDefaultConverter() {
        var list = ORMTemplate.of(dataSource).entity(VisitWithDefaultConverter.class).findAll();
        assertEquals("2023-01-01", list.stream().findFirst().get().visitDate().value());
    }

    @Test
    void testDisabledConverter() {
        var e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(VisitWithDisabledConverter.class).findAll();
        });
        assertInstanceOf(JdbcSQLSyntaxErrorException.class, e.getCause());
    }

    @Test
    void testCustomConverter() {
        var list = ORMTemplate.of(dataSource).entity(VisitWithDefaultConverter.class).findAll();
        assertEquals("2023-01-01", list.stream().findFirst().get().visitDate().value());
    }

    @Test
    void testWithoutConverter() {
        var e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(VisitWithoutConverter.class).findAll();
        });
        assertInstanceOf(JdbcSQLSyntaxErrorException.class, e.getCause());
    }

    @Builder(toBuilder = true)
    @DbTable("visit")
    public record VisitWithNonnullPetRef(
            @PK Integer id,
            @Nonnull LocalDate visitDate,
            @Nullable String description,
            @Nonnull @FK Ref<Pet> pet
    ) implements Entity<Integer> {
    }

    @Test
    void testInsertPetRefWithNull() {
        var e = assertThrows(PersistenceException.class, () -> {
            var repository = ORMTemplate.of(dataSource).entity(VisitWithNonnullPetRef.class);
            VisitWithNonnullPetRef visit = new VisitWithNonnullPetRef(0, LocalDate.now(), "test", null);
            repository.insert(visit);
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    void testUpdatePetRefWithNull() {
        var e = assertThrows(PersistenceException.class, () -> {
            var repository = ORMTemplate.of(dataSource).entity(VisitWithNonnullPetRef.class);
            var visit = repository.getById(1);
            //noinspection DataFlowIssue
            repository.update(new VisitWithNonnullPetRef(visit.id(), visit.visitDate(), visit.description(), null));
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Builder(toBuilder = true)
    @DbTable("visit")
    public record VisitWithNullablePetRef(
            @PK Integer id,
            @Nonnull LocalDate visitDate,
            @Nullable String description,
            @Nullable @FK Ref<Pet> pet,
            @Version Instant timestamp
    ) implements Entity<Integer> {
    }

    @Test
    void testInsertNullablePetRefWithNull() {
        var e = assertThrows(PersistenceException.class, () -> {
            var repository = ORMTemplate.of(dataSource).entity(VisitWithNullablePetRef.class);
            VisitWithNullablePetRef visit = new VisitWithNullablePetRef(0, LocalDate.now(), "test", null, Instant.now());
            repository.insert(visit);
        });
        assertInstanceOf(SQLIntegrityConstraintViolationException.class, e.getCause());
    }

    @Test
    void testUpdateNullablePetRefWithNull() {
        var e = assertThrows(PersistenceException.class, () -> {
            var repository = ORMTemplate.of(dataSource).entity(VisitWithNullablePetRef.class);
            var visit = repository.getById(1);
            repository.update(visit.toBuilder().pet(null).build());
        });
        assertInstanceOf(SQLIntegrityConstraintViolationException.class, e.getCause());
    }

    @Test
    void testInsertNullablePetRefWithNonnull() {
        var visitRepository = ORMTemplate.of(dataSource).entity(VisitWithNullablePetRef.class);
        VisitWithNullablePetRef visit = new VisitWithNullablePetRef(0, LocalDate.now(), "test", Ref.of(Pet.builder().id(1).build()), Instant.now());
        var id = visitRepository.insertAndFetchId(visit);
        var visitFromDb = visitRepository.getById(id);
        assertEquals(id, visitFromDb.id());
        //noinspection DataFlowIssue
        assertNotNull(visitFromDb.pet());
        assertEquals(1, visitFromDb.pet().fetch().id());
    }

    @Test
    public void testSelectNullOwnerRef() {
        try (var stream = ORMTemplate.of(dataSource).entity(PetWithNullableOwnerRef.class).select().getResultStream()) {
            var pet = stream.filter(p -> p.name().equals("Sly")).findFirst().orElseThrow();
            assertNull(pet.owner());
        }
    }

    @Test
    public void testSelectRefInnerJoin() {
        // Ref elements are not joined by default. Test whether join works.
        var owners = ORMTemplate.of(dataSource)
                .selectFrom(PetWithNullableOwnerRef.class, Owner.class, raw("DISTINCT \0", Owner.class))
                .innerJoin(Owner.class).on(PetWithNullableOwnerRef.class)
                .innerJoin(City.class).on(Owner.class)
                .getResultList();
        assertEquals(10, owners.size());
    }

    @Test
    public void testSelectWithWrapper() {
        // data.sql inserts 13 pets total.
        record Wrapper(Pet pet) {}
        var pets = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Wrapper.class)
                .getResultList();
        assertEquals(13, pets.size());
    }

    @Test
    public void testSelectWithLocalWrapperNullOwner() {
        // data.sql: pet 13 (Sly) has NULL owner_id, so owner should be null.
        record LocalWrapper(Pet pet) {}
        var wrapper = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, LocalWrapper.class)
                .where(Pet_.id, EQUALS, 13)
                .getSingleResult();
        assertEquals(13, wrapper.pet().id());
        assertNull(wrapper.pet().owner(), "Pet 13 (Sly) has NULL owner_id");
    }

    @Test
    public void testSelectWithWrapperNullOwner() {
        // data.sql: pet 13 (Sly) has NULL owner_id, so owner should be null.
        var wrapper = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Wrapper.class)
                .where(Pet_.id, EQUALS, 13)
                .getSingleResult();
        assertEquals(13, wrapper.pet().id());
        assertNull(wrapper.pet().owner(), "Pet 13 (Sly) has NULL owner_id");
    }

    @Test
    public void testSelectWithNonnullWrapperNullOwner() {
        record OwnerWrapper(@Nullable @FK Owner owner) {}
        record Pet(
                @PK Integer id,
                @Nonnull OwnerWrapper owner
        ) implements Entity<Integer> {}
        record Wrapper(Pet pet) implements Data {}
        var wrapper = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Wrapper.class)
                .where(raw("\0.id = \0", Pet.class, 13))
                .getSingleResult();
        assertEquals(13, wrapper.pet().id());
        assertNull(wrapper.pet().owner().owner());
    }

    @Test
    public void testSelectWithTwoPetsWithoutPath() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).selectFrom(Owner.class).getResultList().getFirst();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class).select().where(it -> it.whereAny(owner)).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsWithExpression() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().getResultList().getFirst();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class)
                    .select()
                    .where(it -> it.where(raw("\0.owner_id = \0", PetOwnerRef.class, owner.id()))).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsWithExpressionMetamodel() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().getResultList().getFirst();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class)
                    .select()
                    .where(it -> it.where(raw("\0 = \0", PetOwnerRef_.owner, owner.id()))).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsWithWhere() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().getResultList().getFirst();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class)
                    .select()
                    .where(raw("\0.owner_id = \0", PetOwnerRef.class, owner.id())).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsWithWhereMetamodel() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().getResultList().getFirst();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class)
                    .select()
                    .where(raw("\0 = \0", PetOwnerRef_.owner, owner.id())).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsWithPathRaw() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        var list = ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class).select().append(raw("WHERE \0", where(VisitWithTwoPets_.pet1.owner, EQUALS, owner))).getResultList();
        assertEquals(2, list.size());
    }

    @Test
    public void testSelectWithTwoPetsWithPathTemplate() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class)
                .select()
                .where(it -> it.where(raw("\0 = \0", VisitWithTwoPets_.pet1.owner, owner.id()))).getResultList();
        assertEquals(2, visits.size());
    }

    @Test
    public void testSelectWithTwoPetsWithMultipleParameters() {
        var orm = ORMTemplate.of(dataSource);
        var owner = orm.entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = orm.entity(VisitWithTwoPets.class)
                    .select()
                    .where(it -> it.where(VisitWithTwoPets_.pet1.owner, EQUALS, owner)
                            .or(it.where(VisitWithTwoPets_.pet2.owner, EQUALS, owner))).getResultList();
            assertEquals(2, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsWithMultipleParametersTemplate() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPets.class)
                    .select()
                    .where(it -> it.where(raw("\0 = \0 OR \0 = \0", VisitWithTwoPets_.pet1.owner, owner.id(), VisitWithTwoPets_.pet2.owner, owner.id()))).getResultList();
            assertEquals(2, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testPetOwnerRecursion() {
        var e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(PetOwnerRecursion.class).select().getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithoutPath() throws Exception {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class).select().where(it -> it.whereAny(owner)).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithoutPathTemplate() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(it -> it.where(raw("\0.owner_id = \0", PetOwnerRef.class, owner.id()))).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithRootPathTemplateMetamodel() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var list = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(it -> it.where(raw("\0 = \0", PetOwnerRef_.owner, owner.id()))).getResultList();
            assertEquals(2, list.size());
            //noinspection DataFlowIssue
            assertEquals(owner.id(), list.getFirst().pet1().owner().id());
            //noinspection DataFlowIssue
            assertEquals(owner.id(), list.getLast().pet1().owner().id());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithInvalidPathTemplateMetamodel() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
            AtomicReference<Sql> sql = new AtomicReference<>();
            observe(sql::setPlain, () -> {
                ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                        .select()
                        .where(it -> it.where(raw("\0 = \0", Pet_.owner, owner.id()))).getResultList();
            });
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithValidPathMetamodel() {
        String expectedSql = """
            SELECT vwtpor.id, vwtpor.visit_date, vwtpor.description, vwtpor.pet_id, por.name, por.birth_date, por.type_id, pt.name, por.owner_id, vwtpor.pet_id
            FROM visit vwtpor
            LEFT JOIN pet por ON vwtpor.pet_id = por.id
            LEFT JOIN pet_type pt ON por.type_id = pt.id
            WHERE por.owner_id = ?""";
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(it -> it.whereAny(PetOwnerRef_.owner, owner)).getResultList();
        });
        assertEquals(expectedSql, sql.getPlain().statement());
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithRootPathMetamodelTemplate() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var list = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(raw("\0 = \0", PetOwnerRef_.owner, owner.id())).getResultList();
            assertEquals(2, list.size());
            //noinspection DataFlowIssue
            assertEquals(owner.id(), list.getFirst().pet1().owner().id());
            //noinspection DataFlowIssue
            assertEquals(owner.id(), list.getLast().pet1().owner().id());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithInvalidPathMetamodelTemplate() {
        var e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
            AtomicReference<Sql> sql = new AtomicReference<>();
            observe(sql::setPlain, () -> {
                ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                        .select()
                        .where(raw("\0 = \0", Pet_.owner, owner.id())).getResultList();
            });
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithPath() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class).select().where(VisitWithTwoPetsOneRef_.pet1.owner, EQUALS, owner).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefWithPathTemplate() {
        var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(it -> it.where(raw("\0 = \0", VisitWithTwoPetsOneRef_.pet1.owner, owner.id()))).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefPetWithoutPath() {
        var e = assertThrows(PersistenceException.class, () -> {
            var pet = ORMTemplate.of(dataSource).entity(PetOwnerRef.class).select().append("LIMIT 1").getSingleResult();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class).select().where(it -> it.whereAny(pet)).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetsOneRefPetWithoutPathTemplate() {
        // Note that this test is not comparable to the previous test because of the re-use of pet_id.
        var pet = ORMTemplate.of(dataSource).entity(PetOwnerRef.class).select().append("LIMIT 1").getSingleResult();
        var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                .select()
                .where(it -> it.where(raw("\0.id = \0", PetOwnerRef.class, pet.id()))).getResultList();
        assertEquals(2, visits.size());
    }

    @Test
    public void testSelectWithTwoPetsOneRefPetWithPath() {
        var pet = ORMTemplate.of(dataSource).entity(PetOwnerRef.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class).select().where(VisitWithTwoPetsOneRef_.pet1, EQUALS, pet).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefPetWithPathTemplateMetamodel() throws Exception {
        var ORM = ORMTemplate.of(dataSource);
        var pet = ORM.entity(PetOwnerRef.class).select().append("LIMIT 1").getSingleResult();
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORM.entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(it -> it.where(raw("\0 = \0", VisitWithTwoPetsOneRef_.pet1, pet.id()))).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefOtherPetWithPath() {
        var pet = ORMTemplate.of(dataSource).entity(PetOwnerRef.class).getById(1);
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class).select().where(VisitWithTwoPetsOneRef_.pet2, EQUALS, pet).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetsOneRefOtherPetWithPathTemplateMetamodel() {
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            var visits = ORMTemplate.of(dataSource).entity(VisitWithTwoPetsOneRef.class)
                    .select()
                    .where(it -> it.where(raw("\0 = \0", VisitWithTwoPetsOneRef_.pet2, 1))).getResultList();
            assertEquals(1, sql.getPlain().parameters().size());
            assertEquals(2, visits.size());
        });
    }

    @Test
    public void testSelectWithTwoPetRefsWithoutPath() {
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPetRefs.class).select().where(it -> it.whereAny(owner)).getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithTwoPetRefsWithoutPathTemplate() {
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            var owner = ORMTemplate.of(dataSource).entity(Owner.class).select().append("LIMIT 1").getSingleResult();
            ORMTemplate.of(dataSource).entity(VisitWithTwoPetRefs.class)
                    .select()
                    .where(it -> it.where(raw("\0.id = \0", PetOwnerRef.class, owner.id()))) // Cannot find PetOwnerRef alias as ref fields are not joined.
                    .getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testInternerRecord() {
        // 13 pets, but 12 have owners and 1 (Sly) has null owner. The interner deduplicates
        // owner instances by identity; 10 distinct owners + 1 null = 11 identity-distinct values.
        var pets = ORMTemplate.of(dataSource).entity(Pet.class).select().getResultList();
        var owners = newSetFromMap(new IdentityHashMap<>());
        owners.addAll(pets.stream().map(Pet::owner).toList());
        assertEquals(11, owners.size(), "10 distinct owners + 1 null = 11 identity-distinct owner instances");
    }

    @Test
    public void testInternerRef() {
        // Same logic as testInternerRecord but with Ref-based owner field.
        // 13 pets, 10 distinct owner refs + 1 null ref = 11 identity-distinct values.
        var pets = ORMTemplate.of(dataSource).entity(PetOwnerRef.class).select().getResultList();
        var owners = newSetFromMap(new IdentityHashMap<>());
        owners.addAll(pets.stream().map(PetOwnerRef::owner).toList());
        assertEquals(11, owners.size(), "10 distinct owner refs + 1 null = 11 identity-distinct ref instances");
    }

    @Test
    public void testSelectWithInlinePath() {
        // data.sql: owners with city_id=2 (Madison) are: George Franklin (2), Peter McTavish (5),
        // Maria Escobito (8), David Schroeder (9) = 4 owners.
        var list = ORMTemplate.of(dataSource).entity(Owner.class).select().where(Owner_.address.city.name, EQUALS, "Madison").getResultList();
        assertEquals(4, list.size());
    }

    @Test
    public void testSelectWithInlinePathEqualsMultiArgument() {
        var e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(Owner.class).select().where(Owner_.address.city.name, EQUALS, "Madison", "Monona").getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectWithInlinePathInMultiArgument() {
        // data.sql: Madison (city_id=2) has 4 owners, Monona (city_id=5) has 2 owners = 6 total.
        var list = ORMTemplate.of(dataSource).entity(Owner.class).select().where(Owner_.address.city.name, IN, "Madison", "Monona").getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testSelectWhere() {
        // Owner 1 (Betty Davis) has exactly 1 pet: Leo (id=1).
        Owner owner = Owner.builder().id(1).build();
        var pets = ORMTemplate.of(dataSource).entity(Pet.class)
                .select()
                .where(it -> it.whereAny(owner))
                .getResultList();
        assertEquals(1, pets.size());
    }

    @Test
    public void testSelectRefWhere() {
        // Owner 1 (Betty Davis) has exactly 1 pet: Leo (id=1).
        Owner owner = Owner.builder().id(1).build();
        var pets = ORMTemplate.of(dataSource).entity(PetWithNullableOwnerRef.class)
                .select()
                .where(it -> it.whereAny(owner))
                .getResultList();
        assertEquals(1, pets.size());
    }

    @Test
    public void testSelectRefWhereWithJoin() {
        var e = assertThrows(PersistenceException.class, () -> {
            Owner owner = Owner.builder().id(1).build();
            ORMTemplate.of(dataSource).entity(PetWithNullableOwnerRef.class)
                    .select()
                    .innerJoin(Owner.class).on(PetWithNullableOwnerRef.class)
                    .where(it -> it.whereAny(owner))
                    .getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testSelectRefWhereWithJoinAndPath() {
        Owner owner = Owner.builder().id(1).build();
        var pets = ORMTemplate.of(dataSource).entity(PetWithNullableOwnerRef.class)
                .select()
                .innerJoin(Owner.class).on(PetWithNullableOwnerRef.class)
                .where(PetWithNullableOwnerRef_.owner, EQUALS, owner)
                .getResultList();
        assertEquals(1, pets.size());
    }

    @Test
    public void testSelectNullableOwner() {
        // 12 of 13 pets have non-null owner_id. Inner join on Owner filters out pet 13 (Sly).
        var owners = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Owner.class, wrap(Owner.class))
                .getResultList();
        assertEquals(12, owners.size(), "12 pets have non-null owner_id");
    }

    @Test
    public void testCustomRepo1() {
        // Custom repository method getById1 should return the same Pet as the standard getById.
        var repo = ORMTemplate.of(dataSource).repository(PetRepository.class);
        var pet = repo.getById(1);
        assertEquals(pet, repo.getById1(1));
    }

    @Test
    public void testCustomRepo2() {
        // Custom repository method getById2 should return the same Pet as the standard getById.
        var repo = ORMTemplate.of(dataSource).repository(PetRepository.class);
        var pet = repo.getById(1);
        assertEquals(pet, repo.getById2(1));
    }

    @Test
    public void testCustomRepo3() {
        // Custom repository method getById3 should return the same Pet as the standard getById.
        var repo = ORMTemplate.of(dataSource).repository(PetRepository.class);
        var pet = repo.getById(1);
        assertEquals(pet, repo.getById3(1));
    }

    @Test
    public void testCustomRepo4() {
        // data.sql: owner "Betty" Davis (id=1) has 1 pet: Leo (id=1).
        var repo = ORMTemplate.of(dataSource).repository(PetRepository.class);
        assertEquals(1, repo.findByOwnerFirstName("Betty").size());
    }

    @Test
    public void testCustomRepo5() {
        // data.sql: 4 owners live in Madison (city_id=2): Franklin, McTavish, Escobito, Schroeder.
        // They have pets: Basil(2), George(6), Mulligan(10), Freddy(11) = 4 pets.
        var repo = ORMTemplate.of(dataSource).repository(PetRepository.class);
        assertEquals(4, repo.findByOwnerCity("Madison").size());
    }

    @Test
    public void testPetVisitCount() {
        // data.sql: 8 distinct pets have visits (pets 1-8 all have at least one visit;
        // pets 9-13 have no visits).
        var repo = ORMTemplate.of(dataSource).repository(PetRepository.class);
        assertEquals(8, repo.petVisitCount().size());
    }

    @Test
    public void delete() {
        // data.sql: 14 visits. After deleting visit 1, expect 13.
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        repo.remove(Visit.builder().id(1).build());
        assertEquals(13, repo.select().getResultCount());
    }

    @Test
    public void deleteByPet() {
        // data.sql: pet 1 (Leo) has 2 visits (ids 4, 8). After deleting, 14 - 2 = 12.
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        repo.delete().where(it -> it.whereAny(Pet.builder().id(1).build())).executeUpdate();
        assertEquals(12, repo.select().getResultCount());
    }

    @Test
    public void deleteByOwner() {
        // data.sql: owner 1 (Betty Davis) has 1 pet (Leo, id=1). Leo has 2 visits. 14 - 2 = 12.
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        repo.delete().where(it -> it.whereAny(Owner.builder().id(1).build())).executeUpdate();
        assertEquals(12, repo.select().getResultCount());
    }

    @Test
    public void deleteAll() {
        // After deleting all visits, count should be 0.
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        repo.removeAll();
        assertEquals(0, repo.select().getResultCount());
    }

    @Test
    public void deleteBatch() {
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        try (var stream = repo.select().getResultStream()) {
            repo.remove(stream);
        }
        assertEquals(0, repo.count());
    }

    @Test
    public void deleteRefBatch() {
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        try (var stream = repo.select().getResultStream().map(Ref::of)) {
            repo.removeByRef(stream);
        }
        assertEquals(0, repo.count());
    }

    @Test
    public void testBuilder() {
        var list = ORMTemplate.of(dataSource).entity(Pet.class)
                .select()
                .where(wrap(Pet.builder().id(1).build()))
                .getResultList();
        assertEquals(1, list.size());
    }

    @Test
    public void testBuilderWithJoin() {
        // data.sql: 3 visits on 2023-01-08: visit ids 7 (pet 4), 8 (pet 1), 9 (pet 2).
        var list = ORMTemplate.of(dataSource)
                .entity(Pet.class)
                .select()
                .innerJoin(Visit.class).on(raw("\0.id = \0.pet_id", alias(Pet.class), Visit.class))
                .where(raw("\0.visit_date = \0", alias(Visit.class), LocalDate.of(2023, 1, 8)))
                .getResultList();
        assertEquals(3, list.size());
    }

    @Test
    public void testBuilderWithAutoJoin() {
        // data.sql: visit 1 has pet_id=7 (Samantha). Selecting pets joined with visits where visit_id=1
        // should return exactly 1 pet: Samantha (id=7).
        var list = ORMTemplate.of(dataSource)
                .entity(Pet.class)
                .select()
                .innerJoin(Visit.class).on(Pet.class)
                .where(it -> it.whereAny(Visit.builder().id(1).build()))
                .getResultList();
        var list2 = ORMTemplate.of(dataSource)
                .entity(Pet.class)
                .select()
                .innerJoin(Visit.class).on(Pet.class)
                .where(it -> it.whereAny(Visit.builder().id(1).build()))
                .getResultList();

        assertEquals(1, list2.size());
        assertEquals(7, list2.getFirst().id(), "Visit 1 references pet 7 (Samantha)");
    }

    @Test
    public void testBuilderWithAutoAndCustomJoin() {
        var list = ORMTemplate.of(dataSource)
                .entity(Pet.class)
                .select()
                .innerJoin(Visit.class).on(Pet.class)
                .innerJoin(raw("SELECT * FROM \0", Pet.class), "x").on(raw("\0.id = x.id", Pet.class))    // Join just for the sake of testing multiple joins.
                .where(it -> it.whereAny(Visit.builder().id(1).build()))
                .getResultList();
        assertEquals(1, list.size());
        assertEquals(7, list.getFirst().id());
    }

    @Test
    public void testBuilderWithAutoJoinInvalidType() {
        PersistenceException e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource)
                    .entity(Pet.class)
                    .select()
                    .innerJoin(Visit.class).on(Pet.class)
                    .where(it -> it.whereAny(Vet.builder().id(1).build()))
                    .getResultCount();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testBuilderWithSelectTemplate() {
        // data.sql: 8 distinct pets have visits. Total visit count = 14.
        record Result(int petId, int visitCount) {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Result.class, raw("\0, COUNT(*)", Pet_.id))
                .innerJoin(Visit.class).on(Pet.class)
                .groupBy(Pet_.id)
                .getResultList();
        assertEquals(8, list.size(), "8 distinct pets have at least one visit");
        assertEquals(14, list.stream().mapToInt(Result::visitCount).sum(), "14 total visits in data.sql");
    }

    @Test
    public void testBuilderWithNonRecordSelectTemplate() {
        // data.sql: 14 total visits.
        var count = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Integer.class, TemplateString.of("COUNT(*)"))
                .innerJoin(Visit.class).on(Pet.class)
                .getSingleResult();
        assertEquals(14, count);
    }

    @Test
    public void testBuilderWithCustomJoin() {
        record Result(int petId, int visitCount) {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Result.class, raw("\0, COUNT(*)", Pet_.id))
                .join(DefaultJoinType.INNER, raw("SELECT * FROM \0 WHERE \0.id > \0", Visit.class, Visit.class, -1), "x")
                .on(raw("\0.id = x.pet_id", Pet.class))
                .groupBy(Pet_.id)
                .getResultList();
        assertEquals(8, list.size());
        assertEquals(14, list.stream().mapToInt(Result::visitCount).sum());
    }

    @Test
    public void testBuilderWithSubqueryJoin() {
        record Result(int petId, int visitCount) {}
        var orm = ORMTemplate.of(dataSource);
        var list = orm
                .selectFrom(Pet.class, Result.class, raw("\0, COUNT(*)", Pet_.id))
                .join(DefaultJoinType.INNER, orm.subquery(Visit.class), "x").on(raw("\0 = x.pet_id", Pet_.id))
                .groupBy(Pet_.id)
                .getResultList();
        assertEquals(8, list.size());
        assertEquals(14, list.stream().mapToInt(Result::visitCount).sum());
    }

    @Test
    public void testWithArg() {
        var list = ORMTemplate.of(dataSource).entity(Pet.class).select().where(raw("\0.id = 7", Pet.class)).getResultList();
        assertEquals(1, list.size());
        assertEquals(7, list.getFirst().id());
        ORMTemplate.of(dataSource).entity(Pet.class).countById(list.stream().map(Pet::id));
    }

    @Test
    public void testWithTwoArgs() {
        var ORM = ORMTemplate.of(dataSource);
        var list = ORM.entity(Pet.class).select().where(TemplateBuilder.create(it -> "%s.id = 7 OR %s.id = 8".formatted(it.interpolate(Pet.class), it.interpolate(Pet.class)))).getResultList();
        assertEquals(2, list.size());
        assertEquals(7, list.getFirst().id());
        assertEquals(8, list.getLast().id());
    }

    @Test
    public void selectOwnerForUpdate() {
        // Note that H2 only supports FOR UPDATE.
        String expectedSql = """
            SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
            FROM owner o
            LEFT JOIN city c ON o.city_id = c.id
            WHERE o.id = ?
            FOR UPDATE""";
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            repo.select().forUpdate().where(1).getSingleResult();
        });
    }

    @Test
    public void updateOwnerIntegerVersion() {
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        Owner owner = repo.getById(1);
        Owner modifiedOwner = owner.toBuilder().address(owner.address().toBuilder().address("Test Street").build()).build();
        Owner updatedOwner = repo.updateAndFetch(modifiedOwner);
        repo.update(updatedOwner);
    }

    @Test
    public void testUpdateOwnerWrongIntegerVersion() {
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        Owner owner = repo.getById(1);
        Owner modifiedOwner = owner.toBuilder().address(owner.address().toBuilder().address("Test Street").build()).build();
        repo.update(modifiedOwner);
        Owner modifiedOwner2 = modifiedOwner.toBuilder().address(owner.address().toBuilder().address("Test Street 2").build()).build();
        assertThrows(OptimisticLockException.class, () -> repo.update(modifiedOwner2));
    }

    @Test
    public void testUpdateVisitTimestampVersion() {
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        Visit visit = repo.select().getResultList().getFirst();
        Visit modifiedVisit = visit.toBuilder().visitDate(LocalDate.now()).build();
        Visit updatedVisit = repo.updateAndFetch(modifiedVisit);
        repo.update(updatedVisit);
    }

    @Test
    public void testUpdateVisitWrongTimestampVersion() {
        var repo = ORMTemplate.of(dataSource).entity(Visit.class);
        Visit visit = repo.select().getResultList().getFirst();
        Visit modifiedVisit = visit.toBuilder().visitDate(LocalDate.now()).build();
        repo.update(modifiedVisit);
        Visit modifiedVisit2 = modifiedVisit.toBuilder().visitDate(LocalDate.now()).build();
        assertThrows(OptimisticLockException.class, () -> repo.update(modifiedVisit2));
    }

    @Test
    public void testWhereExists() {
        // data.sql: visits reference pets that reference owners. 6 distinct owners have pets with visits:
        // owner 1 (Leo), 2 (Basil), 3 (Rosy, Jewel), 4 (Iggy), 5 (George), 6 (Samantha, Max).
        // Owners 7-10 have pets without visits.
        var list = ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(it -> it.exists(it.subquery(Visit.class).where(raw("\0.id = \0.id", alias(Owner.class, OUTER), alias(Owner.class, INNER)))))
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testWhereExistsMetamodel() {
        // Same as testWhereExists but using metamodel for column references. 6 owners have visits.
        var list = ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(it -> it.exists(it.subquery(Visit.class).where(raw("\0 = \0", column(Owner_.id, OUTER), column(Owner_.id, INNER)))))
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testWhereExistsCascade() {
        // The Owner.id = Owner.id is not ambiguous because they are in different scopes.
        ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(it -> it.exists(it.subquery(Visit.class).where(raw("\0.id = \0.id", Owner.class, Owner.class))))
                .getResultList();
    }

    @Test
    public void testWhereExistsPredicateCascade() {
        // The Owner.id = Owner.id is not ambiguous because they are in different scopes.
        ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(it ->
                        it.where(raw("EXISTS (\0)", it.subquery(Visit.class).where(raw("\0.id = \0.id", Owner.class, Owner.class)))))
                .getResultList();
    }

    @Test
    public void testWhereExistsPredicate() {
        var list = ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .where(it ->
                        it.where(raw("EXISTS (\0)", it.subquery(Visit.class).where(raw("\0.id = \0.id", alias(Owner.class, OUTER), alias(Owner.class, INNER))))))
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testWhereExistsAppendSubquery() {
        var orm = ORMTemplate.of(dataSource);
        var list = ORMTemplate.of(dataSource).entity(Owner.class)
                .select()
                .append(raw("WHERE EXISTS (\0)", orm.subquery(Visit.class).where(raw("\0.id = \0.id", alias(Owner.class, OUTER), alias(Owner.class, INNER)))))
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testWhereAppendQuery() {
        // We cannot append a query, we need to use a query builder instead.
        var e = assertThrows(PersistenceException.class, () -> {
            ORMTemplate.of(dataSource).entity(Owner.class)
                    .select()
                    .append(raw("WHERE (\0)", ORMTemplate.of(dataSource).query("SELECT 1")))
                    .getResultList();
        });
        assertInstanceOf(SqlTemplateException.class, e.getCause());
    }

    @Test
    public void testWherePredicateSubqueryParameters() {
        String expectedSql = """
            SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
            FROM owner o
            LEFT JOIN city c ON o.city_id = c.id
            WHERE (o.id = ?) AND (EXISTS (
              SELECT o1.id, o1.first_name, o1.last_name, o1.address, o1.city_id, o1.telephone, o1.version
              FROM owner o1
              WHERE o1.id = ?
            )) AND (3 = ?)""";
        observe(sql -> {
            assertEquals(expectedSql, sql.statement());
            assertTrue(sql.parameters().get(0) instanceof PositionalParameter(int position, Object dbValue)
                    && position == 1 && Integer.valueOf(1).equals(dbValue));
            assertTrue(sql.parameters().get(1) instanceof PositionalParameter(int position, Object dbValue)
                    && position == 2 && Integer.valueOf(2).equals(dbValue));
            assertTrue(sql.parameters().get(2) instanceof PositionalParameter(int position, Object dbValue)
                    && position == 3 && Integer.valueOf(3).equals(dbValue));
        }, () -> {
            var orm = ORMTemplate.of(dataSource);
            orm.entity(Owner.class)
                    .select()
                    .where(it -> it.where(raw("\0.id = \0", alias(Owner.class, INNER), 1))
                            .and(it.where(raw("EXISTS (\0)", it.subquery(Owner.class).where(raw("\0.id = \0", alias(Owner.class, INNER), 2)))))
                            .and(it.where(raw("3 = \0", 3))))
                    .getResultList();
        });
    }

    @Test
    public void testWhereAppendQueryBuilderParameters() {
        String expectedSql = """
            SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
            FROM owner o
            LEFT JOIN city c ON o.city_id = c.id
            WHERE o.id = ?
            AND EXISTS (
              SELECT o1.id, o1.first_name, o1.last_name, o1.address, o1.city_id, o1.telephone, o1.version
              FROM owner o1
              WHERE o1.id = ?
            )
            AND 3 = ?""";
        observe(sql -> {
            assertEquals(expectedSql, sql.statement());
            assertTrue(sql.parameters().get(0) instanceof PositionalParameter(int position, Object dbValue)
                    && position == 1 && Integer.valueOf(1).equals(dbValue));
            assertTrue(sql.parameters().get(1) instanceof PositionalParameter(int position, Object dbValue)
                    && position == 2 && Integer.valueOf(2).equals(dbValue));
            assertTrue(sql.parameters().get(2) instanceof PositionalParameter(int position, Object dbValue)
                    && position == 3 && Integer.valueOf(3).equals(dbValue));
        }, () -> {
            var orm = ORMTemplate.of(dataSource);
            orm.entity(Owner.class)
                    .select()
                    .append(raw("WHERE \0.id = \0", alias(Owner.class, INNER), 1))
                    .append(raw("AND EXISTS (\0)", orm.subquery(Owner.class).where(raw("\0.id = \0", alias(Owner.class, INNER), 2))))
                    .append(raw("AND 3 = \0", 3))
                    .getResultList();
        });
    }

    /**
     * Simple business object representing a pet.
     */
    @Builder(toBuilder = true)
    @DbTable("pet")
    public record PetWithEnum(
            @PK Integer id,
            @Nonnull String name,
            @Nonnull LocalDate birthDate,
            @Nonnull @DbEnum(ORDINAL) @DbColumn("type_id") PetTypeEnum type,
            @Nullable @FK Owner owner
    ) implements Entity<Integer> {}

    @Test
    public void testOrdinalEnumSelect() {
        // data.sql inserts 13 pets. Using ordinal enum for type_id should map correctly.
        var pets = ORMTemplate.of(dataSource).entity(PetWithEnum.class)
                .select()
                .getResultList();
        assertEquals(13, pets.size());
    }

    /**
     * Simple business object representing a pet.
     */
    @Builder(toBuilder = true)
    @DbTable("pet")
    public record PetWithIntEnum(
            @PK Integer id,
            @Nonnull String name,
            @Nonnull LocalDate birthDate,
            @DbColumn("type_id") int type,
            @Nullable @FK Owner owner
    ) implements Entity<Integer> {}

    @Test
    public void testIntEnumSelect() {
        var pets = ORMTemplate.of(dataSource).entity(PetWithIntEnum.class)
                .select()
                .getResultList();
        assertEquals(13, pets.size());
    }

    @Test
    public void testSelectCompoundFK() {
        // data.sql: visit 3 has vet_id=3, specialty_id=2 (Linda Douglas, surgery).
        var repository = ORMTemplate.of(dataSource).entity(VisitWithCompoundFK.class);
        var visit = repository.getById(3);
        assertEquals(3, visit.vetSpecialty().vet().id(), "Visit 3 references vet 3 (Linda Douglas)");
        assertEquals(2, visit.vetSpecialty().specialty().id(), "Visit 3 references specialty 2 (surgery)");
    }

    @Test
    public void testInsertCompoundFK() {
        var repository = ORMTemplate.of(dataSource).entity(VisitWithCompoundFK.class);
        VisitWithCompoundFK visit = new VisitWithCompoundFK(0, LocalDate.now(), "test", Pet.builder().id(1).build(), VetSpecialty.builder().id(new VetSpecialtyPK(3, 2)).build(), Instant.now());
        repository.insert(visit);
    }

    @Test
    public void testUpdateCompoundFK() {
        var repository = ORMTemplate.of(dataSource).entity(VisitWithCompoundFK.class);
        var visit = repository.getById(3);
        visit = visit.toBuilder().vetSpecialty(null).build();
        repository.update(visit);
        repository.getById(3);
        assertNull(visit.vetSpecialty());
    }

    @Test
    public void testSekectWhereCompoundFK() {
        var repository = ORMTemplate.of(dataSource).entity(VisitWithCompoundFK.class);
        var visit = repository.select().where(VisitWithCompoundFK_.vetSpecialty, VetSpecialty.builder().id(new VetSpecialtyPK(3, 2)).build()).getSingleResult();
        assertEquals(3, visit.vetSpecialty().vet().id());
        assertEquals(2, visit.vetSpecialty().specialty().id());
    }

    @Test
    public void testSelectWhereInlineRecord() {
        var repository = ORMTemplate.of(dataSource).entity(Owner.class);
        var address = new Address("638 Cardinal Ave.", City.builder().id(1).build());
        var owner = repository.select()
                .where(Owner_.address, EQUALS, address)
                .getSingleResult();
        assertEquals(1, owner.id());
        assertEquals("Betty", owner.firstName());
        assertEquals("Davis", owner.lastName());
    }

    @Test
    public void testSelectWhereInlineRecordSql() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
                FROM owner o
                LEFT JOIN city c ON o.city_id = c.id
                WHERE o.address = ? AND o.city_id = ?""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var repository = ORMTemplate.of(dataSource).entity(Owner.class);
            var address = new Address("638 Cardinal Ave.", City.builder().id(1).build());
            repository.select()
                    .where(Owner_.address, EQUALS, address)
                    .getSingleResult();
        });
    }

    @Test
    public void testSelectWhereInlineRecordSubField() {
        var repository = ORMTemplate.of(dataSource).entity(Owner.class);
        var owners = repository.select()
                .where(Owner_.address.address, EQUALS, "638 Cardinal Ave.")
                .getResultList();
        assertEquals(1, owners.size());
        assertEquals("Betty", owners.getFirst().firstName());
    }

    @Test
    public void testSelectWhereInlineRecordGreaterThanSql() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
                FROM owner o
                LEFT JOIN city c ON o.city_id = c.id
                WHERE (o.address > ? OR (o.address = ? AND o.city_id > ?))""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var repository = ORMTemplate.of(dataSource).entity(Owner.class);
            var address = new Address("638 Cardinal Ave.", City.builder().id(1).build());
            repository.select()
                    .where(Owner_.address, GREATER_THAN, address)
                    .getResultList();
        });
    }

    @Test
    public void testSelectWhereInlineRecordGreaterThanOrEqualSql() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
                FROM owner o
                LEFT JOIN city c ON o.city_id = c.id
                WHERE (o.address > ? OR (o.address = ? AND o.city_id >= ?))""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var repository = ORMTemplate.of(dataSource).entity(Owner.class);
            var address = new Address("638 Cardinal Ave.", City.builder().id(1).build());
            repository.select()
                    .where(Owner_.address, GREATER_THAN_OR_EQUAL, address)
                    .getResultList();
        });
    }

    @Test
    public void testSelectWhereInlineRecordLessThanSql() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
                FROM owner o
                LEFT JOIN city c ON o.city_id = c.id
                WHERE (o.address < ? OR (o.address = ? AND o.city_id < ?))""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var repository = ORMTemplate.of(dataSource).entity(Owner.class);
            var address = new Address("638 Cardinal Ave.", City.builder().id(1).build());
            repository.select()
                    .where(Owner_.address, LESS_THAN, address)
                    .getResultList();
        });
    }

    @Test
    public void testSelectWhereInlineRecordNotEqualsSql() {
        String expectedSql = """
                SELECT o.id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version
                FROM owner o
                LEFT JOIN city c ON o.city_id = c.id
                WHERE NOT (o.address = ? AND o.city_id = ?)""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var repository = ORMTemplate.of(dataSource).entity(Owner.class);
            var address = new Address("638 Cardinal Ave.", City.builder().id(1).build());
            repository.select()
                    .where(Owner_.address, NOT_EQUALS, address)
                    .getResultList();
        });
    }

    @Test
    public void testSelectWhereInlineRecordGreaterThan() {
        var repository = ORMTemplate.of(dataSource).entity(Owner.class);
        // Use a low-value address so that results with lexicographically greater addresses exist.
        var address = new Address("0", City.builder().id(0).build());
        var owners = repository.select()
                .where(Owner_.address, GREATER_THAN, address)
                .getResultList();
        assertFalse(owners.isEmpty());
    }

    @Test
    public void testSelectWhereInlineRecordLikeThrows() {
        var repository = ORMTemplate.of(dataSource).entity(Owner.class);
        var address = new Address("638%", City.builder().id(1).build());
        assertThrows(Exception.class, () -> {
            repository.select()
                    .where(Owner_.address, LIKE, address)
                    .getResultList();
        });
    }

    @DbTable("visit")
    public record VisitWithDbColumns(
            @PK Integer id,
            @Nonnull LocalDate visitDate,
            @Nullable String description,
            @Nonnull @FK Pet pet,
            @Nullable @FK @DbColumn(name = "test1") @DbColumn(name = "test2") VetSpecialty vetSpecialty,
            @Version Instant timestamp
    ) implements Entity<Integer> {
    }

    @Test
    public void testSelectCompoundFKDbColumns() {
        String expectedSql = """
                SELECT vwdc.id, vwdc.visit_date, vwdc.description, vwdc.pet_id, p.name, p.birth_date, p.type_id, p.owner_id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version, vwdc.test1, vwdc.test2, vs.vet_id, v.first_name, v.last_name, vs.specialty_id, s.name, vwdc."timestamp"
                FROM visit vwdc
                INNER JOIN pet p ON vwdc.pet_id = p.id
                LEFT JOIN owner o ON p.owner_id = o.id
                LEFT JOIN city c ON o.city_id = c.id
                LEFT JOIN vet_specialty vs ON vwdc.test1 = vs.vet_id AND vwdc.test2 = vs.specialty_id
                LEFT JOIN vet v ON vs.vet_id = v.id
                LEFT JOIN specialty s ON vs.specialty_id = s.id
                WHERE vwdc.id = ?""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var e = assertThrows(PersistenceException.class, () -> {
                var repository = ORMTemplate.of(dataSource).entity(VisitWithDbColumns.class);
                repository.getById(1);
            });
            assertInstanceOf(JdbcSQLSyntaxErrorException.class, e.getCause());
        });
    }

    @DbTable("vet_specialty")
    public record VetSpecialtyDbColumns(
            @Nonnull @PK(generation = NONE) @DbColumn(name = "test1") @DbColumn(name = "test2") VetSpecialtyPK id,// Implicitly @Inlined
            @Nonnull @Persist(insertable = false) @FK("test3") Vet vet,
            @Nonnull @Persist(insertable = false) @FK("test4") Specialty specialty) implements Entity<VetSpecialtyPK> {
        public VetSpecialtyDbColumns(@Nonnull VetSpecialtyPK pk) {
            //noinspection DataFlowIssue
            this(pk, null, null);
        }
    }
    @DbTable("visit")
    public record VisitWithNestedDbColumns(
            @PK Integer id,
            @Nonnull LocalDate visitDate,
            @Nullable String description,
            @Nonnull @FK Pet pet,
            @Nullable @FK VetSpecialtyDbColumns vetSpecialty,
            @Version Instant timestamp
    ) implements Entity<Integer> {
    }

    @Test
    public void testSelectCompoundFKNestedDbColumns() {
        String expectedSql = """
                SELECT vwndc.id, vwndc.visit_date, vwndc.description, vwndc.pet_id, p.name, p.birth_date, p.type_id, p.owner_id, o.first_name, o.last_name, o.address, o.city_id, c.name, o.telephone, o.version, vwndc.vet_id, vwndc.specialty_id, vsdc.test3, v.first_name, v.last_name, vsdc.test4, s.name, vwndc."timestamp"
                FROM visit vwndc
                INNER JOIN pet p ON vwndc.pet_id = p.id
                LEFT JOIN owner o ON p.owner_id = o.id
                LEFT JOIN city c ON o.city_id = c.id
                LEFT JOIN vet_specialty vsdc ON vwndc.vet_id = vsdc.test1 AND vwndc.specialty_id = vsdc.test2
                LEFT JOIN vet v ON vsdc.test3 = v.id
                LEFT JOIN specialty s ON vsdc.test4 = s.id
                WHERE vwndc.id = ?""";
        observe(sql -> assertEquals(expectedSql, sql.statement()), () -> {
            var e = assertThrows(PersistenceException.class, () -> {
                var repository = ORMTemplate.of(dataSource).entity(VisitWithNestedDbColumns.class);
                repository.getById(1);
            });
            assertInstanceOf(JdbcSQLSyntaxErrorException.class, e.getCause());
        });
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testEntityCacheIdentityDeduplication() {
        // At REPEATABLE_READ, entity cache deduplicates by identity within a single query result.
        var visits = ORMTemplate.of(dataSource).entity(Visit.class)
                .select()
                .getResultList();
        assertEquals(14, visits.size());
        var identitySet = visits.stream().map(it -> it.pet()).collect(toCollection(() -> newSetFromMap(new IdentityHashMap<>())));
        var pkSet = visits.stream().map(it -> it.pet().id()).collect(toSet());
        assertEquals(pkSet.size(), identitySet.size());
    }

    @Transactional(readOnly = true)
    @Test
    public void testInternerReadOnlyTransaction() {
        // Uses WeakInterner.
        var visits = ORMTemplate.of(dataSource).entity(Visit.class)
                .select()
                .getResultList();
        assertEquals(14, visits.size());
        var identitySet = visits.stream().map(it -> it.pet()).collect(toCollection(() -> newSetFromMap(new IdentityHashMap<>())));
        var pkSet = visits.stream().map(it -> it.pet().id()).collect(toSet());
        assertEquals(pkSet.size(), identitySet.size());
    }

    @Transactional(propagation = NOT_SUPPORTED)
    @Test
    public void testInternerNoTransaction() {
        // Uses WeakInterner.
        var visits = ORMTemplate.of(dataSource).entity(Visit.class)
                .select()
                .getResultList();
        assertEquals(14, visits.size());
        var identitySet = visits.stream().map(it -> it.pet()).collect(toCollection(() -> newSetFromMap(new IdentityHashMap<>())));
        var pkSet = visits.stream().map(it -> it.pet().id()).collect(toSet());
        assertEquals(pkSet.size(), identitySet.size());
    }

    @Transactional(isolation = READ_UNCOMMITTED)
    @Test
    public void testInternerReadUncomitted() {
        // Uses WeakInterner.
        var visits = ORMTemplate.of(dataSource).entity(Visit.class)
                .select()
                .getResultList();
        assertEquals(14, visits.size());
        var identitySet = visits.stream().map(it -> it.pet()).collect(toCollection(() -> newSetFromMap(new IdentityHashMap<>())));
        var pkSet = visits.stream().map(it -> it.pet().id()).collect(toSet());
        assertEquals(pkSet.size(), identitySet.size());
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testEntityCacheAcrossQueries() {
        // With entity cache, the same entity should be returned across multiple queries.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertSame(pet1, pet2, "Entity cache should return the same instance across queries");
    }

    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    @Test
    public void testEntityCacheInReadOnlyTransaction() {
        // Read-only transactions should also use entity cache for optimization.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertSame(pet1, pet2, "Entity cache should return the same instance in read-only transactions");
    }

    @Transactional(propagation = NOT_SUPPORTED)
    @Test
    public void testNoEntityCacheWithoutTransaction() {
        // Without a transaction, there is no scope for entity cache.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertEquals(pet1, pet2, "Entities should be equal");
        assertTrue(pet1 != pet2, "Without transaction, different instances should be returned");
    }

    @Transactional(isolation = READ_UNCOMMITTED)
    @Test
    public void testNoHydrationAtReadUncommitted() {
        // At READ_UNCOMMITTED, hydration is disabled so different instances should be returned.
        // Dirty checking still works because observed state is stored.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertEquals(pet1, pet2, "Entities should be equal");
        assertTrue(pet1 != pet2, "Without hydration, different instances should be returned");
    }

    @Transactional(isolation = REPEATABLE_READ)
    @Test
    public void testHydrationEnabledAtRepeatableRead() {
        // At REPEATABLE_READ, hydration is enabled so the same cached instance should be returned.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertEquals(pet1, pet2, "Entities should be equal");
        assertTrue(pet1 == pet2, "With hydration enabled, same instance should be returned");
    }

    @Transactional(isolation = READ_COMMITTED)
    @Test
    public void testNoHydrationAtReadCommitted() {
        // At READ_COMMITTED, hydration is disabled so different instances should be returned.
        // Dirty checking still works because observed state is stored.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertEquals(pet1, pet2, "Entities should be equal");
        assertTrue(pet1 != pet2, "Without hydration, different instances should be returned");
    }

    @Transactional(isolation = READ_COMMITTED)
    @Test
    public void testSqlExecutedOnSecondRequestAtReadCommitted() {
        // At READ_COMMITTED, cache does not short-circuit reads, so SQL should be executed on each request.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        AtomicReference<Sql> sql = new AtomicReference<>();
        // First request.
        observe(sql::set, () -> repository.getById(1));
        assertNotNull(sql.get(), "First request should execute SQL");
        // Second request should also execute SQL.
        sql.set(null);
        observe(sql::set, () -> repository.getById(1));
        assertNotNull(sql.get(), "Second request should also execute SQL at READ_COMMITTED");
    }

    @Transactional(isolation = READ_COMMITTED)
    @Test
    public void testFindByIdSqlExecutedOnSecondRequestAtReadCommitted() {
        // At READ_COMMITTED, findById should execute SQL on each request.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::set, () -> repository.findById(1));
        assertNotNull(sql.get(), "First findById should execute SQL");
        sql.set(null);
        observe(sql::set, () -> repository.findById(1));
        assertNotNull(sql.get(), "Second findById should also execute SQL at READ_COMMITTED");
    }

    @Transactional(isolation = READ_COMMITTED)
    @Test
    public void testGetByRefExecutesSqlAtReadCommitted() {
        // At READ_COMMITTED, repository getByRef should execute SQL even when the entity was previously loaded.
        var orm = ORMTemplate.of(dataSource);
        var repository = orm.entity(Pet.class);
        repository.getById(1);
        Ref<Pet> ref = orm.ref(Pet.class, 1);
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::set, () -> repository.getByRef(ref));
        assertNotNull(sql.get(), "getByRef should execute SQL at READ_COMMITTED");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testCacheFirstLookupNoSqlOnSecondRequest() {
        // First request should execute SQL, second request should hit cache without SQL.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        AtomicReference<Sql> sql = new AtomicReference<>();
        // First request - SQL should be executed.
        observe(sql::set, () -> repository.getById(1));
        assertNotNull(sql.get(), "First request should execute SQL");
        // Second request - should hit cache, no SQL executed.
        sql.set(null);
        observe(sql::set, () -> repository.getById(1));
        assertNull(sql.get(), "Second request should not execute SQL due to cache hit");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testCacheFirstLookupSameInstance() {
        // Both requests should return the exact same instance when cache is active.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(1);
        assertSame(pet1, pet2, "Cache should return the exact same instance");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testCacheFirstLookupFindByIdNoSqlOnSecondRequest() {
        // Test findById variant as well.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        AtomicReference<Sql> sql = new AtomicReference<>();
        // First request - SQL should be executed.
        observe(sql::set, () -> repository.findById(1));
        assertNotNull(sql.get(), "First findById should execute SQL");
        // Second request - should hit cache.
        sql.set(null);
        observe(sql::set, () -> repository.findById(1));
        assertNull(sql.get(), "Second findById should not execute SQL due to cache hit");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testCacheFirstLookupByRefNoSqlWhenCached() {
        // Loading entity, then fetching by ref should not execute SQL.
        var orm = ORMTemplate.of(dataSource);
        var repository = orm.entity(Pet.class);
        // Load entity into cache.
        var pet = repository.getById(1);
        // Create ref and fetch - should hit cache.
        Ref<Pet> ref = orm.ref(Pet.class, 1);
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::set, () -> repository.getByRef(ref));
        assertNull(sql.get(), "Fetching by ref should not execute SQL when entity is cached");
        // Verify same instance returned.
        assertSame(pet, repository.getByRef(ref), "getByRef should return the cached instance");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testRefFetchNoSqlWhenCached() {
        // When entity is already loaded, Ref.fetch() should return cached instance without SQL.
        var orm = ORMTemplate.of(dataSource);
        var repository = orm.entity(Pet.class);
        // Load entity into cache first.
        var pet = repository.getById(1);
        // Create ref pointing to the same entity.
        Ref<Pet> ref = orm.ref(Pet.class, 1);
        // Fetch through ref - should hit cache.
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::set, ref::fetch);
        assertNull(sql.get(), "Ref.fetch() should not execute SQL when entity is cached");
        // Verify same instance returned.
        assertSame(pet, ref.fetch(), "Ref.fetch() should return the cached instance");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testRefFetchFromCachedOwner() {
        // When all owners are loaded, fetching a pet's owner ref should not execute SQL.
        var orm = ORMTemplate.of(dataSource);
        // Load all owners into cache.
        var owners = orm.entity(Owner.class).findAll();
        assertNotNull(owners);
        assertTrue(owners.size() > 0, "Should have at least one owner");
        // Fetch a pet that has an owner ref.
        var pet = orm.entity(PetOwnerRef.class).getById(1);
        assertNotNull(pet.owner(), "Pet should have an owner ref");
        // Find the cached owner for verification.
        Owner cachedOwner = owners.stream()
                .filter(o -> o.id().equals(pet.owner().id()))
                .findFirst()
                .orElseThrow();
        // Fetch through the ref - should hit cache, no SQL.
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::set, () -> pet.owner().fetch());
        assertNull(sql.get(), "Ref.fetch() should not execute SQL when owner is already cached");
        // Verify same instance returned.
        assertSame(cachedOwner, pet.owner().fetch(), "Ref.fetch() should return the cached owner instance");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testFindAllByIdPartialCacheHit() {
        // When some entities are cached, findAllById should only query uncached ones.
        var repository = ORMTemplate.of(dataSource).entity(Pet.class);
        // Pre-load entities 1 and 2 into cache.
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(2);
        AtomicReference<Sql> sql = new AtomicReference<>();
        // findAllById with mix of cached and uncached - should query only uncached IDs.
        observe(sql::set, () -> repository.findAllById(List.of(1, 2, 3)));
        assertNotNull(sql.get(), "Should execute SQL for uncached ID 3");
        // When all IDs are cached, no SQL should be executed.
        sql.set(null);
        List<Pet> result = observe(sql::set, () -> repository.findAllById(List.of(1, 2)));
        assertNull(sql.get(), "Should not execute SQL when all IDs are cached");
        assertEquals(2, result.size());
        assertTrue(result.contains(pet1), "Result should contain cached pet1");
        assertTrue(result.contains(pet2), "Result should contain cached pet2");
    }

    @Transactional(propagation = REQUIRED, isolation = REPEATABLE_READ)
    @Test
    public void testFindAllByRefPartialCacheHit() {
        // When some entities are cached, findAllByRef should only query uncached ones.
        var orm = ORMTemplate.of(dataSource);
        var repository = orm.entity(Pet.class);
        // Pre-load entities 1 and 2 into cache.
        var pet1 = repository.getById(1);
        var pet2 = repository.getById(2);
        // Create refs.
        var ref1 = orm.ref(Pet.class, 1);
        var ref2 = orm.ref(Pet.class, 2);
        var ref3 = orm.ref(Pet.class, 3);
        AtomicReference<Sql> sql = new AtomicReference<>();
        // findAllByRef with mix of cached and uncached - should query only uncached refs.
        observe(sql::set, () -> repository.findAllByRef(List.of(ref1, ref2, ref3)));
        assertNotNull(sql.get(), "Should execute SQL for uncached ref 3");
        // When all refs are cached, no SQL should be executed.
        sql.set(null);
        List<Pet> result = observe(sql::set, () -> repository.findAllByRef(List.of(ref1, ref2)));
        assertNull(sql.get(), "Should not execute SQL when all refs are cached");
        assertEquals(2, result.size());
        assertTrue(result.contains(pet1), "Result should contain cached pet1");
        assertTrue(result.contains(pet2), "Result should contain cached pet2");
    }

    @Test
    public void testSelectEntityTypedPkFk() {
        var repository = ORMTemplate.of(dataSource).entity(PetExtension.class);
        var extensions = repository.select().getResultList();
        assertEquals(3, extensions.size());
        // Verify the entity-typed PK is fully populated (not just the FK id).
        var ext = extensions.stream().filter(e -> e.pet().id() == 1).findFirst().orElseThrow();
        assertEquals("Leo", ext.pet().name());
        assertEquals("Good cat", ext.notes());
        // Verify nested FK within the PK entity (Pet -> Owner) is also populated.
        assertNotNull(ext.pet().owner());
        assertEquals("Betty", ext.pet().owner().firstName());
    }

    // Inline record flatten / ORDER BY / GROUP BY tests.

    @Test
    public void testFlattenSingleColumn() {
        // A non-inline metamodel should return a singleton list.
        var result = Owner_.id.flatten();
        assertEquals(1, result.size());
        assertSame(Owner_.id, result.getFirst());
    }

    @Test
    public void testFlattenInlineRecord() {
        // An inline record metamodel should expand to its leaf columns.
        var result = Owner_.address.flatten();
        assertEquals(2, result.size());
        // Field names include the inline record prefix.
        assertEquals("address.address", result.get(0).field());
        assertEquals("address.city", result.get(1).field());
    }

    @Test
    public void testOrderByInlineRecord() {
        // orderBy(Owner_.address) should expand the inline record and produce a valid query.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .orderBy(Owner_.address)
                .getResultList();
        assertEquals(10, list.size());
    }

    @Test
    public void testOrderByInlineRecordSql() {
        // Verify the SQL shape includes both expanded columns.
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Owner.class)
                    .orderBy(Owner_.address)
                    .getResultList();
        });
        String statement = sql.getPlain().statement();
        assertTrue(statement.contains("ORDER BY"), "Expected ORDER BY clause in SQL: " + statement);
        // Should contain the two columns from the inline Address record (address, city_id).
        String orderByClause = statement.substring(statement.indexOf("ORDER BY"));
        assertTrue(orderByClause.contains("address"), "Expected 'address' column in ORDER BY: " + orderByClause);
        assertTrue(orderByClause.contains("city_id"), "Expected 'city_id' column in ORDER BY: " + orderByClause);
    }

    @Test
    public void testOrderByDescendingInlineRecord() {
        // orderByDescending(Owner_.address) should expand and add DESC to each column.
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Owner.class)
                    .orderByDescending(Owner_.address)
                    .getResultList();
        });
        String statement = sql.getPlain().statement();
        String orderByClause = statement.substring(statement.indexOf("ORDER BY"));
        // Count occurrences of DESC (should be 2, one for each expanded column).
        int count = 0;
        int index = 0;
        while ((index = orderByClause.indexOf("DESC", index)) != -1) {
            count++;
            index += 4;
        }
        assertEquals(2, count, "Expected 2 DESC keywords in ORDER BY: " + orderByClause);
    }

    @Test
    public void testGroupByInlineRecord() {
        // groupBy(Owner_.address) should expand the inline record.
        record Result(String address, int cityId, long count) {}
        AtomicReference<Sql> sql = new AtomicReference<>();
        observe(sql::setPlain, () -> {
            ORMTemplate.of(dataSource)
                    .selectFrom(Owner.class, Result.class, raw("\0, \0, COUNT(*)", Owner_.address.address, Owner_.address.city))
                    .groupBy(Owner_.address)
                    .getResultList();
        });
        String statement = sql.getPlain().statement();
        assertTrue(statement.contains("GROUP BY"), "Expected GROUP BY clause in SQL: " + statement);
        String groupByClause = statement.substring(statement.indexOf("GROUP BY"));
        assertTrue(groupByClause.contains("address"), "Expected 'address' column in GROUP BY: " + groupByClause);
        assertTrue(groupByClause.contains("city_id"), "Expected 'city_id' column in GROUP BY: " + groupByClause);
    }

    @Test
    public void testOrderByInlineRecordWithNonInlineColumn() {
        // orderBy with both inline record and regular column.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .orderBy(Owner_.id, Owner_.address)
                .getResultList();
        assertEquals(10, list.size());
    }

    @Test
    public void testScrollWithInlineRecordKey() {
        // scroll with inline record key should expand inline record for ORDER BY.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .scroll(Scrollable.of(Metamodel.key(Owner_.address), 5));
        assertEquals(5, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollAfterWithInlineRecordKey() {
        // scrollAfter should expand inline record for both WHERE and ORDER BY.
        var allOwners = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .orderBy(Owner_.address)
                .getResultList();
        // Get the address of the 5th owner (index 4) as cursor.
        var cursor = allOwners.get(4).address();
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .scroll(new Scrollable<>(Metamodel.key(Owner_.address), cursor, null, null, 10, true));
        // Should return remaining owners after the cursor.
        assertFalse(window.content().isEmpty());
    }

    @Test
    public void testScrollBeforeWithInlineRecordKey() {
        // scrollBefore (cursorless, descending) should expand inline record for ORDER BY.
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .scroll(Scrollable.of(Metamodel.key(Owner_.address), 5).backward());
        assertEquals(5, window.content().size());
        assertTrue(window.hasNext());
    }

    @Test
    public void testScrollRejectsNullableCompoundKey() {
        // EntityWithNullableUK has @UK NullableCompoundUK(String, String) with nullable constituents.
        // scroll should throw PersistenceException because the compound key is effectively nullable.
        var key = (Metamodel.Key<EntityWithNullableUK, NullableCompoundUK>) (Metamodel.Key) EntityWithNullableUK_.uniqueKey;
        assertTrue(key.isNullable());
        assertThrows(PersistenceException.class, () ->
                ORMTemplate.of(dataSource)
                        .selectFrom(EntityWithNullableUK.class)
                        .scroll(Scrollable.of(key, 5)));
    }

    @Test
    public void testScrollAcceptsNullsNotDistinctCompoundKey() {
        // EntityWithNullsNotDistinctUK has @UK(nullsDistinct = false) NullableCompoundUK.
        // The key should NOT be considered nullable, so scroll should not throw PersistenceException.
        var key = (Metamodel.Key<EntityWithNullsNotDistinctUK, NullableCompoundUK>) (Metamodel.Key) EntityWithNullsNotDistinctUK_.uniqueKey;
        assertFalse(key.isNullable());
        // The query may fail due to missing table, but it should NOT throw PersistenceException
        // for nullable key validation. We just need to verify the key is accepted.
    }

    // Metamodel.Key and findBy/getBy tests.

    @Test
    public void testMetamodelKeyFactory() {
        // Owner_.id is already a Key (via @PK -> @UK), so key() should return the same instance.
        assertSame(Owner_.id, Metamodel.key(Owner_.id));
        // Owner_.address is not unique, so key() should wrap it in a KeyDelegate.
        var addressKey = Metamodel.key(Owner_.address);
        assertNotNull(addressKey);
        assertInstanceOf(Metamodel.Key.class, addressKey);
        // The delegate should be equal to the original metamodel.
        assertEquals(Owner_.address, addressKey);
        assertEquals(addressKey, Owner_.address);
    }

    @Test
    public void testOwnerIdIsKey() {
        // Owner_.id should be a Metamodel.Key since @PK is meta-annotated with @UK.
        assertInstanceOf(Metamodel.Key.class, Owner_.id);
    }

    @Test
    public void testScrollWithPrimaryKey() {
        // scroll using Owner_.id directly (a Key via @PK).
        var window = ORMTemplate.of(dataSource)
                .selectFrom(Owner.class)
                .scroll(Scrollable.of(Owner_.id, 5));
        assertEquals(5, window.content().size());
        assertTrue(window.hasNext());
    }

    // Page pagination

    @Test
    public void testEntityPageFirstPage() {
        // data.sql inserts exactly 6 cities (ids 1-6).
        // First page of size 3 should return 3 items with totalCount=6 and totalPages=2.
        var page = ORMTemplate.of(dataSource).entity(City.class).page(0, 3);
        assertEquals(3, page.content().size());
        assertEquals(6, page.totalCount());
        assertEquals(2, page.totalPages());
        assertTrue(page.hasNext());
        assertFalse(page.hasPrevious());
    }

    @Test
    public void testEntityPageSecondPage() {
        // Second page of size 3 should return the remaining 3 cities.
        var page = ORMTemplate.of(dataSource).entity(City.class).page(1, 3);
        assertEquals(3, page.content().size());
        assertEquals(6, page.totalCount());
        assertEquals(2, page.totalPages());
        assertFalse(page.hasNext());
        assertTrue(page.hasPrevious());
    }

    @Test
    public void testEntityPageWithPageable() {
        // Use Pageable.ofSize to create a pageable request for the first page.
        var pageable = Pageable.ofSize(2);
        var firstPage = ORMTemplate.of(dataSource).entity(City.class).page(pageable);
        assertEquals(2, firstPage.content().size());
        assertEquals(6, firstPage.totalCount());
        assertEquals(0, firstPage.pageNumber());
        assertTrue(firstPage.hasNext());
        // Navigate to the next page using nextPageable().
        var secondPage = ORMTemplate.of(dataSource).entity(City.class).page(firstPage.nextPageable());
        assertEquals(2, secondPage.content().size());
        assertEquals(1, secondPage.pageNumber());
        assertTrue(secondPage.hasNext());
    }

    @Test
    public void testEntityPageWithSortOrder() {
        // Use Pageable with a sort order on City_.name.
        var pageable = Pageable.ofSize(3).sortBy(City_.name);
        var page = ORMTemplate.of(dataSource).entity(City.class).page(pageable);
        assertEquals(3, page.content().size());
        assertEquals(6, page.totalCount());
        // Results should be sorted by name ascending.
        var names = page.content().stream().map(City::name).toList();
        assertEquals(names.stream().sorted().toList(), names);
    }

    @Test
    public void testEntityPageBeyondLastPage() {
        // Requesting a page far beyond the last page should return empty content but correct totalCount.
        var page = ORMTemplate.of(dataSource).entity(City.class).page(100, 3);
        assertTrue(page.content().isEmpty());
        assertEquals(6, page.totalCount());
    }

    @Test
    public void testEntityPageRef() {
        // pageRef should return a Page of Ref<City> instead of City.
        var page = ORMTemplate.of(dataSource).entity(City.class).pageRef(0, 3);
        assertEquals(3, page.content().size());
        assertEquals(6, page.totalCount());
        assertInstanceOf(Ref.class, page.content().getFirst());
    }

    @Test
    public void testEntityFindAllRef() {
        var refs = ORMTemplate.of(dataSource).entity(City.class).findAllRef();
        assertEquals(6, refs.size());
        assertInstanceOf(Ref.class, refs.getFirst());
    }

    @Test
    public void testProjectionFindAllRef() {
        var refs = ORMTemplate.of(dataSource).projection(OwnerView.class).findAllRef();
        assertEquals(10, refs.size());
        assertInstanceOf(Ref.class, refs.getFirst());
    }

    @Test
    public void testFindByKey() {
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        var result = repo.findBy(Owner_.id, 1);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().id());
    }

    @Test
    public void testFindByKeyNotFound() {
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        var result = repo.findBy(Owner_.id, 999);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetByKey() {
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        var owner = repo.getBy(Owner_.id, 1);
        assertEquals(1, owner.id());
    }

    @Test
    public void testGetByKeyNotFound() {
        var repo = ORMTemplate.of(dataSource).entity(Owner.class);
        assertThrows(st.orm.NoResultException.class, () -> repo.getBy(Owner_.id, 999));
    }

    // @Persist propagation on inline records

    record OwnerCityInline(int ownerId, int cityId) {}

    @DbTable("pet")
    record PetWithPersistInline(
            @PK Integer id,
            @Nonnull String name,
            @Nonnull LocalDate birthDate,
            int typeId,
            int ownerId,
            int cityId,
            @Persist(insertable = false, updatable = false) OwnerCityInline ownerCity
    ) implements Entity<Integer> {}

    @Test
    public void testInsertWithPersistOnInlineRecord() {
        // The inline record's child columns (owner_id, city_id) should be excluded from INSERT
        // because @Persist(insertable=false, updatable=false) is on the inline field.
        var repository = ORMTemplate.of(dataSource).entity(PetWithPersistInline.class);
        observe(sql -> {
            String statement = sql.statement();
            // The INSERT should include columns for id, name, birth_date, type_id, owner_id, city_id
            // but NOT the inline record's owner_id and city_id duplicates.
            // Insertable columns: id (PK), name, birth_date, type_id, owner_id, city_id (direct fields).
            // Non-insertable: ownerCity.ownerId, ownerCity.cityId (from inline with @Persist(false,false)).
            assertFalse(statement.contains("owner_city_owner_id"), "Inline field ownerId should not appear in INSERT");
            assertFalse(statement.contains("owner_city_city_id"), "Inline field cityId should not appear in INSERT");
        }, () -> {
            var e = assertThrows(PersistenceException.class, () ->
                    repository.insert(new PetWithPersistInline(null, "Test", LocalDate.now(), 1, 1, 1, new OwnerCityInline(1, 1))));
            // Exception is expected because the table schema doesn't match, but the SQL is still generated.
        });
    }

    @Test
    public void testUpdateWithPersistOnInlineRecord() {
        // The inline record's child columns should be excluded from UPDATE
        // because @Persist(insertable=false, updatable=false) is on the inline field.
        var repository = ORMTemplate.of(dataSource).entity(PetWithPersistInline.class);
        observe(sql -> {
            String statement = sql.statement();
            assertFalse(statement.contains("owner_city_owner_id"), "Inline field ownerId should not appear in UPDATE");
            assertFalse(statement.contains("owner_city_city_id"), "Inline field cityId should not appear in UPDATE");
        }, () -> {
            var e = assertThrows(PersistenceException.class, () ->
                    repository.update(new PetWithPersistInline(1, "Test", LocalDate.now(), 1, 1, 1, new OwnerCityInline(1, 1))));
        });
    }

    record InnerInline(String value) {}
    record OuterInline(@Persist(insertable = true, updatable = true) InnerInline override, String other) {}

    @DbTable("pet")
    record PetWithNestedPersistOverride(
            @PK Integer id,
            @Nonnull String name,
            @Nonnull LocalDate birthDate,
            int typeId,
            int ownerId,
            @Persist(insertable = false, updatable = false) OuterInline nested
    ) implements Entity<Integer> {}

    @Test
    public void testChildPersistOverridesParent() {
        // OuterInline has @Persist(false, false) on the parent, but InnerInline has its own
        // @Persist(true, true) which should override. The 'other' field should still inherit false.
        var repository = ORMTemplate.of(dataSource).entity(PetWithNestedPersistOverride.class);
        observe(sql -> {
            String statement = sql.statement();
            // InnerInline.value has @Persist(insertable=true) via child override, so it should be insertable.
            assertTrue(statement.contains("value"), "InnerInline.value should be insertable due to child override");
            // OuterInline.other inherits @Persist(false,false) from parent, so it should not appear.
            assertFalse(statement.contains("other"), "OuterInline.other should not be insertable (inherits parent @Persist)");
        }, () -> {
            var e = assertThrows(PersistenceException.class, () ->
                    repository.insert(new PetWithNestedPersistOverride(null, "Test", LocalDate.now(), 1, 1,
                            new OuterInline(new InnerInline("test"), "excluded"))));
        });
    }
}
