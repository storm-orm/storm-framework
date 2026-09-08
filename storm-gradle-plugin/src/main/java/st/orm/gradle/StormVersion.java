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
import java.io.InputStream;
import java.util.Properties;

/**
 * The plugin's own version, baked in at build time. The plugin and the Storm artifacts it wires are released
 * from the same tag, so this version drives every {@code st.orm} coordinate the plugin adds.
 */
final class StormVersion {

    private static final Properties PROPERTIES = load();

    private StormVersion() {
    }

    static String get() {
        return property("version");
    }

    /**
     * The KSP version bundled with the plugin, declared as a preferred version in the plugin's own
     * dependencies, so it is on the classpath unless the build applies a KSP version of its own.
     */
    static String bundledKspVersion() {
        return property("bundledKspVersion");
    }

    private static String property(String key) {
        String value = PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Storm Gradle plugin: storm-version.properties has no " + key + ".");
        }
        return value;
    }

    private static Properties load() {
        try (InputStream in = StormVersion.class.getResourceAsStream("/st/orm/gradle/storm-version.properties")) {
            if (in == null) {
                throw new IllegalStateException("Storm Gradle plugin: missing storm-version.properties resource.");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Storm Gradle plugin: cannot read storm-version.properties.", e);
        }
    }
}
