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

import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles real Kotlin and Java projects against the locally installed Storm snapshot artifacts and asserts
 * the generated metamodel exists, for entities in main and test sources, and runs real javadoc against the
 * preview class files. The two main-source builds run with {@code --configuration-cache} and run twice, so
 * the full task graph (KSP respectively the annotation processor and the preview-flag argument providers) is
 * proven to serialize and be reusable. Requires a prior {@code mvn install -DskipTests} of the reactor;
 * gated behind {@code -Dstorm.smoke=true}. The Kotlin projects resolve the plugin from mavenLocal
 * (published by the functionalTest task) rather than TestKit's injected classpath: the injected classpath
 * is its own classloader scope, in which the bundled KSP cannot link against the Kotlin Gradle plugin.
 */
public class SmokeCompileTest {

    @TempDir
    Path projectDir;

    private GradleRunner runner() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("build", "-x", "test", "--configuration-cache");
    }

    private GradleRunner mavenLocalRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments);
    }

    private static String stormPluginLine() {
        return "id(\"st.orm\") version \"" + System.getProperty("storm.plugin.version") + "\"";
    }

    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void compilesAKotlinEntityAndGeneratesTheMetamodel() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS_MAVEN_LOCAL);
        // No KSP line: this build proves the automatically applied KSP carries a real compile.
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    %s
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                kotlin {
                    jvmToolchain(21)
                }
                """.formatted(stormPluginLine()));
        var sourceDir = projectDir.resolve("src/main/kotlin/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("City.kt"), """
                package demo

                import st.orm.Entity
                import st.orm.PK

                data class City(
                    @PK val id: Int = 0,
                    val name: String,
                ) : Entity<Int>
                """);
        var result = mavenLocalRunner("build", "-x", "test", "--configuration-cache").build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertTrue(result.getOutput().contains("Configuration cache entry stored."), result.getOutput());
        try (var generated = Files.walk(projectDir.resolve("build/generated/ksp"))) {
            assertTrue(generated.anyMatch(path -> path.getFileName().toString().startsWith("City_")),
                    "Expected a generated City_ metamodel under build/generated/ksp.");
        }
        var second = mavenLocalRunner("build", "-x", "test", "--configuration-cache").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
    }

    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void compilesAJavaEntityAndGeneratesTheMetamodel() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("st.orm")
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """);
        var sourceDir = projectDir.resolve("src/main/java/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("City.java"), """
                package demo;

                import st.orm.Entity;
                import st.orm.PK;

                public record City(@PK Integer id, String name) implements Entity<Integer> {
                }
                """);
        var result = runner().build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertTrue(result.getOutput().contains("Configuration cache entry stored."), result.getOutput());
        try (var generated = Files.walk(projectDir.resolve("build/generated/sources/annotationProcessor"))) {
            assertTrue(generated.anyMatch(path -> path.getFileName().toString().startsWith("City_")),
                    "Expected a generated City_ metamodel under build/generated/sources/annotationProcessor.");
        }
        var second = runner().build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
    }

    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void generatesTheMetamodelForKotlinTestSourceEntities() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS_MAVEN_LOCAL);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    %s
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                kotlin {
                    jvmToolchain(21)
                }
                """.formatted(stormPluginLine()));
        var sourceDir = projectDir.resolve("src/test/kotlin/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("City.kt"), """
                package demo

                import st.orm.Entity
                import st.orm.PK

                data class City(
                    @PK val id: Int = 0,
                    val name: String,
                ) : Entity<Int>
                """);
        var result = mavenLocalRunner("testClasses").build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        try (var generated = Files.walk(projectDir.resolve("build/generated/ksp/test"))) {
            assertTrue(generated.anyMatch(path -> path.getFileName().toString().startsWith("City_")),
                    "Expected a generated City_ metamodel under build/generated/ksp/test.");
        }
    }

    /**
     * A capture whose action ends in a lookup that finds nothing is the ordinary case in a test, so the result
     * type of {@code execute} and {@code executeThrowing} has to admit null. Strict JSpecify enforcement starts
     * at Kotlin 2.1, which the reactor's own Kotlin 2.0 modules never exercise, and storm-test's own tests are
     * written in Java, where a non-null type parameter compiles regardless; this is the surface that sees it.
     */
    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void kotlinCapturesAnActionThatReturnsNoRow() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS_MAVEN_LOCAL);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    %s
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                kotlin {
                    jvmToolchain(21)
                }
                dependencies {
                    testImplementation("st.orm:storm-test")
                }
                """.formatted(stormPluginLine()));
        var sourceDir = projectDir.resolve("src/test/kotlin/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Capture.kt"), """
                package demo

                import st.orm.test.SqlCapture

                fun firstName(capture: SqlCapture, names: List<String>): String? =
                    capture.execute { names.firstOrNull() }

                fun firstNameOrThrow(capture: SqlCapture, names: List<String>): String? =
                    capture.executeThrowing { names.firstOrNull() }
                """);
        var result = mavenLocalRunner("testClasses").build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
    }

    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void generatesTheMetamodelForJavaTestSourceEntities() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("st.orm")
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """);
        var sourceDir = projectDir.resolve("src/test/java/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("City.java"), """
                package demo;

                import st.orm.Entity;
                import st.orm.PK;

                public record City(@PK Integer id, String name) implements Entity<Integer> {
                }
                """);
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("testClasses")
                .build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        try (var generated = Files.walk(projectDir.resolve("build/generated/sources/annotationProcessor"))) {
            assertTrue(generated.anyMatch(path -> path.getFileName().toString().startsWith("City_")),
                    "Expected a generated City_ metamodel under build/generated/sources/annotationProcessor.");
        }
    }

    /**
     * Runs real javadoc against a source whose signature references storm-java21's ORMTemplate: javadoc
     * embeds the javac front end, which rejects the preview class files unless the plugin passes
     * {@code --enable-preview} to the javadoc tool as well.
     */
    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void javadocRunsAgainstThePreviewClassFiles() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("st.orm")
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """);
        var sourceDir = projectDir.resolve("src/main/java/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("CityService.java"), """
                package demo;

                import st.orm.template.ORMTemplate;

                /**
                 * References storm-java21's preview class files in its signature.
                 */
                public record CityService(ORMTemplate orm) {
                }
                """);
        var result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("javadoc")
                .build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        assertTrue(Files.exists(projectDir.resolve("build/docs/javadoc/index.html")),
                "Expected generated javadoc output.");
    }

    /**
     * A converted column is stored as one type and held as another: the tags live in a single JSON column, so the
     * column is text while the record holds a list. Both generators have to address the column by its stored type and
     * keep the declared type for the value; getting that wrong produces a metamodel that does not compile, which the
     * build itself catches, or one that cannot express a predicate against the stored value, which the assertion
     * catches. A reference is declared alongside it so the reference metamodel is compiled here too.
     */
    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void kotlinAddressesAConvertedColumnByItsStoredType() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS_MAVEN_LOCAL);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.4.0"
                    id("com.google.devtools.ksp") version "2.3.10"
                    %s
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                kotlin {
                    jvmToolchain(21)
                }
                """.formatted(stormPluginLine()));
        var sourceDir = projectDir.resolve("src/main/kotlin/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Gallery.kt"), """
                package demo

                import st.orm.Entity
                import st.orm.FK
                import st.orm.Json
                import st.orm.PK
                import st.orm.Ref

                data class City(
                    @PK val id: Int = 0,
                    val name: String,
                ) : Entity<Int>

                data class Gallery(
                    @PK val id: Int = 0,
                    @Json val tags: List<String>,
                    @FK val city: Ref<City>,
                ) : Entity<Int>
                """);
        var result = mavenLocalRunner("build", "-x", "test", "--configuration-cache").build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        var metamodel = Files.readString(projectDir.resolve("build/generated/ksp/main/kotlin/demo/Gallery_.kt"));
        assertTrue(metamodel.contains("AbstractMetamodel<Gallery, String, kotlin.collections.List<String>>"),
                "The converted column must be addressed as String and keep its declared value type:\n" + metamodel);
        assertTrue(metamodel.contains("CityRefMetamodel<Gallery>"),
                "The reference must be addressed through its reference metamodel:\n" + metamodel);
    }

    @Test
    @EnabledIfSystemProperty(named = "storm.smoke", matches = "true")
    public void javaAddressesAConvertedColumnByItsStoredType() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), FunctionalTestSupport.SETTINGS);
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("st.orm")
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """);
        var sourceDir = projectDir.resolve("src/main/java/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("City.java"), """
                package demo;

                import st.orm.Entity;
                import st.orm.PK;

                public record City(@PK Integer id, String name) implements Entity<Integer> {
                }
                """);
        Files.writeString(sourceDir.resolve("Gallery.java"), """
                package demo;

                import java.util.List;
                import st.orm.Entity;
                import st.orm.FK;
                import st.orm.Json;
                import st.orm.PK;
                import st.orm.Ref;

                public record Gallery(
                        @PK Integer id,
                        @Json List<String> tags,
                        @FK Ref<City> city
                ) implements Entity<Integer> {
                }
                """);
        var result = runner().build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
        try (var generated = Files.walk(projectDir.resolve("build/generated/sources/annotationProcessor"))) {
            var galleryMetamodel = generated
                    .filter(path -> path.getFileName().toString().equals("Gallery_.java"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected a generated Gallery_ metamodel."));
            var metamodel = Files.readString(galleryMetamodel);
            assertTrue(metamodel.contains("AbstractMetamodel<Gallery, java.lang.String, java.util.List<java.lang.String>>"),
                    "The converted column must be addressed as String and keep its declared value type:\n" + metamodel);
            assertTrue(metamodel.contains("CityRefMetamodel<Gallery>"),
                    "The reference must be addressed through its reference metamodel:\n" + metamodel);
        }
    }
}
