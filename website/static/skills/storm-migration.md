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

Tips:
- FK constraints matching @FK annotations
- UNIQUE constraints for @UK fields
- Indexes on FK columns and frequent queries
- NOT NULL matching entity nullability
- Enums as strings: VARCHAR. Ordinal: INTEGER.
- @Version: INTEGER or TIMESTAMP

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
