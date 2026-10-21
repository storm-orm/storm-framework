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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.StormConfig;

/**
 * Attributes a statement execution to the application frame that caused it: the innermost frame that is neither
 * framework infrastructure nor declared plumbing, as {@code File.ext:line}.
 *
 * <p>Which frames count as plumbing is a property of the deployment, configured like one: the
 * {@code storm.sql_log.call_site_skip} system property on a plain JVM, or the corresponding keys of the Spring
 * and Ktor integrations.</p>
 *
 * @since 1.14
 */
public final class CallSiteCapture {

    private CallSiteCapture() {
    }

    private static final StackWalker CALL_SITE_WALKER = StackWalker.getInstance();

    /**
     * The launch-site fallback for executions whose stack no longer contains the caller, such as work resumed
     * on a coroutine dispatcher. Bound by integrations that carry a scope onto another thread, alongside the
     * scope itself.
     */
    private static final ThreadLocal<String> CALL_SITE_HINT = new ThreadLocal<>();

    /**
     * Returns the thread local carrying the launch-site fallback for executions whose stack no longer contains
     * the caller.
     *
     * <p>Intended for integrations that carry a scope onto another thread, such as coroutine context elements,
     * which bind it alongside the scope. Application code should not modify it.</p>
     *
     * @return the thread local holding the current launch site.
     */
    public static ThreadLocal<String> callSiteHint() {
        return CALL_SITE_HINT;
    }

    /**
     * Returns the application frame launching work, for an integration to carry as the call-site fallback of a
     * scope that records call sites.
     *
     * <p>At the moment work is launched, the caller is still on the stack; on the thread the work resumes on,
     * it no longer is. Carrying what this returns, bound through {@link #callSiteHint()}, is what lets a
     * statement whose stack is plumbing end to end name the frame that launched the work. When the launch
     * itself has no application frame on its stack, the fallback already carried is returned, so chained
     * launches preserve the original caller.</p>
     *
     * <p>Costs a stack walk; callers gate on whether an observing scope records call sites.</p>
     *
     * @return the launching application frame, or {@code null} when there is none to carry.
     */
    @Nullable
    public static String captureCallSite() {
        var walked = walkFrames();
        return walked.application() != null ? walked.application() : CALL_SITE_HINT.get();
    }

    /**
     * Returns the application frame that caused the execution: the innermost frame that is neither framework
     * infrastructure nor declared plumbing, as {@code File.ext:line}.
     *
     * <p>When every application frame on the stack is declared plumbing, the carried launch site is returned
     * when one is bound, since it names the caller the stack lost; the innermost plumbing frame otherwise, as a
     * plumbing site still says more than none.</p>
     */
    @Nullable
    public static String callSite() {
        var walked = walkFrames();
        if (walked.application() != null) {
            return walked.application();
        }
        String hint = CALL_SITE_HINT.get();
        if (hint != null) {
            return hint;
        }
        return walked.plumbing();
    }

    /** The two frames a walk can surface: the first application frame, and the innermost plumbing frame. */
    private record WalkedFrames(@Nullable String application, @Nullable String plumbing) {
    }

    private static WalkedFrames walkFrames() {
        return CALL_SITE_WALKER.walk(frames -> {
            String plumbing = null;
            for (var iterator = frames.iterator(); iterator.hasNext(); ) {
                var frame = iterator.next();
                if (isInfrastructure(frame.getClassName())) {
                    continue;
                }
                if (isDeclaredPlumbing(frame.getClassName(), frame.getFileName())) {
                    if (plumbing == null) {
                        plumbing = format(frame);
                    }
                    continue;
                }
                return new WalkedFrames(format(frame), plumbing);
            }
            return new WalkedFrames(null, plumbing);
        });
    }

    private static String format(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        if (file != null && !file.equals("<generated>")) {
            return "%s:%d".formatted(file, frame.getLineNumber());
        }
        // A class generated at runtime, such as the proxy Spring puts around a transactional service, has no source
        // file; it names the class it stands for.
        String className = frame.getClassName();
        int generated = className.indexOf("$$");
        return "%s.%s".formatted(generated < 0 ? className : className.substring(0, generated),
                frame.getMethodName());
    }

    /**
     * Returns whether the frame belongs to a package or source file the application declared as plumbing.
     *
     * <p>Entries naming a source file match the frame's file, which is what covers inline functions: inlining
     * regenerates a lambda under the caller's class, where a package prefix cannot see it, while the frame keeps
     * the declaring file's name.</p>
     */
    private static boolean isDeclaredPlumbing(String className, @Nullable String fileName) {
        for (var entry : ignoredCallSitePrefixes) {
            if (entry.endsWith(".kt") || entry.endsWith(".java")) {
                if (entry.equals(fileName)) {
                    return true;
                }
            } else if (className.startsWith(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Package prefixes the application declared as its own database plumbing, so call sites name the code that
     * asked for the work rather than the layer that carried it. Copy-on-write: registered once at startup, read
     * on every walk.
     */
    private static volatile String[] ignoredCallSitePrefixes = {};

    /**
     * Declares packages whose frames are skipped when attributing an execution to a call site.
     *
     * <p>A database layer of the application's own, such as a wrapper that fans a query out over several
     * templates, sits between the caller and Storm on every statement; its frames identify the plumbing rather
     * than the code that asked for the work. Declaring its packages here makes call sites name the caller
     * beyond it.</p>
     *
     * <p>An entry is a package prefix matched against the fully qualified class name, or, when it ends in
     * {@code .kt} or {@code .java}, a source file name matched against the frame's file. The file form covers
     * inline functions, whose lambdas are regenerated under the caller's class while keeping the declaring
     * file's name. When every application frame on a stack is declared plumbing, the innermost plumbing frame is
     * reported rather than none. Intended to be called once at startup.</p>
     *
     * @param packagePrefixes the package prefixes or source file names to skip, such as {@code "com.acme.db"} or
     *                        {@code "DbExtensions.kt"}.
     */
    public static void ignoreCallSites(String... packagePrefixes) {
        var merged = new ArrayList<>(List.of(ignoredCallSitePrefixes));
        for (var prefix : packagePrefixes) {
            merged.add(requireNonNull(prefix, "packagePrefix"));
        }
        ignoredCallSitePrefixes = merged.toArray(String[]::new);
    }

    private static boolean isInfrastructure(String className) {
        if (className.startsWith("st.orm.")
                || className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("kotlin.")
                || className.startsWith("kotlinx.")
                || className.startsWith("org.springframework.")
                || className.startsWith("org.apache.")
                || className.startsWith("io.ktor.")
                || className.startsWith("io.netty.")
                || className.startsWith("org.eclipse.jetty.")) {
            return true;
        }
        return false;
    }

    /**
     * Applies the configured skip list ({@link StormConfig#defaults()}, which reads system properties). Which
     * frames are plumbing is a property of the deployment, so it is configured like one; the Spring and Ktor
     * integrations apply their own configuration through {@link #ignoreCallSites}.
     */
    // Placed after every field it touches, since static initialization runs in textual order.
    static {
        String skip = StormConfig.defaults().getProperty(StormConfig.SQL_LOG_CALL_SITE_SKIP);
        if (skip != null) {
            ignoreCallSites(Arrays.stream(skip.split(","))
                    .map(String::trim)
                    .filter(entry -> !entry.isEmpty())
                    .toArray(String[]::new));
        }
    }
}
