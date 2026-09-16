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

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the refusal of a KSP that does not pair with the project's Kotlin version. Kotlin 2.1 pairs with
 * its own KSP builds, and the bundled KSP reaches such a project in two ways: declared in the plugins block,
 * or inherited by a subproject from a root classpath that carries {@code st.orm}. Both end in the same
 * instruction. These tests resolve the plugin from mavenLocal (published by the functionalTest task), as
 * {@link KspAutoApplyFunctionalTest} does, so every plugin lands in one classloader scope as in a regular
 * build; the checks run at configuration time, so no Storm artifact is resolved.
 */
public class KspMismatchFunctionalTest {

    private static final String PAIRED_LINE = "id(\"com.google.devtools.ksp\") version \"2.1.21-2.0.2\"";

    @TempDir
    Path projectDir;

    private static String pluginVersion() {
        return System.getProperty("storm.plugin.version");
    }

    private BuildResult run(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .build();
    }

    private BuildResult fail(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .buildAndFail();
    }

    private static void assertRefused(String output) {
        assertTrue(output.contains("Storm: KSP 2.3.10 does not pair with Kotlin 2.1.21, which pairs with KSP 2.1.21-2.0.2."),
                "Expected the mismatch to be named:\n" + output);
        assertTrue(output.contains(PAIRED_LINE), "Expected the copy-pasteable paired KSP line:\n" + output);
        assertTrue(output.contains("apply false"), "Expected the multi-project instruction:\n" + output);
        assertTrue(output.contains("storm { metamodel.set(false) }"), output);
    }

    /**
     * Writes a root project with the given root plugins block and one subproject, {@code app}, that applies
     * the Kotlin JVM plugin, KSP and {@code st.orm} without versions, so every version comes from the root.
     */
    private void writeMultiProject(String rootPlugins) throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"),
                FunctionalTestSupport.SETTINGS_MAVEN_LOCAL + "include(\"app\")\n");
        Files.writeString(projectDir.resolve("build.gradle.kts"), rootPlugins);
        var app = projectDir.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("build.gradle.kts"), """
                plugins {
                    id("org.jetbrains.kotlin.jvm")
                    id("com.google.devtools.ksp")
                    id("st.orm")
                }
                """ + FunctionalTestSupport.DUMP_TASK);
    }

    @Test
    public void aDeclaredKspThatDoesNotPairWithTheKotlinVersionIsRefused() throws Exception {
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.1.21"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm") version "%s"
                }
                """.formatted(pluginVersion()));
        assertRefused(fail("help", "-q").getOutput());
    }

    @Test
    public void aSubprojectInheritingTheBundledKspIsRefusedOnAKotlinLineThatPairsWithItsOwnKsp() throws Exception {
        // st.orm on the root classpath carries the bundled KSP, and the subproject's version-less KSP
        // application resolves to it: the build used to run the wrong KSP without a word.
        writeMultiProject("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
                    id("st.orm") version "%s" apply false
                }
                """.formatted(pluginVersion()));
        assertRefused(fail(":app:help", "-q").getOutput());
    }

    @Test
    public void thePairedKspDeclaredOnceInTheRootPluginsBlockWinsTheSubprojectClasspath() throws Exception {
        // The instruction the refusal gives: a declared version beats the bundled preference, so the
        // subproject inherits the paired KSP and the metamodel processor is wired into it.
        writeMultiProject("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false
                    id("com.google.devtools.ksp") version "2.1.21-2.0.2" apply false
                    id("st.orm") version "%s" apply false
                }
                """.formatted(pluginVersion()));
        var output = run(":app:stormDump", "-q").getOutput();
        assertTrue(output.contains("DEP ksp st.orm:storm-metamodel-ksp:"),
                "Expected the metamodel processor on the subproject's paired KSP:\n" + output);
        assertTrue(output.contains("DEP kotlinCompilerPluginClasspath st.orm:storm-compiler-plugin-2.1:"), output);
        assertFalse(output.contains("does not pair"), output);
    }

    @Test
    public void aNewerKspPatchOnTheSameLineIsAccepted() throws Exception {
        // The pairing is decided per Kotlin line, not per recommended patch: a build that moves KSP ahead
        // of the bundled version keeps working.
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.11"
                    id("st.orm") version "%s"
                }
                """.formatted(pluginVersion()));
        var output = run("stormDump", "-q").getOutput();
        assertTrue(output.contains("DEP ksp st.orm:storm-metamodel-ksp:"), output);
    }

    @Test
    public void metamodelOptOutLeavesTheKspChoiceToTheBuild() throws Exception {
        // Without the metamodel processor Storm runs nothing inside KSP, so which KSP the build resolved
        // is not Storm's to refuse.
        FunctionalTestSupport.writeProject(projectDir, FunctionalTestSupport.SETTINGS_MAVEN_LOCAL, """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.1.21"
                    id("com.google.devtools.ksp") version "2.3.10"
                    id("st.orm") version "%s"
                }
                storm {
                    metamodel.set(false)
                }
                """.formatted(pluginVersion()));
        var output = run("stormDump", "-q").getOutput();
        assertTrue(output.contains("DEP implementation st.orm:storm-kotlin:"), output);
        assertFalse(output.contains("storm-metamodel-ksp"), output);
    }
}
