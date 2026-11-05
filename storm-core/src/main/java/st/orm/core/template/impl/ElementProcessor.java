package st.orm.core.template.impl;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import st.orm.Element;
import st.orm.SqlTemplateException;
import st.orm.core.template.TemplateString;

/**
 * Processes a single {@link Element} type within a SQL template compilation pipeline.
 *
 * <p>An {@code ElementProcessor} is responsible for two things:
 * <ul>
 *   <li>Compiling an element into an {@link CompiledElement}.</li>
 *   <li>Binding any values (or other runtime state) after compilation via a {@link TemplateBinder}.</li>
 * </ul>
 *
 * <p>Implementations should be deterministic: for a given element and compiler state,
 * {@link #compile(Element, TemplateCompiler)} should produce the same {@link CompiledElement}. If compilation depends on
 * additional state, that state should be reflected in the compilation key returned by
 * {@link #getCompilationKey(Element)}.
 *
 * @param <E> the element type handled by this processor.
 */
interface ElementProcessor<E extends Element> {

    /**
     * Returns a key that represents the compiled shape of the given element.
     *
     * <p>The compilation key is used for caching compiled results. It must include all fields that can affect the
     * compilation output (SQL text, emitted fragments, placeholder shape, etc.). The key is compared using
     * value-based equality, so it should be immutable and implement stable {@code equals}/{@code hashCode}.</p>
     *
     * <p>If this method returns {@code null} for any element in a template, the compiled result is considered
     * non-cacheable and the template must be recompiled each time it is requested.</p>
     *
     * @param element the element to compute a key for.
     * @return an immutable key for caching, or {@code null} if the element (or its compilation) cannot be cached.
     * @throws SqlTemplateException if the key generation fails.
     */
    default @Nullable Object getCompilationKey(E element) throws SqlTemplateException {
        return null;
    }

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
     * @param element the element to compute a key for.
     * @param keyGenerator a function that generates compilation keys for sub-templates.
     * @return an immutable key for caching, or {@code null} if the element or its compilation cannot be cached.
     * @throws SqlTemplateException if the key generation fails.
     */
    default @Nullable Object getCompilationKey(
            E element,
            Function<TemplateString, @Nullable Object> keyGenerator
    ) throws SqlTemplateException {
        return getCompilationKey(element);
    }

    /**
     * Returns a key that identifies the element's shape: its structure with collection arity erased.
     *
     * <p>The compilation key distinguishes collection parameters by size, because the number of placeholders is
     * part of the compiled statement. The shape key does not, so statements that differ only in how far a
     * collection expanded share it. The default matches the compilation key; processors whose key encodes arity
     * override this.</p>
     *
     * @param element the element to compute a key for.
     * @param keyGenerator a function that generates shape keys for sub-templates.
     * @return an immutable shape key, or {@code null} if the element has none.
     * @throws SqlTemplateException if the key generation fails.
     * @since 1.13
     */
    default @Nullable Object getShapeKey(
            E element,
            Function<TemplateString, @Nullable Object> keyGenerator
    ) throws SqlTemplateException {
        return getCompilationKey(element, keyGenerator);
    }

    /**
     * Compiles the given element into an {@link CompiledElement}.
     *
     * <p>This method is responsible for producing the compile-time representation of the element. It must not perform
     * runtime binding. Any binding should be deferred to {@link #bind(Element, TemplateBinder, BindHint)}.</p>
     *
     * @param element the element to compile.
     * @param compiler the active compiler context.
     * @return the compiled result for this element.
     * @throws SqlTemplateException if compilation fails.
     */
    CompiledElement compile(E element, TemplateCompiler compiler) throws SqlTemplateException;

    /**
     * Performs post-processing after compilation, typically binding runtime values for the element.
     *
     * <p>This method is called after the element has been compiled. Typical responsibilities include binding
     * parameters, registering bind variables, or applying runtime-only adjustments that must not affect the compiled
     * SQL shape.</p>
     *
     * @param element the element that was compiled.
     * @param binder the binder used to bind runtime values.
     * @param bindHint the bind hint for the element, providing additional context for binding.
     * @throws SqlTemplateException if binding fails.
     */
    void bind(E element, TemplateBinder binder, BindHint bindHint) throws SqlTemplateException;
}
