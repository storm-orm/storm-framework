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
 * Thrown when a cursor string cannot be turned into a position: it is malformed, was issued by an earlier cursor
 * format, was issued for another ordering or codec registry, or carries a value of the wrong type.
 *
 * <p>A cursor comes from a client, so this is the exception a web layer maps to its "start over from the first
 * window" response. A cursor that is refused is never a bug in the request that refused it: the request states the
 * ordering, and the cursor did not fit it.</p>
 *
 * @since 1.14
 */
public class InvalidCursorException extends PersistenceException {

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message.
     */
    public InvalidCursorException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause the cause.
     */
    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
