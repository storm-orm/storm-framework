package st.orm.core.model;

import lombok.Builder;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;

/**
 * Two foreign keys to one table, both declared as entities. A path through either, such as {@code first.id},
 * must resolve to that key's own column, also where the keyset predicate of a scroll compiles it as a value.
 */
@Builder(toBuilder = true)
public record PetPair(
        @PK Integer id,
        @FK Pet first,
        @FK Pet second
) implements Entity<Integer> {}
