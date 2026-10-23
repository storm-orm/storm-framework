package st.orm.core.model;

import java.time.LocalDate;

/**
 * Compound key with a date component.
 */
public record ExchangeRatePk(
        String currency,
        LocalDate rateDate
) {}
