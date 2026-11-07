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
 * Thrown when an optimistic locking conflict occurs: the row changed, or was removed, after the entity was read.
 *
 * <p>A conflict stems from concurrent activity, so this is a {@link TransientPersistenceException}: running the whole
 * transaction again, which reads the entity again, may succeed.</p>
 */
public class OptimisticLockException extends TransientPersistenceException {

    /**
     * The object that caused the exception
     */
    Object entity;

    /**
     * Constructs a new {@code OptimisticLockException} exception with {@code null} as its detail message.
     */
    public OptimisticLockException() {
        super();
    }

    /**
     * Constructs a new {@code OptimisticLockException} exception with the specified detail message.
     *
     * @param message
     *            the detail message.
     */
    public OptimisticLockException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code OptimisticLockException} exception with the specified detail message and cause.
     *
     * @param message
     *            the detail message.
     * @param cause
     *            the cause.
     */
    public OptimisticLockException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code OptimisticLockException} exception with the specified cause.
     *
     * @param cause
     *            the cause.
     */
    public OptimisticLockException(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code OptimisticLockException} exception with the
     * specified entity.
     *
     * @param entity
     *            the entity.
     */
    public OptimisticLockException(Object entity) {
        this.entity = entity;
    }

    /**
     * Constructs a new {@code OptimisticLockException} exception with the
     * specified detail message, cause, and entity.
     *
     * @param message
     *            the detail message.
     * @param cause
     *            the cause.
     * @param entity
     *            the entity.
     */
    public OptimisticLockException(String message, Throwable cause, Object entity) {
        super(message, cause);
        this.entity = entity;
    }

    /**
     * Returns the entity that caused this exception.
     *
     * @return the entity.
     */
    public Object getEntity() {
        return this.entity;
    }

}
