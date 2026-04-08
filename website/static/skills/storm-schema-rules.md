## Database Schema Access

This project has a Storm Schema MCP server configured. Use the following tools to access the live database schema:

- `list_tables` - List all tables in the database
- `describe_table(table)` - Describe a table's columns, types, nullability, primary key, foreign keys (with cascade rules), and unique constraints

Use these tools when:
- Asked about the database schema or data model
- Generating or updating Storm entity classes
- Validating that existing entities match the actual database schema
- Investigating foreign key relationships between tables

The MCP server exposes only schema metadata (table definitions, column types, constraints). It has no access to actual data.
