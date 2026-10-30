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
import static java.util.Objects.requireNonNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.StormConfig;
import st.orm.spi.QueryContext;

/**
 * Attributes a statement execution to the application frame that caused it: the innermost frame that is neither
 * framework infrastructure nor declared plumbing, as {@code File.ext:line}.
 *
 * <p>A repository is plumbing of a kind of its own: a query that lives in a repository's default method runs for
 * every caller of that method, so the line inside the repository says which query ran, which the statement already
 * says, and not where. A frame whose class is a repository, or a Kotlin interface's default implementations of
 * one, is kept as the repository frame, and the walk continues to the first application frame beyond it; the site
 * then names both, the caller first and the repository by its method, which says at once which query repeated
 * and where a list variant would go: {@code ReminderService.kt:52 -> VisitRepository.findByPet}. A repository
 * called from plumbing end to end is named alone.</p>
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

    private static final StackWalker CALL_SITE_WALKER = StackWalker.getInstance(RETAIN_CLASS_REFERENCE);

    /** The repository interfaces, by name, since the facades of the other modules are not visible here. */
    private static final Set<String> REPOSITORY_TYPES = Set.of("st.orm.core.repository.Repository",
            "st.orm.repository.Repository");

    /**
     * Whether a class is a repository: it, or the nest host a Kotlin interface's default implementations belong to,
     * implements a repository interface. Decided once per class.
     */
    private static final ClassValue<Boolean> REPOSITORY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                if (implementsRepository(type) || implementsRepository(type.getNestHost())) {
                    return true;
                }
                // A class Kotlin copies into a repository for an inline function, such as a flow operator, is no
                // nest member of it: it belongs to the repository through its top-level class.
                String topLevel = topLevel(type.getName());
                if (topLevel.equals(type.getName())) {
                    return false;
                }
                return implementsRepository(Class.forName(topLevel, false, type.getClassLoader()));
            } catch (LinkageError | SecurityException | ClassNotFoundException e) {
                return false;
            }
        }
    };

    private static boolean implementsRepository(Class<?> type) {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            for (var implemented : candidate.getInterfaces()) {
                if (REPOSITORY_TYPES.contains(implemented.getName()) || implementsRepository(implemented)) {
                    return true;
                }
            }
        }
        return false;
    }

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
        return walked.site(walked.application() != null ? walked.application() : CALL_SITE_HINT.get());
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
        return callSite((String) null);
    }

    /**
     * Returns the call site of the execution the context describes: as {@link #callSite()}, naming the repository
     * method that built the statement where the walk passes none, as for a flow a repository method returned that is
     * collected after the method has returned.
     *
     * @param context the context of the execution, or {@code null}.
     * @return the call site, or {@code null} when none is found.
     */
    @Nullable
    public static String callSite(@Nullable QueryContext context) {
        return callSite(context instanceof QueryImpl.QueryContextImpl impl ? impl.builtBy() : null);
    }

    /** The repository method that built the statement about to run, bound while a flow opens its stream. */
    private static final ThreadLocal<String> BUILT_BY = new ThreadLocal<>();

    /**
     * Returns the repository method building a flow now, for the flow to carry to the execution it runs when it is
     * collected, after the method has returned: the outermost repository frame on the stack, or a built-in
     * repository call. Walks the stack only while call sites are recorded.
     *
     * @return the repository method, or {@code null} when none is on the stack or call sites are not recorded.
     */
    @Nullable
    public static String captureRepository() {
        if (!AdviceLog.recordsRepeats() && !SlowStatementLog.timesStatements()
                && SqlInterceptorManager.localOperators() == null) {
            return null;
        }
        return walkFrames().repository();
    }

    /**
     * Runs work that opens a flow's statement with the repository method that built the flow bound, so the
     * execution's context carries it.
     *
     * @param repository the repository method {@link #captureRepository()} returned, or {@code null}.
     * @param work the work that opens the statement.
     * @return what the work returns.
     * @param <T> the type of what the work returns.
     */
    public static <T> T withRepository(@Nullable String repository, Supplier<T> work) {
        if (repository == null) {
            return work.get();
        }
        String previous = BUILT_BY.get();
        BUILT_BY.set(repository);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                BUILT_BY.remove();
            } else {
                BUILT_BY.set(previous);
            }
        }
    }

    /** Returns the repository method bound for the statement about to run, or {@code null}. */
    static @Nullable String builtBy() {
        return BUILT_BY.get();
    }

    /**
     * Returns the call site, naming the given repository method where the walk passes no repository of its own.
     */
    @Nullable
    private static String callSite(@Nullable String builtBy) {
        var walked = walkFrames().orRepository(builtBy);
        if (walked.application() != null) {
            return walked.site(walked.application());
        }
        String hint = CALL_SITE_HINT.get();
        if (hint != null) {
            return walked.site(hint);
        }
        if (walked.repository() != null) {
            return walked.repository();
        }
        return walked.plumbing();
    }

    /**
     * The frames a walk can surface: the first application frame, the outermost repository frame before it, or the
     * built-in repository call where the walk passed no repository of the application's, and the innermost plumbing
     * frame.
     */
    private record WalkedFrames(@Nullable String application,
                                @Nullable String repository,
                                @Nullable String plumbing) {

        /**
         * Returns the caller's site, followed by the repository method it called where the walk passed one, in the
         * direction of the call: {@code ReminderService.kt:52 -> VisitRepository.findByPet}.
         */
        @Nullable String site(@Nullable String caller) {
            if (caller == null) {
                return null;
            }
            return repository == null ? caller : caller + " -> " + repository;
        }

        /** Returns the walk with the given repository method where the walk passed none of its own. */
        WalkedFrames orRepository(@Nullable String fallback) {
            return repository != null || fallback == null ? this : new WalkedFrames(application, fallback, plumbing);
        }
    }

    /**
     * Walks the stack lazily, as far as the first application frame and no further: every check is made on the frame
     * at hand, so a walk reads only the frames above the caller.
     */
    private static WalkedFrames walkFrames() {
        return CALL_SITE_WALKER.walk(frames -> {
            String repository = null;
            String builtIn = null;
            String proxied = null;
            String plumbing = null;
            for (var iterator = frames.iterator(); iterator.hasNext(); ) {
                var frame = iterator.next();
                // The proxy a repository is reached through dispatches the call; the method it dispatches to is
                // the repository's frame, and the proxy's would shadow it as the outermost one. The repository the
                // proxy stands for names the built-in call it dispatched, PetRepository.findAllByRef.
                if (Proxy.isProxyClass(frame.getDeclaringClass())) {
                    String name = PROXIED_REPOSITORY.get(frame.getDeclaringClass());
                    if (!name.isEmpty()) {
                        proxied = name;
                    }
                    continue;
                }
                if (isInfrastructure(frame.getClassName())) {
                    // The outermost frame of Storm's own repository implementation names the built-in call the
                    // application made, such as findById, where no repository of the application's is passed.
                    if (isBuiltInRepositoryCall(frame)) {
                        builtIn = frame.getMethodName();
                        proxied = null;
                    }
                    continue;
                }
                if (REPOSITORY.get(frame.getDeclaringClass())) {
                    // The outermost repository frame is the method the caller called; one it called in turn is
                    // what the statement already shows.
                    repository = repositoryMethod(frame);
                    continue;
                }
                // A library's inline function, such as a flow operator, has its lambda regenerated under the
                // caller's class with the library's file and line: it reads as the caller's frame but is not.
                if (isInlinedFromElsewhere(frame)) {
                    if (plumbing == null) {
                        plumbing = format(frame);
                    }
                    continue;
                }
                if (isDeclaredPlumbing(frame.getClassName(), frame.getFileName())) {
                    if (plumbing == null) {
                        plumbing = format(frame);
                    }
                    continue;
                }
                return new WalkedFrames(format(frame), repository != null ? repository : builtIn(builtIn, proxied),
                        plumbing);
            }
            return new WalkedFrames(null, repository != null ? repository : builtIn(builtIn, proxied), plumbing);
        });
    }

    /** Names a built-in call by the repository the application called it on where a proxy stood for one. */
    private static @Nullable String builtIn(@Nullable String method, @Nullable String repository) {
        return method == null || repository == null ? method : repository + "." + method;
    }

    /**
     * The simple name of the repository a proxy class stands for, the first interface of it that is a repository,
     * or the empty string for a proxy of anything else.
     */
    private static final ClassValue<String> PROXIED_REPOSITORY = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                for (var implemented : type.getInterfaces()) {
                    if (!isStorm(implemented.getName()) && implementsRepository(implemented)) {
                        return implemented.getSimpleName();
                    }
                }
            } catch (LinkageError | SecurityException e) {
                // A proxy whose interfaces do not resolve stands for no repository the walk can name.
            }
            return "";
        }
    };

    /**
     * Returns whether the frame is a method of Storm's own repository implementation that an application calls, such
     * as {@code findById} or {@code insert}: a method of a {@code ...RepositoryImpl} class in one of Storm's repository
     * packages, and no lambda or constructor of one.
     */
    private static boolean isBuiltInRepositoryCall(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        if (!(className.startsWith("st.orm.core.repository.impl.") || className.startsWith("st.orm.repository.impl."))
                || !className.endsWith("RepositoryImpl")) {
            return false;
        }
        String method = frame.getMethodName();
        return method.indexOf('$') < 0 && !method.equals("<init>");
    }

    /**
     * Names a repository frame by its method, {@code VisitRepository.findByPet}, on the repository its class belongs
     * to, so a Kotlin default method run through the interface's default implementations reads as the method of the
     * interface, as a Java default method does.
     */
    private static String repositoryMethod(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        int inlined = className.indexOf(INLINED);
        if (inlined >= 0) {
            // A copy of an inline function's code names the repository method it was copied into, as in
            // VisitRepository$DefaultImpls$findByPet$$inlined$map$1; the frame's own method is the copy's.
            String copiedInto = className.substring(0, inlined);
            String method = copiedInto.substring(copiedInto.lastIndexOf('$') + 1);
            return simpleName(topLevel(className)) + "." + method;
        }
        return repositoryOf(frame.getDeclaringClass()).getSimpleName() + "." + frame.getMethodName();
    }

    /**
     * Returns the repository a repository frame's class belongs to: the class itself or the nearest class enclosing
     * it that is a repository, as for the default implementations Kotlin compiles an interface's methods into, else
     * its nest host.
     */
    private static Class<?> repositoryOf(Class<?> type) {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getEnclosingClass()) {
            if (implementsRepository(candidate)) {
                return candidate;
            }
        }
        return type.getNestHost();
    }

    /** The part of a class name Kotlin gives a class it copies for an inline function. */
    private static final String INLINED = "$$inlined$";

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * Returns whether the frame is a library's code that Kotlin copied into the application's class for an inline
     * function, such as a flow operator: the copy is a class named {@code ...$$inlined$...} that keeps the inline
     * function's source file, so its frame names the library's file rather than the one named after its top-level
     * class. Decided on the frame alone, so the walk stops at the first application frame.
     */
    private static boolean isInlinedFromElsewhere(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        String file = frame.getFileName();
        if (file == null || !className.contains(INLINED)) {
            return false;
        }
        String own = simpleName(topLevel(className));
        return !file.equals(own + ".kt") && !file.equals(own + ".java");
    }

    /**
     * The lines Kotlin gives the code of an inline function it compiles into a class: past the end of the class's
     * source file, so a frame there names a line the file does not have. The class's source map, its
     * {@code SourceDebugExtension}, says which line of the file each of them was called from, in its
     * {@code KotlinDebug} stratum; a frame in such a line is named by that call site. Read once per class, from the
     * class file, since no reflective API exposes the attribute; a class without a source map, or whose file cannot
     * be read, keeps its lines as the frame reports them.
     */
    static final class InlinedLines {

        private InlinedLines() {
        }

        /** Per class, the inlined lines as triples of first line, last line and call site line. */
        private static final ClassValue<int[]> RANGES = new ClassValue<>() {
            @Override
            protected int[] computeValue(Class<?> type) {
                try {
                    String map = sourceDebugExtension(type);
                    return map == null ? NONE : kotlinDebugRanges(map);
                } catch (Exception | LinkageError e) {
                    return NONE;
                }
            }
        };

        private static final int[] NONE = {};

        /**
         * Returns the line of the class's own source the given line was called from, where it is a line of inlined
         * code, and the line itself otherwise.
         *
         * @param type the class of the frame.
         * @param line the line the frame reports.
         * @return the call site line.
         */
        static int callSiteLine(Class<?> type, int line) {
            int[] ranges = RANGES.get(type);
            for (int i = 0; i < ranges.length; i += 3) {
                if (line >= ranges[i] && line <= ranges[i + 1]) {
                    return ranges[i + 2];
                }
            }
            return line;
        }

        /**
         * Parses the {@code KotlinDebug} stratum of a source map into ranges. A line entry reads
         * {@code InputLine#FileId,RepeatCount:OutputLine,OutputIncrement}, where the defaults are one: each of the
         * repeated input lines maps to as many output lines as the increment says.
         */
        static int[] kotlinDebugRanges(String map) {
            int stratum = map.indexOf("*S KotlinDebug");
            if (stratum < 0) {
                return NONE;
            }
            int lines = map.indexOf("*L", stratum);
            if (lines < 0) {
                return NONE;
            }
            int end = map.indexOf("*E", lines);
            var ranges = new ArrayList<int[]>();
            for (String entry : map.substring(lines + 2, end < 0 ? map.length() : end).split("\\R")) {
                entry = entry.strip();
                int colon = entry.indexOf(':');
                if (entry.isEmpty() || colon < 0) {
                    continue;
                }
                String input = entry.substring(0, colon);
                String output = entry.substring(colon + 1);
                int hash = input.indexOf('#');
                int inputComma = input.indexOf(',');
                int inputLine = Integer.parseInt(input.substring(0, hash >= 0 ? hash
                        : inputComma >= 0 ? inputComma : input.length()));
                int repeat = inputComma >= 0 ? Integer.parseInt(input.substring(inputComma + 1)) : 1;
                int outputComma = output.indexOf(',');
                int outputLine = Integer.parseInt(outputComma >= 0 ? output.substring(0, outputComma) : output);
                int increment = outputComma >= 0 ? Integer.parseInt(output.substring(outputComma + 1)) : 1;
                for (int i = 0; i < repeat; i++) {
                    int first = outputLine + i * increment;
                    ranges.add(new int[] {first, first + Math.max(increment, 1) - 1, inputLine + i});
                }
            }
            int[] flat = new int[ranges.size() * 3];
            for (int i = 0; i < ranges.size(); i++) {
                System.arraycopy(ranges.get(i), 0, flat, i * 3, 3);
            }
            return flat;
        }

        /** Reads the {@code SourceDebugExtension} attribute from the class's file, or returns {@code null}. */
        private static @Nullable String sourceDebugExtension(Class<?> type) throws IOException {
            var loader = type.getClassLoader();
            if (loader == null) {
                return null;
            }
            try (var stream = loader.getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
                if (stream == null) {
                    return null;
                }
                var in = new DataInputStream(new BufferedInputStream(stream));
                if (in.readInt() != 0xCAFEBABE) {
                    return null;
                }
                in.readUnsignedShort();
                in.readUnsignedShort();
                int count = in.readUnsignedShort();
                var utf8 = new String[count];
                for (int index = 1; index < count; index++) {
                    int tag = in.readUnsignedByte();
                    switch (tag) {
                        case 1 -> utf8[index] = in.readUTF();
                        case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                        case 15 -> in.skipNBytes(3);
                        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                        case 5, 6 -> {
                            in.skipNBytes(8);
                            index++;
                        }
                        default -> {
                            return null;
                        }
                    }
                }
                in.skipNBytes(6);
                in.skipNBytes(2L * in.readUnsignedShort());
                for (int member = 0; member < 2; member++) {
                    int members = in.readUnsignedShort();
                    for (int i = 0; i < members; i++) {
                        in.skipNBytes(6);
                        skipAttributes(in);
                    }
                }
                int attributes = in.readUnsignedShort();
                for (int i = 0; i < attributes; i++) {
                    String name = utf8[in.readUnsignedShort()];
                    int length = in.readInt();
                    if ("SourceDebugExtension".equals(name)) {
                        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
                    }
                    in.skipNBytes(length);
                }
                return null;
            }
        }

        private static void skipAttributes(DataInputStream in) throws IOException {
            int attributes = in.readUnsignedShort();
            for (int i = 0; i < attributes; i++) {
                in.skipNBytes(2);
                in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
            }
        }
    }

    /** Returns the top-level class of a class name: the part before its first nested-class separator. */
    private static String topLevel(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static String format(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        if (file != null && !file.equals("<generated>")) {
            return "%s:%d".formatted(file, InlinedLines.callSiteLine(frame.getDeclaringClass(), frame.getLineNumber()));
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

    /**
     * The packages Storm ships under {@code st.orm}, by their first segment. An application's package may start with
     * {@code st.orm.} too, as the example applications' do, so Storm's frames are told apart by these rather than by
     * the prefix; a guard test fails when a module ships a package outside them.
     */
    private static final String[] STORM_PACKAGES = {
            "core.", "gradle.", "jackson.", "kotlin.", "ktor.", "mapping.", "metamodel.", "micrometer.", "repository.",
            "serialization.", "spi.", "spring.", "tck.", "template.", "test."
    };

    private static final String ST_ORM = "st.orm.";

    /**
     * Returns whether the class is Storm's own: a class of the {@code st.orm} package itself, or of one of the
     * packages Storm ships beneath it.
     */
    static boolean isStorm(String className) {
        if (!className.startsWith(ST_ORM)) {
            return false;
        }
        if (className.indexOf('.', ST_ORM.length()) < 0) {
            return true;
        }
        for (var segment : STORM_PACKAGES) {
            if (className.startsWith(segment, ST_ORM.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a frame belongs to the platform, Storm or a framework the application runs on, which a call site
     * never names. A native image's own classes count as the platform, as the JDK's do on the JVM.
     */
    static boolean isInfrastructure(String className) {
        if (isStorm(className)
                || className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("com.oracle.svm.")
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
