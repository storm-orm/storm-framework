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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;
import static st.orm.core.template.TemplateString.raw;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.QueryColumn;
import st.orm.Scrollable;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.model.Pet_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.PredicateBuilderFactory;

/**
 * A query column is a column a query names but the record does not carry: {@code owner.last_name_folded} is generated
 * by the database from the last name, and {@code Owner} declares it as {@code Owner_.lastNameFolded} with no
 * component.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class QueryColumnIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private final List<String> statements = new ArrayList<>();

    private ORMTemplate orm() {
        return ORMTemplate.of(dataSource);
    }

    private <T> T observed(java.util.function.Supplier<T> action) {
        statements.clear();
        return observe(sql -> statements.add(sql.statement().replaceAll("\\s+", " ")), action);
    }

    private List<String> firstNames(List<Owner> owners) {
        return owners.stream().map(Owner::firstName).sorted().toList();
    }

    @Test
    public void queryColumnIsNeverSelected() {
        List<Owner> owners = observed(() -> orm().entity(Owner.class).select().getResultList());
        assertEquals(10, owners.size());
        assertFalse(statements.getFirst().contains("last_name_folded"), statements.getFirst());
    }

    @Test
    public void whereMatchesTheQueryColumn() {
        List<Owner> owners = observed(() -> orm().entity(Owner.class).select()
                .where(Owner_.lastNameFolded, EQUALS, "davis")
                .getResultList());
        assertEquals(List.of("Betty", "Harold"), firstNames(owners));
        assertTrue(statements.getFirst().contains("last_name_folded = ?"), statements.getFirst());
    }

    @Test
    public void searchMatchesTheQueryColumn() {
        List<Owner> owners = orm().entity(Owner.class).select()
                .where(it -> PredicateBuilderFactory.<Owner, Owner, Integer>search("dav", List.of(Owner_.lastNameFolded)))
                .getResultList();
        assertEquals(List.of("Betty", "Harold"), firstNames(owners));
    }

    @Test
    public void pathReachesTheQueryColumnOfARelatedTable() {
        List<Pet> byFolded = observed(() -> orm().entity(Pet.class).select()
                .where(Pet_.owner.lastNameFolded, EQUALS, "davis")
                .getResultList());
        List<Pet> byName = orm().entity(Pet.class).select()
                .where(Pet_.owner.lastName, EQUALS, "Davis")
                .getResultList();
        assertEquals(List.of("Iggy", "Leo"), byFolded.stream().map(Pet::name).sorted().toList());
        assertEquals(byName.stream().map(Pet::id).toList(), byFolded.stream().map(Pet::id).toList());
        assertFalse(statements.getFirst().contains("last_name_folded,"), statements.getFirst());
    }

    @Test
    public void orderByAndKeysetSortByTheQueryColumn() {
        List<Owner> ordered = orm().entity(Owner.class).select()
                .orderBy(Owner_.lastNameFolded)
                .getResultList();
        assertEquals("Black", ordered.getFirst().lastName());
        var window = orm().entity(Owner.class).select()
                .scroll(Scrollable.of(Owner_.id, 3).sortBy(Owner_.lastNameFolded));
        assertEquals(List.of("Black", "Coleman", "Davis"), window.content().stream().map(Owner::lastName).toList());
    }

    @Test
    public void templatePlaceholderNamesTheQueryColumn() {
        List<String> folded = orm().query(raw("SELECT \0 FROM \0 WHERE \0 = \0", Owner_.lastNameFolded, Owner.class, Owner_.lastName, "Davis"))
                .getResultList(String.class);
        assertEquals(List.of("davis", "davis"), folded);
    }

    @Test
    public void recordHoldsNoValueForTheQueryColumn() {
        Owner owner = orm().entity(Owner.class).select().where(Owner_.id, EQUALS, 1).getSingleResult();
        var exception = assertThrows(UnsupportedOperationException.class, () -> Owner_.lastNameFolded.getValue(owner));
        assertTrue(exception.getMessage().contains("query column"), exception.getMessage());
    }

    @Test
    public void updateCannotSetTheQueryColumn() {
        var exception = assertThrows(PersistenceException.class,
                () -> orm().entity(Owner.class).update().set(Owner_.lastNameFolded, "x"));
        assertTrue(exception.getMessage().contains("last_name_folded"), exception.getMessage());
    }

    @Test
    public void insertLeavesTheQueryColumnOut() {
        Owner inserted = observed(() -> orm().entity(Owner.class).insertAndFetch(
                Owner.builder().firstName("Ada").lastName("Lovelace").address(
                        orm().entity(Owner.class).select().where(Owner_.id, EQUALS, 1).getSingleResult().address()).version(0).build()));
        assertFalse(statements.getFirst().contains("last_name_folded"), statements.getFirst());
        assertEquals(List.of("Ada"), firstNames(orm().entity(Owner.class).select()
                .where(Owner_.lastNameFolded, EQUALS, "lovelace").getResultList()));
        assertEquals("Lovelace", inserted.lastName());
    }

    @Test
    public void modelListsTheQueryColumnApart() throws Exception {
        var model = orm().entity(Owner.class).model();
        assertEquals(1, model.queryColumns().size());
        var column = model.queryColumns().getFirst();
        assertEquals("last_name_folded", column.name());
        assertEquals(0, column.index());
        assertEquals(String.class, column.type());
        assertFalse(column.insertable());
        assertFalse(column.updatable());
        assertFalse(column.nullable());
        assertTrue(model.columns().stream().noneMatch(it -> it.name().equals("last_name_folded")));
        assertEquals(List.of(column), model.getColumns(Owner_.lastNameFolded));
    }

    @Test
    public void queryColumnResolvesByName() {
        assertEquals(Owner_.lastNameFolded, Metamodel.of(Owner.class, "lastNameFolded"));
        Metamodel<Pet, ?> throughOwner = Metamodel.of(Pet.class, "owner.lastNameFolded");
        assertEquals("owner", throughOwner.path());
        assertEquals("lastNameFolded", throughOwner.field());
        assertEquals(String.class, throughOwner.fieldType());
        assertTrue(throughOwner.isColumn());
        assertEquals(Pet_.owner.lastNameFolded, throughOwner);
    }

    @QueryColumn(field = "name", type = String.class)
    public record ComponentNamedQueryColumn(@PK Integer id, String name) implements Entity<Integer> {}

    @Test
    public void queryColumnNamedAsAComponentIsRefused() {
        var exception = assertThrows(PersistenceException.class, () -> orm().entity(ComponentNamedQueryColumn.class).model());
        var messages = new ArrayList<String>();
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        assertTrue(messages.stream().anyMatch(message -> message.contains("record component")), messages.toString());
    }

    @QueryColumn(field = "nameCopy", type = String.class, column = "name")
    public record ColumnNamedQueryColumn(@PK Integer id, String name) implements Entity<Integer> {}

    @Test
    public void queryColumnNamingAMappedColumnIsRefused() {
        var exception = assertThrows(PersistenceException.class, () -> orm().entity(ColumnNamedQueryColumn.class).model());
        var messages = new ArrayList<String>();
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        assertTrue(messages.stream().anyMatch(message -> message.contains("which field 'name' maps")), messages.toString());
    }
}
