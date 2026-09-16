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

import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.GradleException;

/**
 * Maps the project's Kotlin version to the Storm compiler-plugin variant compiled against that Kotlin
 * compiler API, and to the recommended KSP version.
 *
 * <p>Keep this matrix in sync with {@code website/src/components/tutorial/tutorialTheme.js}
 * (KOTLIN_VARIANTS), {@code website/static/skills/storm-setup.md}, {@code docs/installation.md},
 * {@code docs/getting-started.md} and {@code README.md}.</p>
 */
final class KotlinVariants {

    /**
     * Kotlin major.minor to recommended KSP version. Kotlin 2.0 and 2.1 pair with their own KSP builds,
     * versioned {@code <kotlin>-<ksp>}; from KSP 2.3 on, KSP versions independently of Kotlin and one release
     * covers Kotlin 2.2 and newer. The Kotlin 2.2 boundary is settled by a functional test that compiles a
     * metamodel on Kotlin 2.2 with the bundled KSP; on Kotlin 2.1 and 2.0 the same KSP fails inside the
     * Kotlin Gradle plugin with a linkage error that names neither KSP nor Kotlin.
     */
    private static final Map<String, String> KSP_BY_KOTLIN = new LinkedHashMap<>();

    static {
        KSP_BY_KOTLIN.put("2.0", "2.0.21-1.0.28");
        KSP_BY_KOTLIN.put("2.1", "2.1.21-2.0.2");
        KSP_BY_KOTLIN.put("2.2", "2.3.10");
        KSP_BY_KOTLIN.put("2.3", "2.3.10");
        KSP_BY_KOTLIN.put("2.4", "2.3.10");
    }

    private KotlinVariants() {
    }

    /**
     * Returns the Storm compiler-plugin variant (the artifact suffix, such as {@code 2.4}) for the given
     * Kotlin version.
     *
     * @param kotlinVersion the full Kotlin version, such as {@code 2.4.0}.
     * @throws GradleException if the Kotlin version has no matching variant.
     */
    static String variantFor(String kotlinVersion) {
        String majorMinor = majorMinor(kotlinVersion);
        if (!KSP_BY_KOTLIN.containsKey(majorMinor)) {
            throw new GradleException(("""
                    Storm: no compiler-plugin variant for Kotlin %s. Supported Kotlin versions: %s.
                    Pin a variant explicitly with:
                        storm { compilerPluginVariant.set("%s") }
                    or disable the compiler plugin with:
                        storm { compilerPlugin.set(false) }""")
                    .formatted(kotlinVersion, String.join(", ", KSP_BY_KOTLIN.keySet()), newestVariant()));
        }
        return majorMinor;
    }

    /**
     * Returns the recommended KSP version for the given Kotlin version, falling back to the newest known
     * recommendation for unknown Kotlin versions.
     *
     * @param kotlinVersion the full Kotlin version, such as {@code 2.4.0}.
     */
    static String kspFor(String kotlinVersion) {
        return KSP_BY_KOTLIN.getOrDefault(majorMinor(kotlinVersion), KSP_BY_KOTLIN.get(newestVariant()));
    }

    /**
     * Returns whether the given KSP version pairs with the given Kotlin version: a Kotlin-paired KSP build,
     * versioned {@code <kotlin>-<ksp>}, pairs with the Kotlin line its prefix names, and a KSP that versions
     * independently of Kotlin pairs with every Kotlin line whose recommendation is such a build. Patch
     * releases within a line pair alike, so a newer KSP patch than the recommended one is not refused.
     *
     * @param kotlinVersion the full Kotlin version, such as {@code 2.1.21}.
     * @param kspVersion the full KSP version, such as {@code 2.1.21-2.0.2} or {@code 2.3.10}.
     */
    static boolean pairs(String kotlinVersion, String kspVersion) {
        int dash = kspVersion.indexOf('-');
        if (dash > 0) {
            return majorMinor(kspVersion.substring(0, dash)).equals(majorMinor(kotlinVersion));
        }
        return kspFor(kotlinVersion).indexOf('-') < 0;
    }

    /**
     * Returns the oldest Kotlin line whose recommended KSP is the given Kotlin-independent KSP version, the
     * line from which the bundled KSP applies automatically, such as {@code 2.2}.
     *
     * @param kspVersion a KSP version that versions independently of Kotlin, such as {@code 2.3.10}.
     * @throws IllegalArgumentException if no Kotlin line recommends the given KSP version.
     */
    static String oldestKotlinFor(String kspVersion) {
        for (var entry : KSP_BY_KOTLIN.entrySet()) {
            if (entry.getValue().equals(kspVersion)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("No Kotlin line recommends KSP " + kspVersion + ".");
    }

    private static String majorMinor(String kotlinVersion) {
        int firstDot = kotlinVersion.indexOf('.');
        int secondDot = kotlinVersion.indexOf('.', firstDot + 1);
        return secondDot > 0 ? kotlinVersion.substring(0, secondDot) : kotlinVersion;
    }

    private static String newestVariant() {
        String newest = null;
        for (String variant : KSP_BY_KOTLIN.keySet()) {
            newest = variant;
        }
        return newest;
    }
}
