package st.orm.core.model;

import static st.orm.GenerationStrategy.NONE;

import lombok.Builder;
import st.orm.Entity;
import st.orm.PK;

/**
 * Entity keyed on a currency and the date the rate applies to.
 */
@Builder(toBuilder = true)
public record ExchangeRate(
        @PK(generation = NONE) ExchangeRatePk id,
        double rate
) implements Entity<ExchangeRatePk> {}
