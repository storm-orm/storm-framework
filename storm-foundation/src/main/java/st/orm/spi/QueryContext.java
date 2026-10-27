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
package st.orm.spi;

import java.util.Optional;
import java.util.OptionalInt;
import st.orm.Data;

/**
 * Describes a single statement execution observed by a {@link QueryObserver}.
 *
 * <p>The {@link #operation()}, {@link #dataType()} and {@link #kind()} properties are low-cardinality and suitable
 * as metric tags. The {@link #statement()} property is high-cardinality and is intended for trace attributes only;
 * it must never be used as a metric tag.</p>
 *
 * @see QueryObserver
 * @since 1.13
 */
public interface QueryContext {

    /**
     * Classifies the kind of SQL statement being executed.
     *
     * @return the SQL operation; {@link SqlOperation#UNDEFINED} when the operation is unknown.
     */
    SqlOperation operation();

    /**
     * Returns the entity or projection type primarily targeted by the statement.
     *
     * @return the data type, or empty when the statement is not associated with a specific type.
     */
    Optional<Class<? extends Data>> dataType();

    /**
     * Returns how the statement is executed.
     *
     * @return the execution kind.
     */
    ExecutionKind kind();

    /**
     * Returns what caused the statement to execute.
     *
     * <p>A statement resolving a reference is shaped exactly like a primary key lookup the application could have
     * written itself, so this is what makes the cost of resolving references measurable on its own.</p>
     *
     * @return the statement origin; {@link StatementOrigin#DIRECT} unless the statement resolves a reference.
     */
    default StatementOrigin origin() {
        return StatementOrigin.DIRECT;
    }

    /**
     * Returns the identity of the statement's shape: the template it was generated from, before values were bound.
     *
     * <p>Statements generated from one template share a shape whatever their parameters look like, including a
     * collection parameter that expands to a different number of placeholders per execution. Grouping by shape
     * therefore treats those as one statement, where the text would split them.</p>
     *
     * @return the shape identity; {@code 0} when unknown.
     */
    default long shapeId() {
        return 0L;
    }

    /**
     * Returns the number of statements in the batch.
     *
     * @return the batch size; present only for {@link ExecutionKind#BATCH} executions when the size is known at
     * execution time.
     */
    OptionalInt batchSize();

    /**
     * Returns whether the execution runs inside a transaction: a transaction block, or a transaction of the
     * template's provider, on the executing thread. A block that opens its transaction at its first statement counts
     * from that statement.
     *
     * <p>An implementation that does not know says the execution is transactional, so that no advice on writes
     * outside a transaction rests on a guess.</p>
     *
     * @return {@code true} when the execution runs in a transaction.
     * @since 1.16
     */
    default boolean transactional() {
        return true;
    }

    /**
     * Returns the identity of the transaction the execution runs in: a number that is the same for every execution
     * in the transaction and different for every other transaction, or {@code 0} when the execution runs outside
     * any transaction or the implementation does not know which transaction it runs in. An execution in a
     * transaction whose identity is unknown is still {@link #transactional()}.
     *
     * <p>A block that opens its transaction at its first statement has an identity from that statement, so the
     * identity is asked once the statement has run.</p>
     *
     * @return the transaction identity, or {@code 0}.
     * @since 1.16
     */
    default long transactionId() {
        return 0L;
    }

    /**
     * Returns whether a read maps its rows to an entity or projection, rather than to computed values such as a
     * count, which a lookup of the rows by key could not replace.
     *
     * <p>An implementation that does not know says it does, so advice on a repeated read keeps naming the lookup of
     * the rows by key.</p>
     *
     * @return {@code true} when the read returns rows of a type.
     * @since 1.16
     */
    default boolean readsRecords() {
        return true;
    }

    /**
     * Returns whether a write is one a repository issued for the entities or keys it was given, as an insert,
     * update or removal of an entity is, rather than a write by condition, which no overload that takes a list
     * replaces.
     *
     * <p>An implementation that does not know says it is not, so no advice on a repeated write names an overload
     * the write may not have.</p>
     *
     * @return {@code true} when the write was made for given entities or keys.
     * @since 1.16
     */
    default boolean repositoryWrite() {
        return false;
    }

    /**
     * Returns the SQL statement being executed, with all parameters replaced by placeholders.
     *
     * <p><strong>Note:</strong> this value is high-cardinality; use it for trace attributes only, never as a metric
     * tag.</p>
     *
     * @return the SQL statement, or empty when no statement text is available.
     */
    Optional<String> statement();

    /**
     * Classifies how a statement is executed.
     */
    enum ExecutionKind {

        /** The statement produces a result set. */
        QUERY,

        /** The statement is executed as a single update. */
        UPDATE,

        /** The statement is executed as a batch of updates. */
        BATCH
    }
}
