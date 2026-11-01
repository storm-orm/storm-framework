package st.orm.core.graal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import st.orm.EntityCallbacks;
import st.orm.core.model.Address;
import st.orm.core.model.Owner;
import st.orm.core.model.OwnerPetCount;
import st.orm.core.model.polymorphic.Checkup;
import st.orm.core.model.polymorphic.OutcomeConverter;
import st.orm.core.model.polymorphic.Surgery;
import st.orm.core.model.polymorphic.Treatment;
import st.orm.core.repository.Repository;
import st.orm.core.spi.TypeDiscovery;
import st.orm.core.template.impl.NativeProxies;

/**
 * The feature registers what the type index of this module's test classes names: the data types with their members
 * and components, the sealed hierarchies, the query result types, the converters and the entity callbacks.
 */
class StormFeatureTest {

    /**
     * Records the registrations. Classes are found on the test classpath; the names in {@link #standIns} resolve to
     * stand-in classes, as the Kotlin classes and the generated companions they stand in for are not on it.
     */
    static final class RecordingRegistrar implements StormFeature.Registrar {
        final Set<Class<?>> types = new HashSet<>();
        final Set<Executable> executables = new HashSet<>();
        final Set<Field> fields = new HashSet<>();
        final Set<Class<?>> sealedTypes = new HashSet<>();
        /** The repository interfaces registered as single-interface proxies. */
        final Set<Class<?>> proxies = new HashSet<>();
        /** Every registered proxy shape, as its ordered interfaces. */
        final Set<List<Class<?>>> proxyShapes = new HashSet<>();
        final Map<String, Class<?>> standIns = new HashMap<>();
        /** The class the build writes for each repository interface, none where the build writes none. */
        final Map<Class<?>, Class<?>> repositoryClasses = new HashMap<>();

        @Override
        public @Nullable Class<?> findClassByName(String name) {
            Class<?> standIn = standIns.get(name);
            if (standIn != null) {
                return standIn;
            }
            try {
                return Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        @Override
        public void register(Class<?> type) {
            types.add(type);
        }

        @Override
        public void register(Executable... registered) {
            executables.addAll(List.of(registered));
        }

        @Override
        public void register(Field... registered) {
            fields.addAll(List.of(registered));
        }

        @Override
        public void registerAllPermittedSubclasses(Class<?> type) {
            sealedTypes.add(type);
        }

        @Override
        public void registerProxy(Class<?>... interfaces) {
            proxyShapes.add(List.of(interfaces));
            if (interfaces.length == 1 && Repository.class.isAssignableFrom(interfaces[0])) {
                proxies.add(interfaces[0]);
            }
        }

        @Override
        public @Nullable Class<?> defineRepositoryClass(Class<?> type) {
            return repositoryClasses.get(type);
        }
    }

    /** Stands in for the Kotlin standard library's empty list. */
    static final class EmptyList {
        static final EmptyList INSTANCE = new EmptyList();

        private EmptyList() {}

        public int size() {
            return 0;
        }
    }

    /** Stands in for the metamodel the Kotlin processor generates for the owner. */
    static final class OwnerMetamodel {
        public static final String FIRST_NAME = "firstName";

        public static OwnerMetamodel of() {
            return new OwnerMetamodel();
        }
    }

    /** Stands in for the kotlinx.serialization companion of the owner. */
    static final class OwnerCompanion {
        public Object serializer() {
            return this;
        }
    }

    @Test
    void registersTheDataTypesWithTheirMembersAndComponents() {
        var registrar = new RecordingRegistrar();
        var summary = new StormFeature().register(registrar);
        assertTrue(registrar.types.contains(Owner.class));
        assertTrue(registrar.executables.containsAll(List.of(Owner.class.getDeclaredConstructors())));
        assertTrue(registrar.executables.containsAll(List.of(Owner.class.getMethods())));
        assertTrue(registrar.fields.containsAll(List.of(Owner.class.getDeclaredFields())));
        assertTrue(registrar.types.contains(Address.class), "the inline component of the owner");
        assertTrue(registrar.types.containsAll(TypeDiscovery.getDataTypes()));
        assertTrue(summary.dataTypes() >= TypeDiscovery.getDataTypes().size(), summary.toString());
    }

    /** Stands in for the class the Kotlin compiler generates for the default methods of a repository interface. */
    static final class AuditedCityRepositoryDefaultImpls {
        public static int findByName(AuditedCityRepository repository, String name) {
            return 0;
        }
    }

    @Test
    void registersTheRepositoriesForProxyCreationWithTheirKotlinDefaultMethods() {
        var registrar = new RecordingRegistrar();
        var summary = new StormFeature().register(registrar);
        assertEquals(Set.of(AuditedCityRepository.class), registrar.proxies);
        assertEquals(1, summary.repositoryTypes());
        registrar = new RecordingRegistrar();
        registrar.standIns.put(AuditedCityRepository.class.getName() + "$DefaultImpls", AuditedCityRepositoryDefaultImpls.class);
        new StormFeature().register(registrar);
        assertTrue(registrar.types.contains(AuditedCityRepositoryDefaultImpls.class));
        assertTrue(registrar.executables.containsAll(List.of(AuditedCityRepositoryDefaultImpls.class.getDeclaredMethods())));
    }

    /** Stands in for the class the build writes for a repository interface. */
    public static final class AuditedCityRepositoryClass {
        public AuditedCityRepositoryClass(Object repository, Object entity, Object projection) {}
    }

    @Test
    void aRepositoryWhoseClassTheBuildWritesRegistersTheClassInsteadOfAProxy() {
        var registrar = new RecordingRegistrar();
        registrar.repositoryClasses.put(AuditedCityRepository.class, AuditedCityRepositoryClass.class);
        registrar.standIns.put(AuditedCityRepository.class.getName() + "$DefaultImpls", AuditedCityRepositoryDefaultImpls.class);
        var summary = new StormFeature().register(registrar);
        assertEquals(Set.of(), registrar.proxies);
        assertTrue(registrar.types.contains(AuditedCityRepositoryClass.class));
        assertTrue(registrar.executables.containsAll(List.of(AuditedCityRepositoryClass.class.getConstructors())));
        // The class calls the Kotlin bodies directly, so they need no reflection.
        assertFalse(registrar.types.contains(AuditedCityRepositoryDefaultImpls.class));
        assertEquals(1, summary.repositoryTypes());
        assertEquals(1, summary.generatedRepositoryTypes());
    }

    @Test
    void registersTheProxiesStormCreatesForItself() {
        var registrar = new RecordingRegistrar();
        new StormFeature().register(registrar);
        for (var shape : NativeProxies.shapes()) {
            assertTrue(registrar.proxyShapes.contains(List.of(shape)), List.of(shape).toString());
        }
        assertTrue(registrar.proxyShapes.contains(List.of(PreparedStatement.class)));
    }

    @Test
    void registersASealedHierarchyFromEitherSide() {
        var registrar = new RecordingRegistrar();
        new StormFeature().register(registrar);
        assertTrue(registrar.sealedTypes.contains(Treatment.class));
        assertTrue(registrar.types.contains(Treatment.class));
        assertTrue(registrar.types.contains(Surgery.class));
        assertTrue(registrar.types.contains(Checkup.class));
    }

    @Test
    void registersTheQueryResultTypesLikeDataTypes() {
        var registrar = new RecordingRegistrar();
        var summary = new StormFeature().register(registrar);
        assertTrue(registrar.types.contains(OwnerPetCount.class));
        assertTrue(registrar.executables.containsAll(List.of(OwnerPetCount.class.getDeclaredConstructors())));
        assertTrue(registrar.types.contains(AuditedCityPetCount.class));
        assertEquals(TypeDiscovery.getResultTypes().size(), summary.resultTypes(), summary.toString());
    }

    @Test
    void registersTheConvertersForConstruction() {
        var registrar = new RecordingRegistrar();
        var summary = new StormFeature().register(registrar);
        assertTrue(registrar.types.contains(OutcomeConverter.class));
        assertTrue(registrar.executables.containsAll(List.of(OutcomeConverter.class.getDeclaredConstructors())));
        assertEquals(TypeDiscovery.getConverterTypes().size(), summary.converterTypes());
    }

    @Test
    void registersTheDeclaredEntityCallbacksForConstruction() {
        var registrar = new RecordingRegistrar();
        var summary = new StormFeature().register(registrar);
        var callback = AuditedCity.Audit.class;
        assertTrue(registrar.types.contains(AuditedCity.class));
        assertTrue(registrar.types.contains(callback));
        assertTrue(registrar.executables.containsAll(List.of(callback.getDeclaredConstructors())));
        int declared = TypeDiscovery.getDataTypes().stream()
                .map(type -> type.getAnnotation(EntityCallbacks.class))
                .filter(Objects::nonNull)
                .mapToInt(annotation -> annotation.value().length)
                .sum();
        assertEquals(declared, summary.callbackTypes());
    }

    @Test
    void registersTheGeneratedCompanionsOfADataTypeWhenPresent() {
        var registrar = new RecordingRegistrar();
        registrar.standIns.put(Owner.class.getName() + "Metamodel", OwnerMetamodel.class);
        registrar.standIns.put(Owner.class.getName() + "$Companion", OwnerCompanion.class);
        new StormFeature().register(registrar);
        assertTrue(registrar.types.contains(OwnerMetamodel.class));
        assertTrue(registrar.fields.containsAll(List.of(OwnerMetamodel.class.getFields())));
        assertTrue(registrar.executables.containsAll(List.of(OwnerMetamodel.class.getMethods())));
        assertTrue(registrar.types.contains(OwnerCompanion.class));
        assertTrue(registrar.executables.containsAll(List.of(OwnerCompanion.class.getDeclaredConstructors())));
        assertTrue(registrar.executables.containsAll(List.of(OwnerCompanion.class.getMethods())));
    }

    @Test
    void registersTheKotlinCollectionClassesThatArePresent() {
        var registrar = new RecordingRegistrar();
        registrar.standIns.put("kotlin.collections.EmptyList", EmptyList.class);
        new StormFeature().register(registrar);
        assertTrue(registrar.types.contains(EmptyList.class));
        assertTrue(registrar.executables.containsAll(List.of(EmptyList.class.getDeclaredConstructors())));
        assertTrue(registrar.executables.containsAll(List.of(EmptyList.class.getDeclaredMethods())));
        assertTrue(registrar.fields.containsAll(List.of(EmptyList.class.getDeclaredFields())));
        assertFalse(registrar.types.stream().anyMatch(type -> type.getName().startsWith("kotlin.")),
                "the Kotlin standard library is not on this module's test classpath");
    }
}
