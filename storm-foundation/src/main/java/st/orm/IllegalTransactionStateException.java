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
 * Thrown when a transaction block cannot open against the transaction state it finds: {@code MANDATORY} without
 * a transaction, {@code NEVER} inside one, or a joining block that states a stricter isolation level than the
 * transaction it joins runs at.
 *
 * <p>The refusal happens when the block opens, before any of its statements run. A joined block runs on the
 * connection of the transaction it joins, whose isolation level cannot change once it is open; a block may state
 * that level or a lower one, and a stated level inside a transaction at the database's default is refused as
 * well, since that level is not known. A refused block fails as a joined block does, so the transaction it joined
 * rolls back with it. Under Spring-managed transactions, a manager configured to validate participation reports
 * its refusal through this exception too.</p>
 *
 * @since 1.14
 */
public class IllegalTransactionStateException extends PersistenceException {

    public IllegalTransactionStateException(String message) {
        super(message);
    }

    public IllegalTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
