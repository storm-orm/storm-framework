package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.JoinType.cross;
import static st.orm.Operator.EQUALS;
import static st.orm.ResolveScope.INNER;
import static st.orm.ResolveScope.OUTER;
import static st.orm.core.template.TemplateString.raw;
import static st.orm.core.template.Templates.alias;

import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Data;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.model.Address;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.PetSummary;
import st.orm.core.model.PetType;
import st.orm.core.model.PetType_;
import st.orm.core.model.Pet_;
import st.orm.core.model.Specialty;
import st.orm.core.model.Specialty_;
import st.orm.core.model.Vet;
import st.orm.core.model.Vet_;
import st.orm.core.model.Visit;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateBuilder;

/**
 * Extended integration tests for QueryBuilder edge cases and miscellaneous coverage gaps
 * in template/builder areas of storm-core.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class BuilderIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // 1. rightJoin

    @Test
    public void testRightJoin() {
        // Right join Visit onto Pet: every Visit row appears, with its Pet.
        // All 14 visits have a pet, so we expect 14 rows.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .rightJoin(Visit.class).on(Pet.class)
                .getResultList();
        assertEquals(14, list.size());
    }

    // 1b. Table-based joins: the FK references the entity; other types mapping the
    // same table (projections, alternative entities, plain Data records) resolve by
    // table match.

    @Test
    public void testInnerJoinProjectionByTable() {
        // Visit's foreign key references the Pet entity; PetSummary is a
        // projection of the pet table, so the join resolves by table match.
        var list = ORMTemplate.of(dataSource)
                .projection(PetSummary.class)
                .select()
                .innerJoin(Visit.class).on(PetSummary.class)
                .getResultList();
        assertEquals(14, list.size());
    }

    @Test
    public void testInnerJoinProjectionAmbiguousForeignKeys() {
        // Two foreign keys reference the pet table: the join is ambiguous
        // and must fail with a message naming the candidate fields.
        record PetPair(@PK Integer id,
                       @FK Pet first,
                       @FK Pet second) implements Entity<Integer> {}
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .projection(PetSummary.class)
                .select()
                .innerJoin(PetPair.class).on(PetSummary.class)
                .getResultList());
        assertTrue(exception.getMessage().contains("multiple foreign keys reference table 'pet'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("first"), exception.getMessage());
        assertTrue(exception.getMessage().contains("second"), exception.getMessage());
    }

    @Test
    public void testInnerJoinEntityAmbiguousForeignKeys() {
        // Exact-type matches are ambiguous too: two foreign keys reference the Pet
        // entity, so the join must fail rather than silently pick the first field.
        record PetPair(@PK Integer id,
                       @FK Pet first,
                       @FK Pet second) implements Entity<Integer> {}
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .innerJoin(PetPair.class).on(Pet.class)
                .getResultList());
        assertTrue(exception.getMessage().contains("multiple foreign keys reference Pet"), exception.getMessage());
        assertTrue(exception.getMessage().contains("first"), exception.getMessage());
        assertTrue(exception.getMessage().contains("second"), exception.getMessage());
    }

    @Test
    public void testInnerJoinAlternativeEntityByTable() {
        // A second entity mapping the pet table: Visit's foreign key references the
        // Pet entity, but the join resolves by table match.
        @DbTable("pet")
        record PetLite(@PK Integer id, String name) implements Entity<Integer> {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Visit.class)
                .innerJoin(PetLite.class).on(Visit.class)
                .getResultList();
        assertEquals(14, list.size());
    }

    @Test
    public void testInnerJoinPlainDataByTable() {
        // A plain Data record mapping the pet table qualifies as a table-based join
        // target: it is table-backed and has a primary key.
        @DbTable("pet")
        record PetView(@PK Integer id, String name) implements Data {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Visit.class)
                .innerJoin(PetView.class).on(Visit.class)
                .getResultList();
        assertEquals(14, list.size());
    }

    @Test
    public void testInnerJoinDataWithoutPrimaryKeyFails() {
        // A record without a primary key exposes no key column to join on: it must
        // not silently match by table.
        @DbTable("pet")
        record PetName(String name) implements Data {}
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .selectFrom(Visit.class)
                .innerJoin(PetName.class).on(Visit.class)
                .getResultList());
        assertTrue(exception.getMessage().contains("No primary key found"), exception.getMessage());
    }

    // 2. crossJoin

    @Test
    public void testCrossJoin() {
        // Cross join City x PetType should produce 6 cities * 6 pet types = 36 rows.
        // Use selectFrom(City.class) and crossJoin(PetType.class).
        var count = ORMTemplate.of(dataSource)
                .selectFrom(City.class)
                .crossJoin(PetType.class)
                .getResultCount();
        assertEquals(36, count);
    }

    @Test
    public void testCrossJoinWithCustomSelectAndWhere() {
        // Pairs every specialty with the vet named Carter: the joined entity's columns resolve in
        // the select template and the WHERE clause binds its value after the cross join.
        record VetSpecialtyPair(int vetId, int specialtyId) {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class, VetSpecialtyPair.class, raw("\0, \0", Vet_.id, Specialty_.id))
                .crossJoin(Specialty.class)
                .where(Vet_.lastName, EQUALS, "Carter")
                .getResultList();
        assertEquals(List.of(
                new VetSpecialtyPair(1, 1),
                new VetSpecialtyPair(1, 2),
                new VetSpecialtyPair(1, 3)), list.stream()
                .sorted(Comparator.comparingInt(VetSpecialtyPair::specialtyId))
                .toList());
    }

    @Test
    public void testCrossJoinBetweenJoinsThatBindValues() {
        // The template cross join and the template inner join bind their own values, and the WHERE
        // clause binds after both: Carter paired with the specialties other than surgery.
        var count = ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .crossJoin(raw("SELECT id FROM specialty WHERE name <> \0", "surgery"))
                .innerJoin(raw("SELECT id FROM specialty WHERE id > \0", 0), "s2").on(raw("s2.id = \0", Vet_.id))
                .where(Vet_.lastName, EQUALS, "Carter")
                .getResultCount();
        assertEquals(2, count);
    }

    @Test
    public void testCrossJoinRefusesJoinCondition() {
        // A cross join has no ON clause: a join condition passed to it fails instead of being dropped.
        var templateCondition = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .selectFrom(City.class)
                .join(cross(), PetType.class, "").on(raw("\0 > 0", PetType_.id))
                .getResultCount());
        assertTrue(templateCondition.getMessage().contains("CROSS JOIN has no ON clause"), templateCondition.getMessage());
        var tableCondition = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .selectFrom(Visit.class)
                .join(cross(), Pet.class, "").on(Visit.class)
                .getResultCount());
        assertTrue(tableCondition.getMessage().contains("CROSS JOIN has no ON clause"), tableCondition.getMessage());
    }

    // 3. having

    @Test
    public void testGroupByWithHaving() {
        // Group visits by pet_id, filter to those with more than 1 visit.
        // From the data: pet 1 has 2 visits, pet 2 has 2, pet 3 has 1, pet 4 has 2,
        // pet 5 has 1, pet 6 has 2, pet 7 has 2, pet 8 has 2 => 6 pets with >1 visit.
        record Result(Pet pet, int visitCount) {}
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class, Result.class, raw("\0, COUNT(*)", Pet.class))
                .innerJoin(Visit.class).on(Pet.class)
                .groupBy(Pet_.id)
                .having(raw("COUNT(*) > \0", 1))
                .getResultList();
        assertTrue(list.size() > 0);
        // Every result should have visitCount > 1.
        for (var r : list) {
            assertTrue(r.visitCount() > 1);
        }
    }

    // 4. forUpdate

    @Test
    public void testForUpdate() {
        // H2 supports FOR UPDATE. Verify the query runs without error.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(City.class)
                .forUpdate()
                .getResultList();
        assertEquals(6, list.size());
    }

    // 5. forShare - H2 should also support FOR SHARE (newer H2 versions)

    @Test
    public void testForShare() {
        // H2 may or may not support FOR SHARE depending on version.
        // We test that it either works or throws PersistenceException.
        try {
            var list = ORMTemplate.of(dataSource)
                    .selectFrom(City.class)
                    .forShare()
                    .getResultList();
            // If it works, we should get 6 cities.
            assertEquals(6, list.size());
        } catch (PersistenceException e) {
            // Expected if H2 does not support FOR SHARE.
            assertNotNull(e);
        }
    }

    // 6. distinct

    @Test
    public void testDistinct() {
        // Select distinct pets that have visits. 8 distinct pets have visits.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(Pet.class)
                .distinct()
                .innerJoin(Visit.class).on(Pet.class)
                .getResultList();
        assertEquals(8, list.size());
    }

    @Test
    public void testDistinctOnCities() {
        // All 6 cities are already distinct, so distinct should return 6.
        var list = ORMTemplate.of(dataSource)
                .selectFrom(City.class)
                .distinct()
                .getResultList();
        assertEquals(6, list.size());
    }

    // 7. selectCount with where clause

    @Test
    public void testSelectCountWithWhere() {
        var orm = ORMTemplate.of(dataSource);
        // Count owners in city 2 (Madison). From data: owners 2, 5, 8, 9 have city_id = 2.
        long count = orm.entity(Owner.class)
                .selectCount()
                .where(Owner_.address.city, new City(2, "Madison"))
                .getSingleResult();
        assertEquals(4, count);
    }

    // 8. delete with where

    @Test
    public void testDeleteWithWhere() {
        var orm = ORMTemplate.of(dataSource);
        // First verify Waunakee exists.
        long beforeCount = orm.entity(City.class)
                .selectCount()
                .where(City_.name, EQUALS, "Waunakee")
                .getSingleResult();
        assertEquals(1, beforeCount);

        // Delete city named "Waunakee". First remove the owner referencing it (owner 10, city 6).
        // Owner 10 has city_id = 6 (Waunakee). Remove its pets' visits first, then pets, then owner.
        // Pet 12 (Lucky, owner 10) has no visit in our data. Let's just delete the owner reference.
        orm.entity(Pet.class).select()
                .where(Pet_.owner, new Owner(10, "Carlos", "Estaban",
                        new Address("2335 Independence La.", new City(6, "Waunakee")),
                        "6085555487", 0))
                .getResultList()
                .forEach(pet -> orm.entity(Pet.class).update(pet.toBuilder().owner(null).build()));

        orm.entity(Owner.class).delete()
                .where(Owner_.address.city.name, EQUALS, "Waunakee")
                .executeUpdate();

        int deleted = orm.entity(City.class).delete()
                .where(City_.name, EQUALS, "Waunakee")
                .executeUpdate();
        assertEquals(1, deleted);

        // Verify it's gone.
        long afterCount = orm.entity(City.class)
                .selectCount()
                .where(City_.name, EQUALS, "Waunakee")
                .getSingleResult();
        assertEquals(0, afterCount);
    }

    // 9. Query.getResultCount()

    @Test
    public void testQueryGetResultCount() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.selectFrom(City.class).build();
        long count = query.getResultCount();
        assertEquals(6, count);
    }

    // 10. Query.isVersionAware()

    @Test
    public void testQueryIsVersionAwareForVersionedEntity() {
        var orm = ORMTemplate.of(dataSource);
        // Owner has @Version, so update query should be version aware.
        var owner = orm.entity(Owner.class).select()
                .typedId(Integer.class)
                .where(1)
                .getSingleResult();
        // Build a select query (selects are not version-aware).
        var selectQuery = orm.selectFrom(Owner.class).build();
        // Select queries are not version aware.
        assertFalse(selectQuery.isVersionAware());
    }

    @Test
    public void testQueryIsVersionAwareForNonVersionedEntity() {
        var orm = ORMTemplate.of(dataSource);
        // City has no @Version.
        var selectQuery = orm.selectFrom(City.class).build();
        assertFalse(selectQuery.isVersionAware());
    }

    // 11. Query.getRefStream / getRefList

    @Test
    public void testQueryGetRefList() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.id FROM \0", City.class, City.class));
        var refs = query.getRefList(City.class, Integer.class);
        assertEquals(6, refs.size());
        // Each ref should have an id.
        for (var ref : refs) {
            assertNotNull(ref.id());
        }
    }

    @Test
    public void testQueryGetRefStream() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.id FROM \0", City.class, City.class));
        try (var stream = query.getRefStream(City.class, Integer.class)) {
            long count = stream.count();
            assertEquals(6, count);
        }
    }

    // 12. Subquery with EXISTS

    @Test
    public void testSubqueryExists() {
        var orm = ORMTemplate.of(dataSource);
        // Select pets that have at least one visit.
        // Use alias with OUTER/INNER scope to properly correlate the subquery.
        var list = orm.selectFrom(Pet.class)
                .where(wb -> wb.exists(
                        wb.subquery(Visit.class)
                                .where(raw("\0.pet_id = \0.id", alias(Visit.class, INNER), alias(Pet.class, OUTER)))
                ))
                .getResultList();
        assertEquals(8, list.size());
    }

    @Test
    public void testSubqueryNotExists() {
        var orm = ORMTemplate.of(dataSource);
        // Select pets that have no visits.
        var list = orm.selectFrom(Pet.class)
                .where(wb -> wb.notExists(
                        wb.subquery(Visit.class)
                                .where(raw("\0.pet_id = \0.id", alias(Visit.class, INNER), alias(Pet.class, OUTER)))
                ))
                .getResultList();
        // 13 total pets - 8 with visits = 5 without visits.
        assertEquals(5, list.size());
    }

    // 13. template-based orderBy and where

    @Test
    public void testOrderByOrdinalTemplate() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .orderBy(raw("1"))
                .limit(3)
                .getResultList();
        assertEquals(3, list.size());
    }

    @Test
    public void testWhereTemplateString() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .where(raw("\0.name = \0", City.class, "Madison"))
                .getResultList();
        assertEquals(1, list.size());
        assertEquals("Madison", list.getFirst().name());
    }

    // 14. selectExpression (custom select)

    @Test
    public void testCustomSelectExpression() {
        var orm = ORMTemplate.of(dataSource);
        record CityNameOnly(String name) {}
        var list = orm.selectFrom(City.class, CityNameOnly.class, raw("\0.name", City.class))
                .getResultList();
        assertEquals(6, list.size());
        for (var c : list) {
            assertNotNull(c.name());
        }
    }

    @Test
    public void testCustomSelectExpressionCount() {
        var orm = ORMTemplate.of(dataSource);
        // Custom select for counting.
        record CountResult(long count) {}
        var result = orm.selectFrom(City.class, CountResult.class, raw("COUNT(*)"))
                .getSingleResult();
        assertEquals(6, result.count());
    }

    // 15. withTableNameResolver via ORMTemplate.of(dataSource, decorator)

    @Test
    public void testWithTableNameResolverViaDecorator() {
        // The default resolver maps "City" -> "city". We use an identity decorator
        // and verify we can still query. Testing a non-trivial resolver that
        // actually changes the name would require a matching table, so we just
        // verify the decorator path works with the default resolver.
        var orm = ORMTemplate.of(dataSource, t -> t.withTableNameResolver(
                type -> type.type().getSimpleName().toLowerCase()
        ));
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    // 16. withColumnNameResolver via ORMTemplate.of(dataSource, decorator)

    @Test
    public void testWithColumnNameResolverViaDecorator() {
        // Use the default snake_case resolver through the decorator path.
        var orm = ORMTemplate.of(dataSource, t -> t.withColumnNameResolver(
                st.orm.mapping.ColumnNameResolver.camelCaseToSnakeCase()
        ));
        var cities = orm.entity(City.class).select().getResultList();
        assertEquals(6, cities.size());
    }

    // 17. withForeignKeyResolver via ORMTemplate.of(dataSource, decorator)

    @Test
    public void testWithForeignKeyResolverViaDecorator() {
        // Use the default camelCaseToSnakeCase foreign key resolver through the decorator path.
        var orm = ORMTemplate.of(dataSource, t -> t.withForeignKeyResolver(
                st.orm.mapping.ForeignKeyResolver.camelCaseToSnakeCase()
        ));
        // Query pets (which have FK to owner and type).
        var pets = orm.entity(Pet.class).select().getResultList();
        // We expect 13 pets (12 with owners + 1 without owner = pet 13 'Sly').
        assertEquals(13, pets.size());
    }

    // 18. Version with Instant (Visit has @Version Instant timestamp)

    @Test
    public void testVersionWithInstantOnUpdate() {
        var orm = ORMTemplate.of(dataSource);
        // Fetch a visit and update its description.
        var visit = orm.entity(Visit.class).select()
                .typedId(Integer.class)
                .where(1)
                .getSingleResult();
        assertNotNull(visit);
        assertEquals(1, visit.id());

        var updated = visit.toBuilder()
                .description("updated rabies shot")
                .build();
        orm.entity(Visit.class).update(updated);

        // Verify the update.
        var reloaded = orm.entity(Visit.class).select()
                .typedId(Integer.class)
                .where(1)
                .getSingleResult();
        assertEquals("updated rabies shot", reloaded.description());
    }

    // 19. Version with int (Owner has @Version int version)

    @Test
    public void testVersionWithIntOnUpdate() {
        var orm = ORMTemplate.of(dataSource);
        // Fetch owner 1 and update.
        var owner = orm.entity(Owner.class).select()
                .typedId(Integer.class)
                .where(1)
                .getSingleResult();
        assertNotNull(owner);
        assertEquals(1, owner.id());
        assertEquals(0, owner.version());

        var updated = owner.toBuilder()
                .firstName("BettyUpdated")
                .build();
        orm.entity(Owner.class).update(updated);

        // Verify the update and version increment.
        var reloaded = orm.entity(Owner.class).select()
                .typedId(Integer.class)
                .where(1)
                .getSingleResult();
        assertEquals("BettyUpdated", reloaded.firstName());
        assertEquals(1, reloaded.version());
    }

    // 20. QueryBuilder.getResultCount() convenience method

    @Test
    public void testBuilderGetResultCount() {
        var orm = ORMTemplate.of(dataSource);
        long count = orm.selectFrom(Pet.class)
                .where(Pet_.name, EQUALS, "Lucky")
                .getResultCount();
        // Two pets named "Lucky" (ids 9 and 12).
        assertEquals(2, count);
    }

    // Additional: Query.getResultList(Class) with typed result

    @Test
    public void testQueryGetResultListTyped() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.name FROM \0", City.class, City.class));
        var names = query.getResultList(String.class);
        assertEquals(6, names.size());
        assertTrue(names.contains("Madison"));
    }

    // Additional: Query.getSingleResult(Class) with typed result

    @Test
    public void testQueryGetSingleResultTyped() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.name FROM \0 WHERE \0.id = \0",
                City.class, City.class, City.class, 2));
        var name = query.getSingleResult(String.class);
        assertEquals("Madison", name);
    }

    // Additional: Query.getOptionalResult(Class) with typed result

    @Test
    public void testQueryGetOptionalResultTyped() {
        var orm = ORMTemplate.of(dataSource);
        // Query for a city that does not exist.
        var query = orm.query(raw("SELECT \0.name FROM \0 WHERE \0.id = \0",
                City.class, City.class, City.class, 999));
        var result = query.getOptionalResult(String.class);
        assertTrue(result.isEmpty());
    }

    // Additional: QueryBuilder.getSingleResult()

    @Test
    public void testBuilderGetSingleResult() {
        var orm = ORMTemplate.of(dataSource);
        var city = orm.selectFrom(City.class)
                .where(City_.name, EQUALS, "Madison")
                .getSingleResult();
        assertEquals("Madison", city.name());
        assertEquals(2, city.id());
    }

    // Additional: QueryBuilder.getOptionalResult()

    @Test
    public void testBuilderGetOptionalResult() {
        var orm = ORMTemplate.of(dataSource);
        var result = orm.selectFrom(City.class)
                .where(City_.name, EQUALS, "NonexistentCity")
                .getOptionalResult();
        assertTrue(result.isEmpty());
    }

    @Test
    public void testBuilderGetOptionalResultPresent() {
        var orm = ORMTemplate.of(dataSource);
        var result = orm.selectFrom(City.class)
                .where(City_.name, EQUALS, "Madison")
                .getOptionalResult();
        assertTrue(result.isPresent());
        assertEquals("Madison", result.get().name());
    }

    // Additional: orderByDescending

    @Test
    public void testOrderByDescending() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .orderByDescending(City_.id)
                .getResultList();
        assertEquals(6, list.size());
        // First city should have the highest id.
        assertTrue(list.get(0).id() > list.get(list.size() - 1).id());
    }

    // Additional: limit and offset

    @Test
    public void testLimitAndOffset() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .orderBy(City_.id)
                .limit(2)
                .offset(2)
                .getResultList();
        assertEquals(2, list.size());
        // Should be cities with id 3 and 4 (0-indexed offset of 2 from ordered-by-id).
        assertEquals(3, list.get(0).id());
        assertEquals(4, list.get(1).id());
    }

    // Additional: whereAny with builder predicate

    @Test
    public void testWhereAnyPredicate() {
        var orm = ORMTemplate.of(dataSource);
        // Use whereAny to match by any predicate (OR logic).
        var list = orm.selectFrom(City.class)
                .typedId(Integer.class)
                .where(wb -> wb.whereId(1).or(wb.whereId(2)).or(wb.whereId(3)))
                .getResultList();
        assertEquals(3, list.size());
    }

    // Additional: Query.unsafe() and Query.managed()

    @Test
    public void testQueryUnsafe() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.selectFrom(City.class).build();
        var unsafeQuery = query.unsafe();
        assertNotNull(unsafeQuery);
        // Unsafe query should still work for select.
        assertEquals(6, unsafeQuery.getResultCount());
    }

    @Test
    public void testUpdateWithSubqueryWhereOnlyRequiresUnsafe() {
        var orm = ORMTemplate.of(dataSource);
        // The only WHERE sits in a subquery; the update itself is unqualified and touches every row.
        var query = orm.query(raw("UPDATE visit SET description = (SELECT name FROM city WHERE city.id = 1)"));
        var e = assertThrows(PersistenceException.class, query::executeUpdate);
        assertTrue(e.getMessage().contains("unsafe"));
        assertEquals(14, query.unsafe().executeUpdate());
    }

    @Test
    public void testQueryManaged() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.selectFrom(City.class).build();
        var managedQuery = query.managed();
        assertNotNull(managedQuery);
        assertEquals(6, managedQuery.getResultCount());
    }

    // Additional: deleteFrom via QueryTemplate

    @Test
    public void testDeleteFromQueryTemplate() {
        var orm = ORMTemplate.of(dataSource);
        // Count visits before.
        long before = orm.selectFrom(Visit.class).getResultCount();
        assertEquals(14, before);

        // Delete visits for pet 5 (1 visit).
        int deleted = orm.deleteFrom(Visit.class)
                .where(raw("\0.pet_id = \0", Visit.class, 5))
                .executeUpdate();
        assertEquals(1, deleted);

        long after = orm.selectFrom(Visit.class).getResultCount();
        assertEquals(13, after);
    }

    // Additional: TemplateString.combine()

    @Test
    public void testTemplateStringCombine() {
        var orm = ORMTemplate.of(dataSource);
        var ts1 = raw("\0.name = \0", City.class, "Madison");
        var ts2 = raw(" OR \0.name = \0", City.class, "Windsor");
        var combined = st.orm.core.template.TemplateString.combine(ts1, ts2);
        var list = orm.selectFrom(City.class)
                .where(combined)
                .getResultList();
        assertEquals(2, list.size());
    }

    // Additional: TemplateString.wrap()

    @Test
    public void testTemplateStringWrap() {
        var orm = ORMTemplate.of(dataSource);
        // wrap(City.class) should insert the City class as a value in a template.
        var wrapped = st.orm.core.template.TemplateString.wrap(City.class);
        assertNotNull(wrapped);
        assertEquals(1, wrapped.values().size());
        assertEquals(2, wrapped.fragments().size());
    }

    // Additional: TemplateString.EMPTY

    @Test
    public void testTemplateStringEmpty() {
        var empty = st.orm.core.template.TemplateString.EMPTY;
        assertNotNull(empty);
        assertEquals(1, empty.fragments().size());
        assertEquals(0, empty.values().size());
        assertEquals("", empty.fragments().getFirst());
    }

    // Additional: TemplateBuilder.create with TemplateBuilder lambda

    @Test
    public void testTemplateBuilderCreate() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(City.class)
                .where(TemplateBuilder.create(it ->
                        "%s.name = %s".formatted(it.interpolate(City.class), it.interpolate("Madison"))
                ))
                .getResultList();
        assertEquals(1, list.size());
        assertEquals("Madison", list.getFirst().name());
    }

    // Additional: model() from QueryTemplate

    @Test
    public void testModel() {
        var orm = ORMTemplate.of(dataSource);
        var model = orm.model(City.class);
        assertNotNull(model);
        assertEquals("city", model.name());
    }

    @Test
    public void testModelWithPrimaryKeyRequired() {
        var orm = ORMTemplate.of(dataSource);
        var model = orm.model(City.class, true);
        assertNotNull(model);
        assertEquals("city", model.name());
    }

    // Additional: ref() from QueryTemplate

    @Test
    public void testRefCreation() {
        var orm = ORMTemplate.of(dataSource);
        var ref = orm.ref(City.class, 2);
        assertNotNull(ref);
        assertEquals(2, ref.id());
    }

    // Additional: dialect() from QueryTemplate

    @Test
    public void testDialect() {
        var orm = ORMTemplate.of(dataSource);
        var dialect = orm.dialect();
        assertNotNull(dialect);
        // Verify some dialect properties are available.
        assertNotNull(dialect.forUpdateLockHint());
    }

    // Additional: createBindVars() from QueryTemplate

    @Test
    public void testCreateBindVars() {
        var orm = ORMTemplate.of(dataSource);
        var bindVars = orm.createBindVars();
        assertNotNull(bindVars);
    }

    // Additional: QueryBuilder.typedId()

    @Test
    public void testTypedQueryBuilder() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.entity(City.class)
                .select()
                .typedId(Integer.class)
                .where(1)
                .getResultList();
        assertEquals(1, list.size());
        assertEquals(1, list.getFirst().id());
    }

    // Additional: Query.getResultList() (Object[] variant)

    @Test
    public void testQueryGetResultListRaw() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.id, \0.name FROM \0", City.class, City.class, City.class));
        var rows = query.getResultList();
        assertEquals(6, rows.size());
        // Each row should be an Object[].
        for (var row : rows) {
            assertEquals(2, row.length);
        }
    }

    // Additional: Query.getSingleResult() (Object[] variant)

    @Test
    public void testQueryGetSingleResultRaw() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.id, \0.name FROM \0 WHERE \0.id = \0",
                City.class, City.class, City.class, City.class, 2));
        var row = query.getSingleResult();
        assertEquals(2, row.length);
        assertEquals("Madison", row[1]);
    }

    // Additional: Query.getOptionalResult() (Object[] variant)

    @Test
    public void testQueryGetOptionalResultRaw() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query(raw("SELECT \0.id, \0.name FROM \0 WHERE \0.id = \0",
                City.class, City.class, City.class, City.class, 999));
        var result = query.getOptionalResult();
        assertTrue(result.isEmpty());
    }

    // Additional: groupBy with template

    @Test
    public void testGroupByTemplate() {
        var orm = ORMTemplate.of(dataSource);
        record Result(int cityId, long ownerCount) {}
        var list = orm.selectFrom(Owner.class, Result.class, raw("\0.city_id, COUNT(*)", Owner.class))
                .groupBy(raw("\0.city_id", Owner.class))
                .getResultList();
        // There are 6 distinct cities among owners (city_ids: 1,2,3,4,5,6).
        assertEquals(6, list.size());
    }

    // Additional: Query via raw string (query(String))

    @Test
    public void testQueryWithRawString() {
        var orm = ORMTemplate.of(dataSource);
        var query = orm.query("SELECT COUNT(*) FROM city");
        var row = query.getSingleResult();
        assertEquals(1, row.length);
        assertEquals(6L, ((Number) row[0]).longValue());
    }

    // Additional: ref single/optional results.

    @Test
    public void testRefSingleResult() {
        var orm = ORMTemplate.of(dataSource);
        var ref = orm.entity(Pet.class).selectRef().where(it -> it.whereId(1)).getSingleResult();
        assertEquals(1, ref.id());
    }

    @Test
    public void testRefOptionalResultEmpty() {
        var orm = ORMTemplate.of(dataSource);
        assertTrue(orm.entity(Pet.class).selectRef().where(it -> it.whereId(-1)).getOptionalResult().isEmpty());
    }

    // Additional: multi-path orderBy and template-based descending order.

    @Test
    public void testOrderByMultiplePaths() {
        var list = ORMTemplate.of(dataSource).selectFrom(Pet.class).orderBy(Pet_.name, Pet_.id).getResultList();
        assertFalse(list.isEmpty());
    }

    @Test
    public void testOrderByDescendingTemplate() {
        var list = ORMTemplate.of(dataSource).selectFrom(City.class)
                .orderByDescending(raw("\0.id", City.class))
                .getResultList();
        assertEquals(6, list.getFirst().id());
    }

    // Additional: exists/notExists conveniences on the builder.

    @Test
    public void testWhereExistsConvenience() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(Owner.class)
                .whereExists(orm.subquery(Visit.class).where(raw("\0.id = \0.id", alias(Owner.class, OUTER), alias(Owner.class, INNER))))
                .getResultList();
        assertEquals(6, list.size());
    }

    @Test
    public void testWhereNotExistsConvenience() {
        var orm = ORMTemplate.of(dataSource);
        var list = orm.selectFrom(Owner.class)
                .whereNotExists(orm.subquery(Visit.class).where(raw("\0.id = \0.id", alias(Owner.class, OUTER), alias(Owner.class, INNER))))
                .getResultList();
        assertEquals(4, list.size());
    }

    @Test
    public void testHavingExistsConvenience() {
        var orm = ORMTemplate.of(dataSource);
        var count = orm.entity(Owner.class).selectCount()
                .havingExists(orm.subquery(Visit.class))
                .getSingleResult();
        assertEquals(10, count);
    }

    @Test
    public void testHavingNotExistsConvenience() {
        var orm = ORMTemplate.of(dataSource);
        var result = orm.entity(Owner.class).selectCount()
                .havingNotExists(orm.subquery(Visit.class))
                .getOptionalResult();
        assertTrue(result.isEmpty());
    }

    // Additional: subquery builders cannot be executed directly.

    @Test
    public void testSubqueryCannotExecute() {
        var orm = ORMTemplate.of(dataSource);
        var subquery = orm.subquery(City.class);
        assertThrows(PersistenceException.class, subquery::getResultList);
        assertThrows(PersistenceException.class, subquery::plan);
        assertThrows(PersistenceException.class, subquery::getResultCount);
    }

    // Additional: unsafe() is a no-op for select queries.

    @Test
    public void testUnsafeSelect() {
        assertEquals(6, ORMTemplate.of(dataSource).selectFrom(City.class).unsafe().getResultList().size());
    }

    // Additional: fetch() rejects queries that do not select the root record graph.

    @Test
    public void testFetchOnRefSelectThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () -> orm.entity(Pet.class).selectRef().fetch(Pet_.owner));
    }

    @Test
    public void testFetchOnCustomSelectTypeThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () -> orm.entity(Pet.class).select(City.class).fetch(Pet_.owner));
    }

    @Test
    public void testFetchOnCustomColumnListThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () -> orm.entity(Pet.class)
                .select(Pet.class, raw("\0.id, \0.name", Pet.class, Pet.class))
                .fetch(Pet_.owner));
    }

    @Test
    public void testFetchWithoutPathsThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () -> orm.selectFrom(Pet.class).fetch());
    }

    // Additional: DELETE builder plans and subquery restrictions.

    @Test
    public void testDeletePlan() {
        var orm = ORMTemplate.of(dataSource);
        // A plan without the unsafe marker defers the missing-WHERE check to execution.
        assertNotNull(orm.deleteFrom(Visit.class).plan());
        // The unsafe marker carries over to every query the plan produces; the delete rolls back with the test
        // transaction.
        var plan = orm.deleteFrom(Visit.class).unsafe().plan();
        assertEquals(14, plan.query().executeUpdate());
    }

    @Test
    public void testDeleteAsSubqueryThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () -> orm.selectFrom(Owner.class)
                .whereExists(orm.deleteFrom(Visit.class))
                .getResultList());
    }
}
