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
import static st.orm.core.template.impl.RecordReflection.hasDiscriminator;
import static st.orm.core.template.impl.RecordReflection.isJoinedEntity;
import static st.orm.core.template.impl.RecordReflection.isSealedEntity;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Stream;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Discriminator.DiscriminatorType;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.Ref;
import st.orm.SelectMode;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.Query;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplateException;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Expression;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.Subquery;
import st.orm.core.template.impl.Elements.TemplateExpression;

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
            @Nonnull SqlTemplate template,
            @Nonnull ModelBuilder modelBuilder,
            @Nonnull AliasedTable table,
            @Nonnull TableMapper tableMapper,
            @Nonnull AliasMapper aliasMapper) throws SqlTemplateException {
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
    public List<ColumnExpression> getColumns(@Nonnull SelectMode mode) {
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
    public List<ColumnExpression> getColumns(@Nonnull Class<? extends Data> table, @Nonnull SelectMode mode) {
        try {
            var m = model.type() == table ? model : modelBuilder.build(table, false);
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
    private Metamodel<?, ?> getMetamodel(@Nonnull ObjectExpression objectExpression)
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
    public String compileExpression(@Nonnull Expression expression, @Nonnull TemplateCompiler compiler) {
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
    public void bindExpression(@Nonnull Expression expression, @Nonnull TemplateBinder binder) {
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
    private String compileTemplateExpression(@Nonnull TemplateString stringTemplate, @Nonnull TemplateCompiler compiler) throws SqlTemplateException{
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
    private void bindTemplateExpression(@Nonnull TemplateString stringTemplate, @Nonnull TemplateBinder binder) throws SqlTemplateException{
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
    private String compileObjectExpression(@Nonnull Metamodel<?, ?> metamodel,
                                           @Nonnull Operator operator,
                                           @Nonnull Object object,
                                           @Nonnull TemplateCompiler compiler) throws SqlTemplateException {
        //noinspection DuplicatedCode
        Model<Data, ?> model = getModel(metamodel);
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
                    (k, v) -> valueMap.put(toFullyQualifiedColumn(k), v));
            if (compiler.isVersionAware()) {
                if (o instanceof Data data) {
                    var versionColumn = model.declaredColumns().stream()
                            .filter(Column::version)
                            .findFirst()
                            .orElseThrow();
                    model.forEachValue(List.of(versionColumn), data,
                            (k, v) -> valueMap.put(toFullyQualifiedColumn(k), v));
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
                placeholders.add(compiler.mapParameter(entry.getValue()));
                column = k;
            } else {
                if (column != null) {
                    throw new SqlTemplateException("Multiple columns specified by WHERE clause arguments. When passing multiple objects, each must resolve to the same single column.");
                }
                multiValues.add(valueMap);
            }
        }
        if (!multiValues.isEmpty()) {
            return compileMultiValues(operator, multiValues, compiler);
        }
        if (column == null) {
            column = toFullyQualifiedColumn(model.getSingleColumn(metamodel));
        }
        try {
            return operator.format(column, placeholders.toArray(new String[0]));
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
    private void bindObjectExpression(@Nonnull Metamodel<?, ?> metamodel,
                                      @Nonnull Operator operator,
                                      @Nonnull Object object,
                                      @Nonnull TemplateBinder binder) throws SqlTemplateException {
        var model = getModel(metamodel);
        List<SequencedMap<String, Object>> multiValues = new ArrayList<>();
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
                    (k, v) -> valueMap.put(k.name(), v));
            if (binder.isVersionAware()) {
                if (o instanceof Data data) {
                    var versionColumn = model.declaredColumns().stream()
                            .filter(Column::version)
                            .findFirst()
                            .orElseThrow();
                    model.forEachValue(List.of(versionColumn), data,
                            (k, v) -> valueMap.put(k.name(), v));
                } else {
                    throw new SqlTemplateException("Data object expected for version-aware statement. When using optimistic locking, the WHERE clause value must be a Data instance that contains the version field.");
                }
            }
            if (multiValues.isEmpty() && valueMap.size() == 1) {
                binder.bindParameter(valueMap.values().iterator().next());
            } else {
                multiValues.add(valueMap);
            }
        }
        if (!multiValues.isEmpty()) {
            bindMultiValues(operator, multiValues, binder);
        }
    }

    /**
     * Compiles a multi-column, multi-row value set into a dialect-specific SQL fragment.
     *
     * @param operator    the operator to apply.
     * @param multiValues the list of column-to-value mappings.
     * @param compiler    the compiler used to map parameters.
     * @return the compiled SQL fragment.
     * @throws SqlTemplateException if the dialect cannot handle the value set.
     */
    private String compileMultiValues(@Nonnull Operator operator,
                                      @Nonnull List<SequencedMap<String, Object>> multiValues,
                                      @Nonnull TemplateCompiler compiler) throws SqlTemplateException {
        return compiler.dialect().multiColumnExpression(operator, multiValues, compiler::mapParameter);
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
    private void bindMultiValues(@Nonnull Operator operator,
                                 @Nonnull List<SequencedMap<String, Object>> multiValues,
                                 @Nonnull TemplateBinder binder) {
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
    private boolean isPrimaryKeyValue(@Nonnull Object value, @Nullable Class<?> pkType) {
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
    private static boolean isPrimitiveCompatible(@Nonnull Object o, @Nonnull Class<?> clazz) {
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
    private Object resolveElements(@Nullable Object value) throws SqlTemplateException {
        return switch (value) {
            case TemplateString ignore -> throw new SqlTemplateException("TemplateString is not allowed as a string template value.");
            case Stream<?> ignore -> throw new SqlTemplateException("Stream is not supported as a string template value. Collect the Stream into a List before passing it.");
            case Subqueryable t -> new Subquery(t.getSubquery(), true);
            case Metamodel<?, ?> m when m.isColumn() -> new st.orm.core.template.impl.Elements.Column(m, CASCADE);
            case Metamodel<?, ?> ignore -> throw new SqlTemplateException("Metamodel does not reference a column. Use a column-level metamodel (e.g., User_.name) rather than a table-level metamodel.");
            case null, default -> value;
        };
    }

    /**
     * Resolves the {@link Model} instance corresponding to the given metamodel.
     *
     * @param metamodel the metamodel describing the table.
     * @param <T>       the entity type.
     * @return the resolved model.
     */
    private <T extends Data> Model<T, ?> getModel(@Nonnull Metamodel<?, ?> metamodel) {
        if (model.type() == metamodel.root()) {
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
     * Converts the specified column into a {@link ColumnExpression} using the current alias resolution rules.
     *
     * <p>The resolved alias depends on the column's metamodel path and the joins that were introduced while building
     * the query model. An exception is thrown if no suitable alias can be found.</p>
     *
     * @param column the column to convert.
     * @return the column expression with alias and optional SQL expression override.
     */
    @Override
    public ColumnExpression toColumnExpression(@Nonnull Column column) {
        try {
            var metamodel = column.metamodel();
            String alias;
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
            if (isRootTable && metamodel.path().isEmpty()) {
                alias = table.alias();
            } else {
                alias = aliasMapper.findAlias(metamodel.tableType(), metamodel.path(), INNER).orElse(null);
            }
            if (alias == null) {
                alias = aliasMapper.findAlias(metamodel.tableType(), null, INNER).orElse(null);
            }
            if (alias == null) {
                throw new SqlTemplateException("Cannot find alias for column: %s.".formatted(column.qualifiedName(template.dialect())));
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
    private String buildDiscriminatorCaseExpression(@Nonnull Class<?> sealedType) throws SqlTemplateException {
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
    private String toFullyQualifiedColumn(@Nonnull Column column) {
        return toColumnExpression(column).toSql();
    }
}
