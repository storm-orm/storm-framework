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

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import st.orm.AcceptAdvice;

/**
 * Decides whether the application accepts a piece of advice, by {@link AcceptAdvice} on the entity the statement
 * reads or writes, or on a method or class on the statement's call path.
 *
 * <p>A frame counts through its method and its class. A lambda counts as the method that declares it, an anonymous
 * class or a Kotlin interface's default implementations as the class that declares them, and a repository proxy as
 * the repository interface it implements.</p>
 *
 * <p>The decision is made once per statement shape and piece of advice, from the execution that first asks, and only
 * for advice the log would otherwise report; a statement without advice never gets here. The record of decisions
 * holds at most {@link AdviceLog#MAX_REPORTED} entries and starts over when full.</p>
 *
 * @since 1.15
 */
final class AdviceAcceptance {

    private AdviceAcceptance() {
    }

    private static final StackWalker WALKER = StackWalker.getInstance(RETAIN_CLASS_REFERENCE);

    /** Packages whose classes never carry the annotation, skipped without reading them. */
    private static final List<String> SKIPPED = List.of("java.", "jdk.", "sun.", "kotlin.", "kotlinx.",
            "org.springframework.", "org.apache.", "org.junit.", "io.ktor.", "io.netty.", "org.eclipse.jetty.");

    /** The advice accepted where an annotation is, or {@code null} where none is. */
    private record Accepted(List<String> anchors) {

        boolean accepts(Advice advice) {
            return anchors.isEmpty() || anchors.contains(advice.anchor());
        }

        /** Joins what two overloads of a method accept. */
        Accepted and(Accepted other) {
            if (anchors.isEmpty() || other.anchors.isEmpty()) {
                return new Accepted(List.of());
            }
            var joined = new ArrayList<>(anchors);
            joined.addAll(other.anchors);
            return new Accepted(List.copyOf(joined));
        }

        static @Nullable Accepted of(@Nullable AcceptAdvice annotation) {
            return annotation == null ? null : new Accepted(List.of(annotation.value()));
        }
    }

    /** What a class accepts: on the class itself, and on its methods by name. */
    private record Declared(@Nullable Accepted type, Map<String, Accepted> methods) {}

    private static final ClassValue<Declared> DECLARED = new ClassValue<>() {
        @Override
        protected Declared computeValue(Class<?> type) {
            Map<String, Accepted> methods = new HashMap<>();
            try {
                for (Method method : type.getDeclaredMethods()) {
                    var accepted = Accepted.of(method.getAnnotation(AcceptAdvice.class));
                    if (accepted != null) {
                        methods.merge(method.getName(), accepted, Accepted::and);
                    }
                }
                return new Declared(Accepted.of(type.getAnnotation(AcceptAdvice.class)), Map.copyOf(methods));
            } catch (LinkageError e) {
                // A class whose methods cannot be resolved accepts nothing.
                return new Declared(null, Map.of());
            }
        }
    };

    private record Decision(long shapeId, Advice advice) {}

    private static final Map<Decision, Boolean> DECIDED = new ConcurrentHashMap<>();

    /**
     * Returns whether the entity accepts its own design advice.
     *
     * @param advice the advice.
     * @param entity the entity the advice is about.
     * @return {@code true} if the entity accepts the advice.
     */
    static boolean accepts(Advice advice, Class<?> entity) {
        var accepted = DECLARED.get(entity).type();
        return accepted != null && accepted.accepts(advice);
    }

    /**
     * Returns whether the application accepts advice on the executing statement, by the type it reads or writes or
     * by its call path; decided once per shape.
     *
     * @param advice the advice.
     * @param shapeId the statement's shape, or {@code 0} when it has none, which is decided at every execution.
     * @param dataType the type the statement reads or writes, or {@code null} when none.
     * @return {@code true} if the advice is accepted.
     */
    static boolean accepts(Advice advice, long shapeId, @Nullable Class<?> dataType) {
        if (shapeId == 0) {
            return decide(advice, dataType);
        }
        var decision = new Decision(shapeId, advice);
        var decided = DECIDED.get(decision);
        if (decided != null) {
            return decided;
        }
        if (DECIDED.size() >= AdviceLog.MAX_REPORTED) {
            DECIDED.clear();
        }
        boolean accepted = decide(advice, dataType);
        DECIDED.put(decision, accepted);
        return accepted;
    }

    private static boolean decide(Advice advice, @Nullable Class<?> dataType) {
        if (dataType != null && accepts(advice, dataType)) {
            return true;
        }
        return WALKER.walk(frames -> frames.anyMatch(frame ->
                accepts(advice, frame.getDeclaringClass(), frame.getMethodName())));
    }

    private static boolean accepts(Advice advice, Class<?> type, String method) {
        if (Proxy.isProxyClass(type)) {
            for (var repository : type.getInterfaces()) {
                if (accepts(advice, DECLARED.get(repository), method)) {
                    return true;
                }
            }
            return false;
        }
        var name = type.getName();
        for (var skipped : SKIPPED) {
            if (name.startsWith(skipped)) {
                return false;
            }
        }
        var declared = DECLARED.get(type);
        if (accepts(advice, declared, method)) {
            return true;
        }
        // An anonymous or local class, such as a lambda or a coroutine, and a Kotlin interface's default
        // implementations, count as the method and class that declare them.
        Class<?> enclosing = type.getEnclosingClass();
        if (enclosing == null || !(type.isAnonymousClass() || type.isLocalClass()
                || type.getSimpleName().equals("DefaultImpls"))) {
            return false;
        }
        Method enclosingMethod = type.getEnclosingMethod();
        return accepts(advice, DECLARED.get(enclosing), enclosingMethod != null ? enclosingMethod.getName() : method);
    }

    private static boolean accepts(Advice advice, Declared declared, String method) {
        if (declared.type() != null && declared.type().accepts(advice)) {
            return true;
        }
        var accepted = declared.methods().get(declaringMethod(method));
        return accepted != null && accepted.accepts(advice);
    }

    /**
     * Returns the method a frame's method stands for: the method that declares a lambda, such as {@code orders} for
     * {@code lambda$orders$0} in Java or {@code orders$lambda$0} in Kotlin, and the method itself otherwise.
     */
    static String declaringMethod(String method) {
        if (method.startsWith("lambda$")) {
            int end = method.indexOf('$', 7);
            return end < 0 ? method : method.substring(7, end);
        }
        int end = method.indexOf('$');
        return end <= 0 ? method : method.substring(0, end);
    }

    /** Forgets the decisions made. */
    static void reset() {
        DECIDED.clear();
    }
}
