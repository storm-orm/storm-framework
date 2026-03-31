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
- NOT NULL matching entity nullability
- Enums as strings: VARCHAR. Ordinal: INTEGER.
- @Version: INTEGER or TIMESTAMP
- `@Json` fields: use the correct JSON column type for the target database (see table above)
- **H2 NUMERIC/DECIMAL precision:** Always specify precision and scale for NUMERIC/DECIMAL columns (e.g., `NUMERIC(4, 1)`, not `NUMERIC`). H2 defaults to scale 0, which silently truncates decimals — values like 8.7 become 9. This affects `@StormTest` with H2 and is difficult to diagnose.

After writing a migration, rebuild the project for metamodel regeneration.

After generating or updating entities and migrations, offer to write a temporary `@StormTest` to verify that the entities and migration are consistent:
```kotlin
@StormTest(scripts = ["V1__create_orders.sql"])
class MigrationVerificationTest {
    @Test
    fun validateEntities(orm: ORMTemplate) {
        val errors = orm.validateSchema(listOf(
            Order::class.java,
            OrderLine::class.java
        ))
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
```
