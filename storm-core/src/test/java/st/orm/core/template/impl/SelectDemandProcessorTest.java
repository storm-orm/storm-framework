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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether the text of a select names a column decides whether an index that holds the columns the select names
 * through the metamodel is known to serve it alone. A function name, a keyword, a type name, an alias, a literal or
 * a star inside a function names none; a bare word, a quoted identifier or a star outside a function may.
 */
public class SelectDemandProcessorTest {

    private static boolean namesNoColumn(String... fragments) {
        return SelectDemandProcessor.namesNoColumn(List.of(fragments));
    }

    @Test
    public void functionsKeywordsAliasesAndLiteralsNameNoColumn() {
        assertTrue(namesNoColumn("COUNT(*)"));
        assertTrue(namesNoColumn("MAX(", "), COUNT(*) AS total"));
        assertTrue(namesNoColumn("COALESCE(", ", 'none')"));
        assertTrue(namesNoColumn("CASE WHEN ", " > 1 THEN 'a' ELSE 'b' END"));
        assertTrue(namesNoColumn("CAST(", " AS VARCHAR(10)) AS \"Name\""));
        assertTrue(namesNoColumn("SUM(", ") OVER (PARTITION BY ", " ORDER BY ", " DESC)"));
        assertTrue(namesNoColumn("count ( * ), lower (", ")"));
        assertTrue(namesNoColumn("'it''s', 42, ", ""));
    }

    @Test
    public void aBareWordAQuotedIdentifierOrAStarOutsideAFunctionMayNameAColumn() {
        assertFalse(namesNoColumn("c.email"));
        assertFalse(namesNoColumn("MAX(", "), c.email"));
        assertFalse(namesNoColumn("\"email\""));
        assertFalse(namesNoColumn("`email`"));
        assertFalse(namesNoColumn("[email]"));
        assertFalse(namesNoColumn("*"));
        assertFalse(namesNoColumn("c.*"));
        assertFalse(namesNoColumn("(SELECT COUNT(*) FROM pet)"));
    }
}
