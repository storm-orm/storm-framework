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

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolationException;

/**
 * The translations of an integrity violation that also tell a web client what went wrong: each is the type a service
 * catches and an {@link ErrorResponse} with the status that says whose fault the violation is, which Spring MVC
 * applies when no handler of the application takes the exception.
 *
 * <p>A violation found before any statement ran, by {@code validateOrThrow} or the null check of a write, is a value
 * the client can correct: 400. So is a length, numeric range or check the database refused. A unique or foreign key
 * violation depends on rows the client cannot see: 409. A null the database refused passed Storm's own check, so the
 * field is nullable where the column is not; that is a server error, and it keeps the plain type, so it surfaces and
 * is logged as any unhandled failure.</p>
 *
 * <p>The client-facing detail names no table, constraint or statement. This class refers to spring-web and is only
 * loaded when it is on the classpath.</p>
 *
 * @since 1.16
 */
final class IntegrityViolationResponses {

    private IntegrityViolationResponses() {}

    static DataAccessException translated(IntegrityViolationException violation) {
        HttpStatus status = status(violation);
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return SpringExceptionMapper.plain(violation);
        }
        return violation.violations().getFirst() instanceof IntegrityViolation.Unique
                ? new DuplicateKeyResponse(violation, status)
                : new DataIntegrityViolationResponse(violation, status);
    }

    private static HttpStatus status(IntegrityViolationException exception) {
        if (exception.getCause() == null) {
            return HttpStatus.BAD_REQUEST;
        }
        IntegrityViolation violation = exception.violations().getFirst();
        if (violation instanceof IntegrityViolation.NotNull) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return violation instanceof IntegrityViolation.Unique
                || violation instanceof IntegrityViolation.ForeignKey
                || violation instanceof IntegrityViolation.Referenced
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
    }

    /**
     * Returns the body of the response: the status and a detail that says what kind of problem the request had. It is
     * kept on the exception, so a handler that adds the fields to it returns what it changed.
     */
    private static ProblemDetail body(HttpStatus status) {
        return ProblemDetail.forStatusAndDetail(status, status == HttpStatus.CONFLICT
                ? "The request conflicts with existing data."
                : "The request holds a value that is not accepted.");
    }

    private static final class DuplicateKeyResponse extends DuplicateKeyException implements ErrorResponse {
        private final HttpStatus status;
        private transient @Nullable ProblemDetail body;

        DuplicateKeyResponse(IntegrityViolationException violation, HttpStatus status) {
            super(violation.getMessage(), violation);
            this.status = status;
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public ProblemDetail getBody() {
            if (body == null) {
                body = body(status);
            }
            return body;
        }
    }

    private static final class DataIntegrityViolationResponse extends DataIntegrityViolationException
            implements ErrorResponse {
        private final HttpStatus status;
        private transient @Nullable ProblemDetail body;

        DataIntegrityViolationResponse(IntegrityViolationException violation, HttpStatus status) {
            super(violation.getMessage(), violation);
            this.status = status;
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public ProblemDetail getBody() {
            if (body == null) {
                body = body(status);
            }
            return body;
        }
    }
}
