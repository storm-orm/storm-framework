package st.orm.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests the error path in {@link StormExtension} when a missing script is referenced.
 * This is tested indirectly by verifying the beforeAll behavior with reflection, since
 * annotating a class with a missing script would cause the test class itself to fail to load.
 */
class StormExtensionMissingScriptTest {

    @Test
    void readScriptWithMissingPathShouldThrowIllegalArgumentException() throws Exception {
        // The readScript method is private static, so we use reflection to test it.
        var method = StormExtension.class.getDeclaredMethod("readScript", Class.class, String.class);
        method.setAccessible(true);
        var exception = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(null, StormExtensionMissingScriptTest.class, "/nonexistent-script.sql"));
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Script not found on classpath"));
    }
}
