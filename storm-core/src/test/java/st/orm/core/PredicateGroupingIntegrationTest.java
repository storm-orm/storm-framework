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
import static st.orm.Operator.EQUALS;
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.WhereBuilder;

/**
 * The parentheses a combination of predicates renders: a group gets them only where an operator other than its own
 * combines it, so a chain under one operator stays flat and {@code (a or b) and c} reads as written.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class PredicateGroupingIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /** Returns the WHERE clause of the select the predicate builds, with its columns as {@code n}. */
    private String where(Function<WhereBuilder<City, City, Integer>, PredicateBuilder<City, ?, ?>> predicate) {
        var orm = ORMTemplate.of(dataSource);
        List<String> statements = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), () -> orm.entity(City.class).select().where(predicate).getResultList());
        String statement = statements.getFirst().replaceAll("\\s+", " ");
        return statement.substring(statement.indexOf("WHERE ") + 6).replaceAll("[a-z]\\.name", "n").trim();
    }

    private static PredicateBuilder<City, City, Integer> name(WhereBuilder<City, City, Integer> it, String name) {
        return it.where(City_.name, EQUALS, name);
    }

    @Test
    public void singleOperatorChainIsFlat() {
        assertEquals("n = ? OR n = ?", where(it -> name(it, "a").or(name(it, "b"))));
        assertEquals("n = ? OR n = ? OR n = ?", where(it -> name(it, "a").or(name(it, "b")).or(name(it, "c"))));
        assertEquals("n = ? OR n = ? OR n = ?", where(it -> name(it, "a").or(name(it, "b").or(name(it, "c")))));
    }

    @Test
    public void groupUnderAnotherOperatorIsParenthesized() {
        assertEquals("(n = ? OR n = ?) AND n = ?", where(it -> name(it, "a").or(name(it, "b")).and(name(it, "c"))));
        assertEquals("n = ? AND (n = ? OR n = ?)", where(it -> name(it, "a").and(name(it, "b").or(name(it, "c")))));
        assertEquals("(n = ? AND n = ?) OR n = ?", where(it -> name(it, "a").and(name(it, "b")).or(name(it, "c"))));
    }

    @Test
    public void combiningLeavesOperandsUnchanged() {
        assertEquals("n = ? OR n = ?", where(it -> {
            var group = name(it, "a").or(name(it, "b"));
            group.and(name(it, "c"));
            return group;
        }));
    }
}
