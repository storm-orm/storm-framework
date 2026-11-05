import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Search

Most applications have a search box somewhere: a customer list searched by name and city, a vet's front desk looking up an owner by the name of their pet. The box hands the application whatever the user typed: several words, in any case, perhaps a quoted phrase or a stray `%`. Storm turns that input into a predicate the query builder takes like any other, matched the same way on every database, so the application passes the input through as it arrives, with no splitting, escaping or lowering of its own.

| You are building | Use |
|------------------|-----|
| A search results page: the user submits a few words, and every word narrows the results | [`search`](#searching-text) |
| A type-ahead or auto-complete box that queries as the user types | [`suggest`](#suggestions-as-the-user-types), with a limit |
| A column that should match a word as a prefix or as the whole value, or a column where a word should match inside a compound | [a match form per column](#match-forms) |
| A search that also matches related rows, such as an owner found by a pet's name | [`search` with a semi-join](#searching-related-rows) |
| Results with the best matches on top, such as an exact name before a partial one | [`orderFirst`](#ranking-the-matches) |
| A search where "muller" finds "Müller" | [`SqlFunction`](#ignoring-accents) over an accent-removing function |

A search that is fast on test data can read every row of a production-sized table; [Indexing a Search](#indexing-a-search) covers the index that keeps it fast, and [Large Tables Without a Substring Index](#large-tables-without-a-substring-index) the table that stands in for one.

## Searching Text

A search results page takes the words the user submitted and shows the rows that match all of them. Every word should match somewhere: `davis madison` finds Harold Davis of Madison through the last name and the city, so the user narrows the results by typing more of what they remember, in any order. `search` takes the input as the box receives it and one or more text columns:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .where(search(input, Owner_.lastName, Owner_.address.city.name))
    .resultList
```

`search` returns a predicate like the infix functions do, so it combines with them: `search(input, Owner_.lastName) and (Owner_.address.postalCode startsWith "53")`.

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> owners = orm.entity(Owner.class)
    .select()
    .where(it -> it.search(input, Owner_.lastName, Owner_.address.city.name))
    .getResultList();
```

`search` returns a predicate like `where` does, so it combines with `.and()` and `.or()`: `it.search(input, Owner_.lastName).and(it.where(Owner_.address.postalCode, STARTS_WITH, "53"))`.

</TabItem>
</Tabs>

Every word must occur in at least one of the columns, ignoring case and matched as literal text, as `CONTAINS_IGNORE_CASE` matches it. The predicate is an AND over the words of an OR over the columns, the same statement on every database:

```sql
WHERE (LOWER(o.last_name) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.name) LIKE LOWER(?) ESCAPE '!')
  AND (LOWER(o.last_name) LIKE LOWER(?) ESCAPE '!' OR LOWER(c.name) LIKE LOWER(?) ESCAPE '!')
```

| Input | Words |
|-------|-------|
| `davis madison` | `davis`, `madison` |
| `"van der" berg` | `van der`, `berg`: a quoted phrase is one word |
| `"van der` | `van der`: a quote at the start of a word opens a phrase that runs to the next quote, or to the end of the input while the user is still typing it |
| `50% a_b 12"` | `50%`, `a_b`, `12"`, matched literally: a quote inside a word is an ordinary character |
| empty or blank | none: the predicate matches every row |

`search` and `suggest` read the input the same way, so a box that suggests while typing and searches on Enter sees the same words in both. `search` matches words; it does not rank the rows it finds, and it does not stem words the way a full-text index does. To put the closest matches first, see [Ranking the Matches](#ranking-the-matches). Accents follow the column's collation; see [Ignoring Accents](#ignoring-accents).

## Suggestions as the User Types

A type-ahead box queries as the user types and shows a handful of matches under the input: the user types `van b` on the way to `van den berg`, and the box queries along the way. A debounce, which waits for a short pause in typing before it queries, keeps that to a few queries rather than one per keystroke, but the pauses still fall on unfinished words. Those queries run far more often than a search page's, on inputs such as `va` or `van b`, so each one must stay cheap however little has been typed. Such a box uses `suggest` instead of `search`. It takes the same input and columns, and matches every word of two characters or more at the start of a word: the start of the value, or the text after a space or a punctuation character that joins or separates words. A one-character word takes no part, and an input without a word that takes part, an empty one included, matches no rows, so the box never fetches and sorts a whole table on the first keystroke.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val suggestions = orm.entity(Owner::class)
    .select()
    .where(suggest(input, Owner_.lastName, Owner_.firstName))
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> suggestions = orm.entity(Owner.class)
    .select()
    .where(it -> it.suggest(input, Owner_.lastName, Owner_.firstName))
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

The two functions read the input alike and differ in what they do with it, the same on every database:

| Input | `search` | `suggest` |
|-------|----------|-----------|
| `van b` | both words, anywhere | both words, at the start of a word: "Van den Berg", not "Sullivan" |
| `van` | `van` anywhere: "Van den Berg" and "Sullivan" | `van` at the start of a word: "Van den Berg" |
| `va` | `va` anywhere | the start of the value: "Van den Berg", not "Sullivan" nor "De Vaal" |
| `van d` | both words, anywhere | `van` at a word start; `d` at a word start on the rows `van` found |
| `v` | `v` anywhere | no rows: one character names too many |
| `van de` | both words, anywhere | `van` at a word start; `de` at a word start on the rows `van` found |
| empty or blank | every row | no rows |

A word shorter than three characters matches too large a share of a table, whatever index serves it: `va` starts a word in a sizeable part of the names in a directory, and the box would fetch and rank them all. So until one of its words reaches three characters, a suggestion matches the whole input as the start of the value, ignoring case: `de va` suggests "De Vaal", `up` suggests "Upton" but not "Van Upton". Its words are joined by one space, and a `prefix` or `exact` column compares the whole input in its own form. Once a word reaches the length, a shorter word next to it narrows only the rows the longer word found. An input of one character matches no rows. The length is `storm.suggest.min_word_length`, three by default and the same on every database, since it changes which rows come back.

The empty-input contract is why both exist. A results page with an empty box lists everything; a suggestion box with an empty box lists nothing. The default match form follows the situation: a person typing into a box types the start of a word, in any order, so `suggest` matches word starts, and a submitted search should find every row the words occur in, so `search` matches anywhere. Either function takes another form per column; see [Match Forms](#match-forms).

A word starts the value or follows a space, a hyphen, an apostrophe, an opening parenthesis, a slash, a colon, a period or a comma, so `marie` suggests "Anne-Marie", `brien` "O'Brien" and `john` "St. John". The same set applies on every database. A word does not match inside a compound: `son` does not suggest "Davidson", and `berg` not "Lindberg", although the search page the box leads to finds both through `search`. Where matches inside a word matter for a column, state `anywhere` for that column and keep the word start for the others; see [Match Forms](#match-forms).

Each database renders the word start in its own way, and finds the same rows: one regular expression where it has the operator, which PostgreSQL's trigram index serves as it serves a `LIKE`, and `LIKE` patterns elsewhere.

### Ignoring Words

A word the index serves can be as useless as a short one when it occurs in a large share of the rows: `van` is three characters, so a trigram index serves it, but it starts a large share of the last names in some countries. Typed alone it returns a page of noise, and next to another word it narrows nothing. Which words those are depends on the column's content, `van`, `de` and `der` for Dutch last names, `the` and `of` for titles, so the search box names them, as it names the columns. `suggest` takes the words to ignore after the input:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val nameNoise = setOf("van", "de", "der", "den")

val suggestions = orm.entity(Owner::class)
    .select()
    .where(suggest(input, nameNoise, Owner_.lastName, Owner_.firstName))
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
private static final Set<String> NAME_NOISE = Set.of("van", "de", "der", "den");

List<Owner> suggestions = orm.entity(Owner.class)
    .select()
    .where(it -> it.suggest(input, NAME_NOISE, Owner_.lastName, Owner_.firstName))
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

An ignored word takes no part in the predicate, compared ignoring case as the words themselves match, and an input without a remaining word matches no rows. Where the input matches as the start of the value, an ignored word keeps its place in it, since it stands at the start of the value as typed: with `van` ignored, `van de` still suggests "Van den Berg". The ignored words apply to bare words only: a quoted word is an explicit request, and is never ignored.

| Input | Words `suggest` matches |
|-------|----------|
| `van berg` | `berg`: finds "Van den Berg" and "Berger" |
| `Van` | none: no rows |
| `"van"` | `van`: quoted, so it counts |
| `"van der"` | `van der`: the phrase is one word |

The set is a constant next to the box that uses it, typed by hand for most boxes. Where the table is large enough to measure, a query over the column counts the words and the set holds those that occur in more rows than the box shows. `search` takes no such set: on a results page a frequent word next to a longer one costs nothing and still narrows correctly, and a page of every owner named "van" something is a broad but legitimate query.

## Match Forms

Each column states how a word matches it. The plain overloads use one form for every column, the default of the function; the overloads that take a match per column state the form for each, through the `anywhere`, `wordStart`, `prefix` and `exact` factories. A call is either all defaults or all stated.

| Match | A typed word matches | Renders | Default for |
|-------|----------------------|---------|-------------|
| `anywhere` | anywhere in the value | `LOWER(col) LIKE '%berg%'` | `search` |
| `wordStart` | the start of any word in the value | one regular expression, or `LOWER(col) LIKE 'berg%'` and a `LIKE` per separator, as the database has it | `suggest` |
| `prefix` | the start of the value | `col LIKE 'berg%'`, the column bare and the word lowered | |
| `exact` | the whole value | `col = 'berg'`, the column bare and the word lowered | |

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val suggestions = orm.entity(Owner::class)
    .select()
    .where(suggest(input, wordStart(Owner_.lastName), wordStart(Owner_.firstName), exact(Owner_.telephone)))
    .orderFirst(Owner_.lastName startsWithIgnoreCase input)
    .orderBy(Owner_.lastName)
    .limit(8)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
import static st.orm.Match.*;

List<Owner> suggestions = orm.entity(Owner.class)
    .select()
    .where(it -> it.suggest(input, wordStart(Owner_.lastName), wordStart(Owner_.firstName), exact(Owner_.telephone)))
    .orderFirst(it -> it.where(Owner_.lastName, STARTS_WITH_IGNORE_CASE, input))
    .orderBy(Owner_.lastName)
    .limit(8)
    .getResultList();
```

</TabItem>
</Tabs>

A word typed into that box matches the start of a word of a name, or a whole telephone number, which an ordinary index over the number serves. Every form compares ignoring case and matches the word as literal text, and the rows found are the same on every database. A phrase is one word in every form: `wordStart` with `"van der"` matches a value with a word starting `van der`, and `exact` with `"van der berg"` the value `van der berg`.

- **`anywhere` and `wordStart`** lower the column, so the index that serves them is one built for substring matching over `LOWER(col)`; see [Indexing a Search](#indexing-a-search). A word at a word start is a word anywhere with a separator in front of it, so the same index serves both.
- **`prefix` and `exact`** leave the column bare and lower the word, so an ordinary index over the column serves them: `last_name LIKE ?`, since `LOWER(last_name)` would lose the index. The column must then compare case-insensitively on its own: a case-insensitive collation, the default on MySQL, MariaDB and SQL Server and `COLLATE NOCASE` on the column on SQLite, or a stored lowered column declared as a [query column](entities.md#query-columns) on PostgreSQL, H2 and Oracle, which the lowered word matches as typed. A `prefix` or `exact` match on a column that compares case-sensitively never matches a value with an upper-case letter.
- **A match composes with a [`SqlFunction`](#ignoring-accents)**: `wordStart(foldAccents(Owner_.lastName))` in Kotlin, `wordStart(foldAccents.apply(Owner_.lastName))` in Java, matches the folded name, and the index is built over the same expression.
- **A match is not a path**, so only `search` and `suggest` take it; `orderFirst` and the operators take the column.

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
- Each word renders as an `IN` over a `UNION` of a branch per table, so each table is read through its own indexes rather than through a join of both. Where the database runs such a union once per outer row (`SqlDialect.flattensInOverUnion()`), each word renders as an `EXISTS` per table instead.
- The columns may come from several semi-joined tables. With `semiJoin<Pet>().on<Owner>()` and `semiJoin<Visit>().on<Pet>()`, `search(input, Owner_.lastName, Pet_.name, Visit_.description)` finds an owner by their name, by a pet's name or by what a visit was about, so `vaccination` finds the owners whose pets came in for one although no name says so. Each word matches a row of any of the tables, and renders as one `IN` over a `UNION` with a branch per table, so each table is read through its own indexes.
- A condition on the join limits which related rows a word may match. `semiJoin<Visit>().on(Pet::class, Visit_.visitDate greaterEq lastYear)` with `search(input, Owner_.lastName, Visit_.description)` matches a word against the names and the visits of the past year, and an index limited to those rows serves it.

See [Filtering by Related Rows](queries.md#filtering-by-related-rows) for how semi-joins answer conditions in general.

## Ranking the Matches

A user who types `davis` into an owner search expects "Davis" at the top, then "Davison", then the rest, not whatever order the database happens to return. `search` and `suggest` decide which rows match, not which come first. What counts as the best match is the application's to say, so the ordering is stated with [`orderFirst`](queries.md#ordering) and the same predicates the WHERE clause takes. A name equal to the input first, a name starting with it next, then alphabetically:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val suggestions = orm.entity(Owner::class)
    .select()
    .where(suggest(input, Owner_.lastName, Owner_.firstName))
    .orderFirst(Owner_.lastName eqIgnoreCase input)
    .orderFirst(Owner_.lastName startsWithIgnoreCase input)
    .orderBy(Owner_.lastName)
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> suggestions = orm.entity(Owner.class)
    .select()
    .where(it -> it.suggest(input, Owner_.lastName, Owner_.firstName))
    .orderFirst(it -> it.where(Owner_.lastName, EQUALS_IGNORE_CASE, input))
    .orderFirst(it -> it.where(Owner_.lastName, STARTS_WITH_IGNORE_CASE, input))
    .orderBy(Owner_.lastName)
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

```sql
ORDER BY CASE WHEN LOWER(o.last_name) = LOWER(?) THEN 0 ELSE 1 END,
         CASE WHEN LOWER(o.last_name) LIKE LOWER(?) ESCAPE '!' THEN 0 ELSE 1 END,
         o.last_name
```

The operators match the input as they do in a WHERE clause: ignoring case, and literally, so `%` and `_` in the input are not wildcards. Combine predicates with `or` to rank by several columns, such as a last name and a first name. The input is compared whole, as typed; trim it, or collapse its spaces, where the box may hold more than one between words.

- No index serves the sort: the database evaluates each key for every row the query finds and sorts them all, even with a limit. After a search, which finds few rows, that costs what any ordering of the matches costs.
- Rank by the columns of the queried table and of joined tables. A semi-joined table adds no columns to the query, so its columns, such as the name of a pet an owner was found by, cannot rank it; Storm refuses them.
- A keyset `scroll` follows `orderFirst`, so search results can be read a window at a time; see [Ranking Before the Sort Fields](pagination-and-scrolling.md#ranking-before-the-sort-fields).
- A `distinct` query cannot be ranked, since the database orders a distinct result only by the columns it selects. A search over related rows uses a semi-join, which needs no `distinct`.

## Indexing a Search

`search`, `suggest` and the `IGNORE_CASE` text operators compare a lowered expression against a pattern that may start anywhere: `LOWER(column) LIKE LOWER('%word%')`, or the word-start form with a separator in front of the word. A B-tree index orders values from their first character, so it serves neither the lowered expression nor a match inside a value. An index already on the column goes unused, and every search reads every row of each table it names, lowering each value as it goes. Lowering text under a linguistic collation is often the larger part of that cost, so a search that answers at once on test data can take seconds on a production-sized table.

What serves the statement is the database's index for substring matching, a trigram or n-gram index, created over the same expression the statement compares: `LOWER(column)`, not the column itself. Storm renders the predicate the same way on every database, so the index matches it without a change to the query. A `prefix` or `exact` match needs no such index: an ordinary index over the bare column serves it, on every database.

- Index every column the search names, including the columns of a semi-joined table and of a table reached through a path. The statement matches each word against each column, and a column without the index keeps its table's scan.
- Index the expression exactly as the statement renders it. An index on the plain column, or on another case function, does not match `LOWER(column)`, and the plan falls back to reading every row.
- A word shorter than the index's gram, usually three characters, cannot use it. `search` tests such a word only on the rows its longer words found, as an `EXISTS` over the related rows of each, so it reads no table in full and the rows found are the same; a search of short words alone still reads every row. The dialect states the length, three on PostgreSQL, and `storm.search.min_term_length` overrides it. `suggest` plans its short words by its own length, `storm.suggest.min_word_length`: a short word next to a longer one is tested the same way, and a suggestion of short words alone matches the start of the value, which the same index serves.
- Read the query plan on production-sized data to confirm that each table is read through its index. On a small table the planner may read every row regardless, because that is cheaper there.
- Read the plan of the statement as the application sends it, with the word as a bind parameter, not with the word written into the SQL. A driver that prepares a statement once for many executions lets the database plan it once for every value, and that plan cannot see the word, so it cannot tell a rare word from a common one. A search that also orders by an indexed column and takes the first few rows, as a list of suggestions does, is then read in that column's order, testing every row until enough match, while the same statement planned for its word takes a few rows from the substring index. Where the plans differ, have the search planned for its values: most drivers and databases have a setting that stops a statement from being prepared, or that plans each execution for its parameters.
- Refresh the table's statistics after creating the index on a table that already holds data; a planner without statistics for the indexed expression may keep reading every row. Where a rare word is still estimated to match many rows, a larger statistics sample for the indexed expression brings the estimate down.
- Keep the predicate. A case-sensitive operator reads every row as well and changes what matches; an `ILIKE` or a case function in a template reads every row just the same.
- Where the database has no substring index, reading every row is what a match anywhere or at a word start costs. Narrow the rows with a selective predicate first, keep the searched table small, match whole words through the database's full-text search in a [SQL template](sql-templates.md), or stand a word-suffix table in for the index; see [Large Tables Without a Substring Index](#large-tables-without-a-substring-index).

A search that reads every row shows up under `st.orm.sql.slow` once its database time passes the threshold; see [The Slow Statement Log](sql-logging.md#the-slow-statement-log). With the [schema loaded](advice.md#loading-the-schema), the line carries [`unindexed-search` advice](advice.md#unindexed-search) for each searched column no index serves in its match form: no index leads with a column a prefix or exact match compares, or none starts with an expression where the match compares the lowered column. It names what serves the form.

## Large Tables Without a Substring Index

On a database without a substring index, a match anywhere or at a word start reads the table in full, which a small table affords and a large one does not. A word-suffix table stands in for the index: one row per word start of the value, holding the text from that word to the end, which a `prefix` match finds through an ordinary index. "Van der Berg" yields three rows, `van der berg`, `der berg` and `berg`, so `berg` is then a prefix of one of them. The rows split on the same separators `wordStart` recognizes, so `prefix` over the suffix table finds the rows `wordStart` finds on a database that has the index.

The table holds the suffixes of the owners' last names, with an ordinary index over the suffix and a case-insensitive collation on it, here MySQL's, so the lowered word matches the suffix as stored. On a database whose collations compare case-sensitively, the procedure stores the suffix lowered and the index serves it the same way. A procedure writes the suffixes of one owner, and a trigger per write keeps the table filled:

```sql
CREATE TABLE owner_name_suffix (
    owner_id INT NOT NULL,
    suffix VARCHAR(255) NOT NULL COLLATE utf8mb4_0900_ai_ci,
    INDEX owner_name_suffix_idx (suffix),
    FOREIGN KEY (owner_id) REFERENCES owner (id) ON DELETE CASCADE
);

CREATE PROCEDURE owner_name_suffixes(IN p_owner_id INT, IN p_name VARCHAR(255))
BEGIN
    DECLARE p INT DEFAULT 1;
    DELETE FROM owner_name_suffix WHERE owner_id = p_owner_id;
    WHILE p <= CHAR_LENGTH(p_name) DO
        IF LOCATE(SUBSTRING(p_name, p, 1), ' -''(/:.,') = 0
                AND (p = 1 OR LOCATE(SUBSTRING(p_name, p - 1, 1), ' -''(/:.,') > 0) THEN
            INSERT INTO owner_name_suffix (owner_id, suffix) VALUES (p_owner_id, SUBSTRING(p_name, p));
        END IF;
        SET p = p + 1;
    END WHILE;
END;

CREATE TRIGGER owner_name_suffix_insert AFTER INSERT ON owner FOR EACH ROW CALL owner_name_suffixes(NEW.id, NEW.last_name);
CREATE TRIGGER owner_name_suffix_update AFTER UPDATE ON owner FOR EACH ROW CALL owner_name_suffixes(NEW.id, NEW.last_name);
```

A backfill runs the same split once over the rows that exist, before the triggers are created:

```sql
INSERT INTO owner_name_suffix (owner_id, suffix)
WITH RECURSIVE position AS (SELECT 1 AS p UNION ALL SELECT p + 1 FROM position WHERE p < 255)
SELECT o.id, SUBSTRING(o.last_name, p)
FROM owner o
JOIN position ON p <= CHAR_LENGTH(o.last_name)
WHERE LOCATE(SUBSTRING(o.last_name, p, 1), ' -''(/:.,') = 0
  AND (p = 1 OR LOCATE(SUBSTRING(o.last_name, p - 1, 1), ' -''(/:.,') > 0);
```

The suffix table is a related table of the owner, so the search semi-joins it and matches the word as a prefix of a suffix row; each owner comes back once, however many of their suffixes match:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val suggestions = orm.entity(Owner::class)
    .select()
    .semiJoin<OwnerNameSuffix>().on<Owner>()
    .where(suggest(input, prefix(OwnerNameSuffix_.suffix)))
    .limit(6)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> suggestions = orm.entity(Owner.class)
    .select()
    .semiJoin(OwnerNameSuffix.class).on(Owner.class)
    .where(it -> it.suggest(input, prefix(OwnerNameSuffix_.suffix)))
    .limit(6)
    .getResultList();
```

</TabItem>
</Tabs>

The suffix table serves `wordStart`; a match `anywhere` has no such table, since a value has as many substrings as characters. Where a search must match inside a word on such a database, the database's full-text search in a [SQL template](sql-templates.md) is what there is.

## Ignoring Accents

Users type without accents: a search for "muller" should find "Müller", and "jose" should find "José". A search follows the column's collation for accents, as the `IGNORE_CASE` operators do. MySQL and MariaDB ignore them under their default collations, so a search there ignores them with nothing more to do. The other databases compare them as they are, so "muller" does not find "Müller". Whether a search ignores accents is therefore decided by the schema and its index, not by the application.

On those databases the database removes the accents, through a function an index is built on, applied to each searched column as a [`SqlFunction`](queries.md#sql-functions). A comparison applies the function to both sides, the column and the input, so the database folds both the same way and the application folds neither.

On PostgreSQL, `unaccent` removes the accents. It is not marked immutable, which an index requires, so the migration wraps it in a function that is, and indexes that function's result as the search compares it:

```sql
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION fold_accents(text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

CREATE INDEX idx_owner_last_name_folded ON owner USING gin (LOWER(fold_accents(last_name)) gin_trgm_ops);
```

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val foldAccents = SqlFunction.of<String>("fold_accents")

val owners = orm.entity(Owner::class)
    .select()
    .where(search(input, foldAccents(Owner_.lastName)))
    .orderFirst(foldAccents(Owner_.lastName) eqIgnoreCase input)
    .resultList
```

An extension property names the call after what it computes, so a query reads it as a field of the column. Declare it once, next to the function, and it applies to every text column of every entity:

```kotlin
val <T : Data> Navigable<T, String>.folded: Metamodel<T, String>
    get() = foldAccents(this)

orm.entity(Owner::class)
    .select()
    .where(search(input, Owner_.lastName.folded, Owner_.firstName.folded))
    .orderFirst(Owner_.lastName.folded eqIgnoreCase input)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
SqlFunction<String> foldAccents = SqlFunction.of("fold_accents");

List<Owner> owners = orm.entity(Owner.class)
    .select()
    .where(it -> it.search(input, foldAccents.apply(Owner_.lastName)))
    .orderFirst(it -> it.where(foldAccents.apply(Owner_.lastName), EQUALS_IGNORE_CASE, input))
    .getResultList();
```

</TabItem>
</Tabs>

The search compares `LOWER(fold_accents(o.last_name)) LIKE LOWER(fold_accents(?))`, the expression the index holds, so "muller", "Müller" and "MÜLLER" all find "Müller" through the index. The entity keeps one field for the name, which the query selects with its accents for display.

- Name the function exactly as the index does. A different function, or the same one applied in another order, is another expression, and the plan falls back to reading every row.
- The database folds the input too, with its own rules. PostgreSQL's `unaccent` maps `ø` to `o` and `æ` to `ae`, which removing combining marks in the application would leave alone; folding both sides in the database keeps the column and the input in step.
- On PostgreSQL the input side renders as a scalar subquery, `LOWER((SELECT fold_accents(?)))`, which the database folds once per execution. A prepared statement that PostgreSQL plans for any value would otherwise fold the input again for every row the index returns. A case-sensitive `prefix` keeps `fold_accents(?)`, which the planner folds into the constant an ordinary index needs for its range.
- The function receives the search pattern, wildcards included, and an accent function leaves them alone, as a function compared through a text operator must.

### Storing the Folded Text

The expression index is enough for most tables. The index finds the candidate rows, and the database then rechecks each candidate by computing `fold_accents` from the name; on a column where a common word matches many thousands of rows per keystroke, that recheck is where the time goes. Where a search stays slow for that reason, and only then, store the folded name instead of computing it per search: a generated column holds `fold_accents(last_name)`, the index is built on that column, and the entity declares it as a [query column](entities.md#query-columns), a column the search names and no row carries.

```sql
ALTER TABLE owner ADD COLUMN last_name_folded VARCHAR GENERATED ALWAYS AS (fold_accents(last_name)) STORED NOT NULL;
CREATE INDEX idx_owner_last_name_folded ON owner USING gin (LOWER(last_name_folded) gin_trgm_ops);
```

The query column names the function that computed it. A value compared with the column passes through that function, on the value side only, so the search compares `LOWER(o.last_name_folded) LIKE LOWER(fold_accents(?))`: the column as stored, which is the expression the index holds, and the input folded by the database in the same statement. The recheck reads the stored column, and a caller passes the input as it is. Applying `foldAccents` to the query column instead would fold both sides again and bring the recheck back.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@QueryColumn(field = "lastNameFolded", type = String::class, function = "fold_accents")
data class Owner(...) : Entity<Int>

orm.entity(Owner::class)
    .select()
    .where(search(input, Owner_.lastNameFolded))
    .orderFirst(Owner_.lastNameFolded eqIgnoreCase input)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
@QueryColumn(field = "lastNameFolded", type = String.class, function = "fold_accents")
record Owner(...) implements Entity<Integer> {}

orm.entity(Owner.class)
    .select()
    .where(it -> it.search(input, Owner_.lastNameFolded))
    .orderFirst(it -> it.where(Owner_.lastNameFolded, EQUALS_IGNORE_CASE, input))
    .getResultList();
```

</TabItem>
</Tabs>

The entity keeps one field for the name, which the query selects with its accents for display; the folded column stays out of every SELECT and INSERT, and the writes pay for the folding once per row.
