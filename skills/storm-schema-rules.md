## Database Schema Access

This project has a Storm Schema MCP server configured. Use the following tools to access the live database schema:

- `list_tables` - List all tables in the database
- `describe_table(table)` - Describe a table's columns, types, nullability, primary key, foreign keys (with cascade rules), and unique constraints
- `select_data(table, ...)` - Query individual records from a table (only available when data access is enabled for this connection)

Use these tools when:
- Asked about the database schema or data model
- Generating or updating Storm entity classes
- Validating that existing entities match the actual database schema
- Investigating foreign key relationships between tables

### Schema vs. data access

The `list_tables` and `describe_table` tools return structural metadata only — no data is exposed.

The `select_data` tool is only available when the developer has explicitly enabled data access for this connection. If the tool is not listed in `tools/list`, data access is disabled — do not attempt to call it. When available, `select_data` accepts a structured request (table, columns, filters, sort, offset, limit) and returns individual rows formatted as a markdown table. It does not accept raw SQL. Results default to 50 rows (max 500), and cell values longer than 200 characters are truncated.

Use `select_data` when sample data would inform a decision — for example, to determine whether a `VARCHAR` column contains enum-like values, whether a `TEXT` column stores JSON, or what value ranges a numeric column holds. Do not query data speculatively or in bulk; use it when a specific question about the data would change the entity design.

When presenting `select_data` results to the user, always display them as a table with column names as column headers and one row per record. Never transpose the data (columns as rows). The response already contains a markdown table — present it directly or reformat it, but always keep the column-per-column, row-per-row orientation.

Some tables may be excluded from data queries by the developer. If `select_data` returns an error about an excluded table, the table's schema is still available through `describe_table` — only data access is restricted.
