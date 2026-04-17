# Database Connections & MCP

The Storm CLI manages database connections and exposes them as MCP (Model Context Protocol) servers. This gives your AI tools direct access to your database schema (table definitions, column types, constraints, and foreign keys) without exposing credentials or actual data. The AI can use this structural knowledge to generate entities that match your schema, validate entities it just created, or understand relationships between tables before writing a query.

---

## How It Works

Database configuration in Storm has two layers: a **global connection library** on your machine, and a **per-project configuration** that references connections from that library.

```
  ~/.storm/connections/         Your project (.storm/)
  ┌─────────────────────┐      ┌──────────────────────────┐
  │  localhost-shopdb   │◀─────│  default  -> localhost-  │
  │  staging-analytics  │◀─────│                 shopdb   │
  │  localhost-legacy   │      │  reports  -> staging-    │
  └─────────────────────┘      │                analytics │
    global, shared by all      └──────────────────────────┘
    projects on this machine     project picks what it needs
```

Global connections store the actual credentials and connection details. Projects reference them by name through aliases. This separation means you configure a database once and reuse it across as many projects as you need. Changing a password or hostname in the global connection updates every project that references it.

Each project alias becomes an MCP server that your AI tool can query. The alias `default` becomes `storm-schema`; any other alias like `reporting` becomes `storm-schema-reporting`. The Storm MCP server handles all supported dialects — PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite, and H2. The necessary drivers are installed automatically.

---

## Global Connections

Global connections live in `~/.storm/connections/` and are available to any project on your machine. Think of them as your personal library of database connections: your local Postgres, your staging Oracle instance, your team's shared MySQL.

### Adding a connection

Run `storm db add` to walk through an interactive setup that asks for the dialect, host, port, database name, and credentials. Storm suggests a connection name based on the host and database, which you can accept or override:

```
? Database dialect: PostgreSQL
? Host: localhost
? Port: 5432
? Database: shopdb
? Username: storm
? Password: ••••••
? Allow AI tools to query data? (read-only SELECT) No
? Connection name: localhost-shopdb

  Connection "localhost-shopdb" saved globally.
```

The data access prompt defaults to No. When disabled, the MCP server exposes only schema metadata (table definitions, column types, constraints). When enabled, the AI can also query individual records. See [Security](#security) for details on what this means and how read-only access is enforced.

You can also provide the name upfront with `storm db add my-postgres` to skip the naming prompt.

Drivers are managed automatically. When you add your first PostgreSQL connection, Storm installs the `pg` driver. When you later add a MySQL connection, the `mysql2` driver is installed alongside it. You never install or update drivers manually.

### Listing and removing connections

Use `storm db list` to see all global connections:

```
  localhost-shopdb    (postgresql://localhost:5432/shopdb)
  staging-analytics   (oracle://db.staging.internal:1521/analytics)
  localhost-legacy    (mysql://localhost:3306/legacy)
```

To remove a connection, run `storm db remove localhost-legacy` (or just `storm db remove` for an interactive picker). Removing a global connection does not affect any project that already references it. The project alias simply becomes unresolvable until you point it at a different connection.

---

## Project Connections

A project's `.storm/databases.json` maps aliases to global connection names. Each alias becomes a separate MCP server that your AI tool can use. This is where you decide which databases are relevant to a particular project, and what to call them.

### Adding a connection to your project

Run `storm mcp add` to pick a global connection and assign it an alias:

```
? Database connection: localhost-shopdb (postgresql://localhost:5432/shopdb)
? Alias for this connection: default

  Database "default" -> localhost-shopdb added.
```

The alias determines the MCP server name. The convention is straightforward:

| Alias | MCP server name |
|-------|----------------|
| `default` | `storm-schema` |
| `reporting` | `storm-schema-reporting` |
| `legacy` | `storm-schema-legacy` |

You can add multiple connections to a single project by running `storm mcp add` repeatedly. Each one registers a separate MCP server, so your AI tool can query each database independently. This is useful when your application talks to more than one database, for example a primary PostgreSQL for transactional data and an Oracle database for reporting.

### Listing project connections

Run `storm mcp list` to see what is configured for the current project, including which global connection each alias resolves to and the corresponding MCP server name:

```
  default    -> localhost-shopdb (global) postgresql://localhost:5432/shopdb
    MCP server: storm-schema
  reporting  -> staging-analytics (global) oracle://db.staging.internal:1521/analytics
    MCP server: storm-schema-reporting
```

### Removing a project connection

Run `storm mcp remove reporting` to remove an alias from the project. This unregisters the MCP server from your AI tool's configuration. The global connection itself is not affected — other projects that reference it continue to work.

### Re-registering connections

If your AI tool's MCP configuration gets out of sync (for example, after switching branches or resetting editor config files), run `storm mcp update`. This re-registers all connections from `databases.json` for every configured AI tool.

---

## Using `storm init`

When you run `storm init`, database configuration is part of the interactive setup. After selecting your AI tools and programming languages, Storm asks if you want to connect to a database:

```
  Storm can connect to your local development database so AI tools
  can read your schema (tables, columns, foreign keys) and generate
  entities automatically. Credentials are stored locally and never
  exposed to the AI.

? Connect to a local database? Yes
```

If you say yes, it walks you through the same flow as `storm db add` (or lets you pick an existing global connection), including whether to enable data access. It then asks for an alias. After the first connection, it offers to add more. This lets you set up your full database configuration in a single `storm init` run, or you can skip it and add connections later with `storm mcp add`.

---

## Multiple Databases in One Project

Real-world applications often work with more than one database. A project might have a primary PostgreSQL database for transactional data and an Oracle database for reporting, or a main database plus a legacy system that is being migrated. Storm supports this natively: each connection gets its own MCP server, and your AI tool can query any of them by name.

```
  .storm/databases.json              .mcp.json
  ┌───────────────────────────┐      ┌─────────────────────────────────┐
  │  "default"  : "local-pg"  │ ───▶ │  storm-schema           (PG)    │
  │  "reporting": "oracle-rpt"│ ───▶ │  storm-schema-reporting (ORA)   │
  │  "legacy"   : "local-my"  │ ───▶ │  storm-schema-legacy    (MySQL) │
  └───────────────────────────┘      └─────────────────────────────────┘
```

When using an AI tool, each MCP server identifies itself by connection name. The AI can call `list_tables` and `describe_table` on each server independently, so it always knows which tables belong to which database. This matters when generating entities: the AI can target the right schema and use the right dialect conventions for each database.

---

## Multiple Projects, Shared Connections

The global/project split also works in the other direction: when several projects use the same database, you configure the connection once globally and reference it from each project. Changing the connection details (for example, updating a password after a credential rotation) updates it for all projects at once.

```
                  ~/.storm/connections/
                  ┌───────────────────┐
                  │  localhost-shopdb │
                  └────────┬──────────┘
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
      storefront      backoffice      mobile-api
     (default: pg)   (default: pg)   (default: pg)
```

If a project needs a different database, it simply references a different global connection. No duplication, no drift.

---

## Project-Local Connections

Most connections should be global, since they represent databases on your machine that any project might use. However, some connections are inherently project-specific: a SQLite database file that lives inside the project directory, or a Docker Compose database that uses a project-specific port mapping.

Project-local connections are stored in `.storm/connections/` inside the project directory. When Storm resolves a connection name, it checks the project-local directory first, then the global directory. This means a project-local connection can shadow a global one with the same name — useful for overriding connection details in a specific project without affecting others.

```
.storm/
├── databases.json
└── connections/
    └── test-h2.json       # only this project can see this
```

---

## Directory Structure

Global connections are stored in `~/.storm/connections/`. Project-level configuration lives in `.storm/` inside your project directory. Both `.storm/` and `.mcp.json` are gitignored because they contain machine-specific paths and credentials.

---

## Using Without Storm ORM

The Storm MCP server is a standalone database tool — it does not require Storm ORM in your project. If you use Python, Go, Ruby, or any other language and just want your AI tool to have schema awareness and optional data access, run:

```bash
npx @storm-orm/cli mcp
```

This walks you through:

1. Selecting your AI tools (Claude Code, Cursor, Codex, etc.)
2. Configuring one or more database connections
3. Optionally enabling read-only data access
4. Registering the MCP server with your AI tools

No Storm rules, skills, or language-specific configuration is installed. Just the database MCP server. Your AI tool gets `list_tables`, `describe_table`, and optionally `select_data`, regardless of what language or framework your project uses.

After setup, you can manage connections with `storm db` and `storm mcp` commands as described above.

---

## Security

Database credentials are stored in connection JSON files under `~/.storm/connections/` (global) or `.storm/connections/` (project-local). Both locations are outside the AI tool's context window: the MCP server reads them at startup, but the connection details are never sent to the AI. The AI only sees schema metadata — and optionally, query results — but it never learns your credentials.

### Schema access (always available)

The MCP server always exposes two schema-only tools:

| Tool | What it returns |
|------|----------------|
| `list_tables` | Table names |
| `describe_table` | Column names, types, nullability, primary keys, foreign keys (with cascade rules), unique constraints |

These tools return structural metadata only. No data is returned, and the database cannot be modified.

### Data access (opt-in)

When you enable data access for a connection, a third tool becomes available:

| Tool | What it returns |
|------|----------------|
| `select_data` | Individual rows from a table, filtered by column conditions. Supports pagination (offset + limit), defaults to 50 rows. Results formatted as a markdown table. |

**Data access is disabled by default.** When you add a database connection, Storm asks:

```
? Allow AI tools to query data? (read-only SELECT) No
```

If you answer No (the default), the MCP server exposes only `list_tables` and `describe_table`. The AI has full visibility into your database structure but cannot see any data.

If you answer Yes, the AI can also query individual records using `select_data`. This is useful when sample data helps the AI make better decisions, for example recognizing that a `status` column contains enum-like values, or that a `TEXT` column stores JSON. But it means **actual data from your database flows through the AI's context**. It is your responsibility to decide whether this is acceptable given the nature of your data.

The `selectAccess` setting is stored per connection in the connection JSON file. You can change it at any time by running `storm db config`.

### Configuring data access

Use `storm db config` to manage data access settings for a connection:

```
storm db config localhost-shopdb
```

This lets you:

1. **Toggle data access** on or off for the connection.
2. **Exclude specific tables** from data queries. Storm connects to the database, lists all tables, and presents a searchable checkbox where you can select which tables to exclude. You can type to filter the list, use Page Up/Down for large schemas, and press Space to toggle individual tables.

Excluded tables still appear in `list_tables` and can be described with `describe_table` — the AI needs to see the schema to generate correct entities and foreign keys. Only `select_data` is restricted for excluded tables.

The settings are stored in the connection JSON file (`selectAccess` and `excludeTables`). You can re-run `storm db config` at any time to update them.

### How data access stays read-only

Enabling data access does not give the AI the ability to write, modify, or delete data. The MCP server enforces read-only access through multiple independent layers:

**1. Structured queries, not SQL.** The AI never writes SQL. The `select_data` tool accepts a structured request — table name, column names, filter conditions, sort order, and row limit — and the MCP server builds the SQL internally. There is no code path that produces anything other than a `SELECT` statement. This is read-only by construction: the server cannot generate `INSERT`, `UPDATE`, `DELETE`, `DROP`, or any other write operation because it simply does not contain the code to do so.

**2. Schema validation.** Every table and column name in a `select_data` request is validated against the actual database schema before any query is executed (case-insensitive; the server resolves the correct casing automatically). Unknown tables and columns are rejected. Filter operators are restricted to a fixed whitelist (`=`, `!=`, `<`, `>`, `<=`, `>=`, `LIKE`, `IN`, `IS NULL`, `IS NOT NULL`). Values are always parameterized — they never appear in the SQL string.

**3. Read-only database connections.** Independent of the query builder, the database connection itself is configured to reject writes at the driver or protocol level:

| Database | Read-only mechanism |
|----------|-------------------|
| PostgreSQL | `default_transaction_read_only = on` — the server rejects any write statement |
| MySQL / MariaDB | `SET SESSION TRANSACTION READ ONLY` — session-level write rejection |
| SQL Server | `readOnlyIntent: true` — connection-level read-only intent |
| SQLite | `readonly: true` — OS-level read-only file handle |
| H2 | `default_transaction_read_only = on` (via PG wire protocol) |
| Oracle | Relies on the structured query builder (Oracle has no session-level read-only setting) |

Even if the structured query builder had a bug that somehow produced a write statement, the database would reject it. These are independent safety layers.

**4. Row and cell limits.** Results default to 50 rows per query (configurable up to 500). Individual cell values are truncated at 200 characters to prevent JSON blobs or large text fields from overloading the AI's context window. Pagination is supported via `offset` and `limit` for all database dialects.

### Summary

| Concern | How it is addressed |
|---------|-------------------|
| **Credentials** | Stored in `~/.storm/`, never sent to the AI |
| **Data visibility** | Off by default. Opt-in per connection. Developer's choice. |
| **Sensitive tables** | `storm db config` hides specific tables from data queries while keeping their schema visible |
| **Write protection** | Read-only by construction (structured queries) + read-only database connections (driver-level). Two independent layers. |
| **SQL injection** | Not possible. Values are parameterized. Table/column names are validated against the schema. |
| **Unbounded queries** | Default 50 rows, max 500. Cell values truncated at 200 characters. Pagination via offset + limit. |

---

## Command Reference

### `storm db` — Global connection library

#### `storm db`

List all global connections with their dialect and host. Same as `storm db list`.

#### `storm db add [name]`

Add a new database connection to your global library. Walks you through dialect, host, port, database, credentials, and data access settings interactively. If `name` is provided, it is used as the connection name; otherwise Storm suggests one based on the host and database.

Database drivers are installed automatically when you add your first connection of a given dialect. For example, adding a PostgreSQL connection installs the `pg` driver, and adding a MySQL connection later installs `mysql2` alongside it.

#### `storm db remove [name]`

Remove a connection from the global library. If `name` is omitted, shows an interactive picker. Does not affect project aliases that reference the connection; those aliases simply become unresolvable until you point them at a different connection.

#### `storm db config [name]`

Configure data access settings for a connection. This lets you:

- Toggle read-only SELECT access on or off.
- Exclude specific tables from data queries. Storm connects to the database, lists all tables, and presents a searchable checkbox list. You can type to filter, use Page Up/Down for large schemas, and press Space to toggle individual tables.

Excluded tables still appear in `list_tables` and can be described with `describe_table`. Only `select_data` is restricted.

### `storm mcp` — Project MCP servers

#### `storm mcp`

Set up a MCP database server (default). Walks you through AI tool selection, database connections, data access, and MCP registration. Works standalone — no Storm ORM required. `storm mcp init` is an alias for this command.

#### `storm mcp update`

Re-register all MCP servers defined in `.storm/databases.json` with your AI tools. Useful after switching branches, resetting editor config files, or when MCP registrations get out of sync.

#### `storm mcp add [alias]`

Add a database connection to this project by picking a global connection and assigning it an alias. The alias determines the MCP server name: `default` becomes `storm-schema`, anything else becomes `storm-schema-<alias>`. Run this command multiple times to add multiple databases to a single project.

#### `storm mcp list`

List all project database connections, showing each alias, the global connection it resolves to, the connection URL, and the corresponding MCP server name.

#### `storm mcp remove [alias]`

Remove a database connection from this project and unregister its MCP server from your AI tool's configuration. The global connection is not affected.

### Supported Databases

The MCP server is a lightweight Node.js process that reads schema metadata. It uses native Node.js database drivers (not JDBC) to connect to the same databases your Storm application uses.

- **PostgreSQL** -- TCP on port 5432.
- **MySQL** -- TCP on port 3306.
- **MariaDB** -- TCP on port 3306. Uses the MySQL driver.
- **Oracle** -- TCP in thin mode on port 1521. Does not require the Oracle Instant Client.
- **SQL Server** -- TCP on port 1433.
- **SQLite** -- Direct file access, opened read-only. No network connection needed.
- **H2** -- TCP using the PG wire protocol on port 5435. Requires H2 to be started with the `-pgPort` flag.
