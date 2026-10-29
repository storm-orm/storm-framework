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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The code of an inline function Kotlin compiles into a class takes lines past the end of the class's source file;
 * the class's source map says, in its KotlinDebug stratum, which line of the file each was called from.
 */
class InlinedLinesTest {

    /** The source map Kotlin writes for a class whose 1105 lines call an inline function from two lines. */
    private static final String MAP = String.join("\n",
            "SMAP", "ServiceTest.kt", "Kotlin", "*S Kotlin", "*F", "+ 1 ServiceTest.kt", "app/ServiceTest",
            "+ 2 RepositoryLookup.kt", "st/orm/repository/RepositoryLookupKt", "*L", "1#1,1105:1", "591#2,5:1106",
            "742#2:1111", "*S KotlinDebug", "*F", "+ 1 ServiceTest.kt", "app/ServiceTest", "*L", "243#1:1106,5",
            "241#1:1111", "*E");

    @Test
    void anInlinedLineMapsToTheLineThatCalledTheInlineFunction() {
        assertArrayEquals(new int[] {1106, 1110, 243, 1111, 1111, 241}, CallSiteCapture.InlinedLines.kotlinDebugRanges(MAP));
    }

    @Test
    void aMapWithoutAKotlinDebugStratumMapsNothing() {
        assertArrayEquals(new int[] {}, CallSiteCapture.InlinedLines.kotlinDebugRanges(
                "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\napp/A\n*L\n1#1,20:1\n*E\n"));
    }

    @Test
    void aClassWithoutASourceMapKeepsItsLines() {
        assertEquals(42, CallSiteCapture.InlinedLines.callSiteLine(InlinedLinesTest.class, 42));
        assertEquals(42, CallSiteCapture.InlinedLines.callSiteLine(String.class, 42));
    }
}
