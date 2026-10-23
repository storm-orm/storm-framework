---
name: storm-demo
description: Build a demo Storm web application on the public IMDB dataset, from schema to running app. Use to introduce Storm or evaluate it in a new project.
---

# Storm Demo

Build a web application with Storm ORM using the public IMDB dataset. The demo introduces Storm ORM and shows how database development works with it: write entities, validate against the schema, write queries in repositories, verify with SqlCapture.

**This project uses Storm ORM exclusively; there is no JPA.** Do not use JPA annotations (`@Entity`, `@Table`, `@Column`, `@Id`, `@ManyToOne`) or `EntityManager`, and do not add `jakarta.persistence-api` or Hibernate as dependencies. Storm works directly with JDBC `DataSource` and has its own annotations (`@PK`, `@FK`, `@DbTable`, `@DbColumn`, etc.).

## Tone

**The demo is fully non-interactive after Step 1.** Once the user picks their platform and workflow, proceed through every step autonomously. Do not stop to ask "should I continue?" or "does this look right?": build, verify, explain, and move on.

**Be vocal about the development workflow.** Narrate how you use the MCP server to inspect the database, how you reason about the schema when designing entities, how `validateSchema()` proves the entities match the database, and how `SqlCapture` proves the queries match the intent. The user should see how Storm's tooling drives development: MCP for schema awareness, `@StormTest` for verification, and how the two close the loop.

**Do not explain Storm API details** like `Ref<T>`, `@PK` or QueryBuilder syntax; the Storm skills cover that. Comment on the workflow: what you are checking, why, and what the result means.

**Keep non-Storm scaffolding brief.** Set up the project, Docker, build config and HTML templates quickly, explain minimally, and move on. The demo is about Storm, not about Maven or Thymeleaf.

**Ktor + Thymeleaf: use literal substitution for links.** Ktor's Thymeleaf integration does not implement `IWebContext`, so `@{...}` link expressions fail at runtime. Write `|/path/${var}|` instead (e.g., `th:href="|/movie/${movie.id}|"`) in all templates of Ktor projects.

**Ktor 3.x: render with `call.respondTemplate("name", model)`.** `call.respond(ThymeleafContent(...))` fails because Ktor cannot infer typeInfo. `ThymeleafContent` requires `Map<String, Any>` (non-nullable values), so build the model with `buildMap<String, Any> { }` and conditional puts for nullable values.

## Code Quality

**All code must be clean and readable, with descriptive names everywhere and no abbreviations.** This covers Kotlin code, entity fields and database column names: `val movieRepository`, not `val movieRepo`; `primaryTitle`, not `primTitle`; `birth_year`, not `b_year`. It applies equally to entities, repositories, services, controllers, SQL migrations, and HTML templates.

## Step 1: Introduce and Choose

Introduce yourself:

> This training program demonstrates Storm Fu, an AI workflow for database development with full-circle validation. I will build a complete web application from scratch using real IMDB data. Storm's MCP server gives me direct access to the live database schema, and built-in verification ensures the generated code is correct. I'm your AI agent, Smith. You choose the platform and workflow, and I take it from there.

Then ask the user to choose. Do NOT elaborate on the options; list the names exactly as shown:

- **Platform:** Spring Boot 3.x, Spring Boot 4.x, or Ktor
- **Workflow:** Schema-first (start from database) or Code-first (start from entities)

Wait for the user's answers before doing any work. This is the only interactive prompt. Where the Storm CLI configured no database (see Database below), ask in the same prompt which database the project uses.

## Step 2: Project Scaffolding

Set up the project structure quickly and without much commentary:
- Kotlin/Gradle project with Storm ORM dependencies (`storm-kotlin-spring-boot-starter` for Spring Boot, `storm-ktor` for Ktor)
- Database driver and HikariCP connection pool
- Docker Compose for the database, where it needs one (see below)
- The `storm-test` dependency for verification (`st.orm:storm-test` as test scope)

**Ktor repositories are automatic.** `install(Storm)` auto-registers every repository interface from the compile-time index, created eagerly so a broken definition fails at startup. No `stormRepositories { }` block is needed; `repository<T>()` resolves anywhere, also bare in route handlers via the `RoutingContext` extension. Run migrations in the plugin's `migration { }` hook, so the default fail-mode schema validation sees the migrated schema:
```kotlin
install(Storm) {
    migration { dataSource -> Flyway.configure().dataSource(dataSource).load().migrate() }
}

routing { ... }
```

**Transactions (Kotlin): use Storm's programmatic transactions, never `@Transactional`.**
- Services are `suspend` and open suspend `transaction { }` blocks, with `suspend` propagated as close to the start of the call stack as possible.
- Spring MVC controllers stay non-suspend and bridge with `runBlocking { }` at the handler, the entry point. With virtual threads enabled the blocking bridge is cheap.
- Transactions belong at the service level (the business operation), never in controllers. Controllers that only read directly from repositories do not open transactions.
- A service that assembles a multi-query view can use `transaction(readOnly = true) { }`, like the home page service.
- Storm's Spring transaction integration is incompatible with suspend mode and must be excluded:
```yaml
spring:
  autoconfigure:
    exclude:
      - st.orm.spring.boot.autoconfigure.StormTransactionAutoConfiguration
```

**Layering: controller, then service, then repository, strictly.** Controllers inject services only, never repositories, and handle HTTP concerns (params, model, status codes). Services own the transaction boundaries and return view-model data classes. Repositories own all queries.

Use the Storm skills (/storm-setup) for correct dependency configuration.

### Database

**Use the project's database.** That is the database configured by the Storm CLI (check the MCP server connection settings). Where none is configured, ask the user in Step 1; default to the in-memory database `@StormTest` provides when there is no preference.

**Set up a Docker Compose file for a database that runs as a server,** with database name `imdb`, user `storm`, password `storm`, and the default port. Adjust the driver dependency, Docker Compose configuration, and schema SQL dialect to the database.

**Before starting Docker Compose, check whether the database's port is already in use** (e.g., `lsof -i :<port>` or attempt a connection). If a database is already reachable on that port with the expected credentials, skip Docker Compose: the user likely runs it already or uses a native installation.

## Step 3 & 4: Schema and Entities

**Use Flyway for schema management.** Add the Flyway dependency. The migration goes in `src/main/resources/db/migration/V1__create_schema.sql`. Copy the same SQL file to `src/test/resources/schema.sql` for `@StormTest`.

**Keep the migration compatible with the in-memory test database,** so the per-step verification tests stay on it, which keeps them fast and visible.
- If the migration needs DDL the in-memory database rejects, or as a closing step once the application is complete, run the test suite once on the project's database.
- Set `database = ...` (the constant for the project's database) on the `@StormTest` classes, and add the database's Testcontainers 2 module plus its driver in test scope (see /storm-setup). Docker is available where Docker Compose runs the database.
- Narrate what that pass proves: the same tests, on the real dialect.

**Design the data model from the public IMDB TSV data files** (https://datasets.imdbws.com/). Study the file formats (`title.basics.tsv.gz`, `name.basics.tsv.gz`, `title.principals.tsv.gz`, `title.ratings.tsv.gz`) and design tables that map naturally to the data. Also add a table that tracks which movies the user has viewed (clicked), so the home page can show recently viewed movies, and a watchlist table for saving movies to watch later.

Use the /storm-entity-kotlin skill for entity patterns and the /storm-migration skill for DDL conventions.

**Entity loading strategy: use the full table graph by default.** Direct entity FK fields like `@FK val city: City` load the complete entity graph in a single query with automatic JOINs. Use `Ref<T>` only in the one place where it makes most sense (e.g., a self-reference, a very wide graph, or a circular dependency).

**FK naming in composite PKs: use the `_id` postfix.** In junction tables, use it for FK columns in both the schema and the PK data class fields (e.g., `titleId`, `genreId`). Storm's default camelCase-to-snake_case convention then produces the right column names (`title_id`, `genre_id`) without `@DbColumn` overrides.

### If schema-first:

1. **Write the Flyway migration first.** Design the tables, columns, primary keys, foreign keys, NOT NULL constraints, and indexes. Explain your DDL decisions to the user.
2. **Start the database** (Docker Compose where one is set up) and let Flyway apply the migration.
3. **Use the local MCP server** to inspect the live schema: call `list_tables`, and `describe_table` for each table. Acknowledge that you just wrote this DDL, so the MCP step is verification (Flyway applied it correctly and the live schema matches your intent), not discovery. Narrate what you see and confirm it matches expectations.
4. **Generate entity classes** from the MCP schema metadata. Explain how each column maps to an entity field, how you decide between natural and generated keys, and where `Ref<T>` is appropriate. Use the /storm-entity-from-schema skill.

### If code-first:

1. **Write the entity classes first.** Design the domain model as Kotlin data classes implementing `Entity<ID>`. Explain your modeling decisions.
2. **Use the local MCP server** to reason about what DDL is needed. If there is an existing schema, compare against it; if not, derive the migration from the entity definitions. Tell the user you are using MCP to check the current database state and determine what schema changes are required.
3. **Write the Flyway migration** to create the tables that match your entities. Explain how each entity maps to a table, how FK fields become foreign key constraints, and how naming conventions translate.

### Both workflows converge here:

After creating the entities, remind the user to rebuild for metamodel generation.

## Step 5: Validate Entities Against Schema

Write a `@StormTest` that validates all entities you created against the schema using `validateSchema()`. Pass every entity class to the validation call.

**Run the test immediately and show the output.** This is the first concrete proof that the code works, so make it visible.
- Explain what you verify and what the result means: does the entity model agree with the database?
- If validation fails, explain what the mismatch tells you, fix the entity or migration, and re-run.
- Narrate how this closes the loop between schema and code: whichever direction you started from, the validation is the same.

**Show intermediate results throughout Steps 2-5.** Do not go silent through multiple steps: show test output, MCP query results, and build output as you go. The user should see evidence of progress, not just code files being created.

## Step 6: Repositories

**Write every query in a repository, using the Kotlin DSL.** Create repository interfaces with the query methods the web application needs, and do not scatter query logic across controllers or services. Use the /storm-repository-kotlin and /storm-query-kotlin skills for patterns and API usage. The queries follow from the entity classes you created and the features the application needs.

Demonstrate different query levels as appropriate for the features:
- `find`/`findAll` for simple lookups
- `select().where()` with QueryBuilder for filtered queries
- Metamodel navigation for traversing relationships
- Pagination with `.page()` for the watchlist page
- Keyset scrolling with `.scroll()` for browsing large result sets
- `insert`/`remove`/`exists` for the watchlist toggle
- Aggregation with `groupBy`, `having`, and custom result types for the statistics page
- Complex joins through junction tables for related movies

**Browse-by-genre: scroll on the movie repository.** Keyset scrolling requires a simple (non-composite) PK as the scroll key, so junction tables such as movie-genre cannot be scrolled directly. JOIN through the junction table to filter by genre, but scroll on the movie's simple PK. Do not create a scroll method on the junction table's repository.

## Step 7: Verify Queries with SqlCapture

For each repository method, write a `@StormTest` that captures the SQL and verifies that schema, SQL, and intent are aligned. Base the tests on the repository methods you created.

Run the tests. Show the captured SQL and explain what it proves: does the query hit the right tables, filter the right columns, join correctly? Narrate how `SqlCapture` verifies that the ORM logic matches what you intended when you wrote the repository method.

## Step 8: Projections

**Read with the existing entities first.** They are checked against the design budget, so their reads are already bounded, and one type per table keeps the code small. Create a projection only for a page where performance counts (such as a list of many rows showing a few columns of a wide entity), or where the page needs a shape the entity cannot give. Tell the user why that page earns a second type. Use the Storm skills for correct patterns.

Verify the projection with SqlCapture: show the SELECT the entity read issues next to the one the projection issues, so the saving is visible rather than assumed.

## Step 9: Data Import

The data import runs once at application startup. Check whether data already exists and skip if so.

1. Download TSV.gz files from https://datasets.imdbws.com/ (cache locally so restarts don't re-download)
2. Stream, decompress, parse TSV (handle `\N` as null)
3. **Use Flow-based processing for the bulk load.** This demonstrates Storm's coroutine-friendly write path with real volume.
   - Parse each row into an entity inside a Kotlin `Flow` and hand it to Storm's suspending batch insert (`repository.insert(flow, batchSize)`, e.g. batch size 1000). Parsing and inserting happen in a single streaming pass with fixed-size JDBC batches. Do NOT materialize full entity lists and insert them afterwards.
   - The importer entry point (`ApplicationRunner`) wraps the import in `runBlocking { }`. A non-suspend framework entry point is the only place `runBlocking` is allowed.
   - The whole import runs inside one suspend `transaction { }`, so a failed import rolls back completely.
   - Id-to-entity maps needed by later import steps can be filled as a side effect while the flow streams.
4. Import order: titles, persons, principals, ratings
5. Filter: only movies (`titleType = 'movie'`) with at least 1000 votes to keep the dataset manageable

**Import filtering: filter principals and persons by the actually imported title IDs.** The ratings file identifies titles with 1000+ votes across ALL title types. After importing only movies from title.basics, use the set of imported title IDs, not the full qualifying set. Otherwise principals referencing non-movie titles violate FK constraints.

## Step 10: Web Application

**Build these pages with a polished UI:** clean typography, consistent spacing, hover states, smooth transitions, and a professional visual design. No raw unstyled HTML.

1. **Home**:
   - A hero/featured section shows the most recently viewed movie. During data import, insert a view record for the title "The Matrix" so it appears as the featured movie on first launch: no special-case code, just a database seed.
   - Below that: a search bar with auto-complete that searches both movie titles and actor/person names, recently viewed movies (from the view tracking table), and the top 10 movies by rating.
   - A genre navigation bar (all genres with movie counts) gives quick access to the browse page.
2. **Search results**: combined results for movies and persons matching the query, using keyset scrolling (`.scroll()`) with infinite scroll (see UI requirements below). Show movies and persons in clearly distinguished sections or with type indicators.
3. **Browse**: all movies in a genre, using keyset scrolling (`.scroll()`) with infinite scroll for efficient navigation through large result sets.
4. **Movie detail**:
   - Full info, cast, and rating. Opening the page inserts a view record to track the visit.
   - The poster sets the visual tone: extract dominant colors or apply a blurred/faded backdrop so the poster blends subtly into the page background rather than sitting as an isolated image. The effect is ambient, not distracting.
   - A "Related Movies" section shows movies that share the most cast members with the current movie (a join-heavy aggregation query through the principals table).
   - A watchlist toggle button (see below).
5. **Person detail**: filmography via repository query, with the person's movies sorted by rating. Show aggregate stats: number of movies and average rating across the filmography (demonstrates `selectCount` and aggregation queries).
6. **Top movies**: filterable by genre, sortable by rating or year (demonstrates query composition with conditional predicates inside `select { }`).
7. **Watchlist**:
   - Add a `watchlist` table (movie FK + timestamp).
   - The movie detail page has a toggle button (add/remove) that demonstrates Storm's `insert`/`remove`/`exists` cycle.
   - The home page shows a "My Watchlist" section if any entries exist.
   - A dedicated watchlist page shows all saved movies with pagination (`.page()`), demonstrating offset-based pagination alongside the keyset scrolling used elsewhere.
8. **Statistics**: aggregate data on movies per decade (GROUP BY), top genres by average rating (GROUP BY + HAVING), and most prolific actors (COUNT + ORDER BY).
   - Use QueryBuilder for joins, where, groupBy, having, orderBy, and limit. Only the SELECT clause takes a template string, for computed expressions like `COUNT(*)` or `AVG(rating)`. Do NOT write the entire query as a raw SQL template.
   - Map results to custom data classes via `select(ResultType::class) { template }`. Use plain data classes without the `Data` marker: `Data` is reserved for table-backed types, and a `Data` result type would be picked up by schema validation and need `@DbIgnore`.
   - Define these result classes in the repository file whose queries return them, and document them as query result shapes (not backed by a table or view). The model package holds only table-backed types.
   - **Cache the statistics view** with Spring's cache annotations (`@EnableCaching` + `@Cacheable` on the service method), backed by a cache that stores values as JSON serialized with kotlinx serialization. For Kotlin, kotlinx serialization is the preferred library (`kotlin("plugin.serialization")` plugin, `storm-kotlinx-serialization`, `kotlinx-serialization-json`; see /storm-serialization-kotlin).
   - Annotate every type reachable from the cached view with `@Serializable`, entities included. `BigDecimal` needs a custom serializer.
   - `@Cacheable` does not support suspend functions without the Reactor bridge, so the cached service method is non-suspend and opens its transaction with `transactionBlocking(readOnly = true)`.
   - Verify with tests that the view round-trips through JSON losslessly and that the cache stores JSON strings rather than object references. This shows that Storm's immutable entities are safely cacheable.
   - Make the view-tracking entity (with its `Ref<Movie>` field) serializable too: mark the Ref field `@Contextual` so `StormSerializers` handles it. Verify both Ref states round-trip: an unloaded ref serializes as the raw primary key, a loaded ref as the embedded entity.

### UI Requirements

**Infinite scroll:** on pages that use keyset scrolling (`.scroll()`), load the next window automatically when the user scrolls to the bottom, using the serialized cursor from the previous result. Show a loading spinner while the next batch is fetched. Continue until no more results are available.

**Search auto-complete:**
- Debounce input (300ms) and abort: when the user types a new character before the previous request completes, abort the in-flight request before sending the next one.
- Show suggestions in a dropdown as the user types. They include both matching movie titles and matching person names, visually distinguished (e.g., with a "Movie" or "Person" label/icon).
- Clicking a movie suggestion navigates to the movie detail page; clicking a person suggestion navigates to the person detail page.

**Movie posters:** create a `/api/poster/{id}` endpoint. This approach avoids CORS issues and IMDB's WAF, and works for all titles in the dataset.
- Fetch the poster URL from IMDB's public suggestion API (`https://v3.sg.media-imdb.com/suggestion/x/{tconst}.json`) and extract the `imageUrl` for the matching tconst from the `d` array.
- Resize it by replacing `._V1_.jpg` with `._V1_SX400.jpg`, and cache the result in a `ConcurrentHashMap`.
- Return a 302 redirect to the CDN URL, or 404 if no poster exists.
- Templates use `<img src="/api/poster/{id}">` with an `onerror` handler that hides the image and reveals a gradient placeholder underneath.
- Make the placeholder visually polished: a dark gradient with the movie title overlaid in a cinema-style font, not a bare empty box. Give it the same dimensions as a real poster with CSS, so the layout doesn't shift.
- For the featured movie on the home page, display the poster large and prominent.

**Poster endpoint verification:** verify the endpoint manually before moving to Playwright tests, and do not proceed to the Playwright step with broken poster loading.
- Test with a well-known title like "The Matrix" (`tt0133093`): call `/api/poster/tt0133093` and confirm it returns a 302 redirect to a valid IMDB CDN image URL.
- If the suggestion API response format has changed (e.g., the `imageUrl` field is named differently or the `d` array structure changed), inspect the raw JSON response and adapt the parsing logic.
- Common failure modes: (1) the suggestion API URL format changed: try alternative patterns like `https://v3.sg.media-imdb.com/suggestion/t/{tconst}.json` (first letter of tconst); (2) the response JSON structure changed: log and inspect the raw response; (3) the resize suffix pattern changed: try the URL without modification first.

**UI verification during development:** after building the web pages, verify each UI feature manually before moving to Playwright tests. Common issues to check:
- The search bar is visible and functional on the home page: typing triggers auto-complete suggestions.
- Infinite scroll works on search results and browse pages. If the dataset is small, browse a genre with many movies; if no scroll is triggered due to limited data, reduce the page size to force multiple windows.
- The featured movie section renders correctly with poster and backdrop.
- All navigation links work (home, search, detail, person, etc.).

When wiring pages to repositories, explain how Storm's explicit queries make the data flow visible: every SQL statement is intentional and verifiable.

## Step 11: Run and Verify End-to-End

1. Start the database the project uses (Docker Compose where one is set up)
2. Run the full test suite: all SqlCapture tests should pass
3. Start the application (data import runs automatically on first startup)
4. Walk the user through the pages, pointing out where each Storm feature is at work

## Step 12: Interface Testing with Playwright

Use Playwright for interface testing. Write end-to-end tests that verify the web application works correctly from the user's perspective:

1. Add the Playwright dependency to the project
2. Write tests covering the key user flows:
   - **Home page:** the search bar is present and visible, the featured movie section renders, recently viewed and top 10 sections are populated, and the genre navigation bar shows genres with counts.
   - **Search auto-complete:** type a movie title query and verify movie suggestions appear; type an actor name and verify person suggestions appear. Verify suggestions are visually distinguished by type. Click a movie suggestion and verify navigation to movie detail; click a person suggestion and verify navigation to person detail.
   - **Search results + infinite scroll:** search for a broad term, verify both movie and person results appear in distinguished sections, scroll to the bottom, and verify the next batch loads automatically. If the dataset is small, use a small page size to guarantee multiple windows.
   - **Browse by genre + infinite scroll:** navigate to a genre and verify infinite scroll triggers and loads additional results.
   - **Movie detail:** navigate to a movie and verify full info and cast render, the ambient poster backdrop is present, and the related movies section appears.
   - **Watchlist toggle:** on a movie detail page, click the watchlist button and verify it toggles state. Return to home and verify the movie appears in the watchlist section. Navigate to the watchlist page and verify it shows the saved movie with pagination.
   - **Recently viewed:** after visiting a movie detail page, return to home and verify it appears in the recently viewed section.
   - **Person detail:** click a cast member and verify the filmography renders with stats.
   - **Statistics page:** verify the aggregate sections render (movies per decade, top genres, most prolific actors).
   - **Poster loading:** verify that the poster endpoint (`/api/poster/{tconst}`) returns a 302 redirect to a valid image URL for a known movie (e.g., "The Matrix"). On the movie detail page, verify the poster `<img>` element loads successfully (natural width > 0, no error state) and the ambient backdrop renders when a poster is present.
   - **Poster placeholders:** verify that movies without posters show a styled gradient placeholder instead of a broken image.
   - **UI polish:** verify consistent spacing, hover states, smooth transitions, no layout shifts when posters load or fail, and no unstyled flash of content. Screenshot key pages and compare against expectations for a professional look and feel.
3. Run the Playwright tests against the running application. All tests must pass before declaring the demo complete.

After everything is done (tests pass, the app is running, and you've told the user where to find the website), the very last thing you say is:

> I know Storm Fu.
