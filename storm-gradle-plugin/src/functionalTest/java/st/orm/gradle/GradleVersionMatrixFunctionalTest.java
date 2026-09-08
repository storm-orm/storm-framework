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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Backs the declared Gradle 8.5+ support with pinned distributions: the declared minimum and the last of the
 * 8.x line. The current version is exercised by every other functional test, which runs on the wrapper's
 * distribution. TestKit downloads the pinned distributions on first use and caches them under the Gradle
 * user home.
 */
public class GradleVersionMatrixFunctionalTest {

    @TempDir
    Path projectDir;

    private String runDump(String gradleVersion, String buildScript) throws Exception {
        FunctionalTestSupport.writeProject(projectDir, buildScript);
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withGradleVersion(gradleVersion)
                .withArguments("stormDump", "-q")
                .build();
        return result.getOutput();
    }

    private String runDumpFromMavenLocal(String gradleVersion, String buildScript) throws Exception {
        // No injected classpath: the Kotlin path resolves the plugin from mavenLocal so that KSP and the
        // Kotlin Gradle plugin share one classloader scope, as in a regular build.
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL,
                buildScript.replace("id(\"st.orm\")",
                        "id(\"st.orm\") version \"" + System.getProperty("storm.plugin.version") + "\""));
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withGradleVersion(gradleVersion)
                .withArguments("stormDump", "-q")
                .build();
        return result.getOutput();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.5", "8.14.3"})
    public void javaPathWiresOnGradle(String gradleVersion) throws Exception {
        var output = runDump(gradleVersion, """
                plugins {
                    java
                    id("st.orm")
                }
                """);
        assertTrue(output.contains("DEP implementation st.orm:storm-bom:"), output);
        assertTrue(output.contains("DEP implementation st.orm:storm-java21:"), output);
        assertTrue(output.contains("DEP annotationProcessor st.orm:storm-metamodel-processor:"), output);
        assertTrue(output.contains("DEP testAnnotationProcessor st.orm:storm-metamodel-processor:"), output);
        assertTrue(output.contains("--enable-preview"), output);
        assertTrue(output.contains("JAVADOC javadoc source=21 preview=true"), output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.5", "8.14.3"})
    public void kotlinPathWiresOnGradle(String gradleVersion) throws Exception {
        // Kotlin 2.0.21: the KGP line whose supported Gradle range covers the plugin's minimum.
        var output = runDumpFromMavenLocal(gradleVersion, """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.0.21"
                    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
                    id("st.orm")
                }
                """);
        assertTrue(output.contains("DEP implementation st.orm:storm-kotlin:"), output);
        assertTrue(output.contains("DEP ksp st.orm:storm-metamodel-ksp:"), output);
        assertTrue(output.contains("DEP kspTest st.orm:storm-metamodel-ksp:"), output);
        assertTrue(output.contains("DEP kotlinCompilerPluginClasspath st.orm:storm-compiler-plugin-2.0:"), output);
    }
}
