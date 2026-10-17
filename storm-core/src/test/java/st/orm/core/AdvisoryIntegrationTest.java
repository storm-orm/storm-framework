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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.SqlInterceptor.observe;

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
import st.orm.Pageable;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.QueryBuilder;

/**
 * A statement carries advice for the slow statement log where its own shape says why it reads more than it needs to:
 * a distinct over a join that repeats its rows, the count behind a page, and a deep offset.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class AdvisoryIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private QueryBuilder<Owner, Owner, Integer> owners() {
        return ORMTemplate.of(dataSource).entity(Owner.class).select();
    }

    /** Runs the query and returns the advice of every statement it executed, in order. */
    private static List<String> advisories(Supplier<?> query) {
        var advisories = new ArrayList<String>();
        observe(sql -> advisories.addAll(sql.advisories()), query::get);
        return advisories;
    }

    @Test
    public void distinctOverAJoinToTheManySideCarriesAdvice() {
        var advice = advisories(() -> owners().distinct().innerJoin(Pet.class).on(Owner.class).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("semiJoin(Pet)"), advice.getFirst());
    }

    @Test
    public void distinctWithoutARepeatingJoinCarriesNoAdvice() {
        assertEquals(List.of(), advisories(() -> owners().distinct().getResultList()));
        assertEquals(List.of(), advisories(() -> owners().innerJoin(Pet.class).on(Owner.class).getResultList()));
        // A left join keeps the owners without pets, which a semi-join would drop.
        assertEquals(List.of(), advisories(() -> owners().distinct().leftJoin(Pet.class).on(Owner.class).getResultList()));
        // The pet holds the key to its owner: the join adds at most one row per pet.
        assertEquals(List.of(), advisories(() -> ORMTemplate.of(dataSource).entity(Pet.class).select()
                .distinct().innerJoin(Owner.class).on(Pet.class).getResultList()));
    }

    @Test
    public void countBehindAPageCarriesAdvice() {
        var advice = advisories(() -> owners().page(Pageable.ofSize(2)));
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("slice"), advice.getFirst());
    }

    @Test
    public void countOfItsOwnAndSliceCarryNoAdvice() {
        assertEquals(List.of(), advisories(() -> owners().getResultCount()));
        assertEquals(List.of(), advisories(() -> owners().slice(Pageable.ofSize(2))));
        // A page that is not full knows its total, so no count runs.
        assertEquals(List.of(), advisories(() -> owners().page(Pageable.ofSize(1_000))));
    }

    @Test
    public void deepOffsetCarriesAdvice() {
        var advice = advisories(() -> owners().offset(1_000).limit(10).getResultList());
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("1000 rows"), advice.getFirst());
        assertTrue(advice.getFirst().contains("scroll"), advice.getFirst());
        assertEquals(List.of(), advisories(() -> owners().offset(999).limit(10).getResultList()));
    }

    @Test
    public void deepPageCarriesOffsetAdvice() {
        var advice = advisories(() -> owners().slice(Pageable.of(100, 10)));
        assertEquals(1, advice.size(), advice.toString());
        assertTrue(advice.getFirst().contains("1000 rows"), advice.getFirst());
    }
}
