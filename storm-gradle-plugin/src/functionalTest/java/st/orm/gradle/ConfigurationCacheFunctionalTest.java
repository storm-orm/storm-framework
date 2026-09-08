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
package st.orm.gradle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds with {@code --configuration-cache} on both language paths, backing the compatibility declared in
 * the plugin metadata. Configuration-cache problems fail the build by default, so a successful first run
 * proves the plugin's configuration-time wiring is clean, and the second run proves the serialized task
 * graph round-trips: it must reuse the entry and replay the dump captured at configuration time.
 */
public class ConfigurationCacheFunctionalTest {

    @TempDir
    Path projectDir;

    private GradleRunner runner() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("stormDump", "--configuration-cache");
    }

    private GradleRunner mavenLocalRunner() {
        // No injected classpath: the Kotlin path resolves the plugin from mavenLocal so that KSP and the
        // Kotlin Gradle plugin share one classloader scope, as in a regular build.
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("stormDump", "--configuration-cache");
    }

    @Test
    public void javaPathStoresAndReusesTheConfigurationCache() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, """
                plugins {
                    java
                    id("st.orm")
                }
                """);
        var first = runner().build();
        assertTrue(first.getOutput().contains("Configuration cache entry stored."), first.getOutput());
        assertTrue(first.getOutput().contains("DEP implementation st.orm:storm-bom:"));
        assertTrue(first.getOutput().contains("DEP implementation st.orm:storm-java21:"));
        assertTrue(first.getOutput().contains("DEP annotationProcessor st.orm:storm-metamodel-processor:"));
        assertTrue(first.getOutput().contains("--enable-preview"), "Expected --enable-preview on the Java path.");
        var second = runner().build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertTrue(second.getOutput().contains("DEP implementation st.orm:storm-java21:"),
                "The cached run must replay the dependencies captured at configuration time.");
        assertTrue(second.getOutput().contains("--enable-preview"),
                "The cached run must replay the preview flags captured at configuration time.");
    }

    @Test
    public void kotlinPathStoresAndReusesTheConfigurationCache() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm") version "%s"
                }
                """.formatted(System.getProperty("storm.plugin.version")));
        var first = mavenLocalRunner().build();
        assertTrue(first.getOutput().contains("Configuration cache entry stored."), first.getOutput());
        assertTrue(first.getOutput().contains("DEP implementation st.orm:storm-kotlin:"));
        assertTrue(first.getOutput().contains("DEP ksp st.orm:storm-metamodel-ksp:"));
        assertTrue(first.getOutput().contains("DEP kotlinCompilerPluginClasspath st.orm:storm-compiler-plugin-"));
        var second = mavenLocalRunner().build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertTrue(second.getOutput().contains("DEP implementation st.orm:storm-kotlin:"),
                "The cached run must replay the dependencies captured at configuration time.");
    }
}
