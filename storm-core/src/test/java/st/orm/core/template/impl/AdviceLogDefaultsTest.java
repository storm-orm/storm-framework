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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The defaults the logs start with, read from classes initialized afresh, since the classes the test JVM shares
 * have been configured by other tests. Each class that reads its settings from the configuration in a static block
 * is checked here with no property set, so a default lost to the order of static initialization, a field declared
 * after the block that reads it, fails a test rather than every application.
 */
class AdviceLogDefaultsTest {

    /**
     * A class loader of its own over the test class path, so static initialization runs anew; the platform loader
     * is the parent, so the JDK's own classes are shared and everything else is loaded afresh.
     */
    private static ClassLoader fresh() throws Exception {
        var urls = new ArrayList<URL>();
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(entry).toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static Class<?> fresh(ClassLoader loader, Class<?> type) throws Exception {
        return Class.forName(type.getName(), true, loader);
    }

    private static Object call(Class<?> type, String method) throws Exception {
        return type.getMethod(method).invoke(null);
    }

    private static Object field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @Test
    void theAdviceLogStartsWithItsDefaults() throws Exception {
        for (var property : List.of("storm.advice.repeat_floor", "storm.advice.categories",
                "storm.advice.entity_budget.joins", "storm.advice.entity_budget.columns")) {
            assertNull(System.getProperty(property), property);
        }
        var adviceLog = fresh(fresh(), AdviceLog.class);
        assertEquals(Duration.ofMillis(50), call(adviceLog, "repeatFloor"));
        assertEquals(Duration.ofMillis(50), adviceLog.getField("DEFAULT_REPEAT_FLOOR").get(null));
        assertEquals(List.of("correctness"), call(adviceLog, "categories"));
        assertEquals(10, call(adviceLog, "entityBudgetJoins"));
        assertEquals(96, call(adviceLog, "entityBudgetColumns"));
    }

    @Test
    void theSlowStatementLogStartsWithItsDefaults() throws Exception {
        for (var property : List.of("storm.sql_log.slow.threshold", "storm.sql_log.slow.limit",
                "storm.sql_log.slow.transaction")) {
            assertNull(System.getProperty(property), property);
        }
        var slowLog = fresh(fresh(), SlowStatementLog.class);
        assertNull(call(slowLog, "threshold"));
        assertEquals(5, call(slowLog, "limit"));
        assertEquals(Duration.ofSeconds(1), call(slowLog, "transactionThreshold"));
    }

    @Test
    void theRendererAndTheCallSiteWalkerStartWithTheirDefaults() throws Exception {
        assertNull(System.getProperty("storm.sql_log.performance.line_width"));
        assertNull(System.getProperty("storm.sql_log.call_site_skip"));
        var loader = fresh();
        assertEquals(200, field(fresh(loader, SqlLogRenderer.class), "lineWidth"));
        assertEquals(0, ((String[]) field(fresh(loader, CallSiteCapture.class), "ignoredCallSitePrefixes")).length);
    }

    @Test
    void aPropertySetsTheRepeatFloor() throws Exception {
        System.setProperty("storm.advice.repeat_floor", "20ms");
        try {
            assertEquals(Duration.ofMillis(20), call(fresh(fresh(), AdviceLog.class), "repeatFloor"));
        } finally {
            System.clearProperty("storm.advice.repeat_floor");
        }
    }
}
