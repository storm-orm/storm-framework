package st.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Tests for exception classes in storm-foundation: {@link PersistenceException},
 * {@link TransientPersistenceException}, {@link OptimisticLockException}, {@link NonUniqueResultException}, and
 * {@link NoResultException}.
 */
class ExceptionTest {

    // PersistenceException

    @Test
    void persistenceExceptionDefaultConstructor() {
        PersistenceException exception = new PersistenceException();
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void persistenceExceptionWithMessage() {
        PersistenceException exception = new PersistenceException("test message");
        assertEquals("test message", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void persistenceExceptionWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("root cause");
        PersistenceException exception = new PersistenceException("test message", cause);
        assertEquals("test message", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void persistenceExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        PersistenceException exception = new PersistenceException(cause);
        assertSame(cause, exception.getCause());
    }

    @Test
    void persistenceExceptionIsRuntimeException() {
        assertInstanceOf(RuntimeException.class, new PersistenceException());
    }

    // OptimisticLockException

    @Test
    void optimisticLockExceptionDefaultConstructor() {
        OptimisticLockException exception = new OptimisticLockException();
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
        assertNull(exception.getEntity());
    }

    @Test
    void optimisticLockExceptionWithMessage() {
        OptimisticLockException exception = new OptimisticLockException("lock conflict");
        assertEquals("lock conflict", exception.getMessage());
        assertNull(exception.getEntity());
    }

    @Test
    void optimisticLockExceptionWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("db error");
        OptimisticLockException exception = new OptimisticLockException("lock conflict", cause);
        assertEquals("lock conflict", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertNull(exception.getEntity());
    }

    @Test
    void optimisticLockExceptionWithCause() {
        RuntimeException cause = new RuntimeException("db error");
        OptimisticLockException exception = new OptimisticLockException(cause);
        assertSame(cause, exception.getCause());
        assertNull(exception.getEntity());
    }

    @Test
    void optimisticLockExceptionWithEntity() {
        Object entity = "test entity";
        OptimisticLockException exception = new OptimisticLockException(entity);
        assertSame(entity, exception.getEntity());
        assertNull(exception.getMessage());
    }

    @Test
    void optimisticLockExceptionWithMessageCauseAndEntity() {
        RuntimeException cause = new RuntimeException("db error");
        Object entity = "test entity";
        OptimisticLockException exception = new OptimisticLockException("lock conflict", cause, entity);
        assertEquals("lock conflict", exception.getMessage());
        assertSame(cause, exception.getCause());
        assertSame(entity, exception.getEntity());
    }

    @Test
    void optimisticLockExceptionIsPersistenceException() {
        assertInstanceOf(PersistenceException.class, new OptimisticLockException());
    }

    @Test
    void optimisticLockExceptionIsTransient() {
        assertInstanceOf(TransientPersistenceException.class, new OptimisticLockException());
    }

    // TransientPersistenceException

    @Test
    void transientPersistenceExceptionDefaultConstructor() {
        TransientPersistenceException exception = new TransientPersistenceException();
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void transientPersistenceExceptionWithMessage() {
        TransientPersistenceException exception = new TransientPersistenceException("deadlock");
        assertEquals("deadlock", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void transientPersistenceExceptionWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("root cause");
        TransientPersistenceException exception = new TransientPersistenceException("deadlock", cause);
        assertEquals("deadlock", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void transientPersistenceExceptionWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        TransientPersistenceException exception = new TransientPersistenceException(cause);
        assertSame(cause, exception.getCause());
    }

    @Test
    void transientPersistenceExceptionIsPersistenceException() {
        assertInstanceOf(PersistenceException.class, new TransientPersistenceException());
    }

    // NonUniqueResultException

    @Test
    void nonUniqueResultExceptionDefaultConstructor() {
        NonUniqueResultException exception = new NonUniqueResultException();
        assertNull(exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void nonUniqueResultExceptionWithCause() {
        Exception cause = new Exception("multiple results");
        NonUniqueResultException exception = new NonUniqueResultException(cause);
        assertSame(cause, exception.getCause());
    }

    @Test
    void nonUniqueResultExceptionWithMessage() {
        NonUniqueResultException exception = new NonUniqueResultException("expected one result");
        assertEquals("expected one result", exception.getMessage());
    }

    @Test
    void nonUniqueResultExceptionWithMessageAndCause() {
        Exception cause = new Exception("multiple results");
        NonUniqueResultException exception = new NonUniqueResultException("expected one result", cause);
        assertEquals("expected one result", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void nonUniqueResultExceptionIsPersistenceException() {
        assertInstanceOf(PersistenceException.class, new NonUniqueResultException());
    }

    // NoResultException

    @Test
    void noResultExceptionDefaultConstructor() {
        NoResultException exception = new NoResultException();
        assertNull(exception.getMessage());
    }

    @Test
    void noResultExceptionWithMessage() {
        NoResultException exception = new NoResultException("no result found");
        assertEquals("no result found", exception.getMessage());
    }

    @Test
    void noResultExceptionIsPersistenceException() {
        assertInstanceOf(PersistenceException.class, new NoResultException());
    }
}
