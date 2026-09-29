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

import static java.util.Arrays.asList;
import static st.orm.ResolveScope.CASCADE;
import static st.orm.ResolveScope.INNER;
import static st.orm.core.template.Templates.alias;
import static st.orm.core.template.Templates.param;
import static st.orm.core.template.impl.RecordReflection.getDiscriminatorType;
import static st.orm.core.template.impl.RecordReflection.getRecordField;
import static st.orm.core.template.impl.RecordReflection.hasDiscriminator;
import static st.orm.core.template.impl.RecordReflection.isJoinedEntity;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SequencedMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Discriminator.DiscriminatorType;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.Operator;
import st.orm.Ref;
import st.orm.SelectMode;
import st.orm.SqlTemplateException;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.Query;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Expression;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.Subquery;
import st.orm.core.template.impl.Elements.TemplateExpression;
import st.orm.mapping.RecordField;

/**
 * Query model implementation responsible for translating high-level query expressions into SQL fragments and bind
 * values.
 *
 * <p>This class acts as the central coordinator between template parsing, model resolution, alias management,
 * expression compilation, and parameter binding.</p>
 *
 * <p>It is stateful per query and assumes that table aliases, joins, and models have already been established by the
 * surrounding query building process.</p>
 *
 * @since 1.8
 */
final class QueryModelImpl implements QueryModel {

    private static final ORMReflection REFLECTION = Providers.getORMReflection();

    private final SqlTemplate template;
    private final ModelBuilder modelBuilder;
    private final AliasedTable table;
    private final TableMapper tableMapper;
    private final AliasMapper aliasMapper;

    private final Model<?, ?> model;

    /**
     * Creates a new query model for the given SQL template and root table.
     *
     * @param template     the SQL template driving compilation and dialect behavior.
     * @param modelBuilder the model builder used to resolve entity metadata.
     * @param table        the root aliased table of the query.
     * @param tableMapper  mapper tracking table usage and uniqueness.
     * @param aliasMapper  mapper responsible for resolving table aliases.
     * @throws SqlTemplateException if the root model cannot be built.
     */
    QueryModelImpl(
            SqlTemplate template,
            ModelBuilder modelBuilder,
            AliasedTable table,
            TableMapper tableMapper,
            AliasMapper aliasMapper) throws SqlTemplateException {
        this.template = template;
        this.modelBuilder = modelBuilder;
        this.table = table;
        this.tableMapper = tableMapper;
        this.aliasMapper = aliasMapper;
        this.model = modelBuilder.build(table.type(), false);
    }

    /**
     * Returns the root table of this query model.
     *
     * <p>The returned table represents the primary table involved in the query and is used as the anchor for alias
     * resolution, column qualification, and model construction.</p>
     *
     * @return the aliased root table of the query.
     */
    @Override
    public AliasedTable getTable() {
        return table;
    }

    /**
     * Returns the columns to be selected for the root table, according to the specified selection mode.
     *
     * <p>The selection mode determines whether only primary key columns, only declared columns, or all nested columns
     * are included.</p>
     *
     * @param mode the selection mode that controls which columns are returned.
     * @return the list of column expressions for the root table.
     */
    @Override
    public List<ColumnExpression> getColumns(SelectMode mode) {
        return getColumns(table.type(), mode);
    }

    /**
     * Returns the columns to be selected for the specified table type, according to the given selection mode.
     *
     * <p>If the requested table type differs from the root table, a corresponding model is resolved or built to
     * determine the correct column set.</p>
     *
     * @param table the table type for which columns should be returned.
     * @param mode  the selection mode that controls which columns are included.
     * @return the list of column expressions for the specified table type.
     */
    @Override
    public List<ColumnExpression> getColumns(Class<? extends Data> table, SelectMode mode) {
        return getColumns(table, mode, FetchPlan.NONE);
    }

    /**
     * Returns the columns to be selected for the specified table type, with the references the plan resolves selected
     * as the referenced table's columns.
     *
     * <p>A resolved reference contributes its target's columns, which resolve against the join derivation materialized
     * for that path. Only NESTED expands: it is the mode that materializes a record, and a reference that is not
     * materialized cannot hold one.</p>
     *
     * @param table the table type for which columns should be returned.
     * @param mode  the selection mode that controls which columns are included.
     * @param fetchPlan the references the query resolves as part of the statement.
     * @return the list of column expressions for the specified table type.
     * @since 1.13
     */
    @Override
    public List<ColumnExpression> getColumns(Class<? extends Data> table, SelectMode mode,
                                             FetchPlan fetchPlan) {
        try {
            FetchPlan effectivePlan = mode == SelectMode.NESTED ? fetchPlan : FetchPlan.NONE;
            var m = effectivePlan.isEmpty() && model.type() == table
                    ? model
                    : modelBuilder.build(table, false, effectivePlan);
            return switch (mode) {
                case PK -> m.declaredColumns().stream().filter(Column::primaryKey).map(this::toColumnExpression).toList();
                case DECLARED -> m.declaredColumns().stream().map(this::toColumnExpression).toList();
                case NESTED -> m.columns().stream().map(this::toColumnExpression).toList();
            };
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    /**
     * Resolves the metamodel to be used for an object-based expression.
     *
     * <p>The metamodel may be explicitly provided by the expression or inferred from the runtime object type,
     * reference type, or primary key value.</p>
     *
     * @param objectExpression the expression containing the object reference.
     * @return the resolved metamodel.
     * @throws SqlTemplateException if the metamodel cannot be uniquely determined.
     */
    private Metamodel<?, ?> getMetamodel(ObjectExpression objectExpression)
            throws SqlTemplateException {
        Metamodel<?, ?> metamodel = objectExpression.metamodel();
        if (metamodel != null) {
            return metamodel;
        }
        for (var object : getObjectIterable(objectExpression.object())) {
            var type = switch (object) {
                case Ref<?> ref -> ref.type();
                case Data data -> data.getClass();
                default -> null;
            };
            if (type != null) {
                if (model.type().isAssignableFrom(type)
                        || (isJoinedEntity(type) && type.isAssignableFrom(model.type()))) {
                    // Also works for sealed entities and for Ref<SealedParent> used against a concrete
                    // subtype table in joined table inheritance.
                    return model.getPrimaryKeyMetamodel().orElseThrow();
                }
                if (tableMapper.isUnique(type)) {
                    var m = model.findMetamodel(type);
                    if (m.isPresent()) {
                        return m.get();
                    } else {
                        return modelBuilder.build(type, true).getPrimaryKeyMetamodel().orElseThrow();
                    }
                }
                if (type == model.primaryKeyType()) {
                    // Entity-typed primary key: the object is the root's primary key value (e.g., a junction
                    // table keyed by an entity whose table also appears elsewhere in the join graph). Mirrors
                    // the primary-key fallback for scalar values below.
                    return model.getPrimaryKeyMetamodel().orElseThrow();
                }
                throw new SqlTemplateException("Cannot uniquely identify object in expression: multiple matches found for type %s. Ensure the expression resolves to a single, unambiguous metamodel path.".formatted(type.getSimpleName()));
            }
            if (isPrimaryKeyValue(object, model.primaryKeyType())) {
                return model.getPrimaryKeyMetamodel().orElseThrow();
            }
            throw new SqlTemplateException("Cannot identify object in expression: no matching metamodel path found for %s. Ensure the expression references a valid field or relationship defined in the entity model.".formatted(object.getClass().getSimpleName()));
        }
        // We cannot check the metamodel in case of an empty list. In this case, we return a root metamodel and deal
        // with the empty list later.
        return model.getPrimaryKeyMetamodel()
                .orElseThrow(() -> new SqlTemplateException("Cannot identify object in expression: no matching metamodel path found. Ensure the expression references a valid field or relationship defined in the entity model."));
    }

    /**
     * Compiles the given expression into its SQL representation.
     *
     * <p>This method resolves the expression type and delegates to the appropriate compilation strategy. Any template
     * placeholders or object-based expressions are converted into SQL fragments using the provided compiler.</p>
     *
     * @param expression the expression to compile.
     * @param compiler   the compiler responsible for producing SQL fragments.
     * @return the compiled SQL fragment representing the expression.
     */
    @Override
    public String compileExpression(Expression expression, TemplateCompiler compiler) {
        try {
            return switch (expression) {
                case TemplateExpression it -> compileTemplateExpression(it.template(), compiler);
                case ObjectExpression it -> compileObjectExpression(getMetamodel(it), it.operator(), it.object(), compiler);
            };
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    /**
     * Binds all parameters required by the given expression to the provided binder.
     *
     * <p>The binding order is guaranteed to match the order used during compilation of the same expression. Nested
     * expressions and object-based expressions are handled recursively.</p>
     *
     * @param expression the expression whose parameters should be bound.
     * @param binder     the binder responsible for collecting parameter values.
     */
    @Override
    public void bindExpression(Expression expression, TemplateBinder binder) {
        try {
            switch (expression) {
                case TemplateExpression it -> bindTemplateExpression(it.template(), binder);
                case ObjectExpression it -> bindObjectExpression(getMetamodel(it), it.operator(), it.object(), binder);
            }
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    /**
     * Compiles a {@link TemplateString} into an SQL fragment.
     *
     * <p>Template fragments are concatenated while embedded values are resolved into SQL elements, parameters,
     * aliases, or nested expressions.</p>
     *
     * @param stringTemplate the template to compile.
     * @param compiler       the compiler used to generate SQL fragments.
     * @return the compiled SQL fragment.
     * @throws SqlTemplateException if an unsupported value is encountered.
     */
    private String compileTemplateExpression(TemplateString stringTemplate, TemplateCompiler compiler) throws SqlTemplateException{
        var fragments = stringTemplate.fragments();
        var values = stringTemplate.values();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            String fragment = fragments.get(i);
            parts.add(fragment);
            if (i < values.size()) {
                Object resolved = resolveElements(values.get(i));
                switch (resolved) {
                    case Stream<?> ignore -> throw new SqlTemplateException("Stream is not supported in expressions. Collect the Stream into a List before passing it.");
                    case Query ignore -> throw new SqlTemplateException("Query is not supported in expressions. Use a QueryBuilder subquery instead.");
                    case Expression it -> parts.add(compileExpression(it, compiler));
                    case Ref<?> it -> parts.add(compileExpression(new ObjectExpression(it), compiler));
                    case Data it -> parts.add(compileExpression(new ObjectExpression(it), compiler));
                    case Class<?> it -> parts.add(compiler.compile(alias(REFLECTION.getDataType(it))));
                    case Object it when REFLECTION.isSupportedType(it) -> parts.add(compiler.compile(alias(REFLECTION.getDataType(it))));
                    case Element it -> parts.add(compiler.compile(it));
                    default -> parts.add(compiler.compile(param(resolved)));
                }
            }
        }
        return String.join("", parts);
    }

    /**
     * Binds all parameters required by a {@link TemplateString}.
     *
     * <p>The binding order matches the order used during compilation of the same template.</p>
     *
     * @param stringTemplate the template whose parameters should be bound.
     * @param binder         the binder collecting parameter values.
     * @throws SqlTemplateException if an unsupported value is encountered.
     */
    private void bindTemplateExpression(TemplateString stringTemplate, TemplateBinder binder) throws SqlTemplateException{
        for (var value : stringTemplate.values()) {
            Object resolved = resolveElements(value);
            switch (resolved) {
                case Stream<?> ignore -> throw new SqlTemplateException("Stream is not supported in expressions. Collect the Stream into a List before passing it.");
                case Query ignore -> throw new SqlTemplateException("Query is not supported in expressions. Use a QueryBuilder subquery instead.");
                case Expression it -> bindExpression(it, binder);
                case Ref<?> it -> bindExpression(new ObjectExpression(it), binder);
                case Data it -> bindExpression(new ObjectExpression(it), binder);
                case Class<?> it -> binder.bind(alias(REFLECTION.getDataType(it)));
                case Object it when REFLECTION.isSupportedType(it) -> binder.bind(alias(REFLECTION.getDataType(it)));
                case Element it -> binder.bind(it);
                default -> binder.bind(param(resolved));
            }
        }
    }

    /**
     * Transforms or casts the specified {@code object} into an iterable based on its type.
     *
     * @param object the object to transform.
     * @return the iterable based on the object type.
     * @throws SqlTemplateException if the object cannot be transformed or cast into an iterable.
     */
    private Iterable<?> getObjectIterable(@Nullable Object object) throws SqlTemplateException {
        return switch (object) {
            case null -> throw new SqlTemplateException("Null value not allowed as a direct parameter. To check for NULL, use the IS_NULL operator instead (e.g., where(field, IS_NULL)).");
            case Object[] a -> asList(a);   // Use this instead of List.of() to allow null values.
            case Iterable<?> i -> i;
            case BindVars ignore -> throw new SqlTemplateException("BindVars is not allowed in this context. Use BindVars with the where(BindVars) or values(BindVars) template methods instead.");
            case Stream<?> ignore -> throw new SqlTemplateException("Stream is not allowed in this context. Collect the Stream into a List, or use Iterable or varargs instead.");
            case TemplateString ignore -> throw new SqlTemplateException("TemplateString is not allowed in this context. Use the expression() builder method instead.");
            default -> List.of(object); // Not expected at the moment though.
        };
    }

    /**
     * Compiles an object-based expression into an SQL predicate.
     *
     * <p>The object may represent a primary key value, entity instance, reference, or a collection
     * thereof. Multi-column and multi-value expressions are handled transparently.</p>
     *
     * @param metamodel the metamodel describing the target columns.
     * @param operator  the operator used to format the predicate.
     * @param object    the object providing the comparison value(s).
     * @param compiler  the compiler used to generate SQL fragments.
     * @return the compiled SQL predicate.
     * @throws SqlTemplateException if the object cannot be mapped to columns or values.
     */
    private String compileObjectExpression(Metamodel<?, ?> metamodel,
                                           Operator operator,
                                           Object object,
                                           TemplateCompiler compiler) throws SqlTemplateException {
        //noinspection DuplicatedCode
        Model<Data, ?> model = getModel(metamodel);
        // A predicate column is looked up in the referenced table's model, which drops the path the reference was
        // navigated from. Keep it so the column resolves against the joined occurrence rather than the root one.
        String crossingPath = crossesReference(metamodel) ? metamodel.path() : null;
        List<SequencedMap<String, Object>> multiValues = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        String column = null;
        for (var o : getObjectIterable(object)) {
            //noinspection DuplicatedCode
            SequencedMap<String, Object> valueMap = new LinkedHashMap<>();
            var derivedObject = switch (o) {
                case Ref<?> ref -> ref.id();
                case Data data -> data;
                case Object it -> it;
            };
            //noinspection unchecked
            model.forEachValue((Metamodel<Data, ?>) metamodel, derivedObject,
                    (k, v) -> valueMap.put(toFullyQualifiedColumn(k, crossingPath), v));
            if (compiler.isVersionAware()) {
                if (o instanceof Data data) {
                    var versionColumn = model.declaredColumns().stream()
                            .filter(Column::version)
                            .findFirst()
                            .orElseThrow();
                    model.forEachValue(List.of(versionColumn), data,
                            (k, v) -> valueMap.put(toFullyQualifiedColumn(k, crossingPath), v));
                } else {
                    throw new SqlTemplateException("Data object expected for version-aware statement. When using optimistic locking, the WHERE clause value must be a Data instance that contains the version field.");
                }
            }
            if (multiValues.isEmpty() && valueMap.size() == 1) {
                var entry = valueMap.entrySet().iterator().next();
                var k = entry.getKey();
                if (column != null && !column.equals(k)) {
                    throw new SqlTemplateException("Multiple columns specified by WHERE clause argument: %s and %s.".formatted(column, k));
                }
                placeholders.add(compiler.mapParameter(TextMatch.bindValue(operator, entry.getValue(), metamodel, compiler.dialect())));
                column = k;
            } else {
                TextMatch.requireSingleColumn(operator, metamodel);
                if (column != null) {
                    throw new SqlTemplateException("Multiple columns specified by WHERE clause arguments. When passing multiple objects, each must resolve to the same single column.");
                }
                multiValues.add(valueMap);
            }
        }
        if (multiValues.isEmpty() && column == null) {
            column = toFullyQualifiedColumn(model.getSingleColumn(metamodel), crossingPath);
        }
        try {
            return ColumnComparison.render(operator, column, placeholders, multiValues, compiler::mapParameter, compiler.dialect());
        } catch (IllegalArgumentException e) {
            throw new SqlTemplateException(e);
        }
    }

    /**
     * Binds parameter values for an object-based expression.
     *
     * <p>The binding order matches the placeholder order produced during compilation.</p>
     *
     * @param metamodel the metamodel describing the target columns.
     * @param object    the object providing the bind values.
     * @param binder    the binder collecting parameter values.
     * @throws SqlTemplateException if binding fails or versioning rules are violated.
     */
    /**
     * Collects the column values of an object expression. Single-column expressions, the vast majority, bind their
     * one value straight from the first-column slots; the name-keyed map is materialized only when a second column
     * appears, since only multi-column expressions need it.
     */
    private static final class ObjectExpressionValues implements BiConsumer<Column, Object> {
        private Column firstColumn;
        private Object firstValue;
        private SequencedMap<String, Object> map;
        private int size;

        void reset() {
            firstColumn = null;
            firstValue = null;
            map = null;
            size = 0;
        }

        @Override
        public void accept(Column column, Object value) {
            if (size++ == 0) {
                firstColumn = column;
                firstValue = value;
                return;
            }
            if (map == null) {
                map = new LinkedHashMap<>();
                map.put(firstColumn.name(), firstValue);
            }
            map.put(column.name(), value);
        }

        /** Hands off the collected values as a map; ownership transfers to the caller. */
        SequencedMap<String, Object> toMap() {
            if (map == null) {
                map = new LinkedHashMap<>();
                if (size == 1) {
                    map.put(firstColumn.name(), firstValue);
                }
            }
            return map;
        }
    }

    private void bindObjectExpression(Metamodel<?, ?> metamodel,
                                      Operator operator,
                                      Object object,
                                      TemplateBinder binder) throws SqlTemplateException {
        var model = getModel(metamodel);
        List<SequencedMap<String, Object>> multiValues = null;
        var values = new ObjectExpressionValues();
        for (var o : getObjectIterable(object)) {
            var derivedObject = switch (o) {
                case Ref<?> ref -> ref.id();
                case Data data -> data;
                case Object it -> it;
            };
            values.reset();
            //noinspection unchecked
            model.forEachValue((Metamodel<Data, ?>) metamodel, derivedObject, values);
            if (binder.isVersionAware()) {
                if (o instanceof Data data) {
                    model.forEachValue(List.of(versionColumn(model)), data, values);
                } else {
                    throw new SqlTemplateException("Data object expected for version-aware statement. When using optimistic locking, the WHERE clause value must be a Data instance that contains the version field.");
                }
            }
            if ((multiValues == null || multiValues.isEmpty()) && values.size == 1) {
                binder.bindParameter(TextMatch.bindValue(operator, values.firstValue, metamodel, template.dialect()));
            } else {
                TextMatch.requireSingleColumn(operator, metamodel);
                if (multiValues == null) {
                    multiValues = new ArrayList<>();
                }
                multiValues.add(values.toMap());
            }
        }
        if (multiValues != null && !multiValues.isEmpty()) {
            bindMultiValues(operator, multiValues, binder);
        }
    }

    /** Returns the version column of the given model. */
    private static Column versionColumn(Model<?, ?> model) {
        for (var column : model.declaredColumns()) {
            if (column.version()) {
                return column;
            }
        }
        throw new NoSuchElementException("No value present");
    }

    /**
     * Binds all values of a multi-column expression in the correct order.
     *
     * <p>The binding order is determined by the dialect's multi-column expression method, ensuring it matches the
     * placeholder order produced during compilation.</p>
     *
     * @param operator    the operator that was used during compilation.
     * @param multiValues the column-to-value mappings to bind.
     * @param binder      the binder collecting parameter values.
     */
    private void bindMultiValues(Operator operator,
                                 List<SequencedMap<String, Object>> multiValues,
                                 TemplateBinder binder) {
        try {
            template.dialect().multiColumnExpression(operator, multiValues, value -> {
                binder.bindParameter(value);
                return "?";
            });
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    /**
     * Determines whether the given value matches the expected primary key type.
     *
     * @param value  the value to test.
     * @param pkType the primary key type.
     * @return {@code true} if the value represents a primary key value.
     */
    private boolean isPrimaryKeyValue(Object value, @Nullable Class<?> pkType) {
        if (pkType == null) {
            return false;
        }
        if (pkType == value.getClass()) {
            return true;
        }
        return pkType.isPrimitive() && isPrimitiveCompatible(value, pkType);
    }

    /**
     * Tests if the specified object is compatible with the specified primitive class.
     *
     * @param o the object to test.
     * @param clazz the primitive class to test against.
     * @return {@code true} if the object is compatible with the primitive class, {@code false} otherwise.
     */
    private static boolean isPrimitiveCompatible(Object o, Class<?> clazz) {
        if (clazz == int.class) return o instanceof Integer;
        if (clazz == long.class) return o instanceof Long;
        if (clazz == boolean.class) return o instanceof Boolean;
        if (clazz == byte.class) return o instanceof Byte;
        if (clazz == char.class) return o instanceof Character;
        if (clazz == short.class) return o instanceof Short;
        if (clazz == float.class) return o instanceof Float;
        if (clazz == double.class) return o instanceof Double;
        return false;
    }

    /**
     * Resolves a template value into a form that can be processed by the compiler or binder.
     *
     * <p>This method transforms known template value types into their corresponding internal representations
     * (e.g., {@link Subqueryable} to {@link Subquery}, column-level {@link Metamodel} to {@link Column}), and rejects
     * invalid types such as {@link TemplateString} and {@link Stream}.</p>
     *
     * <p>{@link Data} and {@link Ref} instances pass through unchanged and are handled by the caller's switch
     * (compiled via {@code ObjectExpression}, which resolves columns through the model). Other values (scalars,
     * {@link Element} instances, etc.) also pass through unchanged and are compiled or bound by the caller.</p>
     *
     * @param value the value to resolve.
     * @return the resolved value.
     * @throws SqlTemplateException if the value is invalid in this context.
     */
    private @Nullable Object resolveElements(@Nullable Object value) throws SqlTemplateException {
        return switch (value) {
            case TemplateString ignore -> throw new SqlTemplateException("TemplateString is not allowed as a string template value.");
            case Stream<?> ignore -> throw new SqlTemplateException("Stream is not supported as a string template value. Collect the Stream into a List before passing it.");
            case Subqueryable t -> new Subquery(t.getSubquery(), true);
            case Navigable<?, ?> m when m.isColumn() -> new Elements.Column(toColumnMetamodel(m), CASCADE);
            case Navigable<?, ?> ignore -> throw new SqlTemplateException("Path does not reference a column. Use a column-level path (e.g., User_.name) rather than a table-level path.");
            case null, default -> value;
        };
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
     * Resolves the {@link Model} instance corresponding to the given metamodel.
     *
     * @param metamodel the metamodel describing the table.
     * @param <T>       the entity type.
     * @return the resolved model.
     */
    private <T extends Data> Model<T, ?> getModel(Metamodel<?, ?> metamodel) {
        if (model.type() == metamodel.root() && !crossesReference(metamodel)) {
            //noinspection unchecked
            return (Model<T, ?>) model;
        }
        // For sealed entity models, the metamodel root may be the first permitted subclass (e.g., Car)
        // while model.type() is the sealed interface (e.g., Vehicle).
        if (model.type().isSealed() && isSealedEntity(model.type()) && model.type().isAssignableFrom(metamodel.root())) {
            //noinspection unchecked
            return (Model<T, ?>) model;
        }
        try {
            //noinspection unchecked
            return (Model<T, ?>) modelBuilder.build(metamodel.tableType(), false);
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    /**
     * Returns whether the given metamodel navigates through a Ref foreign key. Such a metamodel resolves in the
     * referenced table's model rather than the root model, because selecting the root keeps the reference as its
     * foreign key column instead of expanding the referenced entity into the root's columns.
     */
    private static boolean crossesReference(Metamodel<?, ?> metamodel) {
        String path = metamodel.path();
        if (path.isEmpty()) {
            return false;
        }
        Class<?> current = metamodel.root();
        try {
            for (String segment : path.split("\\.")) {
                RecordField field = getRecordField(current, segment);
                if (Ref.class.isAssignableFrom(field.type())) {
                    return true;
                }
                current = field.type();
            }
        } catch (SqlTemplateException e) {
            return false;
        }
        return false;
    }

    /**
     * Converts the specified column into a {@link ColumnExpression} using the current alias resolution rules.
     *
     * <p>The resolved alias depends on the column's metamodel path and the joins that were introduced while building
     * the query model. An exception is thrown if no suitable alias can be found.</p>
     *
     * @param column the column to convert.
     * @return the column expression with alias and optional SQL expression override.
     */
    @Override
    public ColumnExpression toColumnExpression(Column column) {
        return toColumnExpression(column, null);
    }

    /**
     * Converts the specified column into a {@link ColumnExpression}, resolving its alias at {@code crossingPath} when
     * the column was reached by navigating a reference.
     *
     * <p>A column that is reached through a reference is looked up in the referenced table's model, where its own path
     * is empty. When that table is also the type the query is rooted at, the column would otherwise resolve to the root
     * occurrence, which is the wrong one for a table joined to itself. Resolving at the path the reference was
     * navigated from selects the occurrence that the join was generated for.</p>
     */
    private ColumnExpression toColumnExpression(Column column, @Nullable String crossingPath) {
        try {
            var metamodel = column.metamodel();
            String alias = crossingPath == null || crossingPath.isEmpty()
                    ? null
                    : aliasMapper.findAlias(metamodel.tableType(), crossingPath, INNER).orElse(null);
            boolean isRootTable = metamodel.root() == model.type();
            if (!isRootTable && model.type().isSealed() && isSealedEntity(model.type())
                    && metamodel.fieldPath().isEmpty()) {
                // For sealed entity models, the metamodel root is the first permitted subclass (e.g., Car.class)
                // while model.type() is the sealed interface (e.g., Vehicle.class). Base table columns use the
                // root metamodel (empty fieldPath) and should resolve to the root table alias. Extension columns
                // in JOINED inheritance have non-empty fieldPath and are resolved via the alias mapper instead.
                Class<?>[] permitted = model.type().getPermittedSubclasses();
                if (permitted != null) {
                    for (Class<?> sub : permitted) {
                        if (sub == metamodel.root()) {
                            isRootTable = true;
                            break;
                        }
                    }
                }
            }
            if (alias != null) {
                // Resolved at the reference-crossing path above.
            } else if (isRootTable && metamodel.path().isEmpty()) {
                alias = table.alias();
            } else {
                String lookupPath = metamodel.path();
                if (!isRootTable && metamodel.root() != model.type()) {
                    // The column's metamodel is rooted at a different table than the query model root
                    // (typically because the SELECT target differs from the FROM table). The alias mapper
                    // has paths relative to the query model root, so prepend the registered path of the
                    // column root before lookup.
                    String rootPrefix = aliasMapper.findRegisteredPath(metamodel.root()).orElse(null);
                    if (rootPrefix != null && !rootPrefix.isEmpty()) {
                        lookupPath = lookupPath.isEmpty() ? rootPrefix : rootPrefix + "." + lookupPath;
                    }
                }
                alias = aliasMapper.findAlias(metamodel.tableType(), lookupPath, INNER).orElse(null);
            }
            if (alias == null) {
                alias = aliasMapper.findAlias(metamodel.tableType(), null, INNER).orElse(null);
            }
            if (alias == null) {
                throw new SqlTemplateException("Cannot find alias for column %s: %s is not part of this query rooted at %s. Join the table or correct the metamodel path.".formatted(
                        column.qualifiedName(template.dialect()), metamodel.tableType().getSimpleName(), model.type().getSimpleName()));
            }
            // For JOINED sealed entities without a discriminator, the discriminator column (index 1)
            // is replaced by a CASE expression that resolves the concrete type from extension table PKs.
            if (column.index() == 1 && !column.insertable() && model.type().isSealed()
                    && isJoinedEntity(model.type()) && !hasDiscriminator(model.type())) {
                String caseExpression = buildDiscriminatorCaseExpression(model.type());
                return new ColumnExpression(column.type(), column.qualifiedName(template.dialect()), alias, column.index(), caseExpression);
            }
            return new ColumnExpression(column.type(), column.qualifiedName(template.dialect()), alias, column.index());
        } catch (SqlTemplateException e) {
            throw new UncheckedSqlTemplateException(e);
        }
    }

    /**
     * Builds a CASE expression that resolves the concrete type for a JOINED sealed entity without a
     * discriminator column. The expression checks which extension table has a matching row via LEFT JOIN.
     *
     * <p>Example output: {@code CASE WHEN jc.id IS NOT NULL THEN 'JoinedCat' WHEN jd.id IS NOT NULL THEN 'JoinedDog' END}</p>
     */
    private String buildDiscriminatorCaseExpression(Class<?> sealedType) throws SqlTemplateException {
        Class<?>[] permitted = sealedType.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            throw new SqlTemplateException("Sealed type %s has no permitted subclasses.".formatted(sealedType.getSimpleName()));
        }
        DiscriminatorType discriminatorType = getDiscriminatorType(sealedType);
        StringBuilder sb = new StringBuilder("CASE");
        for (Class<?> subtype : permitted) {
            // Find the alias for this subtype's extension table.
            @SuppressWarnings("unchecked")
            Class<? extends Data> subtypeData = (Class<? extends Data>) subtype;
            // In JOINED inheritance, extension columns use a metamodel path like "subtypeFieldName" where
            // the root is the subtype. Try to find the alias for the extension table.
            String extAlias = aliasMapper.findAlias(subtypeData, null, INNER).orElse(null);
            if (extAlias == null) {
                throw new SqlTemplateException("Cannot find alias for extension table of subtype: %s.".formatted(subtype.getSimpleName()));
            }
            // Get the PK column name from the subtype's model.
            var subtypeModel = modelBuilder.build(subtypeData, false);
            String pkColumnName = subtypeModel.declaredColumns().stream()
                    .filter(Column::primaryKey)
                    .findFirst()
                    .map(c -> c.qualifiedName(template.dialect()))
                    .orElseThrow(() -> new SqlTemplateException("No PK column in %s model.".formatted(subtype.getSimpleName())));
            Object discriminatorValue = RecordReflection.getDiscriminatorValue(subtype, sealedType);
            String thenClause = switch (discriminatorType) {
                case INTEGER -> " WHEN %s.%s IS NOT NULL THEN %s".formatted(extAlias, pkColumnName, discriminatorValue);
                case STRING, CHAR -> " WHEN %s.%s IS NOT NULL THEN '%s'".formatted(extAlias, pkColumnName, discriminatorValue);
            };
            sb.append(thenClause);
        }
        sb.append(" END");
        return sb.toString();
    }

    /**
     * Returns the fully qualified column name including its table alias.
     *
     * @param column the column to qualify.
     * @return the fully qualified column name.
     */
    private String toFullyQualifiedColumn(Column column) {
        return toFullyQualifiedColumn(column, null);
    }

    private String toFullyQualifiedColumn(Column column, @Nullable String crossingPath) {
        return toColumnExpression(column, crossingPath).toSql();
    }
}
