Compare Storm entities against the live database schema.

1. Find all entity classes (Kotlin data classes or Java records implementing Entity)
2. Call `list_tables` to get all tables
3. Call `describe_table` for each entity's table
4. Report mismatches:
   - Tables without entities (reverse direction: DB tables that have no corresponding entity class)
   - Entities without tables
   - Column type mismatches (incompatible types are errors; narrowing like Long mapped to INTEGER is a warning)
   - Missing columns (entity field has no matching DB column)
   - Extra columns that are NOT NULL without a default value (these would cause INSERT failures)
   - FK/`@FK` mismatches (FK referencing wrong table is an error; missing FK constraint is a warning)
   - Nullability differences (entity non-null but DB nullable is a warning)
   - Missing unique constraints for @UK fields
   - Primary key issues: mismatch between entity and DB PK columns is an error; missing PK constraint is a warning
   - Missing sequences for @PK(generation = SEQUENCE) fields

Respect suppression annotations:
- Skip types annotated with `@DbIgnore` or `@ProjectionQuery`
- Skip fields annotated with `@DbIgnore`
- `@PK(constraint = false)` suppresses missing PK constraint warning
- `@UK(constraint = false)` suppresses missing unique constraint warning
- `@FK(constraint = false)` suppresses missing FK constraint warning
- Polymorphic FKs (sealed interface targets) cannot have standard DB FK constraints; skip FK validation for these

Projections intentionally map a subset of columns; do not flag extra DB columns for projections, only for entities.

After LLM-assisted entity changes, write a targeted test to verify only the affected entities:
```kotlin
@StormTest(scripts = ["schema.sql"])
class EntitySchemaTest {
    @Test
    fun validateNewEntities(orm: ORMTemplate) {
        // Validate only the specific entities that were created or modified.
        val errors = orm.validateSchema(listOf(
            User::class.java,
            City::class.java,
            Address::class.java
        ))
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
```
The test can be temporary. Run it to verify, then remove it or keep it as a regression test. If validation fails, use the error messages to fix the entities and re-run.
