/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.model.Vet;
import st.orm.core.model.polymorphic.Checkup;
import st.orm.core.model.polymorphic.Checkup_;
import st.orm.core.model.polymorphic.JoinedCheckup;
import st.orm.core.model.polymorphic.JoinedSurgery;
import st.orm.core.model.polymorphic.JoinedSurgery_;
import st.orm.core.model.polymorphic.JoinedTreatment;
import st.orm.core.model.polymorphic.JoinedTreatment_;
import st.orm.core.model.polymorphic.Surgery;
import st.orm.core.model.polymorphic.Surgery_;
import st.orm.core.model.polymorphic.Treatment;
import st.orm.core.model.polymorphic.Treatment_;
import st.orm.core.spi.TypeDiscovery;
import st.orm.core.template.Column;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Templates;

/**
 * Foreign keys declared by the subtypes of a sealed entity, and a subtype of a single-table hierarchy as an entity of
 * its own.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SealedForeignKeyIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private final List<String> statements = new ArrayList<>();

    private ORMTemplate orm() {
        return ORMTemplate.of(dataSource);
    }

    private <T> T capture(Supplier<T> query) {
        statements.clear();
        return observe(sql -> statements.add(sql.statement()), query);
    }

    private String statement() {
        return statements.getFirst().replaceAll("\\s+", " ");
    }

    // Single-Table: the sealed type.

    @Test
    public void sealedModelMapsForeignKeysAsForeignKeyColumns() {
        var model = orm().model(Treatment.class);
        var names = model.declaredColumns().stream().map(Column::name).toList();
        assertEquals(List.of("kind", "id", "pet_id", "treatment_date", "vet_id", "notes", "surgeon_id", "duration"), names);
        var byName = model.declaredColumns().stream().collect(java.util.stream.Collectors.toMap(Column::name, c -> c));
        assertTrue(byName.get("pet_id").foreignKey());
        assertFalse(byName.get("pet_id").nullable(), "Every subtype declares the pet.");
        assertTrue(byName.get("vet_id").foreignKey());
        assertTrue(byName.get("vet_id").ref());
        assertTrue(byName.get("surgeon_id").foreignKey());
        assertTrue(byName.get("surgeon_id").nullable(), "A checkup row holds no surgeon.");
        assertTrue(byName.get("duration").nullable(), "A checkup row holds no duration.");
        assertTrue(model.columns().stream().map(Column::name).anyMatch("last_name"::equals), "The surgeon's columns are selected.");
    }

    @Test
    public void selectSealedTypeJoinsTheForeignKeys() {
        var treatments = capture(() -> orm().entity(Treatment.class).select().getResultList());
        String sql = statement();
        assertTrue(sql.contains("INNER JOIN pet"), sql);
        assertTrue(sql.contains("LEFT JOIN vet"), sql);
        assertTrue(sql.contains("LEFT JOIN owner"), sql);
        assertFalse(sql.contains(".pet,") || sql.contains(".surgeon,"), sql);
        assertEquals(4, treatments.size());
        var checkup = assertInstanceOf(Checkup.class, treatments.get(0));
        assertEquals("Leo", checkup.pet().name());
        assertNotNull(checkup.pet().owner());
        assertEquals(1, checkup.vet().id());
        assertFalse(checkup.vet().isLoaded());
        assertEquals("Healthy", checkup.notes());
        var unassigned = assertInstanceOf(Checkup.class, treatments.get(1));
        assertNull(unassigned.vet());
        var surgery = assertInstanceOf(Surgery.class, treatments.get(2));
        assertEquals("Rosy", surgery.pet().name());
        assertEquals("Leary", surgery.surgeon().lastName());
        assertEquals(90, surgery.duration());
    }

    @Test
    public void findByIdOnSealedTypeHydratesTheSubtypeForeignKey() {
        var surgery = orm().entity(Treatment.class).findById(3).orElseThrow();
        assertInstanceOf(Surgery.class, surgery);
        assertEquals("Leary", ((Surgery) surgery).surgeon().lastName());
    }

    @Test
    public void insertThroughSealedTypeWritesForeignKeyColumns() {
        var treatments = orm().entity(Treatment.class);
        var pet = orm().entity(Pet.class).getById(2);
        var vet = orm().entity(Vet.class).getById(4);
        var surgeryId = treatments.insertAndFetchId(new Surgery(null, pet, LocalDate.of(2024, 5, 1), vet, 30));
        var checkupId = treatments.insertAndFetchId(new Checkup(null, pet, LocalDate.of(2024, 5, 2), Ref.of(vet), "Fine"));
        var surgery = assertInstanceOf(Surgery.class, treatments.getById(surgeryId));
        assertEquals("Basil", surgery.pet().name());
        assertEquals("Ortega", surgery.surgeon().lastName());
        var checkup = assertInstanceOf(Checkup.class, treatments.getById(checkupId));
        assertEquals(4, checkup.vet().id());
        assertEquals("Fine", checkup.notes());
    }

    @Test
    public void batchInsertThroughSealedTypeBindsForeignKeys() {
        var treatments = orm().entity(Treatment.class);
        var pet = orm().entity(Pet.class).getById(1);
        var vet = orm().entity(Vet.class).getById(5);
        long before = treatments.count();
        treatments.insert(List.of(
                new Surgery(null, pet, LocalDate.of(2024, 6, 1), vet, 20),
                new Checkup(null, pet, LocalDate.of(2024, 6, 2), null, "Batch")));
        assertEquals(before + 2, treatments.count());
        var inserted = treatments.select().where(Treatment_.treatmentDate, GREATER_THAN, LocalDate.of(2024, 5, 31)).getResultList();
        assertEquals(2, inserted.size());
        assertEquals("Stevens", assertInstanceOf(Surgery.class, inserted.get(0)).surgeon().lastName());
        assertNull(assertInstanceOf(Checkup.class, inserted.get(1)).vet());
    }

    @Test
    public void updateThroughSealedTypeWritesForeignKeyColumns() {
        var treatments = orm().entity(Treatment.class);
        var surgery = (Surgery) treatments.getById(3);
        var vet = orm().entity(Vet.class).getById(6);
        treatments.update(new Surgery(surgery.id(), surgery.pet(), surgery.treatmentDate(), vet, 120));
        var updated = (Surgery) treatments.getById(3);
        assertEquals("Jenkins", updated.surgeon().lastName());
        assertEquals(120, updated.duration());
    }

    @Test
    public void insertUnsavedForeignKeyThroughSealedTypeIsRefused() {
        var treatments = orm().entity(Treatment.class);
        var pet = orm().entity(Pet.class).getById(1);
        var exception = assertThrows(PersistenceException.class,
                () -> treatments.insert(new Surgery(null, pet, LocalDate.of(2024, 7, 1), new Vet(0, "New", "Vet"), 10)));
        assertTrue(exception.getMessage().contains("default primary key"), exception.getMessage());
    }

    @Test
    public void whereOnSealedMetamodelForeignKey() {
        var pet = orm().entity(Pet.class).getById(1);
        var treatments = capture(() -> orm().entity(Treatment.class).select()
                .where(Treatment_.pet, EQUALS, pet)
                .getResultList());
        assertTrue(statement().contains("pet_id = ?"), statement());
        assertEquals(2, treatments.size());
        assertTrue(treatments.stream().allMatch(it -> it.pet().id() == 1));
    }

    @Test
    public void whereOnSealedMetamodelThroughForeignKey() {
        var treatments = orm().entity(Treatment.class).select()
                .where(Treatment_.pet.name, EQUALS, "Rosy")
                .getResultList();
        assertEquals(1, treatments.size());
        assertInstanceOf(Surgery.class, treatments.getFirst());
    }

    @Test
    public void whereOnRuntimeMetamodelThroughForeignKey() {
        Metamodel<Treatment, String> ownerLastName = Metamodel.of(Treatment.class, "pet.owner.lastName");
        var treatments = orm().entity(Treatment.class).select()
                .where(ownerLastName, EQUALS, "Franklin")
                .getResultList();
        assertEquals(1, treatments.size());
        assertEquals("Basil", treatments.getFirst().pet().name());
        Metamodel<Treatment, Pet> pet = Metamodel.of(Treatment.class, "pet");
        assertEquals(2, orm().entity(Treatment.class).select()
                .where(pet, EQUALS, orm().entity(Pet.class).getById(1))
                .getResultList().size());
    }

    @Test
    public void whereOnSubtypeMetamodelInSealedQuery() {
        var vet = orm().entity(Vet.class).getById(2);
        var surgeries = orm().entity(Treatment.class).select()
                .where(Surgery_.surgeon, EQUALS, vet)
                .getResultList();
        assertEquals(1, surgeries.size());
        assertEquals(90, assertInstanceOf(Surgery.class, surgeries.getFirst()).duration());
        // A common field through a subtype other than the first declaring it resolves as well.
        var ofLeo = orm().entity(Treatment.class).select()
                .where(Surgery_.pet.name, EQUALS, "Leo")
                .getResultList();
        assertEquals(2, ofLeo.size());
        var checkups = orm().entity(Treatment.class).select()
                .where(Checkup_.notes, EQUALS, "Shots")
                .getResultList();
        assertEquals(1, checkups.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void whereOnRuntimeSubtypeMetamodelInSealedQuery() {
        Metamodel<Surgery, String> petName = Metamodel.of(Surgery.class, "pet.name");
        assertEquals(2, orm().entity(Treatment.class).select().where(petName, EQUALS, "Leo").getResultList().size());
        Metamodel<Surgery, Vet> surgeon = Metamodel.of(Surgery.class, "surgeon");
        assertEquals(1, orm().entity(Treatment.class).select()
                .where(surgeon, EQUALS, orm().entity(Vet.class).getById(2))
                .getResultList().size());
        // A path rooted at a joined table resolves in that table.
        Metamodel<Treatment, String> joined = (Metamodel<Treatment, String>) (Metamodel<?, ?>) Metamodel.of(Pet.class, "name");
        assertEquals(2, orm().entity(Treatment.class).select().where(joined, EQUALS, "Leo").getResultList().size());
    }

    @Test
    public void batchRemoveThroughSealedTypeRemovesBothKinds() {
        var treatments = orm().entity(Treatment.class);
        var all = treatments.select().getResultList();
        treatments.remove(all);
        assertEquals(0, treatments.count());
    }

    // Single-Table: a subtype as an entity of its own.

    @Test
    public void subtypeRepositorySelectsItsOwnRows() {
        var surgeries = capture(() -> orm().entity(Surgery.class).select().getResultList());
        String sql = statement();
        assertTrue(sql.contains("FROM treatment"), sql);
        assertTrue(sql.contains("WHERE") && sql.contains(".kind = ?"), sql);
        assertTrue(sql.contains("INNER JOIN vet"), sql);
        assertEquals(2, surgeries.size());
        assertEquals(List.of("Leary", "Douglas"), surgeries.stream().map(it -> it.surgeon().lastName()).toList());
        assertEquals(2, orm().entity(Checkup.class).count());
        assertEquals(2, orm().entity(Surgery.class).count());
    }

    @Test
    public void subtypeRepositoryFindsItsOwnRowsById() {
        var surgeries = orm().entity(Surgery.class);
        assertTrue(surgeries.findById(1).isEmpty(), "Row 1 is a checkup.");
        var surgery = surgeries.getById(3);
        assertEquals("Rosy", surgery.pet().name());
        assertEquals("Leary", surgery.surgeon().lastName());
        assertFalse(surgeries.existsById(1));
        assertTrue(surgeries.existsById(3));
        var checkup = orm().entity(Checkup.class).getById(1);
        assertEquals(1, checkup.vet().id());
    }

    @Test
    public void subtypeRepositoryWhereOnSubtypeMetamodel() {
        var vet = orm().entity(Vet.class).getById(3);
        var surgeries = capture(() -> orm().entity(Surgery.class).select()
                .where(Surgery_.surgeon, EQUALS, vet)
                .getResultList());
        assertTrue(statement().contains(".kind = ? AND ("), statement());
        assertEquals(1, surgeries.size());
        assertEquals(45, surgeries.getFirst().duration());
        var longOnes = orm().entity(Surgery.class).select()
                .where(Surgery_.duration, GREATER_THAN, 60)
                .getResultList();
        assertEquals(1, longOnes.size());
        var ofLeo = orm().entity(Surgery.class).select()
                .where(Surgery_.pet.name, EQUALS, "Leo")
                .getResultList();
        assertEquals(1, ofLeo.size());
        Metamodel<Surgery, String> surgeonName = Metamodel.of(Surgery.class, "surgeon.lastName");
        assertEquals(1, orm().entity(Surgery.class).select().where(surgeonName, EQUALS, "Leary").getResultList().size());
    }

    @Test
    public void subtypeRepositoryWithFilterJoinCarriesTheDiscriminator() {
        var surgeries = capture(() -> orm().entity(Surgery.class).select()
                .semiJoin(Pet.class).on(Surgery.class, it -> it.where(Pet_.name, EQUALS, "Basil"))
                .getResultList());
        assertTrue(statement().contains(".kind = ?"), statement());
        assertEquals(0, surgeries.size());
        var ofLeo = orm().entity(Surgery.class).select()
                .semiJoin(Pet.class).on(Surgery.class, it -> it.where(Pet_.name, EQUALS, "Leo"))
                .getResultList();
        assertEquals(1, ofLeo.size());
        var checkups = orm().entity(Checkup.class).select()
                .semiJoin(Pet.class).on(Checkup.class, it -> it.where(Pet_.name, EQUALS, "Leo"))
                .where(Checkup_.notes, EQUALS, "Healthy")
                .getResultList();
        assertEquals(1, checkups.size());
    }

    @Test
    public void subtypeRepositoryInsertWritesTheDiscriminator() {
        var surgeries = orm().entity(Surgery.class);
        var pet = orm().entity(Pet.class).getById(2);
        var vet = orm().entity(Vet.class).getById(1);
        statements.clear();
        var id = observe(sql -> statements.add(sql.statement()),
                () -> surgeries.insertAndFetchId(new Surgery(null, pet, LocalDate.of(2024, 8, 1), vet, 15)));
        assertTrue(statement().startsWith("INSERT INTO treatment (kind, "), statement());
        assertEquals(3, surgeries.count());
        var inserted = assertInstanceOf(Surgery.class, orm().entity(Treatment.class).getById(id));
        assertEquals("Carter", inserted.surgeon().lastName());
        surgeries.insert(new Surgery(null, pet, LocalDate.of(2024, 8, 2), vet, 16));
        assertEquals(4, surgeries.count());
        assertEquals(2, orm().entity(Checkup.class).count());
    }

    @Test
    public void subtypeRepositoryBatchInsertWritesTheDiscriminator() {
        var checkups = orm().entity(Checkup.class);
        var pet = orm().entity(Pet.class).getById(3);
        checkups.insert(List.of(
                new Checkup(null, pet, LocalDate.of(2024, 9, 1), Ref.of(Vet.class, 2), "One"),
                new Checkup(null, pet, LocalDate.of(2024, 9, 2), null, "Two")));
        assertEquals(4, checkups.count());
        var ids = checkups.insertAndFetchIds(List.of(new Checkup(null, pet, LocalDate.of(2024, 9, 3), null, "Three")));
        assertEquals(1, ids.size());
        assertEquals("Three", checkups.getById(ids.getFirst()).notes());
        assertEquals(2, orm().entity(Surgery.class).count());
    }


    @Test
    public void subtypeRepositoryUpdateAndRemove() {
        var surgeries = orm().entity(Surgery.class);
        var surgery = surgeries.getById(3);
        surgeries.update(new Surgery(3, surgery.pet(), surgery.treatmentDate(), surgery.surgeon(), 100));
        assertEquals(100, surgeries.getById(3).duration());
        surgeries.remove(surgeries.getById(3));
        assertEquals(1, surgeries.count());
        surgeries.removeById(4);
        assertEquals(0, surgeries.count());
        assertEquals(2, orm().entity(Checkup.class).count(), "The checkups stay.");
    }

    @Test
    public void subtypeRepositoryRemoveAllKeepsTheOtherSubtypes() {
        var checkups = orm().entity(Checkup.class);
        checkups.removeAll();
        assertEquals(0, checkups.count());
        assertEquals(2, orm().entity(Surgery.class).count());
        assertEquals(2, orm().entity(Treatment.class).count());
    }

    @Test
    public void subtypeRepositoryDeleteAndUpdateBuilders() {
        var surgeries = orm().entity(Surgery.class);
        int updated = surgeries.update()
                .set(Surgery_.duration, 10)
                .where(Surgery_.duration, GREATER_THAN, 60)
                .executeUpdate();
        assertEquals(1, updated);
        assertEquals(10, surgeries.getById(3).duration());
        int deleted = capture(() -> surgeries.delete().where(Surgery_.duration, EQUALS, 10).executeUpdate());
        assertTrue(statement().contains(".kind = ?"), statement());
        assertEquals(1, deleted);
        assertEquals(1, surgeries.count());
        assertEquals(2, orm().entity(Checkup.class).count());
        assertEquals(1, surgeries.delete().unsafe().executeUpdate());
        assertEquals(2, orm().entity(Treatment.class).count());
    }

    @Test
    public void joinOntoSubtypeMatchesItsRowsOnly() {
        var pets = capture(() -> orm().entity(Pet.class).select()
                .innerJoin(Surgery.class).on(Pet.class)
                .getResultList());
        assertTrue(statement().contains("JOIN treatment") && statement().contains(".kind = ?"), statement());
        assertEquals(List.of("Leo", "Rosy"), pets.stream().map(Pet::name).sorted().toList());
        var checkedUp = orm().entity(Pet.class).select()
                .innerJoin(Checkup.class).on(Pet.class)
                .getResultList();
        assertEquals(List.of("Basil", "Leo"), checkedUp.stream().map(Pet::name).sorted().toList());
    }

    @Test
    public void rawStatementOnSubtypeCarriesTheDiscriminatorInItsWhereClause() {
        var surgeries = orm().query(raw("SELECT \0 FROM \0 WHERE \0", Surgery.class, Surgery.class,
                        Templates.where(Surgery_.duration, GREATER_THAN, 60)))
                .getResultList(Surgery.class);
        assertEquals(1, surgeries.size());
        assertEquals(90, surgeries.getFirst().duration());
        // A condition written out carries no where element to hold the discriminator.
        var exception = assertThrows(PersistenceException.class,
                () -> orm().query(raw("SELECT \0 FROM \0 WHERE \0 > \0", Surgery.class, Surgery.class, Surgery_.duration, 60))
                        .getResultList(Surgery.class));
        assertTrue(exception.getMessage().contains("where element"), exception.getMessage());
    }

    @Test
    public void rawStatementOnSubtypeWithoutWhereClauseIsRefused() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().query(raw("SELECT \0 FROM \0", Surgery.class, Surgery.class)).getResultList(Surgery.class));
        assertTrue(exception.getMessage().contains("WHERE"), exception.getMessage());
        var delete = assertThrows(PersistenceException.class,
                () -> orm().query(raw("DELETE FROM \0", Surgery.class)).unsafe().executeUpdate());
        assertTrue(delete.getMessage().contains("WHERE"), delete.getMessage());
        assertEquals(4, orm().entity(Treatment.class).count());
    }

    @Test
    public void subtypeRepositoryValidatesTheHierarchy() {
        var model = orm().model(Surgery.class);
        assertEquals("treatment", model.name());
        assertEquals(Surgery.class, model.type());
        assertFalse(model.declaredColumns().stream().map(Column::name).anyMatch("kind"::equals),
                "The discriminator is not a column of the subtype's record.");
    }

    @Test
    public void schemaValidationChecksTheHierarchyTable() {
        assertEquals(List.of(), orm().validateSchema(List.of(Treatment.class)));
        assertEquals(List.of(), orm().validateSchema(List.of(Surgery.class, Checkup.class)));
        assertEquals(List.of(), orm().validateSchema(List.of(JoinedTreatment.class, JoinedCheckup.class, JoinedSurgery.class)));
        // A hierarchy with no table of its own reports it once, through the sealed type, however many of its types
        // are listed.
        var missing = orm().validateSchema(List.of(MissingKind.class, MissingHierarchy.class, OtherMissingKind.class));
        assertEquals(1, missing.size(), missing.toString());
        assertTrue(missing.getFirst().contains("MissingHierarchy"), missing.getFirst());
    }

    @Test
    public void typeIndexListsTheSealedTypes() {
        var types = TypeDiscovery.getDataTypes();
        assertTrue(types.contains(Treatment.class), "the sealed entity");
        assertTrue(types.contains(JoinedTreatment.class), "the joined sealed entity");
        assertTrue(types.contains(Surgery.class), "its subtypes");
    }

    @Discriminator
    sealed interface MissingHierarchy extends Entity<Integer> permits MissingKind, OtherMissingKind {
        Integer id();
    }

    record MissingKind(@PK Integer id) implements MissingHierarchy {}

    record OtherMissingKind(@PK Integer id, String note) implements MissingHierarchy {}

    // Joined Table.

    @Test
    public void selectJoinedTypeJoinsBaseAndExtensionForeignKeys() {
        var treatments = capture(() -> orm().entity(JoinedTreatment.class).select().getResultList());
        String sql = statement();
        assertTrue(sql.contains("INNER JOIN pet"), sql);
        assertTrue(sql.contains("LEFT JOIN joined_surgery"), sql);
        assertTrue(sql.contains("LEFT JOIN vet"), sql);
        assertTrue(sql.contains(".pet_id"), sql);
        assertEquals(2, treatments.size());
        var checkup = assertInstanceOf(JoinedCheckup.class, treatments.get(0));
        assertEquals("Leo", checkup.pet().name());
        assertEquals("Healthy", checkup.notes());
        var surgery = assertInstanceOf(JoinedSurgery.class, treatments.get(1));
        assertEquals("Rosy", surgery.pet().name());
        assertEquals("Leary", surgery.surgeon().lastName());
    }

    @Test
    public void insertAndUpdateJoinedTypeWriteForeignKeys() {
        var treatments = orm().entity(JoinedTreatment.class);
        var pet = orm().entity(Pet.class).getById(2);
        var vet = orm().entity(Vet.class).getById(4);
        var id = treatments.insertAndFetchId(new JoinedSurgery(null, pet, vet));
        var surgery = assertInstanceOf(JoinedSurgery.class, treatments.getById(id));
        assertEquals("Basil", surgery.pet().name());
        assertEquals("Ortega", surgery.surgeon().lastName());
        var otherVet = orm().entity(Vet.class).getById(5);
        treatments.update(new JoinedSurgery(id, orm().entity(Pet.class).getById(1), otherVet));
        var updated = assertInstanceOf(JoinedSurgery.class, treatments.getById(id));
        assertEquals("Leo", updated.pet().name());
        assertEquals("Stevens", updated.surgeon().lastName());
        var checkupId = treatments.insertAndFetchId(new JoinedCheckup(null, pet, "New"));
        assertEquals("Basil", assertInstanceOf(JoinedCheckup.class, treatments.getById(checkupId)).pet().name());
        assertEquals(4, treatments.count());
    }

    @Test
    public void whereOnJoinedMetamodels() {
        var pet = orm().entity(Pet.class).getById(3);
        var ofRosy = orm().entity(JoinedTreatment.class).select()
                .where(JoinedTreatment_.pet, EQUALS, pet)
                .getResultList();
        assertEquals(1, ofRosy.size());
        var vet = orm().entity(Vet.class).getById(2);
        var ofLeary = capture(() -> orm().entity(JoinedTreatment.class).select()
                .where(JoinedSurgery_.surgeon, EQUALS, vet)
                .getResultList());
        assertTrue(statement().contains("surgeon_id = ?"), statement());
        assertEquals(1, ofLeary.size());
        assertInstanceOf(JoinedSurgery.class, ofLeary.getFirst());
        var byName = orm().entity(JoinedTreatment.class).select()
                .where(JoinedSurgery_.surgeon.lastName, EQUALS, "Leary")
                .getResultList();
        assertEquals(1, byName.size());
        var byOwner = orm().entity(JoinedTreatment.class).select()
                .where(Metamodel.of(JoinedTreatment.class, "pet.owner.lastName"), EQUALS, "Davis")
                .getResultList();
        assertEquals(1, byOwner.size());
    }

    @Test
    public void joinedSubtypeAsEntityIsRefused() {
        var repository = assertThrows(PersistenceException.class, () -> orm().entity(JoinedSurgery.class));
        assertTrue(repository.getMessage().contains("joined inheritance"), repository.getMessage());
        var select = assertThrows(PersistenceException.class,
                () -> orm().selectFrom(JoinedSurgery.class).getResultList());
        assertTrue(select.getMessage().contains("joined inheritance"), select.getMessage());
    }
}
