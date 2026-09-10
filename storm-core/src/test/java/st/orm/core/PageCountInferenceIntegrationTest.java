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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.Visit;
import st.orm.core.template.ORMTemplate;

/**
 * Integration tests for the count inference of {@code page(Pageable)}: a page that is not full determines the total
 * directly, so the count query only runs for a full page, or for an empty page beyond the first.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class PageCountInferenceIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private List<String> observeStatements(Runnable runnable) {
        List<String> statements = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), runnable);
        return statements;
    }

    @Test
    public void testPartialFirstPageSkipsCount() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.selectFrom(City.class).getResultCount();
        AtomicReference<Page<City>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(City.class).page(Pageable.ofSize((int) total + 10))));
        assertEquals(1, statements.size());
        assertEquals(total, page.getPlain().totalCount());
        assertEquals(total, page.getPlain().content().size());
    }

    @Test
    public void testFullPageRunsCount() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.selectFrom(Visit.class).getResultCount();
        AtomicReference<Page<Visit>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(Visit.class).page(Pageable.ofSize(5))));
        assertEquals(2, statements.size());
        assertTrue(statements.stream().anyMatch(statement -> statement.startsWith("SELECT COUNT(*)")));
        assertEquals(total, page.getPlain().totalCount());
        assertEquals(5, page.getPlain().content().size());
    }

    @Test
    public void testPartialLastPageSkipsCount() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.selectFrom(Visit.class).getResultCount();
        int pageSize = 5;
        int lastPageNumber = (int) (total / pageSize);
        int lastPageContentSize = (int) (total % pageSize);
        assertTrue(lastPageContentSize > 0, "test data must not fill the last page exactly");
        AtomicReference<Page<Visit>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(Visit.class).page(Pageable.of(lastPageNumber, pageSize))));
        assertEquals(1, statements.size());
        assertEquals(total, page.getPlain().totalCount());
        assertEquals(lastPageContentSize, page.getPlain().content().size());
    }

    @Test
    public void testEmptyPageBeyondEndRunsCount() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.selectFrom(Visit.class).getResultCount();
        AtomicReference<Page<Visit>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(Visit.class).page(Pageable.of(100, 5))));
        assertEquals(2, statements.size());
        assertEquals(total, page.getPlain().totalCount());
        assertTrue(page.getPlain().content().isEmpty());
    }

    @Test
    public void testEmptyFirstPageSkipsCount() {
        var orm = ORMTemplate.of(dataSource);
        AtomicReference<Page<City>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(City.class)
                        .where(City_.name, EQUALS, "No Such City")
                        .page(Pageable.ofSize(5))));
        assertEquals(1, statements.size());
        assertEquals(0, page.getPlain().totalCount());
        assertTrue(page.getPlain().content().isEmpty());
    }

    @Test
    public void testPrecomputedTotalSkipsCount() {
        var orm = ORMTemplate.of(dataSource);
        AtomicReference<Page<Visit>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(Visit.class).page(Pageable.ofSize(5), 42)));
        assertEquals(1, statements.size());
        assertEquals(42, page.getPlain().totalCount());
        assertEquals(5, page.getPlain().content().size());
    }

    @Test
    public void testInferenceWithPageableSortOrders() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.selectFrom(City.class).getResultCount();
        AtomicReference<Page<City>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.selectFrom(City.class)
                        .page(Pageable.ofSize((int) total + 10).sortBy(City_.name))));
        assertEquals(1, statements.size());
        assertEquals(total, page.getPlain().totalCount());
        var names = page.getPlain().content().stream().map(City::name).toList();
        assertEquals(names.stream().sorted().toList(), names);
    }

    @Test
    public void testRepositoryPageSkipsCountOnPartialPage() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.entity(City.class).count();
        AtomicReference<Page<City>> page = new AtomicReference<>();
        var statements = observeStatements(() ->
                page.setPlain(orm.entity(City.class).page(0, (int) total + 10)));
        assertEquals(1, statements.size());
        assertEquals(total, page.getPlain().totalCount());
    }

    @Test
    public void testPageableSortsWithExplicitOrderByThrows() {
        var orm = ORMTemplate.of(dataSource);
        assertThrows(PersistenceException.class, () ->
                orm.selectFrom(City.class).orderBy(City_.name).page(Pageable.ofSize(5).sortBy(City_.id)));
    }

    @Test
    public void testNavigationAcrossPagesKeepsTotalConsistent() {
        var orm = ORMTemplate.of(dataSource);
        long total = orm.selectFrom(Visit.class).getResultCount();
        var pageable = Pageable.ofSize(5);
        long seen = 0;
        Page<Visit> page;
        do {
            page = orm.selectFrom(Visit.class).page(pageable);
            assertEquals(total, page.totalCount());
            seen += page.content().size();
            pageable = page.next();
        } while (page.hasNext());
        assertEquals(total, seen);
    }
}
