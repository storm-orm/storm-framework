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
package st.orm.spi.h2;

import static java.lang.reflect.Modifier.isStatic;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.h2.util.ParserUtil;
import st.orm.tck.AbstractReservedWordConformanceTest;
import st.orm.test.StormTest;

@StormTest(rollback = false)
public class H2ReservedWordConformanceTest extends AbstractReservedWordConformanceTest {

    /**
     * The keywords of H2's parser that H2 refuses where Storm renders a name. The parser declares one constant per
     * keyword token, numbered from {@code FIRST_KEYWORD} through {@code LAST_KEYWORD}, and names the constant after
     * the word.
     */
    @Override
    protected Optional<Set<String>> reservedWords() throws SQLException {
        List<String> keywords = new ArrayList<>();
        for (Field field : ParserUtil.class.getFields()) {
            if (isStatic(field.getModifiers()) && field.getType() == int.class
                    && !field.getName().endsWith("_KEYWORD")) {
                int token = readToken(field);
                if (token >= ParserUtil.FIRST_KEYWORD && token <= ParserUtil.LAST_KEYWORD) {
                    keywords.add(field.getName());
                }
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            return Optional.of(refusedAsName(connection, keywords));
        }
    }

    private static int readToken(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * H2 folds an unquoted name to upper case, so a word quoted needlessly no longer matches a column created
     * without quotes.
     */
    @Override
    protected Optional<Set<String>> quotedWords() {
        return Optional.of(H2SqlDialect.RESERVED_WORDS);
    }
}
