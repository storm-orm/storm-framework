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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class KotlinPathFunctionalTest {

    @TempDir
    Path projectDir;

    private String runDump(String buildScript) throws Exception {
        // Resolved from mavenLocal rather than TestKit's injected classpath: with the bundled KSP jar,
        // classpath injection puts KSP in a classloader scope that cannot link against the Kotlin Gradle
        // plugin, so the Kotlin path only works with all plugins resolved into one scope, as in a
        // regular build.
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL,
                buildScript.replace("id(\"st.orm\")",
                        "id(\"st.orm\") version \"" + System.getProperty("storm.plugin.version") + "\""));
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("stormDump", "-q")
                .build();
        return result.getOutput();
    }

    @ParameterizedTest
    @CsvSource({
            "2.0.21, 2.0.21-1.0.28, 2.0",
            "2.4.0, 2.3.10, 2.4",
    })
    public void wiresTheKotlinPathWithTheMatchingVariant(String kotlinVersion, String kspVersion, String variant)
            throws Exception {
        var output = runDump("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "%s"
                    id("com.google.devtools.ksp") version "%s"
                    id("st.orm")
                }
                """.formatted(kotlinVersion, kspVersion));
        assertTrue(output.contains("DEP implementation st.orm:storm-bom:"));
        assertTrue(output.contains("DEP implementation st.orm:storm-kotlin:"));
        assertTrue(output.contains("DEP runtimeOnly st.orm:storm-core:"));
        assertTrue(output.contains("DEP ksp st.orm:storm-metamodel-ksp:"));
        assertTrue(output.contains("DEP kotlinCompilerPluginClasspath st.orm:storm-compiler-plugin-" + variant + ":"),
                "Expected compiler-plugin variant " + variant + " for Kotlin " + kotlinVersion + ":\n" + output);
        assertFalse(output.contains("storm-java21:"), "The Kotlin path must not add storm-java21.");
        assertFalse(output.contains("storm-metamodel-processor"),
                "The Kotlin path must not add the Java annotation processor.");
        assertFalse(output.contains("-enable-preview"), "Preview flags are Java-path only.");
        assertTrue(output.contains("JAVADOC javadoc source=null preview=false"),
                "The Kotlin path must leave the javadoc options alone:\n" + output);
    }

    @Test
    public void wiresTheProcessorIntoEverySourceSetsKspConfiguration() throws Exception {
        var output = runDump("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm")
                }
                sourceSets.create("integration")
                """);
        assertTrue(output.contains("DEP ksp st.orm:storm-metamodel-ksp:"));
        assertTrue(output.contains("DEP kspTest st.orm:storm-metamodel-ksp:"),
                "Entities declared in test sources need the processor too:\n" + output);
        assertTrue(output.contains("DEP kspIntegration st.orm:storm-metamodel-ksp:"),
                "Custom source sets need the processor too:\n" + output);
    }

    @Test
    public void compilerPluginVariantOverrideWins() throws Exception {
        var output = runDump("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm")
                }
                storm {
                    compilerPluginVariant.set("2.3")
                }
                """);
        assertTrue(output.contains("DEP kotlinCompilerPluginClasspath st.orm:storm-compiler-plugin-2.3:"));
    }

    @Test
    public void metamodelCanBeDisabledWithKspApplied() throws Exception {
        var output = runDump("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm")
                }
                storm {
                    metamodel.set(false)
                }
                """);
        assertFalse(output.contains("storm-metamodel-ksp"));
        assertTrue(output.contains("DEP implementation st.orm:storm-kotlin:"));
    }

    @Test
    public void compilerPluginCanBeDisabled() throws Exception {
        var output = runDump("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm")
                }
                storm {
                    compilerPlugin.set(false)
                }
                """);
        assertFalse(output.contains("storm-compiler-plugin-"));
        assertTrue(output.contains("DEP ksp st.orm:storm-metamodel-ksp:"));
    }
}
