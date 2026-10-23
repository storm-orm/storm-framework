package st.orm.core.model;

import static st.orm.GenerationStrategy.NONE;

import java.time.LocalDate;
import lombok.Builder;
import st.orm.Entity;
import st.orm.PK;

/**
 * Entity keyed on a date alone.
 */
@Builder(toBuilder = true)
public record CalendarDay(
        @PK(generation = NONE) LocalDate calendarDate,
        boolean holiday
) implements Entity<LocalDate> {}
