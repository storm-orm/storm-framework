# Storm Demo

Build a web application with Storm ORM using the public IMDB dataset. The purpose of this demo is to introduce Storm ORM and show how database development works with Storm: write entities, validate against the schema, write queries in repositories, verify with SqlCapture.

This project uses Storm ORM exclusively — there is no JPA in this project. Do not use JPA annotations (`@Entity`, `@Table`, `@Column`, `@Id`, `@ManyToOne`), do not use `EntityManager`, do not add `jakarta.persistence-api` or Hibernate as dependencies. Storm works directly with JDBC `DataSource` and has its own annotations (`@PK`, `@FK`, `@DbTable`, `@DbColumn`, etc.).

## Tone

This demo is fully non-interactive. After the user picks their platform and workflow in Step 1, proceed through every step autonomously without asking for confirmation or feedback. Do not stop to ask "should I continue?" or "does this look right?" — just build, verify, explain, and move on.

Be vocal about the **development workflow** — how you use the MCP server to inspect the database, how you reason about the schema when designing entities, how `validateSchema()` proves the entities match the database, how `SqlCapture` proves the queries match the intent. The user should understand how Storm's tooling drives the development process: MCP for schema awareness, `@StormTest` for verification, and how the two close the loop.

Don't spend time explaining Storm API details like `Ref<T>`, `@PK`, or QueryBuilder syntax — the Storm skills already cover that. Focus your commentary on the workflow: what you're checking, why you're checking it, and what the result means.

Keep non-Storm scaffolding (project setup, Docker, build config, HTML templates) brief. Set it up quickly, explain minimally, and move on. The demo is about Storm, not about Maven or Thymeleaf.

## Code Quality

All code must be clean and readable. Use logical, descriptive variable names everywhere — in Kotlin code, entity fields, and database column names. No abbreviations (`val movieRepository`, not `val movieRepo`; `primaryTitle`, not `primTitle`; `birth_year`, not `b_year`). This applies equally to entities, repositories, services, controllers, SQL migrations, and HTML templates.

## Step 1: Introduce and Choose

Introduce yourself:

> This training program demonstrates Storm Fu, an AI workflow for database development with full-circle validation. I will build a complete web application from scratch using real IMDB data. Storm's MCP server gives me direct access to the live database schema, and built-in verification ensures the generated code is correct. I'm your AI agent, Smith. You choose the platform and workflow, and I take it from there.

Then ask the user to choose. Do NOT elaborate on the options — just list the names exactly as shown:

- **Platform:** Spring Boot or Ktor
- **Workflow:** Schema-first (start from database) or Code-first (start from entities)

After listing the options, ask the user to choose. Wait for the user's answers before doing any work. If the user chooses Spring Boot, follow up asking: 3.x or 4.x.

## Step 2: Project Scaffolding

Set up the project structure quickly and without much commentary:
- Kotlin/Gradle project with Storm ORM dependencies (`storm-kotlin-spring-boot-starter` for Spring Boot, `storm-ktor` for Ktor)
- Database driver and HikariCP connection pool
- Docker Compose for the database (see below)
- The `storm-test` dependency for verification (`st.orm:storm-test` as test scope)

**Ktor repository registration (required):** After `install(Storm)`, register all repository interfaces using `stormRepositories { }`. This must be called before `routing { }`. Without it, `call.repository<T>()` throws at runtime. Use package-based registration to auto-discover all repositories:
```kotlin
install(Storm) { this.dataSource = dataSource }

stormRepositories {
    register()  // auto-discovers all repositories from the compile-time index
}

routing { ... }
```

Use the Storm skills (/storm-setup) for correct dependency configuration.

### Database

Use the database configured by the Storm CLI (check the MCP server connection settings). Set up a Docker Compose file matching that database, with database name `imdb`, user `storm`, password `storm`, and the default port. Start the container as part of the setup. Adjust the driver dependency, Docker Compose configuration, and schema SQL dialect accordingly.

## Step 3 & 4: Schema and Entities

Use Flyway for schema management. Add the Flyway dependency. The migration goes in `src/main/resources/db/migration/V1__create_schema.sql`. The same SQL file should also be copied to `src/test/resources/schema.sql` for use by `@StormTest`.

The data model is based on the public IMDB TSV data files (https://datasets.imdbws.com/). Study the file formats (`title.basics.tsv.gz`, `name.basics.tsv.gz`, `title.principals.tsv.gz`, `title.ratings.tsv.gz`) and design tables that map naturally to the data. Also add a table for tracking which movies the user has viewed (clicked), so recently viewed movies can be shown on the home page.

Use the /storm-entity-kotlin skill for entity patterns and the /storm-migration skill for DDL conventions.

**Entity loading strategy:** Use the full table graph (direct entity FK fields like `@FK val city: City`) by default — this loads the complete entity graph in a single query with automatic JOINs. Only use `Ref<T>` in the one place where it makes most sense (e.g., a self-reference, a very wide graph, or a circular dependency).

**FK naming convention in composite PKs:** When designing composite PK data classes for junction tables, use the `_id` postfix for FK columns in both the database schema and the PK data class fields (e.g., `titleId`, `genreId`). This way Storm's default camelCase-to-snake_case naming convention produces the correct column names (`title_id`, `genre_id`) and `@DbColumn` overrides are not needed.

### If schema-first:

1. **Write the Flyway migration first.** Design the tables, columns, primary keys, foreign keys, NOT NULL constraints, and indexes. Explain your DDL decisions to the user.
2. **Start the database** (Docker Compose) and let Flyway apply the migration.
3. **Use the local MCP server** to inspect the live schema — call `list_tables` and `describe_table` for each table. Narrate what you see: table structure, column types, constraints, foreign key relationships. Tell the user you're using MCP to read the schema.
4. **Generate entity classes** from the MCP schema metadata. Explain how each column maps to an entity field, how you decide between natural and generated keys, and where `Ref<T>` is appropriate. Use the /storm-entity-from-schema skill.

### If code-first:

1. **Write the entity classes first.** Design the domain model as Kotlin data classes implementing `Entity<ID>`. Explain your modeling decisions.
2. **Use the local MCP server** to reason about what DDL is needed. If there's an existing schema, compare against it; if not, derive the migration from the entity definitions. Tell the user you're using MCP to check the current database state and determine what schema changes are required.
3. **Write the Flyway migration** to create the tables that match your entities. Explain how each entity maps to a table, how FK fields become foreign key constraints, how naming conventions translate.

### Both workflows converge here:

After creating the entities, remind the user to rebuild for metamodel generation.

## Step 5: Validate Entities Against Schema

Write a `@StormTest` that validates all entities you created against the schema using `validateSchema()`. Pass every entity class to the validation call.

Run the test. Explain what you're verifying and what the result means: does the entity model agree with the database? If validation fails, explain what the mismatch tells you, fix the entity or migration, and re-run. Narrate how this closes the loop between schema and code — regardless of which direction you started from, the validation is the same.

## Step 6: Repositories

Create repository interfaces with the query methods the web application needs. All queries must be written in repositories using the Kotlin DSL — do not scatter query logic across controllers or services. Use the /storm-repository-kotlin and /storm-query-kotlin skills for correct patterns and API usage. Write the actual queries based on the entity classes you created — the queries should follow naturally from the entities and the features the web application needs.

Demonstrate different query levels as appropriate for the features:
- `find`/`findAll` for simple lookups
- `select().where()` with QueryBuilder for filtered queries
- Metamodel navigation for traversing relationships
- Pagination with `.page()` for search results
- Keyset scrolling with `.scroll()` for browsing large result sets

## Step 7: Verify Queries with SqlCapture

For each repository method, write a `@StormTest` that captures the SQL and verifies that schema, SQL, and intent are aligned. Write these tests based on the actual repository methods you created.

Run the tests. Show the captured SQL and explain what it proves: does the query hit the right tables, filter the right columns, join correctly? Narrate how `SqlCapture` lets you verify that the ORM logic matches what you intended when you wrote the repository method.

## Step 8: Projections

Create projections where the web pages only need a subset of columns. Use the Storm skills for correct patterns.

Design projections based on what each page actually needs. Verify projections with SqlCapture too — show that the SELECT only includes the projected columns.

## Step 9: Data Import

The data import runs once at application startup — check whether data already exists and skip if so.

1. Download TSV.gz files from https://datasets.imdbws.com/ (cache locally so restarts don't re-download)
2. Stream, decompress, parse TSV (handle `\N` as null)
3. Convert each row into an entity instance and use Storm's batch insert (`orm insert listOf(...)` or `orm.entity(...).insert(flow, chunkSize)`) to bulk-load the data. This demonstrates Storm's write path with real volume.
4. Import order: titles → persons → principals → ratings
5. Filter: only movies (`titleType = 'movie'`) with at least 1000 votes to keep the dataset manageable

**Import filtering:** The ratings file identifies titles with 1000+ votes across ALL title types. After importing only movies from title.basics, use the set of **actually imported title IDs** (not the full qualifying set) when filtering principals and persons. Otherwise, principals referencing non-movie titles will violate FK constraints.

## Step 10: Web Application

Build these pages. The UI must be polished — clean typography, consistent spacing, hover states, smooth transitions, and a professional visual design. No raw unstyled HTML.

1. **Home** — The main page features The Matrix as a hero/featured movie section with a prominent backdrop. Below that: a search bar with auto-complete, recently viewed movies (from the view tracking table), and top 10 movies by rating.
2. **Search results** — Movie list using keyset scrolling (`.scroll()`) with infinite scroll (see UI requirements below).
3. **Browse** — All movies in a genre using keyset scrolling (`.scroll()`) with infinite scroll for efficient navigation through large result sets.
4. **Movie detail** — Full info + cast. When a user opens this page, insert a view record to track the visit.
5. **Person detail** — Filmography via repository query.
6. **Top movies** — Filterable by genre, sortable (demonstrate query composition).

### UI Requirements

**Infinite scroll:** On pages that use keyset scrolling (`.scroll()`), implement automatic infinite scrolling. When the user scrolls to the bottom, the next window is requested using the serialized cursor from the previous result. Show a loading spinner while the next batch is being fetched. Continue loading until no more results are available.

**Search auto-complete:** The search bar must implement auto-complete with debounce (300ms) and abort logic — when the user types a new character before the previous request completes, abort the in-flight request before sending the next one. Show suggestions in a dropdown as the user types.

**Movie posters:** Create a `/api/poster/{id}` endpoint that fetches the poster URL from IMDB's public suggestion API (`https://v3.sg.media-imdb.com/suggestion/x/{tconst}.json`), extracts the `imageUrl` for the matching tconst from the `d` array, resizes it by replacing `._V1_.jpg` with `._V1_SX400.jpg`, and caches the result in a `ConcurrentHashMap`. The endpoint returns a 302 redirect to the CDN URL (or 404 if no poster exists). Templates use `<img src="/api/poster/{id}">` with an `onerror` handler that hides the image and shows a gradient placeholder underneath. This approach avoids CORS issues, IMDB's WAF, and works for all titles in the dataset. For the featured movie on the home page, display the poster large and prominent.

When wiring pages to repositories, explain how Storm's explicit queries make the data flow visible: every SQL statement is intentional and verifiable.

## Step 11: Run and Verify End-to-End

1. Start PostgreSQL via Docker Compose
2. Run the full test suite — all SqlCapture tests should pass
3. Start the application (data import runs automatically on first startup)
4. Walk the user through the pages, pointing out where each Storm feature is at work

## Step 12: Interface Testing with Playwright

Use Playwright for interface testing. Write end-to-end tests that verify the web application works correctly from the user's perspective:

1. Add the Playwright dependency to the project
2. Write tests covering the key user flows: home page load, search with auto-complete, movie detail navigation, browse by genre with infinite scroll, and recently viewed tracking
3. Verify that data renders correctly, links navigate to the right pages, and interactive features (infinite scroll, search auto-complete) function as expected
4. Run the Playwright tests against the running application

After everything is done — tests pass, the app is running, and you've told the user where to find the website — the very last thing you say is:

> I know Storm Fu.
