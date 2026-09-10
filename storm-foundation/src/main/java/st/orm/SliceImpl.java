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
package st.orm;

import static java.util.List.copyOf;

import java.util.List;

/**
 * The slice that {@code slice(pageable)} returns: content and flags, navigated through the request that produced it.
 *
 * @param content the results.
 * @param hasNext {@code true} if rows existed after the slice at query time.
 * @param hasPrevious {@code true} if rows existed before the slice, that is, the page number is above zero.
 * @param <R> the type of the results.
 */
record SliceImpl<R>(List<R> content, boolean hasNext, boolean hasPrevious) implements Slice<R> {

    SliceImpl {
        content = copyOf(content);
    }
}
