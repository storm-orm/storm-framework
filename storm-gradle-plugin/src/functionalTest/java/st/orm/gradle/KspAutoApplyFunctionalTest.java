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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the automatic KSP application. These tests resolve the plugin from mavenLocal (published by
 * the functionalTest task) rather than TestKit's injected classpath: the injected classpath is its own
 * classloader scope, in which the bundled KSP cannot link against the Kotlin Gradle plugin, so the
 * automatic application deliberately stands down there.
 */
public class KspAutoApplyFunctionalTest {

    @TempDir
    Path projectDir;

    private static String pluginBlock() {
        return """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("st.orm") version "%s"
                }
                """.formatted(System.getProperty("storm.plugin.version"));
    }

    @Test
    public void kotlinWithoutKspGetsTheBundledKspApplied() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, pluginBlock());
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("stormDump", "-q")
                .build();
        // The ksp configuration only exists once the KSP plugin is applied, so the wired metamodel
        // processor proves both the automatic application and the processor wiring.
        assertTrue(result.getOutput().contains("DEP ksp st.orm:storm-metamodel-ksp:"),
                "Expected the auto-applied KSP plugin to carry the metamodel processor:\n" + result.getOutput());
    }

    @Test
    public void optOutKeepsTheInstructiveFailure() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, pluginBlock());
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("help", "-q", "-Pstorm.autoApplyKsp=false")
                .buildAndFail();
        assertTrue(result.getOutput().contains("id(\"com.google.devtools.ksp\") version \"2.3.10\""),
                "Expected the copy-pasteable KSP plugin line:\n" + result.getOutput());
        assertTrue(result.getOutput().contains("storm { metamodel.set(false) }"));
    }

    @Test
    public void metamodelOptOutRemovesTheProcessor() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, pluginBlock() + """
                storm {
                    metamodel.set(false)
                }
                """);
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("stormDump", "-q")
                .build();
        assertTrue(result.getOutput().contains("DEP implementation st.orm:storm-kotlin:"));
        // KSP is still applied, but the metamodel opt-out leaves its configurations empty, so KSP has
        // nothing to run.
        assertFalse(result.getOutput().contains("DEP ksp st.orm:storm-metamodel-ksp:"),
                "Expected no metamodel processor with metamodel.set(false):\n" + result.getOutput());
    }

    @Test
    public void explicitKspStaysAuthoritative() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm") version "%s"
                }
                """.formatted(System.getProperty("storm.plugin.version")));
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("stormDump", "-q")
                .build();
        assertTrue(result.getOutput().contains("DEP ksp st.orm:storm-metamodel-ksp:"),
                "Expected the metamodel processor with an explicitly applied KSP:\n" + result.getOutput());
    }
}
