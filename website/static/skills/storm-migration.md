---
name: storm-migration
description: Write Flyway or Liquibase migration SQL that matches Storm's naming conventions. Use when a schema change accompanies an entity change.
---

Help the user write database migration SQL for Storm entities.

Storm does NOT perform schema migration. Use Flyway or Liquibase.

Ask: migration tool, schema change needed, database dialect, relevant entity definition.

Storm naming conventions for DDL:
- User class -> user table
- UserRole class -> user_role table
- birthDate field -> birth_date column
- city FK field -> city_id column
Override with @DbTable or @DbColumn.

PK generation mapping:
- `IDENTITY`: the database's identity or auto-increment column.
- `SEQUENCE`: a database sequence (`CREATE SEQUENCE`), on databases that have sequences.
- `NONE`: no generation.

Column types:
- **`@Json` fields: use the database's JSON column type, or a text type holding JSON where it has none.**
- Enums as strings: VARCHAR. Ordinal: INTEGER.
- `@Version`: INTEGER or TIMESTAMP.

Constraints:
- FK constraints matching `@FK` annotations.
- UNIQUE constraints for `@UK` fields.
- NOT NULL matching entity nullability.

Indexes:
- **Index FK columns and the columns frequent queries use.**
- **Add the index a `missing-index` advice names, after checking the plan.** Such advice on a slow statement's line in `st.orm.sql.slow` names a table no index serves for the columns the statement filters, groups or sorts by, and the index that would. Where it names two, one finds the matching rows and one reads them in group or sort order; check the plan of each against the target database's data and add the one the plan uses. Columns the advice names as `appended` let the database read the index alone; add them only where the read's cost warrants the wider key, and never where the advice says the key would pass the database's limit. Check the statement's plan on the target database, then add that index in a new migration.
- **Never add an index for advice that has not appeared.** On a small table or an unselective column, reading the table is right.
- **Add the index an `unindexed-foreign-key` advice names.** Such advice on a slow delete names a foreign key of another table that references the deleted table and that no index starts with, so the database reads that table in full for every deleted row. Add the index in a new migration. Several databases never create this index themselves.

Searched columns:
- **Give each column a query matches with `search` or an ignore-case text operator (`containsIgnoreCase`, `CONTAINS_IGNORE_CASE`, ...) a substring index over exactly `LOWER(column)`.** Storm compares `LOWER(column) LIKE LOWER('%word%')`, which a B-tree index cannot serve. Use the target database's substring index (a trigram or n-gram index), one per searched column, also on related tables the search reaches.
- **On a table that already holds data, refresh its statistics in the same migration.** Otherwise the planner may keep reading every row. Verify with the database's query plan, for the parameterized statement, that the index is used.
- **Where the search must ignore accents and the column's collation does not, wrap the database's accent-removing function in a function the database accepts in an index** (marked immutable where it requires that) and create the substring index over `LOWER(fold_accents(column))`. The query names the same function through `SqlFunction`. The statement per database is in the search documentation under Ignoring Accents.
- **Store the folded text in a generated column only where the recheck of the function on every candidate row dominates.** Then index `LOWER(title_folded)` and declare the column as a query column naming the function, `@QueryColumn(field = "titleFolded", type = String::class, function = "fold_accents")`, so no row carries it and a compared value is folded by the database (see /storm-entity).

Testing on the in-memory database:
- **Always give NUMERIC and DECIMAL columns a precision and scale** (`NUMERIC(4, 1)`, not `NUMERIC`). The in-memory database `@StormTest` provides by default defaults NUMERIC to scale 0, which silently truncates decimals: 8.7 becomes 9. This is difficult to diagnose.
- **Verify a migration that uses target-database DDL on the project's database.** DDL only the target database accepts (its JSON type, its identity or sequence syntax) does not run on the in-memory test database. `@StormTest(database = ..., scripts = [...])`, with the constant for the project's database, runs the same test in a Testcontainers-managed container of that database, which also removes the precision caveat. It requires the database's Testcontainers module and driver in test scope, and Docker (see /storm-setup).

After writing a migration, rebuild the project for metamodel regeneration.

After generating or updating entities and migrations, offer to write a temporary `@StormTest` that verifies the entities and migration are consistent:
```kotlin
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Add database = ... (the constant for the project's database) to run the migration as written
// instead of on the in-memory test database.
@StormTest(scripts = ["/V1__create_users.sql"])
class MigrationVerificationTest {
    @Test
    fun validateEntities(orm: ORMTemplate) {
        // Kotlin: vararg KClass form. (Java: orm.validateSchema(List.of(User.class, City.class)))
        val errors = orm.validateSchema(User::class, City::class)
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
```
