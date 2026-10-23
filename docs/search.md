import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Search

Most applications have a search box somewhere: a movie catalog searched by title and director, a customer list searched by name, a vet's front desk looking up an owner by the name of their pet. The box hands the application whatever the user typed: several words, in any case, perhaps a quoted phrase or a stray `%`. Storm turns that input into a predicate the query builder takes like any other, matched the same way on every database, so the application passes the input through as it arrives, with no splitting, escaping or lowering of its own.

| You are building | Use |
|------------------|-----|
| A search results page: the user submits a few words, and every word narrows the results | [`search`](#searching-text) |
| A type-ahead or auto-complete box that queries as the user types | [`suggest`](#suggestions-as-the-user-types), with a limit |
| A search that also matches related rows, such as an owner found by a pet's name | [`search` with a semi-join](#searching-related-rows) |
| Results with the best matches on top, such as an exact title before a partial one | [`orderFirst`](#ranking-the-matches) |
| A search where "leon" finds "Léon" | [`SqlFunction`](#ignoring-accents) over an accent-removing function |

A search that is fast on test data can read every row of a production-sized table; [Indexing a Search](#indexing-a-search) covers the index that keeps it fast.

## Searching Text

A search results page takes the words the user submitted and shows the rows that match all of them. Every word should match somewhere: `ugly bad` finds "The Good, the Bad and the Ugly", and `ugly leone` finds it through the title and the director's name, so the user narrows the results by typing more of what they remember, in any order. `search` takes the input as the box receives it and one or more text columns:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val movies = orm.entity(Movie::class)
    .select()
    .where(search(input, Movie_.title, Movie_.director.name))
    .resultList
```

`search` returns a predicate like the infix functions do, so it combines with them: `search(input, Movie_.title) and (Movie_.year gt 1960)`.

</TabItem>
<TabItem value="java" label="Java">

```java
List<Movie> movies = orm.entity(Movie.class)
    .select()
    .where(it -> it.search(input, Movie_.title, Movie_.director.name))
    .getResultList();
```

`search` returns a predicate like `where` does, so it combines with `.and()` and `.or()`: `it.search(input, Movie_.title).and(it.where(Movie_.year, GREATER_THAN, 1960))`.

</TabItem>
</Tabs>

Every word must occur in at least one of the columns, ignoring case and matched as literal text, as `CONTAINS_IGNORE_CASE` matches it. The predicate is an AND over the words of an OR over the columns, the same statement on every database:

```sql
WHERE (LOWER(m.title) LIKE LOWER(?) ESCAPE '!' OR LOWER(d.name) LIKE LOWER(?) ESCAPE '!')
  AND (LOWER(m.title) LIKE LOWER(?) ESCAPE '!' OR LOWER(d.name) LIKE LOWER(?) ESCAPE '!')
```

| Input | Words |
|-------|-------|
| `ugly bad` | `ugly`, `bad` |
| `"the good" ugly` | `the good`, `ugly`: a quoted phrase is one word |
| `50% a_b` | `50%`, `a_b`, matched literally |
| `"ugly` | `"ugly`: an unpaired quote is an ordinary character |
| empty or blank | none: the predicate matches every row |

`search` matches words; it does not rank the rows it finds, and it does not stem words the way a full-text index does. To put the closest matches first, see [Ranking the Matches](#ranking-the-matches). Accents follow the column's collation; see [Ignoring Accents](#ignoring-accents).

## Suggestions as the User Types

A type-ahead box queries as the user types and shows a handful of matches under the input: the user types `de n` on the way to `de niro`, and the box queries along the way. A debounce, which waits for a short pause in typing before it queries, keeps that to a few queries rather than one per keystroke, but the pauses still fall on unfinished words. Those queries run far more often than a search page's, on inputs such as `de` or `de n`, so each one must stay cheap however little has been typed. Such a box uses `suggest` instead of `search`. It takes the same input and paths and matches the same way, on the words the database's substring index serves alone: a word shorter than the index serves is ignored, and an input without such a word, an empty one included, matches no rows.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val suggestions = orm.entity(Movie::class)
    .select()
    .where(suggest(input, Movie_.title, Movie_.director.name))
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Movie> suggestions = orm.entity(Movie.class)
    .select()
    .where(it -> it.suggest(input, Movie_.title, Movie_.director.name))
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

| Input, on PostgreSQL | `search` | `suggest` |
|-------|----------|-----------|
| `de niro` | every word: `de` and `niro` | `niro` |
| `de` | `de`, reading every row | no rows |
| empty or blank | every row | no rows |

Every statement `suggest` renders is therefore served by the substring index, however the user types; the trade-off is that a short word does not narrow the suggestions. The search page the box leads to keeps `search`, which matches every word. Which words are short is the dialect's to say: three characters on PostgreSQL, whose trigram index needs three, and none on the other databases, where `suggest` matches as `search` does, except that an empty input matches no rows. The `storm.search.min_term_length` property sets another length, for an index of a different kind.

### Ignoring Words

A word the index serves can be as useless as a short one when it occurs in a large share of the rows: `the` is three characters, so the trigram index serves it, but it matches a third of the titles in a movie catalog. Typed alone it returns a page of noise, and next to another word it narrows nothing. Which words those are depends on the column's content, `the` and `of` for titles, `de` and `van` for names, so the search box names them, as it names the columns. `suggest` takes the words to ignore after the input:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val titleNoise = setOf("the", "a", "of")

val suggestions = orm.entity(Movie::class)
    .select()
    .where(suggest(input, titleNoise, Movie_.title, Movie_.director.name))
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
private static final Set<String> TITLE_NOISE = Set.of("the", "a", "of");

List<Movie> suggestions = orm.entity(Movie.class)
    .select()
    .where(it -> it.suggest(input, TITLE_NOISE, Movie_.title, Movie_.director.name))
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

An ignored word is treated as a short word is: it takes no part in the predicate, compared ignoring case as the words themselves match, and an input without a remaining word matches no rows. A quoted phrase is one word, ignored when it equals an ignored word, not when it contains one.

| Input | Words `suggest` matches |
|-------|----------|
| `the god` | `god`: finds "The Godfather" and "City of God" |
| `The` | none: no rows |
| `"the good"` | `the good`: the phrase is one word |

The set is a constant next to the box that uses it, typed by hand for most boxes. Where the catalog is large enough to measure, a query over the column counts the words and the set holds those that occur in more rows than the box shows. `search` takes no such set: on a results page a frequent word next to a longer one costs nothing and still narrows correctly, and a page of every title with `the` in it is a broad but legitimate query.

## Searching Related Rows

Sometimes the user remembers something about a related row rather than the row itself: the front desk knows the dog is called Leo, not the owner's last name. To search the rows related to each row, semi-join the related table and name its column next to the row's own. Every owner comes back once, however many pets match:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .semiJoin<Pet>().on<Owner>()
    .where(search(input, Owner_.lastName, Pet_.name))
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> owners = orm.entity(Owner.class)
    .select()
    .semiJoin(Pet.class).on(Owner.class)
    .where(it -> it.search(input, Owner_.lastName, Pet_.name))
    .getResultList();
```

</TabItem>
</Tabs>

- Each word is matched on its own, so the words may match different related rows: `leo max` finds the owner of both a Leo and a Max.
- Each word renders as an `IN` over a `UNION` of a branch per table, so each table is read through its own indexes rather than through a join of both.
- A condition on the join limits which related rows a word may match. `semiJoin<Principal>().on<Movie> { Principal_.ordering lessEq 3 }` with `search(input, Movie_.title, Principal_.person.name)` matches a word against the titles and the three leads of each movie, and an index limited to those rows serves it.

See [Filtering by Related Rows](queries.md#filtering-by-related-rows) for how semi-joins answer conditions in general.

## Ranking the Matches

A user who types `alien` into a movie search expects "Alien" at the top, then "Aliens", then the rest, not whatever order the database happens to return. `search` and `suggest` decide which rows match, not which come first. What counts as the best match is the application's to say, so the ordering is stated with [`orderFirst`](queries.md#ordering) and the same predicates the WHERE clause takes. A title equal to the input first, a title starting with it next, then the most popular:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val suggestions = orm.entity(Movie::class)
    .select()
    .where(suggest(input, Movie_.title, Movie_.director.name))
    .orderFirst(Movie_.title eqIgnoreCase input)
    .orderFirst(Movie_.title startsWithIgnoreCase input)
    .orderByDescending(Movie_.votes)
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Movie> suggestions = orm.entity(Movie.class)
    .select()
    .where(it -> it.suggest(input, Movie_.title, Movie_.director.name))
    .orderFirst(it -> it.where(Movie_.title, EQUALS_IGNORE_CASE, input))
    .orderFirst(it -> it.where(Movie_.title, STARTS_WITH_IGNORE_CASE, input))
    .orderByDescending(Movie_.votes)
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

```sql
ORDER BY CASE WHEN LOWER(m.title) = LOWER(?) THEN 0 ELSE 1 END,
         CASE WHEN LOWER(m.title) LIKE LOWER(?) ESCAPE '!' THEN 0 ELSE 1 END,
         m.votes DESC
```

The operators match the input as they do in a WHERE clause: ignoring case, and literally, so `%` and `_` in the input are not wildcards. Combine predicates with `or` to rank by several columns, such as a title and an original title. The input is compared whole, as typed; trim it, or collapse its spaces, where the box may hold more than one between words.

- No index serves the sort: the database evaluates each key for every row the query finds and sorts them all, even with a limit. After a search, which finds few rows, that costs what any ordering of the matches costs.
- Rank by the columns of the queried table and of joined tables. A semi-joined table adds no columns to the query, so its columns, such as the name of a credited person a movie was found by, cannot rank it; Storm refuses them.
- A keyset `scroll` follows `orderFirst`, so search results can be read a window at a time; see [Ranking Before the Sort Fields](pagination-and-scrolling.md#ranking-before-the-sort-fields).
- A `distinct` query cannot be ranked, since the database orders a distinct result only by the columns it selects. A search over related rows uses a semi-join, which needs no `distinct`.

## Indexing a Search

`search` and the `IGNORE_CASE` text operators compare a lowered expression against a pattern that may start anywhere: `LOWER(column) LIKE LOWER('%word%')`. A B-tree index orders values from their first character, so it serves neither the lowered expression nor a match inside a word. An index already on the column goes unused, and every search reads every row of each table it names, lowering each value as it goes. Lowering text under a linguistic collation is often the larger part of that cost, so a search that answers at once on test data can take seconds on a production-sized table.

What serves the statement is the database's index for substring matching, a trigram or n-gram index, created over the same expression the statement compares: `LOWER(column)`, not the column itself. Storm renders the predicate the same way on every database, so the index matches it without a change to the query.

- Index every column the search names, including the columns of a semi-joined table and of a table reached through a path. The statement matches each word against each column, and a column without the index keeps its table's scan.
- Index the expression exactly as the statement renders it. An index on the plain column, or on another case function, does not match `LOWER(column)`, and the plan falls back to reading every row.
- A word shorter than the index's gram, usually three characters, cannot use it. `search` tests such a word only on the rows its longer words found, as an `EXISTS` over the related rows of each, so it reads no table in full and the rows found are the same; a search of short words alone still reads every row. `suggest` ignores them. The dialect states the length, three on PostgreSQL, and `storm.search.min_term_length` overrides it.
- Read the query plan on production-sized data to confirm that each table is read through its index. On a small table the planner may read every row regardless, because that is cheaper there.
- Read the plan of the statement as the application sends it, with the word as a bind parameter, not with the word written into the SQL. A driver that prepares a statement once for many executions lets the database plan it once for every value, and that plan cannot see the word, so it cannot tell a rare word from a common one. A search that also orders by an indexed column and takes the first few rows, as a list of suggestions does, is then read in that column's order, testing every row until enough match, while the same statement planned for its word takes a few rows from the substring index. Where the plans differ, have the search planned for its values: most drivers and databases have a setting that stops a statement from being prepared, or that plans each execution for its parameters.
- Refresh the table's statistics after creating the index on a table that already holds data; a planner without statistics for the indexed expression may keep reading every row. Where a rare word is still estimated to match many rows, a larger statistics sample for the indexed expression brings the estimate down.
- Keep the predicate. A case-sensitive operator reads every row as well and changes what matches; an `ILIKE` or a case function in a template reads every row just the same.
- Where the database has no substring index, reading every row is what a substring search costs. Narrow the rows with a selective predicate first, keep the searched table small, or match whole words through the database's full-text search in a [SQL template](sql-templates.md).

A search that reads every row shows up under `st.orm.sql.slow` once its database time passes the threshold; see [The Slow Statement Log](sql-logging.md#the-slow-statement-log). When every word of the search was too short for the index, the line carries advice saying so, and pointing a search box that queries as the user types to `suggest`.

## Ignoring Accents

Users type without accents: a search for "leon" should find "Léon", and "muller" should find "Müller". A search follows the column's collation for accents, as the `IGNORE_CASE` operators do. MySQL and MariaDB ignore them under their default collations, so a search there ignores them with nothing more to do. The other databases compare them as they are, so "leon" does not find "Léon". Whether a search ignores accents is therefore decided by the schema and its index, not by the application.

On those databases the database removes the accents, through a function an index is built on, applied to each searched column as a [`SqlFunction`](queries.md#sql-functions). A comparison applies the function to both sides, the column and the input, so the database folds both the same way and the application folds neither.

On PostgreSQL, `unaccent` removes the accents. It is not marked immutable, which an index requires, so the migration wraps it in a function that is, and indexes that function's result as the search compares it:

```sql
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION fold_accents(text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

CREATE INDEX idx_movie_title_folded ON movie USING gin (LOWER(fold_accents(title)) gin_trgm_ops);
```

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val foldAccents = SqlFunction.of<String>("fold_accents")

val movies = orm.entity(Movie::class)
    .select()
    .where(search(input, foldAccents(Movie_.title)))
    .orderFirst(foldAccents(Movie_.title) eqIgnoreCase input)
    .resultList
```

An extension property names the call after what it computes, so a query reads it as a field of the column. Declare it once, next to the function, and it applies to every text column of every entity:

```kotlin
val <T : Data> Navigable<T, String>.folded: Metamodel<T, String>
    get() = foldAccents(this)

orm.entity(Movie::class)
    .select()
    .where(search(input, Movie_.title.folded, Movie_.director.name.folded))
    .orderFirst(Movie_.title.folded eqIgnoreCase input)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
SqlFunction<String> foldAccents = SqlFunction.of("fold_accents");

List<Movie> movies = orm.entity(Movie.class)
    .select()
    .where(it -> it.search(input, foldAccents.apply(Movie_.title)))
    .orderFirst(it -> it.where(foldAccents.apply(Movie_.title), EQUALS_IGNORE_CASE, input))
    .getResultList();
```

</TabItem>
</Tabs>

The search compares `LOWER(fold_accents(m.title)) LIKE LOWER(fold_accents(?))`, the expression the index holds, so "leon", "Léon" and "LÉON" all find "Léon" through the index. The entity keeps one field for the title, which the query selects with its accents for display.

- Name the function exactly as the index does. A different function, or the same one applied in another order, is another expression, and the plan falls back to reading every row.
- The database folds the input too, with its own rules. PostgreSQL's `unaccent` maps `ø` to `o` and `æ` to `ae`, which removing combining marks in the application would leave alone; folding both sides in the database keeps the column and the input in step.
- The function receives the search pattern, wildcards included, and an accent function leaves them alone, as a function compared through a text operator must.

### Storing the Folded Text

The expression index is enough for most catalogs. The index finds the candidate rows, and the database then rechecks each candidate by computing `fold_accents` from the title; on a column where a common word matches many thousands of rows per keystroke, that recheck is where the time goes. Where a search stays slow for that reason, and only then, store the folded title instead of computing it per search: a generated column holds `fold_accents(title)`, the index is built on that column, and the entity declares it as a [query column](entities.md#query-columns), a column the search names and no row carries.

```sql
ALTER TABLE movie ADD COLUMN title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(title)) STORED NOT NULL;
CREATE INDEX idx_movie_title_folded ON movie USING gin (LOWER(title_folded) gin_trgm_ops);
```

The search then compares `LOWER(m.title_folded) LIKE LOWER(?)`, the expression the index holds, and the recheck reads the stored column. The input must be folded by the same function, in the database, before the search; applying `foldAccents` to the query column instead would fold both sides again and bring the recheck back.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("movie")
@QueryColumn(field = "titleFolded", type = String::class)
data class Movie(...) : Entity<Int>

val folded = orm.query { "SELECT fold_accents($input)" }.singleResult<String>()

orm.entity(Movie::class)
    .select()
    .where(search(folded, Movie_.titleFolded))
    .orderFirst(Movie_.titleFolded eqIgnoreCase folded)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("movie")
@QueryColumn(field = "titleFolded", type = String.class)
record Movie(...) implements Entity<Integer> {}

String folded = orm.query(RAW."SELECT fold_accents(\{input})").getSingleResult(String.class);

orm.entity(Movie.class)
    .select()
    .where(it -> it.search(folded, Movie_.titleFolded))
    .orderFirst(it -> it.where(Movie_.titleFolded, EQUALS_IGNORE_CASE, folded))
    .getResultList();
```

</TabItem>
</Tabs>

The entity keeps one field for the title, which the query selects with its accents for display; the folded column stays out of every SELECT and INSERT, and the writes pay for the folding once per row.
