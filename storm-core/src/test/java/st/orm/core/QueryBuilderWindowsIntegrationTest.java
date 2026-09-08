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
import static st.orm.Operator.GREATER_THAN;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.core.model.Vet;
import st.orm.core.model.VetSpecialty;
import st.orm.core.model.VetView;
import st.orm.core.model.Vet_;
import st.orm.core.model.VisitView;
import st.orm.core.template.ORMTemplate;
import st.orm.spi.QueryObserver;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class QueryBuilderWindowsIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private static List<Integer> ids(List<Window<Vet>> windows) {
        return windows.stream().flatMap(window -> window.content().stream()).map(Vet::id).toList();
    }

    @Test
    public void windowsByPrimaryKeyCoverAllRowsInKeyOrder() {
        // data.sql inserts 6 vets with ids 1 to 6.
        var windows = ORMTemplate.of(dataSource).entity(Vet.class).windows(4).toList();
        assertEquals(2, windows.size());
        assertEquals(4, windows.get(0).content().size());
        assertTrue(windows.get(0).hasNext());
        assertEquals(2, windows.get(1).content().size());
        assertFalse(windows.get(1).hasNext());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), ids(windows));
    }

    @Test
    public void windowsWithSizeCoveringAllRowsIsOneWindow() {
        var orm = ORMTemplate.of(dataSource);
        assertEquals(1, orm.entity(Vet.class).windows(6).count());
        assertEquals(1, orm.entity(Vet.class).windows(10).count());
    }

    @Test
    public void windowsOverEmptyResultIsEmpty() {
        var windows = ORMTemplate.of(dataSource).entity(Vet.class).select()
                .where(Vet_.id, GREATER_THAN, 100)
                .windows(2)
                .toList();
        assertTrue(windows.isEmpty());
    }

    @Test
    public void windowsFollowTheQueryFilter() {
        var windows = ORMTemplate.of(dataSource).entity(Vet.class).select()
                .where(Vet_.id, GREATER_THAN, 2)
                .windows(3)
                .toList();
        assertEquals(List.of(3, 4, 5, 6), ids(windows));
    }

    @Test
    public void windowsBackwardIterateInDescendingKeyOrder() {
        var windows = ORMTemplate.of(dataSource).entity(Vet.class)
                .windows(Scrollable.of(Vet_.id, 4).backward())
                .toList();
        assertEquals(List.of(6, 5, 4, 3, 2, 1), ids(windows));
    }

    @Test
    public void windowsWithSortFieldIterateInSortThenKeyOrder() {
        var orm = ORMTemplate.of(dataSource);
        var windows = orm.entity(Vet.class).windows(Scrollable.of(Vet_.id, Vet_.lastName, 2)).toList();
        assertEquals(3, windows.size());
        var expected = orm.entity(Vet.class).select().getResultList().stream()
                .sorted(Comparator.comparing(Vet::lastName).thenComparing(Vet::id))
                .map(Vet::id)
                .toList();
        assertEquals(expected, ids(windows));
    }

    @Test
    public void windowsResumeFromANavigationToken() {
        var orm = ORMTemplate.of(dataSource);
        Window<Vet> first = orm.entity(Vet.class).windows(2).findFirst().orElseThrow();
        var rest = orm.entity(Vet.class).windows(first.next()).toList();
        assertEquals(2, rest.size());
        assertEquals(List.of(3, 4, 5, 6), ids(rest));
    }

    @Test
    public void windowsResumeFromACursorString() {
        var orm = ORMTemplate.of(dataSource);
        Window<Vet> first = orm.entity(Vet.class).windows(2).findFirst().orElseThrow();
        var rest = orm.entity(Vet.class).windows(Scrollable.fromCursor(Vet_.id, first.nextCursor())).toList();
        assertEquals(List.of(3, 4, 5, 6), ids(rest));
    }

    @Test
    public void windowsFetchOneWindowPerAdvance() {
        var executions = new AtomicInteger();
        var orm = ORMTemplate.builder(dataSource)
                .queryObserver(context -> {
                    executions.incrementAndGet();
                    return QueryObserver.Observation.NOOP;
                })
                .build();
        var stream = orm.entity(Vet.class).windows(2);
        assertEquals(0, executions.get());  // Nothing runs until a window is asked for.
        var windows = stream.limit(1).toList();
        assertEquals(1, windows.size());
        assertEquals(List.of(1, 2), ids(windows));
        assertEquals(1, executions.get());  // One statement for the one window that was consumed.
    }

    @Test
    public void windowsRejectExplicitOrderBy() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .selectFrom(Vet.class)
                .orderBy(Vet_.lastName)
                .windows(2)
                .toList());
        assertTrue(exception.getMessage().contains("ORDER BY"), exception.getMessage());
    }

    @Test
    public void windowsRejectResultsThatDoNotCarryTheKey() {
        var exception = assertThrows(PersistenceException.class, () -> ORMTemplate.of(dataSource)
                .entity(Vet.class)
                .selectRef()
                .windows(Scrollable.of(Vet_.id, 2))
                .toList());
        assertTrue(exception.getMessage().contains("carry the key"), exception.getMessage());
    }

    @Test
    public void projectionWindowsByPrimaryKeyCoverAllRowsInKeyOrder() {
        var windows = ORMTemplate.of(dataSource).projection(VetView.class).windows(4).toList();
        assertEquals(2, windows.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6),
                windows.stream().flatMap(window -> window.content().stream()).map(VetView::id).toList());
    }

    @Test
    public void windowsRejectProjectionWithoutPrimaryKey() {
        // VisitView does not expose the primary key of the underlying visit table.
        var exception = assertThrows(PersistenceException.class,
                () -> ORMTemplate.of(dataSource).projection(VisitView.class).windows(2));
        assertTrue(exception.getMessage().contains("has none"), exception.getMessage());
    }

    @Test
    public void windowsRejectCompoundPrimaryKey() {
        var exception = assertThrows(PersistenceException.class,
                () -> ORMTemplate.of(dataSource).entity(VetSpecialty.class).windows(2));
        assertTrue(exception.getMessage().contains("compound"), exception.getMessage());
    }

    @Test
    public void windowsRejectNonPositiveSize() {
        assertThrows(IllegalArgumentException.class,
                () -> ORMTemplate.of(dataSource).entity(Vet.class).windows(0));
    }
}
