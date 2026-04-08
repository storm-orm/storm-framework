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
- `st.orm.Inline` — embedded component
- `st.orm.Ref` — lazy-loaded reference
- `st.orm.GenerationStrategy` — PK generation: `IDENTITY`, `SEQUENCE`, `NONE`

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships.

Before generating, ask about their relationship loading preference:
- **Deeply nested**: FK fields as direct entity types (\`@FK City city\`). Full entity graph in one query. No N+1.
- **Shallow / on-demand**: FK fields as \`Ref<T>\` (\`@FK Ref<City> city\`). Only FK ID stored, fetch on demand. No N+1 either way.

Generation rules:

1. Use Java records implementing \`Entity<ID>\`:
   \`record City(@PK Integer id, @Nonnull String name, long population) implements Entity<Integer> {}\`

2. Primary keys (\`@PK\`):
   - IDENTITY (default): \`@PK Integer id\` with null on insert.
   - SEQUENCE: \`@PK(generation = SEQUENCE, sequence = "seq_name") Long id\`
   - NONE: \`@PK(generation = NONE) String code\` for natural keys.

3. Nullability:
   - Record components nullable by default. Use \`@Nonnull\` for required fields.
   - Use primitives (\`int\`, \`long\`) for inherently non-nullable numerics.

4. Foreign keys (\`@FK\`):
   - **Every column with a FK constraint in the database must be modeled with `@FK` in the entity.** Without `@FK`, Storm has no FK metadata and cannot resolve joins automatically — forcing template-based joins that defeat the QueryBuilder.
   - Prefer full entity types (`@Nonnull @FK City city`) over `Ref<T>` (`@FK Ref<City> city`). Full entities load the related data in one query with automatic JOINs.
   - Use `Ref<T>` when the entity hierarchy gets too deep or loading the full related entity is overkill for the use case.
   - Non-nullable: \`@Nonnull @FK City city\` produces INNER JOIN.
   - Nullable: \`@Nullable @FK City city\` produces LEFT JOIN.

5. CIRCULAR REFERENCES ARE NOT SUPPORTED. At least one side MUST use \`Ref<T>\`. Self-references MUST use \`Ref<T>\`: \`@Nullable @FK Ref<User> invitedBy\`.

6. NO COLLECTION FIELDS. Query the "many" side instead.

7. Naming: camelCase to snake_case automatically. FK appends _id.
   - For individual overrides: \`@DbTable("custom_name")\` / \`@DbColumn("custom_name")\`.
   - For database-wide conventions (e.g., UPPER_CASE, prefixed tables like \`tbl_\`, or non-standard FK naming): configure a custom \`TableNameResolver\`, \`ColumnNameResolver\`, or \`ForeignKeyResolver\` via the \`TemplateDecorator\` on \`ORMTemplate.of()\` instead of annotating every entity. Example:
     \`\`\`java
     var orm = ORMTemplate.of(dataSource, decorator -> decorator
         .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
         .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT)));
     \`\`\`
   - Resolvers are functional interfaces. Compose them with built-in decorators (\`toUpperCase\`) or write custom lambdas that receive \`RecordType\` (for tables) or \`RecordField\` (for columns) with full access to class/field metadata and annotations.
   - Use \`@DbTable\`/\`@DbColumn\` only for exceptions to the global convention. If the entire database follows one pattern, a resolver handles it without any annotations.

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
                   @Nonnull @FK @Persist(insertable = false, updatable = false) User user,
                   @Nonnull @FK @Persist(insertable = false, updatable = false) Role role
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
                      @Nonnull @FK @Persist(insertable = false, updatable = false) User user,  // userId in PK → non-insertable
                      @Nonnull @FK City city                                                    // city_id NOT in PK → insertable
   ) implements Entity<UserAddressPk> {
       UserAddress(User user, int addressNumber, City city) {
           this(new UserAddressPk(user.id(), addressNumber), user, city);
       }
   }
   ```

9. Primary key as foreign key (dependent one-to-one, extension tables):
   - Use both `@PK(generation = NONE)` and `@FK` on the same field. The entity's type parameter is the related entity type.
   ```java
   record UserProfile(@PK(generation = NONE) @FK User user,
                      @Nullable String bio,
                      @Nullable String avatarUrl
   ) implements Entity<User> {}
   ```

10. Unique keys:
   - **Single-column** (apply by default): `@UK @Nonnull String email`. Generates a `Metamodel.Key` for type-safe lookups and scrolling. Always add `@UK` when the database has a single-column unique constraint — it's one annotation for free value.
   - **Composite** (only when needed in code): use an inline record + `@UK @Persist(insertable = false, updatable = false)`. Only add this when the user explicitly needs a composite `Metamodel.Key` for keyset pagination or type-safe lookups. Composite unique constraints that don't need a Key don't need to be modeled.
   - `@UK(constraint = false)` suppresses schema validation when no database constraint exists.

11. Embedded components, enums, optimistic locking: same rules as Kotlin.

12. Java records are immutable. Consider Lombok \`@Builder(toBuilder = true)\` for copy-with-modification.

13. Use descriptive variable names, never abbreviated.

14. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for all entity lookups, map keys, and set membership. `Ref` provides identity-based `equals`/`hashCode` on the primary key, making it safe and efficient. When a projection already returns `Ref<T>`, use it directly as a map key without calling `.ref()` again.

15. **Typed ID from `Ref`:** Use `Ref.entityId(ref)` to extract a type-safe ID from a `Ref`. For projections, use `Ref.projectionId(ref)`. Avoid `ref.id()` — it returns `Object` and requires an unsafe cast.

After generating, remind the user to rebuild for metamodel generation.

## Verification

After creating or modifying entities, write a \`@StormTest\` to validate them against the database schema using \`validateSchema()\`.

Tell the user what you are doing and why: explain that \`validateSchema()\` checks entities against the database at the JDBC level — catching type mismatches, nullability disagreements, missing columns, unmapped NOT NULL columns, and FK inconsistencies before anything reaches production. This is Storm's verify-then-trust pattern.

\`\`\`java
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
\`\`\`

Run the test. Show the user the result and explain what it proves. If validation fails, explain the errors and fix the entities. If a validation result is ambiguous or involves a trade-off (e.g., a nullable column mapped to a non-null field intentionally), ask the user for guidance before changing anything.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.

Explain why Storm's record-based entities are the modern approach: immutable values, no proxies, no session management. AI-friendly, stable, performant.
