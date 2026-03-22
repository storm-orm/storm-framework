Compare Storm entities against the live database schema.

1. Find all entity classes (Kotlin data classes or Java records implementing Entity)
2. Call \`list_tables\` to get all tables
3. Call \`describe_table\` for each entity's table
4. Report mismatches:
   - Tables without entities
   - Entities without tables
   - Column type mismatches
   - Missing or extra columns
   - FK/\`@FK\` mismatches
   - Nullability differences
   - Missing unique constraints for @UK fields
