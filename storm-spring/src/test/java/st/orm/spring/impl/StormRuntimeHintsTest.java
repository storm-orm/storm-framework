package st.orm.spring.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.TypeReference;

/**
 * Tests for {@link StormRuntimeHints}.
 *
 * <p>The index files are served from a temporary directory through an isolated class loader, so the hints
 * observed here come exclusively from the fixtures and the referenced type names never have to exist as
 * classes.</p>
 */
public class StormRuntimeHintsTest {

    @TempDir
    Path indexDirectory;

    private ClassLoader classLoaderWithIndex(String indexName, String content) throws Exception {
        Path indexFile = indexDirectory.resolve("META-INF/storm/" + indexName);
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, content);
        return new URLClassLoader(new URL[] { indexDirectory.toUri().toURL() }, null);
    }

    private final List<ILoggingEvent> warnings = new ArrayList<>();

    /**
     * Registers the hints for the given loader. A loader without a Data index makes the registrar warn; the warnings
     * are collected for the test to read rather than logged.
     */
    private RuntimeHints registerHints(ClassLoader loader) {
        var logger = (Logger) LoggerFactory.getLogger("st.orm.spring.aot");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            RuntimeHints hints = new RuntimeHints();
            new StormRuntimeHints().registerHints(hints, loader);
            return hints;
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            warnings.addAll(appender.list);
        }
    }

    @Test
    public void testDataTypesAreRegisteredForIntrospectionAndInvocation() throws Exception {
        ClassLoader loader = classLoaderWithIndex("st.orm.Data.idx", "com.example.City\ncom.example.Pet\n");
        RuntimeHints hints = registerHints(loader);
        TypeHint cityHint = hints.reflection().getTypeHint(TypeReference.of("com.example.City"));
        assertNotNull(cityHint, "Data type from the index must be registered");
        assertTrue(cityHint.getMemberCategories().contains(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
        assertTrue(cityHint.getMemberCategories().contains(MemberCategory.INVOKE_PUBLIC_METHODS));
        assertNotNull(hints.reflection().getTypeHint(TypeReference.of("com.example.Pet")));
    }

    @Test
    public void testGeneratedMetamodelCompanionsAreRegistered() throws Exception {
        ClassLoader loader = classLoaderWithIndex("st.orm.Data.idx", "com.example.City\n");
        RuntimeHints hints = registerHints(loader);
        TypeHint metamodelHint = hints.reflection().getTypeHint(TypeReference.of("com.example.CityMetamodel"));
        assertNotNull(metamodelHint, "Generated metamodel companion must be registered");
        assertTrue(metamodelHint.getMemberCategories().contains(MemberCategory.PUBLIC_FIELDS));
        assertTrue(metamodelHint.getMemberCategories().contains(MemberCategory.INVOKE_PUBLIC_METHODS));
        assertNotNull(hints.reflection().getTypeHint(TypeReference.of("com.example.CityNullableMetamodel")),
                "Nullable metamodel companion must be registered");
    }

    @Test
    public void testKotlinDefaultImplsAreRegisteredPerRepository() throws Exception {
        ClassLoader loader = classLoaderWithIndex("st.orm.repository.Repository.idx", "com.example.CityRepository\n");
        RuntimeHints hints = registerHints(loader);
        TypeHint defaultImplsHint = hints.reflection()
                .getTypeHint(TypeReference.of("com.example.CityRepository$DefaultImpls"));
        assertNotNull(defaultImplsHint, "Kotlin DefaultImpls companion must be registered");
        assertTrue(defaultImplsHint.getMemberCategories().contains(MemberCategory.INVOKE_DECLARED_METHODS));
        assertTrue(defaultImplsHint.getMemberCategories().contains(MemberCategory.INVOKE_PUBLIC_METHODS));
    }

    @Test
    public void testConverterTypesAreRegisteredForConstructionOnly() throws Exception {
        ClassLoader loader = classLoaderWithIndex("st.orm.Converter.idx", "com.example.MoneyConverter\n");
        RuntimeHints hints = registerHints(loader);
        TypeHint converterHint = hints.reflection().getTypeHint(TypeReference.of("com.example.MoneyConverter"));
        assertNotNull(converterHint, "Converter type from the index must be registered");
        assertTrue(converterHint.getMemberCategories().contains(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS));
        assertFalse(converterHint.getMemberCategories().contains(MemberCategory.INVOKE_PUBLIC_METHODS));
    }

    @Test
    public void testRepositoriesAreRegisteredInBothProxyShapes() throws Exception {
        ClassLoader loader = classLoaderWithIndex("st.orm.repository.Repository.idx", "com.example.CityRepository\n");
        RuntimeHints hints = registerHints(loader);
        List<List<String>> proxyShapes = hints.proxies().jdkProxyHints()
                .map(proxyHint -> proxyHint.getProxiedInterfaces().stream()
                        .map(TypeReference::getCanonicalName)
                        .toList())
                .toList();
        assertTrue(proxyShapes.contains(List.of("com.example.CityRepository")),
                "Storm's own repository proxy shape must be registered");
        assertTrue(proxyShapes.contains(List.of(
                        "com.example.CityRepository",
                        "java.io.Serializable",
                        "org.springframework.aop.SpringProxy",
                        "org.springframework.aop.framework.Advised",
                        "org.springframework.core.DecoratingProxy")),
                "The Spring AOP wrapper shape must be registered with the exact interface order");
    }

    @Test
    public void testDuplicateIndexEntriesAreRegisteredOnce() throws Exception {
        ClassLoader loader = classLoaderWithIndex("st.orm.repository.Repository.idx",
                "com.example.CityRepository\ncom.example.CityRepository\n");
        RuntimeHints hints = registerHints(loader);
        assertEquals(2, hints.proxies().jdkProxyHints().count(),
                "One repository must produce exactly two proxy shapes");
    }

    @Test
    public void testMissingIndexRegistersNothing() throws Exception {
        ClassLoader loader = new URLClassLoader(new URL[] { indexDirectory.toUri().toURL() }, null);
        RuntimeHints hints = registerHints(loader);
        assertEquals(0, hints.reflection().typeHints().count());
        assertEquals(0, hints.proxies().jdkProxyHints().count());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().getFormattedMessage().startsWith("No Storm type index found"));
    }

    @Test
    public void testUnreadableIndexFailsTheAotBuild() {
        ClassLoader failingLoader = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("simulated read failure");
            }
        };
        assertThrows(UncheckedIOException.class, () -> registerHints(failingLoader));
    }
}
