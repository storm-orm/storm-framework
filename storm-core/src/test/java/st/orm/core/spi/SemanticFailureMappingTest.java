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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.TemplateString.raw;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Entity;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.OptimisticLockException;
import st.orm.PK;
import st.orm.Version;
import st.orm.core.template.ORMTemplate;
import st.orm.spi.ExceptionContext;
import st.orm.spi.SqlOperation;

/**
 * The failures Storm's own logic detects after a statement ran, an optimistic lock conflict and a single result
 * that is missing or not unique, pass through the template's exception mapper with the operation and the type as
 * context, from the query, the query builder and the repository alike. The default mapper keeps them Storm-typed.
 */
class SemanticFailureMappingTest {

    public record Owner(@PK @Nullable Integer id, String name, @Version int version) implements Entity<Integer> {}

    /** The exception the mapper under test returns for every failure. */
    static final class MappedException extends RuntimeException {
        MappedException(Throwable cause) {
            super(cause);
        }
    }

    private DataSource dataSource;
    private List<ExceptionContext> contexts;
    private ORMTemplate orm;

    @BeforeEach
    void database() throws SQLException {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:semantic_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owner (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL, "
                    + "version INTEGER NOT NULL)");
        }
        contexts = new ArrayList<>();
        orm = ORMTemplate.builder(dataSource)
                .exceptionMapper((cause, context) -> {
                    contexts.add(context);
                    return new MappedException(cause);
                })
                .build();
    }

    private Owner insert(String name) {
        return orm.entity(Owner.class).insertAndFetch(new Owner(null, name, 0));
    }

    private ExceptionContext onlyContext() {
        assertEquals(1, contexts.size(), "one failure, mapped once");
        return contexts.getFirst();
    }

    @Test
    void aStaleUpdateIsMappedAsAnUpdateOfTheType() {
        var owners = orm.entity(Owner.class);
        var owner = insert("Betty");
        var renamed = new Owner(owner.id(), "Elizabeth", owner.version());
        owners.update(renamed);
        var mapped = assertThrows(MappedException.class, () -> owners.update(renamed));
        var conflict = assertInstanceOf(OptimisticLockException.class, mapped.getCause());
        assertSame(renamed, conflict.getEntity());
        var context = onlyContext();
        assertEquals(SqlOperation.UPDATE, context.operation());
        assertEquals(Optional.of(Owner.class), context.dataType());
        assertTrue(context.statement().isEmpty(), "a failure detected after the statement ran names no statement");
    }

    @Test
    void aStaleBatchUpdateIsMapped() {
        var owners = orm.entity(Owner.class);
        var renamed = new Owner(insert("Betty").id(), "Elizabeth", 0);
        owners.update(List.of(renamed));
        var mapped = assertThrows(MappedException.class, () -> owners.update(List.of(renamed)));
        assertInstanceOf(OptimisticLockException.class, mapped.getCause());
        assertEquals(SqlOperation.UPDATE, onlyContext().operation());
    }

    @Test
    void aMissingSingleResultOfAQueryIsMappedAsASelect() {
        var mapped = assertThrows(MappedException.class,
                () -> orm.query(raw("SELECT name FROM owner WHERE id = -1")).getSingleResult(String.class));
        assertInstanceOf(NoResultException.class, mapped.getCause());
        var context = onlyContext();
        assertEquals(SqlOperation.SELECT, context.operation());
        assertTrue(context.statement().orElseThrow().contains("FROM owner"));
        var untyped = assertThrows(MappedException.class,
                () -> orm.query(raw("SELECT name FROM owner WHERE id = -1")).getSingleResult());
        assertInstanceOf(NoResultException.class, untyped.getCause());
    }

    @Test
    void aNonUniqueResultOfAQueryIsMapped() {
        insert("Betty");
        insert("George");
        var single = assertThrows(MappedException.class,
                () -> orm.query(raw("SELECT name FROM owner")).getSingleResult());
        assertInstanceOf(NonUniqueResultException.class, single.getCause());
        var optional = assertThrows(MappedException.class,
                () -> orm.query(raw("SELECT name FROM owner")).getOptionalResult(String.class));
        assertInstanceOf(NonUniqueResultException.class, optional.getCause());
        assertEquals(2, contexts.size());
    }

    @Test
    void aNullSingleResultIsMapped() {
        insert("Betty");
        var mapped = assertThrows(MappedException.class,
                () -> orm.query(raw("SELECT NULL FROM owner")).getSingleResult(String.class));
        assertTrue(mapped.getCause().getMessage().contains("COALESCE"), mapped.getCause().getMessage());
        assertEquals(SqlOperation.SELECT, onlyContext().operation());
    }

    @Test
    void theRepositoryAndTheBuilderMapTheirSingleResults() {
        var owners = orm.entity(Owner.class);
        var missing = assertThrows(MappedException.class, () -> owners.getById(-1));
        assertInstanceOf(NoResultException.class, missing.getCause());
        assertEquals(SqlOperation.SELECT, onlyContext().operation());
        insert("Betty");
        insert("George");
        var ambiguous = assertThrows(MappedException.class, () -> owners.select().getSingleResult());
        assertInstanceOf(NonUniqueResultException.class, ambiguous.getCause());
        var optional = assertThrows(MappedException.class, () -> owners.select().getOptionalResult());
        assertInstanceOf(NonUniqueResultException.class, optional.getCause());
    }

    @Test
    void aRefResultIsMappedAsASelect() {
        var owners = orm.entity(Owner.class);
        var missing = assertThrows(MappedException.class,
                () -> owners.selectRef().where(raw("1 = 0")).getSingleResult());
        assertInstanceOf(NoResultException.class, missing.getCause());
        var context = onlyContext();
        assertEquals(SqlOperation.SELECT, context.operation());
        assertTrue(context.dataType().isEmpty(), "a select affects no type");
        insert("Betty");
        insert("George");
        var ambiguous = assertThrows(MappedException.class, () -> owners.selectRef().getSingleResult());
        assertInstanceOf(NonUniqueResultException.class, ambiguous.getCause());
        var optional = assertThrows(MappedException.class, () -> owners.selectRef().getOptionalResult());
        assertInstanceOf(NonUniqueResultException.class, optional.getCause());
        assertEquals(Optional.empty(), owners.selectRef().where(raw("1 = 0")).getOptionalResult());
    }

    @Test
    void theDefaultMapperKeepsTheFailuresStormTyped() {
        var plain = ORMTemplate.builder(dataSource).build();
        var owners = plain.entity(Owner.class);
        assertThrows(NoResultException.class, () -> owners.getById(-1));
        var owner = owners.insertAndFetch(new Owner(null, "Betty", 0));
        var renamed = new Owner(owner.id(), "Elizabeth", owner.version());
        owners.update(renamed);
        var conflict = assertThrows(OptimisticLockException.class, () -> owners.update(renamed));
        assertSame(renamed, conflict.getEntity());
        owners.insert(new Owner(null, "George", 0));
        assertThrows(NonUniqueResultException.class, () -> owners.select().getSingleResult());
        assertTrue(contexts.isEmpty(), "the recording mapper belongs to the other template");
    }
}
