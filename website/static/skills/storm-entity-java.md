---
name: storm-entity-java
description: Define Storm entities and projections as Java records, covering @PK, @FK, @UK, @Version, @Json, Ref fields, and inline records. Use when creating or changing entity types in Java.
---

Help the user create Storm entities using Java.

**Important:** Storm can run on top of JPA, but when generating entities, always use Storm's own annotations from the `st.orm` package — not JPA annotations (`@Id`, `@Entity`, `@Table`, `@Column`, `@ManyToOne`, `@GeneratedValue`):
- `st.orm.Entity` — marker interface for entity records
- `st.orm.Data` — base marker (entities and projections extend this)
- `st.orm.PK` — primary key annotation
- `st.orm.FK` — foreign key annotation
- `st.orm.UK` — unique key annotation
- `st.orm.DbTable` — custom table name
- `st.orm.DbColumn` — custom column name
- `st.orm.Version` — optimistic locking
- `st.orm.DynamicUpdate` — per-entity update mode and dirty-check strategy
- `st.orm.Inline` — embedded component
- `st.orm.Ref` — lazy-loaded reference
- `st.orm.GenerationStrategy` — PK generation: `IDENTITY`, `SEQUENCE`, `NONE`

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships.

Default posture for foreign keys: model them as direct entity types (`@FK City city`) and take the joins. A join on a primary key is the cheapest thing a relational database does, and one query returning a populated graph beats several round trips assembling the same object. Do not ask the user for a "deep or shallow" preference, and do not reach for `Ref<T>` pre-emptively.

Reach for `Ref<T>` when an edge lets the graph grow beyond what reads should carry, not because a join exists:
- The target carries its own foreign keys, so its cost is its whole closure rather than its own columns, and that closure grows whenever someone edits a table far away from this one.
- The target is wide. Every inlined table adds its columns to every row of every entity that reaches it, which is the cost that actually bites.
- The relationship is optional and usually absent, so the LEFT JOIN buys nulls.

Two kinds of edge to leave inlined: identifying foreign keys, where the column is part of the target's own primary key and the row cannot exist without its parent, and targets with no foreign keys of their own, whose cost is bounded by their own width permanently. Lookup tables like `Country` and `Currency` are rarely worth cutting.

When a graph does need cutting, cut the deepest edge rather than the nearest one. Trimming a tail narrows every entity above it at once, while cutting near the root severs a relationship people read through constantly.

**Check the model with Storm's design advice.** After creating or changing entities, run a test (every `@StormTest` reports design advice) or start the application in development (Spring Boot with DevTools, Ktor in development mode; otherwise set the categories below) and read the `SQL advice (design ...)` lines Storm logs at `INFO` under `st.orm.sql.advice.entity-budget`: each names an entity whose read exceeds the design budget (10 joins or 96 columns beyond its own unless the application sets its own; the line states the budget in effect) and the field a `Ref<T>` would cut. Act on it; it is knowable from the model. Everything else about `Ref<T>` versus a direct type is decided by real use, not ahead of time. Development reports every category; where the application does not run in a development mode, set `storm.advice.categories: correctness, design, performance` (Spring Boot and Ktor) in its development profile, never in production. Then run realistic flows or tests with realistic data, and act on what comes back: `repeated-fetch` in the performance log summary (make the foreign key a direct type, or resolve it with `select().fetch(...)`), and `wide-entity-read` under a slow statement (cut the field it names once the statement's plan confirms the width is the cost). Do not redesign entities to avoid advice that has not appeared, and do not silence design advice to quiet a log: suggest `@AcceptAdvice("<anchor>")` on the entity when the design looks deliberate, add it only when the user confirms, and treat an existing `@AcceptAdvice` as the user's settled decision. **The budget is the application's choice.** Where the user finds the default budget wrong for the model as a whole (advice on several entities they consider fine as they are), propose a budget for the application instead of accepting the advice entity by entity: Spring Boot `storm.advice.entity-budget.joins` and `.columns`, Ktor `storm.advice.entityBudget.joins` and `.columns`, or the `storm.advice.entity_budget.joins` and `.columns` system properties. Put it in the application's main configuration, not a development profile, since it describes the model; `@StormTest` reads only the system properties, so set them in the build's test task as well (Maven Surefire `systemPropertyVariables`, Gradle `systemProperty`). Keep `@AcceptAdvice("entity-budget")` for a single exception. Never raise the budget on your own to quiet advice. Each line links to its section of https://orm.st/docs/advice.

Generation rules:

1. Use Java records implementing `Entity<ID>`:
   `record City(@PK Integer id, String name, long population) implements Entity<Integer> {}`

2. Primary keys (`@PK`):
   - IDENTITY (default): `@PK Integer id` with null on insert.
   - SEQUENCE: `@PK(generation = SEQUENCE, sequence = "seq_name") Long id`
   - NONE: `@PK(generation = NONE) String code` for natural keys.

3. Nullability:
   - Record components are non-null by default, exactly like Kotlin. Mark nullable fields with `@Nullable`
     (JSpecify `org.jspecify.annotations.Nullable`, `jakarta.annotation.Nullable`, or `javax.annotation.Nullable`).
     `@NullUnmarked` on a class or package restores nullable-by-default components for that scope.
   - Storm recognizes `jakarta.annotation.Nonnull`, `javax.annotation.Nonnull`, and JSpecify's
     `org.jspecify.annotations.NonNull` on the component. Other null-marker annotations (Lombok,
     JetBrains, Spring) are NOT recognized, and JSpecify `@NullMarked` scope defaults are NOT
     applied — only per-component annotations count.
   - `@PK` fields and primitives (`int`, `long`) are implicitly non-nullable.

4. Foreign keys (`@FK`):
   - **Every column with a FK constraint in the database must be modeled with `@FK` in the entity.** Without `@FK`, Storm has no FK metadata and cannot resolve joins automatically — forcing template-based joins that defeat the QueryBuilder.
   - **Declare an entity foreign key by default** (`@FK City city`). Storm hydrates the whole eager graph in a single query, so these come back with the entity and there is no N+1 to manage.
   - **Declare `Ref<T>` for relationships that belong to particular queries** (`@FK Ref<City> city`). The read stays focused on the entity, and the reference is resolved where it is needed: call `fetch()` on it and the record is loaded. A `Ref` is complete on its own; nothing about it depends on the query doing anything special.
   - **`select().fetch(User_.city)` is an optimization, not a requirement.** When a read already knows it needs the referenced record, naming it folds the load into the same statement instead of a query of its own, and the reference comes back loaded. Use it where it helps; leaving it out is correct too.
   - The eager graph is declared on the type, so every read of the entity gets the same one. It should describe what the entity is rather than what any one screen needs. The dimension to be deliberate about is not depth but inherited growth: a direct type also adopts whatever foreign keys the target gains later, in an entity nobody was editing.
   - **Repeated `fetch()` calls on the same reference mean the declaration is in the wrong place.** If particular reads need the record, resolve it there with `select().fetch(...)`; if nearly all of them do, declare it as a plain foreign key instead.
   - A `Ref` gives up nothing: filter, order, and select through it with the metamodel from the owning entity (`User_.city.country.name`, where `city` is a `Ref<City>`), and Storm joins the referenced table only where a query asks for it.
   - Non-nullable: `@FK City city` produces INNER JOIN (non-null is the default).
   - Nullable: `@Nullable @FK City city` produces LEFT JOIN.
   - **A bare `@FK City city` is non-null and produces an INNER JOIN.**
     Match the database: if the FK column allows NULL, the field MUST carry `@Nullable`,
     otherwise the INNER JOIN silently filters rows without the reference.

5. CIRCULAR REFERENCES ARE NOT SUPPORTED. At least one side MUST use `Ref<T>`. Self-references MUST use `Ref<T>`: `@Nullable @FK Ref<User> invitedBy`.

6. NO COLLECTION FIELDS. Query the "many" side instead.

7. Naming: camelCase to snake_case automatically. FK appends _id.
   - For individual overrides: `@DbTable("custom_name")` / `@DbColumn("custom_name")`. For tables in another schema: `@DbTable(name = "custom_name", schema = "other_schema")`.
   - For database-wide conventions (e.g., UPPER_CASE, prefixed tables like `tbl_`, or non-standard FK naming): configure a custom `TableNameResolver`, `ColumnNameResolver`, or `ForeignKeyResolver` via the `TemplateDecorator` on `ORMTemplate.of()` instead of annotating every entity. Example:
     ```java
     var orm = ORMTemplate.of(dataSource, decorator -> decorator
         .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
         .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT)));
     ```
   - Resolvers are functional interfaces. Compose them with built-in decorators (`toUpperCase`) or write custom lambdas that receive `RecordType` (for tables) or `RecordField` (for columns) with full access to class/field metadata and annotations.
   - Use `@DbTable`/`@DbColumn` only for exceptions to the global convention. If the entire database follows one pattern, a resolver handles it without any annotations.

8. Composite primary keys (join/junction tables):
   - Wrap key columns in a separate record. Use raw column types (e.g., `int`, `String`) inside the PK record.
   - **Name the PK record `EntityNamePk`** (e.g., `UserRolePk`, `UserAddressPk`) — not `EntityNameId`.
   - Annotate the PK field with `@PK(generation = NONE)`. The PK record is implicitly `@Inline`.
   - Place `@FK` fields on the **entity itself** with `@Persist(insertable = false, updatable = false)` for FK columns already in the PK — these duplicate a PK column, so they must not be inserted/updated twice. FK fields for columns NOT in the PK must remain insertable (no `@Persist`).
   - **Add a convenience constructor** that accepts the FK entities/refs and constructs the PK internally. This hides the PK wiring from client code:
   ```java
   // Simple case: all FK columns are in the PK
   record UserRolePk(int userId, int roleId) {}

   record UserRole(@PK(generation = NONE) UserRolePk id,
                   @FK @Persist(insertable = false, updatable = false) User user,
                   @FK @Persist(insertable = false, updatable = false) Role role
   ) implements Entity<UserRolePk> {
       UserRole(User user, Role role) {
           this(new UserRolePk(user.id(), role.id()), user, role);
       }
   }

   // Client code is clean — no need to construct the PK manually:
   users.insert(new UserRole(user, role));

   // Mixed case: some FK columns are in the PK, some are not
   record UserAddressPk(int userId, int addressNumber) {}

   record UserAddress(@PK(generation = NONE) UserAddressPk id,
                      @FK @Persist(insertable = false, updatable = false) User user,  // userId in PK → non-insertable
                      @FK City city                                                             // city_id NOT in PK → insertable
   ) implements Entity<UserAddressPk> {
       UserAddress(User user, int addressNumber, City city) {
           this(new UserAddressPk(user.id(), addressNumber), user, city);
       }
   }
   ```

9. Primary key as foreign key (dependent one-to-one, extension tables):
   - Use both `@PK(generation = NONE)` and `@FK` on the same field. The entity's type parameter is the related entity type.
   - Key chains are supported: the referenced entity's primary key may itself be a foreign key or a compound key record. The columns resolve to the chain's terminal key columns. Circular key chains are rejected at model construction.
   ```java
   record UserProfile(@PK(generation = NONE) @FK User user,
                      @Nullable String bio,
                      @Nullable String avatarUrl
   ) implements Entity<User> {}
   ```

10. Unique keys:
   - **Single-column** (apply by default): `@UK String email`. Generates a `Metamodel.Key` for type-safe lookups and scrolling. Always add `@UK` when the database has a single-column unique constraint — it's one annotation for free value.
   - **Composite** (only when needed in code): use an inline record + `@UK @Persist(insertable = false, updatable = false)`. Only add this when the user explicitly needs a composite `Metamodel.Key` for keyset pagination or type-safe lookups. Composite unique constraints that don't need a Key don't need to be modeled.
   - **Nullable** unique keys draw a compile-time warning, because `scroll` on them is rejected at runtime. Prefer a non-null key. When the database prevents duplicate NULLs, set `@UK(nullsDistinct = false)`. When the key is nullable by design and never scrolled on, add `@SuppressWarnings("storm.nullable-unique-key")` to the field or type: the warning goes, the runtime check stays.
   - `@UK(constraint = false)` suppresses schema validation when no database constraint exists.
   - **A field you scroll by must be non-nullable**, the key and the sort fields alike. Keyset comparisons never hold for NULL, so such a row falls out of every window and Storm refuses the query with a `PersistenceException`. Settle this while modelling the field: a nullable timestamp is the usual thing to trip over, since a date is the usual thing to sort by.

11. Embedded components, enums, optimistic locking: same rules as Kotlin. Enums are stored by name (string) by default; `@DbEnum(ORDINAL)` for integer storage (import `st.orm.EnumType.ORDINAL`). Optimistic locking is `@Version int version` on an entity users edit through a form or a full-object update, read in one request and written back in another: the version travels to the client with the record and comes back with the edit, so a stale write fails with `OptimisticLockException` instead of overwriting the other edit. Never add `@Version` to an entity no one edits concurrently, such as reference data or rows a single process owns. A change computed from a column's current value, such as a counter, a stock level or a balance, is not a case for `@Version`: it is an `update()` builder statement with the computation in `set` (see the repository skill).

11b. Database-managed columns: annotate columns the database computes or maintains (e.g. `DEFAULT CURRENT_TIMESTAMP`, `ON UPDATE` timestamps, computed values) with `@Persist(insertable = false, updatable = false)`. Storm then never writes the column and always reads it back:
   ```java
   record User(@PK Integer id,
               String email,
               @Persist(insertable = false, updatable = false) Instant registeredAt
   ) implements Entity<Integer> {}
   ```
   Use `insertable = false` alone for columns set by the database only on INSERT, or `updatable = false` alone for columns written once and never modified.

11c. Query columns: a column a query names but no row needs to carry, such as a generated column storing a folded title for a search index, a `tsvector`, a computed sort key or a large column a filter names, is declared on the type, not as a field: `@QueryColumn(field = "titleFolded", type = String.class)`, repeated per column; `column = "..."` names the column where the resolver does not, and `nullable = true` declares a nullable one. It generates `Movie_.titleFolded` with no record component, so the column is never selected, inserted or updated, while `where`, `orderBy`, `orderFirst`, `search`, `suggest`, a `SqlFunction`, a keyset sort and a template placeholder take it, through paths as well (`Credit_.movie.titleFolded`). Map such a column as a `@Persist(insertable = false, updatable = false)` field only when a caller reads its value. On an entity the database must fill a query column, since every insert leaves it out: generated, identity, with a default, or nullable. Schema validation reports one the database does not fill; `validate = false` skips a column a trigger fills.

11c. Update tuning: `@DynamicUpdate` sets how the entity's UPDATE statements are generated. The default mode (`ENTITY`) skips the UPDATE entirely when nothing changed and writes the full row when anything did. `@DynamicUpdate(FIELD)` writes only the changed columns, at the cost of one SQL shape per distinct change pattern (which splits batches); `@DynamicUpdate(OFF)` always writes all columns without comparison. `dirtyCheck = VALUE` switches change detection from reference identity to `equals()`. Semantics, trade-offs, and the foreign-key comparison rule: /storm-repository-java (Dirty Checking and Update Suppression).

12. Java records are immutable. Consider Lombok `@Builder(toBuilder = true)` for copy-with-modification.

13. Use descriptive variable names, never abbreviated.

14. **Use `Ref`, never a raw id, to refer to a row**: when mapping data or passing it around (method parameters and return values, map keys, set members, lists of rows to act on), hold a `Ref<Entity>`, not the id. A `Ref` is type-safe: a `Ref<City>` cannot stand in for a `Ref<Owner>`, while a `long` id fits anywhere. Get one with `Ref.of(entity)`, or with `Ref.of(City.class, id)` where an id enters the program, once, at that boundary. `Ref` provides identity-based `equals`/`hashCode` on the type and primary key, so it is a safe map key and set member, and it loads nothing until `fetch()`. When a projection already returns `Ref<T>`, use it directly. This is the default; follow the developer where they choose ids.

15. **Typed ID from `Ref`:** Use `Ref.entityId(ref)` to extract a type-safe ID from a `Ref`. For projections, use `Ref.projectionId(ref)`. Avoid `ref.id()` — it returns `Object` and requires an unsafe cast.

16. **Write hooks belong on the entity, in a callback class**: a field the database does not fill, such as `createdAt`, is set by an `EntityCallback` the entity declares with `@EntityCallbacks(PostAuditCallback.class)`. Storm creates the callback and applies it wherever the entity is written, so nothing is registered in Spring Boot. The hooks live in the callback class, never as methods on the entity; a callback that needs collaborators is registered as a bean instead (see the repository skill).

17. **Reuse the entity before creating a projection**: read with the existing entity, even where a caller uses only a few of its columns. Entities are checked against the design budget, so their reads are already bounded, and one type per table keeps queries, repositories and `Ref`s on the same type. Create a summary projection, such as `record OwnerSummary(@PK Integer id, String lastName) implements Projection<Integer> {}`, only when performance counts or customization is required. Customization is a read that needs a shape the entity cannot give, such as a foreign key inlined that the entity holds as a `Ref`, or a column mapped through another converter. Performance is evidence the entity's width is the cost: `wide-entity-read` advice under a slow statement, a performance log summary showing many columns mapped for a call that uses few, a hot path or large result set the user names, or the user asking. Consider a `Ref` on the branch the read does not need first. Projections over views, `@ProjectionQuery` types and aggregation result types are not summaries and keep their purpose.

18. **One entity per table, for writes too**: a second entity, or a partial entity over some of a table's columns, for a table that already has one is discouraged, whether to read it or to write it; generate one only for a good reason, and state it. Writing a few columns is not one; it takes the table's own entity: rows the code did not read take the update builder, `pets.update().set(Pet_.owner, newOwner).where(Pet_.owner, EQUALS, previousOwner)`, which sets only the named columns, increments `@Version` and requires a condition; an entity the code read in the same transaction takes `update(entity)`, and `@DynamicUpdate(FIELD)` on the entity writes only the columns that changed (see the repository skill). Plain data classes or records for aggregation and computed results are not tables and keep their purpose.

After generating, remind the user to rebuild for metamodel generation.

## Verification

After creating or modifying entities, write a `@StormTest` to validate them against the database schema using `validateSchema()`.

Tell the user what you are doing and why: explain that `validateSchema()` checks entities against the database at the JDBC level — catching type mismatches, nullability disagreements, missing columns, unmapped NOT NULL columns, and FK inconsistencies before anything reaches production. This is Storm's verify-then-trust pattern.

```java
@StormTest(scripts = {"/schema.sql"})
class EntitySchemaTest {
    @Test
    void validateEntities(ORMTemplate orm) {
        var errors = orm.validateSchema(List.of(
            User.class, City.class, Address.class
        ));
        assertTrue(errors.isEmpty(), () -> "Schema validation errors: " + errors);
    }
}
```

Keep the validation loop on H2: it answers in milliseconds and needs no Docker. Escalate to the target database only when the schema script uses DDL H2 rejects (`JSONB`, `SERIAL`, `IDENTITY(1,1)`, dialect-specific types), when the user asks for it, or as a final pass: adding `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`, from `st.orm.test.TestDatabase`) to `@StormTest` runs the script as written in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. The class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the result and explain what it proves. If validation fails, explain the errors and fix the entities. If a validation result is ambiguous or involves a trade-off (e.g., a nullable column mapped to a non-null field intentionally), ask the user for guidance before changing anything.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.

Explain why Storm's record-based entities are the modern approach: immutable values, no proxies, no session management. AI-friendly, stable, performant.
