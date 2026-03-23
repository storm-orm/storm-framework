## Storm ORM

This project uses the [Storm ORM framework](https://orm.st) for database access.
Storm is a modern SQL Template and ORM for Kotlin 2.0+ and Java 21+, built around
immutable data classes and records instead of proxied entities.

If the project does not yet have Storm dependencies in its build file (pom.xml,
build.gradle.kts), use /storm-setup to help the user configure their project.
Detect the project's Kotlin or Java version from the build file to recommend the
correct dependencies and compiler plugin version.

Available Storm skills:
- /storm-setup - Help configure Maven/Gradle dependencies
- /storm-docs - Load full Storm documentation
- /storm-entity-kotlin or /storm-entity-java - Create entities
- /storm-repository-kotlin or /storm-repository-java - Write repositories
- /storm-query-kotlin or /storm-query-java - Write queries with the QueryBuilder
- /storm-sql-kotlin or /storm-sql-java - Write SQL Templates
- /storm-json-kotlin or /storm-json-java - JSON columns and JSON aggregation
- /storm-serialization-kotlin or /storm-serialization-java - Entity serialization for REST APIs
- /storm-migration - Write Flyway/Liquibase migration SQL

When the user asks about Storm topics, suggest the relevant skill if they need detailed guidance.

Quick reference: https://orm.st/llms.txt
Full documentation: https://orm.st/llms-full.txt
