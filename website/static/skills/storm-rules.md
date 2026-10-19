## Storm ORM

This project uses the [Storm ORM framework](https://orm.st) for database access.
Storm is a modern SQL Template and ORM for Kotlin 2.0+ and Java 21+, built around
immutable data classes and records instead of proxied entities.

These rules apply to code you change as well as to code you write. When an edit touches an entity, a repository method
or a query, check all of it against them, not only the lines the edit is about, and fix what breaks one in the same
change.

### Storm Annotations and API

Storm can run on top of JPA, but when generating code, always use Storm's own annotations and JDBC-based API:
- Use `@PK`, not `@Id` or `@GeneratedValue`
- Use `@FK`, not `@ManyToOne` or `@JoinColumn`
- Use `@DbTable`, not `@Table` or `@Entity`
- Use `@DbColumn`, not `@Column`
- Use `@UK`, not `@UniqueConstraint`
- Use `@Version` from `st.orm`, not from `jakarta.persistence`
- Use `@EntityCallbacks(AuditCallback::class)` with an `EntityCallback` implementation, not `@EntityListeners`, `@PrePersist`, `@PreUpdate` or `@PreRemove`; the hooks live in the callback class, never as methods on the entity
- Use `DataSource.orm` or `ORMTemplate.of(dataSource)`, not `EntityManager`
- Do not add `jakarta.persistence-api`, Hibernate, or any JPA implementation unless the project already uses them

Storm works directly with JDBC `DataSource`. There is no persistence context, no session, no lazy proxy objects.

### Framework Detection

Detect the framework from the build file and existing dependencies before suggesting dependencies, patterns, or configuration:

- **Spring Boot**: build file contains `storm-kotlin-spring-boot-starter`, `storm-spring-boot-starter`, `spring-boot-starter`, or `@SpringBootApplication` in the codebase.
- **Ktor**: build file contains `storm-ktor`, `ktor-server-core`, or `io.ktor` dependencies.
- **Standalone**: neither Spring Boot nor Ktor detected. The project uses Storm directly with `ORMTemplate.of(dataSource)`.

The setup and repository skills listed below carry the per-framework entry points, transaction placement, and configuration.

### References, Not Ids

This is the default to apply. The developer can choose otherwise; where they say so, or the code they own already passes ids by design, follow that.

- **Refer to a row with `Ref<T>`, never with its raw id.** Wherever code passes, returns, stores or maps a reference to a row (method parameters, return values, map keys, set members, lists of which rows to act on), use `Ref<City>`, not an `Int`, `Long`, `UUID` or `String` id. A `Ref` is type-safe: a `Ref<City>` cannot be passed where a `Ref<Owner>` is expected, while a bare `Long` fits anywhere. It carries the type and the primary key, loads nothing until `fetch()`, and compares by type and key, so it is a correct map key and set member.
- **Create a `Ref` from what you have.** From an entity: `city.ref()` in Kotlin (`import st.orm.template.ref`), `Ref.of(city)` in Java. From an id, where it enters the program (a path variable, a request field, a message): `refById<City>(id)` in Kotlin (`import st.orm.template.refById`), `Ref.of(City.class, id)` in Java. Convert once, at that boundary, and pass the `Ref` inward.
- **Hand the `Ref` to Storm as is.** Repositories and queries take refs directly (`findByRef`, `getByRef`, `findAllByRef`, a foreign key field compared to a `Ref`), so never unwrap one into an id to query. Take a typed id with `entityId()` where an id must leave the program. How DTOs and other transfer types carry a reference is the developer's choice.

### One Entity per Table, Projections for Performance or Customization

This is the default to apply. The developer can choose otherwise; where they say so, follow that.

- **One type per table.** A second entity, or a partial entity over some of its columns, for a table that already has one is discouraged, for reads and for writes: add one only for a good reason, and state it. Writing a few columns is not one; the update builder does that through the table's own entity. Queries, repositories, `Ref`s and tests then all work with the same type, and the table has one mapping to keep in step with its schema.
- **Read with the entity the table already has**, even where a screen or endpoint uses only a few of its columns. Every entity is checked against the design budget (10 joins and 96 columns beyond its own by default, configurable by the application, reported as design advice), so its read is already bounded, and one type per table keeps the model small: queries, repositories, `Ref`s and tests all work with the same type.
- **Create a summary projection only when performance counts or customization is required**: a `Projection<ID>` over a subset of an entity's columns is a second type to keep in step with the table, so it needs a reason. Customization is a read that needs a shape the entity cannot give, such as a foreign key inlined that the entity holds as a `Ref`, or a column mapped through another converter. Performance is evidence the entity's width is the cost: `wide-entity-read` advice under a slow statement, a performance log summary showing a read that maps many columns for a call that uses few, a hot path or a large result set the user names, or the user asking. Before adding one, consider a `Ref` on the branch the read does not need, which narrows every read of the entity at once.
- **Write a subset of columns through the same entity.** Rows the transaction did not read take the update builder, which sets the named columns on the rows its conditions select: `pets.update { set(Pet_.owner, newOwner); where(Pet_.owner eq previousOwner) }` in Kotlin, `pets.update().set(Pet_.owner, newOwner).where(Pet_.owner, EQUALS, previousOwner)` in Java. An entity the transaction read takes `update(entity)`, and `@DynamicUpdate(FIELD)` on the entity writes only the columns that changed. Neither needs a partial entity.
- **Other projections keep their purpose.** A projection over a database view or a `@ProjectionQuery`, and a plain data class or record for an aggregation or computed result, are not tables, so this rule does not apply to them.

### Writing by Condition

- **`remove` and `update(entity)` take what you hold; `delete()` and `update()` builders take a condition.** `remove(entity)`, `removeById`, `removeByRef` and `update(entity)` act on entities, ids or refs the code already has. `delete()` and the `update()` builder find their rows by a condition and return a `QueryBuilder`, ending in `executeUpdate()`; there is no `delete(entity)`.
- **The builders require a condition.** An UPDATE or DELETE without a WHERE clause, semi-join or anti-join is refused unless `unsafe()` is called.
- **The update builder sets columns through the metamodel**: a value typed by the field, `null` for a nullable field (Java `setNull`), an entity or `Ref` for a foreign key, or a template computing one column (`set(Owner_.lastName) { "UPPER(${Owner_.lastName})" }`). It refuses the primary key, `@Persist(updatable = false)` fields, the `@Version` field, paths into related tables and a field set twice. Both the update and delete builders refuse a Joined Table entity, whose rows span two tables.
- **The update builder increments `@Version`, fires no entity callbacks and evicts its type from the transaction's entity cache.** An entity read before it ran still holds its old values: re-read the entities it touched before writing them back, since a full-row write of a stale copy puts the old values back over the columns it set (a versioned entity fails its optimistic lock check instead). A column an audit callback stamps on update is `set` in the builder, since callbacks see only operations that carry an entity.

### Query and Template Rules

- **Prefer the QueryBuilder and metamodel-based methods** for joins, where clauses, ordering, and pagination. Fall back to SQL templates only when the QueryBuilder cannot express the query.
- **WHERE clauses stay with plain `where(...)`.** In Kotlin, compound AND/OR conditions stay there via infix `and`/`or`. A join widens the query: from the join onward, `where`/`orderBy`/`groupBy`/`having` accept paths from any entity in the query, so joined-entity fields need no special form — put joins before the clauses that reference them. Use the builder form (Kotlin `whereBuilder { }`, Java `where(it -> ...)`) only for what a plain predicate cannot express: AND/OR grouping in Java, or EXISTS/NOT EXISTS and id/ref/record matching inside compound logic.
- **Filter by a to-many relation with `semiJoin` or `antiJoin`, never with a join and `distinct`.** Rows *with* a matching related row (owners of a pet named Leo, a search over a row and its related rows) take `semiJoin`; rows *without* one take `antiJoin`, with its condition on the join. A to-one relation takes a path. A regular join is for when the related rows' columns belong in the result, the ordering or the grouping. This holds whichever table the join is on: `on` names any table of the query, one the root reaches through a path included, so a select of the root with `distinct` and an inner join that only filters is this pattern, also where a comment calls the `distinct` required. Two to-many hops chain two semi-joins, never two joins and `distinct`: `on` names a semi-joined table, or the table a semi-joined link row points at. See the query skill's **Filtering by Related Rows**.
- **Write template expressions as lambdas** (`{ "..." }`) in Kotlin, or `RAW."""..."""` in Java. Never construct `TemplateString.raw()`.
- **Reference columns through the metamodel** (`User_.email`), including inside templates, rather than hardcoding column names.
- **Prefer the reified form unless it needs `_`.** In Kotlin, write a type argument (`.innerJoin<X>().on<Y>()`, `subquery<X>()`, `selectFrom<X>()`, `resultList<T>()`, `orm.repository<UserRepository>()`) unless the call would need `_` placeholders for the remaining type arguments; then use the class literal. Look repositories up by class (`orm.entity(User::class)`, which infers the ID type) and select a different result type by class (`users.select(City::class)`, `users.select(CityCount::class) { template }`). Never write `_` placeholders.
- **A result flow or stream is consume-only while it has rows left.** `resultFlow` / `getResultStream()` is one open statement; inside a transaction a query, a `Ref.fetch()` or a write from the loop throws, on every database, and so does a batched write fed by that flow or stream once a batch runs while rows remain. A loop that needs the database iterates with `windows(size)`: keyset windows over the primary key, one closed statement per window, the connection free in between, one batched write per window; row by row, `windows(size).rows()` in Kotlin and `windows(size).flatMap(Slice::stream)` in Java. A Kotlin `resultFlow` is cold: building it runs nothing, so flows may be built ahead and collected in turn, and each collection runs the query again. Details in /storm-query-kotlin and /storm-query-java under "Flows and the Connection" / "Streams and the Connection".

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
