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

import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.core.template.impl.SearchTerms.Term;

public class SearchTermsTest {

    private static List<String> texts(String input) {
        return SearchTerms.parse(input).stream().map(Term::text).toList();
    }

    @Test
    public void whitespaceSeparatesTerms() {
        assertEquals(List.of("ugly", "bad"), texts("ugly bad"));
        assertEquals(List.of("ugly", "bad"), texts("  ugly \t bad\n"));
    }

    @Test
    public void inputWithoutTermsHoldsNone() {
        assertEquals(List.of(), texts(""));
        assertEquals(List.of(), texts("   "));
        assertEquals(List.of(), texts("\"\""));
        assertEquals(List.of(), texts("\"  \""));
        assertEquals(List.of(), texts("\""));
    }

    @Test
    public void quotedPhraseIsOneTerm() {
        assertEquals(List.of(new Term("the good", true), new Term("ugly", false)), SearchTerms.parse("\"the good\" ugly"));
        assertEquals(List.of("the good"), texts("\" the good \""));
        assertEquals(List.of("a b", "c"), texts("\"a b\"c"));
    }

    @Test
    public void anOpenQuoteRunsToTheEndOfTheInput() {
        // The user is still typing the phrase.
        assertEquals(List.of(new Term("ugly bad", true)), SearchTerms.parse("\"ugly bad"));
        assertEquals(List.of("ugly", "bad and"), texts("ugly \"bad and "));
    }

    @Test
    public void innerQuoteIsAnOrdinaryCharacter() {
        assertEquals(List.of("o\"brien"), texts("o\"brien"));
        assertEquals(List.of("12\"", "x"), texts("12\" x"));
    }

    @Test
    public void wildcardsAreKeptForTheOperatorToEscape() {
        assertEquals(List.of("50%", "a_b"), texts("50% a_b"));
    }

    @Test
    public void repeatedTermIsReadOnce() {
        assertEquals(List.of("bad", "ugly"), texts("bad ugly bad"));
        // A quoted term is read apart from the bare one: it is never ignored where the bare one may be.
        assertEquals(List.of(new Term("bad", false), new Term("bad", true)), SearchTerms.parse("bad \"bad\" bad"));
    }
}
