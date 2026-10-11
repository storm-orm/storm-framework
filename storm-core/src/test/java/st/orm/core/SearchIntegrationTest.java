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
import static st.orm.core.template.SqlInterceptor.observe;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Metamodel;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.PredicateBuilderFactory;

/**
 * A search matches every term in at least one of its paths, ignoring case and literally, over a relation as well.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SearchIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private List<String> statements;

    @SafeVarargs
    private List<String> search(String input, Metamodel<Owner, String>... paths) {
        var orm = ORMTemplate.of(dataSource);
        statements = new ArrayList<>();
        List<String> names = new ArrayList<>();
        observe(sql -> statements.add(sql.statement()), () -> orm.entity(Owner.class).select()
                .where(it -> PredicateBuilderFactory.<Owner, Owner, Integer>search(input, List.of(paths)))
                .getResultList()
                .forEach(owner -> names.add(owner.firstName())));
        return names.stream().sorted().toList();
    }

    private String whereClause() {
        String statement = statements.getFirst().replaceAll("\\s+", " ");
        return statement.substring(statement.indexOf("WHERE ") + 6).trim();
    }

    @Test
    public void everyTermMustMatchSomePath() {
        assertEquals(List.of("Harold"), search("davis WINDSOR", Owner_.lastName, Owner_.address.city.name));
        assertEquals(List.of(), search("davis madison", Owner_.lastName, Owner_.address.city.name));
    }

    @Test
    public void termsAreGroupedPerTermAcrossPaths() {
        search("davis windsor", Owner_.lastName, Owner_.address.city.name);
        assertEquals(2, whereClause().split("\\) AND \\(").length);
        assertEquals(4, whereClause().split("LIKE").length - 1);
    }

    @Test
    public void singlePathRendersNoParentheses() {
        assertEquals(List.of("Betty", "Harold"), search("dav", Owner_.lastName));
        assertFalse(whereClause().startsWith("("));
        assertFalse(whereClause().contains(" OR "));
    }

    @Test
    public void quotedPhraseMatchesAsOneTerm() {
        assertEquals(List.of("Betty"), search("\"sun par\"", Owner_.address.city.name));
        assertEquals(List.of(), search("\"sun madison\"", Owner_.address.city.name));
    }

    @Test
    public void wildcardsMatchLiterally() {
        assertEquals(List.of(), search("%", Owner_.lastName));
        assertEquals(List.of(), search("_", Owner_.lastName));
    }

    @Test
    public void inputWithoutTermsMatchesEveryRow() {
        assertEquals(10, search("", Owner_.lastName).size());
        assertEquals(10, search("  \"\" ", Owner_.lastName, Owner_.firstName).size());
    }
}
