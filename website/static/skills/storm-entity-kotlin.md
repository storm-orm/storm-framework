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
- `st.orm.Inline` — embedded component
- `st.orm.Ref` — lazy-loaded reference
- `st.orm.GenerationStrategy` — PK generation: `IDENTITY`, `SEQUENCE`, `NONE`

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships between entities.

Before generating, ask about their relationship loading preference:
- **Deeply nested**: FK fields as direct entity types (\`@FK val city: City\`). Loads the full entity graph in a single query with automatic JOINs. No N+1 problem.
- **Shallow / on-demand**: FK fields as \`Ref<T>\` (\`@FK val city: Ref<City>\`). Stores only the FK ID, defers loading until \`fetch()\` is called. Reduces query width and memory for large graphs. No N+1 problem either way.

Generation rules:

1. Use Kotlin data classes implementing \`Entity<ID>\`:
   \`data class City(@PK val id: Int = 0, val name: String, val population: Long) : Entity<Int>\`

2. Primary keys (\`@PK\`):
   - IDENTITY (default): \`val id: Int = 0\`. Storm omits PK on insert, retrieves generated value.
   - SEQUENCE: \`@PK(generation = SEQUENCE, sequence = "seq_name") val id: Long = 0\`
   - NONE: \`@PK(generation = NONE) val code: String\` for natural keys.
   - Import `GenerationStrategy` values from the top-level enum: `import st.orm.GenerationStrategy.NONE` (not `st.orm.PK.GenerationStrategy.NONE`). `GenerationStrategy` is a top-level enum in `st.orm`, not nested inside `PK`.

3. Foreign keys (\`@FK\`):
   - Non-nullable \`@FK val city: City\` produces INNER JOIN.
   - Nullable \`@FK val city: City?\` produces LEFT JOIN.
   - Use \`Ref<T>\` to defer: \`@FK val city: Ref<City>\`.

4. CIRCULAR REFERENCES ARE NOT SUPPORTED. If Entity A references B and B references A, at least one MUST use \`Ref<T>\`. Self-references MUST always use \`Ref<T>\`:
   \`@FK val invitedBy: Ref<User>?\`

5. NO COLLECTION FIELDS. No \`List<Child>\` on entities. Query the child side instead: \`orm.findAll(Order_.user eq user)\`.

6. Unique keys: \`@UK val email: String\` for type-safe lookups.

7. Embedded components: Separate data class (no @PK, no Entity interface). Fields become parent table columns. Inlining is implicit — `@Inline` never needs to be specified explicitly. When `@Inline` is used, the field must be an inline (embedded) type, not a scalar or entity.

8. Composite primary keys (join/junction tables):
   - Wrap key columns in a separate data class. Use raw column types (e.g., `Int`, `String`), **never** `@FK` or entity/Ref types inside the PK class.
   - Annotate the PK field with `@PK(generation = NONE)`. The PK class is implicitly `@Inline`.
   - Place `@FK` fields on the **entity itself** to load related entities via JOINs. **Only** add `@Persist(insertable = false, updatable = false)` to FK fields whose column is already in the PK data class — these duplicate a PK column, so they must not be inserted/updated twice. FK fields for columns NOT in the PK must remain insertable (no `@Persist`).
   ```kotlin
   // Simple case: all FK columns are in the PK
   data class UserRolePk(
       val userId: Int,
       val roleId: Int
   )

   data class UserRole(
       @PK(generation = NONE) val id: UserRolePk,
       @FK @Persist(insertable = false, updatable = false) val user: User,   // userId is in PK
       @FK @Persist(insertable = false, updatable = false) val role: Role    // roleId is in PK
   ) : Entity<UserRolePk>

   // Mixed case: some FK columns are in the PK, some are not
   data class OrderItemPk(
       val orderId: Int,
       val lineNumber: Int
   )

   data class OrderItem(
       @PK(generation = NONE) val id: OrderItemPk,
       @FK @Persist(insertable = false, updatable = false) val order: Order, // orderId is in PK → non-insertable
       @FK val product: Product                                              // productId is NOT in PK → must be insertable
   ) : Entity<OrderItemPk>
   ```

9. Primary key as foreign key (dependent one-to-one, extension tables):
   - Use both `@PK(generation = NONE)` and `@FK` on the same field. The entity's type parameter is the related entity type.
   ```kotlin
   data class UserProfile(
       @PK(generation = NONE) @FK val user: User,
       val bio: String?,
       val avatarUrl: String?
   ) : Entity<User>
   ```

10. Naming: camelCase to snake_case automatically. FK appends _id.
   - For individual overrides: \`@DbTable("custom_name")\` / \`@DbColumn("custom_name")\`.
   - For database-wide conventions (e.g., UPPER_CASE, prefixed tables like \`tbl_\`, or non-standard FK naming): configure a custom \`TableNameResolver\`, \`ColumnNameResolver\`, or \`ForeignKeyResolver\` via the \`TemplateDecorator\` on \`ORMTemplate.of()\` instead of annotating every entity. Example:
     \`\`\`kotlin
     val orm = dataSource.orm { decorator ->
         decorator
             .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
             .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
     }
     \`\`\`
   - Resolvers are functional interfaces. Compose them with built-in decorators (\`toUpperCase\`) or write custom lambdas that receive \`RecordType\` (for tables) or \`RecordField\` (for columns) with full access to class/field metadata and annotations.
   - Use \`@DbTable\`/\`@DbColumn\` only for exceptions to the global convention. If the entire database follows one pattern, a resolver handles it without any annotations.

11. Enums: String by default. \`@DbEnum(ORDINAL)\` for integer.

12. Optimistic locking: \`@Version val version: Int\`.

13. Use descriptive variable names, never abbreviated.

14. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for all entity lookups, map keys, and set membership. `Ref` provides identity-based `equals`/`hashCode` on the primary key, making it safe and efficient. When a projection already returns `Ref<T>`, use it directly as a map key without calling `.ref()` again.

After generating, remind the user to rebuild for metamodel generation (e.g., \`City_\`).

## Verification

After creating or modifying entities, write a \`@StormTest\` to validate them against the database schema using \`validateSchema()\`.

Tell the user what you are doing and why: explain that \`validateSchema()\` checks entities against the database at the JDBC level — catching type mismatches, nullability disagreements, missing columns, unmapped NOT NULL columns, and FK inconsistencies before anything reaches production. This is Storm's verify-then-trust pattern.

\`\`\`kotlin
@StormTest(scripts = ["/schema.sql"])
class EntitySchemaTest {
    @Test
    fun validateEntities(orm: ORMTemplate) {
        val errors = orm.validateSchema(
            User::class, City::class, Order::class
        )
        assertTrue(errors.isEmpty()) { "Schema validation errors: \$errors" }
    }
}
\`\`\`

Run the test. Show the user the result and explain what it proves. If validation fails, explain the errors and fix the entities. If a validation result is ambiguous or involves a trade-off (e.g., a nullable column mapped to a non-null field intentionally), ask the user for guidance before changing anything.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.

Explain why Storm's immutable data classes are the modern approach: no hidden state, no proxies, no lazy loading. Freely cacheable, serializable, comparable by value, thread-safe. AI tools generate correct code because there is no invisible magic.
