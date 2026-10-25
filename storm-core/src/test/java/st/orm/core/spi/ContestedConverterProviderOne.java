package st.orm.core.spi;

import java.util.Optional;
import st.orm.Converter;
import st.orm.mapping.RecordField;

/**
 * Claims every field of type {@link ContestedValue}, as does {@link ContestedConverterProviderTwo}; neither is
 * ordered against the other.
 */
public class ContestedConverterProviderOne implements ORMConverterProvider {

    static final Converter<String, ContestedValue> CONVERTER = new Converter<>() {
        @Override
        public String toDatabase(ContestedValue value) {
            return value.text();
        }

        @Override
        public ContestedValue fromDatabase(String value) {
            return new ContestedValue(value);
        }
    };

    @Override
    public Optional<ORMConverter> getConverter(RecordField field) {
        if (field.type() != ContestedValue.class) {
            return Optional.empty();
        }
        return Optional.of(new DefaultORMConverterImpl<>(field, CONVERTER, String.class));
    }
}
