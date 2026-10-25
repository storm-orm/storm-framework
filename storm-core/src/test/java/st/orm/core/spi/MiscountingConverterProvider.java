package st.orm.core.spi;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.SqlTemplateException;
import st.orm.mapping.RecordField;

/**
 * Claims every field of type {@link MiscountedValue} with a converter that declares one column but returns two values
 * for it.
 */
public class MiscountingConverterProvider implements ORMConverterProvider {

    @Override
    public Optional<ORMConverter> getConverter(RecordField field) {
        if (field.type() != MiscountedValue.class) {
            return Optional.empty();
        }
        return Optional.of(new MiscountingConverter(field));
    }

    private record MiscountingConverter(RecordField field) implements ORMConverter {

        @Override
        public List<Class<?>> getParameterTypes() {
            return List.of(String.class);
        }

        @Override
        public List<Name> getColumns(NameResolver nameResolver) throws SqlTemplateException {
            return List.of(nameResolver.getName(field));
        }

        @Override
        public List<@Nullable Object> toDatabase(@Nullable Object record) throws SqlTemplateException {
            try {
                return toDatabaseValue(record == null ? null : Providers.getORMReflection().invoke(field, record));
            } catch (Throwable e) {
                throw new SqlTemplateException(e);
            }
        }

        @Override
        public List<@Nullable Object> toDatabaseValue(@Nullable Object value) {
            String text = value instanceof MiscountedValue(var t) ? t : (String) value;
            return List.of(text, text);
        }

        @Override
        public @Nullable Object fromDatabase(@Nullable Object[] values, RefFactory refFactory) {
            return values[0] == null ? null : new MiscountedValue((String) values[0]);
        }
    }
}
