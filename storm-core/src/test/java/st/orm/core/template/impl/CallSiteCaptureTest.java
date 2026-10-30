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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clinic.Missing$Nested;
import clinic.ReportRepository$DefaultImpls$lookUpAll$$inlined$map$1;
import clinic.ReportService$run$$inlined$map$1;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A call site names the application frame that caused the execution: a class Kotlin copies for an inline function
 * counts as the library's code where its file is not its class's own, and as the repository's where its top-level
 * class is a repository, named by the method it was copied into. The fixtures take those shapes in Java, which allows
 * {@code $} in a class name, so the walk is tested without a Kotlin compiler.
 */
class CallSiteCaptureTest {

    /** Runs the work on a thread of its own, whose stack holds no application frame beyond the work's. */
    private static <T> T onBareThread(Supplier<T> work) throws InterruptedException {
        var result = new AtomicReference<T>();
        var thread = new Thread(() -> result.set(work.get()));
        thread.start();
        thread.join();
        return result.get();
    }

    @Test
    void aRepositoryCopyIsNamedByTheMethodItWasCopiedIntoThroughTheCaller() {
        var site = ReportRepository$DefaultImpls$lookUpAll$$inlined$map$1.emit(CallSiteCapture::callSite);
        assertTrue(site != null && site.endsWith(" -> ReportRepository.lookUpAll"), site);
    }

    @Test
    void aRepositoryCopyWithNoCallerAfterItIsNamedAlone() throws InterruptedException {
        assertEquals("ReportRepository.lookUpAll", onBareThread(
                () -> ReportRepository$DefaultImpls$lookUpAll$$inlined$map$1.emit(CallSiteCapture::callSite)));
    }

    @Test
    void aLibrarysInlineCopyIsPlumbingNamedOnlyWhereNothingElseIs() throws InterruptedException {
        var site = onBareThread(() -> ReportService$run$$inlined$map$1.emit(CallSiteCapture::callSite));
        assertTrue(site != null && site.startsWith("ReportService$run$$inlined$map$1.java:"), site);
        // On a stack with a caller after it, the copy steps aside for the caller.
        var withCaller = ReportService$run$$inlined$map$1.emit(CallSiteCapture::callSite);
        assertTrue(withCaller != null && !withCaller.startsWith("ReportService$"), withCaller);
    }

    @Test
    void aClassNestedInATopLevelClassThatDoesNotLoadIsTheApplicationsOwn() throws InterruptedException {
        var site = onBareThread(() -> Missing$Nested.call(CallSiteCapture::callSite));
        assertTrue(site != null && site.startsWith("Missing$Nested.java:"), site);
    }

    @Test
    void theCarriedLaunchSiteStandsInForACallerTheStackLost() throws InterruptedException {
        // Nothing to carry: a launch with no application frame on its stack carries none.
        assertNull(onBareThread(CallSiteCapture::captureCallSite));
        var site = onBareThread(() -> {
            CallSiteCapture.callSiteHint().set("Launcher.kt:12");
            try {
                return ReportRepository$DefaultImpls$lookUpAll$$inlined$map$1.emit(CallSiteCapture::callSite);
            } finally {
                CallSiteCapture.callSiteHint().remove();
            }
        });
        assertEquals("Launcher.kt:12 -> ReportRepository.lookUpAll", site);
    }

    @Test
    void anApplicationPackageUnderStOrmIsTheApplicationsOwn() {
        assertFalse(CallSiteCapture.isInfrastructure("st.orm.demo.imdb.MovieCollectionService"));
        assertFalse(CallSiteCapture.isInfrastructure("st.orm.demo.imdb.RatingRepository$DefaultImpls"));
        assertTrue(CallSiteCapture.isInfrastructure("st.orm.Ref"));
        assertTrue(CallSiteCapture.isInfrastructure("st.orm.core.template.impl.QueryImpl"));
        assertTrue(CallSiteCapture.isInfrastructure("st.orm.repository.impl.EntityRepositoryImpl"));
        assertTrue(CallSiteCapture.isInfrastructure("st.orm.spi.postgresql.PostgreSQLSqlDialect"));
    }

    @Test
    void aNativeImagesOwnClassesArePlatform() {
        assertTrue(CallSiteCapture.isInfrastructure("com.oracle.svm.core.thread.PlatformThreads"));
        assertTrue(CallSiteCapture.isInfrastructure(
                "com.oracle.svm.core.methodhandles.Target_java_lang_invoke_MethodHandle"));
    }

    /**
     * Every package a Storm module ships under {@code st.orm} counts as Storm's own, so a module that adds a package
     * outside the known ones fails here rather than naming its frames as the application's.
     */
    @Test
    void everyPackageStormShipsIsStormsOwn() throws IOException {
        List<String> missing = new ArrayList<>();
        int packages = 0;
        try (Stream<Path> modules = Files.list(Path.of(".."))) {
            for (var module : modules.filter(Files::isDirectory).toList()) {
                var main = module.resolve("src/main");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(main)) {
                    for (var source : sources.filter(path -> {
                        var name = path.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".kt");
                    }).toList()) {
                        var relative = source.toString().replace('\\', '/');
                        int root = relative.indexOf("/st/orm/");
                        if (root < 0) {
                            continue;
                        }
                        var className = relative.substring(root + 1, relative.lastIndexOf('.')).replace('/', '.');
                        packages++;
                        if (!CallSiteCapture.isStorm(className)) {
                            missing.add(className);
                        }
                    }
                }
            }
        }
        assertTrue(packages > 0, "no Storm sources found from " + Path.of("..").toAbsolutePath());
        assertEquals(List.of(), missing);
    }
}
