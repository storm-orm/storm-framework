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

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.core.model.Vet;
import st.orm.core.model.VetView;
import st.orm.core.model.Vet_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlLog;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SliceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    public void sliceOrdersByTheRequestAndNavigatesByPageNumber() {
        var vets = ORMTemplate.of(dataSource).entity(Vet.class);
        var pageable = Pageable.ofSize(4).sortByDescending(Vet_.id);
        var first = vets.slice(pageable);
        assertEquals(List.of(6, 5, 4, 3), first.stream().map(Vet::id).toList());
        assertTrue(first.hasNext());
        assertFalse(first.hasPrevious());
        var rest = vets.slice(pageable.next());
        assertEquals(List.of(2, 1), rest.stream().map(Vet::id).toList());
        assertFalse(rest.hasNext());
        assertTrue(rest.hasPrevious());
        assertEquals(first.content(), vets.slice(pageable.next().previous()).content());
    }

    @Test
    public void sliceRunsNoCountQuery() {
        var orm = ORMTemplate.of(dataSource);
        var scope = SqlLog.open("slice");
        try {
            // A full page makes page() count; a slice reads the page and one extra row in a single statement.
            orm.entity(Vet.class).slice(0, 4);
        } finally {
            scope.close();
        }
        assertEquals(1, scope.summary().statementCount());
    }

    @Test
    public void sliceRefReadsRefsInTheSameShape() {
        var refs = ORMTemplate.of(dataSource).entity(Vet.class).sliceRef(Pageable.ofSize(4).sortBy(Vet_.id));
        assertEquals(List.of(1, 2, 3, 4), refs.stream().map(ref -> ref.id()).toList());
        assertTrue(refs.hasNext());
    }

    @Test
    public void projectionsSliceLikeEntities() {
        var views = ORMTemplate.of(dataSource).projection(VetView.class).slice(1, 4);
        assertEquals(2, views.size());
        assertFalse(views.hasNext());
        assertTrue(views.hasPrevious());
    }

    @Test
    public void sortOrdersOnTheRequestAndTheQueryAreRefused() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () ->
                orm.selectFrom(Vet.class).orderBy(Vet_.id).slice(Pageable.ofSize(4).sortBy(Vet_.id)));
    }

    @Test
    public void pageSizeMustBePositive() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(IllegalArgumentException.class, () -> orm.selectFrom(Vet.class).slice(0, 0));
    }
}
