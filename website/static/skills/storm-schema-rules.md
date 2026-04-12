## Database Schema Access

This project has a Storm Schema MCP server configured. Use the following tools to access the live database schema:

- `list_tables` - List all tables in the database
- `describe_table(table)` - Describe a table's columns, types, nullability, primary key, foreign keys (with cascade rules), and unique constraints
- `select_data` - Query individual records from a table (only available when data access is enabled for this connection)


### select_data parameters

All parameters except `table` are optional. Pass arrays and objects as native JSON types — never as stringified JSON.

| Parameter | Type | Description |
|-----------|------|-------------|
| `table` | `string` | **Required.** Table name. |
| `columns` | `string[]` | Columns to return. Omit for all columns. Example: `["id", "name"]` |
| `where` | `object[]` | Filter conditions (AND). Each object: `{ "column": "name", "operator": "=", "value": "x" }`. Operators: `=`, `!=`, `<`, `>`, `<=`, `>=`, `LIKE`, `IN`, `IS NULL`, `IS NOT NULL`. |
| `orderBy` | `object[]` | Sort order. Each object: `{ "column": "name", "direction": "DESC" }`. Direction: `ASC` (default) or `DESC`. **Not** `sort`. |
| `offset` | `integer` | Rows to skip (default: 0). |
| `limit` | `integer` | Max rows (default: 50, max: 500). |

Example call:
```json
{
  "table": "USER",
  "columns": ["id", "name", "timestamp"],
  "orderBy": [{"column": "timestamp", "direction": "DESC"}],
  "limit": 10
}
```

Use these tools when:
- Asked about the database schema or data model
- Generating or updating Storm entity classes
- Validating that existing entities match the actual database schema
- Investigating foreign key relationships between tables

### Schema vs. data access

The `list_tables` and `describe_table` tools return structural metadata only — no data is exposed.

The `select_data` tool is only available when the developer has explicitly enabled data access for this connection. If the tool is not listed in `tools/list`, data access is disabled — do not attempt to call it. When available, `select_data` accepts a structured request (table, columns, where, orderBy, offset, limit) and returns individual rows formatted as a markdown table. It does not accept raw SQL. Results default to 50 rows (max 500), and cell values longer than 200 characters are truncated.

Use `select_data` when sample data would inform a decision — for example, to determine whether a `VARCHAR` column contains enum-like values, whether a `TEXT` column stores JSON, or what value ranges a numeric column holds. Do not query data speculatively or in bulk; use it when a specific question about the data would change the entity design.

When presenting `select_data` results to the user, always show the actual data rows as a table — column names as headers, one row per record. Do not summarize, describe, or narrate the data in prose. The user asked to see the data, so show it. The response already contains a markdown table — present it directly. Never transpose the data (columns as rows), and never replace the table with a written description of what the data contains.

Some tables may be excluded from data queries by the developer. If `select_data` returns an error about an excluded table, the table's schema is still available through `describe_table` — only data access is restricted.
