## Database Schema Access

This project has a Storm Schema MCP server configured. Use the following tools to access the live database schema:

- `list_tables` - List all tables in the database
- `describe_table(table)` - Describe a table's columns, types, nullability, primary key, foreign keys (with cascade rules), and unique constraints
- `select_data` - Query individual records from a table (only available when data access is enabled for this connection)


### select_data parameters

All parameters except `table` are optional. **Pass arrays and objects as native JSON types, never as stringified JSON.**

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

The `list_tables` and `describe_table` tools return structural metadata only; no data is exposed.

**Call `select_data` only when it is listed in `tools/list`.** The developer enables data access per connection; when the tool is absent, data access is disabled.
- It accepts a structured request (table, columns, where, orderBy, offset, limit), not raw SQL.
- It returns individual rows formatted as a markdown table, 50 rows by default (max 500), with cell values longer than 200 characters truncated.

**Use `select_data` only when a specific question about the data would change the entity design.** For example: whether a `VARCHAR` column contains enum-like values, whether a `TEXT` column stores JSON, or what value ranges a numeric column holds. Do not query data speculatively or in bulk.

**Show `select_data` results as the table they come in.** Column names are headers, one row per record; present the returned markdown table directly. Do not summarize, describe, or narrate the data in prose: the user asked to see the data. Never transpose the data (columns as rows).

**An excluded table still has its schema.** The developer may exclude some tables from data queries. If `select_data` returns an error about an excluded table, `describe_table` still provides its schema; only data access is restricted.
