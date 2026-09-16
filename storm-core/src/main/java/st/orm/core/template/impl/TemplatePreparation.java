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

import static java.util.List.copyOf;
import static java.util.stream.Collectors.joining;
import static st.orm.ResolveScope.CASCADE;
import static st.orm.ResolveScope.INNER;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.Templates.delete;
import static st.orm.core.template.Templates.from;
import static st.orm.core.template.Templates.insert;
import static st.orm.core.template.Templates.param;
import static st.orm.core.template.Templates.select;
import static st.orm.core.template.Templates.set;
import static st.orm.core.template.Templates.table;
import static st.orm.core.template.Templates.update;
import static st.orm.core.template.Templates.values;
import static st.orm.core.template.Templates.where;
import static st.orm.core.template.impl.Elements.Clause.GROUP_BY;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getForeignKeys;
import static st.orm.core.template.impl.RecordReflection.getPrimaryKeys;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isJoinedEntity;
import static st.orm.core.template.impl.RecordReflection.isRecord;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;
import static st.orm.core.template.impl.RecordReflection.isTypePresent;
import static st.orm.core.template.impl.RecordReflection.isTypeSelected;
import static st.orm.core.template.impl.RecordReflection.mapForeignKeys;
import static st.orm.core.template.impl.RecordReflection.selectedOccurrences;
import static st.orm.core.template.impl.RecordValidation.validateDataType;
import static st.orm.core.template.impl.SqlParser.endsWithKeyword;
import static st.orm.core.template.impl.SqlParser.getSqlOperation;
import static st.orm.core.template.impl.SqlParser.removeComments;
import static st.orm.core.template.impl.SqlParser.startsWithKeyword;
import static st.orm.spi.SqlOperation.DELETE;
import static st.orm.spi.SqlOperation.INSERT;
import static st.orm.spi.SqlOperation.SELECT;
import static st.orm.spi.SqlOperation.UPDATE;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.DefaultJoinType;
import st.orm.Element;
import st.orm.FK;
import st.orm.JoinType;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.PK;
import st.orm.ProjectionQuery;
import st.orm.Ref;
import st.orm.SelectMode;
import st.orm.SqlTemplateException;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.Query;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Alias;
import st.orm.core.template.impl.Elements.Clause;
import st.orm.core.template.impl.Elements.Column;
import st.orm.core.template.impl.Elements.Columns;
import st.orm.core.template.impl.Elements.Cursor;
import st.orm.core.template.impl.Elements.Delete;
import st.orm.core.template.impl.Elements.Expression;
import st.orm.core.template.impl.Elements.From;
import st.orm.core.template.impl.Elements.Insert;
import st.orm.core.template.impl.Elements.Select;
import st.orm.core.template.impl.Elements.Source;
import st.orm.core.template.impl.Elements.Table;
import st.orm.core.template.impl.Elements.TableSource;
import st.orm.core.template.impl.Elements.TemplateTarget;
import st.orm.core.template.impl.Elements.Update;
import st.orm.core.template.impl.Elements.Where;
import st.orm.core.template.impl.SqlTemplateImpl.ElementNode;
import st.orm.core.template.impl.SqlTemplateImpl.Wrapped;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;
import st.orm.spi.SqlOperation;

/**
 * Prepares a {@link TemplateString} for compilation and binding.
 *
 * <p>This class performs three responsibilities:</p>
 *
 * <ul>
 *   <li><b>Preprocessing</b>: determines the {@link SqlOperation} and resolves raw template values into {@link Element}
 *   instances with context-aware rules.</li>
 *   <li><b>Post-processing</b>: derives table aliases and join structure, and registers join mappings required for
 *   expression compilation and record mapping.</li>
 *   <li><b>Preparation</b>: produces a {@link TemplateProcessor} and a {@link CompilationContext} that can be compiled
 *   once and then bound repeatedly.</li>
 * </ul>
 *
 * <p>Preparation is per template invocation. A prepared template can be correlated with a parent processor when nested
 * subqueries must share alias resolution and hint ordering.</p>
 *
 * @since 1.8
 */
class TemplatePreparation {

    /**
     * Reflection helper used to interpret runtime classes and supported types.
     */
    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    /**
     * The template being prepared.
     */
    private final SqlTemplate template;

    /**
     * Builder for resolving ORM models used during post-processing and query model creation.
     */
    private final ModelBuilder modelBuilder;

    /**
     * Dialect helper used for constructing SQL fragments such as join conditions.
     */
    private final SqlDialectTemplate dialectTemplate;

    /**
     * Factory for creating the optional {@link QueryModel} used by expression compilation and alias resolution.
     */
    private final QueryModelFactory queryModelFactory;

    /**
     * Creates a new preparation instance for a specific {@link SqlTemplate}.
     *
     * @param template     the template to prepare.
     * @param modelBuilder the model builder used for resolving record and table metadata.
     */
    TemplatePreparation(SqlTemplate template, ModelBuilder modelBuilder) {
        this.template = template;
        this.modelBuilder = modelBuilder;
        this.dialectTemplate = new SqlDialectTemplate(template.dialect());
        this.queryModelFactory = new QueryModelFactory(template, modelBuilder);
    }

    /**
     * Binding context produced by {@link #preprocess(TemplateString)}.
     *
     * <p>This context contains the raw template fragments, resolved elements (not yet post-processed), and the SQL
     * operation determined from the template string.</p>
     *
     * @param fragments  the template fragments.
     * @param elements   resolved elements corresponding to template values.
     * @param operation  the SQL operation inferred from the template.
     */
    record BindingContext(List<String> fragments, List<Element> elements, SqlOperation operation) { }

    /**
     * Compilation context produced by {@link #prepare(BindingContext)}.
     *
     * <p>This context contains the fragments and post-processed elements used by
     * {@link TemplateProcessor#compile(CompilationContext, boolean)}.</p>
     *
     * @param fragments  the template fragments.
     * @param elements   post-processed elements, possibly containing {@link Wrapped} nodes to inject joins.
     * @param operation  the SQL operation inferred from the template.
     */
    record CompilationContext(List<String> fragments, List<Element> elements, SqlOperation operation) { }

    /**
     * Prepared template consisting of a processor and its compilation context.
     *
     * <p>The returned processor can be compiled once and then bound multiple times. When preparation is performed as a
     * child of another processor, compilation state is shared via the parent-child processor relationship.</p>
     *
     * @param processor the processor responsible for compilation and binding.
     * @param context   the compilation context to compile.
     */
    record PreparedTemplate(TemplateProcessor processor, CompilationContext context) { }

    /**
     * Preprocesses a template string into fragments and resolved elements.
     *
     * <p>This step determines the {@link SqlOperation} and resolves each raw value into an {@link Element} based on
     * operation, surrounding SQL fragments, and template configuration.</p>
     *
     * @param templateString the template string to preprocess.
     * @return the binding context.
     * @throws SqlTemplateException if a value cannot be resolved in the given context.
     */
    BindingContext preprocess(TemplateString templateString) throws SqlTemplateException {
        var fragments = templateString.fragments();
        var values = templateString.values();
        var operation = getSqlOperation(templateString, template.dialect());
        var elements = resolveElements(operation, values, fragments);
        assert values.size() == elements.size();
        return new BindingContext(fragments, elements, operation);
    }

    /**
     * Prepares a binding context for compilation without correlation.
     *
     * @param bindingContext the binding context.
     * @return the prepared template.
     * @throws SqlTemplateException if preparation fails.
     */
    PreparedTemplate prepare(BindingContext bindingContext) throws SqlTemplateException {
        return prepare(bindingContext, null, false);
    }

    /**
     * Prepares a binding context for compilation and binding.
     *
     * <p>This method performs post-processing (aliases, joins, mapping availability) and creates the processor to compile
     * and bind the statement. When {@code correlate} is true, aliases may be shared with the parent processor.</p>
     *
     * @param bindingContext  the binding context.
     * @param parentProcessor the parent processor used for correlated nested preparation, or {@code null}.
     * @param correlate       whether the preparation should correlate with the parent processor.
     * @return the prepared template.
     * @throws SqlTemplateException if preparation fails.
     */
    PreparedTemplate prepare(
            BindingContext bindingContext,
            @Nullable TemplateProcessor parentProcessor,
            boolean correlate
    ) throws SqlTemplateException {
        assert !correlate || parentProcessor != null;
        var tableUse = new TableUse();
        var tableMapper = new TableMapper();
        var aliasMapper = new AliasMapper(
                tableUse,
                template.tableAliasResolver(),
                template.tableNameResolver(),
                correlate ? parentProcessor.aliasMapper() : null
        );
        var postProcessedElements = postProcessElements(bindingContext.operation(), bindingContext.elements(), aliasMapper, tableMapper);
        var flattened = postProcessedElements.stream()
                .flatMap(e -> e instanceof Wrapped(var elements) ? elements.stream().map(ElementNode::element) : Stream.of(e))
                .toList();
        var queryModel = queryModelFactory.getQueryModel(flattened, tableMapper, aliasMapper).orElse(null);
        TemplateProcessor processor;
        if (parentProcessor == null) {
            processor = new TemplateProcessor(template, this, bindingContext.operation(), modelBuilder, tableUse, aliasMapper, queryModel);
        } else {
            processor = parentProcessor.child(this, bindingContext.operation(), modelBuilder, tableUse, aliasMapper, queryModel);
        }
        return new PreparedTemplate(processor, new CompilationContext(bindingContext.fragments(), postProcessedElements, bindingContext.operation()));
    }

    /**
     * Resolves a {@link BindVars} value into an appropriate element based on operation and surrounding fragment.
     *
     * <p>{@link BindVars} must appear in specific locations, for example after WHERE, SET, or VALUES. This method converts
     * a raw bind vars value into an element that encodes the correct target clause.</p>
     *
     * @param operation         the SQL operation.
     * @param previousFragment  the fragment before the value.
     * @param bindVars          the bind vars value.
     * @return the resolved element.
     * @throws SqlTemplateException if the bind vars value is not valid in the given context.
     */
    private Element resolveBindVarsElement(
            SqlOperation operation,
            String previousFragment,
            BindVars bindVars
    ) throws SqlTemplateException {
        String previous = removeComments(previousFragment, template.dialect()).stripTrailing().toUpperCase();
        return switch (operation) {
            case SELECT, DELETE, UNDEFINED -> {
                if (endsWithKeyword(previous, "WHERE")) {
                    yield where(bindVars);
                }
                throw new SqlTemplateException("BindVars element expected after WHERE.");
            }
            case INSERT -> {
                if (endsWithKeyword(previous, "WHERE")) {
                    yield where(bindVars);
                }
                if (endsWithKeyword(previous, "VALUES")) {
                    yield values(bindVars);
                }
                throw new SqlTemplateException("BindVars element expected after VALUES or WHERE.");
            }
            case UPDATE -> {
                if (endsWithKeyword(previous, "SET")) {
                    yield set(bindVars);
                }
                if (endsWithKeyword(previous, "WHERE")) {
                    yield where(bindVars);
                }
                throw new SqlTemplateException("BindVars element expected after SET or WHERE.");
            }
        };
    }

    /**
     * Resolves a raw object value into an element based on operation and surrounding fragment.
     *
     * <p>Objects after WHERE are treated as where-objects (and must be non-null). Records after VALUES or SET are treated
     * as record value sources. All other objects become parameters.</p>
     *
     * @param operation         the SQL operation.
     * @param previousFragment  the fragment before the value.
     * @param o                 the value to resolve.
     * @return the resolved element.
     * @throws SqlTemplateException if the object is invalid in the given context.
     */
    private Element resolveObjectElement(
            SqlOperation operation,
            String previousFragment,
            @Nullable Object o
    ) throws SqlTemplateException {
        String previous = removeComments(previousFragment, template.dialect()).stripTrailing().toUpperCase();
        return switch (operation) {
            case SELECT, DELETE, UNDEFINED -> whereOrParam(previous, o);
            case INSERT -> {
                if (endsWithKeyword(previous, "VALUES")) {
                    if (o instanceof Data r) {
                        yield values(r);
                    }
                    throw new SqlTemplateException("Record expected after VALUES.");
                }
                yield whereOrParam(previous, o);
            }
            case UPDATE -> {
                if (endsWithKeyword(previous, "SET")) {
                    if (o instanceof Data r) {
                        yield set(r);
                    }
                    throw new SqlTemplateException("Record expected after SET.");
                }
                yield whereOrParam(previous, o);
            }
        };
    }

    /**
     * Resolves an object in a position where a where-object is accepted: after WHERE the object becomes a
     * where-element (and must be non-null), anywhere else it becomes a parameter.
     */
    private Element whereOrParam(String previous, @Nullable Object o) throws SqlTemplateException {
        if (endsWithKeyword(previous, "WHERE")) {
            if (o != null) {
                return where(o);
            }
            throw new SqlTemplateException("Non-null object expected after WHERE.");
        }
        return param(o);
    }

    /**
     * Resolves an array value into an element.
     *
     * <p>Arrays are treated like iterables and may be used for IN-like constructs or as parameter collections depending on
     * context.</p>
     *
     * @param operation         the SQL operation.
     * @param previousFragment  the fragment before the value.
     * @param array             the array to resolve.
     * @return the resolved element.
     * @throws SqlTemplateException if the array is invalid in the given context.
     */
    private Element resolveArrayElement(
            SqlOperation operation,
            String previousFragment,
            Object[] array
    ) throws SqlTemplateException {
        return resolveIterableElement(operation, previousFragment, List.of(array));
    }

    /**
     * Resolves an iterable value into an element based on operation and surrounding fragment.
     *
     * <p>Iterables after WHERE become where-iterables. Iterables after VALUES in INSERT must contain only records. In all
     * other contexts they are treated as parameter values.</p>
     *
     * @param operation         the SQL operation.
     * @param previousFragment  the fragment before the value.
     * @param iterable          the iterable to resolve.
     * @return the resolved element.
     * @throws SqlTemplateException if the iterable is invalid in the given context.
     */
    @SuppressWarnings("unchecked")
    private Element resolveIterableElement(
            SqlOperation operation,
            String previousFragment,
            Iterable<?> iterable
    ) throws SqlTemplateException {
        String previous = removeComments(previousFragment, template.dialect()).stripTrailing().toUpperCase();
        return switch (operation) {
            case SELECT, UPDATE, DELETE, UNDEFINED -> {
                if (endsWithKeyword(previous, "WHERE")) {
                    yield where(iterable);
                }
                yield param(iterable);
            }
            case INSERT -> {
                if (endsWithKeyword(previous, "VALUES")) {
                    if (StreamSupport.stream(iterable.spliterator(), false).allMatch(it -> it instanceof Data)) {
                        yield values((Iterable<? extends Data>) iterable);
                    }
                    throw new SqlTemplateException("Records expected after VALUES.");
                }
                if (endsWithKeyword(previous, "WHERE")) {
                    yield where(iterable);
                }
                yield param(iterable);
            }
        };
    }

    /**
     * Resolves a table type into an element based on operation and surrounding fragments.
     *
     * <p>Depending on where the type is used in the template, it can represent FROM, JOIN, SELECT projection, INTO, UPDATE
     * target, DELETE target, or alias access.</p>
     *
     * @param operation         the SQL operation.
     * @param first             the first statement element (Select/Insert/Update/Delete), if already seen.
     * @param previousFragment  the fragment before the value.
     * @param nextFragment      the fragment after the value.
     * @param recordType        the record type to resolve.
     * @return the resolved element.
     * @throws SqlTemplateException if the type cannot be used in the given context.
     */
    private Element resolveTypeElement(
            SqlOperation operation,
            @Nullable Element first,
            String previousFragment,
            String nextFragment,
            Class<? extends Data> recordType
    ) throws SqlTemplateException {
        if (nextFragment.startsWith(".")) {
            return new Alias(recordType, CASCADE);
        }
        String next = removeComments(nextFragment, template.dialect()).stripLeading().toUpperCase();
        String previous = removeComments(previousFragment, template.dialect()).stripTrailing().toUpperCase();
        return switch (operation) {
            case SELECT -> {
                if (endsWithKeyword(previous, "FROM")) {
                    boolean autoJoin = first instanceof Select select && isTypePresent(recordType, select.table());
                    yield from(recordType, autoJoin);
                }
                if (endsWithKeyword(previous, "JOIN")) {
                    yield table(recordType);
                }
                yield select(recordType);
            }
            case INSERT -> {
                if (endsWithKeyword(previous, "INTO")) {
                    yield insert(recordType);
                }
                yield table(recordType);
            }
            case UPDATE -> {
                if (endsWithKeyword(previous, "UPDATE")) {
                    yield update(recordType);
                }
                yield table(recordType);
            }
            case DELETE -> {
                if (startsWithKeyword(next, "FROM")) {
                    yield delete(recordType);
                }
                if (endsWithKeyword(previous, "FROM")) {
                    yield from(recordType, false);
                }
                yield table(recordType);
            }
            case UNDEFINED -> table(recordType);
        };
    }

    /**
     * Resolves raw template values into {@link Element} instances.
     *
     * <p>The resolution is context-aware: the operation, fragments, and template configuration influence how values are
     * interpreted. For example, a {@link Data} instance after VALUES becomes a {@code values(record)} element, while the
     * same instance elsewhere becomes a parameter.</p>
     *
     * <p>This method also enforces constraints such as single statement element usage (Select/Insert/Update/Delete) and
     * record support configuration.</p>
     *
     * @param sqlOperation the operation inferred from the template.
     * @param values       the raw template values.
     * @param fragments    the template fragments.
     * @return resolved elements corresponding one-to-one with {@code values}.
     * @throws SqlTemplateException if a value cannot be resolved or violates statement rules.
     */
    List<Element> resolveElements(
            SqlOperation sqlOperation,
            List<?> values,
            List<String> fragments
    ) throws SqlTemplateException {
        List<Element> resolvedValues = new ArrayList<>();
        Element first = null;
        for (int i = 0; i < values.size(); i++) {
            var v = values.get(i);
            var p = fragments.get(i);
            var n = fragments.get(i + 1);
            Element element = switch (v) {
                case Select ignore when sqlOperation != SELECT ->
                        throw new SqlTemplateException("Select element is only allowed for select statements.");
                case Insert ignore when sqlOperation != INSERT ->
                        throw new SqlTemplateException("Insert element is only allowed for insert statements.");
                case Update ignore when sqlOperation != UPDATE ->
                        throw new SqlTemplateException("Update element is only allowed for update statements.");
                case Delete ignore when sqlOperation != DELETE ->
                        throw new SqlTemplateException("Delete element is only allowed for delete statements.");
                case Select it when !template.supportRecords() ->
                        throw new SqlTemplateException("Records are not supported in this configuration: '%s'".formatted(it.table().getSimpleName()));
                case Insert it when !template.supportRecords() ->
                        throw new SqlTemplateException("Records are not supported in this configuration: '%s'".formatted(it.table().getSimpleName()));
                case Update it when !template.supportRecords() ->
                        throw new SqlTemplateException("Records are not supported in this configuration: '%s'".formatted(it.table().getSimpleName()));
                case Delete it when !template.supportRecords() ->
                        throw new SqlTemplateException("Records are not supported in this configuration: '%s'".formatted(it.table().getSimpleName()));
                case Table t when !template.supportRecords() ->
                        throw new SqlTemplateException("Records are not supported in this configuration: '%s'.".formatted(t.table().getSimpleName()));
                case Class<?> c when isRecord(c) && !template.supportRecords() ->
                        throw new SqlTemplateException("Records are not supported in this configuration: '%s'.".formatted(c.getSimpleName()));
                case Select it -> {
                    if (first != null) {
                        throw new SqlTemplateException("Only a single Select element is allowed.");
                    }
                    yield it;
                }
                case Insert it -> {
                    if (first != null) {
                        throw new SqlTemplateException("Only a single Insert element is allowed.");
                    }
                    yield it;
                }
                case Update it -> {
                    if (first != null) {
                        throw new SqlTemplateException("Only a single Update element is allowed.");
                    }
                    yield it;
                }
                case Delete it -> {
                    if (first != null) {
                        throw new SqlTemplateException("Only a single Delete element is allowed.");
                    }
                    yield it;
                }
                case Expression e -> new Cacheable(e);
                case BindVars b -> resolveBindVarsElement(sqlOperation, p, b);
                case Subqueryable t -> new Elements.Subquery(t.getSubquery(), true);
                case Navigable<?, ?> m when m.isColumn() -> new Column(toColumnMetamodel(m), CASCADE);
                case Navigable<?, ?> ignore -> throw new SqlTemplateException(
                        "Path does not reference a column. Use a column-level path (e.g., User_.name) rather than a table-level path.");
                case Object[] a -> resolveArrayElement(sqlOperation, p, a);
                case Iterable<?> l -> resolveIterableElement(sqlOperation, p, l);
                case Element e -> e;
                case Class<?> c -> resolveTypeElement(sqlOperation, first, p, n, REFLECTION.getDataType(c));
                case Object k when REFLECTION.isSupportedType(k) ->
                        resolveTypeElement(sqlOperation, first, p, n, REFLECTION.getDataType(k));
                case TemplateString ignore -> throw new SqlTemplateException("TemplateString not allowed as string template value.");
                case Stream<?> ignore -> throw new SqlTemplateException("Stream not supported as string template value.");
                case Query ignore -> throw new SqlTemplateException("Query not supported as string template value. Use QueryBuilder instead.");
                case Object o -> resolveObjectElement(sqlOperation, p, o);
                case null -> param(v);
            };
            if (first == null && (element instanceof Select || element instanceof Insert || element instanceof Update || element instanceof Delete)) {
                first = element;
            }
            resolvedValues.add(element);
        }
        return resolvedValues;
    }

    /**
     * Post-processes elements to derive aliases, joins, and mapping availability.
     *
     * <p>This step mutates the element list. In particular, it may replace the FROM element by a {@link Wrapped} node
     * containing the FROM node and additional join nodes, while preserving fragment alignment.</p>
     *
     * @param sqlOperation the statement operation.
     * @param elements     the resolved elements to post-process.
     * @param aliasMapper  alias mapper used for alias derivation and lookups.
     * @param tableMapper  table mapper used for primary key and foreign key mapping availability.
     * @return the mutated list of elements.
     * @throws SqlTemplateException if post-processing fails.
     */
    private List<Element> postProcessElements(
            SqlOperation sqlOperation,
            List<Element> elements,
            AliasMapper aliasMapper,
            TableMapper tableMapper
    ) throws SqlTemplateException {
        var mutableElements = new ArrayList<>(elements);
        switch (sqlOperation) {
            case SELECT -> postProcessSelect(mutableElements, aliasMapper, tableMapper);
            case UPDATE -> postProcessUpdate(mutableElements, aliasMapper, tableMapper);
            case DELETE -> postProcessDelete(mutableElements, aliasMapper, tableMapper);
            default -> postProcessUndefined(mutableElements, aliasMapper, tableMapper);
        }
        resolveColumns(mutableElements);
        return mutableElements;
    }

    /**
     * Resolves each {@link Columns} element to the metamodel its columns are read from.
     *
     * <p>A path naming the key of a table reached through a foreign key normally collapses to the foreign key field,
     * so it resolves to the foreign key column on the referencing table and no join is needed. The two columns hold
     * the same value, guaranteed by the constraint, so which is named is only ever a question of the statement the
     * database accepts.</p>
     *
     * <p>Grouping is where it becomes one. Functional dependency is resolved syntactically and per table, so a
     * statement has to group by the same column it selects: grouping the referencing table's foreign key column while
     * selecting the referenced table's key leaves that key ungrouped, and grouping the referenced key while selecting
     * the foreign key column leaves that column ungrouped. Either way a strict dialect rejects it. So a grouped path
     * stays uncollapsed exactly when the select list carries the referenced table.</p>
     *
     * <p>This runs over the whole element list, where the select list is known and fixed, rather than while an
     * individual element renders: the choice is a property of the statement, and asking for it during rendering
     * would mean reading state that rendering itself mutates.</p>
     */
    private static void resolveColumns(List<Element> mutableElements) throws SqlTemplateException {
        Class<? extends Data> selected = mutableElements.stream()
                .filter(Select.class::isInstance)
                .map(Select.class::cast)
                .map(Select::table)
                .findFirst()
                .orElse(null);
        int lastGrouping = -1;
        var grouped = new ArrayList<Metamodel<?, ?>>();
        for (int i = 0; i < mutableElements.size(); i++) {
            if (mutableElements.get(i) instanceof Columns columns) {
                var resolved = new ArrayList<Metamodel<?, ?>>();
                for (var field : columns.fields()) {
                    resolved.add(resolveColumnsField(field, columns.clause(), selected));
                }
                mutableElements.set(i, new Columns(resolved, columns.scope(), columns.clause()));
                if (columns.clause() == GROUP_BY) {
                    lastGrouping = i;
                    grouped.addAll(resolved);
                }
            }
        }
        if (lastGrouping >= 0 && selected != null) {
            completeGrouping(mutableElements, lastGrouping, grouped, selected);
        }
    }

    /**
     * Adds the keys of the table occurrences the grouping determines but does not name.
     *
     * <p>A grouping states what one row stands for. Expressing that in SQL takes more columns than the caller wrote,
     * because functional dependency is resolved syntactically and per table: grouping an owner's key determines that
     * owner's city, since a foreign key is to-one, but no engine carries the dependency across the join. So every
     * occurrence the select list carries below a grouped one contributes its key too.</p>
     *
     * <p>Adding these cannot change what the statement returns. Each is reached from a grouped occurrence by to-one
     * edges alone, so it holds one value per group and splits nothing; it is the same grouping, spelled so an engine
     * accepts it.</p>
     */
    private static void completeGrouping(List<Element> mutableElements,
                                         int lastGrouping,
                                         List<Metamodel<?, ?>> grouped,
                                         Class<? extends Data> selected) throws SqlTemplateException {
        var groupedPaths = new HashSet<String>();
        for (var field : grouped) {
            // Only an identity grouping determines anything: grouping a value says nothing about other tables.
            String identity = MetamodelFactory.identityPath(field);
            if (identity != null) {
                groupedPaths.add(identity);
            }
        }
        if (groupedPaths.isEmpty()) {
            return;
        }
        var root = grouped.getFirst().root();
        var prefix = selectedPathOf(root, selected);
        if (prefix == null) {
            return;
        }
        var completion = new ArrayList<Metamodel<?, ?>>();
        for (var occurrence : selectedOccurrences(selected).entrySet()) {
            String path = prefix.isEmpty() || occurrence.getKey().isEmpty()
                    ? prefix + occurrence.getKey()
                    : prefix + "." + occurrence.getKey();
            if (isDetermined(path, groupedPaths)) {
                if (!namesOccurrence(path, groupedPaths)) {
                    var key = keyOf(root, path);
                    if (key != null) {
                        completion.add(key);
                        groupedPaths.add(path);
                    }
                }
            } else if (!namesOccurrence(path, groupedPaths)
                    && findPkField(occurrence.getValue()).isPresent()) {
                // The select list carries this table and the grouping neither names it nor determines it, so the
                // statement asks for columns that hold many values per group. Reported here rather than left to the
                // database: the permissive products return an arbitrary row instead of refusing, and the model says
                // enough to know the query has no answer whichever product runs it.
                throw new SqlTemplateException(
                        ("Grouping does not determine %s, whose columns the select list carries. Group by %s as well, "
                                + "or select fewer columns. Grouped: %s.")
                                .formatted(occurrence.getValue().getSimpleName(),
                                        describePath(root, path),
                                        describeGrouped(root, groupedPaths)));
            }
        }
        if (!completion.isEmpty()) {
            var columns = (Columns) mutableElements.get(lastGrouping);
            var fields = new ArrayList<>(columns.fields());
            fields.addAll(completion);
            mutableElements.set(lastGrouping, new Columns(fields, columns.scope(), columns.clause()));
        }
    }

    /** Renders a path the way a caller writes it, so the message names something they can paste back. */
    private static String describePath(Class<? extends Data> root, String path) {
        String metamodel = root.getSimpleName() + "_";
        return path.isEmpty()
                ? metamodel + "." + findPkField(root).map(RecordField::name).orElse("id")
                : metamodel + "." + path;
    }

    /** Renders the grouped identities, so the message states what the grouping does determine. */
    private static String describeGrouped(Class<? extends Data> root, Set<String> groupedPaths) {
        return groupedPaths.stream()
                .sorted()
                .map(path -> describePath(root, path))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Returns whether a grouped occurrence determines the occurrence at the given path. */
    private static boolean isDetermined(String path, Set<String> groupedPaths) {
        for (String grouped : groupedPaths) {
            if (path.equals(grouped) || (grouped.isEmpty() && !path.isEmpty()) || path.startsWith(grouped + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether the grouping already names the occurrence at the given path. */
    private static boolean namesOccurrence(String path, Set<String> groupedPaths) {
        return groupedPaths.contains(path);
    }

    /** Returns the path of the selected table within the query root's selected graph, or {@code null} if ambiguous. */
    private static @Nullable String selectedPathOf(Class<? extends Data> root, Class<? extends Data> selected)
            throws SqlTemplateException {
        if (root == selected) {
            return "";
        }
        String found = null;
        for (var occurrence : selectedOccurrences(root).entrySet()) {
            if (occurrence.getValue() == selected) {
                if (found != null) {
                    return null;    // The table occurs more than once; the grouping has to name the occurrences.
                }
                found = occurrence.getKey();
            }
        }
        return found;
    }

    /** Returns the metamodel naming the key of the occurrence at the given path. */
    private static @Nullable Metamodel<?, ?> keyOf(Class<? extends Data> root, String path) {
        if (path.isEmpty()) {
            return null;    // The root's own key is named by the caller; nothing to complete.
        }
        return MetamodelFactory.referencedKey(MetamodelFactory.of(root, path));
    }

    /**
     * Returns the metamodel the given element's columns are read from.
     */
    private static Metamodel<?, ?> resolveColumnsField(Metamodel<?, ?> field, Clause clause,
                                                      @Nullable Class<? extends Data> selected)
            throws SqlTemplateException {
        var collapsed = MetamodelFactory.canonical(field);
        if (clause != GROUP_BY || selected == null) {
            return collapsed;
        }
        // Asked of the relationship, not of how the path was written: groupBy(Pet_.owner) and
        // groupBy(Pet_.owner.id) name the same thing and have to resolve to the same column.
        var referencedKey = MetamodelFactory.referencedKey(collapsed);
        if (referencedKey == null) {
            return collapsed;
        }
        return isTypeSelected(selected, referencedKey.tableType()) ? referencedKey : collapsed;
    }

    /**
     * Updates the element list to include table aliases and optional joins for SELECT statements.
     *
     * <p>If a FROM clause is present and references a table, this method ensures an alias exists for the root table and,
     * when auto-join is enabled, injects joins derived from the record type graph. If no FROM is present, only explicit
     * table aliases are registered.</p>
     *
     * @param mutableElements the mutable element list.
     * @param aliasMapper     alias mapper used for alias registration and generation.
     * @param tableMapper     table mapper used for mapping primary keys and foreign keys.
     * @throws SqlTemplateException if processing fails.
     */
    private void postProcessSelect(
            List<Element> mutableElements,
            AliasMapper aliasMapper,
            TableMapper tableMapper
    ) throws SqlTemplateException {
        final From from = mutableElements.stream()
                .filter(From.class::isInstance)
                .map(From.class::cast)
                .findAny()
                .orElse(null);
        final From effectiveFrom;
        if (from != null && from.source() instanceof TableSource(var table)) {
            validateDataType(table);
            String path = "";
            String alias;
            if (from.alias().isEmpty()) {
                alias = aliasMapper.generateAlias(table, path, template.dialect());
            } else {
                alias = from.alias();
                aliasMapper.setAlias(table, alias, path);
            }
            var projectionQuery = isRecord(table)
                    ? getRecordType(table).getAnnotation(ProjectionQuery.class)
                    : null;  // Sealed entity interfaces don't have ProjectionQuery.
            Source source = projectionQuery != null
                    ? new Elements.TemplateSource(TemplateString.of(projectionQuery.value()))
                    : new TableSource(table);
            effectiveFrom = new From(source, alias, from.autoJoin());
            mutableElements.replaceAll(element -> element instanceof From ? effectiveFrom : element);
            addJoins(table, mutableElements, effectiveFrom, aliasMapper, tableMapper);
        } else {
            addTableAliases(mutableElements, aliasMapper);
        }
    }

    /**
     * Collects custom joins, optionally adds auto-joins derived from record types, and injects joins into the element list.
     *
     * <p>Custom joins are removed from their original positions and injected after the FROM element so join ordering can
     * be normalized. Custom joins that reference tables are converted to explicit table joins with assigned aliases and
     * registered mapping information.</p>
     *
     * @param fromTable       the root FROM table.
     * @param mutableElements the mutable element list.
     * @param from            the effective FROM element.
     * @param aliasMapper     alias mapper used for alias registration and generation.
     * @param tableMapper     table mapper used for mapping primary keys and foreign keys.
     * @throws SqlTemplateException if processing fails.
     */
    private void addJoins(
            Class<? extends Data> fromTable,
            List<Element> mutableElements,
            From from,
            AliasMapper aliasMapper,
            TableMapper tableMapper
    ) throws SqlTemplateException {
        List<Join> customJoins = new ArrayList<>();
        for (ListIterator<Element> it = mutableElements.listIterator(); it.hasNext(); ) {
            Element element = it.next();
            if (element instanceof Table(var table, var alias)) {
                aliasMapper.setAlias(table, alias, null);
            } else if (element instanceof Join j) {
                String path = "";
                if (j instanceof Join(TableSource ts, var ignore1, var ignore2, var ignore3, var ignore4, boolean ignore5)) {
                    String alias;
                    if (j.sourceAlias().isEmpty()) {
                        alias = aliasMapper.generateAlias(ts.table(), null, template.dialect());
                    } else {
                        alias = j.sourceAlias();
                        aliasMapper.setAlias(ts.table(), j.sourceAlias(), null);
                    }
                    var projectionQuery = getRecordType(ts.table()).getAnnotation(ProjectionQuery.class);
                    Source source = projectionQuery != null
                            ? new Elements.TemplateSource(TemplateString.of(projectionQuery.value()))
                            : ts;
                    customJoins.add(new Join(source, alias, j.target(), j.type(), false));
                    tableMapper.mapPrimaryKey(
                            fromTable,
                            ts.table(),
                            alias,
                            findPkField(ts.table()).orElseThrow(
                                    () -> new SqlTemplateException("No primary key found for table %s.".formatted(ts.table().getSimpleName()))
                            ),
                            ts.table(),
                            path
                    );
                    mapForeignKeys(tableMapper, alias, ts.table(), ts.table(), path);
                } else {
                    customJoins.add(j);
                }
                it.set(new Wrapped(List.of()));
            }
        }
        List<Join> joins = new ArrayList<>();
        ReferencedPaths referenced = collectReferencedTablePaths(fromTable, mutableElements, customJoins);
        // Always ensure joins exist for referenced metamodel paths (in SELECT, WHERE, ORDER BY, ...). With auto-join the
        // whole hydrated entity graph is joined eagerly; without it only the referenced foreign keys are joined on
        // demand, so a selected or filtered column beyond the root's own columns (in particular beyond a reference)
        // still resolves. Unreferenced auto-joins are pruned by JoinProcessor.
        addAutoJoins(fromTable, fromTable, customJoins, aliasMapper, tableMapper, joins, referenced, !from.autoJoin());
        if (!joins.isEmpty()) {
            mutableElements.stream()
                .filter(Select.class::isInstance)
                .map(Select.class::cast)
                .findAny().ifPresent(select -> joins.replaceAll(join ->
                    // The selected rows come from this join, so the row must exist and an outer join would produce
                    // empty results. A join onto the table the query is rooted at is a different occurrence of that
                    // table, and the selected rows come from the root occurrence instead, so it keeps its own type.
                    join.source() instanceof TableSource(var joinTable)
                        && joinTable == select.table() && joinTable != fromTable && join.type().isOuter()
                        ? new Join(join.source(), join.sourceAlias(), join.target(), join.targetAlias(),
                        DefaultJoinType.INNER, join.autoJoin())
                        : join));
            List<ElementNode> elementNodes = new ArrayList<>();
            elementNodes.add(new ElementNode(from, false));
            elementNodes.addAll(orderJoins(joins).stream().map(it -> new ElementNode(it, it.autoJoin())).toList());
            mutableElements.replaceAll(element -> element instanceof From ? new Wrapped(elementNodes) : element);
        }
    }

    /**
     * Orders joins so that outer joins come last to improve portability across databases, without breaking
     * declaration-order dependencies: a join whose ON clause references an outer-joined table must stay behind
     * that outer join.
     *
     * <p>Auto-joins reference their parent table's alias, so a derived join depends on its parent join. Custom
     * joins can reference any table in the join graph through their ON clause, so they are conservatively treated
     * as depending on every join that precedes them. Joins within the same group keep their relative order.</p>
     *
     * @param joins the joins in declaration order.
     * @return the ordered joins.
     */
    private static List<Join> orderJoins(List<Join> joins) {
        List<Join> ordered = new ArrayList<>(joins.size());
        List<Join> deferred = new ArrayList<>(joins.size());
        Set<String> deferredAliases = new HashSet<>();
        for (var join : joins) {
            boolean defer = join.type().isOuter()
                    || (join.autoJoin()
                            ? join.targetAlias() != null && deferredAliases.contains(join.targetAlias())
                            : !deferred.isEmpty());
            if (defer) {
                deferred.add(join);
                deferredAliases.add(join.sourceAlias());
            } else {
                ordered.add(join);
            }
        }
        ordered.addAll(deferred);
        return ordered;
    }

    /**
     * Computes auto-joins for a FROM table and merges them with any custom joins.
     *
     * <p>Auto-joins are derived from {@link FK} fields of the record type graph. The resulting list is in declaration
     * order: derived joins first, custom joins last. Ordering for portability is applied afterwards by
     * {@link #orderJoins(List)}.</p>
     *
     * @param table        the FROM table.
     * @param rootTable    the root table for mapping purposes.
     * @param customJoins  user-defined joins collected from the template.
     * @param aliasMapper  alias mapper used for alias registration and generation.
     * @param tableMapper  table mapper used for mapping foreign keys.
     * @param joins        output list that receives joins.
     * @throws SqlTemplateException if auto-join derivation fails.
     */
    private void addAutoJoins(
            Class<? extends Data> table,
            Class<? extends Data> rootTable,
            List<Join> customJoins,
            AliasMapper aliasMapper,
            TableMapper tableMapper,
            List<Join> joins,
            ReferencedPaths referenced,
            boolean demandOnly
    ) throws SqlTemplateException {
        // Sealed entity interfaces are not records and don't have FK fields that need auto-joining.
        // Their columns are synthetic (union of subtype fields). Skip auto-join for sealed entities.
        // When demandOnly is set, only referenced foreign keys are joined (the hydrated graph is not expanded), so
        // the recursion starts already beyond the eager region.
        if (!isSealedEntity(table)) {
            var context = new AutoJoinContext(rootTable, aliasMapper, tableMapper, joins, referenced);
            deriveAutoJoins(getRecordType(table), table, List.of(), null, false, demandOnly, context);
        } else if (!demandOnly && isJoinedEntity(table)) {
            // For JOINED inheritance, add LEFT JOIN for each extension table (permitted subclass with
            // @DbTable) using PK=PK ON conditions.
            addJoinedExtensionJoins(table, aliasMapper, joins);
        }
        joins.addAll(customJoins);
    }

    /**
     * Adds LEFT JOIN elements for extension tables in a JOINED sealed entity hierarchy.
     *
     * <p>Each permitted subclass with its own {@code @DbTable} gets a LEFT JOIN on the shared primary key, so that
     * extension columns are available in the result set. For example, for a sealed Vehicle with JoinedCar and
     * JoinedTruck subtypes, this produces:</p>
     *
     * <pre>
     * LEFT JOIN joined_car jc ON jc.id = jv.id
     * LEFT JOIN joined_truck jt ON jt.id = jv.id
     * </pre>
     *
     * @param sealedType  the sealed entity interface (the base/FROM table).
     * @param aliasMapper alias mapper used for alias generation and registration.
     * @param joins       output list that receives the extension table joins.
     * @throws SqlTemplateException if extension join generation fails.
     */
    @SuppressWarnings("unchecked")
    private void addJoinedExtensionJoins(
            Class<? extends Data> sealedType,
            AliasMapper aliasMapper,
            List<Join> joins
    ) throws SqlTemplateException {
        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            return;
        }
        // Get the base table alias (registered by postProcessSelect for the FROM table).
        String baseAlias = aliasMapper.findAlias(sealedType, "", INNER)
                .orElseThrow(() -> new SqlTemplateException(
                        "No alias found for base table %s.".formatted(sealedType.getSimpleName())));
        // Get PK column name(s) from the sealed hierarchy.
        RecordField pkField = findPkField(sealedType).orElseThrow(
                () -> new SqlTemplateException(
                        "No PK field found for sealed entity %s.".formatted(sealedType.getSimpleName())));
        var pkColumnNames = getPrimaryKeys(pkField, template.foreignKeyResolver(), template.columnNameResolver());
        for (Class<?> subtype : permitted) {
            Class<? extends Data> extensionType = (Class<? extends Data>) subtype;
            String extensionAlias = aliasMapper.generateAlias(extensionType, null, template.dialect());
            // Build ON clause: extensionAlias.pk = baseAlias.pk (supports compound PKs). The extension table's PK
            // doubles as the FK to the base table, so the FK side goes first, consistent with JoinProcessor.
            StringBuilder onClause = new StringBuilder();
            for (int i = 0; i < pkColumnNames.size(); i++) {
                if (i > 0) {
                    onClause.append(" AND ");
                }
                String pkCol = pkColumnNames.get(i).qualified(template.dialect());
                onClause.append(dialectTemplate.process("\0.\0 = \0.\0",
                        extensionAlias, pkCol, baseAlias, pkCol));
            }
            joins.add(new Join(
                    new TableSource(extensionType),
                    extensionAlias,
                    new TemplateTarget(TemplateString.of(onClause.toString())),
                    DefaultJoinType.LEFT,
                    true
            ));
        }
    }

    /**
     * The arguments of an auto-join derivation that stay fixed across the recursion: the root the mappings are
     * registered against, the mappers that receive them, the output join list, and the query's referenced paths.
     * Bundling them leaves the genuinely varying traversal state visible in the recursive signature.
     */
    private record AutoJoinContext(
            Class<? extends Data> rootTable,
            AliasMapper aliasMapper,
            TableMapper tableMapper,
            List<Join> joins,
            ReferencedPaths referenced) {
    }

    /**
     * Recursively derives joins and alias mappings for a record type graph.
     *
     * <p>{@link FK} fields trigger join generation. {@link Ref} foreign keys do not generate a join, but they register
     * mapping information so the ref component can be queried. Nested record fields that are not primary keys and have no
     * explicit converter are treated as inlined and are traversed.</p>
     *
     * @param type        the current record type.
     * @param table       the current table type.
     * @param path        the current record field path.
     * @param fkName      the alias to use as the join source for nested traversal, or {@code null}.
     * @param outerJoin   whether joins in this subtree must be outer joins due to a nullable ancestor.
     * @param beyondRef   whether the traversal is below a reference boundary, where joins are demand-driven.
     * @param context     the arguments that stay fixed across the recursion.
     * @throws SqlTemplateException if derivation fails.
     */
    private void deriveAutoJoins(
            RecordType type,
            Class<? extends Data> table,
            List<RecordField> path,
            @Nullable String fkName,
            boolean outerJoin,
            boolean beyondRef,
            AutoJoinContext context
    ) throws SqlTemplateException {
        var rootTable = context.rootTable();
        var aliasMapper = context.aliasMapper();
        var tableMapper = context.tableMapper();
        var joins = context.joins();
        var referenced = context.referenced();
        for (var field : type.fields()) {
            var list = new ArrayList<>(path);
            String fkPath = toPathString(path);
            list.add(field);
            var copy = copyOf(list);
            String pkPath = toPathString(copy);
            if (field.isAnnotationPresent(FK.class)) {
                if (Ref.class.isAssignableFrom(field.type())) {
                    boolean navigated = isReferencedBeyond(referenced.joined(), pkPath);
                    // A reference is never hydrated, so it contributes no eager join: it is selected as its foreign
                    // key column, which keeps the entity graph optimized. In the eager (hydrated) region the reference
                    // still registers its mapping so its foreign key component can be resolved, matching prior
                    // behavior. Below a reference boundary the reference is only touched when a query element navigates
                    // beyond it; then a filter-only join is materialized so the referenced table can be filtered,
                    // joined, ordered, or selected. Gating on referenced paths bounds the recursion, which matters
                    // because references can be cyclic (self-referencing foreign keys must be a Ref). Any ref join that
                    // still ends up unreferenced is pruned by JoinProcessor.
                    if (!beyondRef || navigated) {
                        String fromAlias = fkName != null ? fkName : aliasMapper.getAlias(
                                table,
                                fkPath,
                                INNER,
                                template.dialect(),
                                () -> new SqlTemplateException("Table %s for From not found at %s."
                                        .formatted(type.type().getSimpleName(), fkPath)));
                        tableMapper.mapForeignKey(table, getRefDataType(field), fromAlias, field, rootTable, fkPath);
                        if (navigated) {
                            RecordType fieldType = getRecordType(getRefDataType(field));
                            boolean effectiveOuterJoin = outerJoin || field.nullable();
                            String alias = addForeignKeyJoin(table, rootTable, field, fieldType, fromAlias, fkPath,
                                    pkPath, effectiveOuterJoin, aliasMapper, tableMapper, joins);
                            deriveAutoJoins(fieldType, fieldType.requireDataType(), copy, alias,
                                    effectiveOuterJoin, !referenced.hydrated().contains(pkPath), context);
                        }
                    }
                    continue;
                }
                if (!isRecord(field.type())) {
                    throw new SqlTemplateException("FK is only allowed on Ref and record types.");
                }
                if (field.type() == type.type()) {
                    throw new SqlTemplateException(
                            "Self-referencing FK annotation is not allowed: %s. FK must be marked as Ref."
                                    .formatted(type.type().getSimpleName())
                    );
                }
                // Below a reference boundary nothing is hydrated, so entity foreign keys are demand-driven too:
                // only the ones a query element navigates to are joined. Above any reference (the hydrated graph)
                // every entity foreign key is joined eagerly, preserving the existing behavior.
                if (beyondRef && !isReferencedBeyond(referenced.joined(), pkPath)) {
                    continue;
                }
                String fromAlias = resolveFromAlias(fkName, table, fkPath, type, aliasMapper);
                RecordType fieldType = getRecordType(field.type());
                boolean effectiveOuterJoin = outerJoin || field.nullable();
                String alias = addForeignKeyJoin(table, rootTable, field, fieldType, fromAlias, fkPath,
                        pkPath, effectiveOuterJoin, aliasMapper, tableMapper, joins);
                deriveAutoJoins(fieldType, fieldType.requireDataType(), copy, alias, effectiveOuterJoin,
                        beyondRef && !referenced.hydrated().contains(pkPath), context);
            } else if (isRecord(field.type())) {
                if (field.isAnnotationPresent(PK.class) || getORMConverter(field).isPresent()) {
                    continue;
                }
                // Below a reference boundary, only recurse into an inline record when a query element navigates
                // through it; above any reference the whole hydrated graph is traversed, preserving prior behavior.
                if (beyondRef && !isReferencedBeyond(referenced.joined(), pkPath)) {
                    continue;
                }
                String fromAlias = resolveFromAlias(fkName, table, fkPath, type, aliasMapper);
                deriveAutoJoins(getRecordType(field.type()), table, copy, fromAlias, outerJoin, beyondRef, context);
            }
        }
    }

    /**
     * Resolves the alias a derived join starts from: the enclosing foreign key's name when the traversal is inside
     * one, or the alias registered for the table at the foreign key path otherwise.
     */
    private String resolveFromAlias(
            @Nullable String fkName,
            Class<? extends Data> table,
            String fkPath,
            RecordType type,
            AliasMapper aliasMapper
    ) throws SqlTemplateException {
        if (fkName != null) {
            return fkName;
        }
        return aliasMapper.getAlias(
                table,
                fkPath,
                INNER,
                template.dialect(),
                () -> new SqlTemplateException("Table %s for From not found at path %s."
                        .formatted(type.type().getSimpleName(), fkPath))
        );
    }

    /**
     * Generates a foreign key join from {@code fromAlias} on {@code table} to the table backing {@code fieldType},
     * registers the new alias and foreign key mapping, and appends the join to {@code joins}. The join is marked as an
     * auto-join so {@link JoinProcessor} prunes it when the target table is not referenced by the query.
     *
     * @return the alias generated for the joined table.
     */
    private String addForeignKeyJoin(
            Class<? extends Data> table,
            Class<? extends Data> rootTable,
            RecordField field,
            RecordType fieldType,
            String fromAlias,
            String fkPath,
            String pkPath,
            boolean effectiveOuterJoin,
            AliasMapper aliasMapper,
            TableMapper tableMapper,
            List<Join> joins
    ) throws SqlTemplateException {
        String alias = aliasMapper.generateAlias(fieldType.requireDataType(), pkPath, table, fromAlias, template.dialect());
        tableMapper.mapForeignKey(table, fieldType.requireDataType(), fromAlias, field, rootTable, fkPath);
        // The join type encodes the declared cardinality of the foreign key. A non-null foreign key asserts that the
        // relationship always resolves, so it is joined with INNER; a nullable foreign key, or any foreign key beneath
        // a nullable ancestor, is joined with LEFT because the child may be absent. INNER is the faithful encoding: a
        // LEFT join on a non-null field would admit a null that the record cannot hold, since a non-null component
        // cannot be constructed from a missing row. The consequence is that a parent whose non-null foreign key does
        // not resolve, either because the column is null or because it holds a value that points at a row that does
        // not exist, is excluded from the result. This filtering coincides with hydration: when the child table is not
        // referenced its auto-join is pruned and no parent row is dropped, which is correct because nothing is being
        // constructed from it. A field declared non-null over a column that permits null is a mapping error reported
        // separately by schema validation. A foreign key that must neither filter nor join is modeled as a Ref, which
        // selects the foreign key column without a join.
        JoinType joinType = effectiveOuterJoin ? DefaultJoinType.LEFT : DefaultJoinType.INNER;
        ProjectionQuery query = fieldType.getAnnotation(ProjectionQuery.class);
        Source source = query == null
                ? new TableSource(fieldType.requireDataType())
                : new Elements.TemplateSource(TemplateString.of(query.value()));
        Elements.Target target = query == null
                ? new Elements.TableTarget(table, field)
                : getTemplateTarget(
                fromAlias,
                alias,
                field,
                findPkField(fieldType.type()).orElseThrow(
                        () -> new SqlTemplateException("Failed to find primary key for table %s."
                                .formatted(fieldType.type().getSimpleName()))
                )
        );
        joins.add(new Join(source, alias, target, fromAlias, joinType, true));
        return alias;
    }

    /**
     * Returns whether a referenced table path reaches at or beyond the foreign key at {@code pkPath}, meaning a query
     * element (a filter, join, order-by, group-by, or selected column) navigates through it and therefore needs its
     * join to be present. Paths use record field names, matching those registered during join derivation.
     */
    private static boolean isReferencedBeyond(Set<String> referencedTablePaths, String pkPath) {
        if (pkPath.isEmpty()) {
            return false;
        }
        String prefix = pkPath + ".";
        for (String referenced : referencedTablePaths) {
            if (referenced.equals(pkPath) || referenced.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The table paths a query's elements name, split by what naming one requires. Every joined path needs its table
     * joined so its columns resolve. A hydrated path needs that and more: the query materializes the table's record,
     * so the foreign keys the record holds are joined with it, exactly as they are for the root.
     *
     * @param joined   every path whose table the query needs, hydrated paths included.
     * @param hydrated the paths whose table is materialized as a record.
     */
    private record ReferencedPaths(Set<String> joined, Set<String> hydrated) { }

    /**
     * Collects the table paths, relative to the FROM {@code rootTable}, that the query's elements reference beyond the
     * root, so join derivation can materialize any joins those paths need (in particular, joins that traverse a Ref
     * boundary).
     *
     * <p>A query names a table in two ways. A metamodel names it by path, and only metamodels rooted at
     * {@code rootTable} contribute; a reference to the root table itself carries the empty path and is ignored. A
     * selected table, an aliased table, or a join target names it by type, which is resolved here to the path or paths
     * at which that type is reachable from the root. Both forms mean the query needs that table, so both have to reach
     * join derivation: without the type form, a table beyond a reference has no join and no alias to resolve
     * against.</p>
     */
    private ReferencedPaths collectReferencedTablePaths(Class<? extends Data> rootTable,
                                                        List<Element> elements,
                                                        List<Join> customJoins) throws SqlTemplateException {
        Set<String> paths = new HashSet<>();
        Set<Class<? extends Data>> tables = new LinkedHashSet<>();
        Set<Class<? extends Data>> hydratedTables = new LinkedHashSet<>();
        Set<Class<? extends Data>> explicitTables = new HashSet<>();
        Set<String> hydratedPaths = new HashSet<>();
        for (Element element : elements) {
            collectReferencedTablePaths(rootTable, element, paths, tables, hydratedTables, hydratedPaths);
            if (element instanceof Table(var table, var ignore)) {
                explicitTables.add(table);
            }
        }
        // Custom joins are lifted out of the element list before this runs, so they are inspected here. A join's
        // target names a table of the root's graph, while its source is the table the join itself brings in: that
        // occurrence carries its own alias, so a type reference to it is already satisfied and deriving a second
        // occurrence would make the type ambiguous.
        for (Join join : customJoins) {
            if (join.target() instanceof Elements.TableTarget(var table, var ignore)) {
                tables.add(table);
            }
            if (join.source() instanceof TableSource(var table)) {
                explicitTables.add(table);
            }
        }
        tables.removeAll(explicitTables);
        hydratedTables.removeAll(explicitTables);
        Set<String> hydrated = new HashSet<>(hydratedPaths);
        for (Class<? extends Data> table : hydratedTables) {
            addReferencedTypePaths(rootTable, table, hydrated);
        }
        paths.addAll(hydrated);
        for (Class<? extends Data> table : tables) {
            addReferencedTypePaths(rootTable, table, paths);
        }
        return new ReferencedPaths(paths, hydrated);
    }

    private void collectReferencedTablePaths(Class<? extends Data> rootTable,
                                             Element element,
                                             Set<String> paths,
                                             Set<Class<? extends Data>> tables,
                                             Set<Class<? extends Data>> hydratedTables,
                                             Set<String> hydratedPaths) {
        switch (element) {
            case Column column -> addReferencedTablePath(rootTable, MetamodelFactory.canonical(column.field()), paths);
            case Columns columns -> {
                for (var field : columns.fields()) {
                    addReferencedTablePath(rootTable, MetamodelFactory.canonical(field), paths);
                }
            }
            case Cursor cursor -> {
                for (var field : cursor.fields()) {
                    addReferencedTablePath(rootTable, MetamodelFactory.canonical(field), paths);
                }
            }
            // A nested select materializes the table's record, so its own foreign keys are part of what is selected;
            // the other modes select columns of that table alone.
            case Select(var table, var mode) -> (mode == SelectMode.NESTED ? hydratedTables : tables).add(table);
            // A resolved reference is materialized in its own right: the referenced table is joined, and the foreign
            // keys of the record it holds are joined with it. The element's paths are prefix-closed.
            case Elements.Fetch fetch -> hydratedPaths.addAll(fetch.paths());
            case Alias alias -> tables.add(alias.table());
            case Where where -> {
                if (where.expression() != null) {
                    collectReferencedTablePaths(rootTable, where.expression(), paths, tables, hydratedTables, hydratedPaths);
                }
                if (where.bindVarsKey() != null) {
                    addReferencedTablePath(rootTable, where.bindVarsKey(), paths);
                }
            }
            case Cacheable cacheable ->
                    collectReferencedTablePaths(rootTable, cacheable.expression(), paths, tables, hydratedTables, hydratedPaths);
            case Elements.Set set -> {
                for (var metamodel : set.fields()) {
                    addReferencedTablePath(rootTable, metamodel, paths);
                }
            }
            case Wrapped wrapped -> {
                for (var node : wrapped.elements()) {
                    collectReferencedTablePaths(rootTable, node.element(), paths, tables, hydratedTables, hydratedPaths);
                }
            }
            default -> {
            }
        }
    }

    private void collectReferencedTablePaths(Class<? extends Data> rootTable,
                                             Expression expression,
                                             Set<String> paths,
                                             Set<Class<? extends Data>> tables,
                                             Set<Class<? extends Data>> hydratedTables,
                                             Set<String> hydratedPaths) {
        switch (expression) {
            case Elements.ObjectExpression objectExpression -> {
                if (objectExpression.metamodel() != null) {
                    addReferencedTablePath(rootTable, objectExpression.metamodel(), paths);
                }
            }
            case Elements.TemplateExpression templateExpression ->
                    collectReferencedTablePaths(rootTable, templateExpression.template(), paths, tables, hydratedTables, hydratedPaths);
        }
    }

    private void collectReferencedTablePaths(Class<? extends Data> rootTable,
                                             TemplateString template,
                                             Set<String> paths,
                                             Set<Class<? extends Data>> tables,
                                             Set<Class<? extends Data>> hydratedTables,
                                             Set<String> hydratedPaths) {
        for (var value : template.values()) {
            switch (value) {
                case Metamodel<?, ?> metamodel -> addReferencedTablePath(rootTable, metamodel, paths);
                // A navigation-only node (one that navigates beyond a Ref) is rebuilt into a resolvable metamodel,
                // so the joins beyond the reference are derived for it.
                case Navigable<?, ?> navigable when navigable.isColumn() ->
                        addReferencedTablePath(rootTable, toColumnMetamodel(navigable), paths);
                case Expression expression ->
                        collectReferencedTablePaths(rootTable, expression, paths, tables, hydratedTables, hydratedPaths);
                case Element element -> collectReferencedTablePaths(rootTable, element, paths, tables, hydratedTables, hydratedPaths);
                case TemplateString nested ->
                        collectReferencedTablePaths(rootTable, nested, paths, tables, hydratedTables, hydratedPaths);
                default -> {
                }
            }
        }
    }

    /**
     * Records the paths at which {@code table} is reachable from {@code rootTable}, for a query element that names the
     * table by type rather than by path. The root itself is not a path, and a type the root's graph does not reach
     * contributes nothing: such a table is resolved by other means, or reported as unresolvable when its alias is
     * looked up.
     *
     * <p>Every path is recorded when a type is reachable at more than one, because the query has not said which one it
     * means. Alias resolution reports the ambiguity, and a derived join that ends up unreferenced is pruned by
     * {@link JoinProcessor}.</p>
     */
    private void addReferencedTypePaths(Class<? extends Data> rootTable,
                                        Class<? extends Data> table,
                                        Set<String> paths) throws SqlTemplateException {
        if (table == rootTable || isSealedEntity(rootTable)) {
            return;
        }
        // A type already on the path terminates the walk: references may be cyclic, and a table repeated on one path
        // is not aliased per occurrence, so a deeper occurrence would not be addressable anyway.
        Set<Class<? extends Data>> visited = new HashSet<>();
        visited.add(rootTable);
        addReferencedTypePaths(getRecordType(rootTable), table, List.of(), visited, paths);
    }

    private void addReferencedTypePaths(RecordType type,
                                        Class<? extends Data> table,
                                        List<RecordField> path,
                                        Set<Class<? extends Data>> visited,
                                        Set<String> paths) throws SqlTemplateException {
        for (var field : type.fields()) {
            var list = new ArrayList<>(path);
            list.add(field);
            var copy = copyOf(list);
            if (field.isAnnotationPresent(FK.class)) {
                Class<? extends Data> target;
                if (Ref.class.isAssignableFrom(field.type())) {
                    target = getRefDataType(field);
                } else if (isRecord(field.type())) {
                    target = getRecordType(field.type()).requireDataType();
                } else {
                    continue;
                }
                if (target == table) {
                    paths.add(toPathString(copy));
                }
                if (visited.add(target)) {
                    addReferencedTypePaths(getRecordType(target), table, copy, visited, paths);
                    visited.remove(target);
                }
            } else if (isRecord(field.type())
                    && !field.isAnnotationPresent(PK.class)
                    && getORMConverter(field).isEmpty()) {
                // An inline record stays on the same table, so it extends the path without contributing a join.
                addReferencedTypePaths(getRecordType(field.type()), table, copy, visited, paths);
            }
        }
    }

    /**
     * Records the table path of a metamodel that is rooted at {@code rootTable}. The table path is the path up to the
     * table holding the referenced column; the demand-driven join gate expands it to every foreign key it crosses.
     */
    private void addReferencedTablePath(Class<? extends Data> rootTable,
                                        Metamodel<?, ?> metamodel,
                                        Set<String> paths) {
        if (metamodel.root() != rootTable) {
            return;
        }
        String path = metamodel.path();
        if (!path.isEmpty()) {
            paths.add(path);
        }
    }

    /**
     * Resolves a navigable used as a template value into a column metamodel. Full metamodels are used as-is; a
     * navigation-only node (one that navigates beyond a {@link Ref}) is rebuilt into a resolvable metamodel for its
     * path so it can be selected or filtered. The rebuilt metamodel is query-only and cannot extract a value.
     */
    private static <T extends Data> Metamodel<T, ?> toColumnMetamodel(Navigable<T, ?> navigable) {
        return navigable instanceof Metamodel<T, ?> metamodel
                ? metamodel
                : Metamodel.of(navigable.root(), navigable.fieldPath());
    }

    /**
     * Builds a join target template for projection queries.
     *
     * <p>The join condition is generated by matching foreign key columns on the source field to primary key columns on the
     * target field. This supports compound keys by joining all corresponding columns.</p>
     *
     * @param fromAlias  alias of the source table.
     * @param toAlias    alias of the target table.
     * @param fromField  source record field containing FK columns.
     * @param toField    target record field containing PK columns.
     * @return a template target representing the join condition.
     * @throws SqlTemplateException if foreign key and primary key column counts do not match.
     */
    @SuppressWarnings("DuplicatedCode")
    private TemplateTarget getTemplateTarget(
            String fromAlias,
            String toAlias,
            RecordField fromField,
            RecordField toField
    ) throws SqlTemplateException {
        var foreignKeys = getForeignKeys(fromField, template.foreignKeyResolver(), template.columnNameResolver());
        var primaryKeys = getPrimaryKeys(toField, template.foreignKeyResolver(), template.columnNameResolver());
        if (foreignKeys.size() != primaryKeys.size()) {
            throw new SqlTemplateException("Mismatch between foreign keys and primary keys count.");
        }
        StringBuilder joinCondition = new StringBuilder();
        for (int i = 0; i < foreignKeys.size(); i++) {
            if (i > 0) {
                joinCondition.append(" AND ");
            }
            joinCondition.append(dialectTemplate.process("\0.\0 = \0.\0", fromAlias, foreignKeys.get(i), toAlias, primaryKeys.get(i)));
        }
        return new TemplateTarget(TemplateString.of(joinCondition.toString()));
    }

    /**
     * Registers aliases for explicit {@link Table} elements found in the statement.
     *
     * @param elements    the statement elements.
     * @param aliasMapper the alias mapper used for registration.
     * @throws SqlTemplateException if alias registration fails.
     */
    private void addTableAliases(List<Element> elements, AliasMapper aliasMapper) throws SqlTemplateException {
        for (Element element : elements) {
            if (element instanceof Table(var table, var alias)) {
                aliasMapper.setAlias(table, alias, null);
            }
        }
    }

    /**
     * Converts a record field path into a dot-separated path string.
     *
     * @param fields the record field path.
     * @return the dot-separated path string.
     */
    static String toPathString(List<RecordField> fields) {
        return fields.stream().map(RecordField::name).collect(joining("."));
    }

    /**
     * Updates the element list to handle alias registration and FK mapping availability for UPDATE statements.
     *
     * <p>Update statements may not support table aliases on all databases. When no explicit alias is provided, the empty
     * alias is registered. Foreign keys of the updated entity are registered for mapping.</p>
     *
     * @param mutableElements the mutable element list.
     * @param aliasMapper     alias mapper used for alias registration.
     * @param tableMapper     table mapper used for mapping foreign keys.
     * @throws SqlTemplateException if processing fails.
     */
    private void postProcessUpdate(
            List<Element> mutableElements,
            AliasMapper aliasMapper,
            TableMapper tableMapper
    ) throws SqlTemplateException {
        final Update update = mutableElements.stream()
                .filter(Update.class::isInstance)
                .map(Update.class::cast)
                .findAny()
                .orElse(null);
        if (update != null) {
            var table = update.table();
            validateDataType(table);
            String path = "";
            String alias;
            if (update.alias().isEmpty()) {
                alias = "";
            } else {
                alias = update.alias();
                aliasMapper.setAlias(table, alias, path);
            }
            mapForeignKeys(tableMapper, alias, table, table, path);
        }
        addTableAliases(mutableElements, aliasMapper);
    }

    /**
     * Updates the element list to include aliases and optional joins for DELETE statements.
     *
     * <p>If a FROM clause is present, an alias is ensured for the FROM table, and joins may be injected similarly to
     * SELECT. If a DELETE element is present, it must match the FROM table. If a DELETE element is used without FROM,
     * this method fails because the target table is ambiguous.</p>
     *
     * @param mutableElements the mutable element list.
     * @param aliasMapper     alias mapper used for alias registration and generation.
     * @param tableMapper     table mapper used for mapping foreign keys.
     * @throws SqlTemplateException if processing fails.
     */
    private void postProcessDelete(
            List<Element> mutableElements,
            AliasMapper aliasMapper,
            TableMapper tableMapper
    ) throws SqlTemplateException {
        final Delete delete = mutableElements.stream()
                .filter(Delete.class::isInstance)
                .map(Delete.class::cast)
                .findAny()
                .orElse(null);
        final From from = mutableElements.stream()
                .filter(From.class::isInstance)
                .map(From.class::cast)
                .filter(f -> f.source() instanceof TableSource)
                .findAny()
                .orElse(null);
        final From effectiveFrom;
        if (from != null && from.source() instanceof TableSource(var table)) {
            validateDataType(table);
            String path = "";
            String alias;
            if (from.alias().isEmpty()) {
                if (delete == null) {
                    aliasMapper.setAlias(table, "", path);
                    alias = "";
                } else {
                    alias = aliasMapper.generateAlias(table, path, template.dialect());
                }
                effectiveFrom = new From(new TableSource(table), alias, from.autoJoin());
                mutableElements.replaceAll(element -> element instanceof From ? effectiveFrom : element);
            } else {
                effectiveFrom = from;
                alias = from.alias();
                aliasMapper.setAlias(table, alias, path);
            }
            if (delete != null) {
                if (delete.table() != table) {
                    throw new SqlTemplateException("Delete entity %s does not match From table %s."
                            .formatted(delete.table().getSimpleName(), table.getSimpleName()));
                }
                if (delete.alias().isEmpty()) {
                    if (!effectiveFrom.alias().isEmpty()) {
                        mutableElements.replaceAll(element -> element instanceof Delete ? delete(table, alias) : element);
                    }
                }
            }
            addJoins(table, mutableElements, effectiveFrom, aliasMapper, tableMapper);
        } else if (delete != null) {
            throw new SqlTemplateException("From element required when using Delete element.");
        } else {
            addTableAliases(mutableElements, aliasMapper);
        }
    }

    /**
     * Post-processes a statement when no primary table is present.
     *
     * <p>This path treats the statement similarly to SELECT and registers aliases and joins based on detected FROM usage.
     * It is used for templates where the operation is unknown or the primary table cannot be inferred.</p>
     *
     * @param mutableElements the mutable element list.
     * @param aliasMapper     alias mapper used for alias registration.
     * @param tableMapper     table mapper used for mapping availability.
     * @throws SqlTemplateException if processing fails.
     */
    private void postProcessUndefined(
            List<Element> mutableElements,
            AliasMapper aliasMapper,
            TableMapper tableMapper
    ) throws SqlTemplateException {
        postProcessSelect(mutableElements, aliasMapper, tableMapper);
    }
}
