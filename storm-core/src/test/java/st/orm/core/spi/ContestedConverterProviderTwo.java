package st.orm.core.spi;

import java.util.Optional;
import st.orm.mapping.RecordField;

/**
 * Claims every field of type {@link ContestedValue}, as does {@link ContestedConverterProviderOne}; neither is
 * ordered against the other.
 */
public class ContestedConverterProviderTwo implements ORMConverterProvider {

    @Override
    public Optional<ORMConverter> getConverter(RecordField field) {
        if (field.type() != ContestedValue.class) {
            return Optional.empty();
        }
        return Optional.of(new DefaultORMConverterImpl<>(field, ContestedConverterProviderOne.CONVERTER, String.class));
    }
}
