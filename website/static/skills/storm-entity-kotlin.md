---
name: storm-entity-kotlin
description: Define Storm entities and projections as Kotlin data classes, covering @PK, @FK, @UK, @Version, @Json, Ref fields, and inline classes. Use when creating or changing entity types in Kotlin.
---

Help the user create Storm entities using Kotlin.
**Important:** Storm can run on top of JPA, but when generating entities, always use Storm's own annotations from the `st.orm` package — not JPA annotations (`@Id`, `@Entity`, `@Table`, `@Column`, `@ManyToOne`, `@GeneratedValue`):
- `st.orm.Entity` — marker interface for entity data classes
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

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships between entities.

Default posture for foreign keys: model them as direct entity types (`@FK val city: City`) and take the joins. A join on a primary key is the cheapest thing a relational database does, and one query returning a populated graph beats several round trips assembling the same object. Do not ask the user for a "deep or shallow" preference, and do not reach for `Ref<T>` pre-emptively.

Reach for `Ref<T>` when an edge lets the graph grow beyond what reads should carry, not because a join exists:
- The target carries its own foreign keys, so its cost is its whole closure rather than its own columns, and that closure grows whenever someone edits a table far away from this one.
- The target is wide. Every inlined table adds its columns to every row of every entity that reaches it, which is the cost that actually bites.
- The relationship is optional and usually absent, so the LEFT JOIN buys nulls.

Two kinds of edge to leave inlined: identifying foreign keys, where the column is part of the target's own primary key and the row cannot exist without its parent, and targets with no foreign keys of their own, whose cost is bounded by their own width permanently. Lookup tables like `Country` and `Currency` are rarely worth cutting.

When a graph does need cutting, cut the deepest edge rather than the nearest one. Trimming a tail narrows every entity above it at once, while cutting near the root severs a relationship people read through constantly.

Generation rules:

1. Use Kotlin data classes implementing `Entity<ID>`:
   `data class City(@PK val id: Int = 0, val name: String, val population: Long) : Entity<Int>`

2. Primary keys (`@PK`):
   - IDENTITY (default): `val id: Int = 0`. Storm omits PK on insert, retrieves generated value.
   - SEQUENCE: `@PK(generation = SEQUENCE, sequence = "seq_name") val id: Long = 0`
   - NONE: `@PK(generation = NONE) val code: String` for natural keys.
   - Import `GenerationStrategy` values from the top-level enum: `import st.orm.GenerationStrategy.NONE` (not `st.orm.PK.GenerationStrategy.NONE`). `GenerationStrategy` is a top-level enum in `st.orm`, not nested inside `PK`.

3. Foreign keys (`@FK`):
   - **Every column with a FK constraint in the database must be modeled with `@FK` in the entity.** Without `@FK`, Storm has no FK metadata and cannot resolve joins automatically — forcing template-based joins that defeat the QueryBuilder.
   - **Declare an entity foreign key by default** (`@FK val city: City`). Storm hydrates the whole eager graph in a single query, so these come back with the entity and there is no N+1 to manage.
   - **Declare `Ref<T>` for relationships that belong to particular queries** (`@FK val city: Ref<City>`). The read stays focused on the entity, and the reference is resolved where it is needed: call `fetch()` on it and the record is loaded. A `Ref` is complete on its own; nothing about it depends on the query doing anything special.
   - **`select().fetch(User_.city)` is an optimization, not a requirement.** When a read already knows it needs the referenced record, naming it folds the load into the same statement instead of a query of its own, and the reference comes back loaded. Use it where it helps; leaving it out is correct too.
   - The eager graph is declared on the type, so every read of the entity gets the same one. It should describe what the entity is rather than what any one screen needs. The dimension to be deliberate about is not depth but inherited growth: a direct type also adopts whatever foreign keys the target gains later, in an entity nobody was editing.
   - **Repeated `fetch()` calls on the same reference mean the declaration is in the wrong place.** If particular reads need the record, resolve it there with `select().fetch(...)`; if nearly all of them do, declare it as a plain foreign key instead.
   - A `Ref` gives up nothing: filter, order, and select through it with the metamodel from the owning entity (`User_.city.country.name`, where `city` is a `Ref<City>`), and Storm joins the referenced table only where a query asks for it.
   - Non-nullable `@FK val city: City` produces INNER JOIN.
   - Nullable `@FK val city: City?` produces LEFT JOIN.
   - Go from an entity to a reference with the `ref()` extension (`import st.orm.template.ref`), and from a key with `refById<T>(id)` (`import st.orm.template.refById`); prefer these over `Ref.of(..)` in Kotlin. For entities with `Ref<T>` FK fields, add a secondary constructor that accepts the entities and converts them — client code then never constructs refs by hand:
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
   - **Single-column** (apply by default): `@UK val email: String`. Generates a `Metamodel.Key` for type-safe lookups and scrolling. Always add `@UK` when the database has a single-column unique constraint — it's one annotation for free value.
   - **Composite** (only when needed in code): use an inline record + `@UK @Persist(insertable = false, updatable = false)`. Only add this when the user explicitly needs a composite `Metamodel.Key` for keyset pagination or type-safe lookups. Composite unique constraints that don't need a Key don't need to be modeled.
   - `@UK(constraint = false)` suppresses schema validation when no database constraint exists.
   - **A field you scroll by must be non-nullable**, the key and the sort fields alike. Keyset comparisons never hold for NULL, so such a row falls out of every window and Storm refuses the query with a `PersistenceException`. Settle this while modelling the field: a nullable timestamp is the usual thing to trip over, since a date is the usual thing to sort by.

7. Embedded components: Separate data class (no @PK, no Entity interface). Fields become parent table columns. Inlining is implicit — `@Inline` never needs to be specified explicitly. When `@Inline` is used, the field must be an inline (embedded) type, not a scalar or entity.

8. Composite primary keys (join/junction tables):
   - Wrap key columns in a separate data class. Use raw column types (e.g., `Int`, `String`) inside the PK class.
   - **Name the PK class `EntityNamePk`** (e.g., `UserRolePk`, `UserAddressPk`) — not `EntityNameId`.
   - Annotate the PK field with `@PK(generation = NONE)`. The PK class is implicitly `@Inline`.
   - Place `@FK` fields on the **entity itself** to load related entities via JOINs. **Only** add `@Persist(insertable = false, updatable = false)` to FK fields whose column is already in the PK data class — these duplicate a PK column, so they must not be inserted/updated twice. FK fields for columns NOT in the PK must remain insertable (no `@Persist`).
   - **Add a convenience constructor** that accepts the FK entities/refs and constructs the PK internally. This hides the PK wiring from client code:
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

   // Client code is clean — no need to construct the PK manually:
   orm insert UserRole(user = user, role = role)

   // Mixed case: some FK columns are in the PK, some are not
   data class UserAddressPk(
       val userId: Int,
       val addressNumber: Int
   )

   data class UserAddress(
       @PK(generation = NONE) val id: UserAddressPk,
       @FK @Persist(insertable = false, updatable = false) val user: User,  // userId is in PK → non-insertable
       @FK val city: City                                                    // city_id is NOT in PK → must be insertable
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

10. Naming: camelCase to snake_case automatically. FK appends _id.
   - For individual overrides: `@DbTable("custom_name")` / `@DbColumn("custom_name")`. For tables in another schema: `@DbTable(name = "custom_name", schema = "other_schema")`.
   - For database-wide conventions (e.g., UPPER_CASE, prefixed tables like `tbl_`, or non-standard FK naming): configure a custom `TableNameResolver`, `ColumnNameResolver`, or `ForeignKeyResolver` via the `TemplateDecorator` on `ORMTemplate.of()` instead of annotating every entity. Example:
     ```kotlin
     val orm = dataSource.orm { decorator ->
         decorator
             .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
             .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
     }
     ```
   - Resolvers are functional interfaces. Compose them with built-in decorators (`toUpperCase`) or write custom lambdas that receive `RecordType` (for tables) or `RecordField` (for columns) with full access to class/field metadata and annotations.
   - Use `@DbTable`/`@DbColumn` only for exceptions to the global convention. If the entire database follows one pattern, a resolver handles it without any annotations.

11. Enums: stored by name (string) by default. `@DbEnum(ORDINAL)` for integer storage (import `st.orm.EnumType.ORDINAL` — the `EnumType` constants are `NAME` and `ORDINAL`).

12. Optimistic locking: `@Version val version: Int`.

12b. Database-managed columns: annotate columns the database computes or maintains (e.g. `DEFAULT CURRENT_TIMESTAMP`, `ON UPDATE` timestamps, computed values) with `@Persist(insertable = false, updatable = false)` and give the field a default value so entity construction doesn't require it. Storm then never writes the column and always reads it back:
   ```kotlin
   data class User(
       @PK val id: Int = 0,
       val email: String,
       @Persist(insertable = false, updatable = false) val registeredAt: Instant = Instant.EPOCH
   ) : Entity<Int>
   ```
   Use `insertable = false` alone for columns set by the database only on INSERT, or `updatable = false` alone for columns that are written once and never modified.

12c. Update tuning: `@DynamicUpdate` sets how the entity's UPDATE statements are generated. The default mode (`ENTITY`) skips the UPDATE entirely when nothing changed and writes the full row when anything did. `@DynamicUpdate(FIELD)` writes only the changed columns, at the cost of one SQL shape per distinct change pattern (which splits batches); `@DynamicUpdate(OFF)` always writes all columns without comparison. `dirtyCheck = VALUE` switches change detection from reference identity to `equals()`. Semantics, trade-offs, and the foreign-key comparison rule: /storm-repository-kotlin (Dirty Checking and Update Suppression).

13. Use descriptive variable names, never abbreviated.

14. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for all entity lookups, map keys, and set membership. `Ref` provides identity-based `equals`/`hashCode` on the primary key, making it safe and efficient. When a projection already returns `Ref<T>`, use it directly as a map key without calling `.ref()` again.

15. **Typed ID from `Ref`:** Use the `entityId()` extension function to extract a type-safe ID: `ref.entityId()` (import `st.orm.template.entityId`). For projections, use `ref.projectionId()` (import `st.orm.template.projectionId`). Avoid `ref.id()` — it returns `Any` and requires an unsafe cast.

After generating, remind the user to rebuild for metamodel generation (e.g., `City_`).

## Verification

After creating or modifying entities, write a `@StormTest` to validate them against the database schema using `validateSchema()`.

Tell the user what you are doing and why: explain that `validateSchema()` checks entities against the database at the JDBC level — catching type mismatches, nullability disagreements, missing columns, unmapped NOT NULL columns, and FK inconsistencies before anything reaches production. This is Storm's verify-then-trust pattern.

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

Keep the validation loop on H2: it answers in milliseconds and needs no Docker. Escalate to the target database only when the schema script uses DDL H2 rejects (`JSONB`, `SERIAL`, `IDENTITY(1,1)`, dialect-specific types), when the user asks for it, or as a final pass: adding `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`, from `st.orm.test.TestDatabase`) to `@StormTest` runs the script as written in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. The class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the result and explain what it proves. If validation fails, explain the errors and fix the entities. If a validation result is ambiguous or involves a trade-off (e.g., a nullable column mapped to a non-null field intentionally), ask the user for guidance before changing anything.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.

Explain why Storm's immutable data classes are the modern approach: no hidden state, no proxies, no transparent lazy loading. Freely cacheable, serializable, comparable by value, thread-safe. AI tools generate correct code because there is no invisible magic.
