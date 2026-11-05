package st.orm.core.template;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Holds the pieces of a split template string and the values that will be inserted between them.
 *
 * @param fragments the constant string parts, in order
 * @param values    the runtime values to interpolate
 */
public record TemplateString(List<String> fragments, List<@Nullable Object> values) {
    public TemplateString {
        if (fragments.size() != values.size() + 1) {
            throw new IllegalArgumentException("Fragments must have exactly one more element than values.");
        }
    }
    public TemplateString(String[] fragments, @Nullable Object[] values) {
        this(List.of(fragments), asList(values));
    }

    public static TemplateString EMPTY = new TemplateString(List.of(""), List.of());

    public static TemplateString of(List<String> fragments, List<@Nullable Object> values) {
        return new TemplateString(fragments, values);
    }

    public static TemplateString of(String str) {
        return new TemplateString(List.of(str), List.of());
    }

    public static TemplateString raw(TemplateBuilder builder) {
        return TemplateBuilder.create(builder);
    }

    public static TemplateString raw(String template, Object... values) {
        return TemplateBuilder.create(template, values);
    }

    public static TemplateString wrap(@Nullable Object value) {
        return new TemplateString(List.of("", ""), singletonList(value));
    }

    public static TemplateString combine(List<TemplateString> sts) {
        return combine(sts.toArray(new TemplateString[0]));
    }

    public static TemplateString combine(TemplateString... sts) {
        Objects.requireNonNull(sts, "sts must not be null");
        if (sts.length == 0) {
            return EMPTY;
        } else if (sts.length == 1) {
            return Objects.requireNonNull(sts[0], "string templates should not be null");
        }
        int size = 0;
        for (TemplateString st : sts) {
            Objects.requireNonNull(st, "string templates should not be null");
            size += st.values().size();
        }
        String[] combinedFragments = new String[size + 1];
        @Nullable Object[] combinedValues = new Object[size];
        combinedFragments[0] = "";
        int fragmentIndex = 1;
        int valueIndex = 0;
        for (TemplateString st : sts) {
            Iterator<String> iterator = st.fragments().iterator();
            combinedFragments[fragmentIndex - 1] += iterator.next();
            while (iterator.hasNext()) {
                combinedFragments[fragmentIndex++] = iterator.next();
            }
            for (@Nullable Object value : st.values()) {
                combinedValues[valueIndex++] = value;
            }
        }
        return new TemplateString(combinedFragments, combinedValues);
    }
}
