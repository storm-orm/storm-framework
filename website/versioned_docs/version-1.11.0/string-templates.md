# String Templates

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

String templates are the mechanism that makes Storm's SQL template engine injection-safe by design. Rather than concatenating SQL strings (which invites SQL injection), Storm uses language-level string interpolation that separates SQL fragments from parameter values at compile time. This page explains how string templates work in both Kotlin and Java, their current status, and how to set them up.

---

## Overview

Storm's SQL template engine accepts a template consisting of **fragments** (the literal SQL parts) and **values** (the interpolated expressions). The engine never concatenates values into SQL text. Instead, values are processed by the template engine: types expand into column lists, metamodel fields resolve to column names, and plain values become parameterized placeholders (`?`). This design makes SQL injection structurally impossible.

Both Kotlin and Java provide language-level string interpolation that Storm leverages for this purpose, but each language takes a different approach.

| | Kotlin | Java |
|---|---|---|
| **Syntax** | `$variable` or `${expression}` | `\{expression}` |
| **Mechanism** | Compiler plugin (auto-wraps interpolations) | String Templates (preview feature) |
| **Status** | Stable (Kotlin 2.0+) | Preview (Java 21+, evolving) |
| **Module** | `storm-kotlin` | `storm-java21` |

---

## Kotlin

### How It Works

Kotlin's string interpolation (`${}`) is a stable language feature. Storm provides a compiler plugin that transforms interpolated expressions inside template lambdas at compile time.

When you write:

```kotlin
orm.query { "SELECT ${User::class} FROM ${User::class} WHERE id = $id" }
```

The compiler plugin detects that the lambda has a `TemplateContext` receiver and automatically wraps each interpolated expression in a `t()` call:

```kotlin
orm.query { "SELECT ${t(User::class)} FROM ${t(User::class)} WHERE id = ${t(id)}" }
```

The `t()` function is the single entry point for all template elements. It handles types (expanding to column lists), metamodel fields (resolving to column names with aliases), and plain values (becoming parameterized placeholders). The compiler plugin inserts these calls so you don't have to.

This transformation happens at compile time and produces identical bytecode to writing `t()` manually. The resulting template is then processed by Storm's SQL template engine, which splits the string on the `t()` boundaries to obtain fragments and values.

### Setup

Add the Storm compiler plugin to your Kotlin compiler configuration. The plugin is published as a separate artifact per Kotlin major.minor version, so that each artifact is compiled against the matching Kotlin compiler API. Choose the artifact that matches the Kotlin version in your project:

| Kotlin version | Artifact ID |
|---|---|
| 2.0.x | `storm-compiler-plugin-2.0` |
| 2.1.x | `storm-compiler-plugin-2.1` |
| 2.2.x | `storm-compiler-plugin-2.2` |
| 2.3.x | `storm-compiler-plugin-2.3` |

The artifact version matches the Storm version (e.g., `1.11.0`).

<Tabs groupId="build">
<TabItem value="gradle" label="Gradle (Kotlin DSL)" default>

```kotlin
dependencies {
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")
}
```

</TabItem>
<TabItem value="maven" label="Maven">

Add the plugin jar as a dependency of `kotlin-maven-plugin`:

```xml
<plugin>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-maven-plugin</artifactId>
    <version>${kotlin.version}</version>
    <dependencies>
        <dependency>
            <groupId>st.orm</groupId>
            <artifactId>storm-compiler-plugin-2.0</artifactId>
            <version>${storm.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

</TabItem>
</Tabs>

The plugin activates automatically via service loader once it is on the Kotlin compiler classpath. No additional configuration flags are needed.

### Without the Compiler Plugin

The compiler plugin is optional. Without it, you can still use Storm's template engine by wrapping interpolations in `t()` manually:

```kotlin
orm.query { "SELECT ${t(User::class)} FROM ${t(User::class)} WHERE id = ${t(id)}" }
```

This produces identical behavior. The `t()` function is always available inside template lambdas. The compiler plugin simply automates the wrapping.

### Interpolation Safety

When a `TemplateBuilder` lambda runs without the compiler plugin and without any explicit `t()` or `interpolate()` calls, Storm cannot distinguish a pure SQL literal from a string with accidentally concatenated interpolations. The `storm.validation.interpolation_mode` system property controls how Storm handles this situation:

| Value | Behavior |
|-------|----------|
| `warn` | Logs a warning (default). Suitable for development. |
| `fail` | Throws an `IllegalStateException`. Recommended for production. |
| `none` | Disables the check entirely. |

In `warn` mode (the default), Storm logs the following message:

```
WARNING: TemplateBuilder lambda executed without the Storm compiler plugin and without
explicit t() or interpolate() calls. If this template uses string interpolations, values may
have been concatenated directly into the SQL, risking SQL injection.
See https://orm.st/string-templates for setup instructions.
To change this behavior, set -Dstorm.validation.interpolation_mode=warn|fail|none.
```

This helps catch cases where the compiler plugin is missing from the build configuration, causing interpolated values to be concatenated directly into the SQL string instead of being parameterized.

**Configuring the mode:**

```bash
# Production: fail on missing compiler plugin
java -Dstorm.validation.interpolation_mode=fail -jar myapp.jar

# Disable the check entirely
java -Dstorm.validation.interpolation_mode=none -jar myapp.jar
```

See [Configuration](configuration.md#interpolation-safety) for details and recommended production settings.

### Template Functions

Inside a template lambda, the `TemplateContext` receiver provides several functions for controlling how expressions are interpreted. With the compiler plugin, these functions are passed through `t()` automatically:

```kotlin
// Type reference (expands to column list in SELECT, table with joins in FROM)
orm.query { "SELECT ${User::class} FROM ${User::class}" }

// Metamodel column reference (resolves to column name with alias)
orm.query { "SELECT ${User::class} FROM ${User::class} WHERE ${User_.email} = $email" }

// Explicit column reference
orm.query { "SELECT ${User::class} FROM ${User::class} ORDER BY ${column(User_.email)}" }

// Table reference without auto-join
orm.query { "FROM ${from(User::class, autoJoin = false)} JOIN ${table(City::class)} ON ..." }

// Raw SQL (use with caution, bypasses parameterization)
orm.query { "SELECT ${User::class} FROM ${User::class} WHERE ${unsafe("name = 'Alice'")}" }
```

### Fallback: Manual t() Wrapping

If the compiler plugin is not available, you can wrap interpolations in `t()` manually. The compiler plugin detects existing `t()` and `interpolate()` calls and leaves them unchanged, so mixing both styles in the same project is safe:

```kotlin
orm.query { "SELECT ${t(User::class)} FROM ${t(User::class)} WHERE id = ${t(id)}" }
```

When using `t()` manually, the interpolation safety check is automatically suppressed because Storm detects the explicit calls. If you use pure literal templates without any interpolations, you can disable the check with the JVM system property:

```bash
-Dstorm.validation.interpolation_mode=none
```

---

## Java

### How It Works

Java's String Templates (preview feature since Java 21) provide a `StringTemplate` processor mechanism. Storm's `RAW` processor receives the template fragments and values directly from the language runtime, giving Storm the same structural separation as the Kotlin approach.

```java
orm.query(RAW."""
    SELECT \{User.class}
    FROM \{User.class}
    WHERE \{User_.email} = \{email}""")
```

The `\{expression}` syntax is Java's string template interpolation. The `RAW` processor passes fragments and values to Storm's template engine without any string concatenation.

### Status

Java String Templates are a **preview feature** that is still evolving in the JDK. Storm is a forward-looking framework, and String Templates are the best way to write SQL in Java that is both readable and injection-safe by design.

Rather than wait for the feature to stabilize, Storm ships with String Template support today. The Java API is production-ready from a quality perspective, but its API surface will adapt as String Templates move toward a stable release.

Only `storm-java21` depends on this preview feature. The core framework and the Kotlin API are unaffected.

### Setup

Enable preview features in your Java compiler configuration:

<Tabs groupId="build">
<TabItem value="maven" label="Maven" default>

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

</TabItem>
<TabItem value="gradle" label="Gradle (Kotlin DSL)">

```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}
```

</TabItem>
</Tabs>

### Template Elements

Java uses the same template elements as Kotlin, but without the `t()` wrapper (the `RAW` processor handles the separation directly):

```java
// Type reference
orm.query(RAW."SELECT \{User.class} FROM \{User.class}")

// Metamodel column reference
orm.query(RAW."SELECT \{User.class} FROM \{User.class} WHERE \{User_.email} = \{email}")

// Explicit column and table references
orm.query(RAW."FROM \{from(User.class, false)} JOIN \{table(City.class)} ON ...")

// Raw SQL
orm.query(RAW."SELECT \{User.class} FROM \{User.class} WHERE \{unsafe("name = 'Alice'")}")
```

---

## Comparison

Both approaches achieve the same goal: structurally safe SQL templates with compile-time separation of fragments and values. The difference is in how they get there.

| Aspect | Kotlin (Compiler Plugin) | Java (String Templates) |
|--------|--------------------------|-------------------------|
| **Interpolation** | `${expression}` (auto-wrapped by plugin) | `\{expression}` (processed by `RAW`) |
| **Plugin/flag required** | Storm compiler plugin | `--enable-preview` |
| **Multiline** | Triple-quoted strings (`"""..."""`) | Text blocks (`"""..."""`) |
| **Template functions** | `column()`, `table()`, `from()`, `unsafe()` | Same functions available |
| **Explicit wrapping** | `t()` available but optional with plugin | Not needed (`RAW` handles it) |

Both languages support all Storm template features: type expansion, metamodel column references, auto-join generation, subqueries, and raw SQL injection via `unsafe()`.
