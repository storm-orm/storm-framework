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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.ResolveScope.CASCADE;

import org.junit.jupiter.api.Test;
import st.orm.SqlTemplateException;
import st.orm.core.model.City;
import st.orm.core.template.TableAliasResolver;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;

/**
 * Resolving a table by type has to pick a single occurrence. A table that is reachable from itself registers one
 * occurrence per hop around the cycle, which used to make resolution by type ambiguous even though the caller could
 * only have meant the occurrence it navigated to.
 */
public class AliasMapperSelfReferenceTest {

    private static AliasMapper aliasMapper() {
        return new AliasMapper(new TableUse(), TableAliasResolver.DEFAULT, TableNameResolver.DEFAULT, SchemaResolver.DEFAULT, null);
    }

    @Test
    public void resolvesToTheOccurrenceReachedWithoutPassingTheTableTwice() throws SqlTemplateException {
        var aliasMapper = aliasMapper();
        // The shape a self-referencing graph produces: the occurrence that was navigated to, plus the occurrences
        // reached by continuing around the cycle from it.
        aliasMapper.setAlias(City.class, "c1", "user.country.city");
        aliasMapper.setAlias(City.class, "c2", "user.country.city.capital.city");
        aliasMapper.setAlias(City.class, "c3", "user.country.city.largestCity.city");
        assertEquals("c1", aliasMapper.findAlias(City.class, null, CASCADE).orElseThrow());
    }

    @Test
    public void resolvesToTheRootWhenTheRootIsTheOneReferredBackTo() throws SqlTemplateException {
        var aliasMapper = aliasMapper();
        aliasMapper.setAlias(City.class, "c1", "");
        aliasMapper.setAlias(City.class, "c2", "capital.city");
        assertEquals("c1", aliasMapper.findAlias(City.class, null, CASCADE).orElseThrow());
    }

    @Test
    public void staysAmbiguousForOccurrencesOnSeparateBranches() {
        var aliasMapper = aliasMapper();
        // Neither path passes the city twice, so the caller genuinely has to say which one it means.
        assertThrows(SqlTemplateException.class, () -> {
            aliasMapper.setAlias(City.class, "c1", "survey.city");
            aliasMapper.setAlias(City.class, "c2", "client.city");
            aliasMapper.findAlias(City.class, null, CASCADE);
        });
    }

    @Test
    public void staysAmbiguousForOccurrencesRegisteredUnderTheSamePath() {
        var aliasMapper = aliasMapper();
        assertThrows(SqlTemplateException.class, () -> {
            aliasMapper.setAlias(City.class, "c1", "user.city");
            aliasMapper.setAlias(City.class, "c2", "user.city");
            aliasMapper.findAlias(City.class, null, CASCADE);
        });
    }

    @Test
    public void staysAmbiguousWhenAnOccurrenceCarriesNoPath() {
        var aliasMapper = aliasMapper();
        // A registration without a path cannot be placed in the graph, so it cannot be shown to repeat a table.
        assertThrows(SqlTemplateException.class, () -> {
            aliasMapper.setAlias(City.class, "c1", null);
            aliasMapper.setAlias(City.class, "c2", "user.city");
            aliasMapper.findAlias(City.class, null, CASCADE);
        });
    }

    @Test
    public void resolvingByPathIsUnaffected() throws SqlTemplateException {
        var aliasMapper = aliasMapper();
        aliasMapper.setAlias(City.class, "c1", "user.country.city");
        aliasMapper.setAlias(City.class, "c2", "user.country.city.capital.city");
        assertEquals("c2",
                aliasMapper.findAlias(City.class, "user.country.city.capital.city", CASCADE).orElseThrow());
    }

    @Test
    public void singleOccurrenceIsUnaffected() throws SqlTemplateException {
        var aliasMapper = aliasMapper();
        aliasMapper.setAlias(City.class, "c1", "user.city");
        assertTrue(aliasMapper.findAlias(City.class, null, CASCADE).isPresent());
    }
}
