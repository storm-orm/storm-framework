import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.1.1"
    id("com.diffplug.spotless") version "8.8.0"
}

group = "st.orm"
// The tag-driven release passes -Pversion=X.Y.Z, mirroring the Maven reactor's -Drevision.
if (version.toString() == Project.DEFAULT_VERSION) {
    version = "0.0.0-SNAPSHOT"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.compileJava {
    // Gradle daemons commonly run on older JDKs; the plugin supports Gradle 8.5+.
    options.release = 17
}

// The KSP version bundled with the plugin, applied automatically on the Kotlin path. Keep it equal to
// KotlinVariants' newest KSP recommendation: the plugin only auto-applies where the recommendation for
// the project's Kotlin version matches this bundled version.
val bundledKspVersion = "2.3.10"

// Bake the plugin's own version into a resource, so the plugin resolves matching st.orm artifacts at
// runtime. A generated resource is deterministic and works under TestKit classloaders, where the jar
// manifest's Implementation-Version is not visible.
val generateVersionResource by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    inputs.property("bundledKspVersion", bundledKspVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("st/orm/gradle/storm-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$versionValue\nbundledKspVersion=$bundledKspVersion\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource)
}

val functionalTest: SourceSet = sourceSets.create("functionalTest")
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    // Bundled so that applying st.orm alone gives a working metamodel. The version is only preferred,
    // never required: a KSP version the build applies itself always wins the classpath, so projects on
    // Kotlin versions that pair with their own KSP builds keep full control.
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin") {
        version { prefer(bundledKspVersion) }
    }
    testImplementation(gradleApi())
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "functionalTestImplementation"(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    testLogging {
        // A TestKit build that fails in its daemon reports the daemon's response, pid and the tail of its
        // log in the exception message, which the short format leaves out of the build log.
        exceptionFormat = TestExceptionFormat.FULL
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the TestKit functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    // Opt-in gate for the smoke test that compiles a real project against mavenLocal snapshots.
    systemProperty("storm.smoke", System.getProperty("storm.smoke", "false"))
    // The KSP auto-apply tests resolve the plugin from mavenLocal instead of TestKit's injected
    // classpath: the injected classpath is a separate classloader scope, where the bundled KSP cannot
    // link against the Kotlin Gradle plugin and the automatic application deliberately stands down.
    systemProperty("storm.plugin.version", project.version.toString())
    // TestKit runs the builds in a daemon that treats this directory as its Gradle user home. Every Kotlin
    // Gradle plugin and KSP version the tests build with accumulates in that daemon's metaspace, which
    // outgrows the default 384 MiB over the suite, so the daemon gets a gigabyte.
    val testKitDir = layout.buildDirectory.dir("testkit").get().asFile
    systemProperty("org.gradle.testkit.dir", testKitDir.absolutePath)
    doFirst {
        testKitDir.mkdirs()
        testKitDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=1g\n")
    }
    dependsOn("publishToMavenLocal")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(functionalTestTask)
}

gradlePlugin {
    website = "https://orm.st"
    vcsUrl = "https://github.com/storm-orm/storm-framework"
    testSourceSets(functionalTest)
    plugins {
        create("storm") {
            id = "st.orm"
            implementationClass = "st.orm.gradle.StormPlugin"
            displayName = "Storm ORM"
            description = "Applies Storm ORM to Kotlin or Java projects: BOM import, core dependencies, " +
                "metamodel generation (KSP or annotation processor), Kotlin compiler-plugin variant " +
                "selection, and Java preview flags."
            tags = listOf("orm", "sql", "database", "persistence", "kotlin", "ksp")
            // Backed by ConfigurationCacheFunctionalTest and the smoke tests, which build with
            // --configuration-cache on both language paths.
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

spotless {
    java {
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        trimTrailingWhitespace()
        endWithNewline()
    }
}
