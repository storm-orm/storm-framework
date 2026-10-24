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

import static st.orm.core.template.impl.RecordReflection.findSingleTableSealedParent;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorColumn;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorValue;

import java.util.Optional;
import st.orm.Data;
import st.orm.SqlTemplateException;
import st.orm.core.template.SqlDialect;

/**
 * The discriminator of a subtype of a single-table hierarchy used as a statement's table.
 *
 * <p>Such a subtype lives in the sealed type's table beside the other subtypes' rows. A statement on the subtype
 * selects, counts, updates or deletes its own rows by the discriminator, which the statement's WHERE clause and a
 * join's ON clause carry, and an insert writes it.</p>
 *
 * @param subtype the subtype.
 * @param column the discriminator column.
 * @param value the subtype's discriminator value.
 * @since 1.16
 */
public record SubtypeDiscriminator(Class<? extends Data> subtype, ColumnName column, Object value) {

    /**
     * Returns whether {@code type} is a subtype of a single-table hierarchy, whose statements carry its discriminator.
     *
     * @param type the type to inspect.
     * @return {@code true} if the type is a permitted subclass of a single-table sealed entity.
     */
    public static boolean isSingleTableSubtype(Class<?> type) {
        return findSingleTableSealedParent(type).isPresent();
    }

    /**
     * Returns the discriminator of {@code type} when it is a subtype of a single-table hierarchy, or empty otherwise.
     */
    static Optional<SubtypeDiscriminator> of(Class<? extends Data> type) throws SqlTemplateException {
        var parent = findSingleTableSealedParent(type);
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SubtypeDiscriminator(type,
                new ColumnName(getDiscriminatorColumn(parent.get()), false),
                getDiscriminatorValue(type, parent.get())));
    }

    /**
     * Renders the condition selecting the subtype's rows, {@code alias.column = ?}, with the value mapped as a
     * parameter of the compiler. The caller binds {@link #value()} in the same position.
     */
    String predicate(String alias, TemplateCompiler compiler) {
        String columnSql = alias.isEmpty()
                ? column.qualified(compiler.dialect())
                : "%s.%s".formatted(alias, column.qualified(compiler.dialect()));
        return "%s = %s".formatted(columnSql, compiler.mapParameter(value));
    }

    /**
     * Returns the discriminator column's name as the dialect renders it.
     */
    String columnName(SqlDialect dialect) {
        return column.qualified(dialect);
    }
}
