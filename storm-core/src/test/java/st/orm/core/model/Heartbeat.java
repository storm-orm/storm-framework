package st.orm.core.model;

import static st.orm.GenerationStrategy.NONE;

import java.time.Instant;
import lombok.Builder;
import st.orm.Entity;
import st.orm.PK;

/**
 * Entity keyed on the instant it was recorded. The column holds microseconds, so a key must be truncated to that
 * precision before the entity is constructed.
 */
@Builder(toBuilder = true)
public record Heartbeat(
        @PK(generation = NONE) Instant recordedAt,
        String status
) implements Entity<Instant> {}
