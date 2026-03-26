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
package st.orm.template

/**
 * Marker annotation for Storm's block-based SQL DSL.
 *
 * When two implicit receivers within the same block are annotated with this marker, the compiler hides the outer
 * receiver inside the inner block, preventing accidental scope leaking. For example, inside a `whereBuilder { }`
 * lambda nested within a `select { }` block, methods like `orderBy` or `limit` from the outer [SqlScope] are not
 * accessible without an explicit `this@select` qualifier.
 *
 * Applied to: [SqlScope], [WhereBuilder], [SubqueryTemplate], [TemplateContext].
 *
 * @see SqlScope
 */
@DslMarker
annotation class SqlDsl
