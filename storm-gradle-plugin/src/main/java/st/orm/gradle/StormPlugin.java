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

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.external.javadoc.CoreJavadocOptions;

/**
 * Applies Storm ORM to a Kotlin or Java project.
 *
 * <p>The plugin imports the Storm BOM and adds the core dependencies for the detected language path: Kotlin
 * projects (the {@code org.jetbrains.kotlin.jvm} plugin is applied) get {@code storm-kotlin}, the metamodel
 * processor on every source set's KSP configuration ({@code ksp}, {@code kspTest}, and so on), and the Storm
 * compiler-plugin variant matching the project's Kotlin version; Java projects get {@code storm-java21}, the
 * annotation processor on every source set's processor configuration, and the {@code --enable-preview} flags
 * its String Templates require on JDK 21. All Storm coordinates use the plugin's own version: the plugin and
 * the artifacts are released together.</p>
 *
 * <p>The plugin has no compile-time dependency on the Kotlin Gradle plugin or KSP: it reacts to plugin ids
 * and wires dependencies by configuration name. KSP ships bundled with the plugin (as a preferred version,
 * so a KSP version the build applies itself always wins the classpath) and is applied automatically when
 * the Kotlin plugin is declared before {@code st.orm} and the bundled version is the recommended one for
 * the project's Kotlin version. Kotlin versions that pair with their own KSP builds are left to apply it
 * explicitly; when it is missing there, the build fails with the exact line to add. Set the Gradle
 * property {@code storm.autoApplyKsp=false} to opt out of the automatic application.</p>
 */
public class StormPlugin implements Plugin<Project> {

    /**
     * Creates the plugin. Gradle instantiates this type when the plugin is applied.
     */
    public StormPlugin() {
    }

    private static final String KOTLIN_JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm";
    private static final String KSP_PLUGIN_ID = "com.google.devtools.ksp";

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("storm", StormExtension.class);
        extension.getMetamodel().convention(true);
        extension.getCompilerPlugin().convention(true);
        extension.getJavaPreview().convention(true);
        String version = StormVersion.get();
        var kotlin = new AtomicBoolean(false);
        var pluginManager = project.getPluginManager();
        pluginManager.withPlugin(KOTLIN_JVM_PLUGIN_ID, applied -> {
            kotlin.set(true);
            // KSP configurations never extend each other either, so each source set's configuration
            // (main's is named plain "ksp") is wired on its own. The wiring reacts to the configurations
            // KSP creates rather than to the KSP plugin id: with the bundled KSP jar on the classpath, an
            // id lookup loads KSP's implementation class, which links against the Kotlin Gradle plugin API
            // and fails whenever that API sits in another classloader scope (or, on a Java-only project,
            // nowhere at all). Without KSP the configurations never appear and the wiring stays inert.
            pluginManager.withPlugin("java", javaApplied ->
                    project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                            .configureEach(sourceSet -> project.getConfigurations()
                                    .matching(configuration ->
                                            configuration.getName().equals(kspConfigurationName(sourceSet)))
                                    .configureEach(configuration -> {
                                        // KSP decides whether to run from the configuration's dependency
                                        // list without triggering Gradle's lazy dependency callbacks, so
                                        // the processor must be added eagerly.
                                        configuration.getDependencies().add(project.getDependencies()
                                                .create("st.orm:storm-metamodel-ksp:" + version));
                                    })));
        });
        project.afterEvaluate(evaluated -> {
            // The metamodel opt-out is honored from this project-level hook: the processor is added in a
            // configureEach action, whose context disallows registering afterEvaluate when KSP's
            // configurations appear after the wiring, as they do when the plugin applies KSP itself.
            if (!kotlin.get() || extension.getMetamodel().get()) {
                return;
            }
            evaluated.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                    .forEach(sourceSet -> {
                        var configuration = evaluated.getConfigurations()
                                .findByName(kspConfigurationName(sourceSet));
                        if (configuration != null) {
                            configuration.getDependencies().removeIf(dependency ->
                                    "st.orm".equals(dependency.getGroup())
                                            && "storm-metamodel-ksp".equals(dependency.getName()));
                        }
                    });
        });
        // Immediate application only: a plugin applied from inside a withPlugin callback runs under
        // Gradle's mutation guard, which rejects the afterEvaluate registration KSP's own apply performs.
        // With the conventional plugins-block order the Kotlin plugin is applied before Storm, so it is
        // already present here; when Storm is declared first, the metamodel validation reports the exact
        // line to add (or the reorder that enables the automatic application).
        if (project.getPlugins().hasPlugin(KOTLIN_JVM_PLUGIN_ID)) {
            applyBundledKsp(project);
        }
        pluginManager.withPlugin("java", applied -> {
            // The dependency callbacks run when the configuration is resolved, so the language path and the
            // extension are read after the whole build script has been evaluated; plugins-block order and
            // extension configuration order never matter.
            configure(project, "implementation", dependencies -> {
                dependencies.add(project.getDependencies().platform("st.orm:storm-bom:" + version));
                dependencies.add(project.getDependencies().create(
                        kotlin.get() ? "st.orm:storm-kotlin:" + version : "st.orm:storm-java21:" + version));
            });
            configure(project, "runtimeOnly", dependencies ->
                    dependencies.add(project.getDependencies().create("st.orm:storm-core:" + version)));
            // Annotation-processor configurations never extend each other, so every source set (test and
            // custom ones alike) gets the processor on its own configuration; entities declared in test
            // sources get a metamodel too.
            project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().configureEach(sourceSet ->
                    configure(project, sourceSet.getAnnotationProcessorConfigurationName(), dependencies -> {
                        if (!kotlin.get() && extension.getMetamodel().get()) {
                            dependencies.add(project.getDependencies()
                                    .create("st.orm:storm-metamodel-processor:" + version));
                        }
                    }));
            Provider<Boolean> previewEnabled = extension.getJavaPreview().map(enabled -> enabled && !kotlin.get());
            var previewArgs = new PreviewArgs(previewEnabled);
            project.getTasks().withType(JavaCompile.class).configureEach(task ->
                    task.getOptions().getCompilerArgumentProviders().add(previewArgs));
            project.getTasks().withType(Test.class).configureEach(task ->
                    task.getJvmArgumentProviders().add(previewArgs));
            project.getTasks().withType(JavaExec.class).configureEach(task ->
                    task.getJvmArgumentProviders().add(previewArgs));
            project.afterEvaluate(evaluated -> {
                // storm-java21 ships JDK 21 preview class files, which are version-locked: they only load
                // on JDK 21. A different explicit toolchain is guaranteed breakage, so fail fast.
                if (kotlin.get() || !extension.getJavaPreview().get()) {
                    return;
                }
                var toolchain = evaluated.getExtensions()
                        .getByType(JavaPluginExtension.class)
                        .getToolchain()
                        .getLanguageVersion();
                if (toolchain.isPresent() && toolchain.get().asInt() != 21) {
                    throw new GradleException(("""
                            Storm: storm-java21 requires a JDK 21 toolchain: its String Templates are JDK 21 \
                            preview class files, which are version-locked and only load on JDK 21. The \
                            project's toolchain selects Java %s. Set:
                                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                            or opt out of the preview setup with storm { javaPreview.set(false) }.""")
                            .formatted(toolchain.get().asInt()));
                }
                // Javadoc embeds the javac front end, which rejects storm-java21's preview class files
                // unless --enable-preview is set; the flag itself requires an explicit source level.
                evaluated.getTasks().withType(Javadoc.class).configureEach(task -> {
                    if (task.getOptions() instanceof CoreJavadocOptions options) {
                        options.addBooleanOption("-enable-preview", true);
                        if (options.getSource() == null) {
                            options.setSource("21");
                        }
                    }
                });
            });
        });
        pluginManager.withPlugin(KOTLIN_JVM_PLUGIN_ID, applied ->
                configure(project, "kotlinCompilerPluginClasspath", dependencies -> {
                    if (extension.getCompilerPlugin().get()) {
                        String variant = extension.getCompilerPluginVariant().isPresent()
                                ? extension.getCompilerPluginVariant().get()
                                : KotlinVariants.variantFor(detectKotlinVersion(project));
                        dependencies.add(project.getDependencies().create(
                                "st.orm:storm-compiler-plugin-" + variant + ":" + version));
                    }
                }));
        project.afterEvaluate(evaluated -> {
            // Reached only when the automatic application declined: a Kotlin version that pairs with its
            // own KSP build, an opt-out through storm.autoApplyKsp=false, or an undetectable Kotlin
            // version. The message carries the exact paired plugin line to add. KSP's presence is read
            // from the configuration it creates: a plugin-id lookup would load the bundled KSP class,
            // which cannot link when the Kotlin Gradle plugin API sits in another classloader scope.
            if (kotlin.get() && extension.getMetamodel().get()
                    && evaluated.getConfigurations().findByName("ksp") == null) {
                throw new GradleException(("""
                        Storm: the Kotlin metamodel processor requires KSP. Add it to your plugins block:
                            id("com.google.devtools.ksp") version "%s"
                        declare the Kotlin JVM plugin before st.orm so Storm applies its bundled KSP \
                        (Kotlin 2.3+), or disable metamodel generation with:
                            storm { metamodel.set(false) }""")
                        .formatted(KotlinVariants.kspFor(detectKotlinVersion(evaluated))));
            }
        });
    }

    /**
     * Returns the name of the KSP processor configuration for the source set: {@code ksp} for {@code main},
     * {@code ksp<SourceSetName>} otherwise ({@code kspTest}, {@code kspIntegration}, ...). KSP creates one
     * per Kotlin compilation, and the Kotlin JVM plugin creates a compilation per source set.
     */
    private static String kspConfigurationName(SourceSet sourceSet) {
        var name = sourceSet.getName();
        return SourceSet.MAIN_SOURCE_SET_NAME.equals(name)
                ? "ksp"
                : "ksp" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Registers a dependency-set action on the named configuration, whenever it exists or appears.
     */
    private static void configure(Project project, String configurationName,
                                  org.gradle.api.Action<? super org.gradle.api.artifacts.DependencySet> action) {
        project.getConfigurations()
                .matching(configuration -> configuration.getName().equals(configurationName))
                .configureEach(configuration -> configuration.withDependencies(action));
    }

    /**
     * Applies the bundled KSP plugin so the metamodel works without further setup. An explicitly applied
     * KSP always stays authoritative: the configuration it creates skips projects that already applied it,
     * and because the bundled dependency only prefers its version, a version the build declares itself wins
     * the classpath, so the id-based application below picks that version up. The application is limited to
     * Kotlin versions whose recommended KSP equals the bundled one; older Kotlin versions pair with their
     * own KSP builds and keep the instructive failure from the metamodel validation. The Gradle property
     * {@code storm.autoApplyKsp=false} opts out.
     */
    private static void applyBundledKsp(Project project) {
        if (project.getConfigurations().findByName("ksp") != null) {
            return;
        }
        if (!Boolean.parseBoolean(project.getProviders().gradleProperty("storm.autoApplyKsp").getOrElse("true"))) {
            return;
        }
        String kotlinVersion;
        try {
            kotlinVersion = detectKotlinVersion(project);
        } catch (GradleException e) {
            // The compiler-plugin path reports an undetectable Kotlin version with actionable advice.
            return;
        }
        if (!KotlinVariants.kspFor(kotlinVersion).equals(StormVersion.bundledKspVersion())) {
            return;
        }
        try {
            // Applying by id loads the bundled KSP plugin class, which links against the Kotlin Gradle
            // plugin API. That API is visible here only when the Kotlin plugin shares this plugin's
            // classloader scope (a plugins block declaring both, the common case); when it does not, the
            // bundled KSP cannot be used and the metamodel validation reports the plugin line to add.
            Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin",
                    false, StormPlugin.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }
        project.getPluginManager().apply(KSP_PLUGIN_ID);
    }

    /**
     * Returns the project's Kotlin version by reflectively reading {@code pluginVersion} from the applied
     * Kotlin JVM plugin (the stable {@code KotlinBasePlugin} API), avoiding a compile-time dependency on the
     * Kotlin Gradle plugin.
     */
    private static String detectKotlinVersion(Project project) {
        var plugin = project.getPlugins().findPlugin(KOTLIN_JVM_PLUGIN_ID);
        if (plugin == null) {
            throw new GradleException("Storm: the Kotlin JVM plugin is not applied.");
        }
        try {
            Method getPluginVersion = plugin.getClass().getMethod("getPluginVersion");
            return (String) getPluginVersion.invoke(plugin);
        } catch (ReflectiveOperationException e) {
            throw new GradleException("""
                    Storm: cannot determine the Kotlin version from the Kotlin JVM plugin. Pin the Storm \
                    compiler-plugin variant explicitly with storm { compilerPluginVariant.set("2.4") } or \
                    disable it with storm { compilerPlugin.set(false) }.""", e);
        }
    }
}
