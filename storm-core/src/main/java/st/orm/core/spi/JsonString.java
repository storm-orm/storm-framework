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
package st.orm.core.spi;


/**
 * A serialized JSON value produced by a JSON converter.
 *
 * <p>Carrying the JSON as a distinct type instead of a plain string lets dialects choose the appropriate JDBC
 * binding via the {@code SqlDialect.setParameter} overload for this type: most databases bind JSON as a plain
 * string, while a database with a strictly typed native JSON column needs an untyped parameter so the server can
 * cast it.</p>
 *
 * @param value the serialized JSON text.
 * @since 1.12
 */
public record JsonString(String value) {

    /**
     * Returns the raw JSON text, so the value renders correctly when inlined as a SQL string literal.
     */
    @Override
    public String toString() {
        return value;
    }
}
