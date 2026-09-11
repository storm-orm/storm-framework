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

/**
 * Thrown when a statement Storm recognises as a write, an {@code INSERT}, {@code UPDATE} or {@code DELETE}, is
 * executed inside a read-only transaction.
 *
 * <p>The statement is refused before a connection is acquired, so the refusal is the same on every database and
 * driver. The mode is that of the transaction owning the connection: a joined {@code REQUIRED} block inside a
 * read-only transaction is read-only whatever it declares, and a write needs a transaction of its own, opened with
 * {@code REQUIRES_NEW}, or a read-write enclosing transaction. A write in a form Storm does not recognise as one,
 * such as a stored procedure call, reaches the database, which may refuse it under its own rules.</p>
 *
 * @since 1.14.1
 */
public class ReadOnlyTransactionException extends PersistenceException {

    public ReadOnlyTransactionException(String message) {
        super(message);
    }
}
