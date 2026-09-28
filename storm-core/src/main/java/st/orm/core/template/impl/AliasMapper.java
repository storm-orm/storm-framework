/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core.template.impl;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static st.orm.ResolveScope.CASCADE;
import static st.orm.ResolveScope.INNER;
import static st.orm.core.template.impl.RecordReflection.getTableName;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedCollection;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.ResolveScope;
import st.orm.SqlTemplateException;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.TableAliasResolver;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;

final class AliasMapper {

    private final TableUse tableUse;
    private final Map<Class<? extends Data>, SequencedCollection<TableAlias>> aliasMap;
    private final AliasMapper parent;
    private final TableAliasResolver tableAliasResolver;
    private final TableNameResolver tableNameResolver;
    private final SchemaResolver schemaResolver;

    record TableAlias(Class<? extends Data> table, String path, String alias) {}

    /**
     * Alias entry with the path it was registered under and its nesting level: 0=current, 1=parent, etc.
     */
    private record AliasEntry(String alias, @Nullable String path, int level) {}

    AliasMapper(TableUse tableUse,
                TableAliasResolver tableAliasResolver,
                TableNameResolver tableNameResolver,
                SchemaResolver schemaResolver,
                @Nullable AliasMapper parent) {
        this.tableUse = requireNonNull(tableUse);
        this.tableAliasResolver = requireNonNull(tableAliasResolver);
        this.tableNameResolver = requireNonNull(tableNameResolver);
        this.schemaResolver = requireNonNull(schemaResolver);
        this.parent = parent;
        this.aliasMap = new HashMap<>();
    }

    /**
     * Retrieves all aliases, including those from the parent. Note that the stream may contain duplicates as
     * parents may contain the same aliases.
     */
    private Stream<String> aliases() {
        // Effectively using CASCADE to include all aliases.
        var local = aliasMap.values().stream().flatMap(it -> it.stream()
                    .map(TableAlias::alias));
        var global = parent == null ? Stream.<String>of() : parent.aliases();
        return Stream.concat(local, global);
    }

    /**
     * Recursively collects all alias entries for a given table and optional component path,
     * tagging each with its nesting depth (0 = current level, 1 = parent, etc.).
     *
     * <p>At each level it will:</p>
     * <ol>
     *   <li>Include any locally registered aliases whose path matches (or all if {@code path} is null)</li>
     *   <li>Recurse into the parent mapper (incrementing {@code level} by 1)</li>
     * </ol></p>
     *
     * <p>The resulting stream emits <code>AliasEntry</code> instances with both the alias string and its {@code level},
     * so callers can apply their own filtering (e.g. only outer or only inner levels).</p>
     *
     * @param table the record type whose aliases are being collected.
     * @param path optional component path; if <code>null</code>, matches all aliases.
     * @param level the current nesting depth: 0=current, 1=parent, etc.
     */
    private Stream<AliasEntry> collectAliasEntries(Class<? extends Data> table,
                                                   @Nullable String path,
                                                   int level) {
        // Current level matches.
        var local = aliasMap.getOrDefault(table, List.of()).stream()
                .filter(ta -> path == null || Objects.equals(path, ta.path()))
                .map(ta -> new AliasEntry(ta.alias(), ta.path(), level));
        // Recurse to parent if present.
        var parentStream = parent == null
                ? Stream.<AliasEntry>empty()
                : parent.collectAliasEntries(table, path, level + 1);
        return Stream.concat(local, parentStream);
    }

    private SqlTemplateException multipleFoundException(Class<? extends Data> table,
                                                        @Nullable String path,
                                                        ResolveScope resolveMode) {
        if (resolveMode != INNER) {
            if (path != null) {
                return new SqlTemplateException("Multiple aliases found for: %s at path: '%s'. Use INNER scope to limit alias resolution to the current scope.".formatted(table.getSimpleName(), path));
            }
            var paths = collectAliasEntries(table, null, 0).map(AliasEntry::path).toList();
            return multiplePathsFoundException(table, paths);
        }
        var paths = aliasMap.get(table).stream()
                .map(TableAlias::path)
                .toList();
        return multiplePathsFoundException(table, paths);
    }

    public String useAlias(Class<? extends Data> table,
                           String alias) throws SqlTemplateException {
        if (collectAliasEntries(table, null, 0)
                .filter(e -> e.alias().equals(alias))
                .findAny()
                .isEmpty()) {
            throw new SqlTemplateException("Alias %s for table %s not found.".formatted(alias, table.getSimpleName()));
        }
        return alias;
    }

    private boolean exists(Class<? extends Data> table, ResolveScope scope) throws SqlTemplateException {
        return findAlias(table, null, scope).isPresent();
    }

    /**
     * Returns the primary alias for the specified table.
     *
     * @param table the table to get the alias for.
     * @return the primary alias.
     */
    public Optional<String> getPrimaryAlias(Class<? extends Data> table) throws SqlTemplateException {
        return findAlias(table, null, INNER);
    }

    /**
     * Returns the alias for the table at the specified path.
     *
     * <p><strong>Note:</strong> The alias returned is safe for use in SQL and does not require escaping.</p>
     *
     * @param metamodel the metamodel of the table to get the alias for.
     * @param scope the scope to resolve the alias in.
     * @param dialect the SQL dialect to use in case the alias is based on table name and potentially requires escaping.
     * @return the alias for the table at the specified path.
     * @throws SqlTemplateException if the alias could not be resolved.
     */
    public String getAlias(Metamodel<?, ?> metamodel,
                           ResolveScope scope,
                           SqlDialect dialect) throws SqlTemplateException {
        return getAlias(metamodel, scope, dialect,
                () -> new SqlTemplateException("Alias for table not found at %s: %s is not part of this query. Join the table or reference it through a path from the query root.".formatted(
                        metamodel, metamodel.tableType().getSimpleName())));
    }

    /**
     * Returns the alias for the table at the specified path.
     *
     * <p><strong>Note:</strong> The alias returned is safe for use in SQL and does not require escaping.</p>
     *
     * @param metamodel the metamodel of the table to get the alias for.
     * @param scope the scope to resolve the alias in.
     * @param dialect the SQL dialect to use in case the alias is based on table name and potentially requires escaping.
     * @param exceptionSupplier the exception supplier to use in case the alias could not be resolved.
     * @return the alias for the table at the specified path.
     * @throws SqlTemplateException if the alias could not be resolved.
     */
    public String getAlias(Metamodel<?, ?> metamodel,
                           ResolveScope scope,
                           SqlDialect dialect,
                           Supplier<SqlTemplateException> exceptionSupplier) throws SqlTemplateException {
        var table = metamodel.table();
        String path = table.fieldPath();
        if (!Data.class.isAssignableFrom(table.fieldType())) {
            throw new SqlTemplateException("Component type of table %s not a Data type: %s.".formatted(table, table.fieldType().getSimpleName()));
        }
        return getAlias(table.fieldType(), path.isEmpty() ? null : path, scope, dialect, exceptionSupplier);
    }

    /**
     * Returns the alias for the table at the specified path.
     *
     * <p><strong>Note:</strong> The alias returned is safe for use in SQL and does not require escaping.</p>
     *
     * @param table the table to get the alias for.
     * @param path the path of the table (optional).
     * @param scope the scope to resolve the alias in.
     * @param dialect the SQL dialect to use in case the alias is based on table name and potentially requires escaping.
     * @param exceptionSupplier the exception supplier to use in case the alias could not be resolved.
     * @return the alias for the table at the specified path.
     * @throws SqlTemplateException if the alias could not be resolved.
     */
    public String getAlias(Class<? extends Data> table,
                           @Nullable String path,
                           ResolveScope scope,
                           SqlDialect dialect,
                           Supplier<SqlTemplateException> exceptionSupplier) throws SqlTemplateException {
        var alias = findAlias(table, path, scope).orElse("");
        if (!alias.isEmpty()) {
            return alias;
        }
        if (path != null) {
            throw exceptionSupplier.get();
        }
        if (exists(table, scope)) {
            // Table is registered, but alias could not be resolved (due to empty registration). Revert to full table name.
            return getTableName(table, tableNameResolver, schemaResolver).qualified(dialect);
        }
        throw exceptionSupplier.get();
    }

    /**
     * Returns the registered path for the specified table at the current nesting level, but only when the table is
     * registered exactly once (after de-duplication on path).
     *
     * <p>This is used when columns originate from a sub-tree rooted at a different table than the query model root
     * (for example, when {@code SELECT}ing a joined entity type from a repository whose root entity differs). In that
     * case the column metamodel paths are relative to the column root, while the alias mapper has registered the
     * paths relative to the query model root. The path returned here can be prepended to the column path to bridge
     * the two coordinate systems.</p>
     *
     * @param table the table to look up.
     * @return the single registered path for the table, or empty if the table is missing, ambiguous, or registered
     *         without a path.
     */
    public Optional<String> findRegisteredPath(Class<? extends Data> table) {
        var entries = aliasMap.get(table);
        if (entries == null || entries.isEmpty()) {
            return empty();
        }
        String selected = null;
        for (var entry : entries) {
            var entryPath = entry.path();
            if (entryPath == null) {
                continue;
            }
            if (selected == null) {
                selected = entryPath;
            } else if (!selected.equals(entryPath)) {
                // Multiple distinct paths — caller cannot disambiguate.
                return empty();
            }
        }
        return Optional.ofNullable(selected);
    }

    /**
     * Returns the only occurrence at {@code level} that was reached without passing the same table twice, or
     * {@code null} when that does not single one out.
     *
     * <p>Going around a cycle registers an occurrence whose path revisits a table it already passed through, and such
     * a repetition is never what a caller naming the table by type meant: it is only reachable by continuing past the
     * occurrence that was navigated to. Occurrences reached along genuinely separate branches revisit nothing, so they
     * all survive here and remain ambiguous, as do two occurrences registered under the same path.</p>
     *
     * @param entries the alias entries to consider.
     * @param level the nesting level to resolve within.
     * @return the single occurrence reached without repeating a table, or {@code null} when there is not exactly one.
     */
    @Nullable
    private AliasEntry findOccurrenceWithoutRepeatedTable(List<AliasEntry> entries, int level) {
        var pathToTable = mapperAtLevel(level).registeredTablesByPath();
        AliasEntry found = null;
        for (var candidate : entries) {
            if (candidate.level() != level || revisitsTable(candidate.path(), pathToTable)) {
                continue;
            }
            if (found != null) {
                // More than one occurrence was reached without going around a cycle.
                return null;
            }
            found = candidate;
        }
        return found;
    }

    /**
     * Returns whether the given path passes the same table more than once, which is what a trip around a cycle in the
     * table graph produces. A path that is absent or empty passes nothing and never repeats.
     */
    private static boolean revisitsTable(@Nullable String path,
                                         Map<String, Class<? extends Data>> pathToTable) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        var visited = new HashSet<Class<? extends Data>>();
        // Every path starts at the root, which is registered under the empty path, so a path that arrives back at the
        // root table has gone around a cycle just as one that returns to a table it joined along the way.
        var rootTable = pathToTable.get("");
        if (rootTable != null) {
            visited.add(rootTable);
        }
        var prefix = new StringBuilder();
        for (var component : path.split("\\.")) {
            if (!prefix.isEmpty()) {
                prefix.append('.');
            }
            prefix.append(component);
            // Components that do not name a table of their own, such as inline records, register no alias and simply
            // extend the path towards the table that follows them.
            var table = pathToTable.get(prefix.toString());
            if (table != null && !visited.add(table)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the table registered at each path at this level. A path carrying more than one table cannot place that
     * path in the graph and is left out, which only makes {@link #revisitsTable} less willing to report a repeat.
     */
    private Map<String, Class<? extends Data>> registeredTablesByPath() {
        var tablesByPath = new HashMap<String, Class<? extends Data>>();
        var ambiguousPaths = new HashSet<String>();
        for (var entry : aliasMap.entrySet()) {
            for (var tableAlias : entry.getValue()) {
                var registeredPath = tableAlias.path();
                if (registeredPath == null) {
                    continue;
                }
                var existing = tablesByPath.putIfAbsent(registeredPath, entry.getKey());
                if (existing != null && !existing.equals(entry.getKey())) {
                    ambiguousPaths.add(registeredPath);
                }
            }
        }
        tablesByPath.keySet().removeAll(ambiguousPaths);
        return tablesByPath;
    }

    /** Returns the mapper {@code level} steps up the parent chain, or the outermost one when the chain is shorter. */
    private AliasMapper mapperAtLevel(int level) {
        var mapper = this;
        for (int step = 0; step < level && mapper.parent != null; step++) {
            mapper = mapper.parent;
        }
        return mapper;
    }

    public Optional<String> findAlias(Class<? extends Data> table,
                                      @Nullable String path,
                                      ResolveScope scope) throws SqlTemplateException {
        var entries = collectAliasEntries(table, path, 0).toList();
        var filtered = entries.stream()
                .filter(e -> switch (scope) {
                    case INNER   -> e.level() == 0;
                    case OUTER   -> e.level() >  0;
                    case CASCADE -> true;
                })
                .toList();
        if (filtered.isEmpty()) {
            return empty();
        }
        var entry = filtered.getFirst();
        if (filtered.size() > 1) {
            if (filtered.get(1).level() == entry.level()) {
                // A table reachable from itself registers one occurrence per hop around the cycle, for instance an
                // entity holding a foreign key on a record that refers back to that entity. Resolving by type then
                // yields the occurrence that was navigated to, since every other one is only reachable by passing
                // that same table again. Occurrences that are genuinely distinct, such as two foreign keys onto the
                // same table from different branches, pass nothing twice and stay ambiguous.
                var withoutRepeat = findOccurrenceWithoutRepeatedTable(filtered, entry.level());
                if (withoutRepeat == null) {
                    throw multipleFoundException(table, path, scope == INNER ? CASCADE : scope);
                }
                entry = withoutRepeat;
            }
        }
        if (entry.level() == 0) {
            tableUse.addReferencedTable(table, entry.alias());
        }
        return Optional.of(entry.alias());
    }

    public String generateAlias(Class<? extends Data> table,
                                @Nullable String path,
                                SqlDialect dialect) throws SqlTemplateException {
        return generateAlias(table, path, null, null, dialect);
    }

    public String generateAlias(Class<? extends Data> table,
                                @Nullable String path,
                                @Nullable Class<? extends Data> autoJoinTable,
                                @Nullable String autoJoinAlias,
                                SqlDialect dialect) throws SqlTemplateException {
        String alias = generateAlias(table,
                proposedAlias -> aliases().noneMatch(proposedAlias::equals), dialect);    // Take all aliases into account to prevent unnecessary shadowing.
        if (alias.isEmpty()) {
            throw new SqlTemplateException("Failed to generate alias for %s.".formatted(table.getSimpleName()));
        }
        if (autoJoinTable != null && autoJoinAlias != null) {
            tableUse.addAutoJoinTable(table, alias, autoJoinTable, autoJoinAlias);
        } else {
            tableUse.addReferencedTable(table, alias);
        }
        aliasMap.computeIfAbsent(table, ignore -> new LinkedHashSet<>()).add(new TableAlias(table, path, alias));
        return alias;
    }

    public void setAlias(Class<? extends Data> table, String alias, @Nullable String path) throws SqlTemplateException {
        if (!aliasMap.computeIfAbsent(table, ignore -> new LinkedHashSet<>()).add(new TableAlias(table, path, alias))) {
            // Only detect duplicated aliases at the same level.
            throw new SqlTemplateException("Alias already exists for table %s.".formatted(table.getSimpleName()));
        }
    }

    private String generateAlias(Class<? extends Data> table,
                                 Predicate<String> tester,
                                 SqlDialect dialect) throws SqlTemplateException {
        String alias;
        int counter = 0;
        var aliases = new HashSet<>();
        do {
            alias = tableAliasResolver.resolveTableAlias(table, counter++);
            if (alias.isEmpty()) {
                break;
            }
            if (!aliases.add(alias)) {
                throw new SqlTemplateException("Table alias returns the same alias %s multiple times.".formatted(alias));
            }
        } while (!tester.test(alias));
        if (alias.isEmpty()) {
            throw new SqlTemplateException("Table alias for %s is empty.".formatted(table.getSimpleName()));
        }
        return dialect.getSafeIdentifier(alias);
    }

    static SqlTemplateException multiplePathsFoundException(Class<?> table, List<String> paths) {
        paths = paths.stream().filter(Objects::nonNull).distinct().map("'%s'"::formatted).toList();
        if (paths.isEmpty()) {
            return new SqlTemplateException("Multiple paths found for %s.".formatted(table.getSimpleName()));
        }
        if (paths.size() == 1) {
            return new SqlTemplateException("Multiple paths found for %s. Specify path %s to uniquely identify the table.".formatted(table.getSimpleName(), paths.getFirst()));
        }
        return new SqlTemplateException("Multiple paths found for %s in table graph. Specify one of the following paths to uniquely identify the table: %s.".formatted(table.getSimpleName(), String.join(", ", paths)));
    }
}
