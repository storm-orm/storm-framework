## Storm ORM

This project uses the [Storm ORM framework](https://orm.st) for database access.
Storm is a modern SQL Template and ORM for Kotlin 2.0+ and Java 21+, built around
immutable data classes and records instead of proxied entities.

### Storm Annotations and API

Storm can run on top of JPA, but when generating code, always use Storm's own annotations and JDBC-based API:
- Use `@PK`, not `@Id` or `@GeneratedValue`
- Use `@FK`, not `@ManyToOne` or `@JoinColumn`
- Use `@DbTable`, not `@Table` or `@Entity`
- Use `@DbColumn`, not `@Column`
- Use `@UK`, not `@UniqueConstraint`
- Use `@Version` from `st.orm`, not from `jakarta.persistence`
- Use `DataSource.orm` or `ORMTemplate.of(dataSource)`, not `EntityManager`
- Do not add `jakarta.persistence-api`, Hibernate, or any JPA implementation unless the project already uses them

Storm works directly with JDBC `DataSource`. There is no persistence context, no session, no lazy proxy objects.

### Framework Detection

Detect the framework from the build file and existing dependencies before suggesting dependencies, patterns, or configuration:

- **Spring Boot**: build file contains `storm-kotlin-spring-boot-starter`, `storm-spring-boot-starter`, `spring-boot-starter`, or `@SpringBootApplication` in the codebase.
- **Ktor**: build file contains `storm-ktor`, `ktor-server-core`, or `io.ktor` dependencies.
- **Standalone**: neither Spring Boot nor Ktor detected. The project uses Storm directly with `ORMTemplate.of(dataSource)`.

The setup and repository skills listed below carry the per-framework entry points, transaction placement, and configuration.

### Query and Template Rules

- **Prefer the QueryBuilder and metamodel-based methods** for joins, where clauses, ordering, and pagination. Fall back to SQL templates only when the QueryBuilder cannot express the query.
- **WHERE clauses stay with plain `where(...)`.** In Kotlin, compound AND/OR conditions stay there via infix `and`/`or`. A join widens the query: from the join onward, `where`/`orderBy`/`groupBy`/`having` accept paths from any entity in the query, so joined-entity fields need no special form — put joins before the clauses that reference them. Use the builder form (Kotlin `whereBuilder { }`, Java `where(it -> ...)`) only for what a plain predicate cannot express: AND/OR grouping in Java, or EXISTS/NOT EXISTS and id/ref/record matching inside compound logic.
- **Write template expressions as lambdas** (`{ "..." }`) in Kotlin, or `RAW."""..."""` in Java. Never construct `TemplateString.raw()`.
- **Reference columns through the metamodel** (`User_.email`), including inside templates, rather than hardcoding column names.
- **Keep one API style per snippet.** In Kotlin, prefer the reified forms (`orm.entity<User>()`, `.innerJoin<X>().on<Y>()`, `resultList<T>()`), and never mix reified and `::class` styles within one query or code block.
- **A result flow or stream is consume-only while it has rows left.** `resultFlow` / `getResultStream()` is one open statement; inside a transaction a query, a `Ref.fetch()` or a write from the loop throws, on every database, and so does a batched write fed by that flow or stream once a batch runs while rows remain. A loop that needs the database iterates with `windows(size)`: keyset windows over the primary key, one closed statement per window, the connection free in between, one batched write per window. Details in /storm-query-kotlin and /storm-query-java under "Flows and the Connection" / "Streams and the Connection".

Use /storm-setup when the project has no Storm dependencies in its build file yet.

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
