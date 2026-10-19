---
name: storm-demo
description: Build a demo Storm web application on the public IMDB dataset, from schema to running app. Use to introduce Storm or evaluate it in a new project.
---

# Storm Demo

Build a web application with Storm ORM using the public IMDB dataset. The purpose of this demo is to introduce Storm ORM and show how database development works with Storm: write entities, validate against the schema, write queries in repositories, verify with SqlCapture.

This project uses Storm ORM exclusively — there is no JPA in this project. Do not use JPA annotations (`@Entity`, `@Table`, `@Column`, `@Id`, `@ManyToOne`), do not use `EntityManager`, do not add `jakarta.persistence-api` or Hibernate as dependencies. Storm works directly with JDBC `DataSource` and has its own annotations (`@PK`, `@FK`, `@DbTable`, `@DbColumn`, etc.).

## Tone

This demo is fully non-interactive. After the user picks their platform and workflow in Step 1, proceed through every step autonomously without asking for confirmation or feedback. Do not stop to ask "should I continue?" or "does this look right?" — just build, verify, explain, and move on.

Be vocal about the **development workflow** — how you use the MCP server to inspect the database, how you reason about the schema when designing entities, how `validateSchema()` proves the entities match the database, how `SqlCapture` proves the queries match the intent. The user should understand how Storm's tooling drives the development process: MCP for schema awareness, `@StormTest` for verification, and how the two close the loop.

Don't spend time explaining Storm API details like `Ref<T>`, `@PK`, or QueryBuilder syntax — the Storm skills already cover that. Focus your commentary on the workflow: what you're checking, why you're checking it, and what the result means.

Keep non-Storm scaffolding (project setup, Docker, build config, HTML templates) brief. Set it up quickly, explain minimally, and move on. The demo is about Storm, not about Maven or Thymeleaf.

**Ktor + Thymeleaf caveat:** Ktor's Thymeleaf integration does not implement `IWebContext`, so `@{...}` link expressions fail at runtime. Use Thymeleaf literal substitution instead: `|/path/${var}|` (e.g., `th:href="|/movie/${movie.id}|"`). This applies to all templates in Ktor projects.

**Ktor 3.x Thymeleaf rendering:** In Ktor 3.x, use `call.respondTemplate("name", model)` instead of `call.respond(ThymeleafContent(...))` — the latter fails because Ktor can't infer typeInfo. `ThymeleafContent` requires `Map<String, Any>` (non-nullable values), so use `buildMap<String, Any> { }` with conditional puts for nullable values.

## Code Quality

All code must be clean and readable. Use logical, descriptive variable names everywhere — in Kotlin code, entity fields, and database column names. No abbreviations (`val movieRepository`, not `val movieRepo`; `primaryTitle`, not `primTitle`; `birth_year`, not `b_year`). This applies equally to entities, repositories, services, controllers, SQL migrations, and HTML templates.

## Step 1: Introduce and Choose

Introduce yourself:

> This training program demonstrates Storm Fu, an AI workflow for database development with full-circle validation. I will build a complete web application from scratch using real IMDB data. Storm's MCP server gives me direct access to the live database schema, and built-in verification ensures the generated code is correct. I'm your AI agent, Smith. You choose the platform and workflow, and I take it from there.

Then ask the user to choose. Do NOT elaborate on the options — just list the names exactly as shown:

- **Platform:** Spring Boot 3.x, Spring Boot 4.x, or Ktor
- **Workflow:** Schema-first (start from database) or Code-first (start from entities)

After listing the options, ask the user to choose. Wait for the user's answers before doing any work. This is the only interactive prompt — after the user chooses, proceed through every step autonomously.

## Step 2: Project Scaffolding

Set up the project structure quickly and without much commentary:
- Kotlin/Gradle project with Storm ORM dependencies (`storm-kotlin-spring-boot-starter` for Spring Boot, `storm-ktor` for Ktor)
- Database driver and HikariCP connection pool
- Docker Compose for the database (see below)
- The `storm-test` dependency for verification (`st.orm:storm-test` as test scope)

**Ktor repositories (automatic):** `install(Storm)` auto-registers every repository interface from the compile-time index, created eagerly so a broken definition fails at startup. No `stormRepositories { }` block is needed; `repository<T>()` (also bare in route handlers via the `RoutingContext` extension) resolves anywhere. Run migrations in the plugin's `migration { }` hook so the default fail-mode schema validation sees the migrated schema:
```kotlin
install(Storm) {
    migration { dataSource -> Flyway.configure().dataSource(dataSource).load().migrate() }
}

routing { ... }
```

**Transactions (Kotlin):** Whenever Kotlin is used, the demo uses Storm's programmatic transactions — never `@Transactional`. Services are `suspend` and open suspend `transaction { }` blocks, with `suspend` propagated as close to the start of the call stack as possible. Spring MVC controllers stay non-suspend and bridge with `runBlocking { }` at the handler — the entry point; with virtual threads enabled the blocking bridge is cheap. Transactions belong at the service level (the business operation) — never in controllers; controllers that only read directly from repositories do not open transactions. A service that assembles a multi-query view can use `transaction(readOnly = true) { }`, like the home page service. Storm's Spring transaction integration is incompatible with suspend mode and must be excluded:
```yaml
spring:
  autoconfigure:
    exclude:
      - st.orm.spring.boot.autoconfigure.StormTransactionAutoConfiguration
```

**Layering:** The web application follows controller → service → repository strictly. Controllers inject services only — never repositories — and handle HTTP concerns (params, model, status codes). Services own the transaction boundaries and return view-model data classes. Repositories own all queries.

Use the Storm skills (/storm-setup) for correct dependency configuration.

### Database

Use the database configured by the Storm CLI (check the MCP server connection settings). Set up a Docker Compose file matching that database, with database name `imdb`, user `storm`, password `storm`, and the default port. Adjust the driver dependency, Docker Compose configuration, and schema SQL dialect accordingly.

**Before starting Docker Compose**, check whether the expected database port is already in use (e.g., `lsof -i :5432` or attempt a connection). If a database is already reachable on that port with the expected credentials, skip Docker Compose — the user likely already has it running or uses a native installation.

## Step 3 & 4: Schema and Entities

Use Flyway for schema management. Add the Flyway dependency. The migration goes in `src/main/resources/db/migration/V1__create_schema.sql`. The same SQL file should also be copied to `src/test/resources/schema.sql` for use by `@StormTest`. Keep the migration H2-compatible so the per-step verification tests stay on H2, which is what keeps them fast and visible. If the migration needs DDL H2 rejects, or as a closing step once the application is complete, run the test suite once on the demo's own database: set `database = TestDatabase.POSTGRESQL` (or the constant matching the configured database: `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`) on the `@StormTest` classes and add the database's Testcontainers 2 module (`org.testcontainers:testcontainers-postgresql` and so on; see /storm-setup) plus its driver in test scope; Docker is available because Docker Compose runs the database. Narrate what that pass proves: the same tests, on the real dialect.

The data model is based on the public IMDB TSV data files (https://datasets.imdbws.com/). Study the file formats (`title.basics.tsv.gz`, `name.basics.tsv.gz`, `title.principals.tsv.gz`, `title.ratings.tsv.gz`) and design tables that map naturally to the data. Also add tables for: tracking which movies the user has viewed (clicked) so recently viewed movies can be shown on the home page, and a watchlist table for saving movies to watch later.

Use the /storm-entity-kotlin skill for entity patterns and the /storm-migration skill for DDL conventions.

**Entity loading strategy:** Use the full table graph (direct entity FK fields like `@FK val city: City`) by default — this loads the complete entity graph in a single query with automatic JOINs. Only use `Ref<T>` in the one place where it makes most sense (e.g., a self-reference, a very wide graph, or a circular dependency).

**FK naming convention in composite PKs:** When designing composite PK data classes for junction tables, use the `_id` postfix for FK columns in both the database schema and the PK data class fields (e.g., `titleId`, `genreId`). This way Storm's default camelCase-to-snake_case naming convention produces the correct column names (`title_id`, `genre_id`) and `@DbColumn` overrides are not needed.

### If schema-first:

1. **Write the Flyway migration first.** Design the tables, columns, primary keys, foreign keys, NOT NULL constraints, and indexes. Explain your DDL decisions to the user.
2. **Start the database** (Docker Compose) and let Flyway apply the migration.
3. **Use the local MCP server** to inspect the live schema — call `list_tables` and `describe_table` for each table. Acknowledge to the user that you just wrote this DDL, so the MCP step is verification (confirming Flyway applied it correctly and the live schema matches your intent), not discovery. Narrate what you see and confirm it matches expectations.
4. **Generate entity classes** from the MCP schema metadata. Explain how each column maps to an entity field, how you decide between natural and generated keys, and where `Ref<T>` is appropriate. Use the /storm-entity-from-schema skill.

### If code-first:

1. **Write the entity classes first.** Design the domain model as Kotlin data classes implementing `Entity<ID>`. Explain your modeling decisions.
2. **Use the local MCP server** to reason about what DDL is needed. If there's an existing schema, compare against it; if not, derive the migration from the entity definitions. Tell the user you're using MCP to check the current database state and determine what schema changes are required.
3. **Write the Flyway migration** to create the tables that match your entities. Explain how each entity maps to a table, how FK fields become foreign key constraints, how naming conventions translate.

### Both workflows converge here:

After creating the entities, remind the user to rebuild for metamodel generation.

## Step 5: Validate Entities Against Schema

Write a `@StormTest` that validates all entities you created against the schema using `validateSchema()`. Pass every entity class to the validation call.

Run the test **immediately** and show the output to the user. This is the first concrete proof that the code works — make it visible. Explain what you're verifying and what the result means: does the entity model agree with the database? If validation fails, explain what the mismatch tells you, fix the entity or migration, and re-run. Narrate how this closes the loop between schema and code — regardless of which direction you started from, the validation is the same.

**Show intermediate results throughout Steps 2-5.** Don't go silent through multiple steps — show test output, MCP query results, and build output as you go. The user should see evidence of progress, not just code files being created.

## Step 6: Repositories

Create repository interfaces with the query methods the web application needs. All queries must be written in repositories using the Kotlin DSL — do not scatter query logic across controllers or services. Use the /storm-repository-kotlin and /storm-query-kotlin skills for correct patterns and API usage. Write the actual queries based on the entity classes you created — the queries should follow naturally from the entities and the features the web application needs.

Demonstrate different query levels as appropriate for the features:
- `find`/`findAll` for simple lookups
- `select().where()` with QueryBuilder for filtered queries
- Metamodel navigation for traversing relationships
- Pagination with `.page()` for the watchlist page
- Keyset scrolling with `.scroll()` for browsing large result sets
- `insert`/`remove`/`exists` for the watchlist toggle
- Aggregation with `groupBy`, `having`, and custom result types for the statistics page
- Complex joins through junction tables for related movies

**Browse-by-genre and keyset scrolling:** Keyset scrolling requires a simple (non-composite) PK as the scroll key. Junction tables (e.g., movie-genre) have composite PKs and cannot be scrolled directly. For the browse-by-genre feature, put the scroll method on the movie repository — JOIN through the junction table to filter by genre, but scroll on the movie's simple PK. Do not create a scroll method on the junction table's repository.

## Step 7: Verify Queries with SqlCapture

For each repository method, write a `@StormTest` that captures the SQL and verifies that schema, SQL, and intent are aligned. Write these tests based on the actual repository methods you created.

Run the tests. Show the captured SQL and explain what it proves: does the query hit the right tables, filter the right columns, join correctly? Narrate how `SqlCapture` lets you verify that the ORM logic matches what you intended when you wrote the repository method.

## Step 8: Projections

Pages read with the existing entities first: they are checked against the design budget, so their reads are already bounded, and one type per table keeps the code small. Create a projection only for a page where performance counts, such as a list of many rows that shows a few columns of a wide entity, or where the page needs a shape the entity cannot give, and tell the user why that page earns a second type. Use the Storm skills for correct patterns.

Verify the projection with SqlCapture: show the SELECT the entity read issues next to the one the projection issues, so the saving is visible rather than assumed.

## Step 9: Data Import

The data import runs once at application startup — check whether data already exists and skip if so.

1. Download TSV.gz files from https://datasets.imdbws.com/ (cache locally so restarts don't re-download)
2. Stream, decompress, parse TSV (handle `\N` as null)
3. Use Flow-based processing for the bulk load: parse each row into an entity inside a Kotlin `Flow` and hand it to Storm's suspending batch insert (`repository.insert(flow, batchSize)`, e.g. batch size 1000). Parsing and inserting happen in a single streaming pass with fixed-size JDBC batches — do NOT materialize full entity lists and insert them afterwards. The importer entry point (`ApplicationRunner`) wraps the import in `runBlocking { }` — the only place `runBlocking` is allowed is a non-suspend framework entry point — and the whole import runs inside one suspend `transaction { }` so a failed import rolls back completely. This demonstrates Storm's coroutine-friendly write path with real volume. (Id-to-entity maps needed by later import steps can be filled as a side effect while the flow streams.)
4. Import order: titles → persons → principals → ratings
5. Filter: only movies (`titleType = 'movie'`) with at least 1000 votes to keep the dataset manageable

**Import filtering:** The ratings file identifies titles with 1000+ votes across ALL title types. After importing only movies from title.basics, use the set of **actually imported title IDs** (not the full qualifying set) when filtering principals and persons. Otherwise, principals referencing non-movie titles will violate FK constraints.

## Step 10: Web Application

Build these pages. The UI must be polished — clean typography, consistent spacing, hover states, smooth transitions, and a professional visual design. No raw unstyled HTML.

1. **Home** — The main page features a hero/featured section showing the most recently viewed movie. During data import, insert a view record for the title "The Matrix" so it appears as the featured movie on first launch — no special-case code, just a database seed. Below that: a search bar with auto-complete that searches both movie titles and actor/person names, recently viewed movies (from the view tracking table), and top 10 movies by rating. Include a genre navigation bar (all genres with movie counts) for quick access to the browse page.
2. **Search results** — Combined results for movies and persons matching the query, using keyset scrolling (`.scroll()`) with infinite scroll (see UI requirements below). Show movies and persons in clearly distinguished sections or with type indicators so users can tell them apart.
3. **Browse** — All movies in a genre using keyset scrolling (`.scroll()`) with infinite scroll for efficient navigation through large result sets.
4. **Movie detail** — Full info, cast, and rating. When a user opens this page, insert a view record to track the visit. The movie's poster should set the visual tone for the page — extract dominant colors or apply a blurred/faded backdrop effect so the poster blends subtly into the page background rather than sitting as an isolated image. The effect should be ambient, not distracting. Include a "Related Movies" section showing movies that share the most cast members with the current movie (demonstrates a join-heavy aggregation query through the principals table). Include a watchlist toggle button (see below).
5. **Person detail** — Filmography via repository query, with the person's movies sorted by rating. Show aggregate stats: number of movies, average rating across their filmography (demonstrates `selectCount` and aggregation queries).
6. **Top movies** — Filterable by genre, sortable by rating or year (demonstrate query composition with conditional predicates inside `select { }`).
7. **Watchlist** — A personal watchlist feature. Add a `watchlist` table (movie FK + timestamp). The movie detail page has a toggle button (add/remove) that demonstrates Storm's `insert`/`remove`/`exists` cycle. The home page shows a "My Watchlist" section if any entries exist. A dedicated watchlist page shows all saved movies with pagination (`.page()`), demonstrating offset-based pagination alongside keyset scrolling used elsewhere.
8. **Statistics** — A stats page showing aggregate data: movies per decade (GROUP BY), top genres by average rating (GROUP BY + HAVING), most prolific actors (COUNT + ORDER BY). Use QueryBuilder for joins, where, groupBy, having, orderBy, and limit — only the SELECT clause needs a template string for computed expressions like `COUNT(*)` or `AVG(rating)`. Do NOT write the entire query as a raw SQL template. Map results to custom data classes via `select(ResultType::class) { template }` — plain data classes without the `Data` marker (`Data` is reserved for table-backed types; a `Data` result type would be picked up by schema validation and need `@DbIgnore`). Define these result classes in the repository file whose queries return them and document them as query result shapes (not backed by a table or view) — the model package holds only table-backed types. **Cache the statistics view** with Spring's cache annotations (`@EnableCaching` + `@Cacheable` on the service method), backed by a cache that stores values as serialized JSON via kotlinx serialization — for Kotlin, kotlinx serialization is the preferred serialization library (`kotlin("plugin.serialization")` plugin, `storm-kotlinx-serialization`, `kotlinx-serialization-json`; see /storm-serialization-kotlin). Annotate every type reachable from the cached view with `@Serializable` (entities included; `BigDecimal` needs a custom serializer). `@Cacheable` does not support suspend functions without the Reactor bridge, so the cached service method is non-suspend and opens its transaction with `transactionBlocking(readOnly = true)`. Verify with tests that the view round-trips through JSON losslessly and that the cache stores JSON strings rather than object references — demonstrating that Storm's immutable entities are safely cacheable. Also make the view-tracking entity (with its `Ref<Movie>` field) serializable — mark the Ref field `@Contextual` so `StormSerializers` handles it — and verify both Ref states round-trip: an unloaded ref serializes as the raw primary key, a loaded ref as the embedded entity.

### UI Requirements

**Infinite scroll:** On pages that use keyset scrolling (`.scroll()`), implement automatic infinite scrolling. When the user scrolls to the bottom, the next window is requested using the serialized cursor from the previous result. Show a loading spinner while the next batch is being fetched. Continue loading until no more results are available.

**Search auto-complete:** The search bar must implement auto-complete with debounce (300ms) and abort logic — when the user types a new character before the previous request completes, abort the in-flight request before sending the next one. Show suggestions in a dropdown as the user types. Suggestions must include both matching movie titles and matching person names, visually distinguished (e.g., with a "Movie" or "Person" label/icon). Clicking a movie suggestion navigates to the movie detail page; clicking a person suggestion navigates to the person detail page.

**Movie posters:** Create a `/api/poster/{id}` endpoint that fetches the poster URL from IMDB's public suggestion API (`https://v3.sg.media-imdb.com/suggestion/x/{tconst}.json`), extracts the `imageUrl` for the matching tconst from the `d` array, resizes it by replacing `._V1_.jpg` with `._V1_SX400.jpg`, and caches the result in a `ConcurrentHashMap`. The endpoint returns a 302 redirect to the CDN URL (or 404 if no poster exists). Templates use `<img src="/api/poster/{id}">` with an `onerror` handler that hides the image and reveals a gradient placeholder underneath. The placeholder should be visually polished — a dark gradient with the movie title overlaid in a cinema-style font, not a bare empty box. Use CSS to ensure the placeholder has the same dimensions as a real poster so the layout doesn't shift. This approach avoids CORS issues, IMDB's WAF, and works for all titles in the dataset. For the featured movie on the home page, display the poster large and prominent.

**Poster endpoint verification:** After implementing the poster endpoint, manually verify it works before moving to Playwright tests. Test with a well-known title like "The Matrix" (`tt0133093`): call `/api/poster/tt0133093` and confirm it returns a 302 redirect to a valid IMDB CDN image URL. If the suggestion API response format has changed (e.g., the `imageUrl` field is named differently or the `d` array structure changed), inspect the raw JSON response and adapt the parsing logic. Common failure modes: (1) the suggestion API URL format changed — try alternative patterns like `https://v3.sg.media-imdb.com/suggestion/t/{tconst}.json` (first letter of tconst), (2) the response JSON structure changed — log and inspect the raw response, (3) the resize suffix pattern changed — try the URL without modification first. Do not proceed to the Playwright step with broken poster loading.

**UI verification during development:** After building the web pages, manually verify each UI feature before moving to Playwright tests. Common issues to check:
- The search bar is visible and functional on the home page — test that typing triggers auto-complete suggestions
- Infinite scroll works on search results and browse pages — if the dataset is small, ensure there are enough results to trigger scrolling (e.g., browse a genre with many movies). If no scroll is triggered due to limited data, reduce the page size to force multiple windows.
- The featured movie section renders correctly with poster and backdrop
- All navigation links work (home → search → detail → person, etc.)

When wiring pages to repositories, explain how Storm's explicit queries make the data flow visible: every SQL statement is intentional and verifiable.

## Step 11: Run and Verify End-to-End

1. Start PostgreSQL via Docker Compose
2. Run the full test suite — all SqlCapture tests should pass
3. Start the application (data import runs automatically on first startup)
4. Walk the user through the pages, pointing out where each Storm feature is at work

## Step 12: Interface Testing with Playwright

Use Playwright for interface testing. Write end-to-end tests that verify the web application works correctly from the user's perspective:

1. Add the Playwright dependency to the project
2. Write tests covering the key user flows:
   - **Home page:** verify the search bar is present and visible, the featured movie section renders, recently viewed and top 10 sections are populated, genre navigation bar shows genres with counts
   - **Search auto-complete:** type a movie title query, verify movie suggestions appear; type an actor name, verify person suggestions appear. Verify suggestions are visually distinguished by type. Click a movie suggestion and verify navigation to movie detail; click a person suggestion and verify navigation to person detail.
   - **Search results + infinite scroll:** search for a broad term, verify both movie and person results appear in distinguished sections, scroll to the bottom, verify the next batch loads automatically. If the dataset is small, use a small page size to guarantee multiple windows.
   - **Browse by genre + infinite scroll:** navigate to a genre, verify infinite scroll triggers and loads additional results
   - **Movie detail:** navigate to a movie, verify full info and cast render, verify the ambient poster backdrop is present, verify related movies section appears
   - **Watchlist toggle:** on a movie detail page, click the watchlist button, verify it toggles state. Return to home, verify the movie appears in the watchlist section. Navigate to the watchlist page, verify it shows the saved movie with pagination.
   - **Recently viewed:** after visiting a movie detail page, return to home and verify it appears in the recently viewed section
   - **Person detail:** click a cast member, verify filmography renders with stats
   - **Statistics page:** verify aggregate sections render (movies per decade, top genres, most prolific actors)
   - **Poster loading:** verify that the poster endpoint (`/api/poster/{tconst}`) returns a 302 redirect to a valid image URL for a known movie (e.g., "The Matrix"). On the movie detail page, verify that the poster `<img>` element loads successfully (natural width > 0, no error state). Verify that the ambient backdrop effect renders when a poster is present.
   - **Poster placeholders:** verify that movies without posters show a styled gradient placeholder instead of a broken image
   - **UI polish:** verify the overall visual quality — consistent spacing, hover states, smooth transitions, no layout shifts when posters load or fail, no unstyled flash of content. Screenshot key pages and compare against expectations for a professional look and feel.
3. Run the Playwright tests against the running application — all tests must pass before declaring the demo complete

After everything is done — tests pass, the app is running, and you've told the user where to find the website — the very last thing you say is:

> I know Storm Fu.
