package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Convert;
import st.orm.Entity;
import st.orm.Json;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.spi.ContestedConverterProviderOne;
import st.orm.core.spi.ContestedConverterProviderTwo;
import st.orm.core.spi.ContestedValue;
import st.orm.core.spi.DefaultORMConverterImpl;
import st.orm.core.spi.Providers;
import st.orm.mapping.RecordField;

/**
 * The converter of a field is the first claim in provider order; two unordered claims, and a JSON field nobody
 * claims, are refused with the candidates named.
 */
class ConverterClaimTest {

    record Contested(@PK Integer id, ContestedValue value) implements Entity<Integer> {}

    record Favourites(List<String> clinics) {}

    record Preferences(@PK Integer id, @Json Favourites favourites) implements Entity<Integer> {}

    record Resolved(@PK Integer id,
                    @Convert(converter = ContestedTextConverter.class) ContestedValue value) implements Entity<Integer> {}

    public static class ContestedTextConverter implements st.orm.Converter<String, ContestedValue> {
        @Override
        public String toDatabase(ContestedValue value) {
            return value.text();
        }

        @Override
        public ContestedValue fromDatabase(String value) {
            return new ContestedValue(value);
        }
    }

    private static RecordField field(Class<?> type, String name) {
        return RecordReflection.getRecordFields(type).stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void twoUnorderedClaimsAreRefusedNamingTheProviders() {
        var exception = assertThrows(PersistenceException.class,
                () -> Providers.getORMConverter(field(Contested.class, "value")));
        assertTrue(exception.getMessage().contains("'Contested.value'"), exception.getMessage());
        assertTrue(exception.getMessage().contains(ContestedConverterProviderOne.class.getName()), exception.getMessage());
        assertTrue(exception.getMessage().contains(ContestedConverterProviderTwo.class.getName()), exception.getMessage());
    }

    @Test
    void theConverterAFieldSelectsWinsOverAClaimOfAnotherProvider() throws Exception {
        var converter = Providers.getORMConverter(field(Resolved.class, "value")).orElseThrow();
        assertEquals(DefaultORMConverterImpl.class, converter.getClass());
        assertEquals(List.of("fluffy"), converter.toDatabaseValue(new ContestedValue("fluffy")));
    }

    @Test
    void aJsonFieldNobodyClaimsIsRefusedNamingTheJsonModules() {
        var exception = assertThrows(PersistenceException.class,
                () -> Providers.getORMConverter(field(Preferences.class, "favourites")));
        assertTrue(exception.getMessage().contains("'Preferences.favourites'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("storm-jackson2"), exception.getMessage());
        assertTrue(exception.getMessage().contains("storm-kotlinx-serialization"), exception.getMessage());
    }
}
