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
package clinic;

import java.util.function.Supplier;

/**
 * The shape of a class Kotlin copies into a repository's default method for an inline function such as a flow
 * operator: named after the method it was copied into, in a source file that is not the repository's own.
 */
public final class ReportRepository$DefaultImpls$lookUpAll$$inlined$map$1 {

    private ReportRepository$DefaultImpls$lookUpAll$$inlined$map$1() {
    }

    public static <T> T emit(Supplier<T> work) {
        return work.get();
    }
}
