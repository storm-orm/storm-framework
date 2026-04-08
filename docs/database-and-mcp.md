# Database Connections & MCP

The Storm CLI manages database connections and exposes them as MCP (Model Context Protocol) servers. This gives your AI tools direct access to your database schema — table definitions, column types, constraints, and foreign keys — without exposing credentials or actual data. The AI can use this structural knowledge to generate entities that match your schema, validate entities it just created, or understand relationships between tables before writing a query.

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

Each project alias becomes an MCP server that your AI tool can query. The alias `default` becomes `storm-schema`; any other alias like `reporting` becomes `storm-schema-reporting`. The Storm MCP server handles all supported dialects — PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite, and H2 — and the necessary drivers are installed automatically.

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
? Connection name: localhost-shopdb

  Connection "localhost-shopdb" saved globally.
```

You can also provide the name upfront with `storm db add my-postgres` to skip the naming prompt.

Drivers are managed automatically. When you add your first PostgreSQL connection, Storm installs the `pg` driver. When you later add a MySQL connection, the `mysql2` driver is installed alongside it. You never install or update drivers manually.

### Listing and removing connections

Use `storm db list` to see all global connections:

```
  localhost-shopdb    (postgresql://localhost:5432/shopdb)
  staging-analytics   (oracle://db.staging.internal:1521/analytics)
  localhost-legacy    (mysql://localhost:3306/legacy)
```

To remove a connection, run `storm db remove localhost-legacy` (or just `storm db remove` for an interactive picker). Removing a global connection does not affect any project that already references it — the project alias simply becomes unresolvable until you point it at a different connection.

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

You can add multiple connections to a single project by running `storm mcp add` repeatedly. Each one registers a separate MCP server, so your AI tool can query each database independently. This is useful when your application talks to more than one database — for example, a primary PostgreSQL for transactional data and an Oracle database for reporting.

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

If your AI tool's MCP configuration gets out of sync (for example, after switching branches or resetting editor config files), run `storm mcp` without arguments. This re-registers all connections from `databases.json` for every configured AI tool.

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

If you say yes, it walks you through the same flow as `storm db add` (or lets you pick an existing global connection), then asks for an alias. After the first connection, it offers to add more. This lets you set up your full database configuration in a single `storm init` run — or you can skip it and add connections later with `storm mcp add`.

---

## Multiple Databases in One Project

Real-world applications often work with more than one database. A project might have a primary PostgreSQL database for transactional data and an Oracle database for reporting, or a main database plus a legacy system that is being migrated. Storm supports this natively: each connection gets its own MCP server, and your AI tool can query any of them by name.

```
  .storm/databases.json              .mcp.json
  ┌───────────────────────────┐      ┌─────────────────────────────────┐
  │  "default"  : "local-pg"  │ ───▶ │  storm-schema           (PG)   │
  │  "reporting": "oracle-rpt"│ ───▶ │  storm-schema-reporting (ORA)  │
  │  "legacy"   : "local-my"  │ ───▶ │  storm-schema-legacy    (MySQL)│
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

After setup, the file layout looks like this:

```
~/.storm/                          # global (machine-wide)
├── package.json                   # npm package for driver installs
├── node_modules/                  # database drivers (pg, mysql2, oracledb, etc.)
├── server.mjs                     # MCP server script (shared by all connections)
└── connections/
    ├── localhost-shopdb.json       # { dialect, host, port, database, username, password }
    └── staging-analytics.json

<project>/
├── .storm/                        # project-level (gitignored)
│   ├── databases.json             # { "default": "localhost-shopdb", ... }
│   └── connections/               # optional project-local connections
│       └── test-h2.json
├── .mcp.json                      # MCP server registrations (gitignored)
└── CLAUDE.md                      # Storm rules (committed)
```

The `.storm/` directory and `.mcp.json` are gitignored because they contain machine-specific paths and credentials. The Storm rules and skills files are committed and shared with the team.

---

## Security

Database credentials are stored in connection JSON files under `~/.storm/connections/` (global) or `.storm/connections/` (project-local). Both locations are outside the AI tool's context window: the MCP server reads them at startup, but the connection details are never sent to the AI. The AI only sees schema metadata — it cannot read, write, or modify data, and it never learns your credentials.

The MCP server exposes exactly two read-only tools:

| Tool | What it returns |
|------|----------------|
| `list_tables` | Table names |
| `describe_table` | Column names, types, nullability, primary keys, foreign keys (with cascade rules), unique constraints |

No data access. No writes. No credential exposure.

---

## Command Reference

### `storm db` — Global connection library

| Command | Description |
|---------|-------------|
| `storm db` | List all global connections |
| `storm db add [name]` | Add a global database connection |
| `storm db remove [name]` | Remove a global database connection |

### `storm mcp` — Project MCP servers

| Command | Description |
|---------|-------------|
| `storm mcp` | Re-register MCP servers for all project connections |
| `storm mcp add [alias]` | Add a database connection to this project |
| `storm mcp list` | List project database connections with MCP server names |
| `storm mcp remove [alias]` | Remove a database connection from this project |

### Supported Databases

The MCP server is a lightweight Node.js process that reads schema metadata. It uses native Node.js database drivers (not JDBC) to connect to the same databases your Storm application uses.

| Database | Connection | Default Port |
|----------|-----------|-------------|
| PostgreSQL | TCP | 5432 |
| MySQL | TCP | 3306 |
| MariaDB | TCP | 3306 |
| Oracle | TCP (thin mode) | 1521 |
| SQL Server | TCP | 1433 |
| SQLite | Direct file access (read-only) | — |
| H2 | TCP (PG wire protocol, requires `-pgPort`) | 5435 |
