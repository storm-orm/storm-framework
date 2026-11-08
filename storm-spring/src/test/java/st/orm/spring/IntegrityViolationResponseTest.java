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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import st.orm.Data;
import st.orm.Entity;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolation.Check;
import st.orm.IntegrityViolation.ForeignKey;
import st.orm.IntegrityViolation.Length;
import st.orm.IntegrityViolation.NotNull;
import st.orm.IntegrityViolation.NumericRange;
import st.orm.IntegrityViolation.Referenced;
import st.orm.IntegrityViolation.Unique;
import st.orm.IntegrityViolationException;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.spi.ExceptionContext;
import st.orm.spi.ExceptionMapper;
import st.orm.spi.SqlOperation;

/**
 * An integrity violation that no handler of the application takes answers the client with the status that says whose
 * fault it is, on the types a service catches.
 */
class IntegrityViolationResponseTest {

    record Owner(@PK Integer id, String telephone) implements Entity<Integer> {}

    private static final Metamodel<Owner, String> TELEPHONE = Metamodel.of(Owner.class, "telephone");
    private static final List<Metamodel<?, ?>> FIELDS = List.of(TELEPHONE);
    private static final SQLException REFUSED = new SQLException("refused", "23000");

    @RestController
    static class FailingController {
        private final RuntimeException failure;

        FailingController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/fail")
        String fail() {
            throw failure;
        }
    }

    /** The handler the documentation shows for rendering the fields. */
    @RestControllerAdvice
    static class FieldErrorAdvice {
        @ExceptionHandler(DataIntegrityViolationException.class)
        ProblemDetail handle(DataIntegrityViolationException exception) {
            if (!(exception instanceof ErrorResponse response)
                    || !(exception.getCause() instanceof IntegrityViolationException cause)) {
                throw exception;
            }
            ProblemDetail problem = response.getBody();
            problem.setProperty("errors", cause.violations().stream()
                    .map(violation -> Map.of(
                            "fields", violation.fields().stream().map(Metamodel::fieldPath).toList(),
                            "kind", violation.getClass().getSimpleName()))
                    .toList());
            return problem;
        }
    }

    private static final ExceptionContext CONTEXT = new ExceptionContext() {
        @Override
        public SqlOperation operation() {
            return SqlOperation.INSERT;
        }

        @Override
        public Optional<String> statement() {
            return Optional.empty();
        }

        @Override
        public Optional<Class<? extends Data>> dataType() {
            return Optional.empty();
        }

        @Override
        public Optional<String> transactionDescription() {
            return Optional.empty();
        }
    };

    private static RuntimeException translated(ExceptionMapper mapper, IntegrityViolationException failure) {
        return mapper.map(failure, CONTEXT);
    }

    private static RuntimeException translated(IntegrityViolationException failure) {
        return translated(new SpringExceptionMapper(), failure);
    }

    private static IntegrityViolationException reported(IntegrityViolation violation) {
        return new IntegrityViolationException("Refused in secret_table (secret_uk): INSERT", List.of(violation), REFUSED);
    }

    private static IntegrityViolationException foundFirst(IntegrityViolation... violations) {
        return new IntegrityViolationException("Does not fit", List.of(violations));
    }

    private static MockMvc mvc(RuntimeException failure, Object... advice) {
        return MockMvcBuilders.standaloneSetup(new FailingController(failure)).setControllerAdvice(advice).build();
    }

    private static void assertStatus(int status, IntegrityViolationException failure) throws Exception {
        var response = mvc(translated(failure)).perform(get("/fail")).andExpect(status().is(status)).andReturn().getResponse();
        String answered = response.getErrorMessage() + response.getContentAsString();
        assertFalse(answered.contains("secret"), answered);
        assertFalse(answered.contains("INSERT"), answered);
    }

    private static Unique unique() {
        return new Unique("secret_uk", "secret_table", Owner.class, FIELDS);
    }

    @Test
    void aViolationFoundBeforeAnyStatementAnswers400() throws Exception {
        assertStatus(400, foundFirst(new Length("secret_table", Owner.class, FIELDS, 20),
                new NotNull("secret_table", Owner.class, FIELDS)));
        assertStatus(400, foundFirst(new NotNull("secret_table", Owner.class, FIELDS)));
    }

    @Test
    void aValueTheDatabaseRefusedAnswers400() throws Exception {
        assertStatus(400, reported(new Length("secret_table", Owner.class, FIELDS, null)));
        assertStatus(400, reported(new NumericRange("secret_table", Owner.class, FIELDS, null, null)));
        assertStatus(400, reported(new Check("secret_uk", "secret_table", Owner.class)));
    }

    @Test
    void aConflictWithOtherRowsAnswers409() throws Exception {
        assertStatus(409, reported(unique()));
        assertStatus(409, reported(new ForeignKey("secret_uk", "secret_table", Owner.class, FIELDS)));
        assertStatus(409, reported(new Referenced("secret_uk", "secret_table", Owner.class)));
    }

    @Test
    void theBodyIsKeptSoAHandlerReturnsWhatItChanged() {
        var response = assertInstanceOf(ErrorResponse.class, translated(reported(unique())));
        assertSame(response.getBody(), response.getBody());
        assertEquals(409, response.getBody().getStatus());
        var other = assertInstanceOf(ErrorResponse.class,
                translated(reported(new Check("secret_uk", "secret_table", Owner.class))));
        assertSame(other.getBody(), other.getBody());
        assertEquals(400, other.getBody().getStatus());
    }

    @Test
    void aServiceCatchesTheSameTypes() {
        var duplicate = assertInstanceOf(DuplicateKeyException.class, translated(reported(unique())));
        assertInstanceOf(ErrorResponse.class, duplicate);
        assertInstanceOf(IntegrityViolationException.class, duplicate.getCause());
        var other = translated(reported(new Check("secret_uk", "secret_table", Owner.class)));
        assertInstanceOf(DataIntegrityViolationException.class, other);
        assertInstanceOf(ErrorResponse.class, other);
    }

    @Test
    void aNullTheDatabaseRefusedSurfacesAsAServerError() {
        var failure = reported(new NotNull("secret_table", Owner.class, FIELDS));
        var translated = translated(failure);
        assertEquals(DataIntegrityViolationException.class, translated.getClass());
        var thrown = assertThrows(ServletException.class, () -> mvc(translated).perform(get("/fail")));
        assertSame(translated, thrown.getCause());
    }

    @Test
    void aHandlerOfTheApplicationWinsAndRendersTheFields() throws Exception {
        mvc(translated(reported(unique())), new FieldErrorAdvice())
                .perform(get("/fail").accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data."))
                .andExpect(jsonPath("$.errors[0].fields[0]").value("telephone"))
                .andExpect(jsonPath("$.errors[0].kind").value("Unique"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    /**
     * Loads the mapper on a class loader that has no spring-web, as in an application without a web layer.
     */
    private static final class NoWebClassLoader extends ClassLoader {
        NoWebClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.springframework.web.")) {
                throw new ClassNotFoundException(name);
            }
            if (name.startsWith(SpringExceptionMapper.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : define(name);
                }
            }
            return super.loadClass(name, resolve);
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length, SpringExceptionMapper.class.getProtectionDomain());
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    @Test
    void withoutSpringWebTheTypesLoadAndCarryNoStatus() throws Exception {
        var loader = new NoWebClassLoader(SpringExceptionMapper.class.getClassLoader());
        var mapper = (ExceptionMapper) loader.loadClass(SpringExceptionMapper.class.getName())
                .getConstructor().newInstance();
        assertEquals(DuplicateKeyException.class, translated(mapper, reported(unique())).getClass());
        assertEquals(DataIntegrityViolationException.class,
                translated(mapper, foundFirst(new NotNull(null, Owner.class, FIELDS))).getClass());
    }
}
