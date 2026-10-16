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
| Storm | PostgreSQL | MySQL | Oracle |
|---|---|---|---|
| IDENTITY | SERIAL | AUTO_INCREMENT | GENERATED ALWAYS AS IDENTITY |
| SEQUENCE | CREATE SEQUENCE | N/A | CREATE SEQUENCE |
| NONE | No generation | No generation | No generation |

JSON column types per dialect:
| Database | Column Type |
|----------|-------------|
| PostgreSQL | `JSONB` |
| MySQL | `JSON` |
| MariaDB | `JSON` |
| Oracle | `JSON` |
| MS SQL Server | `NVARCHAR(MAX)` |
| H2 | `CLOB` |

Tips:
- FK constraints matching @FK annotations
- UNIQUE constraints for @UK fields
- Indexes on FK columns and frequent queries
- Columns a query matches with `search` or an ignore-case text operator (`containsIgnoreCase`, `CONTAINS_IGNORE_CASE`, ...): Storm compares `LOWER(column) LIKE LOWER('%word%')`, which a B-tree index cannot serve. Create the target database's substring index (a trigram or n-gram index) over exactly `LOWER(column)`, one per searched column, also on related tables the search reaches. On a table that already holds data, refresh its statistics in the same migration, or the planner may keep reading every row; verify with the database's query plan, for the parameterized statement, that the index is used. Where the search must ignore accents and the column's collation does not, add a generated column holding the text without accents (wrapping the database's accent function in an immutable one where needed) and put the substring index on that column instead
- NOT NULL matching entity nullability
- Enums as strings: VARCHAR. Ordinal: INTEGER.
- @Version: INTEGER or TIMESTAMP
- `@Json` fields: use the correct JSON column type for the target database (see table above)
- **H2 NUMERIC/DECIMAL precision:** Always specify precision and scale for NUMERIC/DECIMAL columns (e.g., `NUMERIC(4, 1)`, not `NUMERIC`). H2 defaults to scale 0, which silently truncates decimals — values like 8.7 become 9. This affects `@StormTest` with H2 and is difficult to diagnose.
- **Target-database DDL:** A migration written for the target database (`JSONB`, `SERIAL`, `AUTO_INCREMENT`, `IDENTITY(1,1)`, sequences) may not run on H2 at all. Verify it on the target database instead: `@StormTest(database = POSTGRESQL, scripts = [...])` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`) runs the same test in a Testcontainers-managed container of that database, which also removes the H2 precision caveat. Requires the database's Testcontainers module and driver in test scope and Docker (see /storm-setup).

After writing a migration, rebuild the project for metamodel regeneration.

After generating or updating entities and migrations, offer to write a temporary `@StormTest` to verify that the entities and migration are consistent:
```kotlin
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Add database = POSTGRESQL (or the project's database) to run the migration as written instead of on H2.
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
