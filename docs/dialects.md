import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Database Dialects

Storm works with any JDBC-compatible database using standard SQL. However, databases diverge on features like upserts, pagination, JSON handling, and native data types. Dialect packages let Storm take advantage of these database-specific capabilities while keeping your application code portable. Your entities, repositories, and queries stay the same regardless of which database you use; only the dialect dependency changes.

## Supported Databases

| | Database | Dialect Package | Key Features |
|---|----------|-----------------|--------------|
| ![Oracle](https://img.shields.io/badge/Oracle-F80000?logo=oracle&logoColor=white) | Oracle | `storm-oracle` | Merge (`MERGE INTO`), sequences |
| ![SQL Server](https://img.shields.io/badge/SQL_Server-CC2927?logo=microsoftsqlserver&logoColor=white) | MS SQL Server | `storm-mssqlserver` | Merge (`MERGE INTO`), identity columns |
| ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white) | PostgreSQL | `storm-postgresql` | Upsert (`ON CONFLICT`), JSONB, arrays |
| ![MySQL](https://img.shields.io/badge/MySQL-4479A1?logo=mysql&logoColor=white) | MySQL | `storm-mysql` | Upsert (`ON DUPLICATE KEY`), JSON |
| ![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white) | MariaDB | `storm-mariadb` | Upsert (`ON DUPLICATE KEY`), JSON |
| ![SQLite](https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white) | SQLite | `storm-sqlite` | Upsert (`ON CONFLICT`), file-based storage |
| ![H2](https://img.shields.io/badge/H2-0000bb?logoColor=white) | H2 | `storm-h2` | Merge (`MERGE INTO`), sequences, native UUID |

## Installation

Add the dialect dependency for your database. Dialects are runtime-only dependencies: they do not affect your compile-time code or entity definitions. Your entity classes, repositories, and queries are written against Storm's core API, not against any specific dialect. This means you can switch databases by changing a single dependency without modifying application code.

### Maven

```xml
<!-- Oracle -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-oracle</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>

<!-- MS SQL Server -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-mssqlserver</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>

<!-- PostgreSQL -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-postgresql</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-mysql</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>

<!-- MariaDB -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-mariadb</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>

<!-- SQLite -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-sqlite</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>

<!-- H2 -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-h2</artifactId>
    <version>1.11.0</version>
    <scope>runtime</scope>
</dependency>
```

### Gradle (Groovy DSL)

```groovy
// Oracle
runtimeOnly 'st.orm:storm-oracle:1.11.0'

// MS SQL Server
runtimeOnly 'st.orm:storm-mssqlserver:1.11.0'

// PostgreSQL
runtimeOnly 'st.orm:storm-postgresql:1.11.0'

// MySQL
runtimeOnly 'st.orm:storm-mysql:1.11.0'

// MariaDB
runtimeOnly 'st.orm:storm-mariadb:1.11.0'

// SQLite
runtimeOnly 'st.orm:storm-sqlite:1.11.0'

// H2
runtimeOnly 'st.orm:storm-h2:1.11.0'
```

### Gradle (Kotlin DSL)

```kotlin
// Oracle
runtimeOnly("st.orm:storm-oracle:1.11.0")

// MS SQL Server
runtimeOnly("st.orm:storm-mssqlserver:1.11.0")

// PostgreSQL
runtimeOnly("st.orm:storm-postgresql:1.11.0")

// MySQL
runtimeOnly("st.orm:storm-mysql:1.11.0")

// MariaDB
runtimeOnly("st.orm:storm-mariadb:1.11.0")

// SQLite
runtimeOnly("st.orm:storm-sqlite:1.11.0")

// H2
runtimeOnly("st.orm:storm-h2:1.11.0")
```

## Automatic Detection

Storm automatically detects the appropriate dialect based on the JDBC connection URL. No additional configuration is required. When your application starts, Storm queries the `ServiceLoader` for available dialect implementations, inspects the JDBC URL, and selects the matching dialect. This means adding or switching a dialect is purely a dependency change with no code or configuration modifications.

For example, with the connection URL `jdbc:postgresql://localhost:5432/mydb`, Storm will automatically use the PostgreSQL dialect.

## Database-Specific Features

### Upsert Support

Upsert operations are the primary reason most applications need a dialect. Without a dialect, Storm cannot generate the database-specific INSERT ... ON CONFLICT or MERGE syntax required for atomic upsert operations. Each database uses its own native syntax:

| Database | SQL Strategy | Conflict Detection |
|----------|--------------|--------------------|
| Oracle | `MERGE INTO ...` | Explicit match conditions |
| MS SQL Server | `MERGE INTO ...` | Explicit match conditions |
| PostgreSQL | `INSERT ... ON CONFLICT DO UPDATE` | Targets a specific unique constraint or index |
| MySQL | `INSERT ... ON DUPLICATE KEY UPDATE` | Primary key or any unique constraint |
| MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE` | Primary key or any unique constraint |
| SQLite | `INSERT ... ON CONFLICT DO UPDATE` | Targets a specific unique constraint |
| H2 | `MERGE INTO ...` | Explicit match conditions |

See [Upserts](upserts.md) for usage examples.

### JSON Support

PostgreSQL's JSONB and MySQL/MariaDB's JSON types are fully supported when using the corresponding dialect with a JSON serialization library (`storm-jackson2`/`storm-jackson3` or `storm-kotlinx-serialization`). See [JSON Support](json.md) for details.

### Database-Specific Data Types

Beyond SQL syntax differences, databases support different native data types. Dialects handle the mapping between Kotlin/Java types and database-specific types automatically, so you can use idiomatic types in your entities without worrying about the underlying storage format.

- **Oracle:** NUMBER, CLOB, sequences for ID generation
- **MS SQL Server:** NVARCHAR, UNIQUEIDENTIFIER, IDENTITY
- **PostgreSQL:** JSONB, UUID, arrays, INET, CIDR
- **MySQL/MariaDB:** JSON, TINYINT for booleans, ENUM
- **SQLite:** Dynamic typing, AUTOINCREMENT, file-based storage
- **H2:** Native UUID, sequences, ARRAY types

## Without a Dialect

Storm works without a specific dialect package by generating standard SQL. The core framework handles entity mapping, queries, joins, transactions, streaming, dirty checking, and caching using only standard SQL. However, some features require database-specific syntax and will be unavailable without a dialect:

- **Upsert operations** require database-specific syntax
- **Database-specific optimizations** such as native pagination strategies

All other features (entity mapping, queries, joins, transactions, streaming, dirty checking, and caching) work identically regardless of dialect.

## Testing with SQLite

SQLite is a lightweight option for testing. It stores data in a single file (or in memory) and requires no server process. Add the `storm-sqlite` dialect dependency to enable SQLite-specific features like upsert support.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val dataSource = SQLiteDataSource().apply {
    url = "jdbc:sqlite::memory:"
}
val orm = ORMTemplate.of(dataSource)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var dataSource = new SQLiteDataSource();
dataSource.setUrl("jdbc:sqlite::memory:");
var orm = ORMTemplate.of(dataSource);
```

</TabItem>
</Tabs>

Note that SQLite does not support sequences, row-level locking, or `INFORMATION_SCHEMA`. Constraint discovery uses JDBC metadata, and locking relies on SQLite's file-level locking mechanism.

## Testing with H2

H2 is an in-memory Java SQL database that starts instantly and requires no external processes, making it the default choice for unit tests. Because H2 runs in-process, tests start in milliseconds and do not require Docker, network access, or database installation.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val dataSource = JdbcDataSource().apply {
    setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
}
val orm = ORMTemplate.of(dataSource)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var dataSource = new JdbcDataSource();
dataSource.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
var orm = ORMTemplate.of(dataSource);
```

</TabItem>
</Tabs>

For basic testing without upsert support, H2 works without any dialect dependency. To enable upsert support and other H2-specific optimizations (native UUID handling, tuple comparisons), add the `storm-h2` dialect dependency.

## Integration Testing with Real Databases

While H2 is excellent for fast unit tests, it does not support all database-specific features (JSONB, arrays, database-specific functions). For thorough testing, you should also run integration tests against your production database. Each dialect module includes a `docker-compose.yml` file that starts the corresponding database in a container, making integration testing straightforward. For example, to test with PostgreSQL:

```bash
cd storm-postgresql
docker-compose up -d
mvn test -pl storm-postgresql
```

## Tips

1. **Always include the dialect** for production databases to unlock all features
2. **Use H2 or SQLite** for unit tests; add `storm-h2` or `storm-sqlite` for upsert support
3. **Dialect is runtime-only**; it doesn't affect your compile-time code or entity definitions
4. **One dialect per application**; Storm auto-detects the right dialect from your connection URL
5. **Test with both**: Use H2/SQLite for fast unit tests and the production dialect for integration tests

---

## See Also

- [Upserts](upserts.md) for dialect-specific upsert strategies and usage examples
- [JSON](json.md) for database-specific JSON column support
