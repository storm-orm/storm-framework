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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers for TestKit projects. The generated build script registers a {@code stormDump} task that queries
 * the declared dependencies (triggering the plugin's dependency callbacks without resolving artifacts) and
 * prints the compiler argument providers and javadoc options, so assertions run offline and fast. The
 * javadoc options added through {@code addBooleanOption} have no public read accessor, so the dump reads the
 * task's option file reflectively; {@code JavadocOptionFile.getOptions()} has had this shape since well
 * before the plugin's minimum Gradle version. The dump is captured at configuration time and the task action
 * only replays the captured lines, so the task itself is configuration-cache compatible and the same
 * assertions hold on a cache-reusing run.
 */
final class FunctionalTestSupport {

    static final String SETTINGS = """
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            rootProject.name = "storm-test-project"
            """;

    /**
     * Settings for tests that resolve the plugin from mavenLocal (published by the functionalTest task)
     * instead of TestKit's injected classpath, so every plugin lands in one classloader scope, as in a
     * regular build.
     */
    static final String SETTINGS_MAVEN_LOCAL = """
            pluginManagement {
                repositories {
                    // Only the plugin under test: a Maven build leaves POM-only Kotlin artifacts in
                    // ~/.m2, and without Gradle module metadata the Kotlin Gradle plugin resolves to a
                    // variant for the wrong Gradle version.
                    mavenLocal {
                        content {
                            includeGroup("st.orm")
                        }
                    }
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            rootProject.name = "storm-test-project"
            """;

    static final String DUMP_TASK = """

            tasks.register("stormDump") {
                val lines = buildList {
                    val processorNames = configurations.names.filter { name ->
                        name == "annotationProcessor" || name.endsWith("AnnotationProcessor") ||
                            name == "ksp" || (name.startsWith("ksp") && name.length > 3 && name[3].isUpperCase())
                    }.sorted()
                    (listOf("implementation", "runtimeOnly", "kotlinCompilerPluginClasspath") + processorNames).forEach { name ->
                        configurations.findByName(name)?.incoming?.dependencies?.forEach { d ->
                            add("DEP $name ${d.group}:${d.name}:${d.version}")
                        }
                    }
                    tasks.withType(JavaCompile::class).forEach { t ->
                        add("ARGS ${t.name} " + t.options.compilerArgumentProviders.flatMap { it.asArguments() })
                    }
                    tasks.withType(Javadoc::class).forEach { t ->
                        val options = t.options as CoreJavadocOptions
                        val optionFileField = CoreJavadocOptions::class.java.getDeclaredField("optionFile")
                        optionFileField.isAccessible = true
                        val optionFile = optionFileField.get(options)
                        @Suppress("UNCHECKED_CAST")
                        val names = (optionFile.javaClass.getMethod("getOptions").invoke(optionFile) as Map<String, *>).keys
                        add("JAVADOC ${t.name} source=${options.source} preview=${names.contains("-enable-preview")}")
                    }
                }
                doLast {
                    lines.forEach { println(it) }
                }
            }
            """;

    private FunctionalTestSupport() {
    }

    static void writeProject(Path directory, String buildScript) throws IOException {
        writeProject(directory, SETTINGS, buildScript);
    }

    static void writeProject(Path directory, String settings, String buildScript) throws IOException {
        Files.writeString(directory.resolve("settings.gradle.kts"), settings);
        Files.writeString(directory.resolve("build.gradle.kts"), buildScript + DUMP_TASK);
    }
}
