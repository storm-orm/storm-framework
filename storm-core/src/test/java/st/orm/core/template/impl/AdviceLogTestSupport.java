package st.orm.core.template.impl;

import java.util.List;
import st.orm.Data;

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

    public static void checkDesign(List<Class<? extends Data>> types) {
        DesignCheck.check(types);
    }
}
