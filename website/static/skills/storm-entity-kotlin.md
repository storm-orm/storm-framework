---
name: storm-entity-kotlin
description: Define Storm entities and projections as Kotlin data classes, covering @PK, @FK, @UK, @Version, @Json, Ref fields, and inline classes. Use when creating or changing entity types in Kotlin.
---

Help the user create Storm entities using Kotlin.

**Use Storm's own annotations from the `st.orm` package, never JPA annotations** (`@Id`, `@Entity`, `@Table`, `@Column`, `@ManyToOne`, `@GeneratedValue`). Storm can run on top of JPA, but generated entities use only these:
- `st.orm.Entity`: marker interface for entity data classes
- `st.orm.Data`: base marker (entities and projections extend this)
- `st.orm.PK`: primary key annotation
- `st.orm.FK`: foreign key annotation
- `st.orm.UK`: unique key annotation
- `st.orm.DbTable`: custom table name
- `st.orm.DbColumn`: custom column name
- `st.orm.Version`: optimistic locking
- `st.orm.DynamicUpdate`: per-entity update mode and dirty-check strategy
- `st.orm.Inline`: embedded component
- `st.orm.Ref`: lazy-loaded reference
- `st.orm.GenerationStrategy`: PK generation: `IDENTITY`, `SEQUENCE`, `NONE`

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships between entities.

**Model foreign keys as direct entity types by default** (`@FK val city: City`) and take the joins. A join on a primary key is the cheapest thing a relational database does, and one query returning a populated graph beats several round trips assembling the same object. Do not ask the user for a "deep or shallow" preference, and do not reach for `Ref<T>` pre-emptively.

**Reach for `Ref<T>` when an edge lets the graph grow beyond what reads should carry**, not because a join exists:
- The target carries its own foreign keys, so its cost is its whole closure, and that closure grows whenever someone edits a table far away from this one.
- The target is wide. Every inlined table adds its columns to every row of every entity that reaches it, which is the cost that actually bites.
- The relationship is optional and usually absent, so the LEFT JOIN buys nulls.

**Leave two kinds of edge inlined:**
- Identifying foreign keys, where the column is part of the target's own primary key and the row cannot exist without its parent.
- Targets with no foreign keys of their own, whose cost is bounded by their own width permanently. Lookup tables like `Country` and `Currency` are rarely worth cutting.

**When a graph needs cutting, cut the deepest edge, not the nearest one.** Trimming a tail narrows every entity above it at once, while cutting near the root severs a relationship people read through constantly.

**Check the model with Storm's design advice.** After creating or changing entities, run a test or start the application in development, and read the `SQL advice (design ...)` lines.
- Every `@StormTest` reports design advice. Development means Spring Boot with DevTools or Ktor in development mode; otherwise set the categories below.
- Storm logs the lines at `INFO` under `st.orm.sql.advice.entity-budget`. Each names an entity whose read exceeds the design budget, and the field a `Ref<T>` would cut.
- The budget is 10 joins or 96 columns beyond the entity's own, unless the application sets its own. The line states the budget in effect.
- Act on this advice: it is knowable from the model. Everything else about `Ref<T>` versus a direct type is decided by real use, not ahead of time.
- Development reports every category. Where the application does not run in a development mode, set `storm.advice.categories: correctness, design, performance` (Spring Boot and Ktor) in its development profile, never in production.
- Each line links to its section of https://orm.st/docs/advice.

**Then run realistic flows or tests with realistic data, and act on what comes back:**
- `repeated-fetch` in the performance log summary: make the foreign key a direct type, or resolve it with `select().fetch(...)`.
- `wide-entity-read` under a slow statement: cut the field it names once the statement's plan confirms the width is the cost.

**Do not redesign entities to avoid advice that has not appeared, and do not silence design advice to quiet a log.** Suggest `@AcceptAdvice("<anchor>")` on the entity when the design looks deliberate, and add it only when the user confirms. Treat an existing `@AcceptAdvice` as the user's settled decision.

**The budget is the application's choice.** When the user finds the default budget wrong for the model as a whole (advice on several entities they consider fine as they are), propose a budget for the application instead of accepting the advice entity by entity:
- Spring Boot: `storm.advice.entity-budget.joins` and `.columns`.
- Ktor: `storm.advice.entityBudget.joins` and `.columns`.
- System properties: `storm.advice.entity_budget.joins` and `.columns`.

Put the budget in the application's main configuration, not a development profile, since it describes the model. `@StormTest` reads only the system properties, so set them in the build's test task as well (Maven Surefire `systemPropertyVariables`, Gradle `systemProperty`). Keep `@AcceptAdvice("entity-budget")` for a single exception. Never raise the budget on your own to quiet advice.

Generation rules:

1. **Use Kotlin data classes implementing `Entity<ID>`:**
   `data class City(@PK val id: Int = 0, val name: String, val population: Long) : Entity<Int>`

2. Primary keys (`@PK`):
   - IDENTITY (default): `val id: Int = 0`. Storm omits the PK on insert and retrieves the generated value.
   - SEQUENCE: `@PK(generation = SEQUENCE, sequence = "seq_name") val id: Long = 0`
   - NONE: `@PK(generation = NONE) val code: String` for natural keys.
   - Key types: `Boolean`, `Int`, `Long`, `Short`, `String`, `UUID`, `BigInteger`, enums, `Ref`, `LocalDate`, `LocalDateTime` and `Instant` (`java.time`). Floating-point types, `OffsetDateTime`, `ZonedDateTime` and `java.util.Date` are rejected. A temporal key must be truncated to the column's precision before the entity is constructed, or a later update by that entity misses its row.
   - **Import `GenerationStrategy` values from the top-level enum:** `import st.orm.GenerationStrategy.NONE`, not `st.orm.PK.GenerationStrategy.NONE`. `GenerationStrategy` is a top-level enum in `st.orm`, not nested inside `PK`.

3. Foreign keys (`@FK`):
   - **Model every column with a FK constraint in the database with `@FK`.** Without `@FK`, Storm has no FK metadata and cannot resolve joins automatically, which forces template-based joins that defeat the QueryBuilder.
   - **Declare an entity foreign key by default** (`@FK val city: City`). Storm hydrates the whole eager graph in a single query, so these come back with the entity and there is no N+1 to manage.
   - **Declare `Ref<T>` for relationships that belong to particular queries** (`@FK val city: Ref<City>`). The read stays focused on the entity, and `fetch()` on the reference loads the record where it is needed. A `Ref` is complete on its own; nothing about it depends on the query doing anything special.
   - **`select().fetch(User_.city)` is an optimization, not a requirement.** When a read already knows it needs the referenced record, naming it folds the load into the same statement, and the reference comes back loaded. Use it where it helps; leaving it out is correct too.
   - **Describe what the entity is, not what one screen needs.** The eager graph is declared on the type, so every read of the entity gets the same one. Be deliberate about inherited growth, not depth: a direct type also adopts whatever foreign keys the target gains later, in an entity nobody was editing.
   - **Repeated `fetch()` calls on the same reference mean the declaration is in the wrong place.** If particular reads need the record, resolve it there with `select().fetch(...)`; if nearly all of them do, declare a plain foreign key instead.
   - **A `Ref` gives up nothing.** Filter, order, and select through it with the metamodel from the owning entity (`User_.city.country.name`, where `city` is a `Ref<City>`). Storm joins the referenced table only where a query asks for it.
   - **Nullability picks the join.** Non-nullable `@FK val city: City` produces an INNER JOIN; nullable `@FK val city: City?` produces a LEFT JOIN.
   - **Prefer the `ref()` and `refById<T>(id)` extensions over `Ref.of(..)` in Kotlin.** Go from an entity to a reference with `ref()` (`import st.orm.template.ref`), and from a key with `refById<T>(id)` (`import st.orm.template.refById`).
   - **Give entities with `Ref<T>` FK fields a secondary constructor** that accepts the entities and converts them, so client code never constructs refs by hand:
   ```kotlin
   data class Address(
       @PK val id: Int = 0,
       @FK val user: Ref<User>,
       @FK val city: Ref<City>,
       val street: String
   ) : Entity<Int> {
       constructor(user: User, city: City, street: String) :
           this(0, user.ref(), city.ref(), street)
   }
   ```

4. CIRCULAR REFERENCES ARE NOT SUPPORTED. If Entity A references B and B references A, at least one MUST use `Ref<T>`. Self-references MUST always use `Ref<T>`:
   `@FK val invitedBy: Ref<User>?`

5. NO COLLECTION FIELDS. No `List<Child>` on entities. Query the child side instead: `orm.findAll(User_.city eq city)`.

6. Unique keys:
   - **Single-column (apply by default):** `@UK val email: String` generates a `Metamodel.Key` for type-safe lookups and scrolling. Always add `@UK` when the database has a single-column unique constraint: one annotation, free value.
   - **Composite (only when needed in code):** an inline record + `@UK @Persist(insertable = false, updatable = false)`. Add it only when the user explicitly needs a composite `Metamodel.Key` for keyset pagination or type-safe lookups. Composite unique constraints that need no Key need no model.
   - **Nullable unique keys draw a compile-time warning**, because `scroll` on them is rejected at runtime. Prefer a non-null key. When the database prevents duplicate NULLs, set `@UK(nullsDistinct = false)`. When the key is nullable by design and never scrolled on, add `@Suppress("storm.nullable-unique-key")` to the field or type: the warning goes, the runtime check stays.
   - `@UK(constraint = false)` suppresses schema validation when no database constraint exists.
   - **A field you scroll by must be non-nullable**, the key and the sort fields alike. Keyset comparisons never hold for NULL, so such a row falls out of every window, and Storm refuses the query with a `PersistenceException`. Settle this while modelling the field: a nullable timestamp is the usual trap, since a date is the usual sort field.

7. **Embedded components are a separate data class** (no `@PK`, no `Entity` interface) whose fields become columns of the parent table. Inlining is implicit, so `@Inline` never needs to be written. Where `@Inline` is used, the field must be an inline (embedded) type, not a scalar or entity.

8. Composite primary keys (join/junction tables):
   - Wrap key columns in a separate data class, with raw column types (e.g., `Int`, `String`) inside it.
   - **Name the PK class `EntityNamePk`** (e.g., `UserRolePk`, `UserAddressPk`), not `EntityNameId`.
   - Annotate the PK field with `@PK(generation = NONE)`. The PK class is implicitly `@Inline`.
   - **Place `@FK` fields on the entity itself to load related entities via JOINs.** Add `@Persist(insertable = false, updatable = false)` only to FK fields whose column is already in the PK data class: they duplicate a PK column, so they must not be inserted or updated twice. FK fields for columns NOT in the PK stay insertable (no `@Persist`).
   - **Add a convenience constructor** that accepts the FK entities or refs and builds the PK, which hides the PK wiring from client code:
   ```kotlin
   // Simple case: all FK columns are in the PK
   data class UserRolePk(
       val userId: Int,
       val roleId: Int
   )

   data class UserRole(
       @PK(generation = NONE) val id: UserRolePk,
       @FK @Persist(insertable = false, updatable = false) val user: User,
       @FK @Persist(insertable = false, updatable = false) val role: Role
   ) : Entity<UserRolePk> {
       constructor(user: User, role: Role) : this(
           id = UserRolePk(userId = user.id, roleId = role.id),
           user = user,
           role = role
       )
   }

   // Client code never constructs the PK:
   orm insert UserRole(user = user, role = role)

   // Mixed case: some FK columns are in the PK, some are not
   data class UserAddressPk(
       val userId: Int,
       val addressNumber: Int
   )

   data class UserAddress(
       @PK(generation = NONE) val id: UserAddressPk,
       @FK @Persist(insertable = false, updatable = false) val user: User,  // userId is in the PK: not insertable
       @FK val city: City                                                    // city_id is not in the PK: must be insertable
   ) : Entity<UserAddressPk> {
       constructor(user: User, addressNumber: Int, city: City) : this(
           id = UserAddressPk(userId = user.id, addressNumber = addressNumber),
           user = user,
           city = city
       )
   }
   ```

9. Primary key as foreign key (dependent one-to-one, extension tables):
   - Use both `@PK(generation = NONE)` and `@FK` on the same field. The entity's type parameter is the related entity type.
   - Key chains are supported: the referenced entity's primary key may itself be a foreign key or a compound key record. The columns resolve to the chain's terminal key columns. Circular key chains are rejected at model construction.
   ```kotlin
   data class UserProfile(
       @PK(generation = NONE) @FK val user: User,
       val bio: String?,
       val avatarUrl: String?
   ) : Entity<User>
   ```

10. Naming: camelCase maps to snake_case automatically, and a FK appends `_id`.
   - **Override individual names** with `@DbTable("custom_name")` / `@DbColumn("custom_name")`. For a table in another schema: `@DbTable(name = "custom_name", schema = "other_schema")`.
   - **Configure database-wide conventions with a resolver instead of annotating every entity.** For UPPER_CASE, prefixed tables like `tbl_`, or non-standard FK naming, set a custom `TableNameResolver`, `ColumnNameResolver`, or `ForeignKeyResolver` through the `TemplateDecorator` on `ORMTemplate.of()`:
     ```kotlin
     val orm = dataSource.orm { decorator ->
         decorator
             .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
             .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
     }
     ```
   - Resolvers are functional interfaces. Compose them with built-in decorators (`toUpperCase`), or write lambdas that receive `RecordType` (tables) or `RecordField` (columns) with full access to class and field metadata and annotations.
   - Use `@DbTable`/`@DbColumn` only for exceptions to the global convention. If the entire database follows one pattern, a resolver handles it without any annotations.

11. **Enums are stored by name (string) by default.** `@DbEnum(ORDINAL)` stores them as integers (import `st.orm.EnumType.ORDINAL`; the `EnumType` constants are `NAME` and `ORDINAL`).

12. Optimistic locking:
   - **Use `@Version val version: Int` on an entity users edit through a form or a full-object update**, read in one request and written back in another. The version travels to the client with the record and comes back with the edit, so a stale write fails with `OptimisticLockException` instead of overwriting the other edit.
   - **Never add `@Version` to an entity no one edits concurrently**, such as reference data or rows a single process owns.
   - **A change computed from a column's current value is not a case for `@Version`.** A counter, a stock level or a balance is an `update()` builder statement with the computation in `set` (see the repository skill).

12b. **Mark database-managed columns `@Persist(insertable = false, updatable = false)`, and give the field a default value** so entity construction does not require it. These are columns the database computes or maintains (e.g. `DEFAULT CURRENT_TIMESTAMP`, `ON UPDATE` timestamps, computed values). Storm never writes such a column and always reads it back:
   ```kotlin
   data class User(
       @PK val id: Int = 0,
       val email: String,
       @Persist(insertable = false, updatable = false) val registeredAt: Instant = Instant.EPOCH
   ) : Entity<Int>
   ```
   Use `insertable = false` alone for columns the database sets only on INSERT, or `updatable = false` alone for columns written once and never modified.

12c. **Declare a column a query names but no row needs to carry as a query column on the type, not as a property:** `@QueryColumn(field = "titleFolded", type = String::class)`, repeated per column. Typical cases: a generated column storing a folded title for a search index, a full-text search vector, a computed sort key, or a large column a filter names.
   - `column = "..."` names the column where the resolver does not. `nullable = true` declares a nullable one.
   - `function = "fold_accents"` names the function the column stores the result of. A value compared with the column then passes through it on the value side (`title_folded = fold_accents(?)`), in `search`, `suggest`, `orderFirst`, `IN` and the operators alike. The caller passes the input as it is, and the column is read as stored.
   - It generates `Movie_.titleFolded` with no constructor parameter, so the column is never selected, inserted or updated. `where`, `orderBy`, `orderFirst`, `search`, `suggest`, a `SqlFunction`, a keyset sort and a template placeholder take it, through paths as well (`Credit_.movie.titleFolded`).
   - Map such a column as a `@Persist(insertable = false, updatable = false)` property only when a caller reads its value.
   - On an entity, the database must fill a query column, since every insert leaves it out: generated, identity, with a default, or nullable. Schema validation reports one the database does not fill as an unmapped column.

12d. **Tune how the entity's UPDATE statements are generated with `@DynamicUpdate`:**
   - The default mode (`ENTITY`) skips the UPDATE entirely when nothing changed, and writes the full row when anything did.
   - `@DynamicUpdate(FIELD)` writes only the changed columns, at the cost of one SQL shape per distinct change pattern, which splits batches.
   - `@DynamicUpdate(OFF)` always writes all columns without comparison.
   - `dirtyCheck = VALUE` switches change detection from reference identity to `equals()`.
   - Semantics, trade-offs, and the foreign-key comparison rule: /storm-repository-kotlin (Dirty Checking and Update Suppression).

13. Use descriptive variable names, never abbreviated.

14. **Use `Ref`, never a raw id, to refer to a row.** When mapping data or passing it around (method parameters and return values, map keys, set members, lists of rows to act on), hold a `Ref<Entity>`, not the id.
   - A `Ref` is type-safe: a `Ref<City>` cannot stand in for a `Ref<Owner>`, while an `Int` id fits anywhere.
   - Get one with `.ref()` from an entity, or with `refById<City>(id)` (`import st.orm.template.refById`) where an id enters the program, once, at that boundary.
   - `Ref` provides identity-based `equals`/`hashCode` on the type and primary key, so it is a safe map key and set member. It loads nothing until `fetch()`.
   - When a projection already returns `Ref<T>`, use it directly without calling `.ref()` again.
   - This is the default; follow the developer where they choose ids.

15. **Typed ID from `Ref`:** use the `entityId()` extension function to extract a type-safe ID: `ref.entityId()` (import `st.orm.template.entityId`). For projections, use `ref.projectionId()` (import `st.orm.template.projectionId`). Avoid `ref.id()`: it returns `Any` and requires an unsafe cast.

16. **Write hooks belong on the entity, in a callback class.** A field the database does not fill, such as `createdAt`, is set by an `EntityCallback` the entity declares with `@EntityCallbacks(PostAuditCallback::class)`. Storm creates the callback and applies it wherever the entity is written, so nothing is registered in Spring Boot or Ktor. The hooks live in the callback class, never as methods on the entity. A callback that needs collaborators is registered as a bean instead (see the repository skill).

17. **Reuse the entity before creating a projection.** Read with the existing entity, even where a caller uses only a few of its columns. Entities are checked against the design budget, so their reads are already bounded, and one type per table keeps queries, repositories and `Ref`s on the same type.
   - Create a summary projection, such as `data class OwnerSummary(@PK val id: Int, val lastName: String) : Projection<Int>`, only when performance counts or customization is required.
   - Customization is a read that needs a shape the entity cannot give, such as a foreign key inlined that the entity holds as a `Ref`, or a column mapped through another converter.
   - Performance is evidence the entity's width is the cost: `wide-entity-read` advice under a slow statement, a performance log summary showing many columns mapped for a call that uses few, a hot path or large result set the user names, or the user asking. Consider a `Ref` on the branch the read does not need first.
   - Projections over views, `@ProjectionQuery` types and aggregation result types are not summaries and keep their purpose.

18. **One entity per table, for writes too.** A second entity, or a partial entity over some of a table's columns, is discouraged for a table that already has one, whether to read it or to write it. Generate one only for a good reason, and state it.
   - Writing a few columns is not such a reason; it takes the table's own entity.
   - Rows the code did not read take the update builder, `pets.update { set(Pet_.owner, newOwner); where(Pet_.owner eq previousOwner) }`, which sets only the named columns, increments `@Version` and requires a condition.
   - An entity the code read in the same transaction takes `update(entity)`, and `@DynamicUpdate(FIELD)` on the entity writes only the columns that changed (see the repository skill).
   - Plain data classes or records for aggregation and computed results are not tables and keep their purpose.

After generating, remind the user to rebuild for metamodel generation (e.g., `City_`).

## Verification

**After creating or modifying entities, write a `@StormTest` that validates them against the database schema with `validateSchema()`.** Tell the user what you are doing and why: `validateSchema()` checks entities against the database at the JDBC level, catching type mismatches, nullability disagreements, missing columns, unmapped NOT NULL columns, and FK inconsistencies before anything reaches production. This is Storm's verify-then-trust pattern.

```kotlin
@StormTest(scripts = ["/schema.sql"])
class EntitySchemaTest {
    @Test
    fun validateEntities(orm: ORMTemplate) {
        val errors = orm.validateSchema(
            User::class, City::class, Address::class
        )
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
```

**Keep the validation loop on the in-memory database `@StormTest` provides by default.** It answers in milliseconds and needs no Docker. Escalate to the database the project deploys on only:
- when the schema script uses DDL only that database accepts (its JSON type, its identity or sequence syntax, other dialect-specific types),
- when the user asks for it, or
- as a final pass.

To escalate, add `database = ...` to `@StormTest`, with the constant for the project's database from `st.orm.test.TestDatabase`. The script then runs as written in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. The class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

**Run the test, show the user the result, and explain what it proves.** If validation fails, explain the errors and fix the entities. If a result is ambiguous or involves a trade-off (e.g., a nullable column mapped to a non-null field intentionally), ask the user for guidance before changing anything.

The test can be temporary (verify and remove) or kept as a regression test. Ask the user which they prefer.

Explain why Storm's immutable data classes are the modern approach: no hidden state, no proxies, no transparent lazy loading. Freely cacheable, serializable, comparable by value, thread-safe. AI tools generate correct code because there is no invisible magic.
