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

public class SearchTermsTest {

    @Test
    public void whitespaceSeparatesTerms() {
        assertEquals(List.of("ugly", "bad"), SearchTerms.parse("ugly bad"));
        assertEquals(List.of("ugly", "bad"), SearchTerms.parse("  ugly \t bad\n"));
    }

    @Test
    public void inputWithoutTermsHoldsNone() {
        assertEquals(List.of(), SearchTerms.parse(""));
        assertEquals(List.of(), SearchTerms.parse("   "));
        assertEquals(List.of(), SearchTerms.parse("\"\""));
        assertEquals(List.of(), SearchTerms.parse("\"  \""));
    }

    @Test
    public void quotedPhraseIsOneTerm() {
        assertEquals(List.of("the good", "ugly"), SearchTerms.parse("\"the good\" ugly"));
        assertEquals(List.of("the good"), SearchTerms.parse("\" the good \""));
        assertEquals(List.of("a b", "c"), SearchTerms.parse("\"a b\"c"));
    }

    @Test
    public void unpairedOrInnerQuoteIsAnOrdinaryCharacter() {
        assertEquals(List.of("\"ugly", "bad"), SearchTerms.parse("\"ugly bad"));
        assertEquals(List.of("o\"brien"), SearchTerms.parse("o\"brien"));
    }

    @Test
    public void wildcardsAreKeptForTheOperatorToEscape() {
        assertEquals(List.of("50%", "a_b"), SearchTerms.parse("50% a_b"));
    }

    @Test
    public void repeatedTermIsReadOnce() {
        assertEquals(List.of("bad", "ugly"), SearchTerms.parse("bad ugly bad \"bad\""));
    }
}
