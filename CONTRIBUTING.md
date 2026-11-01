# Contributing to Storm

Thank you for your interest in contributing to Storm! This document provides guidelines and information for contributors.

## Ways to Contribute

- **Report bugs:** Open an issue describing the bug, including steps to reproduce
- **Suggest features:** Open an issue describing your idea and use case
- **Improve documentation:** Fix typos, clarify explanations, add examples
- **Submit code:** Bug fixes, new features, performance improvements

## Development Setup

### Prerequisites

- JDK 21, which runs the build
- JDK 24 or later, registered as a Maven toolchain: storm-core compiles its repository class writer, which uses the class-file API, into the multi-release part of its jar, and tests it on that JDK. Without a toolchains file the build skips the writer, and repositories stay proxies; CI and releases require it
- Kotlin 2.0 or later (for Kotlin modules)
- Maven 3.9+

Register the JDKs in `~/.m2/toolchains.xml`:

```xml
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides><version>21</version></provides>
    <configuration><jdkHome>/path/to/jdk-21</jdkHome></configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides><version>25</version></provides>
    <configuration><jdkHome>/path/to/jdk-25</jdkHome></configuration>
  </toolchain>
</toolchains>
```

### Building the Project

```bash
# Clone the repository
git clone https://github.com/storm-orm/storm-framework.git
cd storm-framework

# Build all modules
mvn clean install
```

### Project Structure

```
storm-framework/
├── storm-bom/                         # Bill of Materials (BOM) for version management
├── storm-foundation/                  # Base annotations, interfaces, and metamodel base classes
├── storm-core/                        # Core library and SQL template engine
├── storm-test/                        # Test support (@StormTest, SqlCapture)
│
├── storm-metamodel-ksp/               # Metamodel generation via Kotlin Symbol Processing (KSP)
├── storm-metamodel-processor/         # Metamodel generation via Java annotation processor
├── storm-compiler-plugin/             # Kotlin compiler plugin
│
├── storm-kotlin/                      # Kotlin API
├── storm-java21/                      # Java 21+ API (uses String Templates)
│
├── storm-kotlin-spring/               # Spring integration (Kotlin)
├── storm-spring/                      # Spring integration (Java)
├── storm-kotlin-spring-boot-starter/  # Spring Boot starter (Kotlin)
├── storm-spring-boot-starter/         # Spring Boot starter (Java)
├── storm-spring-boot-test-autoconfigure/ # @DataStormTest test slice (both starters)
├── storm-ktor/                        # Ktor integration (Kotlin)
├── storm-ktor-test/                   # Ktor integration test support
├── storm-micrometer/                  # Micrometer Observations binding
├── storm-gradle-plugin/               # Gradle plugin (id("st.orm"))
│
├── storm-kotlinx-serialization/       # kotlinx.serialization support
├── storm-jackson2/                    # Jackson 2.x JSON support
├── storm-jackson3/                    # Jackson 3.x JSON support
│
├── storm-postgresql/                  # PostgreSQL dialect
├── storm-mysql/                       # MySQL dialect
├── storm-mariadb/                     # MariaDB dialect
├── storm-oracle/                      # Oracle dialect
├── storm-mssqlserver/                 # MS SQL Server dialect
├── storm-sqlite/                      # SQLite dialect
└── storm-h2/                          # H2 dialect
```

## Code Formatting

Storm uses [Spotless](https://github.com/diffplug/spotless) to enforce consistent code formatting across the project. Formatting is checked automatically in CI and can be enforced locally with a git pre-commit hook.

- **Kotlin** is formatted with [ktlint](https://github.com/pinterest/ktlint) (Kotlin coding conventions)
- **Java** is checked for import order, unused imports, trailing whitespace, and a final newline; beyond that, follow the formatting of the surrounding code (4-space indentation)

### Auto-fix formatting

```bash
mvn spotless:apply
```

### Check formatting without modifying files

```bash
mvn spotless:check
```

### Set up the pre-commit hook

To automatically check formatting before every commit:

```bash
git config core.hooksPath .githooks
```

## Code Guidelines

### General

- Follow existing code style and conventions
- Write clear, self-documenting code
- Keep changes focused; one feature or fix per PR
- Add tests for new functionality

### Kotlin

- Use idiomatic Kotlin (data classes, extension functions, etc.)
- Prefer immutability
- Use coroutines for async operations
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)

### Java

- Use records for immutable data
- Leverage pattern matching where appropriate
- Follow standard Java naming conventions
- Use `Optional` for nullable return values

### Testing

- Write unit tests for new functionality
- Ensure existing tests pass before submitting
- Use meaningful test names that describe the scenario

```bash
# Run all tests
mvn test

# Run tests for a specific module
mvn test -pl storm-kotlin
```

## Use of AI

AI tools (assistants, code generation, and similar) are welcome in your workflow. The condition is ownership: you are the author of what you submit and take full responsibility for it. Review, understand, and test any AI-assisted output as if you had written it yourself — the same correctness, style, and licensing standards apply.

Because the work is yours, do not add AI attribution to commits or pull requests — no `Co-authored-by:` trailers naming an AI, and no "Generated with …" notes.

## Submitting Changes

### Issues and Branch Naming

Not every change needs an issue first:

- **New features, API changes, or behavior changes:** open an issue first so the design can be discussed before you invest time in code.
- **Bug fixes, documentation, tests, CI/infra, chores, and dependency bumps:** open a PR directly. Link an existing issue if there is one, but you do not need to create one.

Name branches `<type>/<short-description>`, using the same types as our commit messages ([Conventional Commits](https://www.conventionalcommits.org/)):

- `feat/` — new functionality
- `fix/` — bug fixes
- `docs/` — documentation
- `ci/` — CI and build configuration
- `chore/` — tooling, dependencies, and housekeeping
- `perf/` / `refactor/` — performance or internal refactors

For example: `feat/composite-foreign-keys`, `fix/cli-regex-injection`, `docs/faq-typos`.

Link related issues from the PR description with `Fixes #123` or `Closes #123`, and GitHub will close them automatically on merge — there is no need to put the issue number in the branch name.

### Pull Request Process

1. **Fork** the repository
2. **Create a branch** for your changes
   ```bash
   git checkout -b feat/your-change
   ```
3. **Make your changes** with clear, atomic commits
4. **Test** your changes thoroughly
5. **Push** to your fork
6. **Open a Pull Request** against the `main` branch

### PR Guidelines

- Provide a clear description of the changes
- Link related issues with `Fixes #123` / `Closes #123`
- Keep PRs focused and reasonably sized
- Respond to review feedback promptly

### Commit Messages

Write clear commit messages that explain *what* and *why*:

```
Add support for composite foreign keys

- Handle multiple column FKs in entity mapping
- Update metamodel generator for composite keys
- Add tests for various composite key scenarios

Fixes #123
```

## Reporting Issues

When reporting bugs, please include:

- Storm version
- Kotlin/Java version
- Database and JDBC driver version
- Minimal code example reproducing the issue
- Expected vs actual behavior
- Stack trace (if applicable)

## Feature Requests

When suggesting features:

- Describe the use case and problem being solved
- Explain how you envision the feature working
- Consider providing API examples
- Note any alternatives you've considered

## Questions?

- Check the [FAQ](docs/faq.md) first
- Open a discussion for general questions
- Open an issue for bugs or feature requests

## License

By contributing to Storm, you agree that your contributions will be licensed under the Apache 2.0 License.
