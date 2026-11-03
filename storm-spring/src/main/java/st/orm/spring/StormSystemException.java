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
package st.orm.spring;

import org.springframework.dao.UncategorizedDataAccessException;

/**
 * Thrown by {@link SpringExceptionMapper} for a Storm failure that has no more specific counterpart in Spring's
 * {@code DataAccessException} hierarchy, such as a statement Storm refuses to run. The Storm exception is the cause.
 *
 * @since 1.16
 */
public class StormSystemException extends UncategorizedDataAccessException {

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause the Storm failure.
     */
    public StormSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
