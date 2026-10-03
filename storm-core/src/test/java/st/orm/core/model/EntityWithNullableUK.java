package st.orm.core.model;

import lombok.Builder;
import st.orm.Entity;
import st.orm.PK;
import st.orm.UK;

/**
 * Test entity with a @UK inline record whose constituent fields are nullable.
 * Used to verify that the metamodel correctly computes isNullable() for compound keys.
 */
@Builder(toBuilder = true)
@SuppressWarnings("storm.nullable-unique-key")
public record EntityWithNullableUK(
        @PK Integer id,
        @UK NullableCompoundUK uniqueKey
) implements Entity<Integer> {
}
