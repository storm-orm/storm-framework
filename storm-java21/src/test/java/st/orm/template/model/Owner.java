package st.orm.template.model;

import org.jspecify.annotations.Nullable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.QueryColumn;
import st.orm.Version;

@QueryColumn(field = "lastNameFolded", type = String.class, function = "lower")
public record Owner(
        @PK Integer id,
        String firstName,
        String lastName,
        Address address,
        @Nullable String telephone,
        @Version int version
) implements Person, Entity<Integer> {}
