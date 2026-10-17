package st.orm.core.template.impl;

/**
 * Lets tests outside this package reset what the advice log reported, which is static and shared across the tests of
 * one JVM.
 */
public final class AdviceLogTestSupport {

    private AdviceLogTestSupport() {
    }

    public static void reset() {
        AdviceLog.reset();
    }
}
