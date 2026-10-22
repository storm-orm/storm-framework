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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import st.orm.Data;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.BindHint.NoBindHint;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.TemplateExpression;

final class CacheableProcessor implements ElementProcessor<Cacheable> {

    private static final int MAX_ARITY = 2;
    private static final Class<?> CONSTANT_SHAPE = Object.class;

    /**
     * Returns a compilation key for the given element, optionally delegating to the provided {@code keyGenerator}
     * to compute keys for nested or derived {@link TemplateString} instances.
     *
     * <p>This variant allows an element to derive its compilation key from a sub-template, or from a transformed
     * view of the original template, while still participating in the same compilation cache. Implementations may
     * invoke {@code keyGenerator} for any {@link TemplateString} that contributes to the compiled shape.</p>
     *
     * <p>If this method returns {@code null}, the element is treated as non-cacheable.</p>
     *
     * @param cacheable the element to compute a key for.
     * @param keyGenerator a function that generates compilation keys for sub-templates.
     * @return an immutable key for caching, or {@code null} if the element or its compilation cannot be cached.
     * @throws SqlTemplateException if the key generation fails.
     */
    @Override
    public Object getCompilationKey(
            Cacheable cacheable,
            Function<TemplateString, Object> keyGenerator
    ) throws SqlTemplateException {
        return key(cacheable, keyGenerator, false);
    }

    /**
     * Returns the shape key: the compilation key with collection arity erased, so statements that differ only in
     * how far a collection expanded share it.
     */
    @Override
    public Object getShapeKey(
            Cacheable cacheable,
            Function<TemplateString, Object> keyGenerator
    ) throws SqlTemplateException {
        return key(cacheable, keyGenerator, true);
    }

    private Object key(
            Cacheable cacheable,
            Function<TemplateString, Object> keyGenerator,
            boolean ignoreArity
    ) throws SqlTemplateException {
        return switch(cacheable.expression()) {
            case TemplateExpression it -> keyGenerator.apply(it.template());
            case ObjectExpression(var metamodel, var operator, var object, var indexable) -> {
                var objectShape = getObjectShape(object, ignoreArity);
                if (objectShape == null) {
                    yield null;
                }
                // Whether the comparison asks for an index is part of what it compiles to.
                if (metamodel == null) {
                    yield List.of(operator, objectShape, indexable);
                }
                yield List.of(metamodel, operator, objectShape, indexable);
            }
        };
    }

    /**
     * Returns the shape of the given object to the degree that it can impact the compiled SQL. For collections,
     * we will only allow a maximum of MAX_SHAPE_ARITY elements to be considered to prevent the cache from growing too
     * large.
     *
     * @param object the object to inspect.
     * @return the shape of the object.
     * @throws SqlTemplateException if the shape cannot be determined.
     */
    private static Object getObjectShape(Object object, boolean ignoreArity)
            throws SqlTemplateException {
        return switch (object) {
            case Collection<?> c -> {
                if (c.isEmpty()) {
                    yield ignoreArity ? List.of(ARITY_ERASED, CONSTANT_SHAPE) : List.of(0, CONSTANT_SHAPE);
                }
                if (ignoreArity) {
                    yield List.of(ARITY_ERASED, getElementShape(c));
                }
                if (c.size() > MAX_ARITY) {
                    yield null;
                }
                yield List.of(c.size(), getElementShape(c));
            }
            case Object[] a -> {
                if (a.length == 0) {
                    yield ignoreArity ? List.of(ARITY_ERASED, CONSTANT_SHAPE) : List.of(0, CONSTANT_SHAPE);
                }
                if (ignoreArity) {
                    yield List.of(ARITY_ERASED, getElementShape(Arrays.asList(a)));
                }
                if (a.length > MAX_ARITY) {
                    yield null;
                }
                yield List.of(a.length, getElementShape(Arrays.asList(a)));
            }
            case Iterable<?> ignore -> null;    // We cannot get the size of an iterable.
            default -> getTypeShape(object);
        };
    }

    /**
     * Returns the shape of a collection's elements, from its first non-null element; a NULL among the values of a
     * list binds as NULL, and a list of nothing else is a constant.
     */
    private static Class<?> getElementShape(Collection<?> elements) throws SqlTemplateException {
        for (var element : elements) {
            if (element != null) {
                return getTypeShape(element);
            }
        }
        return CONSTANT_SHAPE;
    }

    /** Marker taking the place of a collection's size in a shape key, so every arity shares the key. */
    private static final String ARITY_ERASED = "*";

    /**
     * Ref and Data instances may impact the shape of the compiled SQL. All other objects are considered constant.
     *
     * @param object the object to inspect.
     * @return the shape of the object.
     * @throws SqlTemplateException if the shape cannot be determined.
     */
    private static Class<?> getTypeShape(Object object) throws SqlTemplateException {
        return switch (object) {
            case null -> throw new SqlTemplateException("Null value not allowed as a direct parameter in a WHERE clause. To check for NULL, use the IS_NULL operator instead (e.g., where(field, IS_NULL)).");
            case Ref<?> ref -> ref.type();
            case Data data -> data.getClass();
            default -> CONSTANT_SHAPE;
        };
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Cacheable, TemplateBinder, BindHint)}.</p>
     *
     * @param cacheable the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     */
    @Override
    public CompiledElement compile(Cacheable cacheable, TemplateCompiler compiler)
            throws SqlTemplateException {
        return new CompiledElement(
                compiler.getQueryModel().compileExpression(cacheable.expression(), compiler),
                NoBindHint.INSTANCE);
    }

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param cacheable the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     */
    @Override
    public void bind(Cacheable cacheable, TemplateBinder binder, BindHint bindHint) {
        binder.getQueryModel().bindExpression(cacheable.expression(), binder);
    }
}
